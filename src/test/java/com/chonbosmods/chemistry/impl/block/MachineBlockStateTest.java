package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class MachineBlockStateTest {

    private static MachineBlockState sample() {
        EnergyBuffer energy = EnergyBuffer.withCapacity(1000);
        energy.receiveEnergy(250, false);

        ResourceBuffer fluid = ResourceBuffer.withCapacity(500);
        fluid.insert("water", 120, false);

        Map<PortChannel, ResourceBuffer> resources = new EnumMap<>(PortChannel.class);
        resources.put(PortChannel.FLUID, fluid);

        PortConfig ports = PortConfig.of(List.of(
            Port.of(0, PortChannel.FLUID, PortDirection.OUTPUT),
            Port.of(1, PortChannel.POWER, PortDirection.INPUT)));

        WorkState work = new WorkState();
        work.advance(0.5f, 2.0f, true);

        return MachineBlockState.create(energy, resources, ports, work, 100);
    }

    @Test
    void cloneProducesIndependentDeepCopy() {
        MachineBlockState original = sample();
        MachineBlockState copy = (MachineBlockState) original.clone();

        // baseline
        assertEquals(250, copy.energy().getStored());
        assertEquals(120, copy.resource(PortChannel.FLUID).amount());

        // mutate the original after cloning
        original.energy().receiveEnergy(100, false);
        original.resource(PortChannel.FLUID).extract(50, false);

        assertEquals(350, original.energy().getStored());
        assertEquals(70, original.resource(PortChannel.FLUID).amount());

        // the clone is untouched -> proves deep copy
        assertEquals(250, copy.energy().getStored());
        assertEquals(120, copy.resource(PortChannel.FLUID).amount());
    }

    @Test
    void transferNodeDelegationReturnsBuffers() {
        MachineBlockState state = sample();

        assertNotNull(state.energy());
        assertEquals(250, state.energy().getStored());

        assertNotNull(state.resource(PortChannel.FLUID));
        assertEquals(120, state.resource(PortChannel.FLUID).amount());

        assertNull(state.resource(PortChannel.GAS));

        assertSame(state.ports(), state.ports());
        assertEquals(2, state.ports().ports().size());

        assertEquals(100, state.throughput(PortChannel.POWER));
        assertEquals(100, state.throughput(PortChannel.FLUID));
    }

    @Test
    void codecRoundTripsViaEncode() {
        MachineBlockState state = sample();
        BsonValue encoded = MachineBlockState.CODEC.encode(state, EmptyExtraInfo.EMPTY);
        MachineBlockState decoded = MachineBlockState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertEquals(250, decoded.energy().getStored());
        assertEquals(1000, decoded.energy().getMaxStored());

        assertEquals("water", decoded.resource(PortChannel.FLUID).resourceId());
        assertEquals(120, decoded.resource(PortChannel.FLUID).amount());
        assertEquals(500, decoded.resource(PortChannel.FLUID).capacity());

        assertEquals(2, decoded.ports().ports().size());
        assertEquals(PortChannel.FLUID, decoded.ports().ports().get(0).channel());

        assertEquals(0.5f, decoded.work().progress(), 1e-6);
        assertEquals(true, decoded.work().active());

        assertEquals(100, decoded.throughput(PortChannel.FLUID));
    }

    @Test
    void energyDrainPerTickDefaultsToZeroWhenAbsent() {
        // sample() never sets a drain, and the codec key is omitted on encode for a 0 value — decode
        // must restore 0, not trip the primitive-null check.
        MachineBlockState state = sample();
        assertEquals(0L, state.energyDrainPerTick());

        BsonValue encoded = MachineBlockState.CODEC.encode(state, EmptyExtraInfo.EMPTY);
        MachineBlockState decoded = MachineBlockState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertEquals(0L, decoded.energyDrainPerTick());
    }

    @Test
    void energyDrainPerTickRoundTripsWhenSet() {
        MachineBlockState state = sample();
        state.setEnergyDrainPerTick(7L);

        BsonValue encoded = MachineBlockState.CODEC.encode(state, EmptyExtraInfo.EMPTY);
        MachineBlockState decoded = MachineBlockState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertEquals(7L, decoded.energyDrainPerTick());

        // clone() is a codec round-trip, so the field must survive a clone too.
        MachineBlockState copy = (MachineBlockState) state.clone();
        assertEquals(7L, copy.energyDrainPerTick());
    }

    @Test
    void energyOptionalRoundTripsWhenAbsent() {
        Map<PortChannel, ResourceBuffer> resources = new EnumMap<>(PortChannel.class);
        ResourceBuffer gas = ResourceBuffer.withCapacity(200);
        gas.insert("steam", 40, false);
        resources.put(PortChannel.GAS, gas);

        MachineBlockState state = MachineBlockState.create(
            null, resources, PortConfig.of(List.of()), new WorkState(), 100);

        assertNull(state.energy());

        BsonValue encoded = MachineBlockState.CODEC.encode(state, EmptyExtraInfo.EMPTY);
        MachineBlockState decoded = MachineBlockState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertNull(decoded.energy());
        assertEquals(40, decoded.resource(PortChannel.GAS).amount());
    }

    // --- enabled (On/Off; the circuit run/halt control line) ---

    @Test
    void enabledDefaultsTrue() {
        assertTrue(sample().isEnabled());
    }

    @Test
    void setEnabledToggles() {
        MachineBlockState m = sample();
        m.setEnabled(false);
        assertFalse(m.isEnabled());
        m.setEnabled(true);
        assertTrue(m.isEnabled());
    }

    @Test
    void enabledSurvivesEncodeDecode() {
        MachineBlockState m = sample();
        m.setEnabled(false);
        BsonValue encoded = MachineBlockState.CODEC.encode(m, EmptyExtraInfo.EMPTY);
        MachineBlockState decoded = MachineBlockState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertFalse(decoded.isEnabled());
    }

    @Test
    void absentEnabledKeyDecodesTrue() {
        // Legacy data (saved before the flag) carries no "Enabled" key: it must default to ON.
        MachineBlockState m = sample();
        m.setEnabled(false);
        BsonDocument encoded = MachineBlockState.CODEC.encode(m, EmptyExtraInfo.EMPTY).asDocument();
        encoded.remove("Enabled");
        MachineBlockState decoded = MachineBlockState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertTrue(decoded.isEnabled());
    }
}
