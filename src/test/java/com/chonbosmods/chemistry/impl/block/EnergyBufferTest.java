package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void externalReceiveRespectsMaxReceive() {
        EnergyBuffer b = EnergyBuffer.withCapacityAndRates(1000, 10, 10); // cap, maxReceive, maxExtract
        assertEquals(10, b.receiveEnergy(100, false), "external receive clamps to maxReceive");
        assertEquals(10, b.getStored());
    }

    @Test
    void internalReceiveBypassesRateCap() {
        EnergyBuffer b = EnergyBuffer.withCapacityAndRates(1000, 10, 10);
        assertEquals(100, b.receiveEnergyInternal(100, false), "internal fill ignores maxReceive");
        assertEquals(100, b.getStored());
    }

    @Test
    void externalExtractRespectsMaxExtract() {
        EnergyBuffer b = EnergyBuffer.withCapacityAndRates(1000, 1000, 5);
        b.receiveEnergyInternal(100, false);
        assertEquals(5, b.extractEnergy(100, false), "external extract clamps to maxExtract");
    }

    @Test
    void internalExtractBypassesRateCap() {
        EnergyBuffer b = EnergyBuffer.withCapacityAndRates(1000, 1000, 5);
        b.receiveEnergyInternal(100, false);
        assertEquals(100, b.extractEnergyInternal(100, false), "internal drain ignores maxExtract");
        assertEquals(0, b.getStored());
    }

    @Test
    void ratioHelpers() {
        EnergyBuffer b = EnergyBuffer.withCapacityAndRates(100, 100, 100);
        assertTrue(b.isEmpty());
        assertFalse(b.isFull());
        b.receiveEnergyInternal(50, false);
        assertEquals(0.5f, b.getFillRatio(), 1e-6);
        b.receiveEnergyInternal(50, false);
        assertTrue(b.isFull());
        assertFalse(b.isEmpty());
    }

    @Test
    void backCompatWithCapacityDefaultsToUncappedRates() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100); // existing factory keeps working
        assertEquals(100, b.receiveEnergy(500, false), "no explicit cap so external equals internal");
    }

    @Test
    void newRateFieldsRoundTripThroughCodec() {
        EnergyBuffer b = EnergyBuffer.withCapacityAndRates(1000, 10, 5);
        b.receiveEnergyInternal(42, false);
        BsonValue encoded = EnergyBuffer.CODEC.encode(b, EmptyExtraInfo.EMPTY);
        EnergyBuffer decoded = EnergyBuffer.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertEquals(42, decoded.getStored());
        assertEquals(10, decoded.getMaxReceive());
        assertEquals(5, decoded.getMaxExtract());
    }
}
