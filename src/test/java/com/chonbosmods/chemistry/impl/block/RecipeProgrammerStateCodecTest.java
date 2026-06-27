package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class RecipeProgrammerStateCodecTest {

    /**
     * Builds an {@link ItemStack} via its CODEC (decode-from-BSON) instead of the
     * {@code new ItemStack(String,int)} constructor, which resolves the item against the global AssetStore
     * (unloaded in the unit-test JVM -> NPE). Mirrors {@code CookerStateCodecTest#stack}.
     */
    private static ItemStack stack(String id, int quantity) {
        BsonDocument doc = new BsonDocument()
            .append("Id", new BsonString(id))
            .append("Quantity", new BsonInt32(quantity));
        return ItemStack.CODEC.decode(doc, EmptyExtraInfo.EMPTY);
    }

    @Test
    void defaultsAreSane() {
        RecipeProgrammerState s = new RecipeProgrammerState();
        assertNull(s.card());
    }

    @Test
    void codecRoundTripsCard() {
        RecipeProgrammerState state = new RecipeProgrammerState();
        state.setCard(stack("CC_RecipeScript", 1));

        BsonValue encoded = RecipeProgrammerState.CODEC.encode(state, EmptyExtraInfo.EMPTY);
        RecipeProgrammerState decoded = RecipeProgrammerState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertNotNull(decoded.card());
        assertEquals("CC_RecipeScript", decoded.card().getItemId());
        assertEquals(1, decoded.card().getQuantity());
    }

    @Test
    void cardOptionalOmittedWhenNull() {
        RecipeProgrammerState state = new RecipeProgrammerState();
        assertNull(state.card());

        BsonDocument encoded = RecipeProgrammerState.CODEC.encode(state, EmptyExtraInfo.EMPTY).asDocument();
        assertFalse(encoded.containsKey("Card"));

        RecipeProgrammerState decoded = RecipeProgrammerState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertNull(decoded.card());
    }

    @Test
    void cloneIsIndependentDeepCopy() {
        RecipeProgrammerState original = new RecipeProgrammerState();
        original.setCard(stack("CC_RecipeScript", 1));
        RecipeProgrammerState copy = (RecipeProgrammerState) original.clone();

        assertNotNull(copy.card());
        assertEquals("CC_RecipeScript", copy.card().getItemId());

        original.setCard(null);
        assertNotNull(copy.card()); // copy unaffected by mutating the original
    }
}
