package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class FluidFormTest {

    @Test
    void fluidSetIsLiquidsPlusLiquefiedGases() {
        var registry = InMemorySubstanceRegistry.loadFromResources();
        List<FluidForm> forms = FluidForm.allFor(registry);

        long nativeLiquids = forms.stream().filter(f -> !f.liquefied()).count();
        long liquefied = forms.stream().filter(FluidForm::liquefied).count();

        assertEquals(39, nativeLiquids, "39 native LIQUID-phase substances");
        assertEquals(27, liquefied, "27 liquefied GAS-phase substances");
        assertEquals(66, forms.size());
    }

    @Test
    void liquefiedGasGetsLiquidDisplayLabel() {
        var registry = InMemorySubstanceRegistry.loadFromResources();
        FluidForm helium = FluidForm.allFor(registry).stream()
            .filter(f -> f.liquefied() && f.substance().name().equals("Helium"))
            .findFirst().orElseThrow();
        assertEquals("Liquid Helium", helium.displayName());
    }

    @Test
    void nativeLiquidKeepsItsName() {
        var registry = InMemorySubstanceRegistry.loadFromResources();
        FluidForm mercury = FluidForm.allFor(registry).stream()
            .filter(f -> f.substance().name().equals("Mercury"))
            .findFirst().orElseThrow();
        assertTrue(!mercury.liquefied());
        assertEquals("Mercury", mercury.displayName());
    }
}
