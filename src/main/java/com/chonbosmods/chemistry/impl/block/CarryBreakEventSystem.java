package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.codec.EmptyExtraInfo;
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
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * Break-side carry glue (H7, rebuilt on the native BlockHolder path 2026-06-05): when a player breaks
 * a machine or tank with preservable contents ({@link BlockHolderCarry#shouldCarry}), the dropped
 * item is stamped with the block entity's FULL encoded {@code Holder<ChunkStore>} under the engine's
 * {@code "BlockHolder"} metadata key. Placement needs no mod code at all: the engine's
 * {@code BlockPlaceUtils.onPlaceBlockSuccess} natively decodes that document and restores every
 * component (energy, resource buffers + type-locks, work progress) via {@code WorldChunk.setState}.
 * This supersedes the CC_StoredEnergy single-field carry and its place-side rehydration system.
 *
 * <h2>Mechanism (HyProTech-style cancel + custom drop)</h2>
 * {@link BreakBlockEvent} fires BEFORE the engine removes the block (verified in the decompiled 0.5.3
 * {@code BlockHarvestUtils.performBlockBreak}). Because the engine's own drop is a plain stack we
 * cannot stamp, we CANCEL the event when contents are worth carrying and perform our own break on the
 * next world tick: capture + encode the live block entity holder, clear the block (mirroring the
 * vanilla connected-block neighbour pass, H8b), and spawn a single stamped drop. An empty machine/tank
 * (or any other block) is left entirely to the engine: vanilla drops/sounds/particles untouched.
 *
 * <p><b>Thin by design.</b> The stamping seam and carry predicates live in {@link BlockHolderCarry}
 * (unit-tested); this system is engine glue verified in-game. Defensive: it never throws, and an
 * encode failure degrades to a plain (uncharged) drop rather than no drop.
 */
public final class CarryBreakEventSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    private final ComponentType<ChunkStore, MachineBlockState> machineType;
    private final ComponentType<ChunkStore, TankBlockState> tankType;
    private final ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.ForgeCraftState> forgeType;
    private final ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.CookerState> cookerType;
    private final ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.OutfitterState> outfitterType;
    private final ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.AlembicState> alembicType;
    private final ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.AssemblerState> assemblerType;
    private final ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.CultivatorState> cultivatorType;
    private final ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.SculptorState> sculptorType;
    private final ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.net.PipeNode> pipeType;
    private final com.chonbosmods.chemistry.impl.block.net.NetworkService networkService;

    public CarryBreakEventSystem(
            @Nonnull ComponentType<ChunkStore, MachineBlockState> machineType,
            @Nonnull ComponentType<ChunkStore, TankBlockState> tankType,
            @Nonnull ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.ForgeCraftState> forgeType,
            @Nonnull ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.CookerState> cookerType,
            @Nonnull ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.OutfitterState> outfitterType,
            @Nonnull ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.AlembicState> alembicType,
            @Nonnull ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.AssemblerState> assemblerType,
            @Nonnull ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.CultivatorState> cultivatorType,
            @Nonnull ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.craft.SculptorState> sculptorType,
            @Nonnull ComponentType<ChunkStore, com.chonbosmods.chemistry.impl.block.net.PipeNode> pipeType,
            @Nonnull com.chonbosmods.chemistry.impl.block.net.NetworkService networkService) {
        super(BreakBlockEvent.class);
        this.machineType = machineType;
        this.tankType = tankType;
        this.forgeType = forgeType;
        this.cookerType = cookerType;
        this.outfitterType = outfitterType;
        this.alembicType = alembicType;
        this.assemblerType = assemblerType;
        this.cultivatorType = cultivatorType;
        this.sculptorType = sculptorType;
        this.pipeType = pipeType;
        this.networkService = networkService;
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

            // Read the live block entity (still present: break fires pre-removal). Carry only when
            // there are contents worth preserving; everything else stays fully vanilla.
            MachineBlockState machine = BlockModule.getComponent(machineType, world, pos.x(), pos.y(), pos.z());
            boolean carry = BlockHolderCarry.shouldCarry(machine);
            if (!carry) {
                TankBlockState tank = BlockModule.getComponent(tankType, world, pos.x(), pos.y(), pos.z());
                carry = BlockHolderCarry.shouldCarry(tank);
            }
            if (!carry) {
                // A Forge broken mid-craft holds REAL player items (pulled from chests) in its held
                // container; carry them (plus output + energy + card) rather than destroying them.
                com.chonbosmods.chemistry.impl.block.craft.ForgeCraftState forge =
                    BlockModule.getComponent(forgeType, world, pos.x(), pos.y(), pos.z());
                carry = BlockHolderCarry.shouldCarry(forge);
            }
            if (!carry) {
                // A Cooker broken mid-craft likewise holds REAL player items (pulled from chests) in its
                // held container; carry them (plus output + energy + card) rather than destroying them.
                com.chonbosmods.chemistry.impl.block.craft.CookerState cooker =
                    BlockModule.getComponent(cookerType, world, pos.x(), pos.y(), pos.z());
                carry = BlockHolderCarry.shouldCarry(cooker);
            }
            if (!carry) {
                // An Outfitter broken mid-craft likewise holds REAL player items (pulled from chests) in its
                // held container; carry them (plus output + energy + card) rather than destroying them.
                com.chonbosmods.chemistry.impl.block.craft.OutfitterState outfitter =
                    BlockModule.getComponent(outfitterType, world, pos.x(), pos.y(), pos.z());
                carry = BlockHolderCarry.shouldCarry(outfitter);
            }
            if (!carry) {
                // An Alembic broken mid-craft likewise holds REAL player items (pulled from chests) in its
                // held container; carry them (plus output + energy + card) rather than destroying them.
                com.chonbosmods.chemistry.impl.block.craft.AlembicState alembic =
                    BlockModule.getComponent(alembicType, world, pos.x(), pos.y(), pos.z());
                carry = BlockHolderCarry.shouldCarry(alembic);
            }
            if (!carry) {
                // An Assembler broken mid-craft likewise holds REAL player items (pulled from chests) in its
                // held container; carry them (plus output + energy + card) rather than destroying them.
                com.chonbosmods.chemistry.impl.block.craft.AssemblerState assembler =
                    BlockModule.getComponent(assemblerType, world, pos.x(), pos.y(), pos.z());
                carry = BlockHolderCarry.shouldCarry(assembler);
            }
            if (!carry) {
                // A Cultivator broken mid-craft likewise holds REAL player items (pulled from chests) in its
                // held container; carry them (plus output + energy + card) rather than destroying them.
                com.chonbosmods.chemistry.impl.block.craft.CultivatorState cultivator =
                    BlockModule.getComponent(cultivatorType, world, pos.x(), pos.y(), pos.z());
                carry = BlockHolderCarry.shouldCarry(cultivator);
            }
            if (!carry) {
                // A Sculptor broken mid-craft likewise holds REAL player items (pulled from chests) in its
                // held container; carry them (plus output + energy + recipe-script card) rather than destroying them.
                com.chonbosmods.chemistry.impl.block.craft.SculptorState sculptor =
                    BlockModule.getComponent(sculptorType, world, pos.x(), pos.y(), pos.z());
                carry = BlockHolderCarry.shouldCarry(sculptor);
            }
            if (!carry) {
                return;
            }

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
                    WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
                    if (chunk == null) {
                        return;
                    }
                    // Confirm the block is still there before clearing (defensive against races).
                    BlockType actual = chunk.getBlockType(x, y, z);
                    if (actual == null || actual == BlockType.EMPTY) {
                        return;
                    }

                    // Capture + encode the FULL block entity NOW, before removal: the encode snapshots
                    // every component into BSON, so the subsequent setBlock cannot touch it.
                    BsonDocument holderDoc = encodeBlockEntity(chunk, x, y, z);

                    chunk.setBlock(x, y, z, BlockType.EMPTY);

                    // H8b: mirror the vanilla break path's connected-block neighbor pass
                    // (BlockHarvestUtils does this after removal) so adjacent pipes re-resolve their
                    // shapes: without it, cables kept a phantom arm pointing at the removed block.
                    // The pass factory-resets re-resolved pipes' block entities, so RE-SNAPSHOT the
                    // surrounding pipes first: the event-time snapshots may already have been spent
                    // by a tick pass that ran between the (cancelled) event and this deferred break.
                    try {
                        com.chonbosmods.chemistry.impl.block.net.PipeSnapshotScan.snapshotAround(
                            world, pipeType, networkService.snapshotsForWorld(world), x, y, z);
                        com.hypixel.hytale.server.core.universe.world.connectedblocks.ConnectedBlocksUtil
                            .setConnectedBlockAndNotifyNeighbors(
                                world.getChunkStore(),
                                BlockType.getAssetMap().getIndex("Empty"),
                                com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple.NONE,
                                new Vector3i(0, 0, 0),
                                new Vector3i(x, y, z),
                                chunk,
                                chunk.getBlockChunk());
                    } catch (Throwable ignored) {
                        // Visual reshape must never block the drop below.
                    }

                    // Stamp the encoded entity onto the drop; a failed encode degrades to a plain drop.
                    ItemStack drop = BlockHolderCarry.stampStack(new ItemStack(dropItemId, 1), holderDoc);
                    spawnDrop(world, drop, x, y, z);
                } catch (Throwable ignored) {
                    // Never let a break handler crash the world thread.
                }
            });
        } catch (Throwable ignored) {
            // Defensive: a break handler must never throw.
        }
    }

    /**
     * The block entity at (x,y,z) encoded as the engine's persistable entity document, or null on any
     * failure (no entity, codec error): callers degrade to a plain drop.
     */
    private static BsonDocument encodeBlockEntity(WorldChunk chunk, int x, int y, int z) {
        try {
            Holder<ChunkStore> holder = chunk.getBlockComponentHolder(x, y, z);
            if (holder == null) {
                return null;
            }
            BsonValue encoded = ChunkStore.REGISTRY.getEntityCodec().encode(holder, EmptyExtraInfo.EMPTY);
            return encoded == null || !encoded.isDocument() ? null : encoded.asDocument();
        } catch (Throwable t) {
            return null;
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
