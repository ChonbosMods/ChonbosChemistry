package com.chonbosmods.chemistry.impl.block.net.item;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The ITEM-channel filter seam stub (2026-06-06 item-channel design §13.4, "Filter stub"). v1 ships
 * an allow-all filter and an empty lookup so the routing tasks consume the seam from day one; the
 * future tag/type intersection filters only implement the lookup, not the routing.
 */
class ItemFilterTest {

    private static final ItemKey COBBLE = new ItemKey("cobblestone", 16);

    @Test
    void allowAllAdmitsAnything() {
        assertTrue(ItemFilter.ALLOW_ALL.admits(COBBLE, 42L, 0));
        assertTrue(ItemFilter.ALLOW_ALL.admits(new ItemKey("anything", 1), -1L, 5));
    }

    @Test
    void allowAllNullKeyDefensive() {
        // Null-defensive: a missing key never trips the stub (callers can probe blindly).
        assertTrue(ItemFilter.ALLOW_ALL.admits(null, 0L, 0));
    }

    @Test
    void lookupNoneReturnsAllowAllForAnyPipe() {
        assertSame(ItemFilter.ALLOW_ALL, FilterLookup.NONE.forPipe(42L));
        assertSame(ItemFilter.ALLOW_ALL, FilterLookup.NONE.forPipe(-7L));
    }

    @Test
    void lookupNoneNeverNull() {
        // Every pipe resolves to a non-null filter: routing can chain forPipe(..).admits(..) safely.
        assertNotNull(FilterLookup.NONE.forPipe(0L));
    }

    @Test
    void itemKeyCarriesIdAndCount() {
        ItemKey key = new ItemKey("iron_ingot", 8);
        assertSame("iron_ingot", key.id());
        assertTrue(key.count() == 8);
    }
}
