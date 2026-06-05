package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;

/**
 * Per-tick driver for machine block entities (Task B2). Each tick, for every live
 * {@link MachineBlockState} on the {@link ChunkStore}, it runs:
 *
 * <ol>
 *   <li>(optional) creative-source refill: if the node is flagged a creative power source, its energy
 *       buffer is topped up to full so it always has power to feed the network;</li>
 *   <li>a WORK pass: advance the node's {@link WorkState} (currently a stub: see below).</li>
 * </ol>
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

    private final ComponentType<ChunkStore, MachineBlockState> machineType;

    public MachineTickSystem(
            @Nonnull ComponentType<ChunkStore, MachineBlockState> machineType) {
        this.machineType = machineType;
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

        // 3. WORK pass.
        // TODO(recipes): no recipe system yet (Phase A WorkState exists but machines carry no recipe
        // definition until a later task). Once a machine knows its recipe duration + input check, call
        //   node.work().advance(dt, duration, hasInputs);
        // here. For this slice this is intentionally a no-op stub: do NOT invent a recipe system.
    }
}
