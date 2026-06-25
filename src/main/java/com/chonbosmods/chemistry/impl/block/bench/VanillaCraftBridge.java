package com.chonbosmods.chemistry.impl.block.bench;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

import java.util.List;

/**
 * The SOLE chokepoint into the server-internal vanilla CRAFTING API
 * ({@code com.hypixel.hytale.builtin.crafting.*} + the inventory {@code ItemContainer} material
 * helpers) that the autonomous Forge drives.
 *
 * <p>Unlike the Smelter, vanilla crafting has NO autonomous block: a furnace bench owns a
 * {@code ProcessingBenchBlock} ECS component that the engine ticks, but the crafting bench's
 * {@code CraftingManager.craftItem(...)} path is player-driven and needs a live player window. The
 * Forge instead drives crafting itself off our own tick, calling vanilla's STATIC recipe/material
 * helpers directly. This class isolates every one of those internal calls so a Hytale update that
 * shifts an internal signature breaks only this file (guarded by a devServer smoke test), mirroring
 * the rationale of its sibling {@link VanillaBenchBridge}.
 *
 * <h2>Verified engine shapes (Server-0.5.3, confirmed via {@code javap -c -p})</h2>
 * Several classes live in DIFFERENT packages than the design sketch implied; the verified ones:
 * <ul>
 *   <li>{@code CraftingRecipe} is {@code com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe}
 *       (an asset config, NOT {@code builtin.crafting}). Its {@code getInput()} / {@code getOutputs()}
 *       return raw {@link MaterialQuantity}[] arrays; the player/inventory-aware shaping lives on
 *       {@code CraftingManager} statics instead.</li>
 *   <li>{@code CraftingManager} is {@code com.hypixel.hytale.builtin.crafting.component.CraftingManager}
 *       and DOES expose the static {@code getInputMaterials(recipe, tier)} and
 *       {@code getOutputItemStacks(recipe)} helpers (the latter also has a {@code (recipe, tier)} form).</li>
 * </ul>
 *
 * <h2>Match / consume semantics (the two-boolean question, RESOLVED)</h2>
 * The design sketch proposed {@code getSlotMaterialsToRemove(materials, simulate, flag)} for both the
 * present-check and the consume. Decompiling the actual vanilla consume caller
 * ({@code CraftingManager.removeInputFromInventory(ItemContainer, CraftingRecipe, int)}) showed it does
 * NOT do that manual accounting: it calls the higher-level
 * {@code ItemContainer.removeMaterials(getInputMaterials(recipe, tier), true, true, true).succeeded()}.
 * This bridge follows that exact path. The lower-level {@code getSlotMaterialsToRemove}/
 * {@code canRemoveMaterials} pair share their two trailing booleans, whose meaning was confirmed from
 * the bytecode of {@code lambda$getSlotMaterialsToRemove$0} and {@code lambda$canRemoveMaterials$0}:
 * <ul>
 *   <li><b>matchExactType</b> (first boolean) — forwarded as the final arg of
 *       {@code InternalContainerUtilMaterial.getTestRemoveMaterialFromItems(container, mat, qty, b)} and
 *       on into {@code testRemoveItemStackSlotFromItems(..., BiPredicate)} where the predicate is
 *       {@code ItemStack.isEquivalentType}. It governs how strictly a slot's stack must match the
 *       material (item-id / metadata equivalence). Vanilla crafting passes {@code true}.</li>
 *   <li><b>forbidOverRemoval</b> (second boolean) — the exact-fit guard: when {@code true}, a material
 *       whose {@code quantityRemaining < 0} (slots collectively hold MORE than needed but couldn't be
 *       partitioned to the exact amount) ALSO fails the check, not just {@code > 0} (not enough). The
 *       convenience {@code canRemoveMaterials(List)} passes {@code (true, true)}.</li>
 * </ul>
 * Both the present-check ({@link #inputsPresent}) and the consume ({@link #consumeInputs}) use
 * {@code matchExactType=true} but {@code forbidOverRemoval=FALSE} — NOT vanilla crafting's {@code true}.
 * Vanilla's {@code true} is the player-grid exact-fit guard (fail if the container holds MORE than exactly
 * one recipe's worth); an autonomous, pipe-fed machine's buffer always carries surplus, so {@code false}
 * ("enough or more") is the correct buffer semantics. The two agree on what "satisfiable" means.
 *
 * <h2>Atomicity</h2>
 * {@code removeMaterials(...)} delegates to {@code InternalContainerUtilMaterial.internal_removeMaterials},
 * whose bytecode runs the full {@code testRemoveMaterialFromItems} loop over EVERY material FIRST and
 * returns a failed (empty) {@link com.hypixel.hytale.server.core.inventory.transaction.ListTransaction}
 * without mutating if ANY material is unsatisfiable; only on full success does it commit the slot
 * removals. So {@code removeMaterials(...).succeeded()} is all-or-nothing: a {@code false} return leaves
 * the container untouched. {@link #consumeInputs} relies on this — it never partially mutates.
 *
 * <h2>Multi-ingredient / quantity accounting (Task 3 note)</h2>
 * A separately-planned "manual multiset ingredient-match helper" is NOT needed: the test loop inside
 * {@code internal_removeMaterials} iterates per material across all slots and tracks
 * {@code quantityRemaining}, so multi-ingredient recipes and stacked quantities are accounted for by
 * vanilla. Likewise {@code canAddItemStacks} handles output-side packing/backpressure. This bridge does
 * not reimplement any of that.
 *
 * <p>See {@code docs/design.md} (Forge / CC Forge plan, Task 2) for the one-bridge rationale.
 */
public final class VanillaCraftBridge {

    private VanillaCraftBridge() {
    }

    /**
     * Every recipe registered for the given vanilla bench (e.g. {@link BenchType#Crafting}) under the
     * given bench id — the Forge's candidate recipe pool.
     */
    public static List<CraftingRecipe> benchRecipes(BenchType type, String benchId) {
        // signature: CraftingPlugin.getBenchRecipes(BenchType, String benchId) (static)
        //            : List<CraftingRecipe>   [CraftingRecipe =
        //            com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe]
        return CraftingPlugin.getBenchRecipes(type, benchId);
    }

    /**
     * The number of DISTINCT input materials a recipe requires (its {@code getInput()} array length). Used
     * to rank recipes: more ingredients = a more "advanced" recipe to prefer over a basic one.
     */
    public static int ingredientCount(CraftingRecipe r) {
        // signature: MaterialQuantity[] CraftingRecipe.getInput() (verified public on Server-0.5.3)
        MaterialQuantity[] in = r.getInput();
        return in == null ? 0 : in.length;
    }

    /** The recipe's stable asset id. */
    public static String recipeId(CraftingRecipe r) {
        // signature: String CraftingRecipe.getId()
        return r.getId();
    }

    /** The recipe's processing/cook time in seconds (vanilla {@code CraftingRecipe.getTimeSeconds()}). A
     *  crafting recipe with no timed processing returns 0; the caller supplies a default. */
    public static float recipeTimeSeconds(CraftingRecipe r) {
        return r.getTimeSeconds();  // verified public float on CraftingRecipe (Server-0.5.3)
    }

    /**
     * True iff every input material of {@code r} (at {@code tier}) can currently be sourced from
     * {@code input}, WITHOUT mutating it. Mirrors vanilla's pre-craft availability test.
     *
     * <p>Uses {@code canRemoveMaterials(materials, matchExactType=true, forbidOverRemoval=FALSE)}. We pass
     * {@code forbidOverRemoval=false} (NOT vanilla crafting's {@code true}): vanilla's {@code true} is the
     * player-GRID exact-fit guard — it FAILS when the container holds MORE than exactly one recipe's worth
     * (the internal test returns {@code needed - available < 0}). An autonomous, pipe-fed machine's buffer
     * always holds surplus (a stack of ingredients), so {@code true} would make every recipe permanently
     * un-craftable. {@code false} accepts "enough or more" (still fails when there is not enough), which is
     * the correct semantics for a buffer. Agrees with {@link #consumeInputs}. An empty material list (a
     * recipe with no inputs) trivially returns {@code true}.
     */
    public static boolean inputsPresent(CraftingRecipe r, ItemContainer input, int batch) {
        // signature: CraftingManager.getInputMaterials(CraftingRecipe, int tier) (static)
        //            : List<MaterialQuantity>
        List<MaterialQuantity> materials = CraftingManager.getInputMaterials(r, batch); // batch=1 => one recipe set (NOT the machine tier; *0 zeroes qty)
        // signature: ItemContainer.canRemoveMaterials(List<MaterialQuantity>, boolean matchExactType,
        //            boolean forbidOverRemoval) : boolean  [pure test, no mutation]
        return input.canRemoveMaterials(materials, true, false);
    }

    /**
     * Atomically remove ONE ingredient set of {@code r} (at {@code tier}) from {@code input}. Returns
     * {@code false} if the inputs are not fully satisfiable, in which case the container is left
     * UNTOUCHED (vanilla's {@code internal_removeMaterials} tests all materials before committing — see
     * class javadoc "Atomicity"). This is the exact path vanilla's
     * {@code CraftingManager.removeInputFromInventory(ItemContainer, CraftingRecipe, int)} uses.
     */
    public static boolean consumeInputs(CraftingRecipe r, ItemContainer input, int batch) {
        // signature: CraftingManager.getInputMaterials(CraftingRecipe, int tier) (static)
        //            : List<MaterialQuantity>
        List<MaterialQuantity> materials = CraftingManager.getInputMaterials(r, batch); // batch=1 => one recipe set (NOT the machine tier; *0 zeroes qty)
        // signature: ItemContainer.removeMaterials(List<MaterialQuantity>, boolean matchExactType,
        //            boolean forbidOverRemoval, boolean sendUpdate)
        //            : ListTransaction<MaterialTransaction>   [atomic: no-op on failure]
        //            then ListTransaction.succeeded() : boolean.
        // forbidOverRemoval=FALSE (see inputsPresent): consume ONE recipe set, leaving the buffer surplus.
        // Vanilla's player path uses true (exact-fit grid); an autonomous machine removes from a stack.
        return input.removeMaterials(materials, true, false, true).succeeded();
    }

    /**
     * The finished-goods {@link ItemStack}s a single craft of {@code r} produces (the recipe's primary
     * output plus any secondary outputs), expanded from the recipe's output materials.
     */
    public static List<ItemStack> outputs(CraftingRecipe r) {
        // signature: CraftingManager.getOutputItemStacks(CraftingRecipe) (static) : List<ItemStack>
        return CraftingManager.getOutputItemStacks(r);
    }

    /**
     * Backpressure check: whether {@code output} has room to accept {@code stacks} (used to stall the
     * Forge before consuming inputs it could not bank). {@code canAddItemStacks} does the full packing /
     * stack-merge accounting, so no manual multiset math is needed here.
     */
    public static boolean canProduce(ItemContainer output, List<ItemStack> stacks) {
        // signature: ItemContainer.canAddItemStacks(List<ItemStack>) : boolean  [pure test]
        return output.canAddItemStacks(stacks);
    }

    /**
     * Deposit the produced {@code stacks} into {@code output}. Caller should gate on {@link #canProduce}
     * first; the returned transaction is intentionally discarded here (the bridge exposes the
     * deposit as a fire-and-forget mutation, matching {@link VanillaBenchBridge}'s thin-delegation style).
     */
    public static void addOutputs(ItemContainer output, List<ItemStack> stacks) {
        // signature: ItemContainer.addItemStacks(List<ItemStack>)
        //            : ListTransaction<ItemStackTransaction>  [return discarded]
        output.addItemStacks(stacks);
    }
}
