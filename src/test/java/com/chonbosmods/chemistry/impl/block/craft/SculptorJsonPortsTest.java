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
 * Guards the Sculptor's JSON-seeded ports (the runtime source, cloned onto every placed block via the
 * {@code BlockEntity} template) against {@link SculptorPorts#defaults()} (the tested spec). Same guard as
 * {@code AlembicJsonPortsTest}: the Sculptor is an autonomous crafter with no vanilla bench, so its block JSON
 * seeds ports under the {@code SculptorState} component (NOT {@code MachineBlockState}). Catches a JSON typo
 * or drift — in particular, an item-in accidentally placed on a {@code (-1,0,0)} cell (the Alembic's 2-wide
 * layout) instead of the Sculptor's anchor cell — before it reaches the game.
 */
class SculptorJsonPortsTest {

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
    void jsonSeededPortsMatchSculptorDefaults() throws Exception {
        String json;
        try (InputStream in = getClass().getResourceAsStream(
                "/Server/Item/Items/ChonbosMods/CC_Sculptor.json")) {
            assertNotNull(in, "CC_Sculptor.json must be on the resource path");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        BsonDocument portsNode = BsonDocument.parse(json)
            .getDocument("BlockType")
            .getDocument("BlockEntity")
            .getDocument("Components")
            .getDocument("SculptorState")
            .getDocument("Ports");

        PortConfig fromJson = PortConfig.CODEC.decode(portsNode, EmptyExtraInfo.EMPTY);

        assertEquals(sortedKeys(SculptorPorts.defaults()), sortedKeys(fromJson),
            "JSON-seeded sculptor ports must match SculptorPorts.defaults()");
    }
}
