package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class ResourceBufferTest {

    private static <T> T decode(Codec<T> c, String json) throws Exception {
        return c.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void insertLocksToFirstResource() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        assertEquals(300, b.insert("compound:water", 300, false));
        assertEquals("compound:water", b.resourceId());
        assertEquals(0, b.insert("element:oxygen", 100, false));
        assertEquals(300, b.amount());
    }

    @Test
    void insertClampsToCapacity() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        assertEquals(1000, b.insert("compound:water", 1500, false));
    }

    @Test
    void extractEmptyingUnlocks() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        b.insert("compound:water", 300, false);
        assertEquals(300, b.extract(500, false));
        assertNull(b.resourceId());
        assertEquals(200, b.insert("element:oxygen", 200, false));
    }

    @Test
    void simulateDoesNotMutate() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        assertEquals(1000, b.insert("compound:water", 1000, true));
        assertEquals(0, b.amount());
        assertNull(b.resourceId());
    }

    @Test
    void codecRoundTrips() throws Exception {
        ResourceBuffer b = decode(ResourceBuffer.CODEC,
            "{\"ResourceId\":\"compound:water\",\"Amount\":42,\"Capacity\":1000}");
        assertEquals("compound:water", b.resourceId());
        assertEquals(42, b.amount());
        assertEquals(1000, b.capacity());
    }

    @Test
    void codecRoundTripsEmptyUnlockedBuffer() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        assertNull(b.resourceId());
        assertEquals(0, b.amount());
        BsonValue encoded = ResourceBuffer.CODEC.encode(b, EmptyExtraInfo.EMPTY);
        ResourceBuffer decoded = ResourceBuffer.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertNull(decoded.resourceId());
        assertEquals(0, decoded.amount());
        assertEquals(1000, decoded.capacity());
    }

    @Test
    void codecRoundTripsViaEncode() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        b.insert("compound:water", 42, false);
        BsonValue encoded = ResourceBuffer.CODEC.encode(b, EmptyExtraInfo.EMPTY);
        ResourceBuffer decoded = ResourceBuffer.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertEquals("compound:water", decoded.resourceId());
        assertEquals(42, decoded.amount());
        assertEquals(1000, decoded.capacity());
    }
}
