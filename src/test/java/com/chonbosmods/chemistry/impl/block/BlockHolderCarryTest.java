package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;

/**
 * Unified break/place contents carry via the engine's native {@code "BlockHolder"} metadata path
 * (2026-06-05 design; supersedes the CC_StoredEnergy single-field carry). The pure layer under test:
 * the BSON stamping seam and the should-this-block-carry predicates.
 */
class BlockHolderCarryTest {

    private static MachineBlockState machine(EnergyBuffer energy, ResourceBuffer fluid) {
        Map<PortChannel, ResourceBuffer> resources = new EnumMap<>(PortChannel.class);
        if (fluid != null) {
            resources.put(PortChannel.FLUID, fluid);
        }
        return MachineBlockState.create(
            energy, resources, PortConfig.of(List.of()), new WorkState(), 100);
    }

    // --- BSON stamping seam ---

    @Test
    void stampAndReadRoundTrip() {
        BsonDocument holderDoc = new BsonDocument("Components", new BsonInt32(7));

        BsonDocument metadata = BlockHolderCarry.stamp(null, holderDoc);
        assertEquals(holderDoc, BlockHolderCarry.read(metadata));
    }

    @Test
    void stampClonesAndNeverMutatesTheSource() {
        BsonDocument source = new BsonDocument("Existing", new BsonInt32(1));
        BsonDocument holderDoc = new BsonDocument("Components", new BsonInt32(7));

        BsonDocument metadata = BlockHolderCarry.stamp(source, holderDoc);

        assertFalse(source.containsKey(BlockHolderCarry.KEY), "source doc must not be mutated");
        assertTrue(metadata.containsKey("Existing"), "existing metadata keys survive the stamp");
        assertEquals(holderDoc, BlockHolderCarry.read(metadata));
    }

    @Test
    void nullHolderDocIsANoOpAndReadOfPlainMetadataIsNull() {
        BsonDocument source = new BsonDocument("Existing", new BsonInt32(1));
        assertEquals(source, BlockHolderCarry.stamp(source, null));
        assertNull(BlockHolderCarry.read(source));
        assertNull(BlockHolderCarry.read(null));
    }

    // --- carry predicates ---

    @Test
    void machineWithStoredEnergyCarries() {
        EnergyBuffer energy = EnergyBuffer.withCapacity(1000);
        energy.receiveEnergy(1, false);
        assertTrue(BlockHolderCarry.shouldCarry(machine(energy, null)));
    }

    @Test
    void machineWithNonEmptyResourceBufferCarries() {
        ResourceBuffer fluid = ResourceBuffer.withCapacity(500);
        fluid.insert("compound:water", 1, false);
        assertTrue(BlockHolderCarry.shouldCarry(machine(EnergyBuffer.withCapacity(1000), fluid)));
    }

    @Test
    void emptyMachineDoesNotCarry() {
        assertFalse(BlockHolderCarry.shouldCarry(machine(EnergyBuffer.withCapacity(1000), ResourceBuffer.withCapacity(500))));
        assertFalse(BlockHolderCarry.shouldCarry(machine(null, null)), "no buffers at all: vanilla drop");
        assertFalse(BlockHolderCarry.shouldCarry((MachineBlockState) null));
    }

    @Test
    void tankCarriesOnlyWhenHoldingContents() {
        ResourceBuffer holding = ResourceBuffer.withCapacity(200);
        holding.insert("element:bromine", 50, false);
        assertTrue(BlockHolderCarry.shouldCarry(
            TankBlockState.create(holding, PortChannel.FLUID, PortConfig.of(List.of()), 200)));

        assertFalse(BlockHolderCarry.shouldCarry(
            TankBlockState.create(ResourceBuffer.withCapacity(200), PortChannel.FLUID, PortConfig.of(List.of()), 200)));
        assertFalse(BlockHolderCarry.shouldCarry((TankBlockState) null));
    }
}
