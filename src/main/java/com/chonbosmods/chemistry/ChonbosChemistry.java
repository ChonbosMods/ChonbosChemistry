package com.chonbosmods.chemistry;

import com.chonbosmods.chemistry.api.registry.Chemistry;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.craft.CookerState;
import com.chonbosmods.chemistry.impl.block.craft.CookerTickSystem;
import com.chonbosmods.chemistry.impl.block.craft.ForgeCraftState;
import com.chonbosmods.chemistry.impl.block.craft.ForgeTickSystem;
import com.chonbosmods.chemistry.impl.block.CarryBreakEventSystem;
import com.chonbosmods.chemistry.impl.block.MachineTickSystem;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.chonbosmods.chemistry.impl.block.WrenchInteraction;
import com.chonbosmods.chemistry.impl.block.net.NetworkService;
import com.chonbosmods.chemistry.impl.block.net.NetworkTickSystem;
import com.chonbosmods.chemistry.impl.block.net.PipeBreakEventSystem;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.PipePlaceEventSystem;
import com.chonbosmods.chemistry.impl.block.ui.MachinePanelPage;
import com.chonbosmods.chemistry.impl.block.ui.BenchMachinePanelPage;
import com.chonbosmods.chemistry.impl.block.ui.ForgePanelPage;
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
        tankComponentType = getChunkStoreRegistry()
            .registerComponent(TankBlockState.class, "TankBlockState", TankBlockState.CODEC);
        pipeComponentType = getChunkStoreRegistry()
            .registerComponent(PipeNode.class, "PipeNode", PipeNode.CODEC);
        getLogger().atInfo().log("Registered ChunkStore block-entity components: MachineBlockState, ForgeCraftState, CookerState, TankBlockState, PipeNode.");

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
            networkService));
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
        getEntityStoreRegistry().registerSystem(new PipePlaceEventSystem(networkService, pipeComponentType));
        getEntityStoreRegistry().registerSystem(new PipeBreakEventSystem(networkService, pipeComponentType));

        // Contents-preservation (H7, rebuilt 2026-06-05 on the engine's native BlockHolder path): a
        // machine, tank, or Forge broken with contents drops an item carrying the FULL encoded block
        // entity under "BlockHolder" (CarryBreakEventSystem). Placement needs no mod code: the engine's
        // BlockPlaceUtils.onPlaceBlockSuccess natively restores every component. Pure stamping/predicate
        // logic lives in BlockHolderCarry (unit-tested); the system is thin glue verified in-game.
        getEntityStoreRegistry().registerSystem(new CarryBreakEventSystem(
            machineComponentType, tankComponentType, forgeComponentType, cookerComponentType, pipeComponentType,
            networkService));
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

        // CC_Wrench (Task 9): a held tool whose Secondary interaction taps a pipe face to cycle its
        // flow state, or a machine face to cycle its port. The JSON "Type" is this registered id; the
        // item must set "UseLatestTarget": true so the clicked BlockFace flows through (spike Task 8).
        getCodecRegistry(Interaction.CODEC).register(
            "cc_wrench", WrenchInteraction.class, WrenchInteraction.CODEC);
        getLogger().atInfo().log("Registered cc_wrench interaction (CC_Wrench pipe/machine face config).");

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
