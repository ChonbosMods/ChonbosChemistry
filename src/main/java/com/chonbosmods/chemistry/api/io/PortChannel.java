package com.chonbosmods.chemistry.api.io;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/**
 * The transport channel a port carries (design §5.1/§5.4). Distinct from substance
 * {@link com.chonbosmods.chemistry.api.substance.Phase}: power is a transport channel, not a phase.
 */
public enum PortChannel {
    ITEM("item"),
    FLUID("fluid"),
    GAS("gas"),
    POWER("power");

    public static final Codec<PortChannel> CODEC = StringMappedCodec.ofEnum(PortChannel.class, PortChannel::jsonValue);

    private final String jsonValue;

    PortChannel(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
