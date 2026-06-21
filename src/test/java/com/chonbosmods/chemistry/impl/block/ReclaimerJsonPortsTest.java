package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/**
 * Guards the Reclaimer's JSON-seeded ports (the runtime source, cloned onto every placed block via the
 * {@code BlockEntity} template) against {@link ReclaimerPorts#defaults()} (the tested spec). Same guard
 * as {@code SmelterJsonPortsTest}: decode the JSON's port section through the real {@link PortConfig#CODEC}
 * and assert they describe the same ports — catching a JSON typo or drift before it reaches the game.
 */
class ReclaimerJsonPortsTest {

    private static String key(Port p) {
        return p.cellX() + "," + p.cellY() + "," + p.cellZ() + ":" + p.faceIndex()
            + ":" + p.channel() + ":" + p.direction();
    }

    private static List<String> sortedKeys(PortConfig cfg) {
        List<String> keys = new ArrayList<>();
        for (Port p : cfg.ports()) {
            keys.add(key(p));
        }
        keys.sort(null);
        return keys;
    }

    @Test
    void jsonSeededPortsMatchReclaimerDefaults() throws Exception {
        String json;
        try (InputStream in = getClass().getResourceAsStream(
                "/Server/Item/Items/ChonbosMods/CC_Reclaimer.json")) {
            assertNotNull(in, "CC_Reclaimer.json must be on the resource path");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        BsonDocument portsNode = BsonDocument.parse(json)
            .getDocument("BlockType")
            .getDocument("BlockEntity")
            .getDocument("Components")
            .getDocument("MachineBlockState")
            .getDocument("Ports");

        PortConfig fromJson = PortConfig.CODEC.decode(portsNode, EmptyExtraInfo.EMPTY);

        assertEquals(sortedKeys(ReclaimerPorts.defaults()), sortedKeys(fromJson),
            "JSON-seeded reclaimer ports must match ReclaimerPorts.defaults()");
    }
}
