package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Water-fill balance targets (battery/tank balancing design, 2026-06-05): capacity-blind equal
 * levels, capacity only as a clamp. Targets are the level L where sum(min(cap_i, L)) ~= total
 * stored; integer floor, remainder implicitly stays on donors.
 */
class WaterFillTest {

    @Test
    void equalSplitWhenNobodyClamps() {
        assertArrayEquals(new long[] {500, 500}, WaterFill.targets(new long[] {5000, 5000}, 1000));
    }

    @Test
    void capacityBlindUntilSomeoneFills() {
        // The user's example: 5000-cap and 1000-cap share 1000 evenly: size does not matter yet.
        assertArrayEquals(new long[] {500, 500}, WaterFill.targets(new long[] {5000, 1000}, 1000));
    }

    @Test
    void smallStorageClampsAndTheRestAbsorbsItsShare() {
        // Level 2000: the 1000-cap sits full, the 5000-cap takes the rest.
        assertArrayEquals(new long[] {2000, 1000}, WaterFill.targets(new long[] {5000, 1000}, 3000));
    }

    @Test
    void clampOrderIsIndependentOfArrayOrder() {
        assertArrayEquals(new long[] {1000, 2000}, WaterFill.targets(new long[] {1000, 5000}, 3000));
    }

    @Test
    void integerRemainderStaysUnassigned() {
        // 8 over three 10-caps: level floor(8/3)=2, sum(targets)=6, remainder 2 stays with donors.
        long[] targets = WaterFill.targets(new long[] {10, 10, 10}, 8);
        assertArrayEquals(new long[] {2, 2, 2}, targets);
    }

    @Test
    void everyoneFullWhenStoredEqualsTotalCapacity() {
        assertArrayEquals(new long[] {10, 10}, WaterFill.targets(new long[] {10, 10}, 20));
    }

    @Test
    void cascadingClampsAcrossManySizes() {
        // S=100 over caps [10, 20, 100, 100]: 10 and 20 fill, the two big ones split the remaining 70.
        assertArrayEquals(new long[] {10, 20, 35, 35}, WaterFill.targets(new long[] {10, 20, 100, 100}, 100));
    }

    @Test
    void degenerateInputs() {
        assertArrayEquals(new long[0], WaterFill.targets(new long[0], 0));
        assertArrayEquals(new long[] {0, 0}, WaterFill.targets(new long[] {10, 10}, 0));
        assertArrayEquals(new long[] {7}, WaterFill.targets(new long[] {10}, 7));
    }

    @Test
    void targetsNeverExceedCapacityAndSumStaysWithinStored() {
        long[] caps = {3, 7, 11, 950, 12345};
        long stored = 4242;
        long[] targets = WaterFill.targets(caps, stored);
        long sum = 0;
        for (int i = 0; i < caps.length; i++) {
            assertTrue(targets[i] <= caps[i], "target exceeds capacity at " + i);
            assertTrue(targets[i] >= 0);
            sum += targets[i];
        }
        assertTrue(sum <= stored, "targets must never invent resource");
        // Remainder is bounded by the number of unfull storages (floor-level property).
        assertEquals(true, stored - sum < caps.length);
    }
}
