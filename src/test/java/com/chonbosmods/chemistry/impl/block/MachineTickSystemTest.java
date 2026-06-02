package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Headless tests for the only pure, ECS-free part of {@link MachineTickSystem}: the canonical
 * direction-to-offset mapping. The transport/work passes and position resolution require a live
 * World/ChunkStore and are verified by booting devServer + in-game (Task B6).
 */
class MachineTickSystemTest {

    @Test
    void neighborPos_appliesCanonicalOffsetPerDirection() {
        // Canonical face order: +X, -X, +Y, -Y, +Z, -Z (documented on MachineTickSystem.OFFSETS).
        assertArrayEquals(new int[] {11, 20, 30}, MachineTickSystem.neighborPos(10, 20, 30, 0)); // +X
        assertArrayEquals(new int[] {9, 20, 30}, MachineTickSystem.neighborPos(10, 20, 30, 1));  // -X
        assertArrayEquals(new int[] {10, 21, 30}, MachineTickSystem.neighborPos(10, 20, 30, 2)); // +Y
        assertArrayEquals(new int[] {10, 19, 30}, MachineTickSystem.neighborPos(10, 20, 30, 3)); // -Y
        assertArrayEquals(new int[] {10, 20, 31}, MachineTickSystem.neighborPos(10, 20, 30, 4)); // +Z
        assertArrayEquals(new int[] {10, 20, 29}, MachineTickSystem.neighborPos(10, 20, 30, 5)); // -Z
    }

    @Test
    void neighborPos_coversExactlySixDirections() {
        assertEquals(6, MachineTickSystem.directionCount());
        // Out-of-range directions return null rather than throwing (defensive: tick must never throw).
        assertNull(MachineTickSystem.neighborPos(0, 0, 0, -1));
        assertNull(MachineTickSystem.neighborPos(0, 0, 0, 6));
    }
}
