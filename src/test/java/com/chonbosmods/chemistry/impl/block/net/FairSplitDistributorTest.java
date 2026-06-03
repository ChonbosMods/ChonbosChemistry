package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.*;

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
}
