package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;

/**
 * Live {@link MachineLookup} over a {@link World}: resolves the machine/tank block entity at a position
 * via {@link BlockModule#getBlockEntity} and adapts it to {@link MachinePorts}. Thin glue (no logic
 * beyond lookup + adapt); verified on devServer, not unit-tested.
 *
 * <p>Both {@link MachineBlockState} and {@link TankBlockState} already expose
 * {@code ports()}/{@code energy()}/{@code resource(channel)} (via {@code TransferNode}), so each maps
 * to a {@link MachinePorts} view directly. Machine takes precedence when both are present (mirrors
 * {@code MachineTickSystem.neighborNode}).
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
        Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, x, y, z);
        if (ref == null || !ref.isValid()) {
            return null;
        }
        MachineBlockState machine = store.getComponent(ref, machineType);
        if (machine != null) {
            return adapt(machine.ports(), machine.energy(), machine::resource);
        }
        TankBlockState tank = store.getComponent(ref, tankType);
        if (tank != null) {
            return adapt(tank.ports(), tank.energy(), tank::resource);
        }
        return null;
    }

    /** Functional view over a block-state's three TransferNode accessors. */
    private interface ResourceFn {
        ResourceBuffer apply(PortChannel channel);
    }

    private static MachinePorts adapt(PortConfig ports, EnergyHandler energy, ResourceFn resource) {
        return new MachinePorts() {
            @Override
            public PortConfig ports() {
                return ports;
            }

            @Override
            public EnergyHandler energy() {
                return energy;
            }

            @Override
            public ResourceBuffer resource(PortChannel channel) {
                return resource.apply(channel);
            }
        };
    }
}
