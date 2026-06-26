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
 * The Alembic is a 2(X)×2(Y)×1(Z) footprint (2 wide like the Smelter), anchor at the East-bottom corner
 * {@code (0,0,0)}; it mirrors {@code ForgePorts}/{@code SmelterPorts}. Ports (default/model orientation):
 * <ul>
 *   <li>item-out: anchor {@code (0,0,0)}, East face (0)</li>
 *   <li>item-in: West-bottom cell {@code (-1,0,0)}, West face (1)</li>
 *   <li>power-in: anchor {@code (0,0,0)}, North face (5)</li>
 * </ul>
 * item-in lives on the West cell (not the anchor) so a West pipe connects to a 2-wide block. Distinct
 * INPUT + OUTPUT item ports are required: the network splits endpoints per (cell,face).
 */
class AlembicPortsTest {

    @Test
    void itemOutputIsEastFaceOnAnchor() {
        List<Port> ports = AlembicPorts.defaults().portsFor(PortChannel.ITEM, PortDirection.OUTPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(0, p.faceIndex());
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void itemInputIsWestFaceOnWestCell() {
        List<Port> ports = AlembicPorts.defaults().portsFor(PortChannel.ITEM, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(1, p.faceIndex());
        assertEquals(-1, p.cellX()); // West-bottom cell of the 2-wide footprint, not the anchor
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void powerInputIsNorthFaceOnAnchor() {
        List<Port> ports = AlembicPorts.defaults().portsFor(PortChannel.POWER, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(5, p.faceIndex());
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void anchorCellHasItemOutAndPower_westCellHasItemIn() {
        PortConfig anchor = AlembicPorts.defaults().forCell(0, 0, 0);
        assertEquals(2, anchor.ports().size()); // item-out + power on the anchor
        PortConfig westCell = AlembicPorts.defaults().forCell(-1, 0, 0);
        assertEquals(1, westCell.ports().size()); // item-in on the West-bottom cell
    }

    @Test
    void unconfiguredQueryIsEmpty() {
        assertTrue(AlembicPorts.defaults().portsFor(PortChannel.POWER, PortDirection.OUTPUT).isEmpty());
    }
}
