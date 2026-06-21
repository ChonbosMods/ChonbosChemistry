package com.chonbosmods.chemistry.impl.block.net.item;

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

        FakeMachines put(int x, int y, int z, PortDirection dir, ContainerView view) {
            long k = NetworkManager.packKey(x, y, z);
            ports.put(k, PortConfig.of(List.of(Port.of(0, PortChannel.ITEM, dir))));
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
    void machineOutputPort_returnsOutputBenchView() {
        ContainerView out = stub("bench-output");
        MachineAwareContainerLookup lookup = new MachineAwareContainerLookup(
            new FakeChests(), new FakeMachines().put(2, 0, 0, PortDirection.OUTPUT, out));
        assertSame(out, lookup.at(2, 0, 0));
    }

    @Test
    void machineInputPort_returnsInputBenchView() {
        ContainerView in = stub("bench-input");
        MachineAwareContainerLookup lookup = new MachineAwareContainerLookup(
            new FakeChests(), new FakeMachines().put(3, 0, 0, PortDirection.INPUT, in));
        assertSame(in, lookup.at(3, 0, 0));
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
