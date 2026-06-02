package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves that a power cable's defining behavior (design §5.3) is EMERGENT from the existing
 * {@link TransferNode} + {@link EnergyBuffer} + {@link TransportEngine} machinery, so there is
 * intentionally NO dedicated {@code CableBuffer} production class (YAGNI).
 *
 * <p>A "cable" is just a {@link TransferNode} that
 * <ul>
 *   <li>owns an {@link EnergyBuffer} with a small capacity (it stores energy in transit, it does
 *       not teleport it instantaneously down the wire), and</li>
 *   <li>exposes BOTH a POWER INPUT port (so upstream can push energy in) and a POWER OUTPUT port
 *       (so it can push energy onward).</li>
 * </ul>
 *
 * <p>From that, the three cable rules fall out of {@link TransportEngine#pushEnergy} unchanged:
 * <ol>
 *   <li><b>buffered / non-teleport</b>: one push only fills the cable's buffer; the energy is
 *       mid-transit until the cable itself is pushed;</li>
 *   <li><b>one hop per push pass</b>: ticking each node once advances energy exactly one node;</li>
 *   <li><b>full cable stops the upstream pull</b> (design rule 7): when the cable buffer is full it
 *       accepts 0, so the simulate-then-commit engine commits nothing and the source is untouched.</li>
 * </ol>
 *
 * <p>Uses local fixtures equivalent to {@code TransportEngineTest}'s (those are private to that
 * class) and the simplified transport model (faceIndex == direction; a transfer happens if the
 * neighbor has any same-channel INPUT port).
 */
class CableHopTest {

    // --- Test fixtures (mirror TransportEngineTest, which keeps them private) ------------------

    /** A minimal {@link TransferNode}: ports + energy handler + throughput. Energy-only here. */
    private static final class FakeNode implements TransferNode {
        private final PortConfig ports;
        private final EnergyHandler energy;
        private final int throughput;

        FakeNode(PortConfig ports, EnergyHandler energy, int throughput) {
            this.ports = ports;
            this.energy = energy;
            this.throughput = throughput;
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
            return null;
        }

        @Override
        public int throughput(PortChannel channel) {
            return throughput;
        }
    }

    private static NeighborView single(int dir, TransferNode node) {
        Map<Integer, TransferNode> map = new HashMap<>();
        map.put(dir, node);
        return map::get;
    }

    private static EnergyBuffer energy(int capacity, int fill) {
        EnergyBuffer b = EnergyBuffer.withCapacity(capacity);
        if (fill > 0) {
            b.receiveEnergy(fill, false);
        }
        return b;
    }

    private static FakeNode sourceNode(EnergyBuffer buf, int throughput) {
        return new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.POWER, PortDirection.OUTPUT))),
            buf, throughput);
    }

    /** A cable: small buffer, both INPUT (face 1, accepts upstream) and OUTPUT (face 0, pushes on). */
    private static FakeNode cableNode(EnergyBuffer buf, int throughput) {
        return new FakeNode(
            PortConfig.of(List.of(
                Port.of(0, PortChannel.POWER, PortDirection.OUTPUT),
                Port.of(1, PortChannel.POWER, PortDirection.INPUT))),
            buf, throughput);
    }

    private static FakeNode sinkNode(EnergyBuffer buf, int throughput) {
        return new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.POWER, PortDirection.INPUT))),
            buf, throughput);
    }

    // --- Tests ---------------------------------------------------------------------------------

    @Test
    void energyBuffersInCableMidTransit() {
        // SOURCE(100/100, tp 40) -> CABLE(cap 25, tp 40) -> SINK(cap 1000)
        EnergyBuffer srcEnergy = energy(100, 100);
        EnergyBuffer cableEnergy = energy(25, 0);
        EnergyBuffer sinkEnergy = energy(1000, 0);

        FakeNode source = sourceNode(srcEnergy, 40);
        FakeNode cable = cableNode(cableEnergy, 40);
        FakeNode sink = sinkNode(sinkEnergy, 40);

        // Push the SOURCE only. Energy lands in the cable's buffer and goes no further this pass.
        TransportEngine.pushEnergy(source, single(0, cable));

        // min(throughput 40, cable free space 25, source stored 100) = 25 -> capacity-limited.
        assertEquals(25, cableEnergy.getStored(), "cable buffered exactly its free capacity");
        assertEquals(0, sinkEnergy.getStored(), "energy is mid-transit, not teleported to the sink");
        assertEquals(75, srcEnergy.getStored(), "source dropped by the buffered amount");
    }

    @Test
    void oneHopPerPushPass() {
        // Capacities/throughput chosen so the numbers are unambiguous:
        //   SOURCE 100/100, tp 40 ; CABLE cap 25, tp 40 ; SINK cap 1000, tp 40
        EnergyBuffer srcEnergy = energy(100, 100);
        EnergyBuffer cableEnergy = energy(25, 0);
        EnergyBuffer sinkEnergy = energy(1000, 0);

        FakeNode source = sourceNode(srcEnergy, 40);
        FakeNode cable = cableNode(cableEnergy, 40);
        FakeNode sink = sinkNode(sinkEnergy, 40);

        NeighborView sourceView = single(0, cable);
        NeighborView cableView = single(0, sink);

        // Pass 1: tick the source. Energy advances one hop: SOURCE -> CABLE only.
        TransportEngine.pushEnergy(source, sourceView);
        assertEquals(75, srcEnergy.getStored(), "after hop 1 source dropped by 25");
        assertEquals(25, cableEnergy.getStored(), "after hop 1 cable holds 25");
        assertEquals(0, sinkEnergy.getStored(), "after hop 1 sink still empty");

        // Pass 2: tick the cable. Energy advances one more hop: CABLE -> SINK.
        TransportEngine.pushEnergy(cable, cableView);
        // cable forwards min(tp 40, cable stored 25, sink free 1000) = 25.
        assertEquals(25, sinkEnergy.getStored(), "sink received what the cable forwarded");
        assertEquals(0, cableEnergy.getStored(), "cable buffer = in(25) - out(25)");
        assertEquals(75, srcEnergy.getStored(), "source unchanged by the second pass");
    }

    @Test
    void fullCableStopsUpstreamPull() {
        // CABLE pre-filled to full (25/25): it cannot accept, so the upstream pull stops.
        EnergyBuffer srcEnergy = energy(100, 100);
        EnergyBuffer cableEnergy = energy(25, 25); // full
        FakeNode source = sourceNode(srcEnergy, 40);
        FakeNode cable = cableNode(cableEnergy, 40);

        TransportEngine.pushEnergy(source, single(0, cable));

        // receiveEnergy returned 0 -> simulate-then-commit committed nothing on either side.
        assertEquals(100, srcEnergy.getStored(), "full cable stopped the upstream pull (design rule 7)");
        assertEquals(25, cableEnergy.getStored(), "cable stayed full");
    }
}
