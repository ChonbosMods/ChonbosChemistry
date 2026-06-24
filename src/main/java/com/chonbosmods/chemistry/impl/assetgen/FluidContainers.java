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
     * How a filled container's Secondary interaction behaves (BUG 1):
     * <ul>
     *   <li>{@link #DRINK}: route to {@code Root_Secondary_Consume_Drink} + emit the
     *       {@code InteractionVars} (Effect / ConsumeSFX / ConsumedSFX / DurabilityModify). The
     *       container is consumed/placeable as a deco block exactly like vanilla mug/tankard.</li>
     *   <li>{@link #POUR}: route to a {@code PlaceFluid} that spills the held fluid into the world
     *       (modeled on the vanilla bucket {@code Filled_Water.Secondary}), then ModifyInventory
     *       (durability -1, BrokenItem = empty container). NO drink {@code InteractionVars} : the
     *       poured fluid block carries the contact hazard, not the drink.</li>
     * </ul>
     */
    public enum FillMode { DRINK, POUR }

    /**
     * One container's config.
     *
     * @param id              item id (e.g. {@code "Deco_Mug"})
     * @param displayName     vanilla item display name, verbatim from {@code server.lang}
     *        ({@code items.<id>.name} : e.g. "Wooden Mug", "Wooden Bucket", "Tankard"). The filled
     *        item name is generated as {@code <displayName> (<FluidName>)}, mimicking vanilla
     *        ({@code Wooden Bucket (Water)}).
     * @param model           filled-state model path under the asset pack {@code Common/}
     * @param animation       filled-state {@code CustomModelAnimation} (nullable: mug only)
     * @param fillMode        how the filled state's Secondary behaves : {@link FillMode#DRINK} (mug,
     *        tankard) or {@link FillMode#POUR} (bucket). See {@link FillMode}.
     * @param masterFile      neutral grayscale master PNG (under {@code assets-src/containers/})
     * @param liquidMask      tintable rectangle in master-texture pixels
     * @param iconMasterFile  neutral grayscale CONTAINER ICON master PNG (under {@code assets-src/
     *        containers/}) : the vanilla {@code <id>_Water} icon with its liquid pixels desaturated.
     *        Tinted per substance into the filled-state {@code Icon} (BUG 4).
     * @param iconMaskFile    per-pixel liquid mask PNG for the icon master (opaque only on liquid
     *        pixels), consumed by {@link SubstanceIcon#tint}.
     * @param tintedTextureDir directory of the per-substance tinted texture under {@code Common/}
     * @param tintedTexturePrefix base name of the tinted texture (a {@code _<blockId>.png} is appended)
     * @param brokenItem      item returned when the container is emptied
     * @param vanillaTemplate captured vanilla item JSON used as the override base
     * @param itemOutputPath  real asset-pack path of the overridden item JSON, under the output root
     *        (e.g. {@code Server/Item/Items/Deco/Deco_Mug.json}). The override replaces the vanilla
     *        item at this exact path.
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
        String displayName,
        String model,
        String animation,
        FillMode fillMode,
        String masterFile,
        LiquidMask liquidMask,
        String iconMasterFile,
        String iconMaskFile,
        String tintedTextureDir,
        String tintedTexturePrefix,
        String brokenItem,
        String vanillaTemplate,
        String itemOutputPath,
        List<String> preservedStates,
        String filledBlockTypeExtras,
        String filledStateExtras) {

        /** Per-substance tinted texture path, e.g. {@code Blocks/Miscellaneous/Mug_Texture_<blockId>.png}. */
        public String tintedTexturePath(String blockId) {
            return tintedTextureDir + tintedTexturePrefix + "_" + blockId + ".png";
        }

        /** Per-substance tinted container-icon path under {@code Common/}, e.g. {@code Icons/ItemsGenerated/Deco_Mug_<blockId>.png}. */
        public String iconPath(String blockId) {
            return "Icons/ItemsGenerated/" + id + "_" + blockId + ".png";
        }

        /** Whether this container's filled state plays a fill animation (mug only). */
        public boolean hasAnimation() {
            return animation != null;
        }

        /** Whether the filled state pours its fluid out (bucket) instead of being drunk (mug/tankard). */
        public boolean pours() {
            return fillMode == FillMode.POUR;
        }

        /**
         * The vanilla-style filled-item DISPLAY NAME: {@code <displayName> (<fluidName>)}, e.g.
         * {@code Wooden Bucket (Hydrogen peroxide)}. Mirrors vanilla
         * ({@code items.Container_Bucket_Water.name = Wooden Bucket (Water)}). {@code fluidName} is
         * the fluid's {@code FluidForm.displayName()} verbatim.
         */
        public String filledName(String fluidName) {
            return displayName + " (" + fluidName + ")";
        }

        /**
         * The vanilla-style filled-item DESCRIPTION, mimicking the vanilla bucket style
         * ({@code Contains <color is="#ffffff">Water</color> that can be placed in the world.}).
         */
        public String filledDescription(String fluidName) {
            return "Contains <color is=\"#ffffff\">" + fluidName + "</color>"
                + (pours() ? " that can be placed in the world." : " that can be drunk.");
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
            "Wooden Mug",
            "Blocks/Miscellaneous/Mug.blockymodel",
            "Blocks/Miscellaneous/Mug_Full.blockyanim",
            FillMode.DRINK,
            "mug_master.png",
            new LiquidMask(37, 41, 12, 12),
            "Deco_Mug_icon_master.png",
            "Deco_Mug_icon_mask.png",
            "Blocks/Miscellaneous/",
            "Mug_Texture",
            "Deco_Mug",
            "Deco_Mug.vanilla.json",
            "Server/Item/Items/Deco/Deco_Mug.json",
            List.of("Filled_Water"),
            MUG_BLOCKTYPE_EXTRAS,
            MUG_STATE_EXTRAS),
        new FluidContainer(
            "Deco_Tankard",
            "Tankard",
            "Blocks/Miscellaneous/Tankard.blockymodel",
            null,
            FillMode.DRINK,
            "tankard_master.png",
            new LiquidMask(2, 2, 6, 6),
            "Deco_Tankard_icon_master.png",
            "Deco_Tankard_icon_mask.png",
            "Blocks/Miscellaneous/",
            "Tankard_Texture",
            "Deco_Tankard",
            "Deco_Tankard.vanilla.json",
            "Server/Item/Items/Deco/Deco_Tankard.json",
            List.of("Filled_Water"),
            TANKARD_BLOCKTYPE_EXTRAS,
            TANKARD_STATE_EXTRAS),
        new FluidContainer(
            "Container_Bucket",
            "Wooden Bucket",
            "Blocks/Decorative_Sets/Village/Bucket_Full.blockymodel",
            null,
            FillMode.POUR,
            "bucket_master.png",
            new LiquidMask(4, 5, 24, 24),
            "Container_Bucket_icon_master.png",
            "Container_Bucket_icon_mask.png",
            "Blocks/Decorative_Sets/Village/",
            "Bucket_Texture",
            "Container_Bucket",
            "Container_Bucket.vanilla.json",
            "Server/Item/Items/Container/Container_Bucket.json",
            List.of("Filled_Water", "Filled_Milk", "Filled_Mosshorn_Milk"),
            BUCKET_BLOCKTYPE_EXTRAS,
            BUCKET_STATE_EXTRAS));
}
