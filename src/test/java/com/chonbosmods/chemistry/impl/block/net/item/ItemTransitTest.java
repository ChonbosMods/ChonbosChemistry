package com.chonbosmods.chemistry.impl.block.net.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Destination;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import com.chonbosmods.chemistry.impl.block.net.item.ItemTransit.Decision;
import com.chonbosmods.chemistry.impl.block.net.item.ItemTransit.StepResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ItemTransit#step}: the pure per-tick advance + re-route ladder over ONE
 * {@link TravelingStack} (2026-06-06 item-channel design §13.4, "stranded ladder"). Pure: no world
 * access. Fakes mirror the sibling item tests (in-memory {@link PipeGridView}, real {@link Network}s
 * via {@link NetworkManager}) plus a capacity-tracking {@link ContainerLookup} fake.
 */
class ItemTransitTest {

    private static final int SPEED = 5;
    private static final String COBBLE = "cobblestone";

    // ---- fakes -------------------------------------------------------------------------------

    /** A grid backed by an explicit map of packed-key -> node. */
    private static final class FakeGrid implements PipeGridView {
        private final Map<Long, PipeNode> nodes = new HashMap<>();

        FakeGrid put(int x, int y, int z, PipeNode node) {
            nodes.put(NetworkManager.packKey(x, y, z), node);
            return this;
        }

        @Override
        public PipeNode pipeAt(int x, int y, int z) {
            return nodes.get(NetworkManager.packKey(x, y, z));
        }
    }

    /** A container with a fixed remaining capacity (per item, type-agnostic for these tests). */
    private static final class FakeContainer implements ContainerView {
        private int capacity;

        FakeContainer(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public int insert(ItemKey key, int amount, boolean simulate) {
            int fit = Math.min(amount, capacity);
            if (!simulate) {
                capacity -= fit;
            }
            return fit;
        }

        @Override
        public ItemKey firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
            return null;
        }

        @Override
        public int extract(ItemKey key, int amount, boolean simulate) {
            return 0;
        }
    }

    /** A ContainerLookup backed by a map of packed-key -> FakeContainer. */
    private static final class FakeContainerLookup implements ContainerLookup {
        private final Map<Long, FakeContainer> containers = new HashMap<>();

        FakeContainerLookup put(int x, int y, int z, FakeContainer c) {
            containers.put(NetworkManager.packKey(x, y, z), c);
            return this;
        }

        @Override
        public ContainerView at(int x, int y, int z) {
            return containers.get(NetworkManager.packKey(x, y, z));
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static PipeNode itemPipe() {
        return PipeNode.of(PortChannel.ITEM, 1);
    }

    private static long key(int x, int y, int z) {
        return NetworkManager.packKey(x, y, z);
    }

    private static Network networkAt(int x, int y, int z, PipeGridView grid) {
        return new NetworkManager().getOrBuildNetwork(x, y, z, grid);
    }

    private static Destination dest(int cx, int cy, int cz, int vx, int vy, int vz, int face) {
        return new Destination(key(cx, cy, cz), key(vx, vy, vz), face);
    }

    private static StepResult step(TravelingStack s, Network net, PipeGridView grid,
                                   ContainerLookup containers, Endpoints endpoints) {
        return ItemTransit.step(s, SPEED, net, grid, containers, endpoints, FilterLookup.NONE);
    }

    // ---- 1. advance --------------------------------------------------------------------------

    @Test
    void advance_progressAccumulates_thenMovesSegmentAtThreshold() {
        // 3 pipes, container far end. Stack at segment 0 with a path of length 3 (not yet at last).
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe())
            .put(2, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(List.of(dest(3, 0, 0, 2, 0, 0, 0)), List.of());
        FakeContainerLookup containers = new FakeContainerLookup().put(3, 0, 0, new FakeContainer(64));

        long[] path = {key(0, 0, 0), key(1, 0, 0), key(2, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 4, null, path, key(99, 0, 0), key(3, 0, 0));

        // Ticks 1..4: progress accumulates, still ADVANCED, segment unchanged.
        for (int t = 1; t < SPEED; t++) {
            StepResult r = step(s, net, grid, containers, endpoints);
            assertEquals(Decision.ADVANCED, r.decision());
            assertSame(s, r.stack());
            assertEquals(t, s.progressTicks());
            assertEquals(0, s.segmentIndex());
        }
        // Tick 5: threshold reached -> MOVED_SEGMENT, progress reset, segment incremented.
        StepResult moved = step(s, net, grid, containers, endpoints);
        assertEquals(Decision.MOVED_SEGMENT, moved.decision());
        assertEquals(0, s.progressTicks());
        assertEquals(1, s.segmentIndex());
    }

    // ---- 2. full delivery --------------------------------------------------------------------

    @Test
    void fullDelivery_commitsAndReportsDeliveredCount() {
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(List.of(dest(1, 0, 0, 0, 0, 0, 0)), List.of());
        FakeContainer destC = new FakeContainer(64);
        FakeContainerLookup containers = new FakeContainerLookup().put(1, 0, 0, destC);

        // Single-segment path: the stack sits on the via pipe already; one tick to threshold = arrival.
        long[] path = {key(0, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 7, null, path, key(99, 0, 0), key(1, 0, 0));
        s.setProgressTicks(SPEED - 1); // next tick completes the segment -> arrival

        StepResult r = step(s, net, grid, containers, endpoints);
        assertEquals(Decision.DELIVERED, r.decision());
        assertEquals(7, r.deliveredCount());
        // The container actually received 7 (commit, not simulate): remaining capacity is 57.
        assertEquals(57, destC.insert(new ItemKey(COBBLE, 1), 1000, true));
    }

    // ---- 3. partial delivery -> re-route remainder -------------------------------------------

    @Test
    void partialDelivery_commitsWhatFits_remainderRerouted() {
        // Entry pipe (0,0,0) borders BOTH a near full-ish container (dest) and an alternate roomy one.
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        // dest container at (0,1,0) via pipe (0,0,0) face +Y(2): only 3 capacity.
        // alternate at (2,0,0) via pipe (1,0,0) face +X(0): roomy.
        Destination near = dest(0, 1, 0, 0, 0, 0, 2);
        Destination alt = dest(2, 0, 0, 1, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(near, alt), List.of());
        FakeContainer destC = new FakeContainer(3);
        FakeContainer altC = new FakeContainer(64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(0, 1, 0, destC)
            .put(2, 0, 0, altC);

        long[] path = {key(0, 0, 0)}; // arriving at the near dest's via pipe
        TravelingStack s = TravelingStack.of(COBBLE, 10, null, path, key(99, 0, 0), near.containerKey());
        s.setProgressTicks(SPEED - 1);

        StepResult r = step(s, net, grid, containers, endpoints);
        assertEquals(Decision.PARTIAL_DELIVERED_REROUTED, r.decision());
        assertEquals(3, r.deliveredCount());
        // Remainder reduced to 7 and re-targeted at the alternate container.
        assertEquals(7, s.count());
        assertEquals(alt.containerKey(), s.destKey());
        assertNotEquals(near.containerKey(), s.destKey());
        // New path starts at the current segment (0,0,0) and reaches the alternate's via pipe.
        assertArrayEquals(new long[] {key(0, 0, 0), key(1, 0, 0)}, s.path());
        assertEquals(0, s.segmentIndex());
        assertEquals(0, s.progressTicks());
        // The near container actually got 3 committed (remaining 0).
        assertEquals(0, destC.insert(new ItemKey(COBBLE, 1), 1000, true));
    }

    // ---- 4. zero-accept + alternate -> REROUTED ----------------------------------------------

    @Test
    void zeroAccept_alternateExists_reroutes() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Destination near = dest(0, 1, 0, 0, 0, 0, 2);
        Destination alt = dest(2, 0, 0, 1, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(near, alt), List.of());
        FakeContainer destC = new FakeContainer(0); // full: zero accept
        FakeContainer altC = new FakeContainer(64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(0, 1, 0, destC)
            .put(2, 0, 0, altC);

        long[] path = {key(0, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 5, null, path, key(99, 0, 0), near.containerKey());
        s.setProgressTicks(SPEED - 1);

        StepResult r = step(s, net, grid, containers, endpoints);
        assertEquals(Decision.REROUTED, r.decision());
        assertEquals(0, r.deliveredCount());
        assertEquals(5, s.count()); // nothing delivered, full remainder
        assertEquals(alt.containerKey(), s.destKey());
        assertArrayEquals(new long[] {key(0, 0, 0), key(1, 0, 0)}, s.path());
        assertEquals(0, s.segmentIndex(), "re-route path starts at the current segment");
        assertEquals(0, s.progressTicks());
    }

    // ---- 5. zero-accept + no alternate + origin reachable -> RETURNING ------------------------

    @Test
    void zeroAccept_noAlternate_originReachableAndRoomy_returns() {
        // Line of 2 pipes. Dest at far end (full). Origin container at the near end, roomy.
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        // Origin container at (-1,0,0) via pipe (0,0,0) face -X(1). Dest at (2,0,0) via pipe (1,0,0).
        long originKey = key(-1, 0, 0);
        Destination origin = dest(-1, 0, 0, 0, 0, 0, 1);
        Destination destFar = dest(2, 0, 0, 1, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(origin, destFar), List.of());
        FakeContainer destC = new FakeContainer(0); // full
        FakeContainer originC = new FakeContainer(64); // roomy
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(2, 0, 0, destC)
            .put(-1, 0, 0, originC);

        // Stack sits on pipe (1,0,0) (its current segment) arriving at the far dest.
        long[] path = {key(1, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 5, null, path, originKey, destFar.containerKey());
        s.setProgressTicks(SPEED - 1);

        StepResult r = step(s, net, grid, containers, endpoints);
        assertEquals(Decision.RETURNING, r.decision());
        assertEquals(originKey, s.destKey(), "returning stack's dest becomes the origin container");
        // Path returns from current segment (1,0,0) back toward origin's via pipe (0,0,0).
        assertArrayEquals(new long[] {key(1, 0, 0), key(0, 0, 0)}, s.path());
        assertEquals(0, s.segmentIndex());
        assertEquals(0, s.progressTicks());
    }

    // ---- 6. returning stack arriving at origin delivers --------------------------------------

    @Test
    void returningStackArrivingAtOrigin_deliversIntoIt() {
        // A returning stack: dest IS the origin container, and it is now reachable + roomy.
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        long originKey = key(-1, 0, 0);
        Destination origin = dest(-1, 0, 0, 0, 0, 0, 1);
        Endpoints endpoints = new Endpoints(List.of(origin), List.of());
        FakeContainer originC = new FakeContainer(64);
        FakeContainerLookup containers = new FakeContainerLookup().put(-1, 0, 0, originC);

        long[] path = {key(0, 0, 0)}; // via pipe of the origin container
        TravelingStack s = TravelingStack.of(COBBLE, 6, null, path, originKey, originKey);
        s.setProgressTicks(SPEED - 1);

        StepResult r = step(s, net, grid, containers, endpoints);
        assertEquals(Decision.DELIVERED, r.decision());
        assertEquals(6, r.deliveredCount());
        assertEquals(58, originC.insert(new ItemKey(COBBLE, 1), 1000, true));
    }

    // ---- 7. origin gone/full -> POP_OUT ------------------------------------------------------

    @Test
    void originGoneOrFull_popsOutAtCurrentSegment() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        // Dest at far end full; origin container does not exist (lookup returns null).
        Destination destFar = dest(2, 0, 0, 1, 0, 0, 0);
        long originKey = key(-5, 0, 0); // no container, no endpoint
        Endpoints endpoints = new Endpoints(List.of(destFar), List.of());
        FakeContainer destC = new FakeContainer(0);
        FakeContainerLookup containers = new FakeContainerLookup().put(2, 0, 0, destC);

        long[] path = {key(1, 0, 0)}; // current segment = (1,0,0)
        TravelingStack s = TravelingStack.of(COBBLE, 5, null, path, originKey, destFar.containerKey());
        s.setProgressTicks(SPEED - 1);

        StepResult r = step(s, net, grid, containers, endpoints);
        assertEquals(Decision.POP_OUT, r.decision());
        assertEquals(key(1, 0, 0), r.popOutPipeKey(), "pop out at the stack's current segment");
    }

    @Test
    void originFullToo_popsOut() {
        // Dest full AND origin full: ladder exhausts to POP_OUT.
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        long originKey = key(-1, 0, 0);
        Destination origin = dest(-1, 0, 0, 0, 0, 0, 1);
        Destination destFar = dest(2, 0, 0, 1, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(origin, destFar), List.of());
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(2, 0, 0, new FakeContainer(0))
            .put(-1, 0, 0, new FakeContainer(0)); // origin full too

        long[] path = {key(1, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 5, null, path, originKey, destFar.containerKey());
        s.setProgressTicks(SPEED - 1);

        StepResult r = step(s, net, grid, containers, endpoints);
        assertEquals(Decision.POP_OUT, r.decision());
        assertEquals(key(1, 0, 0), r.popOutPipeKey());
    }

    // ---- 8. malformed stack -> POP_OUT, never throws -----------------------------------------

    @Test
    void malformedStack_emptyPath_popsOutWithoutThrowing() {
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(List.of(), List.of());
        FakeContainerLookup containers = new FakeContainerLookup();

        TravelingStack s = TravelingStack.of(COBBLE, 5, null, new long[0], key(99, 0, 0), key(1, 0, 0));

        StepResult r = assertDoesNotThrow(() -> step(s, net, grid, containers, endpoints));
        assertEquals(Decision.POP_OUT, r.decision());
    }

    @Test
    void malformedStack_segmentBeyondPath_popsOutWithoutThrowing() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(List.of(dest(2, 0, 0, 1, 0, 0, 0)), List.of());
        FakeContainerLookup containers = new FakeContainerLookup().put(2, 0, 0, new FakeContainer(64));

        long[] path = {key(0, 0, 0), key(1, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 5, null, path, key(99, 0, 0), key(2, 0, 0));
        s.setSegmentIndex(9); // beyond the path
        s.setProgressTicks(SPEED - 1);

        StepResult r = assertDoesNotThrow(() -> step(s, net, grid, containers, endpoints));
        assertEquals(Decision.POP_OUT, r.decision());
        // Pop out at the LAST valid segment of the path (clamped), defensively.
        assertEquals(key(1, 0, 0), r.popOutPipeKey());
    }

    // ---- 9. failed-dest exclusion ------------------------------------------------------------

    @Test
    void reroute_excludesTheContainerThatJustRejected_evenIfStillNearest() {
        // The failed dest is the NEAREST candidate (depth 0, via the entry pipe). A naive re-route would
        // re-pick it. The ladder must exclude it and choose the farther alternate.
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        // Failed dest at (0,1,0) via pipe (0,0,0) (nearest). Alternate at (2,0,0) via pipe (1,0,0).
        Destination failed = dest(0, 1, 0, 0, 0, 0, 2);
        Destination alt = dest(2, 0, 0, 1, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(failed, alt), List.of());
        FakeContainer failedC = new FakeContainer(0); // rejects
        FakeContainer altC = new FakeContainer(64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(0, 1, 0, failedC)
            .put(2, 0, 0, altC);

        long[] path = {key(0, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 5, null, path, key(99, 0, 0), failed.containerKey());
        s.setProgressTicks(SPEED - 1);

        StepResult r = step(s, net, grid, containers, endpoints);
        assertEquals(Decision.REROUTED, r.decision());
        assertEquals(alt.containerKey(), s.destKey(),
            "re-route must NOT re-pick the container that just rejected, even though it is nearest");
        assertNotEquals(failed.containerKey(), s.destKey());
    }
}
