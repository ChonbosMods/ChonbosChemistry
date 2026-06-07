package com.chonbosmods.chemistry.impl.block.net.item;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.EndpointConnectionCap;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects an ITEM {@link Network}'s delivery {@link Destination}s and extraction {@link Source}s by
 * walking each member pipe's 6 face-neighbours and qualifying any adjacent storage container
 * (2026-06-06 item-channel design §13.4, "Architecture &rarr; Pure"; mirrors the parent
 * {@code NetworkEndpoints} OFFSETS-walk + dedup pattern).
 *
 * <p>Containers are PASSIVE: unlike machines they advertise NO ports, so the bordering pipe FACE flow
 * state alone decides the role (design "Decisions" &rarr; "Face rules on ITEM faces"):
 * <table>
 *   <caption>pipe face flow state &rarr; container role</caption>
 *   <tr><th>NORMAL</th><td>destination (deliver-into; never auto-extracts)</td></tr>
 *   <tr><th>PUSH</th><td>destination only (insert-only)</td></tr>
 *   <tr><th>PULL</th><td>source (auto-extracts; deliveries blocked)</td></tr>
 *   <tr><th>NONE</th><td>neither (invisible)</td></tr>
 * </table>
 *
 * <p>Face walk &amp; dedup mirror {@code NetworkEndpoints} EXACTLY. The OFFSETS loop index IS the face.
 * A NONE face is skipped BEFORE the dedup claim so it stays dedup-NEUTRAL: it neither contributes nor
 * blocks a sibling member's reachable (non-NONE) face onto the same container. A cell occupied by a
 * pipe ({@link PipeGridView#pipeAt} non-null) is never a container endpoint, even if the lookup also
 * claims a container there (pipe occupancy wins). KNOWN SIMPLIFICATION (same as {@code NetworkEndpoints}):
 * the per-container dedup keeps the FIRST non-NONE member face's contribution; a container bordering one
 * network through two faces with DIFFERENT flow states takes the first-encountered face's role.
 *
 * <p>CHANNEL GUARD (defensive): this collector is only meaningful for ITEM networks (the item subsystem
 * never runs on other channels). A non-ITEM network returns empty endpoints rather than mis-qualifying
 * containers around a power/fluid/gas line.
 *
 * <p>Pure JDK + net-layer types only: no engine imports. The world {@link ContainerLookup} (Task 7)
 * supplies the live container access behind the seam.
 */
public final class ItemEndpoints {

    /** The 6 face-neighbour offsets, +X,-X,+Y,-Y,+Z,-Z (mirrors {@code NetworkEndpoints}'s OFFSETS). */
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private ItemEndpoints() {
    }

    /**
     * A container the network can DELIVER into, reached through one member pipe's face. The face flow
     * state was NORMAL or PUSH.
     *
     * @param containerKey packed position key of the container block
     * @param viaPipeKey   packed position key of the member pipe bordering it
     * @param viaFace      the {@code OFFSETS}-order face index (0..5) the pipe presents toward it
     */
    public record Destination(long containerKey, long viaPipeKey, int viaFace) {
    }

    /**
     * A container the network can EXTRACT from, reached through one member pipe's PULL face.
     *
     * @param containerKey packed position key of the container block
     * @param viaPipeKey   packed position key of the member pipe bordering it
     * @param viaFace      the {@code OFFSETS}-order face index (0..5) the pipe presents toward it
     */
    public record Source(long containerKey, long viaPipeKey, int viaFace) {
    }

    /**
     * The container endpoints collected around an ITEM network. Never null; either list may be empty.
     */
    public record Endpoints(List<Destination> destinations, List<Source> sources) {
    }

    /**
     * Walks every member pipe's face-neighbours and collects container endpoints, classifying each by
     * the bordering pipe face's {@link FlowState} per the class-javadoc table.
     *
     * @param net        the network whose members drive the search; non-ITEM networks return empty.
     * @param grid       the pipe grid: resolves each member pipe's {@link PipeNode} to read per-face
     *                   {@link FlowState}, and detects pipe-occupied neighbour cells. A member with no
     *                   node in the grid is treated as all-NORMAL.
     * @param containers live (or fake) container access behind the {@link ContainerLookup} seam.
     * @return the classified endpoints. Never null; either list may be empty.
     */
    public static Endpoints collect(Network net, PipeGridView grid, ContainerLookup containers) {
        List<Destination> destinations = new ArrayList<>();
        List<Source> sources = new ArrayList<>();
        // Channel guard: the ITEM subsystem must not qualify containers around non-item networks.
        if (net.channel() != PortChannel.ITEM) {
            return new Endpoints(destinations, sources);
        }
        // Dedup by container position: once a position has contributed, skip it on subsequent member-pipe
        // hits. NONE faces are dedup-NEUTRAL: a NONE face hides its neighbour entirely, so it must NOT
        // claim the slot (else it would mask the container from a sibling member's reachable face). NOTE
        // the known simplification in the class javadoc: among NON-NONE faces a multi-face container
        // takes the FIRST-encountered face's flow-state role.
        Set<Long> visitedContainers = new HashSet<>();

        for (long memberKey : net.memberKeys()) {
            int px = NetworkManager.unpackX(memberKey);
            int py = NetworkManager.unpackY(memberKey);
            int pz = NetworkManager.unpackZ(memberKey);
            PipeNode member = grid.pipeAt(px, py, pz);
            // Connection cap (2026-06-07 design Decision 1): this MEMBER pipe joins at most 3 containers.
            // Fresh per member pipe (per-pipe, not per-network): two pipes can each reach 3. Counts
            // CONNECTIONS only: a NONE face / empty cell never consumes budget. See EndpointConnectionCap.
            EndpointConnectionCap cap = new EndpointConnectionCap();
            for (int faceIdx = 0; faceIdx < OFFSETS.length; faceIdx++) {
                int[] off = OFFSETS[faceIdx];
                int nx = px + off[0], ny = py + off[1], nz = pz + off[2];
                // The pipe's face toward this neighbour: NONE hides it entirely. Check BEFORE the dedup
                // claim so a NONE face stays dedup-neutral (mirrors NetworkEndpoints exactly).
                FlowState face = member == null ? FlowState.NORMAL : member.flowState(faceIdx);
                if (face == FlowState.NONE) {
                    continue;
                }
                // A pipe-occupied cell is part of the network, never a container endpoint: pipe
                // occupancy wins over any container the lookup might also claim there.
                if (grid.pipeAt(nx, ny, nz) != null) {
                    continue;
                }
                long containerKey = NetworkManager.packKey(nx, ny, nz);
                // Qualify FIRST (container present?) so an empty cell costs no dedup slot and no budget.
                if (containers.at(nx, ny, nz) == null) {
                    continue;
                }
                // Dedup CHECK (not yet claim): a container already collected via another member pipe is
                // skipped here and must NOT consume this pipe's budget.
                if (visitedContainers.contains(containerKey)) {
                    continue;
                }
                // Cap CHECK before the dedup CLAIM: a capped-out endpoint is skipped ENTIRELY (not
                // collected, not dedup-claimed: per the plan), leaving it free for a sibling member pipe.
                if (!cap.tryClaim()) {
                    continue;
                }
                visitedContainers.add(containerKey);
                // Face state alone decides the role (containers have no ports): NORMAL/PUSH deliver,
                // PULL extracts. NONE was already skipped above.
                if (face == FlowState.PULL) {
                    sources.add(new Source(containerKey, memberKey, faceIdx));
                } else {
                    destinations.add(new Destination(containerKey, memberKey, faceIdx));
                }
            }
        }
        return new Endpoints(destinations, sources);
    }
}
