package com.chonbosmods.chemistry.impl.block.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PanelRefreshServiceTest {

    /** Counts refreshes; returns a scripted liveness so tests can simulate a panel dying. */
    private static final class CountingPanel implements PanelRefreshService.LivePanel {
        int refreshes;
        boolean alive = true;

        @Override
        public boolean refresh() {
            this.refreshes++;
            return this.alive;
        }
    }

    @Test
    void refreshesPanelsEveryTenthTick() {
        PanelRefreshService service = new PanelRefreshService();
        Object world = new Object();
        CountingPanel panel = new CountingPanel();
        service.register(world, panel);

        for (int i = 0; i < 9; i++) {
            service.tick(world);
        }
        assertEquals(0, panel.refreshes, "no refresh before the 10th tick");

        service.tick(world);
        assertEquals(1, panel.refreshes, "refresh on the 10th tick");

        for (int i = 0; i < 10; i++) {
            service.tick(world);
        }
        assertEquals(2, panel.refreshes, "refresh again on the 20th tick");
    }

    @Test
    void worldsTickAndRefreshIndependently() {
        PanelRefreshService service = new PanelRefreshService();
        Object worldA = new Object();
        Object worldB = new Object();
        CountingPanel panelA = new CountingPanel();
        CountingPanel panelB = new CountingPanel();
        service.register(worldA, panelA);
        service.register(worldB, panelB);

        // 10 ticks on A only: B's counter must not advance, B's panel must not refresh.
        for (int i = 0; i < 10; i++) {
            service.tick(worldA);
        }
        assertEquals(1, panelA.refreshes);
        assertEquals(0, panelB.refreshes, "ticks on world A must not refresh world B's panels");

        // B catches up with its own 10 ticks; A stays at 1.
        for (int i = 0; i < 10; i++) {
            service.tick(worldB);
        }
        assertEquals(1, panelA.refreshes);
        assertEquals(1, panelB.refreshes);
    }

    @Test
    void deregisteredPanelIsNotRefreshed() {
        PanelRefreshService service = new PanelRefreshService();
        Object world = new Object();
        CountingPanel panel = new CountingPanel();
        service.register(world, panel);

        service.deregister(world, panel);
        for (int i = 0; i < 10; i++) {
            service.tick(world);
        }
        assertEquals(0, panel.refreshes, "deregistered panel must not be refreshed");

        // Deregistering an unknown panel / world is a harmless no-op (dismiss-after-close path).
        service.deregister(world, panel);
        service.deregister(new Object(), panel);
    }

    @Test
    void panelReportingDeadIsDroppedFromFutureRefreshes() {
        PanelRefreshService service = new PanelRefreshService();
        Object world = new Object();
        CountingPanel dying = new CountingPanel();
        CountingPanel healthy = new CountingPanel();
        dying.alive = false;
        service.register(world, dying);
        service.register(world, healthy);

        for (int i = 0; i < 20; i++) {
            service.tick(world);
        }
        assertEquals(1, dying.refreshes, "dead panel dropped after its first (false) refresh");
        assertEquals(2, healthy.refreshes, "healthy panel keeps refreshing");
    }

    @Test
    void registeringSamePanelTwiceRefreshesOnce() {
        PanelRefreshService service = new PanelRefreshService();
        Object world = new Object();
        CountingPanel panel = new CountingPanel();
        service.register(world, panel);
        service.register(world, panel);

        for (int i = 0; i < 10; i++) {
            service.tick(world);
        }
        assertEquals(1, panel.refreshes, "double registration must not double-refresh");
    }

    @Test
    void panelDeregisteringItselfDuringRefreshIsSafe() {
        PanelRefreshService service = new PanelRefreshService();
        Object world = new Object();
        CountingPanel after = new CountingPanel();
        // A panel whose refresh() deregisters itself reentrantly (the close()-inside-refresh path).
        PanelRefreshService.LivePanel reentrant = new PanelRefreshService.LivePanel() {
            @Override
            public boolean refresh() {
                service.deregister(world, this);
                return false;
            }
        };
        service.register(world, reentrant);
        service.register(world, after);

        for (int i = 0; i < 10; i++) {
            service.tick(world); // must not throw ConcurrentModificationException
        }
        assertEquals(1, after.refreshes, "panels after a reentrant deregister still refresh");
    }
}
