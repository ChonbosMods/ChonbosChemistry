package com.chonbosmods.chemistry.impl.block.bench;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The SOLE chokepoint into the server-internal {@code com.hypixel.hytale.builtin.crafting.*} API.
 *
 * <p>The machine substrate reuses Hytale's built-in furnace bench engine by composition: our own
 * ECS component holds a real vanilla {@link ProcessingBenchBlock} and drives it from our tick. This
 * class isolates every reference to {@code builtin.crafting.*} so that all of the coupling risk to
 * an internal (non stability-guaranteed) API lives in one file: a Hytale update that shifts an
 * internal signature breaks only this class, guarded by a devServer smoke test. Nothing else in the
 * codebase should import {@code builtin.crafting.*}.
 *
 * <p>See {@code docs/design.md} §7.3 / D31 (compose-and-delegate over the bench engine, vanilla
 * never ticks our block) and {@code docs/plans/2026-06-08-vanilla-bench-reuse-design.md} (decompile
 * evidence + the one-bridge rationale).
 *
 * <p><b>Signature note:</b> the real {@code setupSlots} and {@code advanceProcessing} take full
 * server context (world {@link Store}, the sibling {@link BenchBlock}, block coords, the
 * {@link BlockType} and tier), not the simplified shapes the design sketch implied. The bridge
 * methods faithfully pass that context through rather than fabricate it; see the per-method
 * {@code signature:} comments for the exact engine shapes later tasks can rely on.
 */
public final class VanillaBenchBridge {

    private VanillaBenchBridge() {
    }

    /** The ECS component type vanilla registers for processing benches, reachable off the plugin. */
    public static ComponentType<ChunkStore, ProcessingBenchBlock> benchComponentType() {
        // signature: CraftingPlugin.get() (static) -> getProcessingBenchBlockComponentType()
        //            : ComponentType<ChunkStore, ProcessingBenchBlock>
        return CraftingPlugin.get().getProcessingBenchBlockComponentType();
    }

    /**
     * The ECS component type vanilla registers for the SIBLING {@link BenchBlock} that rides on every
     * bench block alongside the {@link ProcessingBenchBlock}. {@code advanceProcessing} /
     * {@code checkForRecipeUpdate} both take this companion, so any caller driving a real placed bench
     * must look it up next to the processing component. Kept here so {@code builtin.crafting.*} stays
     * confined to this bridge.
     */
    public static ComponentType<ChunkStore, BenchBlock> benchBlockComponentType() {
        // signature: CraftingPlugin.get() (static) -> getBenchBlockComponentType()
        //            : ComponentType<ChunkStore, BenchBlock>
        return CraftingPlugin.get().getBenchBlockComponentType();
    }

    /**
     * Force the bench's {@code active} flag (TEMP: used by the Task 2 spike to ignite a real VANILLA
     * furnace headlessly). A fueled bench gates {@code advanceProcessing} on {@code active}, which vanilla
     * only flips true from the player's open-furnace window ({@code SetActiveAction}); driving the bench
     * from our own tick (no window) needs us to set it ourselves. Returns vanilla's result: {@code false}
     * if it refused (e.g. asked to activate a fueled bench whose fuel container is empty).
     *
     * <p>The future no-fuel smelter does NOT need this: {@code setupSlots} auto-activates a fuel-less
     * bench (its {@code ProcessingBench} config has no {@code Fuel} slots). This passthrough exists only so
     * the spike can prove drive-ability against the stock furnace, which is fueled.
     */
    public static boolean setActive(ProcessingBenchBlock b,
                                    boolean active,
                                    BenchBlock bench,
                                    BlockModule.BlockStateInfo stateInfo) {
        // signature: boolean setActive(boolean, BenchBlock, BlockModule$BlockStateInfo)
        return b.setActive(active, bench, stateInfo);
    }

    /** Whether the bench currently considers itself processing. */
    public static boolean isActive(ProcessingBenchBlock b) {
        // signature: boolean isActive()
        return b.isActive();
    }

    /** The bench's current input progress (seconds accumulated toward the next completion). */
    public static float inputProgress(ProcessingBenchBlock b) {
        // signature: float getInputProgress()
        return b.getInputProgress();
    }

    /**
     * Construct and configure a fresh {@link ProcessingBenchBlock} from a block type's bench config.
     *
     * <p>Intended to produce a NO-FUEL bench (the smelter has no fuel slots): the no-fuel-ness comes
     * entirely from the {@link BlockType}'s {@code ProcessingBench} config supplied by the caller, so
     * this method just faithfully initializes from whatever block type it is given. No slot counts
     * are hardcoded beyond what {@code setupSlots} requires.
     *
     * <p>{@code setupSlots} needs the full placement context (it reads the world block-state and the
     * sibling {@link BenchBlock}), so the caller passes it through: {@code world}, the {@code bench}
     * companion component, the {@code stateInfo}, the block coords {@code (x, y, z)} and the
     * {@code tier} level.
     */
    public static ProcessingBenchBlock create(World world,
                                              BenchBlock bench,
                                              BlockModule.BlockStateInfo stateInfo,
                                              int x,
                                              int y,
                                              int z,
                                              BlockType blockType,
                                              int tier) {
        ProcessingBenchBlock b = new ProcessingBenchBlock();
        // signature: boolean initializeBenchConfig(BlockType) : requires blockType.getBench() to be
        //            a ProcessingBench (returns false / no-ops otherwise). Return value ignored here.
        b.initializeBenchConfig(blockType);
        // signature: void setupSlots(World, BenchBlock, BlockModule$BlockStateInfo,
        //            int x, int y, int z, BlockType, int tier)
        b.setupSlots(world, bench, stateInfo, x, y, z, blockType, tier);
        return b;
    }

    /**
     * Advance one processing step: refresh the auto-detected recipe, then run the engine's tick.
     *
     * <p>{@code advanceProcessing} returns an {@code int} (the number of completions / units of work
     * done this step), so "work happened" is {@code result > 0}. Like {@code setupSlots} it needs the
     * full server context, passed through by the caller: the entity-store {@link Store}, the sibling
     * {@link BenchBlock}, the {@code stateInfo}, block coords, the {@link BlockType} and {@code tier}.
     *
     * @param dtSeconds elapsed time for this step (the engine's first {@code float} arg)
     * @return {@code true} if the engine reported any work this step
     */
    public static boolean advance(ProcessingBenchBlock b,
                                  float dtSeconds,
                                  Store<EntityStore> entityStore,
                                  BenchBlock bench,
                                  BlockModule.BlockStateInfo stateInfo,
                                  int x,
                                  int y,
                                  int z,
                                  BlockType blockType,
                                  int tier) {
        // signature: void checkForRecipeUpdate(BenchBlock)
        b.checkForRecipeUpdate(bench);
        // signature: int advanceProcessing(float dt, Store<EntityStore>, BenchBlock,
        //            BlockModule$BlockStateInfo, int x, int y, int z, BlockType, int tier)
        //            -> returns completions this step (> 0 means work happened).
        int completions = b.advanceProcessing(dtSeconds, entityStore, bench, stateInfo, x, y, z, blockType, tier);
        return completions > 0;
    }

    /** The bench's input (ingredient) container. */
    public static ItemContainer input(ProcessingBenchBlock b) {
        // signature: ItemContainer getInputContainer()
        return b.getInputContainer();
    }

    /** The bench's output (finished-goods) container. */
    public static ItemContainer output(ProcessingBenchBlock b) {
        // signature: ItemContainer getOutputContainer()
        return b.getOutputContainer();
    }
}
