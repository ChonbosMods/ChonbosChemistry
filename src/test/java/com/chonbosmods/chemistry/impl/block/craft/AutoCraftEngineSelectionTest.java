package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.block.craft.RecipeScript.Entry;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the Task-4 selection seam wired into {@link AutoCraftEngine}: {@code resolveCandidates}
 * (the script-aware candidate set that replaces the bare {@code topByPriority} call in the IDLE branch) and
 * {@code scriptSignature} (the card-change detector that drives progress reset). Both are pure (ids, counts,
 * sets, maps): no world / ECS / ItemStack needed, so the whole engine integration is unit-covered here while
 * the deeper drive() loop is integration-tested in-game.
 */
class AutoCraftEngineSelectionTest {

    private static RecipeScript script(boolean ordered, Entry... entries) {
        return new RecipeScript(ordered, Arrays.asList(entries));
    }

    private static Entry entry(String id, int count) {
        return new Entry(id, count);
    }

    private static Set<String> set(String... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    // ---- resolveCandidates: no-card path is UNCHANGED ------------------------------------------------

    @Test
    void resolveCandidates_nullScript_isExactlyTopByPriority() {
        Set<String> craftable = set("a", "b", "c");
        Map<String, Integer> countMap = Map.of("a", 3, "b", 1, "c", 3);
        // a + c tie for the max ingredient count (3): the top tier is exactly {a, c}.
        Set<String> expected = CraftSelection.topByPriority(craftable, countMap);
        assertEquals(expected,
            AutoCraftEngine.resolveCandidates(null, Map.of(), craftable, countMap));
        assertEquals(set("a", "c"), expected); // sanity: the no-card behavior is the existing one
    }

    // ---- resolveCandidates: UNORDERED script --------------------------------------------------------

    @Test
    void resolveCandidates_unordered_priorityWithinActiveSet() {
        // Script whitelists a + b (both infinite); c is craftable but NOT in the script -> excluded.
        RecipeScript s = script(false, entry("a", 0), entry("b", 0));
        Set<String> craftable = set("a", "b", "c");
        Map<String, Integer> countMap = Map.of("a", 1, "b", 3, "c", 9);
        // Within the active set {a, b}, b has the higher ingredient count -> top tier is {b} only.
        assertEquals(set("b"),
            AutoCraftEngine.resolveCandidates(s, Map.of(), craftable, countMap));
    }

    @Test
    void resolveCandidates_unordered_finiteEntryRetiredByProgress() {
        RecipeScript s = script(false, entry("a", 2), entry("b", 0));
        Set<String> craftable = set("a", "b");
        Map<String, Integer> countMap = Map.of("a", 9, "b", 1);
        // a not yet met (made 1 < 2): a wins on priority.
        assertEquals(set("a"),
            AutoCraftEngine.resolveCandidates(s, Map.of("a", 1), craftable, countMap));
        // a met (made 2 == 2): retired, so only b remains active.
        assertEquals(set("b"),
            AutoCraftEngine.resolveCandidates(s, Map.of("a", 2), craftable, countMap));
    }

    @Test
    void resolveCandidates_unordered_completedScriptIsEmpty() {
        RecipeScript s = script(false, entry("a", 2));
        // a met -> active set empty -> no candidates (drive() goes IDLE).
        assertTrue(AutoCraftEngine.resolveCandidates(s, Map.of("a", 2), set("a"), Map.of("a", 1)).isEmpty());
    }

    // ---- resolveCandidates: ORDERED script ----------------------------------------------------------

    @Test
    void resolveCandidates_ordered_singletonFirstActiveInListOrder() {
        // List order is the priority. a is craftable + active -> forced singleton, ignoring countMap.
        RecipeScript s = script(true, entry("a", 0), entry("b", 0));
        assertEquals(set("a"),
            AutoCraftEngine.resolveCandidates(s, Map.of(), set("a", "b"), Map.of("a", 1, "b", 9)));
    }

    @Test
    void resolveCandidates_ordered_skipsNonCraftableHeadToNextActive() {
        // a is in the script but NOT craftable now -> skip-not-stall: b is the pick.
        RecipeScript s = script(true, entry("a", 0), entry("b", 0));
        assertEquals(set("b"),
            AutoCraftEngine.resolveCandidates(s, Map.of(), set("b"), Map.of("a", 1, "b", 1)));
    }

    @Test
    void resolveCandidates_ordered_nothingActiveIsEmpty() {
        RecipeScript s = script(true, entry("a", 0));
        // a not craftable -> ordered pick null -> empty set.
        assertTrue(AutoCraftEngine.resolveCandidates(s, Map.of(), set("z"), Map.of()).isEmpty());
    }

    // ---- scriptSignature ----------------------------------------------------------------------------

    @Test
    void scriptSignature_nullIsEmptyString() {
        assertEquals("", AutoCraftEngine.scriptSignature(null));
    }

    @Test
    void scriptSignature_identicalScriptsEqual() {
        RecipeScript a = script(false, entry("x", 2), entry("y", 0));
        RecipeScript b = script(false, entry("x", 2), entry("y", 0));
        assertEquals(AutoCraftEngine.scriptSignature(a), AutoCraftEngine.scriptSignature(b));
    }

    @Test
    void scriptSignature_orderedFlagDiffers() {
        RecipeScript a = script(false, entry("x", 2));
        RecipeScript b = script(true, entry("x", 2));
        assertNotEquals(AutoCraftEngine.scriptSignature(a), AutoCraftEngine.scriptSignature(b));
    }

    @Test
    void scriptSignature_countDiffers() {
        RecipeScript a = script(false, entry("x", 2));
        RecipeScript b = script(false, entry("x", 3));
        assertNotEquals(AutoCraftEngine.scriptSignature(a), AutoCraftEngine.scriptSignature(b));
    }

    @Test
    void scriptSignature_idDiffers() {
        RecipeScript a = script(false, entry("x", 2));
        RecipeScript b = script(false, entry("z", 2));
        assertNotEquals(AutoCraftEngine.scriptSignature(a), AutoCraftEngine.scriptSignature(b));
    }

    @Test
    void scriptSignature_entryOrderDiffers() {
        RecipeScript a = script(false, entry("x", 0), entry("y", 0));
        RecipeScript b = script(false, entry("y", 0), entry("x", 0));
        assertNotEquals(AutoCraftEngine.scriptSignature(a), AutoCraftEngine.scriptSignature(b));
    }

    @Test
    void scriptSignature_removalToNullDiffersFromAnyScript() {
        RecipeScript a = script(false, entry("x", 0));
        assertNotEquals(AutoCraftEngine.scriptSignature(a), AutoCraftEngine.scriptSignature(null));
    }
}
