package com.chonbosmods.chemistry.impl.block.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure, world-free helpers for the Recipe Programmer browser: turning a recipe (+ its output item ids) into a
 * row label, and filtering a recipe pool by a search query.
 *
 * <p>The recipe list is a single {@code ItemGrid} (one element + a server-set {@code .Slots} array, not cloned
 * per-row sub-trees), so it scrolls fine at scale : {@link #filter} therefore returns EVERY matching row (no
 * cap). The machine scope + search still narrow the list, they just no longer truncate it. Everything in this
 * class is pure (no ECS / asset registry), so it is fully unit-tested.
 */
public final class RecipeBrowse {

    /** A single browser row: the recipe id (the click payload) + the display label. */
    public record Row(@Nonnull String recipeId, @Nonnull String label) {
    }

    private RecipeBrowse() {
    }

    /**
     * The row label for a recipe: the primary output item's id, falling back to the recipe id when the recipe
     * has no resolvable output. Pure : the caller supplies the already-resolved output item ids (empty/null is
     * fine), so this is testable without the asset registry.
     *
     * @param recipeId      the recipe's id (never null; the fallback label)
     * @param outputItemIds the recipe's output item ids in order (may be null/empty)
     * @return the primary output item id when present and non-blank, else {@code recipeId}
     */
    @Nonnull
    public static String rowLabel(@Nonnull String recipeId, @Nullable List<String> outputItemIds) {
        if (outputItemIds != null) {
            for (String id : outputItemIds) {
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
        }
        return recipeId;
    }

    /**
     * The primary output item id for a recipe, or {@code null} when none is resolvable. Pure: the caller
     * supplies the already-resolved output item ids (the row label helper's input), so this is testable
     * without the asset registry. Unlike {@link #rowLabel} this does NOT fall back to the recipe id : a null
     * means "no output item" (the click handler then has no real item to put in the select pane).
     *
     * @param outputItemIds the recipe's output item ids in order (may be null/empty)
     * @return the first non-blank output item id, or {@code null}
     */
    @Nullable
    public static String primaryOutputId(@Nullable List<String> outputItemIds) {
        if (outputItemIds != null) {
            for (String id : outputItemIds) {
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
        }
        return null;
    }

    /**
     * A readable display label for an item id: drop any {@code namespace:} prefix and common asset prefixes
     * ({@code Ingredient_}, {@code Plant_}, {@code Block_}, {@code Item_}), turn {@code _} into spaces, and
     * Title-Case the words. Pure and null-safe : used for the select-pane name and the program-list rows where
     * the raw item id (e.g. {@code Ingredient_Crystal_Blue}) would be ugly. A blank input yields {@code ""}.
     *
     * @param itemId the raw item id (may be null/blank)
     * @return a humanized label, or {@code ""} when the input is null/blank
     */
    @Nonnull
    public static String humanize(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }
        String raw = itemId.trim();
        int colon = raw.indexOf(':');
        if (colon >= 0 && colon + 1 < raw.length()) {
            raw = raw.substring(colon + 1);
        }
        for (String prefix : new String[] {"Ingredient_", "Plant_", "Block_", "Item_"}) {
            if (raw.startsWith(prefix)) {
                raw = raw.substring(prefix.length());
                break;
            }
        }
        raw = raw.replace('_', ' ').trim();
        if (raw.isEmpty()) {
            return itemId.trim();
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (String word : raw.split(" +")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1).toLowerCase(Locale.ENGLISH));
            }
        }
        return out.length() == 0 ? itemId.trim() : out.toString();
    }

    /**
     * Filter {@code rows} by {@code query} (case-insensitive substring match on the label OR the recipe id),
     * returning ALL matches in input order (no cap : the recipe grid is one scrolling {@code ItemGrid}). A
     * blank/null query matches everything. Input order is preserved (callers pass a pre-sorted pool order).
     *
     * @param rows  the candidate rows, already in render order (e.g. the pool's stable id order)
     * @param query the search text ({@code null}/blank = match all)
     * @return every matching row, in input order
     */
    @Nonnull
    public static List<Row> filter(@Nonnull List<Row> rows, @Nullable String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ENGLISH);
        List<Row> kept = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (matches(row, needle)) {
                kept.add(row);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * The recipe id at the clicked grid slot {@code index} within the currently-rendered {@code rows}, or
     * {@code null} when the index is out of range (a click on an empty / stale slot). The rendered row list is
     * the grid's slot order (row i = slot i), so this is the slot-index -&gt; recipe mapping. Pure / null-safe.
     *
     * @param rows  the rows currently rendered into the grid (the filtered + capped list), in slot order
     * @param index the clicked slot index (from the {@code SlotIndex} payload)
     * @return the recipe id at that slot, or {@code null} when {@code index} is out of range
     */
    @Nullable
    public static String recipeAtSlot(@Nullable List<Row> rows, int index) {
        if (rows == null || index < 0 || index >= rows.size()) {
            return null;
        }
        Row row = rows.get(index);
        return row == null ? null : row.recipeId();
    }

    /** Whether {@code row} matches the (already lowercased, trimmed) {@code needle}; blank matches all. */
    private static boolean matches(@Nonnull Row row, @Nonnull String needle) {
        if (needle.isEmpty()) {
            return true;
        }
        return row.label().toLowerCase(Locale.ENGLISH).contains(needle)
            || row.recipeId().toLowerCase(Locale.ENGLISH).contains(needle);
    }
}
