package com.chonbosmods.chemistry.api.energy;

/**
 * The published energy-capability contract (the Hytale FE-equivalent). Any block may implement
 * this in a few lines; cables and machines transfer against it. Lossless, push-based (design §7).
 * Amounts are non-negative integers in the mod's own energy unit; "simulate" performs no mutation.
 */
public interface EnergyHandler {

    /** @return amount actually accepted (≤ amount, ≤ free space). */
    int receiveEnergy(int amount, boolean simulate);

    /** @return amount actually provided (≤ amount, ≤ stored). */
    int extractEnergy(int amount, boolean simulate);

    int getStored();

    int getMaxStored();
}
