package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure cycle logic for the {@code CC_Wrench}: given a face's current state and what the face points
 * at, computes what that face's state should become on the next (or previous, for sneak) wrench tap
 * (design 2026-06-05 §4). No engine types, no world access: the Task 9 interaction glue resolves the
 * clicked face + target kind and persists the result.
 *
 * <h2>Pipe faces</h2>
 * A pipe face toward a {@link Target#MACHINE} (an endpoint) cycles all four flow states:
 * {@code NORMAL -> PUSH -> PULL -> NONE -> wrap}. {@code PUSH}/{@code PULL} carry endpoint-only
 * semantics (push = acceptor-only, pull = provider-only), so toward another {@link Target#PIPE} (or
 * air, or anything that is not an endpoint) the face cycles only the two pipe-meaningful states:
 * {@code NORMAL <-> NONE}. A stale {@code PUSH}/{@code PULL} encountered on a pipe-to-pipe face is
 * treated as {@code NORMAL}, so the tap advances it into the two-state ring (to {@code NONE}).
 *
 * <h2>Machine faces: the capability cycle</h2>
 * A machine face walks the machine's <em>supported capabilities</em>, derived from its buffers:
 * {@code energy() != null} contributes {@link PortChannel#POWER}; each non-null
 * {@code resource(channel)} contributes that channel. Capabilities are visited in
 * {@link PortChannel} declaration order ({@code ITEM, FLUID, GAS, POWER}) for a stable, predictable
 * walk. For each supported channel the cycle offers {@code (channel, INPUT) -> (channel, OUTPUT)};
 * after the last channel comes a single {@code CLOSED} entry, then it wraps to the first
 * {@code (firstChannel, INPUT)}.
 *
 * <h3>Why "closed" is a {@link PortDirection#CLOSED} value, not a removed port</h3>
 * {@link PortDirection} already defines a {@code CLOSED} value (Task pre-6), and {@link Port}/{@link
 * PortConfig} carry CLOSED ports as ordinary data ({@code PortConfigTest} round-trips them). Modeling
 * "closed" as a {@code CLOSED}-direction {@link Port} (rather than removing the port from the config)
 * keeps this function total: {@link #nextPort} always returns a non-null {@link Port}, so no
 * {@code Optional} is needed and the caller's persist step is uniform (replace the face's port). The
 * task description's {@code Optional<Port>} alternative (empty = closed) is therefore not used.
 *
 * <h3>Why {@link PortDirection#BOTH} is excluded</h3>
 * {@code BOTH} is storage-block semantics, configured in JSON rigs (batteries, tanks). A wrench must
 * not be able to turn a machine face into a storage face, so {@code BOTH} is never produced by this
 * cycle. An existing {@code BOTH} port (or any port whose {channel,direction} is not a cycle entry:
 * e.g. a port on a now-unsupported channel) is "unknown": cycling from it enters at the first cycle
 * entry, {@code (firstSupportedChannel, INPUT)}.
 *
 * <h3>No-buffer machine</h3>
 * A machine with no energy buffer and no resource buffers supports nothing: its cycle is the single
 * {@code CLOSED} entry, so {@link #nextPort} always returns a CLOSED port.
 */
public final class WrenchCycles {

    private WrenchCycles() {
    }

    /** What a pipe face points at, resolved by the interaction glue before calling the cycle. */
    public enum Target {
        /** The face points at a network endpoint (a machine / storage block). */
        MACHINE,
        /** The face points at another pipe, air, or any non-endpoint block. */
        PIPE
    }

    /** Forward flow cycle for a pipe face. Toward MACHINE: 4 states; toward PIPE: NORMAL<->NONE. */
    public static FlowState next(FlowState current, Target target) {
        return step(current, target, +1);
    }

    /** Reverse flow cycle for a pipe face (sneak). Exact inverse of {@link #next}. */
    public static FlowState previous(FlowState current, Target target) {
        return step(current, target, -1);
    }

    private static final FlowState[] MACHINE_RING =
        {FlowState.NORMAL, FlowState.PUSH, FlowState.PULL, FlowState.NONE};
    private static final FlowState[] PIPE_RING = {FlowState.NORMAL, FlowState.NONE};

    private static FlowState step(FlowState current, Target target, int dir) {
        FlowState[] ring = target == Target.MACHINE ? MACHINE_RING : PIPE_RING;
        int idx = indexOf(ring, current);
        if (idx < 0) {
            // Stale state not in this ring (e.g. PUSH/PULL on a pipe-to-pipe face): treat it as the
            // ring's first entry (NORMAL) so the tap advances into the ring.
            idx = 0;
        }
        int n = ring.length;
        return ring[((idx + dir) % n + n) % n];
    }

    private static int indexOf(FlowState[] ring, FlowState s) {
        for (int i = 0; i < ring.length; i++) {
            if (ring[i] == s) {
                return i;
            }
        }
        return -1;
    }

    /**
     * What a machine face's port should become on the next wrench tap.
     *
     * @param machine the machine whose buffers define supported capabilities
     * @param faceIndex the clicked face (a passthrough: copied onto the returned {@link Port})
     * @param current the face's current port, or {@code null} if the face has no port yet. A port
     *     whose {channel,direction} is not a cycle entry (e.g. {@code BOTH}, or a now-unsupported
     *     channel) is treated as "unknown" and enters at the first cycle entry.
     * @return the next {@link Port} on that face: never {@code null}. A no-capability machine always
     *     yields a {@code CLOSED} port.
     */
    public static Port nextPort(MachineBlockState machine, int faceIndex, Port current) {
        List<PortEntry> cycle = cycleEntries(machine);
        int idx = current == null ? -1 : indexOf(cycle, current.channel(), current.direction());
        // Unknown / null current -> enter at the first entry; otherwise advance one step (wrapping).
        int nextIdx = idx < 0 ? 0 : (idx + 1) % cycle.size();
        PortEntry e = cycle.get(nextIdx);
        return Port.of(faceIndex, e.channel(), e.direction());
    }

    /** The ordered cycle for a machine: (ch,INPUT),(ch,OUTPUT) per supported channel, then CLOSED. */
    private static List<PortEntry> cycleEntries(MachineBlockState machine) {
        List<PortEntry> entries = new ArrayList<>();
        PortChannel closedChannel = null;
        // PortChannel declaration order: ITEM, FLUID, GAS, POWER (documented stable order).
        for (PortChannel channel : PortChannel.values()) {
            if (!supports(machine, channel)) {
                continue;
            }
            if (closedChannel == null) {
                closedChannel = channel;
            }
            entries.add(new PortEntry(channel, PortDirection.INPUT));
            entries.add(new PortEntry(channel, PortDirection.OUTPUT));
        }
        // Single CLOSED terminator. Its channel is the first supported channel (so it round-trips as
        // valid data); a no-capability machine has only this entry, with a harmless POWER channel.
        entries.add(new PortEntry(closedChannel == null ? PortChannel.POWER : closedChannel,
            PortDirection.CLOSED));
        return entries;
    }

    private static boolean supports(MachineBlockState machine, PortChannel channel) {
        if (channel == PortChannel.POWER) {
            return machine.energy() != null;
        }
        return machine.resource(channel) != null;
    }

    private static int indexOf(List<PortEntry> cycle, PortChannel channel, PortDirection direction) {
        for (int i = 0; i < cycle.size(); i++) {
            PortEntry e = cycle.get(i);
            if (e.channel() == channel && e.direction() == direction) {
                return i;
            }
        }
        return -1;
    }

    private record PortEntry(PortChannel channel, PortDirection direction) {
    }
}
