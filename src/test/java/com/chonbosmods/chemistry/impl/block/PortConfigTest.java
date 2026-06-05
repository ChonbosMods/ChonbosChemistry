package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.util.List;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class PortConfigTest {

    private static <T> T decode(Codec<T> c, String json) throws Exception {
        return c.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void filtersOutputsByChannel() {
        PortConfig cfg = PortConfig.of(List.of(
            Port.of(0, PortChannel.GAS, PortDirection.INPUT),
            Port.of(1, PortChannel.GAS, PortDirection.OUTPUT),
            Port.of(2, PortChannel.FLUID, PortDirection.OUTPUT),
            Port.of(3, PortChannel.GAS, PortDirection.CLOSED)));
        List<Port> gasOut = cfg.portsFor(PortChannel.GAS, PortDirection.OUTPUT);
        assertEquals(1, gasOut.size());
        assertEquals(1, gasOut.get(0).faceIndex());
    }

    @Test
    void filtersPowerPorts() {
        PortConfig cfg = PortConfig.of(List.of(
            Port.of(0, PortChannel.FLUID, PortDirection.OUTPUT),
            Port.of(1, PortChannel.POWER, PortDirection.OUTPUT),
            Port.of(2, PortChannel.GAS, PortDirection.OUTPUT),
            Port.of(3, PortChannel.POWER, PortDirection.INPUT)));
        List<Port> powerOut = cfg.portsFor(PortChannel.POWER, PortDirection.OUTPUT);
        assertEquals(1, powerOut.size());
        assertEquals(1, powerOut.get(0).faceIndex());
        assertEquals(PortChannel.POWER, powerOut.get(0).channel());
        assertTrue(cfg.portsFor(PortChannel.FLUID, PortDirection.OUTPUT).stream()
            .noneMatch(p -> p.channel() == PortChannel.POWER));
        assertTrue(cfg.portsFor(PortChannel.GAS, PortDirection.OUTPUT).stream()
            .noneMatch(p -> p.channel() == PortChannel.POWER));
    }

    @Test
    void emptyConfigReturnsNoPorts() {
        PortConfig empty = PortConfig.of(java.util.List.of());
        assertTrue(empty.portsFor(PortChannel.GAS, PortDirection.OUTPUT).isEmpty());
        assertTrue(empty.ports().isEmpty());
    }

    @Test
    void decodesAbsentPortsKeyAsEmpty() throws Exception {
        PortConfig cfg = decode(PortConfig.CODEC, "{}");
        assertTrue(cfg.ports().isEmpty());
    }

    @Test
    void portCodecRoundTrips() throws Exception {
        Port p = decode(Port.CODEC, "{\"Face\":2,\"Channel\":\"fluid\",\"Direction\":\"output\"}");
        assertEquals(2, p.faceIndex());
        assertEquals(PortChannel.FLUID, p.channel());
        assertEquals(PortDirection.OUTPUT, p.direction());
    }

    @Test
    void portConfigCodecRoundTripsViaEncode() {
        PortConfig cfg = PortConfig.of(List.of(
            Port.of(0, PortChannel.GAS, PortDirection.INPUT),
            Port.of(1, PortChannel.FLUID, PortDirection.OUTPUT)));
        BsonValue encoded = PortConfig.CODEC.encode(cfg, EmptyExtraInfo.EMPTY);
        PortConfig decoded = PortConfig.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertEquals(2, decoded.ports().size());
        Port a = decoded.ports().get(0);
        assertEquals(0, a.faceIndex());
        assertEquals(PortChannel.GAS, a.channel());
        assertEquals(PortDirection.INPUT, a.direction());
        Port b = decoded.ports().get(1);
        assertEquals(1, b.faceIndex());
        assertEquals(PortChannel.FLUID, b.channel());
        assertEquals(PortDirection.OUTPUT, b.direction());
    }
}
