package com.chonbosmods.chemistry.impl.block.bench;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.logger.HytaleLogger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * TEMP spike (Task 2): {@code /ccbenchspike <x> <y> <z>} drives an already-placed VANILLA furnace
 * through {@link VanillaBenchBridge}, proving we can advance a real {@code ProcessingBenchBlock}
 * ourselves (not via vanilla's autonomous tick). This is the GATING check for the whole machine
 * substrate (design §7.3 / D31, {@code docs/plans/2026-06-08-vanilla-bench-reuse-design.md}).
 *
 * <p><b>Throwaway:</b> deleted in Task 4 once the substrate proper lands. No tests, no polish.
 *
 * <h2>Why coordinate args (not a look-target)</h2>
 * A {@link CommandContext} carries no raycast/look target (unlike an {@code InteractionContext}; cf.
 * {@code WrenchInteraction}). Rather than reconstruct a look ray from the player transform, the spike
 * takes explicit {@code x y z}: the player aims at the furnace, reads its coords off the F3-style
 * debug, and types them. Simplest + deterministic for a one-shot manual gate.
 *
 * <h2>Why we force {@code active}</h2>
 * A stock furnace HAS fuel slots, and {@code advanceProcessing} returns 0 while {@code !active}.
 * Vanilla only flips {@code active=true} from the player's open-furnace window (a {@code SetActiveAction}
 * packet). Driving it headless, we must ignite it ourselves via the bridge ({@code setActive}). The
 * future no-fuel smelter won't need this: {@code setupSlots} auto-activates a fuel-less bench.
 *
 * <p>The {@code tier} arg passed to {@code advanceProcessing} is the bench's own
 * {@code BenchBlock.getTierLevel()} (what the engine itself reads internally for recipe timing); the
 * positional {@code rotationIndex} the engine wants is only used for sound/eject placement, so the
 * tier level is a safe stand-in for this drive-only spike.
 */
public final class BenchSpikeCommand extends CommandBase {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Iterations + dt chunky enough to push a real smelt to completion in one command. */
    private static final int ITERATIONS = 5;
    private static final float DT_SECONDS = 2.0f;

    private final RequiredArg<Integer> xArg = withRequiredArg("x", "Block X of the furnace", ArgTypes.INTEGER);
    private final RequiredArg<Integer> yArg = withRequiredArg("y", "Block Y of the furnace", ArgTypes.INTEGER);
    private final RequiredArg<Integer> zArg = withRequiredArg("z", "Block Z of the furnace", ArgTypes.INTEGER);

    public BenchSpikeCommand() {
        super("ccbenchspike", "TEMP spike: drive a placed vanilla furnace via VanillaBenchBridge.");
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("ccbenchspike: run as a player (needs your world)."));
            return;
        }
        Ref<EntityStore> player = context.senderAsPlayerRef();
        if (player == null || !player.isValid()) {
            context.sendMessage(Message.raw("ccbenchspike: could not resolve your player."));
            return;
        }
        EntityStore entityExternal = player.getStore().getExternalData();
        World world = entityExternal == null ? null : entityExternal.getWorld();
        if (world == null) {
            context.sendMessage(Message.raw("ccbenchspike: could not resolve your world."));
            return;
        }

        int x = xArg.get(context);
        int y = yArg.get(context);
        int z = zArg.get(context);

        // Command handlers run on a command/ForkJoin thread, not the WorldThread, but all the work below
        // touches world/ECS state (block entities, components, container mutation) which asserts unless it
        // runs on the WorldThread. world.execute(...) queues this runnable to run on the world's next tick,
        // i.e. on the WorldThread: hop there so the bench resolution + drive + reporting all run safely.
        world.execute(() -> {
            // Resolve the block entity at (x,y,z): same lookup WrenchInteraction / MachinePanelPage use.
            Ref<ChunkStore> blockRef = BlockModule.getBlockEntity(world, x, y, z);
            if (blockRef == null || !blockRef.isValid()) {
                context.sendMessage(Message.raw("Not a processing bench"));
                return;
            }
            Store<ChunkStore> blockStore = blockRef.getStore();
            if (blockStore == null) {
                context.sendMessage(Message.raw("Not a processing bench"));
                return;
            }

            // The vanilla processing-bench component + its sibling BenchBlock, both off the same ref. Every
            // builtin.crafting.* type funnels through VanillaBenchBridge accessors (no hard dependency here
            // beyond the component handles the bridge hands back).
            ComponentType<ChunkStore, ProcessingBenchBlock> benchType = VanillaBenchBridge.benchComponentType();
            ComponentType<ChunkStore, BenchBlock> benchBlockType = VanillaBenchBridge.benchBlockComponentType();
            ProcessingBenchBlock bench = blockStore.getComponent(blockRef, benchType);
            BenchBlock benchBlock = blockStore.getComponent(blockRef, benchBlockType);
            BlockModule.BlockStateInfo stateInfo =
                blockStore.getComponent(blockRef, BlockModule.BlockStateInfo.getComponentType());
            if (bench == null || benchBlock == null || stateInfo == null) {
                context.sendMessage(Message.raw("Not a processing bench"));
                return;
            }

            BlockType blockType = resolveBlockType(blockStore, stateInfo);
            if (blockType == null) {
                context.sendMessage(Message.raw("ccbenchspike: could not resolve the block type at the bench."));
                return;
            }

            // advanceProcessing reads the tier off BenchBlock.getTierLevel() internally; the positional arg is
            // only used for sound/eject placement, so the bench's own tier level is a safe value here.
            int tier = benchBlock.getTierLevel();
            Store<EntityStore> entityStore = world.getEntityStore().getStore();

            // Ignite the (fueled) furnace ourselves, since no window will (see class javadoc).
            boolean ignited = VanillaBenchBridge.setActive(bench, true, benchBlock, stateInfo);

            int totalCompletions = 0;
            StringBuilder steps = new StringBuilder();
            for (int i = 0; i < ITERATIONS; i++) {
                boolean worked = VanillaBenchBridge.advance(
                    bench, DT_SECONDS, entityStore, benchBlock, stateInfo, x, y, z, blockType, tier);
                if (worked) {
                    totalCompletions++;
                }
                steps.append(worked ? '1' : '0');
            }

            float progress = VanillaBenchBridge.inputProgress(bench);
            boolean active = VanillaBenchBridge.isActive(bench);
            String output = describeContainer(VanillaBenchBridge.output(bench));

            String report = "ccbenchspike @(" + x + "," + y + "," + z + ") ignited=" + ignited
                + " active=" + active + " steps[" + steps + "] workedSteps=" + totalCompletions
                + " progress=" + progress + " output=" + output;

            LOGGER.atInfo().log(report);
            context.sendMessage(Message.raw(report));
        });
    }

    /** BlockType at the bench, resolved like the vanilla ProcessingBenchTick (StateInfo → chunk → section). */
    @Nullable
    private BlockType resolveBlockType(@Nonnull Store<ChunkStore> blockStore,
                                       @Nonnull BlockModule.BlockStateInfo stateInfo) {
        Ref<ChunkStore> chunkRef = stateInfo.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) {
            return null;
        }
        BlockChunk blockChunk = chunkRef.getStore().getComponent(chunkRef, BlockChunk.getComponentType());
        if (blockChunk == null) {
            return null;
        }
        int blockIndex = stateInfo.getIndex();
        int localX = ChunkUtil.xFromBlockInColumn(blockIndex);
        int localY = ChunkUtil.yFromBlockInColumn(blockIndex);
        int localZ = ChunkUtil.zFromBlockInColumn(blockIndex);
        BlockSection section = blockChunk.getSectionAtBlockY(localY);
        if (section == null) {
            return null;
        }
        int blockId = section.get(localX, localY, localZ);
        return BlockType.getAssetMap().getAsset(blockId);
    }

    /** "[0]=iron_ingot x2, [1]=empty" over an output container's slots. */
    @Nonnull
    private String describeContainer(@Nullable ItemContainer container) {
        if (container == null) {
            return "<no container>";
        }
        short capacity = container.getCapacity();
        if (capacity <= 0) {
            return "<0 slots>";
        }
        StringBuilder sb = new StringBuilder();
        for (short slot = 0; slot < capacity; slot++) {
            if (slot > 0) {
                sb.append(", ");
            }
            ItemStack stack = container.getItemStack(slot);
            sb.append('[').append(slot).append("]=");
            if (stack == null) {
                sb.append("empty");
            } else {
                sb.append(stack.getItemId()).append(" x").append(stack.getQuantity());
            }
        }
        return sb.toString();
    }
}
