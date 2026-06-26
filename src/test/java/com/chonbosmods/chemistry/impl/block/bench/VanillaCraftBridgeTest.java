package com.chonbosmods.chemistry.impl.block.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import java.util.List;
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
}
