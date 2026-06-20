package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmelterPortsTest {

    @Test
    void itemInputIsFaceOneWest() {
        List<Port> ports = SmelterPorts.defaults().portsFor(PortChannel.ITEM, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(1, p.faceIndex());
        assertEquals(PortChannel.ITEM, p.channel());
        assertEquals(PortDirection.INPUT, p.direction());
    }

    @Test
    void itemOutputIsFaceZeroEast() {
        List<Port> ports = SmelterPorts.defaults().portsFor(PortChannel.ITEM, PortDirection.OUTPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(0, p.faceIndex());
        assertEquals(PortChannel.ITEM, p.channel());
        assertEquals(PortDirection.OUTPUT, p.direction());
    }

    @Test
    void powerInputIsFaceFiveNorth() {
        List<Port> ports = SmelterPorts.defaults().portsFor(PortChannel.POWER, PortDirection.INPUT);
        assertEquals(1, ports.size());
        Port p = ports.get(0);
        assertEquals(5, p.faceIndex());
        assertEquals(PortChannel.POWER, p.channel());
        assertEquals(PortDirection.INPUT, p.direction());
    }

    @Test
    void unconfiguredQueryIsEmpty() {
        assertTrue(SmelterPorts.defaults().portsFor(PortChannel.POWER, PortDirection.OUTPUT).isEmpty());
    }
}
