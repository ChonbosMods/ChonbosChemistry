package com.chonbosmods.chemistry.impl.block.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BenchMachinePanelTextTest {

    @Test
    void statusOffWhenDisabled() {
        assertEquals("Off", BenchMachinePanelText.status(false, true, 0.5f, "Smelting"));
        assertEquals("Off", BenchMachinePanelText.status(false, false, 0.0f, "Salvaging"));
    }

    @Test
    void statusIdleWhenEnabledButNotActive() {
        assertEquals("Idle", BenchMachinePanelText.status(true, false, 0.0f, "Smelting"));
    }

    @Test
    void statusUsesMachineVerbWithRoundedPercentWhenActive() {
        assertEquals("Smelting 0%", BenchMachinePanelText.status(true, true, 0.0f, "Smelting"));
        assertEquals("Salvaging 50%", BenchMachinePanelText.status(true, true, 0.5f, "Salvaging"));
        assertEquals("Processing 100%", BenchMachinePanelText.status(true, true, 1.0f, "Processing"));
        assertEquals("Smelting 47%", BenchMachinePanelText.status(true, true, 0.474f, "Smelting"));
        assertEquals("Smelting 48%", BenchMachinePanelText.status(true, true, 0.475f, "Smelting"));
    }

    @Test
    void statusFallsBackToProcessingWhenVerbBlank() {
        assertEquals("Processing 50%", BenchMachinePanelText.status(true, true, 0.5f, null));
        assertEquals("Processing 50%", BenchMachinePanelText.status(true, true, 0.5f, ""));
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
}
