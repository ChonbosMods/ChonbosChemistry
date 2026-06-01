package com.chonbosmods.chemistry.api.substance;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import java.util.HashMap;
import java.util.Map;

/** A chemical compound (design doc §3.4). Molar mass is consistent with {@code composition}. */
public final class Compound extends Substance {

    public static final BuilderCodec<Compound> CODEC = BuilderCodec.builder(Compound.class, Compound::new)
        // shared (Substance) fields, with compound-specific keys for formula/molarMass
        .append(new KeyedCodec<>("Name", Codec.STRING), (o, v) -> o.setName(v), Substance::name).add()
        .append(new KeyedCodec<>("Formula", Codec.STRING), (o, v) -> o.setFormula(v), Substance::formula).add()
        .append(new KeyedCodec<>("Phase", Phase.CODEC), (o, v) -> o.setPhase(v), Substance::phase).add()
        .append(new KeyedCodec<>("Color", Color.CODEC), (o, v) -> o.setColor(v), Substance::color).add()
        .append(new KeyedCodec<>("Density", Codec.DOUBLE, false), (o, v) -> o.setDensity(v), Substance::density).add()
        .append(new KeyedCodec<>("MolarMass", Codec.DOUBLE), (o, v) -> o.setMolarMass(v), Substance::molarMass).add()
        // compound-specific fields
        .append(new KeyedCodec<>("Composition", new MapCodec<>(Codec.INTEGER, HashMap::new)),
            (o, v) -> o.composition = v, o -> o.composition).add()
        .append(new KeyedCodec<>("CompoundType", CompoundType.CODEC), (o, v) -> o.compoundType = v, o -> o.compoundType).add()
        .append(new KeyedCodec<>("PropertyFlags", PropertyFlags.CODEC), (o, v) -> o.propertyFlags = v, o -> o.propertyFlags).add()
        .append(new KeyedCodec<>("Toxicity", Toxicity.CODEC, false), (o, v) -> o.toxicity = v, o -> o.toxicity).add()
        .append(new KeyedCodec<>("WaterSolubility", Codec.STRING, false), (o, v) -> o.waterSolubility = v, o -> o.waterSolubility).add()
        .append(new KeyedCodec<>("IsRadioactive", Codec.BOOLEAN), (o, v) -> o.isRadioactive = v, o -> o.isRadioactive).add()
        .append(new KeyedCodec<>("Appearance", Codec.STRING, false), (o, v) -> o.appearance = v, o -> o.appearance).add()
        .append(new KeyedCodec<>("Notes", Codec.STRING, false), (o, v) -> o.notes = v, o -> o.notes).add()
        .append(new KeyedCodec<>("ValueConfidence", ValueConfidence.CODEC), (o, v) -> o.valueConfidence = v, o -> o.valueConfidence).add()
        .build();

    private Map<String, Integer> composition = new HashMap<>();
    private CompoundType compoundType;
    private PropertyFlags propertyFlags;
    private Toxicity toxicity;
    private String waterSolubility;
    private boolean isRadioactive;
    private String appearance;
    private String notes;
    private ValueConfidence valueConfidence;

    /** Element symbol → atom count. Resolve symbols to {@link Element} via the registry. */
    public Map<String, Integer> composition() {
        return composition;
    }

    public CompoundType compoundType() {
        return compoundType;
    }

    public PropertyFlags propertyFlags() {
        return propertyFlags;
    }

    /** Nullable: present only for substances with modeled toxicity. */
    public Toxicity toxicity() {
        return toxicity;
    }

    public String waterSolubility() {
        return waterSolubility;
    }

    public boolean isRadioactive() {
        return isRadioactive;
    }

    public String appearance() {
        return appearance;
    }

    public String notes() {
        return notes;
    }

    public ValueConfidence valueConfidence() {
        return valueConfidence;
    }
}
