package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** How a toxic payload reaches an entity. */
public enum ExposureRoute {
    INHALE("inhale"),
    INGEST("ingest"),
    CONTACT("contact");

    public static final Codec<ExposureRoute> CODEC =
        StringMappedCodec.ofEnum(ExposureRoute.class, ExposureRoute::jsonValue);

    private final String jsonValue;

    ExposureRoute(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
