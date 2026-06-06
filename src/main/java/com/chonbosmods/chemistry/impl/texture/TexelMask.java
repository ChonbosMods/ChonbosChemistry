package com.chonbosmods.chemistry.impl.texture;

/**
 * A predicate over texture pixel (texel) coordinates: returns whether a substance color is allowed
 * to tint the pixel at {@code (x, y)} (top-left origin). Implementations describe an arbitrary
 * tintable region: a single rectangle ({@link LiquidMask}) or a union-minus-exclusion of rectangles
 * ({@link FluidPipeCoreMask}).
 *
 * <p>The tinters ({@link SubstanceLiquidTinter}, {@link GlowBoost}) only ever ask "may I touch this
 * pixel?", so they depend on this narrow interface rather than any concrete mask shape.
 */
@FunctionalInterface
public interface TexelMask {

    /** @return {@code true} if the pixel at {@code (x, y)} is inside the tintable region. */
    boolean contains(int x, int y);
}
