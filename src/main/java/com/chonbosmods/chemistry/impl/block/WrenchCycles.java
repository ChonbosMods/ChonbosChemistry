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

    /**
     * Maximum number of PUSH/PULL (directed) faces a single pipe block may carry, any shape (design
     * 2026-06-07 decision 2). When this many directed faces already exist on OTHER faces, the pipe-face
     * cycle toward a {@link Target#MACHINE} skips PUSH/PULL: it degrades to the two-state PIPE ring
     * ({@code NORMAL <-> NONE}) so the player cannot add a third. The generator + {@code PipeShapes}
     * reference this same concept (a named constant beats a magic 2).
     */
    public static final int MAX_DIRECTED_FACES = 2;

    /** What a pipe face points at, resolved by the interaction glue before calling the cycle. */
    public enum Target {
        /**
         * The face does NOT touch another pipe: a network endpoint (machine / storage / container), a
         * solid block, or empty air. All of these get the full four-state ring so a player can
         * pre-configure a bare pipe END to PUSH/PULL before the endpoint exists (design 2026-06-07).
         */
        MACHINE,
        /** The face touches another pipe: the pipe-to-pipe connection ring only ({@code NORMAL <-> NONE}). */
        PIPE
    }

    /**
     * Resolves the {@link Target} ring for a clicked pipe face from a single fact: whether the block
     * across the face is another pipe (design 2026-06-07). A pipe neighbour is the ONLY thing that
     * restricts the face to the two-state {@code NORMAL <-> NONE} ring (push/pull have no meaning
     * between two pipes). Every other neighbour: a machine/tank/container endpoint, a solid block, or
     * empty air, yields the full {@code NORMAL -> PUSH -> PULL -> NONE} ring, so a pipe END can be
     * configured to push/pull and the config persists for whenever an endpoint is later placed there.
     * The interaction glue computes {@code neighbourIsPipe} with a single world lookup and calls this.
     */
    public static Target targetForNeighbour(boolean neighbourIsPipe) {
        return neighbourIsPipe ? Target.PIPE : Target.MACHINE;
    }

    /**
     * Forward flow cycle for a pipe face with no budget pressure. Toward MACHINE: 4 states; toward PIPE:
     * NORMAL&lt;-&gt;NONE. Equivalent to {@link #next(FlowState, Target, int)} with {@code otherDirectedFaces = 0}.
     */
    public static FlowState next(FlowState current, Target target) {
        return next(current, target, 0);
    }

    /**
     * Reverse flow cycle for a pipe face (sneak) with no budget pressure. Exact inverse of
     * {@link #next(FlowState, Target)}.
     */
    public static FlowState previous(FlowState current, Target target) {
        return previous(current, target, 0);
    }

    /**
     * Forward flow cycle for a pipe face under the directed-face budget (design 2026-06-07 decision 2).
     *
     * @param current the clicked face's current flow state
     * @param target what the face points at (a {@link Target#MACHINE} endpoint, or a {@link Target#PIPE})
     * @param otherDirectedFaces the number of PUSH/PULL faces on this pipe EXCLUDING the clicked face.
     *     The caller (the interaction glue) counts directed faces and subtracts the clicked face so the
     *     face's own current state never counts against itself. NOTE the budget-spent exception: with 2+ OTHER directed faces, a clicked directed face collapses out of push/pull (the ring has no directed entries) and cannot re-enter until budget frees: the wrench drives over-budget legacy data toward compliance.
     * @return the next flow state. Toward PIPE the budget is irrelevant (push/pull are never offered).
     *     Toward MACHINE with fewer than {@link #MAX_DIRECTED_FACES} other directed faces: the full
     *     {@code NORMAL -> PUSH -> PULL -> NONE} ring. Toward MACHINE with the budget spent
     *     ({@code otherDirectedFaces >= MAX_DIRECTED_FACES}): the ring degrades to the PIPE ring
     *     ({@code NORMAL <-> NONE}), so push/pull drop out and a third directed face cannot be added.
     */
    public static FlowState next(FlowState current, Target target, int otherDirectedFaces) {
        return step(current, effectiveTarget(target, otherDirectedFaces), +1);
    }

    /**
     * Reverse flow cycle for a pipe face (sneak) under the directed-face budget. Exact inverse of
     * {@link #next(FlowState, Target, int)} WITHIN the same budget condition (the two stay inverses for a
     * fixed {@code otherDirectedFaces}).
     */
    public static FlowState previous(FlowState current, Target target, int otherDirectedFaces) {
        return step(current, effectiveTarget(target, otherDirectedFaces), -1);
    }

    /**
     * Resolves the target the ring is built from: a MACHINE face whose pipe has already spent the
     * directed-face budget elsewhere degrades to a PIPE-style two-state ring (push/pull skipped). A PIPE
     * target is unaffected (it never offers push/pull regardless of the count).
     */
    private static Target effectiveTarget(Target target, int otherDirectedFaces) {
        if (target == Target.MACHINE && otherDirectedFaces >= MAX_DIRECTED_FACES) {
            return Target.PIPE;
        }
        return target;
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
