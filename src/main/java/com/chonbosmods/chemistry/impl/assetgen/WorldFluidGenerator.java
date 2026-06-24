package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.substance.Color;
import com.chonbosmods.chemistry.api.substance.Compound;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Substance;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import com.chonbosmods.chemistry.impl.texture.GlowBoost;
import com.chonbosmods.chemistry.impl.texture.LiquidMask;
import com.chonbosmods.chemistry.impl.texture.SubstanceLiquidTinter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Build-time asset generator for the world-fluid set: for every {@link FluidForm} (each LIQUID
 * substance, plus a liquefied form of each GAS substance) it bakes the substance color into the
 * fluid surface texture + inventory icon and emits the full block/FluidFX/placement-item JSON set
 * the server loads, mirroring {@link SubstanceAssetGenerator}.
 *
 * <p>Per fluid it writes:
 * <ul>
 *   <li>{@code Common/BlockTextures/<blockId>.png} : tinted + glow-boosted surface (32x32 mask).</li>
 *   <li>{@code Common/Icons/ItemsGenerated/<itemId>.png} : the recolored jar icon (64px).</li>
 *   <li>{@code Server/Item/Block/Fluids/<blockId>_Source.json} : the still-pool source block.</li>
 *   <li>{@code Server/Item/Block/Fluids/<blockId>.json} : the flowing block (Parent = source).</li>
 *   <li>{@code Server/Item/Block/FluidFX/<blockId>.json} : submerged-camera fog + swim physics.</li>
 *   <li>{@code Server/Item/Items/Fluid/<itemId>.json} : the placement item (places the source).</li>
 * </ul>
 * plus the {@code items.<itemId>.name} lang lines merged into {@code server.lang} via
 * {@link LangWriter} so the solid generator's keys survive (and vice-versa). No BlockTypeList is
 * emitted : fluids function via the engine's {@code Fluid} asset map + the item PlaceFluid
 * interaction (see the inline note where it would otherwise be written).
 *
 * <p>Run via the {@code generateWorldFluids} Gradle task:
 * {@code args = [<fluidMasterPng> <outputRoot> <iconMasterPng> <iconMaskPng>]}.
 */
public final class WorldFluidGenerator {

    /** Surface mask for the 32x32 fluid master: the whole tile is the recolorable liquid region. */
    private static final LiquidMask SURFACE_MASK = new LiquidMask(0, 0, 32, 32);

    private WorldFluidGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: <fluidMasterPng> <outputRoot> <iconMasterPng> <iconMaskPng>");
        }
        BufferedImage master = ImageIO.read(Path.of(args[0]).toFile());
        Path out = Path.of(args[1]);
        BufferedImage iconMaster = ImageIO.read(Path.of(args[2]).toFile());
        BufferedImage iconMask = ImageIO.read(Path.of(args[3]).toFile());

        Path texDir = out.resolve("Common/BlockTextures");
        Path iconDir = out.resolve("Common/Icons/ItemsGenerated");
        Path blockDir = out.resolve("Server/Item/Block/Fluids");
        Path fxDir = out.resolve("Server/Item/Block/FluidFX");
        Path itemDir = out.resolve("Server/Item/Items/Fluid");
        Path langDir = out.resolve("Server/Languages/en-US");
        Files.createDirectories(texDir);
        Files.createDirectories(iconDir);
        Files.createDirectories(blockDir);
        Files.createDirectories(fxDir);
        Files.createDirectories(itemDir);
        Files.createDirectories(langDir);

        InMemorySubstanceRegistry registry = InMemorySubstanceRegistry.loadFromResources();
        List<FluidForm> forms = FluidForm.allFor(registry);

        Map<String, String> langEntries = new LinkedHashMap<>();
        Set<String> seenBlocks = new HashSet<>();
        Map<GlowTier, Integer> tierCounts = new EnumMap<>(GlowTier.class);

        for (FluidForm form : forms) {
            Substance substance = form.substance();
            String blockId = FluidAssets.blockId(form.isElement(), form.liquefied(), substance.name());
            String itemId = FluidAssets.itemId(form.isElement(), form.liquefied(), substance.name());
            // itemId is "Chem_" + blockId, so a unique blockId implies a unique itemId : one guard
            // on the blockId covers both ids.
            if (!seenBlocks.add(blockId)) {
                throw new IllegalStateException("duplicate block id " + blockId + " for " + substance.name());
            }

            Color c = substance.color();
            GlowTier tier = form.isElement()
                ? GlowDeriver.tierFor((Element) substance, registry)
                : GlowDeriver.tierFor((Compound) substance, registry);
            tierCounts.merge(tier, 1, Integer::sum);

            // Surface texture: tint the white master by the substance color, then glow-boost.
            BufferedImage tinted = GlowBoost.apply(
                SubstanceLiquidTinter.tint(master, SURFACE_MASK, c), SURFACE_MASK, tier);
            ImageIO.write(tinted, "png", texDir.resolve(blockId + ".png").toFile());

            // BUG 3: loose-fluid icon = a tinted colored CUBE, matching vanilla Fluid_Water.png
            // (a 3D-rendered fluid cube), NOT the jar/vial. iconMaster/iconMask are the desaturated
            // vanilla Fluid_Water cube + its silhouette mask; SubstanceIcon.tint multiplies the
            // substance color into the cube (glow-boosted per tier), keeping the 64px frame as-is.
            ImageIO.write(
                SubstanceIcon.tint(iconMaster, iconMask, c, tier),
                "png", iconDir.resolve(itemId + ".png").toFile());

            List<FluidHazard> hazards = FluidHazardComposer.hazardsFor(form, registry);
            FluidPhysics physics = FluidPhysics.defaultFor(form.liquefied());
            String light = SolidSubstanceAssets.lightJson(c, tier);
            String texturePath = FluidAssets.texturePath(blockId);

            Files.writeString(
                blockDir.resolve(blockId + "_Source.json"),
                FluidAssets.sourceBlockJson(blockId, texturePath, c, light, hazards, physics));
            Files.writeString(
                blockDir.resolve(blockId + ".json"),
                FluidAssets.flowingBlockJson(blockId));
            Files.writeString(
                fxDir.resolve(blockId + ".json"),
                FluidAssets.fluidFxJson(blockId, c.toHex(), physics));
            Files.writeString(
                itemDir.resolve(itemId + ".json"),
                FluidAssets.placementItemJson(itemId, blockId, "Icons/ItemsGenerated/" + itemId + ".png"));

            langEntries.put("items." + itemId + ".name", form.displayName());
        }

        // No BlockTypeList is emitted for fluids: they register in the engine's separate `Fluid`
        // asset map (type/fluid/Fluid) and are placed via each item's PlaceFluid interaction, so
        // none of placement, rendering, or hazard application needs one. A BlockTypeList only makes
        // blocks addressable by block-filter / prefab-selection / worldgen tooling, and it resolves
        // entries against the BlockType asset map (BlockPattern.tryParseBlockTypeKey) : our source/
        // flowing fluids and our PlaceFluid-only Chem_Fluid_* items carry no inline BlockType, so any
        // listing would log "invalid block - skipping". Deferred until a real consumer (e.g. worldgen
        // fluid pools) needs it, at which point the placement items must gain an inline BlockType
        // (mirroring vanilla Fluid_Water) and those item ids get listed.
        LangWriter.merge(langDir.resolve("server.lang"), langEntries);

        System.out.println("Generated " + forms.size() + " world fluids -> " + out);
        StringBuilder summary = new StringBuilder("Glow tiers:");
        for (GlowTier t : GlowTier.values()) {
            summary.append(' ').append(t.name()).append('=').append(tierCounts.getOrDefault(t, 0));
        }
        System.out.println(summary);
    }
}
