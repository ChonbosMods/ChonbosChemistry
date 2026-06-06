package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.Arrays;
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
 * <h2>Optional per-face flowStates</h2>
 * Each of the 6 faces carries a {@link FlowState} (Mekanism-style push/pull config), indexed in the
 * canonical {@code NetworkManager.OFFSETS} order {@code +X,-X,+Y,-Y,+Z,-Z}. Like {@code resourceId},
 * the {@code "FlowStates"} codec key is OPTIONAL (3-arg {@link KeyedCodec}). When every face is
 * {@link FlowState#NORMAL NORMAL} (the default), the encode getter returns null so the key is OMITTED
 * entirely: this keeps existing world saves byte-identical and makes the absent-key-means-all-NORMAL
 * default honest (a {@code BuilderField} skips {@code put} for a null getter result, and decode only
 * invokes the setter for present keys). The wire shape is a BSON array of FlowState strings via
 * {@link ArrayCodec} over {@link FlowState#CODEC}: the codec library offers no generic list/collection
 * factory, but its element-codec {@code ArrayCodec(Codec, IntFunction)} is exactly the clean list
 * support the design prefers over a comma-joined string.
 *
 * <h2>clone()</h2>
 * Deep copy via a codec round-trip (encode then decode), reusing the tested codec, so each placed
 * pipe receives an independent share/state.
 */
public final class PipeNode implements Component<ChunkStore> {

    private static final int DEFAULT_TIER = 1;

    /** Number of cube faces a pipe carries flow state for, indexed in {@code OFFSETS} order. */
    private static final int FACE_COUNT = 6;

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
        // FlowStates is OPTIONAL (3-arg KeyedCodec like ResourceId): the getter returns null when every
        // face is NORMAL so the key is omitted, leaving existing saves untouched; decode only fires the
        // setter for a present key, so an absent key leaves all faces at their NORMAL default.
        .append(new KeyedCodec<>("FlowStates", new ArrayCodec<>(FlowState.CODEC, FlowState[]::new), false),
                (o, v) -> o.setFlowStatesFromCodec(v), PipeNode::flowStatesForCodec)
            .documentation("Per-face flow states in +X,-X,+Y,-Y,+Z,-Z order; absent means all NORMAL.")
            .add()
        .build();

    private PortChannel channel = PortChannel.POWER;
    private int tier = DEFAULT_TIER;
    private long bufferShare;
    private String resourceId;
    /** Per-face flow states, indexed in {@code OFFSETS} order +X,-X,+Y,-Y,+Z,-Z; defaults all NORMAL. */
    private final FlowState[] flowStates = newAllNormal();

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
     * The flow state of the given face (0..5 in {@code OFFSETS} order +X,-X,+Y,-Y,+Z,-Z). Out-of-range
     * indices return {@link FlowState#NORMAL} rather than throwing: this is hot ECS data.
     */
    public FlowState flowState(int face) {
        if (face < 0 || face >= FACE_COUNT) {
            return FlowState.NORMAL;
        }
        return flowStates[face];
    }

    /**
     * Sets the flow state of the given face. Out-of-range indices and a null state are silently ignored
     * (never throws): this is hot ECS data.
     */
    public void setFlowState(int face, FlowState state) {
        if (face < 0 || face >= FACE_COUNT || state == null) {
            return;
        }
        flowStates[face] = state;
    }

    /** A fresh 6-element array with every face NORMAL. */
    private static FlowState[] newAllNormal() {
        FlowState[] states = new FlowState[FACE_COUNT];
        Arrays.fill(states, FlowState.NORMAL);
        return states;
    }

    /**
     * Encode getter: returns the flow-state array only when at least one face is non-NORMAL, else null
     * so the codec OMITS the key (keeping all-NORMAL pipes byte-identical to pre-flow-state saves).
     */
    private static FlowState[] flowStatesForCodec(PipeNode node) {
        for (FlowState s : node.flowStates) {
            if (s != FlowState.NORMAL) {
                return node.flowStates.clone();
            }
        }
        return null;
    }

    /**
     * Decode setter: copies a present "FlowStates" array into our faces, clamping to 6 and treating any
     * null/short entry as NORMAL. Defensive against malformed documents: never throws.
     */
    private void setFlowStatesFromCodec(FlowState[] decoded) {
        Arrays.fill(flowStates, FlowState.NORMAL);
        if (decoded == null) {
            return;
        }
        int n = Math.min(decoded.length, FACE_COUNT);
        for (int i = 0; i < n; i++) {
            if (decoded[i] != null) {
                flowStates[i] = decoded[i];
            }
        }
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
