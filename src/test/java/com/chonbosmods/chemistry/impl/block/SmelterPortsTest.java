package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Smelter is a 2(X)×2(Y)×1(Z) footprint, anchor at the East-bottom corner {@code (0,0,0)}.
 * Ports (default/model orientation):
 * <ul>
 *   <li>item-out: anchor {@code (0,0,0)}, East face (0)</li>
 *   <li>item-in: West-bottom {@code (-1,0,0)}, West face (1)</li>
 *   <li>power-in: anchor {@code (0,0,0)}, North face (5)</li>
 * </ul>
 * The two upper cells {@code (0,1,0)}/{@code (-1,1,0)} expose nothing.
 */
class SmelterPortsTest {

    @Test
    void itemInputIsWestFaceOnWestBottomCell() {
        List<Port> ports = SmelterPorts.defaults().portsFor(PortChannel.ITEM, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(1, p.faceIndex());
        assertEquals(-1, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void itemOutputIsEastFaceOnAnchor() {
        List<Port> ports = SmelterPorts.defaults().portsFor(PortChannel.ITEM, PortDirection.OUTPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(0, p.faceIndex());
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void powerInputIsNorthFaceOnAnchor() {
        List<Port> ports = SmelterPorts.defaults().portsFor(PortChannel.POWER, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(5, p.faceIndex());
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void upperCellsExposeNoPorts() {
        PortConfig cfg = SmelterPorts.defaults();
        assertTrue(cfg.forCell(0, 1, 0).ports().isEmpty());
        assertTrue(cfg.forCell(-1, 1, 0).ports().isEmpty());
    }

    @Test
    void westBottomCellOnlyHasItemIn() {
        PortConfig westBottom = SmelterPorts.defaults().forCell(-1, 0, 0);
        assertEquals(1, westBottom.ports().size());
        Port p = westBottom.ports().get(0);
        assertEquals(PortChannel.ITEM, p.channel());
        assertEquals(PortDirection.INPUT, p.direction());
    }

    @Test
    void unconfiguredQueryIsEmpty() {
        assertTrue(SmelterPorts.defaults().portsFor(PortChannel.POWER, PortDirection.OUTPUT).isEmpty());
    }
}
