package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure tests for {@link WrenchCycles}: pipe-face flow cycling and machine-face capability cycling. */
class WrenchCyclesTest {

    // --- Pipe face toward a MACHINE: full four-state cycle, sneak reverses ---

    @Test
    void pipeTowardMachineForwardCycle() {
        assertEquals(FlowState.PUSH, WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.MACHINE));
        assertEquals(FlowState.PULL, WrenchCycles.next(FlowState.PUSH, WrenchCycles.Target.MACHINE));
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.PULL, WrenchCycles.Target.MACHINE));
        // wrap
        assertEquals(FlowState.NORMAL, WrenchCycles.next(FlowState.NONE, WrenchCycles.Target.MACHINE));
    }

    @Test
    void pipeTowardMachineReverseCycle() {
        assertEquals(FlowState.PULL, WrenchCycles.previous(FlowState.NONE, WrenchCycles.Target.MACHINE));
        assertEquals(FlowState.PUSH, WrenchCycles.previous(FlowState.PULL, WrenchCycles.Target.MACHINE));
        assertEquals(FlowState.NORMAL, WrenchCycles.previous(FlowState.PUSH, WrenchCycles.Target.MACHINE));
        // wrap backward: NORMAL -> NONE
        assertEquals(FlowState.NONE, WrenchCycles.previous(FlowState.NORMAL, WrenchCycles.Target.MACHINE));
    }

    // --- Target resolution: a pipe end (any non-pipe face) is configurable ---

    @Test
    void targetTowardAnotherPipeIsTheTwoStateRing() {
        // A face touching another pipe stays the pipe-to-pipe connection ring (NORMAL <-> NONE):
        // push/pull have no meaning between two pipes.
        assertEquals(WrenchCycles.Target.PIPE, WrenchCycles.targetForNeighbour(true));
    }

    @Test
    void targetTowardNonPipeIsTheFullRing() {
        // A face NOT touching a pipe (air, a solid block, or an endpoint) gets the full four-state ring
        // (design 2026-06-07): a player can pre-configure a bare pipe END to PUSH/PULL and the config
        // persists for whenever a chest/machine is later placed there.
        assertEquals(WrenchCycles.Target.MACHINE, WrenchCycles.targetForNeighbour(false));
        // End-to-end: a bare air-facing end cycles NORMAL -> PUSH -> PULL (not just NORMAL <-> NONE).
        WrenchCycles.Target end = WrenchCycles.targetForNeighbour(false);
        assertEquals(FlowState.PUSH, WrenchCycles.next(FlowState.NORMAL, end));
        assertEquals(FlowState.PULL, WrenchCycles.next(FlowState.PUSH, end));
    }

    // --- Pipe face toward a PIPE: only NORMAL <-> NONE ---

    @Test
    void pipeTowardPipeForwardCycle() {
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.PIPE));
        assertEquals(FlowState.NORMAL, WrenchCycles.next(FlowState.NONE, WrenchCycles.Target.PIPE));
    }

    @Test
    void pipeTowardPipeReverseCycle() {
        assertEquals(FlowState.NONE, WrenchCycles.previous(FlowState.NORMAL, WrenchCycles.Target.PIPE));
        assertEquals(FlowState.NORMAL, WrenchCycles.previous(FlowState.NONE, WrenchCycles.Target.PIPE));
    }

    @Test
    void pipeTowardPipeFromPushOrPullCollapsesToNone() {
        // PUSH/PULL are endpoint-only and not part of the pipe-toward-pipe cycle: treat as NORMAL's
        // neighbour so a stale state still advances into the two-state ring.
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.PUSH, WrenchCycles.Target.PIPE));
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.PULL, WrenchCycles.Target.PIPE));
    }

    // --- Pipe face toward a MACHINE under the 2 push/pull faces budget ---

    @Test
    void budgetConstantIsTwo() {
        assertEquals(2, WrenchCycles.MAX_DIRECTED_FACES);
    }

    @Test
    void zeroOtherDirectedFacesLeavesFullRing() {
        // 0 other directed faces: full NORMAL -> PUSH -> PULL -> NONE -> wrap, same as the 2-arg form.
        assertEquals(FlowState.PUSH, WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.MACHINE, 0));
        assertEquals(FlowState.PULL, WrenchCycles.next(FlowState.PUSH, WrenchCycles.Target.MACHINE, 0));
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.PULL, WrenchCycles.Target.MACHINE, 0));
        assertEquals(FlowState.NORMAL, WrenchCycles.next(FlowState.NONE, WrenchCycles.Target.MACHINE, 0));
    }

    @Test
    void oneOtherDirectedFaceLeavesFullRing() {
        // 1 other directed face: budget not yet spent, push/pull still reachable.
        assertEquals(FlowState.PUSH, WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.MACHINE, 1));
        assertEquals(FlowState.PULL, WrenchCycles.next(FlowState.PUSH, WrenchCycles.Target.MACHINE, 1));
    }

    @Test
    void budgetSpentMachineForwardCycleEqualsPipeRing() {
        // 2 other directed faces: budget spent. The MACHINE cycle degrades to the PIPE ring
        // (NORMAL -> NONE -> wrap): push/pull simply drop out.
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.MACHINE, 2));
        assertEquals(FlowState.NORMAL, WrenchCycles.next(FlowState.NONE, WrenchCycles.Target.MACHINE, 2));
        // It matches the PIPE-target ring exactly.
        assertEquals(WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.PIPE),
            WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.MACHINE, 2));
        assertEquals(WrenchCycles.next(FlowState.NONE, WrenchCycles.Target.PIPE),
            WrenchCycles.next(FlowState.NONE, WrenchCycles.Target.MACHINE, 2));
    }

    @Test
    void budgetSpentMachineReverseCycleEqualsPipeRing() {
        // Reverse mirrors forward within the same budget condition: NONE <-> NORMAL only.
        assertEquals(FlowState.NONE, WrenchCycles.previous(FlowState.NORMAL, WrenchCycles.Target.MACHINE, 2));
        assertEquals(FlowState.NORMAL, WrenchCycles.previous(FlowState.NONE, WrenchCycles.Target.MACHINE, 2));
        assertEquals(WrenchCycles.previous(FlowState.NORMAL, WrenchCycles.Target.PIPE),
            WrenchCycles.previous(FlowState.NORMAL, WrenchCycles.Target.MACHINE, 2));
        assertEquals(WrenchCycles.previous(FlowState.NONE, WrenchCycles.Target.PIPE),
            WrenchCycles.previous(FlowState.NONE, WrenchCycles.Target.MACHINE, 2));
    }

    @Test
    void budgetCountExcludesSelfSoADirectedFaceStillCyclesThroughPushPull() {
        // The cycled face's own current PUSH/PULL state does NOT count against itself: the caller passes
        // otherDirectedFaces = (directed faces EXCLUDING this one). A PULL face with exactly 1 OTHER
        // directed face is at the budget total of 2, but its own slot is free, so it still walks the full
        // ring: PULL -> NONE -> NORMAL -> PUSH -> PULL.
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.PULL, WrenchCycles.Target.MACHINE, 1));
        assertEquals(FlowState.NORMAL, WrenchCycles.next(FlowState.NONE, WrenchCycles.Target.MACHINE, 1));
        assertEquals(FlowState.PUSH, WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.MACHINE, 1));
        assertEquals(FlowState.PULL, WrenchCycles.next(FlowState.PUSH, WrenchCycles.Target.MACHINE, 1));
    }

    @Test
    void budgetSpentNormalToNoneStillAvailable() {
        // Even with the budget spent elsewhere, a NORMAL face can still go to NONE (close the face).
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.MACHINE, 2));
    }

    @Test
    void budgetSpentInverseProperty() {
        // next and previous remain exact inverses within the budget-spent condition (the 2-state ring).
        for (FlowState s : new FlowState[] {FlowState.NORMAL, FlowState.NONE}) {
            FlowState fwd = WrenchCycles.next(s, WrenchCycles.Target.MACHINE, 2);
            assertEquals(s, WrenchCycles.previous(fwd, WrenchCycles.Target.MACHINE, 2));
            FlowState back = WrenchCycles.previous(s, WrenchCycles.Target.MACHINE, 2);
            assertEquals(s, WrenchCycles.next(back, WrenchCycles.Target.MACHINE, 2));
        }
    }

    @Test
    void budgetSpentStalePushOnPipeStillCollapsesToNone() {
        // A budget-spent MACHINE face holding a stale PUSH/PULL (e.g. legacy data) still advances into the
        // 2-state ring: treated as NORMAL's neighbour, it lands on NONE.
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.PUSH, WrenchCycles.Target.MACHINE, 2));
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.PULL, WrenchCycles.Target.MACHINE, 2));
    }

    @Test
    void budgetIgnoredForPipeTarget() {
        // Toward a PIPE the budget is irrelevant (push/pull are never offered there anyway): the count
        // overload must behave identically to the 2-arg form regardless of otherDirectedFaces.
        assertEquals(FlowState.NONE, WrenchCycles.next(FlowState.NORMAL, WrenchCycles.Target.PIPE, 2));
        assertEquals(FlowState.NORMAL, WrenchCycles.next(FlowState.NONE, WrenchCycles.Target.PIPE, 5));
    }

    // --- Machine capability cycle ---

    private static MachineBlockState machine(boolean power, PortChannel... resourceChannels) {
        EnergyBuffer energy = power ? EnergyBuffer.withCapacity(1000L) : null;
        Map<PortChannel, ResourceBuffer> resources = new EnumMap<>(PortChannel.class);
        for (PortChannel ch : resourceChannels) {
            resources.put(ch, ResourceBuffer.withCapacity(1000));
        }
        return MachineBlockState.create(energy, resources, PortConfig.of(List.of()), new WorkState(), 100);
    }

    @Test
    void machineSingleCapabilityWalksInputOutputClosedWrap() {
        // Power-only machine: capability list = [POWER].
        MachineBlockState m = machine(true);
        int face = 2;

        Port closed = WrenchCycles.nextPort(m, face, null);
        // From nothing/closed -> first entry: (POWER, INPUT).
        assertEquals(face, closed.faceIndex());
        assertEquals(PortChannel.POWER, closed.channel());
        assertEquals(PortDirection.INPUT, closed.direction());

        Port out = WrenchCycles.nextPort(m, face, closed);
        assertEquals(PortChannel.POWER, out.channel());
        assertEquals(PortDirection.OUTPUT, out.direction());

        Port nowClosed = WrenchCycles.nextPort(m, face, out);
        assertEquals(PortDirection.CLOSED, nowClosed.direction());

        // wrap: closed -> (POWER, INPUT)
        Port wrapped = WrenchCycles.nextPort(m, face, nowClosed);
        assertEquals(PortChannel.POWER, wrapped.channel());
        assertEquals(PortDirection.INPUT, wrapped.direction());
    }

    @Test
    void machineMultiCapabilityWalksAllChannelPairsInDeclarationOrder() {
        // FLUID + POWER buffers: declaration order of PortChannel is ITEM, FLUID, GAS, POWER, so the
        // capability order is [FLUID, POWER].
        MachineBlockState m = machine(true, PortChannel.FLUID);
        int face = 0;

        Port p1 = WrenchCycles.nextPort(m, face, null);
        assertEquals(PortChannel.FLUID, p1.channel());
        assertEquals(PortDirection.INPUT, p1.direction());

        Port p2 = WrenchCycles.nextPort(m, face, p1);
        assertEquals(PortChannel.FLUID, p2.channel());
        assertEquals(PortDirection.OUTPUT, p2.direction());

        Port p3 = WrenchCycles.nextPort(m, face, p2);
        assertEquals(PortChannel.POWER, p3.channel());
        assertEquals(PortDirection.INPUT, p3.direction());

        Port p4 = WrenchCycles.nextPort(m, face, p3);
        assertEquals(PortChannel.POWER, p4.channel());
        assertEquals(PortDirection.OUTPUT, p4.direction());

        Port p5 = WrenchCycles.nextPort(m, face, p4);
        assertEquals(PortDirection.CLOSED, p5.direction());

        Port p6 = WrenchCycles.nextPort(m, face, p5);
        assertEquals(PortChannel.FLUID, p6.channel());
        assertEquals(PortDirection.INPUT, p6.direction());
    }

    @Test
    void machineUnknownStateEntersAtFirstCycleEntry() {
        // A BOTH port (storage semantics, never produced by the wrench) is not in the cycle: next from
        // it enters at the first supported (channel, INPUT).
        MachineBlockState m = machine(true, PortChannel.GAS);
        int face = 4;
        Port both = Port.of(face, PortChannel.POWER, PortDirection.BOTH);

        Port next = WrenchCycles.nextPort(m, face, both);
        // First capability in declaration order for {GAS, POWER} is GAS.
        assertEquals(PortChannel.GAS, next.channel());
        assertEquals(PortDirection.INPUT, next.direction());

        // A port on a channel the machine no longer supports is likewise unknown -> first entry.
        Port stale = Port.of(face, PortChannel.ITEM, PortDirection.OUTPUT);
        Port reset = WrenchCycles.nextPort(m, face, stale);
        assertEquals(PortChannel.GAS, reset.channel());
        assertEquals(PortDirection.INPUT, reset.direction());
    }

    @Test
    void machineWithNoBuffersAlwaysClosed() {
        MachineBlockState m = machine(false);
        int face = 1;

        Port a = WrenchCycles.nextPort(m, face, null);
        assertEquals(face, a.faceIndex());
        assertEquals(PortDirection.CLOSED, a.direction());

        // Cycling a closed port stays closed (the only entry).
        Port b = WrenchCycles.nextPort(m, face, a);
        assertEquals(PortDirection.CLOSED, b.direction());
    }

    @Test
    void machinePortCarriesThePassthroughFaceIndex() {
        MachineBlockState m = machine(true);
        Port p = WrenchCycles.nextPort(m, 5, null);
        assertEquals(5, p.faceIndex());
    }
}
