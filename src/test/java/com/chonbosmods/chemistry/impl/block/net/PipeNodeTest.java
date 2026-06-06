package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class PipeNodeTest {

    @Test
    void powerPipeRoundTripsWithNullResourceId() {
        PipeNode node = PipeNode.of(PortChannel.POWER, 2);
        node.setBufferShare(500);

        BsonValue encoded = PipeNode.CODEC.encode(node, EmptyExtraInfo.EMPTY);
        PipeNode decoded = PipeNode.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertEquals(PortChannel.POWER, decoded.channel());
        assertEquals(2, decoded.tier());
        assertEquals(500, decoded.bufferShare());
        assertNull(decoded.resourceId());
    }

    @Test
    void fluidPipeRoundTripsWithResourceId() {
        PipeNode node = PipeNode.of(PortChannel.FLUID, 3);
        node.setBufferShare(1200);
        node.setResourceId("oxygen");

        BsonValue encoded = PipeNode.CODEC.encode(node, EmptyExtraInfo.EMPTY);
        PipeNode decoded = PipeNode.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertEquals(PortChannel.FLUID, decoded.channel());
        assertEquals(3, decoded.tier());
        assertEquals(1200, decoded.bufferShare());
        assertEquals("oxygen", decoded.resourceId());
    }

    @Test
    void absentResourceIdDecodesToNull() {
        // Build a full FLUID doc, then strip ResourceId to mimic a legacy/power pipe document.
        PipeNode node = PipeNode.of(PortChannel.FLUID, 1);
        node.setBufferShare(42);
        node.setResourceId("argon");
        BsonDocument doc = PipeNode.CODEC.encode(node, EmptyExtraInfo.EMPTY).asDocument();
        doc.remove("ResourceId");
        assertTrue(doc.containsKey("Channel") && doc.containsKey("Tier"),
            "precondition: doc still carries Channel + Tier");

        PipeNode decoded = PipeNode.CODEC.decode(doc, EmptyExtraInfo.EMPTY);

        assertNull(decoded.resourceId(), "missing ResourceId -> null");
        assertEquals(PortChannel.FLUID, decoded.channel());
        assertEquals(42, decoded.bufferShare());
    }

    @Test
    void cloneProducesIndependentCopy() {
        PipeNode original = PipeNode.of(PortChannel.POWER, 2);
        original.setBufferShare(500);

        PipeNode copy = (PipeNode) original.clone();
        assertEquals(500, copy.bufferShare());

        original.setBufferShare(900);

        assertEquals(900, original.bufferShare());
        assertEquals(500, copy.bufferShare(), "clone is independent of the original");
    }

    @Test
    void flowStatesDefaultToNormalAndAbsentKeyDecodesAsAllNormal() {
        PipeNode node = PipeNode.of(PortChannel.POWER, 1);
        for (int i = 0; i < 6; i++) {
            assertEquals(FlowState.NORMAL, node.flowState(i), "fresh node face " + i + " defaults NORMAL");
        }

        // Encode a default node: the all-NORMAL state must OMIT the key (no-migration guarantee).
        BsonDocument doc = PipeNode.CODEC.encode(node, EmptyExtraInfo.EMPTY).asDocument();
        assertTrue(!doc.containsKey("FlowStates"),
            "all-NORMAL node must omit the FlowStates key so existing saves stay byte-identical");

        // Decode that key-absent doc (mimics an existing world save): all faces still NORMAL.
        PipeNode decoded = PipeNode.CODEC.decode(doc, EmptyExtraInfo.EMPTY);
        for (int i = 0; i < 6; i++) {
            assertEquals(FlowState.NORMAL, decoded.flowState(i), "absent key -> face " + i + " NORMAL");
        }
    }

    @Test
    void flowStatesRoundTripThroughCodec() {
        PipeNode node = PipeNode.of(PortChannel.FLUID, 1);
        node.setFlowState(2, FlowState.PUSH);
        node.setFlowState(5, FlowState.NONE);

        BsonValue encoded = PipeNode.CODEC.encode(node, EmptyExtraInfo.EMPTY);
        PipeNode decoded = PipeNode.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertEquals(FlowState.PUSH, decoded.flowState(2));
        assertEquals(FlowState.NONE, decoded.flowState(5));
        assertEquals(FlowState.NORMAL, decoded.flowState(0));
        assertEquals(FlowState.NORMAL, decoded.flowState(1));
        assertEquals(FlowState.NORMAL, decoded.flowState(3));
        assertEquals(FlowState.NORMAL, decoded.flowState(4));
    }

    @Test
    void cloneCopiesFlowStatesIndependently() {
        PipeNode original = PipeNode.of(PortChannel.FLUID, 1);
        original.setFlowState(0, FlowState.PUSH);

        PipeNode copy = (PipeNode) original.clone();
        assertEquals(FlowState.PUSH, copy.flowState(0));

        original.setFlowState(0, FlowState.PULL);

        assertEquals(FlowState.PULL, original.flowState(0));
        assertEquals(FlowState.PUSH, copy.flowState(0), "clone's flow states are independent of the original");
    }
}
