package com.chonbosmods.chemistry.impl.block.craft;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nullable;

/**
 * The minimal "holds one recipe card" contract: a single {@code card()}/{@code setCard()} accessor pair.
 * Extracted from {@link AutoCraftNode} so a block that holds a card WITHOUT being a crafting machine (no
 * progress, no engine) : the Recipe Programmer bench : can share the card-load interaction
 * ({@code RecipeCardInteraction}) with the 7 auto-crafter machines.
 *
 * <p>{@link AutoCraftNode} extends this (the 7 machine states already implement {@code card()}/{@code
 * setCard()}, so they satisfy it unchanged); {@code RecipeProgrammerState} implements it directly. The
 * card-load interaction resolves a {@code CardHolder} at the targeted block and insert/swaps its card,
 * applying the machine-only per-card progress reset only when the holder is also an {@link AutoCraftNode}.
 */
public interface CardHolder {

    /** @return the inserted recipe card, or null if no card is loaded. */
    @Nullable
    ItemStack card();

    /** Sets (or clears, when {@code null}) the inserted recipe card. */
    void setCard(@Nullable ItemStack card);
}
