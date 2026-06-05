package com.chonbosmods.chemistry.impl.texture;

import com.chonbosmods.chemistry.impl.assetgen.GlowTier;
import java.awt.image.BufferedImage;

/**
 * Tier-based brightness boost on the liquid region of an already-tinted texture (design doc
 * section 4 table): FAINT scales 1.15x, STRONG 1.35x, FIERCE blends 35% toward white-hot.
 * Pixels outside the mask are untouched and alpha is preserved. Returns a new
 * {@link BufferedImage#TYPE_INT_ARGB} image.
 */
public final class GlowBoost {

    private GlowBoost() {}

    public static BufferedImage apply(BufferedImage src, LiquidMask mask, GlowTier tier) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                if (tier != GlowTier.NONE && mask.contains(x, y)) {
                    int a = (argb >>> 24) & 0xFF;
                    argb = (a << 24)
                        | (boost((argb >> 16) & 0xFF, tier) << 16)
                        | (boost((argb >> 8) & 0xFF, tier) << 8)
                        | boost(argb & 0xFF, tier);
                }
                out.setRGB(x, y, argb);
            }
        }
        return out;
    }

    /**
     * Per-channel brightness boost for a single 0..255 value. Public so the icon renderer
     * (which marks its own liquid pixels) can reuse the exact same math: the tier curve must
     * live in exactly one place.
     */
    public static int boost(int channel, GlowTier tier) {
        int v = switch (tier) {
            case NONE -> channel;
            case FAINT -> channel * 115 / 100;
            case STRONG -> channel * 135 / 100;
            case FIERCE -> channel + (255 - channel) * 35 / 100;
        };
        return Math.min(255, v);
    }
}
