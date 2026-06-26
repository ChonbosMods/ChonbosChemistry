package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the Sculptor's recipe-script GATE ({@link SculptorTickSystem#scriptGateAllowSet}). The Sculptor
 * is INERT (crafts nothing) until a recipe-script item occupies its card slot:
 * <ul>
 *   <li>no script (null / empty card) -&gt; an EMPTY allow-set, which {@code CraftSelection.allowed} treats as
 *       DENY-ALL (no recipe id passes), so the machine never crafts;</li>
 *   <li>a script present but unprogrammed (no {@code CARD_ALLOW} metadata) -&gt; {@code null}, which
 *       {@code CraftSelection.allowed} treats as ALLOW-ALL: selection is DEFERRED to the (future) script.</li>
 * </ul>
 */
class SculptorTickSystemTest {

    /**
     * Builds an {@link ItemStack} via its CODEC (decode-from-BSON), the AssetStore-free path the other CC
     * codec tests use. The {@code new ItemStack(String,int)} constructor resolves the item against the global
     * AssetStore (unloaded in the unit-test JVM) to seed durability; the codec path only sets fields.
     */
    private static ItemStack stack(String id, int quantity) {
        BsonDocument doc = new BsonDocument()
            .append("Id", new BsonString(id))
            .append("Quantity", new BsonInt32(quantity));
        return ItemStack.CODEC.decode(doc, EmptyExtraInfo.EMPTY);
    }

    @Test
    void nullCardDeniesAll() {
        Set<String> allow = SculptorTickSystem.scriptGateAllowSet(null);
        assertNotNull(allow);
        assertTrue(allow.isEmpty(), "no script item -> empty allow-set -> deny-all (machine inert)");
        // Sanity: the deny-all semantics actually deny a sample recipe id.
        assertTrue(!CraftSelection.allowed("Block_Stone_Bricks", allow));
    }

    @Test
    void emptyCardDeniesAll() {
        Set<String> allow = SculptorTickSystem.scriptGateAllowSet(ItemStack.EMPTY);
        assertNotNull(allow);
        assertTrue(allow.isEmpty(), "empty card stack -> empty allow-set -> deny-all (machine inert)");
    }

    @Test
    void presentUnprogrammedScriptAllowsAll() {
        // A recipe-script item with no CARD_ALLOW metadata: present (so the gate opens) but unprogrammed, so
        // selection is DEFERRED -> null allow-set -> CraftSelection.allowed treats it as allow-all.
        ItemStack card = stack("CC_RecipeScript", 1);
        Set<String> allow = SculptorTickSystem.scriptGateAllowSet(card);
        assertNull(allow, "present unprogrammed script -> null -> allow-all (selection deferred to the script)");
        // Sanity: allow-all actually permits a sample recipe id.
        assertTrue(CraftSelection.allowed("Block_Stone_Bricks", allow));
    }

    @Test
    void defaultDurationConstantIsTuneable() {
        // The fallback craft duration is public so the GUI and tick agree; pin its value so a tune is deliberate.
        assertEquals(4.0f, SculptorTickSystem.SCULPTOR_DEFAULT_DURATION, 1e-6);
    }
}
