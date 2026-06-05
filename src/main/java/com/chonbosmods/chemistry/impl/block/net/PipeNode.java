package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.chonbosmods.chemistry.ChonbosChemistry;

/**
 * The persistent ECS state a pipe block carries on the {@link ChunkStore}. A pipe is NOT a machine:
 * it owns no recipe, no port configuration, and no work progress. It carries only what the transport
 * network layer needs to rebuild a {@code Network} and re-hydrate its shared buffer after a reload:
 * the transport {@link PortChannel channel} it serves, its {@code tier} (later mapping to
 * capacity/throughput), its persisted {@code bufferShare} of the network's shared buffer, and (for
 * FLUID/GAS) the {@code resourceId} that share holds.
 *
 * <h2>Optional resourceId</h2>
 * {@code resourceId} is null for POWER pipes and for empty FLUID/GAS pipes. Its codec key is marked
 * not-required (3-arg {@link KeyedCodec}); {@link BuilderCodec#decode} only invokes a setter for keys
 * PRESENT in the document, so an absent "ResourceId" leaves the field at its default of null. The
 * backing {@code StringCodec} is not a {@code PrimitiveCodec}, so a present-but-null value would also
 * reach the setter without tripping a primitive non-null check.
 *
 * <h2>clone()</h2>
 * Deep copy via a codec round-trip (encode then decode), reusing the tested codec, so each placed
 * pipe receives an independent share/state.
 */
public final class PipeNode implements Component<ChunkStore> {

    private static final int DEFAULT_TIER = 1;

    public static final BuilderCodec<PipeNode> CODEC = BuilderCodec.builder(PipeNode.class, PipeNode::new)
        .append(new KeyedCodec<>("Channel", PortChannel.CODEC), (o, v) -> o.channel = v, o -> o.channel)
            .documentation("The transport channel (ITEM/FLUID/GAS/POWER) this pipe carries.")
            .add()
        .append(new KeyedCodec<>("Tier", Codec.INTEGER), (o, v) -> o.tier = v, o -> o.tier)
            .documentation("Pipe tier; later maps to capacity/throughput.")
            .add()
        .append(new KeyedCodec<>("BufferShare", Codec.LONG), (o, v) -> o.bufferShare = v, o -> o.bufferShare)
            .documentation("This pipe's persisted share of its network's shared buffer.")
            .add()
        // ResourceId is OPTIONAL: null for POWER or an empty pipe. Marked not-required so an absent key
        // simply leaves the field at its null default (decode only invokes setters for present keys).
        .append(new KeyedCodec<>("ResourceId", Codec.STRING, false), (o, v) -> o.resourceId = v, o -> o.resourceId)
            .documentation("For FLUID/GAS, the resource held in the share; null for POWER or an empty pipe.")
            .add()
        .build();

    private PortChannel channel = PortChannel.POWER;
    private int tier = DEFAULT_TIER;
    private long bufferShare;
    private String resourceId;

    /** Private no-arg constructor for the codec supplier. */
    private PipeNode() {
    }

    /** A pipe carrying the given channel at the given tier, with an empty buffer share and no resource. */
    public static PipeNode of(PortChannel channel, int tier) {
        PipeNode p = new PipeNode();
        p.channel = channel != null ? channel : PortChannel.POWER;
        p.tier = tier;
        p.bufferShare = 0L;
        p.resourceId = null;
        return p;
    }

    public PortChannel channel() {
        return channel;
    }

    public int tier() {
        return tier;
    }

    public long bufferShare() {
        return bufferShare;
    }

    /** Persistence (task H6) sets this pipe's share of the network's shared buffer. */
    public void setBufferShare(long bufferShare) {
        this.bufferShare = bufferShare;
    }

    public String resourceId() {
        return resourceId;
    }

    /** Persistence (task H6) sets the resource id held in this pipe's share; null clears it. */
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    /**
     * Deep copy via codec round-trip: for placement/copy only (one-time block placement), guaranteeing
     * each placed pipe receives an independent buffer share and state.
     */
    @Override
    public Component<ChunkStore> clone() {
        return CODEC.decode(CODEC.encode(this, EmptyExtraInfo.EMPTY), EmptyExtraInfo.EMPTY);
    }

    /** The {@link ChunkStore} component type backing pipe blocks, resolved via the plugin singleton. */
    public static ComponentType<ChunkStore, PipeNode> getComponentType() {
        return ChonbosChemistry.getInstance().pipeComponentType();
    }
}
