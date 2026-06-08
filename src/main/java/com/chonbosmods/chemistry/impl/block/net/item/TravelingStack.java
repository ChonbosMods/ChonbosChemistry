package com.chonbosmods.chemistry.impl.block.net.item;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import org.bson.BsonDocument;

/**
 * One discrete item stack in flight through an ITEM pipe network (2026-06-06 item-channel design §13.4,
 * "Architecture &rarr; Pure"). It carries the item's routing identity ({@code id} + {@code count}), the
 * pre-computed {@code path} of packed pipe keys it is travelling, where along that path it currently
 * sits ({@code segmentIndex} + {@code progressTicks}), and the source/destination CONTAINER positions
 * ({@code originKey}/{@code destKey}) the re-route ladder needs.
 *
 * <h2>Opaque metadata</h2>
 * {@code metadata} is the engine {@code ItemStack}'s raw metadata document (durability, enchants,
 * BlockHolder contents, etc.), carried OPAQUELY: the routing layer NEVER inspects it (no decision
 * branches on metadata: {@link ItemKey} deliberately omits it). It rides along only so an extracted
 * engine stack round-trips byte-for-byte when re-inserted at the destination. It is nullable: a stack
 * with no metadata stores no document and its codec key is OMITTED.
 *
 * <h2>Mutable bean, not a record</h2>
 * Unlike the other pure net-layer value types (which are records), a {@code TravelingStack} ADVANCES
 * every few ticks: {@code segmentIndex} and {@code progressTicks} mutate in place as the transport
 * system steps it. A record would force a full reallocation per tick per in-flight stack. So this is a
 * small mutable bean carrying its own {@link BuilderCodec} CODEC: the same shape {@link
 * com.chonbosmods.chemistry.impl.block.net.PipeNode} itself uses (private no-arg ctor + setter/getter
 * codec fields), keeping the codebase consistent and the hot per-tick path allocation-free.
 *
 * <h2>Codec</h2>
 * All fields persist on the owning pipe's {@code PipeNode.InTransit} list. {@code Metadata} is an
 * OPTIONAL key (3-arg {@link KeyedCodec}): a null document omits it, exactly as {@code PipeNode}'s
 * {@code FlowStates}/{@code ResourceId} keys omit their defaults. {@code Path} persists via
 * {@link Codec#LONG_ARRAY}. Deep-copy is a codec round-trip (see {@link #copy()}), which clones the
 * {@link BsonDocument} and the {@code long[]} so hand-off and clone paths share no mutable state.
 */
public final class TravelingStack {

    public static final BuilderCodec<TravelingStack> CODEC =
        BuilderCodec.builder(TravelingStack.class, TravelingStack::new)
            .append(new KeyedCodec<>("Id", Codec.STRING), (o, v) -> o.id = v, o -> o.id)
                .documentation("The item type id this stack carries.")
                .add()
            .append(new KeyedCodec<>("Count", Codec.INTEGER), (o, v) -> o.count = v, o -> o.count)
                .documentation("How many items this stack carries.")
                .add()
            // Metadata is OPTIONAL (3-arg KeyedCodec like PipeNode.ResourceId): a null document omits
            // the key entirely. Carried OPAQUELY: the routing layer never reads it. BSON_DOCUMENT is not
            // a PrimitiveCodec, so a present-but-null value would also reach the setter without tripping
            // a non-null check; decode only invokes the setter for keys PRESENT in the document.
            //
            // CRITICAL: BsonDocumentCodec encode/decode are IDENTITY (no copy), so a codec round-trip
            // alone would ALIAS the metadata document across original and copy. The decode setter clones
            // it so the decoded stack owns its own metadata: this is what makes copy() (and PipeNode's
            // round-trip clone of the whole InTransit list) deeply independent.
            .append(new KeyedCodec<>("Metadata", Codec.BSON_DOCUMENT, false),
                    (o, v) -> o.metadata = v != null ? v.clone() : null, o -> o.metadata)
                .documentation("Opaque engine ItemStack metadata (durability/enchants/...); null when none.")
                .add()
            .append(new KeyedCodec<>("Path", Codec.LONG_ARRAY), (o, v) -> o.setPath(v), o -> o.path)
                .documentation("Packed pipe keys this stack travels, source pipe to destination pipe.")
                .add()
            .append(new KeyedCodec<>("SegmentIndex", Codec.INTEGER),
                    (o, v) -> o.segmentIndex = v, o -> o.segmentIndex)
                .documentation("Index into Path of the pipe this stack currently occupies.")
                .add()
            .append(new KeyedCodec<>("ProgressTicks", Codec.INTEGER),
                    (o, v) -> o.progressTicks = v, o -> o.progressTicks)
                .documentation("Ticks of progress through the current segment.")
                .add()
            .append(new KeyedCodec<>("OriginKey", Codec.LONG), (o, v) -> o.originKey = v, o -> o.originKey)
                .documentation("Packed position of the source CONTAINER this stack was extracted from.")
                .add()
            .append(new KeyedCodec<>("DestKey", Codec.LONG), (o, v) -> o.destKey = v, o -> o.destKey)
                .documentation("Packed position of the destination CONTAINER this stack is routed to.")
                .add()
            .build();

    private String id = "";
    private int count;
    /** Opaque engine ItemStack metadata; null when the stack carries none. Never read by routing. */
    private BsonDocument metadata;
    /** Packed pipe keys this stack travels. Never null (an empty array stands in for "unset"). */
    private long[] path = new long[0];
    private int segmentIndex;
    private int progressTicks;
    private long originKey;
    private long destKey;

    /** Private no-arg constructor for the codec supplier. */
    private TravelingStack() {
    }

    /**
     * A stack about to enter the network at the head of {@code path} ({@code segmentIndex} 0,
     * {@code progressTicks} 0). {@code metadata} may be null; a non-null document is deep-copied so the
     * stack owns its own metadata. {@code path} is copied defensively (never null: a null becomes empty).
     *
     * <p>PERSISTENCE INVARIANT: a stack with {@code count <= 0} or an EMPTY path is dropped by the
     * defensive decode ({@code PipeNode.DEFENSIVE_IN_TRANSIT_CODEC}): always build the route before a
     * stack can ever be saved (extraction does this up front).
     */
    public static TravelingStack of(String id, int count, BsonDocument metadata, long[] path,
                                    long originKey, long destKey) {
        TravelingStack s = new TravelingStack();
        s.id = id != null ? id : "";
        s.count = count;
        s.metadata = metadata != null ? metadata.clone() : null;
        s.setPath(path);
        s.segmentIndex = 0;
        s.progressTicks = 0;
        s.originKey = originKey;
        s.destKey = destKey;
        return s;
    }

    public String id() {
        return id;
    }

    public int count() {
        return count;
    }

    /** Mutates the count (e.g. a partial-fit arrival leaves a smaller remainder to re-route). */
    public void setCount(int count) {
        this.count = count;
    }

    /**
     * The opaque engine metadata document, or null. Returned BY REFERENCE: callers (the engine glue
     * re-building an {@code ItemStack}) read it directly; the routing layer must not. Treat as
     * effectively read-only outside the owning glue.
     */
    public BsonDocument metadata() {
        return metadata;
    }

    /** The packed pipe-key path. Returned BY REFERENCE (hot path); do not mutate in place. */
    public long[] path() {
        return path;
    }

    /** Defensive setter: a null path becomes empty, and the array is copied so we own it. */
    private void setPath(long[] path) {
        this.path = path != null ? path.clone() : new long[0];
    }

    public int segmentIndex() {
        return segmentIndex;
    }

    /** The transport system advances this as the stack crosses into the next pipe segment. */
    public void setSegmentIndex(int segmentIndex) {
        this.segmentIndex = segmentIndex;
    }

    public int progressTicks() {
        return progressTicks;
    }

    /** The transport system advances this each tick of travel through the current segment. */
    public void setProgressTicks(int progressTicks) {
        this.progressTicks = progressTicks;
    }

    public long originKey() {
        return originKey;
    }

    public long destKey() {
        return destKey;
    }

    /** Re-route/return legs re-target the stack at a new destination CONTAINER. */
    public void setDestKey(long destKey) {
        this.destKey = destKey;
    }

    /** Re-route replaces the remaining path; copied defensively so we own it. */
    public void setPathAndReset(long[] path, int segmentIndex, int progressTicks) {
        setPath(path);
        this.segmentIndex = segmentIndex;
        this.progressTicks = progressTicks;
    }

    /**
     * The minimal routing identity ({@code id} + {@code count}) the filter/pathfinder/extraction layers
     * consume. Metadata is deliberately excluded (no routing decision branches on it).
     */
    public ItemKey key() {
        return new ItemKey(id, count);
    }

    /**
     * A deep, independent copy via a codec round-trip (encode then decode): the {@link BsonDocument}
     * metadata and the {@code long[]} path both clone, so the copy shares NO mutable state with this
     * stack. Used by {@code PipeNode.clone()}'s list-copy and any hand-off that must not alias.
     */
    public TravelingStack copy() {
        return CODEC.decode(CODEC.encode(this, EmptyExtraInfo.EMPTY), EmptyExtraInfo.EMPTY);
    }
}
