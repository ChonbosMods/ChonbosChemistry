package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.substance.Color;
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

    @Test
    void lightJsonScalesColorToTierCap() {
        // radium green #7AE89A (122, 232, 154): max channel 232 anchors the brightest digit to cap.
        // Exact integer math digit = (channel * cap + max / 2) / max, max = 232:
        //   FAINT  cap 5:  r=(122*5+116)/232=3, g=(232*5+116)/232=5,  b=(154*5+116)/232=3  -> "#353"
        //   STRONG cap10:  r=(122*10+116)/232=5, g=10,                b=(154*10+116)/232=7 -> "#5A7"
        //   FIERCE cap15:  r=(122*15+116)/232=8, g=15,                b=(154*15+116)/232=10-> "#8FA"
        Color radiumGreen = new Color(0x7A, 0xE8, 0x9A);
        assertEquals("#353", SolidSubstanceAssets.lightJson(radiumGreen, GlowTier.FAINT));
        assertEquals("#5A7", SolidSubstanceAssets.lightJson(radiumGreen, GlowTier.STRONG));
        assertEquals("#8FA", SolidSubstanceAssets.lightJson(radiumGreen, GlowTier.FIERCE));
        assertNull(SolidSubstanceAssets.lightJson(radiumGreen, GlowTier.NONE));
    }

    @Test
    void lightJsonReturnsNullForBlack() {
        assertNull(SolidSubstanceAssets.lightJson(new Color(0, 0, 0), GlowTier.FIERCE));
    }

    @Test
    void lightJsonScalesSingleChannelToFullCap() {
        // pure red, brightest channel already == 255 -> hits cap exactly, others stay 0.
        assertEquals("#F00", SolidSubstanceAssets.lightJson(new Color(255, 0, 0), GlowTier.FIERCE));
    }

    @Test
    void itemJsonEmitsLightAndGlowModelForGlowingTiers() {
        String json = SolidSubstanceAssets.itemJson("X", "tex.png", GlowTier.STRONG, "#5A7");
        assertTrue(json.contains("\"Light\": { \"Color\": \"#5A7\" }"), json);
        assertTrue(json.contains(SolidSubstanceAssets.MODEL_GLOW), json);
        // glow model is distinct from the base model path (Solid.blockymodel is NOT a substring of
        // SolidGlow.blockymodel because the '.' separates "Solid" from ".blockymodel").
        assertFalse(json.contains(SolidSubstanceAssets.MODEL), json);
    }

    @Test
    void itemJsonOmitsLightForNone() {
        String json = SolidSubstanceAssets.itemJson("X", "tex.png", GlowTier.NONE, null);
        assertFalse(json.contains("\"Light\""), json);
        assertTrue(json.contains(SolidSubstanceAssets.MODEL), json);
    }

    @Test
    void twoArgItemJsonEqualsNoneGlowForm() {
        assertEquals(
            SolidSubstanceAssets.itemJson("X", "tex.png", GlowTier.NONE, null),
            SolidSubstanceAssets.itemJson("X", "tex.png"));
    }

    @Test
    void itemJsonStaysBalancedJsonWhenSplicingLight() {
        String json = SolidSubstanceAssets.itemJson("X", "tex.png", GlowTier.FIERCE, "#8FA");
        long open = json.chars().filter(ch -> ch == '{').count();
        long close = json.chars().filter(ch -> ch == '}').count();
        assertEquals(open, close, "unbalanced braces in spliced JSON: " + json);
    }
}
