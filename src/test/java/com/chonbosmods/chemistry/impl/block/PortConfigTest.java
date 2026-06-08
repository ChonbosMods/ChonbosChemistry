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
    void portCodecAcceptsBothDirection() throws Exception {
        Port p = decode(Port.CODEC, "{\"Face\":3,\"Channel\":\"power\",\"Direction\":\"both\"}");
        assertEquals(3, p.faceIndex());
        assertEquals(PortChannel.POWER, p.channel());
        assertEquals(PortDirection.BOTH, p.direction());
    }

    @Test
    void portAtReturnsTheFacingChannelPort() {
        PortConfig cfg = PortConfig.of(List.of(
            Port.of(0, PortChannel.POWER, PortDirection.BOTH),
            Port.of(1, PortChannel.POWER, PortDirection.OUTPUT),
            Port.of(1, PortChannel.FLUID, PortDirection.INPUT)));
        // Face 0 / POWER -> the BOTH port.
        Port f0 = cfg.portAt(0, PortChannel.POWER);
        assertEquals(PortDirection.BOTH, f0.direction());
        // Face 1 / FLUID -> the FLUID input (channel-filtered, ignores the POWER port on the same face).
        Port f1 = cfg.portAt(1, PortChannel.FLUID);
        assertEquals(PortChannel.FLUID, f1.channel());
        assertEquals(PortDirection.INPUT, f1.direction());
        // No port on face 2.
        assertEquals(null, cfg.portAt(2, PortChannel.POWER));
        // Face 0 has no FLUID port.
        assertEquals(null, cfg.portAt(0, PortChannel.FLUID));
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

    @Test
    void withFacePortReplacesAnyExistingPortOnThatFaceNeverAppends() {
        PortConfig cfg = PortConfig.of(List.of(
            Port.of(0, PortChannel.FLUID, PortDirection.INPUT),
            Port.of(1, PortChannel.POWER, PortDirection.OUTPUT)));
        // Re-config face 0 to a different channel/direction: must replace, not add a second port.
        PortConfig next = cfg.withFacePort(Port.of(0, PortChannel.GAS, PortDirection.OUTPUT));
        assertEquals(2, next.ports().size());
        assertEquals(1, next.portsFor(PortChannel.GAS, PortDirection.OUTPUT).size());
        Port face0 = next.portAt(0, PortChannel.GAS);
        assertNotNull(face0);
        assertEquals(PortDirection.OUTPUT, face0.direction());
        // Face 0 must carry no other port (the old FLUID/INPUT is gone).
        assertNull(next.portAt(0, PortChannel.FLUID));
        // The untouched face 1 survives unchanged.
        Port face1 = next.portAt(1, PortChannel.POWER);
        assertNotNull(face1);
        assertEquals(PortDirection.OUTPUT, face1.direction());
        // Source config is unchanged (immutable rebuild).
        assertEquals(PortChannel.FLUID, cfg.portAt(0, PortChannel.FLUID).channel());
    }

    @Test
    void withFacePortAddsWhenFaceHadNoPort() {
        PortConfig cfg = PortConfig.of(List.of());
        PortConfig next = cfg.withFacePort(Port.of(3, PortChannel.ITEM, PortDirection.INPUT));
        assertEquals(1, next.ports().size());
        assertNotNull(next.portAt(3, PortChannel.ITEM));
    }

    @Test
    void withFacePortNullIsAnUnchangedCopy() {
        PortConfig cfg = PortConfig.of(List.of(Port.of(2, PortChannel.POWER, PortDirection.INPUT)));
        PortConfig next = cfg.withFacePort(null);
        assertEquals(1, next.ports().size());
        assertNotNull(next.portAt(2, PortChannel.POWER));
    }
}
