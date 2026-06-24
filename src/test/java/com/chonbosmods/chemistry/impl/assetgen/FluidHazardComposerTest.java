package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FluidHazardComposerTest {

    private static SubstanceRegistry registry;

    @BeforeAll
    static void load() {
        registry = InMemorySubstanceRegistry.loadFromResources();
    }

    private List<FluidHazard> forCompound(String formula, boolean liquefied) {
        var c = registry.compound(formula).orElseThrow();
        return FluidHazardComposer.hazardsFor(new FluidForm(c, liquefied), registry);
    }

    private List<FluidHazard> forElement(String symbol, boolean liquefied) {
        var e = registry.element(symbol).orElseThrow();
        return FluidHazardComposer.hazardsFor(new FluidForm(e, liquefied), registry);
    }

    @Test
    void corrosiveAcidIsCorrosive() {
        assertTrue(forCompound("H2SO4", false).contains(FluidHazard.CORROSIVE));
    }

    @Test
    void toxicByRouteOnlyIsCorrosive() {
        // Citric acid: corrosive()==false, oxidizer()==false, flammable()==false, but a populated
        // exposure route: CORROSIVE here is driven solely by the toxicity-route rule.
        assertTrue(forCompound("C6H8O7", false).contains(FluidHazard.CORROSIVE));
    }

    @Test
    void liquefiedGasIsCryo() {
        assertTrue(forElement("N", true).contains(FluidHazard.CRYO));
    }

    @Test
    void radioactiveElementGlowsAndRadiates() {
        assertTrue(forElement("U", false).contains(FluidHazard.RADIATION));
    }

    @Test
    void benignWaterHasNoHazards() {
        assertEquals(List.of(), forCompound("H2O", false));
    }

    @Test
    void hazardsAreOrderedAndDeduped() {
        // Liquefied radon is a genuinely multi-hazard form: radioactive noble gas (RADIATION) that
        // is liquefied (CRYO). Pins both enum order (RADIATION before CRYO) and the exact set.
        assertEquals(List.of(FluidHazard.RADIATION, FluidHazard.CRYO), forElement("Rn", true));
    }
}
