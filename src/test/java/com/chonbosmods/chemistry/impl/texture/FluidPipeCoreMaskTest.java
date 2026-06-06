package com.chonbosmods.chemistry.impl.texture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FluidPipeCoreMaskTest {

    private final TexelMask mask = FluidPipeCoreMask.INSTANCE;

    @Test
    void coreIslandPixelIsTintable() {
        // inside the (32,0,10,10) core island, away from any shell rect
        assertTrue(mask.contains(33, 1));
    }

    @Test
    void overlapZonePixelIsExcluded() {
        // (50,17): inside core rect (50,14,12,12) AND inside shell top (40,16,14,14)
        // (shell x spans 40..53, y 16..29) -> excluded so the straight pipe's steel stays neutral
        assertFalse(mask.contains(50, 17));
    }

    @Test
    void shellOnlyPixelIsNotTintable() {
        // (5,33): far steel-shell area, in no core rect at all
        assertFalse(mask.contains(5, 33));
    }

    @Test
    void coreIslandOutsideShellZoneIsTintable() {
        // (36,11): inside core rect (35,10,10,10), below the y=16 start of the shell zone
        assertTrue(mask.contains(36, 11));
    }
}
