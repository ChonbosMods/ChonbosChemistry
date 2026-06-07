package com.chonbosmods.chemistry.impl.block.net.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ItemExtraction#tryExtract}: the pure single-attempt PULL eligibility function
 * (2026-06-06 item-channel design §13.4: "No extraction without a destination", per-pull cap, saturation
 * backpressure). Pure: no world access. Fakes mirror the sibling item tests (in-memory
 * {@link PipeGridView}, real {@link Network}s via {@link NetworkManager}) plus a contents-tracking
 * {@link ContainerLookup} fake supporting firstExtractable/insert/extract with metadata and racing.
 */
class ItemExtractionTest {

    private static final int CAP = 16;
    private static final int SATURATION = 8;
    private static final String COBBLE = "cobblestone";

    // ---- fakes -------------------------------------------------------------------------------

    /** A grid backed by an explicit map of packed-key -> node. */
    private static final class FakeGrid implements PipeGridView {
        private final Map<Long, PipeNode> nodes = new HashMap<>();

        FakeGrid put(int x, int y, int z, PipeNode node) {
            nodes.put(NetworkManager.packKey(x, y, z), node);
            return this;
        }

        @Override
        public PipeNode pipeAt(int x, int y, int z) {
            return nodes.get(NetworkManager.packKey(x, y, z));
        }
    }

    /**
     * A container holding a single item type with a tracked amount (source semantics) AND a tracked
     * insert capacity (destination semantics). {@code extractCap} optionally clamps what each
     * firstExtractable promises; {@code raceTo} optionally simulates the contents shrinking between the
     * promise and the commit (the raced-commit case).
     */
    private static final class FakeContainer implements ContainerView {
        private final String itemId;
        private int contents;       // how many of itemId are present (extract source)
        private int capacity;       // how many can still be inserted (destination)
        private int promiseCap = Integer.MAX_VALUE; // clamps firstExtractable's reported count
        private int raceTo = -1;    // if >=0, the contents actually present at commit time
        private org.bson.BsonDocument sourceMetadata; // metadata firstExtractable reports (source slot)
        org.bson.BsonDocument lastInsertSimulateMetadata; // metadata the LAST simulate insert received
        boolean insertSimulateCalled;

        FakeContainer(String itemId, int contents, int capacity) {
            this.itemId = itemId;
            this.contents = contents;
            this.capacity = capacity;
        }

        FakeContainer promiseCap(int v) {
            this.promiseCap = v;
            return this;
        }

        FakeContainer raceTo(int v) {
            this.raceTo = v;
            return this;
        }

        FakeContainer sourceMetadata(org.bson.BsonDocument v) {
            this.sourceMetadata = v;
            return this;
        }

        @Override
        public int insert(ItemKey key, org.bson.BsonDocument metadata, int amount, boolean simulate) {
            if (simulate) {
                lastInsertSimulateMetadata = metadata;
                insertSimulateCalled = true;
            }
            int fit = Math.min(amount, capacity);
            if (!simulate) {
                capacity -= fit;
            }
            return fit;
        }

        @Override
        public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
            if (contents <= 0) {
                return null;
            }
            ItemKey candidate = new ItemKey(itemId, Math.min(Math.min(contents, cap), promiseCap));
            if (!filter.admits(candidate, pipeKey, viaFace)) {
                return null;
            }
            return new Peek(candidate, sourceMetadata);
        }

        @Override
        public Extracted extract(ItemKey key, int amount, boolean simulate) {
            // The contents actually available at commit may have raced lower than firstExtractable saw.
            int available = raceTo >= 0 ? raceTo : contents;
            int pulled = Math.min(amount, available);
            if (!simulate) {
                contents = available - pulled;
            }
            return new Extracted(pulled, sourceMetadata);
        }
    }

    /** A ContainerLookup backed by a map of packed-key -> FakeContainer. */
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

    private static Destination dest(int cx, int cy, int cz, int vx, int vy, int vz, int face) {
        return new Destination(key(cx, cy, cz), key(vx, vy, vz), face);
    }

    private static Source source(int cx, int cy, int cz, int vx, int vy, int vz, int face) {
        return new Source(key(cx, cy, cz), key(vx, vy, vz), face);
    }

    private static TravelingStack tryExtract(Source src, Network net, PipeGridView grid,
                                             ContainerLookup containers, Endpoints endpoints,
                                             int inTransit) {
        return ItemExtraction.tryExtract(src, net, grid, containers, endpoints, FilterLookup.NONE,
            CAP, inTransit, SATURATION);
    }

    // ---- 1. happy path -----------------------------------------------------------------------

    @Test
    void happyPath_createsStackWithCorrectIdCountPathOriginDest_andCommits() {
        // Source chest at (-1,0,0) via pipe (0,0,0) PULL face -X(1). Destination chest at (2,0,0) via
        // pipe (1,0,0) face +X(0). Two pipes in a line.
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        Destination destination = dest(2, 0, 0, 1, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(src));
        FakeContainer srcC = new FakeContainer(COBBLE, 64, 0);
        FakeContainer destC = new FakeContainer(COBBLE, 0, 64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, srcC)
            .put(2, 0, 0, destC);

        TravelingStack s = tryExtract(src, net, grid, containers, endpoints, 0);

        assertNotNull(s);
        assertEquals(COBBLE, s.id());
        assertEquals(CAP, s.count(), "extractable 64 capped at pull cap 16");
        assertEquals(src.containerKey(), s.originKey());
        assertEquals(destination.containerKey(), s.destKey());
        // Path from the source's via pipe to the destination's via pipe, inclusive.
        assertArrayEquals(new long[] {key(0, 0, 0), key(1, 0, 0)}, s.path());
        assertEquals(0, s.segmentIndex());
        assertEquals(0, s.progressTicks());
        // Commit really pulled 16 from the source: 48 remain.
        assertEquals(48, srcC.extract(new ItemKey(COBBLE, 1), 1000, true).amount());
    }

    // ---- 2. no destination accepts -> null, nothing extracted --------------------------------

    @Test
    void noDestinationAccepts_returnsNull_andNoCommitExtract() {
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        Destination destination = dest(2, 0, 0, 1, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(src));
        FakeContainer srcC = new FakeContainer(COBBLE, 64, 0);
        FakeContainer destC = new FakeContainer(COBBLE, 0, 0); // full: accepts nothing
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, srcC)
            .put(2, 0, 0, destC);

        TravelingStack s = tryExtract(src, net, grid, containers, endpoints, 0);

        assertNull(s, "no admitting destination -> no extraction (Mekanism sorter rule)");
        // The source was never committed against: still has all 64.
        assertEquals(64, srcC.extract(new ItemKey(COBBLE, 1), 1000, true).amount());
    }

    // ---- 3. empty container -> null ----------------------------------------------------------

    @Test
    void emptyContainer_firstExtractableNull_returnsNull() {
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        Destination destination = dest(1, 0, 0, 0, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(src));
        FakeContainer srcC = new FakeContainer(COBBLE, 0, 0); // empty
        FakeContainer destC = new FakeContainer(COBBLE, 0, 64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, srcC)
            .put(1, 0, 0, destC);

        assertNull(tryExtract(src, net, grid, containers, endpoints, 0));
    }

    // ---- 4. saturation -> null, container never consulted ------------------------------------

    @Test
    void saturation_returnsNull_andContainerNeverConsulted() {
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        Destination destination = dest(1, 0, 0, 0, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(src));
        // A container that would throw if touched: proves saturation short-circuits before any probe.
        ContainerView tripwire = new ContainerView() {
            @Override
            public int insert(ItemKey key, org.bson.BsonDocument metadata, int amount, boolean simulate) {
                throw new AssertionError("container consulted under saturation");
            }

            @Override
            public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
                throw new AssertionError("container consulted under saturation");
            }

            @Override
            public Extracted extract(ItemKey key, int amount, boolean simulate) {
                throw new AssertionError("container consulted under saturation");
            }
        };
        ContainerLookup containers = (x, y, z) -> tripwire;

        assertNull(tryExtract(src, net, grid, containers, endpoints, SATURATION),
            "inTransit >= saturationCap blocks extraction (backpressure)");
    }

    // ---- 5. filter at source viaPipe rejects -> null -----------------------------------------

    @Test
    void filterRejectingTheOnlyStack_atSourceViaPipe_returnsNull() {
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        Destination destination = dest(1, 0, 0, 0, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(src));
        FakeContainer srcC = new FakeContainer(COBBLE, 64, 0);
        FakeContainer destC = new FakeContainer(COBBLE, 0, 64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, srcC)
            .put(1, 0, 0, destC);
        // A filter that rejects everything, only consulted at the source via pipe per the design.
        FilterLookup rejectAll = pipeKey -> (k, p, f) -> false;

        TravelingStack s = ItemExtraction.tryExtract(src, net, grid, containers, endpoints, rejectAll,
            CAP, 0, SATURATION);

        assertNull(s, "the source viaPipe filter rejects the only stack -> nothing extractable");
        assertEquals(64, srcC.extract(new ItemKey(COBBLE, 1), 1000, true).amount());
    }

    // ---- 6. source == dest same chest excluded -----------------------------------------------

    @Test
    void sourceEqualsDest_sameChestExcluded_returnsNull_nothingExtracted() {
        // ONE chest at (-1,0,0) bordering the single pipe twice is impossible, but the same chest can
        // legitimately appear as BOTH a source (PULL face) and a destination (NORMAL face) triple. Here
        // it is the only candidate, so excluding it leaves no destination -> null.
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        long chestKey = key(-1, 0, 0);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        // The same chest reachable as a destination (e.g. via a different face triple in endpoints).
        Destination selfDest = new Destination(chestKey, key(0, 0, 0), 1);
        Endpoints endpoints = new Endpoints(List.of(selfDest), List.of(src));
        FakeContainer chestC = new FakeContainer(COBBLE, 64, 64); // room AND contents
        FakeContainerLookup containers = new FakeContainerLookup().put(-1, 0, 0, chestC);

        TravelingStack s = tryExtract(src, net, grid, containers, endpoints, 0);

        assertNull(s, "a container must not pull out of itself to deliver back into itself");
        assertEquals(64, chestC.extract(new ItemKey(COBBLE, 1), 1000, true).amount());
    }

    // ---- 7. cap respected --------------------------------------------------------------------

    @Test
    void cap_extractable64_cap16_stackCountAtMost16() {
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        Destination destination = dest(1, 0, 0, 0, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(src));
        FakeContainer srcC = new FakeContainer(COBBLE, 64, 0);
        FakeContainer destC = new FakeContainer(COBBLE, 0, 64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, srcC)
            .put(1, 0, 0, destC);

        TravelingStack s = tryExtract(src, net, grid, containers, endpoints, 0);

        assertNotNull(s);
        assertTrue(s.count() <= CAP, "stack count must not exceed the pull cap");
        assertEquals(CAP, s.count());
    }

    // ---- 8. first-accepting-candidate partial sizing -----------------------------------------

    @Test
    void firstAcceptingCandidate_sizesStackToWhatItAcceptsInSimulate() {
        // Extractable would be 16, but the NEAREST destination only accepts 5 (simulate). Per the
        // documented step-3 choice we extract min(extractable, nearest-accepts) = 5, avoiding a stack
        // guaranteed to partially bounce.
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        // Nearest dest accepts only 5; a farther roomy one exists but the FIRST accepting wins.
        Destination near = dest(0, 1, 0, 0, 0, 0, 2);
        Destination far = dest(2, 0, 0, 1, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(near, far), List.of(src));
        FakeContainer srcC = new FakeContainer(COBBLE, 16, 0);
        FakeContainer nearC = new FakeContainer(COBBLE, 0, 5);
        FakeContainer farC = new FakeContainer(COBBLE, 0, 64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, srcC)
            .put(0, 1, 0, nearC)
            .put(2, 0, 0, farC);

        TravelingStack s = tryExtract(src, net, grid, containers, endpoints, 0);

        assertNotNull(s);
        assertEquals(near.containerKey(), s.destKey(), "first accepting candidate (nearest) wins");
        assertEquals(5, s.count(), "sized to what the first accepting candidate accepts in simulate");
        // Committed exactly 5 from the source: 11 remain.
        assertEquals(11, srcC.extract(new ItemKey(COBBLE, 1), 1000, true).amount());
    }

    // ---- 9. raced commit ---------------------------------------------------------------------

    @Test
    void racedCommit_extractReturnsLess_stackCarriesActualAmount() {
        // firstExtractable promises 16, but by commit time only 9 are actually present (raced).
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        Destination destination = dest(1, 0, 0, 0, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(src));
        FakeContainer srcC = new FakeContainer(COBBLE, 64, 0).raceTo(9);
        FakeContainer destC = new FakeContainer(COBBLE, 0, 64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, srcC)
            .put(1, 0, 0, destC);

        TravelingStack s = tryExtract(src, net, grid, containers, endpoints, 0);

        assertNotNull(s);
        assertEquals(9, s.count(), "stack carries the ACTUAL committed amount, not the promised 16");
    }

    @Test
    void racedCommit_extractReturnsZero_returnsNull() {
        FakeGrid grid = new FakeGrid().put(0, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        Destination destination = dest(1, 0, 0, 0, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(src));
        FakeContainer srcC = new FakeContainer(COBBLE, 64, 0).raceTo(0); // emptied before commit
        FakeContainer destC = new FakeContainer(COBBLE, 0, 64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, srcC)
            .put(1, 0, 0, destC);

        assertNull(tryExtract(src, net, grid, containers, endpoints, 0),
            "a raced commit of 0 yields no stack (never a count<=0 TravelingStack)");
    }

    // ---- 10. metadata threads source -> confirmation simulate AND onto the TravelingStack ----------

    @Test
    void confirmationSimulate_receivesSourceStackMetadata_andStackCarriesIt() {
        // CRITICAL review fix: the destination-confirmation simulate must see the REAL metadata of the
        // stack about to travel (engine isStackableWith compares metadata), and the launched
        // TravelingStack must carry that metadata so delivery round-trips byte-for-byte.
        FakeGrid grid = new FakeGrid()
            .put(0, 0, 0, itemPipe())
            .put(1, 0, 0, itemPipe());
        Network net = networkAt(0, 0, 0, grid);
        Source src = source(-1, 0, 0, 0, 0, 0, 1);
        Destination destination = dest(2, 0, 0, 1, 0, 0, 0);
        Endpoints endpoints = new Endpoints(List.of(destination), List.of(src));
        org.bson.BsonDocument meta = new org.bson.BsonDocument("Damage", new org.bson.BsonInt32(7));
        FakeContainer srcC = new FakeContainer(COBBLE, 64, 0).sourceMetadata(meta);
        FakeContainer destC = new FakeContainer(COBBLE, 0, 64);
        FakeContainerLookup containers = new FakeContainerLookup()
            .put(-1, 0, 0, srcC)
            .put(2, 0, 0, destC);

        TravelingStack s = tryExtract(src, net, grid, containers, endpoints, 0);

        assertNotNull(s);
        // The confirmation simulate ran against the destination with the source stack's REAL metadata.
        assertTrue(destC.insertSimulateCalled, "destination confirmation simulate must run");
        assertEquals(meta, destC.lastInsertSimulateMetadata,
            "confirmation simulate must receive the source stack's metadata, not null");
        // The launched stack carries the commit's metadata so delivery round-trips byte-for-byte.
        assertEquals(meta, s.metadata(), "the TravelingStack carries the extracted metadata");
    }
}
