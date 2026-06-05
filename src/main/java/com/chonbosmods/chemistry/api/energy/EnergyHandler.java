package com.chonbosmods.chemistry.api.energy;

/**
 * The published energy-capability contract (the Hytale FE-equivalent). Any block may implement
 * this in a few lines; cables and machines transfer against it. Lossless, push-based (design §7).
 * Amounts are non-negative {@code long}s in the mod's own energy unit; "simulate" performs no
 * mutation. Energy is {@code long} (not {@code int}) so large reactors and battery banks can exceed
 * the ~2.1B {@code int} ceiling without overflow (design §7 / §16.1).
 *
 * <p>External transfers ({@link #receiveEnergy}/{@link #extractEnergy}) honor the per-buffer rate
 * caps and model neighbors pushing/pulling across a face. Internal transfers
 * ({@link #receiveEnergyInternal}/{@link #extractEnergyInternal}) bypass those caps and model a
 * block filling or draining its OWN buffer (e.g. a generator depositing what it produced).
 */
public interface EnergyHandler {

    /** @return amount actually accepted (≤ amount, ≤ free space, ≤ maxReceive). */
    long receiveEnergy(long amount, boolean simulate);

    /** @return amount actually provided (≤ amount, ≤ stored, ≤ maxExtract). */
    long extractEnergy(long amount, boolean simulate);

    /** Adds energy bypassing the external rate cap, for a block filling its OWN buffer. */
    long receiveEnergyInternal(long amount, boolean simulate);

    /** Removes energy bypassing the external rate cap, for a block draining its OWN buffer. */
    long extractEnergyInternal(long amount, boolean simulate);

    /** Max accepted per external receiveEnergy call (0 means no external input). */
    long getMaxReceive();

    /** Max provided per external extractEnergy call (0 means no external output). */
    long getMaxExtract();

    /** stored divided by max, in [0,1]; 0 when capacity is 0. */
    float getFillRatio();

    boolean isFull();

    boolean isEmpty();

    long getStored();

    long getMaxStored();
}
