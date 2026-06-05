package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GlowDeriverTest {

    private static SubstanceRegistry registry;

    @BeforeAll
    static void load() {
        registry = InMemorySubstanceRegistry.loadFromResources();
    }

    private GlowTier element(String symbol) {
        return GlowDeriver.tierFor(registry.element(symbol).orElseThrow(), registry);
    }

    private GlowTier compound(String formula) {
        return GlowDeriver.tierFor(registry.compound(formula).orElseThrow(), registry);
    }

    @Test
    void stableElementsDoNotGlow() {
        assertEquals(GlowTier.NONE, element("Fe"));
        assertEquals(GlowTier.NONE, element("Au"));
        // observationally-stable isotopes count as stable: half-life must NOT be consulted
        assertEquals(GlowTier.NONE, element("W"));
        assertEquals(GlowTier.NONE, element("K"));
        // Bi-209 is recorded as observationally stable in the data (NUBASE convention), so Bi
        // reads NONE despite its ~2e19 yr alpha decay: the data is truth, same rule as W and K.
        assertEquals(GlowTier.NONE, element("Bi"));
    }

    @Test
    void phosphorusGlowsFaintAsChemiluminescentException() {
        // User decision (2026-06-05): phosphorus is a curated chemiluminescent exception to
        // the nuclear-only rule. White P glows in the dark (the element is named light-bearer),
        // so P reads FAINT despite having only stable isotopes (which would otherwise be NONE).
        assertEquals(GlowTier.FAINT, element("P"));
    }

    @Test
    void geologicallyLongLivedGlowFaint() {
        assertEquals(GlowTier.FAINT, element("Th"));
        assertEquals(GlowTier.FAINT, element("U"));
    }

    @Test
    void hotElementsGlowStrong() {
        assertEquals(GlowTier.STRONG, element("Tc"));
        assertEquals(GlowTier.STRONG, element("Pm"));
        assertEquals(GlowTier.STRONG, element("Ra"));
        assertEquals(GlowTier.STRONG, element("Po"));
    }

    @Test
    void syntheticSuperheaviesGlowFierce() {
        assertEquals(GlowTier.FIERCE, element("Fm"));
        assertEquals(GlowTier.FIERCE, element("Og"));
    }

    @Test
    void nonRadioactiveCompoundsDoNotGlow() {
        assertEquals(GlowTier.NONE, compound("H2O"));
        assertEquals(GlowTier.NONE, compound("NaCl"));
    }

    @Test
    void uraniumOxideGlowsFaintViaConstituent() {
        assertEquals(GlowTier.FAINT, compound("UO2"));
    }

    @Test
    void isotopeReferenceInNotesEscalatesTier() {
        // elemental Cs is stable -> NONE, but the compound's notes name Cs-137 (30 y)
        assertEquals(GlowTier.STRONG, compound("CsCl"));
    }

    @Test
    void plutoniumDioxideGlowsStrong() {
        // Pu's longest-lived isotope (~2.5e15 s) sits below the FAINT threshold
        assertEquals(GlowTier.STRONG, compound("PuO2"));
    }
}
