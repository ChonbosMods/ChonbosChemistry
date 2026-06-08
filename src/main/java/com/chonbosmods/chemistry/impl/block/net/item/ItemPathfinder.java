package com.chonbosmods.chemistry.impl.block.net.item;

import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeConnectivity;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Destination;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nearest-first, filter-gated shortest-path routing over an ITEM {@link Network}'s MEMBER pipes
 * (2026-06-06 item-channel design §13.4, "Architecture &rarr; Pure"). Given an entry pipe and the item
 * being routed, a BFS over the network's pipe members finds the closest admitting container endpoints.
 *
 * <h2>What the BFS walks</h2>
 * It floods over EXISTING member pipes of {@code net} (it does NOT re-discover the network: discovery
 * already ran in {@link NetworkManager}). The frontier only ever enters cells that are members AND that
 * the grid still resolves to a {@link PipeNode}: a pipe missing from the grid is skipped defensively.
 *
 * <h2>Edge gate</h2>
 * A hop from pipe {@code a} to neighbour pipe {@code b} across face {@code f} is allowed iff BOTH:
 * <ol>
 *   <li>{@link PipeConnectivity#connects}{@code (a, f, b)} (the one pipe-pipe gate: same channel, neither
 *       facing flow state NONE, substance-compatible: ITEM pipes carry a null {@code resourceId} so the
 *       substance clause never blocks them), AND</li>
 *   <li>the <b>filter seam</b> admits the item INTO {@code b}.</li>
 * </ol>
 *
 * <h2>Where the filter is evaluated (precise)</h2>
 * The filter sits at the pipe you are ENTERING and is evaluated as the stack ARRIVES there. Concretely,
 * entering {@code b} from {@code a} across face {@code f} (the {@code OFFSETS}-order face from {@code a}
 * toward {@code b}) consults
 * {@code filters.forPipe(bKey).admits(key, bKey, }{@link PipeConnectivity#opposite opposite}{@code (f))}.
 * The {@code viaFace} passed is {@code b}'s RECEIVING face: the face on {@code b} that points back at
 * {@code a} (opposite the travel direction), i.e. the face the stack physically arrives through. A
 * junction pipe whose filter rejects the item is therefore impassable FOR THAT STACK (the edge is
 * dropped and the BFS detours), while a different item that the same filter admits passes: per-stack
 * impassability. The future tag/type intersection-filter feature configures filters per pipe and they
 * are evaluated against the arriving stack exactly here, with no routing rewrite.
 *
 * <p>The ENTRY pipe's own filter is NEVER consulted: a stack handed to {@code candidates} is already
 * inside the entry pipe (it was extracted into it, or is mid-transit there), so there is no "arrival"
 * to gate. Filters gate ENTERING a pipe, and the stack has already entered the entry pipe.
 *
 * <h2>Ordering</h2>
 * Candidates are returned nearest-first by path length (a destination's distance = its {@code viaPipe}'s
 * BFS depth, where the entry pipe is depth 0), ties broken by packed {@code containerKey} ascending for
 * deterministic, position-stable output independent of endpoint input order or {@link Network#memberKeys}
 * iteration order.
 *
 * <p>Pure JDK + net-layer types only: no engine imports.
 */
public final class ItemPathfinder {

    private ItemPathfinder() {
    }

    /**
     * A reachable delivery target: the {@link Destination} and the shortest pipe path that reaches its
     * via pipe.
     *
     * @param destination the container endpoint (its {@code viaPipeKey} is the last element of
     *     {@code path}).
     * @param path the member-pipe keys from the entry pipe to the destination's via pipe, INCLUSIVE of
     *     both ends, in travel order. For a container adjacent to the entry pipe this is a single
     *     element ({@code [entry]}). Never null, never empty. The array is owned by the candidate:
     *     callers must treat it as read-only (copy before mutating, e.g. into a TravelingStack, whose
     *     factory already copies defensively).
     */
    public record Candidate(Destination destination, long[] path) {
    }

    /**
     * The delivery candidates reachable from {@code entryPipeKey} for {@code key}, ordered nearest-first
     * (path length asc, then packed {@code containerKey} asc).
     *
     * <p>Re-route and return both compose from this one call: pass the stack's CURRENT segment as
     * {@code entryPipeKey} and the paths start from there (mid-transit re-route). To return toward a
     * SPECIFIC origin container, callers filter the result for the destination whose
     * {@code containerKey} equals the origin: this method returns ALL admitting destinations and the
     * caller (Task 5) selects.
     *
     * @param entryPipeKey the packed key of the pipe the stack currently occupies (path origin, depth 0);
     *     its own filter is NOT consulted (the stack is already inside it).
     * @param key the item being routed; passed to the filter seam at every ENTERED pipe. May be null
     *     (filters are null-tolerant).
     * @param endpoints the network's collected endpoints; only {@link Endpoints#destinations} are
     *     considered here (sources are an extraction concern).
     * @param net the network whose {@link Network#memberKeys} bound the BFS.
     * @param grid resolves each member key to its live {@link PipeNode} for the connectivity gate.
     * @param filters the per-pipe filter lookup (v1 {@link FilterLookup#NONE}).
     * @return nearest-first candidates; empty if no destination's via pipe is reachable for this key.
     */
    public static List<Candidate> candidates(
            long entryPipeKey,
            ItemKey key,
            Endpoints endpoints,
            Network net,
            PipeGridView grid,
            FilterLookup filters) {

        // BFS depths + parent links for path reconstruction, keyed by packed pipe key. The entry pipe is
        // the root at depth 0. A pipe absent here was never reached (filter-blocked, NONE-severed, or not
        // a member).
        Map<Long, Integer> depth = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();
        Set<Long> members = net.memberKeys();

        // The entry pipe must itself be a live member; otherwise nothing is reachable.
        if (!members.contains(entryPipeKey) || nodeAt(grid, entryPipeKey) == null) {
            return List.of();
        }

        Deque<Long> frontier = new ArrayDeque<>();
        depth.put(entryPipeKey, 0);
        frontier.add(entryPipeKey);

        while (!frontier.isEmpty()) {
            long aKey = frontier.poll();
            PipeNode a = nodeAt(grid, aKey);
            if (a == null) {
                continue; // defensive: member vanished from the grid
            }
            int ax = NetworkManager.unpackX(aKey);
            int ay = NetworkManager.unpackY(aKey);
            int az = NetworkManager.unpackZ(aKey);
            int aDepth = depth.get(aKey);
            // OFFSETS index IS the face from a toward the neighbour (mirrors NetworkManager's BFS).
            for (int faceIdx = 0; faceIdx < OFFSETS.length; faceIdx++) {
                int[] off = OFFSETS[faceIdx];
                int bx = ax + off[0], by = ay + off[1], bz = az + off[2];
                long bKey = NetworkManager.packKey(bx, by, bz);
                if (depth.containsKey(bKey) || !members.contains(bKey)) {
                    continue; // already reached, or not a member of this network
                }
                PipeNode b = grid.pipeAt(bx, by, bz);
                // Gate 1: the single pipe-pipe connectivity gate (channel + flow states + substance).
                if (!PipeConnectivity.connects(a, faceIdx, b)) {
                    continue;
                }
                // Gate 2: the filter at the ENTERED pipe b, evaluated against the stack arriving through
                // b's RECEIVING face (opposite the travel direction). A rejecting junction is impassable
                // for THIS key only; the BFS detours.
                int receivingFace = PipeConnectivity.opposite(faceIdx);
                if (!filters.forPipe(bKey).admits(key, bKey, receivingFace)) {
                    continue;
                }
                depth.put(bKey, aDepth + 1);
                parent.put(bKey, aKey);
                frontier.add(bKey);
            }
        }

        // Collect every destination whose via pipe the BFS reached, ordered nearest-first with a
        // deterministic containerKey tie-break.
        List<Candidate> out = new ArrayList<>();
        for (Destination dest : endpoints.destinations()) {
            Integer d = depth.get(dest.viaPipeKey());
            if (d == null) {
                continue; // via pipe unreachable for this key (severed or filter-gated)
            }
            out.add(new Candidate(dest, reconstructPath(dest.viaPipeKey(), parent)));
        }
        out.sort(Comparator
            .comparingInt((Candidate c) -> c.path().length)
            .thenComparingLong(c -> c.destination().containerKey()));
        return out;
    }

    /** The 6 face-neighbour offsets in canonical +X,-X,+Y,-Y,+Z,-Z order (mirrors NetworkManager). */
    private static final int[][] OFFSETS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private static PipeNode nodeAt(PipeGridView grid, long key) {
        return grid.pipeAt(
            NetworkManager.unpackX(key), NetworkManager.unpackY(key), NetworkManager.unpackZ(key));
    }

    /**
     * Walks parent links from {@code viaPipeKey} back to the BFS root, returning the path entry..viaPipe
     * in travel order (root first, via pipe last). A via pipe that IS the entry pipe yields a single
     * element.
     */
    private static long[] reconstructPath(long viaPipeKey, Map<Long, Long> parent) {
        List<Long> reversed = new ArrayList<>();
        Long cur = viaPipeKey;
        while (cur != null) {
            reversed.add(cur);
            cur = parent.get(cur);
        }
        long[] path = new long[reversed.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = reversed.get(path.length - 1 - i);
        }
        return path;
    }
}
