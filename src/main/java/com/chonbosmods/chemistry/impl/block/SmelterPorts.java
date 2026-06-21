package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.List;

/**
 * Default {@link PortConfig} for the Smelter machine block.
 *
 * <p>The Smelter is a 2(X)×2(Y)×1(Z) footprint with its anchor at the East-bottom corner
 * {@code (0,0,0)}; the West-bottom cell is {@code (-1,0,0)} and the two upper cells
 * {@code (0,1,0)}/{@code (-1,1,0)} are filler with no ports. Cell offsets are in the block's
 * default/model orientation (rotation is applied at lookup time).
 *
 * <p>Face indices follow this codebase's OFFSETS order ({@code 0 +X East}, {@code 1 -X West},
 * {@code 2 +Y Up}, {@code 3 -Y Down}, {@code 4 +Z South}, {@code 5 -Z North}; the model
 * front/window is {@code +Z South}). The out-of-the-box layout is:
 * <ul>
 *   <li>anchor {@code (0,0,0)}, East (face 0): ITEM output</li>
 *   <li>West-bottom {@code (-1,0,0)}, West (face 1): ITEM input</li>
 *   <li>anchor {@code (0,0,0)}, North (face 5): POWER input</li>
 * </ul>
 *
 * <p>These are defaults only: a wrench or GUI can later re-key any face.
 */
public final class SmelterPorts {

    private SmelterPorts() {
    }

    /**
     * @return the default Smelter port layout: item-out on the anchor's East face, item-in on the
     *     West-bottom cell's West face, power-in on the anchor's North face.
     */
    public static PortConfig defaults() {
        return PortConfig.of(List.of(
            Port.of(0, 0, 0, 0, PortChannel.ITEM, PortDirection.OUTPUT),
            Port.of(-1, 0, 0, 1, PortChannel.ITEM, PortDirection.INPUT),
            Port.of(0, 0, 0, 5, PortChannel.POWER, PortDirection.INPUT)));
    }
}
