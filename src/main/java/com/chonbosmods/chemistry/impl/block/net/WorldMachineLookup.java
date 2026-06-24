package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.PortProjection;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.chonbosmods.chemistry.impl.block.bench.VanillaBenchBridge;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup;
import com.chonbosmods.chemistry.impl.block.net.item.ItemContainerView;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.accessor.BlockAccessor;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.util.FillerBlockUtil;
import javax.annotation.Nonnull;

/**
 * Live {@link MachineLookup} over a {@link World}: resolves the machine/tank serving a block position and
 * adapts it to a per-cell {@link MachinePorts}. Verified on devServer, not unit-tested (the pure pieces it
 * composes — {@link PortConfig}/{@link PortProjection} — are).
 *
 * <h2>Multi-cell footprints</h2>
 * A {@code DrawType: Model} machine can claim several cells (anchor + {@link FillerBlockUtil} fillers). The
 * block entity lives ONLY on the anchor; filler cells carry no entity (confirmed in-game 2026-06-20: a
 * smelter filler resolves {@code getBlockEntity == null}). So a query at any cell first resolves the anchor
 * (anchor = cell − {@link FillerBlockUtil#unpackX unpack}(filler)), reads the anchor's stored, model-space
 * {@link PortConfig}, and returns a view exposing only THIS cell's ports, projected to world faces by the
 * placed block's yaw {@link Rotation} (see {@link PortProjection}). Energy/resource come from the anchor.
 *
 * <p>{@link MachinePorts#ports()} is therefore per-cell (drives the transport collector + effective visual
 * mask), while {@link MachinePorts#advertisesChannel} is anchor-level — the engine welds a connected-block
 * arm toward every footprint cell that inherits the anchor's FaceTags, so the physical visual mask must see
 * the whole machine, not the per-cell ports. A filler cell with no own ports thus has {@code effective <
 * physical} and its inherited-tag pipe arm gets dropped. See [[filler-cells-inherit-anchor-facetags]].
 */
public final class WorldMachineLookup implements MachineLookup {

    private final World world;
    private final Store<ChunkStore> store;
    private final ComponentType<ChunkStore, MachineBlockState> machineType;
    private final ComponentType<ChunkStore, TankBlockState> tankType;

    public WorldMachineLookup(
            @Nonnull World world,
            @Nonnull Store<ChunkStore> store,
            @Nonnull ComponentType<ChunkStore, MachineBlockState> machineType,
            @Nonnull ComponentType<ChunkStore, TankBlockState> tankType) {
        this.world = world;
        this.store = store;
        this.machineType = machineType;
        this.tankType = tankType;
    }

    @Override
    public MachinePorts at(int x, int y, int z) {
        BlockAccessor accessor = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
        if (accessor == null) {
            return null; // chunk not loaded: cannot resolve filler/rotation
        }

        // Resolve this cell to its footprint anchor + the cell's world offset from that anchor.
        int filler = accessor.getFiller(x, y, z);
        int wcx, wcy, wcz, ax, ay, az;
        if (filler == FillerBlockUtil.NO_FILLER) {
            wcx = 0; wcy = 0; wcz = 0;
            ax = x; ay = y; az = z;
        } else {
            wcx = FillerBlockUtil.unpackX(filler);
            wcy = FillerBlockUtil.unpackY(filler);
            wcz = FillerBlockUtil.unpackZ(filler);
            ax = x - wcx; ay = y - wcy; az = z - wcz;
        }

        Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, ax, ay, az);
        if (ref == null || !ref.isValid()) {
            return null; // no machine/tank entity at the resolved anchor
        }

        Rotation rotation = rotationAt(accessor, ax, ay, az);
        MachineBlockState machine = store.getComponent(ref, machineType);
        if (machine != null) {
            PortConfig model = machine.ports();
            PortConfig cell = PortProjection.forWorldCell(model, rotation, wcx, wcy, wcz);
            return adapt(cell, model, machine.energy(), machine::resource, machine.heldBench());
        }
        TankBlockState tank = store.getComponent(ref, tankType);
        if (tank != null) {
            PortConfig model = tank.ports();
            PortConfig cell = PortProjection.forWorldCell(model, rotation, wcx, wcy, wcz);
            return adapt(cell, model, tank.energy(), tank::resource, null); // tanks hold no item bench
        }
        return null;
    }

    /** Maps the placed block's stored rotation index to a yaw {@link Rotation} (NESW: 4 values). */
    private static Rotation rotationAt(BlockAccessor accessor, int x, int y, int z) {
        int idx = accessor.getRotationIndex(x, y, z);
        Rotation[] vals = Rotation.values();
        return vals[((idx % vals.length) + vals.length) % vals.length];
    }

    /** Functional view over a block-state's per-channel resource accessor. */
    private interface ResourceFn {
        ResourceBuffer apply(PortChannel channel);
    }

    /**
     * @param cellPorts the per-cell, world-face {@link PortConfig} for the queried cell (drives transport +
     *     effective mask); {@code modelPorts} the anchor's whole model-space config (drives the anchor-level
     *     {@link MachinePorts#advertisesChannel}, which mirrors the engine's inherited-tag welding).
     */
    private static MachinePorts adapt(
            PortConfig cellPorts, PortConfig modelPorts, EnergyHandler energy, ResourceFn resource,
            ProcessingBenchBlock bench) {
        return new MachinePorts() {
            @Override
            public PortConfig ports() {
                return cellPorts;
            }

            @Override
            public boolean advertisesChannel(PortChannel channel) {
                if (modelPorts == null) {
                    return false;
                }
                for (Port p : modelPorts.ports()) {
                    if (p != null && p.channel() == channel) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public EnergyHandler energy() {
                return energy;
            }

            @Override
            public ResourceBuffer resource(PortChannel channel) {
                return resource.apply(channel);
            }

            @Override
            public ContainerLookup.ContainerView itemContainer(PortDirection direction) {
                if (bench == null) {
                    return null; // no held bench (a tank, or a non-bench machine): no item container
                }
                ItemContainer container = switch (direction) {
                    case INPUT -> VanillaBenchBridge.input(bench);   // ingredients fed IN
                    case OUTPUT -> VanillaBenchBridge.output(bench);  // results drained OUT
                    default -> null; // BOTH/CLOSED: the smelter's item ports are INPUT/OUTPUT only
                };
                return container == null ? null : new ItemContainerView(container);
            }
        };
    }
}
