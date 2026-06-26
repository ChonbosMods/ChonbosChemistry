package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import java.util.List;

/**
 * Default {@link PortConfig} for the Alembic machine block (an autonomous crafter with no vanilla bench;
 * its state lives in {@link AlembicState}, not {@code MachineBlockState}).
 *
 * <p>The Alembic is a 2(X)×2(Y)×1(Z) footprint (2 wide like the Smelter), anchor at the East-bottom corner
 * {@code (0,0,0)}; the West-bottom cell is {@code (-1,0,0)} and the two upper cells {@code (0,1,0)}/
 * {@code (-1,1,0)} are filler with no ports. The out-of-the-box layout mirrors the Smelter: item-out on the
 * anchor's East face (0), item-in on the West-bottom cell's West face (1), power-in on the anchor's North
 * face (5). Face indices follow {@code PortProjection.OFFSETS} (0 +X/East, 1 -X/West, 5 -Z/North). Putting
 * item-in on the West cell (not the anchor) is what makes a West pipe connect: on a 2-wide block the
 * anchor's own West face sits in the MIDDLE of the model, so the West socket must live on the West cell.
 * INPUT and OUTPUT item ports stay distinct (the network splits endpoints per (cell,face)). This matches
 * the Alembic model's Port_ItemIn/Port_ItemOut/Port_Power.
 */
public final class AlembicPorts {

    private AlembicPorts() {
    }

    /**
     * @return the default Alembic port layout (mirrors {@code ForgePorts}/{@code SmelterPorts}): item-out on
     *     the anchor's East face, item-in on the West-bottom cell's West face, power-in on the anchor's
     *     North face.
     */
    public static PortConfig defaults() {
        return PortConfig.of(List.of(
            Port.of(0, 0, 0, 0, PortChannel.ITEM, PortDirection.OUTPUT),
            Port.of(-1, 0, 0, 1, PortChannel.ITEM, PortDirection.INPUT),
            Port.of(0, 0, 0, 5, PortChannel.POWER, PortDirection.INPUT)));
    }
}
