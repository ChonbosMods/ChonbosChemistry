package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.List;

/**
 * The persistent ECS state a single-channel storage block (tank) carries on the {@link ChunkStore}:
 * one {@link ResourceBuffer} serving exactly one {@link PortChannel} (e.g. FLUID), plus a port
 * configuration and a transport throughput cap. Carries no power, so {@link #energy()} is null.
 *
 * <h2>clone()</h2>
 * Deep copy via a codec round-trip (encode then decode), reusing the tested bean codecs, so each
 * placed tank receives an independent buffer.
 */
public final class TankBlockState implements Component<ChunkStore> {

    public static final BuilderCodec<TankBlockState> CODEC = BuilderCodec.builder(TankBlockState.class, TankBlockState::new)
        .append(new KeyedCodec<>("Buffer", ResourceBuffer.CODEC), (o, v) -> o.buffer = v, o -> o.buffer).add()
        .append(new KeyedCodec<>("Channel", PortChannel.CODEC), (o, v) -> o.channel = v, o -> o.channel).add()
        .append(new KeyedCodec<>("Ports", PortConfig.CODEC), (o, v) -> o.ports = v, o -> o.ports).add()
        .append(new KeyedCodec<>("Throughput", Codec.INTEGER), (o, v) -> o.throughput = v, o -> o.throughput).add()
        .build();

    private static final int DEFAULT_THROUGHPUT = 100;

    private ResourceBuffer buffer;
    private PortChannel channel = PortChannel.FLUID;
    private PortConfig ports = PortConfig.of(List.of());
    private int throughput = DEFAULT_THROUGHPUT;

    /** Public no-arg constructor for the codec supplier. */
    public TankBlockState() {
    }

    public static TankBlockState create(ResourceBuffer buffer, PortChannel channel, PortConfig ports, int throughput) {
        TankBlockState s = new TankBlockState();
        s.buffer = buffer;
        s.channel = channel != null ? channel : PortChannel.FLUID;
        s.ports = ports != null ? ports : PortConfig.of(List.of());
        s.throughput = throughput;
        return s;
    }

    public PortChannel channel() {
        return channel;
    }

    // --- port/energy/resource accessors (read directly by network adapters + GUI) ---

    public PortConfig ports() {
        return ports;
    }

    /** @return null: a tank carries no power. */
    public EnergyHandler energy() {
        return null;
    }

    /** @return the buffer for this tank's channel, or null for any other channel. */
    public ResourceBuffer resource(PortChannel c) {
        return c == channel ? buffer : null;
    }

    /**
     * @return max units movable out of a single OUTPUT port per push for this channel.
     *
     * <p>Channel-agnostic: the single configurable {@code throughput} field applies to whatever
     * channel this tank serves.
     */
    public int throughput(PortChannel c) {
        return throughput;
    }

    public int throughput() {
        return throughput;
    }

    @Override
    public Component<ChunkStore> clone() {
        return CODEC.decode(CODEC.encode(this, EmptyExtraInfo.EMPTY), EmptyExtraInfo.EMPTY);
    }
}
