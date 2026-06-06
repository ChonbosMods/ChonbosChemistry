package com.chonbosmods.chemistry.impl.block.ui;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.chonbosmods.chemistry.api.io.FlowState;
import com.chonbosmods.chemistry.api.io.PortChannel;
import com.chonbosmods.chemistry.impl.block.FaceNames;
import com.chonbosmods.chemistry.impl.block.MachineBlockState;
import com.chonbosmods.chemistry.impl.block.ResourceBuffer;
import com.chonbosmods.chemistry.impl.block.TankBlockState;
import com.chonbosmods.chemistry.impl.block.net.Network;
import com.chonbosmods.chemistry.impl.block.net.PipeNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Pure, testable render model for the machine/pipe panel: a title plus one {@link Row} per label
 * selector in {@code Pages/CC_MachinePanel.ui}, each with an <b>explicit</b> visible flag.
 *
 * <p>Explicit visibility on every row, every pass, is what makes live refresh correct: a buffer that
 * vanishes between refreshes hides its row instead of leaving the last text frozen (the snapshot-v1
 * populate only ever toggled rows <i>on</i>). {@code MachinePanelPage} computes a snapshot and applies
 * it to a {@code UICommandBuilder}, both at build and on each refresh.
 */
public final class PanelSnapshot {

    /** One label row: selector, explicit visibility, and the text (null when hidden). */
    public record Row(@Nonnull String selector, boolean visible, String text) {}

    private static final String ENERGY = "#EnergyLabel";
    private static final String FLUID = "#FluidLabel";
    private static final String GAS = "#GasLabel";
    private static final String ITEM = "#ItemLabel";
    private static final String EMPTY = "#EmptyLabel";
    private static final List<String> SELECTORS = List.of(ENERGY, FLUID, GAS, ITEM, EMPTY);

    private static final String NO_BUFFERS = "This block has no buffers to display.";

    /** Number of cube faces a pipe carries flow state for, in {@code OFFSETS} order. */
    private static final int FACE_COUNT = 6;

    private final String title;
    private final List<Row> rows;
    private final boolean live;

    private PanelSnapshot(@Nonnull String title, @Nonnull Map<String, String> visibleTexts, boolean live) {
        this.title = title;
        this.live = live;
        List<Row> built = new ArrayList<>(SELECTORS.size());
        for (String selector : SELECTORS) {
            String text = visibleTexts.get(selector);
            built.add(new Row(selector, text != null, text));
        }
        this.rows = List.copyOf(built);
    }

    @Nonnull
    public String title() {
        return this.title;
    }

    /** All rows, one per selector, in template order: visibility is always explicit. */
    @Nonnull
    public List<Row> rows() {
        return this.rows;
    }

    /**
     * True for real-content snapshots (machine/tank/network): the page registers for refresh and stays
     * open. False for {@link #empty empty-state} snapshots: at build the page stays a static snapshot;
     * on refresh it auto-closes.
     */
    public boolean live() {
        return this.live;
    }

    @Nonnull
    public static PanelSnapshot forMachine(@Nonnull MachineBlockState machine) {
        Map<String, String> texts = new LinkedHashMap<>();
        EnergyHandler energy = machine.energy();
        if (energy != null) {
            texts.put(ENERGY, "Energy: " + gaugeText(energy.getStored(), energy.getMaxStored()));
        }
        putResourceRow(texts, FLUID, "Fluid", machine.resource(PortChannel.FLUID));
        putResourceRow(texts, GAS, "Gas", machine.resource(PortChannel.GAS));
        putResourceRow(texts, ITEM, "Items", machine.resource(PortChannel.ITEM));
        if (texts.isEmpty()) {
            texts.put(EMPTY, NO_BUFFERS);
        }
        return new PanelSnapshot("Machine", texts, true);
    }

    @Nonnull
    public static PanelSnapshot forTank(@Nonnull TankBlockState tank) {
        PortChannel channel = tank.channel();
        String selector;
        String channelName;
        switch (channel) {
            case GAS -> {
                selector = GAS;
                channelName = "Gas";
            }
            case ITEM -> {
                selector = ITEM;
                channelName = "Items";
            }
            default -> { // FLUID (and POWER, which a tank should never carry, falls back harmlessly)
                selector = FLUID;
                channelName = "Fluid";
            }
        }
        Map<String, String> texts = new LinkedHashMap<>();
        putResourceRow(texts, selector, channelName, tank.resource(channel));
        if (texts.isEmpty()) {
            texts.put(EMPTY, NO_BUFFERS);
        }
        return new PanelSnapshot("Tank", texts, true);
    }

    @Nonnull
    public static PanelSnapshot forNetwork(@Nonnull Network network) {
        return forNetwork(network, null);
    }

    /**
     * Network readout for a specific clicked {@code pipe}: same stats as the 1-arg overload, plus a
     * per-face flow-state row on {@code #GasLabel}. The row lists only the non-NORMAL faces (in
     * face-index order, {@code "<Name> <state>"} entries joined by {@code " · "}), or reads
     * {@code "Faces: all normal"} when every face is {@link FlowState#NORMAL}. A null {@code pipe}
     * (the 1-arg back-compat path) leaves the row hidden.
     */
    @Nonnull
    public static PanelSnapshot forNetwork(@Nonnull Network network, PipeNode pipe) {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put(ENERGY, "Network: " + gaugeText(network.stored(), network.capacity()));
        texts.put(FLUID, "Pipes: " + network.memberKeys().size()
            + " • Throughput: " + network.throughput() + "/tick");
        if (pipe != null) {
            texts.put(GAS, facesText(pipe));
        }
        return new PanelSnapshot(channelDisplayName(network.channel()) + " Pipe Network", texts, true);
    }

    /** "Faces: " + the non-NORMAL faces (name + lowercase state) in index order, or "Faces: all normal". */
    @Nonnull
    private static String facesText(@Nonnull PipeNode pipe) {
        StringBuilder sb = new StringBuilder("Faces: ");
        boolean any = false;
        for (int face = 0; face < FACE_COUNT; face++) {
            FlowState state = pipe.flowState(face);
            if (state == FlowState.NORMAL) {
                continue;
            }
            if (any) {
                sb.append(" · ");
            }
            sb.append(FaceNames.name(face)).append(' ').append(state.jsonValue());
            any = true;
        }
        return any ? sb.toString() : "Faces: all normal";
    }

    /** Empty-state snapshot: just the title and a message row. */
    @Nonnull
    public static PanelSnapshot empty(@Nonnull String title, @Nonnull String message) {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put(EMPTY, message);
        return new PanelSnapshot(title, texts, false);
    }

    private static void putResourceRow(
            @Nonnull Map<String, String> texts,
            @Nonnull String selector,
            @Nonnull String channelName,
            ResourceBuffer buffer) {
        if (buffer == null) {
            return;
        }
        String resourceId = buffer.resourceId();
        String name = resourceId == null ? "empty" : resourceId;
        texts.put(selector, channelName + " (" + name + "): " + gaugeText(buffer.amount(), buffer.capacity()));
    }

    /** Human-readable channel name for the pipe network title ("Power" / "Fluid" / "Gas" / "Items"). */
    @Nonnull
    private static String channelDisplayName(PortChannel channel) {
        if (channel == null) {
            return "Power";
        }
        return switch (channel) {
            case FLUID -> "Fluid";
            case GAS -> "Gas";
            case ITEM -> "Items";
            default -> "Power"; // POWER (and any future addition) reads as Power until specialised
        };
    }

    /** "{amount} / {capacity} ({pct}%)": the readable text gauge. */
    @Nonnull
    private static String gaugeText(long amount, long capacity) {
        return amount + " / " + capacity + " (" + percent(amount, capacity) + "%)";
    }

    private static int percent(long amount, long capacity) {
        if (capacity <= 0L) {
            return 0;
        }
        long p = Math.round(100.0 * amount / capacity);
        if (p < 0L) {
            return 0;
        }
        return (int) Math.min(p, 100L);
    }
}
