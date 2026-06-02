package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.api.substance.Phase;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * A single configurable machine face: a {@code faceIndex} carrying a {@link Phase} with a
 * flow {@link PortDirection} (input / output / closed). Pure data (design §5.3).
 */
public final class Port {

    public static final BuilderCodec<Port> CODEC = BuilderCodec.builder(Port.class, Port::new)
        .append(new KeyedCodec<>("Face", Codec.INTEGER), (o, v) -> o.faceIndex = v, o -> o.faceIndex).add()
        .append(new KeyedCodec<>("Phase", Phase.CODEC), (o, v) -> o.phase = v, o -> o.phase).add()
        .append(new KeyedCodec<>("Direction", PortDirection.CODEC), (o, v) -> o.direction = v, o -> o.direction).add()
        .build();

    private int faceIndex;
    private Phase phase;
    private PortDirection direction;

    private Port() {
    }

    public static Port of(int faceIndex, Phase phase, PortDirection direction) {
        Port p = new Port();
        p.faceIndex = faceIndex;
        p.phase = phase;
        p.direction = direction;
        return p;
    }

    public int faceIndex() {
        return faceIndex;
    }

    public Phase phase() {
        return phase;
    }

    public PortDirection direction() {
        return direction;
    }
}
