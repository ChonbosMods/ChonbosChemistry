package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.Set;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

class RecipeScriptTest {

    /**
     * Builds an {@link ItemStack} via its CODEC (decode-from-BSON) instead of the
     * {@code new ItemStack(String,int)} constructor: the constructor resolves the item against the global
     * AssetStore for durability, which is unloaded in the unit-test JVM (NPE). The codec path only sets
     * fields, so it is the test-safe way to make a stack (mirrors the sibling {@code *StateCodecTest}s).
     * Metadata round-trips operate purely on the item's bson metadata document, independent of the asset
     * store, so a codec-built stack round-trips a {@link RecipeScript} faithfully.
     */
    private static ItemStack stack(String id, int quantity, BsonDocument metadata) {
        BsonDocument doc = new BsonDocument()
            .append("Id", new BsonString(id))
            .append("Quantity", new BsonInt32(quantity))
            // The CODEC's metadata key is "Metadata"; seed it so getFromMetadataOrNull (which reads the
            // stack's metadata document directly, asset-store-free) sees the stamped script.
            .append("Metadata", metadata);
        return ItemStack.CODEC.decode(doc, EmptyExtraInfo.EMPTY);
    }

    /**
     * A blank (empty-metadata) card stack. Built via the CODEC (not {@code new ItemStack}) AND read back via
     * {@code getFromMetadataOrNull} only: {@code withMetadata(KeyedCodec, T)} resolves the Item against the
     * global AssetStore (unloaded in tests, NPE), so this test stamps the metadata document directly via
     * {@link KeyedCodec#put} and never calls {@code withMetadata}.
     */
    private static ItemStack blankCard() {
        return stack("CC_RecipeCard", 1, new BsonDocument());
    }

    /**
     * Stamps {@code script} onto a fresh card by writing the package-visible {@code CC_RECIPE_SCRIPT} key
     * straight into the card's metadata document (the asset-store-free seam; see {@link #blankCard()}).
     */
    private static ItemStack programmedCard(RecipeScript script) {
        BsonDocument meta = new BsonDocument();
        AutoCraftEngine.CC_RECIPE_SCRIPT.put(meta, script, EmptyExtraInfo.EMPTY);
        return stack("CC_RecipeCard", 1, meta);
    }

    // --- metadata round-trip ---

    @Test
    void metadataRoundTripsOrderedFlagAndEntriesInOrder() {
        RecipeScript script = new RecipeScript(true, List.of(
            new RecipeScript.Entry("a", 0),
            new RecipeScript.Entry("b", 5),
            new RecipeScript.Entry("c", -1)));

        ItemStack card = programmedCard(script);
        RecipeScript read = card.getFromMetadataOrNull(AutoCraftEngine.CC_RECIPE_SCRIPT);

        assertTrue(read.ordered());
        assertEquals(3, read.entries().size());
        assertEquals("a", read.entries().get(0).recipeId());
        assertEquals(0, read.entries().get(0).count());
        assertEquals("b", read.entries().get(1).recipeId());
        assertEquals(5, read.entries().get(1).count());
        assertEquals("c", read.entries().get(2).recipeId());
        assertEquals(-1, read.entries().get(2).count());
    }

    @Test
    void metadataRoundTripsUnorderedFlag() {
        RecipeScript script = new RecipeScript(false, List.of(new RecipeScript.Entry("x", 1)));
        RecipeScript read = programmedCard(script).getFromMetadataOrNull(AutoCraftEngine.CC_RECIPE_SCRIPT);
        assertFalse(read.ordered());
    }

    // --- cardScript ---

    @Test
    void cardScriptNullCardIsNull() {
        assertNull(AutoCraftEngine.cardScript(null));
    }

    @Test
    void cardScriptBlankCardIsNull() {
        assertNull(AutoCraftEngine.cardScript(blankCard()));
    }

    @Test
    void cardScriptEmptyScriptCardIsNull() {
        ItemStack card = programmedCard(new RecipeScript(false, List.of()));
        assertNull(AutoCraftEngine.cardScript(card));
    }

    @Test
    void cardScriptProgrammedCardReadsBack() {
        RecipeScript script = new RecipeScript(true, List.of(new RecipeScript.Entry("a", 2)));
        RecipeScript read = AutoCraftEngine.cardScript(programmedCard(script));
        assertEquals(Set.of("a"), read.recipeIds());
        assertTrue(read.ordered());
    }

    // --- cardAllowSet (null = allow-all contract) ---

    @Test
    void cardAllowSetBlankCardIsNull() {
        assertNull(AutoCraftEngine.cardAllowSet(blankCard()));
        assertNull(AutoCraftEngine.cardAllowSet(null));
    }

    @Test
    void cardAllowSetEmptyScriptIsNull() {
        ItemStack card = programmedCard(new RecipeScript(false, List.of()));
        assertNull(AutoCraftEngine.cardAllowSet(card));
    }

    @Test
    void cardAllowSetProgrammedCardYieldsDistinctIds() {
        RecipeScript script = new RecipeScript(false, List.of(
            new RecipeScript.Entry("a", 0),
            new RecipeScript.Entry("b", 5),
            new RecipeScript.Entry("c", 0)));
        Set<String> allow = AutoCraftEngine.cardAllowSet(programmedCard(script));
        assertEquals(Set.of("a", "b", "c"), allow);
    }

    // --- helpers ---

    @Test
    void isInfiniteTrueForZeroAndNegative() {
        assertTrue(RecipeScript.isInfinite(new RecipeScript.Entry("a", 0)));
        assertTrue(RecipeScript.isInfinite(new RecipeScript.Entry("a", -1)));
        assertFalse(RecipeScript.isInfinite(new RecipeScript.Entry("a", 5)));
    }

    @Test
    void recipeIdsAreDistinctAndOrderPreserving() {
        RecipeScript script = new RecipeScript(false, List.of(
            new RecipeScript.Entry("c", 1),
            new RecipeScript.Entry("a", 1),
            new RecipeScript.Entry("c", 2),
            new RecipeScript.Entry("b", 1)));
        // distinct, first-seen order: c, a, b (the duplicate "c" collapses, order kept).
        assertEquals(List.of("c", "a", "b"), List.copyOf(script.recipeIds()));
    }

    @Test
    void isEmptyReflectsEntries() {
        assertTrue(new RecipeScript(false, List.of()).isEmpty());
        assertTrue(new RecipeScript(false, null).isEmpty());
        assertFalse(new RecipeScript(false, List.of(new RecipeScript.Entry("a", 1))).isEmpty());
    }

    @Test
    void entriesAreDefensivelyCopied() {
        java.util.List<RecipeScript.Entry> mutable = new java.util.ArrayList<>();
        mutable.add(new RecipeScript.Entry("a", 1));
        RecipeScript script = new RecipeScript(false, mutable);
        mutable.add(new RecipeScript.Entry("b", 1));
        assertEquals(1, script.entries().size());
    }
}
