package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.item.TravelingStack;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonValue;
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
 * <h2>Optional in-transit item stacks</h2>
 * An ITEM pipe carries zero or more {@link TravelingStack}s currently flowing through it. They persist
 * on an OPTIONAL {@code "InTransit"} codec key following the {@code FlowStates} pattern EXACTLY: the
 * encode getter returns null for an empty list so the key is OMITTED entirely (existing world saves stay
 * byte-identical; absent-key-means-none is honest). Decode is DEFENSIVE: the custom element-tolerant
 * codec drops any null, malformed, or degenerate entry (empty path or non-positive count) without
 * throwing: a corrupt save must never crash chunk load (hot ECS data). A stack is owned by exactly the
 * one pipe segment it occupies, so the HAND-OFF between segments (Task 5) is {@code removeInTransit} on
 * pipe A then {@code addInTransit} on pipe B: the accessor surface is shaped for that move.
 *
 * <h2>clone()</h2>
 * Deep copy via a codec round-trip (encode then decode), reusing the tested codec, so each placed
 * pipe receives an independent share/state. The in-transit list round-trips through the same path; each
 * {@link TravelingStack}'s decode clones its opaque metadata document (BsonDocument encode/decode are
 * identity, so the clone happens in the stack's decode setter), making the clone deeply independent.
 */
public final class PipeNode implements Component<ChunkStore> {

    private static final int DEFAULT_TIER = 1;

    /** Number of cube faces a pipe carries flow state for, indexed in {@code OFFSETS} order. */
    private static final int FACE_COUNT = 6;

    /**
     * Element-tolerant codec for the {@code "InTransit"} list of {@link TravelingStack}s. Encode writes a
     * BSON array of each stack via {@link TravelingStack#CODEC}. Decode is DEFENSIVE: it reads the array
     * element-by-element, wrapping each {@link TravelingStack#CODEC} decode in a try/catch so a malformed
     * entry (a garbage scalar, an incomplete document) is DROPPED rather than throwing: the stock
     * {@link ArrayCodec} rethrows any element failure as a {@code CodecException}, which would crash chunk
     * load on a corrupt save. Degenerate-but-decodable entries (empty path or non-positive count: not a
     * real in-flight stack) are dropped too. Returns null on a non-array value (treated as "none").
     */
    private static final Codec<TravelingStack[]> DEFENSIVE_IN_TRANSIT_CODEC = new Codec<>() {
        @Override
        public BsonValue encode(TravelingStack[] value, ExtraInfo extraInfo) {
            BsonArray array = new BsonArray();
            if (value != null) {
                for (TravelingStack stack : value) {
                    if (stack != null) {
                        array.add(TravelingStack.CODEC.encode(stack, extraInfo));
                    }
                }
            }
            return array;
        }

        @Override
        public TravelingStack[] decode(BsonValue value, ExtraInfo extraInfo) {
            if (value == null || !value.isArray()) {
                return null;
            }
            BsonArray array = value.asArray();
            List<TravelingStack> out = new ArrayList<>(array.size());
            for (BsonValue element : array) {
                if (element == null || !element.isDocument()) {
                    continue; // garbage scalar entry: drop it
                }
                try {
                    TravelingStack stack = TravelingStack.CODEC.decode(element, extraInfo);
                    // A real in-flight stack always travels a non-empty path and carries items: a
                    // degenerate decode (incomplete document) is dropped.
                    if (stack != null && stack.path().length > 0 && stack.count() > 0) {
                        out.add(stack);
                    }
                } catch (RuntimeException dropMalformed) {
                    // Defensive: a corrupt entry must not crash chunk load. Drop and continue.
                }
            }
            return out.toArray(new TravelingStack[0]);
        }

        @Override
        public com.hypixel.hytale.codec.schema.config.Schema toSchema(
                com.hypixel.hytale.codec.schema.SchemaContext context) {
            // Schema is a BSON array of TravelingStack documents (the tolerant decode is a runtime detail).
            return new ArrayCodec<>(TravelingStack.CODEC, TravelingStack[]::new).toSchema(context);
        }
    };

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
        // InTransit is OPTIONAL (3-arg KeyedCodec like FlowStates): the getter returns null for an empty
        // list so the key is OMITTED, leaving existing saves untouched; decode only fires the setter for a
        // present key. The backing codec is element-TOLERANT (drops malformed/degenerate entries) so a
        // corrupt save never crashes chunk load.
        .append(new KeyedCodec<>("InTransit", DEFENSIVE_IN_TRANSIT_CODEC, false),
                (o, v) -> o.setInTransitFromCodec(v), PipeNode::inTransitForCodec)
            .documentation("Item stacks currently flowing through this pipe; absent means none.")
            .add()
        .build();

    private PortChannel channel = PortChannel.POWER;
    private int tier = DEFAULT_TIER;
    private long bufferShare;
    private String resourceId;
    /** Per-face flow states, indexed in {@code OFFSETS} order +X,-X,+Y,-Y,+Z,-Z; defaults all NORMAL. */
    private final FlowState[] flowStates = newAllNormal();
    /** Item stacks currently flowing through this pipe; empty for non-ITEM or idle pipes. */
    private final List<TravelingStack> inTransit = new ArrayList<>();

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
     * The item stacks currently flowing through this pipe, as a DEFENSIVE COPY (a fresh modifiable list):
     * structurally mutating the returned list (add/remove/clear) does NOT affect the node, so callers must
     * route structural changes through {@link #addInTransit}/{@link #removeInTransit}/{@link
     * #clearInTransit}. The {@link TravelingStack} elements ARE the live mutable objects, though, so a
     * caller advancing a stack's progress in place is reflected on the node (the per-tick transport path
     * relies on this). Never null; empty for non-ITEM or idle pipes.
     */
    public List<TravelingStack> inTransit() {
        return new ArrayList<>(inTransit);
    }

    /** Adds a stack to this pipe's in-transit set (the "arrive at pipe B" half of a segment hand-off). */
    public void addInTransit(TravelingStack stack) {
        if (stack != null) {
            inTransit.add(stack);
        }
    }

    /**
     * Removes a stack from this pipe's in-transit set by IDENTITY (reference equality), the "leave pipe A"
     * half of a segment hand-off. Identity, not {@code equals}: {@link TravelingStack}s are mutable beans
     * with no value identity, and two distinct stacks can momentarily share every field value; removing by
     * value would drop the wrong one. Returns true if a stack was removed.
     */
    public boolean removeInTransit(TravelingStack stack) {
        if (stack == null) {
            return false;
        }
        for (int i = 0; i < inTransit.size(); i++) {
            if (inTransit.get(i) == stack) {
                inTransit.remove(i);
                return true;
            }
        }
        return false;
    }

    /** Clears all in-transit stacks (used by the wipe/snapshot restore path in Task 11). */
    public void clearInTransit() {
        inTransit.clear();
    }

    /**
     * Encode getter: returns the in-transit stacks only when the list is non-empty, else null so the
     * codec OMITS the key (keeping idle/non-ITEM pipes byte-identical to pre-item-channel saves).
     */
    private static TravelingStack[] inTransitForCodec(PipeNode node) {
        if (node.inTransit.isEmpty()) {
            return null;
        }
        return node.inTransit.toArray(new TravelingStack[0]);
    }

    /**
     * Decode setter: replaces the in-transit list with a present "InTransit" array. The backing codec has
     * already dropped malformed/degenerate entries, so this only filters a stray null. Defensive: never
     * throws.
     */
    private void setInTransitFromCodec(TravelingStack[] decoded) {
        inTransit.clear();
        if (decoded == null) {
            return;
        }
        for (TravelingStack stack : decoded) {
            if (stack != null) {
                inTransit.add(stack);
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
