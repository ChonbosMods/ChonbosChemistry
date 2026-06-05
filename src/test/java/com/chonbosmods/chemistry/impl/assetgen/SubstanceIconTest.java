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
    void renderProducesSquareIconOfRequestedSize() {
        BufferedImage master = argb(8, 8, 0xFFFFFFFF);
        BufferedImage mask = argb(8, 8, 0x00000000);

        BufferedImage icon = SubstanceIcon.render(master, mask, new Color(10, 20, 30), 64);

        assertEquals(64, icon.getWidth());
        assertEquals(64, icon.getHeight());
    }
}
