package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.List;
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
}
