package com.chonbosmods.chemistry.impl.block.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.hypixel.hytale.protocol.BenchRequirement;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link VanillaCraftBridge}'s fuel-stripping logic. No engine/world/AssetStore
 * is needed: {@link MaterialQuantity} is constructed via its public ctor (which only sets fields)
 * and only its public getters are read.
 */
class VanillaCraftBridgeTest {

    // item ingredient: e.g. 3 apples
    private static MaterialQuantity item(String itemId, int qty) {
        return new MaterialQuantity(itemId, null, null, qty, null);
    }

    // resource-type ingredient: a CATEGORY slot (e.g. "any meat") that must be KEPT
    private static MaterialQuantity resource(String resourceTypeId, int qty) {
        return new MaterialQuantity(null, resourceTypeId, null, qty, null);
    }

    // a "Fuel" resource requirement: must be STRIPPED (CC machines burn energy)
    private static MaterialQuantity fuel(int qty) {
        return new MaterialQuantity(null, "Fuel", null, qty, null);
    }

    /**
     * Sanity: {@link MaterialQuantity} is constructable in a plain unit JVM (the ctor only sets
     * fields; it does NOT resolve against the AssetStore the way {@code ItemStack}'s ctor does).
     */
    @Test
    void materialQuantityIsConstructable() {
        assertEquals("Fuel", new MaterialQuantity("X", "Fuel", null, 1, null).getResourceTypeId());
        assertEquals("X", new MaterialQuantity("X", null, null, 1, null).getItemId());
    }

    @Test
    void withoutFuel_dropsOnlyFuel_keepsItemsAndCategories() {
        MaterialQuantity apple = item("Plant_Fruit_Apple", 3);
        MaterialQuantity dough = item("Ingredient_Dough", 1);
        MaterialQuantity meats = resource("Meats", 1);
        List<MaterialQuantity> result =
            VanillaCraftBridge.withoutFuel(List.of(apple, dough, fuel(3), meats));

        assertEquals(3, result.size());
        for (MaterialQuantity m : result) {
            assertFalse("Fuel".equals(m.getResourceTypeId()), "fuel must be stripped");
        }
        assertTrue(result.contains(meats), "the Meats resource-type category must survive");
        assertTrue(result.contains(apple));
        assertTrue(result.contains(dough));
    }

    @Test
    void withoutFuel_noFuel_unchanged() {
        MaterialQuantity apple = item("Plant_Fruit_Apple", 3);
        MaterialQuantity meats = resource("Meats", 1);
        List<MaterialQuantity> in = List.of(apple, meats);
        List<MaterialQuantity> result = VanillaCraftBridge.withoutFuel(in);

        assertEquals(2, result.size());
        assertSame(apple, result.get(0));
        assertSame(meats, result.get(1));
    }

    @Test
    void withoutFuel_onlyFuel_empty() {
        List<MaterialQuantity> result = VanillaCraftBridge.withoutFuel(List.of(fuel(3)));
        assertTrue(result.isEmpty());
    }

    @Test
    void withoutFuel_empty_empty() {
        assertTrue(VanillaCraftBridge.withoutFuel(List.of()).isEmpty());
    }

    /**
     * {@code displayInputMaterials} is the pure (no-ItemStack) layer behind {@code displayInputs}: it
     * reads {@code getInputMaterials(r, 1)} (a pure copy of the recipe's {@code getInput()} array : it
     * does NOT touch the AssetStore, verified from bytecode) and strips Fuel, keeping both itemed
     * ingredients and resource-type CATEGORIES in order. The {@code new ItemStack}/{@code toItemStack}
     * resolution that {@code displayInputs} layers on top is in-game-only (AssetStore-bound).
     */
    @Test
    void displayInputMaterials_stripsFuel_keepsItemsAndCategories() {
        MaterialQuantity dough = item("Ingredient_Dough", 1);
        MaterialQuantity meats = resource("Meats", 1);
        // Public 8-arg CraftingRecipe ctor: (input, output, outputs[], int, requirements[], time, bool, int).
        CraftingRecipe r = new CraftingRecipe(
            new MaterialQuantity[] {dough, fuel(3), meats},
            null, new MaterialQuantity[0], 0, new BenchRequirement[0], 0f, false, 0);

        List<MaterialQuantity> result = VanillaCraftBridge.displayInputMaterials(r);

        assertEquals(2, result.size());
        for (MaterialQuantity m : result) {
            assertFalse("Fuel".equals(m.getResourceTypeId()), "fuel must be stripped");
        }
        // getInputMaterials returns copies (qty * tier=1), so compare by value not identity, in order.
        assertEquals("Ingredient_Dough", result.get(0).getItemId());
        assertEquals(1, result.get(0).getQuantity());
        assertEquals("Meats", result.get(1).getResourceTypeId(),
            "resource-type category must survive in order");
    }

    // ----------------------------------------------------------------------------------------------------
    // displayFor : the pure resource-handling branch (the AssetStore-backed resolver is in-game-only, so it
    // is supplied here as a fake Function<String,String>). Itemed entries pass through; resource-type
    // ("any <resource>") entries resolve to a representative or, failing that, the fallback : never dropped.
    // ----------------------------------------------------------------------------------------------------

    /** A fake resolver: "Meats" -> a representative meat item id; everything else unresolved. */
    private static final Function<String, String> FAKE_REP =
        id -> "Meats".equals(id) ? "Food_Beef_Raw" : null;

    @Test
    void displayFor_itemedEntry_passesThroughItemIdAndQuantity() {
        VanillaCraftBridge.InputDisplay spec =
            VanillaCraftBridge.displayFor(item("Ingredient_Dough", 4), FAKE_REP, "Plant_Fruit_Apple");
        assertEquals("Ingredient_Dough", spec.itemId());
        assertEquals(4, spec.quantity());
    }

    @Test
    void displayFor_resourceWithRepresentative_usesRepresentativeAtQuantity() {
        VanillaCraftBridge.InputDisplay spec =
            VanillaCraftBridge.displayFor(resource("Meats", 3), FAKE_REP, "Plant_Fruit_Apple");
        assertEquals("Food_Beef_Raw", spec.itemId(), "a resolved representative is shown");
        assertEquals(3, spec.quantity());
    }

    @Test
    void displayFor_resourceWithoutRepresentative_fallsBackButStillShows() {
        // "Rubble" has no representative from FAKE_REP : the fallback keeps the slot non-empty (not dropped).
        VanillaCraftBridge.InputDisplay spec =
            VanillaCraftBridge.displayFor(resource("Rubble", 4), FAKE_REP, "Plant_Fruit_Apple");
        assertEquals("Plant_Fruit_Apple", spec.itemId(), "unresolved resource falls back, never dropped");
        assertEquals(4, spec.quantity());
    }

    @Test
    void displayFor_resourceWithoutRepresentativeOrFallback_yieldsNoItem() {
        // No representative AND no fallback : nothing to render (itemId null), but quantity is still carried.
        VanillaCraftBridge.InputDisplay spec =
            VanillaCraftBridge.displayFor(resource("Rubble", 2), FAKE_REP, null);
        assertNull(spec.itemId());
    }

    @Test
    void displayFor_nullResolver_resourceFallsBack() {
        VanillaCraftBridge.InputDisplay spec =
            VanillaCraftBridge.displayFor(resource("Meats", 1), null, "Plant_Fruit_Apple");
        assertEquals("Plant_Fruit_Apple", spec.itemId(), "a null resolver still falls back, never dropped");
    }

    @Test
    void displayFor_nullMaterial_yieldsNoItem() {
        VanillaCraftBridge.InputDisplay spec =
            VanillaCraftBridge.displayFor(null, FAKE_REP, "Plant_Fruit_Apple");
        assertNull(spec.itemId(), "a null material renders nothing");
    }

    // ----------------------------------------------------------------------------------------------------
    // displayViewFor : the pure ingredient-VIEW mapper (ISSUE 2). Unlike displayFor it does NOT collapse a
    // resource-type into a representative item : it PRESERVES the resource-type id so the panel can render the
    // native "any <resource>" icon. Itemed entries -> item view; resource-type entries -> resource view.
    // ----------------------------------------------------------------------------------------------------

    @Test
    void displayViewFor_itemedEntry_isItemView() {
        VanillaCraftBridge.IngredientView v =
            VanillaCraftBridge.displayViewFor(item("Ingredient_Dough", 4));
        assertEquals("Ingredient_Dough", v.itemId());
        assertNull(v.resourceTypeId(), "an itemed entry carries no resource-type id");
        assertEquals(4, v.quantity());
        assertFalse(v.isResourceType(), "an itemed entry is not a resource-type category");
    }

    @Test
    void displayViewFor_resourceEntry_preservesResourceTypeId() {
        VanillaCraftBridge.IngredientView v =
            VanillaCraftBridge.displayViewFor(resource("Rubble", 4));
        assertNull(v.itemId(), "a resource-type entry carries no item id (NOT collapsed to a representative)");
        assertEquals("Rubble", v.resourceTypeId(), "the resource-type id must be preserved for the native icon");
        assertEquals(4, v.quantity());
        assertTrue(v.isResourceType());
    }

    @Test
    void displayViewFor_nullMaterial_isNull() {
        assertNull(VanillaCraftBridge.displayViewFor(null), "a null material yields no view");
    }

    // ------------------------------------------------------------------------------------------------
    // bundledResourceIconPath : map an asset-root ResourceType icon path to the mod-bundled ../CC/ path
    // (ISSUE 2 fix : the raw asset path does NOT resolve in a custom-UI PatchStyle ; it resolves relative
    // to the .ui file, so we ship the icons under resources/Common/UI/Custom/CC/ResourceTypes/).
    // ------------------------------------------------------------------------------------------------

    @Test
    void bundledResourceIconPath_assetRootPath_remappedToBundledCcPath() {
        assertEquals("../CC/ResourceTypes/Any_Meat.png",
            VanillaCraftBridge.bundledResourceIconPath("Icons/ResourceTypes/Any_Meat.png"),
            "the asset-root icon path must be remapped to the mod-bundled ../CC/ResourceTypes/ form");
    }

    @Test
    void bundledResourceIconPath_bareFilename_isPrefixed() {
        assertEquals("../CC/ResourceTypes/Any_Rock.png",
            VanillaCraftBridge.bundledResourceIconPath("Any_Rock.png"),
            "a bare basename (no directory) is still prefixed with the bundled path");
    }

    @Test
    void bundledResourceIconPath_blankOrNull_isNull() {
        assertNull(VanillaCraftBridge.bundledResourceIconPath(null));
        assertNull(VanillaCraftBridge.bundledResourceIconPath(""));
        assertNull(VanillaCraftBridge.bundledResourceIconPath("   "));
    }
}
