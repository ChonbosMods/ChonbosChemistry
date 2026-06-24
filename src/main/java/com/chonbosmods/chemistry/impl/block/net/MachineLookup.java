package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup;

/**
 * The mockable seam between {@link NetworkEndpoints}' pure endpoint-collection and live Hytale block
 * access: it answers "is there a port-bearing machine/tank at this block position?" without the
 * collection ever touching {@code World}/{@code BlockModule}. Mirrors {@link PipeGridView}.
 *
 * <p>The real implementation (built in the tick task) wraps the world + machine/tank component types;
 * unit tests pass a fake backed by an in-memory map. Both {@code MachineBlockState} and
 * {@code TankBlockState} adapt to {@link MachinePorts} trivially (they already expose
 * {@code ports()}/{@code energy()}/{@code resource(channel)}).
 */
public interface MachineLookup {

    /** The port-bearing endpoint at this block position, or null if none is there. */
    MachinePorts at(int x, int y, int z);

    /**
     * A tiny read view over a machine/tank endpoint: just the three accessors the endpoint-collection
     * needs ({@link PortConfig ports}, optional {@link EnergyHandler energy}, per-channel
     * {@link ResourceBuffer}). Both block-state types already provide these.
     */
    interface MachinePorts {

        /**
         * The port configuration for the SPECIFIC footprint cell that was queried (a multi-cell machine
         * resolves the touched cell to its anchor and returns only that cell's ports, projected to world
         * faces). The transport collector and the effective visual mask query faces here, so a filler cell
         * with no configured ports exposes none. Single-cell machines return their whole config.
         */
        PortConfig ports();

        /**
         * Whether the WHOLE machine advertises {@code channel} as a connectable face tag (anchor-level,
         * NOT per-cell). The engine welds a connected-block arm toward EVERY footprint cell that inherits
         * such a tag, so the PHYSICAL visual mask uses this (not the per-cell {@link #ports()}) to mirror
         * the engine: a filler cell whose own {@link #ports()} is empty still reports {@code true} here, so
         * {@code effective < physical} and the inherited-tag arm gets dropped. The default derives from
         * {@link #ports()} (correct for single-cell machines); multi-cell lookups override it with the
         * anchor's full config.
         */
        default boolean advertisesChannel(PortChannel channel) {
            PortConfig p = ports();
            if (p == null) {
                return false;
            }
            for (com.chonbosmods.chemistry.impl.block.Port port : p.ports()) {
                if (port != null && port.channel() == channel) {
                    return true;
                }
            }
            return false;
        }

        /** The endpoint's energy handler, or null if it carries no power (e.g. a tank). */
        EnergyHandler energy();

        /** The endpoint's resource buffer for {@code channel}, or null if it serves none. */
        ResourceBuffer resource(PortChannel channel);

        /**
         * The ITEM container this endpoint exposes for {@code direction}, as a transport
         * {@link ContainerLookup.ContainerView}: an {@code INPUT} port's view feeds the machine (e.g. a
         * held bench's input/ingredient container), an {@code OUTPUT} port's view drains it (the bench
         * output container). Used by {@code ItemEndpoints}/the item transfer driver to move items to/from a
         * machine ITEM port the same way it does a passive chest. Returns {@code null} when the endpoint
         * has no item container for that direction (e.g. a tank, or a machine with no held bench). The
         * default is {@code null}; live multi-cell machines override it (see {@code WorldMachineLookup}).
         */
        default ContainerLookup.ContainerView itemContainer(PortDirection direction) {
            return null;
        }
    }
}
