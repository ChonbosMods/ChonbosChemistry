package com.chonbosmods.chemistry.impl.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.api.substance.Compound;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Isotope;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InMemorySubstanceRegistryTest {

    private static SubstanceRegistry reg;

    @BeforeAll
    static void load() {
        reg = InMemorySubstanceRegistry.loadFromResources();
    }

    @Test
    void loadsAllRecords() {
        assertEquals(118, reg.elements().size());
        assertEquals(153, reg.compounds().size());
        assertEquals(673, reg.isotopes().size());
    }

    @Test
    void elementBySymbolAndByAtomicNumberResolveToSameInstance() {
        Element bySymbol = reg.element("Fe").orElseThrow();
        Element byNumber = reg.element(26).orElseThrow();
        assertSame(bySymbol, byNumber);
        assertEquals(26, bySymbol.atomicNumber());
    }

    @Test
    void lookupMissReturnsEmpty() {
        assertTrue(reg.element("Xx").isEmpty());
        assertTrue(reg.isotope("Zz-999").isEmpty());
        assertTrue(reg.element(999).isEmpty());
    }

    @Test
    void compoundAndIsotopeLookup() {
        assertEquals("H2O", reg.compound("H2O").orElseThrow().formula());
        assertTrue(reg.isotope("U-235").orElseThrow().nuclearFlags().fissile());
    }

    @Test
    void isotopeByNuclideCoordinates() {
        // the irradiator's neutron-activation target: Co-59 + n -> Co-60 = (Z 27, A 60)
        assertEquals("Co-60", reg.isotope(27, 60).orElseThrow().isotopeSymbol());
    }

    @Test
    void isotopesOfResolvesSymbols() {
        List<Isotope> isotopes = reg.isotopesOf(reg.element("Fe").orElseThrow());
        assertTrue(isotopes.stream().anyMatch(i -> i.isotopeSymbol().equals("Fe-56")));
    }

    @Test
    void constituentsOfResolvesComposition() {
        Map<Element, Integer> parts = reg.constituentsOf(reg.compound("H2O").orElseThrow());
        assertEquals(2, parts.get(reg.element("H").orElseThrow()));
        assertEquals(1, parts.get(reg.element("O").orElseThrow()));
    }

    @Test
    void daughtersOfResolvesDecayTargets() {
        List<Isotope> daughters = reg.daughtersOf(reg.isotope("U-238").orElseThrow());
        assertTrue(daughters.stream().anyMatch(i -> i.isotopeSymbol().equals("Th-234")));
    }

    @Test
    void compoundByCompositionReverseLookup() {
        Optional<Compound> water = reg.compoundByComposition(Map.of("H", 2, "O", 1));
        assertTrue(water.isPresent());
        assertEquals("H2O", water.get().formula());
    }

    @Test
    void danglingReferencesAreSkippedNotThrown() throws Exception {
        // a synthetic isotope whose decay daughter is not in the registry
        String json = "{\"ParentSymbol\":\"Zz\",\"ParentZ\":999,\"MassNumber\":999,\"IsotopeSymbol\":\"Zz-999\","
            + "\"NaturalAbundance\":0.0,\"Stability\":\"radioactive\",\"HalfLife\":\"1 s\",\"HalfLifeSeconds\":1.0,"
            + "\"DecayModes\":[{\"Mode\":\"alpha\",\"Branching\":100.0,\"Daughter\":\"Qq-998\",\"DecayEnergy\":1.0}],"
            + "\"DominantEmission\":\"alpha\",\"RadiationVector\":\"contact-only\","
            + "\"NuclearFlags\":{\"Fissile\":false,\"Fertile\":false,\"Fusionable\":false},\"ValueConfidence\":\"estimated\"}";
        Isotope synthetic = Isotope.CODEC.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
        assertTrue(reg.daughtersOf(synthetic).isEmpty());
    }
}
