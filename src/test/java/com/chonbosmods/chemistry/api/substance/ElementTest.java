package com.chonbosmods.chemistry.api.substance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElementTest {

    private static <T> T decode(Codec<T> codec, String json) throws Exception {
        return codec.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void decodesFullElement() throws Exception {
        String json = "{"
            + "\"Name\":\"Iron\",\"Symbol\":\"Fe\",\"Phase\":\"solid\",\"Color\":\"#C0C0C0\","
            + "\"Density\":7.874,\"StandardAtomicWeight\":55.845,\"AtomicNumber\":26,"
            + "\"Group\":8,\"Period\":4,\"Block\":\"d\",\"Category\":\"transition metal\","
            + "\"OxidationStates\":[2,3],\"Electronegativity\":1.83,\"MeltingPoint\":1811.0,\"BoilingPoint\":3134.0,"
            + "\"Appearance\":\"silvery\",\"ReactivityNote\":\"moderate\",\"AbundanceNote\":\"common\","
            + "\"IsotopeSymbols\":[\"Fe-54\",\"Fe-56\"],\"Notes\":\"\",\"ValueConfidence\":\"measured\"}";
        Element e = decode(Element.CODEC, json);
        assertEquals("Iron", e.name());
        assertEquals("Fe", e.formula());
        assertEquals(26, e.atomicNumber());
        assertEquals(Integer.valueOf(8), e.group());
        assertEquals(ElementCategory.TRANSITION_METAL, e.category());
        assertEquals(PeriodicBlock.D, e.block());
        assertEquals(55.845, e.molarMass());
        assertEquals(1.83, e.electronegativity());
        assertEquals(List.of(2, 3), e.oxidationStates());
        assertEquals(List.of("Fe-54", "Fe-56"), e.isotopeSymbols());
        assertEquals(new Color(0xC0, 0xC0, 0xC0), e.color());
    }

    @Test
    void decodesElementWithOptionalFieldsAbsent() throws Exception {
        // f-block style: Group, Electronegativity, MeltingPoint, BoilingPoint omitted (null-valued keys dropped in re-key)
        String json = "{"
            + "\"Name\":\"Lanthanum\",\"Symbol\":\"La\",\"Phase\":\"solid\",\"Color\":\"#A6A6A6\","
            + "\"Density\":6.162,\"StandardAtomicWeight\":138.905,\"AtomicNumber\":57,"
            + "\"Period\":6,\"Block\":\"f\",\"Category\":\"lanthanide\","
            + "\"OxidationStates\":[3],\"IsotopeSymbols\":[\"La-138\",\"La-139\"],\"ValueConfidence\":\"measured\"}";
        Element e = decode(Element.CODEC, json);
        assertEquals("Lanthanum", e.name());
        assertEquals(57, e.atomicNumber());
        assertEquals(ElementCategory.LANTHANIDE, e.category());
        assertNull(e.group());
        assertNull(e.electronegativity());
        assertNull(e.meltingPoint());
        assertEquals(List.of(3), e.oxidationStates());
    }
}
