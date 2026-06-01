package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Whether a toxic effect is immediate or delayed. */
public enum Onset {
    ACUTE("acute"),
    DELAYED("delayed");

    public static final Codec<Onset> CODEC = StringMappedCodec.ofEnum(Onset.class, Onset::jsonValue);

    private final String jsonValue;

    Onset(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
