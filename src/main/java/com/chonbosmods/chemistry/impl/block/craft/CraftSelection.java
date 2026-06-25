package com.chonbosmods.chemistry.impl.block.craft;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, engine-free recipe selection for autonomous-craft machines (Forge, etc.). See
 * {@code docs/plans/2026-06-23-forge-autonomous-craft-design.md} (selection model, LOCKED).
 */
public final class CraftSelection {

    private CraftSelection() {
    }

    /**
     * Even round-robin over the currently-craftable recipes: returns the first id in {@code stableOrder}
     * strictly after {@code lastSelectedId}'s position (wrapping) that is in {@code craftableIds}. Skips
     * non-craftable recipes (never idles while something is makeable) and rotates evenly across same-input
     * collisions. Deterministic - no RNG, no value/priority.
     *
     * @param stableOrder    every bench recipe id in a stable, deterministic order
     * @param craftableIds   the subset currently makeable (card-allowed AND inputs present)
     * @param lastSelectedId the id chosen last tick (cursor), or null to start from the top
     * @return the chosen id, or null when {@code craftableIds} is empty (idle)
     */
    public static String selectNext(List<String> stableOrder, Set<String> craftableIds, String lastSelectedId) {
        int n = stableOrder.size();
        if (craftableIds.isEmpty() || n == 0) {
            return null;
        }
        // -1 when null / not in list -> scan from index 0. Guard the null case explicitly:
        // List.of(...) returns null-hostile immutable lists whose indexOf throws on a null query.
        int start = (lastSelectedId == null) ? -1 : stableOrder.indexOf(lastSelectedId);
        for (int step = 1; step <= n; step++) {
            String candidate = stableOrder.get((start + step) % n);
            if (craftableIds.contains(candidate)) {
                return candidate;
            }
        }
        return null; // craftableIds disjoint from stableOrder (defensive; shouldn't happen)
    }

    /**
     * The subset of {@code craftable} whose priority value (from {@code priority}) is the MAXIMUM among the
     * set: the "top tier" to prefer. Even rotation then runs within this subset (via {@link #selectNext}).
     * Empty in -&gt; empty out. An id missing from {@code priority} counts as priority 0.
     *
     * <p>This is how crafting machines prefer the most-ingredient (more "advanced") recipe over a basic one
     * while still even-rotating among equally-ranked recipes: the engine ranks craftable ids by their
     * distinct-ingredient count, keeps only the top tier here, then hands that tier to {@link #selectNext}.
     */
    public static Set<String> topByPriority(Set<String> craftable, Map<String, Integer> priority) {
        if (craftable.isEmpty()) {
            return Set.of();
        }
        int max = Integer.MIN_VALUE;
        for (String id : craftable) {
            max = Math.max(max, priority.getOrDefault(id, 0));
        }
        Set<String> top = new HashSet<>();
        for (String id : craftable) {
            if (priority.getOrDefault(id, 0) == max) {
                top.add(id);
            }
        }
        return top;
    }

    /** Card permission: a null allow-set (no card) permits everything; otherwise membership. */
    public static boolean allowed(String recipeId, Set<String> allowSet) {
        return allowSet == null || allowSet.contains(recipeId);
    }
}
