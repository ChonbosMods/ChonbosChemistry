package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.EnergyBuffer;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Storage balancing (2026-06-05 design): batteries/tanks on one network drift toward water-fill
 * targets ({@link WaterFill}) using only LEFTOVER per-tick budget, and stop dead at the fixpoint
 * (no churn). Uses real {@link EnergyBuffer}/{@link ResourceBuffer} endpoints via
 * {@link EndpointAdapters} storage views: no mocks.
 */
class NetworkBalancingTest {

    /** A network with one member pipe contributing the given buffer capacity and throughput. */
    private static Network net(PortChannel channel, long capacity, int throughput) {
        Network net = new Network(channel);
        net.addMember(NetworkManager.packKey(0, 1, 0), capacity, throughput);
        return net;
    }

    /** One idle-network distribute pass: storage only, no pure sources/sinks. */
    private static long balanceTick(Network net, List<StorageEndpoint> storages) {
        return NetworkTransfer.distribute(
            net,
            List.of(),
            List.of(),
            storages.stream().map(StorageEndpoint::provider).toList(),
            storages.stream().map(StorageEndpoint::acceptor).toList(),
            storages);
    }

    @Test
    void idleBatteriesEqualizeAtThroughputRateThenStop() {
        EnergyBuffer full = EnergyBuffer.withCapacity(100);
        full.receiveEnergy(100, false);
        EnergyBuffer empty = EnergyBuffer.withCapacity(100);
        List<StorageEndpoint> storages = List.of(
            EndpointAdapters.powerStorage(full), EndpointAdapters.powerStorage(empty));
        Network net = net(PortChannel.POWER, 1000, 10);

        // 10/tick of leftover budget: 50 units to move -> 5 ticks to converge.
        for (int t = 0; t < 5; t++) {
            balanceTick(net, storages);
        }
        assertEquals(50, full.getStored());
        assertEquals(50, empty.getStored());
        assertEquals(0, net.stored(), "balancing must not strand resource in the network buffer");

        // Fixpoint: further ticks move nothing (no churn).
        for (int t = 0; t < 5; t++) {
            assertEquals(0, balanceTick(net, storages), "no movement at the balance fixpoint");
        }
        assertEquals(50, full.getStored());
        assertEquals(50, empty.getStored());
    }

    @Test
    void capacityOnlyClampsSmallStorageSitsFull() {
        EnergyBuffer big = EnergyBuffer.withCapacity(1000);
        big.receiveEnergy(1000, false);
        EnergyBuffer small = EnergyBuffer.withCapacity(100);
        List<StorageEndpoint> storages = List.of(
            EndpointAdapters.powerStorage(big), EndpointAdapters.powerStorage(small));
        Network net = net(PortChannel.POWER, 1000, 500);

        for (int t = 0; t < 5; t++) {
            balanceTick(net, storages);
        }
        // Water-fill: S=1000 over caps [1000,100] -> level 900: small clamps full.
        assertEquals(900, big.getStored());
        assertEquals(100, small.getStored());
    }

    @Test
    void recipientsFillAtEqualPerTickRates() {
        EnergyBuffer donor = EnergyBuffer.withCapacity(100);
        donor.receiveEnergy(100, false);
        EnergyBuffer r1 = EnergyBuffer.withCapacity(100);
        EnergyBuffer r2 = EnergyBuffer.withCapacity(100);
        List<StorageEndpoint> storages = List.of(
            EndpointAdapters.powerStorage(donor),
            EndpointAdapters.powerStorage(r1),
            EndpointAdapters.powerStorage(r2));
        Network net = net(PortChannel.POWER, 1000, 6);

        balanceTick(net, storages);
        // 6 units moved, fair-split across the two below-target recipients: 3 each.
        assertEquals(94, donor.getStored());
        assertEquals(3, r1.getStored());
        assertEquals(3, r2.getStored());

        // Converge fully: targets [33,33,33] floor, remainder 1 stays on the donor.
        for (int t = 0; t < 30; t++) {
            balanceTick(net, storages);
        }
        assertEquals(34, donor.getStored());
        assertEquals(33, r1.getStored());
        assertEquals(33, r2.getStored());
    }

    @Test
    void realDemandStarvesBalancingNotViceVersa() {
        // A pure source feeding a pure sink consumes the whole per-tick budget: balancing waits.
        EnergyBuffer full = EnergyBuffer.withCapacity(100);
        full.receiveEnergy(100, false);
        EnergyBuffer empty = EnergyBuffer.withCapacity(100);
        List<StorageEndpoint> storages = List.of(
            EndpointAdapters.powerStorage(full), EndpointAdapters.powerStorage(empty));

        EnergyBuffer source = EnergyBuffer.withCapacity(1000);
        source.receiveEnergy(1000, false);
        EnergyBuffer sink = EnergyBuffer.withCapacity(1000);
        Network net = net(PortChannel.POWER, 1000, 5);

        long delivered = NetworkTransfer.distribute(
            net,
            List.of(EndpointAdapters.powerProvider(source)),
            List.of(EndpointAdapters.powerAcceptor(sink)),
            storages.stream().map(StorageEndpoint::provider).toList(),
            storages.stream().map(StorageEndpoint::acceptor).toList(),
            storages);

        assertEquals(5, delivered, "full budget goes to the real sink");
        assertEquals(5, sink.getStored());
        assertEquals(100, full.getStored(), "no leftover budget: batteries do not balance this tick");
        assertEquals(0, empty.getStored());
    }

    @Test
    void leftoverBudgetBalancesAfterRealDemandIsMet() {
        // Sink takes only 3 of the 10 budget; the leftover 7 balances the batteries.
        EnergyBuffer full = EnergyBuffer.withCapacity(100);
        full.receiveEnergy(100, false);
        EnergyBuffer empty = EnergyBuffer.withCapacity(100);
        List<StorageEndpoint> storages = List.of(
            EndpointAdapters.powerStorage(full), EndpointAdapters.powerStorage(empty));

        EnergyBuffer source = EnergyBuffer.withCapacity(1000);
        source.receiveEnergy(1000, false);
        EnergyBuffer sink = EnergyBuffer.withCapacity(3);
        Network net = net(PortChannel.POWER, 1000, 10);

        NetworkTransfer.distribute(
            net,
            List.of(EndpointAdapters.powerProvider(source)),
            List.of(EndpointAdapters.powerAcceptor(sink)),
            storages.stream().map(StorageEndpoint::provider).toList(),
            storages.stream().map(StorageEndpoint::acceptor).toList(),
            storages);

        assertEquals(3, sink.getStored());
        assertTrue(empty.getStored() > 0, "leftover budget must balance the batteries");
    }

    @Test
    void typeLockedFluidTanksBalanceAndKeepTheirSubstance() {
        ResourceBuffer waterTank = ResourceBuffer.withCapacity(100);
        waterTank.insert("compound:water", 80, false);
        ResourceBuffer emptyTank = ResourceBuffer.withCapacity(100);
        List<StorageEndpoint> storages = List.of(
            EndpointAdapters.resourceStorage(waterTank), EndpointAdapters.resourceStorage(emptyTank));
        Network net = net(PortChannel.FLUID, 1000, 20);

        for (int t = 0; t < 3; t++) {
            balanceTick(net, storages);
        }
        assertEquals(40, waterTank.amount());
        assertEquals(40, emptyTank.amount());
        assertEquals("compound:water", waterTank.resourceId());
        assertEquals("compound:water", emptyTank.resourceId());
        assertEquals(0, net.stored());
    }

    @Test
    void degenerateStorageSetsMoveNothing() {
        Network net = net(PortChannel.POWER, 1000, 10);
        assertEquals(0, balanceTick(net, List.of()), "no storage: nothing to balance");

        EnergyBuffer lonely = EnergyBuffer.withCapacity(100);
        lonely.receiveEnergy(60, false);
        assertEquals(0, balanceTick(net, List.of(EndpointAdapters.powerStorage(lonely))));
        assertEquals(60, lonely.getStored(), "a single storage never moves");
    }
}
