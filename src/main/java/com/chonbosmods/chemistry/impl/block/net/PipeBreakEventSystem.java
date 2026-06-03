package com.chonbosmods.chemistry.impl.block.net;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import org.joml.Vector3i;

/**
 * Invalidates cached pipe {@link Network}s when a block is broken (H3). A break can split one network
 * into two, so the cache for the changed position AND its 6 face-neighbours is dropped, forcing a
 * correct lazy rebuild via {@link NetworkManager#getOrBuildNetwork}.
 *
 * <p>Thin by design: all logic lives in {@link NetworkManager}
 * ({@link NetworkManager#selfAndNeighbors}, {@link NetworkManager#invalidate}), unit-tested headlessly.
 * No pipe-type check: invalidating a non-pipe position is a safe no-op.
 *
 * <p>Registered via {@code getEntityStoreRegistry().registerSystem(...)} (break fires on the breaking
 * entity's {@link EntityStore}); query is {@link Archetype#empty()}.
 */
public final class PipeBreakEventSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private final NetworkService networkService;

    public PipeBreakEventSystem(@Nonnull NetworkService networkService) {
        super(BreakBlockEvent.class);
        this.networkService = networkService;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreakBlockEvent event) {
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
