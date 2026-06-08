package com.chonbosmods.chemistry.api.io;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Flow role assigned to a port or tank face. */
public enum PortDirection {
    INPUT("input"),
    OUTPUT("output"),
    /**
     * Two-way: the face both offers and accepts on its channel. Used by storage blocks (batteries,
     * tanks) which, under one-port-per-face, cannot declare separate INPUT and OUTPUT ports on the
     * same face. A BOTH facing port is what makes a neighbour eligible to pair as a balancing
     * {@code StorageEndpoint} (design 2026-06-05 §1).
     */
    BOTH("both"),
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
