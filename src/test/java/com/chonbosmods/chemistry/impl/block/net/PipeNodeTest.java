package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.net.item.TravelingStack;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
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
    void malformedFlowStatesArrayDecodesDefensively() {
        // Hand-build a doc with a SHORT FlowStates array (2 entries): missing faces fall back to
        // NORMAL and decode never throws (hot ECS data: the clamp logic must hold under bad input).
        PipeNode source = PipeNode.of(PortChannel.GAS, 1);
        source.setFlowState(0, FlowState.PUSH); // force the key to be present
        BsonDocument doc = PipeNode.CODEC.encode(source, EmptyExtraInfo.EMPTY).asDocument();
        org.bson.BsonArray shortArray = new org.bson.BsonArray();
        shortArray.add(new org.bson.BsonString("none"));
        shortArray.add(new org.bson.BsonString("pull"));
        doc.put("FlowStates", shortArray);

        PipeNode decoded = PipeNode.CODEC.decode(doc, EmptyExtraInfo.EMPTY);

        assertEquals(FlowState.NONE, decoded.flowState(0));
        assertEquals(FlowState.PULL, decoded.flowState(1));
        for (int i = 2; i < 6; i++) {
            assertEquals(FlowState.NORMAL, decoded.flowState(i), "missing face " + i + " -> NORMAL");
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
    void absentInTransitKeyDecodesToEmptyListAndDefaultNodeOmitsKey() {
        PipeNode node = PipeNode.of(PortChannel.ITEM, 1);
        assertTrue(node.inTransit().isEmpty(), "fresh node carries no in-transit stacks");

        // Encode a default node: an empty in-transit list must OMIT the key (old saves byte-identical).
        BsonDocument doc = PipeNode.CODEC.encode(node, EmptyExtraInfo.EMPTY).asDocument();
        assertFalse(doc.containsKey("InTransit"),
            "empty in-transit list must omit the InTransit key so existing saves stay byte-identical");

        // Decode that key-absent doc (mimics an existing world save): still an empty list.
        PipeNode decoded = PipeNode.CODEC.decode(doc, EmptyExtraInfo.EMPTY);
        assertTrue(decoded.inTransit().isEmpty(), "absent InTransit key -> empty list");
    }

    @Test
    void pipeNodeRoundTripsCarryingTwoStacks() {
        PipeNode node = PipeNode.of(PortChannel.ITEM, 1);
        BsonDocument meta = new BsonDocument();
        meta.put("Durability", new BsonInt32(7));
        TravelingStack a = TravelingStack.of("hytale:iron", 4, meta, new long[]{1L, 2L, 3L}, 1L, 3L);
        a.setSegmentIndex(2);
        a.setProgressTicks(1);
        TravelingStack b = TravelingStack.of("hytale:stone", 9, null, new long[]{5L, 6L}, 5L, 6L);
        node.addInTransit(a);
        node.addInTransit(b);

        BsonValue encoded = PipeNode.CODEC.encode(node, EmptyExtraInfo.EMPTY);
        PipeNode decoded = PipeNode.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        List<TravelingStack> stacks = decoded.inTransit();
        assertEquals(2, stacks.size(), "both stacks survive the round-trip");

        TravelingStack da = stacks.get(0);
        assertEquals("hytale:iron", da.id());
        assertEquals(4, da.count());
        assertEquals(2, da.segmentIndex());
        assertEquals(1, da.progressTicks());
        assertEquals(1L, da.originKey());
        assertEquals(3L, da.destKey());
        assertEquals(meta, da.metadata());

        TravelingStack db = stacks.get(1);
        assertEquals("hytale:stone", db.id());
        assertEquals(9, db.count());
        assertNull(db.metadata());
    }

    @Test
    void cloneCopiesInTransitStacksIndependently() {
        PipeNode original = PipeNode.of(PortChannel.ITEM, 1);
        TravelingStack stack = TravelingStack.of("hytale:iron", 1, null, new long[]{1L, 2L}, 1L, 2L);
        stack.setProgressTicks(3);
        original.addInTransit(stack);

        PipeNode copy = (PipeNode) original.clone();
        assertEquals(1, copy.inTransit().size());
        assertEquals(3, copy.inTransit().get(0).progressTicks());

        // Mutate the ORIGINAL's stack progress: the clone's stack must be unaffected.
        original.inTransit().get(0).setProgressTicks(99);

        assertEquals(3, copy.inTransit().get(0).progressTicks(),
            "clone's in-transit stacks are independent of the original");
    }

    @Test
    void removeInTransitUsesIdentityNotEquals() {
        PipeNode node = PipeNode.of(PortChannel.ITEM, 1);
        // Two stacks with IDENTICAL field values but distinct identities.
        TravelingStack a = TravelingStack.of("hytale:iron", 1, null, new long[]{1L}, 0L, 0L);
        TravelingStack b = TravelingStack.of("hytale:iron", 1, null, new long[]{1L}, 0L, 0L);
        node.addInTransit(a);
        node.addInTransit(b);

        node.removeInTransit(a);

        assertEquals(1, node.inTransit().size(), "identity removal drops only the same object");
        assertSame(b, node.inTransit().get(0), "the OTHER value-equal stack survives (identity, not equals)");
    }

    @Test
    void inTransitAccessorReturnsDefensiveCopy() {
        PipeNode node = PipeNode.of(PortChannel.ITEM, 1);
        node.addInTransit(TravelingStack.of("hytale:iron", 1, null, new long[]{1L}, 0L, 0L));

        List<TravelingStack> view = node.inTransit();
        view.clear(); // mutating the returned list must not affect the node

        assertEquals(1, node.inTransit().size(), "inTransit() returns a defensive copy; node state is protected");
    }

    @Test
    void malformedInTransitEntriesAreDroppedDefensively() {
        // One valid stack + one garbage/incomplete entry in the array: decode must drop the bad one and
        // never throw (hot ECS data: a corrupt save must not crash chunk load).
        PipeNode node = PipeNode.of(PortChannel.ITEM, 1);
        node.addInTransit(TravelingStack.of("hytale:iron", 4, null, new long[]{1L, 2L}, 1L, 2L));
        BsonDocument doc = PipeNode.CODEC.encode(node, EmptyExtraInfo.EMPTY).asDocument();

        BsonArray list = doc.getArray("InTransit");
        // A garbage scalar entry (not a document at all).
        list.add(new BsonString("garbage"));
        // An incomplete document missing required keys.
        BsonDocument incomplete = new BsonDocument();
        incomplete.put("Id", new BsonString("hytale:partial"));
        list.add(incomplete);
        doc.put("InTransit", list);

        PipeNode decoded = PipeNode.CODEC.decode(doc, EmptyExtraInfo.EMPTY);

        List<TravelingStack> stacks = decoded.inTransit();
        assertEquals(1, stacks.size(), "malformed entries dropped; only the valid stack survives");
        assertEquals("hytale:iron", stacks.get(0).id());
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
