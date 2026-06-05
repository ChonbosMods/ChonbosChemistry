package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.*;

import com.chonbosmods.chemistry.api.io.PortChannel;
import org.junit.jupiter.api.Test;

class NetworkTest {

    @Test
    void memberKeysReflectsAddedAndRemovedMembers() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(7L, 100, 8);
        net.addMember(42L, 100, 8);
        assertEquals(java.util.Set.of(7L, 42L), net.memberKeys());

        net.removeMember(7L);
        assertEquals(java.util.Set.of(42L), net.memberKeys());
    }

    @Test
    void powerAggregatesCapacityAndMinThroughput() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 100, 8);
        net.addMember(2, 25, 4);
        assertEquals(125, net.capacity());
        assertEquals(4, net.throughput()); // min of members (bottleneck)

        net.removeMember(2);
        assertEquals(100, net.capacity());
        assertEquals(8, net.throughput());
    }

    @Test
    void powerInsertExtractClampsAndIgnoresResourceId() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 100, 8);

        assertEquals(50, net.insert(null, 50, false));
        assertEquals(50, net.stored());

        assertEquals(50, net.insert(null, 100, false)); // clamped to free space
        assertEquals(100, net.stored());

        assertEquals(30, net.extract(30, false));
        assertEquals(70, net.stored());

        // POWER never locks
        assertNull(net.lockedResourceId());
    }

    @Test
    void simulateDoesNotMutate() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 100, 8);

        assertEquals(50, net.insert(null, 50, true));
        assertEquals(0, net.stored());

        net.insert(null, 40, false);
        assertEquals(10, net.extract(10, true)); // simulate: reports removable but does not mutate
        assertEquals(40, net.stored());
    }

    @Test
    void fluidTypeLockRejectsDifferentResourceUntilEmptied() {
        Network net = new Network(PortChannel.FLUID);
        net.addMember(1, 100, 8);

        assertEquals(10, net.insert("oxygen", 10, false));
        assertEquals("oxygen", net.lockedResourceId());

        assertEquals(0, net.insert("helium", 10, false)); // rejected: type-locked
        assertEquals(10, net.stored());

        assertEquals(10, net.extract(10, false)); // now empty
        assertNull(net.lockedResourceId()); // lock cleared

        assertEquals(5, net.insert("helium", 5, false));
        assertEquals("helium", net.lockedResourceId());
    }

    @Test
    void fluidInsertNullResourceRejected() {
        Network net = new Network(PortChannel.FLUID);
        net.addMember(1, 100, 8);

        // null is never a valid thing to store on a type-locked channel: reject it,
        // leaving stored and the lock untouched.
        assertEquals(0, net.insert(null, 10, false));
        assertEquals(0, net.stored());
        assertNull(net.lockedResourceId());

        // proving the rejected null did not corrupt state: a normal insert still locks.
        assertEquals(10, net.insert("oxygen", 10, false));
        assertEquals("oxygen", net.lockedResourceId());

        // POWER legitimately uses a null resourceId and must be unaffected.
        Network power = new Network(PortChannel.POWER);
        power.addMember(1, 100, 8);
        assertEquals(10, power.insert(null, 10, false));
    }

    @Test
    void removeMemberNonexistentKeyIsNoop() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 100, 8);
        net.addMember(2, 25, 4);
        long capBefore = net.capacity();
        int throughputBefore = net.throughput();

        net.removeMember(999); // absent key

        assertEquals(capBefore, net.capacity());
        assertEquals(throughputBefore, net.throughput());
    }

    @Test
    void emptyNetworkAcceptsNothing() {
        Network net = new Network(PortChannel.POWER);
        assertEquals(0, net.capacity());
        assertEquals(0, net.throughput());
        assertEquals(0, net.insert(null, 10, false));
        assertEquals(0, net.extract(10, false));
        assertEquals(0f, net.fillRatio());
    }

    @Test
    void negativeOrZeroAmountAcceptsNothing() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 100, 8);
        assertEquals(0, net.insert(null, 0, false));
        assertEquals(0, net.insert(null, -5, false));
        assertEquals(0, net.extract(0, false));
        assertEquals(0, net.extract(-5, false));
    }

    @Test
    void fillRatioReflectsStoredOverCapacity() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 100, 8);
        net.insert(null, 25, false);
        assertEquals(0.25f, net.fillRatio());
    }

    @Test
    void addMemberWithDuplicateKeyReplaces() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 100, 8);
        net.addMember(1, 40, 2); // replaces key 1
        assertEquals(40, net.capacity());
        assertEquals(2, net.throughput());
    }

    @Test
    void removeMemberClampsStoredToNewCapacity() {
        Network net = new Network(PortChannel.POWER);
        net.addMember(1, 100, 8);
        net.addMember(2, 100, 8);
        net.insert(null, 150, false);
        assertEquals(150, net.stored());

        net.removeMember(2); // capacity drops to 100, below stored
        assertEquals(100, net.capacity());
        assertEquals(100, net.stored()); // clamped down
    }
}
