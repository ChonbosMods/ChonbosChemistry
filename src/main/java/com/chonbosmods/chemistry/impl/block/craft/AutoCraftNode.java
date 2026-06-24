package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.impl.block.PoweredMachineNode;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;

/**
 * The state contract an autonomous-crafting machine exposes to the shared craft engine ({@code
 * AutoCraftEngine}, added next). Every accessor here already exists on {@link ForgeCraftState}; future
 * crafting machines (Cooker, Outfitter) implement the same contract so the engine drives them uniformly.
 *
 * <p>Extends {@link PoweredMachineNode}, the shared powered-machine contract (energy + ports + on/off),
 * so the energy/ports/enabled accessors are inherited rather than redeclared here.
 */
public interface AutoCraftNode extends PoweredMachineNode {

    /** @return the held ingredients pulled for the active craft (empty when idle). */
    SimpleItemContainer held();

    /** @return the result container. */
    SimpleItemContainer output();

    /** @return the inserted recipe card, or null if no card is loaded. */
    ItemStack card();

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
}
