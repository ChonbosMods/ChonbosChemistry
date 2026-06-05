package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * A {@link ResourceBuffer} tagged with the {@link PortChannel} it serves. This is the canonical
 * codec-friendly form for the per-channel resource buffers a {@link MachineBlockState} owns:
 * an {@code EnumMap<PortChannel, ResourceBuffer>} is awkward to persist directly, so the map is
 * flattened to a small {@code List<ChanneledResource>} on encode and rebuilt on decode (small N,
 * one entry per FLUID/GAS/ITEM channel a machine actually uses).
 */
public final class ChanneledResource {

    public static final BuilderCodec<ChanneledResource> CODEC = BuilderCodec.builder(ChanneledResource.class, ChanneledResource::new)
        .append(new KeyedCodec<>("Channel", PortChannel.CODEC), (o, v) -> o.channel = v, o -> o.channel).add()
        .append(new KeyedCodec<>("Buffer", ResourceBuffer.CODEC), (o, v) -> o.buffer = v, o -> o.buffer).add()
        .build();

    private PortChannel channel;
    private ResourceBuffer buffer;

    private ChanneledResource() {
    }

    public static ChanneledResource of(PortChannel channel, ResourceBuffer buffer) {
        ChanneledResource r = new ChanneledResource();
        r.channel = channel;
        r.buffer = buffer;
        return r;
    }

    public PortChannel channel() {
        return channel;
    }

    public ResourceBuffer buffer() {
        return buffer;
    }
}
