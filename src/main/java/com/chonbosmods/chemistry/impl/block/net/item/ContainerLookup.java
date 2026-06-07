package com.chonbosmods.chemistry.impl.block.net.item;

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
         * Insert up to {@code amount} of {@code key}'s item type into this container.
         *
         * @param key      the item type to insert (its {@code count} is ignored; {@code amount} rules)
         * @param amount   the maximum number of items to insert
         * @param simulate when true, compute the acceptance without mutating (the destination probe)
         * @return the number of items accepted (0..amount); 0 means the container is full or rejects it
         */
        int insert(ItemKey key, int amount, boolean simulate);

        /**
         * The first stack this container offers for extraction that {@code filter} admits, reported as
         * an {@link ItemKey} whose {@code count} is the available amount capped at {@code cap}.
         *
         * @param filter   the gate the candidate stack must pass; consulted per design at extraction
         * @param pipeKey  the packed key of the PULL pipe whose filter this is (for the filter call)
         * @param viaFace  the face index (0..5) the pipe presents toward this container (for the filter)
         * @param cap      the per-pull item cap; the returned {@code count} never exceeds it
         * @return the first admitted stack (id + count up to {@code cap}), or {@code null} if the
         *         container is empty or the filter admits nothing in it
         */
        ItemKey firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap);

        /**
         * Extract up to {@code amount} of {@code key}'s item type from this container.
         *
         * @param key      the item type to pull (its {@code count} is ignored; {@code amount} rules)
         * @param amount   the maximum number of items to pull
         * @param simulate when true, compute the available amount without mutating
         * @return the number of items actually pulled (0..amount)
         */
        int extract(ItemKey key, int amount, boolean simulate);
    }
}
