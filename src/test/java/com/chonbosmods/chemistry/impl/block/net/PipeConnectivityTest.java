package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import org.junit.jupiter.api.Test;

/**
 * The single pipe-pipe connection gate (2026-06-05 pipe flow-states design §1). Two adjacent
 * same-channel pipes connect across a face iff neither facing flow state is NONE AND their
 * substances are compatible (either side's {@code resourceId} null, or the two equal). PUSH/PULL on
 * a pipe-pipe face behave as NORMAL: they are endpoint (pipe-machine) semantics only.
 */
class PipeConnectivityTest {

    /** Face +X (index 0) from a toward b; b sees a through opposite face -X (index 1). */
    private static final int FACE_PLUS_X = 0;

    private static PipeNode pipe() {
        return PipeNode.of(PortChannel.FLUID, 1);
    }

    @Test
    void bothNormalBothNullConnects() {
        assertTrue(PipeConnectivity.connects(pipe(), FACE_PLUS_X, pipe()));
    }

    @Test
    void bothNormalSameResourceConnects() {
        PipeNode a = pipe();
        a.setResourceId("hydrogen");
        PipeNode b = pipe();
        b.setResourceId("hydrogen");
        assertTrue(PipeConnectivity.connects(a, FACE_PLUS_X, b));
    }

    @Test
    void emptyLineJoinsLockedOne() {
        // a locked to hydrogen, b empty (null): the empty line joins the locked one.
        PipeNode a = pipe();
        a.setResourceId("hydrogen");
        PipeNode b = pipe();
        assertTrue(PipeConnectivity.connects(a, FACE_PLUS_X, b));
        // symmetric: empty a, locked b
        assertTrue(PipeConnectivity.connects(pipe(), FACE_PLUS_X, locked("hydrogen")));
    }

    @Test
    void differentSubstanceDoesNotConnect() {
        // The gas bug: two pipes locked to different substances must not merge.
        assertFalse(PipeConnectivity.connects(locked("hydrogen"), FACE_PLUS_X, locked("carbon_dioxide")));
    }

    @Test
    void aFaceNoneNeverConnects() {
        // Even with identical substance, a's NONE face severs the connection.
        PipeNode a = locked("hydrogen");
        a.setFlowState(FACE_PLUS_X, FlowState.NONE);
        assertFalse(PipeConnectivity.connects(a, FACE_PLUS_X, locked("hydrogen")));
    }

    @Test
    void bOppositeFaceNoneNeverConnects() {
        // b sees a through the opposite face (FACE_PLUS_X ^ 1); NONE there severs too.
        PipeNode b = locked("hydrogen");
        b.setFlowState(PipeConnectivity.opposite(FACE_PLUS_X), FlowState.NONE);
        assertFalse(PipeConnectivity.connects(locked("hydrogen"), FACE_PLUS_X, b));
    }

    @Test
    void pushPullBehaveAsNormalOnPipePipeFace() {
        PipeNode a = pipe();
        a.setFlowState(FACE_PLUS_X, FlowState.PUSH);
        PipeNode b = pipe();
        b.setFlowState(PipeConnectivity.opposite(FACE_PLUS_X), FlowState.PULL);
        assertTrue(PipeConnectivity.connects(a, FACE_PLUS_X, b));
    }

    @Test
    void differentChannelDoesNotConnect() {
        PipeNode a = PipeNode.of(PortChannel.FLUID, 1);
        PipeNode b = PipeNode.of(PortChannel.GAS, 1);
        assertFalse(PipeConnectivity.connects(a, FACE_PLUS_X, b));
    }

    @Test
    void nullNodesDoNotConnect() {
        assertFalse(PipeConnectivity.connects(null, FACE_PLUS_X, pipe()));
        assertFalse(PipeConnectivity.connects(pipe(), FACE_PLUS_X, null));
    }

    @Test
    void oppositePairsParedDirections() {
        assertEquals(1, PipeConnectivity.opposite(0));
        assertEquals(0, PipeConnectivity.opposite(1));
        assertEquals(3, PipeConnectivity.opposite(2));
        assertEquals(2, PipeConnectivity.opposite(3));
        assertEquals(5, PipeConnectivity.opposite(4));
        assertEquals(4, PipeConnectivity.opposite(5));
    }

    private static PipeNode locked(String resourceId) {
        PipeNode p = pipe();
        p.setResourceId(resourceId);
        return p;
    }
}
