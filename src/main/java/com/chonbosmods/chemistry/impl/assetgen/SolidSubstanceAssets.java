package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Substance;

/**
 * Pure asset-identity + item-JSON rendering for solid-substance container items.
 *
 * <p>Each solid substance becomes one item that reuses the shared {@code Solid.blockymodel} jar but
 * points {@code Texture} at its own generated, color-tinted PNG. A single placeholder icon is shared
 * for now (per-substance icons are a follow-up using the same tint trick on a rendered jar icon).
 */
public final class SolidSubstanceAssets {

    public static final String MODEL = "Items/Chemistry/Solid.blockymodel";
    public static final String CATEGORY = "Blocks.Deco";

    private SolidSubstanceAssets() {}

    /**
     * Asset/item id for a substance, keyed on its (unique) name. Formulas are NOT unique — polymers
     * like Cellulose and Starch share {@code (C6H10O5)n} — so the name is the safe identity.
     */
    public static String assetId(Substance s) {
        return assetId(s instanceof Element, s.name());
    }

    /**
     * Asset/item id, namespaced by kind, sanitized to {@code [A-Za-z0-9_]} with each underscore
     * segment title-cased — Hytale's AssetStore requires PascalCase-per-segment item keys.
     */
    public static String assetId(boolean isElement, String identity) {
        String kind = isElement ? "Element" : "Compound";
        String[] parts = identity.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "").split("_");
        StringBuilder safe = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (safe.length() > 0) {
                safe.append('_');
            }
            safe.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return "Chem_Solid_" + kind + "_" + safe;
    }

    /** Texture path (relative to the asset pack {@code Common/}) for a given item id. */
    public static String texturePath(String id) {
        return "Items/Chemistry/Substance_Textures/" + id + ".png";
    }

    /**
     * The {@code Server/Item/Items/...} JSON for one solid-substance item, modeled on the vanilla
     * {@code Deco_Mug}: a placeable model-block so the jar renders in-hand and can be placed. The
     * substance color rides on {@code BlockType.CustomModelTexture}. {@code Icon} is omitted so the
     * engine renders a per-substance icon from the tinted model.
     */
    public static String itemJson(String id, String texturePath) {
        return """
            {
              "TranslationProperties": { "Name": "server.items.%s.name" },
              "Categories": ["%s"],
              "Icon": "Icons/ItemsGenerated/%s.png",
              "Interactions": { "Primary": "Block_Primary", "Secondary": "Block_Secondary" },
              "BlockType": {
                "Material": "Solid",
                "DrawType": "Model",
                "Opacity": "Transparent",
                "CustomModel": "%s",
                "CustomModelTexture": [ { "Weight": 1, "Texture": "%s" } ],
                "CustomModelScale": 0.45,
                "HitboxType": "Food_Medium",
                "Flags": {},
                "RandomRotation": "YawStep1",
                "Gathering": { "Harvest": {}, "Soft": { "IsWeaponBreakable": false } },
                "BlockParticleSetId": "Stone",
                "BlockSoundSetId": "Stone"
              },
              "PlayerAnimationsId": "Item",
              "Scale": 1.0
            }
            """.formatted(id, CATEGORY, id, MODEL, texturePath);
    }
}
