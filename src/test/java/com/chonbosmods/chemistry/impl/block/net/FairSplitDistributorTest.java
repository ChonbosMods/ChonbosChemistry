package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class FairSplitDistributorTest {

    private static long sum(long[] a) {
        long s = 0;
        for (long v : a) {
            s += v;
        }
        return s;
    }

    private static void assertInvariants(long amount, long[] caps, long[] result) {
        assertEquals(caps.length, result.length, "result length matches caps");
        long capSum = 0;
        for (int i = 0; i < caps.length; i++) {
            long cap = Math.max(0, caps[i]);
            capSum += cap;
            assertTrue(result[i] >= 0, "result[" + i + "] >= 0");
            assertTrue(result[i] <= cap, "result[" + i + "] <= cap");
        }
        assertEquals(Math.min(Math.max(0, amount), capSum), sum(result),
                "sum == min(amount, sum(caps))");
    }

    /**
     * Asserts max-min fairness: among acceptors that are NOT saturated (i.e. did not receive
     * their full effective capacity), find the minimum allocation ({@code floor}). No acceptor may
     * receive more than {@code floor + 1} unless that acceptor is saturated. This catches a
     * degenerate "dump everything into acceptor 0" implementation that bounds+sum alone would miss.
     * The all-saturated / empty case (no unsaturated acceptors) holds vacuously.
     */
    private static void assertFair(long[] caps, long[] result) {
        long floor = Long.MAX_VALUE;
        for (int i = 0; i < caps.length; i++) {
            long cap = Math.max(0, caps[i]);
            boolean saturated = result[i] == cap;
            if (!saturated) {
                floor = Math.min(floor, result[i]);
            }
        }
        if (floor == Long.MAX_VALUE) {
            return; // no unsaturated acceptors: fairness holds vacuously.
        }
        for (int i = 0; i < caps.length; i++) {
            long cap = Math.max(0, caps[i]);
            boolean saturated = result[i] == cap;
            if (!saturated) {
                assertTrue(result[i] <= floor + 1,
                        "unsaturated result[" + i + "]=" + result[i]
                                + " exceeds floor+1=" + (floor + 1));
            }
        }
    }

    @Test
    void evenSplit() {
        long[] caps = {1000, 1000, 1000};
        long[] r = FairSplitDistributor.allocate(90, caps);
        assertArrayEquals(new long[] {30, 30, 30}, r);
        assertInvariants(90, caps, r);
    }

    @Test
    void nearFullRedistribution() {
        long[] caps = {10, 1000, 1000};
        long[] r = FairSplitDistributor.allocate(90, caps);
        assertArrayEquals(new long[] {10, 40, 40}, r);
        assertInvariants(90, caps, r);
    }

    @Test
    void amountExceedsTotalCapacity() {
        long[] caps = {10, 20, 30};
        long[] r = FairSplitDistributor.allocate(100, caps);
        assertArrayEquals(new long[] {10, 20, 30}, r);
        assertInvariants(100, caps, r);
    }

    @Test
    void singleAcceptorRoom() {
        long[] caps = {1000};
        long[] r = FairSplitDistributor.allocate(50, caps);
        assertArrayEquals(new long[] {50}, r);
        assertInvariants(50, caps, r);
    }

    @Test
    void singleAcceptorCapped() {
        long[] caps = {20};
        long[] r = FairSplitDistributor.allocate(50, caps);
        assertArrayEquals(new long[] {20}, r);
        assertInvariants(50, caps, r);
    }

    @Test
    void zeroAcceptors() {
        long[] r = FairSplitDistributor.allocate(90, new long[] {});
        assertArrayEquals(new long[] {}, r);
    }

    @Test
    void amountZero() {
        long[] caps = {10, 10};
        long[] r = FairSplitDistributor.allocate(0, caps);
        assertArrayEquals(new long[] {0, 0}, r);
    }

    @Test
    void remainderDistribution() {
        long[] caps = {3, 3, 3};
        long[] r = FairSplitDistributor.allocate(8, caps);
        assertArrayEquals(new long[] {3, 3, 2}, r);
        assertInvariants(8, caps, r);
    }

    @Test
    void multiRoundSaturation() {
        long[] caps = {5, 5, 1000, 1000};
        long[] r = FairSplitDistributor.allocate(100, caps);
        assertArrayEquals(new long[] {5, 5, 45, 45}, r);
        assertInvariants(100, caps, r);
    }

    @Test
    void negativeAmountIsZero() {
        long[] caps = {10, 10};
        long[] r = FairSplitDistributor.allocate(-5, caps);
        assertArrayEquals(new long[] {0, 0}, r);
    }

    @Test
    void negativeCapacitiesTreatedAsZero() {
        long[] caps = {-5, 10};
        long[] r = FairSplitDistributor.allocate(8, caps);
        assertArrayEquals(new long[] {0, 8}, r);
        assertInvariants(8, caps, r);
    }

    @Test
    void allZeroCaps() {
        long[] caps = {0, 0};
        long[] r = FairSplitDistributor.allocate(10, caps);
        assertArrayEquals(new long[] {0, 0}, r);
        assertInvariants(10, caps, r);
        assertFair(caps, r);
    }

    @Test
    void tinyTinyHugeSaturationThenRemainder() {
        long[] caps = {1, 1, 1000};
        long[] r = FairSplitDistributor.allocate(11, caps);
        assertArrayEquals(new long[] {1, 1, 9}, r);
        assertInvariants(11, caps, r);
        assertFair(caps, r);
    }

    @Test
    void zeroAmongNonzero() {
        long[] caps = {0, 5, 5};
        long[] r = FairSplitDistributor.allocate(10, caps);
        assertArrayEquals(new long[] {0, 5, 5}, r);
        assertInvariants(10, caps, r);
        assertFair(caps, r);
    }

    @Test
    void fuzzInvariantsHold() {
        Random rng = new Random(42L);
        for (int iter = 0; iter < 50_000; iter++) {
            int n = 1 + rng.nextInt(6); // [1,6]
            long[] caps = new long[n];
            for (int i = 0; i < n; i++) {
                caps[i] = -3 + rng.nextInt(40); // [-3,36]
            }
            long amount = -5 + rng.nextInt(200); // [-5,194]

            long[] r = FairSplitDistributor.allocate(amount, caps);

            assertInvariants(amount, caps, r);
            assertFair(caps, r);
        }
    }
}
