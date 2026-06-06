package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Task 4 coverage: the network BFS must honor the single {@link PipeConnectivity#connects} gate
 * (flow states + substance compatibility + channel), not just the bare same-channel check. A
 * {@link FlowState#NONE NONE} face severs a run, and two pipes locked to DIFFERENT substances must
 * not merge into one network (the in-game gas bug).
 */
class NetworkSplitTest {

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

    private static PipeNode fluid() {
        return PipeNode.of(PortChannel.FLUID, 0);
    }

    /** A FLUID pipe locked to {@code resourceId} with a positive persisted share (locks on rebuild). */
    private static PipeNode lockedFluid(String resourceId, long share) {
        PipeNode p = PipeNode.of(PortChannel.FLUID, 0);
        p.setResourceId(resourceId);
        p.setBufferShare(share);
        return p;
    }

    @Test
    void noneFaceSplitsRun() {
        // 3-pipe row; middle pipe's +X face (toward (2,0,0)) is NONE.
        PipeNode middle = fluid();
        middle.setFlowState(0, FlowState.NONE); // +X
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, fluid())
            .put(1, 0, 0, middle)
            .put(2, 0, 0, fluid());
        NetworkManager mgr = new NetworkManager();

        Network left = mgr.getOrBuildNetwork(0, 0, 0, grid);
        assertEquals(2, left.memberKeys().size(), "left run is (0,0,0)+(1,0,0)");

        Network right = mgr.getOrBuildNetwork(2, 0, 0, grid);
        assertEquals(1, right.memberKeys().size(), "right pipe is isolated by the NONE face");
    }

    @Test
    void differentSubstancesDoNotMerge() {
        // Two 2-pipe FLUID runs in a row: left pair bromine, right pair ethanol. Positive shares so the
        // rebuilt networks pool and lock to their substance.
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, lockedFluid("element:bromine", 3))
            .put(1, 0, 0, lockedFluid("element:bromine", 3))
            .put(2, 0, 0, lockedFluid("compound:ethanol", 5))
            .put(3, 0, 0, lockedFluid("compound:ethanol", 5));
        NetworkManager mgr = new NetworkManager();

        Network bromine = mgr.getOrBuildNetwork(0, 0, 0, grid);
        assertEquals(2, bromine.memberKeys().size(), "bromine run does not absorb the ethanol pipes");
        assertEquals("element:bromine", bromine.lockedResourceId());

        Network ethanol = mgr.getOrBuildNetwork(3, 0, 0, grid);
        assertEquals(2, ethanol.memberKeys().size(), "ethanol run does not absorb the bromine pipes");
        assertEquals("compound:ethanol", ethanol.lockedResourceId());

        assertNotEquals(mgr.anchorOf(0, 0, 0), mgr.anchorOf(3, 0, 0), "two SEPARATE networks");
    }

    @Test
    void emptyLineJoinsLockedLine() {
        // Left pair locked to bromine (ids + shares); right pair empty (null ids, 0 shares). An empty
        // line joins a locked one: ONE 4-member network locked to the left substance.
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, lockedFluid("element:bromine", 3))
            .put(1, 0, 0, lockedFluid("element:bromine", 3))
            .put(2, 0, 0, fluid())
            .put(3, 0, 0, fluid());
        NetworkManager mgr = new NetworkManager();

        Network net = mgr.getOrBuildNetwork(3, 0, 0, grid);
        assertEquals(4, net.memberKeys().size(), "empty line joins the locked line into one network");
        assertEquals("element:bromine", net.lockedResourceId(), "lock is the left substance");
        assertEquals(6, net.stored(), "pooled the left pair's shares");
    }

    @Test
    void ringWithOneSeveredEdgeKeepsAllMembers() {
        // 2x2 POWER ring: A(0,0,0) B(1,0,0) C(1,0,1) D(0,0,1). Sever ONLY A's +X face (index 0)
        // toward B. B stays reachable via A-D-C-B, so the network must still have all 4 members.
        // Regression: a visited-before-gate BFS marks B visited when the severed A->B edge is
        // checked, then can never enqueue B via the valid alternate path: it drops to 3 members.
        PipeNode a = PipeNode.of(PortChannel.POWER, 0);
        a.setFlowState(0, FlowState.NONE); // A's +X face (toward B) severed
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, a)
            .put(1, 0, 0, PipeNode.of(PortChannel.POWER, 0)) // B
            .put(1, 0, 1, PipeNode.of(PortChannel.POWER, 0)) // C
            .put(0, 0, 1, PipeNode.of(PortChannel.POWER, 0)); // D
        NetworkManager mgr = new NetworkManager();

        Network net = mgr.getOrBuildNetwork(0, 0, 0, grid);
        assertEquals(4, net.memberKeys().size(),
            "B is loop-reachable via A-D-C-B even though A's +X edge is severed");

        // Symmetric: B resolves to the same single network (it is not dropped/isolated).
        assertEquals(mgr.anchorOf(0, 0, 0), mgr.anchorOf(1, 0, 0), "B belongs to the same network");
    }

    @Test
    void oppositeFaceNoneAlsoSplits() {
        // NONE on the RECEIVING side: the right pipe's -X face (index 1, toward (1,0,0)). Same split.
        PipeNode right = fluid();
        right.setFlowState(1, FlowState.NONE); // -X
        FakePipeGrid grid = new FakePipeGrid()
            .put(0, 0, 0, fluid())
            .put(1, 0, 0, fluid())
            .put(2, 0, 0, right);
        NetworkManager mgr = new NetworkManager();

        Network left = mgr.getOrBuildNetwork(0, 0, 0, grid);
        assertEquals(2, left.memberKeys().size(), "left run is (0,0,0)+(1,0,0)");

        Network rightNet = mgr.getOrBuildNetwork(2, 0, 0, grid);
        assertEquals(1, rightNet.memberKeys().size(), "right pipe isolated by its own -X NONE face");
    }
}
