package com.chonbosmods.chemistry.impl.block;

/**
 * The pure block-visual-state decision for a held-bench machine (vanilla-parity).
 *
 * <p>Vanilla's {@code BenchSystems$ProcessingBenchTick} drives each placed bench's block interaction state
 * to {@code "Processing"} while it is actively processing and {@code "default"} otherwise (decompiled:
 * those are the literal state strings it passes to {@code ProcessingBenchBlock.setBlockInteractionState}).
 * Our {@code DrawType:Model} machine holds its own bench and is never seen by that tick (D31), so
 * {@code MachineTickSystem} replicates the mapping.
 *
 * <p>The subtlety this captures: when the machine is disabled or out of energy we skip the bench advance
 * entirely, so {@code ProcessingBenchBlock.isActive()} keeps its last (possibly true) value. The visual
 * must therefore revert to {@code "default"} on any tick we did not actually power work, not trust the
 * stale active flag — otherwise the animation/glow would freeze ON after power is cut.
 */
public final class MachineVisualState {

    /** Vanilla state name for the active/animated processing state (matches our {@code State.Definitions}). */
    public static final String PROCESSING = "Processing";

    /** Vanilla fallback state name: reverts the block to its root (off) texture, no animation. */
    public static final String DEFAULT = "default";

    private MachineVisualState() {
    }

    /**
     * @param poweredThisTick whether the machine actually advanced its bench this tick (enabled and the
     *     energy gate afforded {@code > 0} time): the run/halt + power reality for this tick
     * @param processing whether the bench actually worked a recipe this tick (see
     *     {@link #isProcessing(boolean, float, float, boolean)})
     * @return {@link #PROCESSING} only when we powered work this tick AND a recipe advanced;
     *     {@link #DEFAULT} otherwise (idle, disabled, or unpowered)
     */
    public static String desired(boolean poweredThisTick, boolean processing) {
        return (poweredThisTick && processing) ? PROCESSING : DEFAULT;
    }

    /**
     * The truthful "actively processing a recipe this tick" signal for a held bench, derived from real
     * progress rather than {@code ProcessingBenchBlock.isActive()}.
     *
     * <p>Why not {@code isActive()}: vanilla's tick is what SETS {@code active = true} when a bench starts a
     * recipe, but that tick never runs on our {@code DrawType:Model} held bench. {@code advanceProcessing}
     * (all we drive) only ever CLEARS {@code active}, and early-returns without clearing it when there is no
     * recipe — so once set it sticks ON and the machine would animate while merely powered-and-idle. Instead
     * we compare the bench's input progress across our own {@code advance()} call: forward motion (or a
     * completion this tick) means it really worked.
     *
     * @param poweredThisTick whether we advanced the bench this tick (false short-circuits to not processing)
     * @param progressBefore {@code getInputProgress()} sampled immediately before the advance
     * @param progressAfter {@code getInputProgress()} sampled immediately after the advance
     * @param completedThisTick whether the advance reported a completion (progress can reset to 0 on a
     *     completion, so this rescues the one tick where {@code progressAfter < progressBefore})
     * @return {@code true} iff powered and the recipe moved forward (or completed) this tick
     */
    public static boolean isProcessing(boolean poweredThisTick, float progressBefore, float progressAfter,
            boolean completedThisTick) {
        if (!poweredThisTick) {
            return false;
        }
        return progressAfter > progressBefore || completedThisTick;
    }
}
