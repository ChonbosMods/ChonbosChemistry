package com.chonbosmods.chemistry.impl.block.net.item;

import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.NetworkManager;
import com.chonbosmods.chemistry.impl.block.net.PipeGridView;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Endpoints;
import com.chonbosmods.chemistry.impl.block.net.item.ItemEndpoints.Source;
import com.chonbosmods.chemistry.impl.block.net.item.ItemTransit.Decision;
import com.chonbosmods.chemistry.impl.block.net.item.ItemTransit.StepResult;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The per-network orchestration of ITEM transport, ONE pass per ITEM network per tick (2026-06-06
 * item-channel design "Tick driver"). It is the glue between the pure single-stack functions
 * ({@link ItemTransit#step} movement, {@link ItemExtraction#tryExtract} extraction) and the live world,
 * but it stays engine-free: it talks only to {@link PipeGridView}, {@link ContainerLookup}, and two thin
 * seams ({@link DropSink} ground drops, {@link SaveMarker} chunk-dirty marking). The real world adapters
 * for those seams live in {@code ItemTransferSystem} and are verified in-game; this core is unit-tested.
 *
 * <h2>Structure decision (driver vs new system)</h2>
 * The design sketch named a separate {@code ItemTransferSystem} ticking like {@code NetworkTickSystem}.
 * In practice {@code NetworkTickSystem} ALREADY owns the per-network dedup set, the per-tick grid/lookup
 * construction, and the member-pipe iteration. A second {@code EntityTickingSystem} would duplicate every
 * bit of that scaffolding (its own dedup, its own grid). So instead this DRIVER is invoked FROM
 * {@code NetworkTickSystem} for ITEM-channel networks, in the spot where fungible channels run
 * {@code NetworkTransfer.distribute}: one dedup, one grid, one tick. Extracting the orchestration into a
 * seam-based class keeps the testable core headless while the system stays thin glue (the same split as
 * {@code BlockHolderCarry}/{@code CarryBreakEventSystem}).
 *
 * <h2>Phase 1 — movement</h2>
 * For each member pipe, {@link ItemTransit#step} each in-transit stack ONCE this tick. Two hazards the
 * pure layer cannot handle drive the bookkeeping here:
 * <ul>
 *   <li><b>Stable-copy iteration.</b> A {@code MOVED_SEGMENT} stack physically migrates between
 *       {@code PipeNode.inTransit} lists mid-pass. We snapshot each member's list ({@link PipeNode#inTransit}
 *       already returns a defensive copy) before stepping so a stack that hops into a not-yet-visited pipe
 *       is not seen twice from the live list.
 *   <li><b>Double-step guard.</b> Even with the snapshot, a stack that moves into a pipe LATER in the
 *       member iteration would be stepped a second time when we reach that pipe. We tag every stepped
 *       stack in a per-tick identity set ({@link IdentityHashMap}-backed, mirroring
 *       {@code NetworkTickSystem}'s per-tick visited-anchor set) and skip already-stepped stacks.
 * </ul>
 * The {@link Decision} drives the physical move: {@code MOVED_SEGMENT} removes from the old pipe and adds
 * to the new path segment's pipe (ground-drop at the old pipe if that pipe is gone: defensive);
 * {@code DELIVERED}/{@code PARTIAL_DELIVERED_REROUTED} mark the owning chunk needing-save (the insert was
 * already committed against the container by the pure layer); {@code POP_OUT} routes a ground drop and
 * removes the stack. {@code ADVANCED}/{@code REROUTED}/{@code RETURNING} only mutate the stack in place.
 *
 * <h2>Phase 2 — extraction</h2>
 * Every {@code pullInterval} ticks (gated by {@code worldTick % pullInterval == 0}; a world-tick modulo,
 * chosen over a per-network counter so it needs no extra persisted state and stays deterministic across
 * network rebuilds), collect-once endpoints are scanned: each PULL {@link Source} runs
 * {@link ItemExtraction#tryExtract}; a returned stack is added to that source's via-pipe in-transit list
 * (and its chunk marked). The saturation cap is the network's member count ([TUNE], design "[TUNE]");
 * the in-transit count is recomputed from the live grid each interval.
 *
 * <h2>Defensive</h2>
 * Never throws: the engine glue runs it inside {@code NetworkTickSystem.tick}, which is already
 * try/guarded per member, but the driver itself also guards null pipes/containers and tolerates a stack
 * whose owning pipe vanished. A non-ITEM network is an immediate no-op (channel guard).
 *
 * <p>Pure JDK + net-layer types only: no engine imports.
 */
public final class ItemTransferDriver {

    /** [TUNE] ticks to traverse one pipe segment (design "[TUNE]": travel ~5 ticks/segment). */
    private final int speedTicks;
    /** [TUNE] ticks between PULL-face extraction attempts (design "[TUNE]": pull interval ~20). */
    private final int pullInterval;
    /** [TUNE] per-pull item cap (design "[TUNE]": ~16 items). */
    private final int pullCap;

    public ItemTransferDriver(int speedTicks, int pullInterval, int pullCap) {
        this.speedTicks = Math.max(1, speedTicks);
        this.pullInterval = Math.max(1, pullInterval);
        this.pullCap = Math.max(1, pullCap);
    }

    /**
     * The ground-drop seam: spawn a world drop carrying {@code stack} (id + count + metadata) at the
     * block position of {@code pipeKey}. The world impl materializes an engine {@code ItemStack} and uses
     * the {@code CarryBreakEventSystem.spawnDrop} primitive.
     */
    @FunctionalInterface
    public interface DropSink {
        void drop(long pipeKey, TravelingStack stack);
    }

    /**
     * The persistence seam: mark the chunk owning {@code pipeKey} (or any block at that key) needing-save,
     * the HyProTech/{@code WrenchInteraction.markNeedsSaving} pattern. Called after any in-transit mutation
     * or delivery so a place/break/unload between ticks re-persists the moved stacks.
     */
    @FunctionalInterface
    public interface SaveMarker {
        void markPipe(long pipeKey);
    }

    /**
     * Run one transport pass over {@code net}. Movement always; extraction only on a pull-interval tick.
     *
     * @param net        the network to tick; non-ITEM networks are a no-op.
     * @param grid       resolves member keys to live {@link PipeNode}s (and detects missing pipes).
     * @param containers the container seam (arrival inserts, PULL scans/extracts).
     * @param endpoints  the network's collected endpoints (collected once by the caller this tick).
     * @param filters    the per-pipe filter lookup (v1 {@link FilterLookup#NONE}).
     * @param worldTick  the current world tick; the pull-interval gate is {@code worldTick % pullInterval}.
     * @param dropSink   ground-drop seam for pop-outs and orphaned hand-offs.
     * @param saveMarker chunk-dirty seam, invoked per affected pipe key.
     */
    public void tickNetwork(
            Network net,
            PipeGridView grid,
            ContainerLookup containers,
            Endpoints endpoints,
            FilterLookup filters,
            long worldTick,
            DropSink dropSink,
            SaveMarker saveMarker) {
        if (net == null || net.channel() != PortChannel.ITEM) {
            return; // channel guard: the item subsystem must not touch fungible networks
        }
        // Per-tick identity set of stacks already stepped this pass (double-step guard), mirroring
        // NetworkTickSystem's per-tick visited-anchor set but keyed on stack IDENTITY.
        Set<TravelingStack> stepped = Collections.newSetFromMap(new IdentityHashMap<>());

        movementPhase(net, grid, containers, endpoints, filters, stepped, dropSink, saveMarker);

        if (worldTick % pullInterval == 0) {
            extractionPhase(net, grid, containers, endpoints, filters, saveMarker);
        }
    }

    // --- phase 1: movement -------------------------------------------------------------------

    private void movementPhase(
            Network net,
            PipeGridView grid,
            ContainerLookup containers,
            Endpoints endpoints,
            FilterLookup filters,
            Set<TravelingStack> stepped,
            DropSink dropSink,
            SaveMarker saveMarker) {
        for (long memberKey : net.memberKeys()) {
            PipeNode pipe = pipeAt(grid, memberKey);
            if (pipe == null) {
                continue; // member gone from the live grid (race with a break); skip
            }
            // Stable copy: stacks may migrate between inTransit lists during this loop. inTransit()
            // already returns a fresh list, but snapshot explicitly to make the intent load-bearing.
            List<TravelingStack> snapshot = pipe.inTransit();
            for (TravelingStack stack : snapshot) {
                if (stack == null || !stepped.add(stack)) {
                    continue; // null guard, or already stepped this tick (it hopped in from an earlier pipe)
                }
                StepResult result =
                    ItemTransit.step(stack, speedTicks, net, grid, containers, endpoints, filters);
                applyDecision(memberKey, pipe, grid, result, dropSink, saveMarker);
            }
        }
    }

    /** Acts on one {@link StepResult}: physical inTransit moves, drops, and save marks. Never throws. */
    private void applyDecision(
            long memberKey,
            PipeNode owningPipe,
            PipeGridView grid,
            StepResult result,
            DropSink dropSink,
            SaveMarker saveMarker) {
        TravelingStack stack = result.stack();
        switch (result.decision()) {
            case ADVANCED, REROUTED, RETURNING -> {
                // Pure in-place mutation of the stack on the SAME pipe: persist the new indices/route.
                saveMarker.markPipe(memberKey);
            }
            case MOVED_SEGMENT -> {
                // Hand off from the owning pipe to the pipe at the new current segment.
                long newKey = currentSegmentKey(stack);
                PipeNode next = pipeAt(grid, newKey);
                owningPipe.removeInTransit(stack);
                saveMarker.markPipe(memberKey);
                if (next == null) {
                    // The next pipe is gone (broken/unloaded mid-route): never void, drop at the old pipe.
                    dropSink.drop(memberKey, stack);
                    return;
                }
                next.addInTransit(stack);
                saveMarker.markPipe(newKey);
            }
            case DELIVERED -> {
                // The container insert was already committed by the pure layer; just remove + persist.
                owningPipe.removeInTransit(stack);
                saveMarker.markPipe(memberKey);
            }
            case PARTIAL_DELIVERED_REROUTED -> {
                // Partial committed; the reduced remainder keeps travelling on the SAME (current) pipe.
                saveMarker.markPipe(memberKey);
            }
            case POP_OUT -> {
                owningPipe.removeInTransit(stack);
                saveMarker.markPipe(memberKey);
                // 0L is not a valid packed position (packKey biases coordinates): treat as "no drop
                // location" and drop at the OWNING pipe instead of unpacking the zero key (design note +
                // ItemTransit javadoc).
                long dropKey = result.popOutPipeKey() == 0L ? memberKey : result.popOutPipeKey();
                dropSink.drop(dropKey, stack);
            }
            default -> {
                // Unknown decision: do nothing (defensive; the enum is exhaustive today).
            }
        }
    }

    // --- phase 2: extraction -----------------------------------------------------------------

    private void extractionPhase(
            Network net,
            PipeGridView grid,
            ContainerLookup containers,
            Endpoints endpoints,
            FilterLookup filters,
            SaveMarker saveMarker) {
        int saturationCap = Math.max(1, net.memberKeys().size()); // [TUNE]: max in-transit = segment count
        for (Source source : endpoints.sources()) {
            if (source == null) {
                continue;
            }
            int inTransitCount = countInTransit(net, grid); // recomputed per source: a prior pull adds to it
            TravelingStack pulled = ItemExtraction.tryExtract(
                source, net, grid, containers, endpoints, filters, pullCap, inTransitCount, saturationCap);
            if (pulled == null) {
                continue;
            }
            PipeNode via = pipeAt(grid, source.viaPipeKey());
            if (via == null) {
                continue; // the source's via pipe vanished between collect and now; drop nothing (no stack placed)
            }
            via.addInTransit(pulled);
            saveMarker.markPipe(source.viaPipeKey());
        }
    }

    /** Total stacks currently in flight across all live member pipes (saturation backpressure input). */
    private static int countInTransit(Network net, PipeGridView grid) {
        int total = 0;
        for (long memberKey : net.memberKeys()) {
            PipeNode pipe = pipeAt(grid, memberKey);
            if (pipe != null) {
                total += pipe.inTransit().size();
            }
        }
        return total;
    }

    // --- helpers -----------------------------------------------------------------------------

    private static long currentSegmentKey(TravelingStack stack) {
        long[] path = stack.path();
        int seg = stack.segmentIndex();
        if (path.length == 0) {
            return 0L;
        }
        int clamped = Math.max(0, Math.min(seg, path.length - 1));
        return path[clamped];
    }

    private static PipeNode pipeAt(PipeGridView grid, long key) {
        return grid.pipeAt(
            NetworkManager.unpackX(key), NetworkManager.unpackY(key), NetworkManager.unpackZ(key));
    }
}
