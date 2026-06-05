package com.chonbosmods.chemistry.impl.texture;

/**
 * The pixel rectangle of a container model's liquid/contents region, in texture pixel coordinates
 * (top-left origin). This is the only area a substance color is allowed to tint; everything else
 * (glass, cork) is left untouched.
 *
 * <p>For the potion-derived "solid" jar (32x64 texture) the {@code Liquid} node maps to
 * {@code x:15..29, y:0..20}, i.e. {@code new LiquidMask(15, 0, 14, 20)}.
 */
public record LiquidMask(int x, int y, int width, int height) {

    public boolean contains(int px, int py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }
}
