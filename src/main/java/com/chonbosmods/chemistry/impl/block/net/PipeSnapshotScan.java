package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nonnull;

/**
 * Thin world glue for H8 wipe recovery: snapshots every pipe within the engine's connected-block
 * update reach of a topology change into the world's {@link PipeNodeSnapshots}, BEFORE the engine's
 * neighbor pass factory-resets re-resolved pipes (see {@link PipeNodeSnapshots} class doc).
 *
 * <p>The engine's {@code ConnectedBlocksUtil.updateNeighborsWithDepth} walks face-neighbors to depth
 * {@code < 3}, so every block it can touch lies within Manhattan distance {@link #RADIUS} of the
 * changed position. The changed position itself is skipped: a broken pipe's own share is
 * intentionally lost with the pipe (and a placed block has nothing to restore).
 *
 * <p>Defensive like the event systems that call it: never throws (a failed component read for one
 * position is skipped).
 */
public final class PipeSnapshotScan {

    /** Manhattan reach of the engine's depth-3 connected-block neighbor update. */
    static final int RADIUS = 3;

    /** Number of cube faces a pipe carries flow state for, indexed in {@code NetworkManager.OFFSETS} order. */
    private static final int FACE_COUNT = 6;

    private PipeSnapshotScan() {
    }

    /**
     * Snapshots every restore-worthy pipe within {@link #RADIUS} (Manhattan) of {@code (x,y,z)}: a pipe
     * carrying a positive share OR any non-NORMAL face (a wrench-only pipe) OR any in-transit stack (an
     * item pipe mid-flight). The face config AND the in-flight stacks are captured alongside the share so
     * the wipe cannot factory-reset a wrenched pipe (Task 5b) or VOID in-flight items (Task 11);
     * {@link PipeNodeSnapshots#put} applies the final worthiness test (and deep-copies the stacks).
     */
    public static void snapshotAround(
            @Nonnull World world,
            @Nonnull ComponentType<ChunkStore, PipeNode> pipeType,
            @Nonnull PipeNodeSnapshots snapshots,
            int x, int y, int z) {
        long tick = world.getTick();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            int budgetY = RADIUS - Math.abs(dx);
            for (int dy = -budgetY; dy <= budgetY; dy++) {
                int budgetZ = budgetY - Math.abs(dy);
                for (int dz = -budgetZ; dz <= budgetZ; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    int px = x + dx;
                    int py = y + dy;
                    int pz = z + dz;
                    try {
                        PipeNode pipe = BlockModule.getComponent(pipeType, world, px, py, pz);
                        if (pipe != null
                                && (pipe.bufferShare() > 0 || hasNonNormalFace(pipe) || !pipe.inTransit().isEmpty())) {
                            // inTransit() is a defensive copy; PipeNodeSnapshots.put deep-copies each stack
                            // again so the snapshot owns frozen instances independent of the live, ticking ones.
                            snapshots.put(
                                NetworkManager.packKey(px, py, pz), pipe.bufferShare(), pipe.resourceId(),
                                captureFaces(pipe), pipe.inTransit(), tick);
                        }
                    } catch (Throwable ignored) {
                        // One unreadable position must never break the event handler.
                    }
                }
            }
        }
    }

    /** True when any of the pipe's 6 faces is non-NORMAL (a wrenched pipe worth snapshotting). */
    private static boolean hasNonNormalFace(PipeNode pipe) {
        for (int face = 0; face < FACE_COUNT; face++) {
            if (pipe.flowState(face) != FlowState.NORMAL) {
                return true;
            }
        }
        return false;
    }

    /** Reads the pipe's 6 per-face flow states into a fresh array, in {@code OFFSETS} order. */
    private static FlowState[] captureFaces(PipeNode pipe) {
        FlowState[] faces = new FlowState[FACE_COUNT];
        for (int face = 0; face < FACE_COUNT; face++) {
            faces[face] = pipe.flowState(face);
        }
        return faces;
    }
}
