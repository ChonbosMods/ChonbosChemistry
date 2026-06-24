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
        List<String> preservedStates) {

        /** Per-substance tinted texture path, e.g. {@code Blocks/Miscellaneous/Mug_Texture_<blockId>.png}. */
        public String tintedTexturePath(String blockId) {
            return tintedTextureDir + tintedTexturePrefix + "_" + blockId + ".png";
        }

        /** Whether this container's filled state plays a fill animation (mug only). */
        public boolean hasAnimation() {
            return animation != null;
        }
    }

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
            List.of("Filled_Water")),
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
            List.of("Filled_Water")),
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
            List.of("Filled_Water", "Filled_Milk", "Filled_Mosshorn_Milk")));
}
