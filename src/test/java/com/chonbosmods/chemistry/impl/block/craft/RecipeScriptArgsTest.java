package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.block.craft.RecipeScript.Entry;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RecipeScriptArgs}: the {@code /cc-script} token grammar (tokens -&gt;
 * {@link RecipeScript}). No world / ECS / ItemStack, so the parser is fully covered here while the command's
 * thin give-the-card glue is verified in-game.
 */
class RecipeScriptArgsTest {

    private static List<String> tokens(String... t) {
        return Arrays.asList(t);
    }

    @Test
    void parse_orderedKeyword_makesOrderedAndIsConsumed() {
        // "ordered a:5 b" -> ordered, [a x5, b xINF]
        RecipeScript s = RecipeScriptArgs.parse(tokens("ordered", "a:5", "b"));
        assertTrue(s.ordered());
        List<Entry> e = s.entries();
        assertEquals(2, e.size());
        assertEquals("a", e.get(0).recipeId());
        assertEquals(5, e.get(0).count());
        assertFalse(RecipeScript.isInfinite(e.get(0)));
        assertEquals("b", e.get(1).recipeId());
        assertTrue(RecipeScript.isInfinite(e.get(1))); // no count => infinite
    }

    @Test
    void parse_noOrderedKeyword_isUnordered() {
        RecipeScript s = RecipeScriptArgs.parse(tokens("a", "b:2"));
        assertFalse(s.ordered());
        assertEquals(2, s.entries().size());
        assertTrue(RecipeScript.isInfinite(s.entries().get(0))); // a => infinite
        assertEquals(2, s.entries().get(1).count());             // b:2 => finite 2
    }

    @Test
    void parse_orderedIsCaseInsensitive() {
        assertTrue(RecipeScriptArgs.parse(tokens("ORDERED", "x")).ordered());
        assertTrue(RecipeScriptArgs.parse(tokens("Ordered", "x")).ordered());
    }

    @Test
    void parse_recipeNamedOrderedNotFirst_isATreatedAsAnEntry() {
        // "ordered" only flips the script as the FIRST token; later it is a plain id.
        RecipeScript s = RecipeScriptArgs.parse(tokens("a", "ordered"));
        assertFalse(s.ordered());
        assertEquals(2, s.entries().size());
        assertEquals("ordered", s.entries().get(1).recipeId());
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
    void parse_orderedWithNoEntries_throws() {
        assertThrows(RecipeScriptArgs.ParseException.class, () -> RecipeScriptArgs.parse(tokens("ordered")));
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
    void describe_orderedAndInfiniteFormatting() {
        RecipeScript s = RecipeScriptArgs.parse(tokens("ordered", "a:5", "b"));
        assertEquals("ordered: a x5, b x∞", RecipeScriptArgs.describe(s));
    }

    @Test
    void describe_unorderedFormatting() {
        RecipeScript s = RecipeScriptArgs.parse(tokens("a"));
        assertEquals("unordered: a x∞", RecipeScriptArgs.describe(s));
    }

    @Test
    void parse_describe_roundTripSignatureMatches() {
        // The parsed script's signature equals one built from the same entries (sanity: parse is faithful).
        RecipeScript parsed = RecipeScriptArgs.parse(tokens("ordered", "a:5", "b"));
        RecipeScript built = new RecipeScript(true, Arrays.asList(new Entry("a", 5), new Entry("b", 0)));
        assertEquals(AutoCraftEngine.scriptSignature(built), AutoCraftEngine.scriptSignature(parsed));
    }
}
