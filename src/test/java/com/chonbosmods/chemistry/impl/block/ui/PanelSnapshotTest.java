package com.chonbosmods.chemistry.impl.block.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.EnergyBuffer;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.chonbosmods.chemistry.impl.block.WorkState;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PanelSnapshotTest {

    private static final List<String> ALL_SELECTORS = List.of(
        "#EnergyLabel", "#FluidLabel", "#GasLabel", "#ItemLabel", "#EmptyLabel");

    private static PanelSnapshot.Row row(PanelSnapshot snapshot, String selector) {
        return snapshot.rows().stream()
            .filter(r -> r.selector().equals(selector))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing row " + selector));
    }

    /** Every snapshot must carry an explicit visible flag for every row: that is the live-refresh fix. */
    private static void assertCoversAllRows(PanelSnapshot snapshot) {
        assertEquals(ALL_SELECTORS.size(), snapshot.rows().size());
        for (String selector : ALL_SELECTORS) {
            assertNotNull(row(snapshot, selector));
        }
    }

    @Test
    void machineSnapshotShowsEnergyAndFluidAndExplicitlyHidesTheRest() {
        EnergyBuffer energy = EnergyBuffer.withCapacity(1000);
        energy.receiveEnergy(250, false);
        ResourceBuffer fluid = ResourceBuffer.withCapacity(500);
        fluid.insert("water", 120, false);
        Map<PortChannel, ResourceBuffer> resources = new EnumMap<>(PortChannel.class);
        resources.put(PortChannel.FLUID, fluid);
        PortConfig ports = PortConfig.of(List.of(Port.of(0, PortChannel.POWER, PortDirection.INPUT)));
        MachineBlockState machine = MachineBlockState.create(energy, resources, ports, new WorkState(), 100);

        PanelSnapshot snapshot = PanelSnapshot.forMachine(machine);

        assertEquals("Machine", snapshot.title());
        assertCoversAllRows(snapshot);
        PanelSnapshot.Row energyRow = row(snapshot, "#EnergyLabel");
        assertTrue(energyRow.visible());
        assertEquals("Energy: 250 / 1000 (25%)", energyRow.text());
        PanelSnapshot.Row fluidRow = row(snapshot, "#FluidLabel");
        assertTrue(fluidRow.visible());
        assertEquals("Fluid (water): 120 / 500 (24%)", fluidRow.text());
        assertFalse(row(snapshot, "#GasLabel").visible());
        assertFalse(row(snapshot, "#ItemLabel").visible());
        assertFalse(row(snapshot, "#EmptyLabel").visible());
    }

    @Test
    void machineSnapshotWithNoBuffersShowsEmptyRowOnly() {
        PortConfig ports = PortConfig.of(List.of());
        MachineBlockState machine = MachineBlockState.create(
            null, new EnumMap<>(PortChannel.class), ports, new WorkState(), 100);

        PanelSnapshot snapshot = PanelSnapshot.forMachine(machine);

        assertCoversAllRows(snapshot);
        PanelSnapshot.Row emptyRow = row(snapshot, "#EmptyLabel");
        assertTrue(emptyRow.visible());
        assertEquals("This block has no buffers to display.", emptyRow.text());
        assertFalse(row(snapshot, "#EnergyLabel").visible());
        assertFalse(row(snapshot, "#FluidLabel").visible());
    }

    @Test
    void tankSnapshotShowsItsChannelRowOnly() {
        ResourceBuffer buffer = ResourceBuffer.withCapacity(200);
        buffer.insert("oxygen", 50, false);
        PortConfig ports = PortConfig.of(List.of());
        TankBlockState tank = TankBlockState.create(buffer, PortChannel.GAS, ports, 200);

        PanelSnapshot snapshot = PanelSnapshot.forTank(tank);

        assertEquals("Tank", snapshot.title());
        assertCoversAllRows(snapshot);
        PanelSnapshot.Row gasRow = row(snapshot, "#GasLabel");
        assertTrue(gasRow.visible());
        assertEquals("Gas (oxygen): 50 / 200 (25%)", gasRow.text());
        assertFalse(row(snapshot, "#FluidLabel").visible());
        assertFalse(row(snapshot, "#EmptyLabel").visible());
    }

    @Test
    void networkSnapshotShowsSharedBufferAndPipeStats() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1L, 500, 5);
        net.addMember(2L, 500, 5);
        net.insert(null, 250, false);

        PanelSnapshot snapshot = PanelSnapshot.forNetwork(net);

        assertEquals("Power Pipe Network", snapshot.title());
        assertCoversAllRows(snapshot);
        PanelSnapshot.Row netRow = row(snapshot, "#EnergyLabel");
        assertTrue(netRow.visible());
        assertEquals("Network: 250 / 1000 (25%)", netRow.text());
        PanelSnapshot.Row statsRow = row(snapshot, "#FluidLabel");
        assertTrue(statsRow.visible());
        assertEquals("Pipes: 2 • Throughput: 5/tick", statsRow.text());
        assertFalse(row(snapshot, "#GasLabel").visible());
        assertFalse(row(snapshot, "#EmptyLabel").visible());
    }

    /** The 1-arg forNetwork (no pipe) leaves the #GasLabel faces row hidden: back-compat path. */
    @Test
    void networkSnapshotWithoutPipeOmitsFacesRow() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1L, 500, 5);

        PanelSnapshot snapshot = PanelSnapshot.forNetwork(net);

        assertFalse(row(snapshot, "#GasLabel").visible());
    }

    /** With a pipe, the #GasLabel row lists only the non-NORMAL faces in face-index order. */
    @Test
    void networkSnapshotShowsNonNormalFaces() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1L, 500, 5);
        PipeNode pipe = PipeNode.of(PortChannel.POWER, 1);
        pipe.setFlowState(0, FlowState.PUSH);
        pipe.setFlowState(5, FlowState.NONE);

        PanelSnapshot snapshot = PanelSnapshot.forNetwork(net, pipe);

        PanelSnapshot.Row facesRow = row(snapshot, "#GasLabel");
        assertTrue(facesRow.visible());
        assertEquals("Faces: East push · North none", facesRow.text());
    }

    /** An all-NORMAL pipe still shows the faces row, reading "Faces: all normal". */
    @Test
    void networkSnapshotAllNormalFaces() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1L, 500, 5);
        PipeNode pipe = PipeNode.of(PortChannel.POWER, 1);

        PanelSnapshot snapshot = PanelSnapshot.forNetwork(net, pipe);

        PanelSnapshot.Row facesRow = row(snapshot, "#GasLabel");
        assertTrue(facesRow.visible());
        assertEquals("Faces: all normal", facesRow.text());
    }

    /**
     * An ITEM network with stats renders the discrete-transport rows (no shared buffer): row 1 is the
     * in-transit/pipe count, row 2 the destinations/sources count. The faces row coexists.
     */
    @Test
    void itemNetworkSnapshotShowsTransitAndEndpointRows() {
        Network net = new Network(PortChannel.ITEM);
        net.addMember(1L, 0, 0);
        net.addMember(2L, 0, 0);
        PipeNode pipe = PipeNode.of(PortChannel.ITEM, 1);
        pipe.setFlowState(0, FlowState.PULL);

        PanelSnapshot snapshot = PanelSnapshot.forNetwork(
            net, pipe, new PanelSnapshot.ItemNetworkStats(3, 2, 1));

        assertEquals("Items Pipe Network", snapshot.title());
        assertCoversAllRows(snapshot);
        PanelSnapshot.Row transitRow = row(snapshot, "#EnergyLabel");
        assertTrue(transitRow.visible());
        assertEquals("In transit: 3 stacks • Pipes: 2", transitRow.text());
        PanelSnapshot.Row endpointRow = row(snapshot, "#FluidLabel");
        assertTrue(endpointRow.visible());
        assertEquals("Destinations: 2 • Sources: 1", endpointRow.text());
        // Faces row still works alongside the item-specific rows.
        PanelSnapshot.Row facesRow = row(snapshot, "#GasLabel");
        assertTrue(facesRow.visible());
        assertEquals("Faces: East pull", facesRow.text());
    }

    /** Defensive: an ITEM network with null stats falls back to the fungible-gauge rendering. */
    @Test
    void itemNetworkSnapshotWithNullStatsFallsBackToGauge() {
        Network net = new Network(PortChannel.ITEM);
        net.addMember(1L, 500, 5);

        PanelSnapshot snapshot = PanelSnapshot.forNetwork(net, null, null);

        PanelSnapshot.Row gaugeRow = row(snapshot, "#EnergyLabel");
        assertTrue(gaugeRow.visible());
        assertEquals("Network: 0 / 500 (0%)", gaugeRow.text());
        PanelSnapshot.Row statsRow = row(snapshot, "#FluidLabel");
        assertTrue(statsRow.visible());
        assertEquals("Pipes: 1 • Throughput: 5/tick", statsRow.text());
    }

    /** Defensive channel gate: stats on a NON-ITEM network are ignored; the fungible gauge stays. */
    @Test
    void nonItemNetworkIgnoresItemStats() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1L, 500, 5);
        net.addMember(2L, 500, 5);
        net.insert(null, 250, false);

        PanelSnapshot snapshot = PanelSnapshot.forNetwork(
            net, null, new PanelSnapshot.ItemNetworkStats(9, 9, 9));

        PanelSnapshot.Row gaugeRow = row(snapshot, "#EnergyLabel");
        assertTrue(gaugeRow.visible());
        assertEquals("Network: 250 / 1000 (25%)", gaugeRow.text());
        PanelSnapshot.Row statsRow = row(snapshot, "#FluidLabel");
        assertEquals("Pipes: 2 • Throughput: 5/tick", statsRow.text());
    }

    /** Singular/plural polish: "1 stack" vs "N stacks" on the in-transit row. */
    @Test
    void itemNetworkSnapshotInTransitSingularVsPlural() {
        Network net = new Network(PortChannel.ITEM);
        net.addMember(1L, 0, 0);

        PanelSnapshot one = PanelSnapshot.forNetwork(
            net, null, new PanelSnapshot.ItemNetworkStats(1, 0, 0));
        assertEquals("In transit: 1 stack • Pipes: 1", row(one, "#EnergyLabel").text());

        PanelSnapshot zero = PanelSnapshot.forNetwork(
            net, null, new PanelSnapshot.ItemNetworkStats(0, 0, 0));
        assertEquals("In transit: 0 stacks • Pipes: 1", row(zero, "#EnergyLabel").text());
    }

    @Test
    void emptySnapshotCarriesTitleAndMessage() {
        PanelSnapshot snapshot = PanelSnapshot.empty("Pipe", "Network unavailable.");

        assertEquals("Pipe", snapshot.title());
        assertCoversAllRows(snapshot);
        PanelSnapshot.Row emptyRow = row(snapshot, "#EmptyLabel");
        assertTrue(emptyRow.visible());
        assertEquals("Network unavailable.", emptyRow.text());
        assertFalse(row(snapshot, "#EnergyLabel").visible());
    }

    /** Only real-content snapshots are live (eligible for refresh registration / keep-alive). */
    @Test
    void blockAndNetworkSnapshotsAreLiveButEmptyStateIsNot() {
        PortConfig ports = PortConfig.of(List.of());
        MachineBlockState machine = MachineBlockState.create(
            null, new EnumMap<>(PortChannel.class), ports, new WorkState(), 100);
        TankBlockState tank = TankBlockState.create(
            ResourceBuffer.withCapacity(200), PortChannel.FLUID, ports, 200);
        Network net = new Network(PortChannel.POWER);

        assertTrue(PanelSnapshot.forMachine(machine).live(), "machine snapshot is live even with no buffers");
        assertTrue(PanelSnapshot.forTank(tank).live());
        assertTrue(PanelSnapshot.forNetwork(net).live());
        assertFalse(PanelSnapshot.empty("Pipe", "Network unavailable.").live(),
            "empty-state snapshot is not live: page should close/stay static");
    }
}
