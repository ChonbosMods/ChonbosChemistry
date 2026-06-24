package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class FluidContainersTest {

    @Test
    void registryListsTheThreeWaterCapableContainers() {
        var ids = FluidContainers.ALL.stream().map(FluidContainers.FluidContainer::id).toList();
        assertTrue(ids.contains("Deco_Mug"), ids.toString());
        assertTrue(ids.contains("Deco_Tankard"), ids.toString());
        assertTrue(ids.contains("Container_Bucket"), ids.toString());
        assertEquals(3, FluidContainers.ALL.size());
    }

    @Test
    void containersCarryVanillaDisplayNames() {
        assertEquals("Wooden Mug", container("Deco_Mug").displayName());
        assertEquals("Tankard", container("Deco_Tankard").displayName());
        assertEquals("Wooden Bucket", container("Container_Bucket").displayName());
    }

    @Test
    void filledNameMimicsVanillaContainerParenFluidFormat() {
        // vanilla: items.Container_Bucket_Water.name = "Wooden Bucket (Water)"
        assertEquals("Wooden Bucket (Water)", container("Container_Bucket").filledName("Water"));
        assertEquals("Wooden Bucket (Hydrogen peroxide)",
            container("Container_Bucket").filledName("Hydrogen peroxide"));
        assertEquals("Wooden Mug (Liquid Helium)", container("Deco_Mug").filledName("Liquid Helium"));
        assertEquals("Tankard (Mercury)", container("Deco_Tankard").filledName("Mercury"));
    }

    @Test
    void filledDescriptionDiffersByFillMode() {
        // POUR (bucket) mimics vanilla bucket: "...that can be placed in the world."
        assertEquals("Contains <color is=\"#ffffff\">Water</color> that can be placed in the world.",
            container("Container_Bucket").filledDescription("Water"));
        // DRINK (mug/tankard): "...that can be drunk."
        assertEquals("Contains <color is=\"#ffffff\">Water</color> that can be drunk.",
            container("Deco_Mug").filledDescription("Water"));
    }

    private static FluidContainers.FluidContainer container(String id) {
        return FluidContainers.ALL.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void bucketPreservesItsThreeVanillaStates() {
        var bucket = FluidContainers.ALL.stream().filter(c -> c.id().equals("Container_Bucket")).findFirst().orElseThrow();
        assertTrue(bucket.preservedStates().containsAll(List.of("Filled_Water", "Filled_Milk", "Filled_Mosshorn_Milk")));
    }

    @Test
    void filledStateDrinksHazard() {
        var reg = InMemorySubstanceRegistry.loadFromResources();
        var acid = new FluidForm(reg.compound("H2SO4").orElseThrow(), false);
        var mug = FluidContainers.ALL.stream().filter(c -> c.id().equals("Deco_Mug")).findFirst().orElseThrow();
        String blockId = FluidAssets.blockId(acid.isElement(), acid.liquefied(), acid.substance().name());
        String json = FluidAssets.filledStateJson(mug, blockId, List.of(FluidHazard.CORROSIVE), icon(mug, blockId));
        assertTrue(json.contains("Filled_" + blockId), json);                 // state key
        assertTrue(json.contains("CC_Effect_Corrosion"), json);               // drink hazard
        assertTrue(json.contains("Root_Secondary_Consume_Drink"), json);      // drink routing
        assertTrue(json.contains("Mug_Texture_" + blockId), json);            // tinted texture ref
        assertTrue(json.contains("\"BrokenItem\": \"Deco_Mug\""), json);      // empty return
        // per-substance icon + carried vanilla render fields (faithful to vanilla filled state)
        assertTrue(json.contains("\"Icon\": \"" + icon(mug, blockId) + "\""), json);
        assertTrue(json.contains("\"CustomModelScale\": 0.6"), json);         // mug vanilla render field
        assertTrue(json.contains("\"HitboxType\": \"Food_Medium\""), json);   // mug vanilla render field
        assertTrue(json.contains("\"IconProperties\""), json);                // state-level extras
        // BUG 2: emits both Name + Description translation keys
        assertTrue(json.contains("\"Name\": \"server.items.Deco_Mug_" + blockId + ".name\""), json);
        assertTrue(json.contains("\"Description\": \"server.items.Deco_Mug_" + blockId + ".description\""), json);
    }

    @Test
    void bucketFilledStatePoursAndDoesNotDrink() {
        var bucket = FluidContainers.ALL.stream().filter(c -> c.id().equals("Container_Bucket")).findFirst().orElseThrow();
        assertTrue(bucket.pours(), "bucket must be POUR mode");
        String blockId = FluidAssets.blockId(false, false, "Water");
        String json = FluidAssets.filledStateJson(bucket, blockId, List.of(FluidHazard.CORROSIVE), icon(bucket, blockId));
        // BUG 1: bucket pours (PlaceFluid -> <blockId>_Source) and does NOT drink.
        assertTrue(json.contains("\"Type\": \"PlaceFluid\""), json);
        assertTrue(json.contains("\"FluidToPlace\": \"" + blockId + "_Source\""), json);
        assertTrue(!json.contains("Root_Secondary_Consume_Drink"), json);   // no drink routing
        assertTrue(!json.contains("InteractionVars"), json);                // no drink vars
        assertTrue(!json.contains("CC_Effect_"), json);                     // hazard carried by fluid, not drink
        // still empties to the broken item on pour (durability -1)
        assertTrue(json.contains("\"BrokenItem\": \"Container_Bucket\""), json);
        assertTrue(json.contains("\"AdjustHeldItemDurability\": -1"), json);
    }

    @Test
    void mugAndTankardDrinkNotPour() {
        for (String id : List.of("Deco_Mug", "Deco_Tankard")) {
            var c = FluidContainers.ALL.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
            assertTrue(!c.pours(), id + " must be DRINK mode");
            String blockId = FluidAssets.blockId(false, false, "Water");
            String json = FluidAssets.filledStateJson(c, blockId, List.of(), icon(c, blockId));
            assertTrue(json.contains("Root_Secondary_Consume_Drink"), id + ": " + json);
            assertTrue(!json.contains("\"Type\": \"PlaceFluid\""), id + ": " + json);
        }
    }

    /** The per-substance tinted CONTAINER icon path the generator passes for a filled state (BUG 4). */
    private static String icon(FluidContainers.FluidContainer c, String blockId) {
        return c.iconPath(blockId);
    }

    @Test
    void benignFilledStateAppliesNoEffect() {
        var reg = InMemorySubstanceRegistry.loadFromResources();
        var water = new FluidForm(reg.compound("H2O").orElseThrow(), false);
        var mug = FluidContainers.ALL.stream().filter(c -> c.id().equals("Deco_Mug")).findFirst().orElseThrow();
        String blockId = FluidAssets.blockId(water.isElement(), water.liquefied(), water.substance().name());
        String json = FluidAssets.filledStateJson(mug, blockId, List.of(), icon(mug, blockId));
        assertTrue(!json.contains("CC_Effect_"), json);       // no hazard effect
        // still drinkable + empties: routes to consume-drink and returns the empty item
        assertTrue(json.contains("Root_Secondary_Consume_Drink"), json);
        assertTrue(json.contains("\"BrokenItem\": \"Deco_Mug\""), json);
    }

    @Test
    void mugStateUsesAnimationButTankardDoesNot() {
        String blockId = FluidAssets.blockId(false, false, "Water");
        var mug = FluidContainers.ALL.stream().filter(c -> c.id().equals("Deco_Mug")).findFirst().orElseThrow();
        var tank = FluidContainers.ALL.stream().filter(c -> c.id().equals("Deco_Tankard")).findFirst().orElseThrow();
        assertTrue(FluidAssets.filledStateJson(mug, blockId, List.of(), icon(mug, blockId)).contains("CustomModelAnimation"));
        assertTrue(!FluidAssets.filledStateJson(tank, blockId, List.of(), icon(tank, blockId)).contains("CustomModelAnimation"));
    }

    @Test
    void filledStateIsValidJsonWhenWrapped() throws Exception {
        var bucket = FluidContainers.ALL.stream().filter(c -> c.id().equals("Container_Bucket")).findFirst().orElseThrow();
        String blockId = FluidAssets.blockId(false, false, "Sulfuric acid");
        String state = FluidAssets.filledStateJson(bucket, blockId, List.of(FluidHazard.CORROSIVE), icon(bucket, blockId));
        // filledStateJson returns a single "Filled_X": { ... } member; wrap as an object and parse
        com.hypixel.hytale.codec.util.RawJsonReader.readBsonDocument(
            com.hypixel.hytale.codec.util.RawJsonReader.fromJsonString("{" + state + "}"));
    }
}
