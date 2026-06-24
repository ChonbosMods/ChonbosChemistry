package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.impl.block.EnergyBuffer;
import com.chonbosmods.chemistry.impl.block.net.NetworkService;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Per-tick driver for the autonomous Forge block (the demand-driven "pull-crafter"). For every live
 * {@link ForgeCraftState} on the {@link ChunkStore}, it resolves the live drive context (ref / world / block
 * coords / block type / energy) and hands it to the shared {@link AutoCraftEngine}, which runs one
 * energy-gated step of the discrete pull loop (idle &rarr; pull &rarr; craft &rarr; complete &rarr; repeat).
 *
 * <p>This system is now a thin client: it owns ONLY the context-resolution prologue (mirroring
 * {@code MachineTickSystem.driveBench}: every lookup guarded, the node skipped on the first missing piece)
 * and the never-throw wrapper; the entire craft loop lives in {@link AutoCraftEngine}, configured by
 * {@link #FORGE_SPEC} (the Forge's bench pool, energy draw, post-craft delay, and the two improvement hooks).
 *
 * <h2>Defensive contract</h2>
 * Mirrors {@code MachineTickSystem}: this runs every server tick on hot ECS data and MUST NEVER throw on a
 * missing component, chunk, world, or bridge call. The whole per-node drive is wrapped in a catch-Throwable
 * that logs each distinct error once and skips the node for the tick.
 *
 * <p>Unlike the Smelter, the Forge does not delegate to a vanilla autonomous bench (vanilla crafting has no
 * such block); the {@link AutoCraftEngine} drives the crafting helpers itself through
 * {@code VanillaCraftBridge} and sources its ingredients from the item-pipe network via
 * {@code NetworkRecipeSource}.
 */
public final class ForgeTickSystem extends EntityTickingSystem<ChunkStore> {

    /** [TUNE] Per-second energy draw of a running Forge. Placeholder, matches {@code SMELTER_DRAW}. */
    private static final long FORGE_DRAW = 200L;

    /**
     * [TUNE] Seconds one craft takes. Placeholder until per-recipe craft times land.
     *
     * <p>Public single source of truth: {@code ForgePanelPage} reads it to render the progress bar
     * fraction ({@code progress / FORGE_DURATION}), so the GUI and the tick never disagree on a craft's
     * length. When per-recipe craft times land, the panel must switch to the same per-recipe lookup. The
     * engine resolves the craft duration via {@link #FORGE_SPEC}'s {@code craftDuration}, which returns this.
     */
    public static final float FORGE_DURATION = 4.0f;

    /**
     * [TUNE] Ticks the Forge idles after completing a craft before sourcing the next recipe. A cosmetic
     * beat so a completed craft reads visually before the next pull begins (the pull amount itself is
     * already exactly one recipe). 0 = no pause (back-to-back, the pre-pause behavior).
     */
    private static final int POST_CRAFT_DELAY_TICKS = 2;

    private final ComponentType<ChunkStore, ForgeCraftState> forgeType;
    private final ComponentType<ChunkStore, PipeNode> pipeType;
    private final NetworkService networkService;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType;
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType;

    public ForgeTickSystem(
            @Nonnull ComponentType<ChunkStore, ForgeCraftState> forgeType,
            @Nonnull ComponentType<ChunkStore, PipeNode> pipeType,
            @Nonnull NetworkService networkService) {
        this.forgeType = forgeType;
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
        return forgeType;
    }

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        ForgeCraftState node = archetypeChunk.getComponent(index, forgeType);
        if (node == null) {
            return;
        }
        // DEFENSIVE: a craft drive error must NEVER kill the WorldThread. Catch every throwable, log each
        // distinct one once, and skip this Forge's drive for the tick (same contract as MachineTickSystem).
        try {
            driveForge(node, dt, index, archetypeChunk, store);
        } catch (Throwable t) {
            String msg = String.valueOf(t);
            if (FORGE_DRIVE_SEEN.add(msg)) {
                FORGE_DRIVE_LOG.atWarning().log("CC forge craft drive failed (skipped, world tick protected): " + msg);
            }
        }
    }

    /** Guards the per-tick craft drive: a failure logs once (per distinct error) and never crashes the tick. */
    private static final HytaleLogger FORGE_DRIVE_LOG = HytaleLogger.forEnclosingClass();
    private static final java.util.Set<String> FORGE_DRIVE_SEEN =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Resolve this Forge node's live drive context (the prologue) and hand it to {@link AutoCraftEngine}. A
     * no-op whenever any piece of the context (energy buffer, ref, world, block coords, block type) cannot be
     * resolved. NEVER throws.
     */
    private void driveForge(
            @Nonnull ForgeCraftState node,
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store) {
        // The energy buffer gates the drive: no buffer means no power gate -> nothing to spend, skip.
        EnergyHandler energyHandler = node.energy();
        if (!(energyHandler instanceof EnergyBuffer energy)) {
            return;
        }

        // Resolve the live drive context off the same block-entity ref + sibling components. Mirrors
        // MachineTickSystem.driveBench; guard every step.
        Ref<ChunkStore> blockRef = archetypeChunk.getReferenceTo(index);
        if (blockRef == null || !blockRef.isValid()) {
            return;
        }
        BlockModule.BlockStateInfo stateInfo = archetypeChunk.getComponent(index, blockInfoType);
        if (stateInfo == null) {
            return;
        }
        Ref<ChunkStore> chunkRef = stateInfo.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) {
            return;
        }
        BlockChunk blockChunk = chunkRef.getStore().getComponent(chunkRef, blockChunkType);
        if (blockChunk == null) {
            return;
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
            return;
        }
        World world = external.getWorld();
        if (world == null) {
            return;
        }
        BlockType blockType = resolveBlockType(blockChunk, localX, localY, localZ);
        if (blockType == null) {
            return;
        }

        // Hand the resolved context to the shared craft engine: it owns the full pull/craft/complete loop +
        // the block visual swap, configured by FORGE_SPEC. The engine never throws (the tick's catch is the
        // backstop).
        AutoCraftEngine.Context ctx = new AutoCraftEngine.Context(
            world, store, x, y, z, blockType, dt, energy, networkService, pipeType);
        AutoCraftEngine.drive(node, ctx, FORGE_SPEC);
    }

    /**
     * The {@link BlockType} at the Forge, resolved like {@code MachineTickSystem.resolveBlockType} (section
     * block id at the local coords -> asset map). Null if the section or id can't be resolved.
     */
    @Nullable
    private BlockType resolveBlockType(@Nonnull BlockChunk blockChunk, int localX, int localY, int localZ) {
        BlockSection section = blockChunk.getSectionAtBlockY(localY);
        if (section == null) {
            return null;
        }
        int blockId = section.get(localX, localY, localZ);
        return BlockType.getAssetMap().getAsset(blockId);
    }

    // --- the Forge's engine configuration (the machine-specific Spec) ---

    /**
     * The Forge's {@link AutoCraftEngine.Spec}: its bench pool (cached on first use, same as the old
     * {@code POOL} volatile), energy draw, post-craft delay, and the two improvement hooks. Today
     * {@code craftDuration} returns {@link #FORGE_DURATION} for every recipe and {@code allowSet} delegates
     * to {@link AutoCraftEngine#cardAllowSet}: identical to the pre-refactor inlined behavior.
     */
    private static final AutoCraftEngine.Spec FORGE_SPEC = new AutoCraftEngine.Spec() {
        @Override
        public RecipePool recipePool() {
            return forgeRecipePool();
        }

        @Override
        public long energyDraw() {
            return FORGE_DRAW;
        }

        @Override
        public int postCraftDelayTicks() {
            return POST_CRAFT_DELAY_TICKS;
        }

        @Override
        public float craftDuration(@Nullable CraftingRecipe r) {
            return FORGE_DURATION; // IMPROVEMENT AXIS: per-recipe craft time plugs in here.
        }

        @Override
        public Set<String> allowSet(AutoCraftNode node) {
            return AutoCraftEngine.cardAllowSet(node.card()); // IMPROVEMENT AXIS: recipe scripting.
        }
    };

    // --- shared recipe pool (load-time-fixed; built once, lazily) ---

    private static volatile RecipePool POOL;

    /**
     * The Forge's shared recipe pool, built once on first tick and cached (the recipe registry is
     * load-time-fixed). Unions the crafting + armory benches and dedups by id into a deterministic (natural
     * String) order via {@link RecipePool#union}.
     */
    private static RecipePool forgeRecipePool() {
        RecipePool p = POOL;
        if (p != null) {
            return p;
        }
        synchronized (ForgeTickSystem.class) {
            if (POOL != null) {
                return POOL;
            }
            RecipePool pool = RecipePool.union(List.of(
                new RecipePool.BenchRef(BenchType.Crafting, "Weapon_Bench"),
                new RecipePool.BenchRef(BenchType.Crafting, "Armor_Bench"),
                new RecipePool.BenchRef(BenchType.Crafting, "ArmorBench"),
                new RecipePool.BenchRef(BenchType.DiagramCrafting, "Armory")));
            POOL = pool;
            // Diagnostic (logged once): if this is 0, the bench ids did not resolve to recipes and the Forge
            // can never craft. Expected ~100 across Weapon_Bench / Armor_Bench / ArmorBench / Armory.
            FORGE_DRIVE_LOG.atInfo().log("Forge recipe pool built: " + pool.stableOrder().size() + " recipes.");
            return POOL;
        }
    }
}
