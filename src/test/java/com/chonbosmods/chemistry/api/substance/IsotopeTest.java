package com.chonbosmods.chemistry.api.substance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.junit.jupiter.api.Test;

class IsotopeTest {

    private static <T> T decode(Codec<T> codec, String json) throws Exception {
        return codec.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void decodesRadioactiveIsotopeWithDecayModes() throws Exception {
        String json = "{\"ParentSymbol\":\"U\",\"ParentZ\":92,\"MassNumber\":235,\"IsotopeSymbol\":\"U-235\","
            + "\"NaturalAbundance\":0.7204,\"Stability\":\"radioactive\",\"HalfLife\":\"703.8 My\",\"HalfLifeSeconds\":2.221e16,"
            + "\"DecayModes\":[{\"Mode\":\"alpha\",\"Branching\":100.0,\"Daughter\":\"Th-231\",\"DecayEnergy\":4.679}],"
            + "\"DominantEmission\":\"alpha\",\"RadiationVector\":\"penetrating\","
            + "\"SpecificActivity\":80011000.0,\"DecayHeat\":6.0e-5,"
            + "\"NuclearFlags\":{\"Fissile\":true,\"Fertile\":false,\"Fusionable\":false},\"ValueConfidence\":\"measured\"}";
        Isotope i = decode(Isotope.CODEC, json);
        assertEquals("U-235", i.isotopeSymbol());
        assertEquals(Stability.RADIOACTIVE, i.stability());
        assertEquals(RadiationVector.PENETRATING, i.radiationVector());
        assertTrue(i.nuclearFlags().fissile());
        assertEquals(1, i.decayModes().size());
        assertEquals("Th-231", i.decayModes().get(0).daughter());
        assertEquals(2.221e16, i.halfLifeSeconds().doubleValue());
    }

    @Test
    void decodesStableIsotopeWithNullsAbsentAndEmptyDecayModes() throws Exception {
        String json = "{\"ParentSymbol\":\"Fe\",\"ParentZ\":26,\"MassNumber\":56,\"IsotopeSymbol\":\"Fe-56\","
            + "\"NaturalAbundance\":91.754,\"Stability\":\"stable\",\"HalfLife\":\"stable\","
            + "\"DecayModes\":[],\"DominantEmission\":\"none\",\"RadiationVector\":\"none\","
            + "\"NuclearFlags\":{\"Fissile\":false,\"Fertile\":false,\"Fusionable\":false},\"ValueConfidence\":\"measured\"}";
        Isotope i = decode(Isotope.CODEC, json);
        assertEquals(Stability.STABLE, i.stability());
        assertEquals("stable", i.halfLife());
        assertNull(i.halfLifeSeconds());
        assertNull(i.specificActivity());
        assertNull(i.decayHeat());
        assertTrue(i.decayModes().isEmpty());
        assertEquals(RadiationVector.NONE, i.radiationVector());
    }
}
