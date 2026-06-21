package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.impl.block.bench.VanillaBenchBridge;
import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-tick driver for machine block entities (Task B2). Each tick, for every live
 * {@link MachineBlockState} on the {@link ChunkStore}, it runs:
 *
 * <ol>
 *   <li>(optional) creative-source refill: if the node is flagged a creative power source, its energy
 *       buffer is topped up to full so it always has power to feed the network;</li>
 *   <li>(optional) burning-sink drain: a test-rig machine that burns its own buffer each tick;</li>
 *   <li>a WORK pass: if the node holds a vanilla bench, drive that bench by the energy it can afford
 *       this tick (Task 8). See {@link #driveBench}.</li>
 * </ol>
 *
 * <h2>Energy-gated bench drive (Task 8)</h2>
 * For each machine that holds a {@link ProcessingBenchBlock}, the WORK pass:
 * <ol>
 *   <li>computes the simulated {@code dt} it can afford from its {@link EnergyBuffer} via
 *       {@link SmelterEnergy#affordableDt} (capped at real-time: v1 has no overclock);</li>
 *   <li>ensures the held bench's transient slot listeners are wired to the live world (one-time
 *       re-wire after load/placement via {@link VanillaBenchBridge#wireSlots});</li>
 *   <li>drives the bench one step via {@link VanillaBenchBridge#advance};</li>
 *   <li>drains the energy actually consumed for that {@code dt} via {@link SmelterEnergy#drainFor}.</li>
 * </ol>
 * No energy or no input means {@code dt == 0} / the bench advance does nothing: it freezes with its
 * progress retained.
 *
 * <h2>Defensive contract</h2>
 * This runs every server tick on hot ECS data. It MUST NEVER throw on a missing component, chunk, or
 * world: every lookup is null/validity guarded and skipped on failure. It also never calls
 * {@code clone()} (it mutates the live component in place).
 *
 * <h2>Query scope</h2>
 * The query matches the machine component type only. Tanks ({@link TankBlockState}) are passive and are
 * not ticked here. Resource transport between blocks is done by {@code NetworkTickSystem} over pipe
 * networks, not by this system.
 */
public final class MachineTickSystem extends EntityTickingSystem<ChunkStore> {

    /**
     * [TUNE] Per-second energy draw of a running bench machine (v1 smelter). Placeholder until per-
     * recipe / per-machine costs land: a single flat draw gates every bench. Passed to
     * {@link SmelterEnergy#affordableDt}/{@link SmelterEnergy#drainFor}.
     */
    private static final long SMELTER_DRAW = 200L;

    private final ComponentType<ChunkStore, MachineBlockState> machineType;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType;
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType;

    public MachineTickSystem(
            @Nonnull ComponentType<ChunkStore, MachineBlockState> machineType) {
        this.machineType = machineType;
        this.blockInfoType = BlockModule.BlockStateInfo.getComponentType();
        this.blockChunkType = BlockChunk.getComponentType();
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // Energy handlers are mutated in place on the live component; keep it single-threaded.
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

        // (optional) Creative source: refill energy to full each tick so it always has power to feed
        // the network (the NetworkTickSystem pulls from it via its OUTPUT ports).
        if (node.isCreativeSource()) {
            EnergyHandler energy = node.energy();
            if (energy != null) {
                energy.receiveEnergy(energy.getMaxStored(), false);
            }
        }

        // (optional) Burning sink: a machine that consumes its own buffered energy each tick. Uses the
        // INTERNAL extract (the machine burning its own buffer, not an external port transfer), so the
        // sink's stored value drops after the network fills it.
        long drain = node.energyDrainPerTick();
        if (drain > 0) {
            EnergyHandler energy = node.energy();
            if (energy != null) {
                energy.extractEnergyInternal(drain, false);
            }
        }

        // 3. WORK pass (Task 8): energy-gated vanilla-bench drive. No-op for non-bench machines.
        // DEFENSIVE: a bench error must NEVER kill the WorldThread (a setupSlots NPE did, 2026-06-21:
        // the whole world became unavailable). Catch every throwable, log each distinct one once, and
        // skip this machine's drive for the tick. The class contract (this MUST NEVER throw) is now enforced.
        try {
            driveBench(node, dt, index, archetypeChunk, store);
        } catch (Throwable t) {
            String msg = String.valueOf(t);
            if (BENCH_DRIVE_SEEN.add(msg)) {
                BENCH_DRIVE_LOG.atWarning().log("CC machine bench drive failed (skipped, world tick protected): " + msg);
            }
        }
    }

    /** Guards the per-tick bench drive: a failure logs once (per distinct error) and never crashes the tick. */
    private static final HytaleLogger BENCH_DRIVE_LOG = HytaleLogger.forEnclosingClass();
    private static final java.util.Set<String> BENCH_DRIVE_SEEN =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Drive this node's held vanilla bench by the energy it can afford this tick (Task 8). A no-op for
     * a machine that holds no bench, or whenever any piece of the live drive context (ref, world, block
     * coords, block type) cannot be resolved. NEVER throws: every lookup is guarded and the node is
     * skipped on the first missing piece (defensive contract).
     *
     * @param dt the engine's real per-tick delta in seconds (the {@code realDt} the energy gate caps to)
     */
    private void driveBench(
            @Nonnull MachineBlockState node,
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store) {
        // The energy buffer gates the drive: no buffer means no power gate -> nothing to spend, skip.
        EnergyHandler energyHandler = node.energy();
        if (!(energyHandler instanceof EnergyBuffer energy)) {
            return;
        }

        // Resolve the live drive context off the same block-entity ref + sibling components. Mirrors the
        // proven BenchSpikeCommand / NetworkTickSystem resolution; guard every step.
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

        // Only a bench-configured block drives here (the smelter declares a Processing Bench). A machine
        // without a Bench config is not a bench machine: nothing to create or advance.
        if (blockType.getBench() == null) {
            return;
        }

        BenchBlock benchBlock = node.heldBenchBlock();
        ProcessingBenchBlock bench = node.heldBench();
        if (bench == null) {
            // Task 12 (held-bench init): the engine does NOT attach a vanilla bench to our DrawType:Model
            // smelter (verified in-game: no sibling ProcessingBenchBlock/BenchBlock components), so we
            // create + HOLD our own on the first tick after placement. create() runs initializeBenchConfig
            // + setupSlots, so the bench is fully wired here (no separate wireSlots needed this tick).
            if (benchBlock == null) {
                benchBlock = new BenchBlock();
            }
            bench = VanillaBenchBridge.create(
                world, benchBlock, stateInfo, x, y, z, blockType, benchBlock.getTierLevel());
            if (bench == null) {
                return; // creation failed: skip defensively, retry next tick
            }
            node.setHeldBench(bench);
            node.setHeldBenchBlock(benchBlock);
            node.setBenchWired(true); // create() already ran setupSlots against the live world
        }
        int tier = benchBlock != null ? benchBlock.getTierLevel() : 0;

        Store<EntityStore> entityStore = world.getEntityStore().getStore();
        if (entityStore == null) {
            return;
        }

        // Re-wire the bench's transient slot listeners against the live world exactly once after this
        // load/placement. The codec round-trip carries the slot CONTENTS + progress but drops the
        // in-memory listener hookup, so re-attach it before the first advance (does NOT discard decoded
        // contents: wireSlots calls setupSlots only, not initializeBenchConfig).
        // TODO(devServer): verify setupSlots preserves decoded contents (Phase 6 smoke test).
        if (!node.isBenchWired()) {
            VanillaBenchBridge.wireSlots(bench, world, benchBlock, stateInfo, x, y, z, blockType, tier);
            node.setBenchWired(true);
        }

        // On/Off gate (panel toggle / circuit run/halt line): a disabled machine HOLDS — no processing and
        // no power consumption this tick, but its loaded input and buffered power are retained (the bench
        // was still created/wired above, so the panel shows contents and power intake still fills it).
        // Energy gate: how much simulated time we can afford this tick (v1: capped at real dt, no
        // overclock). Zero when disabled or the buffer is empty -> bench freezes (advance does nothing).
        double affordable =
            node.isEnabled() ? SmelterEnergy.affordableDt(energy.getStored(), SMELTER_DRAW, dt) : 0.0;
        if (affordable > 0) {
            VanillaBenchBridge.advance(
                bench, (float) affordable, entityStore, benchBlock, stateInfo, x, y, z, blockType, tier);
            long drained = SmelterEnergy.drainFor(affordable, SMELTER_DRAW);
            if (drained > 0) {
                energy.extractEnergyInternal(drained, false);
            }
        }
    }

    /**
     * The {@link BlockType} at the bench, resolved like the vanilla {@code ProcessingBenchTick}
     * (section block id at the local coords -> asset map). Null if the section or id can't be resolved.
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
}
