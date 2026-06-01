package com.chonbosmods.chemistry.api.substance;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/**
 * The design's one-bit radiation type, plus {@code NONE} for stable nuclides. A documented game
 * hint (alpha/low-energy beta -> contact-only; gamma/neutron/high-energy beta/X-ray -> penetrating),
 * not a shielding calculation.
 */
public enum RadiationVector {
    PENETRATING("penetrating"),
    CONTACT_ONLY("contact-only"),
    NONE("none");

    public static final Codec<RadiationVector> CODEC =
        StringMappedCodec.ofEnum(RadiationVector.class, RadiationVector::jsonValue);

    private final String jsonValue;

    RadiationVector(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
