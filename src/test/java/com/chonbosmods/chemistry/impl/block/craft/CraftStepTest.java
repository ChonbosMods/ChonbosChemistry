package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftStepTest {

    private static final List<String> ORDER = List.of("A", "B", "C");
    private static final float DT = 1.0f;
    private static final float DURATION = 4.0f;

    @Test
    void idle_whenNothingCraftable() {
        CraftStep.Outcome o = CraftStep.step(ORDER, Set.of(), "B", true, DT, 2.5f, DURATION);
        assertNull(o.pick());
        assertEquals(0f, o.newProgress());
        assertFalse(o.completed());
        assertEquals("B", o.newCursor()); // cursor unchanged when idle
    }

    @Test
    void unpowered_holdsProgressAndCursor() {
        CraftStep.Outcome o = CraftStep.step(ORDER, Set.of("A"), null, false, DT, 1.5f, DURATION);
        assertEquals("A", o.pick());
        assertEquals(1.5f, o.newProgress()); // retained, no advance
        assertFalse(o.completed());
        assertNull(o.newCursor()); // cursor unchanged
    }

    @Test
    void inProgress_accumulatesAffordableDt() {
        CraftStep.Outcome o = CraftStep.step(ORDER, Set.of("A"), null, true, DT, 1.5f, DURATION);
        assertEquals("A", o.pick());
        assertEquals(2.5f, o.newProgress()); // 1.5 + 1.0
        assertFalse(o.completed());
        assertNull(o.newCursor()); // cursor unchanged mid-craft
    }

    @Test
    void completion_advancesCursorAndCarriesRemainder() {
        CraftStep.Outcome o = CraftStep.step(ORDER, Set.of("A"), null, true, DT, 3.5f, DURATION);
        assertEquals("A", o.pick());
        assertTrue(o.completed());
        assertEquals("A", o.newCursor()); // cursor advances to crafted id ONLY on completion
        assertEquals(0.5f, o.newProgress(), 1e-6f); // 3.5 + 1.0 - 4.0
    }

    @Test
    void completion_exactBoundaryCompletesWithZeroRemainder() {
        CraftStep.Outcome o = CraftStep.step(ORDER, Set.of("A"), null, true, DT, 3.0f, DURATION);
        assertTrue(o.completed());
        assertEquals(0f, o.newProgress(), 1e-6f);
    }

    @Test
    void rotation_pickStaysSameMidCraftThenAdvancesOnCompletion() {
        Set<String> craftable = Set.of("A", "C");

        // Cursor null -> first pick is A; mid-craft so cursor stays null.
        CraftStep.Outcome inProgress = CraftStep.step(ORDER, craftable, null, true, DT, 0f, DURATION);
        assertEquals("A", inProgress.pick());
        assertNull(inProgress.newCursor());

        // Feed enough progress to complete A: cursor now advances to A.
        CraftStep.Outcome done = CraftStep.step(ORDER, craftable, null, true, DT, DURATION, DURATION);
        assertEquals("A", done.pick());
        assertTrue(done.completed());
        assertEquals("A", done.newCursor());

        // Next tick with cursor=A rotates to the next craftable, C.
        CraftStep.Outcome next = CraftStep.step(ORDER, craftable, "A", true, DT, 0f, DURATION);
        assertEquals("C", next.pick());
    }
}
