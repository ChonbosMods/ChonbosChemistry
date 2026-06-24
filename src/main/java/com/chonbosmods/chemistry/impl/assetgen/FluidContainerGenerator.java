package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.substance.Color;
import com.chonbosmods.chemistry.api.substance.Substance;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import com.chonbosmods.chemistry.impl.texture.SubstanceLiquidTinter;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Build-time asset generator that overrides the three water-capable vanilla containers (Deco_Mug,
 * Deco_Tankard, Container_Bucket) so EVERY substance fluid is fillable + drinkable in them.
 *
 * <p>For each {@link FluidForm} the generator bakes a per-substance tinted liquid texture (only the
 * container's {@code LiquidMask} rectangle is recolored) and produces a {@code Filled_<blockId>}
 * item-state member (via {@link FluidAssets#filledStateJson}) plus, for the fill wiring, an
 * {@code AllowedFluids} entry mapping {@code <blockId>_Source} to that state.
 *
 * <p>The override is a structured text-splice that PRESERVES all vanilla content byte-for-byte:
 * generated members are inserted immediately after the opening brace of the target object (the
 * item's {@code "State": {} } map, the bucket's inline {@code RefillContainer.States}, or the shared
 * {@code Mug_Fill.json}'s {@code Next.States}). Vanilla {@code Filled_Water} / {@code Filled_Milk} /
 * {@code Filled_Mosshorn_Milk} always remain (and follow our inserts, so the trailing comma we emit
 * is comma-safe).
 *
 * <p>The fill wiring DIFFERS per container:
 * <ul>
 *   <li>BUCKET: its empty item's top-level {@code Interactions.Secondary} holds two inline
 *       {@code RefillContainer}s (crouching / not-crouching), each with a {@code States} map; both
 *       get {@code Filled_<blockId>.{AllowedFluids:["<blockId>_Source"], TransformFluid:"Empty"}}.</li>
 *   <li>MUG + TANKARD: their empty item's {@code Interactions.Secondary} is the shared
 *       {@code Root_Mug_Fill} root, whose chain ({@code Condition_Fill_Mug} -> {@code Mug_Fill})
 *       ends at {@code Mug_Fill.json}'s {@code Next.States}. We override that ONE shared file with
 *       {@code Filled_<blockId>.AllowedFluids:["<blockId>_Source"]} for ALL fluids; one override
 *       covers both mug and tankard.</li>
 * </ul>
 *
 * <p>Run via the {@code generateFluidContainers} Gradle task:
 * {@code args = [<containersAssetsSrcDir> <outputRoot>]}.
 */
public final class FluidContainerGenerator {

    /** The shared mug/tankard fill file we capture + override once (STEP 0). */
    private static final String MUG_FILL_VANILLA = "Mug_Fill.vanilla.json";
    private static final String MUG_FILL_OUTPUT = "Server/Item/Interactions/Consumables/Mug_Fill.json";

    private FluidContainerGenerator() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <containersAssetsSrcDir> <outputRoot>");
        }
        Path containersDir = Path.of(args[0]);
        Path out = Path.of(args[1]);

        InMemorySubstanceRegistry registry = InMemorySubstanceRegistry.loadFromResources();
        List<FluidForm> forms = FluidForm.allFor(registry);

        Map<String, String> langEntries = new LinkedHashMap<>();
        int filledStateCount = 0;
        int tintedTextureCount = 0;

        // Fill-source mappings shared by mug + tankard, built in their OWN loop over forms so the
        // shared Mug_Fill override never depends on FluidContainers.ALL order/contents.
        List<String> mugFillMembers = new ArrayList<>();
        for (FluidForm form : forms) {
            String blockId = FluidAssets.blockId(form.isElement(), form.liquefied(), form.substance().name());
            mugFillMembers.add(mugFillMember(blockId));
        }

        for (FluidContainers.FluidContainer container : FluidContainers.ALL) {
            BufferedImage master = ImageIO.read(containersDir.resolve(container.masterFile()).toFile());
            // BUG 4: per-substance tinted CONTAINER ICON, derived from the vanilla <id>_Water icon
            // (grayscale liquid master + per-pixel liquid mask). Tinted with SubstanceIcon.tint.
            BufferedImage iconMaster = ImageIO.read(containersDir.resolve(container.iconMasterFile()).toFile());
            BufferedImage iconMask = ImageIO.read(containersDir.resolve(container.iconMaskFile()).toFile());

            List<String> stateMembers = new ArrayList<>();   // "Filled_X": { ... } item states
            List<String> bucketFillMembers = new ArrayList<>(); // bucket-only RefillContainer.States
            Set<String> seenBlocks = new LinkedHashSet<>();

            for (FluidForm form : forms) {
                Substance substance = form.substance();
                String blockId = FluidAssets.blockId(form.isElement(), form.liquefied(), substance.name());
                if (!seenBlocks.add(blockId)) {
                    throw new IllegalStateException(
                        "duplicate fluid block id " + blockId + " for container " + container.id());
                }

                // Tinted per-substance liquid texture (only the LiquidMask rectangle is recolored).
                Color c = substance.color();
                BufferedImage tinted = SubstanceLiquidTinter.tint(master, container.liquidMask(), c);
                Path texOut = out.resolve("Common").resolve(container.tintedTexturePath(blockId));
                Files.createDirectories(texOut.getParent());
                ImageIO.write(tinted, "png", texOut.toFile());
                tintedTextureCount++;

                // BUG 4: per-substance tinted container ICON, written to the path the filled state
                // references (Common/Icons/ItemsGenerated/<id>_<blockId>.png). No glow tier: the
                // container icon is a desaturated vanilla render, kept faithful to vanilla.
                BufferedImage tintedIcon = SubstanceIcon.tint(iconMaster, iconMask, c);
                Path iconOut = out.resolve("Common").resolve(container.iconPath(blockId));
                Files.createDirectories(iconOut.getParent());
                ImageIO.write(tintedIcon, "png", iconOut.toFile());

                // Filled state member (appearance + drink-or-pour + durability; no fill mapping).
                List<FluidHazard> hazards = FluidHazardComposer.hazardsFor(form, registry);
                stateMembers.add(FluidAssets.filledStateJson(
                    container, blockId, hazards, container.iconPath(blockId)));
                filledStateCount++;

                // Lang (BUG 2): the filledStateJson emits BOTH Name + Description keys; write both
                // lang lines. LangWriter stores under "items.<id>_<blockId>.{name,description}"
                // (the loader prefixes "server."). The name MIMICS VANILLA :
                // "<container displayName> (<fluid name>)" (e.g. "Wooden Bucket (Hydrogen peroxide)"),
                // matching items.Container_Bucket_Water.name = "Wooden Bucket (Water)". Both strings
                // are derived from the registry record (displayName + fillMode), nothing per-container
                // is hardcoded here.
                langEntries.put(
                    "items." + container.id() + "_" + blockId + ".name",
                    container.filledName(form.displayName()));
                langEntries.put(
                    "items." + container.id() + "_" + blockId + ".description",
                    container.filledDescription(form.displayName()));

                // POUR containers (bucket): inline RefillContainer fill mapping (AllowedFluids +
                // TransformFluid). Keyed off the registry fillMode, not a hardcoded container id.
                if (container.pours()) {
                    bucketFillMembers.add(bucketFillMember(blockId));
                }
            }

            // Override the item JSON: splice the generated state members into the "State" object,
            // preserving the vanilla Filled_* states.
            String itemTemplate = Files.readString(containersDir.resolve(container.vanillaTemplate()));
            String overriddenItem = spliceMembers(itemTemplate, "\"State\":", String.join(",\n", stateMembers));

            // POUR containers (bucket): also splice the fill mappings into EVERY inline
            // RefillContainer.States (two: crouching + not-crouching). The "States" objects live
            // inside Interactions.Secondary. Keyed off the registry fillMode, not a hardcoded id.
            if (container.pours()) {
                overriddenItem = spliceAllMembers(
                    overriddenItem, "\"States\":", String.join(",\n", bucketFillMembers));
            }

            Path itemOut = out.resolve(container.itemOutputPath());
            Files.createDirectories(itemOut.getParent());
            Files.writeString(itemOut, overriddenItem);
            validateParses(overriddenItem, itemOut.toString());
        }

        // Override the shared mug/tankard fill file ONCE (its single Next.States object).
        String mugFillTemplate = Files.readString(containersDir.resolve(MUG_FILL_VANILLA));
        String overriddenMugFill = spliceMembers(
            mugFillTemplate, "\"States\":", String.join(",\n", mugFillMembers));
        Path mugFillOut = out.resolve(MUG_FILL_OUTPUT);
        Files.createDirectories(mugFillOut.getParent());
        Files.writeString(mugFillOut, overriddenMugFill);
        validateParses(overriddenMugFill, mugFillOut.toString());

        // Lang: coexist with solid + world-fluid keys (LangWriter dedups + preserves existing keys).
        Path langDir = out.resolve("Server/Languages/en-US");
        Files.createDirectories(langDir);
        LangWriter.merge(langDir.resolve("server.lang"), langEntries);

        System.out.println("Generated " + filledStateCount + " filled states across 3 containers ("
            + tintedTextureCount + " tinted textures)");
    }

    /** Bucket inline RefillContainer member: maps the fluid source to the Filled state, empties on use. */
    private static String bucketFillMember(String blockId) {
        return """
            "Filled_%s": {
              "AllowedFluids": [ "%s_Source" ],
              "TransformFluid": "Empty"
            }""".formatted(blockId, blockId);
    }

    /**
     * Shared Mug_Fill member: maps BOTH the source and the flowing fluid id to the Filled state (no
     * TransformFluid, per vanilla). Mirrors vanilla {@code Filled_Water.AllowedFluids:["Water_Source",
     * "Water"]}: a fluid pool's edge cells are FLOWING blocks (distinct id from the source), and
     * {@code RefillContainerInteraction} matches the raycast cell's fluid id, so without the flowing id
     * a mug/tankard could not fill from the edge of a CC fluid pool. (The bucket is deliberately
     * source-only, matching vanilla.)
     */
    private static String mugFillMember(String blockId) {
        return """
            "Filled_%s": {
              "AllowedFluids": [ "%s_Source", "%s" ]
            }""".formatted(blockId, blockId, blockId);
    }

    /**
     * Splice {@code members} immediately after the opening {@code {} of the FIRST object whose key is
     * {@code keyToken} (e.g. {@code "State":} / {@code "States":}). The inserted text ends with a
     * comma, so the vanilla member that follows keeps the object valid.
     */
    private static String spliceMembers(String json, String keyToken, String members) {
        int at = json.indexOf(keyToken);
        if (at < 0) {
            throw new IllegalStateException("key token not found: " + keyToken);
        }
        int brace = json.indexOf('{', at + keyToken.length());
        if (brace < 0) {
            throw new IllegalStateException("opening brace not found after: " + keyToken);
        }
        return json.substring(0, brace + 1)
            + "\n" + members + ",\n"
            + json.substring(brace + 1);
    }

    /** As {@link #spliceMembers} but splices into EVERY object whose key is {@code keyToken}. */
    private static String spliceAllMembers(String json, String keyToken, String members) {
        StringBuilder sb = new StringBuilder();
        int cursor = 0;
        int at;
        while ((at = json.indexOf(keyToken, cursor)) >= 0) {
            int brace = json.indexOf('{', at + keyToken.length());
            if (brace < 0) {
                throw new IllegalStateException("opening brace not found after: " + keyToken);
            }
            sb.append(json, cursor, brace + 1)
                .append("\n").append(members).append(",\n");
            cursor = brace + 1;
        }
        sb.append(json.substring(cursor));
        return sb.toString();
    }

    /** Parse the produced JSON with the engine reader exactly as the tests do; throw on failure. */
    private static void validateParses(String json, String path) {
        try {
            com.hypixel.hytale.codec.util.RawJsonReader.readBsonDocument(
                com.hypixel.hytale.codec.util.RawJsonReader.fromJsonString(json));
        } catch (Exception e) {
            throw new IllegalStateException("generated file failed to parse: " + path, e);
        }
    }
}
