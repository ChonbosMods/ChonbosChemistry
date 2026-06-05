package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link MachineEnergyMetadata}: the pure capture/restore bridge that carries a
 * machine's stored energy across break/place via item metadata.
 *
 * <p>These exercise the pure {@link BsonDocument} layer (encode/decode + buffer application), which is
 * exactly the engine-decoupled core. The thin {@code ItemStack} wrappers cannot be exercised headlessly
 * (the {@code ItemStack} constructor resolves an {@code Item} asset, requiring a loaded {@code
 * AssetStore}), so they are forwarders only and are verified in-game.
 */
class MachineEnergyMetadataTest {

    private static MachineBlockState machineWith(long capacity, long stored) {
        EnergyBuffer buffer = EnergyBuffer.withCapacity(capacity);
        if (stored > 0) {
            buffer.receiveEnergyInternal(stored, false);
        }
        return MachineBlockState.create(buffer, null, null, null, 100);
    }

    @Test
    void writeThenReadRoundTripsStoredAmount() {
        BsonDocument meta = MachineEnergyMetadata.writeStoredEnergy((BsonDocument) null, 1234L);
        assertNotNull(meta);
        assertEquals(1234L, MachineEnergyMetadata.readStoredEnergy(meta));
        assertTrue(MachineEnergyMetadata.hasStoredEnergy(meta));
    }

    @Test
    void readReturnsNullWhenNoMetadataPresent() {
        assertNull(MachineEnergyMetadata.readStoredEnergy((BsonDocument) null));
        assertNull(MachineEnergyMetadata.readStoredEnergy(new BsonDocument()));
        assertFalse(MachineEnergyMetadata.hasStoredEnergy((BsonDocument) null));
        assertFalse(MachineEnergyMetadata.hasStoredEnergy(new BsonDocument()));
    }

    @Test
    void writeWithZeroOrNegativeCollapsesToNullAndClearsKey() {
        // From an empty source, nothing to persist -> null (an empty metadata doc, dropped as plain item).
        assertNull(MachineEnergyMetadata.writeStoredEnergy((BsonDocument) null, 0L));
        assertNull(MachineEnergyMetadata.writeStoredEnergy((BsonDocument) null, -5L));

        // Re-stamping an already-charged doc with 0 removes the key.
        BsonDocument charged = MachineEnergyMetadata.writeStoredEnergy((BsonDocument) null, 500L);
        BsonDocument cleared = MachineEnergyMetadata.writeStoredEnergy(charged, 0L);
        assertNull(cleared);
        // source doc was not mutated.
        assertEquals(500L, MachineEnergyMetadata.readStoredEnergy(charged));
    }

    @Test
    void writePreservesOtherMetadataKeys() {
        BsonDocument source = new BsonDocument();
        source.put("OtherKey", new org.bson.BsonString("keepme"));
        BsonDocument result = MachineEnergyMetadata.writeStoredEnergy(source, 42L);
        assertNotNull(result);
        assertEquals(42L, MachineEnergyMetadata.readStoredEnergy(result));
        assertEquals("keepme", result.getString("OtherKey").getValue());
        // source not mutated.
        assertFalse(source.containsKey(MachineEnergyMetadata.KEY));
    }

    @Test
    void applyStoredEnergyClampsToRemainingCapacity() {
        EnergyBuffer buffer = EnergyBuffer.withCapacity(100);
        long accepted = MachineEnergyMetadata.applyStoredEnergy(buffer, 250L);
        assertEquals(100L, accepted);
        assertEquals(100L, buffer.getStored());
    }

    @Test
    void applyStoredEnergyBypassesExternalReceiveRateCap() {
        // maxReceive = 10 would throttle an external receiveEnergy, but restore is internal.
        EnergyBuffer buffer = EnergyBuffer.withCapacityAndRates(1000, 10, 10);
        long accepted = MachineEnergyMetadata.applyStoredEnergy(buffer, 750L);
        assertEquals(750L, accepted);
        assertEquals(750L, buffer.getStored());
    }

    @Test
    void applyStoredEnergyTolerantOfNullBufferAndNonPositive() {
        assertEquals(0L, MachineEnergyMetadata.applyStoredEnergy(null, 100L));
        EnergyBuffer buffer = EnergyBuffer.withCapacity(100);
        assertEquals(0L, MachineEnergyMetadata.applyStoredEnergy(buffer, 0L));
        assertEquals(0L, MachineEnergyMetadata.applyStoredEnergy(buffer, -10L));
        assertEquals(0L, buffer.getStored());
    }

    @Test
    void restoreIntoEndToEndBreakThenPlaceCarriesCharge() {
        // BREAK: capture a charged battery's stored energy into a metadata doc.
        MachineBlockState broken = machineWith(8000, 6400);
        BsonDocument drop = MachineEnergyMetadata.writeStoredEnergy(
                (BsonDocument) null, broken.energy().getStored());

        // PLACE: a freshly created (empty) block entity is rehydrated from the drop metadata.
        MachineBlockState placed = machineWith(8000, 0);
        long restored = MachineEnergyMetadata.restoreInto(drop, placed);

        assertEquals(6400L, restored);
        assertEquals(6400L, placed.energy().getStored());
    }

    @Test
    void restoreIntoClampsWhenNewBlockHasSmallerCapacity() {
        BsonDocument drop = MachineEnergyMetadata.writeStoredEnergy((BsonDocument) null, 6400L);
        // Defensive: if the placed block has a smaller buffer, restore clamps (no overflow).
        MachineBlockState placed = machineWith(1000, 0);
        long restored = MachineEnergyMetadata.restoreInto(drop, placed);
        assertEquals(1000L, restored);
        assertEquals(1000L, placed.energy().getStored());
    }

    @Test
    void restoreIntoTolerantOfNullStateNullMetaAndMissingKey() {
        assertEquals(0L, MachineEnergyMetadata.restoreInto(new BsonDocument(), null));
        MachineBlockState placed = machineWith(1000, 0);
        assertEquals(0L, MachineEnergyMetadata.restoreInto((BsonDocument) null, placed));
        assertEquals(0L, MachineEnergyMetadata.restoreInto(new BsonDocument(), placed));
        assertEquals(0L, placed.energy().getStored());
    }

    @Test
    void restoreIntoNoOpWhenStateHasNoEnergyBuffer() {
        BsonDocument drop = MachineEnergyMetadata.writeStoredEnergy((BsonDocument) null, 500L);
        MachineBlockState noEnergy = MachineBlockState.create(null, null, null, null, 100);
        assertEquals(0L, MachineEnergyMetadata.restoreInto(drop, noEnergy));
    }
}
