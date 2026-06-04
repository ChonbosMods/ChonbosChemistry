package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PipePowerStates}: the off&lt;-&gt;on state-name mapping used by the
 * tick system to flip cable textures. No Hytale APIs touched.
 */
class PipePowerStatesTest {

    // --- poweredOf: base/default block -> the powered node state "On" ---

    @Test
    void poweredOf_nullBecomesOn() {
        assertEquals("On", PipePowerStates.poweredOf(null));
    }

    @Test
    void poweredOf_emptyBecomesOn() {
        assertEquals("On", PipePowerStates.poweredOf(""));
    }

    @Test
    void poweredOf_defaultBecomesOn() {
        assertEquals("On", PipePowerStates.poweredOf("default"));
    }

    @Test
    void poweredOf_onIsIdempotent() {
        assertEquals("On", PipePowerStates.poweredOf("On"));
    }

    @Test
    void poweredOf_shapeGetsOnSuffix() {
        assertEquals("Elbow_On", PipePowerStates.poweredOf("Elbow"));
        assertEquals("Straight_On", PipePowerStates.poweredOf("Straight"));
        assertEquals("Vertical_3horiz_Ud_On", PipePowerStates.poweredOf("Vertical_3horiz_Ud"));
    }

    @Test
    void poweredOf_alreadyPoweredShapeIsIdempotent() {
        assertEquals("Elbow_On", PipePowerStates.poweredOf("Elbow_On"));
        assertEquals("Vertical_3horiz_Ud_On", PipePowerStates.poweredOf("Vertical_3horiz_Ud_On"));
    }

    // --- unpoweredOf: powered -> off twin ---

    @Test
    void unpoweredOf_onBecomesDefault() {
        assertEquals("default", PipePowerStates.unpoweredOf("On"));
    }

    @Test
    void unpoweredOf_poweredShapeDropsOnSuffix() {
        assertEquals("Elbow", PipePowerStates.unpoweredOf("Elbow_On"));
        assertEquals("Straight", PipePowerStates.unpoweredOf("Straight_On"));
        assertEquals("Vertical_3horiz_Ud", PipePowerStates.unpoweredOf("Vertical_3horiz_Ud_On"));
    }

    @Test
    void unpoweredOf_defaultIsIdempotent() {
        assertEquals("default", PipePowerStates.unpoweredOf("default"));
    }

    @Test
    void unpoweredOf_offShapeIsIdempotent() {
        assertEquals("Elbow", PipePowerStates.unpoweredOf("Elbow"));
        assertEquals("Vertical_3horiz_Ud", PipePowerStates.unpoweredOf("Vertical_3horiz_Ud"));
    }

    @Test
    void unpoweredOf_nullBecomesDefault() {
        assertEquals("default", PipePowerStates.unpoweredOf(null));
    }

    @Test
    void unpoweredOf_emptyBecomesDefault() {
        assertEquals("default", PipePowerStates.unpoweredOf(""));
    }

    // --- isPowered ---

    @Test
    void isPowered_trueForOnAndOnSuffixed() {
        assertTrue(PipePowerStates.isPowered("On"));
        assertTrue(PipePowerStates.isPowered("Elbow_On"));
        assertTrue(PipePowerStates.isPowered("Vertical_3horiz_Ud_On"));
    }

    @Test
    void isPowered_falseForOffStates() {
        assertFalse(PipePowerStates.isPowered("Elbow"));
        assertFalse(PipePowerStates.isPowered("Straight"));
        assertFalse(PipePowerStates.isPowered("default"));
    }

    @Test
    void isPowered_falseForNullAndEmpty() {
        assertFalse(PipePowerStates.isPowered(null));
        assertFalse(PipePowerStates.isPowered(""));
    }

    // --- round-trip / idempotency invariants ---

    @Test
    void roundTrip_offThenPoweredThenUnpoweredReturnsOff() {
        for (String off : new String[] {"Straight", "Elbow", "Six", "Vertical_End_Up"}) {
            String on = PipePowerStates.poweredOf(off);
            assertTrue(PipePowerStates.isPowered(on));
            assertEquals(off, PipePowerStates.unpoweredOf(on));
        }
    }

    @Test
    void roundTrip_baseBlockOnThenOffReturnsDefault() {
        String on = PipePowerStates.poweredOf("default");
        assertEquals("On", on);
        assertEquals("default", PipePowerStates.unpoweredOf(on));
    }
}
