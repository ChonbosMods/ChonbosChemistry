package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

/**
 * Guards every bench machine's connected-block {@code CustomTemplate} FaceTags against its JSON-seeded
 * {@link PortConfig}: the engine welds a pipe arm toward each face the template tags, so a tag must sit on
 * the SAME physical face as the logical port that channel uses, or pipes connect where there is no port.
 *
 * <p>The two namings disagree on the Z axis: a port's {@code faceIndex} follows {@code PortProjection.OFFSETS}
 * ({@code 4 = +Z}, {@code 5 = -Z}), but the connected-block template's North/South are FLIPPED on Z
 * ({@code +Z = North}, {@code -Z = South}; X and Y are direct). This is exactly the trap that mis-tagged the
 * smelter's rear power face (fixed in {@code 84e9204}); this test pins the convention so machines #3–#8 cannot
 * reintroduce it. See {@code docs/plans/2026-06-20-cc-smelter-footprint-facetag-research.md} and
 * {@code filler-cells-inherit-anchor-facetags}.
 */
class MachineTemplateFaceTagTest {

    /** faceIndex -> the connected-block template's direction name (Z inverted vs OFFSETS). */
    private static String templateDirection(int faceIndex) {
        return switch (faceIndex) {
            case 0 -> "East";   // +X
            case 1 -> "West";   // -X
            case 2 -> "Up";     // +Y
            case 3 -> "Down";   // -Y
            case 4 -> "North";  // +Z  (template North == +Z)
            case 5 -> "South";  // -Z  (template South == -Z)
            default -> throw new IllegalArgumentException("unmapped faceIndex " + faceIndex);
        };
    }

    /** The FaceTag the engine matches a pipe of this channel against. */
    private static String faceTag(PortChannel channel) {
        return switch (channel) {
            case ITEM -> "CC_ItemFace";
            case POWER -> "CC_PowerFace";
            default -> throw new IllegalArgumentException("no FaceTag for channel " + channel);
        };
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = MachineTemplateFaceTagTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " must be on the resource path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> tagsForDirection(BsonDocument template, String direction) {
        BsonDocument faceTags = template.getDocument("Shapes").getDocument("machine").getDocument("FaceTags");
        if (!faceTags.containsKey(direction)) {
            return List.of();
        }
        BsonArray arr = faceTags.getArray(direction);
        List<String> tags = new ArrayList<>(arr.size());
        for (BsonValue v : arr) {
            tags.add(v.asString().getValue());
        }
        return tags;
    }

    private void assertTemplateTagsMatchPorts(String machine) throws Exception {
        // Bench machines (Smelter/Reclaimer) seed ports under "MachineBlockState"; the Forge is an
        // autonomous crafter and seeds them under "ForgeCraftState" instead.
        assertTemplateTagsMatchPorts(machine, "MachineBlockState");
    }

    private void assertTemplateTagsMatchPorts(String machine, String componentKey) throws Exception {
        BsonDocument ports = BsonDocument.parse(resource("/Server/Item/Items/ChonbosMods/" + machine + ".json"))
            .getDocument("BlockType").getDocument("BlockEntity").getDocument("Components")
            .getDocument(componentKey).getDocument("Ports");
        PortConfig config = PortConfig.CODEC.decode(ports, EmptyExtraInfo.EMPTY);

        BsonDocument template = BsonDocument.parse(
            resource("/Server/Item/CustomConnectedBlockTemplates/" + machine + "Template.json"));

        for (Port p : config.ports()) {
            String direction = templateDirection(p.faceIndex());
            String expectedTag = faceTag(p.channel());
            List<String> tags = tagsForDirection(template, direction);
            assertTrue(tags.contains(expectedTag),
                machine + ": port " + p.channel() + " on faceIndex " + p.faceIndex()
                    + " needs template FaceTag '" + expectedTag + "' on '" + direction
                    + "' (Z is inverted vs OFFSETS), but that face has " + tags);
        }
    }

    @Test
    void smelterTemplateTagsMatchPorts() throws Exception {
        assertTemplateTagsMatchPorts("CC_Smelter");
    }

    @Test
    void reclaimerTemplateTagsMatchPorts() throws Exception {
        assertTemplateTagsMatchPorts("CC_Reclaimer");
    }

    @Test
    void forgeTemplateTagsMatchPorts() throws Exception {
        // The Forge seeds its ports under the ForgeCraftState component (no vanilla bench), so the
        // helper must read that path instead of MachineBlockState.
        assertTemplateTagsMatchPorts("CC_Forge", "ForgeCraftState");
    }

    @Test
    void cookerTemplateTagsMatchPorts() throws Exception {
        // The Cooker is an autonomous crafter (no vanilla bench) and seeds its ports under the
        // CookerState component, so the helper must read that path instead of MachineBlockState.
        assertTemplateTagsMatchPorts("CC_Cooker", "CookerState");
    }

    @Test
    void outfitterTemplateTagsMatchPorts() throws Exception {
        // The Outfitter is an autonomous crafter (no vanilla bench) and seeds its ports under the
        // OutfitterState component, so the helper must read that path instead of MachineBlockState.
        assertTemplateTagsMatchPorts("CC_Outfitter", "OutfitterState");
    }

    @Test
    void alembicTemplateTagsMatchPorts() throws Exception {
        // The Alembic is an autonomous crafter (no vanilla bench) and seeds its ports under the
        // AlembicState component, so the helper must read that path instead of MachineBlockState.
        assertTemplateTagsMatchPorts("CC_Alembic", "AlembicState");
    }

    @Test
    void assemblerTemplateTagsMatchPorts() throws Exception {
        // The Assembler is an autonomous crafter (no vanilla bench) and seeds its ports under the
        // AssemblerState component, so the helper must read that path instead of MachineBlockState.
        assertTemplateTagsMatchPorts("CC_Assembler", "AssemblerState");
    }
}
