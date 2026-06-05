package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Unified break/place contents carry through the engine's NATIVE {@code "BlockHolder"} metadata path
 * (2026-06-05 design; supersedes the {@code CC_StoredEnergy} single-field carry).
 *
 * <h2>Mechanism (verified against the decompiled 0.5.3 jar)</h2>
 * On BREAK, the block entity's full {@code Holder<ChunkStore>} (captured via
 * {@code WorldChunk.getBlockComponentHolder} and encoded with
 * {@code ChunkStore.REGISTRY.getEntityCodec()}) is stamped onto the dropped item under {@value #KEY}
 * (the engine's own {@code ItemStack.Metadata.BLOCK_HOLDER} key). On PLACE we do <b>nothing</b>: the
 * engine's {@code BlockPlaceUtils.onPlaceBlockSuccess} natively decodes that document and restores
 * EVERY component of the block entity via {@code WorldChunk.setState}: energy, resource buffers, work
 * progress, type-locks, all of it, with zero mod-side place code.
 *
 * <p>This file is the pure, headless-testable layer (BSON stamping seam + the should-this-block-carry
 * predicates); the engine-coupled capture/encode lives in {@code CarryBreakEventSystem}, verified
 * in-game. Defensive: nulls reduce to no-ops so the glue can call blindly.
 */
public final class BlockHolderCarry {

    /** The engine's native block-entity carry key ({@code ItemStack.Metadata.BLOCK_HOLDER}). */
    public static final String KEY = "BlockHolder";

    private BlockHolderCarry() {
    }

    // --- pure BSON layer (unit-tested) ---

    /**
     * Return a metadata document carrying {@code holderDoc} under {@link #KEY}. The source doc is
     * cloned (or created), never mutated. A null {@code holderDoc} returns the source unchanged.
     */
    @Nullable
    public static BsonDocument stamp(@Nullable BsonDocument source, @Nullable BsonDocument holderDoc) {
        if (holderDoc == null) {
            return source;
        }
        BsonDocument doc = source == null ? new BsonDocument() : source.clone();
        doc.put(KEY, holderDoc);
        return doc;
    }

    /** The carried block-entity document under {@link #KEY}, or null when absent/not a document. */
    @Nullable
    public static BsonDocument read(@Nullable BsonDocument metadata) {
        if (metadata == null) {
            return null;
        }
        BsonValue value = metadata.get(KEY);
        return value == null || !value.isDocument() ? null : value.asDocument();
    }

    // --- carry predicates (unit-tested) ---

    /**
     * A machine carries when it has anything worth preserving: positive stored energy or any
     * non-empty resource buffer. (Work progress rides along for free in the holder but does not by
     * itself warrant replacing the vanilla drop.)
     */
    public static boolean shouldCarry(@Nullable MachineBlockState machine) {
        if (machine == null) {
            return false;
        }
        EnergyHandler energy = machine.energy();
        if (energy != null && energy.getStored() > 0L) {
            return true;
        }
        for (PortChannel channel : PortChannel.values()) {
            ResourceBuffer buffer = machine.resource(channel);
            if (buffer != null && buffer.amount() > 0) {
                return true;
            }
        }
        return false;
    }

    /** A tank carries when its buffer holds anything ("tanks relocate with contents preserved", §5.2). */
    public static boolean shouldCarry(@Nullable TankBlockState tank) {
        if (tank == null) {
            return false;
        }
        ResourceBuffer buffer = tank.resource(tank.channel());
        return buffer != null && buffer.amount() > 0;
    }

    // --- ItemStack glue (thin, verified in-game) ---

    /**
     * Stamp the encoded block-entity document onto a copy of {@code stack} under {@link #KEY}.
     * Null stack or null doc returns the stack unchanged (vanilla drop).
     */
    @Nullable
    public static ItemStack stampStack(@Nullable ItemStack stack, @Nullable BsonDocument holderDoc) {
        if (stack == null || holderDoc == null) {
            return stack;
        }
        return stack.withMetadata(KEY, holderDoc);
    }
}
