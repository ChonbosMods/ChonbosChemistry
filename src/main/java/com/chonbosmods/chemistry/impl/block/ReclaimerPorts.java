package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.List;

/**
 * Default {@link PortConfig} for the Reclaimer machine block (machine #2, wraps the vanilla Salvage
 * bench).
 *
 * <p>The Reclaimer is a 1(X)×2(Y)×1(Z) footprint (a single 1×1 column, two tall). Its three port
 * sockets all sit on the bottom (anchor) cell's outer faces: {@code Port_ItemIn} on the West face,
 * {@code Port_ItemOut} on the East face, {@code Port_Power} on the North face. Face indices follow
 * {@code PortProjection.OFFSETS} (0 +X/East, 1 -X/West, 5 -Z/North).
 */
public final class ReclaimerPorts {

    private ReclaimerPorts() {
    }

    /**
     * @return the default Reclaimer port layout: item-out East, item-in West, power-in North, all on
     *     the anchor (bottom) cell of the 1×1×2 footprint.
     */
    public static PortConfig defaults() {
        return PortConfig.of(List.of(
            Port.of(0, 0, 0, 0, PortChannel.ITEM, PortDirection.OUTPUT),
            Port.of(0, 0, 0, 1, PortChannel.ITEM, PortDirection.INPUT),
            Port.of(0, 0, 0, 5, PortChannel.POWER, PortDirection.INPUT)));
    }
}
