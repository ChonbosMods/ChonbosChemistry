package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.impl.texture.LiquidMask;
import java.util.List;

/**
 * Per-container config registry for the substance-fluid container overrides (Deco_Mug, Deco_Tankard,
 * Container_Bucket). Each {@link FluidContainer} carries everything the next-task generator and the
 * {@link FluidAssets#filledStateJson} renderer need: the model + animation the filled state shows,
 * the neutral grayscale master + {@link LiquidMask} the substance tinter multiplies into, the
 * per-substance tinted texture path, the {@code BrokenItem} (empty-return) id, the captured vanilla
 * override base, and the vanilla {@code Filled_*} states that must be preserved.
 *
 * <p>All values are taken verbatim from {@code assets-src/containers/MASKS.md}.
 */
public final class FluidContainers {

    private FluidContainers() {}

    /**
     * One container's config.
     *
     * @param id              item id (e.g. {@code "Deco_Mug"})
     * @param model           filled-state model path under the asset pack {@code Common/}
     * @param animation       filled-state {@code CustomModelAnimation} (nullable: mug only)
     * @param masterFile      neutral grayscale master PNG (under {@code assets-src/containers/})
     * @param liquidMask      tintable rectangle in master-texture pixels
     * @param tintedTextureDir directory of the per-substance tinted texture under {@code Common/}
     * @param tintedTexturePrefix base name of the tinted texture (a {@code _<blockId>.png} is appended)
     * @param brokenItem      item returned when the container is emptied
     * @param vanillaTemplate captured vanilla item JSON used as the override base
     * @param preservedStates vanilla {@code Filled_*} states the override must keep
     * @param filledBlockTypeExtras JSON fragment of the container's vanilla filled-state {@code BlockType}
     *        render/placement fields that we carry verbatim (everything EXCEPT our intentional swaps:
     *        {@code DrawType}, {@code Opacity}, {@code CustomModel}, {@code CustomModelTexture},
     *        {@code CustomModelAnimation}). Sourced verbatim from the captured vanilla
     *        {@code Filled_Water.BlockType}. Each line leads with a comma so it splices after our fields.
     * @param filledStateExtras JSON fragment of the container's vanilla filled-state STATE-LEVEL fields
     *        we carry verbatim ({@code Scale}, {@code IconProperties}). Sourced from the captured vanilla
     *        {@code Filled_Water}. Each line leads with a comma. The per-substance {@code Icon} is supplied
     *        separately by the renderer (it is the only state-level field that varies per fluid).
     */
    public record FluidContainer(
        String id,
        String model,
        String animation,
        String masterFile,
        LiquidMask liquidMask,
        String tintedTextureDir,
        String tintedTexturePrefix,
        String brokenItem,
        String vanillaTemplate,
        List<String> preservedStates,
        String filledBlockTypeExtras,
        String filledStateExtras) {

        /** Per-substance tinted texture path, e.g. {@code Blocks/Miscellaneous/Mug_Texture_<blockId>.png}. */
        public String tintedTexturePath(String blockId) {
            return tintedTextureDir + tintedTexturePrefix + "_" + blockId + ".png";
        }

        /** Whether this container's filled state plays a fill animation (mug only). */
        public boolean hasAnimation() {
            return animation != null;
        }
    }

    // ---- Per-container filled-state render fields, verbatim from each vanilla Filled_Water ----
    // Carried so each per-substance Filled state looks/places exactly like vanilla (minus our swaps:
    // DrawType/Opacity/CustomModel/CustomModelTexture/CustomModelAnimation). Every line leads with a
    // comma so it splices after the renderer's fixed BlockType / state fields.

    /** Mug vanilla Filled_Water BlockType extras (minus the intentional swaps). */
    private static final String MUG_BLOCKTYPE_EXTRAS = """
        ,
                "CustomModelScale": 0.6,
                "HitboxType": "Food_Medium",
                "Flags": { "IsStackable": false },
                "RandomRotation": "YawStep1",
                "BlockSoundSetId": "Wood",
                "PhysicalMaterialId": "Wood",
                "Gathering": { "Harvest": {}, "Soft": { "IsWeaponBreakable": false } }""";
    /** Mug vanilla Filled_Water state-level extras (Icon is per-substance, supplied separately). */
    private static final String MUG_STATE_EXTRAS = """
        ,
              "Scale": 1.43,
              "IconProperties": {
                "Scale": 0.8,
                "Rotation": [ 22.5, 278.0, 328.0 ],
                "Translation": [ 0.0, -9.0 ]
              }""";

    /** Tankard vanilla Filled_Water BlockType extras (minus the intentional swaps). */
    private static final String TANKARD_BLOCKTYPE_EXTRAS = """
        ,
                "CustomModelScale": 1.2,
                "HitboxType": "Food_Medium",
                "Flags": { "IsStackable": false },
                "RandomRotation": "YawStep1",
                "BlockSoundSetId": "Wood",
                "PhysicalMaterialId": "Wood",
                "Gathering": { "Harvest": {}, "Soft": { "IsWeaponBreakable": false } }""";
    /** Tankard vanilla Filled_Water state-level extras. */
    private static final String TANKARD_STATE_EXTRAS = """
        ,
              "Scale": 1.43,
              "IconProperties": {
                "Scale": 1.3,
                "Rotation": [ 22.5, 24.0, 22.5 ],
                "Translation": [ 0.305, -6.895 ]
              }""";

    /** Bucket vanilla Filled_Water BlockType extras (minus the intentional swaps). */
    private static final String BUCKET_BLOCKTYPE_EXTRAS = """
        ,
                "Material": "Empty",
                "CustomModelScale": 0.75,
                "RandomRotation": "YawStep1",
                "Gathering": { "Soft": { "IsWeaponBreakable": true } },
                "HitboxType": "Plant_Full",
                "BlockParticleSetId": "Wood",
                "BlockSoundSetId": "Wood",
                "PhysicalMaterialId": "Wood",
                "Support": { "Down": [ { "FaceType": "Full" } ] }""";
    /** Bucket vanilla Filled_Water state-level extras (no Scale; IconProperties only). */
    private static final String BUCKET_STATE_EXTRAS = """
        ,
              "IconProperties": {
                "Scale": 0.58823,
                "Rotation": [ 0.0, 121.0, 25.0 ],
                "Translation": [ -3.0, -18.0 ]
              }""";

    /** The three water-capable containers, configured from {@code MASKS.md}. */
    public static final List<FluidContainer> ALL = List.of(
        new FluidContainer(
            "Deco_Mug",
            "Blocks/Miscellaneous/Mug.blockymodel",
            "Blocks/Miscellaneous/Mug_Full.blockyanim",
            "mug_master.png",
            new LiquidMask(37, 41, 12, 12),
            "Blocks/Miscellaneous/",
            "Mug_Texture",
            "Deco_Mug",
            "Deco_Mug.vanilla.json",
            List.of("Filled_Water"),
            MUG_BLOCKTYPE_EXTRAS,
            MUG_STATE_EXTRAS),
        new FluidContainer(
            "Deco_Tankard",
            "Blocks/Miscellaneous/Tankard.blockymodel",
            null,
            "tankard_master.png",
            new LiquidMask(2, 2, 6, 6),
            "Blocks/Miscellaneous/",
            "Tankard_Texture",
            "Deco_Tankard",
            "Deco_Tankard.vanilla.json",
            List.of("Filled_Water"),
            TANKARD_BLOCKTYPE_EXTRAS,
            TANKARD_STATE_EXTRAS),
        new FluidContainer(
            "Container_Bucket",
            "Blocks/Decorative_Sets/Village/Bucket_Full.blockymodel",
            null,
            "bucket_master.png",
            new LiquidMask(4, 5, 24, 24),
            "Blocks/Decorative_Sets/Village/",
            "Bucket_Texture",
            "Container_Bucket",
            "Container_Bucket.vanilla.json",
            List.of("Filled_Water", "Filled_Milk", "Filled_Mosshorn_Milk"),
            BUCKET_BLOCKTYPE_EXTRAS,
            BUCKET_STATE_EXTRAS));
}
