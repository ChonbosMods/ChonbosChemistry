package com.chonbosmods.chemistry.impl.block.net.item;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup.MachinePorts;
import javax.annotation.Nonnull;

/**
 * The composite {@link ContainerLookup} the ITEM transfer driver resolves endpoints against: it overlays
 * machine ITEM ports onto passive chests, so the driver moves items to/from a smelter's item-in / item-out
 * port exactly as it does a chest, with NO changes to the driver/extraction/transit internals (Task 14).
 *
 * <p>Resolution at a cell:
 * <ol>
 *   <li>a passive chest via the wrapped {@link ContainerLookup} (chest takes precedence), else</li>
 *   <li>the cell's single ITEM port's bench-backed view via {@link MachineLookup}: the per-cell
 *       {@link MachinePorts#ports()} is scanned for an ITEM {@link Port}, and
 *       {@link MachinePorts#itemContainer} is returned for THAT port's direction (an {@code OUTPUT} port
 *       &rarr; the bench output/extract view, an {@code INPUT} port &rarr; the bench input/insert view),
 *       else</li>
 *   <li>{@code null}.</li>
 * </ol>
 *
 * <p>Correctness rests on the endpoint roles {@link ItemEndpoints} assigns: an OUTPUT port is only ever a
 * {@code Source} (extracted from) and an INPUT port only ever a {@code Destination} (inserted into), so the
 * direction-matched view is always used in its valid direction. ASSUMPTION (holds for the smelter and the
 * current machines): a footprint cell exposes at most one ITEM port; the first ITEM port found wins. A
 * future machine with both an input and an output item port on the SAME cell would need a face-aware
 * resolver here.
 */
public final class MachineAwareContainerLookup implements ContainerLookup {

    private final ContainerLookup chests;
    private final MachineLookup machines;

    public MachineAwareContainerLookup(@Nonnull ContainerLookup chests, @Nonnull MachineLookup machines) {
        this.chests = chests;
        this.machines = machines;
    }

    @Override
    public ContainerView at(int x, int y, int z) {
        ContainerView chest = chests.at(x, y, z);
        if (chest != null) {
            return chest; // passive chest takes precedence
        }
        MachinePorts mp = machines.at(x, y, z);
        if (mp == null) {
            return null;
        }
        PortConfig ports = mp.ports();
        if (ports == null) {
            return null;
        }
        for (Port p : ports.ports()) {
            if (p != null && p.channel() == PortChannel.ITEM) {
                return mp.itemContainer(p.direction()); // bench input/output view for this cell's item port
            }
        }
        return null;
    }
}
