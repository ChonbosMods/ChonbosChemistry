package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.substance.Color;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Phase;
import com.chonbosmods.chemistry.api.substance.Substance;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import com.chonbosmods.chemistry.impl.texture.FluidPipeCoreMask;
import com.chonbosmods.chemistry.impl.texture.SubstanceLiquidTinter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Build-time asset generator: for every LIQUID element/compound, multiplies its color into the white
 * core islands of the FluidPipe {@code fluidpipe_on.png} atlas (via {@link FluidPipeCoreMask}) and
 * emits one {@code fluidpipe_on_<kind>_<name>.png} per substance.
 *
 * <p>These are the swap targets for the transport network instance's per-substance
 * {@code CustomModelTexture} states: when a pipe segment carries bromine, the renderer swaps to
 * {@code fluidpipe_on_element_bromine.png}. The shared OFF state (the empty-core
 * {@code fluidpipe_off}) is untouched, and the steel shell stays neutral because the mask excludes
 * the straight pipe's shell/core overlap.
 *
 * <p>Run via the {@code generateFluidPipeTextures} Gradle task:
 * {@code args = [<fluidpipeOnPng>, <outDir>]}.
 */
public final class FluidPipeTextureGenerator {

    private FluidPipeTextureGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <fluidpipeOnPng> <outDir>");
        }
        BufferedImage atlas = ImageIO.read(Path.of(args[0]).toFile());
        Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);

        InMemorySubstanceRegistry registry = InMemorySubstanceRegistry.loadFromResources();
        List<Substance> liquids = new ArrayList<>();
        registry.elements().stream().filter(s -> s.phase() == Phase.LIQUID).forEach(liquids::add);
        registry.compounds().stream().filter(s -> s.phase() == Phase.LIQUID).forEach(liquids::add);

        Set<String> seen = new HashSet<>();
        for (Substance s : liquids) {
            String kind = s instanceof Element ? "element" : "compound";
            String id = "fluidpipe_on_" + kind + "_" + sanitize(s.name());
            if (!seen.add(id)) {
                throw new IllegalStateException("duplicate texture id " + id + " for " + s.name());
            }
            Color c = s.color();
            BufferedImage tinted = SubstanceLiquidTinter.tint(atlas, FluidPipeCoreMask.INSTANCE, c);
            ImageIO.write(tinted, "png", outDir.resolve(id + ".png").toFile());
        }

        System.out.println("Generated " + liquids.size() + " fluid-pipe core textures -> " + outDir);
    }

    /** Lowercase snake_case: non-alphanumerics become {@code _}, repeats collapse, ends trimmed. */
    static String sanitize(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
    }
}
