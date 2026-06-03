package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup.MachinePorts;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects a {@link Network}'s {@link Provider}s and {@link Acceptor}s by walking each member pipe's
 * 6 face-neighbours and adapting any port-bearing machine/tank found there (via {@link MachineLookup})
 * to the network's channel.
 *
 * <p>A neighbour with an OUTPUT port of the network's channel becomes a {@link Provider} (it feeds the
 * network); one with an INPUT port becomes an {@link Acceptor} (it drains the network). POWER endpoints
 * adapt through their {@link EnergyHandler}; FLUID/GAS endpoints through their {@link ResourceBuffer}
 * for that channel. ITEM is not handled here (no buffer adapter for items yet).
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

    /** The providers + acceptors collected around a network, ready for {@link NetworkTransfer#distribute}. */
    public record Endpoints(List<Provider> providers, List<Acceptor> acceptors) {
    }

    /**
     * Walks every member pipe's face-neighbours and collects endpoints matching {@code net}'s channel.
     *
     * @param net    the network whose channel/members drive the search.
     * @param lookup live (or fake) block access for resolving a neighbour's ports.
     * @return the collected providers (OUTPUT ports) and acceptors (INPUT ports). Never null; either
     *     list may be empty.
     */
    public static Endpoints collect(Network net, MachineLookup lookup) {
        PortChannel channel = net.channel();
        List<Provider> providers = new ArrayList<>();
        List<Acceptor> acceptors = new ArrayList<>();

        for (long memberKey : net.memberKeys()) {
            int px = NetworkManager.unpackX(memberKey);
            int py = NetworkManager.unpackY(memberKey);
            int pz = NetworkManager.unpackZ(memberKey);
            for (int[] off : OFFSETS) {
                MachinePorts neighbour = lookup.at(px + off[0], py + off[1], pz + off[2]);
                if (neighbour == null) {
                    continue;
                }
                PortConfig ports = neighbour.ports();
                if (ports == null) {
                    continue;
                }
                // Face-precise matching deferred: any matching-channel port on the neighbour qualifies.
                if (!ports.portsFor(channel, PortDirection.OUTPUT).isEmpty()) {
                    Provider provider = providerFor(channel, neighbour);
                    if (provider != null) {
                        providers.add(provider);
                    }
                }
                if (!ports.portsFor(channel, PortDirection.INPUT).isEmpty()) {
                    Acceptor acceptor = acceptorFor(channel, neighbour);
                    if (acceptor != null) {
                        acceptors.add(acceptor);
                    }
                }
            }
        }
        return new Endpoints(providers, acceptors);
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
