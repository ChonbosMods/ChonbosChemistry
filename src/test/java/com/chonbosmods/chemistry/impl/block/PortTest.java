package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import org.junit.jupiter.api.Test;

class PortTest {

    @Test
    void positionedFactoryRecordsCellOffset() {
        Port p = Port.of(-1, 0, 0, 1, PortChannel.ITEM, PortDirection.INPUT);
        assertEquals(-1, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
        assertEquals(1, p.faceIndex());
        assertEquals(PortChannel.ITEM, p.channel());
        assertEquals(PortDirection.INPUT, p.direction());
    }

    @Test
    void legacyFactoryDefaultsToAnchorCell() {
        Port p = Port.of(0, PortChannel.POWER, PortDirection.INPUT);
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }
}
