package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.impl.block.craft.CardHolder;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import javax.annotation.Nullable;

/**
 * The persistent ECS state the Recipe Programmer bench carries on the {@link ChunkStore}. Unlike the
 * auto-crafter machines ({@code CookerState} and siblings), the Programmer is NOT a crafting machine: it has
 * no energy, no ports, no progress, no engine : it only HOLDS one recipe card the player authors. So this
 * state holds exactly one field, the loaded {@code card}, and implements {@link CardHolder} (the {@code
 * card()}/{@code setCard()} pair) so the shared card-load interaction ({@code RecipeCardInteraction}) loads
 * and swaps it exactly as it does a machine's card slot.
 *
 * <h2>card</h2>
 * The inserted recipe card item (optional; null = no card). {@code ItemStack.CODEC} is an object codec, so a
 * null value is omitted on encode and an absent key decodes back to null : the same optional-card pattern
 * {@code CookerState} uses for its "Card" key.
 *
 * <h2>clone()</h2>
 * Deep copy via a codec round-trip (encode then decode), exactly like the machine states, so each placed
 * Programmer receives an independent card slot.
 */
public final class RecipeProgrammerState implements Component<ChunkStore>, CardHolder {

    public static final BuilderCodec<RecipeProgrammerState> CODEC =
        BuilderCodec.builder(RecipeProgrammerState.class, RecipeProgrammerState::new)
            // The loaded recipe card: optional. ItemStack.CODEC is an object codec, so a null value is
            // omitted on encode and an absent key decodes back to null (mirrors CookerState's "Card" key).
            .append(new KeyedCodec<>("Card", ItemStack.CODEC), (o, v) -> o.card = v, o -> o.card).add()
            .build();

    /** The inserted recipe card, or null if no card is loaded. See the "Card" codec key. */
    @Nullable
    private ItemStack card;

    /** Public no-arg constructor for the codec supplier. */
    public RecipeProgrammerState() {
    }

    /** @return the inserted recipe card item, or null if no card is loaded. */
    @Override
    @Nullable
    public ItemStack card() {
        return card;
    }

    /** Sets (or clears, when null) the inserted recipe card. */
    @Override
    public void setCard(@Nullable ItemStack card) {
        this.card = card;
    }

    /** Deep copy via codec round-trip (placement/copy only), mirroring the machine states' {@code clone()}. */
    @Override
    public Component<ChunkStore> clone() {
        return CODEC.decode(CODEC.encode(this, EmptyExtraInfo.EMPTY), EmptyExtraInfo.EMPTY);
    }
}
