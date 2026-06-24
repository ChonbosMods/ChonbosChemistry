package com.chonbosmods.chemistry.impl.block.net.item;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup;
import com.chonbosmods.chemistry.impl.block.net.MachineLookup.MachinePorts;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;

/**
 * The composite {@link ContainerLookup} the ITEM transfer driver resolves endpoints against: it overlays
 * machine ITEM ports onto passive chests, so the driver moves items to/from a smelter's item-in / item-out
 * port exactly as it does a chest, with NO changes to the driver/extraction/transit internals (Task 14).
 *
 * <p>Resolution at a cell:
 * <ol>
 *   <li>a passive chest via the wrapped {@link ContainerLookup} (chest takes precedence), else</li>
 *   <li>the cell's ITEM port(s) bench-backed view via {@link MachineLookup}, else</li>
 *   <li>{@code null}.</li>
 * </ol>
 *
 * <p>Correctness rests on the endpoint roles {@link ItemEndpoints} assigns: an OUTPUT port is only ever a
 * {@code Source} (extracted from) and an INPUT port only ever a {@code Destination} (inserted into), so the
 * direction-matched view is always used in its valid direction.
 *
 * <p>A footprint cell may expose BOTH an item-in and an item-out port (the CC Reclaimer's input + output
 * both sit on its single anchor cell). Resolution therefore routes by OPERATION, not "first port wins":
 * {@code insert} (a Destination delivery) targets the bench INPUT container, {@code extract}/
 * {@code firstExtractable} (a Source pull) the bench OUTPUT container. This is correct for single-port cells
 * too (only the valid op is ever exercised there). The earlier first-port-wins resolver returned the cell's
 * first item port's view for everything: with the Reclaimer's output port listed first, deliveries went
 * into the OUTPUT container, so a piped item landed in output and was pulled away unprocessed.
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
        // Route by operation, not "first port wins" (see class javadoc): a cell may carry an item-in AND an
        // item-out port. Insert -> bench INPUT container; extract -> bench OUTPUT container.
        boolean hasInput = false;
        boolean hasOutput = false;
        for (Port p : ports.ports()) {
            if (p == null || p.channel() != PortChannel.ITEM) {
                continue;
            }
            if (p.direction() == PortDirection.INPUT || p.direction() == PortDirection.BOTH) {
                hasInput = true;
            }
            if (p.direction() == PortDirection.OUTPUT || p.direction() == PortDirection.BOTH) {
                hasOutput = true;
            }
        }
        if (!hasInput && !hasOutput) {
            return null;
        }
        ContainerView insertView = hasInput ? mp.itemContainer(PortDirection.INPUT) : null;
        ContainerView extractView = hasOutput ? mp.itemContainer(PortDirection.OUTPUT) : null;
        return new DirectionalMachineView(insertView, extractView);
    }

    /**
     * A machine cell's container view that sends INSERTs to the bench input container and EXTRACTs to the
     * bench output container. Either side may be {@code null} (a cell with only one item port); the absent
     * side is a no-op ({@code insert -> 0}, {@code extract -> 0}, {@code firstExtractable -> null}), which is
     * never hit in practice because {@link ItemEndpoints} only delivers to input-port faces and pulls from
     * output-port faces.
     */
    private static final class DirectionalMachineView implements ContainerView {

        private final ContainerView insertView;
        private final ContainerView extractView;

        DirectionalMachineView(ContainerView insertView, ContainerView extractView) {
            this.insertView = insertView;
            this.extractView = extractView;
        }

        @Override
        public int insert(ItemKey key, BsonDocument metadata, int amount, boolean simulate) {
            return insertView == null ? 0 : insertView.insert(key, metadata, amount, simulate);
        }

        @Override
        public Peek firstExtractable(ItemFilter filter, long pipeKey, int viaFace, int cap) {
            return extractView == null ? null : extractView.firstExtractable(filter, pipeKey, viaFace, cap);
        }

        @Override
        public Extracted extract(ItemKey key, int amount, boolean simulate) {
            return extractView == null ? new Extracted(0, null) : extractView.extract(key, amount, simulate);
        }
    }
}
