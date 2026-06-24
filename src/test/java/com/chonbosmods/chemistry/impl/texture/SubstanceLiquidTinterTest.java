package com.chonbosmods.chemistry.impl.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.api.substance.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class SubstanceLiquidTinterTest {

    private static BufferedImage solid(int w, int h, int argb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }

    @Test
    void tintsPixelsInsideMaskAndLeavesOutsideUnchanged() {
        BufferedImage master = solid(4, 4, 0xFFFFFFFF); // all white, opaque
        LiquidMask mask = new LiquidMask(1, 1, 2, 2); // covers (1,1)..(2,2)

        BufferedImage out = SubstanceLiquidTinter.tint(master, mask, new Color(255, 0, 0));

        // inside the mask: white * red == red, alpha preserved
        assertEquals(0xFFFF0000, out.getRGB(1, 1));
        // outside the mask: untouched white
        assertEquals(0xFFFFFFFF, out.getRGB(0, 0));
    }

    @Test
    void multiplyPreservesShading() {
        // a mid-gray liquid pixel becomes a darker shade of the color (the painted AO survives)
        BufferedImage master = solid(2, 2, 0xFF808080); // gray 128
        LiquidMask mask = new LiquidMask(0, 0, 2, 2);

        BufferedImage out = SubstanceLiquidTinter.tint(master, mask, new Color(200, 100, 50));

        int argb = out.getRGB(0, 0); // 128 * c / 255
        assertEquals(0xFF, (argb >>> 24) & 0xFF);
        assertEquals(100, (argb >> 16) & 0xFF);
        assertEquals(50, (argb >> 8) & 0xFF);
        assertEquals(25, argb & 0xFF);
    }

    @Test
    void transparentLiquidPixelsStayTransparent() {
        BufferedImage master = solid(2, 2, 0x00FFFFFF); // fully transparent
        LiquidMask mask = new LiquidMask(0, 0, 2, 2);

        BufferedImage out = SubstanceLiquidTinter.tint(master, mask, new Color(255, 0, 0));

        assertEquals(0, (out.getRGB(0, 0) >>> 24) & 0xFF);
    }

    @Test
    void translucentLiquidStaysTranslucentAfterTint() {
        // The vanilla-derived fluid master is translucent (alpha 224); tinting must preserve that
        // exact alpha so the generated world-fluid surface keeps the translucent water look.
        BufferedImage master = solid(2, 2, (224 << 24) | 0xFFFFFF); // grayscale, alpha 224
        LiquidMask mask = new LiquidMask(0, 0, 2, 2);

        BufferedImage out = SubstanceLiquidTinter.tint(master, mask, new Color(200, 30, 20));

        assertEquals(224, (out.getRGB(0, 0) >>> 24) & 0xFF);
    }

    @Test
    void doesNotMutateMaster() {
        BufferedImage master = solid(2, 2, 0xFFFFFFFF);
        LiquidMask mask = new LiquidMask(0, 0, 2, 2);

        SubstanceLiquidTinter.tint(master, mask, new Color(0, 0, 0));

        assertEquals(0xFFFFFFFF, master.getRGB(0, 0));
    }
}
