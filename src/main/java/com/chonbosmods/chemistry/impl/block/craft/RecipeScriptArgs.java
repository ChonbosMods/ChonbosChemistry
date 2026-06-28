package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.impl.block.craft.RecipeScript.Entry;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * The pure token grammar for the {@code /cc-script} debug command: turn a flat list of command tokens into a
 * {@link RecipeScript}. Separated from the command so it is unit-testable without the Hytale command/ECS
 * machinery (the command is thin glue that parses, stamps, and gives; this is the brain).
 *
 * <h2>Grammar</h2>
 * <pre>{@code  recipeId[:count] recipeId[:count] ...}</pre>
 * <ul>
 *   <li>Each token is a {@code recipeId} optionally suffixed with {@code :count}. An omitted count, a
 *       {@code :0}, or any non-positive/blank count means INFINITE ({@code count <= 0}; see
 *       {@link RecipeScript}). A positive count is a finite amount.</li>
 *   <li>At least one recipe entry is required; tokens that are blank or have a blank id are rejected.</li>
 * </ul>
 *
 * <p>Examples: {@code "a:5 b"} &rarr; {@code [a x5, b xINF]}; {@code "a b:2"} &rarr; {@code [a xINF, b x2]}.
 */
public final class RecipeScriptArgs {

    private RecipeScriptArgs() {
    }

    /** A parse failure carrying a human-readable reason for the command's usage feedback. */
    public static final class ParseException extends IllegalArgumentException {
        public ParseException(@Nonnull String message) {
            super(message);
        }
    }

    /**
     * Parse {@code tokens} into a {@link RecipeScript} per the class grammar.
     *
     * @param tokens the raw command tokens (one or more {@code recipeId[:count]}); null is treated as empty
     * @return the parsed script (never null)
     * @throws ParseException when there is no recipe entry, or a token is malformed (blank id, non-integer
     *                        count)
     */
    @Nonnull
    public static RecipeScript parse(List<String> tokens) {
        List<String> work = new ArrayList<>();
        if (tokens != null) {
            for (String t : tokens) {
                if (t != null && !t.isBlank()) {
                    work.add(t.trim());
                }
            }
        }
        if (work.isEmpty()) {
            throw new ParseException("no recipe ids given");
        }

        List<Entry> entries = new ArrayList<>();
        for (String token : work) {
            entries.add(parseEntry(token));
        }
        return new RecipeScript(entries);
    }

    /** Parse a single {@code recipeId[:count]} token into an {@link Entry} ({@code count <= 0} = infinite). */
    @Nonnull
    private static Entry parseEntry(@Nonnull String token) {
        int colon = token.indexOf(':');
        if (colon < 0) {
            return new Entry(token, 0); // no count => infinite
        }
        String id = token.substring(0, colon).trim();
        String countStr = token.substring(colon + 1).trim();
        if (id.isEmpty()) {
            throw new ParseException("missing recipe id in token '" + token + "'");
        }
        if (countStr.isEmpty()) {
            return new Entry(id, 0); // trailing ':' => infinite
        }
        int count;
        try {
            count = Integer.parseInt(countStr);
        } catch (NumberFormatException ex) {
            throw new ParseException("bad count '" + countStr + "' in token '" + token + "' (expected an integer)");
        }
        return new Entry(id, count); // count <= 0 normalizes to infinite downstream
    }

    /**
     * A compact human-readable description of {@code script} for chat feedback: {@code "a x5, b x∞"}. Pure
     * (id + count formatting), so it is unit-tested alongside {@link #parse}.
     */
    @Nonnull
    public static String describe(@Nonnull RecipeScript script) {
        StringBuilder sb = new StringBuilder();
        List<Entry> entries = script.entries();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(e.recipeId()).append(" x").append(RecipeScript.isInfinite(e) ? "∞" : e.count());
        }
        return sb.toString();
    }
}
