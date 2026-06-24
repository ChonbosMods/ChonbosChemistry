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
        String json = FluidAssets.filledStateJson(mug, blockId, List.of(FluidHazard.CORROSIVE));
        assertTrue(json.contains("Filled_" + blockId), json);                 // state key
        assertTrue(json.contains("CC_Effect_Corrosion"), json);               // drink hazard
        assertTrue(json.contains("Root_Secondary_Consume_Drink"), json);      // drink routing
        assertTrue(json.contains("Mug_Texture_" + blockId), json);            // tinted texture ref
        assertTrue(json.contains("\"BrokenItem\": \"Deco_Mug\""), json);      // empty return
    }

    @Test
    void benignFilledStateAppliesNoEffect() {
        var reg = InMemorySubstanceRegistry.loadFromResources();
        var water = new FluidForm(reg.compound("H2O").orElseThrow(), false);
        var mug = FluidContainers.ALL.stream().filter(c -> c.id().equals("Deco_Mug")).findFirst().orElseThrow();
        String blockId = FluidAssets.blockId(water.isElement(), water.liquefied(), water.substance().name());
        String json = FluidAssets.filledStateJson(mug, blockId, List.of());
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
        assertTrue(FluidAssets.filledStateJson(mug, blockId, List.of()).contains("CustomModelAnimation"));
        assertTrue(!FluidAssets.filledStateJson(tank, blockId, List.of()).contains("CustomModelAnimation"));
    }

    @Test
    void filledStateIsValidJsonWhenWrapped() throws Exception {
        var bucket = FluidContainers.ALL.stream().filter(c -> c.id().equals("Container_Bucket")).findFirst().orElseThrow();
        String blockId = FluidAssets.blockId(false, false, "Sulfuric acid");
        String state = FluidAssets.filledStateJson(bucket, blockId, List.of(FluidHazard.CORROSIVE));
        // filledStateJson returns a single "Filled_X": { ... } member; wrap as an object and parse
        com.hypixel.hytale.codec.util.RawJsonReader.readBsonDocument(
            com.hypixel.hytale.codec.util.RawJsonReader.fromJsonString("{" + state + "}"));
    }
}
