package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;

/**
 * The mockable seam between {@link NetworkEndpoints}' pure endpoint-collection and live Hytale block
 * access: it answers "is there a port-bearing machine/tank at this block position?" without the
 * collection ever touching {@code World}/{@code BlockModule}. Mirrors {@link PipeGridView}.
 *
 * <p>The real implementation (built in the tick task) wraps the world + machine/tank component types;
 * unit tests pass a fake backed by an in-memory map. Both {@code MachineBlockState} and
 * {@code TankBlockState} adapt to {@link MachinePorts} trivially (they already expose
 * {@code ports()}/{@code energy()}/{@code resource(channel)} via {@code TransferNode}).
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

        /** The endpoint's port configuration (which faces carry which channel/direction). */
        PortConfig ports();

        /** The endpoint's energy handler, or null if it carries no power (e.g. a tank). */
        EnergyHandler energy();

        /** The endpoint's resource buffer for {@code channel}, or null if it serves none. */
        ResourceBuffer resource(PortChannel channel);
    }
}
