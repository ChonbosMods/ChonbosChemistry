package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.substance.Color;
import com.chonbosmods.chemistry.api.substance.Compound;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Phase;
import com.chonbosmods.chemistry.api.substance.Substance;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import com.chonbosmods.chemistry.impl.texture.GlowBoost;
import com.chonbosmods.chemistry.impl.texture.LiquidMask;
import com.chonbosmods.chemistry.impl.texture.SubstanceLiquidTinter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Build-time asset generator: for every SOLID element/compound, bakes its color into the liquid
 * region of the white master and emits the matching item JSON + a localized name. Output lands in a
 * generated asset-pack tree ({@code Common/...} client textures, {@code Server/...} item defs/lang)
 * that the Gradle build folds into the mod jar.
 *
 * <p>Run via the {@code generateSolidSubstanceAssets} Gradle task:
 * {@code args = [<masterPng>, <outputRoot>]}.
 */
public final class SubstanceAssetGenerator {

    /** Liquid mask for the potion-derived Solid jar (32x64 texture): x15..29, y0..20. */
    private static final LiquidMask LIQUID_MASK = new LiquidMask(15, 0, 14, 20);

    private SubstanceAssetGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: <texMasterPng> <outputRoot> <iconMasterPng> <iconMaskPng>");
        }
        BufferedImage master = ImageIO.read(Path.of(args[0]).toFile());
        Path out = Path.of(args[1]);
        BufferedImage iconMaster = ImageIO.read(Path.of(args[2]).toFile());
        BufferedImage iconMask = ImageIO.read(Path.of(args[3]).toFile());

        Path texDir = out.resolve("Common/Items/Chemistry/Substance_Textures");
        Path iconDir = out.resolve("Common/Icons/ItemsGenerated");
        Path itemDir = out.resolve("Server/Item/Items/Chemistry");
        Path langDir = out.resolve("Server/Languages/en-US");
        Files.createDirectories(texDir);
        Files.createDirectories(iconDir);
        Files.createDirectories(itemDir);
        Files.createDirectories(langDir);

        InMemorySubstanceRegistry registry = InMemorySubstanceRegistry.loadFromResources();
        List<Substance> solids = new ArrayList<>();
        registry.elements().stream().filter(s -> s.phase() == Phase.SOLID).forEach(solids::add);
        registry.compounds().stream().filter(s -> s.phase() == Phase.SOLID).forEach(solids::add);

        StringBuilder lang = new StringBuilder();
        Set<String> seen = new HashSet<>();
        Map<GlowTier, Integer> tierCounts = new EnumMap<>(GlowTier.class);
        for (GlowTier t : GlowTier.values()) {
            tierCounts.put(t, 0);
        }
        for (Substance s : solids) {
            String id = SolidSubstanceAssets.assetId(s);
            if (!seen.add(id)) {
                throw new IllegalStateException("duplicate asset id " + id + " for " + s.name());
            }
            String texturePath = SolidSubstanceAssets.texturePath(id);

            Color c = s.color();
            GlowTier tier = s instanceof Element e
                ? GlowDeriver.tierFor(e, registry)
                : GlowDeriver.tierFor((Compound) s, registry);
            tierCounts.merge(tier, 1, Integer::sum);

            BufferedImage tinted = GlowBoost.apply(
                SubstanceLiquidTinter.tint(master, LIQUID_MASK, c), LIQUID_MASK, tier);
            ImageIO.write(tinted, "png", texDir.resolve(id + ".png").toFile());
            ImageIO.write(
                SubstanceIcon.render(iconMaster, iconMask, c, 64, tier),
                "png", iconDir.resolve(id + ".png").toFile());

            Files.writeString(
                itemDir.resolve(id + ".json"),
                SolidSubstanceAssets.itemJson(id, texturePath, tier, SolidSubstanceAssets.lightJson(c, tier)));
            lang.append("items.").append(id).append(".name = ").append(s.name()).append('\n');
        }
        Files.writeString(langDir.resolve("server.lang"), lang.toString());

        System.out.println("Generated " + solids.size() + " solid-substance items -> " + out);
        System.out.printf(
            "Glow tiers: NONE=%d FAINT=%d STRONG=%d FIERCE=%d%n",
            tierCounts.get(GlowTier.NONE),
            tierCounts.get(GlowTier.FAINT),
            tierCounts.get(GlowTier.STRONG),
            tierCounts.get(GlowTier.FIERCE));
    }
}
