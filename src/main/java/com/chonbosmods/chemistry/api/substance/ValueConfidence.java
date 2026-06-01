package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Whether a record's values are measured or estimated/predicted. */
public enum ValueConfidence {
    MEASURED("measured"),
    ESTIMATED("estimated");

    public static final Codec<ValueConfidence> CODEC =
        StringMappedCodec.ofEnum(ValueConfidence.class, ValueConfidence::jsonValue);

    private final String jsonValue;

    ValueConfidence(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
