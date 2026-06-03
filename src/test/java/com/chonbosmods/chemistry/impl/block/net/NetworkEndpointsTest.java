package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
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

    private static PortConfig portConfig(PortChannel channel, PortDirection direction) {
        return PortConfig.of(List.of(Port.of(0, channel, direction)));
    }

    @Test
    void collect_powerNetwork_findsOneProviderAndOneAcceptor() {
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        // OUTPUT-power machine at +X -> provider; INPUT-power machine at -X -> acceptor.
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(PortChannel.POWER, PortDirection.OUTPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));
        lookup.put(4, 5, 5, new FakePorts(
            portConfig(PortChannel.POWER, PortDirection.INPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, lookup);
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
            portConfig(PortChannel.FLUID, PortDirection.OUTPUT),
            null, Map.of(PortChannel.FLUID, ResourceBuffer.withCapacity(1000))));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, lookup);
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
            portConfig(PortChannel.POWER, PortDirection.OUTPUT), null, Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, lookup);
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
        lookup.put(5, 6, 5, new FakePorts(
            portConfig(PortChannel.FLUID, PortDirection.OUTPUT),
            null, Map.of(PortChannel.FLUID, source)));
        lookup.put(5, 4, 5, new FakePorts(
            portConfig(PortChannel.FLUID, PortDirection.INPUT),
            null, Map.of(PortChannel.FLUID, ResourceBuffer.withCapacity(1000))));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, lookup);
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
        lookup.put(6, 6, 5, new FakePorts(
            portConfig(PortChannel.POWER, PortDirection.OUTPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, lookup);
        assertEquals(1, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
    }

    @Test
    void collect_storageWithBothPorts_classifiedAsBufferProviderAndBufferAcceptor() {
        // A storage battery: a single neighbour that has BOTH a POWER OUTPUT and a POWER INPUT port.
        // It must contribute ONE entry to bufferProviders AND ONE to bufferAcceptors (never to the
        // pure lists), so the priority logic can treat it as storage rather than a source/sink.
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        PortConfig both = PortConfig.of(List.of(
            Port.of(0, PortChannel.POWER, PortDirection.OUTPUT),
            Port.of(1, PortChannel.POWER, PortDirection.INPUT)));
        lookup.put(6, 5, 5, new FakePorts(both, EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, lookup);
        assertEquals(0, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
        assertEquals(1, endpoints.bufferProviders().size());
        assertEquals(1, endpoints.bufferAcceptors().size());
    }

    @Test
    void collect_pureProviderAndStorageTogether_areClassifiedSeparately() {
        // A pure source (OUTPUT only) at +X and a storage (BOTH) at -X on the same POWER network.
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));

        FakeLookup lookup = new FakeLookup();
        lookup.put(6, 5, 5, new FakePorts(
            portConfig(PortChannel.POWER, PortDirection.OUTPUT),
            EnergyBuffer.withCapacity(1000L), Map.of()));
        PortConfig both = PortConfig.of(List.of(
            Port.of(0, PortChannel.POWER, PortDirection.OUTPUT),
            Port.of(1, PortChannel.POWER, PortDirection.INPUT)));
        lookup.put(4, 5, 5, new FakePorts(both, EnergyBuffer.withCapacity(1000L), Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, lookup);
        assertEquals(1, endpoints.pureProviders().size());
        assertEquals(0, endpoints.pureAcceptors().size());
        assertEquals(1, endpoints.bufferProviders().size());
        assertEquals(1, endpoints.bufferAcceptors().size());
    }

    @Test
    void collect_emptyNeighbours_yieldsEmptyLists() {
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(5, 5, 5, singlePowerPipeAt(5, 5, 5));
        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, new FakeLookup());
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
            portConfig(PortChannel.POWER, PortDirection.OUTPUT), source, Map.of()));
        lookup.put(4, 5, 5, new FakePorts(
            portConfig(PortChannel.POWER, PortDirection.INPUT), sink, Map.of()));

        NetworkEndpoints.Endpoints endpoints = NetworkEndpoints.collect(net, lookup);
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
