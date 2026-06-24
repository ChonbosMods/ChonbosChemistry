package com.chonbosmods.chemistry.impl.assetgen;

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
}
