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
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup;
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
 * <p>Task 9 adds the ITEM-channel CONTAINER-arm cases (the "chest stubbing" design): an ITEM pipe
 * beside a vanilla chest counts the chest in EFFECTIVE (face-state gated: any non-NONE face joins, since
 * a container has no ports) but NEVER in PHYSICAL (the engine welds no arm toward a tag-less chest), so
 * {@code effective > physical} and the same programmatic swap ADDS the arm. Also pins: NONE drops it;
 * non-ITEM channels ignore containers entirely; the old 5-arg overloads have zero container awareness
 * (delegate with null); and a straight ITEM run ending at a chest diverges to the larger (straight)
 * shape. Container access is the {@link ContainerLookup} seam, faked in-memory ({@code FakeContainers}).
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
        private final java.util.Set<PortChannel> advertisedOverride; // null = derive from ports

        FakePorts(PortConfig ports, EnergyHandler energy, Map<PortChannel, ResourceBuffer> buffers) {
            this(ports, energy, buffers, null);
        }

        FakePorts(PortConfig ports, EnergyHandler energy, Map<PortChannel, ResourceBuffer> buffers,
                java.util.Set<PortChannel> advertisedOverride) {
            this.ports = ports;
            this.energy = energy;
            this.buffers = buffers;
            this.advertisedOverride = advertisedOverride;
        }

        /**
         * A multi-cell FILLER cell as seen by a pipe: its own (per-cell) {@code ports()} is EMPTY, but the
         * whole machine advertises {@code channel} (the engine welds an inherited-tag arm anyway).
         */
        static FakePorts advertisingOnly(PortChannel channel) {
            return new FakePorts(PortConfig.of(List.of()), null, Map.of(), java.util.Set.of(channel));
        }

        @Override
        public PortConfig ports() {
            return ports;
        }

        @Override
        public boolean advertisesChannel(PortChannel channel) {
            return advertisedOverride != null
                ? advertisedOverride.contains(channel)
                : MachinePorts.super.advertisesChannel(channel);
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

    /**
     * Minimal {@link ContainerLookup} fake: a position either HAS a storage container or it does not.
     * The visual masks only ever ask "is there a container here" ({@code at(...) != null}); they never
     * insert/extract, so the {@link ContainerLookup.ContainerView} methods are unreachable and throw to
     * catch any accidental use.
     */
    private static final class FakeContainers implements ContainerLookup {
        private final Map<Long, ContainerView> byKey = new HashMap<>();

        FakeContainers put(int x, int y, int z) {
            byKey.put(NetworkManager.packKey(x, y, z), STUB_VIEW);
            return this;
        }

        @Override
        public ContainerView at(int x, int y, int z) {
            return byKey.get(NetworkManager.packKey(x, y, z));
        }
    }

    /** A present-but-unused container view: the visual masks only test {@code at(...) != null}. */
    private static final ContainerLookup.ContainerView STUB_VIEW = new ContainerLookup.ContainerView() {
        @Override
        public int insert(com.chonbosmods.chemistry.impl.block.net.item.ItemKey key,
                org.bson.BsonDocument metadata, int amount, boolean simulate) {
            throw new UnsupportedOperationException("visual masks never insert");
        }

        @Override
        public Peek firstExtractable(com.chonbosmods.chemistry.impl.block.net.item.ItemFilter filter,
                long pipeKey, int viaFace, int cap) {
            throw new UnsupportedOperationException("visual masks never scan");
        }

        @Override
        public Extracted extract(com.chonbosmods.chemistry.impl.block.net.item.ItemKey key,
                int amount, boolean simulate) {
            throw new UnsupportedOperationException("visual masks never extract");
        }
    };

    private static PortConfig powerPort(int face, PortDirection dir) {
        return PortConfig.of(List.of(Port.of(face, PortChannel.POWER, dir)));
    }

    private static PipeNode itemPipe() {
        return PipeNode.of(PortChannel.ITEM, 1);
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
    void fillerCellWithNoPerCellPort_droppedFromEffective_keptInPhysical() {
        // A multi-cell machine's FILLER cell at +X: its own per-cell ports() is EMPTY (no port lives on
        // this cell), yet the engine still welds an arm because the filler inherits the anchor's FaceTags
        // (which advertise the channel). So effective drops the face while physical keeps it -> the
        // suppressed-arm override drops the inherited arm. This is the multi-cell footprint fix.
        PipeNode self = PipeNode.of(PortChannel.ITEM, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup().put(1, 0, 0, FakePorts.advertisingOnly(PortChannel.ITEM));
        FakeContainers containers = new FakeContainers(); // no chest beyond the filler

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup, containers),
            "filler cell exposes no per-cell port -> effective drops the face");
        assertEquals(BIT_PX, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup, containers),
            "filler inherits the anchor's tag -> engine welds -> physical keeps the face");
    }

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
    void closedFacingPort_effectiveDrops_physicalKeeps() {
        // A machine face wrenched to CLOSED keeps its (persisted) port but contributes nothing to
        // endpoint collection: the EFFECTIVE mask must drop the arm exactly like NetworkEndpoints'
        // classify gate does, while the engine still physically stubs to the channel-bearing machine.
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup().put(1, 0, 0, new FakePorts(
            powerPort(FACE_NX, PortDirection.CLOSED), EnergyBuffer.withCapacity(1000L), Map.of()));

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "a CLOSED facing port is not a transport endpoint: no effective arm");
        assertEquals(BIT_PX, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup),
            "engine still stubs physically to the channel-bearing machine");
    }

    @Test
    void pushAtOutputOnlyPort_noOverlap_effectiveDrops() {
        // Pipe face PUSH at a generator (OUTPUT-only port): the port only offers, the pipe only pushes:
        // classify yields nothing, so the transport layer does not join and the arm must drop. PULL at
        // the same port overlaps (provider) and keeps the arm.
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        self.setFlowState(0, FlowState.PUSH);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup().put(1, 0, 0, new FakePorts(
            powerPort(FACE_NX, PortDirection.OUTPUT), EnergyBuffer.withCapacity(1000L), Map.of()));

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "PUSH x OUTPUT has no overlap: no effective arm");
        assertEquals(BIT_PX, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup));

        self.setFlowState(0, FlowState.PULL);
        assertEquals(BIT_PX, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "PULL x OUTPUT overlaps (provider): arm stays");
    }

    @Test
    void pullAtInputOnlyPort_noOverlap_effectiveDrops() {
        // Mirror case: pipe face PULL at a sink (INPUT-only port): no overlap, arm drops; PUSH keeps it.
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        self.setFlowState(0, FlowState.PULL);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup().put(1, 0, 0, new FakePorts(
            powerPort(FACE_NX, PortDirection.INPUT), EnergyBuffer.withCapacity(1000L), Map.of()));

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "PULL x INPUT has no overlap: no effective arm");

        self.setFlowState(0, FlowState.PUSH);
        assertEquals(BIT_PX, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "PUSH x INPUT overlaps (acceptor): arm stays");
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

    // --- Task 9: ITEM-channel container arms (effective > physical ADDS an arm) ---

    @Test
    void itemPipeContainerNormalFace_effectiveAddsArm_physicalDoesNot() {
        // The core chest-stubbing case: an ITEM pipe with a NORMAL +X face beside a vanilla chest. The
        // chest advertises no CC_ItemFace, so the engine welds nothing (physical = 0): but the network
        // delivers into it, so the effective mask has the arm -> effective > physical -> the swap ADDS it.
        PipeNode self = itemPipe();
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup();
        FakeContainers containers = new FakeContainers().put(1, 0, 0);

        assertEquals(BIT_PX, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup, containers),
            "NORMAL face at a container: effective arm present");
        assertEquals(0, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup, containers),
            "engine never welds toward a tag-less chest: physical drops it");
        assertNotEquals(
            PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup, containers),
            PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup, containers),
            "effective > physical -> arm-ADD override fires");
    }

    @Test
    void itemPipeContainerPullAndPush_effectiveArmPresent_anyNonNoneJoins() {
        // Containers have no ports: NORMAL/PUSH/PULL all qualify (mirrors ItemEndpoints). PULL is the
        // asymmetry vs machines: a PULL face at an OUTPUT-only machine would NOT join, but a PULL face at
        // a (portless) container DOES: the arm renders so the extraction is visible.
        PipeNode self = itemPipe();
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup();
        FakeContainers containers = new FakeContainers().put(1, 0, 0);

        self.setFlowState(FACE_PX, FlowState.PULL);
        assertEquals(BIT_PX, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup, containers),
            "PULL face at a portless container: arm present (extraction is visible)");
        assertEquals(0, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup, containers),
            "PULL physical still 0: engine welds no chest arm");

        self.setFlowState(FACE_PX, FlowState.PUSH);
        assertEquals(BIT_PX, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup, containers),
            "PUSH face at a container: arm present (insert-only delivery)");
    }

    @Test
    void itemPipeContainerNoneFace_neitherMask() {
        // A NONE face hides the container entirely: no effective arm (and physical never counts a chest).
        PipeNode self = itemPipe();
        self.setFlowState(FACE_PX, FlowState.NONE);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup();
        FakeContainers containers = new FakeContainers().put(1, 0, 0);

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup, containers),
            "NONE face hides the container: no effective arm");
        assertEquals(0, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup, containers),
            "physical never counts a container");
    }

    @Test
    void powerPipeContainer_ignoredInBothMasks() {
        // A chest beside a POWER cable is irrelevant: the container-awareness is ITEM-only. Both masks
        // ignore it (effective because the channel guard rejects, physical because it never counts chests).
        PipeNode self = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup();
        FakeContainers containers = new FakeContainers().put(1, 0, 0);

        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup, containers),
            "POWER channel: container ignored in effective");
        assertEquals(0, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup, containers),
            "POWER channel: container ignored in physical");
    }

    @Test
    void oldOverloads_ignoreContainers_delegateWithNull() {
        // The 5-arg overloads delegate with containers=null (no container awareness): even an ITEM pipe
        // beside a chest gets a 0 effective mask through the old signature, identical to the explicit
        // null pass. This pins that every pre-Task-9 caller/test is behaviourally untouched.
        PipeNode self = itemPipe();
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self);
        FakeLookup lookup = new FakeLookup();

        // Old 5-arg form: no ContainerLookup at all.
        assertEquals(0, PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            "5-arg effectiveMask has no container awareness");
        assertEquals(0, PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup),
            "5-arg physicalMask has no container awareness");

        // Explicit null 6-arg form: identical to the 5-arg delegation.
        assertEquals(
            PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup),
            PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup, null),
            "6-arg null effective == 5-arg effective (delegation)");
        assertEquals(
            PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup),
            PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup, null),
            "6-arg null physical == 5-arg physical (delegation)");
    }

    @Test
    void itemRunEndingAtChest_armAddDivergesToLargerShape() {
        // The arm-ADD divergence in a realistic topology: a straight ITEM run with a pipe to the -X
        // (west) and a chest to the +X (east). Effective = straight-through (both bits): the network
        // reaches the pipe AND delivers into the chest. Physical = end-ish (only the -X pipe bit): the
        // engine welds the pipe arm but refuses the chest. effective != physical, and the effective shape
        // is the LARGER (2-arm straight) one the swap retargets to. (Task 11 owns the swap itself; here we
        // assert only that the masks diverge correctly and stateFor reflects the bigger shape.)
        PipeNode self = itemPipe();
        PipeNode west = itemPipe();
        FakeGrid grid = new FakeGrid().put(0, 0, 0, self).put(-1, 0, 0, west);
        FakeLookup lookup = new FakeLookup();
        FakeContainers containers = new FakeContainers().put(1, 0, 0);

        int eff = PipeVisualStates.effectiveMask(self, 0, 0, 0, grid, lookup, containers);
        int phys = PipeVisualStates.physicalMask(self, 0, 0, 0, grid, lookup, containers);

        assertEquals(BIT_PX | BIT_NX, eff, "effective: pipe arm (-X) + chest arm (+X) = straight-through");
        assertEquals(BIT_NX, phys, "physical: only the welded pipe arm (-X); the chest is not welded");
        assertNotEquals(eff, phys, "effective > physical: the chest arm must be ADDED");
        assertNotEquals(
            PipeShapes.stateFor(eff, false), PipeShapes.stateFor(phys, false),
            "the effective (larger, straight) shape differs from the physical (end) shape");
    }
}
