package com.chonbosmods.chemistry.api.substance;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.List;

/** The toxicity lens (design doc §4.4): how a substance harms, and what neutralizes it. */
public final class Toxicity {

    public static final BuilderCodec<Toxicity> CODEC = BuilderCodec.builder(Toxicity.class, Toxicity::new)
        // All optional: non-toxic compounds carry null sub-fields, which the re-key transform drops.
        .append(new KeyedCodec<>("Route", new ArrayCodec<>(ExposureRoute.CODEC, ExposureRoute[]::new), false),
            (o, v) -> o.route = List.of(v), o -> o.route.toArray(new ExposureRoute[0])).add()
        .append(new KeyedCodec<>("PotencyNote", Codec.STRING, false), (o, v) -> o.potencyNote = v, o -> o.potencyNote).add()
        .append(new KeyedCodec<>("Effect", Codec.STRING, false), (o, v) -> o.effect = v, o -> o.effect).add()
        .append(new KeyedCodec<>("Onset", Onset.CODEC, false), (o, v) -> o.onset = v, o -> o.onset).add()
        .append(new KeyedCodec<>("DurationNote", Codec.STRING, false), (o, v) -> o.durationNote = v, o -> o.durationNote).add()
        .append(new KeyedCodec<>("Bioaccumulation", Codec.STRING, false), (o, v) -> o.bioaccumulation = v, o -> o.bioaccumulation).add()
        .append(new KeyedCodec<>("AntidoteNote", Codec.STRING, false), (o, v) -> o.antidoteNote = v, o -> o.antidoteNote).add()
        .build();

    private List<ExposureRoute> route = List.of();
    private String potencyNote;
    private String effect;
    private Onset onset;
    private String durationNote;
    private String bioaccumulation;
    private String antidoteNote;

    public List<ExposureRoute> route() {
        return route;
    }

    public String potencyNote() {
        return potencyNote;
    }

    public String effect() {
        return effect;
    }

    public Onset onset() {
        return onset;
    }

    public String durationNote() {
        return durationNote;
    }

    public String bioaccumulation() {
        return bioaccumulation;
    }

    public String antidoteNote() {
        return antidoteNote;
    }
}
