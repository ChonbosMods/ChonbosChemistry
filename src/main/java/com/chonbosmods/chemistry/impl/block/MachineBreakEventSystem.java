package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import javax.annotation.Nonnull;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Break-side glue (H7): when a player breaks a machine block whose {@link MachineBlockState} carries
 * stored energy, the dropped item is stamped with that energy under {@link MachineEnergyMetadata#KEY}
 * so {@link MachinePlaceEventSystem} can rehydrate it on placement.
 *
 * <h2>Mechanism (copied from the HyProTech reference)</h2>
 * {@link BreakBlockEvent} fires BEFORE the engine removes the block (verified in the decompiled 0.5.3
 * {@code BlockHarvestUtils.performBlockBreak}: the event is invoked, then {@code naturallyRemoveBlock}
 * spawns the vanilla drop). Because the engine's own drop is a plain stack we cannot stamp, we follow
 * HyProTech: read the live block entity at break time, and if it holds positive stored energy, CANCEL
 * the event and perform our own break on the next world tick: clear the block and spawn a single custom
 * drop carrying the energy metadata. An empty machine (or any non-machine block) is left entirely to the
 * engine: we do not cancel, so vanilla drops/sounds/particles are untouched.
 *
 * <p>The deferred {@code world.execute(...)} runs the setBlock + drop on the WorldThread (same timing
 * trick HyProTech uses), guaranteeing chunk/section access is thread-safe.
 *
 * <p><b>Thin by design.</b> All testable logic lives in {@link MachineEnergyMetadata} (unit-tested);
 * this system is engine glue verified in-game. Defensive: it never throws (missing world, missing
 * block entity, or no energy all fall through to vanilla behaviour).
 */
public final class MachineBreakEventSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private final ComponentType<ChunkStore, MachineBlockState> machineType;

    public MachineBreakEventSystem(@Nonnull ComponentType<ChunkStore, MachineBlockState> machineType) {
        super(BreakBlockEvent.class);
        this.machineType = machineType;
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreakBlockEvent event) {
        try {
            if (event.isCancelled()) {
                return;
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

            // Read the live machine block entity (still present: break fires pre-removal).
            MachineBlockState machine = BlockModule.getComponent(machineType, world, pos.x(), pos.y(), pos.z());
            if (machine == null) {
                return; // not a machine block -> leave to vanilla.
            }
            EnergyHandler energy = machine.energy();
            if (energy == null || energy.getStored() <= 0L) {
                return; // no charge worth preserving -> leave to vanilla.
            }
            long stored = energy.getStored();

            // Resolve the item this block drops as (its own item form). Without one we cannot carry the
            // metadata, so bail to vanilla rather than dropping nothing.
            BlockType blockType = event.getBlockType();
            String dropItemId = resolveDropItemId(blockType);
            if (dropItemId == null) {
                return;
            }

            // We will replace the vanilla break: cancel, then do our own break + custom drop next tick.
            event.setCancelled(true);
            int x = pos.x();
            int y = pos.y();
            int z = pos.z();
            world.execute(() -> {
                try {
                    long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
                    BlockAccessor accessor = world.getChunkIfLoaded(chunkIndex);
                    if (accessor == null) {
                        return;
                    }
                    // Confirm the block is still our machine before clearing (defensive against races).
                    BlockType actual = accessor.getBlockType(x, y, z);
                    if (actual == null || actual == BlockType.EMPTY) {
                        return;
                    }
                    accessor.setBlock(x, y, z, BlockType.EMPTY);

                    ItemStack drop = MachineEnergyMetadata.writeStoredEnergy(new ItemStack(dropItemId, 1), stored);
                    spawnDrop(world, drop, x, y, z);
                } catch (Throwable ignored) {
                    // Never let a break handler crash the world thread.
                }
            });
        } catch (Throwable ignored) {
            // Defensive: a break handler must never throw.
        }
    }

    /** The block's own item form (what it drops by default), or null if it has none. */
    private static String resolveDropItemId(BlockType blockType) {
        if (blockType == null) {
            return null;
        }
        Item item = blockType.getItem();
        if (item != null && item.getId() != null && !item.getId().isEmpty()) {
            return item.getId();
        }
        String id = blockType.getId();
        return id == null || id.isEmpty() ? null : id;
    }

    private static void spawnDrop(World world, ItemStack stack, int x, int y, int z) {
        if (stack == null) {
            return;
        }
        Store<EntityStore> store = world.getEntityStore().getStore();
        Vector3d position = new Vector3d(x + 0.5, y + 0.5, z + 0.5);
        Holder<EntityStore>[] holders = ItemComponent.generateItemDrops(
                store, List.of(stack), position, Rotation3f.IDENTITY);
        store.addEntities(holders, AddReason.SPAWN);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
