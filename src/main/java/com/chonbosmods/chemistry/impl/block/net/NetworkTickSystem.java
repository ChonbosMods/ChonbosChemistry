package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
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

    /** Per-world last-seen world tick, to detect when to reset that world's processed-anchor set. */
    private final Map<World, Long> lastTickByWorld = new IdentityHashMap<>();
    /**
     * Per-world set of anchors already distributed this world-tick (dedup so each network runs once).
     * Keyed per world so two worlds (whose anchors are world-agnostic packed keys) can't collide or
     * clear each other's set; each world's set is reset when THAT world's tick advances.
     */
    private final Map<World, Set<Long>> processedAnchorsByWorld = new IdentityHashMap<>();

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
        boolean energized = net.stored() > 0 || delivered > 0;
        applyPoweredVisual(world, net, energized);
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
}
