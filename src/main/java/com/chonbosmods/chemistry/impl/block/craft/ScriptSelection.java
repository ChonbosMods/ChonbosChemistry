package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.impl.block.craft.RecipeScript.Entry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, engine-free selection math for a machine running a {@link RecipeScript} (the structured card). A card
 * is just a list of {@link Entry}s ({@code count <= 0} = infinite, {@code count > 0} = a finite amount) and
 * the machine selects each tick in two phases:
 *
 * <ol>
 *   <li><b>Finite phase (sequential, in card order):</b> if any finite entry is still unfinished
 *       ({@code made < count}) AND currently craftable, make the FIRST such entry in card order ("make all 5
 *       of A, then all 3 of B"). A momentarily un-craftable finite entry is SKIPPED to the next craftable
 *       finite entry and resumed later (skip-not-stall): never a deadlock.</li>
 *   <li><b>Infinite phase (round-robin):</b> once every finite entry is met, round-robin the infinite
 *       ({@code count <= 0}) entries that are craftable, indefinitely. PLAIN even rotation
 *       ({@link CraftSelection#selectNext}) : no most-ingredient priority.</li>
 *   <li><b>Complete:</b> no infinite entry AND every finite entry met : nothing to pick (machine idles).</li>
 * </ol>
 *
 * <p>Sibling to {@link CraftSelection}, which the engine still reuses verbatim for the NO-CARD case
 * (most-ingredient priority + even rotation over the full craftable set). This class never applies
 * most-ingredient priority WITHIN a card.
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
     * {@code craftableNow}. The active set is the script narrowing an already-computed "craftable" set:
     * {@code craftableNow} is the engine's (pool ∩ allow ∩ sourceable ∩ output-room) set, so this method only
     * intersects with it and applies progress retirement: it does not re-derive sourceability.
     *
     * <p>Order is the script's first-seen entry order ({@link LinkedHashSet}); a duplicate id collapses to one
     * member; a {@code craftableNow} id that is not in the script is excluded (the card is a whitelist).
     * Null/empty script ⇒ empty set; a null {@code progress} is treated as an empty map (nothing made yet).
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
     * The single recipe id the machine should make this tick, or {@code null} when nothing is active (idle).
     * Two phases:
     *
     * <ol>
     *   <li><b>Finite phase:</b> the FIRST finite entry ({@code count > 0}) in card order whose target is not
     *       yet met ({@code made < count}) AND that is in {@code craftableNow}. List order is the priority, so
     *       an un-craftable finite head is skipped to the next craftable finite entry (skip-not-stall).</li>
     *   <li><b>Infinite phase:</b> only once NO finite entry is still active : plain round-robin over the
     *       infinite ({@code count <= 0}) entries that are in {@code craftableNow}, via
     *       {@link CraftSelection#selectNext} keyed off {@code lastSelectedId} (the engine cursor). No
     *       most-ingredient priority.</li>
     * </ol>
     *
     * <p>The finite phase strictly precedes the infinite phase: as long as ANY finite entry is craftable +
     * unmet, an infinite entry is never picked. When both phases are empty the result is {@code null} and the
     * machine idles ({@link #isComplete} is the all-finite-met / no-infinite case of that).
     *
     * @param script         the blueprint (null/empty ⇒ null)
     * @param progress        machine-side {@code recipeId → madeCount} (null ⇒ empty)
     * @param craftableNow   the engine's already-computed currently-makeable id set (null/empty ⇒ null)
     * @param lastSelectedId the round-robin cursor for the infinite phase (last crafted id), or null
     * @return the chosen recipe id, or null when nothing is active
     */
    public static String cardPick(RecipeScript script, Map<String, Integer> progress,
                                  Set<String> craftableNow, String lastSelectedId) {
        if (script == null || craftableNow == null || craftableNow.isEmpty()) {
            return null;
        }
        // Finite phase: first finite, unmet, craftable entry in card order (skip-not-stall).
        for (Entry e : script.entries()) {
            if (e == null || e.recipeId() == null || RecipeScript.isInfinite(e)) {
                continue;
            }
            String id = e.recipeId();
            if (made(progress, id) < e.count() && craftableNow.contains(id)) {
                return id;
            }
        }
        // Infinite phase: plain round-robin over the craftable infinite entries (no priority).
        List<String> infiniteOrder = infiniteOrder(script);
        Set<String> infiniteActive = infiniteActive(script, craftableNow);
        return CraftSelection.selectNext(infiniteOrder, infiniteActive, lastSelectedId);
    }

    /**
     * The finite entry the machine should make this tick (the finite phase of {@link #cardPick}): the FIRST
     * finite ({@code count > 0}), unmet ({@code made < count}), craftable entry in card order, or {@code null}
     * when no finite entry is still active. Skip-not-stall: an un-craftable finite head is skipped to the next
     * craftable finite entry.
     *
     * @param script        the blueprint (null/empty ⇒ null)
     * @param progress       machine-side {@code recipeId → madeCount} (null ⇒ empty)
     * @param craftableNow  the engine's currently-makeable id set (null/empty ⇒ null)
     * @return the first active finite id in card order, or null
     */
    public static String finitePick(RecipeScript script, Map<String, Integer> progress,
                                    Set<String> craftableNow) {
        if (script == null || craftableNow == null || craftableNow.isEmpty()) {
            return null;
        }
        for (Entry e : script.entries()) {
            if (e == null || e.recipeId() == null || RecipeScript.isInfinite(e)) {
                continue;
            }
            String id = e.recipeId();
            if (made(progress, id) < e.count() && craftableNow.contains(id)) {
                return id;
            }
        }
        return null;
    }

    /**
     * The craftable infinite ({@code count <= 0}) entries of {@code script}, in script first-seen order (the
     * infinite phase's round-robin pool). Excludes finite entries and any id not in {@code craftableNow}.
     * Null/empty inputs ⇒ empty set.
     */
    public static Set<String> infiniteActive(RecipeScript script, Set<String> craftableNow) {
        Set<String> active = new LinkedHashSet<>();
        if (script == null || craftableNow == null || craftableNow.isEmpty()) {
            return active;
        }
        for (Entry e : script.entries()) {
            if (e == null || e.recipeId() == null || !RecipeScript.isInfinite(e)) {
                continue;
            }
            if (craftableNow.contains(e.recipeId())) {
                active.add(e.recipeId());
            }
        }
        return active;
    }

    /**
     * The distinct infinite ({@code count <= 0}) entry ids of {@code script}, in script first-seen order : the
     * stable rotation order {@link CraftSelection#selectNext} walks for the infinite phase. Independent of
     * craftability (selectNext intersects with the active subset itself). Null/empty ⇒ empty list.
     */
    public static List<String> infiniteOrder(RecipeScript script) {
        List<String> order = new ArrayList<>();
        if (script == null) {
            return order;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Entry e : script.entries()) {
            if (e == null || e.recipeId() == null || !RecipeScript.isInfinite(e)) {
                continue;
            }
            if (seen.add(e.recipeId())) {
                order.add(e.recipeId());
            }
        }
        return order;
    }

    /**
     * Whether the blueprint is FINISHED: true iff the script has NO infinite entry AND every finite entry is
     * met ({@code made >= count}). An infinite entry never completes, so its presence forces false (the
     * machine keeps round-robining it forever). This signals the engine to go idle while the card stays
     * inserted and progress persists.
     *
     * <p>A null/empty script returns false: an empty script maps to a null card so it should never reach the
     * engine; treating "nothing to do" as "complete" here would be misleading.
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
