package com.chonbosmods.chemistry.impl.block.net;

/**
 * The mockable seam between {@link NetworkManager}'s pure discovery algorithm and live Hytale block
 * access: it answers "is there a pipe at this block position, and what does it carry?" without the
 * manager ever touching {@code World}/{@code BlockModule}. The real implementation wrapping the world
 * is built later (the tick task); unit tests pass a fake backed by an in-memory map.
 */
public interface PipeGridView {

    /** The {@link PipeNode} at this block position, or null if there is no pipe there. */
    PipeNode pipeAt(int x, int y, int z);
}
