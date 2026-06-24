package com.chonbosmods.chemistry.impl.assetgen;

/**
 * The editable-in-place physics defaults for a generated fluid (design F5). These numbers feed the
 * world block's {@code Ticker.FlowRate} and the FluidFX {@code MovementSettings}; they are derived
 * from vanilla Water/Lava and meant to be tuned per substance later by hand-editing the generated
 * JSON. Kept minimal: only the fields the block/FluidFX templates actually emit.
 *
 * @param flowRate                  spread speed for {@code Ticker.FlowRate} (vanilla Water defaults to 1.0)
 * @param sinkSpeed                 vertical sink velocity for {@code MovementSettings.SinkSpeed} (negative = down)
 * @param horizontalSpeedMultiplier swim-drag for {@code MovementSettings.HorizontalSpeedMultiplier}
 */
public record FluidPhysics(double flowRate, double sinkSpeed, double horizontalSpeedMultiplier) {

    /** Water-like defaults, copied from vanilla {@code FluidFX/Water.json} + {@code Water_Source}. */
    public static FluidPhysics waterLike() {
        return new FluidPhysics(1.0, -1.35, 0.6);
    }

    /**
     * Default physics for a fluid. Normal fluids are {@link #waterLike()}; liquefied (cryo) fluids
     * sink slower and drag harder horizontally (cold viscosity), so they pool and wade differently.
     */
    public static FluidPhysics defaultFor(boolean liquefied) {
        if (!liquefied) {
            return waterLike();
        }
        return new FluidPhysics(0.7, -0.8, 0.4);
    }
}
