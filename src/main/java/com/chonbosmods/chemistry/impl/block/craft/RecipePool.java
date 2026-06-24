package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.impl.block.bench.VanillaCraftBridge;
import com.hypixel.hytale.protocol.BenchType;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nonnull;

/**
 * A deduped, deterministically-ordered pool of vanilla crafting recipes drawn from one or more benches:
 * a stable id order ({@link #stableOrder}) plus an id &rarr; recipe lookup ({@link #map}). The autonomous
 * craft engine ({@link AutoCraftEngine}) round-robins over {@code stableOrder} and resolves picks through
 * {@code map}.
 *
 * <p>Built once per machine (load-time-fixed: the recipe registry does not change after load) via
 * {@link #union}, then cached by the machine's {@code Spec}.
 */
public record RecipePool(List<String> stableOrder, Map<String, CraftingRecipe> map) {

    /** One bench to union into a pool: its {@link BenchType} + the bench asset id. */
    public record BenchRef(BenchType type, String benchId) {
    }

    /**
     * Build a deduped, deterministically-ordered pool from the given benches. A {@code TreeMap} keyed by
     * recipe id gives the dedup + a deterministic natural-String order (last writer wins on a dup id:
     * recipes with the same id across benches are the same asset); a {@code LinkedHashMap} preserves that
     * sorted iteration order for the id list. Guards against null/empty benches + recipes.
     */
    public static RecipePool union(@Nonnull List<BenchRef> benches) {
        // TreeMap: dedup by id with a deterministic natural String order. Last writer wins on a dup id
        // (recipes with the same id across benches are the same asset).
        TreeMap<String, CraftingRecipe> byId = new TreeMap<>();
        for (BenchRef bench : benches) {
            addBench(byId, bench.type(), bench.benchId());
        }
        // LinkedHashMap preserves the TreeMap's sorted iteration order for the id list.
        Map<String, CraftingRecipe> map = new LinkedHashMap<>(byId);
        List<String> order = List.copyOf(byId.keySet());
        return new RecipePool(order, map);
    }

    /** Add every recipe of one bench into {@code byId}, keyed by recipe id. Guards against null/empty. */
    private static void addBench(@Nonnull Map<String, CraftingRecipe> byId, @Nonnull BenchType type,
            @Nonnull String benchId) {
        List<CraftingRecipe> recipes = VanillaCraftBridge.benchRecipes(type, benchId);
        if (recipes == null) {
            return;
        }
        for (CraftingRecipe r : recipes) {
            if (r == null) {
                continue;
            }
            String id = VanillaCraftBridge.recipeId(r);
            if (id != null) {
                byId.put(id, r);
            }
        }
    }
}
