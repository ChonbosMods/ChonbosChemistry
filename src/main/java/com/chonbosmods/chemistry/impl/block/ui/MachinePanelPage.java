package com.chonbosmods.chemistry.impl.block.ui;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.WorldPipeGridView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints;
import com.chonbosmods.chemistry.impl.block.net.item.WorldContainerLookup;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Server-side block GUI for Chonbo's Chemistry rig blocks (Task B4; live since the 2026-06-05
 * live-refresh design, {@code docs/plans/2026-06-05-live-refresh-panel-design.md}).
 *
 * <p>Interacting (Interact key, default F) with a machine, tank, or pipe block opens this page. The
 * panel's content is computed as a {@link PanelSnapshot} (pure, unit-tested render model) from the
 * block's {@link MachineBlockState} / {@link TankBlockState} component, or, for a {@link PipeNode
 * pipe}, from its resolved shared-buffer {@link Network} (the NETWORK readout: stored/capacity, pipe
 * count, bottleneck throughput).
 *
 * <p><b>Live refresh:</b> after a successful {@link #build}, the page registers itself with the
 * plugin's {@link PanelRefreshService}; every {@link PanelRefreshService#REFRESH_INTERVAL_TICKS}
 * ticks {@code PanelRefreshSystem} drives {@link #refresh()}, which recomputes the snapshot and
 * {@link #sendUpdate(UICommandBuilder)}s a pure delta of {@code set} commands (no template
 * re-append). If the block or its network is gone, or the player ref died, the panel auto-closes and
 * drops out of the registry. Empty-state pages (invalid ref at open, non-chemistry block, network
 * unavailable at open) never register: they stay static snapshots.
 *
 * <p>The UI template ({@code Pages/CC_MachinePanel.ui}) mirrors the proven Natural20 structure
 * ({@code @PageOverlay} + {@code @Container} + plain {@code Label} rows; no {@code @ProgressBar});
 * each gauge is a text label. {@link PanelSnapshot} sets every row's visibility explicitly on every
 * pass, so rows whose buffer vanishes hide instead of freezing.
 *
 * <p><b>Threading:</b> {@link #build} runs on the WorldThread (the engine's interaction tick calls
 * {@code PageManager.openCustomPage} synchronously), and {@code PanelRefreshSystem} is a
 * world-store ticking system, so {@link #refresh()} runs on the same WorldThread: calling
 * {@code NetworkManager.getOrBuildNetwork} is safe in both paths (per-world cache, plain HashMaps,
 * single-threaded-per-world).
 */
public final class MachinePanelPage extends CustomUIPage implements PanelRefreshService.LivePanel {

    @Nonnull
    private final Ref<ChunkStore> blockRef;

    /** Built-in component types for decoding a pipe block's world position. Stable for the world's life. */
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType =
        BlockModule.BlockStateInfo.getComponentType();
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType = BlockChunk.getComponentType();

    /** The world this page registered under (null until a successful build registers it). */
    private World registeredWorld;

    public MachinePanelPage(@Nonnull PlayerRef playerRef, @Nonnull Ref<ChunkStore> blockRef) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction);
        this.blockRef = blockRef;
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/CC_MachinePanel.ui");

        PanelSnapshot snapshot = this.computeSnapshot();
        applySnapshot(commandBuilder, snapshot);

        // Live registration: only pages showing a real block/network refresh; empty-state pages stay
        // static snapshots (there is nothing live to watch).
        if (snapshot.live()) {
            World world = this.resolveWorld();
            if (world != null) {
                this.registeredWorld = world;
                ChonbosChemistry.getInstance().panelRefreshService().register(world, this);
            }
        }
    }

    /**
     * Recompute and re-send the panel (PanelRefreshService.LivePanel). Runs on this world's
     * WorldThread. Auto-closes (and reports dead) when the block, its network, or the player is gone.
     */
    @Override
    public boolean refresh() {
        if (!this.playerRef.isValid()) {
            return false; // player gone; engine tears the page down, nothing to send
        }
        PanelSnapshot snapshot = this.computeSnapshot();
        if (!snapshot.live()) {
            this.close(); // block broken / network gone mid-view: auto-close (design decision)
            return false;
        }
        UICommandBuilder update = new UICommandBuilder();
        applySnapshot(update, snapshot);
        this.sendUpdate(update);
        return true;
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (this.registeredWorld != null) {
            ChonbosChemistry.getInstance().panelRefreshService().deregister(this.registeredWorld, this);
            this.registeredWorld = null;
        }
        super.onDismiss(ref, store);
    }

    /**
     * Compute the current render model for the viewed block. Never throws: every failure path
     * (invalid ref, missing component, unreachable world/network) yields a non-live empty snapshot.
     */
    @Nonnull
    private PanelSnapshot computeSnapshot() {
        if (!this.blockRef.isValid()) {
            return PanelSnapshot.empty("Machine", "This block is no longer valid.");
        }

        Store<ChunkStore> blockStore = this.blockRef.getStore();
        ChonbosChemistry mod = ChonbosChemistry.getInstance();

        MachineBlockState machine = blockStore.getComponent(this.blockRef, mod.machineComponentType());
        if (machine != null) {
            return PanelSnapshot.forMachine(machine);
        }

        TankBlockState tank = blockStore.getComponent(this.blockRef, mod.tankComponentType());
        if (tank != null) {
            return PanelSnapshot.forTank(tank);
        }

        PipeNode pipe = blockStore.getComponent(this.blockRef, mod.pipeComponentType());
        if (pipe != null) {
            return this.computePipeSnapshot(blockStore, mod, pipe);
        }

        return PanelSnapshot.empty("Machine", "This is not a chemistry block.");
    }

    /**
     * Render model for a pipe block: resolve its network (WorldThread-safe, see class javadoc) and
     * show the network's aggregate buffer + member count + bottleneck throughput, plus the clicked
     * {@code pipe}'s per-face flow states. Null-safe at every step; any failure falls back to the
     * non-live "Network unavailable." empty state.
     */
    @Nonnull
    private PanelSnapshot computePipeSnapshot(
            @Nonnull Store<ChunkStore> blockStore, @Nonnull ChonbosChemistry mod, @Nonnull PipeNode pipe) {
        // Decode the clicked block's world (x,y,z) via BlockStateInfo + BlockChunk (same technique as
        // NetworkTickSystem), but reading the components off this.blockRef rather than an ArchetypeChunk.
        BlockModule.BlockStateInfo info = blockStore.getComponent(this.blockRef, this.blockInfoType);
        if (info == null) {
            return PanelSnapshot.empty("Pipe", "Network unavailable.");
        }
        Ref<ChunkStore> chunkRef = info.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) {
            return PanelSnapshot.empty("Pipe", "Network unavailable.");
        }
        BlockChunk blockChunk = chunkRef.getStore().getComponent(chunkRef, this.blockChunkType);
        if (blockChunk == null) {
            return PanelSnapshot.empty("Pipe", "Network unavailable.");
        }
        int blockIndex = info.getIndex();
        int x = (blockChunk.getX() << 5) | ChunkUtil.xFromBlockInColumn(blockIndex);
        int y = ChunkUtil.yFromBlockInColumn(blockIndex);
        int z = (blockChunk.getZ() << 5) | ChunkUtil.zFromBlockInColumn(blockIndex);

        World world = this.resolveWorld();
        if (world == null) {
            return PanelSnapshot.empty("Pipe", "Network unavailable.");
        }

        // Resolve (and cache) the network. A null result means there is no pipe at this position any
        // more (e.g. broken mid-interaction / mid-view).
        NetworkManager manager = mod.networkService().forWorld(world);
        WorldPipeGridView grid = new WorldPipeGridView(world, blockStore, mod.pipeComponentType());
        Network net = manager.getOrBuildNetwork(x, y, z, grid);
        if (net == null) {
            return PanelSnapshot.empty("Pipe", "Network unavailable.");
        }
        // ITEM networks have no shared buffer: compute the discrete-transport stats and pass them in (the
        // snapshot stays pure). Every other channel renders the fungible gauge with null stats.
        PanelSnapshot.ItemNetworkStats stats =
            net.channel() == PortChannel.ITEM ? computeItemStats(net, grid, world, blockStore) : null;
        return PanelSnapshot.forNetwork(net, pipe, stats);
    }

    /**
     * Aggregate an ITEM network's discrete-transport counts for the panel: in-transit = the sum of every
     * member pipe's {@code inTransit()} size; destinations/sources = the {@link ItemEndpoints} container
     * endpoints around the network. WorldThread-safe by the same reasoning as the rest of this page (the
     * class javadoc): build and refresh both run on this world's WorldThread, so resolving member pipe
     * nodes through the grid and a {@link WorldContainerLookup} over the same block store is safe.
     */
    @Nonnull
    private PanelSnapshot.ItemNetworkStats computeItemStats(
            @Nonnull Network net,
            @Nonnull WorldPipeGridView grid,
            @Nonnull World world,
            @Nonnull Store<ChunkStore> blockStore) {
        int inTransit = 0;
        for (long memberKey : net.memberKeys()) {
            int mx = NetworkManager.unpackX(memberKey);
            int my = NetworkManager.unpackY(memberKey);
            int mz = NetworkManager.unpackZ(memberKey);
            PipeNode member = grid.pipeAt(mx, my, mz);
            if (member != null) {
                inTransit += member.inTransit().size();
            }
        }
        ItemEndpoints.Endpoints endpoints =
            ItemEndpoints.collect(net, grid, new WorldContainerLookup(world, blockStore));
        return new PanelSnapshot.ItemNetworkStats(
            inTransit, endpoints.destinations().size(), endpoints.sources().size());
    }

    /** The viewed block's world, off the block ref's external ChunkStore. Null on any failure. */
    private World resolveWorld() {
        if (!this.blockRef.isValid()) {
            return null;
        }
        ChunkStore external = this.blockRef.getStore().getExternalData();
        return external == null ? null : external.getWorld();
    }

    /** Write a snapshot to a command builder: title + every row's explicit visibility and text. */
    private static void applySnapshot(@Nonnull UICommandBuilder cmd, @Nonnull PanelSnapshot snapshot) {
        // Rows are Message.raw(...) literals rather than translation keys: the mod's server.lang is
        // generated + gitignored, so the panel shouldn't depend on lang entries.
        cmd.set("#BlockTitle.TextSpans", Message.raw(snapshot.title()));
        for (PanelSnapshot.Row row : snapshot.rows()) {
            cmd.set(row.selector() + ".Visible", row.visible());
            if (row.visible()) {
                cmd.set(row.selector() + ".TextSpans", Message.raw(row.text()));
            }
        }
    }
}
