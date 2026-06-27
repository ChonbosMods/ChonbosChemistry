package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.impl.block.PoweredMachineNode;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The state contract an autonomous-crafting machine exposes to the shared craft engine ({@code
 * AutoCraftEngine}, added next). Every accessor here already exists on {@link ForgeCraftState}; future
 * crafting machines (Cooker, Outfitter) implement the same contract so the engine drives them uniformly.
 *
 * <p>Extends {@link PoweredMachineNode}, the shared powered-machine contract (energy + ports + on/off),
 * so the energy/ports/enabled accessors are inherited rather than redeclared here. Also extends
 * {@link CardHolder} (the {@code card()}/{@code setCard()} pair), so the card-load interaction can treat a
 * machine and the (non-machine) Recipe Programmer uniformly; the {@code card()}/{@code setCard()} declared
 * below are the same contract, kept here for their machine-specific Javadoc.
 */
public interface AutoCraftNode extends PoweredMachineNode, CardHolder {

    /** @return the held ingredients pulled for the active craft (empty when idle). */
    SimpleItemContainer held();

    /** @return the result container. */
    SimpleItemContainer output();

    /** @return the inserted recipe card, or null if no card is loaded. */
    ItemStack card();

    /**
     * Sets (or clears, when {@code null}) the inserted recipe card. Implementations also mark their owning
     * block needs-saving as their other mutators do. Only the Cooker panel writes this today (the interactive
     * card slot); the other states implement it so the contract compiles uniformly.
     */
    void setCard(@Nullable ItemStack card);

    /** @return the recipe currently being crafted, or null if idle. */
    String currentRecipeId();

    void setCurrentRecipeId(String currentRecipeId);

    /** @return accumulated craft seconds. */
    float progress();

    void setProgress(float progress);

    /** @return the round-robin cursor (last crafted id), or null. */
    String lastSelectedId();

    void setLastSelectedId(String lastSelectedId);

    /** @return ticks remaining to idle after a completed craft before sourcing the next recipe. */
    int craftDelay();

    void setCraftDelay(int craftDelay);

    // --- recipe-script progress (per-machine, resets on card eject/swap) ---

    /**
     * The per-recipe made-counts for the currently-inserted recipe card: a map of {@code recipeId -> made}
     * (how many of each scripted recipe this machine has produced for the inserted card). PROGRESS lives on
     * the MACHINE (not the immutable card); it resets when the card is ejected or swapped, and persists while
     * the same card stays inserted.
     *
     * @return the live progress map; never null (empty when idle/unscripted).
     */
    Map<String, Integer> scriptProgress();

    /**
     * Increments the made-count for {@code recipeId} by one ({@code made[recipeId] += 1}), inserting a
     * starting count of zero first when the id is absent.
     */
    void incrementScriptProgress(String recipeId);

    /** Empties the progress map. Called on card eject/swap so a new card starts fresh. */
    void clearScriptProgress();

    /**
     * @return the signature of the recipe card last seen by the engine (empty string when none). The engine
     *     compares this to the current card's signature to detect a change and reset progress (Task 4); this
     *     accessor only STORES it.
     */
    String lastCardSig();

    /** Records the signature of the recipe card last seen by the engine. A null sig is stored as "". */
    void setLastCardSig(String sig);
}
