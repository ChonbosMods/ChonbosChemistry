package com.chonbosmods.chemistry.impl.block.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BenchMachinePanelTextTest {

    @Test
    void progressOffWhenDisabled() {
        // Disabled wins regardless of progress: the processing section reads "Off".
        assertEquals("Off", BenchMachinePanelText.progress(false, 0.5f));
        assertEquals("Off", BenchMachinePanelText.progress(false, 0.0f));
    }

    @Test
    void progressProcessingWithRoundedPercent() {
        // While on, the section reads "Processing N%" (so "Processing 0%" when on but not yet advanced).
        assertEquals("Processing 0%", BenchMachinePanelText.progress(true, 0.0f));
        assertEquals("Processing 50%", BenchMachinePanelText.progress(true, 0.5f));
        assertEquals("Processing 100%", BenchMachinePanelText.progress(true, 1.0f));
        assertEquals("Processing 47%", BenchMachinePanelText.progress(true, 0.474f));
        assertEquals("Processing 48%", BenchMachinePanelText.progress(true, 0.475f));
    }

    @Test
    void stateIsOffIdleOrActive() {
        assertEquals("Off", BenchMachinePanelText.state(false, true));
        assertEquals("Off", BenchMachinePanelText.state(false, false));
        assertEquals("Idle", BenchMachinePanelText.state(true, false));
        assertEquals("Active", BenchMachinePanelText.state(true, true));
    }

    @Test
    void clamp01BoundsTheFraction() {
        assertEquals(0.0f, BenchMachinePanelText.clamp01(-0.3f));
        assertEquals(1.0f, BenchMachinePanelText.clamp01(2.0f));
        assertEquals(0.42f, BenchMachinePanelText.clamp01(0.42f));
    }

    @Test
    void energyLineFormatsStoredOverMax() {
        assertEquals("Energy: 250 / 1000", BenchMachinePanelText.energy(250L, 1000L));
        assertEquals("Energy: 0 / 0", BenchMachinePanelText.energy(0L, 0L));
    }

    @Test
    void forgeFractionDividesProgressByDuration() {
        // The Forge tracks raw craft seconds; the panel derives the bar fraction against FORGE_DURATION.
        assertEquals(0.0f, BenchMachinePanelText.forgeFraction(0.0f, 4.0f));
        assertEquals(0.5f, BenchMachinePanelText.forgeFraction(2.0f, 4.0f));
        assertEquals(1.0f, BenchMachinePanelText.forgeFraction(4.0f, 4.0f));
    }

    @Test
    void forgeFractionClampsAndGuardsDegenerateDuration() {
        // Over-full progress clamps to 1; a non-positive duration never divides by zero (yields 0).
        assertEquals(1.0f, BenchMachinePanelText.forgeFraction(9.0f, 4.0f));
        assertEquals(0.0f, BenchMachinePanelText.forgeFraction(2.0f, 0.0f));
        assertEquals(0.0f, BenchMachinePanelText.forgeFraction(2.0f, -1.0f));
    }
}
