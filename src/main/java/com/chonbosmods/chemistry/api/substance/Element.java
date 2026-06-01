package com.chonbosmods.chemistry.api.substance;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.List;

/** A chemical element (design doc §3.2). Its isotopes are referenced by symbol, not embedded. */
public final class Element extends Substance {

    public static final BuilderCodec<Element> CODEC = BuilderCodec.builder(Element.class, Element::new)
        // shared (Substance) fields, with element-specific keys for formula/molarMass
        .append(new KeyedCodec<>("Name", Codec.STRING), (o, v) -> o.setName(v), Substance::name).add()
        .append(new KeyedCodec<>("Symbol", Codec.STRING), (o, v) -> o.setFormula(v), Substance::formula).add()
        .append(new KeyedCodec<>("Phase", Phase.CODEC), (o, v) -> o.setPhase(v), Substance::phase).add()
        .append(new KeyedCodec<>("Color", Color.CODEC), (o, v) -> o.setColor(v), Substance::color).add()
        .append(new KeyedCodec<>("Density", Codec.DOUBLE, false), (o, v) -> o.setDensity(v), Substance::density).add()
        .append(new KeyedCodec<>("StandardAtomicWeight", Codec.DOUBLE), (o, v) -> o.setMolarMass(v), Substance::molarMass).add()
        // element-specific fields
        .append(new KeyedCodec<>("AtomicNumber", Codec.INTEGER), (o, v) -> o.atomicNumber = v, o -> o.atomicNumber).add()
        .append(new KeyedCodec<>("Group", Codec.INTEGER, false), (o, v) -> o.group = v, o -> o.group).add()
        .append(new KeyedCodec<>("Period", Codec.INTEGER), (o, v) -> o.period = v, o -> o.period).add()
        .append(new KeyedCodec<>("Block", PeriodicBlock.CODEC), (o, v) -> o.block = v, o -> o.block).add()
        .append(new KeyedCodec<>("Category", ElementCategory.CODEC), (o, v) -> o.category = v, o -> o.category).add()
        .append(new KeyedCodec<>("OxidationStates", new ArrayCodec<>(Codec.INTEGER, Integer[]::new)),
            (o, v) -> o.oxidationStates = List.of(v), o -> o.oxidationStates.toArray(new Integer[0])).add()
        .append(new KeyedCodec<>("Electronegativity", Codec.DOUBLE, false), (o, v) -> o.electronegativity = v, o -> o.electronegativity).add()
        .append(new KeyedCodec<>("MeltingPoint", Codec.DOUBLE, false), (o, v) -> o.meltingPoint = v, o -> o.meltingPoint).add()
        .append(new KeyedCodec<>("BoilingPoint", Codec.DOUBLE, false), (o, v) -> o.boilingPoint = v, o -> o.boilingPoint).add()
        .append(new KeyedCodec<>("Appearance", Codec.STRING, false), (o, v) -> o.appearance = v, o -> o.appearance).add()
        .append(new KeyedCodec<>("ReactivityNote", Codec.STRING, false), (o, v) -> o.reactivityNote = v, o -> o.reactivityNote).add()
        .append(new KeyedCodec<>("AbundanceNote", Codec.STRING, false), (o, v) -> o.abundanceNote = v, o -> o.abundanceNote).add()
        .append(new KeyedCodec<>("IsotopeSymbols", new ArrayCodec<>(Codec.STRING, String[]::new)),
            (o, v) -> o.isotopeSymbols = List.of(v), o -> o.isotopeSymbols.toArray(new String[0])).add()
        .append(new KeyedCodec<>("Notes", Codec.STRING, false), (o, v) -> o.notes = v, o -> o.notes).add()
        .append(new KeyedCodec<>("ValueConfidence", ValueConfidence.CODEC), (o, v) -> o.valueConfidence = v, o -> o.valueConfidence).add()
        .build();

    private int atomicNumber;
    private Integer group;
    private int period;
    private PeriodicBlock block;
    private ElementCategory category;
    private List<Integer> oxidationStates = List.of();
    private Double electronegativity;
    private Double meltingPoint;
    private Double boilingPoint;
    private String appearance;
    private String reactivityNote;
    private String abundanceNote;
    private List<String> isotopeSymbols = List.of();
    private String notes;
    private ValueConfidence valueConfidence;

    public int atomicNumber() {
        return atomicNumber;
    }

    /** Nullable: f-block elements (lanthanides/actinides) carry no group. */
    public Integer group() {
        return group;
    }

    public int period() {
        return period;
    }

    public PeriodicBlock block() {
        return block;
    }

    public ElementCategory category() {
        return category;
    }

    public List<Integer> oxidationStates() {
        return oxidationStates;
    }

    public Double electronegativity() {
        return electronegativity;
    }

    public Double meltingPoint() {
        return meltingPoint;
    }

    public Double boilingPoint() {
        return boilingPoint;
    }

    public String appearance() {
        return appearance;
    }

    public String reactivityNote() {
        return reactivityNote;
    }

    public String abundanceNote() {
        return abundanceNote;
    }

    /** Symbols of this element's isotopes; resolve to {@link Isotope} via the registry. */
    public List<String> isotopeSymbols() {
        return isotopeSymbols;
    }

    public String notes() {
        return notes;
    }

    public ValueConfidence valueConfidence() {
        return valueConfidence;
    }
}
