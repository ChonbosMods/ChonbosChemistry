package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FluidAssetsTest {

    @Test
    void blockIdNamespacesByKindAndLiquefied() {
        assertEquals("Fluid_Compound_Sulfuric_Acid",
            FluidAssets.blockId(false, false, "Sulfuric acid"));
        assertEquals("Fluid_Element_Liquefied_Helium",
            FluidAssets.blockId(true, true, "Helium"));
    }

    @Test
    void sourceAndFlowingIds() {
        assertEquals("Fluid_Element_Mercury", FluidAssets.blockId(true, false, "Mercury"));
        assertEquals("Fluid_Element_Mercury_Source",
            FluidAssets.sourceId(FluidAssets.blockId(true, false, "Mercury")));
    }

    @Test
    void itemIdMirrorsBlockId() {
        assertEquals("Chem_Fluid_Element_Mercury",
            FluidAssets.itemId(true, false, "Mercury"));
    }

    @Test
    void everyFluidFormGetsUniqueIds() {
        var registry = InMemorySubstanceRegistry.loadFromResources();
        List<FluidForm> forms = FluidForm.allFor(registry);
        Set<String> ids = new HashSet<>();
        for (FluidForm f : forms) {
            String id = FluidAssets.blockId(f.isElement(), f.liquefied(), f.substance().name());
            assertTrue(ids.add(id), "duplicate block id " + id + " for " + f.displayName());
        }
        assertEquals(forms.size(), ids.size());
    }
}
