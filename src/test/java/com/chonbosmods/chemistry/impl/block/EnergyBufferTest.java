package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class EnergyBufferTest {

    @Test
    void receiveClampsToCapacityAndReturnsAccepted() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        assertEquals(60, b.receiveEnergy(60, false));
        assertEquals(40, b.receiveEnergy(80, false));
        assertEquals(100, b.getStored());
    }

    @Test
    void receiveSimulateDoesNotMutate() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        assertEquals(100, b.receiveEnergy(500, true));
        assertEquals(0, b.getStored());
    }

    @Test
    void extractClampsToStoredAndReturnsProvided() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        b.receiveEnergy(50, false);
        assertEquals(50, b.extractEnergy(80, false));
        assertEquals(0, b.getStored());
    }

    @Test
    void codecRoundTripsStoredAndCapacity() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        b.receiveEnergy(30, false);
        BsonValue encoded = EnergyBuffer.CODEC.encode(b, EmptyExtraInfo.EMPTY);
        EnergyBuffer reDecoded = EnergyBuffer.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertEquals(30, reDecoded.getStored());
        assertEquals(100, reDecoded.getMaxStored());
    }
}
