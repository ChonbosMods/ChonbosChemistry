package com.chonbosmods.chemistry.impl.block.net;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkUnloadEvent;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;

/**
 * Drops cached pipe {@link Network}s whose members lie in an unloading chunk (H3). When a chunk leaves
 * memory its pipe block entities are gone from the live grid, so any cached network touching that chunk
 * column must rebuild lazily next time it is accessed.
 *
 * <p>{@link ChunkUnloadEvent} is invoked on the {@link ChunkStore} (see the engine's
 * {@code ChunkUnloadingSystem}, which calls {@code commandBuffer.invoke(chunkRef, event)} with a
 * {@code Ref<ChunkStore>}), so this is registered via
 * {@code getChunkStoreRegistry().registerSystem(...)} as an
 * {@link EntityEventSystem}&lt;{@link ChunkStore}, {@link ChunkUnloadEvent}&gt;. Query is
 * {@link Archetype#empty()}.
 *
 * <p>{@link WorldChunk#getX()}/{@link WorldChunk#getZ()} are chunk (column) coordinates, matching the
 * {@code chunkX = blockX >> 5} convention {@link NetworkManager#invalidateChunk} uses. The world is
 * taken from the chunk itself ({@link WorldChunk#getWorld()}), so the correct per-world manager is
 * targeted even with multiple worlds. Logic lives in {@link NetworkManager#invalidateChunk}.
 */
public final class PipeChunkUnloadEventSystem extends EntityEventSystem<ChunkStore, ChunkUnloadEvent> {

    private final NetworkService networkService;

    public PipeChunkUnloadEventSystem(@Nonnull NetworkService networkService) {
        super(ChunkUnloadEvent.class);
        this.networkService = networkService;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> commandBuffer,
            @Nonnull ChunkUnloadEvent event) {
        WorldChunk chunk = event.getChunk();
        if (chunk == null) {
            return;
        }
        World world = chunk.getWorld();
        if (world == null) {
            return;
        }
        // TODO(H6): persist buffer shares before drop (handled inside invalidateChunk).
        networkService.forWorld(world).invalidateChunk(chunk.getX(), chunk.getZ());
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return Archetype.empty();
    }
}
