package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecipeCardOps}, the pure helpers behind the {@code cc_recipe_card} interaction.
 *
 * <p><b>Test-JVM constraint.</b> {@code new ItemStack(id, qty[, meta])} calls {@code getItem().
 * getMaxDurability()}, which resolves against the global AssetStore (unloaded here → NPE). So the
 * <em>positive</em> "build a qty-1 metadata-preserving copy" path is in-game-only (it constructs a real
 * ItemStack). What IS unit-testable: the input guards that short-circuit BEFORE any construction
 * ({@link RecipeCardOps#toStoredCard} on null/empty input) and {@link RecipeCardOps#isGivebackWorthy}.
 */
class RecipeCardOpsTest {

    @Test
    void toStoredCardReturnsNullForNullHeld() {
        // No held item: nothing to insert (the guard returns before constructing an ItemStack).
        assertNull(RecipeCardOps.toStoredCard(null));
    }

    @Test
    void toStoredCardReturnsNullForEmptyHeld() {
        // The shared EMPTY sentinel reads as empty and short-circuits to null (no construction).
        assertNull(RecipeCardOps.toStoredCard(ItemStack.EMPTY));
    }

    @Test
    void isGivebackWorthyFalseForNull() {
        // An empty/absent stored card is never handed back (the INSERT case: nothing to return).
        assertFalse(RecipeCardOps.isGivebackWorthy(null));
    }

    @Test
    void isGivebackWorthyFalseForEmpty() {
        assertFalse(RecipeCardOps.isGivebackWorthy(ItemStack.EMPTY));
    }

    @Test
    void isGivebackWorthyTrueForRealCard() {
        // A decoded (non-empty) stack is giveback-worthy. Built via the CODEC, not the constructor, so it
        // avoids the AssetStore durability lookup that NPEs in the test JVM.
        ItemStack card = RecipeCardOpsTestStacks.stack("CC_RecipeScript", 1);
        assertTrue(RecipeCardOps.isGivebackWorthy(card));
    }
}
