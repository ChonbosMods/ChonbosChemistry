package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.chonbosmods.chemistry.impl.block.EnergyBuffer;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link EndpointAdapters} factory methods against real energy/resource buffers. */
class EndpointAdaptersTest {

    // --- POWER acceptor ---

    @Test
    void powerAcceptor_reportsFreeSpaceAndInsertsThroughBuffer() {
        EnergyBuffer energy = EnergyBuffer.withCapacity(1000L);
        Acceptor acceptor = EndpointAdapters.powerAcceptor(energy);

        // resourceId is ignored for POWER (null).
        assertEquals(1000L, acceptor.capacityFor(null));

        // simulate must not mutate.
        assertEquals(300L, acceptor.insert(null, 300L, true));
        assertEquals(1000L, acceptor.capacityFor(null));

        // commit moves 300, free space drops to 700.
        assertEquals(300L, acceptor.insert(null, 300L, false));
        assertEquals(700L, acceptor.capacityFor(null));
        assertEquals(300L, energy.getStored());
    }

    @Test
    void powerAcceptor_capacityNeverNegative() {
        EnergyBuffer energy = EnergyBuffer.withCapacity(100L);
        energy.receiveEnergy(100L, false);
        Acceptor acceptor = EndpointAdapters.powerAcceptor(energy);
        assertEquals(0L, acceptor.capacityFor(null));
    }

    // --- POWER provider ---

    @Test
    void powerProvider_extractsAndReportsNullResource() {
        EnergyBuffer energy = EnergyBuffer.withCapacity(1000L);
        energy.receiveEnergy(500L, false);
        Provider provider = EndpointAdapters.powerProvider(energy);

        assertNull(provider.resourceId());

        // simulate predicts, does not mutate.
        assertEquals(400L, provider.extract(400L, true));
        assertEquals(500L, energy.getStored());

        // commit drains 400.
        assertEquals(400L, provider.extract(400L, false));
        assertEquals(100L, energy.getStored());

        // can't extract more than stored.
        assertEquals(100L, provider.extract(9999L, false));
        assertEquals(0L, energy.getStored());
    }

    // --- FLUID/GAS provider ---

    @Test
    void resourceProvider_extractsUpToHeldAndReportsResourceId() {
        ResourceBuffer buffer = ResourceBuffer.withCapacity(1000);
        buffer.insert("oxygen", 50, false);
        Provider provider = EndpointAdapters.resourceProvider(buffer);

        assertEquals("oxygen", provider.resourceId());

        // simulate predicts the extractable amount (capped at the 50 held).
        assertEquals(50L, provider.extract(9999L, true));
        assertEquals(50, buffer.amount());

        // commit drains all 50; buffer empties + unlocks so resourceId becomes null.
        assertEquals(50L, provider.extract(9999L, false));
        assertEquals(0, buffer.amount());
        assertNull(provider.resourceId());
    }

    @Test
    void resourceProvider_clampsLongBudgetToIntRange() {
        ResourceBuffer buffer = ResourceBuffer.withCapacity(100);
        buffer.insert("water", 100, false);
        Provider provider = EndpointAdapters.resourceProvider(buffer);
        // A budget above Integer.MAX_VALUE must not overflow; extract is capped at the 100 held.
        assertEquals(100L, provider.extract((long) Integer.MAX_VALUE + 1000L, false));
        assertEquals(0, buffer.amount());
    }

    // --- FLUID/GAS acceptor ---

    @Test
    void resourceAcceptor_capacityAndInsertRespectTypeLock() {
        ResourceBuffer buffer = ResourceBuffer.withCapacity(1000);
        Acceptor acceptor = EndpointAdapters.resourceAcceptor(buffer);

        // empty/unlocked buffer accepts any resource: full free space.
        assertEquals(1000L, acceptor.capacityFor("oxygen"));

        // commit 300 oxygen -> locks to oxygen, free space 700.
        assertEquals(300L, acceptor.insert("oxygen", 300L, false));
        assertEquals(700L, acceptor.capacityFor("oxygen"));
        assertEquals(300, buffer.amount());

        // a different resource cannot enter while locked.
        assertEquals(0L, acceptor.capacityFor("nitrogen"));
        assertEquals(0L, acceptor.insert("nitrogen", 100L, false));
        assertEquals(300, buffer.amount());
    }

    @Test
    void resourceAcceptor_clampsLongBudgetToIntRange() {
        ResourceBuffer buffer = ResourceBuffer.withCapacity(100);
        Acceptor acceptor = EndpointAdapters.resourceAcceptor(buffer);
        // A budget above Integer.MAX_VALUE must not overflow; insert is capped at the 100 capacity.
        assertEquals(100L, acceptor.insert("water", (long) Integer.MAX_VALUE + 1000L, false));
        assertEquals(100, buffer.amount());
    }
}
