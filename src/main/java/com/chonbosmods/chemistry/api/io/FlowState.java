package com.chonbosmods.chemistry.api.io;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Per-face pipe flow state (Mekanism-style): how a pipe face moves resources relative to its neighbor. */
public enum FlowState {
    NORMAL("normal"),
    PUSH("push"),
    PULL("pull"),
    NONE("none");

    public static final Codec<FlowState> CODEC = StringMappedCodec.ofEnum(FlowState.class, FlowState::jsonValue);

    private final String jsonValue;

    FlowState(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
