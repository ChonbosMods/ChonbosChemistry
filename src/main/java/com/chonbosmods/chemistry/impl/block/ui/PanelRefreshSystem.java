package com.chonbosmods.chemistry.impl.block.ui;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;

/**
 * Query-less per-world pulse for the live panel refresh (see
 * {@code docs/plans/2026-06-05-live-refresh-panel-design.md}): a plain {@link TickingSystem} on the
 * entity store ticks once per world per tick on that world's WorldThread: the same thread
 * {@code MachinePanelPage.build} runs on, so {@code refresh()} inherits all of build's thread-safety
 * reasoning (incl. {@code NetworkManager.getOrBuildNetwork}).
 *
 * <p>All cadence/registry logic lives in the unit-tested {@link PanelRefreshService}; this is the
 * thinnest possible engine glue. Never throws: a world that can't be resolved is skipped.
 */
public final class PanelRefreshSystem extends TickingSystem<EntityStore> {

    private final PanelRefreshService service;

    public PanelRefreshSystem(@Nonnull PanelRefreshService service) {
        this.service = service;
    }

    @Override
    public void tick(float dt, int index, @Nonnull Store<EntityStore> store) {
        EntityStore external = store.getExternalData();
        if (external == null) {
            return;
        }
        World world = external.getWorld();
        if (world == null) {
            return;
        }
        this.service.tick(world);
    }
}
