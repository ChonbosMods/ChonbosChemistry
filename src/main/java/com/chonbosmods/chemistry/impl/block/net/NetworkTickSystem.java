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
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
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
 * <h2>Buffer persistence</h2>
 * The cached {@link Network}'s in-memory {@code stored} carries across ticks (a freshly built network
 * starts empty and fills from providers over successive ticks). Syncing that back to
 * {@code PipeNode.bufferShare} is H6's concern and is intentionally NOT done here.
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

    /** Per-world last-seen world tick, to detect when to reset {@link #processedAnchors}. */
    private final Map<World, Long> lastTickByWorld = new IdentityHashMap<>();
    /** Anchors of networks already distributed this world-tick (dedup so each network runs once). */
    private final Set<Long> processedAnchors = new HashSet<>();

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

        // Reset the per-tick dedup set when this world advances a tick.
        Long last = lastTickByWorld.get(world);
        long now = world.getTick();
        if (last == null || last != now) {
            lastTickByWorld.put(world, now);
            processedAnchors.clear();
        }

        NetworkManager manager = networkService.forWorld(world);
        PipeGridView grid = new WorldPipeGridView(world, store, pipeType);
        Network net = manager.getOrBuildNetwork(x, y, z, grid);
        if (net == null) {
            return;
        }
        Long anchor = manager.anchorOf(x, y, z);
        if (anchor == null || !processedAnchors.add(anchor)) {
            return; // no anchor, or this network already distributed this tick
        }

        MachineLookup lookup = new WorldMachineLookup(world, store, machineType, tankType);
        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, lookup);
        NetworkTransfer.distribute(net, endpoints.providers(), endpoints.acceptors());
    }
}
