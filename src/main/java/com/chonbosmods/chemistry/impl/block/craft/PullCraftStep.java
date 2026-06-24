package com.chonbosmods.chemistry.impl.block.craft;

import java.util.List;
import java.util.Set;

/**
 * Pure, engine-free per-tick decision for the demand-driven (pull) Forge. Models BOTH phases of the
 * discrete pull cycle: IDLE (no active craft) picks a recipe to START; CRAFTING advances an already-held
 * craft and COMPLETEs it at duration. The engine applies the returned {@link Action} (START then pulls the
 * recipe's ingredients from the network; COMPLETE then consumes held + produces outputs). Selection is the
 * even round-robin in {@link CraftSelection}; the cursor advances to the crafted id ONLY on completion.
 */
public final class PullCraftStep {

    private PullCraftStep() {
    }

    /** What the engine should do this tick. */
    public enum Action { IDLE, START, ADVANCE, COMPLETE }

    /**
     * @param action      the engine action this tick
     * @param pick        the recipe id this decision concerns (the START pick, or the active id for
     *                    ADVANCE/COMPLETE; null for IDLE)
     * @param newProgress craft seconds to store (0 for IDLE/START/COMPLETE; accumulated for ADVANCE)
     * @param newCursor   round-robin cursor to store (advances to the crafted id ONLY on COMPLETE)
     */
    public record Decision(Action action, String pick, float newProgress, String newCursor) {
    }

    /**
     * One tick of the pull-craft loop.
     *
     * @param crafting     whether a craft is active (the Forge's currentRecipeId != null)
     * @param currentId    the active recipe id (null when {@code !crafting})
     * @param progress     accumulated craft seconds so far
     * @param duration     seconds one craft takes
     * @param powered      whether the energy gate afforded work this tick
     * @param affordableDt simulated craft seconds affordable this tick (0 when unpowered)
     * @param stableOrder  all bench recipe ids in a stable order (for round-robin when idle)
     * @param craftable    ids currently makeable (card-allowed AND full set available across sources AND output room)
     * @param cursor       the stored round-robin cursor (last crafted id), or null
     */
    public static Decision decide(boolean crafting, String currentId, float progress, float duration,
            boolean powered, float affordableDt, List<String> stableOrder, Set<String> craftable, String cursor) {
        if (crafting) {
            if (!powered) {
                return new Decision(Action.ADVANCE, currentId, progress, cursor); // hold: progress unchanged
            }
            float np = progress + affordableDt;
            // Discrete pull cycle: each craft is a fresh pull starting at 0, so the fractional remainder
            // (np - duration) is NOT carried; reset to 0 on completion. cursor advances to the crafted id.
            if (np >= duration) {
                return new Decision(Action.COMPLETE, currentId, 0f, currentId);
            }
            return new Decision(Action.ADVANCE, currentId, np, cursor);
        }
        // idle: never reserve ingredients we cannot craft (don't pull when unpowered)
        if (!powered) {
            return new Decision(Action.IDLE, null, 0f, cursor);
        }
        String pick = CraftSelection.selectNext(stableOrder, craftable, cursor);
        if (pick == null) {
            return new Decision(Action.IDLE, null, 0f, cursor);
        }
        return new Decision(Action.START, pick, 0f, cursor); // engine then atomically pulls pick's ingredients
    }
}
