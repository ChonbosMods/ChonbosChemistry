package com.chonbosmods.chemistry.api.io;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Flow role assigned to a port or tank face. */
public enum PortDirection {
    INPUT("input"),
    OUTPUT("output"),
    CLOSED("closed");

    public static final Codec<PortDirection> CODEC = StringMappedCodec.ofEnum(PortDirection.class, PortDirection::jsonValue);

    private final String jsonValue;

    PortDirection(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
