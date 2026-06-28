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
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
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
 * The Recipe Programmer bench panel ({@code Pages/CC_RecipeProgrammerPanel.ui}). Three columns toward the
 * native crafting-bench look:
 *
 * <ul>
 *   <li><b>LEFT : an ICON GRID browser.</b> A machine-filter button-bar ({@code #MachineBtn*} : one
 *       SecondaryTextButton per machine) + live search drives a filtered (UNCAPPED) list of recipes (via
 *       {@link RecipeBrowse#filter}); every matching row is rendered into an {@code ItemGrid #RecipeGrid} as
 *       {@link ItemGridSlot}s of the recipe's PRIMARY output stack (icon + quantity). Clicking a recipe slot
 *       fires {@code SlotClicking} (the client auto-injects the {@code SlotIndex}), which selects that recipe;
 *       clicking a machine button scopes the browser to that machine's pool.</li>
 *   <li><b>MIDDLE : a select / count / add pane.</b> The selected recipe's output display NAME, a
 *       {@code NumberField #Count} (0 = infinite), and a blue {@code #AddToCard} button. Add appends an
 *       {@link Entry}{@code (selectedRecipeId, count)} to the loaded card's program (replacing the count when
 *       the recipe is already present) via {@link AutoCraftEngine#writeScript}.</li>
 *   <li><b>RIGHT : the loaded card's program</b> as an {@code ItemGrid #ProgramGrid} : one slot per entry =
 *       the recipe's primary output (finite entries show "icon xN", infinite entries a bare icon). Double-
 *       clicking a slot ({@code SlotDoubleClicking}, same {@code SlotIndex} payload as {@code SlotClicking})
 *       removes that entry. Plus the Take Card button.</li>
 * </ul>
 *
 * <p>Mirrors vanilla {@code EntitySpawnPage} (search + count NumberField + a primary action button reading
 * {@code #Count.Value}) and the {@code ItemGrid} {@code SlotClicking} + {@code SlotIndex} mechanism proven in
 * HyProTech's AlloySmelterPage (the only codebase precedent for a recipe-selector grid).
 *
 * <p><b>SCALE.</b> {@code #RecipeGrid} is a single {@code ItemGrid} (one element + a server-set {@code .Slots}
 * array, NOT cloned per-row sub-trees), so it scrolls fine even at the Sculptor's ~911 recipes :
 * {@link #buildRecipeList} filters by machine scope + search and renders a slot for EVERY match (no cap).
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

    /**
     * The rows currently rendered into {@code #RecipeGrid}, in slot order (row i = grid slot i). Set by
     * {@link #buildRecipeList}; read by the {@code SlotClicking} handler to map {@code SlotIndex} -&gt; recipe
     * id via {@link RecipeBrowse#recipeAtSlot}.
     */
    @Nonnull
    private List<RecipeBrowse.Row> renderedRows = List.of();

    /**
     * The program entries currently rendered into {@code #ProgramGrid}, in slot order (slot i = entry i). Set
     * by {@link #buildProgram}; read by the {@code SlotDoubleClicking} handler to map {@code SlotIndex} -&gt;
     * the entry to remove.
     */
    @Nonnull
    private List<Entry> renderedEntries = List.of();

    /** The recipe id currently selected in the middle pane, or {@code null} when nothing is selected. */
    private String selectedRecipeId;

    /** The {@code #Count} NumberField's MaxValue (mirrors the .ui Format.MaxValue) : the stepper clamp. */
    private static final int MAX_COUNT = 9999;

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
        // Icon-grid recipe selection: clicking a slot fires SlotClicking; the client auto-injects SlotIndex
        // (the clicked slot) into the payload (proven in HyProTech AlloySmelterPage : the only precedent).
        eventBuilder.addEventBinding(CustomUIEventBindingType.SlotClicking, "#RecipeGrid",
            new EventData().append("Type", EventType.SELECT_RECIPE), false);
        // Program grid: DOUBLE-clicking a slot fires SlotDoubleClicking; the client auto-injects SlotIndex the
        // same way SlotClicking does (same Slot* event family) -> remove that entry.
        eventBuilder.addEventBinding(CustomUIEventBindingType.SlotDoubleClicking, "#ProgramGrid",
            new EventData().append("Type", EventType.REMOVE_ENTRY), false);
        // Add-to-card: the primary blue button; reads the NumberField's current value into @Count.
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#AddToCard",
            new EventData().append("Type", EventType.ADD_TO_CARD).append("@Count", "#Count.Value"), false);
        // Count steppers: each reads the LIVE #Count.Value into @Count (no server-side pending state), and
        // the handler writes back a clamped #Count.Value. The field stays directly editable.
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CountDown",
            new EventData().append("Type", EventType.COUNT_DOWN).append("@Count", "#Count.Value"), false);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#CountUp",
            new EventData().append("Type", EventType.COUNT_UP).append("@Count", "#Count.Value"), false);
        // Take Card (P3.1).
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TakeCard",
            new EventData().append("Type", EventType.TAKE_CARD), false);

        // Finite program-entry counts render as "icon xN"; infinite entries (stack of 1) show no number.
        commandBuilder.set("#ProgramGrid.DisplayItemQuantity", true);
        // The ingredient grid shows each input's required quantity ("xN").
        commandBuilder.set("#IngredientGrid.DisplayItemQuantity", true);

        this.updateMachineStyles(commandBuilder);
        this.buildRecipeList(commandBuilder);
        this.updateSelectPane(commandBuilder);
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
                this.buildRecipeList(cmd);
                this.sendUpdate(cmd);
            }
            case EventType.MACHINE -> {
                Machine m = MachineRecipePools.byId(data.machineId);
                if (m == null || m == this.activeMachine) {
                    return;
                }
                this.activeMachine = m;
                this.searchQuery = "";
                this.selectedRecipeId = null;
                UICommandBuilder cmd = new UICommandBuilder();
                this.updateMachineStyles(cmd);
                cmd.set("#SearchInput.Value", "");
                this.buildRecipeList(cmd);
                this.updateSelectPane(cmd);
                this.sendUpdate(cmd);
            }
            case EventType.SELECT_RECIPE -> this.selectRecipe(data.slotIndex);
            case EventType.REMOVE_ENTRY -> this.removeEntry(data.slotIndex);
            case EventType.ADD_TO_CARD -> this.addRecipe(ref, store, this.selectedRecipeId, data.count);
            case EventType.COUNT_DOWN -> this.stepCount(data.count, -1);
            case EventType.COUNT_UP -> this.stepCount(data.count, 1);
            case EventType.TAKE_CARD -> this.takeCardAction(ref, store);
            default -> { /* unknown event: ignore */ }
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Browser : machine filter + search + icon grid
    // ----------------------------------------------------------------------------------------------------

    /** Set each machine-filter button's Style (active vs inactive) for the current {@link #activeMachine}. */
    private void updateMachineStyles(@Nonnull UICommandBuilder cmd) {
        for (Machine m : MachineRecipePools.MACHINES) {
            boolean active = m == this.activeMachine;
            cmd.set(machineBtnSelector(m) + ".Style", active ? TAB_STYLE_ACTIVE : TAB_STYLE_INACTIVE);
        }
    }

    /** The selector for a machine-filter button (matches the .ui ids {@code #MachineBtn<id>}). */
    @Nonnull
    private static String machineBtnSelector(@Nonnull Machine m) {
        return "#MachineBtn" + m.id();
    }

    /**
     * Rebuild {@code #RecipeGrid} for the active machine + search query. Maps the pool's recipes to rows,
     * filters them by {@link #searchQuery} (no cap : it is one scrolling {@code ItemGrid}, not cloned rows) via
     * {@link RecipeBrowse#filter}, then sets them as the grid's {@code .Slots}. Each slot is an
     * {@link ItemGridSlot} of the recipe's primary output stack (icon + quantity), in slot order (the
     * {@link #renderedRows} order : the slot-index -&gt; recipe mapping). Runs on the WorldThread.
     */
    private void buildRecipeList(@Nonnull UICommandBuilder cmd) {
        List<RecipeBrowse.Row> candidates = this.candidateRows();
        List<RecipeBrowse.Row> rows = RecipeBrowse.filter(candidates, this.searchQuery);
        this.renderedRows = rows;

        RecipePool pool = MachineRecipePools.pool(this.activeMachine);
        List<ItemGridSlot> slots = new ArrayList<>(rows.size());
        for (RecipeBrowse.Row row : rows) {
            slots.add(slotFor(pool, row));
        }
        cmd.set("#RecipeGrid.Slots", slots);

        // A plain "N recipes" count of the filtered (uncapped) list.
        cmd.set("#ListNote.Text", rows.size() + (rows.size() == 1 ? " recipe" : " recipes"));
    }

    /**
     * The grid slot for a browser row: an {@link ItemGridSlot} of the recipe's primary output stack
     * ({@code new ItemStack(outputId, qty)} : a metadata-stripped, clean stack to avoid the slot-set error we
     * hit with full stacks). When the output cannot be resolved the slot is empty but still activatable, so the
     * grid keeps stable slot indices (slot i still maps to row i).
     */
    @Nonnull
    private static ItemGridSlot slotFor(@Nonnull RecipePool pool, @Nonnull RecipeBrowse.Row row) {
        ItemGridSlot slot = new ItemGridSlot();
        slot.setActivatable(true);
        slot.setSkipItemQualityBackground(true);
        OutputStack out = outputStack(pool, row.recipeId());
        if (out != null) {
            slot.setItemStack(new ItemStack(out.itemId(), Math.max(1, out.quantity())));
        }
        return slot;
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
        OutputStack out = outputStack(pool, recipeId);
        return out == null ? List.of() : List.of(out.itemId());
    }

    /** The primary output id + quantity of {@code recipeId} in {@code pool}, or {@code null} (guarded). */
    private static OutputStack outputStack(@Nonnull RecipePool pool, @Nonnull String recipeId) {
        try {
            CraftingRecipe recipe = pool.map().get(recipeId);
            if (recipe == null) {
                return null;
            }
            List<ItemStack> outputs = VanillaCraftBridge.outputs(recipe);
            if (outputs == null || outputs.isEmpty()) {
                return null;
            }
            for (ItemStack stack : outputs) {
                if (stack != null && stack.getItemId() != null && !stack.getItemId().isBlank()) {
                    return new OutputStack(stack.getItemId(), Math.max(1, stack.getQuantity()));
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ----------------------------------------------------------------------------------------------------
    // Middle : select pane (name + count + add)
    // ----------------------------------------------------------------------------------------------------

    /**
     * Handle a {@code SlotClicking} on the recipe grid: map the clicked {@code slotIndex} to a recipe via
     * {@link RecipeBrowse#recipeAtSlot} over {@link #renderedRows}, record it as the selection, and refresh the
     * select pane. An out-of-range / stale slot is a no-op.
     */
    private void selectRecipe(int slotIndex) {
        String recipeId = RecipeBrowse.recipeAtSlot(this.renderedRows, slotIndex);
        if (recipeId == null) {
            return;
        }
        this.selectedRecipeId = recipeId;
        UICommandBuilder cmd = new UICommandBuilder();
        this.updateSelectPane(cmd);
        this.sendUpdate(cmd);
    }

    /**
     * Populate the middle select pane from {@link #selectedRecipeId}: the output display NAME ({@code
     * #SelectName}) and a status line ({@code #SelectStatus}). When nothing is selected the name is a prompt
     * and the status is cleared. The count field + Add button are static in the .ui (no per-selection reset).
     */
    private void updateSelectPane(@Nonnull UICommandBuilder cmd) {
        if (this.selectedRecipeId == null) {
            cmd.set("#SelectName.Text", "Select a recipe");
            cmd.set("#SelectStatus.Text", "");
            cmd.set("#IngredientGrid.Slots", new ArrayList<ItemGridSlot>());
            return;
        }
        RecipePool pool = MachineRecipePools.pool(this.activeMachine);
        OutputStack out = outputStack(pool, this.selectedRecipeId);
        String label = out != null ? RecipeBrowse.humanize(out.itemId()) : this.selectedRecipeId;
        if (label.isBlank()) {
            label = this.selectedRecipeId;
        }
        cmd.set("#SelectName.Text", label);
        cmd.set("#SelectStatus.Text", "");
        this.buildIngredients(cmd, pool);
    }

    /**
     * Populate the ingredient ICON GRID ({@code #IngredientGrid}) from the selected recipe's INPUT
     * ingredients (Fuel stripped : CC machines burn ENERGY), one {@link ItemGridSlot} per
     * {@link ItemStack} returned by {@link VanillaCraftBridge#displayInputs} (so "xN" required-quantity
     * shows via {@code DisplayItemQuantity}). Resource-type ("any &lt;resource&gt;") categories render a
     * representative item of that category (or a fallback placeholder), so they SHOW rather than being
     * skipped. Guarded : any read failure clears the grid.
     */
    private void buildIngredients(@Nonnull UICommandBuilder cmd, @Nonnull RecipePool pool) {
        List<ItemGridSlot> slots = new ArrayList<>();
        try {
            CraftingRecipe recipe = pool.map().get(this.selectedRecipeId);
            if (recipe != null) {
                for (ItemStack stack : VanillaCraftBridge.displayInputs(recipe)) {
                    if (stack != null && !ItemStack.isEmpty(stack)) {
                        slots.add(new ItemGridSlot(stack));
                    }
                }
            }
        } catch (Throwable ignored) {
            slots = new ArrayList<>();
        }
        cmd.set("#IngredientGrid.Slots", slots);
    }

    // ----------------------------------------------------------------------------------------------------
    // Program : read-only entries + add + take card
    // ----------------------------------------------------------------------------------------------------

    /**
     * Add {@code recipeId} to the loaded card's program with {@code count} ({@code <= 0} = infinite), by
     * re-stamping the card via {@link AutoCraftEngine#writeScript}. If the recipe is already on the card its
     * count is REPLACED (not duplicated). Then rebuilds the program view. No-ops with a status message when no
     * recipe is selected or no card is loaded.
     */
    private void addRecipe(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, String recipeId, int count) {
        if (recipeId == null || recipeId.isEmpty()) {
            this.setSelectStatus("Select a recipe first.");
            return;
        }
        RecipeProgrammerState state = this.programmer();
        if (state == null) {
            return;
        }
        ItemStack card = state.card();
        if (card == null || ItemStack.isEmpty(card)) {
            this.setSelectStatus("No card loaded.");
            return;
        }
        int safeCount = Math.max(0, count); // 0 = infinite; never store a negative

        RecipeScript current = AutoCraftEngine.cardScript(card);
        List<Entry> entries = new ArrayList<>();
        boolean replaced = false;
        if (current != null) {
            for (Entry e : current.entries()) {
                if (e == null || e.recipeId() == null) {
                    continue;
                }
                if (e.recipeId().equals(recipeId)) {
                    entries.add(new Entry(recipeId, safeCount)); // replace the count in place
                    replaced = true;
                } else {
                    entries.add(e);
                }
            }
        }
        if (!replaced) {
            entries.add(new Entry(recipeId, safeCount));
        }
        RecipeScript next = new RecipeScript(entries);
        ItemStack stamped = AutoCraftEngine.writeScript(card, next);
        state.setCard(stamped);
        this.markNeedsSaving();

        // The positive "added/updated" feedback is intentionally NOT shown : the program grid updates
        // visibly, so the success message was redundant. #SelectStatus is reserved for genuine errors
        // ("No card loaded." / "Select a recipe first.") : clear any stale error on a successful add.
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#SelectStatus.Text", "");
        this.buildProgram(cmd);
        this.sendUpdate(cmd);
    }

    /**
     * Adjust {@code #Count} by {@code delta} from the LIVE field value the binding carried (@Count -&gt;
     * #Count.Value), writing the clamped result straight back to {@code #Count.Value}. No server-side
     * pending count is kept : the NumberField is the single source of truth (it also stays directly
     * editable). Clamped to {@code [0, MAX_COUNT]} (0 = infinite).
     */
    private void stepCount(int current, int delta) {
        int next = Math.max(0, Math.min(MAX_COUNT, current + delta));
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#Count.Value", next);
        this.sendUpdate(cmd);
    }

    /** Set just the middle-pane status line and push it. */
    private void setSelectStatus(@Nonnull String text) {
        UICommandBuilder cmd = new UICommandBuilder();
        cmd.set("#SelectStatus.Text", text);
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
     * Populate the program status line ({@code #ProgramText}) + the program ICON GRID ({@code #ProgramGrid})
     * from the loaded card. Each entry becomes one {@link ItemGridSlot} of the recipe's primary output stack:
     * a FINITE entry ({@code count > 0}) is a stack of {@code count} (renders "icon xN" because the grid sets
     * {@code DisplayItemQuantity}), an INFINITE entry ({@code count <= 0}) is a stack of 1 (no number : the bare
     * icon). The rendered entries are recorded in {@link #renderedEntries} (slot i = entry i) for the remove
     * handler. Null-safe: no card / blank card render an empty grid + a status line.
     */
    private void buildProgram(@Nonnull UICommandBuilder cmd) {
        RecipeProgrammerState state = this.programmer();
        ItemStack card = state == null ? null : state.card();

        if (card == null || ItemStack.isEmpty(card)) {
            this.renderedEntries = List.of();
            cmd.set("#ProgramText.TextSpans", Message.raw("No card loaded."));
            cmd.set("#ProgramGrid.Slots", new ArrayList<ItemGridSlot>());
            return;
        }
        RecipeScript script = AutoCraftEngine.cardScript(card);
        if (script == null || script.isEmpty()) {
            this.renderedEntries = List.of();
            cmd.set("#ProgramText.TextSpans", Message.raw("Blank card (no program)."));
            cmd.set("#ProgramGrid.Slots", new ArrayList<ItemGridSlot>());
            return;
        }

        RecipePool pool = MachineRecipePools.pool(this.activeMachine);
        List<Entry> entries = new ArrayList<>();
        List<ItemGridSlot> slots = new ArrayList<>();
        for (Entry e : script.entries()) {
            if (e == null || e.recipeId() == null) {
                continue;
            }
            entries.add(e);
            slots.add(programSlotFor(pool, e));
        }
        this.renderedEntries = List.copyOf(entries);
        cmd.set("#ProgramGrid.Slots", slots);
        cmd.set("#ProgramText.TextSpans",
            Message.raw(entries.size() + " entr" + (entries.size() == 1 ? "y" : "ies")));
    }

    /**
     * The program-grid slot for an entry: an {@link ItemGridSlot} of the recipe's primary output. A finite
     * entry uses the entry's {@code count} as the stack quantity (so the grid renders "icon xN"); an infinite
     * entry uses a quantity of 1 (a stack of 1 shows no number : the bare icon, as the user wants). When the
     * output cannot be resolved the slot is empty but still activatable, keeping stable slot indices.
     */
    @Nonnull
    private static ItemGridSlot programSlotFor(@Nonnull RecipePool pool, @Nonnull Entry e) {
        ItemGridSlot slot = new ItemGridSlot();
        slot.setActivatable(true);
        slot.setSkipItemQualityBackground(true);
        OutputStack out = outputStack(pool, e.recipeId());
        if (out != null) {
            int qty = RecipeScript.isInfinite(e) ? 1 : Math.max(1, e.count());
            slot.setItemStack(new ItemStack(out.itemId(), qty));
        }
        return slot;
    }

    /**
     * Remove the program entry at the double-clicked {@code slotIndex} (mapped via {@link #renderedEntries}),
     * rewriting the card via {@link AutoCraftEngine#writeScript} with that entry dropped, then rebuild the
     * program grid. An out-of-range / stale slot is a no-op.
     */
    private void removeEntry(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= this.renderedEntries.size()) {
            return;
        }
        Entry target = this.renderedEntries.get(slotIndex);
        if (target == null) {
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
        if (current == null) {
            return;
        }
        List<Entry> kept = new ArrayList<>();
        boolean removed = false;
        for (int i = 0; i < current.entries().size(); i++) {
            Entry e = current.entries().get(i);
            // Drop the FIRST entry that matches the target's recipe id (entries are de-duped on add).
            if (!removed && e != null && e.recipeId() != null && e.recipeId().equals(target.recipeId())) {
                removed = true;
                continue;
            }
            kept.add(e);
        }
        if (!removed) {
            return;
        }
        ItemStack stamped = AutoCraftEngine.writeScript(card, new RecipeScript(kept));
        state.setCard(stamped);
        this.markNeedsSaving();

        UICommandBuilder cmd = new UICommandBuilder();
        this.buildProgram(cmd);
        this.sendUpdate(cmd);
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

    /** A resolved primary output: an item id + quantity. World-free, just a tiny carrier. */
    private record OutputStack(@Nonnull String itemId, int quantity) {
    }

    /** The event payload: which control fired + its parameters (machine id / search / slot index / count). */
    public static final class PageData {
        private String type = "";
        private String machineId;
        private String searchQuery;
        private int slotIndex = -1;
        private int count;

        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
            .append(new KeyedCodec<>("Type", Codec.STRING, false),
                (o, v) -> o.type = v == null ? "" : v, o -> o.type).add()
            .append(new KeyedCodec<>("MachineId", Codec.STRING, false),
                (o, v) -> o.machineId = v, o -> o.machineId).add()
            .append(new KeyedCodec<>("@SearchQuery", Codec.STRING, false),
                (o, v) -> o.searchQuery = v, o -> o.searchQuery).add()
            // SlotIndex is auto-injected by the client on SlotClicking / SlotDoubleClicking (same Slot* family;
            // proven for SlotClicking in HyProTech AlloySmelterPage). Used for machine tabs, recipe select, and
            // program-entry removal.
            .append(new KeyedCodec<>("SlotIndex", Codec.INTEGER, false),
                (o, v) -> o.slotIndex = v == null ? -1 : v, o -> o.slotIndex).add()
            // @Count is resolved from #Count.Value on the Add binding (vanilla EntitySpawnPage pattern).
            .append(new KeyedCodec<>("@Count", Codec.INTEGER, false),
                (o, v) -> o.count = v == null ? 0 : v, o -> o.count).add()
            .build();
    }

    /** The event types, as the literal {@code Type} strings bound in the .ui event data. */
    private static final class EventType {
        static final String MACHINE = "Machine";
        static final String SEARCH = "Search";
        static final String SELECT_RECIPE = "SelectRecipe";
        static final String REMOVE_ENTRY = "RemoveEntry";
        static final String ADD_TO_CARD = "AddToCard";
        static final String COUNT_DOWN = "CountDown";
        static final String COUNT_UP = "CountUp";
        static final String TAKE_CARD = "TakeCard";

        private EventType() {
        }
    }
}
