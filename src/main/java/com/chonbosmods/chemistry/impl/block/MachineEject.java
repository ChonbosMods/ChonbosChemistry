package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemKey;
import java.util.ArrayList;
import java.util.List;
import org.bson.BsonDocument;

/**
 * Pure "empty the machine" logic for the smelter panel's Eject button: drain every source container
 * (the held bench's input + output) fully into an inventory {@link InventorySink}, returning the
 * <em>overflow</em> stacks (what didn't fit) for the caller to drop on the floor. Engine-free (operates
 * over the {@link ContainerView} seam + a sink lambda), so it unit-tests with in-memory fakes; the
 * world-side wiring (player inventory sink + ground-drop of the overflow) lives in the panel page.
 */
public final class MachineEject {

    /** A per-extract cap large enough that any single slot drains in one extract. */
    private static final int DRAIN_CAP = 1_000_000;
    /** Loop guard against a pathological view that reports extractable but never drains. */
    private static final int MAX_SLOT_PULLS = 4096;

    private MachineEject() {
    }

    /** Where ejected items go (the player's inventory). Returns how many of {@code amount} were accepted. */
    public interface InventorySink {
        int insert(String id, BsonDocument metadata, int amount);
    }

    /** A stack that did not fit in the inventory and must be dropped. */
    public record EjectStack(String id, int count, BsonDocument metadata) {
    }

    /**
     * Drain all {@code sources} fully into {@code sink}; return the overflow (never null). Null sources are
     * skipped. The source containers are emptied regardless of how much the sink accepts — the unaccepted
     * remainder is returned for the caller to drop, so items are never destroyed.
     */
    public static List<EjectStack> ejectAll(List<ContainerView> sources, InventorySink sink) {
        List<EjectStack> overflow = new ArrayList<>();
        if (sources == null) {
            return overflow;
        }
        for (ContainerView view : sources) {
            if (view == null) {
                continue;
            }
            int guard = 0;
            ContainerView.Peek peek;
            while (guard++ < MAX_SLOT_PULLS
                    && (peek = view.firstExtractable(null, 0L, 0, DRAIN_CAP)) != null) {
                ItemKey key = peek.key();
                if (key == null || key.id() == null || key.count() <= 0) {
                    break;
                }
                ContainerView.Extracted got = view.extract(key, key.count(), false);
                int amount = got.amount();
                if (amount <= 0) {
                    break; // raced empty / non-draining: stop rather than spin
                }
                int accepted = sink.insert(key.id(), got.metadata(), amount);
                int leftover = amount - accepted;
                if (leftover > 0) {
                    overflow.add(new EjectStack(key.id(), leftover, got.metadata()));
                }
            }
        }
        return overflow;
    }
}
