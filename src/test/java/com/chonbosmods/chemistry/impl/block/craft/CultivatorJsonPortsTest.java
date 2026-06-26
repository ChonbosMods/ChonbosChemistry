package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/**
 * Guards the Cultivator's JSON-seeded ports (the runtime source, cloned onto every placed block via the
 * {@code BlockEntity} template) against {@link CultivatorPorts#defaults()} (the tested spec). Same guard as
 * {@code ForgeJsonPortsTest}: the Cultivator is an autonomous crafter with no vanilla bench, so its block JSON
 * seeds ports under the {@code CultivatorState} component (NOT {@code MachineBlockState}).
 */
class CultivatorJsonPortsTest {

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
    void jsonSeededPortsMatchCultivatorDefaults() throws Exception {
        String json;
        try (InputStream in = getClass().getResourceAsStream(
                "/Server/Item/Items/ChonbosMods/CC_Cultivator.json")) {
            assertNotNull(in, "CC_Cultivator.json must be on the resource path");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        BsonDocument portsNode = BsonDocument.parse(json)
            .getDocument("BlockType")
            .getDocument("BlockEntity")
            .getDocument("Components")
            .getDocument("CultivatorState")
            .getDocument("Ports");

        PortConfig fromJson = PortConfig.CODEC.decode(portsNode, EmptyExtraInfo.EMPTY);

        assertEquals(sortedKeys(CultivatorPorts.defaults()), sortedKeys(fromJson),
            "JSON-seeded cultivator ports must match CultivatorPorts.defaults()");
    }
}
