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
 * Guards the Forge's JSON-seeded ports (the runtime source, cloned onto every placed block via the
 * {@code BlockEntity} template) against {@link ForgePorts#defaults()} (the tested spec). Same guard as
 * {@code ReclaimerJsonPortsTest}, but the Forge's ports live under the {@code ForgeCraftState} component
 * (NOT {@code MachineBlockState}): the Forge is an autonomous crafter with its own engine, so its block
 * JSON has no {@code Bench} and seeds ports under {@code ForgeCraftState.Ports}.
 */
class ForgeJsonPortsTest {

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
    void jsonSeededPortsMatchForgeDefaults() throws Exception {
        String json;
        try (InputStream in = getClass().getResourceAsStream(
                "/Server/Item/Items/ChonbosMods/CC_Forge.json")) {
            assertNotNull(in, "CC_Forge.json must be on the resource path");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        BsonDocument portsNode = BsonDocument.parse(json)
            .getDocument("BlockType")
            .getDocument("BlockEntity")
            .getDocument("Components")
            .getDocument("ForgeCraftState")
            .getDocument("Ports");

        PortConfig fromJson = PortConfig.CODEC.decode(portsNode, EmptyExtraInfo.EMPTY);

        assertEquals(sortedKeys(ForgePorts.defaults()), sortedKeys(fromJson),
            "JSON-seeded forge ports must match ForgePorts.defaults()");
    }
}
