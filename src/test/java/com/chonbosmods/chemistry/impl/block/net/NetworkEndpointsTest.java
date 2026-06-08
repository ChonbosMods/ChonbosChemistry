package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.EnergyBuffer;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup.MachinePorts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkEndpoints#collect}: walks a network's member pipes' face-neighbours and
 * adapts matching-channel machines to providers/acceptors. Uses an in-memory fake {@link MachineLookup}
 * and builds the network through a fake {@link PipeGridView}.
 */
class NetworkEndpointsTest {

    /** A fake endpoint exposing a fixed PortConfig + optional energy + per-channel resource buffers. */
    private static final class FakePorts implements MachinePorts {
        private final PortConfig ports;
        private final EnergyHandler energy;
        private final Map<PortChannel, ResourceBuffer> buffers;

        FakePorts(PortConfig ports, EnergyHandler energy, Map<PortChannel, ResourceBuffer> buffers) {
            this.ports = ports;
            this.energy = energy;
            this.buffers = buffers;
        }

        @Override
        public PortConfig ports() {
            return ports;
        }

        @Override
        public EnergyHandler energy() {
            return energy;
        }

        @Override
        public ResourceBuffer resource(PortChannel channel) {
            return buffers.get(channel);
        }
    }

    private static final class FakeLookup implements MachineLookup {
        private final Map<Long, MachinePorts> byKey = new HashMap<>();

        void put(int x, int y, int z, MachinePorts ports) {
            byKey.put(NetworkManager.packKey(x, y, z), ports);
        }

        @Override
        public MachinePorts at(int x, int y, int z) {
            return byKey.get(NetworkManager.packKey(x, y, z));
        }
    }

    /** Fake grid with one POWER pipe at the origin, for building a single-member network. */
    private static PipeGridView singlePowerPipeAt(int x, int y, int z) {
        long key = NetworkManager.packKey(x, y, z);
        return (px, py, pz) ->
            NetworkManager.packKey(px, py, pz) == key ? PipeNode.of(PortChannel.POWER, 1) : null;
    }

    private static PipeConfigGrid singleFluidPipeAt(int x, int y, int z) {
        return new PipeConfigGrid(x, y, z, PortChannel.FLUID);
    }

    /** A one-pipe grid of a given channel. */
    private record PipeConfigGrid(int x, int y, int z, PortChannel channel) implements PipeGridView {
        @Override
        public PipeNode pipeAt(int px, int py, int pz) {
            return (px == x && py == y && pz == z) ? PipeNode.of(channel, 1) : null;
        }
    }

    /**
     * A single POWER pipe at (x,y,z) whose face {@code face} carries flow state {@code state} (all other
     * faces NORMAL). Lets the flow-filter tests drive the per-face PUSH/PULL/NONE matrix. The same node
     * instance is returned on every lookup so the network's seed pipe and the collected member pipe share
     * state.
     */
    private static PipeGridView powerPipeWithFace(int x, int y, int z, int face, FlowState state) {
        long key = NetworkManager.packKey(x, y, z);
        PipeNode node = PipeNode.of(PortChannel.POWER, 1);
        node.setFlowState(face, state);
        return (px, py, pz) -> NetworkManager.packKey(px, py, pz) == key ? node : null;
    }

    /** A single port on the given face: the port that faces back at a pipe under face-precise matching. */
    private static PortConfig portConfig(int face, PortChannel channel, PortDirection direction) {
        return PortConfig.of(List.of(Port.of(face, channel, direction)));
    }

    @Test
    void collect_powerNetwork_findsOneProviderAndOneAcceptor() {
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        // OUTPUT-power machine at +X (faces pipe on its -X face = 1) -> provider;
        // INPUT-power machine at -X (faces pipe on its +X face = 0) -> acceptor.
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.POWER, PortDirection.OUTPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));
        lookup.put(4, 5, 5, new FakePorts(
            portConfig(0, PortChannel.POWER, PortDirection.INPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, singlePowerPipeAt(5, 5, 5), lookup);
        assertEquals(1, endpoints.pureProviders().size());
        assertEquals(1, endpoints.pureAcceptors().size());
        assertEquals(0, endpoints.bufferProviders().size());
        assertEquals(0, endpoints.bufferAcceptors().size());
    }

    @Test
    void collect_fluidMachineOnPowerNetwork_yieldsNeither() {
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        // A FLUID machine adjacent to a POWER network: wrong channel, ignored.
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.FLUID, PortDirection.OUTPUT),
            null, Map.of(PortChannel.FLUID, ResourceBuffer.withCapacity(1000))));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, singlePowerPipeAt(5, 5, 5), lookup);
        assertEquals(0, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
    }

    @Test
    void collect_powerOutputWithoutEnergyHandler_isSkipped() {
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        // Has a POWER OUTPUT port but no energy handler -> no adapter, skipped.
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.POWER, PortDirection.OUTPUT), null, Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, singlePowerPipeAt(5, 5, 5), lookup);
        assertEquals(0, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
    }

    @Test
    void collect_fluidNetwork_findsResourceProviderAndAcceptor() {
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singleFluidPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        ResourceBuffer source = ResourceBuffer.withCapacity(1000);
        source.insert("oxygen", 200, false);
        // OUTPUT tank at +Y (faces pipe on -Y = 3); INPUT tank at -Y (faces pipe on +Y = 2).
        lookup.put(5, 6, 5, new FakePorts(
            portConfig(3, PortChannel.FLUID, PortDirection.OUTPUT),
            null, Map.of(PortChannel.FLUID, source)));
        lookup.put(5, 4, 5, new FakePorts(
            portConfig(2, PortChannel.FLUID, PortDirection.INPUT),
            null, Map.of(PortChannel.FLUID, ResourceBuffer.withCapacity(1000))));

        NetworkEndpoints.Endpoints endpoints =
            NetworkEndpoints.collect(net, singleFluidPipeAt(5, 5, 5), lookup);
        assertEquals(1, endpoints.pureProviders().size());
        assertEquals(1, endpoints.pureAcceptors().size());
        assertEquals("oxygen", endpoints.pureProviders().get(0).resourceId());
    }

    @Test
    void collect_machineBorderingTwoMemberPipes_isCollectedOnce() {
        // An L of three connected POWER pipes: (5,5,5)-(5,6,5)-(6,5,5). A single OUTPUT-power machine at
        // (6,6,5) is face-adjacent to TWO of them: (5,6,5) on its -X face and (6,5,5) on its -Y face.
        // Without dedup it would be wrapped as a provider TWICE (double share); we assert exactly one.
        NetworkManager manager = new NetworkManager();
        PipeGridView grid = (px, py, pz) -> {
            long k = NetworkManager.packKey(px, py, pz);
            if (k == NetworkManager.packKey(5, 5, 5)
                || k == NetworkManager.packKey(5, 6, 5)
                || k == NetworkManager.packKey(6, 5, 5)) {
                return PipeNode.of(PortChannel.POWER, 1);
            }
            return null;
        };
        Network net = manager.getOrBuildNetwork(5, 5, 5, grid);

        FakeLookup lookup = new FakeLookup();
        // The machine faces pipe (5,6,5) on its -X face (1) and pipe (6,5,5) on its -Y face (3): give it
        // an OUTPUT port on BOTH so it qualifies whichever member pipe the dedup encounters first.
        lookup.put(6, 6, 5, new FakePorts(
            PortConfig.of(List.of(
                Port.of(1, PortChannel.POWER, PortDirection.OUTPUT),
                Port.of(3, PortChannel.POWER, PortDirection.OUTPUT))),
            EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, grid, lookup);
        assertEquals(1, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
    }

    @Test
    void noneFaceDoesNotClaimTheNeighbourDedupSlot() {
        // Regression: order-dependent NONE-face dedup must not hide a machine reachable via another
        // member pipe. ONE network, two member pipes BOTH bordering one machine: pipe (5,6,5) reaches it
        // via a NONE face; pipe (6,5,5) via a NORMAL face. The machine has OUTPUT ports on BOTH touching
        // faces, so it qualifies as a pure provider through the NORMAL side regardless of which member
        // the dedup visits first. If a NONE face wrongly CLAIMS the per-neighbour dedup slot (add-before-
        // NONE-check), the NORMAL-side member is skipped and the machine vanishes.
        //
        // An L of three connected POWER pipes: (5,5,5)-(6,5,5)-(5,6,5). The machine sits at (6,6,5),
        // face-adjacent to (6,5,5) [on its +Y face 2] and to (5,6,5) [on its +X face 0]. The collect
        // pass walks net.memberKeys(); BFS enqueues neighbours in OFFSETS order (+X before +Y), so the
        // +X member (6,5,5) is registered first and is hit FIRST during collection. Make THAT first
        // member the NONE-face one: on the broken code it claims (6,6,5)'s dedup slot then continues,
        // and the later NORMAL-face member (5,6,5) is denied -> machine vanishes.
        NetworkManager manager = new NetworkManager();
        PipeNode none = PipeNode.of(PortChannel.POWER, 1); // (6,5,5): +Y face (2) toward machine = NONE
        none.setFlowState(2, FlowState.NONE);
        PipeNode normal = PipeNode.of(PortChannel.POWER, 1); // (5,6,5): +X face (0) toward machine NORMAL
        PipeNode seed = PipeNode.of(PortChannel.POWER, 1); // (5,5,5): plain corner of the L
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
        Network net = manager.getOrBuildNetwork(5, 5, 5, grid);

        FakeLookup lookup = new FakeLookup();
        // Machine faces (6,5,5) on its -Y face (3) and (5,6,5) on its -X face (1): OUTPUT on BOTH.
        lookup.put(6, 6, 5, new FakePorts(
            PortConfig.of(List.of(
                Port.of(3, PortChannel.POWER, PortDirection.OUTPUT),
                Port.of(1, PortChannel.POWER, PortDirection.OUTPUT))),
            EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, grid, lookup);
        // Reachable via the NORMAL face: collected exactly once. (The NONE face is dedup-neutral.)
        assertEquals(1, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
    }

    @Test
    void collect_storageWithBothPorts_classifiedAsBufferProviderAndBufferAcceptor() {
        // A storage battery: a single neighbour whose facing port is BOTH (two-way). Across a NORMAL
        // pipe face it must contribute ONE entry to bufferProviders AND ONE to bufferAcceptors (never to
        // the pure lists), so the priority logic can treat it as storage rather than a source/sink.
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        // Faces the pipe on its -X face (1).
        PortConfig both = portConfig(1, PortChannel.POWER, PortDirection.BOTH);
        lookup.put(6, 5, 5, new FakePorts(both, EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, singlePowerPipeAt(5, 5, 5), lookup);
        assertEquals(0, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
        assertEquals(1, endpoints.bufferProviders().size());
        assertEquals(1, endpoints.bufferAcceptors().size());
    }

    @Test
    void collect_storageWithBothPorts_alsoYieldsPairedStorageEndpoint() {
        // Balancing (2026-06-05 design) needs the PAIRED view with live gauges: the same storage that
        // feeds bufferProviders/bufferAcceptors must surface once in storages().
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        PortConfig both = portConfig(1, PortChannel.POWER, PortDirection.BOTH);
        EnergyBuffer battery = EnergyBuffer.withCapacity(1000L);
        battery.receiveEnergy(250L, false);
        lookup.put(6, 5, 5, new FakePorts(both, battery, Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, singlePowerPipeAt(5, 5, 5), lookup);
        assertEquals(1, endpoints.storages().size());
        StorageEndpoint storage = endpoints.storages().get(0);
        assertEquals(250L, storage.stored(), "gauges must read the live buffer");
        assertEquals(1000L, storage.capacity());

        // Pure endpoints never appear in storages().
        FakeLookup pureOnly = new FakeLookup();
        pureOnly.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.POWER, PortDirection.OUTPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));
        Network net2 = new NetworkManager().getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));
        assertEquals(0,
            NetworkEndpoints.collect(net2, singlePowerPipeAt(5, 5, 5), pureOnly).storages().size());
    }

    @Test
    void collect_pureProviderAndStorageTogether_areClassifiedSeparately() {
        // A pure source (OUTPUT only) at +X and a storage (BOTH) at -X on the same POWER network.
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.POWER, PortDirection.OUTPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));
        // Storage at -X faces the pipe on its +X face (0).
        PortConfig both = portConfig(0, PortChannel.POWER, PortDirection.BOTH);
        lookup.put(4, 5, 5, new FakePorts(both, EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, singlePowerPipeAt(5, 5, 5), lookup);
        assertEquals(1, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
        assertEquals(1, endpoints.bufferProviders().size());
        assertEquals(1, endpoints.bufferAcceptors().size());
    }

    // --- Face-precise flow-state filtering (2026-06-05 pipe flow-states design Task 6) ---

    @Test
    void portOnWrongFaceDoesNotQualify() {
        // Pipe (5,5,5), machine (6,5,5): the pipe reaches it via face 0 (+X), so the machine's facing
        // port must sit on face 1 (-X). An OUTPUT port on face 0 faces AWAY -> not collected.
        PipeGridView grid = singlePowerPipeAt(5, 5, 5);
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, grid);

        FakeLookup wrongFace = new FakeLookup();
        wrongFace.put(6, 5, 5, new FakePorts(
            portConfig(0, PortChannel.POWER, PortDirection.OUTPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));
        NetworkEndpoints.Endpoints away = NetworkEndpoints.collect(net, grid, wrongFace);
        assertEquals(0, away.pureProviders().size());
        assertEquals(0, away.pureAcceptors().size());

        // Same machine with the port on the facing face (1) -> collected as a pure provider.
        FakeLookup rightFace = new FakeLookup();
        rightFace.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.POWER, PortDirection.OUTPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));
        Network net2 = new NetworkManager().getOrBuildNetwork(5, 5, 5, grid);
        NetworkEndpoints.Endpoints facing = NetworkEndpoints.collect(net2, grid, rightFace);
        assertEquals(1, facing.pureProviders().size());
        assertEquals(0, facing.pureAcceptors().size());
    }

    @Test
    void noneFaceHidesMachine() {
        // Pipe face 0 = NONE: a machine with a valid facing port is invisible to the network.
        PipeGridView grid = powerPipeWithFace(5, 5, 5, 0, FlowState.NONE);
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, grid);

        FakeLookup lookup = new FakeLookup();
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.POWER, PortDirection.OUTPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, grid, lookup);
        assertEquals(0, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
        assertEquals(0, endpoints.bufferProviders().size());
        assertEquals(0, endpoints.bufferAcceptors().size());
        assertEquals(0, endpoints.storages().size());
    }

    @Test
    void pushFaceKeepsAcceptorHalfOnly() {
        // Machine has a BOTH facing port (a storage). Pipe face 0 = PUSH: the network only pushes INTO
        // the machine, so it joins as a buffer ACCEPTOR half only (no provider, NOT a paired storage).
        PipeGridView pushGrid = powerPipeWithFace(5, 5, 5, 0, FlowState.PUSH);
        NetworkManager manager = new NetworkManager();
        Network pushNet = manager.getOrBuildNetwork(5, 5, 5, pushGrid);
        FakeLookup lookup = new FakeLookup();
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.POWER, PortDirection.BOTH),
            EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints push = NetworkEndpoints.collect(pushNet, pushGrid, lookup);
        assertEquals(0, push.pureProviders().size());
        assertEquals(0, push.pureAcceptors().size());
        assertEquals(0, push.bufferProviders().size());
        assertEquals(1, push.bufferAcceptors().size());
        assertEquals(0, push.storages().size());

        // PULL: provider half only.
        PipeGridView pullGrid = powerPipeWithFace(5, 5, 5, 0, FlowState.PULL);
        Network pullNet = new NetworkManager().getOrBuildNetwork(5, 5, 5, pullGrid);
        NetworkEndpoints.Endpoints pull = NetworkEndpoints.collect(pullNet, pullGrid, lookup);
        assertEquals(1, pull.bufferProviders().size());
        assertEquals(0, pull.bufferAcceptors().size());
        assertEquals(0, pull.storages().size());
    }

    @Test
    void storagePairsOnlyWithNormalTwoWayAccess() {
        // BOTH facing port + NORMAL pipe face -> full StorageEndpoint (storages + both buffer lists).
        FakeLookup lookup = new FakeLookup();
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.POWER, PortDirection.BOTH),
            EnergyBuffer.withCapacity(1000L), Map.of()));

        PipeGridView normalGrid = powerPipeWithFace(5, 5, 5, 0, FlowState.NORMAL);
        Network normalNet = new NetworkManager().getOrBuildNetwork(5, 5, 5, normalGrid);
        NetworkEndpoints.Endpoints normal = NetworkEndpoints.collect(normalNet, normalGrid, lookup);
        assertEquals(1, normal.storages().size());
        assertEquals(1, normal.bufferProviders().size());
        assertEquals(1, normal.bufferAcceptors().size());

        // BOTH + PULL -> bufferProviders only, NO StorageEndpoint (balancing needs two-way access).
        PipeGridView pullGrid = powerPipeWithFace(5, 5, 5, 0, FlowState.PULL);
        Network pullNet = new NetworkManager().getOrBuildNetwork(5, 5, 5, pullGrid);
        NetworkEndpoints.Endpoints pull = NetworkEndpoints.collect(pullNet, pullGrid, lookup);
        assertEquals(0, pull.storages().size());
        assertEquals(1, pull.bufferProviders().size());
        assertEquals(0, pull.bufferAcceptors().size());
    }

    @Test
    void collect_emptyNeighbours_yieldsEmptyLists() {
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));
        NetworkEndpoints.Endpoints endpoints =
            NetworkEndpoints.collect(net, singlePowerPipeAt(5, 5, 5), new FakeLookup());
        assertEquals(0, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
        assertEquals(0, endpoints.bufferProviders().size());
        assertEquals(0, endpoints.bufferAcceptors().size());
    }

    @Test
    void collect_endToEnd_distributesFromProviderToAcceptorOverNetwork() {
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        EnergyBuffer source = EnergyBuffer.withCapacity(1000L);
        source.receiveEnergy(1000L, false);
        EnergyBuffer sink = EnergyBuffer.withCapacity(1000L);

        FakeLookup lookup = new FakeLookup();
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(1, PortChannel.POWER, PortDirection.OUTPUT), source, Map.of()));
        lookup.put(4, 5, 5, new FakePorts(
            portConfig(0, PortChannel.POWER, PortDirection.INPUT), sink, Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, singlePowerPipeAt(5, 5, 5), lookup);
        long moved = NetworkTransfer.distribute(net, endpoints);

        // Single tier-1 pipe network: the per-tick throughput cap bounds EACH phase, so one pass pulls
        // at most `throughput` from the source into the buffer and delivers at most `throughput` of it
        // to the sink. Energy now crawls: it takes many passes to move the full 1000 (this is the
        // "pipes visibly hold energy in transit" behavior). One pass moves exactly the throughput.
        long tput = PipeTiers.throughputForTier(1);
        assertEquals(tput, moved);
        assertEquals(1000L - tput, source.getStored());
        assertEquals(tput, sink.getStored());
        assertEquals(0L, net.stored()); // all that entered the buffer this pass also left it.
    }
}
