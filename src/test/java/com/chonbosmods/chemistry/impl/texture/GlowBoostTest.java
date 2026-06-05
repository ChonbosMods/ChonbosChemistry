package com.chonbosmods.chemistry.impl.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.impl.assetgen.GlowTier;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class GlowBoostTest {

    private static BufferedImage onePixel(int rgb) {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFF000000 | rgb); // inside mask
        img.setRGB(1, 0, 0xFF000000 | rgb); // outside mask
        return img;
    }

    private static final LiquidMask MASK = new LiquidMask(0, 0, 1, 1);

    @Test
    void noneIsIdentity() {
        BufferedImage out = GlowBoost.apply(onePixel(0x646464), MASK, GlowTier.NONE);
        assertEquals(0x646464, out.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void faintScales115Percent() {
        BufferedImage out = GlowBoost.apply(onePixel(0x646464), MASK, GlowTier.FAINT);
        assertEquals(0x737373, out.getRGB(0, 0) & 0xFFFFFF); // 100*115/100 = 115 = 0x73
        assertEquals(0x646464, out.getRGB(1, 0) & 0xFFFFFF); // outside mask untouched
    }

    @Test
    void strongScales135PercentAndClamps() {
        BufferedImage out = GlowBoost.apply(onePixel(0xC8C8C8), MASK, GlowTier.STRONG);
        assertEquals(0xFFFFFF, out.getRGB(0, 0) & 0xFFFFFF); // 200*135/100 = 270 -> 255
    }

    @Test
    void fierceBlendsTowardWhite() {
        BufferedImage out = GlowBoost.apply(onePixel(0x006400), MASK, GlowTier.FIERCE);
        // 0 + 255*35/100 = 89 = 0x59 ; 100 + 155*35/100 = 154 (100+54) = 0x9A
        assertEquals(0x599A59, out.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void preservesAlpha() {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0x80006400); // inside mask, half alpha
        img.setRGB(1, 0, 0x80006400); // outside mask
        BufferedImage out = GlowBoost.apply(img, MASK, GlowTier.FIERCE);
        // alpha 0x80 preserved, rgb blended toward white-hot like fierceBlendsTowardWhite
        assertEquals(0x80599A59, out.getRGB(0, 0));
    }

    @Test
    void boostKeepsWhiteHotAndZeroFloor() {
        assertEquals(255, GlowBoost.boost(255, GlowTier.FIERCE)); // already white-hot
        assertEquals(0, GlowBoost.boost(0, GlowTier.FAINT)); // 0*115/100 = 0
        assertEquals(89, GlowBoost.boost(0, GlowTier.FIERCE)); // 0 + 255*35/100 = 89
    }

    @Test
    void doesNotMutateSrc() {
        BufferedImage src = onePixel(0x646464);
        GlowBoost.apply(src, MASK, GlowTier.STRONG);
        assertEquals(0xFF646464, src.getRGB(0, 0));
    }
}
