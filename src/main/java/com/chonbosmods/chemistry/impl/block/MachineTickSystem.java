package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
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
import javax.annotation.Nonnull;

/**
 * Per-tick driver for machine block entities (Task B2). Each tick, for every live
 * {@link MachineBlockState} on the {@link ChunkStore}, it runs:
 *
 * <ol>
 *   <li>(optional) creative-source refill: if the node is flagged a creative power source, its energy
 *       buffer is topped up to full so it always has power to push;</li>
 *   <li>a TRANSPORT pass: push energy then resources to neighbors via {@link TransportEngine};</li>
 *   <li>a WORK pass: advance the node's {@link WorkState} (currently a stub: see step 3 below).</li>
 * </ol>
 *
 * <h2>Defensive contract</h2>
 * This runs every server tick on hot ECS data. It MUST NEVER throw on a missing neighbor, component,
 * chunk, or world: every lookup is null/validity guarded and skipped on failure. It also never calls
 * {@code clone()} (it mutates the live component in place).
 *
 * <h2>Query scope</h2>
 * The query matches the machine component type only. Tanks ({@link TankBlockState}) are passive: they
 * are not ticked themselves but participate as neighbors reached through the {@link NeighborView}
 * (both machine and tank types are resolved when looking up an adjacent {@link TransferNode}).
 */
public final class MachineTickSystem extends EntityTickingSystem<ChunkStore> {

    /**
     * Canonical direction-to-offset mapping, indexed by direction 0..5:
     * +X, -X, +Y, -Y, +Z, -Z. This IS the {@link Port#faceIndex()} order the transport engine looks
     * up via {@link NeighborView}. Aligning these indices to Hytale's real block face indices is a
     * later (Phase B wiring) task.
     */
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /** TEMP B6-remove: log roughly once per second. ~20 ticks/sec, so accumulate dt to ~1.0s. */
    private static final float LOG_INTERVAL_SECONDS = 1.0f;

    private final ComponentType<ChunkStore, MachineBlockState> machineType;
    private final ComponentType<ChunkStore, TankBlockState> tankType;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType;
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType;

    /** TEMP B6-remove: per-system accumulator gating the debug log. */
    private float logAccumulator;

    /** TEMP B6-remove: last logged readout per block position, so we print only on change. */
    private final java.util.Map<String, long[]> lastReadout = new java.util.HashMap<>();

    public MachineTickSystem(
            @Nonnull ComponentType<ChunkStore, MachineBlockState> machineType,
            @Nonnull ComponentType<ChunkStore, TankBlockState> tankType) {
        this.machineType = machineType;
        this.tankType = tankType;
        // Resolved here (not per-tick): these built-in component types are stable for the world's life.
        this.blockInfoType = BlockModule.BlockStateInfo.getComponentType();
        this.blockChunkType = BlockChunk.getComponentType();
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // Transport reads/writes neighbor components across archetype chunks; keep it single-threaded.
        return false;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return machineType;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        // 1. The ticked machine node.
        MachineBlockState node = archetypeChunk.getComponent(index, machineType);
        if (node == null) {
            return;
        }

        // 2. Resolve this block's world position via BlockStateInfo + BlockChunk. Guard every step.
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
        // BlockChunk x/z are chunk coords (32-wide columns): worldX = chunkX*32 + localX, etc.
        final int x = (blockChunk.getX() << 5) | ChunkUtil.xFromBlockInColumn(blockIndex);
        final int y = ChunkUtil.yFromBlockInColumn(blockIndex);
        final int z = (blockChunk.getZ() << 5) | ChunkUtil.zFromBlockInColumn(blockIndex);

        // World access for neighbor lookups. Guard: store/world could be unexpectedly absent.
        ChunkStore external = store.getExternalData();
        if (external == null) {
            return;
        }
        final World world = external.getWorld();
        if (world == null) {
            return;
        }

        // Neighbor view: resolve the adjacent TransferNode (machine OR tank) in a given direction.
        NeighborView view = direction -> neighborNode(world, store, x, y, z, direction);

        // (optional) Creative source: refill energy to full BEFORE pushing so it always has power.
        if (node.isCreativeSource()) {
            EnergyHandler energy = node.energy();
            if (energy != null) {
                energy.receiveEnergy(energy.getMaxStored(), false);
            }
        }

        // 3. TRANSPORT pass: push energy then resources to neighbors. Both are internally guarded.
        TransportEngine.pushEnergy(node, view);
        TransportEngine.pushResources(node, view);

        // 4. WORK pass.
        // TODO(recipes): no recipe system yet (Phase A WorkState exists but machines carry no recipe
        // definition until a later task). Once a machine knows its recipe duration + input check, call
        //   node.work().advance(dt, duration, hasInputs);
        // here. For this slice this is intentionally a no-op stub: do NOT invent a recipe system.

        // 5. TEMP B6-remove: visible activity for the smoke test, throttled to ~once per second.
        logAccumulator += dt;
        if (logAccumulator >= LOG_INTERVAL_SECONDS) {
            logAccumulator = 0.0f;
            logActivity(node, x, y, z);
        }
    }

    /**
     * Resolves the {@link TransferNode} adjacent to {@code (x,y,z)} in {@code direction}; null when
     * absent. Fully guarded: returns null on out-of-range direction, missing block entity, or a
     * neighbor that carries neither a machine nor a tank component.
     */
    private TransferNode neighborNode(
            @Nonnull World world, @Nonnull Store<ChunkStore> store, int x, int y, int z, int direction) {
        int[] pos = neighborPos(x, y, z, direction);
        if (pos == null) {
            return null;
        }
        // getBlockEntity already returns null for unloaded chunks and blocks without an entity.
        Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, pos[0], pos[1], pos[2]);
        if (ref == null || !ref.isValid()) {
            return null;
        }
        MachineBlockState machine = store.getComponent(ref, machineType);
        if (machine != null) {
            return machine;
        }
        TankBlockState tank = store.getComponent(ref, tankType);
        if (tank != null) {
            return tank;
        }
        return null;
    }

    /**
     * TEMP B6-remove: log the ticked machine's energy + per-channel resource amounts, but ONLY when
     * the readout changed since last interval (so steady/idle blocks stay quiet), with a (+/-) delta.
     */
    private void logActivity(@Nonnull MachineBlockState node, int x, int y, int z) {
        ChonbosChemistry plugin = ChonbosChemistry.getInstance();
        if (plugin == null) {
            return;
        }
        EnergyHandler energy = node.energy();
        ResourceBuffer fluid = node.resource(PortChannel.FLUID);
        ResourceBuffer gas = node.resource(PortChannel.GAS);
        ResourceBuffer item = node.resource(PortChannel.ITEM);
        long[] cur = {
            energy != null ? energy.getStored() : -1,
            fluid != null ? fluid.amount() : -1,
            gas != null ? gas.amount() : -1,
            item != null ? item.amount() : -1,
        };
        String key = x + "," + y + "," + z;
        long[] prev = lastReadout.get(key);
        if (prev != null && java.util.Arrays.equals(prev, cur)) {
            return; // unchanged since last interval — stay quiet
        }
        lastReadout.put(key, cur);

        StringBuilder sb = new StringBuilder("[CC] (")
            .append(x).append(',').append(y).append(',').append(z).append(')');
        appendChannel(sb, "energy", cur[0], energy != null ? energy.getMaxStored() : -1, prev != null ? prev[0] : -1);
        appendChannel(sb, "fluid", cur[1], fluid != null ? fluid.capacity() : -1, prev != null ? prev[1] : -1);
        appendChannel(sb, "gas", cur[2], gas != null ? gas.capacity() : -1, prev != null ? prev[2] : -1);
        appendChannel(sb, "item", cur[3], item != null ? item.capacity() : -1, prev != null ? prev[3] : -1);
        plugin.getLogger().atInfo().log(sb.toString());
    }

    /** TEMP B6-remove: append " label=cur/cap(+delta)" for a present channel (cur &lt; 0 = absent). */
    private static void appendChannel(@Nonnull StringBuilder sb, @Nonnull String label, long cur, long cap, long prev) {
        if (cur < 0) {
            return;
        }
        sb.append(' ').append(label).append('=').append(cur).append('/').append(cap);
        if (prev >= 0 && prev != cur) {
            long d = cur - prev;
            sb.append('(').append(d >= 0 ? "+" : "").append(d).append(')');
        }
    }

    // --- pure, ECS-free helpers (unit-tested in MachineTickSystemTest) ---

    /**
     * Applies the canonical {@link #OFFSETS} for {@code direction} to {@code (x,y,z)}.
     *
     * @return a new {@code int[]{nx,ny,nz}}, or null if {@code direction} is outside 0..5.
     */
    static int[] neighborPos(int x, int y, int z, int direction) {
        if (direction < 0 || direction >= OFFSETS.length) {
            return null;
        }
        int[] o = OFFSETS[direction];
        return new int[] {x + o[0], y + o[1], z + o[2]};
    }

    /** @return the number of canonical directions (6). */
    static int directionCount() {
        return OFFSETS.length;
    }
}
