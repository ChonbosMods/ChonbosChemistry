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
 * The Sculptor reuses the Reclaimer's 1(X)×2(Y)×1(Z) footprint (a single 1×1 column, two tall), so unlike
 * the 2-wide Alembic ALL three ports sit on the ANCHOR cell {@code (0,0,0)} — it mirrors {@code ReclaimerPorts}
 * exactly, NOT {@code AlembicPorts}. Ports (default/model orientation):
 * <ul>
 *   <li>item-out: anchor {@code (0,0,0)}, East face (0)</li>
 *   <li>item-in: anchor {@code (0,0,0)}, West face (1) — NOT a {@code (-1,0,0)} cell like the 2-wide Alembic</li>
 *   <li>power-in: anchor {@code (0,0,0)}, North face (5)</li>
 * </ul>
 * Distinct INPUT + OUTPUT item ports are required: the network splits endpoints per (cell,face).
 */
class SculptorPortsTest {

    @Test
    void itemOutputIsEastFaceOnAnchor() {
        List<Port> ports = SculptorPorts.defaults().portsFor(PortChannel.ITEM, PortDirection.OUTPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(0, p.faceIndex());
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void itemInputIsWestFaceOnAnchorCell() {
        List<Port> ports = SculptorPorts.defaults().portsFor(PortChannel.ITEM, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(1, p.faceIndex());
        assertEquals(0, p.cellX()); // anchor cell of the 1-wide footprint, NOT a (-1,0,0) cell
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void powerInputIsNorthFaceOnAnchor() {
        List<Port> ports = SculptorPorts.defaults().portsFor(PortChannel.POWER, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(5, p.faceIndex());
        assertEquals(0, p.cellX());
        assertEquals(0, p.cellY());
        assertEquals(0, p.cellZ());
    }

    @Test
    void allThreePortsLiveOnTheAnchorCell() {
        PortConfig anchor = SculptorPorts.defaults().forCell(0, 0, 0);
        assertEquals(3, anchor.ports().size()); // item-out + item-in + power, all on the anchor (1-wide)
        // No port lives on a west (-1,0,0) cell: that is the Alembic's 2-wide layout, not the Sculptor's.
        PortConfig westCell = SculptorPorts.defaults().forCell(-1, 0, 0);
        assertTrue(westCell.ports().isEmpty());
    }

    @Test
    void unconfiguredQueryIsEmpty() {
        assertTrue(SculptorPorts.defaults().portsFor(PortChannel.POWER, PortDirection.OUTPUT).isEmpty());
    }
}
