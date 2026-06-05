package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.api.substance.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class SubstanceIconTest {

    private static BufferedImage argb(int w, int h, int fill) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, fill);
            }
        }
        return img;
    }

    @Test
    void tintRecolorsOnlyMaskedPixels() {
        // a rendered jar where every pixel is white, and a mask marking the bottom-left as liquid
        BufferedImage master = argb(2, 2, 0xFFFFFFFF);
        BufferedImage mask = argb(2, 2, 0x00000000);
        mask.setRGB(0, 1, 0xFF00FF00); // (0,1) is liquid (opaque in mask)

        BufferedImage out = SubstanceIcon.tint(master, mask, new Color(200, 30, 40));

        assertEquals(0xFFC81E28, out.getRGB(0, 1)); // liquid pixel multiplied by the color
        assertEquals(0xFFFFFFFF, out.getRGB(1, 0)); // glass pixel untouched
    }

    @Test
    void tintBoostsLiquidPixelForGlowingTier() {
        // same white master * Color(200,30,40) = (0xC8,0x1E,0x28), then FAINT boost (x115/100):
        // 200*115/100=230=0xE6, 30*115/100=34=0x22, 40*115/100=46=0x2E -> exceeds the unboosted value
        BufferedImage master = argb(2, 2, 0xFFFFFFFF);
        BufferedImage mask = argb(2, 2, 0x00000000);
        mask.setRGB(0, 1, 0xFF00FF00); // (0,1) is liquid

        BufferedImage out = SubstanceIcon.tint(master, mask, new Color(200, 30, 40), GlowTier.FAINT);

        assertEquals(0xFFE6222E, out.getRGB(0, 1)); // liquid pixel multiplied then boosted
        assertEquals(0xFFFFFFFF, out.getRGB(1, 0)); // glass pixel untouched (outside mask)
    }

    @Test
    void tintBlendsLiquidPixelTowardWhiteForFierceTier() {
        // same white master * Color(200,30,40) = (0xC8,0x1E,0x28), then FIERCE blend c+(255-c)*35/100:
        // r: 200+(255-200)*35/100 = 200+55*35/100 = 200+19 = 219 = 0xDB
        // g: 30+(255-30)*35/100  = 30+225*35/100  = 30+78  = 108 = 0x6C
        // b: 40+(255-40)*35/100  = 40+215*35/100  = 40+75  = 115 = 0x73 -> 0xFFDB6C73
        BufferedImage master = argb(2, 2, 0xFFFFFFFF);
        BufferedImage mask = argb(2, 2, 0x00000000);
        mask.setRGB(0, 1, 0xFF00FF00); // (0,1) is liquid

        BufferedImage out = SubstanceIcon.tint(master, mask, new Color(200, 30, 40), GlowTier.FIERCE);

        assertEquals(0xFFDB6C73, out.getRGB(0, 1)); // liquid pixel multiplied then blended toward white
        assertEquals(0xFFFFFFFF, out.getRGB(1, 0)); // glass pixel untouched (outside mask)
    }

    @Test
    void renderProducesSquareIconOfRequestedSize() {
        BufferedImage master = argb(8, 8, 0xFFFFFFFF);
        BufferedImage mask = argb(8, 8, 0x00000000);

        BufferedImage icon = SubstanceIcon.render(master, mask, new Color(10, 20, 30), 64);

        assertEquals(64, icon.getWidth());
        assertEquals(64, icon.getHeight());
    }
}
