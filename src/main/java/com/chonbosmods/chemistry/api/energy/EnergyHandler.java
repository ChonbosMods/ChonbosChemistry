package com.chonbosmods.chemistry.api.energy;

/**
 * The published energy-capability contract (the Hytale FE-equivalent). Any block may implement
 * this in a few lines; cables and machines transfer against it. Lossless, push-based (design §7).
 * Amounts are non-negative {@code long}s in the mod's own energy unit; "simulate" performs no
 * mutation. Energy is {@code long} (not {@code int}) so large reactors and battery banks can exceed
 * the ~2.1B {@code int} ceiling without overflow (design §7 / §16.1).
 */
public interface EnergyHandler {

    /** @return amount actually accepted (≤ amount, ≤ free space). */
    long receiveEnergy(long amount, boolean simulate);

    /** @return amount actually provided (≤ amount, ≤ stored). */
    long extractEnergy(long amount, boolean simulate);

    long getStored();

    long getMaxStored();
}
