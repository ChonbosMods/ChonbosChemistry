package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;

/**
 * The minimal contract every powered machine exposes to the shared machine-tick infrastructure: an energy
 * buffer, a per-face port configuration, and an on/off control line. Implemented by the processing machines'
 * {@code MachineBlockState} and (via {@code AutoCraftNode}) the crafting machines' state. Lets shared helpers
 * (drive-context resolution, energy gating, visual state) operate on any machine uniformly.
 */
public interface PoweredMachineNode {
    /** @return the energy buffer, or null if this machine carries no power. */
    EnergyHandler energy();

    /** @return the per-face port configuration. */
    PortConfig ports();

    /** @return whether the machine is ON (enabled). */
    boolean isEnabled();
}
