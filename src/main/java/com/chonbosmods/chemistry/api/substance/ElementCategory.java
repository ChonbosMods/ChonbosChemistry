package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Periodic-table category (drives reactivity tier and recipe balance). */
public enum ElementCategory {
    ALKALI_METAL("alkali metal"),
    ALKALINE_EARTH_METAL("alkaline earth metal"),
    TRANSITION_METAL("transition metal"),
    POST_TRANSITION_METAL("post-transition metal"),
    METALLOID("metalloid"),
    NONMETAL("nonmetal"),
    HALOGEN("halogen"),
    NOBLE_GAS("noble gas"),
    LANTHANIDE("lanthanide"),
    ACTINIDE("actinide");

    public static final Codec<ElementCategory> CODEC =
        StringMappedCodec.ofEnum(ElementCategory.class, ElementCategory::jsonValue);

    private final String jsonValue;

    ElementCategory(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
