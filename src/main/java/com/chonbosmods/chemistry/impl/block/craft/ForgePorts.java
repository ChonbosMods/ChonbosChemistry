package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.impl.block.Port;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import java.util.List;

/**
 * Default {@link PortConfig} for the Forge machine block (an autonomous crafter with no vanilla bench;
 * its state lives in {@link ForgeCraftState}, not {@code MachineBlockState}).
 *
 * <p>The Forge is a single 1×1×1 footprint. Its three port sockets all sit on the anchor cell's outer
 * faces: {@code Port_ItemIn} on the West face, {@code Port_ItemOut} on the East face, {@code Port_Power}
 * on the North face. Face indices follow {@code PortProjection.OFFSETS} (0 +X/East, 1 -X/West, 5 -Z/North).
 * INPUT and OUTPUT item ports are kept distinct because the network splits endpoints per (cell,face).
 */
public final class ForgePorts {

    private ForgePorts() {
    }

    /**
     * @return the default Forge port layout: item-out East, item-in West, power-in North, all on the
     *     anchor cell of the 1×1×1 footprint.
     */
    public static PortConfig defaults() {
        return PortConfig.of(List.of(
            Port.of(0, 0, 0, 0, PortChannel.ITEM, PortDirection.OUTPUT),
            Port.of(0, 0, 0, 1, PortChannel.ITEM, PortDirection.INPUT),
            Port.of(0, 0, 0, 5, PortChannel.POWER, PortDirection.INPUT)));
    }
}
