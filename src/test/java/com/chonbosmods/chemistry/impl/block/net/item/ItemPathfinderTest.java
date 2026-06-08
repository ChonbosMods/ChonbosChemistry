package com.chonbosmods.chemistry.impl.block.net.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeConnectivity;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Destination;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import com.chonbosmods.chemistry.impl.block.net.item.ItemPathfinder.Candidate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ItemPathfinder#candidates}: nearest-first, filter-gated, NONE-respecting BFS
 * over an ITEM network's member pipes. Fakes mirror {@code ItemEndpointsTest}: an in-memory
 * {@link PipeGridView} backed by a position map, real {@link Network}s built by {@link NetworkManager},
 * and small {@link FilterLookup} stubs.
 */
class ItemPathfinderTest {

    private static final ItemKey COBBLE = new ItemKey("cobblestone", 16);
    private static final ItemKey IRON = new ItemKey("iron_ingot", 8);

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

    // 1. Straight line: 3 pipes, container at the far end: path = the 3 keys in order.
    @Test
    void straightLine_pathIsAllThreePipesInOrder() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe())
            .put(2, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        // Container at (3,0,0), reached from pipe (2,0,0) via +X (face 0).
        Endpoints endpoints = new Endpoints(List.of(dest(3, 0, 0, 2, 0, 0, 0)), List.of());

        List<Candidate> got = ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE);

        assertEquals(1, got.size());
        assertArrayEquals(new long[] {key(0, 0, 0), key(1, 0, 0), key(2, 0, 0)},
            got.get(0).path());
        assertEquals(key(3, 0, 0), got.get(0).destination().containerKey());
    }

    // 6. Entry pipe IS the via pipe (container adjacent to entry): path = [entry].
    @Test
    void containerAdjacentToEntry_pathIsJustEntry() {
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(List.of(dest(1, 0, 0, 0, 0, 0, 0)), List.of());

        List<Candidate> got = ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE);

        assertEquals(1, got.size());
        assertArrayEquals(new long[] {key(0, 0, 0)}, got.get(0).path());
    }

    // 2a. Two destinations at different distances -> nearest first.
    @Test
    void twoDestinations_nearestFirst() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe())
            .put(2, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        // Near: container off pipe (1,0,0) via +Y (face 2). Far: off pipe (2,0,0) via +X (face 0).
        Destination near = dest(1, 1, 0, 1, 0, 0, 2);
        Destination far = dest(3, 0, 0, 2, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(far, near), List.of());

        List<Candidate> got = ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE);

        assertEquals(2, got.size());
        assertEquals(near.containerKey(), got.get(0).destination().containerKey());
        assertEquals(far.containerKey(), got.get(1).destination().containerKey());
        assertArrayEquals(new long[] {key(0, 0, 0), key(1, 0, 0)}, got.get(0).path());
    }

    // 2b. Equal distances -> tie-break by containerKey ascending (deterministic).
    @Test
    void equalDistances_tieBreakByContainerKeyAscending() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        // Both containers border pipe (1,0,0): via +Y (2) and via +Z (4). Same distance (depth 1).
        Destination a = dest(1, 1, 0, 1, 0, 0, 2); // (1,1,0)
        Destination b = dest(1, 0, 1, 1, 0, 0, 4); // (1,0,1)
        long ka = key(1, 1, 0);
        long kb = key(1, 0, 1);
        long lower = Math.min(ka, kb);
        long higher = Math.max(ka, kb);
        // Feed in the WORST order (higher first) so only a real sort can produce ascending output.
        Endpoints endpoints = new Endpoints(
            higher == ka ? List.of(a, b) : List.of(b, a), List.of());

        List<Candidate> got = ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE);

        assertEquals(2, got.size());
        assertEquals(lower, got.get(0).destination().containerKey());
        assertEquals(higher, got.get(1).destination().containerKey());
    }

    // 3. NONE face mid-route blocks; alternate loop route is taken.
    @Test
    void noneFaceMidRoute_blocksAndAlternateRouteTaken() {
        // A square loop of 4 pipes around (0,0,0)-(1,0,0)-(1,0,1)-(0,0,1).
        // The direct edge (0,0,0)+X->(1,0,0) is severed by a NONE on pipe (0,0,0)'s +X face (and the
        // reciprocal on (1,0,0)). The long way round (via (0,0,1)->(1,0,1)->(1,0,0)) still reaches it.
        PipeNode p00 = itemPipe();
        p00.setFlowState(0, FlowState.NONE);  // +X severed
        PipeNode p10 = itemPipe();
        p10.setFlowState(PipeConnectivity.opposite(0), FlowState.NONE); // -X severed (reciprocal)
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, p00)
            .put(1, 0, 0, p10)
            .put(1, 0, 1, itemPipe())
            .put(0, 0, 1, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        // Container off (1,0,0) via +X (face 0).
        Endpoints endpoints = new Endpoints(List.of(dest(2, 0, 0, 1, 0, 0, 0)), List.of());

        List<Candidate> got = ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE);

        assertEquals(1, got.size());
        // Must detour the long way: (0,0,0)->(0,0,1)->(1,0,1)->(1,0,0).
        assertArrayEquals(
            new long[] {key(0, 0, 0), key(0, 0, 1), key(1, 0, 1), key(1, 0, 0)},
            got.get(0).path());
    }

    // 3b. NONE severs the ONLY route -> destination unreachable.
    @Test
    void noneFace_severingOnlyRoute_destinationUnreachable() {
        PipeNode p00 = itemPipe();
        p00.setFlowState(0, FlowState.NONE);
        PipeNode p10 = itemPipe();
        p10.setFlowState(PipeConnectivity.opposite(0), FlowState.NONE);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, p00).put(1, 0, 0, p10);
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(List.of(dest(2, 0, 0, 1, 0, 0, 0)), List.of());

        List<Candidate> got = ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE);

        assertTrue(got.isEmpty(), "no route to the via pipe -> no candidate");
    }

    // 4. Filter-gated junction: a middle pipe rejects the key -> impassable for it, alternate used;
    //    a different key passes (per-stack impassability).
    @Test
    void filterGatedJunction_blocksRejectedKey_alternateUsed_otherKeyPasses() {
        // Same square loop, all faces open. The DIRECT-route junction pipe (1,0,0) has a filter that
        // rejects COBBLE but admits IRON. For COBBLE the entry must detour around (1,0,0)... but the
        // destination's via pipe IS (1,0,0), so COBBLE cannot reach it AT ALL (entering it is blocked).
        // To prove "alternate used", route to a container off (1,0,1) instead, whose via pipe is open.
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe())
            .put(1, 0, 1, itemPipe())
            .put(0, 0, 1, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        // Container off (1,0,1) via +X (face 0). Two routes from entry (0,0,0):
        //   A: (0,0,0)->(1,0,0)->(1,0,1)   (passes through (1,0,0))
        //   B: (0,0,0)->(0,0,1)->(1,0,1)   (avoids (1,0,0))
        Endpoints endpoints = new Endpoints(List.of(dest(2, 0, 1, 1, 0, 1, 0)), List.of());

        long junction = key(1, 0, 0);
        FilterLookup rejectCobbleAtJunction = pipeKey -> pipeKey == junction
            ? (k, pk, face) -> k == null || !"cobblestone".equals(k.id())
            : ItemFilter.ALLOW_ALL;

        // COBBLE: cannot enter (1,0,0), must take route B (the detour).
        List<Candidate> cobble = ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, rejectCobbleAtJunction);
        assertEquals(1, cobble.size());
        assertArrayEquals(
            new long[] {key(0, 0, 0), key(0, 0, 1), key(1, 0, 1)},
            cobble.get(0).path(),
            "rejected key must detour around the filtering junction");

        // IRON: admitted everywhere, BFS takes the shorter route A through (1,0,0).
        List<Candidate> iron = ItemPathfinder.candidates(
            key(0, 0, 0), IRON, endpoints, net, grid, rejectCobbleAtJunction);
        assertEquals(1, iron.size());
        assertArrayEquals(
            new long[] {key(0, 0, 0), key(1, 0, 0), key(1, 0, 1)},
            iron.get(0).path(),
            "admitted key takes the shortest route through the junction");
    }

    // 4b. Entry pipe's OWN filter never gates: a stack already inside the entry pipe is not re-checked.
    @Test
    void entryPipeFilter_doesNotGateTheStackAlreadyInside() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(List.of(dest(1, 1, 0, 1, 0, 0, 2)), List.of());

        long entry = key(0, 0, 0);
        // Even though the ENTRY pipe rejects cobble, the stack is already in it: it still routes out.
        FilterLookup rejectAtEntry = pipeKey -> pipeKey == entry
            ? (k, pk, face) -> false
            : ItemFilter.ALLOW_ALL;

        List<Candidate> got = ItemPathfinder.candidates(
            entry, COBBLE, endpoints, net, grid, rejectAtEntry);

        assertEquals(1, got.size());
        assertArrayEquals(new long[] {key(0, 0, 0), key(1, 0, 0)}, got.get(0).path());
    }

    // 5. No destinations -> empty list.
    @Test
    void noDestinations_emptyList() {
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(List.of(), List.of());

        List<Candidate> got = ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE);

        assertTrue(got.isEmpty());
    }

    // 7. Mid-transit re-route: candidates() from a mid-path segment returns paths FROM THERE.
    @Test
    void midTransitReroute_pathsStartFromCurrentSegment() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe())
            .put(2, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(List.of(dest(3, 0, 0, 2, 0, 0, 0)), List.of());

        // A stack now sitting in the MIDDLE pipe (1,0,0) re-routes: its path starts at (1,0,0).
        List<Candidate> got = ItemPathfinder.candidates(
            key(1, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE);

        assertEquals(1, got.size());
        assertArrayEquals(new long[] {key(1, 0, 0), key(2, 0, 0)}, got.get(0).path());
    }

    // Determinism: candidates() is a pure function of its inputs (repeat call -> identical ordering).
    @Test
    void deterministicAcrossCalls() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(
            List.of(dest(1, 1, 0, 1, 0, 0, 2), dest(1, 0, 1, 1, 0, 0, 4)), List.of());

        List<Long> first = order(ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE));
        List<Long> second = order(ItemPathfinder.candidates(
            key(0, 0, 0), COBBLE, endpoints, net, grid, FilterLookup.NONE));
        assertEquals(first, second);
    }

    private static List<Long> order(List<Candidate> cands) {
        List<Long> out = new ArrayList<>();
        for (Candidate c : cands) {
            out.add(c.destination().containerKey());
        }
        return out;
    }
}
