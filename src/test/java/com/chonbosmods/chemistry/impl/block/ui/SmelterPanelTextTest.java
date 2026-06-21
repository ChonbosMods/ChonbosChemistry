package com.chonbosmods.chemistry.impl.block.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmelterPanelTextTest {

    @Test
    void statusOffWhenDisabled() {
        // Disabled wins regardless of activity/progress.
        assertEquals("Off", SmelterPanelText.status(false, true, 0.5f));
        assertEquals("Off", SmelterPanelText.status(false, false, 0.0f));
    }

    @Test
    void statusIdleWhenEnabledButNotActive() {
        assertEquals("Idle", SmelterPanelText.status(true, false, 0.0f));
    }

    @Test
    void statusSmeltingWithRoundedPercentWhenActive() {
        assertEquals("Smelting 0%", SmelterPanelText.status(true, true, 0.0f));
        assertEquals("Smelting 50%", SmelterPanelText.status(true, true, 0.5f));
        assertEquals("Smelting 100%", SmelterPanelText.status(true, true, 1.0f));
        // 0.474 -> 47%, half-up rounding at .5
        assertEquals("Smelting 47%", SmelterPanelText.status(true, true, 0.474f));
        assertEquals("Smelting 48%", SmelterPanelText.status(true, true, 0.475f));
    }

    @Test
    void clamp01BoundsTheFraction() {
        assertEquals(0.0f, SmelterPanelText.clamp01(-0.3f));
        assertEquals(1.0f, SmelterPanelText.clamp01(2.0f));
        assertEquals(0.42f, SmelterPanelText.clamp01(0.42f));
    }

    @Test
    void energyLineFormatsStoredOverMax() {
        assertEquals("Energy: 250 / 1000", SmelterPanelText.energy(250L, 1000L));
        assertEquals("Energy: 0 / 0", SmelterPanelText.energy(0L, 0L));
    }
}
