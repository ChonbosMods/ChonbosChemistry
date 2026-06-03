package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** A concrete {@link EnergyHandler}: a stored amount bounded by a fixed capacity. */
public final class EnergyBuffer implements EnergyHandler {

    public static final BuilderCodec<EnergyBuffer> CODEC = BuilderCodec.builder(EnergyBuffer.class, EnergyBuffer::new)
        .append(new KeyedCodec<>("Stored", Codec.LONG), (o, v) -> o.stored = v, o -> o.stored).add()
        .append(new KeyedCodec<>("Capacity", Codec.LONG), (o, v) -> o.capacity = v, o -> o.capacity).add()
        .build();

    private long stored;
    private long capacity;

    private EnergyBuffer() {
    }

    public static EnergyBuffer withCapacity(long capacity) {
        EnergyBuffer b = new EnergyBuffer();
        b.capacity = capacity;
        return b;
    }

    @Override
    public long receiveEnergy(long amount, boolean simulate) {
        long accepted = Math.max(0L, Math.min(amount, capacity - stored));
        if (!simulate) {
            stored += accepted;
        }
        return accepted;
    }

    @Override
    public long extractEnergy(long amount, boolean simulate) {
        long provided = Math.max(0L, Math.min(amount, stored));
        if (!simulate) {
            stored -= provided;
        }
        return provided;
    }

    @Override
    public long getStored() {
        return stored;
    }

    @Override
    public long getMaxStored() {
        return capacity;
    }
}
