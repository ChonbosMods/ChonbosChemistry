package com.chonbosmods.chemistry.api.shim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.junit.jupiter.api.Test;

class StringMappedCodecTest {

    enum Sample {
        ALPHA("alpha"),
        BETA_MINUS("beta-minus");

        final String wire;

        Sample(String wire) {
            this.wire = wire;
        }
    }

    private final StringMappedCodec<Sample> codec = StringMappedCodec.ofEnum(Sample.class, s -> s.wire);

    @Test
    void decodesReadableHyphenatedValue() throws Exception {
        Sample decoded = codec.decodeJson(RawJsonReader.fromJsonString("\"beta-minus\""), EmptyExtraInfo.EMPTY);
        assertEquals(Sample.BETA_MINUS, decoded);
    }

    @Test
    void encodesBackToWireValue() {
        assertEquals("beta-minus", codec.encode(Sample.BETA_MINUS, EmptyExtraInfo.EMPTY).asString().getValue());
    }

    @Test
    void rejectsUnknownValueWithClearError() {
        assertThrows(IllegalArgumentException.class, () ->
            codec.decodeJson(RawJsonReader.fromJsonString("\"gamma\""), EmptyExtraInfo.EMPTY));
    }
}
