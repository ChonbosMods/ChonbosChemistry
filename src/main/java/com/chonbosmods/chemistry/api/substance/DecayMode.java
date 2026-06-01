package com.chonbosmods.chemistry.api.substance;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One decay channel of an isotope. {@code mode} is a free string (composite values like
 * {@code "electron-capture + minor beta-plus"} exist in the data), {@code decayEnergy} is the
 * total decay Q-value in MeV.
 */
public final class DecayMode {

    public static final BuilderCodec<DecayMode> CODEC = BuilderCodec.builder(DecayMode.class, DecayMode::new)
        .append(new KeyedCodec<>("Mode", Codec.STRING), (o, v) -> o.mode = v, o -> o.mode).add()
        .append(new KeyedCodec<>("Branching", Codec.DOUBLE), (o, v) -> o.branching = v, o -> o.branching).add()
        .append(new KeyedCodec<>("Daughter", Codec.STRING), (o, v) -> o.daughter = v, o -> o.daughter).add()
        .append(new KeyedCodec<>("DecayEnergy", Codec.DOUBLE), (o, v) -> o.decayEnergy = v, o -> o.decayEnergy).add()
        .build();

    private String mode;
    private double branching;
    private String daughter;
    private double decayEnergy;

    public String mode() {
        return mode;
    }

    public double branching() {
        return branching;
    }

    public String daughter() {
        return daughter;
    }

    public double decayEnergy() {
        return decayEnergy;
    }
}
