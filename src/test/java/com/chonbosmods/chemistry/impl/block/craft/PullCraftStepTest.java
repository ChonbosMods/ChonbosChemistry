package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import com.chonbosmods.chemistry.impl.block.craft.PullCraftStep.Action;
import com.chonbosmods.chemistry.impl.block.craft.PullCraftStep.Decision;

/** Branch-by-branch coverage of the pure pull-craft state machine. */
class PullCraftStepTest {

    private static final List<String> ORDER = List.of("a", "b", "c");

    /** 1. idle + unpowered: power gates BEFORE selection, so no pick even with craftable present. */
    @Test
    void idleUnpoweredStaysIdleAndDoesNotSelect() {
        Decision d = PullCraftStep.decide(false, null, 0f, 5f,
                false, 0f, ORDER, Set.of("a", "b", "c"), "a");
        assertEquals(Action.IDLE, d.action());
        assertNull(d.pick());
        assertEquals(0f, d.newProgress());
        assertEquals("a", d.newCursor()); // cursor unchanged
    }

    /** 2. idle + powered + empty craftable: nothing to make, stay idle, cursor unchanged. */
    @Test
    void idlePoweredEmptyCraftableStaysIdle() {
        Decision d = PullCraftStep.decide(false, null, 0f, 5f,
                true, 1f, ORDER, Set.of(), "a");
        assertEquals(Action.IDLE, d.action());
        assertNull(d.pick());
        assertEquals(0f, d.newProgress());
        assertEquals("a", d.newCursor());
    }

    /** 3. idle + powered + craftable: START on the round-robin pick (cursor "a", craftable {b,c} -> "b"). */
    @Test
    void idlePoweredCraftableStartsRoundRobinPick() {
        Decision d = PullCraftStep.decide(false, null, 0f, 5f,
                true, 1f, ORDER, Set.of("b", "c"), "a");
        assertEquals(Action.START, d.action());
        assertEquals("b", d.pick());
        assertEquals(0f, d.newProgress());
        assertEquals("a", d.newCursor()); // cursor unchanged on START
    }

    /** 4. crafting + unpowered: hold, progress unchanged, cursor unchanged. */
    @Test
    void craftingUnpoweredHoldsProgress() {
        Decision d = PullCraftStep.decide(true, "b", 2.5f, 5f,
                false, 0f, ORDER, Set.of(), "a");
        assertEquals(Action.ADVANCE, d.action());
        assertEquals("b", d.pick());
        assertEquals(2.5f, d.newProgress()); // held, unchanged
        assertEquals("a", d.newCursor());
    }

    /** 5. crafting + powered, below duration: ADVANCE, accumulate progress, cursor unchanged. */
    @Test
    void craftingPoweredBelowDurationAdvances() {
        Decision d = PullCraftStep.decide(true, "b", 2.5f, 5f,
                true, 1.5f, ORDER, Set.of(), "a");
        assertEquals(Action.ADVANCE, d.action());
        assertEquals("b", d.pick());
        assertEquals(4f, d.newProgress()); // 2.5 + 1.5
        assertEquals("a", d.newCursor());
    }

    /** 6. crafting + powered, np EXACTLY == duration (4f+1f==5f, exact in float): COMPLETE. Pins >= over >. */
    @Test
    void craftingPoweredAtExactDurationCompletes() {
        Decision d = PullCraftStep.decide(true, "b", 4f, 5f,
                true, 1f, ORDER, Set.of(), "a");
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("b", d.pick());
        assertEquals(0f, d.newProgress()); // remainder NOT carried
        assertEquals("b", d.newCursor()); // cursor advanced to crafted id
    }

    /** 6b. crafting + powered, np strictly > duration (4.5f+1f==5.5f): also COMPLETE (covers the > side of >=). */
    @Test
    void craftingPoweredOverDurationCompletes() {
        Decision d = PullCraftStep.decide(true, "b", 4.5f, 5f,
                true, 1f, ORDER, Set.of(), "a");
        assertEquals(Action.COMPLETE, d.action());
        assertEquals("b", d.pick());
        assertEquals(0f, d.newProgress()); // remainder NOT carried even when overshooting
        assertEquals("b", d.newCursor());
    }

    /** 6c. crafting + powered but zero affordable work this tick: ADVANCE, progress unchanged, cursor unchanged. */
    @Test
    void craftingPoweredZeroDtAdvancesWithoutProgress() {
        Decision d = PullCraftStep.decide(true, "b", 2.5f, 5f,
                true, 0f, ORDER, Set.of(), "a");
        assertEquals(Action.ADVANCE, d.action());
        assertEquals("b", d.pick());
        assertEquals(2.5f, d.newProgress()); // no work afforded: unchanged
        assertEquals("a", d.newCursor());
    }

    /** 7. rotation: complete "b" advances cursor to "b"; next idle with craftable {b,c} rotates to "c". */
    @Test
    void cursorAdvancesOnlyOnCompletionThenRotatesForward() {
        // Cycle 1: finish crafting "b".
        Decision complete = PullCraftStep.decide(true, "b", 5f, 5f,
                true, 0.5f, ORDER, Set.of(), "a");
        assertEquals(Action.COMPLETE, complete.action());
        assertEquals("b", complete.newCursor());

        // Cycle 2: now idle with the advanced cursor; round-robin moves past "b" to "c".
        Decision next = PullCraftStep.decide(false, null, 0f, 5f,
                true, 1f, ORDER, Set.of("b", "c"), complete.newCursor());
        assertEquals(Action.START, next.action());
        assertEquals("c", next.pick());
        assertEquals("b", next.newCursor()); // cursor still "b" on START (advances only on completion)
    }
}
