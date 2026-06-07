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
    // tippedStateFor: per-face push/pull tip state selection, any shape (Task 4)
    // =====================================================================================
    //
    // Key contract (must match scripts/gen_pipe_tips.py state_key()):
    //   <ShapePascal>_T<modelFace><p|l>[_T<modelFace><p|l>]  (faces ASCENDING, p/l LOWERCASE)
    // Inputs are WORLD-space; tippedStateFor un-rotates tipped faces to model space via the
    // block's rotationIndex before building the key (the generated models are rotation-0).

    /** Build a 6-entry faceStates array (OFFSETS order) that is all-NORMAL except the given faces. */
    private static FlowState[] faces(java.util.Map<Integer, FlowState> overrides) {
        FlowState[] fs = new FlowState[6];
        java.util.Arrays.fill(fs, FlowState.NORMAL);
        overrides.forEach((f, s) -> fs[f] = s);
        return fs;
    }

    private static FlowState[] allNormal() {
        return faces(java.util.Map.of());
    }

    // --- zero tipped faces -> the plain stateFor result ---

    @Test
    void tipped_noTippedFaces_isPlainStateFor() {
        // Tee (3 arms) all-NORMAL: no tip -> plain "Tee".
        int mask = PX | NX | PZ; // tee r3 in the table
        assertEquals(PipeShapes.stateFor(mask, false),
                PipeShapes.tippedStateFor(mask, allNormal(), mask, false, 0));
    }

    @Test
    void tipped_pushFaceNotAnEndpointArm_isPlainStateFor() {
        // A PUSH on +X but endpointArmMask excludes +X (it points at a pipe): no tip.
        int mask = PX | NX | PZ;
        FlowState[] fs = faces(java.util.Map.of(0, FlowState.PUSH));
        assertEquals(PipeShapes.stateFor(mask, false),
                PipeShapes.tippedStateFor(mask, fs, NX | PZ, false, 0)); // +X not in endpoint mask
    }

    @Test
    void tipped_pushFaceNotAnEffectiveArm_isIgnored() {
        // PUSH on +Y but +Y is not in the effective mask: not an arm, ignored.
        int mask = PX | NX | PZ; // no +Y
        FlowState[] fs = faces(java.util.Map.of(2, FlowState.PUSH));
        // endpointArmMask claims +Y too, but effective mask gates it out.
        assertEquals(PipeShapes.stateFor(mask, false),
                PipeShapes.tippedStateFor(mask, fs, mask | PY, false, 0));
    }

    // --- one tipped face (rotation 0) ---

    @Test
    void tipped_singleEnd_plusZ_push_isEnd_T4p() {
        // +Z single arm = End shape; PUSH endpoint arm at world face 4 -> model face 4 (rot 0).
        int mask = PZ;
        FlowState[] fs = faces(java.util.Map.of(4, FlowState.PUSH));
        assertEquals("End_T4p", PipeShapes.tippedStateFor(mask, fs, PZ, false, 0));
    }

    @Test
    void tipped_singleEnd_plusZ_pull_isEnd_T4l() {
        int mask = PZ;
        FlowState[] fs = faces(java.util.Map.of(4, FlowState.PULL));
        assertEquals("End_T4l", PipeShapes.tippedStateFor(mask, fs, PZ, false, 0));
    }

    @Test
    void tipped_singleEnd_verticalUp_push_isVerticalEndUp_T2p() {
        // +Y single arm = Vertical_End_Up; PUSH at world face 2 -> model face 2 (yaw-invariant).
        int mask = PY;
        FlowState[] fs = faces(java.util.Map.of(2, FlowState.PUSH));
        assertEquals("Vertical_End_Up_T2p", PipeShapes.tippedStateFor(mask, fs, PY, false, 0));
    }

    @Test
    void tipped_singleEnd_verticalDown_pull_isVerticalEndDown_T3l() {
        int mask = NY;
        FlowState[] fs = faces(java.util.Map.of(3, FlowState.PULL));
        assertEquals("Vertical_End_Down_T3l", PipeShapes.tippedStateFor(mask, fs, NY, false, 0));
    }

    // --- two tipped faces (the design's flagship sample) ---

    @Test
    void tipped_tee_T0p_T4l() {
        // Tee covering +X|-X|+Z (mask 0b010011 -> "Tee" r3 in the table), PUSH on +X (face 0),
        // PULL on +Z (face 4). Faces ascending in the key: T0p then T4l.
        int mask = PX | NX | PZ;
        FlowState[] fs = faces(java.util.Map.of(0, FlowState.PUSH, 4, FlowState.PULL));
        assertEquals("Tee_T0p_T4l", PipeShapes.tippedStateFor(mask, fs, mask, false, 0));
    }

    @Test
    void tipped_twoFaces_sortedAscendingRegardlessOfDeclarationOrder() {
        // PULL on +Z (4) and PUSH on -X (1): ascending order is T1l then T4p.
        int mask = PX | NX | PZ;
        FlowState[] fs = faces(java.util.Map.of(4, FlowState.PUSH, 1, FlowState.PULL));
        assertEquals("Tee_T1l_T4p", PipeShapes.tippedStateFor(mask, fs, mask, false, 0));
    }

    @Test
    void tipped_cross_twoVerticalInvariantFaces() {
        // Cross is horizontal only; use a Six shape so +Y/-Y are arms. Tip +Y push, -Y pull.
        int mask = 0b111111; // Six
        FlowState[] fs = faces(java.util.Map.of(2, FlowState.PUSH, 3, FlowState.PULL));
        assertEquals("Six_T2p_T3l", PipeShapes.tippedStateFor(mask, fs, mask, false, 0));
    }

    // --- legacy 3+ tipped faces: keep the 2 LOWEST (defensive; wrench can't produce this) ---

    @Test
    void tipped_threeFaces_keepsTwoLowest() {
        // Six shape, tips on +X(0) push, -X(1) pull, +Y(2) push. Lowest two faces: 0 and 1.
        int mask = 0b111111;
        FlowState[] fs = faces(java.util.Map.of(0, FlowState.PUSH, 1, FlowState.PULL, 2, FlowState.PUSH));
        assertEquals("Six_T0p_T1l", PipeShapes.tippedStateFor(mask, fs, mask, false, 0));
    }

    // --- energized appends _On (mirrors PipePowerStates.poweredOf) ---

    @Test
    void tipped_energizedAppendsOn() {
        int mask = PX | NX | PZ;
        FlowState[] fs = faces(java.util.Map.of(0, FlowState.PUSH, 4, FlowState.PULL));
        assertEquals("Tee_T0p_T4l_On", PipeShapes.tippedStateFor(mask, fs, mask, true, 0));
    }

    @Test
    void tipped_energizedIsExactlyPoweredOfUnenergized() {
        int mask = PZ;
        FlowState[] fs = faces(java.util.Map.of(4, FlowState.PUSH));
        String off = PipeShapes.tippedStateFor(mask, fs, PZ, false, 0);
        String on = PipeShapes.tippedStateFor(mask, fs, PZ, true, 0);
        assertEquals(PipePowerStates.poweredOf(off), on);
        assertEquals(off, PipePowerStates.unpoweredOf(on));
    }

    @Test
    void tipped_noTip_energized_isPlainEnergizedStateFor() {
        int mask = PX | NX | PZ;
        assertEquals(PipeShapes.stateFor(mask, true),
                PipeShapes.tippedStateFor(mask, allNormal(), mask, true, 0));
    }

    // --- NORMAL / NONE faces never produce a tip ---

    @Test
    void tipped_normalAndNoneFaces_noTip() {
        int mask = PX | NX | PZ;
        FlowState[] fs = faces(java.util.Map.of(0, FlowState.NORMAL, 4, FlowState.NONE));
        assertEquals(PipeShapes.stateFor(mask, false),
                PipeShapes.tippedStateFor(mask, fs, mask, false, 0));
    }

    // --- rotation mapping: world tipped face un-rotates to model face in the key ---

    @Test
    void tipped_rotationMapsWorldFaceToModelFace() {
        // A +X(0) end at the table's rotation. End for +X resolves to rotationIndex 1.
        // World face 0 under rot1 maps to model face 4 (the rot-0 base End's arm is +Z=4).
        // So a PUSH on world +X with rotationIndex 1 must key as End_T4p (model space).
        int mask = PX;
        FlowState[] fs = faces(java.util.Map.of(0, FlowState.PUSH));
        int rot = PipeShapes.resolve(mask).rotationIndex(); // == 1
        assertEquals(1, rot);
        assertEquals("End_T4p", PipeShapes.tippedStateFor(mask, fs, PX, false, rot));
    }

    @Test
    void tipped_rotationRoundTripExhaustive() {
        // For every rotation (0..3) and every world face (0..5), un-rotating then re-rotating is
        // identity, and the model-face index that lands in the key is the documented inverse-yaw of
        // the world face. This pins the mapping used internally by tippedStateFor.
        // world->model per rotationIndex (derived from the generator's (x,y,z)->(z,y,-x) yaw):
        int[][] worldToModel = {
            {0, 1, 2, 3, 4, 5}, // rot0 identity
            {4, 5, 2, 3, 1, 0}, // rot1
            {1, 0, 2, 3, 5, 4}, // rot2
            {5, 4, 2, 3, 0, 1}, // rot3
        };
        for (int rot = 0; rot < 4; rot++) {
            for (int wf = 0; wf < 6; wf++) {
                int modelFace = PipeShapes.worldFaceToModelFace(wf, rot);
                assertEquals(worldToModel[rot][wf], modelFace,
                        "rot " + rot + " world face " + wf + " -> wrong model face");
                // round-trip: re-rotate the model face back to world.
                assertEquals(wf, PipeShapes.modelFaceToWorldFace(modelFace, rot),
                        "rot " + rot + " face " + wf + ": round-trip not identity");
            }
        }
    }

    @Test
    void tipped_verticalFacesYawInvariant() {
        // +Y(2) and -Y(3) map to themselves under all four rotations.
        for (int rot = 0; rot < 4; rot++) {
            assertEquals(2, PipeShapes.worldFaceToModelFace(2, rot), "rot " + rot + " +Y not invariant");
            assertEquals(3, PipeShapes.worldFaceToModelFace(3, rot), "rot " + rot + " -Y not invariant");
        }
    }

    @Test
    void tipped_twoFacesUnderRotation_keyUsesModelFaces() {
        // Tee covering +X|-X|+Z resolves to rotation 3 (table: 0b010011 -> Tee r3).
        // PUSH on world +X(0), PULL on world +Z(4). Under rot3: world 0 -> model 5, world 4 -> model 0.
        // Ascending model faces: 0(l) then 5(p) -> Tee_T0l_T5p.
        int mask = PX | NX | PZ;
        int rot = PipeShapes.resolve(mask).rotationIndex();
        assertEquals(3, rot);
        FlowState[] fs = faces(java.util.Map.of(0, FlowState.PUSH, 4, FlowState.PULL));
        assertEquals("Tee_T0l_T5p", PipeShapes.tippedStateFor(mask, fs, mask, false, rot));
    }
}
