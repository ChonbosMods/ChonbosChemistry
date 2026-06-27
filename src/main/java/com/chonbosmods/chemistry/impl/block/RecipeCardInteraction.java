package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.impl.block.craft.AutoCraftNode;
import com.chonbosmods.chemistry.impl.block.craft.CardHolder;
import com.chonbosmods.chemistry.impl.block.net.item.ItemContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemKey;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.client.SimpleBlockInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import javax.annotation.Nullable;
import org.bson.BsonDocument;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * The {@code CC_RecipeScript} block interaction: right-clicking (Secondary) a CC {@link CardHolder} block
 * (any of the 7 auto-crafter machines OR the Recipe Programmer bench) while holding a recipe-script card
 * INSERTS the card into its card slot, or SWAPS it for the card already loaded there. Mirrors
 * {@link WrenchInteraction} exactly (a {@link SimpleBlockInteraction} whose Secondary resolves the targeted
 * CC block-entity and mutates it); F/Use still opens the block's panel (a different input), untouched.
 *
 * <h2>Behaviour (design: card insert/swap, 2026-06)</h2>
 * <ul>
 *   <li><b>Card slot EMPTY</b> ({@code node.card() == null/empty}): INSERT. Store a qty-1
 *       metadata-preserving copy of the held card ({@link RecipeCardOps#toStoredCard}) into the machine and
 *       consume ONE card from the held stack.</li>
 *   <li><b>Card slot OCCUPIED</b>: SWAP. Insert the held card (consume one) AND give the previously-stored
 *       card back to the player (add to inventory, dropping overflow at the player's feet).</li>
 * </ul>
 *
 * <p>The card carries its {@code CC_RecipeScript} blueprint transparently in its ItemStack metadata; this
 * class never parses it (the engine's {@code AutoCraftEngine.cardScript} reads it). For a MACHINE target
 * (an {@link AutoCraftNode}), swapping the card resets its per-card progress via
 * {@link AutoCraftNode#clearScriptProgress()} so a freshly inserted card starts counting from zero (the
 * engine also detects this via the card signature, but the explicit reset here keeps the panel feedback
 * immediate and matches the panel's eject behaviour). The Recipe Programmer holds no progress, so that reset
 * is skipped for it (guarded by an {@code instanceof AutoCraftNode} check).
 *
 * <h2>Engine facts used (mirrors {@link WrenchInteraction})</h2>
 * <ul>
 *   <li>The item JSON sets {@code "UseLatestTarget": true} AND inherits the {@code BlockSelectorTool}
 *       facet (the tool-like setup the wrench gets from {@code Tool_Hammer_Crude}) so the targeted
 *       {@code blockPos} flows through to {@code interactWithBlock}.</li>
 *   <li>Runs on the WorldThread, so direct ECS reads/writes and {@code markNeedsSaving} are safe here.</li>
 *   <li>Never throws: every lookup is null-guarded (hot interaction path).</li>
 * </ul>
 */
public final class RecipeCardInteraction extends SimpleBlockInteraction {

    /** Chained from {@link SimpleBlockInteraction#CODEC} (contributes the {@code UseLatestTarget} key). */
    public static final BuilderCodec<RecipeCardInteraction> CODEC =
        BuilderCodec.builder(RecipeCardInteraction.class, RecipeCardInteraction::new, SimpleBlockInteraction.CODEC)
            .documentation("CC_RecipeScript: inserts/swaps a recipe-script card in a CC auto-crafter machine.")
            .build();

    /** Public no-arg constructor for the codec supplier (HyProTech pattern). */
    public RecipeCardInteraction() {
        super();
    }

    @Override
    protected void interactWithBlock(
            World world,
            CommandBuffer<EntityStore> commandBuffer,
            InteractionType interactionType,
            InteractionContext context,
            @Nullable ItemStack itemStack,
            Vector3i blockPos,
            CooldownHandler cooldownHandler) {
        try {
            if (world == null || context == null || blockPos == null) {
                return;
            }
            // A qty-1 metadata-preserving copy is what we will store; if the held stack is empty/idless
            // there is nothing to insert (defensive: Secondary normally only fires with a held item).
            ItemStack toInsert = RecipeCardOps.toStoredCard(itemStack);
            if (toInsert == null) {
                return;
            }

            CardHolder holder = resolveHolder(world, blockPos.x(), blockPos.y(), blockPos.z());
            if (holder == null) {
                // Not one of our auto-crafter machines nor the Recipe Programmer: do nothing (no chat spam:
                // a card is a normal item that may be right-clicked anywhere).
                return;
            }

            // SWAP vs INSERT is decided purely by whether a card is already stored.
            ItemStack stored = holder.card();
            boolean swap = RecipeCardOps.isGivebackWorthy(stored);

            // Insert the new card. The per-card progress reset is MACHINE-only (the Programmer has no
            // progress): apply it only when the holder is an auto-crafter, so the new card starts fresh.
            holder.setCard(toInsert);
            if (holder instanceof AutoCraftNode acn) {
                acn.clearScriptProgress();
            }

            // Consume ONE card from the held stack (vanilla ModifyInventoryInteraction pattern).
            consumeOneHeld(context, itemStack);

            // On a swap, hand the previously-stored card back to the player (add-or-drop).
            if (swap) {
                giveBack(commandBuffer, context, world, stored);
            }

            markNeedsSaving(world, blockPos.x(), blockPos.y(), blockPos.z());
            sendMessage(commandBuffer, context, swap ? "Recipe card swapped." : "Recipe card inserted.");
        } catch (Throwable ignored) {
            // Never let an interaction handler crash the WorldThread.
        }
    }

    @Override
    protected void simulateInteractWithBlock(
            InteractionType interactionType,
            InteractionContext context,
            @Nullable ItemStack itemStack,
            World world,
            Vector3i blockPos) {
        // Server-side only: all mutation happens in interactWithBlock on the WorldThread.
    }

    // --- holder resolution (mirrors CarryBreakEventSystem's per-type cascade) ---

    /**
     * The single {@link CardHolder} block-entity at (x,y,z), resolved across all 7 auto-crafter component
     * types PLUS the Recipe Programmer's component type, or {@code null} when the targeted block is not one
     * of ours. The 7 machine states implement {@code CardHolder} (via {@link AutoCraftNode}) and the
     * Programmer's state implements it directly, so the first one present is returned directly.
     */
    @Nullable
    private CardHolder resolveHolder(World world, int x, int y, int z) {
        ChonbosChemistry plugin = ChonbosChemistry.getInstance();
        if (plugin == null) {
            return null;
        }
        Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, x, y, z);
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Store<ChunkStore> store = ref.getStore();
        if (store == null) {
            return null;
        }
        CardHolder holder;
        if ((holder = get(store, ref, plugin.cookerComponentType())) != null) {
            return holder;
        }
        if ((holder = get(store, ref, plugin.forgeComponentType())) != null) {
            return holder;
        }
        if ((holder = get(store, ref, plugin.outfitterComponentType())) != null) {
            return holder;
        }
        if ((holder = get(store, ref, plugin.alembicComponentType())) != null) {
            return holder;
        }
        if ((holder = get(store, ref, plugin.assemblerComponentType())) != null) {
            return holder;
        }
        if ((holder = get(store, ref, plugin.cultivatorComponentType())) != null) {
            return holder;
        }
        if ((holder = get(store, ref, plugin.sculptorComponentType())) != null) {
            return holder;
        }
        if ((holder = get(store, ref, plugin.recipeProgrammerComponentType())) != null) {
            return holder;
        }
        return null;
    }

    /**
     * Reads {@code type}'s component off {@code ref} and returns it as a {@link CardHolder}; null type or
     * absent component yields null. The cast is safe: every component type passed here belongs either to one
     * of the 7 auto-crafter states (all {@code CardHolder} via {@code AutoCraftNode}) or to the Recipe
     * Programmer's state (a direct {@code CardHolder}). We bound {@code T} to plain
     * {@link com.hypixel.hytale.component.Component} (not an intersection with {@code CardHolder}, which
     * conflicts on {@code clone()}) and cast the read component.
     */
    @Nullable
    private <T extends com.hypixel.hytale.component.Component<ChunkStore>> CardHolder get(
            Store<ChunkStore> store, Ref<ChunkStore> ref, @Nullable ComponentType<ChunkStore, T> type) {
        if (type == null) {
            return null;
        }
        T component = store.getComponent(ref, type);
        return component instanceof CardHolder ? (CardHolder) component : null;
    }

    // --- held-item consume (vanilla ModifyInventoryInteraction pattern) ---

    private void consumeOneHeld(InteractionContext context, @Nullable ItemStack heldItem) {
        if (context == null || heldItem == null) {
            return;
        }
        ItemContainer held = context.getHeldItemContainer();
        if (held == null) {
            return;
        }
        ItemStackSlotTransaction tx =
            held.removeItemStackFromSlot((short) context.getHeldItemSlot(), heldItem, 1);
        if (tx != null && tx.succeeded()) {
            context.setHeldItem(tx.getSlotAfter());
        }
    }

    // --- give-back (mirrors CookerPanelPage.eject: add to inventory, drop overflow at feet) ---

    private void giveBack(
            CommandBuffer<EntityStore> commandBuffer, InteractionContext context, World world,
            @Nullable ItemStack stored) {
        if (commandBuffer == null || context == null || world == null || ItemStack.isEmpty(stored)) {
            return;
        }
        Ref<EntityStore> entity = context.getEntity();
        if (entity == null || !entity.isValid()) {
            return;
        }
        Player player = commandBuffer.getComponent(entity, Player.getComponentType());
        if (player == null) {
            return;
        }
        String id = stored.getItemId();
        if (id == null || id.isEmpty()) {
            return;
        }
        BsonDocument metadata = stored.getMetadata();

        // Add the stored card (qty 1) to the player's inventory; anything that didn't fit is dropped.
        CombinedItemContainer inventory = player.getInventory().getCombinedHotbarFirst();
        ItemContainerView invView = new ItemContainerView(inventory);
        int accepted = invView.insert(new ItemKey(id, 1), metadata, 1, false);
        int leftover = 1 - accepted;
        if (leftover <= 0) {
            return;
        }

        // Drop the unaccepted remainder at the player's feet (mirrors the panel eject overflow path).
        TransformComponent transform = commandBuffer.getComponent(entity, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return; // no position to drop at; the card is lost only if the inventory was full (rare)
        }
        Vector3d pos = transform.getPosition();
        ItemStack drop = metadata != null ? new ItemStack(id, leftover, metadata.clone()) : new ItemStack(id, leftover);
        Store<EntityStore> store = world.getEntityStore().getStore();
        Holder<EntityStore>[] holders =
            ItemComponent.generateItemDrops(store, List.of(drop), new Vector3d(pos.x, pos.y, pos.z), Rotation3f.IDENTITY);
        store.addEntities(holders, AddReason.SPAWN);
    }

    // --- persistence + chat (mirrors WrenchInteraction) ---

    private void markNeedsSaving(World world, int x, int y, int z) {
        if (y < ChunkUtil.MIN_Y || y >= ChunkUtil.HEIGHT) {
            return;
        }
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        BlockComponentChunk blockComponents =
            world.getChunkStore().getChunkComponent(chunkIndex, BlockComponentChunk.getComponentType());
        if (blockComponents != null) {
            blockComponents.markNeedsSaving();
        }
    }

    private void sendMessage(CommandBuffer<EntityStore> commandBuffer, InteractionContext context, String message) {
        if (commandBuffer == null || context == null || context.getEntity() == null) {
            return;
        }
        PlayerRef playerRef = commandBuffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        playerRef.sendMessage(Message.raw(message));
    }
}
