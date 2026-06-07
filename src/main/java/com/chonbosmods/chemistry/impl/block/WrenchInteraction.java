package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.NetworkService;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.BlockFace;
import com.hypixel.hytale.protocol.InteractionSyncData;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nullable;
import org.joml.Vector3fc;
import org.joml.Vector3i;

/**
 * The {@code CC_Wrench} block interaction: taps a pipe face to cycle its {@link FlowState}, or a
 * machine/tank face to cycle its port capability (design 2026-06-05 §4). All decision logic lives in
 * the pure, unit-tested {@link WrenchCycles}; this class is the engine glue that resolves the clicked
 * face + target from the {@link InteractionContext}, calls the cycle, persists the result, and rebuilds
 * the affected networks. It never throws: every world lookup is null-guarded (hot interaction path).
 *
 * <h2>Engine facts used (spike, design §"Spike findings (Task 8)")</h2>
 * <ul>
 *   <li><b>Clicked face:</b> read off {@link InteractionContext#getState()} →
 *       {@link InteractionSyncData#blockFace} (the server/working state), falling back to
 *       {@link InteractionContext#getClientState()}, then to the {@link InteractionSyncData#raycastNormal}
 *       hit normal (largest-abs-component + sign). This mirrors HyProTech's {@code resolveSide}.
 *       <b>{@code "UseLatestTarget": true} is mandatory in the item JSON</b> or {@code blockFace} is
 *       always {@code None}.</li>
 *   <li><b>Sneak/reverse:</b> {@link InteractionContext} has no crouch accessor; we read it off the
 *       player entity via {@code commandBuffer.getComponent(context.getEntity(),
 *       MovementStatesComponent.getComponentType())} → {@link MovementStates#crouching}.</li>
 *   <li><b>Handler signature:</b> {@code blockPos} is {@code org.joml.Vector3i} (not the math-vector
 *       type). {@code interactWithBlock} runs on the WorldThread (the engine's interaction tick), the
 *       same discipline as {@code MachinePanelPage.build}, so direct ECS component reads/writes and
 *       {@code NetworkManager} cache invalidation are safe here.</li>
 *   <li><b>Persistence:</b> mirrors HyProTech: after mutating a block-attached component we fetch the
 *       {@link BlockComponentChunk} for the block's chunk and call {@link BlockComponentChunk#markNeedsSaving()}.</li>
 * </ul>
 *
 * <h2>BlockFace → OUR face index (OFFSETS order +X,-X,+Y,-Y,+Z,-Z, indices 0..5)</h2>
 * Confirmed against HyProTech's {@code EnergySide} table (each side carries an explicit
 * {@code (dx,dy,dz)}: {@code East=(+1,0,0)}, {@code West=(-1,0,0)}, {@code Up=(0,+1,0)},
 * {@code Down=(0,-1,0)}, {@code South=(0,0,+1)}, {@code North=(0,0,-1)}), which is the authoritative
 * source for Hytale's BlockFace→world-axis mapping. Lined up against our OFFSETS order:
 * <pre>
 *   East  +X  0       West  -X  1
 *   Up    +Y  2       Down  -Y  3
 *   South +Z  4       North -Z  5
 * </pre>
 */
public final class WrenchInteraction extends SimpleBlockInteraction {

    /** Chained from {@link SimpleBlockInteraction#CODEC} (contributes the {@code UseLatestTarget} key). */
    public static final BuilderCodec<WrenchInteraction> CODEC =
        BuilderCodec.builder(WrenchInteraction.class, WrenchInteraction::new, SimpleBlockInteraction.CODEC)
            .documentation("CC_Wrench: cycles a pipe face's flow state or a machine face's port.")
            .build();

    /** Face-neighbour offsets in OFFSETS order +X,-X,+Y,-Y,+Z,-Z (mirrors NetworkManager.OFFSETS). */
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /** Public no-arg constructor for the codec supplier (HyProTech pattern). */
    public WrenchInteraction() {
        super();
    }

    @Override
    protected void interactWithBlock(
            World world,
            CommandBuffer<EntityStore> commandBuffer,
            InteractionType interactionType,
            InteractionContext context,
            @Nullable ItemStack itemStack,
            Vector3i blockPos,
            CooldownHandler cooldownHandler) {
        if (world == null || context == null || blockPos == null) {
            return;
        }
        int faceIndex = resolveFaceIndex(context);
        if (faceIndex < 0) {
            sendMessage(commandBuffer, context, "Wrench: no clicked face (aim at a block face).");
            return;
        }
        int x = blockPos.x();
        int y = blockPos.y();
        int z = blockPos.z();

        Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, x, y, z);
        if (ref == null || !ref.isValid()) {
            sendMessage(commandBuffer, context, "Wrench: nothing to configure here.");
            return;
        }
        Store<ChunkStore> store = ref.getStore();
        if (store == null) {
            return;
        }

        ChonbosChemistry plugin = ChonbosChemistry.getInstance();
        if (plugin == null) {
            return;
        }
        ComponentType<ChunkStore, PipeNode> pipeType = plugin.pipeComponentType();
        ComponentType<ChunkStore, MachineBlockState> machineType = plugin.machineComponentType();
        ComponentType<ChunkStore, TankBlockState> tankType = plugin.tankComponentType();

        boolean sneaking = isSneaking(commandBuffer, context);

        // Pipe takes precedence: a position is either a pipe or a machine/tank, never both.
        PipeNode pipe = pipeType != null ? store.getComponent(ref, pipeType) : null;
        if (pipe != null) {
            handlePipe(world, x, y, z, faceIndex, pipe, sneaking, plugin, commandBuffer, context, machineType, tankType);
            return;
        }

        MachineBlockState machine = machineType != null ? store.getComponent(ref, machineType) : null;
        if (machine != null) {
            handleMachine(world, x, y, z, faceIndex, machine, plugin, commandBuffer, context);
            return;
        }
        // TankBlockState exposes ports() but the wrench's machine cycle is keyed on MachineBlockState
        // buffers; tanks use BOTH (storage) faces configured in JSON and are not wrench-cyclable here.
        sendMessage(commandBuffer, context, "Wrench: not a pipe or machine.");
    }

    @Override
    protected void simulateInteractWithBlock(
            InteractionType interactionType,
            InteractionContext context,
            @Nullable ItemStack itemStack,
            World world,
            Vector3i blockPos) {
        // Server-side only: all mutation happens in interactWithBlock on the WorldThread.
    }

    // --- pipe face: cycle flow state ---

    private void handlePipe(
            World world, int x, int y, int z, int faceIndex, PipeNode pipe, boolean sneaking,
            ChonbosChemistry plugin, CommandBuffer<EntityStore> commandBuffer, InteractionContext context,
            @Nullable ComponentType<ChunkStore, MachineBlockState> machineType,
            @Nullable ComponentType<ChunkStore, TankBlockState> tankType) {
        int[] off = OFFSETS[faceIndex];
        int nx = x + off[0];
        int ny = y + off[1];
        int nz = z + off[2];
        WrenchCycles.Target target =
            neighbourIsEndpoint(world, nx, ny, nz, machineType, tankType) ? WrenchCycles.Target.MACHINE
                : WrenchCycles.Target.PIPE;

        FlowState current = pipe.flowState(faceIndex);
        FlowState next = sneaking ? WrenchCycles.previous(current, target) : WrenchCycles.next(current, target);
        pipe.setFlowState(faceIndex, next);
        markNeedsSaving(world, x, y, z);

        // Both sides' networks must rebuild: a flow change re-partitions connectivity across this face.
        NetworkService networkService = plugin.networkService();
        if (networkService != null) {
            NetworkManager manager = networkService.forWorld(world);
            manager.invalidate(x, y, z);
            manager.invalidate(nx, ny, nz);
        }
        sendMessage(commandBuffer, context, "Pipe face " + FaceNames.name(faceIndex) + ": " + next.jsonValue());
    }

    /**
     * True when the block across the face is a network endpoint the pipe-face cycle should treat as a
     * MACHINE-style target (the full 4-state NORMAL→PUSH→PULL→NONE ring rather than the pipe-to-pipe
     * 2-state ring). That is: a chemistry machine, a tank, or any block carrying an engine
     * {@link ItemContainerBlock} component (a vanilla chest, the item-channel's passive endpoint). The
     * container check is component PRESENCE only (the spike's detection, design §"Spike findings (Task 7)":
     * {@code BlockModule.getBlockEntity} + {@code store.getComponent(ref, ItemContainerBlock.getComponentType())}):
     * a container advertises no ports, so PUSH/PULL on the pipe face is exactly how the player aims the
     * arm into/out of it.
     */
    private boolean neighbourIsEndpoint(
            World world, int x, int y, int z,
            @Nullable ComponentType<ChunkStore, MachineBlockState> machineType,
            @Nullable ComponentType<ChunkStore, TankBlockState> tankType) {
        Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, x, y, z);
        if (ref == null || !ref.isValid()) {
            return false;
        }
        Store<ChunkStore> store = ref.getStore();
        if (store == null) {
            return false;
        }
        if (machineType != null && store.getComponent(ref, machineType) != null) {
            return true;
        }
        if (tankType != null && store.getComponent(ref, tankType) != null) {
            return true;
        }
        // A vanilla container (chest, etc.) is a passive ITEM endpoint: component presence == "has a
        // container", so its pipe face gets the full machine-style 4-cycle to aim the arm.
        return store.getComponent(ref, ItemContainerBlock.getComponentType()) != null;
    }

    // --- machine face: cycle port capability ---

    private void handleMachine(
            World world, int x, int y, int z, int faceIndex, MachineBlockState machine,
            ChonbosChemistry plugin, CommandBuffer<EntityStore> commandBuffer, InteractionContext context) {
        PortConfig ports = machine.ports();
        // The face's current port (if any). One-port-per-face: a face carries at most one port; we hand
        // the cycle the first port whose face matches so it can advance channel/direction from there.
        Port current = currentPortOnFace(ports, faceIndex);
        Port next = WrenchCycles.nextPort(machine, faceIndex, current);
        machine.setPorts(ports.withFacePort(next));
        markNeedsSaving(world, x, y, z);

        // Machines are not network members; invalidate the 6 neighbour pipe positions so endpoint
        // collection re-runs and sees the changed port.
        NetworkService networkService = plugin.networkService();
        if (networkService != null) {
            NetworkManager manager = networkService.forWorld(world);
            for (int[] o : OFFSETS) {
                manager.invalidate(x + o[0], y + o[1], z + o[2]);
            }
        }
        sendMessage(commandBuffer, context, "Face " + FaceNames.name(faceIndex) + ": " + describe(next));
    }

    /** The first port configured on {@code faceIndex} regardless of channel, or null if the face is bare. */
    private Port currentPortOnFace(PortConfig ports, int faceIndex) {
        if (ports == null) {
            return null;
        }
        for (Port p : ports.ports()) {
            if (p.faceIndex() == faceIndex) {
                return p;
            }
        }
        return null;
    }

    /** "fluid output" / "power input" / "closed" for chat feedback. */
    private String describe(Port port) {
        if (port == null) {
            return "closed";
        }
        switch (port.direction()) {
            case CLOSED:
                return "closed";
            default:
                return port.channel().jsonValue() + " " + port.direction().jsonValue();
        }
    }

    // --- clicked face resolution (mirrors HyProTech resolveSide) ---

    private int resolveFaceIndex(InteractionContext context) {
        int fromState = faceIndexFromState(context.getState());
        if (fromState >= 0) {
            return fromState;
        }
        return faceIndexFromState(context.getClientState());
    }

    private int faceIndexFromState(@Nullable InteractionSyncData state) {
        if (state == null) {
            return -1;
        }
        int fromFace = faceIndexFromBlockFace(state.blockFace);
        if (fromFace >= 0) {
            return fromFace;
        }
        return faceIndexFromNormal(state.raycastNormal);
    }

    /** Maps the engine BlockFace to our OFFSETS index (see class javadoc table). None/null → -1. */
    private int faceIndexFromBlockFace(@Nullable BlockFace face) {
        if (face == null) {
            return -1;
        }
        switch (face) {
            case East:  return 0; // +X
            case West:  return 1; // -X
            case Up:    return 2; // +Y
            case Down:  return 3; // -Y
            case South: return 4; // +Z
            case North: return 5; // -Z
            default:    return -1; // None
        }
    }

    /** Fallback: derive the face from the hit normal by largest-abs-component + sign (joml Vector3fc). */
    private int faceIndexFromNormal(@Nullable Vector3fc normal) {
        if (normal == null) {
            return -1;
        }
        float nx = normal.x();
        float ny = normal.y();
        float nz = normal.z();
        float ax = Math.abs(nx);
        float ay = Math.abs(ny);
        float az = Math.abs(nz);
        if (ax >= ay && ax >= az) {
            return nx >= 0f ? 0 : 1; // +X / -X
        }
        if (ay >= ax && ay >= az) {
            return ny >= 0f ? 2 : 3; // +Y / -Y
        }
        return nz >= 0f ? 4 : 5;     // +Z / -Z
    }

    // --- sneak read ---

    private boolean isSneaking(CommandBuffer<EntityStore> commandBuffer, InteractionContext context) {
        if (commandBuffer == null || context == null || context.getEntity() == null) {
            return false;
        }
        MovementStatesComponent comp =
            commandBuffer.getComponent(context.getEntity(), MovementStatesComponent.getComponentType());
        if (comp == null) {
            return false;
        }
        MovementStates states = comp.getMovementStates();
        return states != null && (states.crouching || states.forcedCrouching);
    }

    // --- persistence + chat ---

    /** Marks the block-component chunk for the given block needing-save (HyProTech persistence pattern). */
    private void markNeedsSaving(World world, int x, int y, int z) {
        if (y < ChunkUtil.MIN_Y || y >= ChunkUtil.HEIGHT) {
            return;
        }
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        BlockComponentChunk blockComponents =
            world.getChunkStore().getChunkComponent(chunkIndex, BlockComponentChunk.getComponentType());
        if (blockComponents != null) {
            blockComponents.markNeedsSaving();
        }
    }

    private void sendMessage(CommandBuffer<EntityStore> commandBuffer, InteractionContext context, String message) {
        if (commandBuffer == null || context == null || context.getEntity() == null) {
            return;
        }
        // The interacting entity carries a PlayerRef component (PlayerRef implements Component<EntityStore>);
        // PlayerRef.sendMessage is the chat path our PanelRefreshService/MachinePanelPage code already uses.
        PlayerRef playerRef = commandBuffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        playerRef.sendMessage(Message.raw(message));
    }
}
