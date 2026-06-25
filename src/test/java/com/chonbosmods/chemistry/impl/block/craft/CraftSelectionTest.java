package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftSelectionTest {

    private static final List<String> ORDER = List.of("A", "B", "C", "D", "E");

    @Test
    void emptyCraftable_isIdle() {
        assertNull(CraftSelection.selectNext(ORDER, Set.of(), "A"));
    }

    @Test
    void singleCraftable_returnsItRepeatedly() {
        assertEquals("C", CraftSelection.selectNext(ORDER, Set.of("C"), null));
        assertEquals("C", CraftSelection.selectNext(ORDER, Set.of("C"), "C"));
    }

    @Test
    void nullCursor_startsFromTopOfStableOrder() {
        assertEquals("A", CraftSelection.selectNext(ORDER, Set.of("A", "C", "E"), null));
    }

    @Test
    void severalCraftable_rotatesEvenlySkippingNonCraftable() {
        Set<String> craftable = Set.of("A", "C", "E");
        String cur = null;
        cur = CraftSelection.selectNext(ORDER, craftable, cur); assertEquals("A", cur);
        cur = CraftSelection.selectNext(ORDER, craftable, cur); assertEquals("C", cur);
        cur = CraftSelection.selectNext(ORDER, craftable, cur); assertEquals("E", cur);
        cur = CraftSelection.selectNext(ORDER, craftable, cur); assertEquals("A", cur);
    }

    @Test
    void deterministic_sameInputsSameOutput() {
        assertEquals(
            CraftSelection.selectNext(ORDER, Set.of("B", "D"), "A"),
            CraftSelection.selectNext(ORDER, Set.of("B", "D"), "A"));
    }

    @Test
    void cursorIdAbsentFromOrder_treatedAsStart() {
        assertEquals("A", CraftSelection.selectNext(ORDER, Set.of("A", "B"), "ZZZ"));
    }

    @Test
    void allowed_nullAllowSetPermitsAll_elseMembership() {
        org.junit.jupiter.api.Assertions.assertTrue(CraftSelection.allowed("X", null));
        org.junit.jupiter.api.Assertions.assertTrue(CraftSelection.allowed("X", Set.of("X", "Y")));
        org.junit.jupiter.api.Assertions.assertFalse(CraftSelection.allowed("Z", Set.of("X", "Y")));
    }

    // --- topByPriority: the "prefer the most-ingredient recipe" top-tier filter ---

    @Test
    void topByPriority_emptyIn_emptyOut() {
        assertEquals(Set.of(), CraftSelection.topByPriority(Set.of(), Map.of("A", 2)));
    }

    @Test
    void topByPriority_singleCraftable_returnsThatOne() {
        assertEquals(Set.of("A"), CraftSelection.topByPriority(Set.of("A"), Map.of("A", 1)));
    }

    @Test
    void topByPriority_allSamePriority_returnsWholeSet() {
        // All same priority -> nothing dropped; selectNext then rotates over all (unchanged behavior).
        Set<String> craftable = Set.of("A", "B", "C");
        assertEquals(craftable, CraftSelection.topByPriority(craftable, Map.of("A", 1, "B", 1, "C", 1)));
    }

    @Test
    void topByPriority_mixed_keepsMaxTierOnly() {
        // kebab=2, bread=2, meat=1 -> {kebab, bread}; meat dropped.
        Set<String> craftable = Set.of("kebab", "bread", "meat");
        Map<String, Integer> priority = Map.of("kebab", 2, "bread", 2, "meat", 1);
        assertEquals(Set.of("kebab", "bread"), CraftSelection.topByPriority(craftable, priority));
    }

    @Test
    void topByPriority_mixedTier_thenSelectNextRotatesWithinTier() {
        // After filtering to {kebab, bread}, selectNext even-rotates between just those two.
        List<String> order = List.of("bread", "kebab", "meat");
        Set<String> top = CraftSelection.topByPriority(
            Set.of("kebab", "bread", "meat"), Map.of("kebab", 2, "bread", 2, "meat", 1));
        String cur = null;
        cur = CraftSelection.selectNext(order, top, cur); assertEquals("bread", cur);
        cur = CraftSelection.selectNext(order, top, cur); assertEquals("kebab", cur);
        cur = CraftSelection.selectNext(order, top, cur); assertEquals("bread", cur); // meat never picked
    }

    @Test
    void topByPriority_idMissingFromMap_treatedAsZero() {
        // "X" is absent from the map -> priority 0 -> dropped when another id outranks it.
        Set<String> craftable = Set.of("A", "X");
        assertEquals(Set.of("A"), CraftSelection.topByPriority(craftable, Map.of("A", 1)));
    }

    @Test
    void topByPriority_allMissingFromMap_allZero_returnsWholeSet() {
        Set<String> craftable = Set.of("A", "B");
        assertEquals(craftable, CraftSelection.topByPriority(craftable, Map.of()));
    }
}
