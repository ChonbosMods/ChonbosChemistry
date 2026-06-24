package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The shared "drive-context resolution" prologue every machine tick runs: from an ECS component index,
 * resolve the block-entity ref / BlockStateInfo / chunk / world block coords / World / BlockType, with each
 * step guarded. Used by both the processing-machine tick and the crafting-machine tick so the (previously
 * copy-pasted) prologue lives in one place. The visual-state swap is NOT here (it differs per machine family).
 */
public final class MachineDriveContext {
    private MachineDriveContext() {}

    /** The resolved block context: the world, the block's world coords, and its BlockType. */
    public record Resolved(World world, int x, int y, int z, BlockType blockType) {}

    /** Resolve the live block context for the component at {@code index} in {@code chunk}. Returns null when
     *  any piece is missing (chunk unloaded, block gone, world absent, etc.): the caller then skips the tick.
     *  NEVER throws. */
    @Nullable
    public static Resolved resolve(int index, @Nonnull ArchetypeChunk<ChunkStore> chunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType,
            @Nonnull ComponentType<ChunkStore, BlockChunk> blockChunkType) {
        // Resolve the live drive context off the same block-entity ref + sibling components. Mirrors the
        // proven BenchSpikeCommand / NetworkTickSystem resolution; guard every step.
        Ref<ChunkStore> blockRef = chunk.getReferenceTo(index);
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }

        BlockModule.BlockStateInfo stateInfo = chunk.getComponent(index, blockInfoType);
        if (stateInfo == null) {
            return null;
        }
        Ref<ChunkStore> chunkRef = stateInfo.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) {
            return null;
        }
        BlockChunk blockChunk = chunkRef.getStore().getComponent(chunkRef, blockChunkType);
        if (blockChunk == null) {
            return null;
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
            return null;
        }
        World world = external.getWorld();
        if (world == null) {
            return null;
        }

        BlockType blockType = resolveBlockType(blockChunk, localX, localY, localZ);
        if (blockType == null) {
            return null;
        }

        return new Resolved(world, x, y, z, blockType);
    }

    /** Section block id at the local coords -> asset BlockType. Null if section/id unresolved. (Moved verbatim
     *  from ForgeTickSystem.resolveBlockType; MachineTickSystem has an identical one it will adopt next.) */
    @Nullable
    private static BlockType resolveBlockType(@Nonnull BlockChunk blockChunk, int localX, int localY, int localZ) {
        BlockSection section = blockChunk.getSectionAtBlockY(localY);
        if (section == null) {
            return null;
        }
        int blockId = section.get(localX, localY, localZ);
        return BlockType.getAssetMap().getAsset(blockId);
    }
}
