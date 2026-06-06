package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup.MachinePorts;

/**
 * Pure computation of a pipe's two 6-bit connectivity masks (Task 11, 2026-06-05 pipe flow-states
 * design). These masks drive the programmatic <em>suppressed-arm</em> visuals: when a pipe's
 * <em>effective</em> connectivity (what the network actually joins) differs from its <em>physical</em>
 * connectivity (what the engine's connected-block pattern matcher would weld), the mod overrides the
 * cable's interaction state ({@link PipeShapes#stateFor}) so the rendered arm count matches reality.
 *
 * <h2>The two masks (both in OFFSETS order +X=0,-X=1,+Y=2,-Y=3,+Z=4,-Z=5)</h2>
 * <ul>
 *   <li>{@link #effectiveMask}: bit set iff this face is ACTUALLY joined by the transport layer:
 *       <ul>
 *         <li>a same-channel pipe neighbour that {@link PipeConnectivity#connects} accepts (channel +
 *             both flow states non-NONE + substance compatible), OR</li>
 *         <li>a machine/tank neighbour that endpoint-collection would qualify through this face: the
 *             pipe face's own {@link FlowState} is not {@link FlowState#NONE NONE} AND the neighbour's
 *             facing port ({@code portAt(opposite(face), channel)}) OVERLAPS the face's flow state per
 *             {@link NetworkEndpoints}' full classify matrix (OUTPUT joins under NORMAL/PULL, INPUT
 *             under NORMAL/PUSH, BOTH under any non-NONE, CLOSED never): see
 *             {@code machineEndpointJoins}.</li>
 *       </ul></li>
 *   <li>{@link #physicalMask}: bit set iff the engine's pattern matcher would stub an arm to this face,
 *       ignoring flow states and substance compatibility:
 *       <ul>
 *         <li>any same-channel pipe neighbour (regardless of NONE faces or substance lock), OR</li>
 *         <li>a machine/tank neighbour that ADVERTISES the channel as a connectable face tag.</li>
 *       </ul></li>
 * </ul>
 *
 * <h2>Machine physical-mask approximation (flag for in-game validation)</h2>
 * The engine stubs a connected-block arm to a neighbour based on the {@code FaceTags} advertised by the
 * neighbour's block <em>template</em>, which the template declares statically REGARDLESS of the live
 * port config. We have no FaceTags read seam in the pure layer, so {@link #physicalMask} APPROXIMATES
 * "advertises the channel face" as "the neighbour has ANY port for this channel on the facing face"
 * (via {@link #machineAdvertisesChannel}). This is exact when a machine's connectable faces equal its
 * configured-port faces (the common case for our CC machines/tanks, whose templates tag exactly the
 * port faces). It can DIVERGE if a template advertises a channel face the live config leaves portless,
 * or vice versa: then the physical mask is wrong and the suppressed-arm override may mis-fire. This is
 * the only modelled gap in the otherwise-faithful pure twin; flagged for Task 15 in-game validation.
 *
 * <h2>Why the comparison matters</h2>
 * When {@code effectiveMask == physicalMask} the pipe is "undisturbed": the engine's pattern system
 * already drew the right arms and the H8 powered flip retextures them in place. The driver must then do
 * NOTHING beyond H8. Only a divergence (a suppressed arm: a NONE face, a substance-mismatched pipe, or
 * a machine the flow state hides) warrants a programmatic state swap. See
 * {@code NetworkTickSystem#applySuppressedArmVisual} for the thin application half (which also handles
 * the rotation-mismatch fallback documented on {@link PipeShapes}).
 *
 * <p>Pure JDK + mod data types ({@link PipeNode}/{@link FlowState}/{@link PortChannel}/{@link PortConfig}
 * + the {@link PipeGridView}/{@link MachineLookup} seams): no engine/world types, fully unit-testable.
 */
public final class PipeVisualStates {

    /** The 6 face-neighbour offsets, +X,-X,+Y,-Y,+Z,-Z (mirrors {@link NetworkManager}'s OFFSETS). */
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private PipeVisualStates() {
    }

    /**
     * The EFFECTIVE connectivity bitmask for the pipe at {@code (x,y,z)}: which of its 6 faces the
     * transport layer actually joins. See the class javadoc. Never throws; an absent/odd lookup simply
     * leaves that face unset.
     *
     * @param pipe   the pipe node at {@code (x,y,z)} (its per-face {@link FlowState} and channel drive
     *               the gate); if null, returns 0 (no pipe, no arms).
     * @param x      pipe block X.
     * @param y      pipe block Y.
     * @param z      pipe block Z.
     * @param grid   resolves a same-channel pipe neighbour's {@link PipeNode}.
     * @param lookup resolves a machine/tank neighbour's ports.
     * @return the 6-bit effective mask (bit i set means face i is joined), in OFFSETS order.
     */
    public static int effectiveMask(PipeNode pipe, int x, int y, int z, PipeGridView grid, MachineLookup lookup) {
        if (pipe == null) {
            return 0;
        }
        PortChannel channel = pipe.channel();
        int mask = 0;
        for (int face = 0; face < OFFSETS.length; face++) {
            int[] off = OFFSETS[face];
            int nx = x + off[0], ny = y + off[1], nz = z + off[2];

            // (a) same-channel pipe neighbour, gated exactly like the network BFS.
            PipeNode neighbourPipe = grid == null ? null : grid.pipeAt(nx, ny, nz);
            if (neighbourPipe != null) {
                if (PipeConnectivity.connects(pipe, face, neighbourPipe)) {
                    mask |= (1 << face);
                }
                continue; // a pipe occupies the cell: it is not also a machine endpoint
            }

            // (b) machine/tank endpoint, gated exactly like NetworkEndpoints.collect: the pipe face
            // must be non-NONE AND the neighbour's facing port must OVERLAP the face's flow state
            // (the full classify matrix, not just port-exists: see machineEndpointJoins).
            if (pipe.flowState(face) == FlowState.NONE) {
                continue;
            }
            if (machineEndpointJoins(lookup, nx, ny, nz, face, channel, pipe.flowState(face))) {
                mask |= (1 << face);
            }
        }
        return mask;
    }

    /**
     * The PHYSICAL connectivity bitmask for the pipe at {@code (x,y,z)}: which of its 6 faces the
     * engine's connected-block pattern matcher would weld an arm to, ignoring flow states and substance
     * compatibility. See the class javadoc (and its machine-approximation note). Never throws.
     *
     * @param pipe   the pipe node at {@code (x,y,z)}; if null, returns 0.
     * @param x      pipe block X.
     * @param y      pipe block Y.
     * @param z      pipe block Z.
     * @param grid   resolves a same-channel pipe neighbour's {@link PipeNode}.
     * @param lookup resolves a machine/tank neighbour's ports.
     * @return the 6-bit physical mask, in OFFSETS order.
     */
    public static int physicalMask(PipeNode pipe, int x, int y, int z, PipeGridView grid, MachineLookup lookup) {
        if (pipe == null) {
            return 0;
        }
        PortChannel channel = pipe.channel();
        int mask = 0;
        for (int face = 0; face < OFFSETS.length; face++) {
            int[] off = OFFSETS[face];
            int nx = x + off[0], ny = y + off[1], nz = z + off[2];

            // (a) any same-channel pipe neighbour stubs an arm, regardless of NONE/substance.
            PipeNode neighbourPipe = grid == null ? null : grid.pipeAt(nx, ny, nz);
            if (neighbourPipe != null) {
                if (neighbourPipe.channel() == channel) {
                    mask |= (1 << face);
                }
                continue;
            }

            // (b) machine/tank that ADVERTISES the channel face (approximated by "has any facing port
            // for the channel"): the engine stubs to it regardless of the pipe's own flow state.
            if (machineAdvertisesChannel(lookup, nx, ny, nz, face, channel)) {
                mask |= (1 << face);
            }
        }
        return mask;
    }

    /**
     * Whether the transport layer would actually JOIN the machine/tank at {@code (nx,ny,nz)} through
     * this face: the neighbour carries a facing port of {@code channel}
     * ({@code portAt(opposite(face), channel)}) AND that port's direction OVERLAPS the pipe face's flow
     * state, replicating {@link NetworkEndpoints}' classify matrix in full:
     * <ul>
     *   <li>OUTPUT port: joins under NORMAL or PULL (PUSH has no overlap: the port only offers).</li>
     *   <li>INPUT port: joins under NORMAL or PUSH (PULL has no overlap).</li>
     *   <li>BOTH port: joins under any non-NONE flow state.</li>
     *   <li>CLOSED port: never joins (the wrench persists "closed" as a CLOSED port, not a removed one).</li>
     * </ul>
     * Lifted gate-for-gate from {@code NetworkEndpoints.classify} so the visual twin cannot drift from
     * the transport truth. Used by {@link #effectiveMask}.
     */
    private static boolean machineEndpointJoins(
            MachineLookup lookup, int nx, int ny, int nz, int face, PortChannel channel, FlowState flow) {
        if (lookup == null) {
            return false;
        }
        MachinePorts neighbour = lookup.at(nx, ny, nz);
        if (neighbour == null) {
            return false;
        }
        PortConfig ports = neighbour.ports();
        if (ports == null) {
            return false;
        }
        Port facing = ports.portAt(PipeConnectivity.opposite(face), channel);
        if (facing == null) {
            return false;
        }
        // The direction x flow-state overlap, gate-for-gate with NetworkEndpoints.classify.
        return switch (facing.direction()) {
            case OUTPUT -> flow == FlowState.NORMAL || flow == FlowState.PULL;
            case INPUT -> flow == FlowState.NORMAL || flow == FlowState.PUSH;
            case BOTH -> true; // flow is already non-NONE (caller gates NONE)
            default -> false;  // CLOSED: never a transport endpoint
        };
    }

    /**
     * Whether a machine/tank at {@code (nx,ny,nz)} ADVERTISES {@code channel} on its connectable face
     * toward the pipe, for the {@link #physicalMask}. See the class javadoc's machine-approximation
     * note: with no FaceTags read seam we approximate "advertises the channel face" as "has ANY port for
     * the channel ANYWHERE on the block". We deliberately do NOT restrict to the facing face here: a
     * connected-block template typically tags every channel-bearing face the same, so a machine present
     * with the channel at all is treated as physically stubbed-to on the touching face. This keeps the
     * physical mask a conservative superset of the effective mask in the common case (so divergence only
     * appears where a flow state / substance / facing actually suppresses a real port).
     */
    private static boolean machineAdvertisesChannel(
            MachineLookup lookup, int nx, int ny, int nz, int face, PortChannel channel) {
        if (lookup == null) {
            return false;
        }
        MachinePorts neighbour = lookup.at(nx, ny, nz);
        if (neighbour == null) {
            return false;
        }
        PortConfig ports = neighbour.ports();
        if (ports == null) {
            return false;
        }
        for (Port p : ports.ports()) {
            if (p != null && p.channel() == channel) {
                return true;
            }
        }
        return false;
    }
}
