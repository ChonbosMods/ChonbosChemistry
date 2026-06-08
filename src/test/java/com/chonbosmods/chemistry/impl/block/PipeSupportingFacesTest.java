package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for "you cannot place a chest (or any support-requiring block) on top of a pipe."
 *
 * <p>Root cause (2026-06-07): the engine only auto-grants {@code ALL_SUPPORTING_FACES} to a block when
 * its {@code DrawType} is {@code Cube}/{@code CubeWithModel}/{@code GizmoCube} AND its material is
 * {@code Solid} ({@code BlockType.processConfig}). Our pipes are {@code DrawType: Model}, so they fall
 * into the {@code else} branch and offer ZERO supporting faces. A block whose own {@code Support}
 * requires a {@code Full} face below it (vanilla chests declare {@code Support.Down = Full}) then fails
 * its placement support check when placed against a pipe. Vanilla chests are {@code DrawType: Model}
 * too and compensate with an explicit {@code Supporting} map; our pipes must do the same.
 *
 * <p>The fix declares {@code Supporting: Full} on all six cube faces of every pipe BlockType (the pipe
 * collides as a full cube, so it should support neighbours on any face, exactly like a solid block).
 * This test asserts that declaration is present so a future asset edit cannot silently regress it.
 * Runs with cwd = project root (mirrors {@code DataDecodeIntegrationTest}).
 */
class PipeSupportingFacesTest {

    /** The four pipe-family BlockType definitions (all {@code DrawType: Model}). */
    private static final String[] PIPE_FILES = {
        "CC_ItemPipe.json", "CC_FluidPipe.json", "CC_GasPipe.json", "CC_PowerCable.json"
    };

    /** The six cube faces a full-cube pipe must offer support on (engine BlockFace JSON keys). */
    private static final String[] FACES = {"Up", "Down", "North", "South", "East", "West"};

    @Test
    void everyPipeAdvertisesFullSupportOnAllSixFaces() throws Exception {
        for (String file : PIPE_FILES) {
            BsonDocument blockType = readBlockType(file);
            assertTrue(blockType.containsKey("Supporting"),
                file + ": BlockType must declare a Supporting map (Model pipes get no auto-support)");
            BsonDocument supporting = blockType.getDocument("Supporting");
            for (String face : FACES) {
                assertTrue(supporting.containsKey(face),
                    file + ": Supporting must cover the " + face + " face");
                BsonArray faceSupports = supporting.getArray(face);
                assertFalse(faceSupports.isEmpty(),
                    file + ": Supporting." + face + " must list at least one face support");
                BsonDocument first = faceSupports.get(0).asDocument();
                assertEquals("Full", first.getString("FaceType").getValue(),
                    file + ": Supporting." + face + " must offer a Full support face (chests require Full)");
            }
        }
    }

    private static BsonDocument readBlockType(String file) throws Exception {
        Path path = Path.of("src/main/resources/Server/Item/Items/ChonbosMods", file);
        String json = Files.readString(path);
        return BsonDocument.parse(json).getDocument("BlockType");
    }
}
