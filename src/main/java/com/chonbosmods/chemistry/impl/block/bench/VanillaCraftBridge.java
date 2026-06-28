package com.chonbosmods.chemistry.impl.block.bench;

import com.hypixel.hytale.builtin.crafting.CraftingPlugin;
import com.hypixel.hytale.builtin.crafting.component.CraftingManager;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.protocol.ItemResourceType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    /**
     * The resource-type id vanilla uses for a recipe's FUEL requirement (e.g. Apple Pie carries a
     * {@code {ResourceTypeId:"Fuel", Quantity:3}} alongside its real ingredients). Vanilla satisfies it
     * by matching+consuming ANY fuel-valued item (sticks, coal, planks) found in the container.
     */
    static final String FUEL_RESOURCE_TYPE = "Fuel";

    private VanillaCraftBridge() {
    }

    /** True iff {@code m} is a recipe's "Fuel" resource-type requirement (NOT a real ingredient). */
    private static boolean isFuel(MaterialQuantity m) {
        return m != null && FUEL_RESOURCE_TYPE.equals(m.getResourceTypeId());
    }

    /**
     * A copy of {@code materials} with every "Fuel" resource-type entry removed. CC machines burn ENERGY
     * as their fuel, so a recipe's fuel requirement must never pull/consume physical fuel items (sticks,
     * coal, planks). ONLY "Fuel" is stripped: other resource types (Meats, Vegetables, Rock, Wood_Trunk,
     * ...) are real ingredient CATEGORIES (a slot matching "any meat"/"any rock") and are kept.
     */
    static List<MaterialQuantity> withoutFuel(List<MaterialQuantity> materials) {
        List<MaterialQuantity> kept = new ArrayList<>(materials.size());
        for (MaterialQuantity m : materials) {
            if (!isFuel(m)) {
                kept.add(m);
            }
        }
        return kept;
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
     * The number of DISTINCT REAL input materials a recipe requires. Used to rank recipes: more
     * ingredients = a more "advanced" recipe to prefer over a basic one.
     *
     * <p>A recipe's "Fuel" resource-type requirement is EXCLUDED from the count: CC machines burn energy
     * (not physical fuel items), so fuel is never an ingredient we pull. Counting it would inflate the
     * priority (e.g. Apple Pie would rank as 4 ingredients instead of its real 3). See {@link #withoutFuel}.
     */
    public static int ingredientCount(CraftingRecipe r) {
        // signature: MaterialQuantity[] CraftingRecipe.getInput() (verified public on Server-0.5.3)
        MaterialQuantity[] in = r.getInput();
        if (in == null) {
            return 0;
        }
        int count = 0;
        for (MaterialQuantity m : in) {
            if (!isFuel(m)) {
                count++;
            }
        }
        return count;
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
     *
     * <p>The recipe's "Fuel" resource-type requirement is STRIPPED ({@link #withoutFuel}) before the
     * availability test: CC machines burn ENERGY as their fuel, so the availability of a recipe must
     * NEVER depend on a physical fuel item (sticks/coal/planks) being present in the network. Other
     * resource-type categories (Meats, Rock, Wood_Trunk, ...) are real ingredients and are kept.
     */
    public static boolean inputsPresent(CraftingRecipe r, ItemContainer input, int batch) {
        // signature: CraftingManager.getInputMaterials(CraftingRecipe, int tier) (static)
        //            : List<MaterialQuantity>
        List<MaterialQuantity> materials = withoutFuel(
            CraftingManager.getInputMaterials(r, batch)); // batch=1 => one recipe set (NOT the machine tier; *0 zeroes qty)
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
     *
     * <p>The recipe's "Fuel" resource-type requirement is STRIPPED ({@link #withoutFuel}) before the
     * consume: CC machines burn ENERGY as their fuel, so a craft must NEVER consume a physical fuel item
     * (sticks/coal/planks) from the network (vanilla would otherwise match+drain any fuel-valued item).
     * Other resource-type categories (Meats, Rock, Wood_Trunk, ...) are real ingredients and are consumed.
     */
    public static boolean consumeInputs(CraftingRecipe r, ItemContainer input, int batch) {
        // signature: CraftingManager.getInputMaterials(CraftingRecipe, int tier) (static)
        //            : List<MaterialQuantity>
        List<MaterialQuantity> materials = withoutFuel(
            CraftingManager.getInputMaterials(r, batch)); // batch=1 => one recipe set (NOT the machine tier; *0 zeroes qty)
        // signature: ItemContainer.removeMaterials(List<MaterialQuantity>, boolean matchExactType,
        //            boolean forbidOverRemoval, boolean sendUpdate)
        //            : ListTransaction<MaterialTransaction>   [atomic: no-op on failure]
        //            then ListTransaction.succeeded() : boolean.
        // forbidOverRemoval=FALSE (see inputsPresent): consume ONE recipe set, leaving the buffer surplus.
        // Vanilla's player path uses true (exact-fit grid); an autonomous machine removes from a stack.
        return input.removeMaterials(materials, true, false, true).succeeded();
    }

    /**
     * The recipe's REAL input materials (Fuel stripped) at a single recipe set, for DISPLAY. Pure: it
     * reads {@code getInputMaterials(r, 1)} (falling back to {@code r.getInput()}) and strips "Fuel"
     * (CC machines burn energy : fuel is never a shown ingredient), returning the surviving
     * {@link MaterialQuantity}s in order. Split out from {@link #displayInputs} so the Fuel-strip /
     * material-iteration logic is unit-testable without resolving any {@code ItemStack} (which would
     * hit the AssetStore and NPE in a plain unit JVM).
     */
    static List<MaterialQuantity> displayInputMaterials(CraftingRecipe r) {
        List<MaterialQuantity> raw;
        List<MaterialQuantity> fromManager = CraftingManager.getInputMaterials(r, 1);
        if (fromManager != null && !fromManager.isEmpty()) {
            raw = fromManager;
        } else {
            MaterialQuantity[] in = r.getInput();
            raw = in == null ? List.of() : List.of(in);
        }
        return withoutFuel(raw);
    }

    /**
     * The item id used as a last-resort placeholder for a resource-type ("any &lt;resource&gt;") ingredient
     * whose category resolves to NO representative item. A non-empty stack is rendered so the slot still
     * APPEARS (the requirement is never silently dropped). Chosen as a stable, always-registered vanilla
     * item; if even this is absent the slot is dropped (it can never resolve to an icon anyway).
     */
    static final String RESOURCE_FALLBACK_ITEM_ID = "Plant_Fruit_Apple";

    /**
     * A display spec for one ingredient slot: the item id whose icon to show + the quantity to overlay.
     * A pure carrier (no AssetStore), so {@link #displayFor} is unit-testable. {@code itemId == null}
     * means "render nothing for this material" (it could not be resolved even to a fallback).
     */
    record InputDisplay(String itemId, int quantity) {
    }

    /**
     * Resolve ONE {@link MaterialQuantity} to its display spec, PURELY (no AssetStore):
     * <ul>
     *   <li>an ITEMED entry ({@code getItemId()} non-blank) -&gt; that item id at its quantity;</li>
     *   <li>a RESOURCE-TYPE / category entry ({@code getResourceTypeId()} non-blank, e.g. "Meats",
     *       "Rubble") -&gt; {@code resourceRep.apply(resourceTypeId)} (a representative item id for that
     *       category); if that yields null/blank, the {@code fallbackItemId} so the slot still SHOWS
     *       (the "any &lt;resource&gt;" requirement is never silently dropped);</li>
     *   <li>neither -&gt; {@code null} (nothing to render).</li>
     * </ul>
     * Split out from {@link #displayInputs} so the resource-handling branch is testable with a fake
     * resolver (the real resolver, {@link #representativeItemIdForResource}, scans the {@link Item}
     * AssetStore and is in-game-only).
     */
    static InputDisplay displayFor(MaterialQuantity m, Function<String, String> resourceRep, String fallbackItemId) {
        if (m == null) {
            return new InputDisplay(null, 0);
        }
        int qty = Math.max(1, m.getQuantity());
        String itemId = m.getItemId();
        if (itemId != null && !itemId.isBlank()) {
            return new InputDisplay(itemId, qty);
        }
        String resourceTypeId = m.getResourceTypeId();
        if (resourceTypeId != null && !resourceTypeId.isBlank()) {
            String rep = resourceRep == null ? null : resourceRep.apply(resourceTypeId);
            if (rep != null && !rep.isBlank()) {
                return new InputDisplay(rep, qty);
            }
            // No representative resolved : fall back to a generic item so the slot still appears.
            if (fallbackItemId != null && !fallbackItemId.isBlank()) {
                return new InputDisplay(fallbackItemId, qty);
            }
        }
        return new InputDisplay(null, qty);
    }

    /**
     * The first registered {@link Item} that belongs to the resource type {@code resourceTypeId} (its
     * {@code getResourceTypes()} contains a matching {@link ItemResourceType#id}) : a representative icon
     * for an "any &lt;resource&gt;" ingredient slot. Scans the live {@link Item} AssetStore, so it is
     * in-game-only (the AssetStore is empty / NPEs in a plain unit JVM); returns {@code null} when nothing
     * matches or the registry is unavailable (guarded). Matches vanilla's
     * {@code CraftingManager.matches} resource test (item.getResourceTypes() contains the id).
     */
    static String representativeItemIdForResource(String resourceTypeId) {
        if (resourceTypeId == null || resourceTypeId.isBlank()) {
            return null;
        }
        try {
            Map<String, Item> items = Item.getAssetMap().getAssetMap();
            if (items == null) {
                return null;
            }
            for (Map.Entry<String, Item> entry : items.entrySet()) {
                Item item = entry.getValue();
                if (item == null) {
                    continue;
                }
                ItemResourceType[] types = item.getResourceTypes();
                if (types == null) {
                    continue;
                }
                for (ItemResourceType t : types) {
                    if (t != null && resourceTypeId.equals(t.id)) {
                        String id = item.getId();
                        if (id != null && !id.isBlank()) {
                            return id;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // registry unavailable / not ready : no representative
        }
        return null;
    }

    /**
     * The recipe's INPUT ingredients as display {@link ItemStack}s, Fuel-stripped (CC machines burn
     * ENERGY : fuel is never shown). For each surviving {@link MaterialQuantity} (via {@link #displayFor}):
     * an itemed entry becomes {@code new ItemStack(itemId, quantity)}; a resource-type / category entry
     * (Meats, Rubble, Rock, ...) becomes a {@link ItemStack} of a REPRESENTATIVE item of that category
     * (the first registered item in it, {@link #representativeItemIdForResource}), or, when none resolves,
     * a {@link #RESOURCE_FALLBACK_ITEM_ID} placeholder so the slot still APPEARS (the "any &lt;resource&gt;"
     * requirement is never silently dropped). Used to populate {@code #IngredientGrid}. NOT unit-tested
     * ({@code new ItemStack} + the {@link Item} AssetStore scan are in-game-only) : the pure layers are
     * {@link #displayInputMaterials} and {@link #displayFor}.
     */
    public static List<ItemStack> displayInputs(CraftingRecipe r) {
        List<ItemStack> out = new ArrayList<>();
        for (MaterialQuantity m : displayInputMaterials(r)) {
            InputDisplay spec = displayFor(m, VanillaCraftBridge::representativeItemIdForResource,
                RESOURCE_FALLBACK_ITEM_ID);
            if (spec.itemId() == null || spec.itemId().isBlank()) {
                continue;
            }
            ItemStack stack = new ItemStack(spec.itemId(), Math.max(1, spec.quantity()));
            if (!ItemStack.isEmpty(stack)) {
                out.add(stack);
            }
        }
        return out;
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
