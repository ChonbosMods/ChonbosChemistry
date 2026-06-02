package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The persistent ECS state a machine block carries on the {@link ChunkStore}: its energy buffer,
 * its per-channel resource buffers, its port configuration, its work progress, and its transport
 * throughput cap. Wraps the Phase A beans and exposes them through {@link TransferNode} so the
 * transport engine can drive any machine uniformly.
 *
 * <h2>Resources codec design</h2>
 * The in-memory shape is an {@code EnumMap<PortChannel, ResourceBuffer>} (fast lookup by channel),
 * but an EnumMap is awkward to round-trip through {@link BuilderCodec}. The canonical persisted form
 * is therefore a {@code List<ChanneledResource>} (each a {channel, buffer} pair). The map is rebuilt
 * from that list after decode; both representations are kept consistent because the codec setter
 * repopulates the map. N is tiny (at most one entry per FLUID/GAS/ITEM channel).
 *
 * <h2>clone()</h2>
 * Deep copy is performed by a codec round-trip (encode then decode), reusing the tested bean codecs.
 * This guarantees each placed block receives fully independent buffers.
 */
public final class MachineBlockState implements Component<ChunkStore>, TransferNode {

    public static final BuilderCodec<MachineBlockState> CODEC = BuilderCodec.builder(MachineBlockState.class, MachineBlockState::new)
        // Energy is optional: a node may carry no power. EnergyBuffer.CODEC is an object codec, so a
        // null value is simply omitted on encode and decodes back to null.
        .append(new KeyedCodec<>("Energy", EnergyBuffer.CODEC), (o, v) -> o.energy = v, o -> o.energy).add()
        .append(new KeyedCodec<>("Resources", new ArrayCodec<>(ChanneledResource.CODEC, ChanneledResource[]::new)),
            MachineBlockState::setResourcesFromArray, MachineBlockState::resourcesToArray).add()
        .append(new KeyedCodec<>("Ports", PortConfig.CODEC), (o, v) -> o.ports = v, o -> o.ports).add()
        .append(new KeyedCodec<>("Work", WorkState.CODEC), (o, v) -> o.work = v, o -> o.work).add()
        .append(new KeyedCodec<>("Throughput", Codec.INTEGER), (o, v) -> o.throughput = v, o -> o.throughput).add()
        // Test-rig flag: when true the tick system refills this node's energy buffer each tick so it
        // acts as an infinite power source (e.g. a creative Power Cell), no generator/recipe needed.
        .append(new KeyedCodec<>("CreativeSource", Codec.BOOLEAN), (o, v) -> o.creativeSource = v, o -> o.creativeSource).add()
        .build();

    private static final int DEFAULT_THROUGHPUT = 100;

    private EnergyBuffer energy;
    private final EnumMap<PortChannel, ResourceBuffer> resources = new EnumMap<>(PortChannel.class);
    private PortConfig ports = PortConfig.of(List.of());
    private WorkState work = new WorkState();
    private int throughput = DEFAULT_THROUGHPUT;
    private boolean creativeSource;

    /** Public no-arg constructor for the codec supplier. */
    public MachineBlockState() {
    }

    public static MachineBlockState create(
            EnergyBuffer energy,
            Map<PortChannel, ResourceBuffer> resources,
            PortConfig ports,
            WorkState work,
            int throughput) {
        MachineBlockState s = new MachineBlockState();
        s.energy = energy;
        if (resources != null) {
            s.resources.putAll(resources);
        }
        s.ports = ports != null ? ports : PortConfig.of(List.of());
        s.work = work != null ? work : new WorkState();
        s.throughput = throughput;
        return s;
    }

    // --- codec glue for the EnumMap <-> List<ChanneledResource> bridge ---

    private void setResourcesFromArray(ChanneledResource[] entries) {
        resources.clear();
        if (entries != null) {
            for (ChanneledResource entry : entries) {
                if (entry != null && entry.channel() != null && entry.buffer() != null) {
                    resources.put(entry.channel(), entry.buffer());
                }
            }
        }
    }

    private ChanneledResource[] resourcesToArray() {
        List<ChanneledResource> list = new ArrayList<>(resources.size());
        for (Map.Entry<PortChannel, ResourceBuffer> e : resources.entrySet()) {
            list.add(ChanneledResource.of(e.getKey(), e.getValue()));
        }
        return list.toArray(new ChanneledResource[0]);
    }

    // --- TransferNode ---

    @Override
    public PortConfig ports() {
        return ports;
    }

    @Override
    public EnergyHandler energy() {
        return energy;
    }

    @Override
    public ResourceBuffer resource(PortChannel channel) {
        return resources.get(channel);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Channel-agnostic for now: every channel shares the single configurable {@code throughput}
     * field. A later task may split this into per-channel caps if machines need it.
     */
    @Override
    public int throughput(PortChannel channel) {
        return throughput;
    }

    public WorkState work() {
        return work;
    }

    public int throughput() {
        return throughput;
    }

    /**
     * @return whether this machine is a creative (infinite) power source: when true, the tick system
     *     tops up its energy buffer to full each tick before the transport pass so it can always push.
     */
    public boolean isCreativeSource() {
        return creativeSource;
    }

    public void setCreativeSource(boolean creativeSource) {
        this.creativeSource = creativeSource;
    }

    /**
     * Deep copy via codec round-trip: this is for placement/copy only (one-time block placement),
     * NOT the per-tick hot path. The tick system mutates the live component in place and never clones.
     */
    @Override
    public Component<ChunkStore> clone() {
        return CODEC.decode(CODEC.encode(this, EmptyExtraInfo.EMPTY), EmptyExtraInfo.EMPTY);
    }
}
