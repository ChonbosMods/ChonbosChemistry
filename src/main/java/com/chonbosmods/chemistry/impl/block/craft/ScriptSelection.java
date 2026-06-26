package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.impl.block.craft.RecipeScript.Entry;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure, engine-free selection math for a machine running a {@link RecipeScript} (the structured card from
 * {@code docs/plans/2026-06-26-recipe-script-selector-design.md} §3). Sibling to {@link CraftSelection},
 * which the engine still reuses verbatim for the UNORDERED case (most-ingredient priority + even rotation
 * over the active set); this class adds the script-specific pieces: the progress-retiring active set, the
 * ordered first-craftable pick, and blueprint completion.
 *
 * <p>All methods are static, null-defensive and take only plain data (ids, counts, sets, maps): no world,
 * ECS or ItemStack types, so the whole thing is unit-testable headlessly with hand-built cards.
 */
public final class ScriptSelection {

    private ScriptSelection() {
    }

    /**
     * The ids the machine may still make THIS tick: each script entry contributes its id iff it is
     * <em>unfinished</em> ({@link RecipeScript#isInfinite(Entry)} OR {@code made < count}) AND it is in
     * {@code craftableNow}. Per design §3.1 the active set is the script narrowing an already-computed
     * "craftable" set: {@code craftableNow} is the engine's (pool ∩ allow ∩ sourceable ∩ output-room) set,
     * so this method only intersects with it and applies progress retirement: it does not re-derive
     * sourceability.
     *
     * <p>Order is the script's first-seen entry order ({@link LinkedHashSet}) so {@link #orderedPick} can
     * walk it as the priority list; a duplicate id collapses to one member; a {@code craftableNow} id that
     * is not in the script is excluded (the card is a whitelist). Null/empty script ⇒ empty set; a null
     * {@code progress} is treated as an empty map (nothing made yet).
     *
     * @param script       the immutable blueprint (null/empty ⇒ nothing active)
     * @param progress      machine-side {@code recipeId → madeCount} (null ⇒ empty)
     * @param craftableNow the engine's already-computed currently-makeable id set
     * @return the unfinished + currently-craftable script ids, in script order (never null)
     */
    public static Set<String> activeSet(RecipeScript script, Map<String, Integer> progress,
                                        Set<String> craftableNow) {
        Set<String> active = new LinkedHashSet<>();
        if (script == null || craftableNow == null || craftableNow.isEmpty()) {
            return active;
        }
        for (Entry e : script.entries()) {
            if (e == null || e.recipeId() == null) {
                continue;
            }
            String id = e.recipeId();
            if (!craftableNow.contains(id)) {
                continue;
            }
            int made = made(progress, id);
            if (RecipeScript.isInfinite(e) || made < e.count()) {
                active.add(id); // LinkedHashSet collapses a duplicate id to one member
            }
        }
        return active;
    }

    /**
     * The ORDERED pick (design §3.3): the first script entry, in list order, whose id is in {@code active}:
     * list order IS the priority, so the existing priority/rotation logic is ignored. This naturally
     * <em>skips</em> a script entry that is not currently craftable (absent from {@code active}) and moves
     * to the next active one, rather than stalling the whole queue on one missing ingredient: the design's
     * default "skip-not-stall" (the "strict wait at the head" alternative is §9, not implemented here).
     *
     * @param script the blueprint whose entry order defines the walk (null ⇒ null)
     * @param active the active set from {@link #activeSet} (membership test only; its own order is unused)
     * @return the first active id in script order, or null when none is active (machine idles)
     */
    public static String orderedPick(RecipeScript script, Set<String> active) {
        if (script == null || active == null || active.isEmpty()) {
            return null;
        }
        for (Entry e : script.entries()) {
            if (e != null && e.recipeId() != null && active.contains(e.recipeId())) {
                return e.recipeId();
            }
        }
        return null;
    }

    /**
     * Whether the blueprint is FINISHED (design §3 step 5): true iff the script has NO infinite entry AND
     * every finite entry is met ({@code made >= count}). An infinite entry never completes, so its presence
     * forces false (the machine keeps making it forever). This signals the engine to go idle while the card
     * stays inserted and progress persists (the player sees an all-met blueprint).
     *
     * <p>A null/empty script returns false: Task 1 maps an empty script to a null card so an empty script
     * should never reach the engine; treating "nothing to do" as "complete" here would be misleading, and a
     * non-empty all-finite-met script is the only true case. A null {@code progress} is an empty map.
     *
     * @param script   the blueprint (null/empty ⇒ false)
     * @param progress machine-side {@code recipeId → madeCount} (null ⇒ empty)
     * @return true when no infinite entry exists and every finite entry's count is met
     */
    public static boolean isComplete(RecipeScript script, Map<String, Integer> progress) {
        if (script == null || script.isEmpty()) {
            return false;
        }
        for (Entry e : script.entries()) {
            if (e == null || e.recipeId() == null) {
                continue;
            }
            if (RecipeScript.isInfinite(e)) {
                return false; // an infinite target never finishes
            }
            if (made(progress, e.recipeId()) < e.count()) {
                return false; // a finite entry still unmet
            }
        }
        return true;
    }

    /** Made-count for {@code id}, treating a null map / absent key as 0 (nothing made yet). */
    private static int made(Map<String, Integer> progress, String id) {
        if (progress == null) {
            return 0;
        }
        Integer v = progress.get(id);
        return (v == null) ? 0 : v;
    }
}
