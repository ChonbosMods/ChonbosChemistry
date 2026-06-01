package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/**
 * Functional category of a compound. {@code MOLECULAR}/{@code IONIC} are part of the contract
 * but currently unused by the bundled data, which prefers the more specific functional buckets.
 */
public enum CompoundType {
    MOLECULAR("molecular"),
    IONIC("ionic"),
    ACID("acid"),
    BASE("base"),
    SALT("salt"),
    OXIDE("oxide"),
    ALLOY("alloy"),
    ORGANIC("organic"),
    OTHER("other");

    public static final Codec<CompoundType> CODEC =
        StringMappedCodec.ofEnum(CompoundType.class, CompoundType::jsonValue);

    private final String jsonValue;

    CompoundType(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
