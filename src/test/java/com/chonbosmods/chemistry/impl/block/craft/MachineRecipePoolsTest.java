package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chonbosmods.chemistry.impl.block.craft.MachineRecipePools.Machine;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Structural unit tests for {@link MachineRecipePools}: the 7 machines, their distinct ids/names, and that
 * each carries a non-empty list of vanilla {@link RecipePool.BenchRef benches}.
 *
 * <p><b>The LIVE pool build (non-empty recipes) is NOT covered here</b>: {@link RecipePool#union} resolves
 * against the vanilla recipe registry ({@code CraftingPlugin.getBenchRecipes}), which is unavailable in a
 * plain unit JVM (same constraint as {@code VanillaCraftBridgeTest}). That is verified in-game. These tests
 * lock the static wiring : the count, the ids, the names, the bench lists.
 */
class MachineRecipePoolsTest {

    @Test
    void exactlySevenMachines() {
        assertEquals(7, MachineRecipePools.MACHINES.size());
    }

    @Test
    void machineIdsAreDistinctAndNonBlank() {
        Set<String> ids = new HashSet<>();
        for (Machine m : MachineRecipePools.MACHINES) {
            assertNotNull(m.id());
            assertFalse(m.id().isBlank(), "machine id must be non-blank");
            assertTrue(ids.add(m.id()), "duplicate machine id: " + m.id());
        }
    }

    @Test
    void machineDisplayNamesAreNonBlank() {
        for (Machine m : MachineRecipePools.MACHINES) {
            assertNotNull(m.displayName());
            assertFalse(m.displayName().isBlank(), "machine display name must be non-blank");
        }
    }

    @Test
    void everyMachineHasAtLeastOneBench() {
        for (Machine m : MachineRecipePools.MACHINES) {
            assertFalse(m.benches().isEmpty(), m.id() + " must union at least one bench");
            for (RecipePool.BenchRef ref : m.benches()) {
                assertNotNull(ref.type(), m.id() + " bench type must be non-null");
                assertNotNull(ref.benchId(), m.id() + " bench id must be non-null");
                assertFalse(ref.benchId().isBlank(), m.id() + " bench id must be non-blank");
            }
        }
    }

    @Test
    void expectedMachineIdsPresent() {
        Set<String> ids = new HashSet<>();
        for (Machine m : MachineRecipePools.MACHINES) {
            ids.add(m.id());
        }
        assertEquals(
            Set.of("Cooker", "Forge", "Cultivator", "Alembic", "Assembler", "Outfitter", "Sculptor"),
            ids);
    }

    @Test
    void byId_resolvesKnownAndRejectsUnknown() {
        for (Machine m : MachineRecipePools.MACHINES) {
            assertSame(m, MachineRecipePools.byId(m.id()), "byId must round-trip " + m.id());
        }
        assertNull(MachineRecipePools.byId("NotAMachine"));
        assertNull(MachineRecipePools.byId(null));
    }
}
