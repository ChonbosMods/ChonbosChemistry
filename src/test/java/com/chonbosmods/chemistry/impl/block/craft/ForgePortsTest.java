package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Forge is a single-cell footprint, anchor at {@code (0,0,0)} (mirrors the Reclaimer's anchor-cell
 * port layout). Ports (default/model orientation):
 * <ul>
 *   <li>item-out: anchor {@code (0,0,0)}, East face (0)</li>
 *   <li>item-in: anchor {@code (0,0,0)}, West face (1)</li>
 *   <li>power-in: anchor {@code (0,0,0)}, North face (5)</li>
 * </ul>
 * Distinct INPUT + OUTPUT item ports are required: the network splits endpoints per (cell,face).
 */
class ForgePortsTest {

    @Test
    void itemOutputIsEastFaceOnAnchor() {
        List<Port> ports = ForgePorts.defaults().portsFor(PortChannel.ITEM, PortDirection.OUTPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(0, p.faceIndex());
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void itemInputIsWestFaceOnAnchor() {
        List<Port> ports = ForgePorts.defaults().portsFor(PortChannel.ITEM, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(1, p.faceIndex());
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void powerInputIsNorthFaceOnAnchor() {
        List<Port> ports = ForgePorts.defaults().portsFor(PortChannel.POWER, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(5, p.faceIndex());
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void anchorCellHasItemInItemOutAndPower() {
        PortConfig anchor = ForgePorts.defaults().forCell(0, 0, 0);
        assertEquals(3, anchor.ports().size());
    }

    @Test
    void unconfiguredQueryIsEmpty() {
        assertTrue(ForgePorts.defaults().portsFor(PortChannel.POWER, PortDirection.OUTPUT).isEmpty());
    }
}
