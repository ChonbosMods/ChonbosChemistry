package com.chonbosmods.chemistry;

import com.chonbosmods.chemistry.api.registry.Chemistry;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.craft.CookerState;
import com.chonbosmods.chemistry.impl.block.craft.CookerTickSystem;
import com.chonbosmods.chemistry.impl.block.craft.OutfitterState;
import com.chonbosmods.chemistry.impl.block.craft.OutfitterTickSystem;
import com.chonbosmods.chemistry.impl.block.craft.AlembicState;
import com.chonbosmods.chemistry.impl.block.craft.AlembicTickSystem;
import com.chonbosmods.chemistry.impl.block.craft.AssemblerState;
import com.chonbosmods.chemistry.impl.block.craft.AssemblerTickSystem;
import com.chonbosmods.chemistry.impl.block.craft.CultivatorState;
import com.chonbosmods.chemistry.impl.block.craft.CultivatorTickSystem;
import com.chonbosmods.chemistry.impl.block.craft.SculptorState;
import com.chonbosmods.chemistry.impl.block.craft.SculptorTickSystem;
import com.chonbosmods.chemistry.impl.block.craft.ForgeCraftState;
import com.chonbosmods.chemistry.impl.block.craft.ForgeTickSystem;
import com.chonbosmods.chemistry.impl.block.CarryBreakEventSystem;
import com.chonbosmods.chemistry.impl.block.MachineTickSystem;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.chonbosmods.chemistry.impl.block.RecipeCardInteraction;
import com.chonbosmods.chemistry.impl.block.WrenchInteraction;
import com.chonbosmods.chemistry.impl.block.net.NetworkService;
import com.chonbosmods.chemistry.impl.block.net.NetworkTickSystem;
import com.chonbosmods.chemistry.impl.block.net.PipeBreakEventSystem;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.PipePlaceEventSystem;
import com.chonbosmods.chemistry.impl.block.ui.MachinePanelPage;
import com.chonbosmods.chemistry.impl.block.ui.BenchMachinePanelPage;
import com.chonbosmods.chemistry.impl.block.ui.ForgePanelPage;
import com.chonbosmods.chemistry.impl.block.ui.CookerPanelPage;
import com.chonbosmods.chemistry.impl.block.ui.OutfitterPanelPage;
import com.chonbosmods.chemistry.impl.block.ui.AlembicPanelPage;
import com.chonbosmods.chemistry.impl.block.ui.AssemblerPanelPage;
import com.chonbosmods.chemistry.impl.block.ui.CultivatorPanelPage;
import com.chonbosmods.chemistry.impl.block.ui.SculptorPanelPage;
import com.chonbosmods.chemistry.impl.block.ui.PanelRefreshService;
import com.chonbosmods.chemistry.impl.block.ui.PanelRefreshSystem;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;

/**
 * Chonbo's Chemistry: foundational chemistry library for Hytale.
 *
 * <p>A shared substance data model, a radiation engine, and a payload-agnostic
 * containment/affliction system, exposed through a clean API that an
 * interconnected family of chemistry mods builds on.
 *
 * <p>The codebase is split into two enforced top-level packages:
 * <ul>
 *   <li>{@code com.chonbosmods.chemistry.api}: interfaces, schema, and contracts.
 *       Defines; never decides. Zero gameplay, zero content, zero concrete values.</li>
 *   <li>{@code com.chonbosmods.chemistry.impl}: the engine, content, gear, instruments,
 *       and concrete values that implement those contracts.</li>
 * </ul>
 * This bootstrap lives in the package root because it bridges both halves; it is the
 * only class permitted to do so.
 */
public class ChonbosChemistry extends JavaPlugin {

    private static ChonbosChemistry instance;

    private ComponentType<ChunkStore, MachineBlockState> machineComponentType;
    private ComponentType<ChunkStore, ForgeCraftState> forgeComponentType;
    private ComponentType<ChunkStore, CookerState> cookerComponentType;
    private ComponentType<ChunkStore, OutfitterState> outfitterComponentType;
    private ComponentType<ChunkStore, AlembicState> alembicComponentType;
    private ComponentType<ChunkStore, AssemblerState> assemblerComponentType;
    private ComponentType<ChunkStore, CultivatorState> cultivatorComponentType;
    private ComponentType<ChunkStore, SculptorState> sculptorComponentType;
    private ComponentType<ChunkStore, TankBlockState> tankComponentType;
    private ComponentType<ChunkStore, PipeNode> pipeComponentType;

    /** Per-world cache of transport pipe networks: shared by the invalidation events and the tick. */
    private NetworkService networkService;

    /** Per-world registry + cadence for live panel refresh (pages register; PanelRefreshSystem ticks). */
    private PanelRefreshService panelRefreshService;

    public ChonbosChemistry(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static ChonbosChemistry getInstance() {
        return instance;
    }

    /** The {@link ChunkStore} component type backing machine blocks (used by the ticking system + GUI). */
    public ComponentType<ChunkStore, MachineBlockState> machineComponentType() {
        return machineComponentType;
    }

    /** The {@link ChunkStore} component type backing Forge blocks (the autonomous crafter's own state). */
    public ComponentType<ChunkStore, ForgeCraftState> forgeComponentType() {
        return forgeComponentType;
    }

    /** The {@link ChunkStore} component type backing Cooker blocks (the autonomous crafter's own state). */
    public ComponentType<ChunkStore, CookerState> cookerComponentType() {
        return cookerComponentType;
    }

    /** The {@link ChunkStore} component type backing Outfitter blocks (the autonomous crafter's own state). */
    public ComponentType<ChunkStore, OutfitterState> outfitterComponentType() {
        return outfitterComponentType;
    }

    /** The {@link ChunkStore} component type backing Alembic blocks (the autonomous crafter's own state). */
    public ComponentType<ChunkStore, AlembicState> alembicComponentType() {
        return alembicComponentType;
    }

    /** The {@link ChunkStore} component type backing Assembler blocks (the autonomous crafter's own state). */
    public ComponentType<ChunkStore, AssemblerState> assemblerComponentType() {
        return assemblerComponentType;
    }

    /** The {@link ChunkStore} component type backing Cultivator blocks (the autonomous crafter's own state). */
    public ComponentType<ChunkStore, CultivatorState> cultivatorComponentType() {
        return cultivatorComponentType;
    }

    /** The {@link ChunkStore} component type backing Sculptor blocks (the autonomous crafter's own state). */
    public ComponentType<ChunkStore, SculptorState> sculptorComponentType() {
        return sculptorComponentType;
    }

    /** The {@link ChunkStore} component type backing tank blocks (used by the ticking system + GUI). */
    public ComponentType<ChunkStore, TankBlockState> tankComponentType() {
        return tankComponentType;
    }

    /** The {@link ChunkStore} component type backing pipe blocks (used by the transport network layer). */
    public ComponentType<ChunkStore, PipeNode> pipeComponentType() {
        return pipeComponentType;
    }

    /** The per-world {@link NetworkService} caching pipe transport networks (used by events + tick). */
    public NetworkService networkService() {
        return networkService;
    }

    /** The live-panel refresh registry ({@code MachinePanelPage} registers/deregisters itself here). */
    public PanelRefreshService panelRefreshService() {
        return panelRefreshService;
    }

    @Override
    protected void setup() {
        getLogger().atInfo().log("Chonbo's Chemistry setting up...");
        InMemorySubstanceRegistry registry = InMemorySubstanceRegistry.loadFromResources();
        Chemistry.set(registry);
        getLogger().atInfo().log("Loaded " + registry.elements().size() + " elements, "
            + registry.compounds().size() + " compounds, " + registry.isotopes().size() + " isotopes.");

        machineComponentType = getChunkStoreRegistry()
            .registerComponent(MachineBlockState.class, "MachineBlockState", MachineBlockState.CODEC);
        forgeComponentType = getChunkStoreRegistry()
            .registerComponent(ForgeCraftState.class, "ForgeCraftState", ForgeCraftState.CODEC);
        cookerComponentType = getChunkStoreRegistry()
            .registerComponent(CookerState.class, "CookerState", CookerState.CODEC);
        outfitterComponentType = getChunkStoreRegistry()
            .registerComponent(OutfitterState.class, "OutfitterState", OutfitterState.CODEC);
        alembicComponentType = getChunkStoreRegistry()
            .registerComponent(AlembicState.class, "AlembicState", AlembicState.CODEC);
        assemblerComponentType = getChunkStoreRegistry()
            .registerComponent(AssemblerState.class, "AssemblerState", AssemblerState.CODEC);
        cultivatorComponentType = getChunkStoreRegistry()
            .registerComponent(CultivatorState.class, "CultivatorState", CultivatorState.CODEC);
        sculptorComponentType = getChunkStoreRegistry()
            .registerComponent(SculptorState.class, "SculptorState", SculptorState.CODEC);
        tankComponentType = getChunkStoreRegistry()
            .registerComponent(TankBlockState.class, "TankBlockState", TankBlockState.CODEC);
        pipeComponentType = getChunkStoreRegistry()
            .registerComponent(PipeNode.class, "PipeNode", PipeNode.CODEC);
        getLogger().atInfo().log("Registered ChunkStore block-entity components: MachineBlockState, ForgeCraftState, CookerState, OutfitterState, AlembicState, AssemblerState, CultivatorState, SculptorState, TankBlockState, PipeNode.");

        // Per-tick driver: creative-refill then work pass. Must be registered AFTER the components above.
        // (No longer pushes resources to neighbors: the NetworkTickSystem below does that over pipes.)
        getChunkStoreRegistry().registerSystem(new MachineTickSystem(machineComponentType));
        getLogger().atInfo().log("Registered MachineTickSystem (creative-refill then work).");

        // Transport network cache (per-world) + the events that invalidate it on pipe topology changes
        // (H3). Place/break fire on the EntityStore (the acting entity); chunk-unload fires on the
        // ChunkStore (the unloading chunk), so they register on their respective registries.
        networkService = new NetworkService();
        // Per-tick pipe-network distribution (H4): for each pipe network, pull from OUTPUT-port machines
        // and fair-split into INPUT-port machines once per tick. This is what moves resources now.
        getChunkStoreRegistry().registerSystem(new NetworkTickSystem(
            pipeComponentType, machineComponentType, tankComponentType, forgeComponentType, cookerComponentType,
            outfitterComponentType, alembicComponentType, assemblerComponentType, cultivatorComponentType,
            sculptorComponentType, networkService));
        getLogger().atInfo().log("Registered NetworkTickSystem (per-tick pipe-network distribution).");
        // Demand-driven craft driver for the Forge (no held bench; own containers + even round-robin
        // selection). Registered AFTER networkService: it pulls each craft's ingredients from the ITEM
        // pipe network on its input face (idle -> pull -> craft -> complete -> repeat).
        getChunkStoreRegistry().registerSystem(
            new ForgeTickSystem(forgeComponentType, pipeComponentType, networkService));
        getLogger().atInfo().log("Registered ForgeTickSystem (autonomous crafting).");
        // Demand-driven craft driver for the Cooker (sibling of the Forge: no held bench; own containers +
        // round-robin selection). Registered AFTER networkService: it pulls each craft's ingredients from
        // the ITEM pipe network on its input face (idle -> pull -> craft -> complete -> repeat).
        getChunkStoreRegistry().registerSystem(
            new CookerTickSystem(cookerComponentType, pipeComponentType, networkService));
        getLogger().atInfo().log("Registered CookerTickSystem (autonomous crafting).");
        // Demand-driven craft driver for the Outfitter (sibling of the Cooker: no held bench; own containers +
        // round-robin selection). Registered AFTER networkService: it pulls each craft's ingredients from
        // the ITEM pipe network on its input face (idle -> pull -> craft -> complete -> repeat).
        getChunkStoreRegistry().registerSystem(
            new OutfitterTickSystem(outfitterComponentType, pipeComponentType, networkService));
        getLogger().atInfo().log("Registered OutfitterTickSystem (autonomous crafting).");
        // Demand-driven craft driver for the Alembic (sibling of the Outfitter: no held bench; own containers +
        // round-robin selection). Registered AFTER networkService: it pulls each craft's ingredients from
        // the ITEM pipe network on its input face (idle -> pull -> craft -> complete -> repeat).
        getChunkStoreRegistry().registerSystem(
            new AlembicTickSystem(alembicComponentType, pipeComponentType, networkService));
        getLogger().atInfo().log("Registered AlembicTickSystem (autonomous crafting).");
        // Demand-driven craft driver for the Assembler (sibling of the Alembic: no held bench; own containers +
        // round-robin selection). Registered AFTER networkService: it pulls each craft's ingredients from
        // the ITEM pipe network on its input face (idle -> pull -> craft -> complete -> repeat).
        getChunkStoreRegistry().registerSystem(
            new AssemblerTickSystem(assemblerComponentType, pipeComponentType, networkService));
        getLogger().atInfo().log("Registered AssemblerTickSystem (autonomous crafting).");
        // Demand-driven craft driver for the Cultivator (sibling of the Assembler: no held bench; own containers +
        // round-robin selection) sourcing the Farmingbench recipe pool. Registered AFTER networkService: it pulls
        // each craft's ingredients from the ITEM pipe network on its input face (idle -> pull -> craft -> complete).
        getChunkStoreRegistry().registerSystem(
            new CultivatorTickSystem(cultivatorComponentType, pipeComponentType, networkService));
        getLogger().atInfo().log("Registered CultivatorTickSystem (autonomous crafting).");
        // Demand-driven craft driver for the Sculptor (sibling of the Alembic: no held bench; own containers +
        // round-robin selection) on the Reclaimer's 1x1x2 footprint. INERT until a recipe-script item is in its
        // card slot (the gate lives in SculptorTickSystem.scriptGateAllowSet). Registered AFTER networkService.
        getChunkStoreRegistry().registerSystem(
            new SculptorTickSystem(sculptorComponentType, pipeComponentType, networkService));
        getLogger().atInfo().log("Registered SculptorTickSystem (autonomous crafting, script-gated).");
        getEntityStoreRegistry().registerSystem(new PipePlaceEventSystem(networkService, pipeComponentType));
        getEntityStoreRegistry().registerSystem(new PipeBreakEventSystem(networkService, pipeComponentType));

        // Contents-preservation (H7, rebuilt 2026-06-05 on the engine's native BlockHolder path): a
        // machine, tank, or Forge broken with contents drops an item carrying the FULL encoded block
        // entity under "BlockHolder" (CarryBreakEventSystem). Placement needs no mod code: the engine's
        // BlockPlaceUtils.onPlaceBlockSuccess natively restores every component. Pure stamping/predicate
        // logic lives in BlockHolderCarry (unit-tested); the system is thin glue verified in-game.
        getEntityStoreRegistry().registerSystem(new CarryBreakEventSystem(
            machineComponentType, tankComponentType, forgeComponentType, cookerComponentType, outfitterComponentType,
            alembicComponentType, assemblerComponentType, cultivatorComponentType, sculptorComponentType,
            pipeComponentType, networkService));
        getLogger().atInfo().log("Registered CarryBreakEventSystem (BlockHolder contents carry).");
        // NOTE: NO ChunkUnloadEvent handler. In Server 0.5.3 the engine's ChunkUnloadingSystem dispatches
        // ChunkUnloadEvent from PARALLEL worker threads (forEachEntityParallel), and delivering it to an
        // EntityEventSystem forks a command buffer that asserts it is on the WorldThread -> the WorldThread
        // dies (IllegalStateException: Assert not in thread). So chunk-unload cache eviction cannot use a
        // system handler here. Cached networks for unloaded chunks are a minor memory concern only: they
        // rebuild lazily on next access and are still invalidated on pipe place/break. TODO(H6): persist
        // buffer shares + evict on unload via a WorldThread-safe hook (NetworkManager.invalidateChunk is ready).
        getLogger().atInfo().log("Registered pipe-network invalidation events (place, break).");

        // Block GUI (Task B4): right-clicking a rig block resolves its block entity and opens a
        // snapshot panel showing energy / resource-buffer gauges. The "CC_MachinePanel" id is
        // referenced by each rig block's Interactions.Use (Type: OpenCustomUI, Page.Id) in JSON.
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, MachinePanelPage.class, "CC_MachinePanel", MachinePanelPage::new);
        getLogger().atInfo().log("Registered CC_MachinePanel custom UI page (block GUI).");

        // Shared bench-machine panel (read-only I/O + On/Off + Eject + live progress/power), one page
        // class per machine, parameterized by title + active verb. The "CC_<Machine>Panel" id is
        // referenced by each machine block's Interactions.Use (OpenCustomUI Page.Id) in JSON.
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, BenchMachinePanelPage.class, "CC_SmelterPanel",
            (playerRef, blockRef) -> new BenchMachinePanelPage(playerRef, blockRef, "Smelter"));
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, BenchMachinePanelPage.class, "CC_ReclaimerPanel",
            (playerRef, blockRef) -> new BenchMachinePanelPage(playerRef, blockRef, "Reclaimer"));
        getLogger().atInfo().log("Registered CC_SmelterPanel + CC_ReclaimerPanel bench-machine GUIs.");

        // The Forge panel: a sibling of the bench-machine GUI, but reading the autonomous Forge's own
        // ForgeCraftState (own input/output containers + recipe-card slot) rather than a held vanilla bench.
        // The "CC_ForgePanel" id is referenced by CC_Forge.json's Interactions.Use (OpenCustomUI Page.Id).
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, ForgePanelPage.class, "CC_ForgePanel",
            (playerRef, blockRef) -> new ForgePanelPage(playerRef, blockRef, "Forge"));
        getLogger().atInfo().log("Registered CC_ForgePanel forge GUI.");

        // The Cooker panel: a sibling of the Forge GUI (a pure auto-craft machine), reading the autonomous
        // Cooker's own CookerState (own input/output containers + recipe-card slot) rather than a held vanilla
        // bench. The "CC_CookerPanel" id is referenced by CC_Cooker.json's Interactions.Use (OpenCustomUI Page.Id).
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, CookerPanelPage.class, "CC_CookerPanel",
            (playerRef, blockRef) -> new CookerPanelPage(playerRef, blockRef, "Cooker"));
        getLogger().atInfo().log("Registered CC_CookerPanel cooker GUI.");

        // The Outfitter panel: a sibling of the Cooker GUI (a pure auto-craft machine), reading the autonomous
        // Outfitter's own OutfitterState (own input/output containers + recipe-card slot) rather than a held
        // vanilla bench. The "CC_OutfitterPanel" id is referenced by CC_Outfitter.json's Interactions.Use
        // (OpenCustomUI Page.Id).
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, OutfitterPanelPage.class, "CC_OutfitterPanel",
            (playerRef, blockRef) -> new OutfitterPanelPage(playerRef, blockRef, "Outfitter"));
        getLogger().atInfo().log("Registered CC_OutfitterPanel outfitter GUI.");

        // The Alembic panel: a sibling of the Outfitter GUI (a pure auto-craft machine), reading the autonomous
        // Alembic's own AlembicState (own input/output containers + recipe-card slot) rather than a held
        // vanilla bench. The "CC_AlembicPanel" id is referenced by CC_Alembic.json's Interactions.Use
        // (OpenCustomUI Page.Id).
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, AlembicPanelPage.class, "CC_AlembicPanel",
            (playerRef, blockRef) -> new AlembicPanelPage(playerRef, blockRef, "Alembic"));
        getLogger().atInfo().log("Registered CC_AlembicPanel alembic GUI.");

        // The Assembler panel: a sibling of the Alembic GUI (a pure auto-craft machine), reading the autonomous
        // Assembler's own AssemblerState (own input/output containers + recipe-card slot) rather than a held
        // vanilla bench. The "CC_AssemblerPanel" id is referenced by CC_Assembler.json's Interactions.Use
        // (OpenCustomUI Page.Id).
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, AssemblerPanelPage.class, "CC_AssemblerPanel",
            (playerRef, blockRef) -> new AssemblerPanelPage(playerRef, blockRef, "Assembler"));
        getLogger().atInfo().log("Registered CC_AssemblerPanel assembler GUI.");

        // The Cultivator panel: a sibling of the Assembler GUI (a pure auto-craft machine), reading the autonomous
        // Cultivator's own CultivatorState (own input/output containers + recipe-card slot) rather than a held
        // vanilla bench. The "CC_CultivatorPanel" id is referenced by CC_Cultivator.json's Interactions.Use
        // (OpenCustomUI Page.Id).
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, CultivatorPanelPage.class, "CC_CultivatorPanel",
            (playerRef, blockRef) -> new CultivatorPanelPage(playerRef, blockRef, "Cultivator"));
        getLogger().atInfo().log("Registered CC_CultivatorPanel cultivator GUI.");

        // The Sculptor panel: a sibling of the Alembic GUI (a pure auto-craft machine on the Reclaimer's
        // footprint), reading the autonomous Sculptor's own SculptorState (own input/output containers +
        // recipe-script slot) rather than a held vanilla bench. The "CC_SculptorPanel" id is referenced by
        // CC_Sculptor.json's Interactions.Use (OpenCustomUI Page.Id).
        OpenCustomUIInteraction.registerBlockEntityCustomPage(
            this, SculptorPanelPage.class, "CC_SculptorPanel",
            (playerRef, blockRef) -> new SculptorPanelPage(playerRef, blockRef, "Sculptor"));
        getLogger().atInfo().log("Registered CC_SculptorPanel sculptor GUI.");

        // CC_Wrench (Task 9): a held tool whose Secondary interaction taps a pipe face to cycle its
        // flow state, or a machine face to cycle its port. The JSON "Type" is this registered id; the
        // item must set "UseLatestTarget": true so the clicked BlockFace flows through (spike Task 8).
        getCodecRegistry(Interaction.CODEC).register(
            "cc_wrench", WrenchInteraction.class, WrenchInteraction.CODEC);
        getLogger().atInfo().log("Registered cc_wrench interaction (CC_Wrench pipe/machine face config).");

        // CC_RecipeScript: a held card whose Secondary interaction inserts (or swaps) the card into a CC
        // auto-crafter machine's card slot. Like the wrench, the item must set "UseLatestTarget": true and
        // be tool-like (BlockSelectorTool) so the targeted blockPos flows through to interactWithBlock.
        getCodecRegistry(Interaction.CODEC).register(
            "cc_recipe_card", RecipeCardInteraction.class, RecipeCardInteraction.CODEC);
        getLogger().atInfo().log("Registered cc_recipe_card interaction (CC_RecipeScript insert/swap).");

        // Live panel refresh (2026-06-05 design): pages register with the service after a successful
        // build; this query-less per-world pulse refreshes them every 10th tick on the WorldThread.
        panelRefreshService = new PanelRefreshService();
        getEntityStoreRegistry().registerSystem(new PanelRefreshSystem(panelRefreshService));
        getLogger().atInfo().log("Registered PanelRefreshSystem (live panel refresh, every "
            + PanelRefreshService.REFRESH_INTERVAL_TICKS + " ticks).");
    }

    @Override
    protected void start() {
        getLogger().atInfo().log("Chonbo's Chemistry v" + getManifest().getVersion() + " started!");
    }

    @Override
    protected void shutdown() {
        Chemistry.clear();
        getLogger().atInfo().log("Chonbo's Chemistry shutting down...");
    }
}
