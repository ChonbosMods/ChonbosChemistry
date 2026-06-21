package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Projects a machine's model-orientation {@link PortConfig} onto a specific WORLD footprint cell, given
 * the placed block's yaw {@link Rotation}. Face indices are OFFSETS order (0 +X, 1 -X, 2 +Y, 3 -Y,
 * 4 +Z, 5 -Z). The Smelter layout (anchor East-bottom (0,0,0)): item-out East on anchor, item-in West
 * on West-bottom (-1,0,0), power North on anchor.
 */
class PortProjectionTest {

    private static PortConfig smelterModel() {
        return PortConfig.of(List.of(
            Port.of(0, 0, 0, 0, PortChannel.ITEM, PortDirection.OUTPUT),
            Port.of(-1, 0, 0, 1, PortChannel.ITEM, PortDirection.INPUT),
            Port.of(0, 0, 0, 5, PortChannel.POWER, PortDirection.INPUT)));
    }

    @Test
    void rotationNoneIsIdentity() {
        PortConfig model = smelterModel();

        PortConfig anchor = PortProjection.forWorldCell(model, Rotation.None, 0, 0, 0);
        assertEquals(2, anchor.ports().size());
        assertNotNull(anchor.portAt(0, PortChannel.ITEM));   // item-out East
        assertNotNull(anchor.portAt(5, PortChannel.POWER));  // power North
        assertNull(anchor.portAt(1, PortChannel.ITEM));      // item-in lives on a different cell

        PortConfig westBottom = PortProjection.forWorldCell(model, Rotation.None, -1, 0, 0);
        assertEquals(1, westBottom.ports().size());
        assertNotNull(westBottom.portAt(1, PortChannel.ITEM)); // item-in West

        assertTrue(PortProjection.forWorldCell(model, Rotation.None, 0, 1, 0).ports().isEmpty());
    }

    @Test
    void rotation180NegatesXZAndFlipsFaces() {
        // 180deg yaw is convention-independent: (x,z) -> (-x,-z), y unchanged; West(1) <-> East(0).
        PortConfig model = smelterModel();

        // The West-bottom cell, modelled at (-1,0,0), now sits at WORLD (+1,0,0); its West item-in face
        // now points East (face 0).
        PortConfig westBottomNowEast = PortProjection.forWorldCell(model, Rotation.OneEighty, 1, 0, 0);
        assertEquals(1, westBottomNowEast.ports().size());
        Port itemIn = westBottomNowEast.portAt(0, PortChannel.ITEM); // West face rotated to East
        assertNotNull(itemIn);
        assertEquals(PortDirection.INPUT, itemIn.direction());

        // The anchor stays at (0,0,0) (rotation-invariant), but its East item-out now points West (1) and
        // its North power face now points South (4).
        PortConfig anchor = PortProjection.forWorldCell(model, Rotation.OneEighty, 0, 0, 0);
        assertEquals(2, anchor.ports().size());
        assertNotNull(anchor.portAt(1, PortChannel.ITEM));  // East -> West
        assertNotNull(anchor.portAt(4, PortChannel.POWER)); // North(-Z,5) -> South(+Z,4)
    }
}
