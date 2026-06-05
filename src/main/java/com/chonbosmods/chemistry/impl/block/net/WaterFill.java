package com.chonbosmods.chemistry.impl.block.net;

import java.util.Arrays;

/**
 * Water-fill balance targets for storage balancing (2026-06-05 design): given the total stored
 * across a network's storage endpoints, every endpoint converges toward the same level L: equal
 * absolute amounts, capacity-blind. Capacity matters only as a clamp: an endpoint too small to hold
 * L sits full, and the remaining endpoints split what it could not take.
 *
 * <p>Integer floor semantics: {@code sum(targets) <= totalStored}, with the (sub-endpoint-count)
 * remainder implicitly staying on donors. That floor deadband is what makes balancing convergent and
 * churn-free: movement only happens while some donor is &ge;1 above target AND some recipient is
 * &ge;1 below, and internal moves never change the total, so the targets are stable and the system
 * reaches a fixpoint where nothing moves.
 *
 * <p>Pure JDK math: no Hytale types.
 */
public final class WaterFill {

    private WaterFill() {
    }

    /**
     * The per-endpoint balance targets.
     *
     * @param capacities  each storage endpoint's total capacity (not free space), all &ge; 0.
     * @param totalStored the summed stored amount across those endpoints (&le; sum of capacities).
     * @return targets aligned to {@code capacities} by index: {@code min(cap_i, L)} for the floor
     *     level L, never negative, never above capacity, summing to at most {@code totalStored}.
     */
    public static long[] targets(long[] capacities, long totalStored) {
        int n = capacities.length;
        long[] targets = new long[n];
        if (n == 0 || totalStored <= 0) {
            return targets;
        }

        // Walk capacities in ascending order. While the smallest remaining capacity fits under the
        // current even split, that endpoint clamps full and the rest re-split what is left.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Long.compare(capacities[a], capacities[b]));

        long remaining = totalStored;
        int unfilled = n;
        for (int k = 0; k < n; k++) {
            int idx = order[k];
            long evenShare = remaining / unfilled;
            if (capacities[idx] <= evenShare) {
                targets[idx] = capacities[idx]; // clamps full; the rest absorb its share
                remaining -= capacities[idx];
                unfilled--;
            } else {
                // Everyone left (sorted ascending, so all have cap > evenShare) takes the floor level.
                for (int m = k; m < n; m++) {
                    targets[order[m]] = evenShare;
                }
                break;
            }
        }
        return targets;
    }
}
