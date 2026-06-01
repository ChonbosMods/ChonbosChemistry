package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/**
 * Whether a nuclide is stable. Follows the NUBASE "observationally stable" convention, so it is
 * non-monotonic in half-life: downstream code must key off this flag directly and must not assume
 * {@code RADIOACTIVE} implies a shorter half-life than any {@code STABLE} nuclide.
 */
public enum Stability {
    STABLE("stable"),
    RADIOACTIVE("radioactive");

    public static final Codec<Stability> CODEC = StringMappedCodec.ofEnum(Stability.class, Stability::jsonValue);

    private final String jsonValue;

    Stability(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
