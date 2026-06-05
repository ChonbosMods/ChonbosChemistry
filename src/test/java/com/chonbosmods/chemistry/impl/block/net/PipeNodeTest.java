package com.chonbosmods.chemistry.impl.block.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
