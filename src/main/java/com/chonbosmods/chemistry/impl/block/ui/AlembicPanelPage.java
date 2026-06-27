package com.chonbosmods.chemistry.impl.block.ui;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.impl.block.MachineEject;
import com.chonbosmods.chemistry.impl.block.craft.AlembicState;
import com.chonbosmods.chemistry.impl.block.craft.AlembicTickSystem;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
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
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.ui.ItemGridSlot;
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
import org.joml.Vector3d;

/**
 * READ-ONLY panel for the CC Alembic ({@code Pages/CC_AlembicPanel.ui}). It is the sibling of
 * {@link BenchMachinePanelPage} (Smelter/Reclaimer) but reads a {@link AlembicState} rather than a
 * {@code MachineBlockState}: the Alembic is an AUTONOMOUS crafter, so it owns its input/output containers,
 * energy buffer, craft progress (raw seconds), and an inserted recipe-card slot directly, with no held
 * vanilla bench to delegate to.
 *
 * <p>Layout mirrors the bench panel (status/controls on the LEFT, processing I/O on the RIGHT) and ADDS a
 * single-slot {@code #CardSlot} for the inserted recipe card. Like the bench panel the slots are read-only
 * display ({@code AreItemsDraggable: false}): items flow through pipes, not by hand, and the recipe card is
 * DISPLAY-ONLY for v1 (card insert/remove via hand is deferred: the card-programming UX is a later task).
 *
 * <p>The left "input" grid is NOT a push-fed input buffer: the Alembic is a demand-driven puller, so its
 * {@code held()} container holds ONLY the active craft's pulled ingredients (empty when idle, a few stacks
 * during a craft). The panel DISPLAYS those held ingredients as a scrolling list ({@code #InputGrid}, filled
 * slots only): empty while idle, populated during a craft, scrolling if a recipe ever needs more than the
 * viewport. The output grid ({@code #OutputGrid}) is the fixed 4-slot result buffer and shows empty slots.
 *
 * <p>The two player controls are the same as the bench panel: an On/Off toggle (writes
 * {@link AlembicState#setEnabled(boolean)}) and an Eject button draining the Alembic's own input + output
 * containers into the player's inventory ({@link MachineEject}), dropping overflow at the player's feet.
 *
 * <p>Progress bar fraction is {@code progress() / } the ACTIVE recipe's real cook time via
 * {@link BenchMachinePanelText#forgeFraction}, with the duration resolved by
 * {@link AlembicTickSystem#craftDurationFor} (the same per-recipe {@code getTimeSeconds}, or
 * {@code ALEMBIC_DEFAULT_DURATION} fallback, the tick times the craft by). So the bar fills exactly over each
 * craft regardless of recipe: a 5s pie and a 2s grill both reach 100% at completion, never early or late.
 * A craft is treated as ACTIVE while {@code currentRecipeId() != null} (the tick's real "crafting" definition:
 * on the START tick the ingredients are pulled but progress is still 0, yet the Alembic IS crafting, so
 * {@code progress > 0} would wrongly flash "Idle").
 *
 * <p><b>Threading:</b> identical to {@link BenchMachinePanelPage} — {@link #build}/{@link #refresh()} run on
 * the WorldThread; button events ({@link #handleDataEvent}) may arrive off it, so the ECS mutations are
 * wrapped in {@link World#execute(Runnable)}. Live-refreshes via {@link PanelRefreshService}.
 */
public final class AlembicPanelPage extends InteractiveCustomUIPage<AlembicPanelPage.PageData>
        implements PanelRefreshService.LivePanel {

    @Nonnull
    private final Ref<ChunkStore> blockRef;
    /** Alembic display name shown in the title bar. */
    @Nonnull
    private final String title;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType =
        BlockModule.BlockStateInfo.getComponentType();
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType = BlockChunk.getComponentType();
    private World registeredWorld;
    /**
     * The enabled state last reflected in {@code #PowerToggle.Text} for THIS open panel (per-instance).
     * Null = never sent yet, so the first {@link #applyState} always sends. We only re-send the clickable
     * button's text when this differs from the live {@code enabled}, to avoid re-rendering the button every
     * refresh (a redundant re-render can swallow a click landing in that window).
     */
    private Boolean lastSentEnabled;

    public AlembicPanelPage(
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
        commandBuilder.append("Pages/CC_AlembicPanel.ui");
        commandBuilder.set("#PanelTitle.TextSpans", Message.raw(this.title));
        applyState(commandBuilder);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#PowerToggle",
            new EventData().append("Action", Action.TOGGLE));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#EjectBtn",
            new EventData().append("Action", Action.EJECT));

        World world = this.resolveWorld();
        if (world != null) {
            this.registeredWorld = world;
            ChonbosChemistry.getInstance().panelRefreshService().register(world, this);
        }
    }

    @Override
    public boolean refresh() {
        if (!this.playerRef.isValid()) {
            return false;
        }
        if (this.alembic() == null) {
            this.close(); // alembic broken mid-view: auto-close
            return false;
        }
        UICommandBuilder update = new UICommandBuilder();
        applyState(update);
        this.sendUpdate(update);
        return true;
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
        AlembicState alembic = this.alembic();
        if (alembic == null) {
            return;
        }
        if (Action.TOGGLE.equals(action)) {
            alembic.setEnabled(!alembic.isEnabled());
            this.markNeedsSaving();
        } else if (Action.EJECT.equals(action)) {
            this.eject(ref, store, alembic);
            this.markNeedsSaving();
        } else {
            return;
        }
        UICommandBuilder update = new UICommandBuilder();
        applyState(update);
        this.sendUpdate(update);
    }

    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (this.registeredWorld != null) {
            ChonbosChemistry.getInstance().panelRefreshService().deregister(this.registeredWorld, this);
            this.registeredWorld = null;
        }
        super.onDismiss(ref, store);
    }

    /** Resolve the Alembic's {@link AlembicState}, or null if the block is gone / not a Alembic. */
    private AlembicState alembic() {
        if (!this.blockRef.isValid()) {
            return null;
        }
        return this.blockRef.getStore()
            .getComponent(this.blockRef, ChonbosChemistry.getInstance().alembicComponentType());
    }

    /** Populate every panel selector from the current Alembic state. Null-safe throughout. */
    private void applyState(@Nonnull UICommandBuilder cmd) {
        AlembicState alembic = this.alembic();
        if (alembic == null) {
            return;
        }
        boolean enabled = alembic.isEnabled();
        // Toggle button shows the ACTION (mode to switch to), not the current state: ON -> "Turn Off".
        // Only re-send when the state actually changed: re-rendering this clickable button every refresh
        // can drop a click landing in that window. Other selectors below are display-only and re-send freely.
        if (lastSentEnabled == null || lastSentEnabled != enabled) {
            cmd.set("#PowerToggle.Text", enabled ? "Turn Off" : "Turn On");
            lastSentEnabled = enabled;
        }

        // Held = the active craft's pulled ingredients: show FILLED slots only (idle -> empty list).
        cmd.set("#InputGrid.Slots", filledSlotsOf(alembic.held()));
        cmd.set("#InputGrid.DisplayItemQuantity", true);
        cmd.set("#OutputGrid.Slots", slotsOf(alembic.output()));
        cmd.set("#OutputGrid.DisplayItemQuantity", true);

        // Recipe card: single read-only slot (DISPLAY-ONLY for v1; insert/remove deferred).
        cmd.set("#CardSlot.Slots", cardSlot(alembic.card()));
        cmd.set("#CardSlot.DisplayItemQuantity", false);

        // The Alembic has no tier upgrades yet (tier 0).
        cmd.set("#TierText.TextSpans", Message.raw("Tier: 0"));

        // A craft is in flight while a recipe is loaded (start tick pulls ingredients before progress > 0).
        // Progress fraction is raw-seconds based: seconds -> 0..1 against the ACTIVE recipe's real cook time
        // (the same duration the tick is timing this craft by), so the bar fills exactly over the craft: no
        // pausing at 100% for a long recipe, no completing before 100% for a short one. Idle -> default.
        float progressSeconds = alembic.progress();
        boolean active = alembic.currentRecipeId() != null;
        float duration = AlembicTickSystem.craftDurationFor(alembic.currentRecipeId());
        float frac = BenchMachinePanelText.forgeFraction(progressSeconds, duration);
        cmd.set("#ProgressBar.Value", frac);
        cmd.set("#ProgressText.TextSpans", Message.raw(BenchMachinePanelText.progress(enabled, frac)));

        long stored = 0;
        long capacity = 0;
        EnergyHandler energy = alembic.energy();
        if (energy != null) {
            stored = energy.getStored();
            capacity = energy.getMaxStored();
        }
        float powerFrac = capacity > 0 ? (float) stored / capacity : 0.0F;
        cmd.set("#PowerBar.Value", BenchMachinePanelText.clamp01(powerFrac));
        cmd.set("#EnergyText.TextSpans", Message.raw(BenchMachinePanelText.energy(stored, capacity)));
        cmd.set("#StatusText.TextSpans",
            Message.raw("Status: " + BenchMachinePanelText.state(enabled, active)));
    }

    /** One {@link ItemGridSlot} per container slot (empty slots render empty); read-only display only. */
    private static List<ItemGridSlot> slotsOf(ItemContainer container) {
        List<ItemGridSlot> slots = new ArrayList<>();
        if (container == null) {
            return slots;
        }
        short capacity = container.getCapacity();
        for (short s = 0; s < capacity; s++) {
            ItemStack stack = container.getItemStack(s);
            slots.add(stack == null || ItemStack.isEmpty(stack)
                ? new ItemGridSlot()
                : new ItemGridSlot(stack));
        }
        return slots;
    }

    /**
     * One {@link ItemGridSlot} per NON-EMPTY stack only (empties/nulls skipped); read-only display. Used for
     * the held ingredients list so an idle Alembic renders an empty list and a crafting Alembic renders just the
     * pulled ingredient stacks.
     */
    private static List<ItemGridSlot> filledSlotsOf(ItemContainer container) {
        List<ItemGridSlot> slots = new ArrayList<>();
        if (container == null) {
            return slots;
        }
        short capacity = container.getCapacity();
        for (short s = 0; s < capacity; s++) {
            ItemStack stack = container.getItemStack(s);
            if (stack != null && !ItemStack.isEmpty(stack)) {
                slots.add(new ItemGridSlot(stack));
            }
        }
        return slots;
    }

    /** The single recipe-card slot: the card item, or one empty slot when no card is loaded. */
    private static List<ItemGridSlot> cardSlot(ItemStack card) {
        List<ItemGridSlot> slots = new ArrayList<>(1);
        slots.add(card == null || ItemStack.isEmpty(card) ? new ItemGridSlot() : new ItemGridSlot(new ItemStack(card.getItemId(), Math.max(1, card.getQuantity()))));
        return slots;
    }

    /** Drain the Alembic's own input + output containers into the player's inventory; drop overflow at feet. */
    private void eject(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull AlembicState alembic) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        CombinedItemContainer inventory = player.getInventory().getCombinedHotbarFirst();
        ItemContainerView invView = new ItemContainerView(inventory);
        MachineEject.InventorySink sink =
            (id, metadata, amount) -> invView.insert(new ItemKey(id, amount), metadata, amount, false);

        List<ContainerView> sources = List.of(
            new ItemContainerView(alembic.held()),
            new ItemContainerView(alembic.output()));
        List<MachineEject.EjectStack> overflow = MachineEject.ejectAll(sources, sink);

        // Ejecting the held ingredients mid-craft MUST cancel the active craft. Otherwise next tick still
        // sees currentRecipeId != null (crafting == true) and advances/completes a craft whose ingredients
        // have already left into the player's inventory: the output is produced from nothing and the player
        // keeps both the ingredients and the finished item (item duplication). Resetting here drops the Alembic
        // back to IDLE next tick, which re-pulls fresh only if the ingredients are still available. This runs
        // on the WorldThread (applyAction is invoked via world.execute), so it is race-safe against the tick.
        // Placed before the overflow handling so the reset ALWAYS runs, even on the early returns below.
        alembic.setCurrentRecipeId(null);
        alembic.setProgress(0f);

        // Also return the inserted recipe card to the player. Insert via the SAME inventory sink the drained
        // items use (hotbar-first); any leftover joins the overflow so the block below drops it at the
        // player's feet. Then clear the slot + per-card script progress so the card can't be re-read. Guarded
        // null-safe; no dup/loss (the card is returned first, then cleared).
        ItemStack card = alembic.card();
        if (card != null && !ItemStack.isEmpty(card)) {
            int accepted = sink.insert(card.getItemId(), card.getMetadata(), card.getQuantity());
            int leftover = card.getQuantity() - accepted;
            if (leftover > 0) {
                overflow.add(new MachineEject.EjectStack(card.getItemId(), leftover, card.getMetadata()));
            }
            alembic.setCard(null);
            alembic.clearScriptProgress();
        }

        if (overflow.isEmpty()) {
            return;
        }

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return; // can't place a drop without a position; the items stay in the Alembic
        }
        Vector3d pos = transform.getPosition();
        List<ItemStack> drops = new ArrayList<>();
        for (MachineEject.EjectStack es : overflow) {
            ItemStack drop = new ItemStack(es.id(), es.count());
            if (es.metadata() != null) {
                drop = drop.withMetadata(es.metadata().clone());
            }
            drops.add(drop);
        }
        Holder<EntityStore>[] holders = ItemComponent.generateItemDrops(
            store, drops, new Vector3d(pos.x, pos.y, pos.z), Rotation3f.IDENTITY);
        store.addEntities(holders, AddReason.SPAWN);
    }

    /** Mark the Alembic's chunk needing-save after a toggle/eject (WrenchInteraction pattern). */
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

    /** The event payload: which control fired ({@link Action#TOGGLE}/{@link Action#EJECT}). */
    public static final class PageData {
        private String action = "";

        public static final BuilderCodec<PageData> CODEC = BuilderCodec.builder(PageData.class, PageData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING, false),
                (o, v) -> o.action = v == null ? "" : v, o -> o.action).add()
            .build();
    }

    /** The two button actions, as the literal strings bound in the .ui event data. */
    private static final class Action {
        static final String TOGGLE = "Toggle";
        static final String EJECT = "Eject";

        private Action() {
        }
    }
}
