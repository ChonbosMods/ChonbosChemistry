package com.chonbosmods.chemistry.impl.block;

/**
 * Pure energy-to-time math for the Smelter's per-tick driver.
 *
 * <p>v1 model (1x cap, no overclock): the machine may run at most real-time, capped further by
 * the energy it can afford. Given the energy currently {@code stored} and a per-second draw
 * {@code costPerSec} (the placeholder {@code SMELTER_DRAW} wired in by the tick system), this
 * computes how much simulated time the machine can run this tick and how much energy that costs.
 *
 * <p>Energy amounts are {@code long} (matching {@link EnergyBuffer}); simulated time is {@code double}.
 * This class is intentionally engine-free and side-effect-free so it can be unit-tested in isolation.
 */
public final class SmelterEnergy {

    private SmelterEnergy() {
    }

    /**
     * How much simulated time the machine can afford to run this tick.
     *
     * <p>With {@code costPerSec <= 0} there is no energy gate, so the full {@code realDt} runs.
     * Otherwise the time is the lesser of {@code realDt} (the 1x real-time cap: no overclock) and
     * {@code stored / costPerSec} (the energy-limited budget). Negative {@code stored} is treated as
     * zero, so an empty or invalid buffer simply freezes the machine (returns 0).
     *
     * @param stored     energy currently available, in energy units
     * @param costPerSec per-second energy draw while running (placeholder {@code SMELTER_DRAW})
     * @param realDt     the real elapsed time this tick, in seconds
     * @return the affordable run time this tick, in seconds (never above {@code realDt}, never below 0)
     */
    public static double affordableDt(long stored, long costPerSec, double realDt) {
        if (realDt <= 0) {
            return 0.0;
        }
        if (costPerSec <= 0) {
            return realDt; // no cost gate: run the full real dt
        }
        long usable = Math.max(stored, 0L); // negative/empty buffer -> nothing affordable
        double energyBudget = (double) usable / (double) costPerSec;
        return Math.min(realDt, energyBudget);
    }

    /**
     * The energy drained for {@code dt} seconds of work at {@code costPerSec} per second,
     * rounded to the nearest energy unit. Returns 0 when {@code costPerSec} is 0 (no cost) or
     * when {@code dt} is non-positive (no work done).
     *
     * @param dt         simulated time run this tick, in seconds (typically the {@link #affordableDt} result)
     * @param costPerSec per-second energy draw while running (placeholder {@code SMELTER_DRAW})
     * @return the energy to remove from the buffer, in energy units (>= 0)
     */
    public static long drainFor(double dt, long costPerSec) {
        if (dt <= 0) {
            return 0L;
        }
        return Math.round(dt * costPerSec); // 0 when costPerSec == 0
    }
}
