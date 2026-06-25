package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class CookerStateCodecTest {

    /**
     * Builds an {@link ItemStack} via its CODEC (decode-from-BSON) instead of the
     * {@code new ItemStack(String,int)} constructor. The constructor resolves the item against the
     * global AssetStore to seed durability, which is unloaded in the unit-test JVM (NPE). The codec
     * path only sets fields, so it is the test-safe way to make a stack. Production code (the tick/GUI)
     * runs with the AssetStore loaded and uses the normal constructor.
     */
    private static ItemStack stack(String id, int quantity) {
        BsonDocument doc = new BsonDocument()
            .append("Id", new BsonString(id))
            .append("Quantity", new BsonInt32(quantity));
        return ItemStack.CODEC.decode(doc, EmptyExtraInfo.EMPTY);
    }

    private static CookerState sample() {
        CookerState s = new CookerState();
        s.setProgress(2.5f);
        s.setLastSelectedId("Weapon_Sword_Iron");
        s.setEnabled(false);
        // filter=false bypasses the asset-store-backed stack-size/filter checks (unloaded in tests);
        // it writes straight into the slot map, which is what the codec persists.
        s.held().setItemStackForSlot((short) 0, stack("Ore_Iron", 3), false);
        s.setCard(stack("CC_RecipeCard", 1));
        s.setCurrentRecipeId("Weapon_Sword_Iron");
        return s;
    }

    @Test
    void defaultsAreSane() {
        CookerState s = new CookerState();
        assertNotNull(s.held());
        assertEquals(27, s.held().getCapacity());
        assertNotNull(s.output());
        assertEquals(4, s.output().getCapacity());
        assertNull(s.card());
        assertEquals(0f, s.progress(), 1e-6);
        assertNull(s.lastSelectedId());
        assertNull(s.currentRecipeId());
        assertTrue(s.isEnabled());
    }

    @Test
    void codecRoundTripsState() {
        CookerState state = sample();
        BsonValue encoded = CookerState.CODEC.encode(state, EmptyExtraInfo.EMPTY);
        CookerState decoded = CookerState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertEquals(2.5f, decoded.progress(), 1e-6);
        assertEquals("Weapon_Sword_Iron", decoded.lastSelectedId());
        assertEquals("Weapon_Sword_Iron", decoded.currentRecipeId());
        assertFalse(decoded.isEnabled());

        ItemStack in = decoded.held().getItemStack((short) 0);
        assertNotNull(in);
        assertEquals("Ore_Iron", in.getItemId());
        assertEquals(3, in.getQuantity());

        assertNotNull(decoded.card());
        assertEquals("CC_RecipeCard", decoded.card().getItemId());
        assertEquals(1, decoded.card().getQuantity());
    }

    @Test
    void cloneIsIndependentDeepCopy() {
        CookerState original = sample();
        CookerState copy = (CookerState) original.clone();

        assertEquals(2.5f, copy.progress(), 1e-6);
        assertEquals("Ore_Iron", copy.held().getItemStack((short) 0).getItemId());

        original.setProgress(9.0f);
        original.held().setItemStackForSlot((short) 1, stack("Ore_Copper", 5), false);

        assertEquals(2.5f, copy.progress(), 1e-6);
    }

    @Test
    void cardOptionalOmittedWhenNull() {
        CookerState state = new CookerState();
        assertNull(state.card());

        BsonDocument encoded = CookerState.CODEC.encode(state, EmptyExtraInfo.EMPTY).asDocument();
        assertFalse(encoded.containsKey("Card"));

        CookerState decoded = CookerState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertNull(decoded.card());
    }

    @Test
    void currentRecipeIdOptionalOmittedWhenNull() {
        CookerState state = new CookerState();
        assertNull(state.currentRecipeId());

        BsonDocument encoded = CookerState.CODEC.encode(state, EmptyExtraInfo.EMPTY).asDocument();
        assertFalse(encoded.containsKey("Current"));

        CookerState decoded = CookerState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertNull(decoded.currentRecipeId());
    }

    @Test
    void absentEnabledKeyDecodesTrue() {
        CookerState state = sample();
        BsonDocument encoded = CookerState.CODEC.encode(state, EmptyExtraInfo.EMPTY).asDocument();
        encoded.remove("Enabled");
        CookerState decoded = CookerState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertTrue(decoded.isEnabled());
    }
}
