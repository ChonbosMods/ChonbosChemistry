package com.chonbosmods.chemistry.impl.block.net.item;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import java.util.Objects;
import org.bson.BsonDocument;

/**
 * The pure ITEM transport layer's {@link ContainerLookup.ContainerView} operations against ONE live
 * engine {@link ItemContainer}: insert / firstExtractable / extract. Extracted from
 * {@link WorldContainerLookup} (where it was the chest adapter) so the SAME wrapper backs any engine
 * {@link ItemContainer} endpoint — a vanilla chest's container OR a machine's held-bench input/output
 * container (Task 14). All routing logic stays in the pure layer; this is the thin engine bridge.
 *
 * <p>Insert/extract semantics (spike-verified, see {@link WorldContainerLookup} javadoc for the full
 * rationale): commit via {@code addItemStack}/{@code removeItemStackFromSlot} with cloned metadata so a
 * damaged/enchanted/BlockHolder item round-trips byte-for-byte; simulate is a read-only slot scan
 * (no engine dry-run yields a partial count). The metadata stackability match mirrors the engine's
 * {@code isStackableWith} (id AND metadata; the hidden durability field is a documented conservative
 * under-promise, never an over-promise). Insert (commit + simulate) HONORS the destination container's
 * own slot filter so a filtered machine input (e.g. a Smelter/Reclaimer input that admits only valid
 * recipe ingredients) rejects non-admitted items pushed by the network: commit passes
 * {@code applyFilter=true} to {@code addItemStack}, and simulate probes each slot via
 * {@code canAddItemStackToSlot(.., applyFilter=true)}. Unfiltered containers (chests) admit everything,
 * so both paths are unchanged for them. Extraction stays gated solely by OUR {@link ItemFilter}
 * ({@code filter=false} on remove). Defensive throughout; never throws.
 */
public final class ItemContainerView implements ContainerLookup.ContainerView {

    private final ItemContainer container;

    public ItemContainerView(ItemContainer container) {
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
                // Attach a cloned metadata document so the delivered stack round-trips byte-for-byte and
                // we never alias the caller's doc (mirrors DropSink).
                stack = stack.withMetadata(metadata.clone());
            }
            // addItemStack(stack, b1, b2, applyFilter): the 3rd boolean is the filter-apply gate
            // (bytecode-verified: it propagates to internal_addToEmptySlot / internal_addToExistingSlot,
            // whose final boolean gates cantAddToSlot -> the slot's SlotFilter). Pass true so a filtered
            // destination (e.g. a Smelter/Reclaimer input that admits only valid recipe ingredients)
            // rejects a non-admitted item on commit. Unfiltered containers (chests) admit everything,
            // so this is a no-op for them.
            ItemStackTransaction tx = container.addItemStack(stack, false, false, true);
            return tx == null ? 0 : accepted(amount, tx.getRemainder());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Read-only "how much of {@code id} (with {@code metadata}) would fit" scan; never mutates. */
    private int simulateInsert(String id, BsonDocument metadata, int amount) {
        int maxStack = maxStackOf(id);
        if (maxStack <= 0) {
            return 0;
        }
        // A 1-count probe of this id+metadata, reused across slots to test the destination's slot filter
        // without mutating anything. canAddItemStackToSlot(slot, stack, matchExactType=false,
        // applyFilter=true): the 4th boolean is the filter-apply gate (bytecode-verified). On an
        // unfiltered container/slot the engine's filter test passes for everything, so a chest's room
        // count is unchanged; on a filtered Smelter/Reclaimer input a non-admitted item makes every slot
        // contribute 0 room, so simulateInsert == 0 and the network never routes the junk here.
        ItemStack probe = new ItemStack(id, 1);
        if (metadata != null) {
            probe = probe.withMetadata(metadata.clone());
        }
        short capacity = container.getCapacity();
        int room = 0;
        for (short slot = 0; slot < capacity && room < amount; slot++) {
            if (!container.canAddItemStackToSlot(slot, probe, false, true)) {
                continue; // the slot's filter rejects this id -> contributes no room
            }
            ItemStack inSlot = container.getItemStack(slot);
            if (inSlot == null || ItemStack.isEmpty(inSlot)) {
                room += maxStack; // an empty slot can take a full stack of any id
            } else if (id.equals(inSlot.getItemId())
                    && Objects.equals(metadata, inSlot.getMetadata())) {
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
        return Math.max(0, requested - left);
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
