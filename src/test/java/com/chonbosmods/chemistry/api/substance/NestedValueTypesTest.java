package com.chonbosmods.chemistry.api.substance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class NestedValueTypesTest {

    private static <T> T decode(Codec<T> codec, String json) throws Exception {
        return codec.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void decayModeDecodes() throws Exception {
        DecayMode m = decode(DecayMode.CODEC,
            "{\"Mode\":\"alpha\",\"Branching\":100.0,\"Daughter\":\"Th-234\",\"DecayEnergy\":4.27}");
        assertEquals("alpha", m.mode());
        assertEquals(100.0, m.branching());
        assertEquals("Th-234", m.daughter());
        assertEquals(4.27, m.decayEnergy());
    }

    @Test
    void nuclearFlagsDecodes() throws Exception {
        NuclearFlags f = decode(NuclearFlags.CODEC, "{\"Fissile\":true,\"Fertile\":false,\"Fusionable\":false}");
        assertTrue(f.fissile());
        assertFalse(f.fertile());
        assertFalse(f.fusionable());
    }

    @Test
    void propertyFlagsDecodes() throws Exception {
        PropertyFlags f = decode(PropertyFlags.CODEC,
            "{\"Corrosive\":true,\"Flammable\":false,\"Oxidizer\":true,\"Volatile\":false,\"Explosive\":false,\"WaterSoluble\":true}");
        assertTrue(f.corrosive());
        assertTrue(f.oxidizer());
        assertTrue(f.waterSoluble());
        assertFalse(f.flammable());
    }

    @Test
    void toxicityDecodesRouteListAndOnsetEnum() throws Exception {
        Toxicity t = decode(Toxicity.CODEC,
            "{\"Route\":[\"contact\",\"inhale\"],\"PotencyNote\":\"severe\",\"Effect\":\"burn\","
                + "\"Onset\":\"acute\",\"DurationNote\":\"immediate\",\"Bioaccumulation\":\"none\",\"AntidoteNote\":\"irrigation\"}");
        assertEquals(List.of(ExposureRoute.CONTACT, ExposureRoute.INHALE), t.route());
        assertEquals(Onset.ACUTE, t.onset());
        assertEquals("severe", t.potencyNote());
        assertEquals("irrigation", t.antidoteNote());
    }
}
