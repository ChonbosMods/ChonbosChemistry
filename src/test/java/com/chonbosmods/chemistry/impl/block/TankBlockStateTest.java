package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.util.List;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class TankBlockStateTest {

    private static TankBlockState sample() {
        ResourceBuffer buffer = ResourceBuffer.withCapacity(8000);
        buffer.insert("water", 3000, false);
        PortConfig ports = PortConfig.of(List.of(
            Port.of(0, PortChannel.FLUID, PortDirection.OUTPUT)));
        return TankBlockState.create(buffer, PortChannel.FLUID, ports, 200);
    }

    @Test
    void cloneProducesIndependentDeepCopy() {
        TankBlockState original = sample();
        TankBlockState copy = (TankBlockState) original.clone();

        assertEquals(3000, copy.resource(PortChannel.FLUID).amount());

        original.resource(PortChannel.FLUID).extract(1000, false);
        assertEquals(2000, original.resource(PortChannel.FLUID).amount());

        assertEquals(3000, copy.resource(PortChannel.FLUID).amount());
    }

    @Test
    void resourceMappingHonoursChannel() {
        TankBlockState state = sample();
        assertNotNull(state.resource(PortChannel.FLUID));
        assertNull(state.resource(PortChannel.GAS));
        assertNull(state.resource(PortChannel.POWER));
        assertNull(state.energy());
        assertEquals(200, state.throughput(PortChannel.FLUID));
    }

    @Test
    void codecRoundTrips() {
        TankBlockState state = sample();
        BsonValue encoded = TankBlockState.CODEC.encode(state, EmptyExtraInfo.EMPTY);
        TankBlockState decoded = TankBlockState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertEquals(PortChannel.FLUID, decoded.channel());
        assertEquals("water", decoded.resource(PortChannel.FLUID).resourceId());
        assertEquals(3000, decoded.resource(PortChannel.FLUID).amount());
        assertEquals(8000, decoded.resource(PortChannel.FLUID).capacity());
        assertEquals(1, decoded.ports().ports().size());
        assertEquals(200, decoded.throughput(PortChannel.FLUID));
    }
}
