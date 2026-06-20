package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import java.util.List;

/**
 * Default {@link PortConfig} for the Smelter machine block.
 *
 * <p>Face indices follow this codebase's OFFSETS order ({@code 0 +X East}, {@code 1 -X West},
 * {@code 2 +Y Up}, {@code 3 -Y Down}, {@code 4 +Z South}, {@code 5 -Z North}; the model
 * front/window is {@code +Z South}). The sensible out-of-the-box layout is:
 * <ul>
 *   <li>West (face 1): ITEM input</li>
 *   <li>East (face 0): ITEM output</li>
 *   <li>North (face 5): POWER input</li>
 * </ul>
 *
 * <p>These are defaults only: a wrench or GUI can later re-key any face.
 */
public final class SmelterPorts {

    private SmelterPorts() {
    }

    /** @return the default Smelter port layout: W item-in, E item-out, N power-in. */
    public static PortConfig defaults() {
        return PortConfig.of(List.of(
            Port.of(1, PortChannel.ITEM, PortDirection.INPUT),
            Port.of(0, PortChannel.ITEM, PortDirection.OUTPUT),
            Port.of(5, PortChannel.POWER, PortDirection.INPUT)));
    }
}
