package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
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
 * <p>Endpoints are classified by their port set (H6 FIX 2):
 * <ul>
 *   <li>OUTPUT-only neighbour → a PURE {@link Provider} (a source: it feeds the network);
 *   <li>INPUT-only neighbour → a PURE {@link Acceptor} (a sink: it drains the network);
 *   <li>neighbour with BOTH an OUTPUT and an INPUT port (e.g. a storage battery) → a BUFFER endpoint:
 *       it contributes ONE entry to the buffer-provider list AND one to the buffer-acceptor list.
 * </ul>
 * This split lets {@link NetworkTransfer} prioritise real sources/sinks over storage and so avoids the
 * self-churn where a storage block re-supplies its own energy each tick and starves the actual source.
 * POWER endpoints adapt through their {@link EnergyHandler}; FLUID/GAS endpoints through their
 * {@link ResourceBuffer} for that channel. ITEM is not handled here (no buffer adapter for items yet).
 *
 * <p>Face-precise geometry is deferred: like the existing transport code, ANY matching-channel
 * OUTPUT/INPUT port qualifies a neighbour, regardless of which face it sits on. Requiring the port to
 * face back at the pipe is a later refinement (consistent with {@code MachineTickSystem}'s OFFSETS
 * caveat).
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
            List<Acceptor> bufferAcceptors) {
    }

    /**
     * Walks every member pipe's face-neighbours and collects endpoints matching {@code net}'s channel,
     * classifying each by its port set (see {@link Endpoints}).
     *
     * @param net    the network whose channel/members drive the search.
     * @param lookup live (or fake) block access for resolving a neighbour's ports.
     * @return the classified endpoints. Never null; any list may be empty.
     */
    public static Endpoints collect(Network net, MachineLookup lookup) {
        PortChannel channel = net.channel();
        List<Provider> pureProviders = new ArrayList<>();
        List<Acceptor> pureAcceptors = new ArrayList<>();
        List<Provider> bufferProviders = new ArrayList<>();
        List<Acceptor> bufferAcceptors = new ArrayList<>();
        // A neighbour block bordering two member pipes of THIS network would otherwise be wrapped twice
        // (a double share in the fair-split). Dedup by neighbour block position: once a position has
        // contributed, skip it on subsequent member-pipe hits.
        Set<Long> visitedNeighbours = new HashSet<>();

        for (long memberKey : net.memberKeys()) {
            int px = NetworkManager.unpackX(memberKey);
            int py = NetworkManager.unpackY(memberKey);
            int pz = NetworkManager.unpackZ(memberKey);
            for (int[] off : OFFSETS) {
                int nx = px + off[0], ny = py + off[1], nz = pz + off[2];
                if (!visitedNeighbours.add(NetworkManager.packKey(nx, ny, nz))) {
                    continue; // this neighbour position already contributed for this network
                }
                MachinePorts neighbour = lookup.at(nx, ny, nz);
                if (neighbour == null) {
                    continue;
                }
                PortConfig ports = neighbour.ports();
                if (ports == null) {
                    continue;
                }
                // Face-precise matching deferred: any matching-channel port on the neighbour qualifies.
                boolean hasOutput = !ports.portsFor(channel, PortDirection.OUTPUT).isEmpty();
                boolean hasInput = !ports.portsFor(channel, PortDirection.INPUT).isEmpty();
                if (hasOutput && hasInput) {
                    // Storage endpoint: contributes to BOTH buffer lists (when its adapters resolve).
                    Provider provider = providerFor(channel, neighbour);
                    if (provider != null) {
                        bufferProviders.add(provider);
                    }
                    Acceptor acceptor = acceptorFor(channel, neighbour);
                    if (acceptor != null) {
                        bufferAcceptors.add(acceptor);
                    }
                } else if (hasOutput) {
                    Provider provider = providerFor(channel, neighbour);
                    if (provider != null) {
                        pureProviders.add(provider);
                    }
                } else if (hasInput) {
                    Acceptor acceptor = acceptorFor(channel, neighbour);
                    if (acceptor != null) {
                        pureAcceptors.add(acceptor);
                    }
                }
            }
        }
        return new Endpoints(pureProviders, pureAcceptors, bufferProviders, bufferAcceptors);
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
