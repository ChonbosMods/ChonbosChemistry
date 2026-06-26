package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The pure block-visual-state decision (vanilla-parity): the engine's {@code ProcessingBenchTick} drives
 * {@code "Processing"} while a bench actively processes and {@code "default"} otherwise. Our held-bench
 * machine is never seen by that tick, so we replicate the mapping. Crucially, when we cut power / disable
 * the machine we skip the bench advance, so the bench keeps its last {@code active} flag — the decision must
 * therefore force {@code "default"} on an unpowered tick, not trust {@code isActive()} alone.
 */
class MachineVisualStateTest {

    @Test
    void processingWhenPoweredThisTickAndBenchActive() {
        assertEquals("Processing", MachineVisualState.desired(true, true));
    }

    @Test
    void defaultWhenUnpoweredEvenIfBenchStillActive() {
        // Disabled or out of energy: we skipped advance, bench.isActive() is stale-true -> must revert.
        assertEquals("default", MachineVisualState.desired(false, true));
    }

    @Test
    void defaultWhenPoweredButBenchInactive() {
        // Powered but no recipe / no input / output full: bench never went active.
        assertEquals("default", MachineVisualState.desired(true, false));
    }

    @Test
    void defaultWhenIdle() {
        assertEquals("default", MachineVisualState.desired(false, false));
    }

    @Test
    void constantsAreTheVanillaStateNames() {
        assertEquals("Processing", MachineVisualState.PROCESSING);
        assertEquals("default", MachineVisualState.DEFAULT);
    }

    // --- isProcessing: the truthful "actively working this tick" signal -------------------------------
    // isActive() is unreliable for our held bench (advanceProcessing only ever clears it, and early-returns
    // without clearing when idle, so it sticks ON). Gate on real recipe progress made this tick instead.

    @Test
    void notProcessingWhenUnpowered() {
        // Even if progress looks advanced, an unpowered tick never processes.
        assertEquals(false, MachineVisualState.isProcessing(false, 1.0f, 2.0f, false));
    }

    @Test
    void processingWhenProgressAdvancedThisTick() {
        assertEquals(true, MachineVisualState.isProcessing(true, 1.0f, 1.5f, false));
    }

    @Test
    void notProcessingWhenPoweredButNoProgress() {
        // THE BUG: machine ON + powered but no input/recipe -> progress frozen -> must NOT animate.
        assertEquals(false, MachineVisualState.isProcessing(true, 0.0f, 0.0f, false));
    }

    @Test
    void processingOnCompletionTickEvenIfProgressReset() {
        // Completion resets inputProgress to 0 (after < before) but work DID happen this tick.
        assertEquals(true, MachineVisualState.isProcessing(true, 3.0f, 0.0f, true));
    }

    // --- autoCraftActive: the STICKY active visual for an autonomous auto-crafter ----------------------
    // The active state must hold ON across the brief inter-craft gap (the post-craft pacing delay) so the
    // block does not flicker active->idle->active between back-to-back recipes: assume it keeps going until
    // PROVEN idle (no job, no delay, nothing craftable this tick).

    @Test
    void activeWhenWorkedThisTick() {
        // A real craft committed/advanced/completed this tick.
        assertEquals(true, MachineVisualState.autoCraftActive(true, false, false));
    }

    @Test
    void activeWhileCraftInFlightEvenIfNoWorkThisTick() {
        // A craft is loaded but blocked (e.g. output full / stall): job pending -> stay active, don't flicker.
        assertEquals(true, MachineVisualState.autoCraftActive(false, true, false));
    }

    @Test
    void activeDuringPostCraftDelay() {
        // THE FLICKER FIX: the pacing gap between two crafts holds the active visual ON.
        assertEquals(true, MachineVisualState.autoCraftActive(false, false, true));
    }

    @Test
    void idleOnlyWhenProvenIdle() {
        // No work this tick, no craft in flight, no pending delay: genuinely idle -> off.
        assertEquals(false, MachineVisualState.autoCraftActive(false, false, false));
    }
}
