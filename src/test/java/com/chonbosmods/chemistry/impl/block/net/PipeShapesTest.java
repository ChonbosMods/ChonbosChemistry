package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.chonbosmods.chemistry.api.io.FlowState;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link PipeShapes}: the effective-topology shape table that maps a
 * 6-bit connected-faces bitmask (OFFSETS order: +X=0,-X=1,+Y=2,-Y=3,+Z=4,-Z=5) to a pipe
 * interaction-state key (the PascalCase {@code State.Definitions} names) plus a Y-rotation index.
 *
 * <p>No Hytale APIs touched. The expected face-sets are derived from the power
 * {@code CustomConnectedBlockTemplate}'s {@code PatternsToMatchAnyOf} rules (see the class javadoc
 * and the offline derivation documented there).
 */
class PipeShapesTest {

    // Convenience bit constants mirroring OFFSETS face indices.
    private static final int PX = 1 << 0; // +X
    private static final int NX = 1 << 1; // -X
    private static final int PY = 1 << 2; // +Y
    private static final int NY = 1 << 3; // -Y
    private static final int PZ = 1 << 4; // +Z
    private static final int NZ = 1 << 5; // -Z

    // --- known cases: state key (unenergized) ---

    @Test
    void node_noFaces_isDefaultBaseBlock() {
        // mask 0 = the bare node; unenergized that is the base/default block.
        assertEquals("default", PipeShapes.stateFor(0b000000, false));
    }

    @Test
    void node_noFaces_energized_isOn() {
        assertEquals("On", PipeShapes.stateFor(0b000000, true));
    }

    @Test
    void straight_northSouth_isStraight() {
        // +Z|-Z is the template's base "straight" orientation (rotation 0).
        PipeShapes.Shape s = PipeShapes.resolve(PZ | NZ);
        assertEquals("Straight", s.stateKey());
        assertEquals(0, s.rotationIndex());
    }

    @Test
    void straight_eastWest_isStraightRotated() {
        // +X|-X is the same "straight" geometry, Y-rotated 90 degrees (rotation 1).
        PipeShapes.Shape s = PipeShapes.resolve(PX | NX);
        assertEquals("Straight", s.stateKey());
        assertEquals(1, s.rotationIndex());
    }

    @Test
    void singlePlusZ_isEnd() {
        // A single horizontal connection is the "end" shape (per the template's coverage).
        PipeShapes.Shape s = PipeShapes.resolve(PZ);
        assertEquals("End", s.stateKey());
        assertEquals(0, s.rotationIndex());
    }

    @Test
    void singlePlusX_isEndRotated() {
        PipeShapes.Shape s = PipeShapes.resolve(PX);
        assertEquals("End", s.stateKey());
        assertEquals(1, s.rotationIndex());
    }

    @Test
    void singlePlusY_isVerticalEndUp() {
        PipeShapes.Shape s = PipeShapes.resolve(PY);
        assertEquals("Vertical_End_Up", s.stateKey());
        assertEquals(0, s.rotationIndex());
    }

    @Test
    void singleMinusY_isVerticalEndDown() {
        PipeShapes.Shape s = PipeShapes.resolve(NY);
        assertEquals("Vertical_End_Down", s.stateKey());
        assertEquals(0, s.rotationIndex());
    }

    @Test
    void allSixFaces_isSix() {
        PipeShapes.Shape s = PipeShapes.resolve(0b111111);
        assertEquals("Six", s.stateKey());
        assertEquals(0, s.rotationIndex());
    }

    @Test
    void straightVertical_upDown_isStraightVertical() {
        PipeShapes.Shape s = PipeShapes.resolve(PY | NY);
        assertEquals("Straight_Vertical", s.stateKey());
        assertEquals(0, s.rotationIndex());
    }

    @Test
    void elbow_plusXplusZ_isElbow() {
        PipeShapes.Shape s = PipeShapes.resolve(PX | PZ);
        assertEquals("Elbow", s.stateKey());
        assertEquals(0, s.rotationIndex());
    }

    @Test
    void cross_fourHorizontal_isCross() {
        PipeShapes.Shape s = PipeShapes.resolve(PX | NX | PZ | NZ);
        assertEquals("Cross", s.stateKey());
        assertEquals(0, s.rotationIndex());
    }

    // --- EXHAUSTIVE: every 6-bit mask resolves to a non-null state, no exceptions ---

    @Test
    void everyMaskResolvesToNonNullShape() {
        for (int mask = 0; mask < 64; mask++) {
            PipeShapes.Shape s = PipeShapes.resolve(mask);
            assertNotNull(s, "mask " + mask + " resolved to null");
            assertNotNull(s.stateKey(), "mask " + mask + " has null stateKey");
            assertFalse(s.stateKey().isEmpty(), "mask " + mask + " has empty stateKey");
            assertTrue(s.rotationIndex() >= 0 && s.rotationIndex() <= 3,
                    "mask " + mask + " rotation out of [0,3]: " + s.rotationIndex());
        }
    }

    @Test
    void everyMaskUnenergizedStateNonNull() {
        for (int mask = 0; mask < 64; mask++) {
            String off = PipeShapes.stateFor(mask, false);
            assertNotNull(off, "mask " + mask + " unenergized state null");
            assertFalse(off.isEmpty(), "mask " + mask + " unenergized state empty");
        }
    }

    // --- EXHAUSTIVE: energized == _On twin of unenergized across all 64 masks ---

    @Test
    void energizedIsOnTwinOfUnenergizedForEveryMask() {
        for (int mask = 0; mask < 64; mask++) {
            String off = PipeShapes.stateFor(mask, false);
            String on = PipeShapes.stateFor(mask, true);
            assertEquals(PipePowerStates.poweredOf(off), on,
                    "mask " + mask + ": energized state is not the powered twin of the unenergized state");
            // And the reverse: powering the ON state down returns the OFF state.
            assertEquals(off, PipePowerStates.unpoweredOf(on),
                    "mask " + mask + ": unpoweredOf(on) does not return the off state");
        }
    }

    @Test
    void energizedTwinSpotChecks() {
        // node -> On
        assertEquals("On", PipeShapes.stateFor(0, true));
        // straight -> Straight_On
        assertEquals("Straight_On", PipeShapes.stateFor(PZ | NZ, true));
        // six -> Six_On
        assertEquals("Six_On", PipeShapes.stateFor(0b111111, true));
        // vertical end up -> Vertical_End_Up_On
        assertEquals("Vertical_End_Up_On", PipeShapes.stateFor(PY, true));
    }

    // --- the resolve(...) record round-trips into stateFor(...) ---

    @Test
    void resolveStateKeyMatchesUnenergizedStateForNonNode() {
        for (int mask = 1; mask < 64; mask++) {
            PipeShapes.Shape s = PipeShapes.resolve(mask);
            assertEquals(s.stateKey(), PipeShapes.stateFor(mask, false),
                    "mask " + mask + ": resolve().stateKey() != stateFor(off)");
        }
    }

    @Test
    void nodeResolvesToBareOnShapeKey() {
        // The node record carries the bare powered-node key "On" as its stateKey so callers that
        // want a non-empty key get one; stateFor(false) still maps it to the base "default" block.
        PipeShapes.Shape s = PipeShapes.resolve(0);
        assertEquals("On", s.stateKey());
        assertEquals(0, s.rotationIndex());
        assertEquals("default", PipeShapes.stateFor(0, false));
    }

    // --- mask bounds are validated ---

    @Test
    void maskOutOfRangeThrows() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PipeShapes.resolve(-1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PipeShapes.resolve(64));
    }

    // =====================================================================================
    // indicatorStateFor: end-stub push/pull direction arrows (Task 12)
    // =====================================================================================

    // --- the 6 single-arm directions x PUSH/PULL, unenergized ---

    @Test
    void indicator_plusZ_push() {
        // +Z single arm -> End shape (rotation 0); PUSH face -> End_Push.
        assertEquals("End_Push", PipeShapes.indicatorStateFor(PZ, FlowState.PUSH, false));
    }

    @Test
    void indicator_plusZ_pull() {
        assertEquals("End_Pull", PipeShapes.indicatorStateFor(PZ, FlowState.PULL, false));
    }

    @Test
    void indicator_plusX_push() {
        // +X is still the End shape (just a different rotation): the key is direction-only, not rotated.
        assertEquals("End_Push", PipeShapes.indicatorStateFor(PX, FlowState.PUSH, false));
    }

    @Test
    void indicator_minusX_pull() {
        assertEquals("End_Pull", PipeShapes.indicatorStateFor(NX, FlowState.PULL, false));
    }

    @Test
    void indicator_minusZ_push() {
        assertEquals("End_Push", PipeShapes.indicatorStateFor(NZ, FlowState.PUSH, false));
    }

    @Test
    void indicator_plusY_push() {
        assertEquals("Vertical_End_Up_Push", PipeShapes.indicatorStateFor(PY, FlowState.PUSH, false));
    }

    @Test
    void indicator_plusY_pull() {
        assertEquals("Vertical_End_Up_Pull", PipeShapes.indicatorStateFor(PY, FlowState.PULL, false));
    }

    @Test
    void indicator_minusY_push() {
        assertEquals("Vertical_End_Down_Push", PipeShapes.indicatorStateFor(NY, FlowState.PUSH, false));
    }

    @Test
    void indicator_minusY_pull() {
        assertEquals("Vertical_End_Down_Pull", PipeShapes.indicatorStateFor(NY, FlowState.PULL, false));
    }

    // --- energized appends the _On twin (mirrors PipePowerStates.poweredOf) ---

    @Test
    void indicator_energizedIsOnTwin() {
        assertEquals("End_Push_On", PipeShapes.indicatorStateFor(PZ, FlowState.PUSH, true));
        assertEquals("End_Pull_On", PipeShapes.indicatorStateFor(PX, FlowState.PULL, true));
        assertEquals("Vertical_End_Up_Push_On",
                PipeShapes.indicatorStateFor(PY, FlowState.PUSH, true));
        assertEquals("Vertical_End_Down_Pull_On",
                PipeShapes.indicatorStateFor(NY, FlowState.PULL, true));
    }

    @Test
    void indicator_energizedIsExactlyPoweredOfUnenergized() {
        int[] singleArms = {PX, NX, PY, NY, PZ, NZ};
        for (int arm : singleArms) {
            for (FlowState fs : new FlowState[] {FlowState.PUSH, FlowState.PULL}) {
                String off = PipeShapes.indicatorStateFor(arm, fs, false);
                String on = PipeShapes.indicatorStateFor(arm, fs, true);
                assertEquals(PipePowerStates.poweredOf(off), on,
                        "arm " + arm + " " + fs + ": energized indicator is not the powered twin");
                assertEquals(off, PipePowerStates.unpoweredOf(on),
                        "arm " + arm + " " + fs + ": unpoweredOf(on) does not return the off indicator");
            }
        }
    }

    // --- null cases: NORMAL / NONE / null face on a valid single arm ---

    @Test
    void indicator_normalFace_isNull() {
        assertNull(PipeShapes.indicatorStateFor(PZ, FlowState.NORMAL, false));
        assertNull(PipeShapes.indicatorStateFor(PY, FlowState.NORMAL, true));
    }

    @Test
    void indicator_noneFace_isNull() {
        assertNull(PipeShapes.indicatorStateFor(PZ, FlowState.NONE, false));
        assertNull(PipeShapes.indicatorStateFor(NY, FlowState.NONE, true));
    }

    @Test
    void indicator_nullFace_isNull() {
        assertNull(PipeShapes.indicatorStateFor(PX, null, false));
    }

    // --- null cases: zero mask and multi-arm masks (not an end-stub) ---

    @Test
    void indicator_zeroMask_isNull() {
        assertNull(PipeShapes.indicatorStateFor(0, FlowState.PUSH, false));
        assertNull(PipeShapes.indicatorStateFor(0, FlowState.PULL, true));
    }

    @Test
    void indicator_multiArmMasks_isNull() {
        // Straight (two arms), elbow (two), tee (three), cross (four), six (all): none are end stubs.
        assertNull(PipeShapes.indicatorStateFor(PZ | NZ, FlowState.PUSH, false));
        assertNull(PipeShapes.indicatorStateFor(PX | PZ, FlowState.PULL, false));
        assertNull(PipeShapes.indicatorStateFor(PX | NX | PZ, FlowState.PUSH, true));
        assertNull(PipeShapes.indicatorStateFor(PX | NX | PZ | NZ, FlowState.PULL, false));
        assertNull(PipeShapes.indicatorStateFor(0b111111, FlowState.PUSH, true));
    }

    @Test
    void indicator_outOfRangeMask_isNull() {
        // Defensive: a bit above the low 6 (or negative) is not a valid single-arm end and never throws.
        assertNull(PipeShapes.indicatorStateFor(64, FlowState.PUSH, false));
        assertNull(PipeShapes.indicatorStateFor(-1, FlowState.PULL, false));
    }

    // --- the indicator's base end shape matches stateFor's plain end (direction aside) ---

    @Test
    void indicator_baseMatchesPlainEndShape() {
        int[] singleArms = {PX, NX, PY, NY, PZ, NZ};
        for (int arm : singleArms) {
            String plain = PipeShapes.stateFor(arm, false); // e.g. "End", "Vertical_End_Up"
            String push = PipeShapes.indicatorStateFor(arm, FlowState.PUSH, false);
            String pull = PipeShapes.indicatorStateFor(arm, FlowState.PULL, false);
            assertEquals(plain + "_Push", push, "arm " + arm + ": push indicator base mismatch");
            assertEquals(plain + "_Pull", pull, "arm " + arm + ": pull indicator base mismatch");
        }
    }

    // --- every state key indicatorStateFor can emit must exist in the cable JSON Definitions ---
    // (path validity is covered by the build resources; this asserts the key set is exactly the 12)

    @Test
    void indicator_emitsOnlyTheTwelveKnownKeys() {
        java.util.Set<String> emitted = new java.util.TreeSet<>();
        int[] singleArms = {PX, NX, PY, NY, PZ, NZ};
        for (int arm : singleArms) {
            for (FlowState fs : new FlowState[] {FlowState.PUSH, FlowState.PULL}) {
                emitted.add(PipeShapes.indicatorStateFor(arm, fs, false));
                emitted.add(PipeShapes.indicatorStateFor(arm, fs, true));
            }
        }
        java.util.Set<String> expected = new java.util.TreeSet<>(java.util.Arrays.asList(
                "End_Push", "End_Pull",
                "Vertical_End_Up_Push", "Vertical_End_Up_Pull",
                "Vertical_End_Down_Push", "Vertical_End_Down_Pull",
                "End_Push_On", "End_Pull_On",
                "Vertical_End_Up_Push_On", "Vertical_End_Up_Pull_On",
                "Vertical_End_Down_Push_On", "Vertical_End_Down_Pull_On"));
        assertEquals(expected, emitted);
    }
}
