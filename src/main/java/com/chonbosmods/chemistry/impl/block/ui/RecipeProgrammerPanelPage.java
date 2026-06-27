package com.chonbosmods.chemistry.impl.block.ui;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.impl.block.RecipeProgrammerState;
import com.chonbosmods.chemistry.impl.block.bench.VanillaCraftBridge;
import com.chonbosmods.chemistry.impl.block.craft.AutoCraftEngine;
import com.chonbosmods.chemistry.impl.block.craft.MachineRecipePools;
import com.chonbosmods.chemistry.impl.block.craft.MachineRecipePools.Machine;
import com.chonbosmods.chemistry.impl.block.craft.RecipeBrowse;
import com.chonbosmods.chemistry.impl.block.craft.RecipePool;
import com.chonbosmods.chemistry.impl.block.craft.RecipeScript;
import com.chonbosmods.chemistry.impl.block.craft.RecipeScript.Entry;
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
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;
import org.bson.BsonDocument;
import org.joml.Vector3d;

/**
 * The Recipe Programmer bench panel ({@code Pages/CC_RecipeProgrammerPanel.ui}). Phase 3.2: the recipe
 * BROWSER : a machine-filter button-bar, a live search box, a scrolling + CAPPED recipe list, and the loaded
 * card's current program (read-only). Clicking a recipe row ADDS that recipe (infinite count) to the loaded
 * card's program by re-stamping the card via {@link AutoCraftEngine#writeScript}. Plus the P3.1 Take Card
 * button. Editing counts / removing / reordering is P3.3 (not here).
 *
 * <p>Mirrors vanilla {@code EntitySpawnPage}: the button-bar (Activating + Style/Visible toggle), the
 * {@code CompactTextField #SearchInput} (ValueChanged -&gt; {@code @SearchQuery}), and the runtime
 * {@code TopScrolling} list ({@code clear} + per-row {@code append} of {@code Common/TextButton.ui} +
 * per-row Activating binding carrying the recipe id).
 *
 * <p><b>SCALE (the one real risk).</b> The Sculptor pool is ~911 recipes and there is no list virtualization.
 * {@link #buildRecipeList} ALWAYS filters (machine scope + search) and then hard-caps to
 * {@link RecipeBrowse#ROW_CAP} via {@link RecipeBrowse#filterAndCap} BEFORE appending a single row : the full
 * pool is never rendered, even on an empty query. A "showing N of M" note appears when capped.
 *
 * <p><b>Threading:</b> {@link #build} runs on the WorldThread; event handlers may arrive off it, so every
 * ECS / card mutation (and the asset-registry reads in {@link #buildRecipeList}) runs on
 * {@link World#execute(Runnable)}. Nothing here throws on the page thread.
 */
public final class RecipeProgrammerPanelPage
        extends InteractiveCustomUIPage<RecipeProgrammerPanelPage.PageData> {

    /** Active vs inactive machine-filter button styles (vanilla Common.ui button styles). */
    private static final Value<String> TAB_STYLE_ACTIVE = Value.ref("Common.ui", "DefaultTextButtonStyle");
    private static final Value<String> TAB_STYLE_INACTIVE = Value.ref("Common.ui", "SecondaryTextButtonStyle");

    @Nonnull
    private final Ref<ChunkStore> blockRef;
    /** Programmer display name shown in the title bar. */
    @Nonnull
    private final String title;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType =
        BlockModule.BlockStateInfo.getComponentType();
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType = BlockChunk.getComponentType();

    /** The machine whose recipe pool the browser is currently scoped to (defaults to the first machine). */
    @Nonnull
    private Machine activeMachine = MachineRecipePools.MACHINES.get(0);
    /** The current (lowercased, trimmed) search query; empty = match all (still capped). */
    @Nonnull
    private String searchQuery = "";

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

        // Machine-filter button-bar: one Activating binding per machine (payload = MachineId).
        for (Machine m : MachineRecipePools.MACHINES) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, machineBtnSelector(m),
                new EventData().append("Type", EventType.MACHINE).append("MachineId", m.id()), false);
        }
        // Live search (ValueChanged resolves the field's current value into @SearchQuery).
        eventBuilder.addEventBinding(CustomUIEventBindingType.ValueChanged, "#SearchInput",
            new EventData().append("Type", EventType.SEARCH).append("@SearchQuery", "#SearchInput.Value"), false);
        // Take Card (P3.1).
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TakeCard",
            new EventData().append("Type", EventType.TAKE_CARD), false);

        this.updateMachineStyles(commandBuilder);
        this.buildRecipeList(commandBuilder, eventBuilder);
        this.buildProgram(commandBuilder);
    }

    @Override
    public void handleDataEvent(
            @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        String type = data.type;
        if (type == null || type.isEmpty()) {
            return;
        }
        World world = this.resolveWorld();
        if (world == null) {
            return;
        }
        // Events may arrive off the WorldThread: do all ECS + asset-registry work on it.
        world.execute(() -> {
            try {
                this.handleOnWorld(ref, store, data);
            } catch (Throwable ignored) {
                // never let a browser event crash the world thread
            }
        });
    }

    /** Route a decoded event on the WorldThread. Guarded by the caller's catch-Throwable. */
    private void handleOnWorld(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull PageData data) {
        switch (data.type) {
            case EventType.SEARCH -> {
                String q = data.searchQuery == null ? "" : data.searchQuery.trim();
                this.searchQuery = q;
                UICommandBuilder cmd = new UICommandBuilder();
                UIEventBuilder evt = new UIEventBuilder();
                this.buildRecipeList(cmd, evt);
                this.sendUpdate(cmd, evt, false);
            }
            case EventType.MACHINE -> {
                Machine m = MachineRecipePools.byId(data.machineId);
                if (m == null || m == this.activeMachine) {
                    return;
                }
                this.activeMachine = m;
                this.searchQuery = "";
                UICommandBuilder cmd = new UICommandBuilder();
                UIEventBuilder evt = new UIEventBuilder();
                this.updateMachineStyles(cmd);
                cmd.set("#SearchInput.Value", "");
                this.buildRecipeList(cmd, evt);
                this.sendUpdate(cmd, evt, false);
            }
            case EventType.ADD_RECIPE -> this.addRecipe(ref, store, data.recipeId);
            case EventType.TAKE_CARD -> this.takeCardAction(ref, store);
            default -> { /* unknown event: ignore */ }
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Browser : machine filter + search + capped list
    // ----------------------------------------------------------------------------------------------------

    /** Set each machine-filter button's Style (active vs inactive) for the current {@link #activeMachine}. */
    private void updateMachineStyles(@Nonnull UICommandBuilder cmd) {
        for (Machine m : MachineRecipePools.MACHINES) {
            boolean active = m == this.activeMachine;
            cmd.set(machineBtnSelector(m) + ".Style", active ? TAB_STYLE_ACTIVE : TAB_STYLE_INACTIVE);
        }
    }

    /**
     * Rebuild {@code #RecipeList} for the active machine + search query. <b>Filters + caps BEFORE rendering</b>
     * (see class doc / {@link RecipeBrowse#filterAndCap}): the pool's recipes are mapped to rows, filtered by
     * {@link #searchQuery}, truncated to {@link RecipeBrowse#ROW_CAP}, then cleared + re-appended one row at a
     * time. Each row is a {@code Common/TextButton.ui} carrying an Activating binding that adds the recipe.
     * Runs on the WorldThread (resolves recipe outputs against the asset registry).
     */
    private void buildRecipeList(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder evt) {
        cmd.clear("#RecipeList");

        List<RecipeBrowse.Row> candidates = this.candidateRows();
        RecipeBrowse.Result result = RecipeBrowse.filterAndCap(candidates, this.searchQuery, RecipeBrowse.ROW_CAP);

        List<RecipeBrowse.Row> rows = result.rows();
        for (int i = 0; i < rows.size(); i++) {
            RecipeBrowse.Row row = rows.get(i);
            String selector = "#RecipeList[" + i + "]";
            cmd.append("#RecipeList", "Common/TextButton.ui");
            cmd.set(selector + " #Button.Text", row.label());
            evt.addEventBinding(CustomUIEventBindingType.Activating, selector + " #Button",
                new EventData().append("Type", EventType.ADD_RECIPE).append("RecipeId", row.recipeId()), false);
        }

        // "showing N of M (refine search)" : only when capped.
        if (result.isCapped()) {
            cmd.set("#ListNote.Text",
                "Showing " + rows.size() + " of " + result.totalMatched() + " (refine search)");
            cmd.set("#ListNote.Visible", true);
        } else {
            cmd.set("#ListNote.Text", "");
            cmd.set("#ListNote.Visible", false);
        }
    }

    /**
     * Map the active machine's pool to browser rows in the pool's stable id order. The pool resolves against
     * the live recipe registry; each recipe's row label is its primary output item id (fallback: recipe id)
     * via {@link RecipeBrowse#rowLabel}. Guarded : a recipe whose outputs cannot be read still yields a row.
     */
    @Nonnull
    private List<RecipeBrowse.Row> candidateRows() {
        RecipePool pool = MachineRecipePools.pool(this.activeMachine);
        List<RecipeBrowse.Row> rows = new ArrayList<>(pool.stableOrder().size());
        for (String recipeId : pool.stableOrder()) {
            if (recipeId == null) {
                continue;
            }
            rows.add(new RecipeBrowse.Row(recipeId, RecipeBrowse.rowLabel(recipeId, outputItemIds(pool, recipeId))));
        }
        return rows;
    }

    /** The output item ids of {@code recipeId} in {@code pool}, or an empty list (guarded against any throw). */
    @Nonnull
    private static List<String> outputItemIds(@Nonnull RecipePool pool, @Nonnull String recipeId) {
        try {
            CraftingRecipe recipe = pool.map().get(recipeId);
            if (recipe == null) {
                return List.of();
            }
            List<ItemStack> outputs = VanillaCraftBridge.outputs(recipe);
            if (outputs == null || outputs.isEmpty()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>(outputs.size());
            for (ItemStack stack : outputs) {
                if (stack != null && stack.getItemId() != null) {
                    ids.add(stack.getItemId());
                }
            }
            return ids;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /** The selector for a machine-filter button (matches the .ui ids {@code #MachineBtn_<id>}). */
    @Nonnull
    private static String machineBtnSelector(@Nonnull Machine m) {
        return "#MachineBtn_" + m.id();
    }

    // ----------------------------------------------------------------------------------------------------
    // Program : read-only entries + click-to-add + take card
    // ----------------------------------------------------------------------------------------------------

    /**
     * Add {@code recipeId} to the loaded card's program as an INFINITE entry (count 0), de-duped (an already
     * present recipe is a no-op), by re-stamping the card via {@link AutoCraftEngine#writeScript}. Then
     * rebuilds the program view so the add lands visibly. No-op when no recipe id / no card is loaded.
     */
    private void addRecipe(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) {
            return;
        }
        RecipeProgrammerState state = this.programmer();
        if (state == null) {
            return;
        }
        ItemStack card = state.card();
        if (card == null || ItemStack.isEmpty(card)) {
            return;
        }
        RecipeScript current = AutoCraftEngine.cardScript(card);
        List<Entry> entries = new ArrayList<>();
        boolean ordered = false;
        if (current != null) {
            ordered = current.ordered();
            for (Entry e : current.entries()) {
                if (e != null && e.recipeId() != null) {
                    if (e.recipeId().equals(recipeId)) {
                        return; // already in the program: adding again is a no-op
                    }
                    entries.add(e);
                }
            }
        }
        entries.add(new Entry(recipeId, 0)); // 0 = infinite
        RecipeScript next = new RecipeScript(ordered, entries);
        ItemStack stamped = AutoCraftEngine.writeScript(card, next);
        state.setCard(stamped);
        this.markNeedsSaving();

        UICommandBuilder cmd = new UICommandBuilder();
        this.buildProgram(cmd);
        this.sendUpdate(cmd);
    }

    /** Take Card action: return the loaded card + rebuild the program view. */
    private void takeCardAction(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        RecipeProgrammerState state = this.programmer();
        if (state == null) {
            return;
        }
        this.takeCard(ref, store, state);
        this.markNeedsSaving();
        UICommandBuilder cmd = new UICommandBuilder();
        this.buildProgram(cmd);
        this.sendUpdate(cmd);
    }

    /**
     * Populate the program status line ({@code #ProgramText}) + the read-only entries list
     * ({@code #ProgramList}) from the loaded card. Null-safe: no card / blank card render gracefully.
     */
    private void buildProgram(@Nonnull UICommandBuilder cmd) {
        cmd.clear("#ProgramList");
        RecipeProgrammerState state = this.programmer();
        ItemStack card = state == null ? null : state.card();

        if (card == null || ItemStack.isEmpty(card)) {
            cmd.set("#ProgramText.TextSpans", Message.raw("No card loaded."));
            return;
        }
        RecipeScript script = AutoCraftEngine.cardScript(card);
        if (script == null || script.isEmpty()) {
            cmd.set("#ProgramText.TextSpans", Message.raw("Blank card (no program)."));
            return;
        }

        List<Entry> entries = script.entries();
        cmd.set("#ProgramText.TextSpans",
            Message.raw((script.ordered() ? "Ordered" : "Whitelist") + " : " + entries.size() + " entr"
                + (entries.size() == 1 ? "y" : "ies")));

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e == null || e.recipeId() == null) {
                continue;
            }
            String selector = "#ProgramList[" + i + "]";
            String countLabel = RecipeScript.isInfinite(e) ? "x∞" : "x" + e.count();
            cmd.append("#ProgramList", "Common/TextButton.ui");
            cmd.set(selector + " #Button.Text", e.recipeId() + "  " + countLabel);
        }
    }

    /** Resolve the Programmer's {@link RecipeProgrammerState}, or null if the block is gone / not ours. */
    private RecipeProgrammerState programmer() {
        if (!this.blockRef.isValid()) {
            return null;
        }
        return this.blockRef.getStore()
            .getComponent(this.blockRef, ChonbosChemistry.getInstance().recipeProgrammerComponentType());
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

    /** Mark the Programmer's chunk needing-save after a card mutation (WrenchInteraction pattern). */
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

    /** The event payload: which control fired + its parameters (machine id / search text / recipe id). */
    public static final class PageData {
        private String type = "";
        private String machineId;
        private String searchQuery;
        private String recipeId;

        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
            .append(new KeyedCodec<>("Type", Codec.STRING, false),
                (o, v) -> o.type = v == null ? "" : v, o -> o.type).add()
            .append(new KeyedCodec<>("MachineId", Codec.STRING, false),
                (o, v) -> o.machineId = v, o -> o.machineId).add()
            .append(new KeyedCodec<>("@SearchQuery", Codec.STRING, false),
                (o, v) -> o.searchQuery = v, o -> o.searchQuery).add()
            .append(new KeyedCodec<>("RecipeId", Codec.STRING, false),
                (o, v) -> o.recipeId = v, o -> o.recipeId).add()
            .build();
    }

    /** The event types, as the literal {@code Type} strings bound in the .ui event data. */
    private static final class EventType {
        static final String MACHINE = "Machine";
        static final String SEARCH = "Search";
        static final String ADD_RECIPE = "AddRecipe";
        static final String TAKE_CARD = "TakeCard";

        private EventType() {
        }
    }
}
