package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.EnergyBuffer;
import com.chonbosmods.chemistry.impl.block.MachineVisualState;
import com.chonbosmods.chemistry.impl.block.PortProjection;
import com.chonbosmods.chemistry.impl.block.SmelterEnergy;
import com.chonbosmods.chemistry.impl.block.bench.VanillaCraftBridge;
import com.chonbosmods.chemistry.impl.block.net.NetworkService;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Per-tick driver for the autonomous Forge block (the demand-driven "pull-crafter"). For every live
 * {@link ForgeCraftState} on the {@link ChunkStore}, it runs one energy-gated step of the discrete pull
 * loop (idle &rarr; pull &rarr; craft &rarr; complete &rarr; repeat):
 *
 * <ol>
 *   <li>resolve the live drive context (ref / world / block coords / block type) exactly like
 *       {@code MachineTickSystem.driveBench}: every lookup guarded, the node skipped on the first
 *       missing piece;</li>
 *   <li>when IDLE and powered, project the Forge's item-in port to the world pipe cell, snapshot the ITEM
 *       network reachable there ({@link ForgeSourcePull#snapshot}), and compute the craftable recipe ids:
 *       card-allowed AND fully sourceable from that network ({@link ForgeSourcePull#available}) AND output
 *       has room ({@link VanillaCraftBridge#canProduce} backpressure). While CRAFTING, no sourcing happens;
 *       the loop just advances the active craft;</li>
 *   <li>run the pure {@link PullCraftStep#decide decision}. On START the chosen recipe's ingredients are
 *       atomically pulled from the network ({@link ForgeSourcePull#tryPull}, at most once per tick) into the
 *       held container; on ADVANCE progress accrues; on COMPLETE the held ingredients are consumed and the
 *       outputs produced through {@link VanillaCraftBridge}. Energy drains ONLY on a tick that drove real
 *       work;</li>
 *   <li>drive the block's {@code "Processing"} / {@code "default"} visual state directly on the placed block
 *       (the Forge holds no vanilla bench, so we swap via the {@link BlockAccessor}, not a bench).</li>
 * </ol>
 *
 * <h2>Defensive contract</h2>
 * Mirrors {@code MachineTickSystem}: this runs every server tick on hot ECS data and MUST NEVER throw on a
 * missing component, chunk, world, or bridge call. The whole per-node drive is wrapped in a catch-Throwable
 * that logs each distinct error once and skips the node for the tick.
 *
 * <p>Unlike the Smelter, the Forge does not delegate to a vanilla autonomous bench (vanilla crafting has no
 * such block); it drives the crafting helpers itself through {@link VanillaCraftBridge} and sources its
 * ingredients from the item-pipe network via {@link ForgeSourcePull}.
 */
public final class ForgeTickSystem extends EntityTickingSystem<ChunkStore> {

    /** [TUNE] Per-second energy draw of a running Forge. Placeholder, matches {@code SMELTER_DRAW}. */
    private static final long FORGE_DRAW = 200L;

    /**
     * [TUNE] Seconds one craft takes. Placeholder until per-recipe craft times land.
     *
     * <p>Public single source of truth: {@code ForgePanelPage} reads it to render the progress bar
     * fraction ({@code progress / FORGE_DURATION}), so the GUI and the tick never disagree on a craft's
     * length. When per-recipe craft times land, the panel must switch to the same per-recipe lookup.
     */
    public static final float FORGE_DURATION = 4.0f;

    /**
     * The metadata key on a recipe card carrying its allow-set (the recipe ids the Forge may craft). A card
     * with no such key imposes NO filter (a blank card crafts everything; the card-PROGRAMMING UX is
     * deferred). Read as a {@code String[]} via {@code ItemStack.getFromMetadataOrNull}.
     */
    private static final KeyedCodec<String[]> CARD_ALLOW =
        new KeyedCodec<>("CC_ForgeAllow", Codec.STRING_ARRAY);

    private final ComponentType<ChunkStore, ForgeCraftState> forgeType;
    private final ComponentType<ChunkStore, PipeNode> pipeType;
    private final NetworkService networkService;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType;
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType;

    public ForgeTickSystem(
            @Nonnull ComponentType<ChunkStore, ForgeCraftState> forgeType,
            @Nonnull ComponentType<ChunkStore, PipeNode> pipeType,
            @Nonnull NetworkService networkService) {
        this.forgeType = forgeType;
        this.pipeType = pipeType;
        this.networkService = networkService;
        this.blockInfoType = BlockModule.BlockStateInfo.getComponentType();
        this.blockChunkType = BlockChunk.getComponentType();
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // Energy + containers are mutated in place on the live component; keep it single-threaded.
        return false;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return forgeType;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        ForgeCraftState node = archetypeChunk.getComponent(index, forgeType);
        if (node == null) {
            return;
        }
        // DEFENSIVE: a craft drive error must NEVER kill the WorldThread. Catch every throwable, log each
        // distinct one once, and skip this Forge's drive for the tick (same contract as MachineTickSystem).
        try {
            driveForge(node, dt, index, archetypeChunk, store);
        } catch (Throwable t) {
            String msg = String.valueOf(t);
            if (FORGE_DRIVE_SEEN.add(msg)) {
                FORGE_DRIVE_LOG.atWarning().log("CC forge craft drive failed (skipped, world tick protected): " + msg);
            }
        }
    }

    /** Guards the per-tick craft drive: a failure logs once (per distinct error) and never crashes the tick. */
    private static final HytaleLogger FORGE_DRIVE_LOG = HytaleLogger.forEnclosingClass();
    private static final java.util.Set<String> FORGE_DRIVE_SEEN =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Drive one round-robin craft step for this Forge node. A no-op whenever any piece of the live context
     * (energy buffer, ref, world, block coords, block type) cannot be resolved. NEVER throws.
     */
    private void driveForge(
            @Nonnull ForgeCraftState node,
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store) {
        // The energy buffer gates the drive: no buffer means no power gate -> nothing to spend, skip.
        EnergyHandler energyHandler = node.energy();
        if (!(energyHandler instanceof EnergyBuffer energy)) {
            return;
        }

        // Resolve the live drive context off the same block-entity ref + sibling components. Mirrors
        // MachineTickSystem.driveBench; guard every step.
        Ref<ChunkStore> blockRef = archetypeChunk.getReferenceTo(index);
        if (blockRef == null || !blockRef.isValid()) {
            return;
        }
        BlockModule.BlockStateInfo stateInfo = archetypeChunk.getComponent(index, blockInfoType);
        if (stateInfo == null) {
            return;
        }
        Ref<ChunkStore> chunkRef = stateInfo.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) {
            return;
        }
        BlockChunk blockChunk = chunkRef.getStore().getComponent(chunkRef, blockChunkType);
        if (blockChunk == null) {
            return;
        }
        int blockIndex = stateInfo.getIndex();
        int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
        int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
        int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);
        int x = (blockChunk.getX() << 5) | localX;
        int y = localY;
        int z = (blockChunk.getZ() << 5) | localZ;

        ChunkStore external = store.getExternalData();
        if (external == null) {
            return;
        }
        World world = external.getWorld();
        if (world == null) {
            return;
        }
        BlockType blockType = resolveBlockType(blockChunk, localX, localY, localZ);
        if (blockType == null) {
            return;
        }

        // 2. The shared (cached) bench recipe pool: stable id order + id -> recipe map.
        RecipePool pool = recipePool();

        // 3. State: are we mid-craft? and the energy gate (simulated craft seconds affordable this tick,
        // 0 when disabled or the buffer is empty).
        boolean crafting = node.currentRecipeId() != null;
        double affordable =
            node.isEnabled() ? SmelterEnergy.affordableDt(energy.getStored(), FORGE_DRAW, dt) : 0.0;
        boolean powered = affordable > 0;

        // 4. Build the craftable set + the network snapshot ONLY when IDLE AND powered: never reserve
        // ingredients we cannot craft, and no point sourcing while a craft is mid-flight (PullCraftStep
        // ignores `craftable` while crafting). `snapshot` is reused for the chosen pick's atomic pull below.
        Set<String> craftable = java.util.Collections.emptySet();
        ForgeSourcePull.Snapshot snapshot = null;
        if (!crafting && powered) {
            int[] pipeCell = inputPipeCell(world, x, y, z, node);
            if (pipeCell != null) {
                snapshot = ForgeSourcePull.snapshot(
                    world, store, pipeType, networkService, pipeCell[0], pipeCell[1], pipeCell[2]);
            }
            if (snapshot != null) {
                Set<String> allow = cardAllowSet(node.card());
                java.util.HashSet<String> set = new java.util.HashSet<>();
                for (String id : pool.stableOrder) {
                    if (!CraftSelection.allowed(id, allow)) {
                        continue;
                    }
                    CraftingRecipe r = pool.map.get(id);
                    if (r == null) {
                        continue;
                    }
                    try {
                        // Fully sourceable from the input network AND the output has room (backpressure).
                        if (!ForgeSourcePull.available(snapshot, r)) {
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
            }
        }

        // 5. The pure per-tick decision (idle -> START a pick, crafting -> ADVANCE / COMPLETE).
        PullCraftStep.Decision d = PullCraftStep.decide(
            crafting, node.currentRecipeId(), node.progress(), FORGE_DURATION, powered, (float) affordable,
            pool.stableOrder, craftable, node.lastSelectedId());

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
                CraftingRecipe r = pool.map.get(d.pick());
                // tryPull MUTATES + spends the snapshot: call it AT MOST ONCE per tick, only for the pick,
                // and only after every `available()` probe above is done.
                List<ItemStack> pulled =
                    (r != null && snapshot != null) ? ForgeSourcePull.tryPull(snapshot, r) : null;
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
                CraftingRecipe r = pool.map.get(d.pick());
                List<ItemStack> outs = (r != null) ? VanillaCraftBridge.outputs(r) : java.util.List.of();
                // Completion backpressure (defensive: START reserved output room and only we add / the net
                // removes, so this should pass; guard anyway so a full output never destroys the held
                // ingredients).
                if (r != null && VanillaCraftBridge.canProduce(node.output(), outs)) {
                    VanillaCraftBridge.addOutputs(node.output(), outs);
                    clearHeld(node); // the held ingredients are consumed by the craft
                    node.setCurrentRecipeId(null);
                    node.setProgress(0f);
                    node.setLastSelectedId(d.newCursor()); // cursor advances to the crafted id
                    activeCraft = powered;
                } else {
                    // STALL: output full -> keep the craft + held intact, sit at the completion threshold
                    // and retry next tick when the output drains. Do NOT advance the cursor or drain energy.
                    node.setProgress(FORGE_DURATION);
                    // currentRecipeId + held untouched; activeCraft stays false (blocked, not working).
                }
            }
        }

        // 7. Drain energy only for a tick that drove real work.
        if (activeCraft) {
            long drained = SmelterEnergy.drainFor(affordable, FORGE_DRAW);
            if (drained > 0) {
                energy.extractEnergyInternal(drained, false);
            }
        }

        // 8. Block visual state (vanilla-parity): "Processing" only while we actually crafted this tick,
        // else "default". The Forge has NO held bench, so we swap the placed block's interaction state
        // directly via the BlockAccessor (mirroring NetworkTickSystem's cable-visual swaps), reading the
        // current state first so we issue a packet only on a real transition (no per-tick animation restart).
        boolean processing = activeCraft;
        String desired = MachineVisualState.desired(powered, processing);
        applyVisualState(world, x, y, z, blockType, desired);
    }

    /**
     * Clear {@code node}'s held container, then place each {@code pulled} stack into consecutive slots
     * (the active craft's pulled ingredients). {@code filter=false}: the test-/asset-safe raw write the
     * codebase already uses (mirrors {@code ForgeSourcePull}'s aggregate writes). Clamps defensively to the
     * held capacity (27 slots; a recipe never exceeds it).
     */
    private static void loadHeld(@Nonnull ForgeCraftState node, @Nonnull List<ItemStack> pulled) {
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
    private static void clearHeld(@Nonnull ForgeCraftState node) {
        SimpleItemContainer held = node.held();
        short cap = held.getCapacity();
        for (short slot = 0; slot < cap; slot++) {
            held.setItemStackForSlot(slot, ItemStack.EMPTY, false);
        }
    }

    /**
     * The WORLD coords of the cell the input pipe occupies (the pipe adjacent to the Forge's ITEM INPUT
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
    private int[] inputPipeCell(@Nonnull World world, int x, int y, int z, @Nonnull ForgeCraftState node) {
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
     * Swap the placed Forge block's interaction state to {@code desired}, reading its current state first so
     * a packet is issued only on a real transition. Fully guarded: a missing chunk/block is skipped and never
     * throws on the world thread (the outer drive's catch is the backstop, but we avoid even the lookups on
     * the steady-state path).
     */
    private void applyVisualState(@Nonnull World world, int x, int y, int z,
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
     * The card's allow-set: the recipe ids it permits the Forge to craft, or {@code null} for no filter.
     * A null card (no card loaded) returns null; a card with no {@link #CARD_ALLOW} metadata also returns
     * null (a blank card imposes no filter — the card-programming UX is deferred). Otherwise the stored
     * {@code String[]} is returned as a {@link Set}.
     */
    @Nullable
    private static Set<String> cardAllowSet(@Nullable ItemStack card) {
        if (card == null) {
            return null;
        }
        String[] allow = card.getFromMetadataOrNull(CARD_ALLOW);
        if (allow == null || allow.length == 0) {
            return null;
        }
        return Set.of(allow);
    }

    /**
     * The {@link BlockType} at the Forge, resolved like {@code MachineTickSystem.resolveBlockType} (section
     * block id at the local coords -> asset map). Null if the section or id can't be resolved.
     */
    @Nullable
    private BlockType resolveBlockType(@Nonnull BlockChunk blockChunk, int localX, int localY, int localZ) {
        BlockSection section = blockChunk.getSectionAtBlockY(localY);
        if (section == null) {
            return null;
        }
        int blockId = section.get(localX, localY, localZ);
        return BlockType.getAssetMap().getAsset(blockId);
    }

    // --- shared recipe pool (load-time-fixed; built once, lazily) ---

    /** The Forge's candidate recipe pool: a deterministic id order + an id -> recipe lookup. */
    private record RecipePool(List<String> stableOrder, Map<String, CraftingRecipe> map) {
    }

    private static volatile RecipePool POOL;

    /**
     * The shared recipe pool, built once on first tick and cached (the recipe registry is load-time-fixed).
     * Unions the crafting + armory benches and dedups by id into a deterministic (natural String) order.
     */
    private static RecipePool recipePool() {
        RecipePool p = POOL;
        if (p != null) {
            return p;
        }
        synchronized (ForgeTickSystem.class) {
            if (POOL != null) {
                return POOL;
            }
            // TreeMap: dedup by id with a deterministic natural String order. Last writer wins on a dup id
            // (recipes with the same id across benches are the same asset).
            TreeMap<String, CraftingRecipe> byId = new TreeMap<>();
            addBench(byId, BenchType.Crafting, "Weapon_Bench");
            addBench(byId, BenchType.Crafting, "Armor_Bench");
            addBench(byId, BenchType.Crafting, "ArmorBench");
            addBench(byId, BenchType.DiagramCrafting, "Armory");
            // LinkedHashMap preserves the TreeMap's sorted iteration order for the id list.
            Map<String, CraftingRecipe> map = new LinkedHashMap<>(byId);
            List<String> order = List.copyOf(byId.keySet());
            POOL = new RecipePool(order, map);
            // Diagnostic (logged once): if this is 0, the bench ids did not resolve to recipes and the Forge
            // can never craft. Expected ~100 across Weapon_Bench / Armor_Bench / ArmorBench / Armory.
            FORGE_DRIVE_LOG.atInfo().log("Forge recipe pool built: " + order.size() + " recipes.");
            return POOL;
        }
    }

    /** Add every recipe of one bench into {@code byId}, keyed by recipe id. Guards against null/empty. */
    private static void addBench(@Nonnull Map<String, CraftingRecipe> byId, @Nonnull BenchType type,
            @Nonnull String benchId) {
        List<CraftingRecipe> recipes = VanillaCraftBridge.benchRecipes(type, benchId);
        if (recipes == null) {
            return;
        }
        for (CraftingRecipe r : recipes) {
            if (r == null) {
                continue;
            }
            String id = VanillaCraftBridge.recipeId(r);
            if (id != null) {
                byId.put(id, r);
            }
        }
    }
}
