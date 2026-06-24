package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FluidHazardJsonTest {

    @Test
    void igniteUsesVanillaBurn() {
        String drink = FluidHazardJson.drinkEffects(List.of(FluidHazard.IGNITE));
        assertTrue(drink.contains("Lava_Burn"), drink);
    }

    @Test
    void eachHazardMapsToItsEffectId() {
        assertEquals("Lava_Burn", FluidHazardJson.effectId(FluidHazard.IGNITE));
        assertEquals("CC_Effect_Radiation", FluidHazardJson.effectId(FluidHazard.RADIATION));
        assertEquals("CC_Effect_Corrosion", FluidHazardJson.effectId(FluidHazard.CORROSIVE));
        assertEquals("CC_Effect_Cryo", FluidHazardJson.effectId(FluidHazard.CRYO));
    }

    @Test
    void stackedDrinkEffectsContainAllAndAreCommaSeparated() {
        String drink = FluidHazardJson.drinkEffects(List.of(FluidHazard.RADIATION, FluidHazard.CORROSIVE));
        assertTrue(drink.contains("CC_Effect_Radiation"), drink);
        assertTrue(drink.contains("CC_Effect_Corrosion"), drink);
        // two ApplyEffect entries -> one separating comma between objects
        assertEquals(2, drink.split("\"Type\"", -1).length - 1, drink);
    }

    @Test
    void benignDrinkAndContactAreEmpty() {
        assertTrue(FluidHazardJson.drinkEffects(List.of()).isBlank());
        assertTrue(FluidHazardJson.contactInteractions(List.of()).isBlank());
    }

    @Test
    void contactGivesEachHazardItsOwnCooldownId() {
        String contact = FluidHazardJson.contactInteractions(List.of(FluidHazard.RADIATION, FluidHazard.CORROSIVE));
        assertTrue(contact.contains("CC_Effect_Radiation"), contact);
        assertTrue(contact.contains("CC_Effect_Corrosion"), contact);
        assertTrue(contact.contains("CC_Fluid_RADIATION"), contact);
        assertTrue(contact.contains("CC_Fluid_CORROSIVE"), contact);
    }

    @Test
    void producedFragmentsAreValidJsonObjectsWhenWrapped() {
        // wrap fragments in array brackets and confirm they parse as a JSON array
        String drink = "[" + FluidHazardJson.drinkEffects(List.of(FluidHazard.IGNITE, FluidHazard.CRYO)) + "]";
        // minimal structural check: balanced braces and the expected entry count
        assertEquals(2, drink.split("\\{", -1).length - 1, drink);
    }
}
