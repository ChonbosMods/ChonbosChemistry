package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.impl.block.EnergyBuffer;
import com.chonbosmods.chemistry.impl.block.MachineDriveContext;
import com.chonbosmods.chemistry.impl.block.bench.VanillaCraftBridge;
import com.chonbosmods.chemistry.impl.block.net.NetworkService;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-tick driver for the autonomous Assembler block (a PURE auto-craft machine). For every live
 * {@link AssemblerState} on the {@link ChunkStore}, it resolves the live drive context (ref / world / block
 * coords / block type / energy) and hands it to the shared {@link AutoCraftEngine}, which runs one
 * energy-gated step of the discrete pull loop (idle &rarr; pull &rarr; craft &rarr; complete &rarr; repeat).
 *
 * <p>Mirrors the thin {@link ForgeTickSystem}: this system owns ONLY the context-resolution prologue
 * (via {@link MachineDriveContext}: every lookup guarded, the node skipped on the first missing piece) and
 * the never-throw wrapper; the entire craft loop lives in {@link AutoCraftEngine}, configured by
 * {@link #ASSEMBLER_SPEC} (the Assembler's bench pool, energy draw, post-craft delay, and the two improvement
 * hooks). Unlike the Forge, the Assembler's pool unions a Workbench, a Furniture_Bench, AND a
 * Furniture_Misc (all {@link BenchType#Crafting}): each yields {@link CraftingRecipe} objects, so the engine
 * reads input&rarr;output off any of them, and each recipe crafts for its own time via
 * {@link VanillaCraftBridge#recipeTimeSeconds}.
 *
 * <h2>Defensive contract</h2>
 * Mirrors {@code MachineTickSystem}: this runs every server tick on hot ECS data and MUST NEVER throw on a
 * missing component, chunk, world, or bridge call. The whole per-node drive is wrapped in a catch-Throwable
 * that logs each distinct error once and skips the node for the tick.
 */
public final class AssemblerTickSystem extends EntityTickingSystem<ChunkStore> {

    /** [TUNE] Per-second energy draw of a running Assembler. Placeholder, matches {@code FORGE_DRAW}. */
    private static final long ASSEMBLER_DRAW = 200L;

    /**
     * [TUNE] Fallback seconds one craft takes for instant (time-0) crafting recipes. Recipes carrying a
     * real {@link VanillaCraftBridge#recipeTimeSeconds} use it; instant (time-0) crafting recipes fall
     * back to this.
     *
     * <p>Public single source of truth: kept PUBLIC so a future GUI can read it to render a fallback progress
     * fraction (mirroring how {@link ForgeTickSystem#FORGE_DURATION} is public), so the GUI and the tick
     * never disagree on a fallen-back craft's length.
     */
    public static final float ASSEMBLER_DEFAULT_DURATION = 4.0f;

    /**
     * [TUNE] Multiplier on every craft's craft time (the recipe's real seconds, or the fallback). {@code 2.0}
     * = double the vanilla craft time, a deliberate slow-down for now. Both the tick AND the GUI progress bar
     * derive their duration through {@link AutoCraftEngine.Spec#craftDuration} / {@link #craftDurationFor},
     * so scaling here keeps them in agreement automatically.
     */
    private static final float ASSEMBLER_TIME_MULTIPLIER = 2.0f;

    /**
     * [TUNE] Ticks the Assembler idles after completing a craft before sourcing the next recipe. A cosmetic
     * beat so a completed craft reads visually before the next pull begins (the pull amount itself is
     * already exactly one recipe). 0 = no pause (back-to-back).
     */
    private static final int POST_CRAFT_DELAY_TICKS = 2;

    private final ComponentType<ChunkStore, AssemblerState> assemblerType;
    private final ComponentType<ChunkStore, PipeNode> pipeType;
    private final NetworkService networkService;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType;
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType;

    public AssemblerTickSystem(
            @Nonnull ComponentType<ChunkStore, AssemblerState> assemblerType,
            @Nonnull ComponentType<ChunkStore, PipeNode> pipeType,
            @Nonnull NetworkService networkService) {
        this.assemblerType = assemblerType;
        this.pipeType = pipeType;
        this.networkService = networkService;
        this.blockInfoType = BlockModule.BlockStateInfo.getComponentType();
        this.blockChunkType = BlockChunk.getComponentType();
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        // Energy + containers are mutated in place on the live component; keep it single-threaded.
        return false;
    }

    @Override
    public Query<ChunkStore> getQuery() {
        return assemblerType;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        AssemblerState node = archetypeChunk.getComponent(index, assemblerType);
        if (node == null) {
            return;
        }
        // DEFENSIVE: a craft drive error must NEVER kill the WorldThread. Catch every throwable, log each
        // distinct one once, and skip this Assembler's drive for the tick (same contract as MachineTickSystem).
        try {
            driveCraft(node, dt, index, archetypeChunk, store);
        } catch (Throwable t) {
            String msg = String.valueOf(t);
            if (ASSEMBLER_DRIVE_SEEN.add(msg)) {
                ASSEMBLER_DRIVE_LOG.atWarning().log("CC assembler craft drive failed (skipped, world tick protected): " + msg);
            }
        }
    }

    /** Guards the per-tick craft drive: a failure logs once (per distinct error) and never crashes the tick. */
    private static final HytaleLogger ASSEMBLER_DRIVE_LOG = HytaleLogger.forEnclosingClass();
    private static final java.util.Set<String> ASSEMBLER_DRIVE_SEEN =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Resolve this Assembler node's live drive context (the prologue) and hand it to {@link AutoCraftEngine}. A
     * no-op whenever any piece of the context (energy buffer, ref, world, block coords, block type) cannot be
     * resolved. NEVER throws.
     */
    private void driveCraft(
            @Nonnull AssemblerState node,
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store) {
        // The energy buffer gates the drive: no buffer means no power gate -> nothing to spend, skip.
        EnergyHandler energyHandler = node.energy();
        if (!(energyHandler instanceof EnergyBuffer energy)) {
            return;
        }

        // Resolve the live drive context off the same block-entity ref + sibling components. The shared
        // prologue (MachineDriveContext) guards every step and returns null on the first missing piece.
        MachineDriveContext.Resolved ctx =
            MachineDriveContext.resolve(index, archetypeChunk, store, blockInfoType, blockChunkType);
        if (ctx == null) {
            return;
        }

        // Hand the resolved context to the shared craft engine: it owns the full pull/craft/complete loop +
        // the block visual swap, configured by ASSEMBLER_SPEC. The engine never throws (the tick's catch is the
        // backstop).
        AutoCraftEngine.Context engineCtx = new AutoCraftEngine.Context(
            ctx.world(), store, ctx.x(), ctx.y(), ctx.z(), ctx.blockType(), dt, energy, networkService, pipeType);
        AutoCraftEngine.drive(node, engineCtx, ASSEMBLER_SPEC);
    }

    // --- the Assembler's engine configuration (the machine-specific Spec) ---

    /**
     * The Assembler's {@link AutoCraftEngine.Spec}: its bench pool (cached on first use), energy draw,
     * post-craft delay, and the two improvement hooks. {@code craftDuration} returns the recipe's real craft
     * time ({@link VanillaCraftBridge#recipeTimeSeconds}) or {@link #ASSEMBLER_DEFAULT_DURATION} for instant
     * (time-0) crafting recipes; {@code allowSet} delegates to {@link AutoCraftEngine#cardAllowSet}.
     */
    private static final AutoCraftEngine.Spec ASSEMBLER_SPEC = new AutoCraftEngine.Spec() {
        @Override
        public RecipePool recipePool() {
            return assemblerRecipePool();
        }

        @Override
        public long energyDraw() {
            return ASSEMBLER_DRAW;
        }

        @Override
        public int postCraftDelayTicks() {
            return POST_CRAFT_DELAY_TICKS;
        }

        @Override
        public float craftDuration(@Nullable CraftingRecipe r) {
            // Per-recipe craft time: a recipe carries its real seconds; a time-0 recipe falls back to the
            // default. The engine passes null when idle (the value is unused while idle); MUST null-guard or
            // every idle tick NPEs. Scaled by ASSEMBLER_TIME_MULTIPLIER (the GUI bar reads the same scaled value
            // via craftDurationFor, so bar and tick stay in lockstep).
            float base = (r == null) ? ASSEMBLER_DEFAULT_DURATION : VanillaCraftBridge.recipeTimeSeconds(r);
            if (base <= 0f) {
                base = ASSEMBLER_DEFAULT_DURATION;
            }
            return base * ASSEMBLER_TIME_MULTIPLIER;
        }

        @Override
        public Set<String> allowSet(AutoCraftNode node) {
            return AutoCraftEngine.cardAllowSet(node.card()); // IMPROVEMENT AXIS: recipe scripting.
        }
    };

    /**
     * The real craft duration (seconds) of the recipe currently loaded in an Assembler, for the PANEL's
     * progress bar denominator. Resolves {@code recipeId} against the shared pool and runs the SAME
     * {@link AutoCraftEngine.Spec#craftDuration} the tick uses (the recipe's {@link VanillaCraftBridge#recipeTimeSeconds}
     * or {@link #ASSEMBLER_DEFAULT_DURATION} for a time-0 recipe), so the bar fills over exactly the craft time the
     * tick is timing: no pausing at 100% (real time longer than the constant) and no completing before 100%
     * (real time shorter). A {@code null} id (idle) or an id not in the pool yields the default. Safe to call
     * off the tick: the pool is a cached, immutable, load-time-fixed snapshot.
     */
    public static float craftDurationFor(@Nullable String recipeId) {
        CraftingRecipe r = recipeId == null ? null : assemblerRecipePool().map().get(recipeId);
        return ASSEMBLER_SPEC.craftDuration(r); // null-guarded -> ASSEMBLER_DEFAULT_DURATION
    }

    // --- shared recipe pool (load-time-fixed; built once, lazily) ---

    private static volatile RecipePool POOL;

    /**
     * The Assembler's shared recipe pool, built once on first tick and cached (the recipe registry is
     * load-time-fixed). Unions the Workbench + the Furniture_Bench + the Furniture_Misc (all
     * {@link BenchType#Crafting}) and dedups by id into a deterministic (natural String) order via
     * {@link RecipePool#union}.
     */
    private static RecipePool assemblerRecipePool() {
        RecipePool p = POOL;
        if (p != null) {
            return p;
        }
        synchronized (AssemblerTickSystem.class) {
            if (POOL != null) {
                return POOL;
            }
            RecipePool pool = RecipePool.union(List.of(
                new RecipePool.BenchRef(BenchType.Crafting, "Workbench"),
                new RecipePool.BenchRef(BenchType.Crafting, "Furniture_Bench"),
                new RecipePool.BenchRef(BenchType.Crafting, "Furniture_Misc")));
            POOL = pool;
            // Diagnostic (logged once): if this is 0, the bench ids did not resolve to recipes and the Assembler
            // can never craft. Confirms the Workbench + Furniture_Bench + Furniture_Misc resolved.
            ASSEMBLER_DRIVE_LOG.atInfo().log("Assembler recipe pool built: " + pool.stableOrder().size() + " recipes.");
            return POOL;
        }
    }
}
