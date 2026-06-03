package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * System-level, black-box scenarios over {@link NetworkTransfer#distribute} + {@link Network},
 * asserting the mod's transport design rules via buffer/acceptor state (not just return values):
 *
 * <ol>
 *   <li>FLUID/GAS type-lock end-to-end (design rule 1): one resource flows, the network locks, the
 *       other is never pulled, and the lock clears once the buffer fully drains.
 *   <li>Backpressure / no voiding (design rule 7): full acceptors stop the flow and the buffer
 *       retains its contents — nothing is voided.
 *   <li>Multi-source shared buffer: several providers accumulate into one buffer, which then
 *       fair-splits to acceptors.
 *   <li>Index alignment past a zero-capacity acceptor in the middle of the list.
 *   <li>A lying {@code capacityFor} (claims more than {@code insert} truly takes) cannot make the
 *       network lose resource: the remainder stays stored and is deliverable later.
 * </ol>
 *
 * <p>Cases 3 and 5 additionally assert the lossless conservation invariant
 * {@code (Σ providers initially) == (Σ providers remaining) + net.stored() + (Σ delivered)}.
 */
class NetworkScenarioTest {

    // --- Fakes (mirrors NetworkTransferTest's inline style; kept local to stay black-box). ---

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

    /** Resource-agnostic acceptor (POWER): tracks how much it has received. */
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
    }

    /** Accepts only the locked resource; capacityFor returns 0 for anything else. */
    private static final class FakeFluidAcceptor implements Acceptor {
        private final String locked;
        private long room;
        private long received;

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
                received += taken;
            }
            return taken;
        }

        long received() {
            return received;
        }
    }

    /** Lies on capacityFor (claims more than insert will truly accept); tracks received. */
    private static final class LyingAcceptor implements Acceptor {
        private final long claimed;
        private long trueRoom;
        private long received;

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
                received += taken;
            }
            return taken;
        }

        long received() {
            return received;
        }
    }

    // --- Scenario 1: type-lock end-to-end, then lock clears on empty. ---

    @Test
    void typeLockEndToEndThenClearsOnEmpty() {
        Network net = new Network(PortChannel.FLUID);
        net.addMember(1, 1000, 8);

        FakeFluidAcceptor oxygenSink = new FakeFluidAcceptor("oxygen", 1000);
        FakeFluidAcceptor heliumSink = new FakeFluidAcceptor("helium", 1000);

        // Offer oxygen and helium together: only oxygen flows, network locks to oxygen,
        // helium is never pulled.
        long delivered = NetworkTransfer.distribute(
                net,
                List.of(new FakeProvider("oxygen", 50), new FakeProvider("helium", 50)),
                List.of(oxygenSink, heliumSink));

        assertEquals(50, delivered);
        assertEquals(50, oxygenSink.received());
        assertEquals(0, heliumSink.received()); // helium never pulled — type-locked out
        // Buffer fully drained to the matching oxygen acceptor, so the lock is cleared.
        assertEquals(0, net.stored());
        assertNull(net.lockedResourceId());

        // Lock cleared on empty: a later distribute with a helium provider now succeeds.
        long delivered2 = NetworkTransfer.distribute(
                net, List.of(new FakeProvider("helium", 40)), List.of(heliumSink));

        assertEquals(40, delivered2);
        assertEquals(40, heliumSink.received());
        // Helium fully drained out to its acceptor within this pass, so the buffer empties and the
        // lock clears again (proving the lock had switched to helium, not stayed stuck on oxygen).
        assertEquals(0, net.stored());
        assertNull(net.lockedResourceId());
    }

    // --- Scenario 2: backpressure / no voiding when all acceptors are full. ---

    @Test
    void backpressureFullAcceptorsVoidNothing() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 1000, 8);

        FakeProvider p1 = new FakeProvider(null, 60);
        FakeProvider p2 = new FakeProvider(null, 40);
        long originalTotal = p1.available() + p2.available(); // 100

        FakeAcceptor full1 = new FakeAcceptor(0);
        FakeAcceptor full2 = new FakeAcceptor(0);

        long delivered = NetworkTransfer.distribute(
                net, List.of(p1, p2), List.of(full1, full2));

        assertEquals(0, delivered);              // acceptors are full
        assertEquals(0, full1.received());
        assertEquals(0, full2.received());
        assertEquals(100, net.stored());         // buffer retains everything pulled

        // No voiding: every unit is accounted for (remaining + stored + delivered == original).
        long remaining = p1.available() + p2.available();
        assertEquals(originalTotal, remaining + net.stored() + delivered);
    }

    // --- Scenario 3: multi-source accumulation, then fair split. ---

    @Test
    void multiSourceAccumulatesThenFairSplits() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 1000, 8);

        FakeProvider p30 = new FakeProvider(null, 30);
        FakeProvider p40 = new FakeProvider(null, 40);
        long originalTotal = p30.available() + p40.available(); // 70

        // No acceptors: both providers drain into the single shared buffer.
        long delivered1 = NetworkTransfer.distribute(net, List.of(p30, p40), List.of());

        assertEquals(0, delivered1);
        assertEquals(70, net.stored());      // accumulated 30 + 40
        assertEquals(0, p30.available());    // both fully drained
        assertEquals(0, p40.available());

        // Conservation across the accumulation call.
        assertEquals(
                originalTotal,
                p30.available() + p40.available() + net.stored() + delivered1);

        // Second pass: two equal acceptors fair-split the 70 -> 35 / 35 (even, both can hold).
        FakeAcceptor a = new FakeAcceptor(1000);
        FakeAcceptor b = new FakeAcceptor(1000);
        long delivered2 = NetworkTransfer.distribute(net, List.of(), List.of(a, b));

        assertEquals(70, delivered2);
        assertEquals(35, a.received());
        assertEquals(35, b.received());
        assertEquals(0, net.stored());
    }

    // --- Scenario 4: interleaved zero-capacity acceptor keeps index alignment. ---

    @Test
    void interleavedZeroCapacityAcceptorKeepsIndexAlignment() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 1000, 8);

        // Pre-fill the buffer to exactly 100.
        NetworkTransfer.distribute(net, List.of(new FakeProvider(null, 100)), List.of());
        assertEquals(100, net.stored());

        FakeAcceptor left = new FakeAcceptor(50);
        FakeAcceptor middle = new FakeAcceptor(0); // zero room, sits between the two
        FakeAcceptor right = new FakeAcceptor(50);

        long delivered = NetworkTransfer.distribute(
                net, List.of(), List.of(left, middle, right));

        // FairSplit(100, [50, 0, 50]) -> 50 / 0 / 50: middle gets nothing, outer two split evenly.
        assertEquals(100, delivered);
        assertEquals(50, left.received());
        assertEquals(0, middle.received());
        assertEquals(50, right.received());
        assertEquals(0, net.stored()); // buffer conserved: nothing lost, nothing over-extracted
    }

    // --- Scenario 5: lying capacityFor cannot lose resource; remainder deliverable later. ---

    @Test
    void lyingAcceptorStrandsRemainderButLosesNothing() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 1000, 8);

        FakeProvider p = new FakeProvider(null, 100);
        long originalTotal = p.available(); // 100

        // First fill the buffer, then deliver to a liar that claims 100 but truly takes only 30.
        NetworkTransfer.distribute(net, List.of(p), List.of());
        assertEquals(100, net.stored());

        LyingAcceptor liar = new LyingAcceptor(100, 30);
        long delivered = NetworkTransfer.distribute(net, List.of(), List.of(liar));

        assertEquals(30, delivered);        // only the truly-accepted amount moved
        assertEquals(30, liar.received());
        assertEquals(70, net.stored());     // remainder stays stored — not voided

        // Conservation: provider drained, the rest is split between buffer and the acceptor.
        assertEquals(originalTotal, p.available() + net.stored() + liar.received());

        // The stranded 70 is deliverable on a later call to an honest acceptor.
        FakeAcceptor honest = new FakeAcceptor(1000);
        long delivered2 = NetworkTransfer.distribute(net, List.of(), List.of(honest));

        assertEquals(70, delivered2);
        assertEquals(70, honest.received());
        assertEquals(0, net.stored());
    }
}
