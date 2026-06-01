package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** The dominant emission of a nuclide's decay, or {@code NONE} if stable. */
public enum DominantEmission {
    ALPHA("alpha"),
    BETA_MINUS("beta-minus"),
    BETA_PLUS("beta-plus"),
    GAMMA("gamma"),
    NEUTRON("neutron"),
    ELECTRON_CAPTURE("electron-capture"),
    X_RAY("X-ray"),
    NONE("none");

    public static final Codec<DominantEmission> CODEC =
        StringMappedCodec.ofEnum(DominantEmission.class, DominantEmission::jsonValue);

    private final String jsonValue;

    DominantEmission(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
