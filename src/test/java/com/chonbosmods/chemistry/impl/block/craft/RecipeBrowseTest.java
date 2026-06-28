package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.block.craft.RecipeBrowse.Row;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RecipeBrowse}: the recipe-row label fallback and the (uncapped) filter function
 * that backs the single-{@code ItemGrid} recipe browser. No world / ECS / asset registry.
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

    // --- filter (uncapped : returns ALL matching rows, input order preserved) ---

    @Test
    void filter_blankQueryReturnsAllUncapped() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            rows.add(row("id" + i, "label" + i));
        }
        List<Row> r = RecipeBrowse.filter(rows, "");
        assertEquals(100, r.size(), "blank query returns every row (no cap)");
        // order preserved
        assertEquals("id0", r.get(0).recipeId());
        assertEquals("id99", r.get(99).recipeId());
    }

    @Test
    void filter_nullQueryMatchesAll() {
        List<Row> rows = List.of(row("a", "Apple"), row("b", "Banana"));
        List<Row> r = RecipeBrowse.filter(rows, null);
        assertEquals(2, r.size());
    }

    @Test
    void filter_matchesLabelCaseInsensitively() {
        List<Row> rows = List.of(row("r1", "Iron Sword"), row("r2", "Wooden Plank"));
        List<Row> r = RecipeBrowse.filter(rows, "iron");
        assertEquals(1, r.size());
        assertEquals("r1", r.get(0).recipeId());
    }

    @Test
    void filter_matchesRecipeIdToo() {
        List<Row> rows = List.of(row("forge_blade", "Mystery"), row("other", "Plank"));
        List<Row> r = RecipeBrowse.filter(rows, "blade");
        assertEquals(1, r.size());
        assertEquals("forge_blade", r.get(0).recipeId());
    }

    @Test
    void filter_returnsAllMatchesAndPreservesOrder() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            // half match "keep", half do not
            rows.add(row("id" + i, (i % 2 == 0 ? "keep" : "drop") + i));
        }
        List<Row> r = RecipeBrowse.filter(rows, "keep");
        assertEquals(25, r.size(), "25 of 50 rows contain 'keep' : all returned, no cap");
        // order preserved: first kept row is id0, next id2, ..., last (25th) is id48
        assertEquals("id0", r.get(0).recipeId());
        assertEquals("id2", r.get(1).recipeId());
        assertEquals("id48", r.get(24).recipeId());
    }

    @Test
    void filter_emptyForNoMatches() {
        List<Row> rows = List.of(row("a", "Apple"));
        assertTrue(RecipeBrowse.filter(rows, "zzz").isEmpty());
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
