package com.chonbosmods.chemistry.impl.block.net;

/**
 * Provisional tier &rarr; (capacity, throughput) mapping for a single pipe segment. The exact curve
 * does not matter yet: it only needs to be monotonic in tier so the network aggregates (Σ capacity,
 * MIN throughput) behave. All numbers below are {@code [TUNE]} placeholders to be balanced later.
 */
public final class PipeTiers {

    // [TUNE] placeholder base values, tuned to a deliberate crawl so flow is watchable in-game.
    // Capacity scales 500 per tier step; throughput 5 per step (tier 0 = 500 cap / 5 throughput).
    private static final long BASE_CAPACITY = 500L;
    private static final int BASE_THROUGHPUT = 5;

    private PipeTiers() {
    }

    /**
     * Per-segment buffer capacity for a pipe of {@code tier}. [TUNE] {@code base 500 * (tier+1)};
     * negative tiers are clamped to tier 0 so the value stays positive and monotonic.
     */
    public static long capacityForTier(int tier) {
        int t = Math.max(0, tier);
        return BASE_CAPACITY * (t + 1L);
    }

    /**
     * Per-segment throughput (transfer rate) for a pipe of {@code tier}. [TUNE]
     * {@code base 5 * (tier+1)}; negative tiers are clamped to tier 0.
     */
    public static int throughputForTier(int tier) {
        int t = Math.max(0, tier);
        return (int) Math.min(Integer.MAX_VALUE, (long) BASE_THROUGHPUT * (t + 1L));
    }
}
