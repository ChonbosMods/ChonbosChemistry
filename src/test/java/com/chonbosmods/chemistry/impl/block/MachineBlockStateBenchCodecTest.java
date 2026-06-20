package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link MachineBlockState} can HOLD a vanilla {@link ProcessingBenchBlock} (the smelter's
 * recipe/slots/progress engine) and its sibling {@link BenchBlock}, persisted as OPTIONAL embedded
 * codec keys (Task 7 / D31). The held types are internal fields, never registered ECS components, so
 * vanilla's {@code BenchSystems.ProcessingBenchTick} (which queries by the {@code ProcessingBenchBlock}
 * component type) never ticks our block.
 */
class MachineBlockStateBenchCodecTest {

    private static MachineBlockState sampleNoBench() {
        EnergyBuffer energy = EnergyBuffer.withCapacity(1000);
        energy.receiveEnergy(250, false);

        Map<PortChannel, ResourceBuffer> resources = new EnumMap<>(PortChannel.class);

        return MachineBlockState.create(energy, resources, PortConfig.of(List.of()), new WorkState(), 100);
    }

    /**
     * Test A: a MachineBlockState with NO held bench round-trips through its own CODEC (encode then
     * decode, mirroring clone()) without error, and both held-bench accessors stay null. The absent
     * "HeldBench"/"HeldBenchBlock" keys must be tolerated on decode. Purely headless: no server.
     */
    @Test
    void noBenchRoundTripsAndStaysNull() {
        MachineBlockState state = sampleNoBench();
        assertNull(state.heldBench());
        assertNull(state.heldBenchBlock());

        BsonValue encoded = MachineBlockState.CODEC.encode(state, EmptyExtraInfo.EMPTY);
        MachineBlockState decoded = MachineBlockState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertNull(decoded.heldBench());
        assertNull(decoded.heldBenchBlock());
        // sanity: the rest of the state still decodes
        assertNotNull(decoded.energy());
    }

    /**
     * Test B: an EMPTY ProcessingBenchBlock + BenchBlock set on a MachineBlockState survive clone()
     * (a codec round-trip) as independent, non-null instances. Proves the held benches are embedded in
     * the CODEC and deep-copied rather than shared.
     */
    @Test
    void heldBenchClonesIndependently() {
        MachineBlockState state = sampleNoBench();
        state.setHeldBench(new ProcessingBenchBlock());
        state.setHeldBenchBlock(new BenchBlock());

        assertNotNull(state.heldBench());
        assertNotNull(state.heldBenchBlock());

        MachineBlockState copy = (MachineBlockState) state.clone();

        assertNotNull(copy.heldBench());
        assertNotNull(copy.heldBenchBlock());
        assertNotSame(state.heldBench(), copy.heldBench());
        assertNotSame(state.heldBenchBlock(), copy.heldBenchBlock());
    }
}
