package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Pure (engine-light) helpers for the {@code cc_recipe_card} insert/swap interaction. Kept separate from
 * {@link RecipeCardInteraction} so the one piece of non-trivial logic, building the qty-1
 * metadata-preserving copy that gets stored in the machine's card slot, is unit-testable without the
 * WorldThread/ECS glue (the interaction round-trip itself is in-game-only).
 */
public final class RecipeCardOps {

    private RecipeCardOps() {
    }

    /**
     * A quantity-1 copy of {@code held} that PRESERVES its metadata (the {@code CC_RecipeScript} blueprint
     * the engine reads via {@code AutoCraftEngine.cardScript}). The held stack's count is irrelevant: a card
     * slot holds exactly one card. Returns {@code null} when {@code held} is null/empty (nothing to insert).
     *
     * <p>The metadata is cloned so the stored card cannot alias the player's held stack (mutating one must
     * not bleed into the other). A null/absent metadata document yields a plain qty-1 card (a blank card,
     * which the engine reads as "no filter").
     */
    @Nullable
    public static ItemStack toStoredCard(@Nullable ItemStack held) {
        if (ItemStack.isEmpty(held)) {
            return null;
        }
        String id = held.getItemId();
        if (id == null || id.isEmpty()) {
            return null;
        }
        BsonDocument metadata = held.getMetadata();
        if (metadata == null) {
            return new ItemStack(id, 1);
        }
        return new ItemStack(id, 1, metadata.clone());
    }

    /** True when {@code stored} is a real card worth handing back to the player (non-null, non-empty). */
    public static boolean isGivebackWorthy(@Nullable ItemStack stored) {
        return !ItemStack.isEmpty(stored);
    }
}
