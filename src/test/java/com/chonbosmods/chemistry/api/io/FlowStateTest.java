package com.chonbosmods.chemistry.api.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class FlowStateTest {

    @Test
    void hasFourValues() {
        assertEquals(4, FlowState.values().length);
        // Named values exist.
        FlowState[] expected = {FlowState.NORMAL, FlowState.PUSH, FlowState.PULL, FlowState.NONE};
        assertEquals(4, expected.length);
    }

    @Test
    void codecRoundTripsEachValue() {
        for (FlowState value : FlowState.values()) {
            BsonValue encoded = FlowState.CODEC.encode(value, EmptyExtraInfo.EMPTY);
            FlowState decoded = FlowState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
            assertEquals(value, decoded);
        }
    }

    @Test
    void encodesToLowercaseName() {
        assertEquals("normal", FlowState.CODEC.encode(FlowState.NORMAL, EmptyExtraInfo.EMPTY).asString().getValue());
        assertEquals("push", FlowState.CODEC.encode(FlowState.PUSH, EmptyExtraInfo.EMPTY).asString().getValue());
        assertEquals("pull", FlowState.CODEC.encode(FlowState.PULL, EmptyExtraInfo.EMPTY).asString().getValue());
        assertEquals("none", FlowState.CODEC.encode(FlowState.NONE, EmptyExtraInfo.EMPTY).asString().getValue());
    }
}
