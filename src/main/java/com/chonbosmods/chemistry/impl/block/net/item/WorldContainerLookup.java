package com.chonbosmods.chemistry.impl.block.net.item;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;

/**
 * Live {@link ContainerLookup} over a {@link World}: bridges the pure ITEM transport layer to the engine
 * {@code ItemContainerBlock} block-entity component (verified in the 2026-06-06 design "Spike findings
 * (Task 7)"). It is the thin world adapter behind the {@link ContainerLookup} seam; all routing logic
 * stays in the pure layer.
 *
 * <h2>Container access path (spike-verified, NOT the stale §16.6 claims)</h2>
 * The HyProTech {@code MachineItemAccess} path ({@code ItemContainerState}/{@code ItemContainerBlockState}/
 * {@code BlockStateModule}) DOES NOT EXIST in Server 0.5.3. The container moved to a plain {@code ChunkStore}
 * block-entity COMPONENT, resolved exactly like {@link com.chonbosmods.chemistry.impl.block.net.PipeNode}:
 * {@code BlockModule.getBlockEntity(world, x, y, z)} then {@code store.getComponent(ref, type)} where
 * {@code type == ItemContainerBlock.getComponentType()}. {@link ItemContainerBlock#getItemContainer()} is
 * {@code @Nonnull} (it lazily allocates a {@code SimpleItemContainer(capacity)} on first call), so a present
 * component always yields a usable {@link ItemContainer}. Component presence IS the "this block has a
 * container" test: vanilla chests declare the very same component in their BlockType JSON.
 *
 * <h2>Insert / extract against the engine {@link ItemContainer}</h2>
 * <ul>
 *   <li><b>Insert commit:</b> {@code addItemStack(stack, false, false, false)} returns an
 *       {@link ItemStackTransaction}; {@code accepted = query.qty − remainder.qty}.
 *   <li><b>Insert commit metadata:</b> a non-null metadata document is attached to the delivered stack
 *       via {@code new ItemStack(id, amount).withMetadata(metadata.clone())} (mirrors DropSink), so a
 *       damaged/enchanted/BlockHolder item delivered through a pipe round-trips byte-for-byte.
 *   <li><b>Insert simulate:</b> the engine's {@code addItemStack} has no dry-run that yields a partial
 *       count, and its partial-count test helpers are {@code protected}. So simulate is a read-only slot
 *       scan ({@code getCapacity}/{@code getItemStack}/{@code getMaxStack}): empty-slot capacity plus the
 *       room in same-id slots, capped at the requested amount. It never mutates. The commit reconciles
 *       any scan/engine divergence (the pure layer trusts the actual returned amount per the T2
 *       staleness contract).
 *       <p><b>NAMED DIVERGENCE</b> (documented residual, CRITICAL review fix): an occupied same-id slot
 *       counts as room ONLY when its metadata matches the inserting stack's ({@code Objects.equals} on
 *       the documents, both-null included) &mdash; mirroring the engine's {@code isStackableWith}
 *       (id AND durability AND metadata). The engine ALSO compares a durability field NOT exposed on the
 *       public {@code ItemStack} surface, so we cannot compare it: the residual is a CONSERVATIVE
 *       under-promise on durability-edge cases (a later re-route finds the real room: no churn). We never
 *       OVER-promise on metadata (which would cause launch-then-bounce): empty slots always count;
 *       same-id different-metadata slots never count.
 *   <li><b>Extract:</b> {@link #firstExtractable} scans slots for the first filter-admitted stack (capped);
 *       {@link #extract} commits via {@code removeItemStackFromSlot(slot, amount, false, false)} and reads
 *       the removed stack's metadata from {@code getOutput()}. Extract re-finds the slot by item id at
 *       commit time and tolerates a mid-tick race by extracting whatever is actually there.
 * </ul>
 *
 * <p>{@code filter=false} on add/remove: pipe transport is gated by OUR {@link ItemFilter} layer, not the
 * container's own slot filters (those govern player UI, not machine logistics). Defensive null-safety
 * throughout; never throws (the caller ticks on the world thread).
 */
public final class WorldContainerLookup implements ContainerLookup {

    private final World world;
    private final Store<ChunkStore> store;
    private final ComponentType<ChunkStore, ItemContainerBlock> containerType;

    public WorldContainerLookup(@Nonnull World world, @Nonnull Store<ChunkStore> store) {
        this.world = world;
        this.store = store;
        this.containerType = ItemContainerBlock.getComponentType();
    }

    @Override
    public ContainerView at(int x, int y, int z) {
        ItemContainer container = containerAt(x, y, z);
        return container == null ? null : new WorldContainerView(container);
    }

    /** Resolves the engine {@link ItemContainer} at a position, or null when there is no container block. */
    private ItemContainer containerAt(int x, int y, int z) {
        try {
            Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, x, y, z);
            if (ref == null || !ref.isValid()) {
                return null;
            }
            ItemContainerBlock block = store.getComponent(ref, containerType);
            if (block == null) {
                return null;
            }
            return block.getItemContainer(); // @Nonnull: lazily allocated, never null once the component exists
        } catch (Throwable t) {
            return null; // never throw on the world thread
        }
    }

    /** The per-container operations, wrapping one live engine {@link ItemContainer}. */
    private static final class WorldContainerView implements ContainerView {

        private final ItemContainer container;

        WorldContainerView(ItemContainer container) {
            this.container = container;
        }

        @Override
        public int insert(ItemKey key, BsonDocument metadata, int amount, boolean simulate) {
            if (key == null || amount <= 0 || key.id() == null || key.id().isEmpty()) {
                return 0;
            }
            try {
                if (simulate) {
                    return simulateInsert(key.id(), metadata, amount);
                }
                ItemStack stack = new ItemStack(key.id(), amount);
                if (metadata != null) {
                    // Mirror DropSink (ItemTransferSystem.spawnDrop): attach a cloned metadata document so
                    // the delivered stack round-trips byte-for-byte and we never alias the caller's doc.
                    stack = stack.withMetadata(metadata.clone());
                }
                ItemStackTransaction tx = container.addItemStack(stack, false, false, false);
                if (tx == null || !tx.succeeded()) {
                    // A failed transaction still reports the remainder; succeeded() is false only when
                    // NOTHING went in. Compute from the remainder either way (0 when fully rejected).
                    return tx == null ? 0 : accepted(amount, tx.getRemainder());
                }
                return accepted(amount, tx.getRemainder());
            } catch (Throwable t) {
                return 0;
            }
        }

        /**
         * Read-only "how much of {@code id} (with {@code metadata}) would fit" scan (spike finding: no
         * engine dry-run yields a partial count). Sums room in STACKABLE same-id slots plus empty-slot
         * capacity, capped at {@code amount}. Mirrors the engine's {@code testAddTo*} grain using only
         * public accessors.
         *
         * <p>NAMED DIVERGENCE from the engine's {@code isStackableWith} (documented residual, CRITICAL
         * review fix): an occupied same-id slot counts as room ONLY when its metadata matches the
         * inserting stack's ({@link java.util.Objects#equals} on the documents, both-null included). The
         * engine's {@code isStackableWith} ALSO compares a durability field that is not exposed on the
         * public {@link ItemStack} surface, so we cannot replicate it here. The consequence is a
         * CONSERVATIVE under-promise on durability-edge cases (two same-id same-metadata stacks the
         * engine would actually merge might be counted as non-stackable if their hidden durability
         * differs): a later re-route simply finds the real room with no churn. We never OVER-promise on
         * metadata (that would cause launch-then-bounce), which is the failure mode this fix exists to
         * prevent: the commit always reconciles via the real {@code addItemStack} remainder (T2
         * staleness contract).
         */
        private int simulateInsert(String id, BsonDocument metadata, int amount) {
            int maxStack = maxStackOf(id);
            if (maxStack <= 0) {
                return 0;
            }
            short capacity = container.getCapacity();
            int room = 0;
            for (short slot = 0; slot < capacity && room < amount; slot++) {
                ItemStack inSlot = container.getItemStack(slot);
                if (inSlot == null || ItemStack.isEmpty(inSlot)) {
                    room += maxStack; // an empty slot can take a full stack of any id
                } else if (id.equals(inSlot.getItemId())
                        && java.util.Objects.equals(metadata, inSlot.getMetadata())) {
                    // Same id AND matching metadata: the engine would stack into this slot.
                    int free = maxStack - inSlot.getQuantity();
                    if (free > 0) {
                        room += free;
                    }
                }
            }
            return Math.min(amount, room);
        }

        @Override
        public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
            if (cap <= 0) {
                return null;
            }
            try {
                short capacity = container.getCapacity();
                for (short slot = 0; slot < capacity; slot++) {
                    ItemStack inSlot = container.getItemStack(slot);
                    if (inSlot == null || ItemStack.isEmpty(inSlot)) {
                        continue;
                    }
                    String id = inSlot.getItemId();
                    if (id == null || id.isEmpty()) {
                        continue;
                    }
                    int available = Math.min(inSlot.getQuantity(), cap);
                    if (available <= 0) {
                        continue;
                    }
                    ItemKey candidate = new ItemKey(id, available);
                    if (filter == null || filter.admits(candidate, pipeKey, viaFace)) {
                        // Carry the matched slot's metadata so the destination-confirmation simulate sees
                        // the REAL stack about to travel (engine isStackableWith compares metadata).
                        return new Peek(candidate, cloneMetadata(inSlot.getMetadata()));
                    }
                }
                return null;
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public Extracted extract(ItemKey key, int amount, boolean simulate) {
            if (key == null || amount <= 0 || key.id() == null || key.id().isEmpty()) {
                return new Extracted(0, null);
            }
            try {
                String id = key.id();
                short capacity = container.getCapacity();
                // Re-find the slot by id at commit time (staleness contract: contents may have raced).
                for (short slot = 0; slot < capacity; slot++) {
                    ItemStack inSlot = container.getItemStack(slot);
                    if (inSlot == null || ItemStack.isEmpty(inSlot) || !id.equals(inSlot.getItemId())) {
                        continue;
                    }
                    int take = Math.min(amount, inSlot.getQuantity());
                    if (take <= 0) {
                        continue;
                    }
                    if (simulate) {
                        return new Extracted(take, cloneMetadata(inSlot.getMetadata()));
                    }
                    ItemStackSlotTransaction tx = container.removeItemStackFromSlot(slot, take, false, false);
                    if (tx == null || !tx.succeeded()) {
                        continue; // raced empty on this slot; try the next matching slot
                    }
                    ItemStack out = tx.getOutput();
                    int got = out == null ? 0 : out.getQuantity();
                    if (got <= 0) {
                        continue;
                    }
                    BsonDocument metadata = out == null ? null : cloneMetadata(out.getMetadata());
                    return new Extracted(got, metadata);
                }
                return new Extracted(0, null);
            } catch (Throwable t) {
                return new Extracted(0, null);
            }
        }

        private static int accepted(int requested, ItemStack remainder) {
            int left = remainder == null || ItemStack.isEmpty(remainder) ? 0 : remainder.getQuantity();
            int acc = requested - left;
            return Math.max(0, acc);
        }

        private static int maxStackOf(String id) {
            try {
                Item item = new ItemStack(id, 1).getItem();
                return item == null ? 0 : item.getMaxStack();
            } catch (Throwable t) {
                return 0;
            }
        }

        private static BsonDocument cloneMetadata(BsonDocument metadata) {
            return metadata == null ? null : metadata.clone();
        }
    }
}
