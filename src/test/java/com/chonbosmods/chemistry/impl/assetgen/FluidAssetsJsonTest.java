package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.substance.Color;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class FluidAssetsJsonTest {

    private static final Color RED = new Color(200, 30, 20);

    /**
     * Parses {@code json} as a complete BSON document via the Hytale codec reader, throwing if it is
     * not well-formed. Jackson's {@code ObjectMapper} (from the task's reference test) is NOT on this
     * project's test classpath, so we use the engine's own {@link RawJsonReader} : a faithful parse
     * (the server reads assets through the same reader) rather than a brace-balance approximation.
     */
    private static void assertValidJson(String json) {
        try {
            RawJsonReader.readBsonDocument(RawJsonReader.fromJsonString(json));
        } catch (Exception e) {
            throw new AssertionError("not valid JSON: " + e.getMessage() + "\n" + json, e);
        }
    }

    @Test
    void texturePathUsesBlockTexturesPrefix() {
        assertEquals("BlockTextures/Fluid_Element_Mercury.png",
            FluidAssets.texturePath("Fluid_Element_Mercury"));
    }

    @Test
    void sourceBlockCarriesParticleColorAndHazardContact() {
        String json = FluidAssets.sourceBlockJson(
            "Fluid_Compound_Sulfuric_Acid",
            FluidAssets.texturePath("Fluid_Compound_Sulfuric_Acid"),
            RED, null, List.of(FluidHazard.CORROSIVE), FluidPhysics.waterLike());
        assertTrue(json.contains("\"MaxFluidLevel\": 1"), json);
        assertTrue(json.contains(RED.toHex()), json);            // ParticleColor
        assertTrue(json.contains("CC_Effect_Corrosion"), json);  // contact hazard
        // FluidFXId links the source block to its FluidFX asset (keyed by the bare blockId):
        // without it, submersion fog/movement never apply.
        assertTrue(json.contains("\"FluidFXId\": \"Fluid_Compound_Sulfuric_Acid\""), json);
    }

    @Test
    void benignSourceBlockHasNoCollisionHazards() {
        String json = FluidAssets.sourceBlockJson(
            "Fluid_Compound_Water", FluidAssets.texturePath("Fluid_Compound_Water"),
            RED, null, List.of(), FluidPhysics.waterLike());
        assertTrue(json.contains("\"MaxFluidLevel\": 1"), json);
        assertTrue(!json.contains("CC_Effect_"), json);
    }

    @Test
    void flowingBlockInheritsSource() {
        String json = FluidAssets.flowingBlockJson("Fluid_Element_Mercury");
        assertTrue(json.contains("\"Parent\": \"Fluid_Element_Mercury_Source\""), json);
        assertTrue(json.contains("\"MaxFluidLevel\": 8"), json);
    }

    @Test
    void placementItemPlacesSource() {
        String json = FluidAssets.placementItemJson(
            "Chem_Fluid_Element_Mercury", "Fluid_Element_Mercury",
            "Icons/ItemsGenerated/Chem_Fluid_Element_Mercury.png");
        assertTrue(json.contains("\"PlaceFluid\""), json);
        assertTrue(json.contains("Fluid_Element_Mercury_Source"), json);
    }

    @Test
    void cryoFxSlowsSink() {
        String fx = FluidAssets.fluidFxJson("Fluid_Element_Liquefied_Helium", "#aaeeff",
            FluidPhysics.defaultFor(true));
        assertTrue(fx.contains("SinkSpeed"), fx);
        assertTrue(fx.contains("#aaeeff"), fx);   // FogColor
    }

    @Test
    void everyGeneratedJsonIsValidJson() {
        // structural smoke test: each renderer's output parses as a JSON/BSON document
        assertValidJson(FluidAssets.sourceBlockJson("Fluid_X", "BlockTextures/Fluid_X.png",
            RED, "#F00", List.of(FluidHazard.CORROSIVE, FluidHazard.RADIATION), FluidPhysics.waterLike()));
        assertValidJson(FluidAssets.flowingBlockJson("Fluid_X"));
        assertValidJson(FluidAssets.fluidFxJson("Fluid_X", "#abc", FluidPhysics.defaultFor(false)));
        assertValidJson(FluidAssets.placementItemJson("Chem_Fluid_X", "Fluid_X", "Icons/X.png"));
    }
}
