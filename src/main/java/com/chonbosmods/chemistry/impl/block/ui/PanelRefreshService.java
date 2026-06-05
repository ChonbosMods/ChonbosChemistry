package com.chonbosmods.chemistry.impl.block.ui;

import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * Registry of currently-open live panels, keyed per world, plus the refresh cadence counter
 * (see {@code docs/plans/2026-06-05-live-refresh-panel-design.md}).
 *
 * <p>Pages self-register after a successful build and deregister on dismiss. {@link #tick} is called
 * once per world tick (by {@code PanelRefreshSystem}, on that world's WorldThread); every
 * {@link #REFRESH_INTERVAL_TICKS}th call per world it refreshes that world's panels, dropping any
 * panel whose {@link LivePanel#refresh()} reports it is no longer live.
 *
 * <p>World keys are compared by identity ({@code IdentityHashMap}), matching how
 * {@code NetworkTickSystem} keys its per-world state. All access for a given world happens on that
 * world's WorldThread, so plain collections suffice.
 */
public final class PanelRefreshService {

    /** Refresh every 10th tick (~2 updates/sec at 20 tps): design decision, not `[TUNE]`. */
    public static final int REFRESH_INTERVAL_TICKS = 10;

    /** A panel that can re-send its content. */
    public interface LivePanel {
        /**
         * Re-populate and send the panel's current values.
         *
         * @return true if the panel is still live; false to drop it from the registry (it closed
         *     itself or its backing block is gone).
         */
        boolean refresh();
    }

    private final Map<Object, Set<LivePanel>> panelsByWorld = new IdentityHashMap<>();
    private final Map<Object, Integer> tickCountByWorld = new IdentityHashMap<>();

    public void register(@Nonnull Object worldKey, @Nonnull LivePanel panel) {
        this.panelsByWorld.computeIfAbsent(worldKey, k -> new LinkedHashSet<>()).add(panel);
    }

    /** Remove a panel (dismiss path). Unknown panel/world is a harmless no-op. */
    public void deregister(@Nonnull Object worldKey, @Nonnull LivePanel panel) {
        Set<LivePanel> panels = this.panelsByWorld.get(worldKey);
        if (panels != null) {
            panels.remove(panel);
        }
    }

    /** Advance this world's cadence counter; on every 10th call, refresh its panels. */
    public void tick(@Nonnull Object worldKey) {
        int count = this.tickCountByWorld.getOrDefault(worldKey, 0) + 1;
        if (count < REFRESH_INTERVAL_TICKS) {
            this.tickCountByWorld.put(worldKey, count);
            return;
        }
        this.tickCountByWorld.put(worldKey, 0);

        Set<LivePanel> panels = this.panelsByWorld.get(worldKey);
        if (panels == null || panels.isEmpty()) {
            return;
        }
        for (LivePanel panel : panels.toArray(new LivePanel[0])) {
            if (!panel.refresh()) {
                panels.remove(panel);
            }
        }
    }
}
