package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SmelterEnergyTest {

    @Test
    void affordsFullDtWhenEnergyAmple() {
        // 1000 stored, 200/s, want 1.0s -> can afford the full 1.0s (costs 200, well under 1000)
        assertEquals(1.0, SmelterEnergy.affordableDt(1000, 200, 1.0), 1e-9);
    }

    @Test
    void clampsDtToStoredEnergy() {
        // 100 stored, 200/s, want 1.0s -> can only afford 100/200 = 0.5s
        assertEquals(0.5, SmelterEnergy.affordableDt(100, 200, 1.0), 1e-9);
    }

    @Test
    void zeroEnergyFreezes() {
        assertEquals(0.0, SmelterEnergy.affordableDt(0, 200, 1.0), 1e-9);
    }

    @Test
    void neverExceedsRealDtEvenWithSurplus() {
        // huge stored, but real dt caps it at 1x (no overclock in v1)
        assertEquals(1.0, SmelterEnergy.affordableDt(1_000_000, 200, 1.0), 1e-9);
    }

    @Test
    void drainMatchesWorkDone() {
        assertEquals(100, SmelterEnergy.drainFor(0.5, 200)); // 0.5s * 200/s = 100
    }

    @Test
    void drainRoundsToNearestLong() {
        // 0.4995s * 200 = 99.9 -> rounds to 100
        assertEquals(100, SmelterEnergy.drainFor(0.4995, 200));
    }

    @Test
    void zeroCostPerSecIsHandledNotDividedByZero() {
        // if a machine somehow has 0 cost/sec, it can run the full real dt and drains 0
        assertEquals(1.0, SmelterEnergy.affordableDt(0, 0, 1.0), 1e-9);
        assertEquals(0, SmelterEnergy.drainFor(1.0, 0));
    }
}
