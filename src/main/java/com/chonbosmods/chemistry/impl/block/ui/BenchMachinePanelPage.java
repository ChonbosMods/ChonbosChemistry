package com.chonbosmods.chemistry.impl.block.ui;

import com.chonbosmods.chemistry.ChonbosChemistry;
import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.MachineEject;
import com.chonbosmods.chemistry.impl.block.bench.VanillaBenchBridge;
import com.chonbosmods.chemistry.impl.block.net.item.ContainerLookup.ContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemContainerView;
import com.chonbosmods.chemistry.impl.block.net.item.ItemKey;
import com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock;
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
 * Shared READ-ONLY panel for every CC machine that wraps a vanilla processing bench ({@code
 * Pages/CC_BenchMachinePanel.ui}): Smelter, Reclaimer, etc. Title + active verb are passed per machine
 * (the rest of the GUI is identical). Shows the held bench's input/output contents (non-draggable
 * {@code ItemGrid}s populated from Java), live progress + power-buffer bars, and two controls the player
 * CAN use — an On/Off toggle and an Eject button. Items move through the machine via pipes, never by hand,
 * so the slots carry no drag-drop. Live-refreshes via {@link PanelRefreshService} like {@link MachinePanelPage}.
 *
 * <p>On/Off writes {@link MachineBlockState#setEnabled(boolean)} — the SAME run/halt seam the future
 * circuit Machine I/O bridge will drive. Eject drains both bench containers into the player's inventory
 * ({@link MachineEject}), dropping the overflow at the player's feet.
 *
 * <p><b>Threading:</b> {@link #build}/{@link #refresh()} run on the WorldThread (interaction tick /
 * {@code PanelRefreshSystem}); button events ({@link #handleDataEvent}) may arrive off it, so the ECS
 * mutations (toggle / eject) are wrapped in {@link World#execute(Runnable)}.
 */
public final class BenchMachinePanelPage extends InteractiveCustomUIPage<BenchMachinePanelPage.PageData>
        implements PanelRefreshService.LivePanel {

    @Nonnull
    private final Ref<ChunkStore> blockRef;
    /** Machine display name shown in the title bar (e.g. "Smelter", "Reclaimer"). */
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

    public BenchMachinePanelPage(
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
        commandBuilder.append("Pages/CC_BenchMachinePanel.ui");
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
        if (this.machine() == null) {
            this.close(); // smelter broken mid-view: auto-close
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
        MachineBlockState machine = this.machine();
        if (machine == null) {
            return;
        }
        if (Action.TOGGLE.equals(action)) {
            machine.setEnabled(!machine.isEnabled());
            this.markNeedsSaving();
        } else if (Action.EJECT.equals(action)) {
            this.eject(ref, store, machine);
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

    /** Resolve the smelter's {@link MachineBlockState}, or null if the block is gone / not a machine. */
    private MachineBlockState machine() {
        if (!this.blockRef.isValid()) {
            return null;
        }
        return this.blockRef.getStore()
            .getComponent(this.blockRef, ChonbosChemistry.getInstance().machineComponentType());
    }

    /** Populate every panel selector from the current machine + held-bench state. Null-safe throughout. */
    private void applyState(@Nonnull UICommandBuilder cmd) {
        MachineBlockState machine = this.machine();
        if (machine == null) {
            return;
        }
        boolean enabled = machine.isEnabled();
        // Toggle button shows the ACTION (mode to switch to), not the current state: ON -> "Turn Off".
        // Only re-send when the state actually changed: re-rendering this clickable button every refresh
        // can drop a click landing in that window. Other selectors below are display-only and re-send freely.
        if (lastSentEnabled == null || lastSentEnabled != enabled) {
            cmd.set("#PowerToggle.Text", enabled ? "Turn Off" : "Turn On");
            lastSentEnabled = enabled;
        }

        ProcessingBenchBlock bench = machine.heldBench();
        cmd.set("#InputGrid.Slots", slotsOf(bench == null ? null : VanillaBenchBridge.input(bench)));
        cmd.set("#InputGrid.DisplayItemQuantity", true);
        cmd.set("#OutputGrid.Slots", slotsOf(bench == null ? null : VanillaBenchBridge.output(bench)));
        cmd.set("#OutputGrid.DisplayItemQuantity", true);

        int tier = machine.heldBenchBlock() != null ? machine.heldBenchBlock().getTierLevel() : 0;
        cmd.set("#TierText.TextSpans", Message.raw("Tier: " + tier));

        float frac = bench == null ? 0.0F : VanillaBenchBridge.progressFraction(bench, tier);
        boolean active = bench != null && VanillaBenchBridge.isActive(bench);
        cmd.set("#ProgressBar.Value", BenchMachinePanelText.clamp01(frac));
        cmd.set("#ProgressText.TextSpans", Message.raw(BenchMachinePanelText.progress(enabled, active, frac)));

        long stored = 0;
        long capacity = 0;
        EnergyHandler energy = machine.energy();
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

    /** Drain both bench containers into the player's inventory; drop the overflow at the player's feet. */
    private void eject(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull MachineBlockState machine) {
        ProcessingBenchBlock bench = machine.heldBench();
        if (bench == null) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }
        CombinedItemContainer inventory = player.getInventory().getCombinedHotbarFirst();
        ItemContainerView invView = new ItemContainerView(inventory);
        MachineEject.InventorySink sink =
            (id, metadata, amount) -> invView.insert(new ItemKey(id, amount), metadata, amount, false);

        List<ContainerView> sources = List.of(
            new ItemContainerView(VanillaBenchBridge.input(bench)),
            new ItemContainerView(VanillaBenchBridge.output(bench)));
        List<MachineEject.EjectStack> overflow = MachineEject.ejectAll(sources, sink);
        if (overflow.isEmpty()) {
            return;
        }

        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return; // can't place a drop without a position; the items stay in the bench
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

    /** Mark the smelter's chunk needing-save after a toggle/eject (WrenchInteraction pattern). */
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
