package com.chonbosmods.chemistry.impl.assetgen;

/** Shared id sanitization: PascalCase-per-underscore-segment, as Hytale AssetStore keys require. */
public final class AssetIds {

    private AssetIds() {}

    public static String pascalSegments(String identity) {
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
        return safe.toString();
    }
}
