package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * One channel's single virtual tank/battery (the shared-buffer model): the whole connected set
 * of pipe segments acts as ONE buffer, so contents are fungible with no per-pipe location.
 *
 * <p>Membership is a set of pipe segments keyed by a {@code long}, each carrying its own
 * {@code capacity} and tier {@code throughput}. Aggregates are derived from members:
 * {@code capacity() = Σ member capacities} and {@code throughput() = MIN member throughput}
 * (the network is limited by its weakest pipe). The buffer is therefore dynamic-capacity, which
 * is why {@code stored}/{@code lockedResourceId} are held here directly rather than nesting an
 * {@code EnergyBuffer}/{@code ResourceBuffer} (those fix capacity at construction).
 *
 * <p>For FLUID/GAS the network type-locks to the first inserted resource until emptied, mirroring
 * {@link com.chonbosmods.chemistry.impl.block.ResourceBuffer}. POWER never locks.
 *
 * <p>This is transient runtime state: it has NO codec and is never serialized. Persistence is
 * handled elsewhere by distributing shares back to pipe components.
 */
public final class Network {

    private record Member(long capacity, int throughput) {
    }

    private final PortChannel channel;
    private final Map<Long, Member> members = new HashMap<>();

    private long capacity;   // Σ member capacities
    private int throughput;  // MIN member throughput (0 if empty)
    private long stored;
    private String lockedResourceId; // null when unlocked; always null for POWER

    // Monotonic per-network counter used to rotate the fair-split remainder recipient across
    // distribute calls. Transient runtime state only: NOT persisted and NOT part of any codec.
    private int rotation;

    public Network(PortChannel channel) {
        this.channel = channel;
    }

    /** Add (or replace, if the key already exists) a pipe segment, then recompute aggregates. */
    public void addMember(long key, long capacity, int throughput) {
        members.put(key, new Member(capacity, throughput));
        recompute();
    }

    /** Remove a pipe segment, then recompute aggregates. */
    public void removeMember(long key) {
        members.remove(key);
        recompute();
    }

    private void recompute() {
        long cap = 0;
        int minThroughput = 0;
        boolean first = true;
        for (Member m : members.values()) {
            cap += m.capacity();
            if (first || m.throughput() < minThroughput) {
                minThroughput = m.throughput();
                first = false;
            }
        }
        this.capacity = cap;
        this.throughput = minThroughput;
        // Real share-accounting on pipe removal is handled in the integration phase; clamping
        // stored down to the shrunken capacity is the safe pure-logic behavior here.
        if (this.stored > this.capacity) {
            this.stored = this.capacity;
        }
    }

    public PortChannel channel() {
        return channel;
    }

    /**
     * @return the next rotation offset for fair-split remainder placement, post-incrementing the
     *     internal counter so successive calls cycle the recipient. Transient: never serialized.
     */
    public int nextRotation() {
        return rotation++;
    }

    /**
     * The packed keys of this network's pipe segments (see {@link NetworkManager#packKey}). Used by the
     * endpoint-collection pass to walk each pipe's 6 face-neighbours looking for machine/tank ports.
     * Unmodifiable live view; iteration order is unspecified.
     */
    public Set<Long> memberKeys() {
        return Collections.unmodifiableSet(members.keySet());
    }

    /** @return Σ member capacities (0 if empty). */
    public long capacity() {
        return capacity;
    }

    /** @return MIN member throughput, the bottleneck (0 if empty). */
    public int throughput() {
        return throughput;
    }

    public long stored() {
        return stored;
    }

    public float fillRatio() {
        return capacity <= 0 ? 0f : (float) stored / capacity;
    }

    /** @return locked resource id; null when unlocked or for POWER. */
    public String lockedResourceId() {
        return lockedResourceId;
    }

    /**
     * @return amount accepted (≤ free space). For FLUID/GAS, rejects (returns 0) when locked to a
     *     different resource or when {@code resourceId} is null (never a valid stored resource on a
     *     type-locked channel); a non-simulate accept of &gt;0 sets the lock. POWER ignores
     *     {@code resourceId} and never locks. Negative/zero {@code amount} → 0.
     */
    public long insert(String resourceId, long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        boolean typeLocked = channel == PortChannel.FLUID || channel == PortChannel.GAS;
        if (typeLocked && resourceId == null) {
            return 0; // null is never a valid resource to store on a type-locked channel
        }
        if (typeLocked && lockedResourceId != null && !lockedResourceId.equals(resourceId)) {
            return 0;
        }
        long accepted = Math.min(amount, capacity - stored);
        if (accepted <= 0) {
            return 0;
        }
        if (!simulate) {
            stored += accepted;
            if (typeLocked) {
                lockedResourceId = resourceId;
            }
        }
        return accepted;
    }

    /**
     * @return amount removed (≤ stored). A non-simulate drain to exactly 0 clears the FLUID/GAS
     *     lock so a different resource can enter next. Negative/zero {@code amount} → 0.
     */
    public long extract(long amount, boolean simulate) {
        if (amount <= 0) {
            return 0;
        }
        long removed = Math.min(amount, stored);
        if (removed <= 0) {
            return 0;
        }
        if (!simulate) {
            stored -= removed;
            if (stored == 0) {
                lockedResourceId = null;
            }
        }
        return removed;
    }
}
