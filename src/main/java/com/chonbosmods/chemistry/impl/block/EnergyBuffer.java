package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** A concrete {@link EnergyHandler}: a stored amount bounded by a fixed capacity. */
public final class EnergyBuffer implements EnergyHandler {

    public static final BuilderCodec<EnergyBuffer> CODEC = BuilderCodec.builder(EnergyBuffer.class, EnergyBuffer::new)
        .append(new KeyedCodec<>("Stored", Codec.INTEGER), (o, v) -> o.stored = v, o -> o.stored).add()
        .append(new KeyedCodec<>("Capacity", Codec.INTEGER), (o, v) -> o.capacity = v, o -> o.capacity).add()
        .build();

    private int stored;
    private int capacity;

    private EnergyBuffer() {
    }

    public static EnergyBuffer withCapacity(int capacity) {
        EnergyBuffer b = new EnergyBuffer();
        b.capacity = capacity;
        return b;
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        int accepted = Math.max(0, Math.min(amount, capacity - stored));
        if (!simulate) {
            stored += accepted;
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        int provided = Math.max(0, Math.min(amount, stored));
        if (!simulate) {
            stored -= provided;
        }
        return provided;
    }

    @Override
    public int getStored() {
        return stored;
    }

    @Override
    public int getMaxStored() {
        return capacity;
    }
}
