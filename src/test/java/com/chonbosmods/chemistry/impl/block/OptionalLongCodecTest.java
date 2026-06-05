package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.bson.BsonInt64;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

/**
 * Covers the raw-JSON decode path of {@link OptionalLongCodec}: the bug was that the anonymous
 * {@code OPTIONAL_LONG} wrapper did NOT implement {@code RawJsonCodec}, so JSON asset loads hit the
 * {@code Codec.decodeJson} default that prints a SEVERE line. The shared wrapper now forwards to
 * {@code Codec.LONG}'s direct long reader.
 */
class OptionalLongCodecTest {

    @Test
    void decodeJsonReadsLongDirectly() throws Exception {
        // A bare numeric JSON value, exactly what "EnergyDrainPerTick": 2 yields for this codec.
        RawJsonReader reader = RawJsonReader.fromJsonString("2");
        Long value = OptionalLongCodec.INSTANCE.decodeJson(reader, EmptyExtraInfo.EMPTY);
        assertEquals(2L, value);
    }

    @Test
    void bsonRoundTripUnchanged() {
        BsonValue encoded = OptionalLongCodec.INSTANCE.encode(7L, EmptyExtraInfo.EMPTY);
        assertEquals(new BsonInt64(7L), encoded);
        assertEquals(7L, OptionalLongCodec.INSTANCE.decode(encoded, EmptyExtraInfo.EMPTY));
    }
}
