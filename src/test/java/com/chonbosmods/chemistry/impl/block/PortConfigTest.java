package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.api.substance.Phase;
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
    void filtersOutputsByPhase() {
        PortConfig cfg = PortConfig.of(List.of(
            Port.of(0, Phase.GAS, PortDirection.INPUT),
            Port.of(1, Phase.GAS, PortDirection.OUTPUT),
            Port.of(2, Phase.LIQUID, PortDirection.OUTPUT),
            Port.of(3, Phase.GAS, PortDirection.CLOSED)));
        List<Port> gasOut = cfg.portsFor(Phase.GAS, PortDirection.OUTPUT);
        assertEquals(1, gasOut.size());
        assertEquals(1, gasOut.get(0).faceIndex());
    }

    @Test
    void portCodecRoundTrips() throws Exception {
        Port p = decode(Port.CODEC, "{\"Face\":2,\"Phase\":\"liquid\",\"Direction\":\"output\"}");
        assertEquals(2, p.faceIndex());
        assertEquals(Phase.LIQUID, p.phase());
        assertEquals(PortDirection.OUTPUT, p.direction());
    }

    @Test
    void portConfigCodecRoundTripsViaEncode() {
        PortConfig cfg = PortConfig.of(List.of(
            Port.of(0, Phase.GAS, PortDirection.INPUT),
            Port.of(1, Phase.LIQUID, PortDirection.OUTPUT)));
        BsonValue encoded = PortConfig.CODEC.encode(cfg, EmptyExtraInfo.EMPTY);
        PortConfig decoded = PortConfig.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertEquals(2, decoded.ports().size());
        Port a = decoded.ports().get(0);
        assertEquals(0, a.faceIndex());
        assertEquals(Phase.GAS, a.phase());
        assertEquals(PortDirection.INPUT, a.direction());
        Port b = decoded.ports().get(1);
        assertEquals(1, b.faceIndex());
        assertEquals(Phase.LIQUID, b.phase());
        assertEquals(PortDirection.OUTPUT, b.direction());
    }
}
