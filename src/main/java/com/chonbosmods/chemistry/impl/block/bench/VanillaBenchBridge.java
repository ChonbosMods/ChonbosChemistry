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
 * class is the sole place that CALLS {@code builtin.crafting.*} BEHAVIOR (slot setup, recipe
 * detection, processing advance, container access, component-type lookup), so all of the coupling
 * risk to an internal (non stability-guaranteed) API lives in one file: a Hytale update that shifts
 * an internal signature breaks only this class, guarded by a devServer smoke test.
 *
 * <p><b>Allowed relaxation:</b> merely HOLDING the bench types as fields and referencing their public
 * {@code CODEC}s for composition/persistence is permitted elsewhere (e.g. {@code MachineBlockState}
 * embeds {@link ProcessingBenchBlock} + {@link BenchBlock} as optional codec keys so a placed
 * smelter's bench contents + progress survive chunk save/reload, per D31). That is data plumbing, not
 * a behavior call: it does not invoke bench logic, so it carries none of the internal-API coupling
 * risk this bridge isolates. Invoking any bench BEHAVIOR still belongs here and nowhere else.
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

    /** The bench's current input progress (seconds accumulated toward the next completion). */
    public static float inputProgress(ProcessingBenchBlock b) {
        // signature: float getInputProgress()
        return b.getInputProgress();
    }

    /**
     * The current smelt progress as a 0..1 fraction (inputProgress / recipe time at {@code tier}), or 0
     * when there is no active recipe. For the panel progress bar.
     */
    public static float progressFraction(ProcessingBenchBlock b, int tier) {
        // signature: CraftingRecipe getRecipe(); float getRecipeTimeSeconds(int tier)
        if (b.getRecipe() == null) {
            return 0.0F;
        }
        float total = b.getRecipeTimeSeconds(tier);
        if (total <= 0.0F) {
            return 0.0F;
        }
        return Math.max(0.0F, Math.min(1.0F, b.getInputProgress() / total));
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
     * Restore a codec-loaded bench's transient config + re-wire its slot listeners against the live world.
     *
     * <p>A {@link ProcessingBenchBlock} carries its slot CONTENTS + progress + item containers across a
     * codec round-trip (chunk save/reload), but its {@code processingBench} CONFIG field is
     * {@code transient} (verified via javap) so it decodes to {@code null} — and {@code setupSlots} calls
     * {@code processingBench.getInput(...)}, which NPEs on a loaded bench (this crashed the WorldThread:
     * 2026-06-21). So, unlike its prior contract, this MUST re-run {@code initializeBenchConfig(blockType)}
     * first to repopulate {@code processingBench} from the block type. That sets only the transient config
     * (the persisted, separately-stored input/fuel/output containers are left intact), so decoded CONTENTS
     * are preserved. Then {@code setupSlots} re-attaches the slot listeners to the world at {@code (x,y,z)}.
     *
     * <p>Same full placement context as {@link #create}: the {@code world}, the sibling
     * {@link BenchBlock}, the {@code stateInfo}, block coords and {@code tier}.
     */
    public static void wireSlots(ProcessingBenchBlock b,
                                 World world,
                                 BenchBlock bench,
                                 BlockModule.BlockStateInfo stateInfo,
                                 int x,
                                 int y,
                                 int z,
                                 BlockType blockType,
                                 int tier) {
        // Repopulate the transient processingBench config (null on a decoded bench) BEFORE setupSlots,
        // which dereferences it. Does not touch the persisted item containers, so contents are kept.
        b.initializeBenchConfig(blockType);
        // signature: void setupSlots(World, BenchBlock, BlockModule$BlockStateInfo,
        //            int x, int y, int z, BlockType, int tier)
        b.setupSlots(world, bench, stateInfo, x, y, z, blockType, tier);
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

    /**
     * Drive the placed block's interaction (visual) state by name, exactly as vanilla's
     * {@code BenchSystems$ProcessingBenchTick} does for engine-ticked benches: it passes {@code "Processing"}
     * while active and {@code "default"} otherwise to this same method. Our held-bench machine is never seen
     * by that tick, so {@link com.chonbosmods.chemistry.impl.block.MachineTickSystem} calls this to swap the
     * block texture / play the {@code CustomModelAnimation} declared on the matching {@code State.Definitions}.
     *
     * <p>Internally delegates to {@code World.setBlockInteractionState(Vector3i, BlockType, String)}; kept
     * here so the {@code builtin.crafting.*} behavior call stays confined to this bridge.
     */
    public static void setVisualState(ProcessingBenchBlock b,
                                      String state,
                                      BlockType blockType,
                                      World world,
                                      int x,
                                      int y,
                                      int z) {
        // signature: void setBlockInteractionState(String state, BlockType, World, int x, int y, int z)
        b.setBlockInteractionState(state, blockType, world, x, y, z);
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
