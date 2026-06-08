package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup.MachinePorts;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects a {@link Network}'s {@link Provider}s and {@link Acceptor}s by walking each member pipe's
 * 6 face-neighbours and adapting any port-bearing machine/tank found there (via {@link MachineLookup})
 * to the network's channel.
 *
 * <p>Collection is FACE-PRECISE (2026-06-05 pipe flow-states design §1, Task 6): a neighbour qualifies
 * only through the single port on the face that points BACK at the pipe, and the pipe face's own
 * {@link FlowState} then filters what role that port may play. The port says what the machine OFFERS;
 * the pipe face says what the network ACCEPTS through it.
 *
 * <p>The filter matrix (facing-port direction × pipe-face flow state):
 * <table>
 *   <caption>OFFER (port) × ACCEPT (pipe face)</caption>
 *   <tr><th></th><th>NORMAL</th><th>PUSH</th><th>PULL</th><th>NONE</th></tr>
 *   <tr><td>OUTPUT</td><td>pure provider</td><td>skip</td><td>pure provider</td><td>skip</td></tr>
 *   <tr><td>INPUT</td><td>pure acceptor</td><td>pure acceptor</td><td>skip</td><td>skip</td></tr>
 *   <tr><td>BOTH</td><td>StorageEndpoint
 *       (storages + both buffer lists)</td><td>buffer acceptor only</td>
 *       <td>buffer provider only</td><td>skip</td></tr>
 * </table>
 * Rationale: NONE hides the neighbour entirely. PUSH means the network only feeds the machine, so only
 * its acceptor half is reachable; PULL only its provider half. A storage block pairs into a balancing
 * {@link StorageEndpoint} ONLY across a NORMAL (two-way) face: a one-way PUSH/PULL face joins it as a
 * single buffer half, never as a paired gauge (balancing needs to both fill AND drain it).
 *
 * <p>Pure sources/sinks vs storage (H6 FIX 2): splitting the BOTH/storage halves into separate buffer
 * lists lets {@link NetworkTransfer} prioritise real sources/sinks over storage and so avoids the
 * self-churn where a storage block re-supplies its own energy each tick and starves the actual source.
 * POWER endpoints adapt through their {@link EnergyHandler}; FLUID/GAS endpoints through their
 * {@link ResourceBuffer} for that channel. ITEM is not handled here (no buffer adapter for items yet).
 *
 * <p>KNOWN SIMPLIFICATION: the per-network neighbour dedup keeps only the FIRST member pipe's face
 * contribution for a neighbour bordering several member pipes. A machine touching one network through
 * two faces with DIFFERENT flow states therefore takes the first-encountered face's role; the other
 * face is ignored. Acceptable while machines are large multiblocks (one network rarely wraps two faces
 * of the same machine); flagged for a future per-face revision. NONE faces are dedup-NEUTRAL: a NONE
 * face hides its neighbour and never claims the dedup slot, so the first-face-wins race applies only
 * among NON-NONE faces (a machine reachable via a sibling member's non-NONE face stays visible even if
 * an earlier-visited member borders it through a NONE face).
 */
public final class NetworkEndpoints {

    /** The 6 face-neighbour offsets, +X,-X,+Y,-Y,+Z,-Z (mirrors {@link NetworkManager}'s OFFSETS). */
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private NetworkEndpoints() {
    }

    /**
     * The endpoints collected around a network, classified for {@link NetworkTransfer#distribute}:
     * pure sources, pure sinks, and storage (BOTH-port) endpoints split into a buffer-provider and a
     * buffer-acceptor view. Never null; any list may be empty.
     */
    public record Endpoints(
            List<Provider> pureProviders,
            List<Acceptor> pureAcceptors,
            List<Provider> bufferProviders,
            List<Acceptor> bufferAcceptors,
            List<StorageEndpoint> storages) {
    }

    /**
     * Walks every member pipe's face-neighbours and collects endpoints matching {@code net}'s channel,
     * classifying each by the FACING port × pipe-face flow state per the matrix in the class javadoc.
     *
     * @param net    the network whose channel/members drive the search.
     * @param grid   the pipe grid: resolves each member pipe's {@link PipeNode} to read its per-face
     *               {@link FlowState}. A member with no node in the grid is treated as all-NORMAL.
     * @param lookup live (or fake) block access for resolving a neighbour's ports.
     * @return the classified endpoints. Never null; any list may be empty.
     */
    public static Endpoints collect(Network net, PipeGridView grid, MachineLookup lookup) {
        PortChannel channel = net.channel();
        List<Provider> pureProviders = new ArrayList<>();
        List<Acceptor> pureAcceptors = new ArrayList<>();
        List<Provider> bufferProviders = new ArrayList<>();
        List<Acceptor> bufferAcceptors = new ArrayList<>();
        List<StorageEndpoint> storages = new ArrayList<>();
        // A neighbour block bordering two member pipes of THIS network would otherwise be wrapped twice
        // (a double share in the fair-split). Dedup by neighbour block position: once a position has
        // contributed, skip it on subsequent member-pipe hits. NONE faces are dedup-NEUTRAL: a NONE face
        // hides its neighbour entirely, so it must NOT claim the slot (else it would mask the machine
        // from a sibling member's reachable face). NOTE the known simplification in the class javadoc:
        // among NON-NONE faces a multi-face machine takes the FIRST-encountered face's flow-state role.
        Set<Long> visitedNeighbours = new HashSet<>();

        for (long memberKey : net.memberKeys()) {
            int px = NetworkManager.unpackX(memberKey);
            int py = NetworkManager.unpackY(memberKey);
            int pz = NetworkManager.unpackZ(memberKey);
            PipeNode member = grid.pipeAt(px, py, pz);
            for (int faceIdx = 0; faceIdx < OFFSETS.length; faceIdx++) {
                int[] off = OFFSETS[faceIdx];
                int nx = px + off[0], ny = py + off[1], nz = pz + off[2];
                // The pipe's face toward this neighbour: NONE hides it entirely. Check BEFORE the dedup
                // claim so a NONE face stays dedup-neutral: it neither contributes nor blocks a sibling
                // member's reachable (non-NONE) face onto the same neighbour.
                FlowState face = member == null ? FlowState.NORMAL : member.flowState(faceIdx);
                if (face == FlowState.NONE) {
                    continue;
                }
                long neighbourKey = NetworkManager.packKey(nx, ny, nz);
                // Dedup CHECK (not yet claim): a neighbour already collected via another member pipe is
                // skipped here. The CLAIM happens only once the neighbour qualifies (below), so an
                // unqualified neighbour does not block a sibling member's reachable face.
                if (visitedNeighbours.contains(neighbourKey)) {
                    continue;
                }
                MachinePorts neighbour = lookup.at(nx, ny, nz);
                if (neighbour == null) {
                    continue;
                }
                PortConfig ports = neighbour.ports();
                if (ports == null) {
                    continue;
                }
                // Face-precise: only the port on the neighbour face that points BACK at the pipe
                // (opposite of this pipe's face) qualifies.
                Port facing = ports.portAt(PipeConnectivity.opposite(faceIdx), channel);
                if (facing == null) {
                    continue;
                }
                // A facing port that does not OVERLAP the pipe face (CLOSED, or PUSH-at-OUTPUT, etc.) is
                // not a real connection: it claims no dedup slot. Probe first.
                if (!overlaps(facing.direction(), face)) {
                    continue;
                }
                // Only a collected endpoint claims the dedup slot, leaving an unqualified neighbour free
                // for a sibling member pipe.
                visitedNeighbours.add(neighbourKey);
                classify(channel, facing.direction(), face, neighbour,
                    pureProviders, pureAcceptors, bufferProviders, bufferAcceptors, storages);
            }
        }
        return new Endpoints(pureProviders, pureAcceptors, bufferProviders, bufferAcceptors, storages);
    }

    /**
     * Applies the OFFER (facing-port {@code direction}) × ACCEPT (pipe {@code face}) matrix for one
     * qualified neighbour, appending the resulting endpoint(s) to the relevant list(s). See the class
     * javadoc for the full matrix and rationale. CLOSED ports and unreachable combinations contribute
     * nothing.
     */
    private static void classify(
            PortChannel channel, PortDirection direction, FlowState face, MachinePorts neighbour,
            List<Provider> pureProviders, List<Acceptor> pureAcceptors,
            List<Provider> bufferProviders, List<Acceptor> bufferAcceptors,
            List<StorageEndpoint> storages) {
        switch (direction) {
            case OUTPUT -> {
                // Port offers out; the network accepts a provider on NORMAL or PULL. PUSH wants to feed
                // the machine but the port only offers: no overlap.
                if (face == FlowState.NORMAL || face == FlowState.PULL) {
                    Provider provider = providerFor(channel, neighbour);
                    if (provider != null) {
                        pureProviders.add(provider);
                    }
                }
            }
            case INPUT -> {
                // Port accepts in; the network offers an acceptor on NORMAL or PUSH. PULL wants to drain
                // the machine but the port only accepts: no overlap.
                if (face == FlowState.NORMAL || face == FlowState.PUSH) {
                    Acceptor acceptor = acceptorFor(channel, neighbour);
                    if (acceptor != null) {
                        pureAcceptors.add(acceptor);
                    }
                }
            }
            case BOTH -> {
                if (face == FlowState.NORMAL) {
                    // Two-way access: a paired StorageEndpoint whose halves also feed the buffer lists.
                    StorageEndpoint storage = storageFor(channel, neighbour);
                    if (storage != null) {
                        storages.add(storage);
                        bufferProviders.add(storage.provider());
                        bufferAcceptors.add(storage.acceptor());
                    }
                } else if (face == FlowState.PUSH) {
                    // One-way IN: buffer acceptor half only, never paired (balancing needs two-way).
                    Acceptor acceptor = acceptorFor(channel, neighbour);
                    if (acceptor != null) {
                        bufferAcceptors.add(acceptor);
                    }
                } else if (face == FlowState.PULL) {
                    // One-way OUT: buffer provider half only, never paired.
                    Provider provider = providerFor(channel, neighbour);
                    if (provider != null) {
                        bufferProviders.add(provider);
                    }
                }
            }
            default -> {
                // CLOSED (or any future direction): contributes nothing.
            }
        }
    }

    /**
     * Whether a facing port of the given {@code direction} OVERLAPS a pipe face's {@code flow} state, i.e.
     * whether {@link #classify} would produce at least one endpoint for the pair. Gate-for-gate with the
     * classify matrix:
     * <ul>
     *   <li>OUTPUT: overlaps NORMAL or PULL.</li>
     *   <li>INPUT: overlaps NORMAL or PUSH.</li>
     *   <li>BOTH: overlaps any non-NONE flow (callers gate NONE before reaching here).</li>
     *   <li>CLOSED: never overlaps.</li>
     * </ul>
     * Single source of truth for "is this machine face a real connection", reused by {@code collect}'s
     * classify gate and by {@link PipeVisualStates#effectiveMask} so the visual twin cannot drift.
     */
    static boolean overlaps(PortDirection direction, FlowState flow) {
        return switch (direction) {
            case OUTPUT -> flow == FlowState.NORMAL || flow == FlowState.PULL;
            case INPUT -> flow == FlowState.NORMAL || flow == FlowState.PUSH;
            case BOTH -> flow != FlowState.NONE;
            default -> false; // CLOSED (or any future direction): never a transport endpoint
        };
    }

    private static StorageEndpoint storageFor(PortChannel channel, MachinePorts endpoint) {
        if (channel == PortChannel.POWER) {
            EnergyHandler energy = endpoint.energy();
            return energy == null ? null : EndpointAdapters.powerStorage(energy);
        }
        if (channel == PortChannel.FLUID || channel == PortChannel.GAS) {
            ResourceBuffer buffer = endpoint.resource(channel);
            return buffer == null ? null : EndpointAdapters.resourceStorage(buffer);
        }
        return null; // ITEM unsupported here
    }

    private static Provider providerFor(PortChannel channel, MachinePorts endpoint) {
        if (channel == PortChannel.POWER) {
            EnergyHandler energy = endpoint.energy();
            return energy == null ? null : EndpointAdapters.powerProvider(energy);
        }
        if (channel == PortChannel.FLUID || channel == PortChannel.GAS) {
            ResourceBuffer buffer = endpoint.resource(channel);
            return buffer == null ? null : EndpointAdapters.resourceProvider(buffer);
        }
        return null; // ITEM unsupported here
    }

    private static Acceptor acceptorFor(PortChannel channel, MachinePorts endpoint) {
        if (channel == PortChannel.POWER) {
            EnergyHandler energy = endpoint.energy();
            return energy == null ? null : EndpointAdapters.powerAcceptor(energy);
        }
        if (channel == PortChannel.FLUID || channel == PortChannel.GAS) {
            ResourceBuffer buffer = endpoint.resource(channel);
            return buffer == null ? null : EndpointAdapters.resourceAcceptor(buffer);
        }
        return null; // ITEM unsupported here
    }
}
