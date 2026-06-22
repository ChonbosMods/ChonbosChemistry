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
     * The line shown under the progress bar: the machine {@link #state}, with the progress percent
     * appended in the Active state (e.g. {@code "Active 47%"}).
     *
     * @param enabled  the machine's On/Off line ({@link com.chonbosmods.chemistry.impl.block.MachineBlockState#isEnabled()})
     * @param active   whether the held bench is currently processing
     * @param fraction process progress 0..1 (only used when {@code active})
     */
    static String progress(boolean enabled, boolean active, float fraction) {
        if (!enabled || !active) {
            return state(enabled, active);
        }
        return "Active " + Math.round(clamp01(fraction) * 100.0f) + "%";
    }

    /** Clamp a bar fraction into the {@code [0, 1]} range a {@code ProgressBar.Value} expects. */
    static float clamp01(float fraction) {
        return Math.max(0.0f, Math.min(1.0f, fraction));
    }

    /** The energy status line: {@code "Energy: stored / max"}. */
    static String energy(long stored, long max) {
        return "Energy: " + stored + " / " + max;
    }
}
