package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers and caches transport {@link Network}s by flood-filling connected pipe segments.
 *
 * <h2>Block keys</h2>
 * Each block position is packed into a single {@code long} as three signed 21-bit fields (x, y, z),
 * each biased by {@link #COORD_BIAS} so the stored field is non-negative before shifting. 3 &times; 21
 * = 63 bits, leaving the sign bit clear, so a packed key is always &ge; 0 and ordering is well defined.
 * The usable per-axis range is {@link #MIN_COORD}..{@link #MAX_COORD} (&plusmn;2^20), which comfortably
 * exceeds any reachable Hytale world coordinate. {@link #packKey}/{@link #unpackX}/{@link #unpackY}/
 * {@link #unpackZ} round-trip exactly, including negatives.
 *
 * <h2>Discovery</h2>
 * {@link #getOrBuildNetwork} BFS-floods from a starting pipe over the 6 face-neighbors
 * ({@link #OFFSETS}, mirroring {@code MachineTickSystem}'s convention), following ONLY pipes of the
 * SAME {@link com.chonbosmods.chemistry.api.io.PortChannel channel}: a different-channel neighbour is a
 * boundary and is not part of this network. Each discovered segment is added to a fresh
 * {@link Network} with its tier's capacity/throughput from {@link PipeTiers}.
 *
 * <h2>Caching &amp; anchor</h2>
 * A network's deterministic id is its <em>anchor</em>: the lexicographically-smallest packed member
 * key. Because packed keys are non-negative and uniquely encode a position, "smallest packed key" is a
 * stable id independent of which member discovery started from. Two maps back the cache:
 * {@code anchorKey -> Network} and {@code pipeKey -> anchorKey} for every member, so any member position
 * resolves to its network in O(1). {@link #invalidate} drops a network and all its member entries so
 * the next build rebuilds fresh (used by the H3 event task on pipe place/break).
 *
 * <p>This class imports only {@link PipeNode}/{@link Network}/{@code PortChannel}/JDK: no live Hytale
 * world access leaks into the algorithm (that lives behind {@link PipeGridView}).
 */
public final class NetworkManager {

    /**
     * Canonical 6 face-neighbour offsets, indexed +X,-X,+Y,-Y,+Z,-Z, mirroring
     * {@code MachineTickSystem.OFFSETS}. Diagonals are intentionally absent: only face neighbours
     * connect.
     */
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /** Bits per packed coordinate field. 3 fields fit in 63 bits, keeping the sign bit clear. */
    private static final int BITS = 21;
    private static final long MASK = (1L << BITS) - 1L; // low 21 bits
    /** Bias added to each signed coord so the stored field is non-negative (range 0..2^21-1). */
    private static final int COORD_BIAS = 1 << (BITS - 1); // 2^20

    /** Minimum signed coordinate the packing supports (inclusive). */
    public static final int MIN_COORD = -COORD_BIAS;          // -2^20
    /** Maximum signed coordinate the packing supports (inclusive). */
    public static final int MAX_COORD = COORD_BIAS - 1;       //  2^20 - 1

    /** anchorKey -> the cached Network for that connected component. */
    private final Map<Long, Network> networksByAnchor = new HashMap<>();
    /** member pipeKey -> the anchorKey of the network it belongs to. */
    private final Map<Long, Long> anchorByPipe = new HashMap<>();

    // --- packing ---

    /**
     * Packs a signed block position into a non-negative {@code long}.
     *
     * @throws IllegalArgumentException if any coordinate is outside {@link #MIN_COORD}..{@link #MAX_COORD}.
     */
    public static long packKey(int x, int y, int z) {
        checkRange(x, "x");
        checkRange(y, "y");
        checkRange(z, "z");
        long px = (long) (x + COORD_BIAS) & MASK;
        long py = (long) (y + COORD_BIAS) & MASK;
        long pz = (long) (z + COORD_BIAS) & MASK;
        return (px << (2 * BITS)) | (py << BITS) | pz;
    }

    public static int unpackX(long key) {
        return (int) ((key >>> (2 * BITS)) & MASK) - COORD_BIAS;
    }

    public static int unpackY(long key) {
        return (int) ((key >>> BITS) & MASK) - COORD_BIAS;
    }

    public static int unpackZ(long key) {
        return (int) (key & MASK) - COORD_BIAS;
    }

    private static void checkRange(int c, String axis) {
        if (c < MIN_COORD || c > MAX_COORD) {
            throw new IllegalArgumentException(
                "coordinate " + axis + "=" + c + " out of packable range [" + MIN_COORD + "," + MAX_COORD + "]");
        }
    }

    // --- discovery + cache ---

    /**
     * Returns the {@link Network} the pipe at {@code (x,y,z)} belongs to, building (and caching) it via
     * same-channel BFS on first request.
     *
     * @return the network, or null if there is no pipe at {@code (x,y,z)}.
     */
    public Network getOrBuildNetwork(int x, int y, int z, PipeGridView grid) {
        PipeNode start = grid.pipeAt(x, y, z);
        if (start == null) {
            return null;
        }
        long startKey = packKey(x, y, z);
        Long cachedAnchor = anchorByPipe.get(startKey);
        if (cachedAnchor != null) {
            Network cached = networksByAnchor.get(cachedAnchor);
            if (cached != null) {
                return cached;
            }
            // Defensive: dangling pipe->anchor entry with no network; fall through to rebuild.
            anchorByPipe.remove(startKey);
        }

        // BFS flood-fill over same-channel face neighbours, collecting member keys.
        PortChannel channel = start.channel();
        List<Long> memberKeys = new ArrayList<>();
        List<PipeNode> memberNodes = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Deque<int[]> frontier = new ArrayDeque<>();
        visited.add(startKey);
        frontier.add(new int[] {x, y, z});

        while (!frontier.isEmpty()) {
            int[] p = frontier.poll();
            int px = p[0], py = p[1], pz = p[2];
            PipeNode node = grid.pipeAt(px, py, pz);
            // node is non-null: we only enqueue confirmed same-channel pipes (start verified above).
            memberKeys.add(packKey(px, py, pz));
            memberNodes.add(node);

            for (int[] off : OFFSETS) {
                int nx = px + off[0], ny = py + off[1], nz = pz + off[2];
                if (nx < MIN_COORD || nx > MAX_COORD
                        || ny < MIN_COORD || ny > MAX_COORD
                        || nz < MIN_COORD || nz > MAX_COORD) {
                    continue; // outside packable range; treat as world edge
                }
                long nKey = packKey(nx, ny, nz);
                if (!visited.add(nKey)) {
                    continue;
                }
                PipeNode neighbour = grid.pipeAt(nx, ny, nz);
                if (neighbour == null || neighbour.channel() != channel) {
                    continue; // no pipe, or a channel boundary
                }
                frontier.add(new int[] {nx, ny, nz});
            }
        }

        // Anchor = lexicographically-smallest packed member key (deterministic id).
        long anchor = memberKeys.get(0);
        for (long k : memberKeys) {
            if (k < anchor) {
                anchor = k;
            }
        }

        Network network = new Network(channel);
        for (int i = 0; i < memberKeys.size(); i++) {
            long key = memberKeys.get(i);
            int tier = memberNodes.get(i).tier();
            network.addMember(key, PipeTiers.capacityForTier(tier), PipeTiers.throughputForTier(tier));
            anchorByPipe.put(key, anchor);
        }
        networksByAnchor.put(anchor, network);
        return network;
    }

    /**
     * Drops the cached network that the pipe at {@code (x,y,z)} belonged to: removes its anchor entry
     * and every member's {@code pipeKey -> anchor} mapping, so the next {@link #getOrBuildNetwork}
     * rebuilds it fresh. Safe no-op if that position is not currently cached.
     */
    public void invalidate(int x, int y, int z) {
        Long anchor = anchorByPipe.get(packKey(x, y, z));
        if (anchor == null) {
            return;
        }
        networksByAnchor.remove(anchor);
        // Remove all members pointing at this anchor.
        anchorByPipe.values().removeIf(a -> a.equals(anchor));
    }

    /**
     * Drops every cached network that has at least one member pipe in the 32-wide chunk column
     * {@code (chunkX, chunkZ)} (block X in {@code [chunkX*32, chunkX*32+31]}, block Z likewise; the
     * chunk spans all Y). Used by the H3 chunk-unload event: when a chunk leaves memory its pipes are
     * gone from the live grid, so any network touching it must rebuild lazily on next access. Safe
     * no-op when no cached network intersects the column.
     *
     * <p>Whole networks are dropped, not just the in-chunk members: a network can straddle a chunk
     * boundary, and a partially-unloaded network would build wrong topology. Dropping it forces a clean
     * lazy rebuild over whatever is still loaded.
     *
     * @return the number of cached networks dropped.
     */
    public int invalidateChunk(int chunkX, int chunkZ) {
        // TODO(H6): persist buffer shares before drop.
        Set<Long> anchorsToDrop = new HashSet<>();
        for (long pipeKey : anchorByPipe.keySet()) {
            int bx = unpackX(pipeKey);
            int bz = unpackZ(pipeKey);
            if ((bx >> CHUNK_BITS) == chunkX && (bz >> CHUNK_BITS) == chunkZ) {
                anchorsToDrop.add(anchorByPipe.get(pipeKey));
            }
        }
        if (anchorsToDrop.isEmpty()) {
            return 0;
        }
        for (long anchor : anchorsToDrop) {
            networksByAnchor.remove(anchor);
        }
        anchorByPipe.values().removeIf(anchorsToDrop::contains);
        return anchorsToDrop.size();
    }

    /** Chunk columns are 32 blocks wide; blockX -&gt; chunkX is {@code blockX >> 5} (mirrors {@code ChunkUtil}). */
    private static final int CHUNK_BITS = 5;

    /**
     * The 7 positions a single-block topology change invalidates: the changed block itself plus its 6
     * face-neighbours, in the order {@code self, +X, -X, +Y, -Y, +Z, -Z} (matching {@link #OFFSETS}).
     * A break splits a network and a place can merge same-channel networks; dropping self and all face
     * neighbours forces a correct lazy rebuild on either side of the change. Pure helper (no world
     * access): the event systems map each returned position through {@link #invalidate}.
     *
     * @return a fresh {@code int[7][3]} of {@code {x,y,z}} triples.
     */
    public static int[][] selfAndNeighbors(int x, int y, int z) {
        int[][] out = new int[1 + OFFSETS.length][3];
        out[0][0] = x;
        out[0][1] = y;
        out[0][2] = z;
        for (int i = 0; i < OFFSETS.length; i++) {
            out[i + 1][0] = x + OFFSETS[i][0];
            out[i + 1][1] = y + OFFSETS[i][1];
            out[i + 1][2] = z + OFFSETS[i][2];
        }
        return out;
    }

    /** Number of distinct cached networks (one per discovered connected component). */
    public int cachedNetworkCount() {
        return networksByAnchor.size();
    }

    /**
     * The anchor (network id = min packed member key) currently cached for the pipe at {@code (x,y,z)},
     * or null if that position is not cached. Test accessor.
     */
    public Long anchorOf(int x, int y, int z) {
        return anchorByPipe.get(packKey(x, y, z));
    }
}
