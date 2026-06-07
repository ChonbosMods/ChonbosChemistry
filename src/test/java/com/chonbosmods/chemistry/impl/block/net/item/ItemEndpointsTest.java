package com.chonbosmods.chemistry.impl.block.net.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ItemEndpoints#collect}: walks an ITEM network's member pipes' face-neighbours
 * and classifies any adjacent container by the bordering pipe FACE flow state alone (NORMAL/PUSH =
 * destination, PULL = source, NONE = skip). Uses an in-memory {@link PipeGridView} and a position-set
 * {@link ContainerLookup}. Mirrors {@code NetworkEndpointsTest}'s fakes and its NONE-neutrality
 * determinism technique.
 */
class ItemEndpointsTest {

    /** A no-op container view: ItemEndpoints qualifies on presence only, never calls into the view. */
    private static final ContainerView STUB_VIEW = new ContainerView() {
        @Override
        public int insert(ItemKey key, org.bson.BsonDocument metadata, int amount, boolean simulate) {
            return 0;
        }

        @Override
        public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
            return null;
        }

        @Override
        public Extracted extract(ItemKey key, int amount, boolean simulate) {
            return new Extracted(0, null);
        }
    };

    /** A ContainerLookup that reports a (stub) container at any position in a fixed set. */
    private static final class FakeContainerLookup implements ContainerLookup {
        private final Set<Long> positions = new HashSet<>();

        void put(int x, int y, int z) {
            positions.add(NetworkManager.packKey(x, y, z));
        }

        @Override
        public ContainerView at(int x, int y, int z) {
            return positions.contains(NetworkManager.packKey(x, y, z)) ? STUB_VIEW : null;
        }
    }

    /** A single ITEM pipe at (x,y,z) whose {@code face} carries {@code state}; all other faces NORMAL. */
    private static PipeGridView itemPipeWithFace(int x, int y, int z, int face, FlowState state) {
        long key = NetworkManager.packKey(x, y, z);
        PipeNode node = PipeNode.of(PortChannel.ITEM, 1);
        node.setFlowState(face, state);
        return (px, py, pz) -> NetworkManager.packKey(px, py, pz) == key ? node : null;
    }

    private static Network itemNetworkAt(int x, int y, int z, PipeGridView grid) {
        return new NetworkManager().getOrBuildNetwork(x, y, z, grid);
    }

    @Test
    void normalFace_container_isDestinationNotSource() {
        // Pipe (5,5,5) reaches the container at (6,5,5) via face 0 (+X), NORMAL.
        PipeGridView grid = itemPipeWithFace(5, 5, 5, 0, FlowState.NORMAL);
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeContainerLookup containers = new FakeContainerLookup();
        containers.put(6, 5, 5);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertEquals(1, endpoints.destinations().size());
        assertEquals(0, endpoints.sources().size());
        ItemEndpoints.Destination dest = endpoints.destinations().get(0);
        assertEquals(NetworkManager.packKey(6, 5, 5), dest.containerKey());
        assertEquals(NetworkManager.packKey(5, 5, 5), dest.viaPipeKey());
        assertEquals(0, dest.viaFace());
    }

    @Test
    void pullFace_container_isSourceNotDestination() {
        PipeGridView grid = itemPipeWithFace(5, 5, 5, 0, FlowState.PULL);
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeContainerLookup containers = new FakeContainerLookup();
        containers.put(6, 5, 5);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertEquals(0, endpoints.destinations().size());
        assertEquals(1, endpoints.sources().size());
        ItemEndpoints.Source src = endpoints.sources().get(0);
        assertEquals(NetworkManager.packKey(6, 5, 5), src.containerKey());
        assertEquals(NetworkManager.packKey(5, 5, 5), src.viaPipeKey());
        assertEquals(0, src.viaFace());
    }

    @Test
    void pushFace_container_isDestinationOnly() {
        PipeGridView grid = itemPipeWithFace(5, 5, 5, 0, FlowState.PUSH);
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeContainerLookup containers = new FakeContainerLookup();
        containers.put(6, 5, 5);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertEquals(1, endpoints.destinations().size());
        assertEquals(0, endpoints.sources().size());
    }

    @Test
    void noneFace_container_isNeither() {
        PipeGridView grid = itemPipeWithFace(5, 5, 5, 0, FlowState.NONE);
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeContainerLookup containers = new FakeContainerLookup();
        containers.put(6, 5, 5);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertEquals(0, endpoints.destinations().size());
        assertEquals(0, endpoints.sources().size());
    }

    @Test
    void noneFaceIsDedupNeutral_containerStillCollectedViaNormalSide() {
        // Mirrors NetworkEndpointsTest.noneFaceDoesNotClaimTheNeighbourDedupSlot determinism trick.
        // ONE network, two member pipes BOTH bordering one container at (6,6,5):
        //   - pipe (6,5,5) reaches it via its +Y face (2) = NONE  (visited FIRST: see below)
        //   - pipe (5,6,5) reaches it via its +X face (0) = NORMAL
        // collect walks net.memberKeys(); BFS enqueues neighbours in OFFSETS order (+X before +Y), so
        // the +X member (6,5,5) registers first and is visited FIRST. If a NONE face wrongly CLAIMED the
        // per-container dedup slot (add-before-NONE-check), the NORMAL-side member (5,6,5) would be
        // denied and the container would vanish. It must instead be collected as a destination via the
        // NORMAL side.
        PipeNode seed = PipeNode.of(PortChannel.ITEM, 1);   // (5,5,5): plain corner of the L
        PipeNode none = PipeNode.of(PortChannel.ITEM, 1);   // (6,5,5): +Y face (2) toward container = NONE
        none.setFlowState(2, FlowState.NONE);
        PipeNode normal = PipeNode.of(PortChannel.ITEM, 1); // (5,6,5): +X face (0) toward container NORMAL
        PipeGridView grid = (px, py, pz) -> {
            long k = NetworkManager.packKey(px, py, pz);
            if (k == NetworkManager.packKey(5, 5, 5)) {
                return seed;
            }
            if (k == NetworkManager.packKey(6, 5, 5)) {
                return none;
            }
            if (k == NetworkManager.packKey(5, 6, 5)) {
                return normal;
            }
            return null;
        };
        Network net = itemNetworkAt(5, 5, 5, grid);

        FakeContainerLookup containers = new FakeContainerLookup();
        containers.put(6, 6, 5);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertEquals(1, endpoints.destinations().size(),
            "container reachable via the NORMAL face must be collected; NONE is dedup-neutral");
        assertEquals(0, endpoints.sources().size());
        // Collected through the NORMAL-side member pipe.
        assertEquals(NetworkManager.packKey(5, 6, 5), endpoints.destinations().get(0).viaPipeKey());
    }

    @Test
    void pipeOccupiedNeighbour_isIgnoredEvenIfLookupClaimsContainer() {
        // Two connected ITEM pipes (5,5,5)-(6,5,5). The container lookup maliciously claims a container
        // AT (6,5,5) too: the pipe-occupancy check (grid.pipeAt != null) must win, so (6,5,5) is NOT a
        // container endpoint. (5,5,5) only borders the pipe at +X; no other neighbour has a container.
        PipeGridView grid = (px, py, pz) -> {
            long k = NetworkManager.packKey(px, py, pz);
            if (k == NetworkManager.packKey(5, 5, 5) || k == NetworkManager.packKey(6, 5, 5)) {
                return PipeNode.of(PortChannel.ITEM, 1);
            }
            return null;
        };
        Network net = itemNetworkAt(5, 5, 5, grid);

        FakeContainerLookup containers = new FakeContainerLookup();
        containers.put(6, 5, 5); // overlaps the pipe cell: must be ignored

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertEquals(0, endpoints.destinations().size());
        assertEquals(0, endpoints.sources().size());
    }

    @Test
    void nonItemNetwork_returnsEmpty_defensiveGuard() {
        // ItemEndpoints is only meaningful for ITEM networks. A POWER network with an adjacent container
        // must yield nothing (defensive: the item subsystem never runs on other channels).
        long key = NetworkManager.packKey(5, 5, 5);
        PipeGridView grid = (px, py, pz) ->
            NetworkManager.packKey(px, py, pz) == key ? PipeNode.of(PortChannel.POWER, 1) : null;
        Network net = itemNetworkAt(5, 5, 5, grid);

        FakeContainerLookup containers = new FakeContainerLookup();
        containers.put(6, 5, 5);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertTrue(endpoints.destinations().isEmpty());
        assertTrue(endpoints.sources().isEmpty());
    }

    @Test
    void emptyNeighbours_yieldsEmptyEndpoints() {
        PipeGridView grid = itemPipeWithFace(5, 5, 5, 0, FlowState.NORMAL);
        Network net = itemNetworkAt(5, 5, 5, grid);
        Endpoints endpoints = ItemEndpoints.collect(net, grid, new FakeContainerLookup());
        assertEquals(0, endpoints.destinations().size());
        assertEquals(0, endpoints.sources().size());
    }

    // --- connection cap: a pipe joins at most 3 non-pipe endpoints (2026-06-07 design Decision 1) ---

    /** A single ITEM pipe at (x,y,z) with all 6 faces NORMAL. */
    private static PipeGridView itemPipeAllNormal(int x, int y, int z) {
        long key = NetworkManager.packKey(x, y, z);
        PipeNode node = PipeNode.of(PortChannel.ITEM, 1);
        return (px, py, pz) -> NetworkManager.packKey(px, py, pz) == key ? node : null;
    }

    @Test
    void cap_fourContainersAroundOnePipe_onlyThreeLowestFacesCollected() {
        // One pipe (5,5,5), containers on faces 0(+X),1(-X),2(+Y),3(-Y). The cap is 3: face-index order
        // wins, so faces 0,1,2 collect and face 3 (-Y) is skipped ENTIRELY.
        PipeGridView grid = itemPipeAllNormal(5, 5, 5);
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeContainerLookup containers = new FakeContainerLookup();
        containers.put(6, 5, 5); // face 0 +X
        containers.put(4, 5, 5); // face 1 -X
        containers.put(5, 6, 5); // face 2 +Y
        containers.put(5, 4, 5); // face 3 -Y (the 4th: must be dropped)

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertEquals(3, endpoints.destinations().size(), "cap drops the 4th endpoint");
        Set<Integer> faces = new HashSet<>();
        for (ItemEndpoints.Destination d : endpoints.destinations()) {
            faces.add(d.viaFace());
        }
        assertTrue(faces.contains(0) && faces.contains(1) && faces.contains(2),
            "the three lowest faces win the slots");
        assertTrue(!faces.contains(3), "face 3 (-Y) is the 4th and must be skipped");
    }

    @Test
    void cap_noneFaceDoesNotConsumeBudget_lowerCountThatShifts() {
        // Faces 0,1,2,3 have containers but face 1 (-X) is NONE: NONE does NOT count against the cap, so
        // the qualifying faces are 0,2,3 (three of them) and ALL collect. The NONE face shifts which 3 win.
        long key = NetworkManager.packKey(5, 5, 5);
        PipeNode node = PipeNode.of(PortChannel.ITEM, 1);
        node.setFlowState(1, FlowState.NONE); // -X hidden, consumes no budget
        PipeGridView grid = (px, py, pz) -> NetworkManager.packKey(px, py, pz) == key ? node : null;
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeContainerLookup containers = new FakeContainerLookup();
        containers.put(6, 5, 5); // face 0 +X  -> collects
        containers.put(4, 5, 5); // face 1 -X  -> NONE, skipped, no budget
        containers.put(5, 6, 5); // face 2 +Y  -> collects
        containers.put(5, 4, 5); // face 3 -Y  -> collects (would be dropped if NONE counted)

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertEquals(3, endpoints.destinations().size());
        Set<Integer> faces = new HashSet<>();
        for (ItemEndpoints.Destination d : endpoints.destinations()) {
            faces.add(d.viaFace());
        }
        assertTrue(faces.contains(0) && faces.contains(2) && faces.contains(3),
            "NONE face -X consumes no budget; faces 0,2,3 all win");
        assertTrue(!faces.contains(1), "the NONE face never collects");
    }

    @Test
    void cap_isPerPipeNotPerNetwork_twoPipesThreeEach() {
        // Two connected ITEM pipes (5,5,5)-(6,5,5) joined at the +X/-X faces. Each borders 3 OTHER
        // containers. Per-pipe budget: 3 each = 6 total, none dropped.
        PipeNode a = PipeNode.of(PortChannel.ITEM, 1); // (5,5,5)
        PipeNode b = PipeNode.of(PortChannel.ITEM, 1); // (6,5,5)
        PipeGridView grid = (px, py, pz) -> {
            long k = NetworkManager.packKey(px, py, pz);
            if (k == NetworkManager.packKey(5, 5, 5)) {
                return a;
            }
            if (k == NetworkManager.packKey(6, 5, 5)) {
                return b;
            }
            return null;
        };
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeContainerLookup containers = new FakeContainerLookup();
        // pipe a (5,5,5): containers on -X, +Y, -Y (3)
        containers.put(4, 5, 5);
        containers.put(5, 6, 5);
        containers.put(5, 4, 5);
        // pipe b (6,5,5): containers on +X, +Y, -Y (3)
        containers.put(7, 5, 5);
        containers.put(6, 6, 5);
        containers.put(6, 4, 5);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
        assertEquals(6, endpoints.destinations().size(),
            "per-pipe cap: two pipes x 3 each = 6, nothing dropped");
    }
}
