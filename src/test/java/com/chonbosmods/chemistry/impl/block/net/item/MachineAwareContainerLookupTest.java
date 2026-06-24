package com.chonbosmods.chemistry.impl.block.net.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup.MachinePorts;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MachineAwareContainerLookup}: the composite container resolver the item transfer
 * driver uses. It returns a passive chest's view when present, else a machine ITEM port's bench-backed
 * view for the cell's single item port (OUTPUT &rarr; the extract view, INPUT &rarr; the insert view),
 * else null. Chest takes precedence over a machine at the same cell.
 */
class MachineAwareContainerLookupTest {

    private static ContainerView stub(String label) {
        return new ContainerView() {
            @Override
            public int insert(ItemKey key, BsonDocument metadata, int amount, boolean simulate) {
                return 0;
            }

            @Override
            public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
                return null;
            }

            @Override
            public Extracted extract(ItemKey key, int amount, boolean simulate) {
                return new Extracted(0, null);
            }

            @Override
            public String toString() {
                return label;
            }
        };
    }

    /** A view whose {@code insert}/{@code extract} return identifiable sentinels, so a test can tell which
     *  underlying bench container (input vs output) the composite routed an operation to. */
    private static ContainerView behaviorView(String label, int insertResult, int extractResult) {
        return new ContainerView() {
            @Override
            public int insert(ItemKey key, BsonDocument metadata, int amount, boolean simulate) {
                return insertResult;
            }

            @Override
            public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
                return null;
            }

            @Override
            public Extracted extract(ItemKey key, int amount, boolean simulate) {
                return new Extracted(extractResult, null);
            }

            @Override
            public String toString() {
                return label;
            }
        };
    }

    private static final class FakeChests implements ContainerLookup {
        private final Map<Long, ContainerView> byKey = new HashMap<>();

        FakeChests put(int x, int y, int z, ContainerView v) {
            byKey.put(NetworkManager.packKey(x, y, z), v);
            return this;
        }

        @Override
        public ContainerView at(int x, int y, int z) {
            return byKey.get(NetworkManager.packKey(x, y, z));
        }
    }

    /** A machine with one ITEM port on a cell; itemContainer(dir) returns a per-direction sentinel view. */
    private static final class FakeMachines implements MachineLookup {
        private final Map<Long, PortConfig> ports = new HashMap<>();
        private final Map<Long, Map<PortDirection, ContainerView>> views = new HashMap<>();

        /** Adds an ITEM port (accumulating: call twice for a cell with both an in and an out port). */
        FakeMachines put(int x, int y, int z, PortDirection dir, ContainerView view) {
            long k = NetworkManager.packKey(x, y, z);
            int face = dir == PortDirection.OUTPUT ? 0 : 1; // distinct faces; resolver is direction-keyed
            List<Port> list = new ArrayList<>();
            PortConfig existing = ports.get(k);
            if (existing != null) {
                list.addAll(existing.ports());
            }
            list.add(Port.of(face, PortChannel.ITEM, dir));
            ports.put(k, PortConfig.of(list));
            views.computeIfAbsent(k, kk -> new HashMap<>()).put(dir, view);
            return this;
        }

        @Override
        public MachinePorts at(int x, int y, int z) {
            long k = NetworkManager.packKey(x, y, z);
            PortConfig cfg = ports.get(k);
            if (cfg == null) {
                return null;
            }
            Map<PortDirection, ContainerView> v = views.getOrDefault(k, Map.of());
            return new MachinePorts() {
                @Override
                public PortConfig ports() {
                    return cfg;
                }

                @Override
                public EnergyHandler energy() {
                    return null;
                }

                @Override
                public ResourceBuffer resource(PortChannel channel) {
                    return null;
                }

                @Override
                public ContainerView itemContainer(PortDirection direction) {
                    return v.get(direction);
                }
            };
        }
    }

    @Test
    void chestPresent_returnsChestView() {
        ContainerView chest = stub("chest");
        MachineAwareContainerLookup lookup =
            new MachineAwareContainerLookup(new FakeChests().put(1, 0, 0, chest), new FakeMachines());
        assertSame(chest, lookup.at(1, 0, 0));
    }

    @Test
    void machineOutputPort_extractHitsOutputView() {
        ContainerView out = behaviorView("bench-output", 0, 9);
        MachineAwareContainerLookup lookup = new MachineAwareContainerLookup(
            new FakeChests(), new FakeMachines().put(2, 0, 0, PortDirection.OUTPUT, out));
        ContainerView v = lookup.at(2, 0, 0);
        assertEquals(9, v.extract(null, 5, false).amount(), "extract must hit the bench OUTPUT view");
        assertEquals(0, v.insert(null, null, 5, false), "no input port on this cell -> insert is a no-op");
    }

    @Test
    void machineInputPort_insertHitsInputView() {
        ContainerView in = behaviorView("bench-input", 7, 0);
        MachineAwareContainerLookup lookup = new MachineAwareContainerLookup(
            new FakeChests(), new FakeMachines().put(3, 0, 0, PortDirection.INPUT, in));
        ContainerView v = lookup.at(3, 0, 0);
        assertEquals(7, v.insert(null, null, 5, false), "insert must hit the bench INPUT view");
        assertEquals(0, v.extract(null, 5, false).amount(), "no output port on this cell -> nothing to pull");
    }

    @Test
    void inputAndOutputOnSameCell_insertToInput_extractFromOutput() {
        // CC Reclaimer: item-in + item-out share the anchor cell, output listed FIRST (as in CC_Reclaimer.json).
        // Insert (a Destination delivery) must reach the bench INPUT container and extract (a Source pull) the
        // OUTPUT container. "First item port wins" sent deliveries into output -> item landed in output and was
        // pulled away unprocessed.
        ContainerView in = behaviorView("bench-input", 7, 0);
        ContainerView out = behaviorView("bench-output", 0, 9);
        MachineAwareContainerLookup lookup = new MachineAwareContainerLookup(new FakeChests(),
            new FakeMachines()
                .put(5, 0, 0, PortDirection.OUTPUT, out)
                .put(5, 0, 0, PortDirection.INPUT, in));
        ContainerView v = lookup.at(5, 0, 0);
        assertEquals(7, v.insert(null, null, 3, false), "delivery must go to the bench INPUT container");
        assertEquals(9, v.extract(null, 3, false).amount(), "extraction must pull the bench OUTPUT container");
    }

    @Test
    void chestTakesPrecedenceOverMachine() {
        ContainerView chest = stub("chest");
        ContainerView benchOut = stub("bench-output");
        MachineAwareContainerLookup lookup = new MachineAwareContainerLookup(
            new FakeChests().put(4, 0, 0, chest),
            new FakeMachines().put(4, 0, 0, PortDirection.OUTPUT, benchOut));
        assertSame(chest, lookup.at(4, 0, 0));
    }

    @Test
    void emptyCell_returnsNull() {
        MachineAwareContainerLookup lookup =
            new MachineAwareContainerLookup(new FakeChests(), new FakeMachines());
        assertNull(lookup.at(9, 9, 9));
    }
}
