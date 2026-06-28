package com.chonbosmods.chemistry.impl.block.craft;

import com.chonbosmods.chemistry.impl.block.craft.RecipePool.BenchRef;
import com.hypixel.hytale.protocol.BenchType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The single source of truth for the 7 recipe-bearing CC machines and the vanilla bench pool each one draws
 * its auto-craft recipes from. Each {@link Machine} carries a stable id, a display name (for the browser's
 * machine-filter button bar), and the list of {@link BenchRef}s it unions : a verbatim copy of the
 * {@code RecipePool.union(...)} call in that machine's {@code *TickSystem}.
 *
 * <p><b>This duplicates (does not refactor) the per-TickSystem unions.</b> Each machine below is annotated
 * "keep in sync with &lt;X&gt;TickSystem"; the TickSystems remain the runtime authority for crafting, and
 * this class exists purely so the Recipe Programmer browser can iterate the same pools without reaching into
 * each system. If a TickSystem's BenchRef list ever changes, update the matching entry here.
 *
 * <p>The pools are built lazily and cached ({@link #pool(Machine)}): {@link RecipePool#union} resolves
 * against the live vanilla recipe registry, so a built pool is only non-empty on a running server (the unit
 * tests cover the structural invariants : 7 machines, non-empty BenchRef lists, distinct ids : not the live
 * build, which is verified in-game).
 */
public final class MachineRecipePools {

    /** One recipe-bearing machine: its stable id, browser display name, CC block item id, and benches. */
    public static final class Machine {
        private final String id;
        private final String displayName;
        private final String itemId;
        private final List<BenchRef> benches;

        private Machine(@Nonnull String id, @Nonnull String displayName, @Nonnull String itemId,
                @Nonnull List<BenchRef> benches) {
            this.id = id;
            this.displayName = displayName;
            this.itemId = itemId;
            this.benches = List.copyOf(benches);
        }

        /** @return the stable machine id (the browser's machine-filter event payload + cache key). */
        @Nonnull
        public String id() {
            return id;
        }

        /** @return the human-readable name shown beside the machine tabs. */
        @Nonnull
        public String displayName() {
            return displayName;
        }

        /**
         * @return the CC block item id for this machine's tab icon (one of the {@code CC_*} items under
         *     {@code Server/Item/Items/ChonbosMods/}). The machine-tab ItemGrid renders an {@code ItemStack}
         *     of this id; the icon auto-updates when real block art lands.
         */
        @Nonnull
        public String itemId() {
            return itemId;
        }

        /** @return the vanilla benches this machine unions (never empty). */
        @Nonnull
        public List<BenchRef> benches() {
            return benches;
        }
    }

    // --- The 7 machines. BenchRef lists are copied verbatim from each *TickSystem's RecipePool.union(...). ---

    /** keep in sync with CookerTickSystem */
    private static final Machine COOKER = new Machine("Cooker", "Cooker", "CC_Cooker", List.of(
        new BenchRef(BenchType.Processing, "Campfire"),
        new BenchRef(BenchType.Crafting, "Cookingbench")));

    /** keep in sync with ForgeTickSystem */
    private static final Machine FORGE = new Machine("Forge", "Forge", "CC_Forge", List.of(
        new BenchRef(BenchType.Crafting, "Weapon_Bench"),
        new BenchRef(BenchType.Crafting, "Armor_Bench"),
        new BenchRef(BenchType.Crafting, "ArmorBench"),
        new BenchRef(BenchType.DiagramCrafting, "Armory")));

    /** keep in sync with CultivatorTickSystem */
    private static final Machine CULTIVATOR = new Machine("Cultivator", "Cultivator", "CC_Cultivator", List.of(
        new BenchRef(BenchType.Crafting, "Farmingbench")));

    /** keep in sync with AlembicTickSystem */
    private static final Machine ALEMBIC = new Machine("Alembic", "Alembic", "CC_Alembic", List.of(
        new BenchRef(BenchType.Crafting, "Alchemybench"),
        new BenchRef(BenchType.Crafting, "Arcanebench")));

    /** keep in sync with AssemblerTickSystem */
    private static final Machine ASSEMBLER = new Machine("Assembler", "Assembler", "CC_Assembler", List.of(
        new BenchRef(BenchType.Crafting, "Workbench"),
        new BenchRef(BenchType.Crafting, "Furniture_Bench"),
        new BenchRef(BenchType.Crafting, "Furniture_Misc")));

    /** keep in sync with OutfitterTickSystem */
    private static final Machine OUTFITTER = new Machine("Outfitter", "Outfitter", "CC_Outfitter", List.of(
        new BenchRef(BenchType.Processing, "Tannery"),
        new BenchRef(BenchType.Crafting, "Loombench")));

    /** keep in sync with SculptorTickSystem */
    private static final Machine SCULPTOR = new Machine("Sculptor", "Sculptor", "CC_Sculptor", List.of(
        new BenchRef(BenchType.StructuralCrafting, "Builders"),
        new BenchRef(BenchType.Crafting, "Builders")));

    /** All 7 machines, in browser button-bar order. Immutable. */
    public static final List<Machine> MACHINES = List.of(
        COOKER, FORGE, CULTIVATOR, ALEMBIC, ASSEMBLER, OUTFITTER, SCULPTOR);

    /** id &rarr; built {@link RecipePool}, lazily populated by {@link #pool(Machine)}. */
    private static final Map<String, RecipePool> POOL_CACHE = new ConcurrentHashMap<>();

    private MachineRecipePools() {
    }

    /**
     * @return the machine at {@code index} in {@link #MACHINES} (the machine-tab grid's slot order : slot i =
     *     machine i), or {@code null} when {@code index} is out of range (a stale / garbage slot click).
     */
    @Nullable
    public static Machine byIndex(int index) {
        if (index < 0 || index >= MACHINES.size()) {
            return null;
        }
        return MACHINES.get(index);
    }

    /** @return the machine with {@code id}, or {@code null} if none matches (a stale/garbage event payload). */
    @Nullable
    public static Machine byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Machine m : MACHINES) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    /**
     * The deduped, deterministically-ordered {@link RecipePool} for {@code machine}, built once via
     * {@link RecipePool#union} and cached by machine id. Resolves against the live recipe registry, so the
     * result is only non-empty on a running server.
     */
    @Nonnull
    public static RecipePool pool(@Nonnull Machine machine) {
        return POOL_CACHE.computeIfAbsent(machine.id(), k -> RecipePool.union(machine.benches()));
    }
}
