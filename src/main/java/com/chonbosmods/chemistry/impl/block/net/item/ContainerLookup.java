package com.chonbosmods.chemistry.impl.block.net.item;

import org.bson.BsonDocument;

/**
 * The mockable seam between the pure ITEM transport layer and live Hytale container access
 * (2026-06-06 item-channel design §13.4, "Architecture &rarr; Pure"). It answers "is there a storage
 * container at this block position, and how do its contents move?" without the transport code ever
 * touching {@code World}/{@code BlockModule} or an engine {@code ItemStack}. The world implementation
 * wrapping the engine arrives in Task 7 (design doc §16.6 primitives {@code getContainerState} /
 * {@code moveItemStackFromSlot} / {@code canAddItemStackToSlot}); unit tests pass a fake backed by an
 * in-memory map.
 *
 * <p>Containers are PASSIVE (design doc "Decisions"): they advertise no ports and never auto-extract.
 * The role a container plays (destination vs source) is decided entirely by the bordering pipe face's
 * {@link com.chonbosmods.chemistry.api.io.FlowState flow state} in {@link ItemEndpoints}, not by
 * anything the container itself exposes. {@code ContainerView} therefore offers only the raw
 * insert/extract operations the routing decisions need; it carries no direction or filtering opinion.
 */
@FunctionalInterface
public interface ContainerLookup {

    /**
     * The container at this block position, or {@code null} if there is no storage container there
     * (an empty cell, a pipe, or a non-container block).
     */
    ContainerView at(int x, int y, int z);

    /**
     * The operations the pure transport layer performs against a single storage container. Kept
     * deliberately minimal and engine-free (only {@link ItemKey}/{@link ItemFilter}): the world impl
     * (Task 7) bridges each call to the §16.6 engine primitives. The surface serves the three
     * consumers planned in Tasks 5/6:
     *
     * <ul>
     *   <li>{@link #insert} (simulate=true) qualifies and confirms a DESTINATION before any extraction
     *       commits, and (simulate=false) commits a delivery on arrival (Task 5 arrival / Task 6
     *       destination confirmation: "no extraction without a destination").
     *   <li>{@link #firstExtractable} drives a PULL face's per-interval scan for the first stack the
     *       filter admits (Task 6 extraction eligibility).
     *   <li>{@link #extract} commits (or simulates) the actual pull of that stack (Task 6).
     * </ul>
     *
     * <p>Design note (leaner surface than the plan sketch): the plan listed a separate
     * {@code canAccept(...)} alongside {@code insert(...)}. They are folded into a single
     * {@code insert(key, amount, simulate)} because the engine primitive
     * {@code canAddItemStackToSlot} is itself a dry-run insert: a {@code simulate=true} insert IS the
     * acceptance probe, returning the amount that would fit. One method removes a redundant call shape
     * while still serving every destination-qualification use in Tasks 5/6. Likewise the plan's
     * {@code extract(slotHint/ItemKey, ...)} drops the slot hint: {@link #firstExtractable} already
     * resolves which stack to pull (by id), so {@link #extract} keys off {@link ItemKey} alone and the
     * impl re-finds the slot, keeping the pure signature free of an engine slot index.
     */
    interface ContainerView {

        /**
         * The result of a {@link #extract} commit (or simulate): how many items came out, and the
         * extracted stack's OPAQUE engine metadata (durability/enchants/BlockHolder contents/...).
         *
         * <p>WHY this exists (design resolution, Task 6 step 5): the pure routing layer reasons over
         * {@link ItemKey} (id + count) alone and never inspects metadata, but a {@link TravelingStack}
         * must carry the extracted item's metadata so the engine stack round-trips byte-for-byte when
         * re-inserted at the destination. The metadata is only knowable by the WORLD container at the
         * moment of extraction (it reads the matched slot). Folding it into the {@code extract} return
         * is the cleanest honest seam: it keeps {@code extract} the single point that touches the
         * container's real contents, avoids a separate stateful {@code lastExtractMetadata()} accessor,
         * and lets the in-memory test fake supply {@code null} metadata trivially.
         *
         * @param amount   the number of items actually pulled (0..requested)
         * @param metadata the extracted stack's opaque metadata, or {@code null} when it has none
         *                 (the in-memory test fake and metadata-free items both pass {@code null});
         *                 carried verbatim and never inspected by the routing layer
         */
        record Extracted(int amount, BsonDocument metadata) {
        }

        /**
         * The first stack a container offers for extraction: its routing identity (id + capped count)
         * AND the matched slot's OPAQUE metadata. Returned by {@link #firstExtractable}.
         *
         * <p>WHY metadata rides along here (CRITICAL review fix, 2026-06-06): a PULL extraction confirms
         * its destination with a {@code simulate=true} {@link #insert} BEFORE committing the pull. The
         * engine stacks into an occupied destination slot only when the stacks are
         * {@code isStackableWith} (id AND durability AND metadata equal), so the confirmation simulate
         * MUST carry the REAL metadata of the stack about to travel: a same-id-but-different-metadata
         * destination slot is NOT room. Reporting only the {@link ItemKey} (id + count) would let the
         * simulate over-promise. The metadata is read here, at the same scan that finds the stack, so
         * the confirmation probe sees exactly what would be inserted on arrival.
         *
         * @param key      the first admitted stack (id + count up to the cap), never null when this Peek
         *                 is non-null
         * @param metadata that stack's opaque metadata, or {@code null} when it has none (the in-memory
         *                 test fake and metadata-free items both pass {@code null}); carried verbatim and
         *                 never inspected by the routing layer
         */
        record Peek(ItemKey key, BsonDocument metadata) {
        }

        /**
         * Insert up to {@code amount} of {@code key}'s item type into this container.
         *
         * <p>The {@code metadata} participates in stackability EXACTLY as the engine's
         * {@code isStackableWith} (id AND durability AND metadata equal): a {@code simulate=true} probe
         * counts an occupied same-id slot as room ONLY when that slot's metadata matches {@code metadata}
         * (both null, or the documents equal); empty slots always count. On commit
         * ({@code simulate=false}) the accepted items are attached to a stack carrying {@code metadata}
         * (cloned), so a damaged/enchanted/BlockHolder item delivered through a pipe round-trips
         * byte-for-byte. Pass {@code null} when the item has no metadata.
         *
         * @param key      the item type to insert (its {@code count} is ignored; {@code amount} rules)
         * @param metadata the opaque metadata to attach on commit and match for stackability; nullable
         * @param amount   the maximum number of items to insert
         * @param simulate when true, compute the acceptance without mutating (the destination probe)
         * @return the number of items accepted (0..amount); 0 means the container is full or rejects it
         */
        int insert(ItemKey key, BsonDocument metadata, int amount, boolean simulate);

        /**
         * The first stack this container offers for extraction that {@code filter} admits, reported as a
         * {@link Peek} whose {@code key.count} is the available amount capped at {@code cap} and whose
         * {@code metadata} is the matched slot's opaque metadata (for the destination-confirmation
         * simulate, see {@link Peek}).
         *
         * @param filter   the gate the candidate stack must pass; consulted per design at extraction
         * @param pipeKey  the packed key of the PULL pipe whose filter this is (for the filter call)
         * @param viaFace  the face index (0..5) the pipe presents toward this container (for the filter)
         * @param cap      the per-pull item cap; the returned {@code count} never exceeds it
         * @return the first admitted stack as a {@link Peek} (id + count up to {@code cap} + metadata),
         *         or {@code null} if the container is empty or the filter admits nothing in it
         */
        Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap);

        /**
         * Extract up to {@code amount} of {@code key}'s item type from this container.
         *
         * @param key      the item type to pull (its {@code count} is ignored; {@code amount} rules)
         * @param amount   the maximum number of items to pull
         * @param simulate when true, compute the available amount without mutating
         * @return an {@link Extracted}: the amount actually pulled (0..amount, possibly less than
         *         {@code amount} if the contents raced down between probe and commit) plus the
         *         extracted stack's opaque metadata; never null (a 0-amount extract carries null
         *         metadata)
         */
        Extracted extract(ItemKey key, int amount, boolean simulate);
    }
}
