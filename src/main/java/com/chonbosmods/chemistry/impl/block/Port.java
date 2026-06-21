package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.api.io.PortDirection;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * A single configurable machine face: a {@code faceIndex} carrying a {@link PortChannel} with a
 * flow {@link PortDirection} (input / output / closed), on a specific footprint cell given by
 * {@code (cellX, cellY, cellZ)} (the cell's offset from the multi-cell block's anchor, in the block's
 * default/model orientation; {@code (0,0,0)} = the anchor itself). Single-cell machines leave the
 * offset at the anchor. Pure data (design §5.3).
 */
public final class Port {

    public static final BuilderCodec<Port> CODEC = BuilderCodec.builder(Port.class, Port::new)
        .append(new KeyedCodec<>("Face", Codec.INTEGER), (o, v) -> o.faceIndex = v, o -> o.faceIndex).add()
        .append(new KeyedCodec<>("Channel", PortChannel.CODEC), (o, v) -> o.channel = v, o -> o.channel).add()
        .append(new KeyedCodec<>("Direction", PortDirection.CODEC), (o, v) -> o.direction = v, o -> o.direction).add()
        // CellX/Y/Z are OPTIONAL (3-arg KeyedCodec): legacy ports persisted before multi-cell footprints
        // omit them, so the setter is never invoked and the offset stays (0,0,0) = the anchor cell.
        .append(new KeyedCodec<>("CellX", Codec.INTEGER, false), (o, v) -> o.cellX = v, o -> o.cellX).add()
        .append(new KeyedCodec<>("CellY", Codec.INTEGER, false), (o, v) -> o.cellY = v, o -> o.cellY).add()
        .append(new KeyedCodec<>("CellZ", Codec.INTEGER, false), (o, v) -> o.cellZ = v, o -> o.cellZ).add()
        .build();

    private int faceIndex;
    private PortChannel channel;
    private PortDirection direction;
    private int cellX;
    private int cellY;
    private int cellZ;

    private Port() {
    }

    /** A port on the anchor cell {@code (0,0,0)}; convenience for single-cell machines. */
    public static Port of(int faceIndex, PortChannel channel, PortDirection direction) {
        return of(0, 0, 0, faceIndex, channel, direction);
    }

    /** A port on footprint cell {@code (cellX,cellY,cellZ)} (offset from the anchor, model orientation). */
    public static Port of(int cellX, int cellY, int cellZ, int faceIndex, PortChannel channel, PortDirection direction) {
        Port p = new Port();
        p.cellX = cellX;
        p.cellY = cellY;
        p.cellZ = cellZ;
        p.faceIndex = faceIndex;
        p.channel = channel;
        p.direction = direction;
        return p;
    }

    public int cellX() {
        return cellX;
    }

    public int cellY() {
        return cellY;
    }

    public int cellZ() {
        return cellZ;
    }

    public int faceIndex() {
        return faceIndex;
    }

    public PortChannel channel() {
        return channel;
    }

    public PortDirection direction() {
        return direction;
    }
}
