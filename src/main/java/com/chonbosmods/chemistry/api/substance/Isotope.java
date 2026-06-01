package com.chonbosmods.chemistry.api.substance;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.List;

/**
 * A nuclide: element metadata, not a {@link Substance} (it shares ~none of a substance's bulk
 * attributes, and is a refinement of one element rather than a peer of Element/Compound).
 * Referenced from {@link Element#isotopeSymbols()} by {@link #isotopeSymbol()}.
 */
public final class Isotope {

    public static final BuilderCodec<Isotope> CODEC = BuilderCodec.builder(Isotope.class, Isotope::new)
        .append(new KeyedCodec<>("ParentSymbol", Codec.STRING), (o, v) -> o.parentSymbol = v, o -> o.parentSymbol).add()
        .append(new KeyedCodec<>("ParentZ", Codec.INTEGER), (o, v) -> o.parentZ = v, o -> o.parentZ).add()
        .append(new KeyedCodec<>("MassNumber", Codec.INTEGER), (o, v) -> o.massNumber = v, o -> o.massNumber).add()
        .append(new KeyedCodec<>("IsotopeSymbol", Codec.STRING), (o, v) -> o.isotopeSymbol = v, o -> o.isotopeSymbol).add()
        .append(new KeyedCodec<>("NaturalAbundance", Codec.DOUBLE), (o, v) -> o.naturalAbundance = v, o -> o.naturalAbundance).add()
        .append(new KeyedCodec<>("Stability", Stability.CODEC), (o, v) -> o.stability = v, o -> o.stability).add()
        .append(new KeyedCodec<>("HalfLife", Codec.STRING), (o, v) -> o.halfLife = v, o -> o.halfLife).add()
        .append(new KeyedCodec<>("HalfLifeSeconds", Codec.DOUBLE, false), (o, v) -> o.halfLifeSeconds = v, o -> o.halfLifeSeconds).add()
        .append(new KeyedCodec<>("DecayModes", new ArrayCodec<>(DecayMode.CODEC, DecayMode[]::new)),
            (o, v) -> o.decayModes = List.of(v), o -> o.decayModes.toArray(new DecayMode[0])).add()
        .append(new KeyedCodec<>("DominantEmission", DominantEmission.CODEC), (o, v) -> o.dominantEmission = v, o -> o.dominantEmission).add()
        .append(new KeyedCodec<>("RadiationVector", RadiationVector.CODEC), (o, v) -> o.radiationVector = v, o -> o.radiationVector).add()
        .append(new KeyedCodec<>("SpecificActivity", Codec.DOUBLE, false), (o, v) -> o.specificActivity = v, o -> o.specificActivity).add()
        .append(new KeyedCodec<>("DecayHeat", Codec.DOUBLE, false), (o, v) -> o.decayHeat = v, o -> o.decayHeat).add()
        .append(new KeyedCodec<>("NuclearFlags", NuclearFlags.CODEC), (o, v) -> o.nuclearFlags = v, o -> o.nuclearFlags).add()
        .append(new KeyedCodec<>("Notes", Codec.STRING, false), (o, v) -> o.notes = v, o -> o.notes).add()
        .append(new KeyedCodec<>("ValueConfidence", ValueConfidence.CODEC), (o, v) -> o.valueConfidence = v, o -> o.valueConfidence).add()
        .build();

    private String parentSymbol;
    private int parentZ;
    private int massNumber;
    private String isotopeSymbol;
    private double naturalAbundance;
    private Stability stability;
    private String halfLife;
    private Double halfLifeSeconds;
    private List<DecayMode> decayModes = List.of();
    private DominantEmission dominantEmission;
    private RadiationVector radiationVector;
    private Double specificActivity;
    private Double decayHeat;
    private NuclearFlags nuclearFlags;
    private String notes;
    private ValueConfidence valueConfidence;

    public String parentSymbol() {
        return parentSymbol;
    }

    public int parentZ() {
        return parentZ;
    }

    public int massNumber() {
        return massNumber;
    }

    public String isotopeSymbol() {
        return isotopeSymbol;
    }

    public double naturalAbundance() {
        return naturalAbundance;
    }

    public Stability stability() {
        return stability;
    }

    /** Human-readable half-life (e.g. "1.387 My", or "stable"). */
    public String halfLife() {
        return halfLife;
    }

    /** Nullable: absent for stable nuclides. */
    public Double halfLifeSeconds() {
        return halfLifeSeconds;
    }

    public List<DecayMode> decayModes() {
        return decayModes;
    }

    public DominantEmission dominantEmission() {
        return dominantEmission;
    }

    public RadiationVector radiationVector() {
        return radiationVector;
    }

    /** Nullable: absent for stable nuclides. */
    public Double specificActivity() {
        return specificActivity;
    }

    /** Nullable: absent for stable nuclides or where unavailable. */
    public Double decayHeat() {
        return decayHeat;
    }

    public NuclearFlags nuclearFlags() {
        return nuclearFlags;
    }

    public String notes() {
        return notes;
    }

    public ValueConfidence valueConfidence() {
        return valueConfidence;
    }
}
