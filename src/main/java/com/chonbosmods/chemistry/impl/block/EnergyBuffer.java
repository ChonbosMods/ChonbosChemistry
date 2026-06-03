package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;

/** A concrete {@link EnergyHandler}: a stored amount bounded by a fixed capacity, with per-buffer
 * external transfer rate caps. */
public final class EnergyBuffer implements EnergyHandler {

    public static final BuilderCodec<EnergyBuffer> CODEC = BuilderCodec.builder(EnergyBuffer.class, EnergyBuffer::new)
        .append(new KeyedCodec<>("Stored", Codec.LONG), (o, v) -> o.stored = v, o -> o.stored).add()
        .append(new KeyedCodec<>("Capacity", Codec.LONG), (o, v) -> o.capacity = v, o -> o.capacity).add()
        .append(new KeyedCodec<>("MaxReceive", Codec.LONG), (o, v) -> o.maxReceive = v, o -> o.maxReceive)
            .addValidator(Validators.<Long>greaterThanOrEqual(0L))
            .documentation("Max energy accepted per external receiveEnergy call.")
            .add()
        .append(new KeyedCodec<>("MaxExtract", Codec.LONG), (o, v) -> o.maxExtract = v, o -> o.maxExtract)
            .addValidator(Validators.<Long>greaterThanOrEqual(0L))
            .documentation("Max energy provided per external extractEnergy call.")
            .add()
        .build();

    private long stored;
    private long capacity;
    private long maxReceive;
    private long maxExtract;

    private EnergyBuffer() {
    }

    public static EnergyBuffer withCapacity(long capacity) {
        return withCapacityAndRates(capacity, Long.MAX_VALUE, Long.MAX_VALUE);
    }

    public static EnergyBuffer withCapacityAndRates(long capacity, long maxReceive, long maxExtract) {
        EnergyBuffer b = new EnergyBuffer();
        b.capacity = capacity;
        b.maxReceive = maxReceive;
        b.maxExtract = maxExtract;
        return b;
    }

    @Override
    public long receiveEnergy(long amount, boolean simulate) {
        return receiveEnergyInternal(Math.min(amount, maxReceive), simulate);
    }

    @Override
    public long receiveEnergyInternal(long amount, boolean simulate) {
        long accepted = Math.max(0L, Math.min(amount, capacity - stored));
        if (!simulate) {
            stored += accepted;
        }
        return accepted;
    }

    @Override
    public long extractEnergy(long amount, boolean simulate) {
        return extractEnergyInternal(Math.min(amount, maxExtract), simulate);
    }

    @Override
    public long extractEnergyInternal(long amount, boolean simulate) {
        long provided = Math.max(0L, Math.min(amount, stored));
        if (!simulate) {
            stored -= provided;
        }
        return provided;
    }

    @Override
    public long getMaxReceive() {
        return maxReceive;
    }

    @Override
    public long getMaxExtract() {
        return maxExtract;
    }

    @Override
    public float getFillRatio() {
        return capacity <= 0 ? 0f : (float) stored / (float) capacity;
    }

    @Override
    public boolean isFull() {
        return stored >= capacity;
    }

    @Override
    public boolean isEmpty() {
        return stored <= 0;
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
