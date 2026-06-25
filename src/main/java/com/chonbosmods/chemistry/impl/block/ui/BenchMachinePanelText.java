package com.chonbosmods.chemistry.impl.block.ui;

/**
 * Pure text/number formatting for {@link BenchMachinePanelPage} (the shared GUI for every CC machine that
 * wraps a vanilla processing bench). Kept separate from the page so the display logic is unit-testable
 * without the UI/ECS runtime. No state, no engine types.
 */
final class BenchMachinePanelText {

    private BenchMachinePanelText() {
    }

    /**
     * The canonical machine state word, uniform across all CC machines: {@code "Off"} (disabled),
     * {@code "Idle"} (on but doing nothing), or {@code "Active"} (on and processing).
     */
    static String state(boolean enabled, boolean active) {
        if (!enabled) {
            return "Off";
        }
        return active ? "Active" : "Idle";
    }

    /**
     * The line shown under the progress bar (the processing section): {@code "Off"} when disabled, else
     * {@code "Processing N%"} with the current progress percent (so {@code "Processing 0%"} when on but not
     * yet advanced). The 3-state machine word lives in {@link #state} (Off/Idle/Active) for the status line.
     */
    static String progress(boolean enabled, float fraction) {
        if (!enabled) {
            return "Off";
        }
        return "Processing " + Math.round(clamp01(fraction) * 100.0f) + "%";
    }

    /** Clamp a bar fraction into the {@code [0, 1]} range a {@code ProgressBar.Value} expects. */
    static float clamp01(float fraction) {
        return Math.max(0.0f, Math.min(1.0f, fraction));
    }

    /** The energy status line: {@code "Energy: stored / max"}. */
    static String energy(long stored, long max) {
        return "Energy: " + stored + " / " + max;
    }

    /**
     * Progress as a {@code [0, 1]} bar fraction for the autonomous Forge, which tracks raw accumulated
     * craft seconds rather than a 0..1 value: {@code clamp01(progress / duration)}. A non-positive
     * {@code duration} yields {@code 0} (degenerate config never divides by zero). Unlike the held-bench
     * machines (which expose a fraction directly), the Forge owns its progress, so the panel derives the
     * fraction here against {@code ForgeTickSystem.FORGE_DURATION} (the shared craft-length constant).
     */
    static float forgeFraction(float progressSeconds, float duration) {
        if (duration <= 0.0f) {
            return 0.0f;
        }
        return clamp01(progressSeconds / duration);
    }
}
