package com.chonbosmods.chemistry.impl.block.craft;

import java.util.List;
import java.util.Set;

/** Pure per-tick craft decision for autonomous-craft machines (Forge). Engine-free. */
public final class CraftStep {

    private CraftStep() {
    }

    /**
     * @param pick        the recipe id selected this tick (null = idle, nothing craftable)
     * @param newCursor   the round-robin cursor to store (advances to {@code pick} ONLY on completion)
     * @param newProgress accumulated craft seconds to store
     * @param completed   true iff a craft finished this tick (caller then consumes inputs + produces outputs)
     */
    public record Outcome(String pick, String newCursor, float newProgress, boolean completed) {
    }

    /**
     * One tick of the craft loop. Selection is even round-robin ({@link CraftSelection#selectNext}); the
     * cursor advances to the crafted recipe ONLY on completion (so an in-progress craft keeps selecting the
     * same recipe under steady inputs, and on completion the next tick rotates to the next craftable).
     *
     * @param stableOrder  all bench recipe ids, stable order
     * @param craftableIds currently makeable ids (card-allowed AND inputs present AND output has room)
     * @param cursor       the stored round-robin cursor (last completed id), or null
     * @param powered      whether the machine afforded work this tick (energy gate passed)
     * @param affordableDt simulated craft seconds affordable this tick (0 when unpowered)
     * @param progress     accumulated craft seconds so far
     * @param duration     seconds one craft takes
     */
    public static Outcome step(List<String> stableOrder, Set<String> craftableIds, String cursor,
            boolean powered, float affordableDt, float progress, float duration) {
        String pick = CraftSelection.selectNext(stableOrder, craftableIds, cursor);
        if (pick == null) {
            return new Outcome(null, cursor, 0f, false); // idle: reset progress, cursor unchanged
        }
        if (!powered) {
            return new Outcome(pick, cursor, progress, false); // hold: retain progress
        }
        float np = progress + affordableDt;
        if (np >= duration) {
            return new Outcome(pick, pick, np - duration, true); // complete: cursor advances, carry remainder
        }
        return new Outcome(pick, cursor, np, false); // in progress: cursor unchanged
    }
}
