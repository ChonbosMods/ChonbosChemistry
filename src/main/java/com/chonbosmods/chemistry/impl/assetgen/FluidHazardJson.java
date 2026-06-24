package com.chonbosmods.chemistry.impl.assetgen;

import java.util.List;

/**
 * Renders the immediate-effect (v1, design F6) JSON fragments for a fluid's hazards: the contact
 * collision entries on the world block and the drink-effect entries on container Filled states.
 * Returns comma-joined object fragments only (no surrounding array brackets : the caller wraps
 * them); an empty hazard list yields an empty string, so benign fluids render an empty array.
 *
 * <p>The accumulating-dose affliction engine (design 4.4/5.5) is not built yet; v1 applies these
 * immediate status effects and the affliction engine later plugs in behind the same call sites.
 */
public final class FluidHazardJson {

    private FluidHazardJson() {}

    /** The status/effect id each hazard applies: vanilla Burn is reused; the rest are CC effects. */
    public static String effectId(FluidHazard h) {
        return switch (h) {
            case IGNITE -> "Lava_Burn";
            case RADIATION -> "CC_Effect_Radiation";
            case CORROSIVE -> "CC_Effect_Corrosion";
            case CRYO -> "CC_Effect_Cryo";
        };
    }

    /** Drink-effect array entries (the inside of InteractionVars.Effect.Interactions). */
    public static String drinkEffects(List<FluidHazard> hazards) {
        return applyEffectEntries(hazards);
    }

    /**
     * Contact collision entries, one {@code ApplyEffect} per hazard. The cooldown is NOT emitted
     * per entry: the vanilla interaction schema (decompiled {@code SimpleInteraction} has only
     * {@code next}/{@code failed} fields, no {@code cooldown}; {@code RootInteraction} alone owns
     * {@code InteractionCooldown}) puts a single {@code Cooldown} once at the {@code Collision}
     * level. The caller ({@link FluidAssets#sourceBlockJson}) wraps these in that single Collision.
     */
    public static String contactInteractions(List<FluidHazard> hazards) {
        return applyEffectEntries(hazards);
    }

    /**
     * The shared body of {@link #drinkEffects} and {@link #contactInteractions}: comma-joined
     * {@code ApplyEffect} object fragments (no surrounding brackets), one per hazard. Both call sites
     * emit the same per-hazard entry post-cooldown-refactor; they differ only in where the caller
     * wraps the result (a drink-effect array vs. a Collision interactions array).
     */
    private static String applyEffectEntries(List<FluidHazard> hazards) {
        StringBuilder b = new StringBuilder();
        for (FluidHazard h : hazards) {
            if (b.length() > 0) {
                b.append(",\n");
            }
            b.append("{ \"Type\": \"ApplyEffect\", \"EffectId\": \"%s\" }".formatted(effectId(h)));
        }
        return b.toString();
    }
}
