package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.block.craft.RecipeScript.Entry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link ScriptSelection}: the active-set intersection, the two-phase {@code cardPick}
 * (finite entries sequentially in card order, then plain round-robin of the infinite entries), and the
 * blueprint-completion rule. All cases build {@link RecipeScript}/{@link Entry} directly: no
 * ItemStack/metadata/AssetStore needed.
 */
class ScriptSelectionTest {

    private static RecipeScript script(Entry... entries) {
        return new RecipeScript(Arrays.asList(entries));
    }

    private static Entry entry(String id, int count) {
        return new Entry(id, count);
    }

    private static Set<String> set(String... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    // ---- activeSet -------------------------------------------------------------------------------

    @Test
    void activeSet_infiniteEntryAlwaysActiveWhenCraftable() {
        RecipeScript s = script(entry("stairs", 0));
        Set<String> active = ScriptSelection.activeSet(s, Map.of("stairs", 9999), set("stairs"));
        assertEquals(set("stairs"), active);
    }

    @Test
    void activeSet_finiteEntryActiveUntilMetThenRetired() {
        RecipeScript s = script(entry("planks", 64));
        assertEquals(set("planks"),
            ScriptSelection.activeSet(s, Map.of("planks", 63), set("planks")));
        assertTrue(ScriptSelection.activeSet(s, Map.of("planks", 64), set("planks")).isEmpty());
        assertTrue(ScriptSelection.activeSet(s, Map.of("planks", 100), set("planks")).isEmpty());
    }

    @Test
    void activeSet_intersectsWithCraftableNow() {
        RecipeScript s = script(entry("planks", 64), entry("stairs", 0));
        Set<String> active = ScriptSelection.activeSet(s, Map.of(), set("planks"));
        assertEquals(set("planks"), active);
    }

    @Test
    void activeSet_preservesScriptOrder() {
        RecipeScript s = script(entry("a", 0), entry("b", 0), entry("c", 0));
        Set<String> active = ScriptSelection.activeSet(s, Map.of(), set("c", "b", "a"));
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(active));
    }

    @Test
    void activeSet_nullScriptOrEmptyIsEmpty() {
        assertTrue(ScriptSelection.activeSet(null, Map.of(), set("planks")).isEmpty());
        assertTrue(ScriptSelection.activeSet(script(), Map.of(), set("planks")).isEmpty());
    }

    // ---- finitePick (the finite phase: sequential, first-in-order, skip-not-stall) ----------------

    @Test
    void finitePick_firstUnmetFiniteInCardOrder() {
        // [a x5, b x3]: a comes first, both craftable, neither met -> a.
        RecipeScript s = script(entry("a", 5), entry("b", 3));
        assertEquals("a", ScriptSelection.finitePick(s, Map.of(), set("a", "b")));
    }

    @Test
    void finitePick_advancesToNextOnlyAfterFirstMet() {
        // a met (5/5) -> next finite b.
        RecipeScript s = script(entry("a", 5), entry("b", 3));
        assertEquals("b", ScriptSelection.finitePick(s, Map.of("a", 5), set("a", "b")));
    }

    @Test
    void finitePick_skipsUncraftableFiniteHeadToNextCraftableFinite() {
        // a unmet but NOT craftable this tick -> skip to b (skip-not-stall), resumed later.
        RecipeScript s = script(entry("a", 5), entry("b", 3));
        assertEquals("b", ScriptSelection.finitePick(s, Map.of(), set("b")));
    }

    @Test
    void finitePick_ignoresInfiniteEntries() {
        // Only infinite entries -> no finite pick.
        RecipeScript s = script(entry("a", 0), entry("b", 0));
        assertNull(ScriptSelection.finitePick(s, Map.of(), set("a", "b")));
    }

    @Test
    void finitePick_nullWhenAllFiniteMet() {
        RecipeScript s = script(entry("a", 5), entry("b", 3));
        assertNull(ScriptSelection.finitePick(s, Map.of("a", 5, "b", 3), set("a", "b")));
    }

    // ---- infiniteActive / infiniteOrder ----------------------------------------------------------

    @Test
    void infiniteActive_onlyCraftableInfiniteEntries() {
        RecipeScript s = script(entry("a", 5), entry("b", 0), entry("c", 0));
        // a is finite (excluded); c not craftable (excluded); only b.
        assertEquals(set("b"), ScriptSelection.infiniteActive(s, set("a", "b")));
    }

    @Test
    void infiniteOrder_distinctInfiniteIdsInScriptOrder() {
        RecipeScript s = script(entry("a", 0), entry("fin", 4), entry("b", 0), entry("a", 0));
        assertEquals(List.of("a", "b"), ScriptSelection.infiniteOrder(s));
    }

    // ---- cardPick: finite phase strictly precedes infinite phase ----------------------------------

    @Test
    void cardPick_finiteBeforeInfinite() {
        // [a x5 (finite), b x0 (infinite)]: while a unmet+craftable -> a, never b.
        RecipeScript s = script(entry("a", 5), entry("b", 0));
        assertEquals("a", ScriptSelection.cardPick(s, Map.of("a", 2), set("a", "b"), null));
    }

    @Test
    void cardPick_sequentialFiniteThenInfinite() {
        // [a x5, b x3, c x0]: a, then b, then forever c.
        RecipeScript s = script(entry("a", 5), entry("b", 3), entry("c", 0));
        Set<String> craftable = set("a", "b", "c");
        assertEquals("a", ScriptSelection.cardPick(s, Map.of(), craftable, null));
        assertEquals("b", ScriptSelection.cardPick(s, Map.of("a", 5), craftable, null));
        assertEquals("c", ScriptSelection.cardPick(s, Map.of("a", 5, "b", 3), craftable, null));
    }

    @Test
    void cardPick_skipUncraftableFiniteResumesLater() {
        RecipeScript s = script(entry("a", 5), entry("b", 3));
        // a uncraftable -> pick b; once a craftable again and still unmet -> a (in-order resume).
        assertEquals("b", ScriptSelection.cardPick(s, Map.of(), set("b"), null));
        assertEquals("a", ScriptSelection.cardPick(s, Map.of(), set("a", "b"), null));
    }

    @Test
    void cardPick_infinitePhaseRoundRobinsPlainly() {
        // All infinite: even rotation off the cursor, NO priority. [a, b, c] order.
        RecipeScript s = script(entry("a", 0), entry("b", 0), entry("c", 0));
        Set<String> craftable = set("a", "b", "c");
        assertEquals("a", ScriptSelection.cardPick(s, Map.of(), craftable, null));
        assertEquals("b", ScriptSelection.cardPick(s, Map.of(), craftable, "a"));
        assertEquals("c", ScriptSelection.cardPick(s, Map.of(), craftable, "b"));
        assertEquals("a", ScriptSelection.cardPick(s, Map.of(), craftable, "c")); // wraps
    }

    @Test
    void cardPick_infinitePhaseSkipsNonCraftableInRotation() {
        RecipeScript s = script(entry("a", 0), entry("b", 0), entry("c", 0));
        // b not craftable: after a, rotation skips b to c.
        assertEquals("c", ScriptSelection.cardPick(s, Map.of(), set("a", "c"), "a"));
    }

    @Test
    void cardPick_nullWhenNothingActive() {
        RecipeScript s = script(entry("a", 5));
        assertNull(ScriptSelection.cardPick(s, Map.of("a", 5), set("a"), null)); // finite met, no infinite
        assertNull(ScriptSelection.cardPick(s, Map.of(), Set.of(), null));        // nothing craftable
        assertNull(ScriptSelection.cardPick(null, Map.of(), set("a"), null));     // no script
    }

    // ---- isComplete ------------------------------------------------------------------------------

    @Test
    void isComplete_falseWhileAnyFiniteEntryUnmet() {
        RecipeScript s = script(entry("planks", 64), entry("stairs", 32));
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 64, "stairs", 31)));
    }

    @Test
    void isComplete_falseWhenAnyInfiniteEntryPresentEvenIfFiniteMet() {
        RecipeScript s = script(entry("planks", 64), entry("stairs", 0));
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 64)));
    }

    @Test
    void isComplete_trueWhenAllFiniteAndAllMet() {
        RecipeScript s = script(entry("planks", 64), entry("stairs", 32));
        assertTrue(ScriptSelection.isComplete(s, Map.of("planks", 64, "stairs", 32)));
    }

    @Test
    void isComplete_nullProgressTreatedAsEmpty() {
        RecipeScript s = script(entry("planks", 64));
        assertFalse(ScriptSelection.isComplete(s, null));
    }

    // ---- mix example: finite-then-infinite, never complete ----------------------------------------

    @Test
    void mixExample_planks64ThenStairsForever() {
        // [planks(64), stairs(inf)]: both sourceable each tick.
        RecipeScript s = script(entry("planks", 64), entry("stairs", 0));
        Set<String> craftable = set("planks", "stairs");

        // While made[planks] < 64: finite phase -> planks (stairs never chosen yet).
        assertEquals("planks", ScriptSelection.cardPick(s, Map.of("planks", 10), craftable, null));
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 10)));

        // After planks met: infinite phase -> stairs forever.
        assertEquals("stairs", ScriptSelection.cardPick(s, Map.of("planks", 64), craftable, null));
        assertEquals("stairs", ScriptSelection.cardPick(s, Map.of("planks", 64), craftable, "stairs"));

        // Never complete: stairs is infinite.
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 64)));
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 9999, "stairs", 9999)));
    }
}
