package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Physical phase at standard conditions (drives world representation: item / fluid / gas). */
public enum Phase {
    SOLID("solid"),
    LIQUID("liquid"),
    GAS("gas");

    public static final Codec<Phase> CODEC = StringMappedCodec.ofEnum(Phase.class, Phase::jsonValue);

    private final String jsonValue;

    Phase(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
