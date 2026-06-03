package com.chonbosmods.chemistry.impl.block.net;

/**
 * Provisional tier &rarr; (capacity, throughput) mapping for a single pipe segment. The exact curve
 * does not matter yet: it only needs to be monotonic in tier so the network aggregates (Σ capacity,
 * MIN throughput) behave. All numbers below are {@code [TUNE]} placeholders to be balanced later.
 */
public final class PipeTiers {

    // [TUNE] placeholder base values. Capacity scales 1000 per tier step; throughput 100 per step.
    private static final long BASE_CAPACITY = 1000L;
    private static final int BASE_THROUGHPUT = 100;

    private PipeTiers() {
    }

    /**
     * Per-segment buffer capacity for a pipe of {@code tier}. [TUNE] {@code base 1000 * (tier+1)};
     * negative tiers are clamped to tier 0 so the value stays positive and monotonic.
     */
    public static long capacityForTier(int tier) {
        int t = Math.max(0, tier);
        return BASE_CAPACITY * (t + 1L);
    }

    /**
     * Per-segment throughput (transfer rate) for a pipe of {@code tier}. [TUNE]
     * {@code base 100 * (tier+1)}; negative tiers are clamped to tier 0.
     */
    public static int throughputForTier(int tier) {
        int t = Math.max(0, tier);
        return (int) Math.min(Integer.MAX_VALUE, (long) BASE_THROUGHPUT * (t + 1L));
    }
}
