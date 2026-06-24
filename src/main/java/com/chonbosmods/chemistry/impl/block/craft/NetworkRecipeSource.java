package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.bench.VanillaCraftBridge;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.NetworkService;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.WorldPipeGridView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Destination;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Source;
import com.chonbosmods.chemistry.impl.block.net.item.WorldContainerLookup;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Engine glue that lets the autonomous Forge source a recipe's ingredients from the item-pipe network on
 * its INPUT face (the demand-driven "pull-crafter" redesign, Task 3). The {@link ForgeTickSystem} (a
 * later task) calls this each tick to (a) {@linkplain #snapshot snapshot} what is reachable on that
 * network, (b) ask {@linkplain #available whether a recipe is fully sourceable}, and (c)
 * {@linkplain #tryPull atomically extract} a recipe's ingredients from the real chests, returning the
 * extracted {@link ItemStack}s to hold.
 *
 * <h2>Where the network comes from (mirrors {@code NetworkTickSystem})</h2>
 * {@link #snapshot} resolves the ITEM network EXACTLY as the transport tick does: it builds a
 * {@link WorldPipeGridView} + {@link WorldContainerLookup}, asks {@link NetworkManager#getOrBuildNetwork}
 * for the network at the given input-cell world coords, guards {@code net.channel() == }{@link
 * PortChannel#ITEM ITEM}, then enumerates reachable containers via {@link ItemEndpoints#collect}. The
 * Forge pulls from ANY inventory reachable on that network (LOCKED design decision), so it takes the
 * UNION of the collected {@link Source}s and {@link Destination}s, deduped by container key.
 *
 * <h2>The input cell is supplied by the caller</h2>
 * The Forge's item-in port lives on the model cell offset {@code (-1,0,0)}, face 1 (see {@code
 * ForgePorts}); projecting that to world space needs the placed block's yaw rotation. The tick already
 * resolves rotation (the same way {@code WorldMachineLookup} does) and can run {@code
 * PortProjection.forWorldCell}; so this class takes the ALREADY-PROJECTED input-cell world coords as
 * parameters and stays free of rotation/footprint plumbing. The network is whatever pipe borders that
 * cell: {@link NetworkManager#getOrBuildNetwork} is asked for the pipe AT that cell, so the caller passes
 * the cell the input pipe occupies (the pipe is adjacent to the machine's item-in face).
 *
 * <h2>The aggregate + diff (why {@link com.hypixel.hytale.server.core.inventory.MaterialQuantity} is
 * NEVER read)</h2>
 * {@code MaterialQuantity} is an opaque FFI/MemorySegment flyweight with NO readable instance getters
 * (verified during the {@link VanillaCraftBridge} work). We must never read a field off it; we may ONLY
 * pass it (and the {@code List<MaterialQuantity>} from {@code CraftingManager.getInputMaterials}) into the
 * vanilla {@link ItemContainer} material helpers. So this class never touches a recipe's materials
 * directly. Instead:
 * <ol>
 *   <li>{@link #snapshot} builds, once per tick, an aggregate {@link SimpleItemContainer} holding a COPY
 *       of every stack on every reachable container, AND retains the list of the real containers.</li>
 *   <li>{@link #available} runs vanilla's pure {@code canRemoveMaterials} against the aggregate (via
 *       {@link VanillaCraftBridge#inputsPresent}): no mutation, no material read.</li>
 *   <li>{@link #tryPull} TALLIES the aggregate by item id BEFORE, runs vanilla's atomic
 *       {@code removeMaterials} against the aggregate (via {@link VanillaCraftBridge#consumeInputs}),
 *       TALLIES AFTER, and {@linkplain #consumedDiff diffs} the two tallies. The diff is the exact
 *       {@code (itemId -> quantity)} the recipe consumed, derived PURELY from {@link ItemStack} reads
 *       ({@code getItemId}/{@code getQuantity}): never from {@code MaterialQuantity}. The Forge then
 *       removes exactly those quantities from the REAL containers and returns them as held stacks.</li>
 * </ol>
 *
 * <h2>Atomicity &amp; safety</h2>
 * The aggregate consume is all-or-nothing (vanilla's {@code internal_removeMaterials} tests every material
 * before committing: see {@link VanillaCraftBridge} "Atomicity"): a {@code false} return leaves the
 * aggregate untouched, so {@link #tryPull} returns null with no side effects. The real removal is driven
 * by a SIMULATE-FIRST pass (read-only slot scan) over the real containers: only if every consumed
 * {@code (itemId, qty)} is fully satisfiable across the real containers does the second pass commit. So a
 * mid-tick divergence between the snapshot copy and the live containers (another system mutating a chest
 * this tick) aborts cleanly BEFORE any real removal, never leaving a half-consumed mess. The tick is
 * single-threaded on the world thread, so in the common case the simulate pass always confirms.
 *
 * <h2>v1 caveats (documented, acceptable)</h2>
 * <ul>
 *   <li><b>Metadata not preserved on held stacks.</b> The returned held list is built as
 *       {@code new ItemStack(itemId, qty)} per consumed entry: damage/enchant/BlockHolder metadata on a
 *       sourced ingredient is dropped. Recipes are matched by id+quantity (vanilla's material match), so
 *       this is correct for the craft; it only matters if an ingredient's metadata should round-trip into
 *       the output, which v1 does not support.</li>
 *   <li><b>Cross-container extract is per-(itemId) greedy.</b> A consumed quantity is met by scanning the
 *       real containers in collection order, taking from each matching slot until satisfied. There is no
 *       attempt to honour which SPECIFIC container the aggregate's match came from (the aggregate has no
 *       such provenance). For fungible crafting ingredients this is exactly right.</li>
 * </ul>
 *
 * <p>Defensive throughout: every engine call is guarded; the class never throws on the world thread
 * (the caller's tick has a catch-Throwable backstop, but we avoid even reaching it on the steady path).
 */
public final class NetworkRecipeSource {

    private static final HytaleLogger LOG = HytaleLogger.forEnclosingClass();
    private static final Set<String> WARN_SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private NetworkRecipeSource() {
    }

    /**
     * A per-tick view of the Forge's input network: the aggregate {@link SimpleItemContainer} (a copy of
     * every reachable stack, used for the pure availability test + the consume diff) plus the retained
     * REAL {@link ItemContainer}s (used to commit the actual extraction). Built once per tick by
     * {@link #snapshot}; never mutated by {@link #available}; {@link #tryPull} mutates the aggregate (the
     * diff probe) and the real containers (the commit).
     */
    public static final class Snapshot {
        private final SimpleItemContainer aggregate;
        private final List<ItemContainer> realContainers;

        private Snapshot(SimpleItemContainer aggregate, List<ItemContainer> realContainers) {
            this.aggregate = aggregate;
            this.realContainers = realContainers;
        }

        /** The aggregate copy (for {@link VanillaCraftBridge} availability/consume probes). */
        public SimpleItemContainer aggregate() {
            return aggregate;
        }

        /** The live containers the aggregate was copied from (extraction targets). */
        public List<ItemContainer> realContainers() {
            return realContainers;
        }
    }

    /**
     * Snapshot the ITEM network bordering the given input cell: build an aggregate copy of every reachable
     * container's contents and retain the real containers for extraction. Returns {@code null} if there is
     * no ITEM network at {@code (inX,inY,inZ)} (no pipe, or a non-item network), in which case the Forge
     * cannot source anything this tick. Never throws.
     *
     * @param world     the live world (network + container access).
     * @param store     the chunk-store the block entities live on.
     * @param pipeType  the {@link PipeNode} component type (for the grid view; same one the tick holds).
     * @param service   the per-world network registry ({@link NetworkService#forWorld}).
     * @param inX,inY,inZ the WORLD coords of the cell the input pipe occupies (caller-projected from the
     *                  Forge's model item-in cell offset via {@code PortProjection}; see class javadoc).
     */
    @Nullable
    public static Snapshot snapshot(
            @Nonnull World world,
            @Nonnull Store<ChunkStore> store,
            @Nonnull ComponentType<ChunkStore, PipeNode> pipeType,
            @Nonnull NetworkService service,
            int inX, int inY, int inZ) {
        try {
            NetworkManager manager = service.forWorld(world);
            WorldPipeGridView grid = new WorldPipeGridView(world, store, pipeType);
            Network net = manager.getOrBuildNetwork(inX, inY, inZ, grid);
            if (net == null || net.channel() != PortChannel.ITEM) {
                return null; // no pipe at the input cell, or it is not an ITEM network
            }
            WorldContainerLookup containers = new WorldContainerLookup(world, store);
            // Machine-unaware collect (null MachineLookup): the Forge sources from passive inventories on
            // its input line; we deliberately do NOT pull from other machines' bench buffers here (the
            // union of Sources + Destinations is chests only with the 3-arg collect).
            Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);

            // UNION of sources + destinations, deduped by container key: the Forge pulls from ANY inventory
            // reachable on the input network (LOCKED design), regardless of that container's pipe-face role.
            Set<Long> containerKeys = new LinkedHashSet<>();
            for (Source s : endpoints.sources()) {
                containerKeys.add(s.containerKey());
            }
            for (Destination d : endpoints.destinations()) {
                containerKeys.add(d.containerKey());
            }

            List<ItemContainer> real = new ArrayList<>();
            int totalSlots = 0;
            for (long key : containerKeys) {
                ItemContainer c = containers.rawContainerAt(
                    NetworkManager.unpackX(key), NetworkManager.unpackY(key), NetworkManager.unpackZ(key));
                if (c == null) {
                    continue; // raced away, or no container component: skip
                }
                real.add(c);
                totalSlots += c.getCapacity();
            }
            if (real.isEmpty()) {
                return null; // network present but nothing to source from
            }

            // Aggregate: one slot per real slot across all containers (generous; copies never need merging
            // because the recipe match is quantity-aware across all slots). filter=false skips the slot
            // filter checks (we are mirroring contents into a scratch buffer, not enforcing UI rules).
            // CAP at Short.MAX_VALUE: a SimpleItemContainer's capacity is a short. If the network exposes
            // more real slots than that (pathological), the copy loop below stops at the cap. This is a
            // DELIBERATE safe degradation: the aggregate then UNDER-reports availability (some far stacks
            // are unseen, so a recipe needing them just reads as not-craftable), and never OVER-removes
            // (extraction is driven by the diff of what the capped aggregate actually consumed). Truncation
            // is warned (once) below so the case is diagnosable rather than silent.
            int aggCapacity = Math.min(totalSlots, Short.MAX_VALUE);
            if (totalSlots > aggCapacity) {
                warnOnce("snapshot-truncated", new IllegalStateException(
                    "input network exposes " + totalSlots + " slots, capped to " + aggCapacity
                        + " aggregate slots: availability under-reported (never over-removed)"));
            }
            SimpleItemContainer aggregate = new SimpleItemContainer((short) aggCapacity);
            short dst = 0;
            for (ItemContainer c : real) {
                short cap = c.getCapacity();
                for (short slot = 0; slot < cap; slot++) {
                    if (dst >= aggregate.getCapacity()) {
                        break;
                    }
                    ItemStack inSlot = c.getItemStack(slot);
                    if (inSlot == null || ItemStack.isEmpty(inSlot)) {
                        continue;
                    }
                    // Copy via a fresh stack so the aggregate never aliases the live container's stack
                    // (the diff probe MUTATES the aggregate). Metadata is cloned so the copy is faithful for
                    // vanilla's material match (id + metadata equivalence).
                    ItemStack copy = inSlot.getMetadata() == null
                        ? new ItemStack(inSlot.getItemId(), inSlot.getQuantity())
                        : new ItemStack(inSlot.getItemId(), inSlot.getQuantity()).withMetadata(inSlot.getMetadata().clone());
                    aggregate.setItemStackForSlot(dst, copy, false);
                    dst++;
                }
            }
            return new Snapshot(aggregate, real);
        } catch (Throwable t) {
            warnOnce("snapshot", t);
            return null; // never throw on the world thread
        }
    }

    /**
     * Whether recipe {@code r} (one ingredient set) is fully sourceable from {@code snapshot}'s network,
     * WITHOUT mutating anything. Pure availability test against the aggregate (vanilla's
     * {@code canRemoveMaterials}, via {@link VanillaCraftBridge#inputsPresent}). Reads no
     * {@code MaterialQuantity}. Returns {@code false} on a null snapshot or any error.
     */
    public static boolean available(@Nullable Snapshot snapshot, @Nonnull CraftingRecipe r) {
        if (snapshot == null) {
            return false;
        }
        try {
            return VanillaCraftBridge.inputsPresent(r, snapshot.aggregate, 1);
        } catch (Throwable t) {
            warnOnce("available", t);
            return false;
        }
    }

    /**
     * Atomically extract one ingredient set of recipe {@code r} from the REAL containers on
     * {@code snapshot}'s network and return the extracted {@link ItemStack}s (the Forge holds these as the
     * active craft's pulled ingredients). Returns {@code null} if the recipe is not fully sourceable, in
     * which case NOTHING is removed from any real container.
     *
     * <p><b>SNAPSHOT IS SPENT AFTER A SUCCESSFUL PULL.</b> This method permanently MUTATES the snapshot's
     * aggregate (the before/after consume probe runs vanilla's atomic {@code removeMaterials} against it).
     * After a successful pull the snapshot no longer reflects the network's contents, so the caller MUST
     * rebuild a fresh {@link #snapshot} before any further {@link #available}/{@link #tryPull} probe. (A
     * {@code null}/failed pull leaves the aggregate untouched: the snapshot is still reusable in that case,
     * but rebuilding once per craft is the simplest contract.)
     *
     * <p>Mechanism (the aggregate + diff; never reads {@code MaterialQuantity}):
     * <ol>
     *   <li>Tally the aggregate by item id BEFORE.</li>
     *   <li>Run vanilla's atomic consume against the aggregate ({@link VanillaCraftBridge#consumeInputs}).
     *       On failure the aggregate is untouched and this returns null.</li>
     *   <li>Tally AFTER; {@code consumed = before − after} per id (the exact ids+quantities the recipe
     *       used, from {@link ItemStack} reads only).</li>
     *   <li>SIMULATE-FIRST over the real containers: confirm every {@code (id, qty)} is satisfiable. If
     *       not (a mid-tick divergence), abort with null BEFORE removing anything real.</li>
     *   <li>Commit: remove each {@code (id, qty)} from the real containers and build the held list.</li>
     * </ol>
     */
    @Nullable
    public static List<ItemStack> tryPull(@Nullable Snapshot snapshot, @Nonnull CraftingRecipe r) {
        if (snapshot == null) {
            return null;
        }
        try {
            SimpleItemContainer aggregate = snapshot.aggregate;
            Map<String, Integer> before = tally(aggregate);
            // Atomic probe on the COPY: no-op on failure (vanilla tests all materials before committing).
            if (!VanillaCraftBridge.consumeInputs(r, aggregate, 1)) {
                return null; // not fully sourceable
            }
            Map<String, Integer> after = tally(aggregate);
            Map<String, Integer> consumed = consumedDiff(before, after);
            if (consumed.isEmpty()) {
                return List.of(); // a recipe with no inputs: nothing to extract
            }

            // SIMULATE-FIRST: confirm the real containers can satisfy the diff before mutating any of them.
            // This closes the residual risk of a half-consumed real network when the live containers have
            // diverged from the snapshot copy mid-tick.
            for (Map.Entry<String, Integer> e : consumed.entrySet()) {
                if (countAcross(snapshot.realContainers, e.getKey()) < e.getValue()) {
                    warnOnce("tryPull-divergence:" + e.getKey(),
                        new IllegalStateException("real containers no longer hold enough " + e.getKey()
                            + " (need " + e.getValue() + "): aborting before any real removal"));
                    return null;
                }
            }

            // COMMIT: remove each (id, qty) from the real containers and build the held list.
            List<ItemStack> held = new ArrayList<>(consumed.size());
            for (Map.Entry<String, Integer> e : consumed.entrySet()) {
                String id = e.getKey();
                int need = e.getValue();
                int got = removeAcross(snapshot.realContainers, id, need);
                if (got > 0) {
                    held.add(new ItemStack(id, got)); // v1: metadata not preserved (see class javadoc)
                }
                // got < need is impossible after the simulate pass on a single-threaded tick; if it ever
                // happens we still return what we actually pulled (held), so no item is duplicated.
            }
            return held;
        } catch (Throwable t) {
            warnOnce("tryPull", t);
            return null; // never throw on the world thread
        }
    }

    // --- pure helpers (unit-tested where they don't touch the engine container) ---

    /**
     * The per-item-id quantity DIFF {@code before − after}, keeping only ids whose count dropped (the
     * amount the consume removed). Pure: a TreeMap keyed by item id, deterministic iteration. An id absent
     * from {@code after} counts as fully consumed; an id whose count is unchanged or grew is omitted.
     * Negative diffs (an id that somehow grew) are never produced. This is how the consumed ingredient
     * set is derived WITHOUT reading any {@code MaterialQuantity}.
     */
    static Map<String, Integer> consumedDiff(Map<String, Integer> before, Map<String, Integer> after) {
        Map<String, Integer> consumed = new TreeMap<>();
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int delta = e.getValue() - after.getOrDefault(e.getKey(), 0);
            if (delta > 0) {
                consumed.put(e.getKey(), delta);
            }
        }
        return consumed;
    }

    /** Tally an aggregate container's contents by item id (summing quantities across all slots). */
    private static Map<String, Integer> tally(SimpleItemContainer container) {
        Map<String, Integer> counts = new TreeMap<>();
        short cap = container.getCapacity();
        for (short slot = 0; slot < cap; slot++) {
            ItemStack s = container.getItemStack(slot);
            if (s == null || ItemStack.isEmpty(s)) {
                continue;
            }
            String id = s.getItemId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            counts.merge(id, s.getQuantity(), Integer::sum);
        }
        return counts;
    }

    /** Total quantity of {@code id} across all real containers (read-only; the simulate pass). */
    private static int countAcross(List<ItemContainer> containers, String id) {
        int total = 0;
        for (ItemContainer c : containers) {
            short cap = c.getCapacity();
            for (short slot = 0; slot < cap; slot++) {
                ItemStack s = c.getItemStack(slot);
                if (s == null || ItemStack.isEmpty(s) || !id.equals(s.getItemId())) {
                    continue;
                }
                total += s.getQuantity();
            }
        }
        return total;
    }

    /**
     * Remove up to {@code need} of {@code id} from the real containers, slot by slot, returning the actual
     * amount removed. Mirrors {@code ItemContainerView.extract}'s single-slot removal path
     * ({@code removeItemStackFromSlot(slot, take, false, false)}), accumulating across slots and
     * containers until {@code need} is met.
     */
    private static int removeAcross(List<ItemContainer> containers, String id, int need) {
        int removed = 0;
        for (ItemContainer c : containers) {
            if (removed >= need) {
                break;
            }
            short cap = c.getCapacity();
            for (short slot = 0; slot < cap && removed < need; slot++) {
                ItemStack s = c.getItemStack(slot);
                if (s == null || ItemStack.isEmpty(s) || !id.equals(s.getItemId())) {
                    continue;
                }
                int take = Math.min(need - removed, s.getQuantity());
                if (take <= 0) {
                    continue;
                }
                var tx = c.removeItemStackFromSlot(slot, take, false, false);
                if (tx == null || !tx.succeeded()) {
                    continue; // raced empty on this slot; try the next
                }
                ItemStack out = tx.getOutput();
                int got = out == null ? 0 : out.getQuantity();
                if (got > 0) {
                    removed += got;
                }
            }
        }
        return removed;
    }

    private static void warnOnce(String where, Throwable t) {
        if (WARN_SEEN.add(where + ":" + t)) {
            LOG.atWarning().log("CC NetworkRecipeSource." + where + " failed (skipped): " + t);
        }
    }
}
