package com.chonbosmods.chemistry.impl.block.net;

/**
 * Max-min fair split of a shared buffer across multiple acceptors, mirroring Mekanism's
 * {@code SplitInfo} behavior: every acceptor receives an equal share, except acceptors that
 * cannot hold a full share take only what they can, and the leftover is redistributed (re-split)
 * across the acceptors that still have room.
 *
 * <p>Pure logic: no Hytale APIs, no state, deterministic and order-independent in the produced
 * multiset of allocations. Integer-division remainder units are handed out one-per-acceptor; the
 * starting acceptor is controlled by a {@code rotationOffset} so callers that allocate repeatedly
 * with a stable acceptor order can rotate WHICH acceptor receives the leftover ±1 units, keeping
 * equal consumers balanced over time. The default {@link #allocate(long, long[])} uses offset 0
 * (ascending index) for backwards-compatible determinism.
 */
public final class FairSplitDistributor {

    private FairSplitDistributor() {
    }

    /**
     * Allocates {@code amount} across {@code remainingCapacities} using max-min fairness.
     *
     * @param amount              total units to distribute (clamped to {@code >= 0})
     * @param remainingCapacities per-acceptor remaining room (negatives treated as 0)
     * @return an array the same length as the input where {@code result[i]} is the amount given to
     *     acceptor i. Guarantees {@code sum(result) == min(amount, sum(caps))} and
     *     {@code 0 <= result[i] <= caps[i]}.
     */
    public static long[] allocate(long amount, long[] remainingCapacities) {
        return allocate(amount, remainingCapacities, 0);
    }

    /**
     * Rotated variant of {@link #allocate(long, long[])}: identical max-min fair split, but the
     * integer-division remainder units are handed out starting at the {@code rotationOffset}-th
     * still-active acceptor (wrapping) instead of always the lowest index. This only changes WHICH
     * acceptors receive the leftover ±1 units; all invariants (sum, bounds, max-min fairness) are
     * preserved. Callers that allocate repeatedly with a stable acceptor order pass an incrementing
     * offset so equal acceptors receive equally over time rather than the first one always winning.
     *
     * @param amount              total units to distribute (clamped to {@code >= 0})
     * @param remainingCapacities per-acceptor remaining room (negatives treated as 0)
     * @param rotationOffset      index (modulo the active-acceptor count, non-negative) of the active
     *     acceptor that receives the first remainder unit
     * @return an array the same length as the input where {@code result[i]} is the amount given to
     *     acceptor i, with the same guarantees as {@link #allocate(long, long[])}.
     */
    public static long[] allocate(long amount, long[] remainingCapacities, int rotationOffset) {
        int n = remainingCapacities.length;
        long[] allocated = new long[n];
        if (n == 0 || amount <= 0) {
            return allocated;
        }

        // Normalize capacities: negatives -> 0. remaining[i] tracks room still available.
        long[] remaining = new long[n];
        for (int i = 0; i < n; i++) {
            remaining[i] = Math.max(0L, remainingCapacities[i]);
        }

        long pool = amount;

        // Main fair-share loop. Each iteration either saturates at least one acceptor (shrinking
        // the active set and re-splitting the freed pool) or, when every active acceptor can take
        // the full share, gives that share to all and exits to the remainder step. Both branches
        // make progress, so the loop terminates.
        while (true) {
            int activeCount = 0;
            for (int i = 0; i < n; i++) {
                if (remaining[i] > 0) {
                    activeCount++;
                }
            }
            if (activeCount == 0 || pool == 0) {
                return allocated;
            }

            long share = pool / activeCount;
            if (share == 0) {
                break; // pool < activeCount: hand out remainder one unit at a time.
            }

            boolean newlySaturated = false;
            for (int i = 0; i < n; i++) {
                if (remaining[i] > 0 && remaining[i] <= share) {
                    long give = remaining[i];
                    allocated[i] += give;
                    remaining[i] = 0;
                    pool -= give;
                    newlySaturated = true;
                }
            }

            if (!newlySaturated) {
                // Every active acceptor can hold the full share: give it to all, then remainder.
                for (int i = 0; i < n; i++) {
                    if (remaining[i] > 0) {
                        allocated[i] += share;
                        remaining[i] -= share;
                        pool -= share;
                    }
                }
                break;
            }
            // else: re-loop; saturating freed pool to re-split over the now-smaller active set.
        }

        // Remainder step: distribute the leftover pool (now < active count) one unit at a time to
        // acceptors that still have room, starting at the rotationOffset-th active acceptor and
        // wrapping in ascending index order, until the pool is exhausted.
        if (pool > 0) {
            // Collect still-active acceptor indices in ascending order.
            int[] active = new int[n];
            int activeCount = 0;
            for (int i = 0; i < n; i++) {
                if (remaining[i] > 0) {
                    active[activeCount++] = i;
                }
            }
            if (activeCount > 0) {
                // Normalize the offset to a non-negative starting slot within the active set.
                int start = Math.floorMod(rotationOffset, activeCount);
                for (int k = 0; k < activeCount && pool > 0; k++) {
                    int idx = active[(start + k) % activeCount];
                    allocated[idx] += 1;
                    remaining[idx] -= 1;
                    pool -= 1;
                }
            }
        }

        return allocated;
    }
}
