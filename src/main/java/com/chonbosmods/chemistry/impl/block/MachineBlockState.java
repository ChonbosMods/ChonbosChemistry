package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.builtin.crafting.component.BenchBlock;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
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
 * throughput cap. Wraps the Phase A beans and exposes them through accessors
 * ({@code ports()}/{@code energy()}/{@code resource(channel)}/{@code throughput(channel)}) that the
 * network endpoint adapters and GUI read directly.
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
public final class MachineBlockState implements Component<ChunkStore> {

    /**
     * Shared non-primitive {@code Codec<Long>} (also a {@code RawJsonCodec<Long>}) for optional Long
     * keys whose absent/null value must reach the setter. See {@link OptionalLongCodec} for the full
     * rationale (PrimitiveCodec-null-bypass invariant + raw-JSON path).
     */
    private static final Codec<Long> OPTIONAL_LONG = OptionalLongCodec.INSTANCE;

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
        // Test-rig "burning sink" flag: when > 0 the tick system extracts this many energy units from
        // this node's own buffer each tick (the machine consuming its own power), so a sink's stored
        // value visibly drains. OPTIONAL with default 0: see OPTIONAL_LONG above for why an absent or
        // explicit-null key must reach the setter (3-arg KeyedCodec not-required) where it coalesces
        // to 0 rather than tripping the primitive non-null check.
        .append(new KeyedCodec<>("EnergyDrainPerTick", OPTIONAL_LONG, false),
                (o, v) -> o.energyDrainPerTick = v == null ? 0L : v, o -> o.energyDrainPerTick).add()
        // The vanilla bench engine (smelter recipe/slots/progress) and its sibling tier-carrier, HELD as
        // internal fields (not registered ECS components: D31 keeps vanilla's ProcessingBenchTick from
        // ever seeing our block). Both are OPTIONAL: a machine without a bench (or a pre-existing
        // persisted machine) decodes fine with the keys absent -> the fields stay null. Mirrors the
        // "Energy" key above: ProcessingBenchBlock.CODEC / BenchBlock.CODEC are object codecs, so a null
        // value is simply omitted on encode and an absent key decodes back to null. A present bench
        // round-trips the vanilla bench data via the vanilla codecs.
        .append(new KeyedCodec<>("HeldBench", ProcessingBenchBlock.CODEC),
                (o, v) -> o.heldBench = v, o -> o.heldBench).add()
        .append(new KeyedCodec<>("HeldBenchBlock", BenchBlock.CODEC),
                (o, v) -> o.heldBenchBlock = v, o -> o.heldBenchBlock).add()
        .build();

    private static final int DEFAULT_THROUGHPUT = 100;

    private EnergyBuffer energy;
    private final EnumMap<PortChannel, ResourceBuffer> resources = new EnumMap<>(PortChannel.class);
    private PortConfig ports = PortConfig.of(List.of());
    private WorkState work = new WorkState();
    private int throughput = DEFAULT_THROUGHPUT;
    private boolean creativeSource;
    // Energy units this machine burns from its own buffer each tick (0 = no self-drain). Default 0 so
    // an absent codec key leaves a normal machine non-draining.
    private long energyDrainPerTick;
    // The held vanilla bench engine + its sibling tier-carrier. Nullable: a non-bench machine (or a
    // pre-existing persisted machine) carries neither, so the optional codec keys decode to null. Set
    // by the smelter placement path (Task 12) and driven by our own tick (Task 8) via VanillaBenchBridge.
    private ProcessingBenchBlock heldBench;
    private BenchBlock heldBenchBlock;

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

    // --- port/energy/resource accessors (read directly by network adapters + GUI) ---

    public PortConfig ports() {
        return ports;
    }

    /**
     * Replaces this machine's port configuration. Used by the {@code CC_Wrench} interaction to persist a
     * cycled face port (the caller rebuilds the {@link PortConfig} with the face's port replaced, never
     * appended, so a face never carries two ports). A null config resets to empty. The mutation is
     * persisted by the engine's ECS save path; the wrench marks the block component chunk needing-save.
     */
    public void setPorts(PortConfig ports) {
        this.ports = ports != null ? ports : PortConfig.of(List.of());
    }

    /** @return the energy handler, or null if this node carries no power. */
    public EnergyHandler energy() {
        return energy;
    }

    /** @return the resource buffer for a fluid/gas/item channel, or null if this node has none. */
    public ResourceBuffer resource(PortChannel channel) {
        return resources.get(channel);
    }

    /**
     * @return max units movable out of a single OUTPUT port per push for this channel.
     *
     * <p>Channel-agnostic for now: every channel shares the single configurable {@code throughput}
     * field. A later task may split this into per-channel caps if machines need it.
     */
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
     * @return energy units this machine burns from its own buffer each tick (0 = none). Drives the
     *     test-rig "burning sink": the tick system calls {@code extractEnergyInternal(drain, false)}
     *     so the sink's stored energy visibly drains after the network fills it.
     */
    public long energyDrainPerTick() {
        return energyDrainPerTick;
    }

    public void setEnergyDrainPerTick(long energyDrainPerTick) {
        this.energyDrainPerTick = energyDrainPerTick;
    }

    /**
     * @return the held vanilla {@link ProcessingBenchBlock} (smelter recipe/slots/progress engine), or
     *     null if this machine carries no bench. Held as an internal field (never a registered ECS
     *     component) so vanilla never ticks it; our own tick drives it via {@code VanillaBenchBridge}.
     */
    public ProcessingBenchBlock heldBench() {
        return heldBench;
    }

    public void setHeldBench(ProcessingBenchBlock heldBench) {
        this.heldBench = heldBench;
    }

    /**
     * @return the held sibling {@link BenchBlock} (carries the bench tier), or null if this machine
     *     carries no bench. Rides alongside {@link #heldBench()} and is passed back into the bench
     *     engine by {@code VanillaBenchBridge.advance(...)}.
     */
    public BenchBlock heldBenchBlock() {
        return heldBenchBlock;
    }

    public void setHeldBenchBlock(BenchBlock heldBenchBlock) {
        this.heldBenchBlock = heldBenchBlock;
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
