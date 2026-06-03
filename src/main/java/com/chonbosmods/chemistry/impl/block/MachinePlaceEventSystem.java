package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.joml.Vector3i;

/**
 * Place-side glue (H7): when a player places an item carrying {@link MachineEnergyMetadata#KEY} stored
 * energy, that energy is written into the freshly created {@link MachineBlockState} block entity so a
 * machine broken with charge ({@link MachineBreakEventSystem}) is placed back already charged.
 *
 * <h2>Timing (verified against the decompiled 0.5.3 jar)</h2>
 * {@link PlaceBlockEvent} fires BEFORE the engine sets the block ({@code BlockPlaceUtils.placeBlock}
 * invokes the event, then {@code tryPlaceBlock} sets it). So at event time the block entity does not yet
 * exist. We therefore snapshot the stored amount off {@link PlaceBlockEvent#getItemInHand()} NOW (the
 * stack is consumed/moved by the engine afterward) and defer the actual restore with
 * {@code world.execute(...)} so it runs after placement, on the WorldThread (the same deferral HyProTech
 * uses). The deferred step reads the now-existing {@link MachineBlockState} and applies the energy.
 *
 * <p><b>Thin by design.</b> The encode/decode + clamp logic lives in {@link MachineEnergyMetadata}
 * (unit-tested); this system is engine glue verified in-game. Defensive: missing metadata, a non-machine
 * placed block, or an unloaded chunk all reduce to normal placement; the handler never throws.
 */
public final class MachinePlaceEventSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    private final ComponentType<ChunkStore, MachineBlockState> machineType;

    public MachinePlaceEventSystem(@Nonnull ComponentType<ChunkStore, MachineBlockState> machineType) {
        super(PlaceBlockEvent.class);
        this.machineType = machineType;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull PlaceBlockEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            ItemStack inHand = event.getItemInHand();
            if (inHand == null) {
                return;
            }
            // Snapshot the stored amount NOW: the engine moves/consumes the in-hand stack after this.
            BsonDocument metadata = inHand.getMetadata();
            Long stored = MachineEnergyMetadata.readStoredEnergy(metadata);
            if (stored == null || stored <= 0L) {
                return; // normal placement.
            }
            Vector3i pos = event.getTargetBlock();
            if (pos == null) {
                return;
            }
            EntityStore external = store.getExternalData();
            World world = external == null ? null : external.getWorld();
            if (world == null) {
                return;
            }

            int x = pos.x();
            int y = pos.y();
            int z = pos.z();
            long amount = stored;
            // Defer until after the engine has set the block + created its entity (see class javadoc).
            world.execute(() -> {
                try {
                    MachineBlockState machine =
                            BlockModule.getComponent(machineType, world, x, y, z);
                    if (machine == null) {
                        return; // placed something that isn't a machine, or chunk gone -> ignore.
                    }
                    MachineEnergyMetadata.applyStoredEnergy(machine.energy(), amount);
                } catch (Throwable ignored) {
                    // Never let a deferred restore crash the world thread.
                }
            });
        } catch (Throwable ignored) {
            // Defensive: a place handler must never throw.
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
