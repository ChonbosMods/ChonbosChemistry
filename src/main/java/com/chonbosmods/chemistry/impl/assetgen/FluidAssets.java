package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.substance.Color;
import java.util.List;

/**
 * Pure asset-identity + path helpers for substance fluids, mirroring {@link SolidSubstanceAssets}.
 * Block ids: {@code Fluid_<Kind>[_Liquefied]_<Name>}; the flowing block uses the bare id and the
 * still pool appends {@code _Source}. Placement item ids: {@code Chem_Fluid_<Kind>[_Liquefied]_<Name>}.
 */
public final class FluidAssets {

    private FluidAssets() {}

    public static String blockId(boolean isElement, boolean liquefied, String name) {
        String kind = isElement ? "Element" : "Compound";
        String pre = liquefied ? "Liquefied_" : "";
        return "Fluid_" + kind + "_" + pre + AssetIds.pascalSegments(name);
    }

    public static String itemId(boolean isElement, boolean liquefied, String name) {
        return "Chem_" + blockId(isElement, liquefied, name);
    }

    public static String sourceId(String blockId) {
        return blockId + "_Source";
    }

    /** Surface texture path relative to the asset pack {@code Common/}. */
    public static String texturePath(String blockId) {
        return "BlockTextures/" + blockId + ".png";
    }

    /**
     * The still-pool source block JSON, modeled on vanilla {@code Water_Source.json}/{@code
     * Lava_Source.json}. {@code MaxFluidLevel} 1, {@code Opacity} Transparent, the single surface
     * texture, {@code ParticleColor} = {@code color.toHex()}, and a {@code Ticker} that spreads
     * {@code blockId} at the substance's {@link FluidPhysics#flowRate()} (never demoting : the
     * flowing variant carries demotion). {@code FluidFXId} = {@code blockId} : the only link to the
     * matching {@link #fluidFxJson} asset (which is keyed by the bare blockId), so submersion fog and
     * movement actually apply.
     *
     * <p>{@code Light} is spliced only when {@code lightColor != null} (same null-guard style as
     * {@link SolidSubstanceAssets#itemJson}). Hazards become a single {@code Interactions.Collision}
     * with ONE Collision-level {@code Cooldown} (vanilla {@code RootInteraction} owns the cooldown;
     * the per-hazard {@code ApplyEffect} entries do not : see {@link FluidHazardJson}); a benign
     * fluid (empty hazard list) emits NO {@code Interactions} block, so no {@code CC_Effect_} appears.
     */
    public static String sourceBlockJson(String blockId, String texturePath, Color color,
                                         String lightColor, List<FluidHazard> hazards,
                                         FluidPhysics physics) {
        String light = lightColor == null
            ? ""
            : "\n  \"Light\": { \"Color\": \"%s\" },".formatted(lightColor);
        String interactions = hazards.isEmpty()
            ? ""
            : """

                  "Interactions": {
                    "Collision": {
                      "Cooldown": { "Id": "CC_Fluid_Contact", "Cooldown": 1 },
                      "Interactions": [
                %s
                      ]
                    }
                  },"""
                .formatted(indent(FluidHazardJson.contactInteractions(hazards), "        "));
        return """
            {
              "MaxFluidLevel": 1,
              "Opacity": "Transparent",
              "Textures": [
                { "Weight": 1, "All": "%s" }
              ],
              "ParticleColor": "%s",
              "FluidFXId": "%s",%s%s
              "Ticker": {
                "CanDemote": false,
                "SpreadFluid": "%s",
                "FlowRate": %s
              },
              "Tags": {
                "Fluid": [ "%s" ]
              }
            }
            """.formatted(texturePath, color.toHex(), blockId, light, interactions,
                blockId, num(physics.flowRate()), blockId);
    }

    /**
     * The flowing variant, modeled on vanilla {@code Water.json}: it inherits everything from the
     * source via {@code Parent} = {@code blockId + "_Source"} and only overrides {@code MaxFluidLevel}
     * (8 = a full flow column) and the {@code Ticker} demotion (it demotes; the source supports it).
     */
    public static String flowingBlockJson(String blockId) {
        return """
            {
              "Parent": "%s_Source",
              "MaxFluidLevel": 8,
              "Ticker": {
                "CanDemote": true,
                "SupportedBy": "%s_Source"
              }
            }
            """.formatted(blockId, blockId);
    }

    /**
     * The submerged-camera FluidFX, modeled on vanilla {@code FluidFX/Water.json}/{@code Lava.json}:
     * a colored fog ({@code Fog} "Color", {@code FogColor} = {@code fogColorHex}) and the swim
     * {@code MovementSettings} driven by {@code physics} ({@code SinkSpeed},
     * {@code HorizontalSpeedMultiplier}). Other movement fields keep vanilla Water values.
     */
    public static String fluidFxJson(String blockId, String fogColorHex, FluidPhysics physics) {
        return """
            {
              "Fog": "Color",
              "FogColor": "%s",
              "FogDistance": [ -32, 6 ],
              "ColorsSaturation": 1.0,
              "ColorsFilter": [ 1, 1, 1 ],
              "DistortionAmplitude": 5,
              "DistortionFrequency": 6,
              "MovementSettings": {
                "SwimUpSpeed": 2.5,
                "SwimDownSpeed": -2.5,
                "HorizontalSpeedMultiplier": %s,
                "SinkSpeed": %s,
                "FieldOfViewMultiplier": 1,
                "EntryVelocityMultiplier": 1
              }
            }
            """.formatted(fogColorHex, num(physics.horizontalSpeedMultiplier()), num(physics.sinkSpeed()));
    }

    /**
     * The placement item, modeled on vanilla {@code Fluid_Water.json}: pressing Secondary places the
     * {@code blockId + "_Source"} pool. The display name is NOT a parameter: the item JSON references
     * {@code server.items.<itemId>.name}, and the world-fluid generator writes that lang line itself
     * (from {@code FluidForm.displayName()}). {@code MaxStack} 1, category {@code Blocks.Fluids},
     * material tag {@code Fluid}.
     */
    public static String placementItemJson(String itemId, String blockId, String iconPath) {
        return """
            {
              "TranslationProperties": { "Name": "server.items.%s.name" },
              "Icon": "%s",
              "Categories": [ "Blocks.Fluids" ],
              "Interactions": {
                "Secondary": {
                  "Interactions": [
                    { "Type": "PlaceFluid", "FluidToPlace": "%s_Source" }
                  ]
                }
              },
              "PlayerAnimationsId": "Block",
              "Tags": {
                "Material": [ "Fluid" ]
              },
              "MaxStack": 1
            }
            """.formatted(itemId, iconPath, blockId);
    }

    /** Render a double as a JSON-valid numeric literal (e.g. {@code 1.0}, {@code -1.35}). */
    private static String num(double d) {
        return Double.toString(d);
    }

    /** Left-pad every line of {@code text} with {@code pad} (for embedding fragments in a block). */
    private static String indent(String text, String pad) {
        return pad + text.replace("\n", "\n" + pad);
    }
}
