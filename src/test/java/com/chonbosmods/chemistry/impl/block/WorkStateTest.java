package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.codec.EmptyExtraInfo;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class WorkStateTest {

    @Test
    void completesWhenProgressExceedsDuration() {
        WorkState w = new WorkState();
        assertFalse(w.advance(1.0f, 2.0f, true)); // 1.0 of 2.0s, inputs present
        assertTrue(w.advance(1.5f, 2.0f, true));   // 2.5 > 2.0 -> complete
        assertEquals(0.5f, w.progress(), 1e-6);    // carry the remainder
    }

    @Test
    void runDryFreezesProgress() {
        WorkState w = new WorkState();
        w.advance(1.0f, 2.0f, true);
        assertFalse(w.advance(1.0f, 2.0f, false)); // no inputs -> no progress, no loss
        assertEquals(1.0f, w.progress(), 1e-6);
    }

    @Test
    void activeReflectsWhetherWorkProgressed() {
        WorkState w = new WorkState();
        w.advance(1.0f, 2.0f, true);
        assertTrue(w.active());                     // progressed -> active
        w.advance(1.0f, 2.0f, false);
        assertFalse(w.active());                    // run-dry -> inactive (but progress retained)
        assertEquals(1.0f, w.progress(), 1e-6);
    }

    @Test
    void nonPositiveDurationNeverCompletesButAccumulates() {
        WorkState w = new WorkState();
        assertFalse(w.advance(5.0f, 0f, true)); // duration <= 0 never completes
        assertTrue(w.active());                 // still goes active
        assertEquals(5.0f, w.progress(), 1e-6); // progress still accumulates
    }

    @Test
    void exactDurationDoesNotCompleteThisTick() {
        WorkState w = new WorkState();
        assertFalse(w.advance(1.0f, 2.0f, true)); // 1.0 of 2.0s
        assertFalse(w.advance(1.0f, 2.0f, true)); // exactly 2.0; strict > means no completion
        assertEquals(2.0f, w.progress(), 1e-6);   // progress sits exactly at duration
    }

    @Test
    void codecRoundTripsViaEncode() {
        WorkState w = new WorkState();
        w.advance(1.0f, 2.0f, true);
        BsonValue encoded = WorkState.CODEC.encode(w, EmptyExtraInfo.EMPTY);
        WorkState reDecoded = WorkState.CODEC.decode(encoded, EmptyExtraInfo.EMPTY);
        assertEquals(1.0f, reDecoded.progress(), 1e-6);
        assertTrue(reDecoded.active());
    }
}
