package com.chonbosmods.chemistry;

import com.chonbosmods.chemistry.api.registry.Chemistry;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.MachineBreakEventSystem;
import com.chonbosmods.chemistry.impl.block.MachinePlaceEventSystem;
import com.chonbosmods.chemistry.impl.block.MachineTickSystem;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.chonbosmods.chemistry.impl.block.net.NetworkService;
import com.chonbosmods.chemistry.impl.block.net.NetworkTickSystem;
import com.chonbosmods.chemistry.impl.block.net.PipeBreakEventSystem;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.PipePlaceEventSystem;
import com.chonbosmods.chemistry.impl.block.ui.MachinePanelPage;
import com.chonbosmods.chemistry.impl.block.ui.PanelRefreshService;
import com.chonbosmods.chemistry.impl.block.ui.PanelRefreshSystem;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import com.hypixel.hytale.component.ComponentType;
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
        tankComponentType = getChunkStoreRegistry()
            .registerComponent(TankBlockState.class, "TankBlockState", TankBlockState.CODEC);
        pipeComponentType = getChunkStoreRegistry()
            .registerComponent(PipeNode.class, "PipeNode", PipeNode.CODEC);
        getLogger().atInfo().log("Registered ChunkStore block-entity components: MachineBlockState, TankBlockState, PipeNode.");

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
            pipeComponentType, machineComponentType, tankComponentType, networkService));
        getLogger().atInfo().log("Registered NetworkTickSystem (per-tick pipe-network distribution).");
        getEntityStoreRegistry().registerSystem(new PipePlaceEventSystem(networkService, pipeComponentType));
        getEntityStoreRegistry().registerSystem(new PipeBreakEventSystem(networkService, pipeComponentType));

        // Machine contents-preservation (H7): a machine block broken with stored energy drops an item
        // carrying that energy (MachineBreakEventSystem), and placing such an item rehydrates the new
        // block entity's energy buffer (MachinePlaceEventSystem). Both fire on the EntityStore (the
        // acting entity), like the pipe events above. Pure capture/restore logic lives in
        // MachineEnergyMetadata (unit-tested); these systems are thin glue verified in-game.
        getEntityStoreRegistry().registerSystem(new MachineBreakEventSystem(machineComponentType, pipeComponentType, networkService));
        getEntityStoreRegistry().registerSystem(new MachinePlaceEventSystem(machineComponentType));
        getLogger().atInfo().log("Registered machine energy break/place preservation events.");
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
