package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;

/**
 * A block participating in transport: its ports, its energy handler, its per-channel resource
 * buffers, and per-channel throughput.
 *
 * <p>Lives in {@code impl.block} (not {@code api.io}) so the api stays contracts-only. Promotion to
 * api is deferred until third-party blocks need to participate in transport.
 */
public interface TransferNode {
    PortConfig ports();

    /** @return the energy handler, or null if this node carries no power. */
    EnergyHandler energy();

    /** @return the resource buffer for a fluid/gas/item channel, or null if this node has none for that channel. */
    ResourceBuffer resource(PortChannel channel);

    /**
     * @return max units movable out of a single OUTPUT port per push for this channel: this is a
     *     per-output-port cap, not a per-channel-per-tick total, so a node with N OUTPUT ports of
     *     this channel can emit up to N times this value in one push.
     */
    int throughput(PortChannel channel);
}
