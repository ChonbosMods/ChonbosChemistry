package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the ONE pure helper in {@link NetworkRecipeSource}: {@link NetworkRecipeSource#consumedDiff}, the
 * before/after item-id tally diff that derives the consumed ingredient set WITHOUT reading any opaque
 * {@code MaterialQuantity}. Everything else in {@code NetworkRecipeSource} is engine glue (live world + network
 * + container access) and is verified in-game, not headlessly.
 */
class NetworkRecipeSourceDiffTest {

    @Test
    void singleIngredientConsumed_yieldsItsDrop() {
        Map<String, Integer> before = Map.of("iron", 10, "wood", 4);
        Map<String, Integer> after = Map.of("iron", 7, "wood", 4);
        Map<String, Integer> consumed = NetworkRecipeSource.consumedDiff(before, after);
        assertEquals(Map.of("iron", 3), consumed);
    }

    @Test
    void multipleIngredientsConsumed_yieldEachDrop() {
        Map<String, Integer> before = Map.of("iron", 10, "wood", 4, "gold", 2);
        Map<String, Integer> after = Map.of("iron", 6, "wood", 1, "gold", 2);
        Map<String, Integer> consumed = NetworkRecipeSource.consumedDiff(before, after);
        assertEquals(Map.of("iron", 4, "wood", 3), consumed);
    }

    @Test
    void idFullyConsumed_absentFromAfter_countsAsFullDrop() {
        Map<String, Integer> before = Map.of("dust", 5);
        Map<String, Integer> after = Map.of();
        Map<String, Integer> consumed = NetworkRecipeSource.consumedDiff(before, after);
        assertEquals(Map.of("dust", 5), consumed);
    }

    @Test
    void noChange_yieldsEmptyDiff() {
        Map<String, Integer> same = Map.of("iron", 10, "wood", 4);
        assertTrue(NetworkRecipeSource.consumedDiff(same, same).isEmpty());
    }

    @Test
    void grownOrNewId_isNeverNegative_andOmitted() {
        // An id that grew (or only appears in 'after') must never produce a negative or spurious entry.
        Map<String, Integer> before = Map.of("iron", 5);
        Map<String, Integer> after = Map.of("iron", 8, "slag", 3);
        Map<String, Integer> consumed = NetworkRecipeSource.consumedDiff(before, after);
        assertTrue(consumed.isEmpty());
        assertFalse(consumed.containsKey("slag"));
    }

    @Test
    void beforeEmpty_afterNonEmpty_yieldsEmptyDiff() {
        // Pins the "never emits a key present only in after" contract: an id appearing solely in 'after'
        // (it grew from nothing) must never be reported as consumed.
        Map<String, Integer> before = Map.of();
        Map<String, Integer> after = Map.of("iron", 4, "wood", 2);
        assertTrue(NetworkRecipeSource.consumedDiff(before, after).isEmpty());
    }

    @Test
    void mixedGrowAndShrink_keepsOnlyShrinks() {
        Map<String, Integer> before = Map.of("a", 5, "b", 5, "c", 5);
        Map<String, Integer> after = Map.of("a", 2, "b", 9, "c", 5);
        Map<String, Integer> consumed = NetworkRecipeSource.consumedDiff(before, after);
        assertEquals(Map.of("a", 3), consumed);
    }
}
