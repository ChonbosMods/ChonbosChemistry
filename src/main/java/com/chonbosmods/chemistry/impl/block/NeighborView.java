package com.chonbosmods.chemistry.impl.block;

/**
 * Resolves the transfer node adjacent to a source in a given direction (0..5); null when absent
 * (e.g. unloaded chunk).
 *
 * <p>Lives in {@code impl.block} (not {@code api.io}) so the api stays contracts-only. Promotion to
 * api is deferred until third-party blocks need to participate in transport.
 */
public interface NeighborView {
    TransferNode neighborIn(int direction);
}
