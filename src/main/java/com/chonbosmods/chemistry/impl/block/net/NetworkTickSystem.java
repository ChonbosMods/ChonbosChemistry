package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints;
import com.chonbosmods.chemistry.impl.block.net.item.ItemTransferSystem;
import com.chonbosmods.chemistry.impl.block.net.item.WorldContainerLookup;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

/**
 * Per-tick driver for pipe transport (Task H4). Ticks where pipes are (query = {@link PipeNode}); for
 * each ticked pipe it resolves the pipe's {@link Network}, and once per network per tick runs one
 * {@link NetworkTransfer#distribute} pass over the endpoints collected around that network. This is
 * what moves energy/fluid/gas now: machines no longer push to adjacent blocks (see
 * {@code MachineTickSystem}).
 *
 * <h2>Per-network dedup</h2>
 * A network has many member pipes, each of which ticks; the distribution must run exactly once per
 * network per tick. We dedup on the network's anchor (its deterministic id) using a per-tick visited
 * set, reset whenever {@link World#getTick()} advances (keyed per world so multiple worlds don't clear
 * each other's set).
 *
 * <h2>Buffer persistence (H6 FIX 1)</h2>
 * After each network's distribute pass, the network's {@code stored()} is split evenly across its member
 * pipes ({@link NetworkManager#splitEvenly}) and written back to each {@link PipeNode}'s
 * {@code bufferShare}/{@code resourceId}. This keeps the persisted shares fresh as of the last tick end,
 * so that when a place/break event invalidates a network (between ticks, same thread) and it rebuilds,
 * {@link NetworkManager#getOrBuildNetwork} re-pools those shares and the energy/fluid survives.
 *
 * <h2>Defensive contract</h2>
 * Runs every server tick on hot ECS data: every lookup is guarded and skipped on failure; never throws
 * on a missing component/chunk/world/network.
 */
public final class NetworkTickSystem extends EntityTickingSystem<ChunkStore> {

    private final ComponentType<ChunkStore, PipeNode> pipeType;
    private final ComponentType<ChunkStore, MachineBlockState> machineType;
    private final ComponentType<ChunkStore, TankBlockState> tankType;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType;
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType;
    private final NetworkService networkService;

    /**
     * The ITEM-channel transport glue (Task 7), invoked instead of {@link NetworkTransfer#distribute} for
     * ITEM networks. Stateless and reused across worlds/networks (it builds its per-tick container lookup
     * + seams from the world/store passed in). See {@code ItemTransferDriver} for the structure rationale
     * (a driver-from-this-system, not a second ticking system).
     */
    private final ItemTransferSystem itemTransfer = new ItemTransferSystem();

    /** Logger for the gated tick diagnostics ([CC-item] stall log + [CC-tiprot] rotation log). */
    private static final Logger LOGGER = Logger.getLogger(NetworkTickSystem.class.getName());

    /**
     * TEMPORARY diagnostics master switch (the [CC-item] stall log + the [CC-tiprot] rotation log).
     * OFF by default so the server stays quiet; flip to true (or set the {@code cc.debug.tick} system
     * property) for a single confirmation run. Both diagnostics are removed once their bugs are fixed.
     */
    private static final boolean DEBUG_TICK_LOGS = Boolean.getBoolean("cc.debug.tick");

    /** Per-world last-seen world tick, to detect when to reset that world's processed-anchor set. */
    private final Map<World, Long> lastTickByWorld = new IdentityHashMap<>();
    /**
     * Per-world set of anchors already distributed this world-tick (dedup so each network runs once).
     * Keyed per world so two worlds (whose anchors are world-agnostic packed keys) can't collide or
     * clear each other's set; each world's set is reset when THAT world's tick advances.
     */
    private final Map<World, Set<Long>> processedAnchorsByWorld = new IdentityHashMap<>();

    /**
     * Packed block keys ({@link NetworkManager#packKey}) of pipes this system has PROGRAMMATICALLY
     * overridden via {@link #applySuppressedArmVisuals} (a divergence/tip composite written through the
     * rotation-setting {@code setBlock(...,198)} path). Tracked so that when such a pipe later becomes
     * undisturbed again ({@code !anyTip && effective == physical}) we actively RESET it to the plain
     * engine shape instead of leaving the stale override on screen: the engine never re-resolves a pipe
     * on a container/flow-state change (only place/break runs ConnectedBlocksUtil), so without this the
     * override persists until an unrelated edit elsewhere wipes it.
     *
     * <p>Lifecycle: a key is ADDED when this method applies a programmatic state, and REMOVED when the
     * undisturbed branch resets that pipe back to plain. A pipe whose chunk/grid entry has vanished (a
     * break) is simply never revisited here, so its key may linger in the set indefinitely: an acceptable
     * small leak, identical in kind to the old {@code warnedRotationMismatch} set, not worth a second
     * bookkeeping pass to scrub (the break path already invalidates the network).
     */
    private final Set<Long> overriddenPositions = new HashSet<>();

    public NetworkTickSystem(
            @Nonnull ComponentType<ChunkStore, PipeNode> pipeType,
            @Nonnull ComponentType<ChunkStore, MachineBlockState> machineType,
            @Nonnull ComponentType<ChunkStore, TankBlockState> tankType,
            @Nonnull NetworkService networkService) {
        this.pipeType = pipeType;
        this.machineType = machineType;
        this.tankType = tankType;
        this.networkService = networkService;
        this.blockInfoType = BlockModule.BlockStateInfo.getComponentType();
        this.blockChunkType = BlockChunk.getComponentType();
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // Collection reads neighbor components across archetype chunks; keep it single-threaded.
        return false;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return pipeType;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        PipeNode pipe = archetypeChunk.getComponent(index, pipeType);
        if (pipe == null) {
            return;
        }

        // Resolve this pipe's world position (same technique as MachineTickSystem). Guard every step.
        BlockModule.BlockStateInfo info = archetypeChunk.getComponent(index, blockInfoType);
        if (info == null) {
            return;
        }
        Ref<ChunkStore> chunkRef = info.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) {
            return;
        }
        BlockChunk blockChunk = chunkRef.getStore().getComponent(chunkRef, blockChunkType);
        if (blockChunk == null) {
            return;
        }
        int blockIndex = info.getIndex();
        final int x = (blockChunk.getX() << 5) | ChunkUtil.xFromBlockInColumn(blockIndex);
        final int y = ChunkUtil.yFromBlockInColumn(blockIndex);
        final int z = (blockChunk.getZ() << 5) | ChunkUtil.zFromBlockInColumn(blockIndex);

        ChunkStore external = store.getExternalData();
        if (external == null) {
            return;
        }
        final World world = external.getWorld();
        if (world == null) {
            return;
        }

        // Reset this world's per-tick dedup set when this world advances a tick.
        Set<Long> processedAnchors =
            processedAnchorsByWorld.computeIfAbsent(world, w -> new HashSet<>());
        Long last = lastTickByWorld.get(world);
        long now = world.getTick();
        if (last == null || last != now) {
            lastTickByWorld.put(world, now);
            processedAnchors.clear();
        }

        NetworkManager manager = networkService.forWorld(world);
        PipeGridView grid = new WorldPipeGridView(world, store, pipeType);

        // H8: restore any engine-wiped pipes from their pre-wipe snapshots BEFORE any rebuild this
        // pass pools shares (events and tick systems share the world thread, so the restore always
        // beats the first rebuild after a topology change). Restored positions are invalidated so
        // their networks re-pool the restored shares. Cheap no-op when nothing is pending.
        PipeNodeSnapshots snapshots = networkService.snapshotsForWorld(world);
        if (!snapshots.isEmpty()) {
            for (long restoredKey : snapshots.restorePending(grid, now)) {
                manager.invalidate(
                    NetworkManager.unpackX(restoredKey),
                    NetworkManager.unpackY(restoredKey),
                    NetworkManager.unpackZ(restoredKey));
            }
        }

        Network net = manager.getOrBuildNetwork(x, y, z, grid);
        if (net == null) {
            return;
        }
        Long anchor = manager.anchorOf(x, y, z);
        if (anchor == null || !processedAnchors.add(anchor)) {
            return; // no anchor, or this network already distributed this tick
        }

        MachineLookup lookup = new WorldMachineLookup(world, store, machineType, tankType);

        // ITEM networks transport DISCRETE stacks, not a fungible shared buffer: they run the dedicated
        // item driver (Task 7) instead of NetworkTransfer.distribute, and skip the energy/lock write-back
        // machinery entirely (an ITEM network has no stored()/type-lock to persist or clear). The visual
        // passes below still run for them (flow-state arms + future container arms), with energized=false:
        // item pipes ship no _On states (design "NO _On state for item pipes"), and the visual passes
        // no-op on any missing block state anyway, so a false energized is correct and defensive.
        // ITEM-only container lookup, shared by both the item transfer pass and the container-aware
        // visual masks below. Null for every non-ITEM network: those masks take no container awareness
        // (a chest beside a power/fluid/gas cable is irrelevant) at zero cost, and constructing the
        // world adapter is pointless work for them.
        WorldContainerLookup containers = null;

        boolean energized;
        if (net.channel() == PortChannel.ITEM) {
            containers = new WorldContainerLookup(world, store);
            ItemEndpoints.Endpoints itemEndpoints = ItemEndpoints.collect(net, grid, containers);
            // DIAGNOSTIC (2026-06-06 stall report, remove after root-cause): once per pull interval,
            // log what the glue sees so one in-game run pinpoints the failing phase (endpoint
            // detection vs extraction vs movement). Rate-limited to the pull boundary: ~1 line/s.
            boolean diagTick = DEBUG_TICK_LOGS && now % 20 == 0;
            int beforeTransit = diagTick ? countInTransitDiag(net, grid) : 0;
            itemTransfer.tickNetwork(net, world, containers, grid, itemEndpoints);
            if (diagTick) {
                int afterTransit = countInTransitDiag(net, grid);
                LOGGER.info("[CC-item] tick=" + now + " anchor=" + anchor
                    + " members=" + net.memberKeys().size()
                    + " sources=" + itemEndpoints.sources().size()
                    + " dests=" + itemEndpoints.destinations().size()
                    + " inTransit " + beforeTransit + "->" + afterTransit);
            }
            energized = false; // ITEM has no _On twin; Task 9 owns the full item visual integration
        } else {
            NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, grid, lookup);
            long delivered = NetworkTransfer.distribute(net, endpoints);

            // H6 FIX 1: persist the post-distribute buffer back onto the member pipe shares so an
            // invalidation/rebuild (place/break between ticks) re-pools it losslessly.
            // Task 5: if this write-back CLEARED the network's type-lock (a FLUID/GAS line drained empty),
            // invalidate its members so the next access re-merges it with an adjacent run the now-gone
            // substance boundary used to separate (the drain case has no place/break event to trigger this).
            if (NetworkManager.writeBackShares(net, grid)) {
                manager.invalidateMembers(net);
            }

            // Visual ON/OFF: a network is "energized" if it currently holds energy OR moved any this tick.
            // The OR on delivered is critical: in steady flow the buffer is pulled and fully delivered
            // within one tick, ending at stored()==0, yet the cables must still read as ON.
            energized = net.stored() > 0 || delivered > 0;
        }
        applyPoweredVisual(world, net, energized);

        // Task 11: programmatic suppressed-arm visuals. For each member pipe whose EFFECTIVE
        // connectivity (what the network joins) differs from its PHYSICAL connectivity (what the engine's
        // pattern matcher welds), override the interaction state so the rendered arm count matches the
        // suppressed topology (a NONE face, a substance mismatch, or a flow-hidden machine). Undisturbed
        // pipes (masks equal) are left to the engine + the H8 powered flip above. Throttled by reading
        // the placed state first and only swapping on a real change (same discipline as the powered flip).
        applySuppressedArmVisuals(world, net, grid, lookup, containers, energized);
    }

    /**
     * Flips every member cable of {@code net} to its powered or unpowered texture state to match
     * {@code energized}. Reads each placed cable's current interaction-state name and only issues a
     * block swap when the desired state name actually differs (the engine's
     * {@code setBlockInteractionState} additionally no-ops on an equal name, but we avoid even the call
     * and the BlockType lookups on the steady-state path). Fully guarded: a missing chunk/block/state
     * for any member is skipped and never throws on the world thread.
     */
    private void applyPoweredVisual(@Nonnull World world, @Nonnull Network net, boolean energized) {
        for (long key : net.memberKeys()) {
            try {
                int mx = NetworkManager.unpackX(key);
                int my = NetworkManager.unpackY(key);
                int mz = NetworkManager.unpackZ(key);

                BlockAccessor accessor = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(mx, mz));
                if (accessor == null) {
                    continue; // chunk not loaded; its cable will re-resolve when it next ticks
                }
                BlockType bt = accessor.getBlockType(mx, my, mz);
                if (bt == null || bt == BlockType.EMPTY) {
                    continue; // not our block anymore (race with a break); skip
                }

                // Current placed interaction-state name (null for the base/default block).
                String current = bt.getStateForBlock(bt);
                String desired = energized
                        ? PipePowerStates.poweredOf(current)
                        : PipePowerStates.unpoweredOf(current);

                // Only swap on a real change. Treat a null/"" current as "default" for the compare so
                // we never redundantly swap a base block to "default".
                String normalized = (current == null || current.isEmpty()) ? "default" : current;
                if (desired.equals(normalized)) {
                    continue;
                }
                // force=false: the engine also re-checks equality before swapping (belt and braces).
                accessor.setBlockInteractionState(mx, my, mz, bt, desired, false);
            } catch (Throwable ignored) {
                // A single member's visual swap must never crash the tick.
            }
        }
    }

    /**
     * Task 11: applies programmatic suppressed-arm visuals to every member pipe of {@code net}. For each
     * member it computes the pure {@link PipeVisualStates#effectiveMask effective} and
     * {@link PipeVisualStates#physicalMask physical} connectivity masks and, when they DIFFER, swaps the
     * cable's interaction state to {@link PipeShapes#stateFor}{@code (effectiveMask, energized)} so the
     * rendered arm count matches reality. The divergence runs in EITHER direction:
     * {@code effective < physical} DROPS a suppressed arm (a NONE face, a substance-mismatched pipe, or a
     * flow-hidden machine), and (Task 9, ITEM networks only) {@code effective > physical} ADDS an arm the
     * engine refused to weld: a vanilla chest carries no {@code CC_ItemFace} tag, so the engine stubs
     * nothing toward it, but the network delivers into it: the swap adds the chest arm. When the masks
     * are EQUAL the pipe is undisturbed and is left entirely to the engine pattern system plus the H8
     * powered flip ({@link #applyPoweredVisual}, already run): this method does nothing for it.
     *
     * <h2>Container awareness (Task 9)</h2>
     * {@code containers} is the ITEM network's {@link WorldContainerLookup} (the same one the item
     * transfer pass used this tick), or {@code null} for a non-ITEM network. It is threaded straight into
     * both mask calls: {@link PipeVisualStates#effectiveMask} counts a container neighbour only on the
     * ITEM channel with a non-null lookup, and {@link PipeVisualStates#physicalMask} ignores it entirely.
     * For non-ITEM networks the null lookup means zero container awareness, identical to the pre-Task-9
     * behaviour. {@code energized} is always {@code false} for ITEM networks (item pipes ship no
     * {@code _On} states): the caller's channel branch sets it, so the tipped/plain states resolve to
     * the unenergized node here.
     *
     * <h2>Per-face tips (Task 4) reach containers too</h2>
     * The push/pull tips work for any arm pointing at an endpoint, including a container end: the
     * endpoint-arm mask requires only that the arm NOT point at a PIPE (a container is not a pipe), so an
     * ITEM pipe whose face is PUSH/PULL toward a chest contributes that face to {@code endpointArmMask}
     * and {@link PipeShapes#tippedStateFor} renders the composite tip shape. The container only needs to
     * make the face count in the effective mask (it does, above) for the tip path to engage.
     *
     * <h2>Throttle</h2>
     * Like the powered flip, this reads the placed state first and issues the block swap ONLY when the
     * desired programmatic state name actually differs from the current one OR the block's current
     * rotation differs from the rotation the effective shape wants ({@link PipeShapes#resolve}). Now that
     * rotation is part of the write (see below), the rotation half of the throttle is REQUIRED: without it
     * a pipe already showing the right state name but at a stale rotation would never be corrected, and a
     * pipe at the right rotation but read each tick would otherwise re-issue the same write. The
     * steady-state path costs a mask comparison + a state read + a rotation read and no block mutation.
     *
     * <h2>Rotation is SET, not preserved (2026-06-07 fix)</h2>
     * The engine's {@code setBlockInteractionState} resolves the new shape from the state name but reuses
     * the block's EXISTING rotation index (see {@link PipeShapes}), so a composite tip/container shape
     * rendered at the block's frozen rotation: arms pointed the wrong way, lone-stub tips faced the wrong
     * neighbour, and collar UVs striped on multi-arm shapes. This path instead calls the rotation-setting
     * {@code WorldChunk.setBlock} primitive directly with {@code rotation =
     * PipeShapes.resolve(effectiveMask).rotationIndex()} (the rotation the shape WANTS), mirroring what
     * {@code setBlockInteractionState} does internally for everything else ({@code id} from the asset map,
     * {@code newState} from {@code getBlockForState}, {@code filler = 0}) but writing the wanted rotation
     * rather than the frozen one. Settings reuse the engine's {@code 198} (0xC6) verbatim:
     * <ul>
     *   <li><b>bit 1 (0x02) SET</b> &rarr; the existing block entity (this PipeNode: flow states,
     *       in-transit stacks) is PRESERVED, not re-cloned from the template (the H8 wipe). Identical to
     *       what {@code setBlockInteractionState} already did, so no data-loss regression.</li>
     *   <li>the remaining bits match {@code setBlockInteractionState}'s side-effect profile (break-particle
     *       suppression etc.), so the only behavioural delta vs. the old swap is the rotation write.</li>
     * </ul>
     * A direct {@code setBlock} does NOT run {@code ConnectedBlocksUtil}, so it never reshapes or wipes the
     * neighbour pipes (no cascade). The old rotation-mismatch fallback (apply-anyway-and-warn) is obsolete
     * and removed: we now set the correct rotation outright.
     *
     * <p>Fully guarded: a missing chunk/block/state for any member is skipped and never throws on the
     * world thread.
     */
    private void applySuppressedArmVisuals(
            @Nonnull World world,
            @Nonnull Network net,
            @Nonnull PipeGridView grid,
            @Nonnull MachineLookup lookup,
            WorldContainerLookup containers,
            boolean energized) {
        // DIAGNOSTIC (2026-06-07 tip-rotation confirmation, remove after root-cause): rate-limit the
        // per-pipe rotation log to ~1 line/s so one in-game run confirms whether the chosen composite
        // shape WANTS a rotation the block does not currently have (the suspected tip/collar-misorient
        // root cause). Gated to the same pull boundary the item diagnostic uses.
        final boolean diagTick = DEBUG_TICK_LOGS && world.getTick() % 20 == 0;
        for (long key : net.memberKeys()) {
            try {
                int mx = NetworkManager.unpackX(key);
                int my = NetworkManager.unpackY(key);
                int mz = NetworkManager.unpackZ(key);

                PipeNode member = grid.pipeAt(mx, my, mz);
                if (member == null) {
                    continue; // pipe gone from the live grid (race with a break); skip
                }

                // Container-aware masks for ITEM networks (containers != null): the effective mask
                // counts a chest the engine never welds toward, so effective > physical ADDS the arm.
                // Non-ITEM networks pass containers=null (no container awareness, identical to pre-Task-9).
                int effective = PipeVisualStates.effectiveMask(member, mx, my, mz, grid, lookup, containers);
                int physical = PipeVisualStates.physicalMask(member, mx, my, mz, grid, lookup, containers);

                // Task 4: per-face push/pull tips, any shape (up to MAX_DIRECTED_FACES arms). A face tips
                // when it is an effective arm AND an endpoint arm (points at a machine/container, NOT
                // another pipe) AND its flow state is PUSH/PULL. We build the per-face flow-state array
                // (OFFSETS order) and the endpoint-arm mask, then let PipeShapes select the composite key.
                // A stale persisted PUSH/PULL on a face whose neighbour is (now) another PIPE must NOT draw
                // an arrow (pipe-pipe push/pull is treated as NORMAL everywhere else): the endpoint-arm
                // mask excludes such faces, generalising the old single-arm guard to every arm.
                FlowState[] faceStates = new FlowState[6];
                int endpointArmMask = 0;
                boolean anyTip = false;
                for (int armFace = 0; armFace < 6; armFace++) {
                    FlowState fs = member.flowState(armFace);
                    faceStates[armFace] = fs;
                    if ((effective & (1 << armFace)) == 0) {
                        continue; // not an effective arm; can't be an endpoint arm either
                    }
                    int ax = mx + NetworkManager.OFFSETS[armFace][0];
                    int ay = my + NetworkManager.OFFSETS[armFace][1];
                    int az = mz + NetworkManager.OFFSETS[armFace][2];
                    if (grid.pipeAt(ax, ay, az) == null) {
                        endpointArmMask |= 1 << armFace; // neighbour is not a pipe: an endpoint arm
                        if (fs == FlowState.PUSH || fs == FlowState.PULL) {
                            anyTip = true; // a tipped face exists (cheap pre-check, no rotation needed)
                        }
                    }
                }

                if (!anyTip && effective == physical) {
                    // Undisturbed and no tip. Two sub-cases (see shouldResetOverride):
                    if (!overriddenPositions.contains(key)) {
                        // Never programmatically touched: the engine pattern + H8 flip own this pipe's
                        // visual. Cheap steady-state fast path: no chunk fetch, no state read.
                        continue;
                    }
                    // We previously overrode this pipe (added a container/tip arm) and it has now returned
                    // to the plain engine topology: the engine will NOT re-resolve it on its own (a
                    // container/flow change runs no neighbour pass), so the stale override would linger.
                    // Actively reset it to the plain effective shape via the SAME entity-preserving,
                    // neighbour-non-wiping setBlock(...,198) path, gated by the read-before-write throttle.
                    resetOverriddenToPlain(world, key, mx, my, mz, effective, energized);
                    continue;
                }

                BlockAccessor accessor = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(mx, mz));
                if (accessor == null) {
                    continue; // chunk not loaded; will re-resolve when it next ticks
                }
                BlockType bt = accessor.getBlockType(mx, my, mz);
                if (bt == null || bt == BlockType.EMPTY) {
                    continue; // not our block anymore (race with a break); skip
                }

                // The rotation the effective shape WANTS. We SET this (the fix): the composite tip/
                // container shape is oriented correctly instead of inheriting the block's frozen yaw. The
                // tip key is built in this same wanted rotation so its model-space tip faces line up with
                // the orientation we are about to write (NOT the stale current rotation).
                int wantRot = PipeShapes.resolve(effective).rotationIndex();
                int currentRotation = currentRotationIndex(accessor, mx, my, mz);

                // The tipped key falls back to the plain effective-topology shape when no arm tips.
                String desired = PipeShapes.tippedStateFor(
                    effective, faceStates, endpointArmMask, energized, wantRot);
                String current = bt.getStateForBlock(bt);
                String normalizedCurrent = (current == null || current.isEmpty()) ? "default" : current;

                // Throttle: skip when the block ALREADY shows this shape AND is at the wanted rotation.
                // The rotation half is required now that rotation is part of the write (see javadoc):
                // without it a stale-rotation block would never be corrected, and a correct block would be
                // rewritten every tick.
                if (desired.equals(normalizedCurrent) && currentRotation == wantRot) {
                    continue;
                }

                // DIAGNOSTIC (2026-06-07 tip-rotation, gated by cc.debug.tick, kept harmless/off for the
                // confirmation run): the masks, chosen key, wanted rotation, and the block's CURRENT
                // rotation. Rate-limited (diagTick) to ~1 line/s.
                if (diagTick) {
                    LOGGER.info("[CC-tiprot] (" + mx + "," + my + "," + mz + ") eff="
                        + Integer.toBinaryString(effective) + " phys="
                        + Integer.toBinaryString(physical) + " key=" + desired
                        + " wantRot=" + wantRot + " curRot=" + currentRotation);
                }

                // Resolve the new shape BlockType from the desired state name (mirrors what
                // setBlockInteractionState does internally). Null => the state key is not defined on this
                // BlockType: skip defensively rather than write garbage.
                BlockType newState = bt.getBlockForState(desired);
                if (newState == null) {
                    continue;
                }

                // Rotation-setting swap (the fix): write the shape geometry AND the WANTED rotation via the
                // setBlock primitive, mirroring setBlockInteractionState's internals (id from the asset
                // map, filler 0, settings 198) but with wantRot in place of the preserved currentRotation.
                // settings 198 (0xC6): bit 1 (0x02) is SET, so the existing PipeNode entity (flow states,
                // in-transit stacks) is PRESERVED (no template re-clone = no H8 wipe); the rest matches the
                // engine's own swap side-effects. A direct setBlock skips ConnectedBlocksUtil, so it never
                // reshapes/wipes neighbour pipes.
                int id = BlockType.getAssetMap().getIndex(newState.getId());
                accessor.setBlock(mx, my, mz, id, newState, wantRot, 0, 198);
                // Record that this pipe now carries a programmatic override, so when it later becomes
                // undisturbed again the branch above resets it to plain (the engine won't re-resolve it).
                overriddenPositions.add(key);
            } catch (Throwable ignored) {
                // A single member's suppressed-arm swap must never crash the tick.
            }
        }
    }

    /**
     * Resets a previously-overridden pipe back to its PLAIN effective-topology shape, then drops it from
     * {@link #overriddenPositions}. Called only from the undisturbed branch of
     * {@link #applySuppressedArmVisuals} for a position that {@link #shouldResetOverride} flagged
     * (undisturbed + no tip + currently overridden).
     *
     * <h2>Why this is needed</h2>
     * Once we programmatically override a pipe (e.g. add a chest/container arm), the engine no longer owns
     * its visual: a later container/flow-state change that drops the arm does NOT trigger the engine's
     * neighbour re-resolution (only place/break runs ConnectedBlocksUtil), so the overridden shape would
     * stay on screen until an unrelated edit elsewhere wipes it. This actively writes the plain shape the
     * tick the divergence disappears.
     *
     * <h2>Energized composition (no powered flicker)</h2>
     * The plain desired key is {@link PipeShapes#stateFor}{@code (effective, energized)}, which already
     * bakes the {@code _On} twin via {@link PipePowerStates#poweredOf} when {@code energized} (identical to
     * what {@link #applyPoweredVisual} and the tip path produce). {@code applyPoweredVisual} runs BEFORE
     * this method each tick and only flips the texture twin; this reset, running last, writes the
     * energized-correct plain key outright, so a powered cable resets straight to its {@code _On} shape with
     * no off-frame. Next tick {@code applyPoweredVisual} sees the already-correct twin and no-ops.
     *
     * <h2>Throttle</h2>
     * Mirrors the apply path: reads the placed state name + rotation and writes only when the plain shape
     * name or the wanted rotation differs, so a pipe already at the plain shape just drops from the set
     * with no block mutation. The rotation is SET to {@code PipeShapes.resolve(effective).rotationIndex()}
     * via the same entity-preserving {@code setBlock(...,198)} primitive (no neighbour wipe).
     *
     * <p>Fully guarded: a missing chunk/block/state is skipped; the position still drops from the set so a
     * vanished pipe never lingers via this path.
     */
    private void resetOverriddenToPlain(
            @Nonnull World world, long key, int mx, int my, int mz, int effective, boolean energized) {
        try {
            BlockAccessor accessor = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(mx, mz));
            if (accessor == null) {
                return; // chunk not loaded; leave the key set, retry when it next ticks
            }
            BlockType bt = accessor.getBlockType(mx, my, mz);
            if (bt == null || bt == BlockType.EMPTY) {
                overriddenPositions.remove(key); // not our block anymore (broken); stop tracking it
                return;
            }

            int wantRot = PipeShapes.resolve(effective).rotationIndex();
            int currentRotation = currentRotationIndex(accessor, mx, my, mz);
            String desired = PipeShapes.stateFor(effective, energized);
            String current = bt.getStateForBlock(bt);
            String normalizedCurrent = (current == null || current.isEmpty()) ? "default" : current;

            if (shouldResetOverride(desired, normalizedCurrent, wantRot, currentRotation)) {
                BlockType newState = bt.getBlockForState(desired);
                if (newState != null) {
                    int id = BlockType.getAssetMap().getIndex(newState.getId());
                    accessor.setBlock(mx, my, mz, id, newState, wantRot, 0, 198);
                }
                // "default" is the base block: getBlockForState may legitimately return the base BlockType
                // here, which the setBlock above applies; either way the override no longer holds.
            }
            // Whether we wrote or the throttle skipped (already plain), the override is resolved: stop
            // tracking so the cheap fast path owns this pipe again next tick.
            overriddenPositions.remove(key);
        } catch (Throwable ignored) {
            // A single member's reset must never crash the tick.
        }
    }

    /**
     * Pure decision: should a previously-overridden pipe issue a plain-shape write this tick? True only
     * when the placed shape name differs from the desired plain key OR the placed rotation differs from the
     * wanted rotation: identical throttle discipline to the apply path, factored out for unit testing.
     *
     * @param desiredStateName the plain {@link PipeShapes#stateFor} key the pipe should return to
     * @param currentStateName the pipe's current placed interaction-state name (normalized; never null/"")
     * @param wantRotation the rotation the plain shape wants ({@code resolve(effective).rotationIndex()})
     * @param currentRotation the pipe's current placed rotation index
     * @return {@code true} if a block write is needed; {@code false} if already at the plain shape+rotation
     */
    static boolean shouldResetOverride(
            String desiredStateName, String currentStateName, int wantRotation, int currentRotation) {
        return !desiredStateName.equals(currentStateName) || wantRotation != currentRotation;
    }

    /**
     * The block's current rotation index at {@code (x,y,z)}: this is exactly the index
     * {@code setBlockInteractionState} reads and preserves on a state swap (PipeShapes' orientation
     * finding). {@code getRotationIndex} carries the SDK's removal tag (as does its {@code getRotation}
     * twin), so the suppression is isolated to this one-line accessor rather than the whole visual pass.
     */
    @SuppressWarnings("removal")
    private static int currentRotationIndex(@Nonnull BlockAccessor accessor, int x, int y, int z) {
        return accessor.getRotationIndex(x, y, z);
    }

    /** DIAGNOSTIC helper (2026-06-06 stall report): live in-transit total across member pipes. */
    private static int countInTransitDiag(Network net, PipeGridView grid) {
        int total = 0;
        for (long key : net.memberKeys()) {
            PipeNode pipe = grid.pipeAt(
                NetworkManager.unpackX(key), NetworkManager.unpackY(key), NetworkManager.unpackZ(key));
            if (pipe != null) {
                total += pipe.inTransit().size();
            }
        }
        return total;
    }
}
