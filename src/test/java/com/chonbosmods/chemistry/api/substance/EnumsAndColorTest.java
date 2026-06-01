package com.chonbosmods.chemistry.api.substance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.junit.jupiter.api.Test;

class EnumsAndColorTest {

    private static <T> T decode(com.hypixel.hytale.codec.Codec<T> codec, String json) throws Exception {
        return codec.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void phaseDecodesReadableValue() throws Exception {
        assertEquals(Phase.SOLID, decode(Phase.CODEC, "\"solid\""));
    }

    @Test
    void elementCategoryDecodesMultiWordValue() throws Exception {
        assertEquals(ElementCategory.POST_TRANSITION_METAL, decode(ElementCategory.CODEC, "\"post-transition metal\""));
    }

    @Test
    void radiationVectorDecodesHyphenatedValue() throws Exception {
        assertEquals(RadiationVector.CONTACT_ONLY, decode(RadiationVector.CODEC, "\"contact-only\""));
    }

    @Test
    void dominantEmissionDecodesXray() throws Exception {
        assertEquals(DominantEmission.X_RAY, decode(DominantEmission.CODEC, "\"X-ray\""));
    }

    @Test
    void periodicBlockDecodesLowercaseLetter() throws Exception {
        assertEquals(PeriodicBlock.D, decode(PeriodicBlock.CODEC, "\"d\""));
    }

    @Test
    void colorDecodesFromHex() throws Exception {
        Color c = decode(Color.CODEC, "\"#C8C8C8\"");
        assertEquals(200, c.r());
        assertEquals(200, c.g());
        assertEquals(200, c.b());
    }

    @Test
    void colorEncodesBackToHex() {
        assertEquals("#1E6FE0", new Color(0x1E, 0x6F, 0xE0).toHex());
    }
}
