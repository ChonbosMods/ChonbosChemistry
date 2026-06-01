package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Periodic-table block. */
public enum PeriodicBlock {
    S("s"),
    P("p"),
    D("d"),
    F("f");

    public static final Codec<PeriodicBlock> CODEC =
        StringMappedCodec.ofEnum(PeriodicBlock.class, PeriodicBlock::jsonValue);

    private final String jsonValue;

    PeriodicBlock(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
