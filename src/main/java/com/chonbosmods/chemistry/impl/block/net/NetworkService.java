package com.chonbosmods.chemistry.impl.block.net;

import com.hypixel.hytale.server.core.universe.world.World;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Per-{@link World} registry of {@link NetworkManager}s: shared cache state that the pipe place/break
 * and chunk-unload event systems (H3) and the later transport tick all read through.
 *
 * <h2>Why per-world</h2>
 * Networks are world-scoped. {@link NetworkManager} keys pipes by absolute block coordinates, and the
 * same absolute coordinate exists in every world: two worlds' networks would collide in a single shared
 * manager. Giving each {@link World} its own manager keeps caches isolated without threading a world id
 * through {@link NetworkManager}'s packed keys.
 *
 * <p>This is the ONLY class in the {@code net} package (besides the event systems) that imports
 * {@link World}: {@link NetworkManager} stays world-agnostic and headless-testable. Lookup is by
 * identity ({@link IdentityHashMap}): a {@link World} instance is the stable per-world key for the
 * server's lifetime.
 */
public final class NetworkService {

    /** world instance -> its lazily-created network cache. Identity-keyed (one World instance per world). */
    private final Map<World, NetworkManager> managers = new IdentityHashMap<>();

    /** world instance -> its pending pre-wipe pipe snapshots (H8 wipe recovery). Identity-keyed. */
    private final Map<World, PipeNodeSnapshots> snapshots = new IdentityHashMap<>();

    /**
     * The {@link NetworkManager} for {@code world}, creating (and remembering) one on first request.
     * Never null.
     */
    @Nonnull
    public NetworkManager forWorld(@Nonnull World world) {
        return managers.computeIfAbsent(world, w -> new NetworkManager());
    }

    /**
     * The {@link PipeNodeSnapshots} for {@code world}, creating (and remembering) one on first
     * request. Never null. Written by the pipe place/break event systems (pre-wipe), drained by
     * {@link NetworkTickSystem} at the start of each pass (restore-before-rebuild).
     */
    @Nonnull
    public PipeNodeSnapshots snapshotsForWorld(@Nonnull World world) {
        return snapshots.computeIfAbsent(world, w -> new PipeNodeSnapshots());
    }

    /** Number of worlds with a cached manager. Test/diagnostic accessor. */
    public int trackedWorldCount() {
        return managers.size();
    }
}
