package com.chonbosmods.chemistry.impl.block.ui;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Server-side block GUI for Chonbo's Chemistry rig blocks (Task B4, snapshot v1).
 *
 * <p>Right-clicking a machine or tank block opens this page. It reads the block's
 * {@link MachineBlockState} or {@link TankBlockState} component off the clicked block entity's
 * {@code Ref<ChunkStore>} and renders an energy readout (machines with power only) plus one row per
 * non-null channel buffer (FLUID / GAS / ITEM), each showing {@code amount / capacity (pct%)}.
 *
 * <p>The UI template ({@code Pages/CC_MachinePanel.ui}) mirrors the proven Natural20 structure
 * ({@code @PageOverlay} + {@code @Container} + plain {@code Label} rows; no {@code @ProgressBar}),
 * so each gauge is a text label toggled visible and given its text here.
 *
 * <p><b>Snapshot only:</b> values are read once at {@link #build}. There is no live refresh tick.
 * A future live variant would re-run the population from a scheduled task via
 * {@link #sendUpdate(UICommandBuilder)} (see the marker comment in {@link #build}).
 */
public final class MachinePanelPage extends CustomUIPage {

    @Nonnull
    private final Ref<ChunkStore> blockRef;

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

        this.showEmpty(commandBuilder, "Machine", "This is not a chemistry block.");
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
