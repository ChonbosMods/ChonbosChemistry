package com.chonbosmods.chemistry.impl.block.net.item;

import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.joml.Vector3d;

/**
 * The engine glue for discrete ITEM transport: the thin world adapter around {@link ItemTransferDriver}
 * (2026-06-06 item-channel design "Tick driver" + "Spike findings (Task 7)"). It is NOT an
 * {@code EntityTickingSystem}: it is invoked FROM {@code NetworkTickSystem} once per ITEM network per
 * tick, in the slot where fungible channels run {@code NetworkTransfer.distribute}. Reusing that system's
 * existing per-network dedup, per-tick grid construction, and member iteration avoids duplicating all of
 * that scaffolding in a second ticking system (see {@link ItemTransferDriver} class javadoc for the full
 * structure rationale).
 *
 * <p>This class supplies the two world seams the headless driver needs:
 * <ul>
 *   <li>{@link ItemTransferDriver.DropSink}: materializes an engine {@code ItemStack} from a
 *       {@link TravelingStack} (id + count + opaque metadata) and spawns a ground drop at the pipe's
 *       position, using the same {@code ItemComponent.generateItemDrops} primitive as
 *       {@code CarryBreakEventSystem.spawnDrop}.
 *   <li>{@link ItemTransferDriver.SaveMarker}: marks the affected pipe/container chunk needing-save via
 *       {@code BlockComponentChunk.markNeedsSaving} (the {@code WrenchInteraction.markNeedsSaving} pattern),
 *       so the moved in-transit stacks re-persist across a place/break/unload between ticks.
 * </ul>
 *
 * <p>Stateless and reusable across worlds: {@code NetworkTickSystem} holds ONE instance and calls
 * {@link #tickNetwork} per ITEM network, passing the world + grid + containers it already built. Fully
 * defensive: the drop/save seams never throw (the caller ticks on the world thread).
 */
public final class ItemTransferSystem {

    /** [TUNE] travel ticks per pipe segment (design "[TUNE]": ~5 ticks/segment). */
    static final int SPEED_TICKS = 5;
    /** [TUNE] ticks between PULL extraction attempts (design "[TUNE]": ~20). */
    static final int PULL_INTERVAL_TICKS = 20;
    /** [TUNE] per-pull item cap (design "[TUNE]": ~16 items). */
    static final int PULL_CAP = 16;

    private final ItemTransferDriver driver =
        new ItemTransferDriver(SPEED_TICKS, PULL_INTERVAL_TICKS, PULL_CAP);

    /**
     * Run one ITEM transport pass for {@code net}. Called from {@code NetworkTickSystem} for ITEM-channel
     * networks instead of {@code NetworkTransfer.distribute}. The endpoints are collected by the caller via
     * {@link ItemEndpoints#collect} so the item subsystem shares the same discovery the visual passes use.
     *
     * @param net        the ITEM network (the driver no-ops on non-ITEM, but the caller already filters).
     * @param world      the live world (drop spawning + chunk dirty marking).
     * @param containers the caller's container lookup (the SAME instance the endpoint collection and the
     *                   container-aware visual masks use: one adapter per ITEM network per tick).
     * @param grid       the per-tick pipe grid view.
     * @param endpoints  the network's collected item endpoints (destinations + PULL sources).
     */
    public void tickNetwork(
            @Nonnull Network net,
            @Nonnull World world,
            @Nonnull WorldContainerLookup containers,
            @Nonnull PipeGridView grid,
            @Nonnull Endpoints endpoints) {
        ItemTransferDriver.DropSink dropSink = (pipeKey, stack) -> spawnDrop(world, pipeKey, stack);
        ItemTransferDriver.SaveMarker saveMarker = pipeKey -> markNeedsSaving(world, pipeKey);
        driver.tickNetwork(
            net, grid, containers, endpoints, FilterLookup.NONE, world.getTick(), dropSink, saveMarker);
    }

    /**
     * Materialize the traveling stack as an engine {@link ItemStack} (id + count + opaque metadata) and
     * spawn it as a ground drop at the block centre of {@code pipeKey}. Mirrors
     * {@code CarryBreakEventSystem.spawnDrop}. Never throws.
     */
    private static void spawnDrop(@Nonnull World world, long pipeKey, @Nonnull TravelingStack stack) {
        try {
            if (stack.count() <= 0 || stack.id() == null || stack.id().isEmpty()) {
                return; // nothing meaningful to drop
            }
            ItemStack drop = new ItemStack(stack.id(), stack.count());
            BsonDocument metadata = stack.metadata();
            if (metadata != null) {
                drop = drop.withMetadata(metadata.clone());
            }
            int x = NetworkManager.unpackX(pipeKey);
            int y = NetworkManager.unpackY(pipeKey);
            int z = NetworkManager.unpackZ(pipeKey);
            Store<EntityStore> entityStore = world.getEntityStore().getStore();
            Vector3d position = new Vector3d(x + 0.5, y + 0.5, z + 0.5);
            Holder<EntityStore>[] holders = ItemComponent.generateItemDrops(
                entityStore, List.of(drop), position, Rotation3f.IDENTITY);
            entityStore.addEntities(holders, AddReason.SPAWN);
        } catch (Throwable ignored) {
            // A dropped stack failing must never crash the tick (it is preferable to a thrown exception).
        }
    }

    /** Marks the block-component chunk owning {@code pipeKey} needing-save (WrenchInteraction pattern). */
    private static void markNeedsSaving(@Nonnull World world, long pipeKey) {
        try {
            int x = NetworkManager.unpackX(pipeKey);
            int y = NetworkManager.unpackY(pipeKey);
            int z = NetworkManager.unpackZ(pipeKey);
            if (y < ChunkUtil.MIN_Y || y >= ChunkUtil.HEIGHT) {
                return;
            }
            long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
            BlockComponentChunk blockComponents =
                world.getChunkStore().getChunkComponent(chunkIndex, BlockComponentChunk.getComponentType());
            if (blockComponents != null) {
                blockComponents.markNeedsSaving();
            }
        } catch (Throwable ignored) {
            // Persistence marking must never crash the tick.
        }
    }
}
