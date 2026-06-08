package com.chonbosmods.chemistry.impl.block.net.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Destination;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Source;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ItemTransferDriver}: the pure-ish per-network orchestration of ITEM transport
 * (2026-06-06 item-channel design "Tick driver"). The driver wraps {@link ItemTransit#step} (movement)
 * and {@link ItemExtraction#tryExtract} (extraction) with the per-tick bookkeeping the engine glue can
 * NOT express headlessly: the stable-copy iteration, the double-step guard, the segment hand-off between
 * {@code PipeNode.inTransit} lists, the {@code 0L} pop-out fallback, and the pull-interval gate.
 *
 * <p>The driver keeps engine types out of its core loop by taking seams ({@link PipeGridView},
 * {@link ContainerLookup}, a {@link ItemTransferDriver.DropSink}, a {@link ItemTransferDriver.SaveMarker});
 * the thin world adapters (real container access, ground drops) stay in-game verified.
 */
class ItemTransferDriverTest {

    private static final int SPEED = 5;
    private static final int PULL_INTERVAL = 20;
    private static final int PULL_CAP = 16;
    private static final String COBBLE = "cobblestone";

    // ---- fakes -------------------------------------------------------------------------------

    private static final class FakeGrid implements PipeGridView {
        private final Map<Long, PipeNode> nodes = new HashMap<>();

        FakeGrid put(int x, int y, int z, PipeNode node) {
            nodes.put(NetworkManager.packKey(x, y, z), node);
            return this;
        }

        FakeGrid remove(int x, int y, int z) {
            nodes.remove(NetworkManager.packKey(x, y, z));
            return this;
        }

        @Override
        public PipeNode pipeAt(int x, int y, int z) {
            return nodes.get(NetworkManager.packKey(x, y, z));
        }
    }

    /** A container with a fixed remaining capacity and optional contents for extraction. */
    private static final class FakeContainer implements ContainerView {
        private int capacity;
        private String contentId;
        private int contentCount;

        FakeContainer(int capacity) {
            this.capacity = capacity;
        }

        FakeContainer withContents(String id, int count) {
            this.contentId = id;
            this.contentCount = count;
            return this;
        }

        @Override
        public int insert(ItemKey key, org.bson.BsonDocument metadata, int amount, boolean simulate) {
            int fit = Math.min(amount, capacity);
            if (!simulate) {
                capacity -= fit;
            }
            return fit;
        }

        @Override
        public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
            if (contentId == null || contentCount <= 0) {
                return null;
            }
            return new Peek(new ItemKey(contentId, Math.min(contentCount, cap)), null);
        }

        @Override
        public Extracted extract(ItemKey key, int amount, boolean simulate) {
            int got = Math.min(amount, contentCount);
            if (!simulate) {
                contentCount -= got;
            }
            return new Extracted(got, null);
        }
    }

    private static final class FakeContainerLookup implements ContainerLookup {
        private final Map<Long, FakeContainer> containers = new HashMap<>();

        FakeContainerLookup put(int x, int y, int z, FakeContainer c) {
            containers.put(NetworkManager.packKey(x, y, z), c);
            return this;
        }

        @Override
        public ContainerView at(int x, int y, int z) {
            return containers.get(NetworkManager.packKey(x, y, z));
        }
    }

    /** Records ground drops the driver requests. */
    private static final class RecordingDropSink implements ItemTransferDriver.DropSink {
        final List<long[]> drops = new ArrayList<>(); // {pipeKey, count}
        final List<TravelingStack> stacks = new ArrayList<>();

        @Override
        public void drop(long pipeKey, TravelingStack stack) {
            drops.add(new long[] {pipeKey, stack.count()});
            stacks.add(stack);
        }
    }

    /** Records the pipe keys the driver marks needing-save. */
    private static final class RecordingSaveMarker implements ItemTransferDriver.SaveMarker {
        final List<Long> marked = new ArrayList<>();

        @Override
        public void markPipe(long pipeKey) {
            marked.add(pipeKey);
        }
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static PipeNode itemPipe() {
        return PipeNode.of(PortChannel.ITEM, 1);
    }

    private static long key(int x, int y, int z) {
        return NetworkManager.packKey(x, y, z);
    }

    private static Network networkAt(int x, int y, int z, PipeGridView grid) {
        return new NetworkManager().getOrBuildNetwork(x, y, z, grid);
    }

    private static ItemTransferDriver driver() {
        return new ItemTransferDriver(SPEED, PULL_INTERVAL, PULL_CAP);
    }

    // ---- 1. movement: a stack crosses A->B in one tick and is NOT double-stepped ---------------

    @Test
    void movement_stackCrossesSegment_notDoubleStepped() {
        // Two pipes A(0,0,0) -> B(1,0,0), container far end at (2,0,0). Stack on A, progress at threshold-1
        // so this tick it moves into B. The driver must move it to B's inTransit AND not step it again
        // when it visits B in the same pass (double-step guard).
        PipeNode a = itemPipe();
        PipeNode b = itemPipe();
        FakeGrid grid = new FakeGrid().put(0, 0, 0, a).put(1, 0, 0, b);
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(
            List.of(new Destination(key(2, 0, 0), key(1, 0, 0), 0)), List.of());
        FakeContainerLookup containers = new FakeContainerLookup().put(2, 0, 0, new FakeContainer(64));

        long[] path = {key(0, 0, 0), key(1, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 4, null, path, key(99, 0, 0), key(2, 0, 0));
        s.setProgressTicks(SPEED - 1); // crosses into segment 1 this tick
        a.addInTransit(s);

        RecordingDropSink sink = new RecordingDropSink();
        RecordingSaveMarker saver = new RecordingSaveMarker();
        driver().tickNetwork(net, grid, containers, endpoints, FilterLookup.NONE, 1, sink, saver);

        // Moved out of A, into B, at segment 1, progress reset, and not advanced a SECOND time.
        assertTrue(a.inTransit().isEmpty(), "stack left pipe A");
        assertEquals(1, b.inTransit().size(), "stack arrived at pipe B");
        TravelingStack moved = b.inTransit().get(0);
        assertSame(s, moved);
        assertEquals(1, moved.segmentIndex());
        assertEquals(0, moved.progressTicks(), "not double-stepped (would be 1 if stepped again in B)");
        assertTrue(sink.drops.isEmpty());
    }

    // ---- 2. delivery removes the stack + marks save ------------------------------------------

    @Test
    void delivery_removesStackAndMarksSave() {
        PipeNode a = itemPipe();
        FakeGrid grid = new FakeGrid().put(0, 0, 0, a);
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(
            List.of(new Destination(key(1, 0, 0), key(0, 0, 0), 0)), List.of());
        FakeContainer destC = new FakeContainer(64);
        FakeContainerLookup containers = new FakeContainerLookup().put(1, 0, 0, destC);

        long[] path = {key(0, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 7, null, path, key(99, 0, 0), key(1, 0, 0));
        s.setProgressTicks(SPEED - 1);
        a.addInTransit(s);

        RecordingDropSink sink = new RecordingDropSink();
        RecordingSaveMarker saver = new RecordingSaveMarker();
        driver().tickNetwork(net, grid, containers, endpoints, FilterLookup.NONE, 1, sink, saver);

        assertTrue(a.inTransit().isEmpty(), "delivered stack removed from the pipe");
        assertEquals(57, destC.insert(new ItemKey(COBBLE, 1), null, 1000, true), "7 actually delivered");
        assertTrue(saver.marked.contains(key(0, 0, 0)), "owning pipe marked needing-save");
        assertTrue(sink.drops.isEmpty());
    }

    // ---- 3. pop-out routes to DropSink, with 0L -> owning-pipe fallback ----------------------

    @Test
    void popOut_routesToDropSink_atCurrentSegment() {
        // Dest full, no origin: ItemTransit returns POP_OUT with popOutPipeKey = current segment.
        PipeNode a = itemPipe();
        FakeGrid grid = new FakeGrid().put(0, 0, 0, a);
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(
            List.of(new Destination(key(1, 0, 0), key(0, 0, 0), 0)), List.of());
        FakeContainerLookup containers = new FakeContainerLookup().put(1, 0, 0, new FakeContainer(0));

        long[] path = {key(0, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 5, null, path, key(-99, 0, 0), key(1, 0, 0));
        s.setProgressTicks(SPEED - 1);
        a.addInTransit(s);

        RecordingDropSink sink = new RecordingDropSink();
        driver().tickNetwork(net, grid, containers, endpoints, FilterLookup.NONE, 1, sink,
            new RecordingSaveMarker());

        assertTrue(a.inTransit().isEmpty(), "popped-out stack removed from the pipe");
        assertEquals(1, sink.drops.size());
        assertEquals(key(0, 0, 0), sink.drops.get(0)[0], "dropped at the current segment pipe");
    }

    @Test
    void popOut_zeroKey_fallsBackToOwningPipe() {
        // A malformed empty-path stack pops out with popOutPipeKey == 0L. The driver must NOT drop at the
        // unpacked 0L position (packKey biases coords); it drops at the OWNING pipe instead.
        PipeNode a = itemPipe();
        FakeGrid grid = new FakeGrid().put(3, 4, 5, a);
        Network net = networkAt(3, 4, 5, grid);
        Endpoints endpoints = new Endpoints(List.of(), List.of());
        FakeContainerLookup containers = new FakeContainerLookup();

        TravelingStack s = TravelingStack.of(COBBLE, 5, null, new long[0], key(0, 0, 0), key(1, 0, 0));
        a.addInTransit(s);

        RecordingDropSink sink = new RecordingDropSink();
        driver().tickNetwork(net, grid, containers, endpoints, FilterLookup.NONE, 1, sink,
            new RecordingSaveMarker());

        assertTrue(a.inTransit().isEmpty());
        assertEquals(1, sink.drops.size());
        assertEquals(key(3, 4, 5), sink.drops.get(0)[0], "0L pop-out falls back to the owning pipe key");
    }

    // ---- 4. moved-segment into a missing pipe -> ground drop at the old pipe -----------------

    @Test
    void movedSegment_intoMissingPipe_dropsAtOldPipe() {
        PipeNode a = itemPipe();
        // B is in the path but absent from the live grid (broken/unloaded mid-route).
        FakeGrid grid = new FakeGrid().put(0, 0, 0, a);
        Network net = networkAt(0, 0, 0, grid);
        Endpoints endpoints = new Endpoints(
            List.of(new Destination(key(2, 0, 0), key(1, 0, 0), 0)), List.of());
        FakeContainerLookup containers = new FakeContainerLookup().put(2, 0, 0, new FakeContainer(64));

        long[] path = {key(0, 0, 0), key(1, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 4, null, path, key(99, 0, 0), key(2, 0, 0));
        s.setProgressTicks(SPEED - 1);
        a.addInTransit(s);

        RecordingDropSink sink = new RecordingDropSink();
        driver().tickNetwork(net, grid, containers, endpoints, FilterLookup.NONE, 1, sink,
            new RecordingSaveMarker());

        assertTrue(a.inTransit().isEmpty(), "removed from the old pipe even though the next pipe is gone");
        assertEquals(1, sink.drops.size());
        assertEquals(key(0, 0, 0), sink.drops.get(0)[0], "dropped at the OLD pipe (defensive)");
    }

    // ---- 5. extraction: gated by the pull interval, adds to the source via pipe ---------------

    @Test
    void extraction_onlyAtPullInterval_addsToSourceViaPipe() {
        PipeNode src = itemPipe();
        PipeNode dst = itemPipe();
        FakeGrid grid = new FakeGrid().put(0, 0, 0, src).put(1, 0, 0, dst);
        Network net = networkAt(0, 0, 0, grid);
        // Source container at (-1,0,0) via pipe (0,0,0); destination container at (2,0,0) via pipe (1,0,0).
        Source source = new Source(key(-1, 0, 0), key(0, 0, 0), 1);
        Destination destination = new Destination(key(2, 0, 0), key(1, 0, 0), 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(source));
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, new FakeContainer(0).withContents(COBBLE, 50))
            .put(2, 0, 0, new FakeContainer(64));

        ItemTransferDriver d = driver();
        // Tick 1: not a pull-interval boundary -> no extraction.
        d.tickNetwork(net, grid, containers, endpoints, FilterLookup.NONE, 1, new RecordingDropSink(),
            new RecordingSaveMarker());
        assertTrue(src.inTransit().isEmpty(), "no extraction off the interval");

        // Tick PULL_INTERVAL: extraction fires, a capped stack lands on the source via pipe.
        RecordingSaveMarker saver = new RecordingSaveMarker();
        d.tickNetwork(net, grid, containers, endpoints, FilterLookup.NONE, PULL_INTERVAL,
            new RecordingDropSink(), saver);
        assertEquals(1, src.inTransit().size(), "extraction adds the new stack to the source via pipe");
        TravelingStack pulled = src.inTransit().get(0);
        assertEquals(COBBLE, pulled.id());
        assertEquals(PULL_CAP, pulled.count(), "capped at the per-pull cap");
        assertEquals(source.containerKey(), pulled.originKey());
        assertTrue(saver.marked.contains(source.viaPipeKey()), "source pipe marked needing-save");
    }

    // ---- 6. non-ITEM network is a no-op ------------------------------------------------------

    @Test
    void nonItemNetwork_isNoOp() {
        PipeNode power = PipeNode.of(PortChannel.POWER, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, power);
        Network net = networkAt(0, 0, 0, grid);
        assertEquals(PortChannel.POWER, net.channel());

        // Even with a stack sitting on it, a non-ITEM network does nothing (channel guard).
        long[] path = {key(0, 0, 0)};
        TravelingStack s = TravelingStack.of(COBBLE, 4, null, path, key(99, 0, 0), key(1, 0, 0));
        power.addInTransit(s);

        RecordingDropSink sink = new RecordingDropSink();
        driver().tickNetwork(net, grid, new FakeContainerLookup(), new Endpoints(List.of(), List.of()),
            FilterLookup.NONE, PULL_INTERVAL, sink, new RecordingSaveMarker());

        assertEquals(1, power.inTransit().size(), "non-ITEM network untouched");
        assertTrue(sink.drops.isEmpty());
    }

    // ---- 7. saturation backpressure blocks extraction ----------------------------------------

    @Test
    void extraction_saturated_pullsNothing() {
        // One-member network -> saturationCap = 1. Pre-load 1 in-transit stack: extraction must back off.
        PipeNode src = itemPipe();
        FakeGrid grid = new FakeGrid().put(0, 0, 0, src);
        Network net = networkAt(0, 0, 0, grid);
        Source source = new Source(key(-1, 0, 0), key(0, 0, 0), 1);
        Destination destination = new Destination(key(2, 0, 0), key(0, 0, 0), 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(source));
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, new FakeContainer(0).withContents(COBBLE, 50))
            .put(2, 0, 0, new FakeContainer(64));

        // A pre-existing in-transit stack saturates the single-member network (saturationCap == 1). Use a
        // 2-segment self path with low progress so the movement phase only ADVANCES it (it stays in
        // transit this tick), keeping the in-transit count at 1 when extraction is consulted.
        TravelingStack sitting = TravelingStack.of(
            COBBLE, 1, null, new long[] {key(0, 0, 0), key(0, 0, 0)}, key(-1, 0, 0), key(2, 0, 0));
        src.addInTransit(sitting);

        driver().tickNetwork(net, grid, containers, endpoints, FilterLookup.NONE, PULL_INTERVAL,
            new RecordingDropSink(), new RecordingSaveMarker());

        // Only the one pre-existing stack remains; saturation blocked a new extraction.
        assertEquals(1, src.inTransit().size(), "saturated network extracts nothing");
        assertSame(sitting, src.inTransit().get(0), "the surviving stack is the pre-existing one");
    }
}
