package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.validation.Validators;

/** A concrete {@link EnergyHandler}: a stored amount bounded by a fixed capacity, with per-buffer
 * external transfer rate caps. */
public final class EnergyBuffer implements EnergyHandler {

    /**
     * Shared non-primitive {@code Codec<Long>} (also a {@code RawJsonCodec<Long>}) for optional Long
     * keys whose absent/null value must reach the setter (here coalescing to the uncapped default).
     * See {@link OptionalLongCodec} for the full rationale (PrimitiveCodec-null-bypass invariant +
     * raw-JSON path).
     */
    private static final Codec<Long> OPTIONAL_LONG = OptionalLongCodec.INSTANCE;

    public static final BuilderCodec<EnergyBuffer> CODEC = BuilderCodec.builder(EnergyBuffer.class, EnergyBuffer::new)
        .append(new KeyedCodec<>("Stored", Codec.LONG), (o, v) -> o.stored = v, o -> o.stored).add()
        .append(new KeyedCodec<>("Capacity", Codec.LONG), (o, v) -> o.capacity = v, o -> o.capacity).add()
        // Rate caps are OPTIONAL: a document lacking them (legacy devserver-persisted blocks, and the
        // rig block JSON that specifies only Stored/Capacity) must decode to UNCAPPED rates, matching
        // withCapacity(long). An ABSENT key is handled by the Long.MAX_VALUE field defaults below
        // (BuilderCodec.decode only invokes setters for keys present in the document). A key present
        // but explicitly null is handled here: the keys are marked not-required (3-arg KeyedCodec) and
        // wrapped in a non-primitive Long codec so null reaches the setter (instead of tripping the
        // primitive non-null check), and the setter coalesces null -> Long.MAX_VALUE. A present numeric
        // key decodes normally. maxReceive/maxExtract = 0 still means "no external input/output".
        .append(new KeyedCodec<>("MaxReceive", OPTIONAL_LONG, false),
                (o, v) -> o.maxReceive = v == null ? Long.MAX_VALUE : v, o -> o.maxReceive)
            .addValidator(Validators.<Long>greaterThanOrEqual(0L))
            .documentation("Max energy accepted per external receiveEnergy call. Omit for uncapped.")
            .add()
        .append(new KeyedCodec<>("MaxExtract", OPTIONAL_LONG, false),
                (o, v) -> o.maxExtract = v == null ? Long.MAX_VALUE : v, o -> o.maxExtract)
            .addValidator(Validators.<Long>greaterThanOrEqual(0L))
            .documentation("Max energy provided per external extractEnergy call. Omit for uncapped.")
            .add()
        .build();

    private long stored;
    private long capacity;
    // Default to uncapped. BuilderCodec.decode only invokes a field's setter for keys PRESENT in the
    // document, so an absent (legacy) MaxReceive/MaxExtract leaves these at their constructor value.
    // Uncapped is the right default: maxReceive/maxExtract = 0 would mean "no external input/output",
    // making a legacy-decoded buffer silently energy-inert.
    private long maxReceive = Long.MAX_VALUE;
    private long maxExtract = Long.MAX_VALUE;

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
