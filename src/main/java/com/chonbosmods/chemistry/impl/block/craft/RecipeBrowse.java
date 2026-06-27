package com.chonbosmods.chemistry.impl.block.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Pure, world-free helpers for the Recipe Programmer browser: turning a recipe (+ its output item ids) into a
 * row label, and filtering + <b>capping</b> a recipe pool by a search query before any UI row is rendered.
 *
 * <p><b>Why the cap matters.</b> The Sculptor's pool is ~911 recipes and the UI has no list virtualization :
 * every rendered row is a real cloned sub-tree with its own event binding. {@link #filterAndCap} is the choke
 * point that guarantees the browser NEVER tries to render the whole pool: it filters first, then truncates to
 * a hard cap. Everything in this class is pure (no ECS / asset registry), so it is fully unit-tested.
 */
public final class RecipeBrowse {

    /** The hard cap on rendered recipe rows. Vanilla {@code EntitySpawnPage} caps at 20; we allow a few more. */
    public static final int ROW_CAP = 40;

    /** A single browser row: the recipe id (the click payload) + the display label. */
    public record Row(@Nonnull String recipeId, @Nonnull String label) {
    }

    /** The filtered + capped result: the rows to render plus the pre-cap total (for the "N of M" note). */
    public record Result(@Nonnull List<Row> rows, int totalMatched) {

        /** @return whether the result was truncated (more matched than rendered). */
        public boolean isCapped() {
            return totalMatched > rows.size();
        }
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
     * Filter {@code rows} by {@code query} (case-insensitive substring match on the label OR the recipe id),
     * then truncate to {@code cap}. A blank query matches everything (but is STILL capped : an empty Sculptor
     * search must not render 911 rows). Input order is preserved (callers pass a pre-sorted pool order).
     *
     * @param rows  the candidate rows, already in render order (e.g. the pool's stable id order)
     * @param query the search text ({@code null}/blank = match all)
     * @param cap   the max rows to keep ({@code <= 0} is clamped to 0)
     * @return the kept rows + the pre-cap match total
     */
    @Nonnull
    public static Result filterAndCap(@Nonnull List<Row> rows, @Nullable String query, int cap) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ENGLISH);
        int safeCap = Math.max(0, cap);
        List<Row> kept = new ArrayList<>(Math.min(rows.size(), safeCap));
        int matched = 0;
        for (Row row : rows) {
            if (matches(row, needle)) {
                matched++;
                if (kept.size() < safeCap) {
                    kept.add(row);
                }
            }
        }
        return new Result(List.copyOf(kept), matched);
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
