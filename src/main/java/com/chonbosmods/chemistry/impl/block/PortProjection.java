package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3i;

/**
 * Projects a machine's model-orientation {@link PortConfig} onto a single WORLD footprint cell, for a
 * placed block at a given yaw {@link Rotation}. Each {@link Port}'s model cell offset and face are
 * rotated into world space (via the engine's {@link Rotation#rotateY}), then only the ports landing on
 * the requested world cell are kept. The result is what a pipe touching that cell actually sees: the
 * transport collector and the effective visual mask query it by world face, unaware of the footprint.
 *
 * <p>Pure (engine {@link Rotation} enum + JDK only): no {@code World}/{@code BlockModule}, unit-testable.
 * The {@code rotationIndex -> Rotation} resolution lives in {@code WorldMachineLookup} (the only
 * in-game-verified bit); this class takes the resolved {@link Rotation} directly.
 */
public final class PortProjection {

    /** Face-index unit offsets, OFFSETS order (0 +X, 1 -X, 2 +Y, 3 -Y, 4 +Z, 5 -Z). */
    private static final int[][] FACE_OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private PortProjection() {
    }

    /**
     * The ports of {@code model} (model orientation) that land on the world cell {@code (wcx,wcy,wcz)}
     * once the block's {@code rotation} is applied, with each port's face index rotated to world space.
     * A cell with no such ports yields an empty config.
     */
    public static PortConfig forWorldCell(PortConfig model, Rotation rotation, int wcx, int wcy, int wcz) {
        List<Port> matches = new ArrayList<>();
        for (Port p : model.ports()) {
            if (p == null) {
                continue;
            }
            Vector3i wc = rotate(rotation, p.cellX(), p.cellY(), p.cellZ());
            if (wc.x() != wcx || wc.y() != wcy || wc.z() != wcz) {
                continue;
            }
            int worldFace = rotateFace(rotation, p.faceIndex());
            matches.add(Port.of(wcx, wcy, wcz, worldFace, p.channel(), p.direction()));
        }
        return PortConfig.of(matches);
    }

    private static Vector3i rotate(Rotation rotation, int x, int y, int z) {
        return rotation.rotateY(new Vector3i(x, y, z), new Vector3i());
    }

    private static int rotateFace(Rotation rotation, int face) {
        int[] o = FACE_OFFSETS[face];
        Vector3i rv = rotate(rotation, o[0], o[1], o[2]);
        for (int i = 0; i < FACE_OFFSETS.length; i++) {
            if (FACE_OFFSETS[i][0] == rv.x() && FACE_OFFSETS[i][1] == rv.y() && FACE_OFFSETS[i][2] == rv.z()) {
                return i;
            }
        }
        // A yaw rotation maps every unit face to another unit face; unreachable for valid input.
        throw new IllegalStateException("face " + face + " rotated to non-unit " + rv);
    }
}
