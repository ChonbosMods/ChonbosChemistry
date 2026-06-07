package com.chonbosmods.chemistry.impl.block.net.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import java.util.HashMap;
import java.util.Map;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/**
 * Multi-tick end-to-end simulation of the PURE item-transport layer (driver + fakes): a source chest
 * with a PULL face, a 3-pipe run, a destination chest. Drives hundreds of consecutive ticks the way
 * {@code NetworkTickSystem} does in-game, pinning that extraction REPEATS every pull interval until
 * the source drains (bug report 2026-06-06: in-game transfer ran once then stalled: this test bisects
 * pure-layer correctness from engine-glue issues).
 */
class ItemTransferSimulationTest {

    private static final class FakeGrid implements PipeGridView {
        private final Map<Long, PipeNode> byKey = new HashMap<>();

        FakeGrid put(int x, int y, int z, PipeNode node) {
            byKey.put(NetworkManager.packKey(x, y, z), node);
            return this;
        }

        @Override
        public PipeNode pipeAt(int x, int y, int z) {
            return byKey.get(NetworkManager.packKey(x, y, z));
        }
    }

    /** A chest: id-keyed contents, simple capacity in items. */
    private static final class FakeChest implements ContainerLookup.ContainerView {
        final Map<String, Integer> contents = new HashMap<>();
        int capacity;

        FakeChest(int capacity) {
            this.capacity = capacity;
        }

        int total() {
            return contents.values().stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public int insert(ItemKey key, BsonDocument metadata, int amount, boolean simulate) {
            int room = capacity - total();
            int accepted = Math.max(0, Math.min(amount, room));
            if (!simulate && accepted > 0) {
                contents.merge(key.id(), accepted, Integer::sum);
            }
            return accepted;
        }

        @Override
        public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
            for (Map.Entry<String, Integer> e : contents.entrySet()) {
                if (e.getValue() <= 0) {
                    continue;
                }
                ItemKey key = new ItemKey(e.getKey(), Math.min(e.getValue(), cap));
                if (filter == null || filter.admits(key, pipeKey, viaFace)) {
                    return new Peek(key, null);
                }
            }
            return null;
        }

        @Override
        public Extracted extract(ItemKey key, int amount, boolean simulate) {
            int have = contents.getOrDefault(key.id(), 0);
            int pulled = Math.max(0, Math.min(amount, have));
            if (!simulate && pulled > 0) {
                contents.merge(key.id(), -pulled, Integer::sum);
            }
            return new Extracted(pulled, null);
        }
    }

    private static final class FakeContainers implements ContainerLookup {
        private final Map<Long, ContainerView> byKey = new HashMap<>();

        FakeContainers put(int x, int y, int z, ContainerView view) {
            byKey.put(NetworkManager.packKey(x, y, z), view);
            return this;
        }

        @Override
        public ContainerView at(int x, int y, int z) {
            return byKey.get(NetworkManager.packKey(x, y, z));
        }
    }

    @Test
    void sourceChestDrainsCompletelyOverManyTicks() {
        // sourceChest(-1,0,0) <-PULL- pipe0(0,0,0) - pipe1(1,0,0) - pipe2(2,0,0) -NORMAL-> destChest(3,0,0)
        PipeNode p0 = PipeNode.of(PortChannel.ITEM, 1);
        p0.setFlowState(1, FlowState.PULL); // -X face toward the source chest
        PipeNode p1 = PipeNode.of(PortChannel.ITEM, 1);
        PipeNode p2 = PipeNode.of(PortChannel.ITEM, 1);
        FakeGrid grid = new FakeGrid().put(0, 0, 0, p0).put(1, 0, 0, p1).put(2, 0, 0, p2);

        Network net = new Network(PortChannel.ITEM);
        net.addMember(NetworkManager.packKey(0, 0, 0), 500, 5);
        net.addMember(NetworkManager.packKey(1, 0, 0), 500, 5);
        net.addMember(NetworkManager.packKey(2, 0, 0), 500, 5);

        FakeChest source = new FakeChest(1000);
        source.contents.put("item:cobble", 64);
        FakeChest dest = new FakeChest(1000);
        FakeContainers containers = new FakeContainers()
            .put(-1, 0, 0, source)
            .put(3, 0, 0, dest);

        ItemTransferDriver driver = new ItemTransferDriver(5, 20, 16);
        ItemTransferDriver.DropSink noDrops = (key, stack) ->
            org.junit.jupiter.api.Assertions.fail("nothing should pop out in the happy path");
        ItemTransferDriver.SaveMarker noopSave = key -> { };

        // Drive 600 consecutive world ticks, recollecting endpoints each tick like NetworkTickSystem.
        for (long tick = 1; tick <= 600; tick++) {
            ItemEndpoints.Endpoints endpoints = ItemEndpoints.collect(net, grid, containers);
            driver.tickNetwork(net, grid, containers, endpoints, FilterLookup.NONE, tick, noDrops, noopSave);
        }

        assertEquals(0, source.total(), "source chest must drain completely (64 items, 16/pull)");
        assertEquals(64, dest.contents.getOrDefault("item:cobble", 0).intValue(),
            "every item must arrive at the destination");
        assertTrue(p0.inTransit().isEmpty() && p1.inTransit().isEmpty() && p2.inTransit().isEmpty(),
            "no stacks left stranded in the pipes");
    }
}
