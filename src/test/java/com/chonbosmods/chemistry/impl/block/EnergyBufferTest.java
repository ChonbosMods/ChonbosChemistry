package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import org.bson.BsonDocument;
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

    /**
     * Legacy / rig-block JSON only specifies Stored + Capacity (the rate keys were added later).
     * A document lacking MaxReceive/MaxExtract must decode to UNCAPPED rates (Long.MAX_VALUE),
     * not to 0 (which the interface contract reads as "no external input/output", making the
     * block silently energy-inert) and not to a decode error.
     */
    @Test
    void legacyDocumentMissingRateKeysDecodesToUncapped() {
        // Build a full document, then strip the rate keys to mimic legacy/pre-existing data.
        EnergyBuffer full = EnergyBuffer.withCapacityAndRates(1000, 10, 5);
        full.receiveEnergyInternal(30, false);
        BsonDocument doc = EnergyBuffer.CODEC.encode(full, EmptyExtraInfo.EMPTY).asDocument();
        doc.remove("MaxReceive");
        doc.remove("MaxExtract");
        assertTrue(doc.containsKey("Stored") && doc.containsKey("Capacity"),
            "precondition: legacy doc still carries Stored + Capacity");

        EnergyBuffer decoded = EnergyBuffer.CODEC.decode(doc, EmptyExtraInfo.EMPTY);

        assertEquals(Long.MAX_VALUE, decoded.getMaxReceive(), "missing MaxReceive -> uncapped");
        assertEquals(Long.MAX_VALUE, decoded.getMaxExtract(), "missing MaxExtract -> uncapped");
        // External receive must NOT be clamped to 0: it should accept what the internal path accepts.
        long external = decoded.receiveEnergy(500, true);
        long internal = decoded.receiveEnergyInternal(500, true);
        assertEquals(internal, external, "uncapped external receive equals internal accept");
        assertTrue(external > 0, "uncapped buffer is not energy-inert");
    }

    @Test
    void internalSimulateDoesNotMutate() {
        EnergyBuffer b = EnergyBuffer.withCapacity(1000);
        b.receiveEnergyInternal(100, false);
        assertEquals(100, b.getStored());
        assertEquals(50, b.receiveEnergyInternal(50, true), "simulate reports movable amount");
        assertEquals(50, b.extractEnergyInternal(50, true), "simulate reports movable amount");
        assertEquals(100, b.getStored(), "simulate leaves stored unchanged");
    }

    @Test
    void maxReceiveZeroBlocksExternalInput() {
        EnergyBuffer b = EnergyBuffer.withCapacityAndRates(1000, 0, 1000);
        assertEquals(0, b.receiveEnergy(100, false), "maxReceive=0 blocks external input");
        assertEquals(0, b.getStored());
        assertEquals(100, b.receiveEnergyInternal(100, false), "internal fill still works");
        assertEquals(100, b.getStored());
    }

    @Test
    void externalReceiveClampedByFreeCapacityNotJustRate() {
        EnergyBuffer b = EnergyBuffer.withCapacityAndRates(30, 1000, 1000); // rate >> capacity
        assertEquals(30, b.receiveEnergy(100, false), "bounded by free capacity, not rate");
        assertEquals(30, b.getStored());
    }
}
