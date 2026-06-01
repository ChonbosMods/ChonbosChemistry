package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Timing of a toxic effect. {@code ACUTE_CHRONIC} is the data's composite "both" value. */
public enum Onset {
    ACUTE("acute"),
    DELAYED("delayed"),
    CHRONIC("chronic"),
    ACUTE_CHRONIC("acute/chronic");

    public static final Codec<Onset> CODEC = StringMappedCodec.ofEnum(Onset.class, Onset::jsonValue);

    private final String jsonValue;

    Onset(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
