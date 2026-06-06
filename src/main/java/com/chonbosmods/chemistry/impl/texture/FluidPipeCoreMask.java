package com.chonbosmods.chemistry.impl.texture;

/**
 * Tintable region of the FluidPipe {@code fluidpipe_on.png} 64x64 atlas: the white luminance-ramp
 * "core" (interior fluid) islands, where a substance color is multiplied in, MINUS the pixels the
 * straight pipe's steel shell shares with a core rect (which must stay neutral steel).
 *
 * <p>The {@link #CORE_RECTS} were extracted from the {@code Core*}/{@code CoreStub*} nodes of all 27
 * {@code FluidPipe_*.blockymodel} demo files: each face's uv extent is the authored cube size
 * projected per face (a 90/270 rotation swaps width/height). The {@link #SHELL_EXCLUSIONS} are the
 * straight model's shell top {@code (40,16,14,14)} and bottom {@code (40,17,14,14)} faces, which
 * overlap 99 core pixels in the {@code x40..53, y16..25} zone; tinting them would discolor the
 * straight pipe's exterior. If the user re-authors the pipe demos, re-extract both rect sets.
 *
 * <p>{@link #contains} is true iff the pixel lies in any core rect AND in none of the shell
 * exclusions. (Multiply-tint preserves alpha, so transparent core pixels are unaffected regardless.)
 */
public final class FluidPipeCoreMask implements TexelMask {

    /** Shared singleton: the mask is immutable and stateless. */
    public static final FluidPipeCoreMask INSTANCE = new FluidPipeCoreMask();

    /** Union of all core face UV rects across the 27 FluidPipe_*.blockymodel files: {x, y, w, h}. */
    private static final int[][] CORE_RECTS = {
        {32, 0, 10, 10},
        {34, 0, 12, 9},
        {34, 2, 12, 9},
        {34, 4, 12, 9},
        {34, 5, 12, 9},
        {34, 13, 12, 9},
        {34, 16, 12, 9},
        {35, 0, 10, 10},
        {35, 2, 10, 10},
        {35, 5, 10, 10},
        {35, 10, 10, 10},
        {35, 13, 10, 10},
        {35, 16, 10, 10},
        {46, 2, 12, 9},
        {46, 5, 12, 9},
        {48, 0, 10, 10},
        {48, 0, 12, 12},
        {50, 2, 12, 12},
        {50, 14, 12, 12},
        {51, 3, 10, 10},
    };

    /** Straight model shell top/bottom faces (x40..53) that overlap the core; never tint these. */
    private static final int[][] SHELL_EXCLUSIONS = {
        {40, 16, 14, 14},
        {40, 17, 14, 14},
    };

    private FluidPipeCoreMask() {}

    @Override
    public boolean contains(int x, int y) {
        boolean inCore = false;
        for (int[] r : CORE_RECTS) {
            if (inRect(r, x, y)) {
                inCore = true;
                break;
            }
        }
        if (!inCore) {
            return false;
        }
        for (int[] r : SHELL_EXCLUSIONS) {
            if (inRect(r, x, y)) {
                return false;
            }
        }
        return true;
    }

    private static boolean inRect(int[] r, int x, int y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }
}
