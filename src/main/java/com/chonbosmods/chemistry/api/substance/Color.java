package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/**
 * The first-class, required substance color: the generative key for texture tinting (design doc
 * §7), not a cosmetic field. Serialized as a {@code "#RRGGBB"} hex string.
 *
 * <p>A record rather than a bean: it is adapted from a single string via {@link StringMappedCodec},
 * not field-by-field {@code BuilderCodec}, so the beans-not-records constraint does not apply.
 */
public record Color(int r, int g, int b) {

    public static final Codec<Color> CODEC = new StringMappedCodec<>(Color::fromHex, Color::toHex);

    public static Color fromHex(String hex) {
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if (digits.length() != 6) {
            throw new IllegalArgumentException("Expected #RRGGBB hex color, got: '" + hex + "'");
        }
        int rgb = Integer.parseInt(digits, 16);
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    public String toHex() {
        return String.format("#%02X%02X%02X", r, g, b);
    }

    public int packedRgb() {
        return (r << 16) | (g << 8) | b;
    }
}
