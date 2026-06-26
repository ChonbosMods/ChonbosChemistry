package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import java.util.List;

/**
 * Default {@link PortConfig} for the Sculptor machine block (an autonomous crafter with no vanilla bench;
 * its state lives in {@link SculptorState}, not {@code MachineBlockState}).
 *
 * <p>Unlike the 2-wide Alembic/Assembler, the Sculptor reuses the Reclaimer's footprint: a
 * 1(X)×2(Y)×1(Z) column (a single 1×1 cell, two tall). Because it is ONLY ONE cell wide, all three port
 * sockets sit on the bottom (anchor) cell {@code (0,0,0)} : the anchor's own West face is an OUTER face
 * here (it is not buried in the middle of a 2-wide model), so item-in lives on the anchor's West face, NOT
 * on a {@code (-1,0,0)} cell. This is the {@code ReclaimerPorts} layout exactly: item-out on the anchor's
 * East face (0), item-in on the anchor's West face (1), power-in on the anchor's North face (5). Face
 * indices follow {@code PortProjection.OFFSETS} (0 +X/East, 1 -X/West, 5 -Z/North). INPUT and OUTPUT item
 * ports stay distinct (the network splits endpoints per (cell,face)).
 */
public final class SculptorPorts {

    private SculptorPorts() {
    }

    /**
     * @return the default Sculptor port layout (mirrors {@code ReclaimerPorts}): item-out East, item-in
     *     West, power-in North, ALL on the anchor (bottom) cell of the 1×1×2 footprint.
     */
    public static PortConfig defaults() {
        return PortConfig.of(List.of(
            Port.of(0, 0, 0, 0, PortChannel.ITEM, PortDirection.OUTPUT),   // anchor East
            Port.of(0, 0, 0, 1, PortChannel.ITEM, PortDirection.INPUT),    // anchor West (NOT cell -1!)
            Port.of(0, 0, 0, 5, PortChannel.POWER, PortDirection.INPUT))); // anchor North/rear
    }
}
