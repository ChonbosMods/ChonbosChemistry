package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.substance.Phase;
import com.chonbosmods.chemistry.api.substance.Substance;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SolidSubstanceAssetsTest {

    @Test
    void everySolidSubstanceGetsAUniqueAssetId() {
        var registry = InMemorySubstanceRegistry.loadFromResources();
        List<Substance> solids = new ArrayList<>();
        registry.elements().stream().filter(s -> s.phase() == Phase.SOLID).forEach(solids::add);
        registry.compounds().stream().filter(s -> s.phase() == Phase.SOLID).forEach(solids::add);

        Set<String> ids = new HashSet<>();
        for (Substance s : solids) {
            String id = SolidSubstanceAssets.assetId(s);
            assertTrue(ids.add(id), "duplicate asset id " + id + " for " + s.name());
        }
        assertEquals(solids.size(), ids.size());
        assertTrue(solids.size() >= 200, "expected ~205 solids, got " + solids.size());
    }

    @Test
    void elementAssetIdNamespacesBySymbol() {
        assertEquals("Chem_Solid_Element_Fe", SolidSubstanceAssets.assetId(true, "Fe"));
    }

    @Test
    void compoundAssetIdSanitizesNonAlphanumeric() {
        assertEquals("Chem_Solid_Compound_Ca_OH_2", SolidSubstanceAssets.assetId(false, "Ca(OH)2"));
    }

    @Test
    void assetIdTitleCasesEachSegment() {
        // Hytale's AssetStore requires PascalCase-per-segment item keys.
        assertEquals("Chem_Solid_Compound_Sodium_Chloride",
            SolidSubstanceAssets.assetId(false, "Sodium chloride"));
        assertEquals("Chem_Solid_Compound_Iron_III_Oxide",
            SolidSubstanceAssets.assetId(false, "Iron(III) oxide"));
    }

    @Test
    void itemJsonIsAPlaceableModelBlock() {
        String id = "Chem_Solid_Element_Fe";
        String json = SolidSubstanceAssets.itemJson(id, SolidSubstanceAssets.texturePath(id));

        // a BlockType model-block: renders the jar model in hand AND is placeable (like the mug)
        assertTrue(json.contains("\"DrawType\": \"Model\""), json);
        assertTrue(json.contains("\"CustomModel\": \"Items/Chemistry/Solid.blockymodel\""), json);
        // per-substance color rides on the block's CustomModelTexture
        assertTrue(json.contains("\"Items/Chemistry/Substance_Textures/Chem_Solid_Element_Fe.png\""), json);
        assertTrue(json.contains("Block_Primary"), json);
        // item references the server.* namespace; the lang file keys it without the prefix
        assertTrue(json.contains("\"server.items.Chem_Solid_Element_Fe.name\""), json);
        assertTrue(json.contains("\"Icons/ItemsGenerated/Chem_Solid_Element_Fe.png\""), json);
        assertTrue(json.contains("Blocks.Deco"), json);
    }
}
