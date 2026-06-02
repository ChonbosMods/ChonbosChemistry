package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * A single configurable machine face: a {@code faceIndex} carrying a {@link PortChannel} with a
 * flow {@link PortDirection} (input / output / closed). Pure data (design §5.3).
 */
public final class Port {

    public static final BuilderCodec<Port> CODEC = BuilderCodec.builder(Port.class, Port::new)
        .append(new KeyedCodec<>("Face", Codec.INTEGER), (o, v) -> o.faceIndex = v, o -> o.faceIndex).add()
        .append(new KeyedCodec<>("Channel", PortChannel.CODEC), (o, v) -> o.channel = v, o -> o.channel).add()
        .append(new KeyedCodec<>("Direction", PortDirection.CODEC), (o, v) -> o.direction = v, o -> o.direction).add()
        .build();

    private int faceIndex;
    private PortChannel channel;
    private PortDirection direction;

    private Port() {
    }

    public static Port of(int faceIndex, PortChannel channel, PortDirection direction) {
        Port p = new Port();
        p.faceIndex = faceIndex;
        p.channel = channel;
        p.direction = direction;
        return p;
    }

    public int faceIndex() {
        return faceIndex;
    }

    public PortChannel channel() {
        return channel;
    }

    public PortDirection direction() {
        return direction;
    }
}
