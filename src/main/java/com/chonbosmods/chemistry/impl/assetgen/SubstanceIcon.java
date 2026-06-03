package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.substance.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Builds a model-rendered per-substance inventory icon by recoloring a single pre-rendered jar
 * image. {@code icon_master.png} is the white-liquid jar rendered in Blockbench; {@code
 * icon_liquid_mask.png} is the same render with only the liquid node visible (its silhouette marks
 * which icon pixels are liquid). Multiplying the masked pixels by the substance color reproduces the
 * in-world jar as a 2D icon — without rendering 205 times.
 */
public final class SubstanceIcon {

    private SubstanceIcon() {}

    /** Full-resolution copy of {@code master} with mask-marked (liquid) pixels multiplied by {@code color}. */
    public static BufferedImage tint(BufferedImage master, BufferedImage mask, Color color) {
        int w = master.getWidth();
        int h = master.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int px = master.getRGB(x, y);
                int ma = (px >>> 24) & 0xFF;
                int ka = (mask.getRGB(x, y) >>> 24) & 0xFF;
                if (ka > 8 && ma > 0) {
                    int r = ((px >> 16) & 0xFF) * color.r() / 255;
                    int g = ((px >> 8) & 0xFF) * color.g() / 255;
                    int b = (px & 0xFF) * color.b() / 255;
                    px = (ma << 24) | (r << 16) | (g << 8) | b;
                }
                out.setRGB(x, y, px);
            }
        }
        return out;
    }

    /** Tinted, square-cropped (to the jar silhouette, with padding), and scaled to {@code size} px. */
    public static BufferedImage render(BufferedImage master, BufferedImage mask, Color color, int size) {
        BufferedImage tinted = tint(master, mask, color);
        int w = tinted.getWidth();
        int h = tinted.getHeight();
        int minx = w;
        int miny = h;
        int maxx = -1;
        int maxy = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((master.getRGB(x, y) >>> 24) & 0xFF) > 0) {
                    minx = Math.min(minx, x);
                    maxx = Math.max(maxx, x);
                    miny = Math.min(miny, y);
                    maxy = Math.max(maxy, y);
                }
            }
        }
        if (maxx < 0) {
            minx = 0;
            miny = 0;
            maxx = w - 1;
            maxy = h - 1;
        }
        int side = Math.max(maxx - minx + 1, maxy - miny + 1);
        side += side / 10; // a little breathing room
        int sx = (minx + maxx) / 2 - side / 2;
        int sy = (miny + maxy) / 2 - side / 2;

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(tinted, 0, 0, size, size, sx, sy, sx + side, sy + side, null);
        g.dispose();
        return out;
    }
}
