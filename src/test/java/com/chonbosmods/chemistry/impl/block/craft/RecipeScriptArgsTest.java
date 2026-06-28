package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.block.craft.RecipeScript.Entry;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RecipeScriptArgs}: the {@code /cc-script} token grammar (tokens -&gt;
 * {@link RecipeScript}). No world / ECS / ItemStack, so the parser is fully covered here while the command's
 * thin give-the-card glue is verified in-game. The grammar is now just {@code recipeId[:count] ...} : the
 * leading {@code ordered} token was dropped with the ordered flag.
 */
class RecipeScriptArgsTest {

    private static List<String> tokens(String... t) {
        return Arrays.asList(t);
    }

    @Test
    void parse_entriesWithAndWithoutCounts() {
        // "a:5 b" -> [a x5, b xINF]
        RecipeScript s = RecipeScriptArgs.parse(tokens("a:5", "b"));
        List<Entry> e = s.entries();
        assertEquals(2, e.size());
        assertEquals("a", e.get(0).recipeId());
        assertEquals(5, e.get(0).count());
        assertEquals("b", e.get(1).recipeId());
        assertTrue(RecipeScript.isInfinite(e.get(1))); // no count => infinite
    }

    @Test
    void parse_multipleEntries() {
        RecipeScript s = RecipeScriptArgs.parse(tokens("a", "b:2"));
        assertEquals(2, s.entries().size());
        assertTrue(RecipeScript.isInfinite(s.entries().get(0))); // a => infinite
        assertEquals(2, s.entries().get(1).count());             // b:2 => finite 2
    }

    @Test
    void parse_orderedIsNoLongerAKeyword_treatedAsAnId() {
        // "ordered" is now just a recipe id, never a flag.
        RecipeScript s = RecipeScriptArgs.parse(tokens("ordered", "x"));
        assertEquals(2, s.entries().size());
        assertEquals("ordered", s.entries().get(0).recipeId());
        assertEquals("x", s.entries().get(1).recipeId());
    }

    @Test
    void parse_zeroOrNegativeCount_isInfinite() {
        RecipeScript s = RecipeScriptArgs.parse(tokens("a:0", "b:-3"));
        assertTrue(RecipeScript.isInfinite(s.entries().get(0)));
        assertTrue(RecipeScript.isInfinite(s.entries().get(1)));
    }

    @Test
    void parse_trailingColon_isInfinite() {
        RecipeScript s = RecipeScriptArgs.parse(tokens("a:"));
        assertTrue(RecipeScript.isInfinite(s.entries().get(0)));
        assertEquals("a", s.entries().get(0).recipeId());
    }

    @Test
    void parse_blankTokensAreSkipped() {
        RecipeScript s = RecipeScriptArgs.parse(tokens("", "  ", "a:1", " "));
        assertEquals(1, s.entries().size());
        assertEquals("a", s.entries().get(0).recipeId());
    }

    @Test
    void parse_empty_throws() {
        assertThrows(RecipeScriptArgs.ParseException.class, () -> RecipeScriptArgs.parse(tokens()));
        assertThrows(RecipeScriptArgs.ParseException.class, () -> RecipeScriptArgs.parse(null));
        assertThrows(RecipeScriptArgs.ParseException.class, () -> RecipeScriptArgs.parse(tokens("  ")));
    }

    @Test
    void parse_nonIntegerCount_throws() {
        assertThrows(RecipeScriptArgs.ParseException.class, () -> RecipeScriptArgs.parse(tokens("a:five")));
    }

    @Test
    void parse_emptyIdBeforeColon_throws() {
        assertThrows(RecipeScriptArgs.ParseException.class, () -> RecipeScriptArgs.parse(tokens(":5")));
    }

    @Test
    void describe_finiteAndInfiniteFormatting() {
        RecipeScript s = RecipeScriptArgs.parse(tokens("a:5", "b"));
        assertEquals("a x5, b x∞", RecipeScriptArgs.describe(s));
    }

    @Test
    void describe_singleInfiniteFormatting() {
        RecipeScript s = RecipeScriptArgs.parse(tokens("a"));
        assertEquals("a x∞", RecipeScriptArgs.describe(s));
    }

    @Test
    void parse_describe_roundTripSignatureMatches() {
        // The parsed script's signature equals one built from the same entries (sanity: parse is faithful).
        RecipeScript parsed = RecipeScriptArgs.parse(tokens("a:5", "b"));
        RecipeScript built = new RecipeScript(Arrays.asList(new Entry("a", 5), new Entry("b", 0)));
        assertEquals(AutoCraftEngine.scriptSignature(built), AutoCraftEngine.scriptSignature(parsed));
    }
}
