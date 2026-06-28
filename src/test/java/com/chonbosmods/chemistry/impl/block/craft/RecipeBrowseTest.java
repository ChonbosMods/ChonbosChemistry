package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.block.craft.RecipeBrowse.Result;
import com.chonbosmods.chemistry.impl.block.craft.RecipeBrowse.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RecipeBrowse}: the recipe-row label fallback and the filter-then-CAP function
 * that protects the un-virtualized list from the Sculptor's ~911 recipes. No world / ECS / asset registry.
 */
class RecipeBrowseTest {

    private static Row row(String id, String label) {
        return new Row(id, label);
    }

    // --- rowLabel ---

    @Test
    void rowLabel_usesPrimaryOutputItemId() {
        assertEquals("Plant_Fruit_Apple",
            RecipeBrowse.rowLabel("recipe_apple", List.of("Plant_Fruit_Apple", "Other")));
    }

    @Test
    void rowLabel_fallsBackToRecipeIdWhenNoOutputs() {
        assertEquals("recipe_x", RecipeBrowse.rowLabel("recipe_x", List.of()));
        assertEquals("recipe_y", RecipeBrowse.rowLabel("recipe_y", null));
    }

    @Test
    void rowLabel_skipsNullOrBlankOutputsBeforeFalling() {
        assertEquals("Good", RecipeBrowse.rowLabel("recipe_z", Arrays.asList(null, "  ", "Good")));
        assertEquals("recipe_z", RecipeBrowse.rowLabel("recipe_z", Arrays.asList(null, "   ")));
    }

    // --- filterAndCap ---

    @Test
    void filterAndCap_blankQueryMatchesAllButStillCaps() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(row("id" + i, "label" + i));
        }
        Result r = RecipeBrowse.filterAndCap(rows, "", 40);
        assertEquals(40, r.rows().size(), "blank query must still be capped");
        assertEquals(100, r.totalMatched(), "totalMatched is the pre-cap count");
        assertTrue(r.isCapped());
    }

    @Test
    void filterAndCap_nullQueryMatchesAll() {
        List<Row> rows = List.of(row("a", "Apple"), row("b", "Banana"));
        Result r = RecipeBrowse.filterAndCap(rows, null, 40);
        assertEquals(2, r.rows().size());
        assertFalse(r.isCapped());
    }

    @Test
    void filterAndCap_matchesLabelCaseInsensitively() {
        List<Row> rows = List.of(row("r1", "Iron Sword"), row("r2", "Wooden Plank"));
        Result r = RecipeBrowse.filterAndCap(rows, "iron", 40);
        assertEquals(1, r.rows().size());
        assertEquals("r1", r.rows().get(0).recipeId());
    }

    @Test
    void filterAndCap_matchesRecipeIdToo() {
        List<Row> rows = List.of(row("forge_blade", "Mystery"), row("other", "Plank"));
        Result r = RecipeBrowse.filterAndCap(rows, "blade", 40);
        assertEquals(1, r.rows().size());
        assertEquals("forge_blade", r.rows().get(0).recipeId());
    }

    @Test
    void filterAndCap_capCountsOnlyMatchesAndPreservesOrder() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            // half match "keep", half do not
            rows.add(row("id" + i, (i % 2 == 0 ? "keep" : "drop") + i));
        }
        Result r = RecipeBrowse.filterAndCap(rows, "keep", 10);
        assertEquals(10, r.rows().size());
        assertEquals(25, r.totalMatched(), "25 of 50 rows contain 'keep'");
        assertTrue(r.isCapped());
        // order preserved: first kept row is id0
        assertEquals("id0", r.rows().get(0).recipeId());
    }

    @Test
    void filterAndCap_notCappedWhenMatchesUnderCap() {
        List<Row> rows = List.of(row("a", "Apple"), row("b", "Apricot"), row("c", "Banana"));
        Result r = RecipeBrowse.filterAndCap(rows, "ap", 40);
        assertEquals(2, r.rows().size());
        assertEquals(2, r.totalMatched());
        assertFalse(r.isCapped());
    }

    @Test
    void filterAndCap_zeroCapClampsToEmptyButCountsMatches() {
        List<Row> rows = List.of(row("a", "Apple"), row("b", "Apricot"));
        Result r = RecipeBrowse.filterAndCap(rows, "ap", 0);
        assertTrue(r.rows().isEmpty());
        assertEquals(2, r.totalMatched());
        assertTrue(r.isCapped());
    }

    @Test
    void filterAndCap_noMatches() {
        List<Row> rows = List.of(row("a", "Apple"));
        Result r = RecipeBrowse.filterAndCap(rows, "zzz", 40);
        assertTrue(r.rows().isEmpty());
        assertEquals(0, r.totalMatched());
        assertFalse(r.isCapped());
    }

    @Test
    void rowCap_isReasonablePositive() {
        assertTrue(RecipeBrowse.ROW_CAP > 0 && RecipeBrowse.ROW_CAP <= 100);
    }

    // --- primaryOutputId ---

    @Test
    void primaryOutputId_firstNonBlank() {
        assertEquals("Apple", RecipeBrowse.primaryOutputId(Arrays.asList(null, "  ", "Apple", "Other")));
    }

    @Test
    void primaryOutputId_nullWhenNoneResolvable() {
        org.junit.jupiter.api.Assertions.assertNull(RecipeBrowse.primaryOutputId(null));
        org.junit.jupiter.api.Assertions.assertNull(RecipeBrowse.primaryOutputId(List.of()));
        org.junit.jupiter.api.Assertions.assertNull(RecipeBrowse.primaryOutputId(Arrays.asList(null, "   ")));
    }

    // --- humanize ---

    @Test
    void humanize_stripsPrefixAndTitleCases() {
        assertEquals("Crystal Blue", RecipeBrowse.humanize("Ingredient_Crystal_Blue"));
        assertEquals("Apple", RecipeBrowse.humanize("Plant_Apple"));
        assertEquals("Iron Ingot", RecipeBrowse.humanize("Item_Iron_Ingot"));
    }

    @Test
    void humanize_dropsNamespacePrefix() {
        assertEquals("Oak Log", RecipeBrowse.humanize("hytale:Block_Oak_Log"));
    }

    @Test
    void humanize_blankYieldsEmpty() {
        assertEquals("", RecipeBrowse.humanize(null));
        assertEquals("", RecipeBrowse.humanize("   "));
    }

    @Test
    void humanize_plainIdTitleCased() {
        assertEquals("Mug", RecipeBrowse.humanize("Mug"));
        assertEquals("Some Thing", RecipeBrowse.humanize("some_thing"));
    }

    // --- recipeAtSlot (slot index -> recipe mapping) ---

    @Test
    void recipeAtSlot_mapsIndexToRecipeId() {
        List<Row> rows = List.of(row("r0", "A"), row("r1", "B"), row("r2", "C"));
        assertEquals("r0", RecipeBrowse.recipeAtSlot(rows, 0));
        assertEquals("r2", RecipeBrowse.recipeAtSlot(rows, 2));
    }

    @Test
    void recipeAtSlot_outOfRangeIsNull() {
        List<Row> rows = List.of(row("r0", "A"));
        org.junit.jupiter.api.Assertions.assertNull(RecipeBrowse.recipeAtSlot(rows, -1));
        org.junit.jupiter.api.Assertions.assertNull(RecipeBrowse.recipeAtSlot(rows, 1));
        org.junit.jupiter.api.Assertions.assertNull(RecipeBrowse.recipeAtSlot(null, 0));
        org.junit.jupiter.api.Assertions.assertNull(RecipeBrowse.recipeAtSlot(List.of(), 0));
    }
}
