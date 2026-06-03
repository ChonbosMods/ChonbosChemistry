package com.chonbosmods.chemistry.impl.texture;

import com.chonbosmods.chemistry.api.substance.Color;
import java.awt.image.BufferedImage;

/**
 * Generates a per-substance container texture by multiplying ONLY the liquid region of a neutral
 * white master image by a substance {@link Color}.
 *
 * <p>Hytale has no runtime texture tint, so substance color must be baked into a real texture file.
 * The master keeps the liquid as a grayscale luminance ramp, so {@code out = master * (color/255)}
 * preserves the painted shading while producing the substance hue. Glass/cork pixels (outside the
 * mask) are copied unchanged.
 */
public final class SubstanceLiquidTinter {

    private SubstanceLiquidTinter() {}

    public static BufferedImage tint(BufferedImage master, LiquidMask mask, Color color) {
        int w = master.getWidth();
        int h = master.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = master.getRGB(x, y);
                if (mask.contains(x, y)) {
                    int a = (argb >>> 24) & 0xFF;
                    int r = ((argb >> 16) & 0xFF) * color.r() / 255;
                    int g = ((argb >> 8) & 0xFF) * color.g() / 255;
                    int b = (argb & 0xFF) * color.b() / 255;
                    argb = (a << 24) | (r << 16) | (g << 8) | b;
                }
                out.setRGB(x, y, argb);
            }
        }
        return out;
    }
}
