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

    /** A MachineLookup reporting a machine with one ITEM port (channel/dir on a face) at a fixed cell. */
    private static final class FakeMachineLookup
            implements com.chonbosmods.chemistry.impl.block.net.MachineLookup {
        private final java.util.Map<Long, com.chonbosmods.chemistry.impl.block.PortConfig> byKey =
            new java.util.HashMap<>();

        FakeMachineLookup put(int x, int y, int z, int face,
                com.chonbosmods.chemistry.api.io.PortDirection dir) {
            byKey.put(NetworkManager.packKey(x, y, z),
                com.chonbosmods.chemistry.impl.block.PortConfig.of(java.util.List.of(
                    com.chonbosmods.chemistry.impl.block.Port.of(face, PortChannel.ITEM, dir))));
            return this;
        }

        @Override
        public MachinePorts at(int x, int y, int z) {
            com.chonbosmods.chemistry.impl.block.PortConfig cfg = byKey.get(NetworkManager.packKey(x, y, z));
            if (cfg == null) {
                return null;
            }
            return new MachinePorts() {
                @Override
                public com.chonbosmods.chemistry.impl.block.PortConfig ports() {
                    return cfg;
                }

                @Override
                public com.chonbosmods.chemistry.api.energy.EnergyHandler energy() {
                    return null;
                }

                @Override
                public com.chonbosmods.chemistry.impl.block.ResourceBuffer resource(PortChannel channel) {
                    return null;
                }
            };
        }
    }

    @Test
    void machineInputPort_normalFace_isDestinationOnly() {
        // Pipe (5,5,5) face 0 (+X) NORMAL reaches a machine at (6,5,5) whose facing face (1, -X) carries an
        // ITEM INPUT port: the network delivers INTO it (a Destination), never extracts.
        PipeGridView grid = itemPipeWithFace(5, 5, 5, 0, FlowState.NORMAL);
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeMachineLookup machines = new FakeMachineLookup()
            .put(6, 5, 5, 1, com.chonbosmods.chemistry.api.io.PortDirection.INPUT);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, new FakeContainerLookup(), machines);
        assertEquals(1, endpoints.destinations().size());
        assertEquals(0, endpoints.sources().size());
        assertEquals(NetworkManager.packKey(6, 5, 5), endpoints.destinations().get(0).containerKey());
    }

    @Test
    void machineOutputPort_pullFace_isSourceOnly() {
        PipeGridView grid = itemPipeWithFace(5, 5, 5, 0, FlowState.PULL);
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeMachineLookup machines = new FakeMachineLookup()
            .put(6, 5, 5, 1, com.chonbosmods.chemistry.api.io.PortDirection.OUTPUT);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, new FakeContainerLookup(), machines);
        assertEquals(0, endpoints.destinations().size());
        assertEquals(1, endpoints.sources().size());
        assertEquals(NetworkManager.packKey(6, 5, 5), endpoints.sources().get(0).containerKey());
    }

    @Test
    void machineInputPort_pullFace_doesNotExtract() {
        // INPUT port + PULL face: no overlap (an input-only port offers nothing to extract). Neither list.
        PipeGridView grid = itemPipeWithFace(5, 5, 5, 0, FlowState.PULL);
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeMachineLookup machines = new FakeMachineLookup()
            .put(6, 5, 5, 1, com.chonbosmods.chemistry.api.io.PortDirection.INPUT);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, new FakeContainerLookup(), machines);
        assertEquals(0, endpoints.destinations().size());
        assertEquals(0, endpoints.sources().size());
    }

    @Test
    void machineOutputPort_pushFace_doesNotDeliver() {
        // OUTPUT port + PUSH face: no overlap. Neither list.
        PipeGridView grid = itemPipeWithFace(5, 5, 5, 0, FlowState.PUSH);
        Network net = itemNetworkAt(5, 5, 5, grid);
        FakeMachineLookup machines = new FakeMachineLookup()
            .put(6, 5, 5, 1, com.chonbosmods.chemistry.api.io.PortDirection.OUTPUT);

        Endpoints endpoints = ItemEndpoints.collect(net, grid, new FakeContainerLookup(), machines);
        assertEquals(0, endpoints.destinations().size());
        assertEquals(0, endpoints.sources().size());
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
}
