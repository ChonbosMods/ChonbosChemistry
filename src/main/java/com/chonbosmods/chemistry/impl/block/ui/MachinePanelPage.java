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
 * {@code Ref<ChunkStore>} and renders an energy gauge (machines with power only) plus one resource
 * row per non-null channel buffer (FLUID / GAS / ITEM), each showing amount/capacity and a fill bar.
 *
 * <p><b>Snapshot only:</b> values are read once at {@link #build}. There is no live refresh tick.
 * A future live variant would hold a server task that calls {@link #sendUpdate(UICommandBuilder)}
 * with a freshly-built command set on an interval (see the marker comment in {@link #build}).
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

        // NOTE (future live refresh): for a live panel, factor the population below into a private
        // method and re-run it from a scheduled task that calls sendUpdate(commandBuilder). v1 is a
        // one-shot snapshot, so we populate inline here.
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

    // Labels are built from Message.raw(...) literals rather than translation keys: the mod's
    // server.lang is generated + gitignored, so a snapshot panel shouldn't depend on lang entries.
    private void populateMachine(@Nonnull UICommandBuilder cmd, @Nonnull MachineBlockState machine) {
        cmd.set("#BlockTitle.TextSpans", Message.raw("Machine"));

        boolean anything = false;

        EnergyHandler energy = machine.energy();
        if (energy != null) {
            int stored = energy.getStored();
            int max = energy.getMaxStored();
            this.setGauge(cmd, "#EnergySection", "#EnergyLabel", "#EnergyBar",
                "Energy: " + amountText(stored, max), stored, max);
            anything = true;
        }

        anything |= this.setResourceRow(cmd, machine.resource(PortChannel.FLUID),
            "#FluidSection", "#FluidLabel", "#FluidBar", "Fluid");
        anything |= this.setResourceRow(cmd, machine.resource(PortChannel.GAS),
            "#GasSection", "#GasLabel", "#GasBar", "Gas");
        anything |= this.setResourceRow(cmd, machine.resource(PortChannel.ITEM),
            "#ItemSection", "#ItemLabel", "#ItemBar", "Items");

        if (!anything) {
            cmd.set("#EmptyLabel.Visible", true);
            cmd.set("#EmptyLabel.TextSpans", Message.raw("This block has no buffers to display."));
        }
    }

    private void populateTank(@Nonnull UICommandBuilder cmd, @Nonnull TankBlockState tank) {
        cmd.set("#BlockTitle.TextSpans", Message.raw("Tank"));

        // A tank serves exactly one channel and carries no power.
        PortChannel channel = tank.channel();
        ResourceBuffer buffer = tank.resource(channel);

        String section;
        String label;
        String bar;
        String channelName;
        switch (channel) {
            case GAS -> {
                section = "#GasSection";
                label = "#GasLabel";
                bar = "#GasBar";
                channelName = "Gas";
            }
            case ITEM -> {
                section = "#ItemSection";
                label = "#ItemLabel";
                bar = "#ItemBar";
                channelName = "Items";
            }
            default -> { // FLUID (and POWER, which a tank should never carry, falls back here harmlessly)
                section = "#FluidSection";
                label = "#FluidLabel";
                bar = "#FluidBar";
                channelName = "Fluid";
            }
        }

        boolean shown = this.setResourceRow(cmd, buffer, section, label, bar, channelName);
        if (!shown) {
            cmd.set("#EmptyLabel.Visible", true);
            cmd.set("#EmptyLabel.TextSpans", Message.raw("This block has no buffers to display."));
        }
    }

    /**
     * Populate a "Label + ProgressBar" row from a resource buffer.
     *
     * @return true if the row was shown (buffer non-null), false otherwise.
     */
    private boolean setResourceRow(
            @Nonnull UICommandBuilder cmd,
            ResourceBuffer buffer,
            @Nonnull String section,
            @Nonnull String label,
            @Nonnull String bar,
            @Nonnull String channelName) {
        if (buffer == null) {
            return false;
        }
        String resourceId = buffer.resourceId();
        String name = resourceId == null ? "empty" : resourceId;
        String labelText = channelName + " (" + name + "): " + amountText(buffer.amount(), buffer.capacity());
        this.setGauge(cmd, section, label, bar, labelText, buffer.amount(), buffer.capacity());
        return true;
    }

    private void setGauge(
            @Nonnull UICommandBuilder cmd,
            @Nonnull String section,
            @Nonnull String label,
            @Nonnull String bar,
            @Nonnull String labelText,
            int stored,
            int max) {
        cmd.set(section + ".Visible", true);
        cmd.set(label + ".TextSpans", Message.raw(labelText));
        cmd.set(bar + ".Value", fraction(stored, max));
    }

    private void showEmpty(@Nonnull UICommandBuilder cmd, @Nonnull String title, @Nonnull String message) {
        cmd.set("#BlockTitle.TextSpans", Message.raw(title));
        cmd.set("#EmptyLabel.Visible", true);
        cmd.set("#EmptyLabel.TextSpans", Message.raw(message));
    }

    private static String amountText(int amount, int capacity) {
        return amount + " / " + capacity;
    }

    private static float fraction(int amount, int capacity) {
        if (capacity <= 0) {
            return 0.0F;
        }
        float f = (float) amount / (float) capacity;
        if (f < 0.0F) {
            return 0.0F;
        }
        return Math.min(f, 1.0F);
    }
}
