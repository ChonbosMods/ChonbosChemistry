package com.chonbosmods.chemistry.impl.block.net;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.joml.Vector3i;

/**
 * Invalidates cached pipe {@link Network}s when a block is placed (H3). A place can merge two
 * same-channel networks that the new block now bridges, so the cache for the changed position AND its
 * 6 face-neighbours is dropped, forcing a correct lazy rebuild via
 * {@link NetworkManager#getOrBuildNetwork}.
 *
 * <p>This system is deliberately thin: all real logic lives in {@link NetworkManager}
 * ({@link NetworkManager#selfAndNeighbors}, {@link NetworkManager#invalidate}) and is unit-tested
 * headlessly. We do not check whether the placed block is actually a pipe: invalidating a non-pipe
 * position is a safe no-op (invalidate does nothing when the position is uncached).
 *
 * <p>Place/break fire on the {@link EntityStore} (the placing/breaking entity), so this is registered
 * via {@code getEntityStoreRegistry().registerSystem(...)}. The query is {@link Archetype#empty()}:
 * we react to every place event regardless of entity archetype.
 */
public final class PipePlaceEventSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    private final NetworkService networkService;

    public PipePlaceEventSystem(@Nonnull NetworkService networkService) {
        super(PlaceBlockEvent.class);
        this.networkService = networkService;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull PlaceBlockEvent event) {
        Vector3i pos = event.getTargetBlock();
        if (pos == null) {
            return;
        }
        World world = Universe.get().getDefaultWorld();
        if (world == null) {
            return;
        }
        NetworkManager manager = networkService.forWorld(world);
        for (int[] p : NetworkManager.selfAndNeighbors(pos.x(), pos.y(), pos.z())) {
            manager.invalidate(p[0], p[1], p[2]);
        }
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
