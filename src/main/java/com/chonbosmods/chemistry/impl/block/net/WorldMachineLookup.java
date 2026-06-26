package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.craft.CookerState;
import com.chonbosmods.chemistry.impl.block.craft.OutfitterState;
import com.chonbosmods.chemistry.impl.block.craft.ForgeCraftState;
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
    private final ComponentType<ChunkStore, ForgeCraftState> forgeType;
    private final ComponentType<ChunkStore, CookerState> cookerType;
    private final ComponentType<ChunkStore, OutfitterState> outfitterType;

    public WorldMachineLookup(
            @Nonnull World world,
            @Nonnull Store<ChunkStore> store,
            @Nonnull ComponentType<ChunkStore, MachineBlockState> machineType,
            @Nonnull ComponentType<ChunkStore, TankBlockState> tankType,
            @Nonnull ComponentType<ChunkStore, ForgeCraftState> forgeType,
            @Nonnull ComponentType<ChunkStore, CookerState> cookerType,
            @Nonnull ComponentType<ChunkStore, OutfitterState> outfitterType) {
        this.world = world;
        this.store = store;
        this.machineType = machineType;
        this.tankType = tankType;
        this.forgeType = forgeType;
        this.cookerType = cookerType;
        this.outfitterType = outfitterType;
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
            // The Smelter/Reclaimer borrows its held vanilla bench's input/output containers.
            return adapt(cell, model, machine.energy(), machine::resource, benchItems(machine.heldBench()));
        }
        TankBlockState tank = store.getComponent(ref, tankType);
        if (tank != null) {
            PortConfig model = tank.ports();
            PortConfig cell = PortProjection.forWorldCell(model, rotation, wcx, wcy, wcz);
            // Tanks hold no item bench: their item-container source returns null for every direction.
            return adapt(cell, model, tank.energy(), tank::resource, NO_ITEMS);
        }
        ForgeCraftState forge = store.getComponent(ref, forgeType);
        if (forge != null) {
            PortConfig model = forge.ports();
            PortConfig cell = PortProjection.forWorldCell(model, rotation, wcx, wcy, wcz);
            // The Forge has no vanilla bench: it owns its input/output containers directly. It carries no
            // fluid/gas buffers, so its resource accessor returns null for every channel (item + power only).
            return adapt(cell, model, forge.energy(), channel -> null, forgeItems(forge));
        }
        CookerState cooker = store.getComponent(ref, cookerType);
        if (cooker != null) {
            PortConfig model = cooker.ports();
            PortConfig cell = PortProjection.forWorldCell(model, rotation, wcx, wcy, wcz);
            // The Cooker has no vanilla bench: it owns its input/output containers directly. It carries no
            // fluid/gas buffers, so its resource accessor returns null for every channel (item + power only).
            return adapt(cell, model, cooker.energy(), channel -> null, cookerItems(cooker));
        }
        OutfitterState outfitter = store.getComponent(ref, outfitterType);
        if (outfitter != null) {
            PortConfig model = outfitter.ports();
            PortConfig cell = PortProjection.forWorldCell(model, rotation, wcx, wcy, wcz);
            // The Outfitter has no vanilla bench: it owns its input/output containers directly. It carries no
            // fluid/gas buffers, so its resource accessor returns null for every channel (item + power only).
            return adapt(cell, model, outfitter.energy(), channel -> null, outfitterItems(outfitter));
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
     * Functional view over a block-state's per-direction item container. Generalizes the item-container
     * source so each block-state path supplies its own: the Smelter/Reclaimer from its held vanilla bench
     * ({@link #benchItems}), the Forge from its own owned containers ({@link #forgeItems}), and a tank from
     * nothing ({@link #NO_ITEMS}). Returns {@code null} for a direction with no item container.
     */
    private interface ItemContainerFn {
        ItemContainer apply(PortDirection direction);
    }

    /** The no-item source (tanks): no item container in any direction. */
    private static final ItemContainerFn NO_ITEMS = direction -> null;

    /**
     * The Smelter/Reclaimer item source: borrows the held vanilla bench's input (ingredients fed IN) and
     * output (results drained OUT) containers. {@code null} bench (no held bench) yields no item container.
     */
    private static ItemContainerFn benchItems(ProcessingBenchBlock bench) {
        if (bench == null) {
            return NO_ITEMS;
        }
        return direction -> switch (direction) {
            case INPUT -> VanillaBenchBridge.input(bench);   // ingredients fed IN
            case OUTPUT -> VanillaBenchBridge.output(bench);  // results drained OUT
            default -> null; // BOTH/CLOSED: the bench's item ports are INPUT/OUTPUT only
        };
    }

    /**
     * The Forge item source: the Forge is a demand-driven PULLER, so it advertises NO pushable input
     * container. The West ITEM INPUT port still exists (it drives the pipe connection and lets
     * {@link com.chonbosmods.chemistry.impl.block.craft.NetworkRecipeSource} resolve the input network), but
     * INPUT returns {@code null}: the generic push transport must never deposit into {@code held()}, which
     * holds only the active craft's pulled ingredients (a stray push would clog that invariant). The Forge
     * sources its own ingredients via {@code NetworkRecipeSource}; only the OUTPUT side ({@link
     * ForgeCraftState#output()}) participates in network push (results drained OUT). Same INPUT/OUTPUT-only
     * port shape as the bench source, but with the INPUT container deliberately withheld.
     */
    private static ItemContainerFn forgeItems(ForgeCraftState forge) {
        return direction -> switch (direction) {
            case INPUT -> null;            // pull-window: port exists, but no pushable container (Forge PULLS)
            case OUTPUT -> forge.output(); // results drained OUT
            default -> null; // BOTH/CLOSED: the Forge's item ports are INPUT/OUTPUT only
        };
    }

    /**
     * The Cooker item source: like the Forge, the Cooker is a demand-driven PULLER, so it advertises NO
     * pushable input container. The ITEM INPUT port still exists (it drives the pipe connection and lets
     * {@link com.chonbosmods.chemistry.impl.block.craft.NetworkRecipeSource} resolve the input network), but
     * INPUT returns {@code null}: the generic push transport must never deposit into {@code held()}, which
     * holds only the active craft's pulled ingredients (a stray push would clog that invariant). The Cooker
     * sources its own ingredients via {@code NetworkRecipeSource}; only the OUTPUT side ({@link
     * CookerState#output()}) participates in network push (results drained OUT). Same INPUT/OUTPUT-only port
     * shape as the Forge source, with the INPUT container deliberately withheld.
     */
    private static ItemContainerFn cookerItems(CookerState cooker) {
        return direction -> switch (direction) {
            case INPUT -> null;             // pull-window: port exists, but no pushable container (Cooker PULLS)
            case OUTPUT -> cooker.output(); // results drained OUT
            default -> null; // BOTH/CLOSED: the Cooker's item ports are INPUT/OUTPUT only
        };
    }

    /**
     * The Outfitter item source: like the Cooker, the Outfitter is a demand-driven PULLER, so it advertises NO
     * pushable input container. The ITEM INPUT port still exists (it drives the pipe connection and lets
     * {@link com.chonbosmods.chemistry.impl.block.craft.NetworkRecipeSource} resolve the input network), but
     * INPUT returns {@code null}: the generic push transport must never deposit into {@code held()}, which
     * holds only the active craft's pulled ingredients (a stray push would clog that invariant). The Outfitter
     * sources its own ingredients via {@code NetworkRecipeSource}; only the OUTPUT side ({@link
     * OutfitterState#output()}) participates in network push (results drained OUT). Same INPUT/OUTPUT-only port
     * shape as the Cooker source, with the INPUT container deliberately withheld.
     */
    private static ItemContainerFn outfitterItems(OutfitterState outfitter) {
        return direction -> switch (direction) {
            case INPUT -> null;                // pull-window: port exists, but no pushable container (Outfitter PULLS)
            case OUTPUT -> outfitter.output(); // results drained OUT
            default -> null; // BOTH/CLOSED: the Outfitter's item ports are INPUT/OUTPUT only
        };
    }

    /**
     * @param cellPorts the per-cell, world-face {@link PortConfig} for the queried cell (drives transport +
     *     effective mask); {@code modelPorts} the anchor's whole model-space config (drives the anchor-level
     *     {@link MachinePorts#advertisesChannel}, which mirrors the engine's inherited-tag welding).
     */
    private static MachinePorts adapt(
            PortConfig cellPorts, PortConfig modelPorts, EnergyHandler energy, ResourceFn resource,
            ItemContainerFn items) {
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
                ItemContainer container = items.apply(direction);
                return container == null ? null : new ItemContainerView(container);
            }
        };
    }
}
