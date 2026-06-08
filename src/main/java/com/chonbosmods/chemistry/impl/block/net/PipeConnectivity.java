package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.FlowState;
import java.util.Objects;

/**
 * The single pipe-pipe connection gate (2026-06-05 pipe flow-states design §1). Two adjacent
 * pipes connect across a face iff ALL of the following hold:
 *
 * <ul>
 *   <li>both nodes are non-null (defensive),</li>
 *   <li>they share the same {@link com.chonbosmods.chemistry.api.io.PortChannel channel} (a
 *       different-channel neighbour is a network boundary): kept here so the BFS has ONE gate,</li>
 *   <li>neither facing {@link FlowState} is {@link FlowState#NONE NONE}: {@code a}'s face toward
 *       {@code b} and {@code b}'s opposite face ({@code faceFromAToB ^ 1}),</li>
 *   <li>their substances are compatible: either side's {@code resourceId} is null (an empty line
 *       joins a locked one), or the two are equal. Two pipes locked to DIFFERENT substances do not
 *       connect: this is the gas-bug fix.</li>
 * </ul>
 *
 * <p>{@link FlowState#PUSH PUSH}/{@link FlowState#PULL PULL} are endpoint (pipe-machine) semantics
 * only; on a pipe-pipe face they are treated as {@link FlowState#NORMAL NORMAL} (i.e. they connect).
 * Only {@code NONE} severs a pipe-pipe face.
 *
 * <p>Pure JDK + {@link PipeNode}/{@link FlowState}: no engine types.
 */
public final class PipeConnectivity {

    private PipeConnectivity() {
    }

    /**
     * Whether pipe {@code a} connects to pipe {@code b} across the face {@code a} presents toward
     * {@code b}.
     *
     * @param a            the source pipe (its face {@code faceFromAToB} points at {@code b}).
     * @param faceFromAToB the {@code OFFSETS}-order face index (+X,-X,+Y,-Y,+Z,-Z) from {@code a}
     *     toward {@code b}; {@code b} sees {@code a} through {@link #opposite(int)} of this.
     * @param b            the neighbouring pipe.
     * @return true iff the two pipes should be joined into one network across this face.
     */
    public static boolean connects(PipeNode a, int faceFromAToB, PipeNode b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.channel() != b.channel()) {
            return false;
        }
        if (a.flowState(faceFromAToB) == FlowState.NONE
                || b.flowState(opposite(faceFromAToB)) == FlowState.NONE) {
            return false;
        }
        String ra = a.resourceId();
        String rb = b.resourceId();
        return ra == null || rb == null || Objects.equals(ra, rb);
    }

    /**
     * The opposite face in {@code OFFSETS} order: paired directions sit adjacent, so XOR-1 flips
     * +X&harr;-X (0&harr;1), +Y&harr;-Y (2&harr;3), +Z&harr;-Z (4&harr;5). Reused by the network
     * BFS and wrench logic.
     */
    public static int opposite(int face) {
        return face ^ 1;
    }
}
