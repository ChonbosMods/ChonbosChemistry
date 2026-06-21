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
     * The status line shown under the progress bar.
     *
     * @param enabled  the machine's On/Off line ({@link com.chonbosmods.chemistry.impl.block.MachineBlockState#isEnabled()})
     * @param active   whether the held bench is currently processing
     * @param fraction smelt/process progress 0..1 (only used when {@code active})
     * @param verb     the machine's active verb (e.g. "Smelting", "Salvaging"); blank falls back to "Processing"
     * @return {@code "Off"} when disabled, else {@code "Idle"} when not active, else {@code "<Verb> NN%"}
     */
    static String status(boolean enabled, boolean active, float fraction, String verb) {
        if (!enabled) {
            return "Off";
        }
        if (!active) {
            return "Idle";
        }
        String v = (verb == null || verb.isEmpty()) ? "Processing" : verb;
        return v + " " + Math.round(clamp01(fraction) * 100.0f) + "%";
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
