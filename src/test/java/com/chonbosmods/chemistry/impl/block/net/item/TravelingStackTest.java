package com.chonbosmods.chemistry.impl.block.net.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class TravelingStackTest {

    @Test
    void roundTripsWithMetadataAndMultiSegmentPath() {
        BsonDocument meta = new BsonDocument();
        meta.put("Durability", new BsonInt32(42));
        meta.put("Owner", new BsonString("keroppi"));
        long[] path = {100L, 200L, 300L};
        TravelingStack stack = TravelingStack.of("hytale:iron_ingot", 7, meta, path, 5L, 9L);
        stack.setSegmentIndex(1);
        stack.setProgressTicks(3);

        BsonValue encoded = TravelingStack.CODEC.encode(stack, EmptyExtraInfo.EMPTY);
        TravelingStack decoded = TravelingStack.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertEquals("hytale:iron_ingot", decoded.id());
        assertEquals(7, decoded.count());
        assertArrayEquals(path, decoded.path());
        assertEquals(1, decoded.segmentIndex());
        assertEquals(3, decoded.progressTicks());
        assertEquals(5L, decoded.originKey());
        assertEquals(9L, decoded.destKey());
        assertEquals(meta, decoded.metadata());
    }

    @Test
    void nullableMetadataAbsentStaysNull() {
        TravelingStack stack = TravelingStack.of("hytale:stone", 1, null, new long[]{1L, 2L}, 0L, 1L);

        BsonValue encoded = TravelingStack.CODEC.encode(stack, EmptyExtraInfo.EMPTY);
        TravelingStack decoded = TravelingStack.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);

        assertNull(decoded.metadata(), "absent metadata stays null (opaque, never synthesized)");
        assertArrayEquals(new long[]{1L, 2L}, decoded.path());
    }

    @Test
    void absentMetadataKeyOmittedFromDocument() {
        TravelingStack stack = TravelingStack.of("hytale:stone", 1, null, new long[]{1L}, 0L, 0L);
        BsonDocument doc = TravelingStack.CODEC.encode(stack, EmptyExtraInfo.EMPTY).asDocument();
        // Metadata is OPTIONAL: a null doc omits the key entirely (the FlowStates omission pattern).
        org.junit.jupiter.api.Assertions.assertTrue(!doc.containsKey("Metadata"),
            "null metadata must omit the Metadata key");
    }

    @Test
    void keyExposesIdAndCountForRoutingLayers() {
        TravelingStack stack = TravelingStack.of("hytale:coal", 12, null, new long[]{1L}, 0L, 0L);
        ItemKey key = stack.key();
        assertEquals("hytale:coal", key.id());
        assertEquals(12, key.count());
    }

    @Test
    void cloneDeepCopiesMetadataAndPath() {
        BsonDocument meta = new BsonDocument();
        meta.put("Durability", new BsonInt32(10));
        TravelingStack original = TravelingStack.of("hytale:sword", 1, meta, new long[]{1L, 2L}, 0L, 2L);

        TravelingStack copy = original.copy();

        assertNotSame(original.metadata(), copy.metadata(), "metadata is deep-copied, not shared");
        assertNotSame(original.path(), copy.path(), "path array is copied, not shared");

        // Mutate the original's live progress + metadata: the copy must be untouched.
        original.setProgressTicks(99);
        original.metadata().put("Durability", new BsonInt32(0));

        assertEquals(0, copy.progressTicks());
        assertEquals(new BsonInt32(10), copy.metadata().get("Durability"));
    }
}
