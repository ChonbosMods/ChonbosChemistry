package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

/**
 * Guards the Smelter's processing animation against its model: every bone the {@code .blockyanim} drives must
 * resolve to a {@code shape.type == "box"} node in {@code CC_Smelter.blockymodel}. Two silent failure modes
 * this catches at build time (both hit us live, animating nothing):
 * <ul>
 *   <li>a misspelled / absent bone name — the engine just skips it;</li>
 *   <li>a bone that IS present but is an empty container group ({@code shape.type == "none"}, e.g. {@code Block3}
 *       / {@code Block6}) — a resize ({@code shapeStretch}) has no cube to act on, so it shows nothing. The
 *       cube lives one level down ({@code Block3 -> Block3--C1 + Block5}); animate that instead.</li>
 * </ul>
 * A name is valid if ANY node carrying it is a box (the model has duplicate names, e.g. an empty {@code Block}
 * group beside the real {@code Block} cube — animating by name hits the box one).
 */
class SmelterAnimationNodesTest {

    private static String resource(String path) throws Exception {
        try (InputStream in = SmelterAnimationNodesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " must be on the resource path");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Names of every node whose {@code shape.type == "box"} (i.e. actually carries resizable geometry). */
    private static void collectBoxNodeNames(BsonValue v, Set<String> out) {
        if (v.isDocument()) {
            BsonDocument doc = v.asDocument();
            BsonValue name = doc.get("name");
            BsonValue shape = doc.get("shape");
            if (name != null && name.isString() && shape != null && shape.isDocument()) {
                BsonValue type = shape.asDocument().get("type");
                if (type != null && type.isString() && type.asString().getValue().equals("box")) {
                    out.add(name.asString().getValue());
                }
            }
            for (var e : doc.entrySet()) {
                collectBoxNodeNames(e.getValue(), out);
            }
        } else if (v.isArray()) {
            for (BsonValue c : v.asArray()) {
                collectBoxNodeNames(c, out);
            }
        }
    }

    @Test
    void everyAnimatedBoneIsARealBoxInTheModel() throws Exception {
        Set<String> boxNodes = new HashSet<>();
        collectBoxNodeNames(BsonDocument.parse(
            resource("/Common/Blocks/ChonbosMods/Machines/CC_Smelter.blockymodel")), boxNodes);

        BsonDocument anim = BsonDocument.parse(
            resource("/Common/Blocks/ChonbosMods/Machines/CC_Smelter_Smelting.blockyanim"));
        Set<String> animated = new TreeSet<>(anim.getDocument("nodeAnimations").keySet());

        assertTrue(boxNodes.containsAll(animated),
            "animated bones that are NOT a box in CC_Smelter.blockymodel (absent, or an empty 'none' group "
                + "with no cube to resize): "
                + animated.stream().filter(b -> !boxNodes.contains(b)).toList());
    }
}
