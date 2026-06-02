package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransportEngineTest {

    // --- Test fixtures -------------------------------------------------------

    /** A configurable {@link TransferNode}: ports + optional energy handler + per-channel resource buffers + throughput. */
    private static final class FakeNode implements TransferNode {
        private final PortConfig ports;
        private final EnergyHandler energy;
        private final Map<PortChannel, ResourceBuffer> resources = new EnumMap<>(PortChannel.class);
        private final int throughput;

        FakeNode(PortConfig ports, EnergyHandler energy, int throughput) {
            this.ports = ports;
            this.energy = energy;
            this.throughput = throughput;
        }

        FakeNode withResource(PortChannel channel, ResourceBuffer buffer) {
            resources.put(channel, buffer);
            return this;
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
            return resources.get(channel);
        }

        @Override
        public int throughput(PortChannel channel) {
            return throughput;
        }
    }

    private static NeighborView empty() {
        return direction -> null;
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

    private static ResourceBuffer resource(int capacity, String id, int fill) {
        ResourceBuffer b = ResourceBuffer.withCapacity(capacity);
        if (fill > 0) {
            b.insert(id, fill, false);
        }
        return b;
    }

    // --- Energy tests --------------------------------------------------------

    @Test
    void pushesEnergyToAdjacentInputUpToReceiverSpace() {
        EnergyBuffer srcEnergy = energy(100, 100);
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.POWER, PortDirection.OUTPUT))),
            srcEnergy, 40);
        EnergyBuffer dstEnergy = energy(25, 0);
        FakeNode dst = new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.POWER, PortDirection.INPUT))),
            dstEnergy, 40);

        TransportEngine.pushEnergy(source, single(0, dst));

        assertEquals(25, dstEnergy.getStored());
        assertEquals(75, srcEnergy.getStored());
    }

    @Test
    void throughputCapsTransfer() {
        EnergyBuffer srcEnergy = energy(100, 100);
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.POWER, PortDirection.OUTPUT))),
            srcEnergy, 40);
        EnergyBuffer dstEnergy = energy(1000, 0);
        FakeNode dst = new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.POWER, PortDirection.INPUT))),
            dstEnergy, 40);

        TransportEngine.pushEnergy(source, single(0, dst));

        assertEquals(40, dstEnergy.getStored());
        assertEquals(60, srcEnergy.getStored());
    }

    @Test
    void absentNeighborTransfersNothing() {
        EnergyBuffer srcEnergy = energy(100, 100);
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.POWER, PortDirection.OUTPUT))),
            srcEnergy, 40);

        TransportEngine.pushEnergy(source, empty());

        assertEquals(100, srcEnergy.getStored());
    }

    @Test
    void blockedReceiverLeavesSourceUntouched() {
        EnergyBuffer srcEnergy = energy(100, 100);
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.POWER, PortDirection.OUTPUT))),
            srcEnergy, 40);
        EnergyBuffer dstEnergy = energy(25, 25); // already full
        FakeNode dst = new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.POWER, PortDirection.INPUT))),
            dstEnergy, 40);

        TransportEngine.pushEnergy(source, single(0, dst));

        assertEquals(100, srcEnergy.getStored());
        assertEquals(25, dstEnergy.getStored());
    }

    @Test
    void neighborWithoutInputPortIsSkipped() {
        EnergyBuffer srcEnergy = energy(100, 100);
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.POWER, PortDirection.OUTPUT))),
            srcEnergy, 40);
        EnergyBuffer dstEnergy = energy(1000, 0);
        // neighbour can hold energy but exposes only a POWER OUTPUT (no INPUT) port
        FakeNode dst = new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.POWER, PortDirection.OUTPUT))),
            dstEnergy, 40);

        TransportEngine.pushEnergy(source, single(0, dst));

        assertEquals(100, srcEnergy.getStored());
        assertEquals(0, dstEnergy.getStored());
    }

    @Test
    void nodeWithoutEnergyHandlerIsNoop() {
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.POWER, PortDirection.OUTPUT))),
            null, 40);
        EnergyBuffer dstEnergy = energy(1000, 0);
        FakeNode dst = new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.POWER, PortDirection.INPUT))),
            dstEnergy, 40);

        assertDoesNotThrow(() -> TransportEngine.pushEnergy(source, single(0, dst)));
        assertEquals(0, dstEnergy.getStored());
    }

    // --- Resource tests (FLUID channel) -------------------------------------

    @Test
    void pushesFluidToAdjacentInputUpToCapacity() {
        ResourceBuffer srcBuf = resource(100, "compound:water", 100);
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.FLUID, PortDirection.OUTPUT))),
            null, 30).withResource(PortChannel.FLUID, srcBuf);
        ResourceBuffer dstBuf = resource(50, null, 0);
        FakeNode dst = new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.FLUID, PortDirection.INPUT))),
            null, 30).withResource(PortChannel.FLUID, dstBuf);

        TransportEngine.pushResources(source, single(0, dst));

        assertEquals(30, dstBuf.amount());
        assertEquals("compound:water", dstBuf.resourceId());
        assertEquals(70, srcBuf.amount());
    }

    @Test
    void fluidStopsWhenReceiverTypeLocksDifferent() {
        ResourceBuffer srcBuf = resource(100, "compound:water", 100);
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.FLUID, PortDirection.OUTPUT))),
            null, 30).withResource(PortChannel.FLUID, srcBuf);
        ResourceBuffer dstBuf = resource(50, "element:oxygen", 10); // locked to a different type
        FakeNode dst = new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.FLUID, PortDirection.INPUT))),
            null, 30).withResource(PortChannel.FLUID, dstBuf);

        TransportEngine.pushResources(source, single(0, dst));

        assertEquals(100, srcBuf.amount());
        assertEquals(10, dstBuf.amount());
        assertEquals("element:oxygen", dstBuf.resourceId());
    }

    @Test
    void noResourceIdIsNoop() {
        ResourceBuffer srcBuf = resource(100, null, 0); // empty → resourceId null
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.FLUID, PortDirection.OUTPUT))),
            null, 30).withResource(PortChannel.FLUID, srcBuf);
        ResourceBuffer dstBuf = resource(50, null, 0);
        FakeNode dst = new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.FLUID, PortDirection.INPUT))),
            null, 30).withResource(PortChannel.FLUID, dstBuf);

        assertDoesNotThrow(() -> TransportEngine.pushResources(source, single(0, dst)));
        assertEquals(0, srcBuf.amount());
        assertEquals(0, dstBuf.amount());
        assertNull(dstBuf.resourceId());
    }

    @Test
    void absentResourceBufferIsSkipped() {
        // source has a FLUID OUTPUT port but no FLUID resource buffer at all
        FakeNode source = new FakeNode(
            PortConfig.of(List.of(Port.of(0, PortChannel.FLUID, PortDirection.OUTPUT))),
            null, 30);
        ResourceBuffer dstBuf = resource(50, null, 0);
        FakeNode dst = new FakeNode(
            PortConfig.of(List.of(Port.of(1, PortChannel.FLUID, PortDirection.INPUT))),
            null, 30).withResource(PortChannel.FLUID, dstBuf);

        assertDoesNotThrow(() -> TransportEngine.pushResources(source, single(0, dst)));
        assertEquals(0, dstBuf.amount());
    }
}
