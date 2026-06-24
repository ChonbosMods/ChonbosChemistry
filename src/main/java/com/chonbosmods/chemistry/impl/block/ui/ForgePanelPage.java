package com.chonbosmods.chemistry.impl.block.ui;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.impl.block.MachineEject;
import com.chonbosmods.chemistry.impl.block.craft.ForgeCraftState;
import com.chonbosmods.chemistry.impl.block.craft.ForgeTickSystem;
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
 * READ-ONLY panel for the CC Forge ({@code Pages/CC_ForgePanel.ui}). It is the sibling of
 * {@link BenchMachinePanelPage} (Smelter/Reclaimer) but reads a {@link ForgeCraftState} rather than a
 * {@code MachineBlockState}: the Forge is an AUTONOMOUS crafter, so it owns its input/output containers,
 * energy buffer, craft progress (raw seconds), and an inserted recipe-card slot directly, with no held
 * vanilla bench to delegate to.
 *
 * <p>Layout mirrors the bench panel (status/controls on the LEFT, processing I/O on the RIGHT) and ADDS a
 * single-slot {@code #CardSlot} for the inserted recipe card. Like the bench panel the slots are read-only
 * display ({@code AreItemsDraggable: false}): items flow through pipes, not by hand, and the recipe card is
 * DISPLAY-ONLY for v1 (card insert/remove via hand is deferred — the card-programming UX is a later task).
 *
 * <p>The two player controls are the same as the bench panel: an On/Off toggle (writes
 * {@link ForgeCraftState#setEnabled(boolean)}) and an Eject button draining the Forge's own input + output
 * containers into the player's inventory ({@link MachineEject}), dropping overflow at the player's feet.
 *
 * <p>Progress bar fraction is {@code progress() / FORGE_DURATION} via
 * {@link BenchMachinePanelText#forgeFraction}; {@code FORGE_DURATION} comes from
 * {@link ForgeTickSystem#FORGE_DURATION} (the single craft-length source of truth shared with the tick). A
 * craft is treated as ACTIVE while {@code progress() > 0}.
 *
 * <p><b>Threading:</b> identical to {@link BenchMachinePanelPage} — {@link #build}/{@link #refresh()} run on
 * the WorldThread; button events ({@link #handleDataEvent}) may arrive off it, so the ECS mutations are
 * wrapped in {@link World#execute(Runnable)}. Live-refreshes via {@link PanelRefreshService}.
 */
public final class ForgePanelPage extends InteractiveCustomUIPage<ForgePanelPage.PageData>
        implements PanelRefreshService.LivePanel {

    @Nonnull
    private final Ref<ChunkStore> blockRef;
    /** Forge display name shown in the title bar. */
    @Nonnull
    private final String title;
    private final ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType =
        BlockModule.BlockStateInfo.getComponentType();
    private final ComponentType<ChunkStore, BlockChunk> blockChunkType = BlockChunk.getComponentType();
    private World registeredWorld;

    public ForgePanelPage(
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
        commandBuilder.append("Pages/CC_ForgePanel.ui");
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
        if (this.forge() == null) {
            this.close(); // forge broken mid-view: auto-close
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
        ForgeCraftState forge = this.forge();
        if (forge == null) {
            return;
        }
        if (Action.TOGGLE.equals(action)) {
            forge.setEnabled(!forge.isEnabled());
            this.markNeedsSaving();
        } else if (Action.EJECT.equals(action)) {
            this.eject(ref, store, forge);
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

    /** Resolve the Forge's {@link ForgeCraftState}, or null if the block is gone / not a Forge. */
    private ForgeCraftState forge() {
        if (!this.blockRef.isValid()) {
            return null;
        }
        return this.blockRef.getStore()
            .getComponent(this.blockRef, ChonbosChemistry.getInstance().forgeComponentType());
    }

    /** Populate every panel selector from the current Forge state. Null-safe throughout. */
    private void applyState(@Nonnull UICommandBuilder cmd) {
        ForgeCraftState forge = this.forge();
        if (forge == null) {
            return;
        }
        boolean enabled = forge.isEnabled();
        // Toggle button shows the ACTION (mode to switch to), not the current state: ON -> "Turn Off".
        cmd.set("#PowerToggle.Text", enabled ? "Turn Off" : "Turn On");

        cmd.set("#InputGrid.Slots", slotsOf(forge.held()));
        cmd.set("#InputGrid.DisplayItemQuantity", true);
        cmd.set("#OutputGrid.Slots", slotsOf(forge.output()));
        cmd.set("#OutputGrid.DisplayItemQuantity", true);

        // Recipe card: single read-only slot (DISPLAY-ONLY for v1; insert/remove deferred).
        cmd.set("#CardSlot.Slots", cardSlot(forge.card()));
        cmd.set("#CardSlot.DisplayItemQuantity", false);

        // The Forge has no tier upgrades yet (tier 0).
        cmd.set("#TierText.TextSpans", Message.raw("Tier: 0"));

        // A craft is in flight while progress has accumulated; raw seconds -> 0..1 against FORGE_DURATION.
        float progressSeconds = forge.progress();
        boolean active = progressSeconds > 0.0F;
        float frac = BenchMachinePanelText.forgeFraction(progressSeconds, ForgeTickSystem.FORGE_DURATION);
        cmd.set("#ProgressBar.Value", frac);
        cmd.set("#ProgressText.TextSpans", Message.raw(BenchMachinePanelText.progress(enabled, active, frac)));

        long stored = 0;
        long capacity = 0;
        EnergyHandler energy = forge.energy();
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

    /** The single recipe-card slot: the card item, or one empty slot when no card is loaded. */
    private static List<ItemGridSlot> cardSlot(ItemStack card) {
        List<ItemGridSlot> slots = new ArrayList<>(1);
        slots.add(card == null || ItemStack.isEmpty(card) ? new ItemGridSlot() : new ItemGridSlot(card));
        return slots;
    }

    /** Drain the Forge's own input + output containers into the player's inventory; drop overflow at feet. */
    private void eject(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull ForgeCraftState forge) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        CombinedItemContainer inventory = player.getInventory().getCombinedHotbarFirst();
        ItemContainerView invView = new ItemContainerView(inventory);
        MachineEject.InventorySink sink =
            (id, metadata, amount) -> invView.insert(new ItemKey(id, amount), metadata, amount, false);

        List<ContainerView> sources = List.of(
            new ItemContainerView(forge.held()),
            new ItemContainerView(forge.output()));
        List<MachineEject.EjectStack> overflow = MachineEject.ejectAll(sources, sink);
        if (overflow.isEmpty()) {
            return;
        }

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return; // can't place a drop without a position; the items stay in the Forge
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

    /** Mark the Forge's chunk needing-save after a toggle/eject (WrenchInteraction pattern). */
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
