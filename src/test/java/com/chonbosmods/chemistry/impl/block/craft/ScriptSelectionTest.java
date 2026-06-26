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
 * Pure unit tests for {@link ScriptSelection}: the active-set intersection, ordered first-craftable pick
 * (skip-not-stall per design §3.3), and blueprint-completion rule (design §3 step 5). All cases build
 * {@link RecipeScript}/{@link Entry} directly: no ItemStack/metadata/AssetStore needed.
 */
class ScriptSelectionTest {

    private static RecipeScript script(boolean ordered, Entry... entries) {
        return new RecipeScript(ordered, Arrays.asList(entries));
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
        RecipeScript s = script(false, entry("stairs", 0));
        Set<String> active = ScriptSelection.activeSet(s, Map.of("stairs", 9999), set("stairs"));
        assertEquals(set("stairs"), active);
    }

    @Test
    void activeSet_finiteEntryActiveUntilMetThenRetired() {
        RecipeScript s = script(false, entry("planks", 64));

        // made < count -> active
        assertEquals(set("planks"),
            ScriptSelection.activeSet(s, Map.of("planks", 63), set("planks")));
        // made == count -> retired
        assertTrue(ScriptSelection.activeSet(s, Map.of("planks", 64), set("planks")).isEmpty());
        // made > count -> retired
        assertTrue(ScriptSelection.activeSet(s, Map.of("planks", 100), set("planks")).isEmpty());
    }

    @Test
    void activeSet_intersectsWithCraftableNow() {
        RecipeScript s = script(false, entry("planks", 64), entry("stairs", 0));
        // stairs is in the script + unfinished, but NOT currently craftable -> excluded.
        Set<String> active = ScriptSelection.activeSet(s, Map.of(), set("planks"));
        assertEquals(set("planks"), active);
    }

    @Test
    void activeSet_preservesScriptOrder() {
        RecipeScript s = script(true, entry("a", 0), entry("b", 0), entry("c", 0));
        Set<String> active = ScriptSelection.activeSet(s, Map.of(), set("c", "b", "a"));
        assertEquals(List.of("a", "b", "c"), new ArrayList<>(active));
    }

    @Test
    void activeSet_duplicateIdCollapses() {
        RecipeScript s = script(false, entry("planks", 0), entry("planks", 0));
        Set<String> active = ScriptSelection.activeSet(s, Map.of(), set("planks"));
        assertEquals(set("planks"), active);
        assertEquals(1, active.size());
    }

    @Test
    void activeSet_craftableIdNotInScriptIsExcluded() {
        RecipeScript s = script(false, entry("planks", 0));
        Set<String> active = ScriptSelection.activeSet(s, Map.of(), set("planks", "slab"));
        assertEquals(set("planks"), active);
    }

    @Test
    void activeSet_nullProgressTreatedAsEmpty() {
        RecipeScript s = script(false, entry("planks", 64));
        Set<String> active = ScriptSelection.activeSet(s, null, set("planks"));
        assertEquals(set("planks"), active);
    }

    @Test
    void activeSet_nullScriptOrEmptyIsEmpty() {
        assertTrue(ScriptSelection.activeSet(null, Map.of(), set("planks")).isEmpty());
        assertTrue(ScriptSelection.activeSet(script(false), Map.of(), set("planks")).isEmpty());
    }

    // ---- orderedPick -----------------------------------------------------------------------------

    @Test
    void orderedPick_returnsFirstActiveInScriptOrder() {
        RecipeScript s = script(true, entry("a", 0), entry("b", 0), entry("c", 0));
        assertEquals("a", ScriptSelection.orderedPick(s, set("a", "b", "c")));
    }

    @Test
    void orderedPick_skipsUncraftableEntryToNextActive() {
        // 'a' is unfinished in the script but not in the active set (uncraftable this tick):
        // ordered traversal SKIPS it rather than stalling (design §3.3 default).
        RecipeScript s = script(true, entry("a", 0), entry("b", 0), entry("c", 0));
        assertEquals("b", ScriptSelection.orderedPick(s, set("b", "c")));
    }

    @Test
    void orderedPick_nullWhenActiveEmpty() {
        RecipeScript s = script(true, entry("a", 0), entry("b", 0));
        assertNull(ScriptSelection.orderedPick(s, Set.of()));
    }

    @Test
    void orderedPick_nullScriptIsNull() {
        assertNull(ScriptSelection.orderedPick(null, set("a")));
    }

    // ---- isComplete ------------------------------------------------------------------------------

    @Test
    void isComplete_falseWhileAnyFiniteEntryUnmet() {
        RecipeScript s = script(true, entry("planks", 64), entry("stairs", 32));
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 64, "stairs", 31)));
    }

    @Test
    void isComplete_falseWhenAnyInfiniteEntryPresentEvenIfFiniteMet() {
        RecipeScript s = script(false, entry("planks", 64), entry("stairs", 0));
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 64)));
    }

    @Test
    void isComplete_trueWhenAllFiniteAndAllMet() {
        RecipeScript s = script(true, entry("planks", 64), entry("stairs", 32));
        assertTrue(ScriptSelection.isComplete(s, Map.of("planks", 64, "stairs", 32)));
    }

    @Test
    void isComplete_nullProgressTreatedAsEmpty() {
        RecipeScript s = script(true, entry("planks", 64));
        assertFalse(ScriptSelection.isComplete(s, null));
    }

    // ---- mix example (design §2 Example C) -------------------------------------------------------

    @Test
    void mixExample_planks64ThenStairsForever() {
        // [planks(64), stairs(inf)] : both sourceable each tick.
        RecipeScript s = script(true, entry("planks", 64), entry("stairs", 0));
        Set<String> craftable = set("planks", "stairs");

        // While made[planks] < 64: both active, ordered pick -> planks.
        Set<String> early = ScriptSelection.activeSet(s, Map.of("planks", 10), craftable);
        assertEquals(set("planks", "stairs"), early);
        assertEquals("planks", ScriptSelection.orderedPick(s, early));
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 10)));

        // After planks met: only stairs active, ordered pick -> stairs.
        Set<String> late = ScriptSelection.activeSet(s, Map.of("planks", 64), craftable);
        assertEquals(set("stairs"), late);
        assertEquals("stairs", ScriptSelection.orderedPick(s, late));

        // Never complete: stairs is infinite.
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 64)));
        assertFalse(ScriptSelection.isComplete(s, Map.of("planks", 9999, "stairs", 9999)));
    }
}
