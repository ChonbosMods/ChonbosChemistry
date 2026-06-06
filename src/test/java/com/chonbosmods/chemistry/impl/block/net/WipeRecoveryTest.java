package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.*;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Headless tests for the H8 wipe-recovery pair: {@link PipeNodeSnapshots} (snapshot/restore around
 * the engine's connected-block block-entity wipe) and {@link NetworkManager}'s two-pass rebuild
 * pooling (a type-locked share whose resourceId was wiped re-pools under a sibling's lock instead
 * of being silently dropped).
 */
class WipeRecoveryTest {

    /** A fake grid backed by a {@code Map<packedKey, PipeNode>}: a position with no entry has no pipe. */
    private static final class FakePipeGrid implements PipeGridView {
        private final Map<Long, PipeNode> pipes = new HashMap<>();

        FakePipeGrid put(int x, int y, int z, PipeNode node) {
            pipes.put(NetworkManager.packKey(x, y, z), node);
            return this;
        }

        @Override
        public PipeNode pipeAt(int x, int y, int z) {
            return pipes.get(NetworkManager.packKey(x, y, z));
        }
    }

    private static PipeNode fluid(int tier) {
        return PipeNode.of(PortChannel.FLUID, tier);
    }

    // --- PipeNodeSnapshots ---

    @Test
    void restoresWipedPipeAndReportsItsPosition() {
        PipeNode pipe = fluid(1); // wiped state: share 0, resourceId null (the template default)
        FakePipeGrid grid = new FakePipeGrid().put(5, 60, 5, pipe);
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(5, 60, 5), 120, "element:bromine", 10);

        List<Long> restored = snaps.restorePending(grid, 11);

        assertEquals(List.of(NetworkManager.packKey(5, 60, 5)), restored);
        assertEquals(120, pipe.bufferShare());
        assertEquals("element:bromine", pipe.resourceId());
        assertTrue(snaps.isEmpty(), "applied snapshot must be drained");
    }

    @Test
    void doesNotTouchAPipeWhoseStateMovedOn() {
        PipeNode pipe = fluid(1);
        pipe.setBufferShare(7); // a rebuild's write-back already re-split shares: NOT the wipe signature
        pipe.setResourceId("compound:ethanol");
        FakePipeGrid grid = new FakePipeGrid().put(0, 60, 0, pipe);
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(0, 60, 0), 500, "element:bromine", 10);

        List<Long> restored = snaps.restorePending(grid, 11);

        assertTrue(restored.isEmpty());
        assertEquals(7, pipe.bufferShare(), "non-wiped pipe must not be overwritten (no duplication)");
        assertEquals("compound:ethanol", pipe.resourceId());
        assertTrue(snaps.isEmpty(), "spent snapshot must be drained even when not applied");
    }

    @Test
    void missingPipeIsRetriedUntilExpiry() {
        FakePipeGrid emptyGrid = new FakePipeGrid();
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(1, 60, 1), 50, null, 100);

        assertTrue(snaps.restorePending(emptyGrid, 101).isEmpty());
        assertEquals(1, snaps.pendingCount(), "unloaded position is retried, not dropped");

        assertTrue(snaps.restorePending(emptyGrid, 100 + PipeNodeSnapshots.EXPIRY_TICKS + 1).isEmpty());
        assertEquals(0, snaps.pendingCount(), "expired snapshot is dropped");
    }

    @Test
    void zeroShareSnapshotsAreIgnored() {
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(2, 60, 2), 0, "element:bromine", 1);
        snaps.put(NetworkManager.packKey(3, 60, 3), -5, null, 1);
        assertTrue(snaps.isEmpty());
    }

    @Test
    void newerSnapshotReplacesOlderForSamePosition() {
        PipeNode pipe = fluid(1);
        FakePipeGrid grid = new FakePipeGrid().put(4, 60, 4, pipe);
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        long key = NetworkManager.packKey(4, 60, 4);
        snaps.put(key, 10, "element:bromine", 1);
        snaps.put(key, 25, "element:bromine", 2);

        snaps.restorePending(grid, 3);

        assertEquals(25, pipe.bufferShare());
    }

    // --- PipeNodeSnapshots flow-state recovery (Task 5b) ---

    /** A 6-element all-NORMAL flow-state array with the given faces overridden. */
    private static FlowState[] faces(Map<Integer, FlowState> overrides) {
        FlowState[] f = new FlowState[6];
        java.util.Arrays.fill(f, FlowState.NORMAL);
        overrides.forEach((i, s) -> f[i] = s);
        return f;
    }

    @Test
    void restoresWipedPipeFlowStatesEvenWithZeroShare() {
        // A wrenched-but-empty pipe: face 0 NONE, share 0, resourceId null. The wipe gave it a fresh
        // all-NORMAL clone (share 0, id null): exactly the wipe signature, so the face config restores.
        PipeNode pipe = fluid(1); // wiped: all-NORMAL, share 0, id null
        FakePipeGrid grid = new FakePipeGrid().put(5, 60, 5, pipe);
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(5, 60, 5), 0, null, faces(Map.of(0, FlowState.NONE)), 10);

        List<Long> restored = snaps.restorePending(grid, 11);

        assertEquals(List.of(NetworkManager.packKey(5, 60, 5)), restored,
            "a wrenched empty pipe matching the wipe signature must be restored and reported");
        assertEquals(FlowState.NONE, pipe.flowState(0));
        assertEquals(FlowState.NORMAL, pipe.flowState(1));
        assertTrue(snaps.isEmpty(), "applied snapshot must be drained");
    }

    @Test
    void restoresFlowStatesAlongsideShareAndResource() {
        PipeNode pipe = fluid(1); // wiped: all-NORMAL, share 0, id null
        FakePipeGrid grid = new FakePipeGrid().put(6, 60, 6, pipe);
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(6, 60, 6), 120, "element:bromine",
            faces(Map.of(2, FlowState.PUSH, 3, FlowState.PULL)), 10);

        snaps.restorePending(grid, 11);

        assertEquals(120, pipe.bufferShare());
        assertEquals("element:bromine", pipe.resourceId());
        assertEquals(FlowState.PUSH, pipe.flowState(2));
        assertEquals(FlowState.PULL, pipe.flowState(3));
    }

    @Test
    void allNormalSnapshotIsNeverStored() {
        // A snapshot whose faces are all NORMAL (and share 0) carries nothing: it must be ignored at put
        // time so it can never later stomp a re-wrenched pipe.
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(7, 60, 7), 0, null, faces(Map.of()), 10);
        assertTrue(snaps.isEmpty(), "an all-NORMAL, empty-share snapshot carries nothing");
    }

    @Test
    void restoreNeverStompsAFaceTheTargetWasReWrenchedTo() {
        // Snapshot says face 0 NONE. After the wipe, the target matches the wipe SHARE signature (share
        // 0, id null) but was legitimately re-wrenched to face 0 PUSH. The restore must leave PUSH: only
        // faces still at the wiped NORMAL default get the snapshot's value.
        PipeNode pipe = fluid(1);
        pipe.setFlowState(0, FlowState.PUSH); // re-wrenched after the wipe
        FakePipeGrid grid = new FakePipeGrid().put(7, 60, 7, pipe);
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(7, 60, 7), 0, null,
            faces(Map.of(0, FlowState.NONE, 3, FlowState.PULL)), 10);

        snaps.restorePending(grid, 11);

        assertEquals(FlowState.PUSH, pipe.flowState(0),
            "a re-wrenched non-NORMAL face must never be stomped by a stale snapshot");
        assertEquals(FlowState.PULL, pipe.flowState(3),
            "a face still at the wiped NORMAL default is restored from the snapshot");
    }

    @Test
    void flowStatesNotRestoredWhenShareSignatureMovedOn() {
        // The pipe's share state moved on (share 7): not the wipe signature, so neither share nor face
        // config is restored (the pipe is live and authoritative).
        PipeNode pipe = fluid(1);
        pipe.setBufferShare(7);
        pipe.setResourceId("compound:ethanol");
        FakePipeGrid grid = new FakePipeGrid().put(8, 60, 8, pipe);
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(8, 60, 8), 0, null, faces(Map.of(0, FlowState.NONE)), 10);

        List<Long> restored = snaps.restorePending(grid, 11);

        assertTrue(restored.isEmpty());
        assertEquals(FlowState.NORMAL, pipe.flowState(0),
            "a pipe past the wipe signature must keep its live faces");
    }

    // --- PipeSnapshotScan worthiness widening (Task 5b) ---

    @Test
    void wrenchedEmptyPipeIsSnapshotWorthy() {
        // share 0 + id null, but face 0 NONE: previously skipped (nothing to save); now worth saving.
        PipeNode pipe = fluid(1);
        pipe.setFlowState(0, FlowState.NONE);
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(9, 60, 9), pipe.bufferShare(), pipe.resourceId(),
            snapshotFaces(pipe), 5);
        assertEquals(1, snaps.pendingCount(),
            "a wrenched empty pipe (any non-NORMAL face) must be snapshot-worthy");
    }

    @Test
    void emptyAllNormalPipeIsNotSnapshotWorthy() {
        // share 0, id null, all faces NORMAL: nothing to save, must still be ignored.
        PipeNode pipe = fluid(1);
        PipeNodeSnapshots snaps = new PipeNodeSnapshots();
        snaps.put(NetworkManager.packKey(10, 60, 10), pipe.bufferShare(), pipe.resourceId(),
            snapshotFaces(pipe), 5);
        assertTrue(snaps.isEmpty(), "an empty all-NORMAL pipe carries nothing worth restoring");
    }

    /** Reads a pipe's 6 faces into a fresh array, mirroring what PipeSnapshotScan captures. */
    private static FlowState[] snapshotFaces(PipeNode pipe) {
        FlowState[] f = new FlowState[6];
        for (int i = 0; i < 6; i++) {
            f[i] = pipe.flowState(i);
        }
        return f;
    }

    // --- NetworkManager two-pass rebuild pooling ---

    @Test
    void rebuildPoolsWipedNullIdShareUnderSiblingLock() {
        PipeNode knows = fluid(1);
        knows.setBufferShare(100);
        knows.setResourceId("element:bromine");
        PipeNode wiped = fluid(1);
        wiped.setBufferShare(40); // restored share whose id... or a share written before a partial wipe
        wiped.setResourceId(null);

        FakePipeGrid grid = new FakePipeGrid().put(0, 60, 0, knows).put(1, 60, 0, wiped);
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(0, 60, 0, grid);

        assertNotNull(net);
        assertEquals(140, net.stored(), "null-id share must re-pool under the sibling's lock");
        assertEquals("element:bromine", net.lockedResourceId());
    }

    @Test
    void rebuildDropsNullIdSharesWhenNoMemberKnowsTheResource() {
        PipeNode a = fluid(1);
        a.setBufferShare(30);
        PipeNode b = fluid(1);
        b.setBufferShare(20);

        FakePipeGrid grid = new FakePipeGrid().put(0, 60, 0, a).put(1, 60, 0, b);
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(0, 60, 0, grid);

        assertNotNull(net);
        assertEquals(0, net.stored(), "unknowable fluid shares stay dropped (documented)");
        assertNull(net.lockedResourceId());
    }

    @Test
    void powerRebuildStillPoolsNullIdShares() {
        PipeNode a = PipeNode.of(PortChannel.POWER, 1);
        a.setBufferShare(30);
        PipeNode b = PipeNode.of(PortChannel.POWER, 1);
        b.setBufferShare(20);

        FakePipeGrid grid = new FakePipeGrid().put(0, 60, 0, a).put(1, 60, 0, b);
        NetworkManager manager = new NetworkManager();
        Network net = manager.getOrBuildNetwork(0, 60, 0, grid);

        assertNotNull(net);
        assertEquals(50, net.stored());
        assertNull(net.lockedResourceId());
    }
}
