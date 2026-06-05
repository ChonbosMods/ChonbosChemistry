package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;

/**
 * Adapts a machine/tank's live {@link EnergyHandler} or {@link ResourceBuffer} to the network's
 * {@link Provider}/{@link Acceptor} test-seams for a given channel, so {@link NetworkTransfer} can
 * drive any endpoint uniformly without importing block-state types.
 *
 * <h2>long vs int</h2>
 * Energy is {@code long} (the network buffer is {@code long}); resource buffers are {@code int}. The
 * resource adapters therefore clamp the incoming {@code long} budget to {@code int} range at the
 * boundary ({@code min(max, Integer.MAX_VALUE)}) before delegating, and widen the {@code int} result
 * back to {@code long}. Energy adapters delegate directly (no clamping needed).
 *
 * <p>Pure-ish: JDK + the two block beans only; no live world / ECS access. Unit-tested directly
 * against real {@code EnergyBuffer}/{@code ResourceBuffer} instances.
 */
public final class EndpointAdapters {

    private EndpointAdapters() {
    }

    // --- POWER ---

    /** A POWER {@link Provider} over an {@link EnergyHandler}: {@code resourceId()} is null. */
    public static Provider powerProvider(EnergyHandler energy) {
        return new Provider() {
            @Override
            public String resourceId() {
                return null;
            }

            @Override
            public long extract(long max, boolean simulate) {
                return energy.extractEnergy(max, simulate);
            }
        };
    }

    /** A POWER {@link Acceptor} over an {@link EnergyHandler}: capacity is its free space (clamped ≥0). */
    public static Acceptor powerAcceptor(EnergyHandler energy) {
        return new Acceptor() {
            @Override
            public long capacityFor(String resourceId) {
                return Math.max(0L, energy.getMaxStored() - energy.getStored());
            }

            @Override
            public long insert(String resourceId, long amount, boolean simulate) {
                return energy.receiveEnergy(amount, simulate);
            }
        };
    }

    // --- FLUID / GAS (resource buffers) ---

    /**
     * A FLUID/GAS {@link Provider} over a {@link ResourceBuffer}: {@code resourceId()} is the buffer's
     * currently-held resource (null when empty/unlocked).
     */
    public static Provider resourceProvider(ResourceBuffer buffer) {
        return new Provider() {
            @Override
            public String resourceId() {
                return buffer.resourceId();
            }

            @Override
            public long extract(long max, boolean simulate) {
                return buffer.extract(clampToInt(max), simulate);
            }
        };
    }

    /**
     * A FLUID/GAS {@link Acceptor} over a {@link ResourceBuffer}: capacity is the buffer's free space
     * when it can accept {@code resourceId} (empty/unlocked, or locked to the same resource), else 0.
     */
    public static Acceptor resourceAcceptor(ResourceBuffer buffer) {
        return new Acceptor() {
            @Override
            public long capacityFor(String resourceId) {
                String held = buffer.resourceId();
                if (held != null && !held.equals(resourceId)) {
                    return 0L; // locked to a different resource
                }
                return Math.max(0, buffer.capacity() - buffer.amount());
            }

            @Override
            public long insert(String resourceId, long amount, boolean simulate) {
                return buffer.insert(resourceId, clampToInt(amount), simulate);
            }
        };
    }

    /** Clamp a {@code long} budget to non-negative {@code int} range for an int-based buffer. */
    private static int clampToInt(long value) {
        if (value <= 0L) {
            return 0;
        }
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
