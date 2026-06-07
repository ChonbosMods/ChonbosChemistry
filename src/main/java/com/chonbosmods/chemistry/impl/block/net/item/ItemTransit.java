package com.chonbosmods.chemistry.impl.block.net.item;

import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import com.chonbosmods.chemistry.impl.block.net.item.ItemPathfinder.Candidate;
import java.util.List;

/**
 * The pure per-tick advance + re-route ladder over ONE {@link TravelingStack}: the behavioural heart of
 * ITEM transport (2026-06-06 item-channel design §13.4). It mutates only the stack's travel indices
 * (and, on the ladder, its count/path/dest) and REPORTS what the engine glue (Task 7) must physically
 * do; it never touches {@code World}, an engine {@code ItemStack}, or the per-pipe {@code inTransit}
 * lists. The glue acts on the returned {@link Decision}:
 * <ul>
 *   <li>{@code MOVED_SEGMENT}: physically move the stack between {@code PipeNode.inTransit} lists.
 *   <li>{@code DELIVERED}/{@code PARTIAL_DELIVERED_REROUTED}: the insert was already COMMITTED here
 *       against the {@link ContainerLookup} seam; the glue removes (DELIVERED) the stack or keeps the
 *       reduced remainder travelling (PARTIAL).
 *   <li>{@code POP_OUT}: spawn the ground drop at {@link StepResult#popOutPipeKey} and remove the stack.
 * </ul>
 *
 * <h2>The stranded ladder (design "Decisions", verbatim)</h2>
 * <i>"Stranded ladder: re-route if possible &rarr; else return toward origin &rarr; origin gone/full
 * &rarr; pop out as a ground drop at the stack's current pipe. Breaking a pipe with a stack inside drops
 * it. Never voids, never idles."</i> Concretely, when an ARRIVAL insert accepts ZERO (full or the
 * container vanished), this layer climbs:
 * <ol>
 *   <li><b>RE-ROUTE</b>: {@link ItemPathfinder#candidates}{@code (currentSegment, ...)} EXCLUDING the
 *       failed destination container &rarr; take nearest &rarr; new path/dest ({@link Decision#REROUTED}).
 *   <li><b>RETURN</b>: none admit &rarr; candidates filtered to ONLY the origin container &rarr; path
 *       back ({@link Decision#RETURNING}; the dest becomes the origin: a returning stack that later
 *       arrives delivers INTO origin like any arrival).
 *   <li><b>POP OUT</b>: origin unreachable / gone / full (insert simulate 0) &rarr;
 *       {@link Decision#POP_OUT} with {@code popOutPipeKey} = the current segment.
 * </ol>
 * A PARTIAL accept commits what fits and re-routes the remainder ({@link Decision#PARTIAL_DELIVERED_REROUTED});
 * if the remainder cannot re-route it climbs the same return/pop-out rungs (so a partial fit never idles).
 *
 * <h2>Arrival-only validation (design's "arrival re-validates" &mdash; [TUNE] cost)</h2>
 * Destination validity is checked ONLY AT ARRIVAL, never every tick mid-transit. Re-validating the
 * destination on every advance tick would multiply container probes by segment count for every in-flight
 * stack; the design accepts a stack travelling toward a destination that has since filled, paying one
 * wasted trip and resolving it via the ladder on arrival. This keeps the hot advance path a pure
 * index bump.
 *
 * <h2>Defensive</h2>
 * A malformed stack (empty path, or {@code segmentIndex} outside the path: shouldn't exist per the
 * persistence invariant, but never throw) resolves to {@link Decision#POP_OUT} so the glue can drop it
 * rather than crash the tick. {@code popOutPipeKey} is then the clamped current segment, or {@code 0L}
 * for an empty path: {@code 0L} is NOT a valid packed position (packKey biases coordinates), so the
 * glue (Task 7) must treat a zero key as "no drop location": drop at the pipe that OWNS the stack's
 * inTransit entry instead of unpacking the key.
 *
 * <p>Pure JDK + net-layer types only: no engine imports.
 */
public final class ItemTransit {

    /** [TUNE] — the design's per-segment travel time (~5 ticks/segment); injected by the caller. */

    private ItemTransit() {
    }

    /** What the glue must do after a {@link #step}. */
    public enum Decision {
        /** Progress accumulated within the current segment; nothing physical to move yet. */
        ADVANCED,
        /** Crossed into the next segment; the glue moves the stack between {@code inTransit} lists. */
        MOVED_SEGMENT,
        /** Arrived and the destination accepted the FULL count (committed); remove the stack. */
        DELIVERED,
        /** Arrived; the destination accepted SOME (committed), the reduced remainder re-routes. */
        PARTIAL_DELIVERED_REROUTED,
        /** Arrived to a zero-accept destination; the stack re-routed to a different container. */
        REROUTED,
        /** No alternate destination; the stack is heading back toward its origin container. */
        RETURNING,
        /** Stranded: spawn a ground drop at {@link StepResult#popOutPipeKey} and remove the stack. */
        POP_OUT
    }

    /**
     * The outcome of one {@link #step}.
     *
     * @param decision      what the glue must do.
     * @param stack         the SAME instance passed in, mutated in place (never reallocated): convenience
     *                      so callers can chain without re-fetching.
     * @param deliveredCount items actually inserted into a container this step (DELIVERED / PARTIAL);
     *                       0 for every non-delivering decision.
     * @param popOutPipeKey the packed pipe key to drop at, valid ONLY when {@code decision == POP_OUT}
     *                      (0 otherwise).
     */
    public record StepResult(Decision decision, TravelingStack stack, int deliveredCount,
                             long popOutPipeKey) {
    }

    /**
     * Advance ONE traveling stack by one tick, resolving arrival + the stranded ladder. Mutates only the
     * stack (indices always; count/path/dest on the ladder). No world access.
     *
     * @param stack      the stack to step; mutated in place and returned in the result.
     * @param speedTicks [TUNE] ticks to traverse one segment (progress threshold).
     * @param net        the stack's ITEM network (bounds the re-route BFS).
     * @param grid       resolves member pipes to live {@link com.chonbosmods.chemistry.impl.block.net.PipeNode}s.
     * @param containers the container seam: arrival insert + ladder destination probes.
     * @param endpoints  the network's collected endpoints (re-route + return candidate sources).
     * @param filters    the per-pipe filter lookup (v1 {@link FilterLookup#NONE}).
     * @return the {@link StepResult}.
     */
    public static StepResult step(
            TravelingStack stack,
            int speedTicks,
            Network net,
            PipeGridView grid,
            ContainerLookup containers,
            Endpoints endpoints,
            FilterLookup filters) {

        long[] path = stack.path();
        int seg = stack.segmentIndex();

        // Defensive: malformed stack (empty path, or segment outside the path). Per the persistence
        // invariant this should never happen, but never throw: pop out so the glue drops it.
        if (path.length == 0 || seg < 0 || seg >= path.length) {
            long popKey = path.length == 0 ? 0L : path[Math.max(0, Math.min(seg, path.length - 1))];
            return popOut(stack, popKey);
        }

        // Accumulate progress. Below threshold: plain advance (the hot path is a pure index bump; no
        // destination re-validation mid-transit per the arrival-only decision).
        int progress = stack.progressTicks() + 1;
        if (progress < speedTicks) {
            stack.setProgressTicks(progress);
            return new StepResult(Decision.ADVANCED, stack, 0, 0L);
        }

        // Segment complete. If this is NOT the last segment, cross into the next one and let the glue
        // move the physical stack; no container work happens here.
        if (seg < path.length - 1) {
            stack.setProgressTicks(0);
            stack.setSegmentIndex(seg + 1);
            return new StepResult(Decision.MOVED_SEGMENT, stack, 0, 0L);
        }

        // ARRIVAL: the stack is on the destination's via pipe with the segment complete. Re-validate the
        // destination HERE (and only here) by attempting the insert.
        return arrive(stack, net, grid, containers, endpoints, filters);
    }

    /**
     * Arrival at the last path segment: insert into the target container, then climb the stranded ladder
     * on a non-full accept. The current segment (= {@code path[last]}) is the via pipe and the re-route
     * BFS entry.
     */
    private static StepResult arrive(
            TravelingStack stack,
            Network net,
            PipeGridView grid,
            ContainerLookup containers,
            Endpoints endpoints,
            FilterLookup filters) {

        long currentSegment = stack.path()[stack.segmentIndex()];
        long destKey = stack.destKey();
        ContainerView dest = containerAt(containers, destKey);
        int count = stack.count();

        // Insert probe + commit folded into one: a full accept commits all; a partial commits what fits;
        // a zero accept (full or container gone) commits nothing.
        int accepted = dest == null ? 0 : dest.insert(stack.key(), count, false);

        if (accepted >= count) {
            // Full delivery (covers a returning stack arriving at its origin: dest == origin, treated
            // like any arrival).
            return new StepResult(Decision.DELIVERED, stack, accepted, 0L);
        }

        if (accepted > 0) {
            // Partial: what fit is already committed; reduce the remainder and re-route it. If it cannot
            // re-route/return, the ladder pops it out (a partial fit never idles).
            stack.setCount(count - accepted);
            StepResult ladder = climbLadder(stack, currentSegment, destKey, net, grid, containers,
                endpoints, filters);
            if (ladder.decision() == Decision.REROUTED) {
                // Report the partial delivery + the committed count alongside the re-route.
                return new StepResult(Decision.PARTIAL_DELIVERED_REROUTED, stack, accepted, 0L);
            }
            // Remainder could only return or pop out: still surface those, but with the delivered count.
            return new StepResult(ladder.decision(), stack, accepted, ladder.popOutPipeKey());
        }

        // Zero accept: nothing delivered. Climb the full ladder (re-route -> return -> pop out).
        return climbLadder(stack, currentSegment, destKey, net, grid, containers, endpoints, filters);
    }

    /**
     * The stranded ladder, design-verbatim: RE-ROUTE (excluding the failed dest) &rarr; RETURN toward the
     * origin container &rarr; POP OUT at the current segment. Mutates the stack's dest/path/indices on a
     * re-route or return. Returns {@link Decision#REROUTED}, {@link Decision#RETURNING}, or
     * {@link Decision#POP_OUT}.
     *
     * @param failedDestKey the container that just rejected (or vanished); EXCLUDED from re-route picks.
     */
    private static StepResult climbLadder(
            TravelingStack stack,
            long currentSegment,
            long failedDestKey,
            Network net,
            PipeGridView grid,
            ContainerLookup containers,
            Endpoints endpoints,
            FilterLookup filters) {

        List<Candidate> candidates =
            ItemPathfinder.candidates(currentSegment, stack.key(), endpoints, net, grid, filters);

        long originKey = stack.originKey();

        // Rung 1: RE-ROUTE to the nearest admitting container that is NOT the one that just rejected,
        // NOT the origin (returning to origin is the dedicated rung 2), and that actually has room
        // (simulate insert > 0). Candidates are already nearest-first.
        for (Candidate c : candidates) {
            long containerKey = c.destination().containerKey();
            if (containerKey == failedDestKey || containerKey == originKey) {
                continue; // never re-pick the rejecting container; the origin is rung 2's job
            }
            ContainerView view = containerAt(containers, containerKey);
            if (view == null || view.insert(stack.key(), stack.count(), true) <= 0) {
                continue; // gone or no room: not a viable re-route target
            }
            retarget(stack, c, containerKey);
            return new StepResult(Decision.REROUTED, stack, 0, 0L);
        }

        // Rung 2: RETURN toward the ORIGIN container. Filter candidates to the one whose container IS the
        // origin, and only if it has room. The origin may equal the failed dest (a returning stack that
        // re-failed): that's fine, return is a distinct rung from re-route's exclusion.
        for (Candidate c : candidates) {
            long containerKey = c.destination().containerKey();
            if (containerKey != originKey) {
                continue;
            }
            ContainerView view = containerAt(containers, containerKey);
            if (view == null || view.insert(stack.key(), stack.count(), true) <= 0) {
                break; // origin reachable but gone/full: fall through to pop out
            }
            retarget(stack, c, containerKey);
            return new StepResult(Decision.RETURNING, stack, 0, 0L);
        }

        // Rung 3: POP OUT at the current segment (origin unreachable/gone/full). Never void, never idle.
        return popOut(stack, currentSegment);
    }

    /** Point the stack at a new destination via a candidate's path; resets segment/progress to the head. */
    private static void retarget(TravelingStack stack, Candidate c, long containerKey) {
        // Candidate.path is candidate-owned; setPathAndReset copies defensively (TravelingStack owns it).
        stack.setPathAndReset(c.path(), 0, 0);
        stack.setDestKey(containerKey);
    }

    private static StepResult popOut(TravelingStack stack, long pipeKey) {
        return new StepResult(Decision.POP_OUT, stack, 0, pipeKey);
    }

    private static ContainerView containerAt(ContainerLookup containers, long key) {
        return containers.at(
            NetworkManager.unpackX(key), NetworkManager.unpackY(key), NetworkManager.unpackZ(key));
    }
}
