package com.chonbosmods.chemistry.impl.block.ui;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.impl.block.RecipeProgrammerState;
import com.chonbosmods.chemistry.impl.block.craft.AutoCraftEngine;
import com.chonbosmods.chemistry.impl.block.craft.RecipeScript;
import com.chonbosmods.chemistry.impl.block.craft.RecipeScriptArgs;
import com.chonbosmods.chemistry.impl.block.net.item.ItemContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemKey;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.joml.Vector3d;

/**
 * The STUB panel for the Recipe Programmer bench ({@code Pages/CC_RecipeProgrammerPanel.ui}). Phase 3.1: a
 * minimal read-only view that proves the bench + card-load loop end-to-end. It reads the loaded card off the
 * block's {@link RecipeProgrammerState} and shows the card's program as TEXT (via
 * {@link AutoCraftEngine#cardScript} + {@link RecipeScriptArgs#describe}), plus a single <b>Take Card</b>
 * button that returns the loaded card to the player and clears the slot.
 *
 * <p>The full authoring GUI (machine-filter tabs, search, the scrolling recipe browser, count steppers, the
 * ordered toggle, Duplicate) is later phases (P3.2-P3.4). Here there is no progress, no engine, no live tick
 * : the panel re-pushes itself manually after the Take Card button fires.
 *
 * <p><b>Threading:</b> mirrors {@link CookerPanelPage} : {@link #build} runs on the WorldThread; button
 * events ({@link #handleDataEvent}) may arrive off it, so the ECS mutations are wrapped in
 * {@link World#execute(Runnable)}.
 */
public final class RecipeProgrammerPanelPage
        extends InteractiveCustomUIPage<RecipeProgrammerPanelPage.PageData> {

    @Nonnull
    private final Ref<ChunkStore> blockRef;
    /** Programmer display name shown in the title bar. */
    @Nonnull
    private final String title;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType =
        BlockModule.BlockStateInfo.getComponentType();
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType = BlockChunk.getComponentType();

    public RecipeProgrammerPanelPage(
            @Nonnull PlayerRef playerRef,
            @Nonnull Ref<ChunkStore> blockRef,
            @Nonnull String title) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PageData.CODEC);
        this.blockRef = blockRef;
        this.title = title;
    }

    @Override
    public void build(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder commandBuilder,
            @Nonnull UIEventBuilder eventBuilder,
            @Nonnull Store<EntityStore> store) {
        commandBuilder.append("Pages/CC_RecipeProgrammerPanel.ui");
        commandBuilder.set("#PanelTitle.TextSpans", Message.raw(this.title));
        applyState(commandBuilder);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TakeCard",
            new EventData().append("Action", Action.TAKE_CARD));
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        String action = data.action;
        if (action == null) {
            return;
        }
        World world = this.resolveWorld();
        if (world == null) {
            return;
        }
        // Button events may arrive off the WorldThread: do all ECS work on it.
        world.execute(() -> this.applyAction(ref, store, action));
    }

    private void applyAction(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull String action) {
        RecipeProgrammerState state = this.programmer();
        if (state == null) {
            return;
        }
        if (Action.TAKE_CARD.equals(action)) {
            this.takeCard(ref, store, state);
            this.markNeedsSaving();
        } else {
            return;
        }
        // Manual re-push (no live PanelRefreshService for the stub).
        UICommandBuilder update = new UICommandBuilder();
        applyState(update);
        this.sendUpdate(update);
    }

    /** Resolve the Programmer's {@link RecipeProgrammerState}, or null if the block is gone / not ours. */
    private RecipeProgrammerState programmer() {
        if (!this.blockRef.isValid()) {
            return null;
        }
        return this.blockRef.getStore()
            .getComponent(this.blockRef, ChonbosChemistry.getInstance().recipeProgrammerComponentType());
    }

    /** Populate the panel's program-text label from the loaded card. Null-safe throughout. */
    private void applyState(@Nonnull UICommandBuilder cmd) {
        RecipeProgrammerState state = this.programmer();
        if (state == null) {
            return;
        }
        cmd.set("#ProgramText.TextSpans", Message.raw(programText(state.card())));
    }

    /**
     * The human-readable program text for {@code card}: "No card loaded." when no card is present, "Blank
     * card (no program)." when a card is loaded but carries no script, else the {@code describe} string.
     */
    private static String programText(ItemStack card) {
        if (card == null || ItemStack.isEmpty(card)) {
            return "No card loaded.";
        }
        RecipeScript script = AutoCraftEngine.cardScript(card);
        if (script == null) {
            return "Blank card (no program).";
        }
        return RecipeScriptArgs.describe(script);
    }

    /**
     * Return the loaded card to the player (add to inventory, drop overflow at feet) and clear the slot.
     * Mirrors {@link CookerPanelPage}'s card-return path in {@code eject}.
     */
    private void takeCard(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull RecipeProgrammerState state) {
        ItemStack card = state.card();
        if (card == null || ItemStack.isEmpty(card)) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        String id = card.getItemId();
        if (id == null || id.isEmpty()) {
            return;
        }
        BsonDocument metadata = card.getMetadata();
        int qty = Math.max(1, card.getQuantity());

        // Add the card to the player's inventory (hotbar-first); anything that didn't fit is dropped at feet.
        CombinedItemContainer inventory = player.getInventory().getCombinedHotbarFirst();
        ItemContainerView invView = new ItemContainerView(inventory);
        int accepted = invView.insert(new ItemKey(id, qty), metadata, qty, false);
        int leftover = qty - accepted;

        // Clear the slot now that the card has been returned (the leftover, if any, is dropped below).
        state.setCard(null);

        if (leftover <= 0) {
            return;
        }
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return; // no position to drop at; the card is lost only if the inventory was full (rare)
        }
        Vector3d pos = transform.getPosition();
        ItemStack drop = metadata != null
            ? new ItemStack(id, leftover, metadata.clone())
            : new ItemStack(id, leftover);
        Holder<EntityStore>[] holders = ItemComponent.generateItemDrops(
            store, List.of(drop), new Vector3d(pos.x, pos.y, pos.z), Rotation3f.IDENTITY);
        store.addEntities(holders, AddReason.SPAWN);
    }

    /** Mark the Programmer's chunk needing-save after a card take (WrenchInteraction pattern). */
    private void markNeedsSaving() {
        try {
            World world = this.resolveWorld();
            if (world == null || !this.blockRef.isValid()) {
                return;
            }
            BlockModule.BlockStateInfo info = this.blockRef.getStore().getComponent(this.blockRef, this.blockInfoType);
            if (info == null) {
                return;
            }
            Ref<ChunkStore> chunkRef = info.getChunkRef();
            if (chunkRef == null || !chunkRef.isValid()) {
                return;
            }
            BlockChunk blockChunk = chunkRef.getStore().getComponent(chunkRef, this.blockChunkType);
            if (blockChunk == null) {
                return;
            }
            int x = (blockChunk.getX() << 5) | ChunkUtil.xFromBlockInColumn(info.getIndex());
            int z = (blockChunk.getZ() << 5) | ChunkUtil.zFromBlockInColumn(info.getIndex());
            long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
            BlockComponentChunk components =
                world.getChunkStore().getChunkComponent(chunkIndex, BlockComponentChunk.getComponentType());
            if (components != null) {
                components.markNeedsSaving();
            }
        } catch (Throwable ignored) {
            // persistence marking must never crash the interaction
        }
    }

    private World resolveWorld() {
        if (!this.blockRef.isValid()) {
            return null;
        }
        ChunkStore external = this.blockRef.getStore().getExternalData();
        return external == null ? null : external.getWorld();
    }

    /** The event payload: which control fired ({@link Action#TAKE_CARD}). */
    public static final class PageData {
        private String action = "";

        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING, false),
                (o, v) -> o.action = v == null ? "" : v, o -> o.action).add()
            .build();
    }

    /** The one button action, as the literal string bound in the .ui event data. */
    private static final class Action {
        static final String TAKE_CARD = "TakeCard";

        private Action() {
        }
    }
}
