package com.chonbosmods.chemistry.api.substance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

/** End-to-end: decode the real re-keyed data/ through the codecs. Runs with cwd = project root. */
class DataDecodeIntegrationTest {

    private static final ExtraInfo EI = EmptyExtraInfo.EMPTY;

    private static <T> T[] decodeAll(Codec<T> codec, IntFunction<T[]> array, String relPath) throws Exception {
        String json = Files.readString(Path.of("data", relPath));
        return new ArrayCodec<>(codec, array).decodeJson(RawJsonReader.fromJsonString(json), EI);
    }

    private static <T> T find(T[] arr, Predicate<T> p) {
        return Arrays.stream(arr).filter(p).findFirst().orElseThrow();
    }

    @Test
    void decodesAll118Elements() throws Exception {
        Element[] els = decodeAll(Element.CODEC, Element[]::new, "elements.json");
        assertEquals(118, els.length);
        Element fe = find(els, e -> "Fe".equals(e.formula()));
        assertEquals(26, fe.atomicNumber());
        assertEquals(ElementCategory.TRANSITION_METAL, fe.category());
        assertEquals(PeriodicBlock.D, fe.block());
    }

    @Test
    void decodesAll153Compounds() throws Exception {
        Compound[] cs = decodeAll(Compound.CODEC, Compound[]::new, "compounds.json");
        assertEquals(153, cs.length);
        Compound water = find(cs, c -> "H2O".equals(c.formula()));
        assertEquals(18.015, water.molarMass(), 0.05);
    }

    @Test
    void decodesAll673Isotopes() throws Exception {
        String[] files = {
            "batchA_Z1-20", "batchB_Z21-40", "batchC_Z41-60",
            "batchD_Z61-80", "batchE_Z81-100", "batchF_Z101-118"
        };
        int total = 0;
        Isotope u235 = null;
        Isotope be10 = null;
        for (String f : files) {
            Isotope[] isotopes = decodeAll(Isotope.CODEC, Isotope[]::new, "isotopes/" + f + ".json");
            total += isotopes.length;
            for (Isotope i : isotopes) {
                if ("U-235".equals(i.isotopeSymbol())) {
                    u235 = i;
                }
                if ("Be-10".equals(i.isotopeSymbol())) {
                    be10 = i;
                }
            }
        }
        assertEquals(673, total);
        assertTrue(u235.nuclearFlags().fissile(), "U-235 should be fissile");
        assertEquals("1.387 My", be10.halfLife(), "Be-10 should carry the corrected half-life");
    }

    @Test
    void roundTripsAFullyPopulatedElementThroughBson() throws Exception {
        Element[] els = decodeAll(Element.CODEC, Element[]::new, "elements.json");
        Element fe = find(els, e -> "Fe".equals(e.formula()));
        BsonValue encoded = Element.CODEC.encode(fe, EI);
        Element reDecoded = Element.CODEC.decode(encoded, EI);
        assertEquals(fe.atomicNumber(), reDecoded.atomicNumber());
        assertEquals(fe.molarMass(), reDecoded.molarMass());
        assertEquals(fe.color(), reDecoded.color());
        assertEquals(fe.category(), reDecoded.category());
    }
}
