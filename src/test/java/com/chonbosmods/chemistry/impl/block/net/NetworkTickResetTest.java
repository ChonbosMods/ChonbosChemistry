package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link NetworkTickSystem#shouldResetOverride}: the throttle decision that gates the
 * plain-shape write when a previously-overridden pipe becomes undisturbed again. No Hytale APIs touched.
 *
 * <p>Contract: a write (return {@code true}) is needed iff the placed state name differs from the desired
 * plain key OR the placed rotation differs from the wanted rotation. When both already match, the pipe is
 * already at the plain shape and the override simply drops from the set with no block mutation
 * (return {@code false}), mirroring the apply path's read-before-write throttle.
 */
class NetworkTickResetTest {

    @Test
    void noWrite_whenNameAndRotationAlreadyMatch() {
        assertFalse(NetworkTickSystem.shouldResetOverride("Elbow", "Elbow", 2, 2));
    }

    @Test
    void write_whenStateNameDiffers() {
        // Still showing the overridden composite (e.g. a tee+tip) but the plain shape is an elbow.
        assertTrue(NetworkTickSystem.shouldResetOverride("Elbow", "Tee_T0p", 0, 0));
    }

    @Test
    void write_whenRotationDiffers() {
        // Same shape name but the override left a stale rotation: the plain shape wants a different yaw.
        assertTrue(NetworkTickSystem.shouldResetOverride("Elbow", "Elbow", 3, 1));
    }

    @Test
    void write_whenBothDiffer() {
        assertTrue(NetworkTickSystem.shouldResetOverride("Straight", "Cross", 1, 0));
    }

    @Test
    void noWrite_forBaseDefaultNodeAtMatchingRotation() {
        // The empty-mask node resets to the base "default" block; if already default at rotation 0, no write.
        assertFalse(NetworkTickSystem.shouldResetOverride("default", "default", 0, 0));
    }

    @Test
    void write_forEnergizedTwin_whenCurrentIsUnpowered() {
        // Energized reset targets the _On twin; an unpowered placed name must still trigger the write so the
        // powered cable lands on its energized plain shape (no flicker).
        assertTrue(NetworkTickSystem.shouldResetOverride("Elbow_On", "Elbow", 0, 0));
    }
}
