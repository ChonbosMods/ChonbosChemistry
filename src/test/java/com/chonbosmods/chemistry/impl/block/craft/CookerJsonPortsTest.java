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
 * Guards the Cooker's JSON-seeded ports (the runtime source, cloned onto every placed block via the
 * {@code BlockEntity} template) against {@link CookerPorts#defaults()} (the tested spec). Same guard as
 * {@code ForgeJsonPortsTest}: the Cooker is an autonomous crafter with no vanilla bench, so its block JSON
 * seeds ports under the {@code CookerState} component (NOT {@code MachineBlockState}).
 */
class CookerJsonPortsTest {

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
    void jsonSeededPortsMatchCookerDefaults() throws Exception {
        String json;
        try (InputStream in = getClass().getResourceAsStream(
                "/Server/Item/Items/ChonbosMods/CC_Cooker.json")) {
            assertNotNull(in, "CC_Cooker.json must be on the resource path");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        BsonDocument portsNode = BsonDocument.parse(json)
            .getDocument("BlockType")
            .getDocument("BlockEntity")
            .getDocument("Components")
            .getDocument("CookerState")
            .getDocument("Ports");

        PortConfig fromJson = PortConfig.CODEC.decode(portsNode, EmptyExtraInfo.EMPTY);

        assertEquals(sortedKeys(CookerPorts.defaults()), sortedKeys(fromJson),
            "JSON-seeded cooker ports must match CookerPorts.defaults()");
    }
}
