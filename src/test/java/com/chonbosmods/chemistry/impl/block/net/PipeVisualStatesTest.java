package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
 * Unit tests for the PURE half of Task 11 ({@link PipeVisualStates#effectiveMask} /
 * {@link PipeVisualStates#physicalMask}). Covers: a NONE face suppressing a pipe arm; a substance
 * mismatch suppressing a pipe arm; a machine arm suppressed by a NONE face; a facing-port machine
 * counted in BOTH masks; and the documented physical-mask machine approximation (a machine with a
 * port but no FACING port still counts physically). Uses the same in-memory fakes as
 * {@link NetworkEndpointsTest}.
 *
 * <p>Face indices are OFFSETS order: +X=0,-X=1,+Y=2,-Y=3,+Z=4,-Z=5. The pipe under test sits at the
 * origin (0,0,0); a +X neighbour is bit 0 (mask value 1).
 */
class PipeVisualStatesTest {

    private static final int FACE_PX = 0; // +X, bit 0
    private static final int FACE_NX = 1; // -X, bit 1
    private static final int BIT_PX = 1 << FACE_PX;
    private static final int BIT_NX = 1 << FACE_NX;

    // --- fakes ---

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

    private static final class FakeGrid implements PipeGridView {
        private final Map<Long, PipeNode> byKey = new HashMap<>();

        FakeGrid put(int x, int y, int z, PipeNode node) {
            byKey.put(NetworkManager.packKey(x, y, z), node);
            return this;
        }

        @Override
        public PipeNode pipeAt(int x, int y, int z) {
            return byKey.get(NetworkManager.packKey(x, y, z));
        }
    }

    private static final class FakeLookup implements MachineLookup {
        private final Map<Long, MachinePorts> byKey = new HashMap<>();

        FakeLookup put(int x, int y, int z, MachinePorts ports) {
            byKey.put(NetworkManager.packKey(x, y, z), ports);
            return this;
        }

        @Override
        public MachinePorts at(int x, int y, int z) {
            return byKey.get(NetworkManager.packKey(x, y, z));
        }
    }

    private static PortConfig powerPort(int face, PortDirection dir) {
        return PortConfig.of(List.of(Port.of(face, PortChannel.POWER, dir)));
    }

    // --- pipe-pipe edges ---

    @Test
    void twoNormalPowerPipes_connectInBothMasks() {
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        PipeNode east = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self).put(1, 0, 0, east);
        FakeLookup lookup = new FakeLookup();

        assertEquals(BIT_PX, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup));
        assertEquals(BIT_PX, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup));
    }

    @Test
    void noneFaceSuppressesPipeArm_effectiveLosesIt_physicalKeepsIt() {
        // Self's +X face is NONE: the BFS/connectivity gate severs it, so the effective mask drops the
        // arm; but the engine still welds it (a same-channel pipe is physically adjacent).
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        self.setFlowState(FACE_PX, FlowState.NONE);
        PipeNode east = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self).put(1, 0, 0, east);
        FakeLookup lookup = new FakeLookup();

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "NONE face suppresses the effective pipe arm");
        assertEquals(BIT_PX, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup),
            "engine still welds the physically-adjacent same-channel pipe");
        assertNotEquals(
            PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup),
            "a divergence -> suppressed-arm override fires");
    }

    @Test
    void substanceMismatchSuppressesPipeArm_effectiveLosesIt_physicalKeepsIt() {
        // Two FLUID pipes locked to DIFFERENT substances: connects() rejects (gas-bug fix), so the
        // effective arm drops; physically they are still both FLUID pipes -> welded.
        PipeNode self = PipeNode.of(PortChannel.FLUID, 1);
        self.setResourceId("water");
        PipeNode east = PipeNode.of(PortChannel.FLUID, 1);
        east.setResourceId("lava");
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self).put(1, 0, 0, east);
        FakeLookup lookup = new FakeLookup();

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "substance mismatch suppresses the effective pipe arm");
        assertEquals(BIT_PX, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup),
            "engine welds both same-channel FLUID pipes regardless of substance");
    }

    @Test
    void differentChannelPipe_inNeitherMask() {
        // A POWER pipe next to a FLUID pipe: a channel boundary. Neither the network nor the engine's
        // (per-channel) connected-block template joins across it.
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        PipeNode east = PipeNode.of(PortChannel.FLUID, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self).put(1, 0, 0, east);
        FakeLookup lookup = new FakeLookup();

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup));
        assertEquals(0, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup));
    }

    // --- pipe-machine edges ---

    @Test
    void facingPortMachine_countsInBothMasks() {
        // Machine at +X with a POWER OUTPUT port on its facing (-X = 1) face: a real endpoint. Counted
        // effectively (non-NONE pipe face + facing port) and physically (advertises the channel).
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup().put(1, 0, 0, new FakePorts(
            powerPort(FACE_NX, PortDirection.OUTPUT), EnergyBuffer.withCapacity(1000L), Map.of()));

        assertEquals(BIT_PX, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup));
        assertEquals(BIT_PX, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup));
    }

    @Test
    void noneFaceSuppressesMachineArm_effectiveLosesIt_physicalKeepsIt() {
        // Same facing-port machine, but the pipe's +X face is NONE: endpoint collection hides it, so the
        // effective arm drops; the engine still stubs to a channel-advertising machine.
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        self.setFlowState(FACE_PX, FlowState.NONE);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup().put(1, 0, 0, new FakePorts(
            powerPort(FACE_NX, PortDirection.OUTPUT), EnergyBuffer.withCapacity(1000L), Map.of()));

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "NONE pipe face hides the machine endpoint effectively");
        assertEquals(BIT_PX, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup),
            "engine still stubs to the channel-advertising machine");
    }

    @Test
    void machineWithoutFacingPort_effectiveDrops_physicalKeepsViaApproximation() {
        // A machine whose only POWER port is on the WRONG face (not facing back at the pipe): endpoint
        // collection's face-precise gate rejects it, so the EFFECTIVE mask drops it. The documented
        // physical-mask approximation ("has ANY port for the channel") still counts it physically: this
        // pins the approximation and flags the divergence for in-game (Task 15) validation.
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        // Port on +X (0) faces AWAY from the pipe (pipe needs the port on -X = 1).
        FakeLookup lookup = new FakeLookup().put(1, 0, 0, new FakePorts(
            powerPort(FACE_PX, PortDirection.OUTPUT), EnergyBuffer.withCapacity(1000L), Map.of()));

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "face-precise gate: a non-facing port is not a real endpoint");
        assertEquals(BIT_PX, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup),
            "physical approximation: any same-channel port advertises the face");
    }

    @Test
    void wrongChannelMachine_inNeitherMask() {
        // A FLUID machine beside a POWER pipe: not this network's channel, so neither mask counts it.
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup().put(1, 0, 0, new FakePorts(
            PortConfig.of(List.of(Port.of(FACE_NX, PortChannel.FLUID, PortDirection.OUTPUT))),
            null, Map.of(PortChannel.FLUID, ResourceBuffer.withCapacity(1000))));

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup));
        assertEquals(0, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup));
    }

    @Test
    void emptyNeighbourhood_bothMasksZero() {
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup();

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup));
        assertEquals(0, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup));
    }

    @Test
    void nullPipe_bothMasksZero() {
        FakeGrid grid = new FakeGrid();
        FakeLookup lookup = new FakeLookup();
        assertEquals(0, PipeVisualStates.effectiveMask(null, 0, 0, 0, grid, lookup));
        assertEquals(0, PipeVisualStates.physicalMask(null, 0, 0, 0, grid, lookup));
    }

    @Test
    void undisturbedMultiArmPipe_masksEqual() {
        // A straight run: pipes on +X and -X, both NORMAL same-channel. No suppression: masks match
        // exactly (the "do nothing beyond H8" path).
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        PipeNode east = PipeNode.of(PortChannel.POWER, 1);
        PipeNode west = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, self).put(1, 0, 0, east).put(-1, 0, 0, west);
        FakeLookup lookup = new FakeLookup();

        int eff = PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup);
        int phys = PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup);
        assertEquals(BIT_PX | BIT_NX, eff);
        assertEquals(eff, phys, "undisturbed straight run: no override");
    }
}
