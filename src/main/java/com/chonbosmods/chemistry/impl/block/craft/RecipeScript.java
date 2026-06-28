package com.chonbosmods.chemistry.impl.block.craft;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * An immutable recipe-script blueprint carried in a recipe card's item metadata: it tells an auto-crafter
 * WHICH recipes it may make and HOW MANY of each. This replaces the old flat {@code String[]} whitelist on
 * the card (see {@link AutoCraftEngine}).
 *
 * <h2>Shape</h2>
 * A card is just a list of {@link Entry}s, each an {@code recipeId} + {@code count}.
 * <b>{@code count <= 0} means INFINITE</b> (a permanent member with no finite target); {@code count > 0} is a
 * finite amount (make that many, then the entry is met). The machine runs the card in two phases: finite
 * entries first (sequential, in card order), then a plain round-robin of the infinite entries forever (see
 * {@link ScriptSelection}). There is NO ordered/unordered flag and NO most-ingredient priority within a card.
 *
 * <p>Pure, null-safe and defensive: the entries list is copied on construction so the instance cannot be
 * mutated through the caller's list.
 */
public final class RecipeScript {

    /** A single scripted recipe target: an id plus a count ({@code <= 0} = infinite, see class doc). */
    public static final class Entry {

        public static final BuilderCodec<Entry> CODEC = BuilderCodec.builder(Entry.class, Entry::new)
            .append(new KeyedCodec<>("Id", Codec.STRING), (o, v) -> o.recipeId = v, o -> o.recipeId).add()
            // Count is OPTIONAL (3-arg KeyedCodec): an absent count decodes to 0 = infinite, the natural
            // default for a bare whitelist member. Codec.INTEGER is an object codec, so a null on encode
            // is simply omitted and an absent key reaches the setter without tripping a non-null check.
            .append(new KeyedCodec<>("Count", Codec.INTEGER, false), (o, v) -> o.count = v, o -> o.count).add()
            .build();

        private String recipeId;
        private int count;

        /** Public no-arg constructor for the codec supplier. */
        public Entry() {
        }

        /** An entry for {@code recipeId} with the given {@code count} ({@code <= 0} = infinite). */
        public Entry(@Nonnull String recipeId, int count) {
            this.recipeId = recipeId;
            this.count = count;
        }

        /** @return the recipe id this entry targets. */
        public String recipeId() {
            return recipeId;
        }

        /** @return the target count ({@code <= 0} = infinite, see {@link RecipeScript#isInfinite(Entry)}). */
        public int count() {
            return count;
        }
    }

    // A legacy card may still carry an extra "Ordered" key; the codec simply does not declare it, so decoding
    // an old card harmlessly ignores it (extra keys are dropped). The flag no longer exists in the model.
    public static final BuilderCodec<RecipeScript> CODEC =
        BuilderCodec.builder(RecipeScript.class, RecipeScript::new)
            .append(new KeyedCodec<>("Entries", new ArrayCodec<>(Entry.CODEC, Entry[]::new)),
                    (o, v) -> o.entries = List.of(v), o -> o.entries.toArray(new Entry[0])).add()
            .build();

    private List<Entry> entries = List.of();

    /** Public no-arg constructor for the codec supplier. */
    public RecipeScript() {
    }

    /**
     * An immutable script. The {@code entries} list is defensively copied; a null list is treated as empty.
     *
     * @param entries the per-recipe targets ({@code count <= 0} = infinite)
     */
    public RecipeScript(List<Entry> entries) {
        this.entries = (entries == null) ? List.of() : List.copyOf(entries);
    }

    /** @return the per-recipe targets, never null (an immutable copy). */
    @Nonnull
    public List<Entry> entries() {
        return entries;
    }

    /** @return {@code true} when {@code e}'s count is {@code <= 0} (an infinite / permanent target). */
    public static boolean isInfinite(@Nonnull Entry e) {
        return e.count() <= 0;
    }

    /**
     * @return the distinct recipe ids in this script, in first-seen order (a {@link LinkedHashSet} so the
     *     whitelist iteration order is stable and duplicates collapse). Never null.
     */
    @Nonnull
    public Set<String> recipeIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e != null && e.recipeId() != null) {
                ids.add(e.recipeId());
            }
        }
        return ids;
    }

    /** @return {@code true} when this script has no entries (a blank / unprogrammed script). */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
