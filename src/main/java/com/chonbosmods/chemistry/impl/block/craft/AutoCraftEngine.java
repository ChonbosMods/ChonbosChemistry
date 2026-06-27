package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.EnergyBuffer;
import com.chonbosmods.chemistry.impl.block.MachineVisualState;
import com.chonbosmods.chemistry.impl.block.PortProjection;
import com.chonbosmods.chemistry.impl.block.SmelterEnergy;
import com.chonbosmods.chemistry.impl.block.bench.VanillaCraftBridge;
import com.chonbosmods.chemistry.impl.block.net.NetworkService;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * The reusable autonomous-crafting LOOP, lifted out of {@code ForgeTickSystem} so future crafting machines
 * (Cooker, Outfitter) reuse it and the two improvement axes (per-recipe craft time, recipe scripting) live
 * in ONE place. The owning tick system resolves the live drive context (ref / world / block coords / block
 * type / energy) and hands it to {@link #drive}, which runs ONE energy-gated step of the discrete pull loop
 * (idle &rarr; pull &rarr; craft &rarr; complete &rarr; repeat):
 *
 * <ol>
 *   <li>when IDLE and powered, project the machine's item-in port to the world pipe cell, snapshot the ITEM
 *       network reachable there ({@link NetworkRecipeSource#snapshot}), and compute the craftable recipe ids:
 *       allow-set-permitted AND fully sourceable from that network ({@link NetworkRecipeSource#available}) AND
 *       output has room ({@link VanillaCraftBridge#canProduce} backpressure). While CRAFTING, no sourcing
 *       happens; the loop just advances the active craft;</li>
 *   <li>run the pure {@link PullCraftStep#decide decision}. On START the chosen recipe's ingredients are
 *       atomically pulled from the network ({@link NetworkRecipeSource#tryPull}, at most once per tick) into the
 *       held container; on ADVANCE progress accrues; on COMPLETE the held ingredients are consumed and the
 *       outputs produced through {@link VanillaCraftBridge}. Energy drains ONLY on a tick that drove real
 *       work;</li>
 *   <li>drive the block's {@code "Processing"} / {@code "default"} visual state directly on the placed block
 *       (an autonomous crafter holds no vanilla bench, so we swap via the {@link BlockAccessor}).</li>
 * </ol>
 *
 * <h2>Defensive contract</h2>
 * This runs every server tick on hot ECS data and MUST NEVER throw on a missing component, chunk, world, or
 * bridge call (the caller's catch-Throwable is the backstop). It mutates the {@link AutoCraftNode} + the
 * placed block in place.
 */
public final class AutoCraftEngine {

    private AutoCraftEngine() {
    }

    /**
     * The metadata key on a recipe card carrying its {@link RecipeScript}: the structured blueprint of which
     * recipes the machine may craft, how many of each, and in what order. A card with no such key (or an empty
     * script) imposes NO filter (a blank card crafts everything; see {@link #cardScript}). Read/written via
     * {@code ItemStack.getFromMetadataOrNull} / {@code ItemStack.withMetadata}.
     *
     * <p>Package-private so the codec round-trip is directly testable (stamp + read a card in-test) without a
     * separate test seam.
     */
    static final KeyedCodec<RecipeScript> CC_RECIPE_SCRIPT =
        new KeyedCodec<>("CC_RecipeScript", RecipeScript.CODEC);

    /**
     * One craft step for {@code node}: source &rarr; decide &rarr; produce &rarr; drain energy &rarr; swap
     * the block visual. Mutates the node + the block. NEVER throws (guard internally; the caller's
     * catch-Throwable is the backstop).
     */
    public static void drive(@Nonnull AutoCraftNode node, @Nonnull Context ctx, @Nonnull Spec spec) {
        int x = ctx.x();
        int y = ctx.y();
        int z = ctx.z();
        World world = ctx.world();
        Store<ChunkStore> store = ctx.store();
        BlockType blockType = ctx.blockType();
        float dt = ctx.dt();
        EnergyBuffer energy = ctx.energy();

        // 2. The shared (cached) bench recipe pool: stable id order + id -> recipe map.
        RecipePool pool = spec.recipePool();

        // 2b. Card-change -> progress reset (every tick): resolve the inserted card's script + its signature
        // and clear the machine's per-card progress whenever the signature changes. This covers eject (-> ""),
        // reprogram (different entries/order/counts), and reload-mismatch. The Task-5 GUI ALSO clears on eject;
        // this is the engine backstop. `script` (null when no/blank card) drives the script-aware candidate set
        // (step 4b) and the scripted progress increment (step 6, COMPLETE).
        RecipeScript script = cardScript(node.card());
        String sig = scriptSignature(script);
        if (!sig.equals(node.lastCardSig())) {
            node.clearScriptProgress();
            node.setLastCardSig(sig);
        }

        // 3. State: are we mid-craft? and the energy gate (simulated craft seconds affordable this tick,
        // 0 when disabled or the buffer is empty).
        boolean crafting = node.currentRecipeId() != null;
        double affordable =
            node.isEnabled() ? SmelterEnergy.affordableDt(energy.getStored(), spec.energyDraw(), dt) : 0.0;
        boolean powered = affordable > 0;

        // 3b. Post-craft pause: when idle with a pending delay, burn one tick of it and DON'T source this
        // tick. This PACES back-to-back crafts (a brief beat before the next pull); the pull amount is already
        // exact. The visual stays ACTIVE across this beat (see step 8: the active state is sticky), so the
        // machine does not flicker idle between recipes.
        boolean delaying = false;
        if (!crafting && node.craftDelay() > 0) {
            node.setCraftDelay(node.craftDelay() - 1);
            delaying = true;
        }

        // 4. Build the craftable set + the network snapshot ONLY when IDLE, powered AND not in the post-craft
        // pause: never reserve ingredients we cannot craft, and no point sourcing while a craft is mid-flight
        // (PullCraftStep ignores `craftable` while crafting). `snapshot` is reused for the chosen pull below.
        Set<String> craftable = java.util.Collections.emptySet();
        Set<String> topTier = java.util.Collections.emptySet();
        NetworkRecipeSource.Snapshot snapshot = null;
        if (!crafting && powered && !delaying) {
            int[] pipeCell = inputPipeCell(world, x, y, z, node, ctx);
            if (pipeCell != null) {
                snapshot = NetworkRecipeSource.snapshot(
                    world, store, ctx.pipeType(), ctx.networkService(), pipeCell[0], pipeCell[1], pipeCell[2]);
            }
            if (snapshot != null) {
                Set<String> allow = spec.allowSet(node);
                java.util.HashSet<String> set = new java.util.HashSet<>();
                for (String id : pool.stableOrder()) {
                    if (!CraftSelection.allowed(id, allow)) {
                        continue;
                    }
                    CraftingRecipe r = pool.map().get(id);
                    if (r == null) {
                        continue;
                    }
                    try {
                        // Fully sourceable from the input network AND the output has room (backpressure).
                        if (!NetworkRecipeSource.available(snapshot, r)) {
                            continue;
                        }
                        if (!VanillaCraftBridge.canProduce(node.output(), VanillaCraftBridge.outputs(r))) {
                            continue;
                        }
                    } catch (RuntimeException ex) {
                        // A single malformed recipe (e.g. a 0-quantity ingredient) must not poison the
                        // whole scan: skip it rather than aborting every recipe's evaluation this tick.
                        continue;
                    }
                    set.add(id);
                }
                craftable = set;

                // PRIORITY: prefer the most-ingredient (more "advanced") recipes. Rank each craftable id by
                // its distinct-ingredient count and keep only the top tier; PullCraftStep/selectNext then
                // even-rotate WITHIN that tier (it receives the pre-filtered set, unchanged). When the complex
                // ingredients run out, those recipes drop from `craftable` and a simpler tier becomes the top.
                // Shared by ALL crafting machines (Forge + Cooker) through this one engine.
                if (!craftable.isEmpty()) {
                    java.util.Map<String, Integer> countMap = new java.util.HashMap<>();
                    for (String id : craftable) {
                        countMap.put(id, VanillaCraftBridge.ingredientCount(pool.map().get(id)));
                    }
                    // Script-aware candidate set: no card -> unchanged topByPriority; ordered -> the forced
                    // single pick; unordered -> priority WITHIN the script's progress-retired active set.
                    topTier = resolveCandidates(script, node.scriptProgress(), craftable, countMap);
                }
            }
        }

        // 5. The pure per-tick decision (idle -> START a pick, crafting -> ADVANCE / COMPLETE).
        // IMPROVEMENT AXIS: the craft duration is resolved per-recipe via spec.craftDuration; today every
        // recipe returns the same constant, so behavior is identical (this is the seam where per-recipe
        // timing will plug in). Resolve the recipe the SAME way the apply below does.
        CraftingRecipe durationRecipe = crafting
            ? pool.map().get(node.currentRecipeId())
            : null;
        float duration = spec.craftDuration(durationRecipe);
        // Pass the PRE-FILTERED top tier (most-ingredient recipes) so selectNext even-rotates within it.
        // PullCraftStep/selectNext are UNCHANGED: they just receive the already-narrowed set.
        PullCraftStep.Decision d = PullCraftStep.decide(
            crafting, node.currentRecipeId(), node.progress(), duration, powered, (float) affordable,
            pool.stableOrder(), topTier, node.lastSelectedId());

        // 6. Apply the decision. `activeCraft` = we drove real work this tick (drain energy + show Processing).
        boolean activeCraft = false;
        switch (d.action()) {
            case IDLE -> {
                node.setProgress(0f);
                node.setCurrentRecipeId(null);
                clearHeld(node); // invariant: idle => held empty (defends against any path leaving stale held)
                node.setLastSelectedId(d.newCursor());
            }
            case START -> {
                CraftingRecipe r = pool.map().get(d.pick());
                // tryPull MUTATES + spends the snapshot: call it AT MOST ONCE per tick, only for the pick,
                // and only after every `available()` probe above is done.
                List<ItemStack> pulled =
                    (r != null && snapshot != null) ? NetworkRecipeSource.tryPull(snapshot, r) : null;
                if (pulled != null) {
                    loadHeld(node, pulled);
                    node.setCurrentRecipeId(d.pick());
                    node.setProgress(0f);
                    activeCraft = true; // committed a craft this tick
                } else {
                    // lost the pull (race / vanished): stay idle this tick, retry next.
                    node.setProgress(0f);
                    node.setCurrentRecipeId(null);
                }
                node.setLastSelectedId(d.newCursor()); // unchanged on START
            }
            case ADVANCE -> {
                node.setProgress(d.newProgress());
                node.setLastSelectedId(d.newCursor());
                activeCraft = powered;
            }
            case COMPLETE -> {
                CraftingRecipe r = pool.map().get(d.pick());
                List<ItemStack> outs = (r != null) ? VanillaCraftBridge.outputs(r) : java.util.List.of();
                // Completion backpressure (defensive: START reserved output room and only we add / the net
                // removes, so this should pass; guard anyway so a full output never destroys the held
                // ingredients).
                if (r != null && VanillaCraftBridge.canProduce(node.output(), outs)) {
                    VanillaCraftBridge.addOutputs(node.output(), outs);
                    clearHeld(node); // the held ingredients are consumed by the craft
                    // Scripted progress: count this completed craft EXACTLY ONCE, and only when a script is
                    // active for this card (no-card path leaves progress untouched). d.pick() is the id that
                    // just completed (the same id used to resolve `r`/`outs` above).
                    if (script != null) {
                        node.incrementScriptProgress(d.pick());
                    }
                    node.setCurrentRecipeId(null);
                    node.setProgress(0f);
                    node.setLastSelectedId(d.newCursor()); // cursor advances to the crafted id
                    node.setCraftDelay(spec.postCraftDelayTicks()); // brief idle beat before the next pull
                    activeCraft = powered;
                } else {
                    // STALL: output full -> keep the craft + held intact, sit at the completion threshold
                    // and retry next tick when the output drains. Do NOT advance the cursor or drain energy.
                    node.setProgress(duration);
                    // currentRecipeId + held untouched; activeCraft stays false (blocked, not working).
                }
            }
        }

        // 7. Drain energy only for a tick that drove real work.
        if (activeCraft) {
            long drained = SmelterEnergy.drainFor(affordable, spec.energyDraw());
            if (drained > 0) {
                energy.extractEnergyInternal(drained, false);
            }
        }

        // 8. Block visual state (vanilla-parity): "Processing" while the machine is working, else "default".
        // The active state is STICKY: it stays on while we worked this tick (activeCraft), while a craft is in
        // flight (crafting: e.g. stalled on a full output), OR during the post-craft pacing delay (delaying),
        // and reverts to idle ONLY once genuinely idle (proven false). This stops the block flickering idle in
        // the gap between back-to-back crafts. Still ANDed with the power gate, so cutting power forces idle.
        // The machine has NO held bench, so we swap the placed block's interaction state directly via the
        // BlockAccessor (mirroring NetworkTickSystem's cable swaps), reading the current state first so we
        // issue a packet only on a real transition (no per-tick animation restart).
        boolean processing = MachineVisualState.autoCraftActive(activeCraft, crafting, delaying);
        String desired = MachineVisualState.desired(powered, processing);
        applyVisualState(world, x, y, z, blockType, desired);
    }

    /**
     * Clear {@code node}'s held container, then place each {@code pulled} stack into consecutive slots
     * (the active craft's pulled ingredients). {@code filter=false}: the test-/asset-safe raw write the
     * codebase already uses (mirrors {@code NetworkRecipeSource}'s aggregate writes). Clamps defensively to the
     * held capacity (27 slots; a recipe never exceeds it).
     */
    private static void loadHeld(@Nonnull AutoCraftNode node, @Nonnull List<ItemStack> pulled) {
        SimpleItemContainer held = node.held();
        clearHeld(node);
        short cap = held.getCapacity();
        int n = Math.min(pulled.size(), cap);
        for (int i = 0; i < n; i++) {
            ItemStack s = pulled.get(i);
            if (s == null) {
                continue;
            }
            held.setItemStackForSlot((short) i, s, false);
        }
    }

    /** Empty every slot of {@code node}'s held container ({@code filter=false} raw write). */
    private static void clearHeld(@Nonnull AutoCraftNode node) {
        SimpleItemContainer held = node.held();
        short cap = held.getCapacity();
        for (short slot = 0; slot < cap; slot++) {
            held.setItemStackForSlot(slot, ItemStack.EMPTY, false);
        }
    }

    /**
     * The WORLD coords of the cell the input pipe occupies (the pipe adjacent to the machine's ITEM INPUT
     * face), or {@code null} if it cannot be resolved (no input port, or the chunk isn't loaded).
     *
     * <p>The Forge's item-in port lives at model cell offset {@code (-1,0,0)}, face 1 (West); see
     * {@code ForgePorts}. We resolve the placed block's yaw {@link Rotation} the same way
     * {@code WorldMachineLookup.rotationAt} does ({@code accessor.getRotationIndex}), then project the port
     * through {@link PortProjection#pipeCellForPort}: rotate the cell offset + face to world and step one
     * cell along the rotated face to land on the pipe cell. For identity rotation that is
     * {@code anchor + (-1,0,0)} (port cell) {@code + (-1,0,0)} (West face) = {@code anchor + (-2,0,0)}.
     */
    @Nullable
    private static int[] inputPipeCell(@Nonnull World world, int x, int y, int z,
            @Nonnull AutoCraftNode node, @Nonnull Context ctx) {
        BlockAccessor accessor = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
        if (accessor == null) {
            return null; // chunk not loaded: cannot resolve rotation
        }
        int idx = accessor.getRotationIndex(x, y, z);
        Rotation[] vals = Rotation.values();
        Rotation rotation = vals[((idx % vals.length) + vals.length) % vals.length];
        Vector3i pipe = PortProjection.pipeCellForPort(
            node.ports(), rotation, x, y, z, PortChannel.ITEM, PortDirection.INPUT);
        if (pipe == null) {
            return null; // no ITEM INPUT port configured
        }
        return new int[] {pipe.x(), pipe.y(), pipe.z()};
    }

    /**
     * Swap the placed machine block's interaction state to {@code desired}, reading its current state first so
     * a packet is issued only on a real transition. Fully guarded: a missing chunk/block is skipped and never
     * throws on the world thread (the outer drive's catch is the backstop, but we avoid even the lookups on
     * the steady-state path).
     */
    private static void applyVisualState(@Nonnull World world, int x, int y, int z,
            @Nonnull BlockType blockType, @Nonnull String desired) {
        BlockAccessor accessor = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
        if (accessor == null) {
            return;
        }
        BlockType placed = accessor.getBlockType(x, y, z);
        if (placed == null || placed == BlockType.EMPTY) {
            return; // not our block anymore (race with a break); skip
        }
        String current = placed.getStateForBlock(placed);
        String normalized = (current == null || current.isEmpty()) ? MachineVisualState.DEFAULT : current;
        if (desired.equals(normalized)) {
            return; // already in the desired state: no packet
        }
        // force=false: the engine also re-checks equality before swapping (belt and braces).
        accessor.setBlockInteractionState(x, y, z, placed, desired, false);
    }

    /**
     * The {@link RecipeScript} carried in {@code card}'s metadata, or {@code null} when there is no usable
     * script: a null/empty card, a card with no {@link #CC_RECIPE_SCRIPT} metadata, or a script with no
     * entries ({@link RecipeScript#isEmpty()}). A blank/unprogrammed card therefore reads as {@code null}.
     *
     * <p>This is the single place richer recipe-script logic plugs in; {@link #cardAllowSet} derives the flat
     * allow-set from it.
     */
    @Nullable
    public static RecipeScript cardScript(@Nullable ItemStack card) {
        if (card == null || card.isEmpty()) {
            return null;
        }
        RecipeScript script = card.getFromMetadataOrNull(CC_RECIPE_SCRIPT);
        if (script == null || script.isEmpty()) {
            return null;
        }
        return script;
    }

    /**
     * The DEFAULT recipe-scripting: the card's allow-set, the recipe ids it permits the machine to craft, or
     * {@code null} for no filter. Derived from {@link #cardScript}: a null card, a card with no script, or an
     * empty script all yield {@code null} (no filter / allow all); otherwise the script's distinct recipe ids
     * are returned ({@link RecipeScript#recipeIds()}).
     *
     * <p>The {@code null = allow-all} contract is preserved exactly: non-gated machines with a blank card
     * still craft anything, and the Sculptor's {@code scriptGateAllowSet} still denies-all on a blank card via
     * its own card-presence check. A {@link Spec}'s {@code allowSet()} should delegate here unless it wants
     * custom scripting.
     */
    @Nullable
    public static Set<String> cardAllowSet(@Nullable ItemStack card) {
        RecipeScript script = cardScript(card);
        return (script == null) ? null : script.recipeIds();
    }

    /**
     * The script-aware candidate set fed to {@link PullCraftStep#decide}, replacing the bare
     * {@link CraftSelection#topByPriority} call in the IDLE branch.
     *
     * <ul>
     *   <li><b>No card</b> ({@code script == null}): UNCHANGED no-card path: returns
     *       {@code topByPriority(craftable, countMap)} exactly as before scripting existed.</li>
     *   <li><b>Ordered script</b>: forces the single {@link ScriptSelection#orderedPick ordered pick} (list
     *       order is the priority): a singleton, or empty when nothing in the script is active.</li>
     *   <li><b>Unordered script</b>: most-ingredient priority WITHIN the script's
     *       {@link ScriptSelection#activeSet active set} (finite entries retired by progress, intersected with
     *       what is craftable now).</li>
     * </ul>
     *
     * <p>When the result is empty (completed / nothing active / ordered-with-nothing-craftable),
     * {@link PullCraftStep#decide} naturally yields IDLE: no special-casing is needed, and a finished script's
     * active set is empty ({@link ScriptSelection#isComplete} is honored implicitly).
     *
     * <p>Pure (no world/ECS): unit-tested directly.
     */
    static Set<String> resolveCandidates(@Nullable RecipeScript script, Map<String, Integer> progress,
            Set<String> craftable, Map<String, Integer> countMap) {
        if (script == null) {
            return CraftSelection.topByPriority(craftable, countMap); // UNCHANGED no-card path
        }
        Set<String> active = ScriptSelection.activeSet(script, progress, craftable);
        if (script.ordered()) {
            String pick = ScriptSelection.orderedPick(script, active);
            return pick == null ? java.util.Set.of() : java.util.Set.of(pick); // ordered: force the one pick
        }
        return CraftSelection.topByPriority(active, countMap); // unordered: priority WITHIN the active set
    }

    /**
     * A deterministic signature of {@code script} used by {@link #drive} to detect a card change (eject,
     * reprogram, reload-mismatch) and reset the machine's progress. Equal scripts produce equal signatures;
     * any change (incl. card removal, which maps to {@code null}) produces a different signature.
     *
     * <p>Form: {@code ""} for a null script (no/blank card), else {@code ordered + "|" + each entry
     * "id:count"} joined by {@code ";"}, in the script's entry order. Pure: unit-tested directly.
     */
    static String scriptSignature(@Nullable RecipeScript script) {
        if (script == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(script.ordered());
        for (RecipeScript.Entry e : script.entries()) {
            sb.append('|');
            if (e != null) {
                sb.append(e.recipeId()).append(':').append(e.count());
            }
        }
        return sb.toString();
    }

    /**
     * Live per-tick context the machine's tick resolves and passes in. The tick owns context resolution
     * (ref / world / coords / block type / energy); the engine owns the craft loop over it.
     */
    public record Context(World world, Store<ChunkStore> store, int x, int y, int z, BlockType blockType,
            float dt, EnergyBuffer energy,
            NetworkService networkService, ComponentType<ChunkStore, PipeNode> pipeType) {
    }

    /** Per-machine knobs + the two improvement hooks (per-recipe craft time, recipe scripting). */
    public interface Spec {

        /** The machine-specific bench pool (typically cached). */
        RecipePool recipePool();

        /** Per-second energy draw while crafting. */
        long energyDraw();

        /** Ticks the machine idles after a completed craft before sourcing the next recipe. */
        int postCraftDelayTicks();

        /**
         * IMPROVEMENT AXIS: per-recipe craft time. The seconds one craft of {@code r} takes. Today every
         * recipe returns the same constant; this is the seam where per-recipe timing will plug in. May be
         * called with a null recipe (e.g. when idle); implementations should still return the default.
         */
        float craftDuration(@Nullable CraftingRecipe r);

        /**
         * IMPROVEMENT AXIS: recipe scripting. The allow-set restricting which recipe ids the machine may
         * craft, or {@code null} for no filter. Defaults delegate to {@link #cardAllowSet(ItemStack)}.
         */
        Set<String> allowSet(AutoCraftNode node);
    }
}
