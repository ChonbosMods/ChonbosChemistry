package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetworkTransferTest {

    /** Hands out up to its remaining stock; commit (simulate=false) decrements it. */
    private static final class FakeProvider implements Provider {
        private final String resourceId;
        private long available;

        FakeProvider(String resourceId, long available) {
            this.resourceId = resourceId;
            this.available = available;
        }

        @Override
        public String resourceId() {
            return resourceId;
        }

        @Override
        public long extract(long max, boolean simulate) {
            long given = Math.min(Math.max(0L, max), available);
            if (!simulate) {
                available -= given;
            }
            return given;
        }

        long available() {
            return available;
        }
    }

    /** Resource-agnostic acceptor (POWER): takes up to its remaining room. */
    private static final class FakeAcceptor implements Acceptor {
        private long room;

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
            }
            return taken;
        }

        long room() {
            return room;
        }
    }

    /** Accepts only the locked resource; capacityFor returns 0 for anything else. */
    private static final class FakeFluidAcceptor implements Acceptor {
        private final String locked;
        private long room;

        FakeFluidAcceptor(String locked, long room) {
            this.locked = locked;
            this.room = room;
        }

        @Override
        public long capacityFor(String resourceId) {
            return locked.equals(resourceId) ? room : 0;
        }

        @Override
        public long insert(String resourceId, long amount, boolean simulate) {
            if (!locked.equals(resourceId)) {
                return 0;
            }
            long taken = Math.min(Math.max(0L, amount), room);
            if (!simulate) {
                room -= taken;
            }
            return taken;
        }

        long room() {
            return room;
        }
    }

    /** Lies on capacityFor (claims more than insert will truly accept). */
    private static final class LyingAcceptor implements Acceptor {
        private final long claimed;
        private long trueRoom;

        LyingAcceptor(long claimed, long trueRoom) {
            this.claimed = claimed;
            this.trueRoom = trueRoom;
        }

        @Override
        public long capacityFor(String resourceId) {
            return claimed;
        }

        @Override
        public long insert(String resourceId, long amount, boolean simulate) {
            long taken = Math.min(Math.max(0L, amount), trueRoom);
            if (!simulate) {
                trueRoom -= taken;
            }
            return taken;
        }
    }

    @Test
    void powerEvenSplit() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 1000, 8);
        FakeAcceptor a = new FakeAcceptor(1000);
        FakeAcceptor b = new FakeAcceptor(1000);

        long delivered = NetworkTransfer.distribute(
                net, List.of(new FakeProvider(null, 100)), List.of(a, b));

        assertEquals(100, delivered);
        assertEquals(950, a.room()); // got 50
        assertEquals(950, b.room()); // got 50
        assertEquals(0, net.stored());
    }

    @Test
    void powerNearFull() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 1000, 8);
        FakeAcceptor a = new FakeAcceptor(10);
        FakeAcceptor b = new FakeAcceptor(1000);

        long delivered = NetworkTransfer.distribute(
                net, List.of(new FakeProvider(null, 90)), List.of(a, b));

        assertEquals(90, delivered);
        assertEquals(0, a.room());   // got 10
        assertEquals(920, b.room()); // got 80
        assertEquals(0, net.stored());
    }

    @Test
    void backpressureNetworkFull() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 50, 8);
        FakeProvider p = new FakeProvider(null, 100);

        long delivered = NetworkTransfer.distribute(net, List.of(p), List.of());

        assertEquals(0, delivered);
        assertEquals(50, net.stored());  // only free space pulled
        assertEquals(50, p.available()); // provider still holds the rest

        FakeAcceptor a = new FakeAcceptor(1000);
        long delivered2 = NetworkTransfer.distribute(net, List.of(), List.of(a));
        assertEquals(50, delivered2);
        assertEquals(0, net.stored());
        assertEquals(950, a.room());
    }

    @Test
    void backpressureAcceptorsFull() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 1000, 8);
        // Pre-fill the buffer.
        NetworkTransfer.distribute(net, List.of(new FakeProvider(null, 100)), List.of());
        assertEquals(100, net.stored());

        FakeAcceptor a = new FakeAcceptor(0);
        FakeAcceptor b = new FakeAcceptor(0);
        long delivered = NetworkTransfer.distribute(net, List.of(), List.of(a, b));

        assertEquals(0, delivered);
        assertEquals(100, net.stored()); // network keeps everything
    }

    @Test
    void fluidTypeLockAtProvider() {
        Network net = new Network(PortChannel.FLUID);
        net.addMember(1, 1000, 8);

        FakeFluidAcceptor oxygen = new FakeFluidAcceptor("oxygen", 1000);
        FakeFluidAcceptor helium = new FakeFluidAcceptor("helium", 1000);

        long delivered = NetworkTransfer.distribute(
                net,
                List.of(new FakeProvider("oxygen", 50), new FakeProvider("helium", 50)),
                List.of(oxygen, helium));

        // Helium provider skipped once locked to oxygen; only 50 oxygen flowed through.
        assertEquals(50, delivered);
        assertEquals(950, oxygen.room()); // received 50
        assertEquals(1000, helium.room()); // received nothing — helium was never pulled
        // All 50 oxygen drained to the oxygen acceptor, so the buffer emptied and unlocked.
        assertEquals(0, net.stored());
        assertNull(net.lockedResourceId());
    }

    @Test
    void fluidTypeLockBuffersWhenAcceptorsCannotTake() {
        // Same lock behavior, but with no matching acceptor the buffer retains the lock.
        Network net = new Network(PortChannel.FLUID);
        net.addMember(1, 1000, 8);

        FakeFluidAcceptor helium = new FakeFluidAcceptor("helium", 1000);
        long delivered = NetworkTransfer.distribute(
                net,
                List.of(new FakeProvider("oxygen", 50), new FakeProvider("helium", 50)),
                List.of(helium));

        assertEquals(0, delivered);          // helium acceptor cannot take oxygen
        assertEquals(50, net.stored());      // oxygen buffered (helium provider skipped)
        assertEquals("oxygen", net.lockedResourceId());
        assertEquals(1000, helium.room());
    }

    @Test
    void losslessLyingAcceptor() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 1000, 8);
        NetworkTransfer.distribute(net, List.of(new FakeProvider(null, 100)), List.of());
        assertEquals(100, net.stored());

        // Claims room for 100, but will only truly accept 30.
        LyingAcceptor liar = new LyingAcceptor(100, 30);
        long delivered = NetworkTransfer.distribute(net, List.of(), List.of(liar));

        assertEquals(30, delivered);
        assertEquals(70, net.stored()); // net only lost the truly-moved 30
    }

    @Test
    void emptyNoProvidersNoAcceptors() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 1000, 8);

        long delivered = NetworkTransfer.distribute(net, List.of(), List.of());

        assertEquals(0, delivered);
        assertEquals(0, net.stored());
        assertNull(net.lockedResourceId());
    }
}
