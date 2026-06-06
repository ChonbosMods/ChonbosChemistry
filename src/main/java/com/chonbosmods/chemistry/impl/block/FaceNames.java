package com.chonbosmods.chemistry.impl.block;

/**
 * Human-readable names for the 6 cube faces in the canonical {@code NetworkManager.OFFSETS} order
 * {@code +X,-X,+Y,-Y,+Z,-Z} (indices 0..5). Used by the {@code CC_Wrench} interaction to label chat
 * feedback ("Pipe face East: PUSH"); Task 13's flow-state HUD reuses it.
 *
 * <h2>Index → name mapping (matches the engine's {@code BlockFace} axis convention)</h2>
 * The names mirror Hytale's {@code BlockFace} → world-axis mapping, confirmed against HyProTech's
 * {@code EnergySide} table (each side carries an explicit {@code (dx,dy,dz)}):
 * {@code East=(+1,0,0)}, {@code West=(-1,0,0)}, {@code Up=(0,+1,0)}, {@code Down=(0,-1,0)},
 * {@code South=(0,0,+1)}, {@code North=(0,0,-1)}. Lined up against our OFFSETS order this gives:
 *
 * <pre>
 *   index 0  +X  East
 *   index 1  -X  West
 *   index 2  +Y  Up
 *   index 3  -Y  Down
 *   index 4  +Z  South
 *   index 5  -Z  North
 * </pre>
 */
public final class FaceNames {

    /** Face names indexed in OFFSETS order +X,-X,+Y,-Y,+Z,-Z. */
    private static final String[] NAMES = {"East", "West", "Up", "Down", "South", "North"};

    private FaceNames() {
    }

    /**
     * The name of the given OFFSETS-order face index (0..5), or {@code "?"} for an out-of-range index.
     * Never throws.
     */
    public static String name(int faceIndex) {
        if (faceIndex < 0 || faceIndex >= NAMES.length) {
            return "?";
        }
        return NAMES[faceIndex];
    }
}
