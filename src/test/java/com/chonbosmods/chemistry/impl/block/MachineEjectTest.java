package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.block.MachineEject.EjectStack;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemFilter;
import com.chonbosmods.chemistry.impl.block.net.item.ItemKey;
import java.util.ArrayList;
import java.util.List;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MachineEject#ejectAll}: drain every source container fully into an inventory
 * sink, returning the overflow stacks (what didn't fit) for the caller to drop on the floor.
 */
class MachineEjectTest {

    /** An in-memory ContainerView over an ordered list of (id, count) stacks; metadata ignored (null). */
    private static final class FakeView implements ContainerView {
        private final List<String> ids = new ArrayList<>();
        private final List<Integer> counts = new ArrayList<>();

        FakeView add(String id, int count) {
            ids.add(id);
            counts.add(count);
            return this;
        }

        @Override
        public int insert(ItemKey key, BsonDocument metadata, int amount, boolean simulate) {
            return 0; // sources are never inserted into during eject
        }

        @Override
        public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
            for (int i = 0; i < ids.size(); i++) {
                if (counts.get(i) > 0) {
                    return new Peek(new ItemKey(ids.get(i), Math.min(counts.get(i), cap)), null);
                }
            }
            return null;
        }

        @Override
        public Extracted extract(ItemKey key, int amount, boolean simulate) {
            for (int i = 0; i < ids.size(); i++) {
                if (ids.get(i).equals(key.id()) && counts.get(i) > 0) {
                    int take = Math.min(amount, counts.get(i));
                    counts.set(i, counts.get(i) - take);
                    return new Extracted(take, null);
                }
            }
            return new Extracted(0, null);
        }

        int total() {
            int t = 0;
            for (int c : counts) {
                t += c;
            }
            return t;
        }
    }

    /** A sink that accepts up to {@code capacity} items total, then rejects the rest. */
    private static final class FakeSink implements MachineEject.InventorySink {
        int remaining;
        int accepted;

        FakeSink(int capacity) {
            this.remaining = capacity;
        }

        @Override
        public int insert(String id, BsonDocument metadata, int amount) {
            int acc = Math.min(amount, remaining);
            remaining -= acc;
            accepted += acc;
            return acc;
        }
    }

    @Test
    void allFits_drainsSources_noOverflow() {
        FakeView in = new FakeView().add("ore", 16);
        FakeView out = new FakeView().add("bar", 4);
        FakeSink sink = new FakeSink(1000);

        List<EjectStack> overflow = MachineEject.ejectAll(List.of(in, out), sink);

        assertTrue(overflow.isEmpty());
        assertEquals(0, in.total());
        assertEquals(0, out.total());
        assertEquals(20, sink.accepted);
    }

    @Test
    void partialFit_returnsOverflow() {
        FakeView in = new FakeView().add("ore", 16);
        FakeSink sink = new FakeSink(10); // only 10 of 16 fit

        List<EjectStack> overflow = MachineEject.ejectAll(List.of(in), sink);

        assertEquals(1, overflow.size());
        assertEquals("ore", overflow.get(0).id());
        assertEquals(6, overflow.get(0).count());
        assertEquals(0, in.total()); // fully drained from the source either way
        assertEquals(10, sink.accepted);
    }

    @Test
    void emptySources_noOp() {
        FakeView empty = new FakeView();
        FakeSink sink = new FakeSink(1000);

        List<EjectStack> overflow = MachineEject.ejectAll(List.of(empty), sink);

        assertTrue(overflow.isEmpty());
        assertEquals(0, sink.accepted);
    }

    @Test
    void nullSource_skipped() {
        List<ContainerView> sources = new ArrayList<>();
        sources.add(null);
        sources.add(new FakeView().add("ore", 3));
        FakeSink sink = new FakeSink(1000);

        List<EjectStack> overflow = MachineEject.ejectAll(sources, sink);

        assertTrue(overflow.isEmpty());
        assertEquals(3, sink.accepted);
    }
}
