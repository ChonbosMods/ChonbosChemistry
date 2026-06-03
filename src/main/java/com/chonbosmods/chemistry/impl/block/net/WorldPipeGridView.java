package com.chonbosmods.chemistry.impl.block.net;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;

/**
 * Live {@link PipeGridView} over a {@link World}: resolves the {@link PipeNode} block entity at a
 * position via {@link BlockModule#getBlockEntity}. Thin glue (no logic beyond lookup); verified on
 * devServer, not unit-tested.
 *
 * <p>Defensive like {@code MachineTickSystem}: returns null for unloaded chunks, blocks without an
 * entity, or blocks whose entity carries no {@link PipeNode}.
 */
public final class WorldPipeGridView implements PipeGridView {

    private final World world;
    private final Store<ChunkStore> store;
    private final ComponentType<ChunkStore, PipeNode> pipeType;

    public WorldPipeGridView(
            @Nonnull World world,
            @Nonnull Store<ChunkStore> store,
            @Nonnull ComponentType<ChunkStore, PipeNode> pipeType) {
        this.world = world;
        this.store = store;
        this.pipeType = pipeType;
    }

    @Override
    public PipeNode pipeAt(int x, int y, int z) {
        Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, x, y, z);
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return store.getComponent(ref, pipeType);
    }
}
