package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One phase's stored resource: a {@code resourceId} + integer {@code amount} bounded by
 * {@code capacity}, type-locked to the first inserted resource until emptied (design §5.2).
 * A wrong-type or over-capacity insert accepts only what fits and never voids (rule 1).
 */
public final class ResourceBuffer {

    public static final BuilderCodec<ResourceBuffer> CODEC = BuilderCodec.builder(ResourceBuffer.class, ResourceBuffer::new)
        .append(new KeyedCodec<>("ResourceId", Codec.STRING), (o, v) -> o.resourceId = v, o -> o.resourceId).add()
        .append(new KeyedCodec<>("Amount", Codec.INTEGER), (o, v) -> o.amount = v, o -> o.amount).add()
        .append(new KeyedCodec<>("Capacity", Codec.INTEGER), (o, v) -> o.capacity = v, o -> o.capacity).add()
        .build();

    private String resourceId; // null when empty/unlocked
    private int amount;
    private int capacity;

    private ResourceBuffer() {
    }

    public static ResourceBuffer withCapacity(int capacity) {
        ResourceBuffer b = new ResourceBuffer();
        b.capacity = capacity;
        return b;
    }

    /** @return amount accepted (0 if locked to a different resource). */
    public int insert(String id, int qty, boolean simulate) {
        if (resourceId != null && !resourceId.equals(id)) {
            return 0;
        }
        int accepted = Math.max(0, Math.min(qty, capacity - amount));
        if (!simulate && accepted > 0) {
            resourceId = id;
            amount += accepted;
        }
        return accepted;
    }

    /** @return amount removed; empties → unlocks. */
    public int extract(int qty, boolean simulate) {
        int removed = Math.max(0, Math.min(qty, amount));
        if (!simulate && removed > 0) {
            amount -= removed;
            if (amount == 0) {
                resourceId = null;
            }
        }
        return removed;
    }

    public String resourceId() {
        return resourceId;
    }

    public int amount() {
        return amount;
    }

    public int capacity() {
        return capacity;
    }
}
