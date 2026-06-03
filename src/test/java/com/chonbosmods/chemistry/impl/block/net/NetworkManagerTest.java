package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.*;

import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for {@link NetworkManager}: BFS discovery, same-channel boundaries, deterministic
 * anchor ids, caching, invalidation, and packed-key round-trips. All exercised against a fake
 * {@link PipeGridView} so no live Hytale block access is involved.
 */
class NetworkManagerTest {

    /** A fake grid backed by a {@code Map<packedKey, PipeNode>}: a position with no entry has no pipe. */
    private static final class FakePipeGrid implements PipeGridView {
        private final Map<Long, PipeNode> pipes = new HashMap<>();

        FakePipeGrid put(int x, int y, int z, PipeNode node) {
            pipes.put(NetworkManager.packKey(x, y, z), node);
            return this;
        }

        @Override
        public PipeNode pipeAt(int x, int y, int z) {
            return pipes.get(NetworkManager.packKey(x, y, z));
        }
    }

    private static PipeNode power(int tier) {
        return PipeNode.of(PortChannel.POWER, tier);
    }

    private static PipeNode fluid(int tier) {
        return PipeNode.of(PortChannel.FLUID, tier);
    }

    @Test
    void singlePipeBuildsNetworkOfOneMember() {
        FakePipeGrid grid = new FakePipeGrid().put(0, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        Network net = mgr.getOrBuildNetwork(0, 0, 0, grid);

        assertNotNull(net);
        assertEquals(PortChannel.POWER, net.channel());
        assertEquals(PipeTiers.capacityForTier(0), net.capacity());
        assertEquals(PipeTiers.throughputForTier(0), net.throughput());
        assertEquals(1, mgr.cachedNetworkCount());
    }

    @Test
    void noPipeReturnsNull() {
        FakePipeGrid grid = new FakePipeGrid();
        NetworkManager mgr = new NetworkManager();

        assertNull(mgr.getOrBuildNetwork(5, 5, 5, grid));
        assertEquals(0, mgr.cachedNetworkCount());
    }

    @Test
    void straightRunOfThreeAggregatesCapacityAndMinThroughput() {
        // Tiers 2,0,1 along a straight X run: capacity = Σ, throughput = MIN (tier-0's value).
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(2))
            .put(1, 0, 0, power(0))
            .put(2, 0, 0, power(1));
        NetworkManager mgr = new NetworkManager();

        Network net = mgr.getOrBuildNetwork(0, 0, 0, grid);

        long expectedCap = PipeTiers.capacityForTier(2)
            + PipeTiers.capacityForTier(0)
            + PipeTiers.capacityForTier(1);
        assertEquals(expectedCap, net.capacity());
        // min throughput is tier-0's, which is the smallest given a monotonic curve.
        assertEquals(PipeTiers.throughputForTier(0), net.throughput());
        assertEquals(1, mgr.cachedNetworkCount());
    }

    @Test
    void differentChannelNeighborIsASeparateNetwork() {
        // A POWER pipe adjacent to a FLUID pipe: channel boundary => two distinct networks.
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 0, 0, fluid(0));
        NetworkManager mgr = new NetworkManager();

        Network powerNet = mgr.getOrBuildNetwork(0, 0, 0, grid);
        Network fluidNet = mgr.getOrBuildNetwork(1, 0, 0, grid);

        assertNotSame(powerNet, fluidNet);
        assertEquals(PortChannel.POWER, powerNet.channel());
        assertEquals(PortChannel.FLUID, fluidNet.channel());
        // Each has exactly one member (its own channel only).
        assertEquals(PipeTiers.capacityForTier(0), powerNet.capacity());
        assertEquals(PipeTiers.capacityForTier(0), fluidNet.capacity());
        assertEquals(2, mgr.cachedNetworkCount());
    }

    @Test
    void branchingShapeFormsOneNetworkOfFour() {
        // T/plus shape: center + three arms, all POWER. 4 connected members.
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))   // center
            .put(1, 0, 0, power(0))   // +X arm
            .put(-1, 0, 0, power(0))  // -X arm
            .put(0, 1, 0, power(0));  // +Y arm
        NetworkManager mgr = new NetworkManager();

        Network net = mgr.getOrBuildNetwork(0, 0, 0, grid);

        assertEquals(4 * PipeTiers.capacityForTier(0), net.capacity());
        assertEquals(1, mgr.cachedNetworkCount());
    }

    @Test
    void cachingReturnsSameInstanceFromAnyMember() {
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 0, 0, power(0))
            .put(2, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        Network a = mgr.getOrBuildNetwork(0, 0, 0, grid);
        Network b = mgr.getOrBuildNetwork(2, 0, 0, grid);

        assertSame(a, b);
        assertEquals(1, mgr.cachedNetworkCount());
    }

    @Test
    void anchorIsMinPackedKeyRegardlessOfBuildOrigin() {
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 0, 0, power(0))
            .put(2, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        long expectedAnchor = Math.min(
            NetworkManager.packKey(0, 0, 0),
            Math.min(NetworkManager.packKey(1, 0, 0), NetworkManager.packKey(2, 0, 0)));

        // Build from the far end; anchor must still be the min member key.
        mgr.getOrBuildNetwork(2, 0, 0, grid);
        assertEquals(expectedAnchor, mgr.anchorOf(0, 0, 0));
        assertEquals(expectedAnchor, mgr.anchorOf(1, 0, 0));
        assertEquals(expectedAnchor, mgr.anchorOf(2, 0, 0));
    }

    @Test
    void invalidateDropsNetworkSoNextBuildIsFresh() {
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        Network first = mgr.getOrBuildNetwork(0, 0, 0, grid);
        assertEquals(1, mgr.cachedNetworkCount());

        mgr.invalidate(1, 0, 0);
        assertEquals(0, mgr.cachedNetworkCount());
        assertNull(mgr.anchorOf(0, 0, 0));
        assertNull(mgr.anchorOf(1, 0, 0));

        Network second = mgr.getOrBuildNetwork(0, 0, 0, grid);
        assertNotSame(first, second);
        assertEquals(1, mgr.cachedNetworkCount());
    }

    @Test
    void invalidateUncachedPositionIsNoOp() {
        FakePipeGrid grid = new FakePipeGrid().put(0, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        // Never built; invalidate must be a safe no-op.
        mgr.invalidate(0, 0, 0);
        mgr.invalidate(99, 99, 99);
        assertEquals(0, mgr.cachedNetworkCount());
    }

    @Test
    void diagonalPipesAreNotConnected() {
        // Two pipes touching only at a corner (diagonal) are NOT face-adjacent => separate networks.
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 1, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        Network a = mgr.getOrBuildNetwork(0, 0, 0, grid);
        Network b = mgr.getOrBuildNetwork(1, 1, 0, grid);

        assertNotSame(a, b);
        assertEquals(2, mgr.cachedNetworkCount());
        assertEquals(PipeTiers.capacityForTier(0), a.capacity());
        assertEquals(PipeTiers.capacityForTier(0), b.capacity());
    }

    @Test
    void cyclicLoopTerminatesAsOneNetworkOfFour() {
        // A closed ring of 4 POWER pipes in the XY plane: each pipe is face-adjacent to two others,
        // forming a cycle. BFS must mark-on-enqueue so it terminates (no infinite loop / stack overflow).
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 0, 0, power(0))
            .put(1, 1, 0, power(0))
            .put(0, 1, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        Network net = mgr.getOrBuildNetwork(0, 0, 0, grid);

        assertNotNull(net);
        // 4 members aggregated, single connected component, call returned (no hang).
        assertEquals(4 * PipeTiers.capacityForTier(0), net.capacity());
        assertEquals(1, mgr.cachedNetworkCount());
    }

    @Test
    void packKeyRejectsCoordinatesOutOfRange() {
        // Just above MAX on x and just below MIN on z must both be rejected.
        assertThrows(IllegalArgumentException.class,
            () -> NetworkManager.packKey(NetworkManager.MAX_COORD + 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> NetworkManager.packKey(0, 0, NetworkManager.MIN_COORD - 1));
    }

    @Test
    void bfsAtWorldEdgeSkipsOutOfRangeNeighbourWithoutThrowing() {
        // A pipe sitting at the max packable X coord: its +X neighbour is out of range and must be
        // skipped (not packed), so discovery yields a 1-member network without throwing.
        FakePipeGrid grid = new FakePipeGrid()
            .put(NetworkManager.MAX_COORD, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();

        Network net = assertDoesNotThrow(
            () -> mgr.getOrBuildNetwork(NetworkManager.MAX_COORD, 0, 0, grid));

        assertNotNull(net);
        assertEquals(PipeTiers.capacityForTier(0), net.capacity());
        assertEquals(1, mgr.cachedNetworkCount());
    }

    @Test
    void crossOriginRunsShareAnchorAndAggregates() {
        // Same 3-pipe straight run built at two world locations on two fresh managers must produce
        // networks with identical capacity + throughput (shape-equivalent). Within a single manager,
        // building from either end returns the same cached instance with the same anchor id.
        FakePipeGrid gridA = new FakePipeGrid()
            .put(0, 0, 0, power(0))
            .put(1, 0, 0, power(0))
            .put(2, 0, 0, power(0));
        FakePipeGrid gridB = new FakePipeGrid()
            .put(2, 0, 0, power(0))
            .put(3, 0, 0, power(0))
            .put(4, 0, 0, power(0));
        NetworkManager mgrA = new NetworkManager();
        NetworkManager mgrB = new NetworkManager();

        Network netA = mgrA.getOrBuildNetwork(0, 0, 0, gridA);
        Network netB = mgrB.getOrBuildNetwork(2, 0, 0, gridB);

        // Identical aggregate shape regardless of world origin.
        assertEquals(netA.capacity(), netB.capacity());
        assertEquals(netA.throughput(), netB.throughput());
        assertEquals(netA.channel(), netB.channel());

        // Within mgrA, building from the far end returns the same cached instance and same anchor.
        Network sameA = mgrA.getOrBuildNetwork(2, 0, 0, gridA);
        assertSame(netA, sameA);
        assertEquals(mgrA.anchorOf(0, 0, 0), mgrA.anchorOf(2, 0, 0));
    }

    @Test
    void packKeyRoundTripsForNegativeCoordinates() {
        int[][] samples = {
            {0, 0, 0},
            {-1, -1, -1},
            {-1000000, 5, -7},
            {123456, -654321, 99999},
            {NetworkManager.MIN_COORD, NetworkManager.MIN_COORD, NetworkManager.MIN_COORD},
            {NetworkManager.MAX_COORD, NetworkManager.MAX_COORD, NetworkManager.MAX_COORD},
        };
        for (int[] s : samples) {
            long key = NetworkManager.packKey(s[0], s[1], s[2]);
            assertEquals(s[0], NetworkManager.unpackX(key), "x for " + java.util.Arrays.toString(s));
            assertEquals(s[1], NetworkManager.unpackY(key), "y for " + java.util.Arrays.toString(s));
            assertEquals(s[2], NetworkManager.unpackZ(key), "z for " + java.util.Arrays.toString(s));
        }
    }

    // --- H3: chunk invalidation + self/neighbor helper ---

    @Test
    void invalidateChunkDropsNetworksInThatColumnAndLeavesOthers() {
        // Network A lives in chunk column (0,0): blocks 0..31. Network B lives in chunk column (1,0):
        // blocks 32..63. Different channels would also separate them, but distance alone keeps them
        // distinct connected components here.
        FakePipeGrid grid = new FakePipeGrid()
            .put(1, 4, 2, power(0))   // chunk (0,0)
            .put(2, 4, 2, power(0))   // chunk (0,0), adjacent -> same network A
            .put(40, 7, 5, power(0)); // chunk (1,0) -> separate network B
        NetworkManager mgr = new NetworkManager();

        mgr.getOrBuildNetwork(1, 4, 2, grid);
        mgr.getOrBuildNetwork(40, 7, 5, grid);
        assertEquals(2, mgr.cachedNetworkCount(), "two distinct networks cached up front");

        int dropped = mgr.invalidateChunk(0, 0);

        assertEquals(1, dropped, "exactly network A (in chunk column 0,0) dropped");
        assertEquals(1, mgr.cachedNetworkCount(), "network B (chunk 1,0) untouched");
        assertNull(mgr.anchorOf(1, 4, 2), "network A member no longer cached");
        assertNull(mgr.anchorOf(2, 4, 2), "network A member no longer cached");
        assertNotNull(mgr.anchorOf(40, 7, 5), "network B member still cached");
    }

    @Test
    void invalidateChunkDropsWholeNetworkEvenWhenItStraddlesTheBoundary() {
        // A network straddling chunk (0,0) and (1,0): block 31 (chunk 0) adjacent to block 32 (chunk 1).
        FakePipeGrid grid = new FakePipeGrid()
            .put(31, 0, 0, power(0))
            .put(32, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();
        mgr.getOrBuildNetwork(31, 0, 0, grid);
        assertEquals(1, mgr.cachedNetworkCount());

        // Unloading EITHER column must drop the whole straddling network.
        int dropped = mgr.invalidateChunk(1, 0);

        assertEquals(1, dropped);
        assertEquals(0, mgr.cachedNetworkCount(), "straddling network fully dropped");
        assertNull(mgr.anchorOf(31, 0, 0), "the in-other-chunk member is dropped too");
    }

    @Test
    void invalidateChunkIsNoOpWhenNothingCachedThere() {
        FakePipeGrid grid = new FakePipeGrid().put(0, 0, 0, power(0));
        NetworkManager mgr = new NetworkManager();
        mgr.getOrBuildNetwork(0, 0, 0, grid);

        assertEquals(0, mgr.invalidateChunk(99, 99), "no network in chunk (99,99)");
        assertEquals(1, mgr.cachedNetworkCount(), "existing network untouched");
    }

    @Test
    void invalidateChunkHandlesNegativeChunkColumns() {
        // block -1 is in chunk column -1 (since -1 >> 5 == -1).
        FakePipeGrid grid = new FakePipeGrid().put(-1, 3, -1, power(0));
        NetworkManager mgr = new NetworkManager();
        mgr.getOrBuildNetwork(-1, 3, -1, grid);
        assertEquals(1, mgr.cachedNetworkCount());

        assertEquals(1, mgr.invalidateChunk(-1, -1), "negative chunk column matched");
        assertEquals(0, mgr.cachedNetworkCount());
    }

    @Test
    void selfAndNeighborsReturnsTheSevenExpectedPositions() {
        int[][] got = NetworkManager.selfAndNeighbors(10, 20, 30);

        assertEquals(7, got.length, "self + 6 face neighbours");
        // Order is self, +X, -X, +Y, -Y, +Z, -Z (matching the canonical OFFSETS).
        int[][] expected = {
            {10, 20, 30},
            {11, 20, 30}, {9, 20, 30},
            {10, 21, 30}, {10, 19, 30},
            {10, 20, 31}, {10, 20, 29},
        };
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], got[i], "position " + i);
        }
    }
}
