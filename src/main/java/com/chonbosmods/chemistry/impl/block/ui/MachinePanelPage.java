package com.chonbosmods.chemistry.impl.block.ui;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.WorldPipeGridView;
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
 * Server-side block GUI for Chonbo's Chemistry rig blocks (Task B4, snapshot v1).
 *
 * <p>Interacting (Interact key, default F) with a machine, tank, or pipe block opens this page. It
 * reads the block's {@link MachineBlockState} or {@link TankBlockState} component off the clicked
 * block entity's {@code Ref<ChunkStore>} and renders an energy readout (machines with power only)
 * plus one row per non-null channel buffer (FLUID / GAS / ITEM), each showing
 * {@code amount / capacity (pct%)}.
 *
 * <p>For a {@link PipeNode pipe} block there is no per-block buffer to show: a pipe is one segment of
 * a shared-buffer {@link Network}. This page instead resolves the pipe's network (via
 * {@code NetworkService.forWorld(world).getOrBuildNetwork(...)}) and renders the NETWORK readout: the
 * unified shared buffer ({@code stored / capacity (pct%)}), the pipe (member) count, and the network's
 * bottleneck throughput per tick. Pipes are POWER-only today, but the channel name in the title is
 * derived from {@link Network#channel()} so this reads correctly if FLUID/GAS pipes arrive later.
 *
 * <p>The UI template ({@code Pages/CC_MachinePanel.ui}) mirrors the proven Natural20 structure
 * ({@code @PageOverlay} + {@code @Container} + plain {@code Label} rows; no {@code @ProgressBar}),
 * so each gauge is a text label toggled visible and given its text here.
 *
 * <p><b>Snapshot only:</b> values are read once at {@link #build}. There is no live refresh tick.
 * A future live variant would re-run the population from a scheduled task via
 * {@link #sendUpdate(UICommandBuilder)} (see the marker comment in {@link #build}).
 *
 * <p><b>Threading:</b> {@link #build} runs on the WorldThread: the engine processes the Interact
 * interaction inside an {@code EntityTickingSystem} (its {@code TickInteractionManagerSystem}), whose
 * {@code firstRun} calls {@code PageManager.openCustomPage} which invokes {@link #build} synchronously
 * in that same call stack. That is the same thread {@code NetworkTickSystem} runs on, so calling
 * {@code getOrBuildNetwork} here is safe against the per-world {@code NetworkManager} cache (plain
 * HashMaps, single-threaded-per-world).
 */
public final class MachinePanelPage extends CustomUIPage {

    @Nonnull
    private final Ref<ChunkStore> blockRef;

    /** Built-in component types for decoding a pipe block's world position. Stable for the world's life. */
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType =
        BlockModule.BlockStateInfo.getComponentType();
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType = BlockChunk.getComponentType();

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

        // NOTE (future live refresh): for a live panel, re-run the population below from a scheduled
        // task that calls sendUpdate(commandBuilder). v1 is a one-shot snapshot, populated inline.
        if (!this.blockRef.isValid()) {
            this.showEmpty(commandBuilder, "Machine", "This block is no longer valid.");
            return;
        }

        Store<ChunkStore> blockStore = this.blockRef.getStore();
        ChonbosChemistry mod = ChonbosChemistry.getInstance();

        MachineBlockState machine = blockStore.getComponent(this.blockRef, mod.machineComponentType());
        if (machine != null) {
            this.populateMachine(commandBuilder, machine);
            return;
        }

        TankBlockState tank = blockStore.getComponent(this.blockRef, mod.tankComponentType());
        if (tank != null) {
            this.populateTank(commandBuilder, tank);
            return;
        }

        PipeNode pipe = blockStore.getComponent(this.blockRef, mod.pipeComponentType());
        if (pipe != null) {
            this.populatePipe(commandBuilder, blockStore, mod);
            return;
        }

        this.showEmpty(commandBuilder, "Machine", "This is not a chemistry block.");
    }

    /**
     * Render the NETWORK readout for a pipe block. A pipe carries no per-block buffer: it is one
     * segment of a shared-buffer {@link Network}, so we decode the pipe's world position, resolve its
     * network, and show the network's aggregate buffer + member count + bottleneck throughput.
     *
     * <p>Null-safe at every step: this page must NEVER throw. Any failure to decode the position, reach
     * the world, or resolve the network falls back to {@code showEmpty(.., "Pipe", "Network unavailable.")}.
     * Calling {@code getOrBuildNetwork} is safe here because {@link #build} runs on the WorldThread (see
     * the class javadoc).
     */
    private void populatePipe(
            @Nonnull UICommandBuilder cmd,
            @Nonnull Store<ChunkStore> blockStore,
            @Nonnull ChonbosChemistry mod) {
        // Decode the clicked block's world (x,y,z) via BlockStateInfo + BlockChunk (same technique as
        // NetworkTickSystem), but reading the components off this.blockRef rather than an ArchetypeChunk.
        BlockModule.BlockStateInfo info = blockStore.getComponent(this.blockRef, this.blockInfoType);
        if (info == null) {
            this.showEmpty(cmd, "Pipe", "Network unavailable.");
            return;
        }
        Ref<ChunkStore> chunkRef = info.getChunkRef();
        if (chunkRef == null || !chunkRef.isValid()) {
            this.showEmpty(cmd, "Pipe", "Network unavailable.");
            return;
        }
        BlockChunk blockChunk = chunkRef.getStore().getComponent(chunkRef, this.blockChunkType);
        if (blockChunk == null) {
            this.showEmpty(cmd, "Pipe", "Network unavailable.");
            return;
        }
        int blockIndex = info.getIndex();
        int x = (blockChunk.getX() << 5) | ChunkUtil.xFromBlockInColumn(blockIndex);
        int y = ChunkUtil.yFromBlockInColumn(blockIndex);
        int z = (blockChunk.getZ() << 5) | ChunkUtil.zFromBlockInColumn(blockIndex);

        ChunkStore external = blockStore.getExternalData();
        if (external == null) {
            this.showEmpty(cmd, "Pipe", "Network unavailable.");
            return;
        }
        World world = external.getWorld();
        if (world == null) {
            this.showEmpty(cmd, "Pipe", "Network unavailable.");
            return;
        }

        // Resolve (and cache) the network. Safe on the WorldThread (see class javadoc). A null result
        // means there is no pipe at this position any more (e.g. broken mid-interaction).
        NetworkManager manager = mod.networkService().forWorld(world);
        WorldPipeGridView grid = new WorldPipeGridView(world, blockStore, mod.pipeComponentType());
        Network net = manager.getOrBuildNetwork(x, y, z, grid);
        if (net == null) {
            this.showEmpty(cmd, "Pipe", "Network unavailable.");
            return;
        }

        // Title carries the channel name (pipes are POWER-only today, but write it generically).
        String channelName = channelDisplayName(net.channel());
        cmd.set("#BlockTitle.TextSpans", Message.raw(channelName + " Pipe Network"));

        // Row 1: the unified shared buffer (stored / capacity (pct%)).
        setRow(cmd, "#EnergyLabel", "Network: " + gaugeText(net.stored(), net.capacity()));
        // Row 2: how big the network is + its bottleneck throughput. Reuse the (hidden) fluid row.
        int pipeCount = net.memberKeys().size();
        setRow(cmd, "#FluidLabel", "Pipes: " + pipeCount + " • Throughput: " + net.throughput() + "/tick");
    }

    /** Human-readable channel name for the pipe network title ("Power" / "Fluid" / "Gas" / "Items"). */
    private static String channelDisplayName(PortChannel channel) {
        if (channel == null) {
            return "Power";
        }
        return switch (channel) {
            case FLUID -> "Fluid";
            case GAS -> "Gas";
            case ITEM -> "Items";
            default -> "Power"; // POWER (and any future addition) reads as Power until specialised
        };
    }

    // Rows are built from Message.raw(...) literals rather than translation keys: the mod's
    // server.lang is generated + gitignored, so a snapshot panel shouldn't depend on lang entries.
    private void populateMachine(@Nonnull UICommandBuilder cmd, @Nonnull MachineBlockState machine) {
        cmd.set("#BlockTitle.TextSpans", Message.raw("Machine"));

        boolean anything = false;

        EnergyHandler energy = machine.energy();
        if (energy != null) {
            setRow(cmd, "#EnergyLabel", "Energy: " + gaugeText(energy.getStored(), energy.getMaxStored()));
            anything = true;
        }

        anything |= this.setResourceRow(cmd, machine.resource(PortChannel.FLUID), "#FluidLabel", "Fluid");
        anything |= this.setResourceRow(cmd, machine.resource(PortChannel.GAS), "#GasLabel", "Gas");
        anything |= this.setResourceRow(cmd, machine.resource(PortChannel.ITEM), "#ItemLabel", "Items");

        if (!anything) {
            this.showEmptyRow(cmd);
        }
    }

    private void populateTank(@Nonnull UICommandBuilder cmd, @Nonnull TankBlockState tank) {
        cmd.set("#BlockTitle.TextSpans", Message.raw("Tank"));

        // A tank serves exactly one channel and carries no power.
        PortChannel channel = tank.channel();
        ResourceBuffer buffer = tank.resource(channel);

        String labelSelector;
        String channelName;
        switch (channel) {
            case GAS -> {
                labelSelector = "#GasLabel";
                channelName = "Gas";
            }
            case ITEM -> {
                labelSelector = "#ItemLabel";
                channelName = "Items";
            }
            default -> { // FLUID (and POWER, which a tank should never carry, falls back here harmlessly)
                labelSelector = "#FluidLabel";
                channelName = "Fluid";
            }
        }

        if (!this.setResourceRow(cmd, buffer, labelSelector, channelName)) {
            this.showEmptyRow(cmd);
        }
    }

    /**
     * Populate one resource label row from a buffer.
     *
     * @return true if the row was shown (buffer non-null), false otherwise.
     */
    private boolean setResourceRow(
            @Nonnull UICommandBuilder cmd,
            ResourceBuffer buffer,
            @Nonnull String labelSelector,
            @Nonnull String channelName) {
        if (buffer == null) {
            return false;
        }
        String resourceId = buffer.resourceId();
        String name = resourceId == null ? "empty" : resourceId;
        setRow(cmd, labelSelector, channelName + " (" + name + "): " + gaugeText(buffer.amount(), buffer.capacity()));
        return true;
    }

    private static void setRow(@Nonnull UICommandBuilder cmd, @Nonnull String labelSelector, @Nonnull String text) {
        cmd.set(labelSelector + ".Visible", true);
        cmd.set(labelSelector + ".TextSpans", Message.raw(text));
    }

    private void showEmptyRow(@Nonnull UICommandBuilder cmd) {
        cmd.set("#EmptyLabel.Visible", true);
        cmd.set("#EmptyLabel.TextSpans", Message.raw("This block has no buffers to display."));
    }

    private void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull String title, @Nonnull String message) {
        cmd.set("#BlockTitle.TextSpans", Message.raw(title));
        cmd.set("#EmptyLabel.Visible", true);
        cmd.set("#EmptyLabel.TextSpans", Message.raw(message));
    }

    /** "{amount} / {capacity} ({pct}%)" — the readable text gauge. */
    private static String gaugeText(long amount, long capacity) {
        return amount + " / " + capacity + " (" + percent(amount, capacity) + "%)";
    }

    private static int percent(long amount, long capacity) {
        if (capacity <= 0L) {
            return 0;
        }
        long p = Math.round(100.0 * amount / capacity);
        if (p < 0L) {
            return 0;
        }
        return (int) Math.min(p, 100L);
    }
}
