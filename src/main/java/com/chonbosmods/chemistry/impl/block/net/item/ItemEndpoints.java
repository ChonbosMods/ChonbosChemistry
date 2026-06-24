package com.chonbosmods.chemistry.impl.block.net.item;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup.MachinePorts;
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
        return collect(net, grid, containers, null);
    }

    /**
     * Machine-aware overload: in addition to passive containers, qualifies a neighbour machine's ITEM
     * PORT as a directional endpoint (a smelter's item-in / item-out). Unlike a passive container (role
     * by face flow state alone), a machine port's role is fixed by its {@link PortDirection} and only
     * qualifies when that direction OVERLAPS the bordering pipe face: an {@code OUTPUT} port is a
     * {@link Source} on a {@code NORMAL}/{@code PULL} face; an {@code INPUT} port is a {@link Destination}
     * on a {@code NORMAL}/{@code PUSH} face; {@code BOTH} mirrors a container (PULL extracts, else
     * delivers); {@code CLOSED} or a non-overlapping face qualifies nothing. A machine ITEM port takes
     * precedence over (and is mutually exclusive with) a passive container at the same cell. Downstream
     * the endpoint's view is resolved by ROLE (a Source &rarr; the bench OUTPUT container, a Destination
     * &rarr; the bench INPUT container) via {@link MachineLookup.MachinePorts#itemContainer}, so the
     * record itself needs no machine marker.
     *
     * @param machines machine/tank port access behind the {@link MachineLookup} seam; {@code null} =
     *                 machine-unaware (identical to the 3-arg overload: containers only).
     */
    public static Endpoints collect(
            Network net, PipeGridView grid, ContainerLookup containers, MachineLookup machines) {
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
        // Machine ports dedup per PORT (cell + machine face), NOT per cell: a single footprint cell can
        // expose several item ports (the CC Reclaimer's item-in + item-out both sit on its anchor cell).
        // Keying machine ports by cell alone collapsed them so only the first-walked role registered,
        // dropping the other — piped items then bypassed the machine entirely ("went straight through").
        // Passive containers below stay cell-keyed (a chest is one inventory).
        Set<String> visitedMachinePorts = new HashSet<>();

        for (long memberKey : net.memberKeys()) {
            int px = NetworkManager.unpackX(memberKey);
            int py = NetworkManager.unpackY(memberKey);
            int pz = NetworkManager.unpackZ(memberKey);
            PipeNode member = grid.pipeAt(px, py, pz);
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

                // (1) Machine ITEM port (directional), takes precedence over a passive container at the
                // same cell. Qualify by the facing port's direction x face overlap; an unqualified port
                // (CLOSED / non-overlapping face) claims no dedup slot, exactly like an absent container.
                if (machines != null) {
                    MachinePorts mp = machines.at(nx, ny, nz);
                    if (mp != null && mp.ports() != null) {
                        int machineFace = faceIdx ^ 1;
                        Port port = mp.ports().portAt(machineFace, PortChannel.ITEM);
                        if (port != null) {
                            Boolean isSource = machinePortRole(port.direction(), face);
                            // Dedup by (cell, machine face) so multiple item ports on one cell each register
                            // (see visitedMachinePorts above). Still claim the cell in visitedContainers so a
                            // machine ITEM port suppresses any passive container at the same cell.
                            if (isSource != null && visitedMachinePorts.add(containerKey + ":" + machineFace)) {
                                visitedContainers.add(containerKey);
                                if (isSource) {
                                    sources.add(new Source(containerKey, memberKey, faceIdx));
                                } else {
                                    destinations.add(new Destination(containerKey, memberKey, faceIdx));
                                }
                            }
                            continue; // a machine ITEM port owns this cell: never also a passive container
                        }
                    }
                }

                // (2) Passive container (chest). Qualify FIRST (container present?) so an empty cell costs
                // no dedup slot.
                if (containers.at(nx, ny, nz) == null) {
                    continue;
                }
                // Dedup CHECK (not yet claim): a container already collected via another member pipe is
                // skipped here. Only a qualified container claims the slot (below), so an unqualified
                // cell does not block a sibling member's reachable face.
                if (visitedContainers.contains(containerKey)) {
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

    /**
     * The role a machine ITEM {@link Port} of {@code direction} plays for a bordering pipe {@code face},
     * or {@code null} when the direction does not overlap the face (qualifies nothing). Mirrors the
     * power/fluid/gas direction&times;flow matrix ({@code NetworkEndpoints.overlaps}) specialised to ITEM:
     * <ul>
     *   <li>{@code OUTPUT} &rarr; {@code TRUE} (Source) on a {@code NORMAL}/{@code PULL} face.</li>
     *   <li>{@code INPUT} &rarr; {@code FALSE} (Destination) on a {@code NORMAL}/{@code PUSH} face.</li>
     *   <li>{@code BOTH} &rarr; mirrors a passive container: {@code PULL} extracts, else delivers.</li>
     *   <li>{@code CLOSED} (or a non-overlapping face) &rarr; {@code null} (nothing).</li>
     * </ul>
     * The caller has already skipped {@code NONE} faces.
     *
     * @return {@code TRUE} = a Source, {@code FALSE} = a Destination, {@code null} = no overlap
     */
    private static Boolean machinePortRole(PortDirection direction, FlowState face) {
        return switch (direction) {
            case OUTPUT -> (face == FlowState.NORMAL || face == FlowState.PULL) ? Boolean.TRUE : null;
            case INPUT -> (face == FlowState.NORMAL || face == FlowState.PUSH) ? Boolean.FALSE : null;
            case BOTH -> face == FlowState.PULL ? Boolean.TRUE : Boolean.FALSE;
            default -> null; // CLOSED
        };
    }
}
