package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.RawJsonCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonValue;

/**
 * A non-primitive {@code Codec<Long>} delegating to {@link Codec#LONG}. {@code Codec.LONG} is a
 * {@code PrimitiveCodec}, which makes {@code BuilderField} reject a null (absent optional) value
 * before it reaches the setter. This identity wrapper is NOT a {@code PrimitiveCodec}, so an
 * absent/explicit-null optional key decodes to null and the field setter can apply a default.
 *
 * <p>It additionally implements {@link RawJsonCodec} and forwards {@link #decodeJson} to
 * {@code Codec.LONG} (which is a {@link com.hypixel.hytale.codec.codecs.simple.LongCodec}, itself a
 * {@code RawJsonCodec<Long>}). Without this, the JSON asset-load path falls back to the default
 * {@code Codec.decodeJson}, which prints a {@code SEVERE [SERR] decodeJson: class ...} line on every
 * load before routing through the BSON path. (The value still decodes via that fallback, but the log
 * spam is avoided and the read goes straight through {@code RawJsonReader.readLongValue()}.)
 *
 * <p>Shared between {@link MachineBlockState} and {@link EnergyBuffer}, both of which need an optional
 * {@code Long} key whose absent/null value must reach the setter rather than tripping the primitive
 * non-null check.
 */
final class OptionalLongCodec implements Codec<Long>, RawJsonCodec<Long> {

    static final OptionalLongCodec INSTANCE = new OptionalLongCodec();

    private OptionalLongCodec() {
    }

    @Override
    public Long decode(BsonValue bsonValue, ExtraInfo extraInfo) {
        return Codec.LONG.decode(bsonValue, extraInfo);
    }

    @Override
    public BsonValue encode(Long value, ExtraInfo extraInfo) {
        return Codec.LONG.encode(value, extraInfo);
    }

    @Nullable
    @Override
    public Long decodeJson(@Nonnull RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        // Codec.LONG is a LongCodec, which implements RawJsonCodec<Long> and reads a long directly.
        return Codec.LONG.decodeJson(reader, extraInfo);
    }

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        return Codec.LONG.toSchema(context);
    }
}
