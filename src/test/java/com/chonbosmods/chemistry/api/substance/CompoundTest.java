package com.chonbosmods.chemistry.api.substance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.junit.jupiter.api.Test;

class CompoundTest {

    private static <T> T decode(Codec<T> codec, String json) throws Exception {
        return codec.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void decodesCompoundWithToxicityAndComposition() throws Exception {
        String json = "{\"Name\":\"Sulfuric acid\",\"Formula\":\"H2SO4\",\"Phase\":\"liquid\",\"Color\":\"#EFEFE0\","
            + "\"Density\":1.83,\"MolarMass\":98.079,\"Composition\":{\"H\":2,\"S\":1,\"O\":4},"
            + "\"CompoundType\":\"acid\",\"PropertyFlags\":{\"Corrosive\":true,\"Flammable\":false,\"Oxidizer\":true,"
            + "\"Volatile\":false,\"Explosive\":false,\"WaterSoluble\":true},"
            + "\"Toxicity\":{\"Route\":[\"contact\",\"inhale\"],\"PotencyNote\":\"severe\",\"Effect\":\"burn\","
            + "\"Onset\":\"acute\",\"DurationNote\":\"immediate\",\"Bioaccumulation\":\"none\",\"AntidoteNote\":\"water\"},"
            + "\"WaterSolubility\":\"miscible\",\"IsRadioactive\":false,\"ValueConfidence\":\"measured\"}";
        Compound c = decode(Compound.CODEC, json);
        assertEquals("H2SO4", c.formula());
        assertEquals(98.079, c.molarMass());
        assertEquals(CompoundType.ACID, c.compoundType());
        assertEquals(Integer.valueOf(4), c.composition().get("O"));
        assertEquals(Integer.valueOf(2), c.composition().get("H"));
        assertTrue(c.propertyFlags().corrosive());
        assertTrue(c.propertyFlags().oxidizer());
        assertEquals(Onset.ACUTE, c.toxicity().onset());
        assertEquals("water", c.toxicity().antidoteNote());
        assertFalse(c.isRadioactive());
    }

    @Test
    void decodesCompoundWithOptionalFieldsAbsent() throws Exception {
        // alloy: no Toxicity, no Density, no WaterSolubility
        String json = "{\"Name\":\"Steel\",\"Formula\":\"FeC\",\"Phase\":\"solid\",\"Color\":\"#888888\","
            + "\"MolarMass\":67.0,\"Composition\":{\"Fe\":1,\"C\":1},\"CompoundType\":\"alloy\","
            + "\"PropertyFlags\":{\"Corrosive\":false,\"Flammable\":false,\"Oxidizer\":false,"
            + "\"Volatile\":false,\"Explosive\":false,\"WaterSoluble\":false},"
            + "\"IsRadioactive\":false,\"ValueConfidence\":\"measured\"}";
        Compound c = decode(Compound.CODEC, json);
        assertEquals("Steel", c.name());
        assertEquals(CompoundType.ALLOY, c.compoundType());
        assertNull(c.toxicity());
        assertNull(c.density());
        assertNull(c.waterSolubility());
    }
}
