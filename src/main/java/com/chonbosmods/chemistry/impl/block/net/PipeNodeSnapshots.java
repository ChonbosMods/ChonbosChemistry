package com.chonbosmods.chemistry.impl.block.net;

import com.chonbosmods.chemistry.api.io.FlowState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Per-world store of pre-wipe {@link PipeNode} snapshots (H8: wipe recovery).
 *
 * <h2>The wipe</h2>
 * The engine's connected-block neighbor pass ({@code ConnectedBlocksUtil}, {@code settings=132})
 * re-resolves pipe shapes via {@code WorldChunk.setBlock} with settings bit 2 CLEAR, which replaces
 * the re-resolved block's entity with a fresh clone of the asset template
 * ({@code WorldChunk.java:368-371}). Every pipe whose shape re-resolves is factory-reset:
 * {@code bufferShare=0, resourceId=null}. Breaking/placing any connected-block participant (pipes,
 * and machines via the {@code CC_*MachineTemplate}s) triggers that pass on neighbors up to depth 3,
 * so a topology change silently destroys the wiped pipes' persisted network shares. POWER networks
 * mostly dodged it (a charged machine's break is cancelled and replaced by a bare
 * {@code setBlock(EMPTY)} that skips the neighbor pass: {@code CarryBreakEventSystem}); FLUID/GAS
 * additionally dropped wiped shares on rebuild because a null {@code resourceId} cannot re-pool
 * onto a type-locked channel.
 *
 * <h2>The recovery</h2>
 * The pipe place/break event systems snapshot every pipe near a topology change BEFORE the engine's
 * pass runs ({@code BreakBlockEvent}/{@code PlaceBlockEvent} fire pre-removal/pre-update), via
 * {@link PipeSnapshotScan}. {@link NetworkTickSystem} calls {@link #restorePending} at the start of
 * its pass: BEFORE any rebuild pools shares, every pending snapshot is checked against the live
 * grid and wiped pipes get their share/resource written back (the caller invalidates the restored
 * positions so the next rebuild re-pools them).
 *
 * <h2>Restore guard (no duplication)</h2>
 * A snapshot applies ONLY to a pipe showing the wipe signature: {@code share == 0 AND
 * resourceId == null}. Any other state means the pipe legitimately moved on (e.g. a rebuild's
 * write-back already re-split shares); restoring then would duplicate resource, so the snapshot is
 * discarded instead. Because events and tick systems share the world thread and the restore runs
 * before the first rebuild of the pass, a wiped pipe cannot be re-split between wipe and restore.
 *
 * <h2>Flow states (Task 5b: wrench config survives the wipe)</h2>
 * The wipe also resets a re-resolved pipe's 6 per-face {@link FlowState flow states} to all-NORMAL
 * (a fresh template clone), so a wrenched pipe near any place/break would silently factory-reset its
 * face config. Snapshots therefore carry the 6 faces too, and a wrench-only pipe (face config set but
 * {@code share == 0} / {@code resourceId == null}) is now snapshot-worthy even though it holds no
 * resource. Flow states restore on the SAME wipe signature as shares, with two extra guards so a stale
 * snapshot never stomps a re-wrenched pipe: an all-NORMAL snapshot carries nothing and is skipped
 * entirely, and a target face already non-NORMAL (a legitimate post-wipe re-wrench) is left untouched:
 * only faces still at their wiped NORMAL default are overwritten.
 *
 * <p>A snapshot whose position has no live pipe (chunk unloaded, or the pipe itself was removed) is
 * retried each pass until {@link #EXPIRY_TICKS} elapses, then dropped.
 *
 * <p>Pure logic: JDK + {@link PipeGridView}/{@link PipeNode} only; headless-tested.
 */
public final class PipeNodeSnapshots {

    /** How long (world ticks) an unapplied snapshot survives before being dropped. */
    static final long EXPIRY_TICKS = 200;

    /** Number of cube faces a pipe carries flow state for, indexed in {@code NetworkManager.OFFSETS} order. */
    private static final int FACE_COUNT = 6;

    /** {@code flowStates} may be null (no face config captured) or a 6-element array of (non-null) faces. */
    private record Snapshot(long share, String resourceId, FlowState[] flowStates, long tick) {
    }

    /** packed position key -> pending snapshot (see {@link NetworkManager#packKey}). */
    private final Map<Long, Snapshot> pending = new HashMap<>();

    /**
     * Records a pre-wipe snapshot for the pipe at {@code posKey} that carries no flow-state config.
     * Convenience overload of {@link #put(long, long, String, FlowState[], long)} with null faces.
     */
    public void put(long posKey, long share, String resourceId, long tick) {
        put(posKey, share, resourceId, null, tick);
    }

    /**
     * Records a pre-wipe snapshot for the pipe at {@code posKey}. A snapshot is worth keeping when it
     * carries a positive share OR any non-NORMAL face (a wrench-only pipe with no resource): otherwise
     * there is nothing the wipe could destroy and the call is ignored. A newer snapshot for the same
     * position replaces the older one.
     *
     * @param flowStates the pipe's 6 per-face flow states, or null when no face config was captured;
     *     a non-null array is defensively copied.
     */
    public void put(long posKey, long share, String resourceId, FlowState[] flowStates, long tick) {
        FlowState[] faces = normalizeFaces(flowStates);
        if (share <= 0 && faces == null) {
            return; // empty share and all-NORMAL (or absent) faces: nothing worth restoring.
        }
        pending.put(posKey, new Snapshot(share, resourceId, faces, tick));
    }

    /**
     * Returns a 6-element defensive copy of {@code flowStates} when at least one face is non-NORMAL, or
     * null when the input is null, too short, or entirely NORMAL (an all-NORMAL config carries nothing).
     */
    private static FlowState[] normalizeFaces(FlowState[] flowStates) {
        if (flowStates == null) {
            return null;
        }
        FlowState[] faces = new FlowState[FACE_COUNT];
        boolean anyNonNormal = false;
        for (int i = 0; i < FACE_COUNT; i++) {
            FlowState s = i < flowStates.length && flowStates[i] != null ? flowStates[i] : FlowState.NORMAL;
            faces[i] = s;
            if (s != FlowState.NORMAL) {
                anyNonNormal = true;
            }
        }
        return anyNonNormal ? faces : null;
    }

    /** Fast-path check so the per-tick caller can skip work when nothing is pending. */
    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /** Number of pending snapshots. Test/diagnostic accessor. */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * Attempts every pending snapshot against the live grid (see class doc for the guard rules).
     *
     * @return the packed position keys whose pipes were restored; the caller must invalidate each
     *     restored position's network so the next rebuild re-pools the restored shares.
     */
    public List<Long> restorePending(PipeGridView grid, long nowTick) {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Long> restored = new ArrayList<>();
        Iterator<Map.Entry<Long, Snapshot>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Snapshot> entry = it.next();
            Snapshot snap = entry.getValue();
            if (nowTick - snap.tick() > EXPIRY_TICKS) {
                it.remove();
                continue;
            }
            long key = entry.getKey();
            PipeNode pipe = grid.pipeAt(
                NetworkManager.unpackX(key), NetworkManager.unpackY(key), NetworkManager.unpackZ(key));
            if (pipe == null) {
                continue; // chunk unloaded or pipe gone: retry until expiry
            }
            if (pipe.bufferShare() == 0 && pipe.resourceId() == null) {
                // Wipe signature: re-apply the persisted share/resource...
                pipe.setBufferShare(snap.share());
                pipe.setResourceId(snap.resourceId());
                // ...and the per-face flow config, but never stomp a face the pipe was already
                // re-wrenched to (non-NORMAL): only faces still at their wiped NORMAL default are
                // overwritten. A null (all-NORMAL) snapshot carries nothing and is skipped.
                FlowState[] faces = snap.flowStates();
                if (faces != null) {
                    for (int face = 0; face < FACE_COUNT; face++) {
                        if (pipe.flowState(face) == FlowState.NORMAL) {
                            pipe.setFlowState(face, faces[face]);
                        }
                    }
                }
                restored.add(key);
            }
            it.remove(); // applied, or the pipe's state legitimately moved on: snapshot is spent
        }
        return restored;
    }
}
