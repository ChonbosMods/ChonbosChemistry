package com.chonbosmods.chemistry.api.shim;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.bson.BsonValue;

/**
 * A {@link Codec} that maps a JSON string to/from a typed value via explicit wire-value functions,
 * delegating the string read/write to {@link Codec#STRING}.
 *
 * <p>Exists because Hytale's {@code EnumCodec} only accepts PascalCase wire values (it rejects
 * {@code "beta-minus"}, {@code "transition metal"}, etc.), whereas the chemistry data uses
 * human-readable values. Each enum carries its own {@code jsonValue}; {@link #ofEnum} builds the
 * lookup. Single-string-adapted value types (e.g. {@code Color}) use the general constructor.
 */
public final class StringMappedCodec<T> implements Codec<T> {

    private final Function<String, T> fromWire;
    private final Function<T, String> toWire;

    public StringMappedCodec(Function<String, T> fromWire, Function<T, String> toWire) {
        this.fromWire = fromWire;
        this.toWire = toWire;
    }

    /** Builds a codec for an enum whose constants each expose a readable wire value. */
    public static <E extends Enum<E>> StringMappedCodec<E> ofEnum(Class<E> type, Function<E, String> wire) {
        Map<String, E> byWire = new HashMap<>();
        for (E constant : type.getEnumConstants()) {
            byWire.put(wire.apply(constant), constant);
        }
        Function<String, E> fromWire = value -> {
            E match = byWire.get(value);
            if (match == null) {
                throw new IllegalArgumentException(
                    "Unknown " + type.getSimpleName() + " value: '" + value + "' (expected one of " + byWire.keySet() + ")");
            }
            return match;
        };
        return new StringMappedCodec<>(fromWire, wire);
    }

    @Override
    public T decode(BsonValue value, ExtraInfo extraInfo) {
        return fromWire.apply(STRING.decode(value, extraInfo));
    }

    @Override
    public BsonValue encode(T value, ExtraInfo extraInfo) {
        return STRING.encode(toWire.apply(value), extraInfo);
    }

    @Override
    public T decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return fromWire.apply(STRING.decodeJson(reader, extraInfo));
    }

    @Override
    public Schema toSchema(SchemaContext context) {
        return STRING.toSchema(context);
    }
}
