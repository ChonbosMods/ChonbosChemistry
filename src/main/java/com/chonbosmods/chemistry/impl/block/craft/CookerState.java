package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.impl.block.EnergyBuffer;
import com.chonbosmods.chemistry.impl.block.PortConfig;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.List;

/**
 * The persistent ECS state the Cooker block carries on the {@link ChunkStore}. Unlike the processing
 * machines (Smelter/Reclaimer) whose {@code MachineBlockState} HOLDS a vanilla
 * {@code ProcessingBenchBlock} to borrow its input/output containers, the Cooker has no vanilla bench
 * block to delegate to: it is an autonomous crafter and therefore owns its own containers and craft
 * state here.
 *
 * <h2>Fields</h2>
 * <ul>
 *   <li>{@code energy}: the power buffer (optional; a Cooker with no power decodes to null), mirroring
 *       {@code MachineBlockState}'s "Energy" key.</li>
 *   <li>{@code ports}: the per-face port configuration, mirroring {@code MachineBlockState}'s "Ports".</li>
 *   <li>{@code held}: the ingredients pulled for the ACTIVE craft (empty when idle; generous size so a
 *       future many-ingredient recipe fits, and the GUI scrolls it).</li>
 *   <li>{@code output}: the 4-slot result container.</li>
 *   <li>{@code currentRecipeId}: the recipe currently being crafted (null = idle = no active craft).</li>
 *   <li>{@code card}: the inserted recipe card item (optional; null = no card), omitted on encode when
 *       null exactly like the "Energy" object-codec pattern.</li>
 *   <li>{@code progress}: accumulated craft seconds.</li>
 *   <li>{@code lastSelectedId}: the round-robin cursor over selectable recipes (optional String).</li>
 *   <li>{@code enabled}: the On/Off control line (default ON), mirroring {@code MachineBlockState}.</li>
 * </ul>
 *
 * <h2>clone()</h2>
 * Deep copy is performed by a codec round-trip (encode then decode), exactly like
 * {@code MachineBlockState}, so each placed Cooker receives fully independent containers + buffers.
 */
public final class CookerState implements Component<ChunkStore>, AutoCraftNode {

    /** Held slots: generous size so a future many-ingredient recipe fits (the GUI scrolls it). */
    private static final short HELD_SLOTS = 27;
    /** Result slots. */
    private static final short OUTPUT_SLOTS = 4;

    public static final BuilderCodec<CookerState> CODEC = BuilderCodec.builder(CookerState.class, CookerState::new)
        // Energy is optional: a Cooker may carry no power. EnergyBuffer.CODEC is an object codec, so a
        // null value is simply omitted on encode and decodes back to null. Mirrors MachineBlockState.
        .append(new KeyedCodec<>("Energy", EnergyBuffer.CODEC), (o, v) -> o.energy = v, o -> o.energy).add()
        .append(new KeyedCodec<>("Ports", PortConfig.CODEC), (o, v) -> o.ports = v, o -> o.ports).add()
        .append(new KeyedCodec<>("Held", SimpleItemContainer.CODEC), (o, v) -> o.held = v, o -> o.held).add()
        .append(new KeyedCodec<>("Output", SimpleItemContainer.CODEC), (o, v) -> o.output = v, o -> o.output).add()
        // The inserted recipe card: optional. ItemStack.CODEC is an object codec, so a null value is
        // omitted on encode and an absent key decodes back to null (same pattern as "Energy").
        .append(new KeyedCodec<>("Card", ItemStack.CODEC), (o, v) -> o.card = v, o -> o.card).add()
        .append(new KeyedCodec<>("Progress", Codec.FLOAT), (o, v) -> o.progress = v, o -> o.progress).add()
        // Round-robin cursor over selectable recipes. OPTIONAL (3-arg, not-required): a fresh/unselected
        // Cooker has no cursor, so the key is omitted and the field stays null. Codec.STRING is an object
        // codec (not a PrimitiveCodec), so a null value omits on encode and an absent key reaches the
        // setter as null rather than tripping a primitive non-null check.
        .append(new KeyedCodec<>("Cursor", Codec.STRING, false),
                (o, v) -> o.lastSelectedId = v, o -> o.lastSelectedId).add()
        // The recipe currently being crafted. OPTIONAL (3-arg, not-required): an idle Cooker has no active
        // craft, so the key is omitted and the field stays null. Same object-codec pattern as "Cursor".
        .append(new KeyedCodec<>("Current", Codec.STRING, false),
                (o, v) -> o.currentRecipeId = v, o -> o.currentRecipeId).add()
        // On/Off control line (default ON). OPTIONAL (3-arg, not-required): legacy/fresh data with no
        // "Enabled" key leaves the field its true default. Mirrors MachineBlockState verbatim.
        .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                (o, v) -> o.enabled = v, o -> o.enabled).add()
        .build();

    private EnergyBuffer energy;
    private PortConfig ports = PortConfig.of(List.of());
    private SimpleItemContainer held = new SimpleItemContainer(HELD_SLOTS);
    private SimpleItemContainer output = new SimpleItemContainer(OUTPUT_SLOTS);
    /** The recipe currently being crafted, or null if idle (no active craft). See the "Current" key. */
    private String currentRecipeId;
    /** The inserted recipe card, or null if no card is loaded. See the "Card" codec key. */
    private ItemStack card;
    /** Accumulated craft seconds. */
    private float progress;
    /**
     * Transient post-craft pause: ticks remaining to idle after a completed craft before sourcing the next
     * recipe (a cosmetic beat so a completion reads visually before the next pull begins). NOT codec-persisted
     * (it is momentary; a reload simply starts with no pending pause).
     */
    private int craftDelay;
    /** Round-robin cursor over selectable recipes (null until first selection). See the "Cursor" key. */
    private String lastSelectedId;
    /** On/Off control line (default ON); the circuit run/halt seam. See the "Enabled" codec key. */
    private boolean enabled = true;

    /** Public no-arg constructor for the codec supplier. */
    public CookerState() {
    }

    // --- energy/ports accessors (mirror MachineBlockState) ---

    /** @return the energy handler, or null if this Cooker carries no power. */
    @Override
    public EnergyHandler energy() {
        return energy;
    }

    @Override
    public PortConfig ports() {
        return ports;
    }

    /** Replaces this Cooker's port configuration. A null config resets to empty. */
    public void setPorts(PortConfig ports) {
        this.ports = ports != null ? ports : PortConfig.of(List.of());
    }

    // --- container accessors (the Cooker owns these directly) ---

    /** @return the held ingredients pulled for the active craft (empty when idle). */
    @Override
    public SimpleItemContainer held() {
        return held;
    }

    /** @return the 4-slot result container. */
    @Override
    public SimpleItemContainer output() {
        return output;
    }

    // --- current craft ---

    /** @return the recipe currently being crafted, or null if idle (no active craft). */
    @Override
    public String currentRecipeId() {
        return currentRecipeId;
    }

    @Override
    public void setCurrentRecipeId(String currentRecipeId) {
        this.currentRecipeId = currentRecipeId;
    }

    // --- recipe card ---

    /** @return the inserted recipe card item, or null if no card is loaded. */
    @Override
    public ItemStack card() {
        return card;
    }

    /** Sets (or clears, when null) the inserted recipe card. */
    public void setCard(ItemStack card) {
        this.card = card;
    }

    // --- craft progress ---

    /** @return accumulated craft seconds. */
    @Override
    public float progress() {
        return progress;
    }

    @Override
    public void setProgress(float progress) {
        this.progress = progress;
    }

    // --- post-craft visual pause (transient; not persisted) ---

    /** @return ticks remaining to idle after a completed craft before sourcing the next recipe. */
    @Override
    public int craftDelay() {
        return craftDelay;
    }

    @Override
    public void setCraftDelay(int craftDelay) {
        this.craftDelay = Math.max(0, craftDelay);
    }

    // --- round-robin cursor ---

    /** @return the last selected recipe id (round-robin cursor), or null until first selection. */
    @Override
    public String lastSelectedId() {
        return lastSelectedId;
    }

    @Override
    public void setLastSelectedId(String lastSelectedId) {
        this.lastSelectedId = lastSelectedId;
    }

    // --- enabled (On/Off; the circuit run/halt control line) ---

    /**
     * @return whether this Cooker is ON (enabled). When OFF, the tick holds it: no crafting, but its
     *     held ingredients and buffered power are retained. Mirrors {@code MachineBlockState.isEnabled()}.
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Deep copy via codec round-trip: for placement/copy only (one-time block placement), NOT the
     * per-tick hot path. Mirrors {@code MachineBlockState.clone()}.
     */
    @Override
    public Component<ChunkStore> clone() {
        return CODEC.decode(CODEC.encode(this, EmptyExtraInfo.EMPTY), EmptyExtraInfo.EMPTY);
    }
}
