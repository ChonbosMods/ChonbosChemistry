package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Pure tests for {@link FaceNames}: the OFFSETS-order index → name mapping and out-of-range safety. */
class FaceNamesTest {

    @Test
    void mapsEachOffsetsIndexToItsName() {
        assertEquals("East", FaceNames.name(0));  // +X
        assertEquals("West", FaceNames.name(1));  // -X
        assertEquals("Up", FaceNames.name(2));    // +Y
        assertEquals("Down", FaceNames.name(3));  // -Y
        assertEquals("South", FaceNames.name(4)); // +Z
        assertEquals("North", FaceNames.name(5)); // -Z
    }

    @Test
    void outOfRangeIndicesReturnPlaceholderNeverThrow() {
        assertEquals("?", FaceNames.name(-1));
        assertEquals("?", FaceNames.name(6));
        assertEquals("?", FaceNames.name(Integer.MIN_VALUE));
        assertEquals("?", FaceNames.name(Integer.MAX_VALUE));
    }
}
