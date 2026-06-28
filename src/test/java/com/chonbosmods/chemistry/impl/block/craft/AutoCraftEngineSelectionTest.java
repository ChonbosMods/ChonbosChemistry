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
 * Pure unit tests for the selection seam wired into {@link AutoCraftEngine}: {@code resolveCandidates} (the
 * script-aware candidate set that replaces the bare {@code topByPriority} call in the IDLE branch) and
 * {@code scriptSignature} (the card-change detector that drives progress reset). Both are pure (ids, counts,
 * sets, maps): no world / ECS / ItemStack needed, so the engine integration is unit-covered here while the
 * deeper drive() loop is integration-tested in-game.
 *
 * <p>Candidate contract under the new card model: the no-card path is byte-identical {@code topByPriority};
 * the card path returns a SINGLETON for the finite phase (the first-in-order finite pick, which
 * {@code selectNext} then forces) and the FULL craftable-infinite set for the infinite phase (which
 * {@code selectNext} round-robins via the cursor). No most-ingredient priority within a card.
 */
class AutoCraftEngineSelectionTest {

    private static RecipeScript script(Entry... entries) {
        return new RecipeScript(Arrays.asList(entries));
    }

    private static Entry entry(String id, int count) {
        return new Entry(id, count);
    }

    private static Set<String> set(String... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    // ---- resolveCandidates: no-card path is UNCHANGED (topByPriority) -------------------------------

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

    // ---- resolveCandidates: card FINITE phase (singleton, no priority) ------------------------------

    @Test
    void resolveCandidates_finitePhase_singletonFirstInCardOrder() {
        // [a x5, b x3]: a is first finite, craftable, unmet -> singleton {a}, IGNORING countMap.
        RecipeScript s = script(entry("a", 5), entry("b", 3));
        assertEquals(set("a"),
            AutoCraftEngine.resolveCandidates(s, Map.of(), set("a", "b"), Map.of("a", 1, "b", 9)));
    }

    @Test
    void resolveCandidates_finitePhase_advancesAfterFirstMet() {
        RecipeScript s = script(entry("a", 5), entry("b", 3));
        assertEquals(set("b"),
            AutoCraftEngine.resolveCandidates(s, Map.of("a", 5), set("a", "b"), Map.of()));
    }

    @Test
    void resolveCandidates_finitePhase_skipsNonCraftableHeadToNextFinite() {
        // a unmet but not craftable -> skip-not-stall: singleton {b}.
        RecipeScript s = script(entry("a", 5), entry("b", 3));
        assertEquals(set("b"),
            AutoCraftEngine.resolveCandidates(s, Map.of(), set("b"), Map.of()));
    }

    // ---- resolveCandidates: card INFINITE phase (full set, round-robined by selectNext) ------------

    @Test
    void resolveCandidates_infinitePhase_fullCraftableInfiniteSet_noPriority() {
        // All finite met (none here, all infinite): full craftable infinite set, NOT a priority subset.
        RecipeScript s = script(entry("a", 0), entry("b", 0), entry("c", 0));
        Set<String> craftable = set("a", "b", "c");
        // Even with differing ingredient counts, no priority is applied -> all three are candidates.
        assertEquals(set("a", "b", "c"),
            AutoCraftEngine.resolveCandidates(s, Map.of(), craftable, Map.of("a", 1, "b", 9, "c", 3)));
    }

    @Test
    void resolveCandidates_infinitePhase_onlyAfterFiniteExhausted() {
        // [a x2 finite, b x0 infinite]: while a unmet -> {a}; once a met -> infinite phase {b}.
        RecipeScript s = script(entry("a", 2), entry("b", 0));
        Set<String> craftable = set("a", "b");
        assertEquals(set("a"), AutoCraftEngine.resolveCandidates(s, Map.of("a", 1), craftable, Map.of()));
        assertEquals(set("b"), AutoCraftEngine.resolveCandidates(s, Map.of("a", 2), craftable, Map.of()));
    }

    @Test
    void resolveCandidates_infinitePhase_excludesNonCraftableAndFinite() {
        RecipeScript s = script(entry("a", 0), entry("fin", 4), entry("b", 0));
        // fin is finite + met; c not in script. Only craftable infinite {a} (b not craftable this tick).
        assertEquals(set("a"),
            AutoCraftEngine.resolveCandidates(s, Map.of("fin", 4), set("a", "fin", "c"), Map.of()));
    }

    @Test
    void resolveCandidates_completedCardIsEmpty() {
        // a finite + met, no infinite -> empty (drive() goes IDLE).
        RecipeScript s = script(entry("a", 2));
        assertTrue(AutoCraftEngine.resolveCandidates(s, Map.of("a", 2), set("a"), Map.of()).isEmpty());
    }

    @Test
    void resolveCandidates_nothingCraftableIsEmpty() {
        RecipeScript s = script(entry("a", 0));
        assertTrue(AutoCraftEngine.resolveCandidates(s, Map.of(), set("z"), Map.of()).isEmpty());
    }

    // ---- scriptSignature ----------------------------------------------------------------------------

    @Test
    void scriptSignature_nullIsEmptyString() {
        assertEquals("", AutoCraftEngine.scriptSignature(null));
    }

    @Test
    void scriptSignature_identicalScriptsEqual() {
        RecipeScript a = script(entry("x", 2), entry("y", 0));
        RecipeScript b = script(entry("x", 2), entry("y", 0));
        assertEquals(AutoCraftEngine.scriptSignature(a), AutoCraftEngine.scriptSignature(b));
    }

    @Test
    void scriptSignature_countDiffers() {
        assertNotEquals(AutoCraftEngine.scriptSignature(script(entry("x", 2))),
            AutoCraftEngine.scriptSignature(script(entry("x", 3))));
    }

    @Test
    void scriptSignature_idDiffers() {
        assertNotEquals(AutoCraftEngine.scriptSignature(script(entry("x", 2))),
            AutoCraftEngine.scriptSignature(script(entry("z", 2))));
    }

    @Test
    void scriptSignature_entryOrderDiffers() {
        assertNotEquals(AutoCraftEngine.scriptSignature(script(entry("x", 0), entry("y", 0))),
            AutoCraftEngine.scriptSignature(script(entry("y", 0), entry("x", 0))));
    }

    @Test
    void scriptSignature_removalToNullDiffersFromAnyScript() {
        assertNotEquals(AutoCraftEngine.scriptSignature(script(entry("x", 0))),
            AutoCraftEngine.scriptSignature(null));
    }
}
