package com.chonbosmods.chemistry.impl.block.craft;

import java.util.List;
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

    /** Card permission: a null allow-set (no card) permits everything; otherwise membership. */
    public static boolean allowed(String recipeId, Set<String> allowSet) {
        return allowSet == null || allowSet.contains(recipeId);
    }
}
