package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Pure, headless-testable bridge that carries a machine block's stored energy across a break/place
 * cycle through an {@link ItemStack}'s BSON metadata. This is the small "capture/restore" codec helper
 * the event glue ({@code MachineBreakEventSystem} / {@code MachinePlaceEventSystem}) leans on; all the
 * arithmetic and codec round-tripping lives here so it can be unit-tested without a running server.
 *
 * <h2>Mechanism (mirrors the HyProTech reference)</h2>
 * On BREAK, the stored amount is stamped onto the dropped item under the metadata key {@value #KEY}.
 * On PLACE, it is pulled back out and pushed into the freshly created block entity's
 * {@link EnergyBuffer} via {@link EnergyHandler#receiveEnergyInternal} (clamped to capacity, bypassing
 * the external per-call rate cap because this is an internal restore, not a transfer).
 *
 * <h2>Why a BSON seam</h2>
 * The {@link ItemStack} constructor eagerly resolves its {@code Item} asset, which is impossible
 * headlessly (no loaded {@code AssetStore} in a unit test). So the engine-coupled work is split into a
 * pure {@link BsonDocument} layer ({@link #writeStoredEnergy(BsonDocument, long)},
 * {@link #readStoredEnergy(BsonDocument)}) that is fully unit-tested, plus thin {@link ItemStack}
 * wrappers that only forward metadata to/from that layer. The wrappers reproduce the engine's own
 * {@code ItemStack.withMetadata(String, Codec, T)} / {@code getFromMetadataOrNull(String, Codec)}
 * encode/decode semantics exactly (verified against the decompiled 0.5.3 jar).
 *
 * <p>Defensive throughout: a null doc/stack/buffer, absent/zero/negative stored value, or zero-capacity
 * buffer all reduce to a no-op so the handlers can call these blindly and never throw.
 */
public final class MachineEnergyMetadata {

    /** Metadata key under which the stored-energy {@code long} rides on a broken machine's drop. */
    public static final String KEY = "CC_StoredEnergy";

    /** Non-primitive {@code Codec<Long>}: matches the codec the engine would use to (de)serialize. */
    private static final Codec<Long> ENERGY_CODEC = Codec.LONG;

    private MachineEnergyMetadata() {
    }

    // --- pure BSON layer (unit-tested) ---

    /**
     * Return a metadata document carrying {@code storedEnergy} under {@link #KEY}, mirroring
     * {@code ItemStack.withMetadata(key, codec, data)}: the source doc is cloned (or created), the
     * value is encoded and put under the key, and an emptied result collapses to {@code null}.
     *
     * <p>A non-positive amount (an empty buffer) is not worth persisting and instead REMOVES the key,
     * so re-stamping an already-charged doc with 0 clears it.
     *
     * @param source the existing metadata (may be null); never mutated.
     * @return the new metadata doc, or {@code null} if the result is empty.
     */
    @Nullable
    public static BsonDocument writeStoredEnergy(@Nullable BsonDocument source, long storedEnergy) {
        BsonDocument doc = source == null ? new BsonDocument() : source.clone();
        if (storedEnergy <= 0L) {
            doc.remove(KEY);
        } else {
            doc.put(KEY, ENERGY_CODEC.encode(storedEnergy));
        }
        return doc.isEmpty() ? null : doc;
    }

    /**
     * Read the persisted stored-energy amount from a metadata document, or {@code null} when the doc is
     * null or carries no {@link #KEY}. Mirrors {@code ItemStack.getFromMetadataOrNull(key, codec)}.
     */
    @Nullable
    public static Long readStoredEnergy(@Nullable BsonDocument metadata) {
        if (metadata == null) {
            return null;
        }
        BsonValue value = metadata.get(KEY);
        return value == null ? null : ENERGY_CODEC.decode(value);
    }

    // --- ItemStack wrappers (thin glue, verified in-game) ---

    /**
     * Stamp {@code storedEnergy} onto a copy of {@code stack} under {@link #KEY}, returning the new
     * stack. A non-positive amount returns the original stack unchanged so an empty machine drops as a
     * plain item.
     */
    @Nullable
    public static ItemStack writeStoredEnergy(@Nullable ItemStack stack, long storedEnergy) {
        if (stack == null || storedEnergy <= 0L) {
            return stack;
        }
        return stack.withMetadata(KEY, ENERGY_CODEC, storedEnergy);
    }

    /**
     * Read the persisted stored-energy amount off {@code stack}, or {@code null} when the stack is null
     * or carries no {@link #KEY} metadata (the normal case for a never-charged block).
     */
    @Nullable
    public static Long readStoredEnergy(@Nullable ItemStack stack) {
        if (stack == null) {
            return null;
        }
        return readStoredEnergy(stack.getMetadata());
    }

    /** @return true when {@code metadata} carries a positive {@link #KEY} stored-energy value. */
    public static boolean hasStoredEnergy(@Nullable BsonDocument metadata) {
        Long stored = readStoredEnergy(metadata);
        return stored != null && stored > 0L;
    }

    /** @return true when {@code stack} carries a positive {@link #KEY} stored-energy value. */
    public static boolean hasStoredEnergy(@Nullable ItemStack stack) {
        return stack != null && hasStoredEnergy(stack.getMetadata());
    }

    // --- buffer application (unit-tested) ---

    /**
     * Push {@code storedEnergy} into {@code buffer}, clamped to its remaining capacity. Uses the
     * internal (rate-cap-bypassing) receive path because restoring a block's own saved charge is not a
     * network transfer. A null buffer or non-positive amount is a no-op.
     *
     * @return the amount actually accepted (0 when nothing was applied).
     */
    public static long applyStoredEnergy(@Nullable EnergyHandler buffer, long storedEnergy) {
        if (buffer == null || storedEnergy <= 0L) {
            return 0L;
        }
        return buffer.receiveEnergyInternal(storedEnergy, false);
    }

    /**
     * Read the persisted amount off {@code metadata} and apply it to {@code state}'s energy buffer.
     * No-op (returns 0) when the metadata carries no stored energy or the state has no energy buffer.
     * This is exactly what the place handler invokes once the block entity exists.
     *
     * @return the amount of energy restored into the block entity (0 when nothing applied).
     */
    public static long restoreInto(@Nullable BsonDocument metadata, @Nullable MachineBlockState state) {
        if (state == null) {
            return 0L;
        }
        Long stored = readStoredEnergy(metadata);
        if (stored == null) {
            return 0L;
        }
        return applyStoredEnergy(state.energy(), stored);
    }

    /** {@link ItemStack} overload of {@link #restoreInto(BsonDocument, MachineBlockState)}. */
    public static long restoreInto(@Nullable ItemStack stack, @Nullable MachineBlockState state) {
        return restoreInto(stack == null ? null : stack.getMetadata(), state);
    }

    /**
     * Break-handler convenience: if {@code state} carries positive stored energy, stamp that amount
     * onto {@code drop} and return the new stack; otherwise return {@code drop} unchanged.
     */
    @Nullable
    public static ItemStack captureFrom(@Nullable ItemStack drop, @Nonnull MachineBlockState state) {
        EnergyHandler energy = state.energy();
        if (energy == null) {
            return drop;
        }
        return writeStoredEnergy(drop, energy.getStored());
    }
}
