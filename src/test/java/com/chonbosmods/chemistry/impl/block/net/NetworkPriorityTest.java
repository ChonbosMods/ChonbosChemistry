package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * H6 FIX 2 (source/storage priority) coverage for {@link NetworkTransfer#distribute(Network,
 * java.util.List, java.util.List, java.util.List, java.util.List)}. Reproduces the in-game BUG 2:
 * a cell (pure source) plus a storage battery (BOTH ports) must not self-churn the storage into
 * starving the cell. Multi-tick, asserting per tick.
 *
 * <p>Design rules verified:
 * <ul>
 *   <li>Phase 1 (fill) pulls from PURE providers first; buffer providers only when budget remains AND
 *       a pure acceptor (real demand) exists.
 *   <li>Phase 2 (drain) fair-splits to PURE acceptors first; only the leftover budget reaches buffer
 *       acceptors.
 *   <li>Storage alone (no pure endpoints) moves nothing — no self-churn.
 * </ul>
 */
class NetworkPriorityTest {

    /** Hands out up to its remaining stock; commit decrements it. {@code infinite} never depletes. */
    private static final class FakeProvider implements Provider {
        private final String resourceId;
        private long available;
        private final boolean infinite;

        FakeProvider(String resourceId, long available, boolean infinite) {
            this.resourceId = resourceId;
            this.available = available;
            this.infinite = infinite;
        }

        @Override
        public String resourceId() {
            return resourceId;
        }

        @Override
        public long extract(long max, boolean simulate) {
            long cap = infinite ? Math.max(0L, max) : Math.min(Math.max(0L, max), available);
            if (!simulate && !infinite) {
                available -= cap;
            }
            return cap;
        }

        long available() {
            return available;
        }
    }

    /** Resource-agnostic POWER acceptor: tracks received and remaining room. */
    private static final class FakeAcceptor implements Acceptor {
        private long room;
        private long received;

        FakeAcceptor(long room) {
            this.room = room;
        }

        @Override
        public long capacityFor(String resourceId) {
            return room;
        }

        @Override
        public long insert(String resourceId, long amount, boolean simulate) {
            long taken = Math.min(Math.max(0L, amount), room);
            if (!simulate) {
                room -= taken;
                received += taken;
            }
            return taken;
        }

        long received() {
            return received;
        }

        long room() {
            return room;
        }
    }

    /**
     * A storage battery modelled as a single backing buffer exposed through BOTH a provider view (drains
     * the battery) and an acceptor view (charges it). The two views share {@code stored}, so charging
     * and discharging in the same tick are accounted against the same balance.
     */
    private static final class FakeStorage {
        private long stored;
        private final long capacity;

        FakeStorage(long stored, long capacity) {
            this.stored = stored;
            this.capacity = capacity;
        }

        long stored() {
            return stored;
        }

        Provider asProvider() {
            return new Provider() {
                @Override
                public String resourceId() {
                    return null;
                }

                @Override
                public long extract(long max, boolean simulate) {
                    long taken = Math.min(Math.max(0L, max), stored);
                    if (!simulate) {
                        stored -= taken;
                    }
                    return taken;
                }
            };
        }

        Acceptor asAcceptor() {
            return new Acceptor() {
                @Override
                public long capacityFor(String resourceId) {
                    return Math.max(0L, capacity - stored);
                }

                @Override
                public long insert(String resourceId, long amount, boolean simulate) {
                    long taken = Math.min(Math.max(0L, amount), capacity - stored);
                    if (!simulate) {
                        stored += taken;
                    }
                    return taken;
                }
            };
        }
    }

    private static Network powerNet() {
        Network net = new Network(PortChannel.POWER);
        // Throughput 5 (tier-0 default), generous capacity so the buffer never backpressures here.
        net.addMember(1, 100_000, 5);
        return net;
    }

    // --- Scenario A: cell + storage, cell never starved, storage charges 5/tick. ---

    @Test
    void cellPlusStorage_cellFeedsEveryTick_storageCharges_cellNeverStarved() {
        Network net = powerNet();
        FakeProvider cell = new FakeProvider(null, 0, true); // infinite source
        FakeStorage storage = new FakeStorage(0, 100_000);

        // No pure acceptor: storage charges from the cell, but the cell is never starved by the
        // storage's own provider side (the buffer provider must NOT activate without real demand,
        // and even if it did, charging only the surplus keeps the cell flowing).
        for (int tick = 1; tick <= 4; tick++) {
            long delivered = NetworkTransfer.distribute(
                net, List.of(cell), List.of(),
                List.of(storage.asProvider()), List.of(storage.asAcceptor()));
            // Each tick the cell supplies the 5/tick budget which charges the storage.
            assertEquals(5L * tick, storage.stored(), "storage charged 5/tick at tick " + tick);
            assertEquals(5, delivered, "5 delivered to storage at tick " + tick);
        }
    }

    // --- Scenario B: cell + storage + sink: sink gets the split first, storage only surplus. ---

    @Test
    void cellPlusStoragePlusSink_sinkPrioritised_storageChargesOnlySurplus() {
        Network net = powerNet(); // budget 5/tick
        FakeProvider cell = new FakeProvider(null, 0, true);
        FakeStorage storage = new FakeStorage(0, 100_000);
        FakeAcceptor sink = new FakeAcceptor(100_000);

        long delivered = NetworkTransfer.distribute(
            net, List.of(cell), List.of(sink),
            List.of(storage.asProvider()), List.of(storage.asAcceptor()));

        // The 5/tick budget all goes to the pure sink; the storage gets the surplus (none here).
        assertEquals(5, sink.received(), "sink consumed the whole budget");
        assertEquals(0, storage.stored(), "storage charged only the surplus (zero)");
        assertEquals(5, delivered);
    }

    @Test
    void cellPlusStoragePlusSink_partialSink_storageGetsRemainder() {
        // Sink has room for only 2 this tick; the remaining 3 of the 5-budget charges the storage.
        Network net = powerNet();
        FakeProvider cell = new FakeProvider(null, 0, true);
        FakeStorage storage = new FakeStorage(0, 100_000);
        FakeAcceptor sink = new FakeAcceptor(2);

        long delivered = NetworkTransfer.distribute(
            net, List.of(cell), List.of(sink),
            List.of(storage.asProvider()), List.of(storage.asAcceptor()));

        assertEquals(2, sink.received(), "sink took its 2 of room first");
        assertEquals(3, storage.stored(), "storage charged the remaining 3 of the budget");
        assertEquals(5, delivered);
    }

    // --- Scenario C: charged storage + sink, NO cell: storage discharges toward the sink. ---

    @Test
    void chargedStoragePlusSink_noCell_storageDischargesTowardSink() {
        Network net = powerNet(); // budget 5/tick
        FakeStorage storage = new FakeStorage(100, 100_000);
        FakeAcceptor sink = new FakeAcceptor(100_000);

        long total = 0;
        for (int tick = 1; tick <= 3; tick++) {
            long delivered = NetworkTransfer.distribute(
                net, List.of(), List.of(sink),
                List.of(storage.asProvider()), List.of(storage.asAcceptor()));
            total += delivered;
            // Buffer provider activates because a pure acceptor (the sink) wants energy.
            assertEquals(5, delivered, "storage discharged 5/tick at tick " + tick);
        }
        assertEquals(15, sink.received());
        assertEquals(85, storage.stored(), "storage drained 15 over 3 ticks");
        assertEquals(15, total);
    }

    // --- Scenario D: storage alone, no pure endpoints: nothing moves (no self-churn). ---

    @Test
    void storageAlone_noPureEndpoints_nothingMoves() {
        Network net = powerNet();
        FakeStorage storage = new FakeStorage(500, 100_000);

        for (int tick = 1; tick <= 3; tick++) {
            long delivered = NetworkTransfer.distribute(
                net, List.of(), List.of(),
                List.of(storage.asProvider()), List.of(storage.asAcceptor()));
            assertEquals(0, delivered, "no pure endpoint -> no movement at tick " + tick);
            assertEquals(500, storage.stored(), "storage unchanged at tick " + tick);
            assertEquals(0, net.stored(), "network buffer unchanged at tick " + tick);
        }
    }

    // --- Scenario E: two storages, no pure endpoints: no transfer between them (stable v1). ---

    @Test
    void twoStorages_noPureEndpoints_noTransferBetweenThem() {
        // Deliberate v1 choice: batteries do not balance each other without demand. A charged storage
        // and an empty storage, no pure source/sink: nothing moves across ticks.
        Network net = powerNet();
        FakeStorage charged = new FakeStorage(500, 100_000);
        FakeStorage empty = new FakeStorage(0, 100_000);

        for (int tick = 1; tick <= 3; tick++) {
            long delivered = NetworkTransfer.distribute(
                net, List.of(), List.of(),
                List.of(charged.asProvider(), empty.asProvider()),
                List.of(charged.asAcceptor(), empty.asAcceptor()));
            assertEquals(0, delivered, "no demand -> no inter-battery balancing at tick " + tick);
            assertEquals(500, charged.stored());
            assertEquals(0, empty.stored());
        }
    }
}
