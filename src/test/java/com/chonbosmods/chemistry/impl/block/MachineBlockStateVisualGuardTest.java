package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Per-block change-guard so the tick only re-issues a block-visual-state update when the desired state
 * actually changes (avoids restarting the looping animation / spamming a state packet every tick). The
 * guard is transient (not codec-persisted): after a chunk reload it self-corrects on the first transition.
 */
class MachineBlockStateVisualGuardTest {

    @Test
    void firstCallAlwaysUpdates() {
        MachineBlockState m = new MachineBlockState();
        assertTrue(m.shouldUpdateVisualState("default"));
    }

    @Test
    void sameStateDoesNotUpdateAgain() {
        MachineBlockState m = new MachineBlockState();
        m.shouldUpdateVisualState("Processing");
        assertFalse(m.shouldUpdateVisualState("Processing"));
    }

    @Test
    void changedStateUpdates() {
        MachineBlockState m = new MachineBlockState();
        m.shouldUpdateVisualState("Processing");
        assertTrue(m.shouldUpdateVisualState("default"));
    }
}
