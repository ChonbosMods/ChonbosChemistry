package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.List;

/**
 * Default {@link PortConfig} for the Reclaimer machine block (machine #2, wraps the vanilla Salvage
 * bench).
 *
 * <p>The Reclaimer is a 2(X)×2(Y)×2(Z) footprint with its anchor at the East-bottom-South corner
 * (mirroring the {@link SmelterPorts} convention). Its model carries three port sockets:
 * {@code Port_ItemIn} on the West face, {@code Port_ItemOut} on the East face, {@code Port_Power} on
 * the North face. Face indices follow {@code PortProjection.OFFSETS} (0 +X/East, 1 -X/West, 5 -Z/North).
 */
public final class ReclaimerPorts {

    private ReclaimerPorts() {
    }

    /**
     * @return the default Reclaimer port layout: item-out on the anchor's East face, item-in on the
     *     West-bottom cell's West face, power-in on the North-bottom cell's North face.
     */
    public static PortConfig defaults() {
        return PortConfig.of(List.of(
            Port.of(0, 0, 0, 0, PortChannel.ITEM, PortDirection.OUTPUT),
            Port.of(-1, 0, 0, 1, PortChannel.ITEM, PortDirection.INPUT),
            Port.of(0, 0, -1, 5, PortChannel.POWER, PortDirection.INPUT)));
    }
}
