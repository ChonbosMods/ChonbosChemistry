# Forge Autonomous-Craft Engine Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the CC Forge — machine #3, the first *pure-crafting* auto-machine — by composing a new
autonomous-craft engine: own item containers, even-round-robin recipe selection, energy-gated timed craft.

**Architecture:** Crafting benches have no autonomous block to wrap (unlike the Smelter's
`ProcessingBenchBlock`), so we own the loop. The selection heart is a **pure, headless-TDD'd** function
(even round-robin over the currently-craftable set). The engine wrapping (own `SimpleItemContainer`s in a
new block-state, energy gate, ports, GUI) mirrors the proven Smelter/Reclaimer substrate. Vanilla pieces
reused via static helpers: `CraftingPlugin.getBenchRecipes`, `CraftingManager.matches` /
`getOutputItemStacks`.

**Tech Stack:** Java 25, Hytale Server 0.5.3 (`builtin.crafting.*`), Gradle, JUnit 5. Design:
`docs/plans/2026-06-23-forge-autonomous-craft-design.md` (selection model is LOCKED there).

**Working dir:** worktree `.worktrees/cc-forge` on `feature/cc-forge`. NOTE: `./gradlew test` runs in the
worktree's own build dir — safe to run freely (does NOT touch the main checkout's live game). In-game
verify (Phase 6) requires syncing to the main checkout — worktree builds don't reach the running game.

---

## Phase 1 — Pure selection core (headless TDD)

The heart of the engine. No engine types: pure Java over `List<String>` (bench recipe ids in stable
order) + `Set<String>` (currently-craftable ids) + the cursor id.

### Task 1: Even round-robin cursor

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/CraftSelection.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/craft/CraftSelectionTest.java`

**Step 1: Write the failing test**

```java
package com.chonbosmods.chemistry.impl.block.craft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftSelectionTest {

    private static final List<String> ORDER = List.of("A", "B", "C", "D", "E");

    @Test
    void emptyCraftable_isIdle() {
        assertNull(CraftSelection.selectNext(ORDER, Set.of(), "A"));
    }

    @Test
    void singleCraftable_returnsItRepeatedly() {
        assertEquals("C", CraftSelection.selectNext(ORDER, Set.of("C"), null));
        assertEquals("C", CraftSelection.selectNext(ORDER, Set.of("C"), "C")); // stays on the only one
    }

    @Test
    void nullCursor_startsFromTopOfStableOrder() {
        assertEquals("A", CraftSelection.selectNext(ORDER, Set.of("A", "C", "E"), null));
    }

    @Test
    void severalCraftable_rotatesEvenlySkippingNonCraftable() {
        Set<String> craftable = Set.of("A", "C", "E"); // B, D never craftable
        String cur = null;
        cur = CraftSelection.selectNext(ORDER, craftable, cur); assertEquals("A", cur);
        cur = CraftSelection.selectNext(ORDER, craftable, cur); assertEquals("C", cur);
        cur = CraftSelection.selectNext(ORDER, craftable, cur); assertEquals("E", cur);
        cur = CraftSelection.selectNext(ORDER, craftable, cur); assertEquals("A", cur); // wraps
    }

    @Test
    void deterministic_sameInputsSameOutput() {
        assertEquals(
            CraftSelection.selectNext(ORDER, Set.of("B", "D"), "A"),
            CraftSelection.selectNext(ORDER, Set.of("B", "D"), "A"));
    }

    @Test
    void cursorIdAbsentFromOrder_treatedAsStart() {
        assertEquals("A", CraftSelection.selectNext(ORDER, Set.of("A", "B"), "ZZZ"));
    }

    @Test
    void allowed_nullAllowSetPermitsAll_elseMembership() {
        org.junit.jupiter.api.Assertions.assertTrue(CraftSelection.allowed("X", null));
        org.junit.jupiter.api.Assertions.assertTrue(CraftSelection.allowed("X", Set.of("X", "Y")));
        org.junit.jupiter.api.Assertions.assertFalse(CraftSelection.allowed("Z", Set.of("X", "Y")));
    }
}
```

**Step 2: Run to verify it fails**

Run: `./gradlew test --tests "*CraftSelectionTest"`
Expected: FAIL — `CraftSelection` does not exist (compile error).

**Step 3: Write minimal implementation**

```java
package com.chonbosmods.chemistry.impl.block.craft;

import java.util.List;
import java.util.Set;

/**
 * Pure, engine-free recipe selection for autonomous-craft machines (Forge, etc.). See
 * {@code docs/plans/2026-06-23-forge-autonomous-craft-design.md} (selection model, LOCKED).
 */
public final class CraftSelection {

    private CraftSelection() {
    }

    /**
     * Even round-robin over the currently-craftable recipes: returns the first id in {@code stableOrder}
     * strictly after {@code lastSelectedId}'s position (wrapping) that is in {@code craftableIds}. Skips
     * non-craftable recipes (never idles while something is makeable) and rotates evenly across same-input
     * collisions. Deterministic — no RNG, no value/priority.
     *
     * @param stableOrder    every bench recipe id in a stable, deterministic order
     * @param craftableIds   the subset currently makeable (card-allowed AND inputs present)
     * @param lastSelectedId the id chosen last tick (cursor), or null to start from the top
     * @return the chosen id, or null when {@code craftableIds} is empty (idle)
     */
    public static String selectNext(List<String> stableOrder, Set<String> craftableIds, String lastSelectedId) {
        int n = stableOrder.size();
        if (craftableIds.isEmpty() || n == 0) {
            return null;
        }
        int start = stableOrder.indexOf(lastSelectedId); // -1 when null / not in list -> scan from index 0
        for (int step = 1; step <= n; step++) {
            String candidate = stableOrder.get((start + step) % n);
            if (craftableIds.contains(candidate)) {
                return candidate;
            }
        }
        return null; // craftableIds disjoint from stableOrder (defensive; shouldn't happen)
    }

    /** Card permission: a null allow-set (no card) permits everything; otherwise membership. */
    public static boolean allowed(String recipeId, Set<String> allowSet) {
        return allowSet == null || allowSet.contains(recipeId);
    }
}
```

Note: `start` is in `[-1, n)`; `(start + step) % n` with `step ∈ [1, n]` stays non-negative, so no
negative-modulo guard is needed.

**Step 4: Run to verify it passes**

Run: `./gradlew test --tests "*CraftSelectionTest"`
Expected: PASS (7 tests).

**Step 5: Commit**

```bash
git add src/main/java/com/chonbosmods/chemistry/impl/block/craft/CraftSelection.java \
        src/test/java/com/chonbosmods/chemistry/impl/block/craft/CraftSelectionTest.java
git commit -m "feat(forge): pure even-round-robin recipe selection core (TDD)"
```

---

## Phase 2 — Crafting bridge (vanilla API access)

Extend the SOLE chokepoint `VanillaBenchBridge` (or a sibling `VanillaCraftBridge` in the same
`...impl.block.bench` package) with the crafting-side calls. Keep all `builtin.crafting.*` behavior here.

**FIRST verify exact signatures against the jar** (do not trust from memory):
```bash
JAR=$(find ~/.gradle -name 'Server-0.5.3.jar' | head -1)
javap -p -classpath "$JAR" com.hypixel.hytale.builtin.crafting.CraftingPlugin | grep -i getBenchRecipes
javap -p -classpath "$JAR" com.hypixel.hytale.builtin.crafting.component.CraftingManager | grep -iE "matches|getOutputItemStacks|getInputMaterials"
javap -p -classpath "$JAR" com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe | grep -iE "getInput|getOutput|getBenchRequirement|getId|getTimeSeconds|isRestricted"
javap -p -classpath "$JAR" com.hypixel.hytale.protocol.BenchType | grep -iE "Crafting|Diagram"
```

### Task 2: Bridge methods (recipe pool, match, outputs, ids)

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/bench/VanillaCraftBridge.java`

Provide (signatures confirmed in the verify step above):
- `static List<CraftingRecipe> benchRecipes(BenchType type, String benchId)` → wraps
  `CraftingPlugin.getBenchRecipes(type, benchId)`.
- `static String recipeId(CraftingRecipe r)` → the stable id (confirm getter; used as the stable-order key).
- `static boolean inputsPresent(CraftingRecipe r, ItemContainer input, int tier)` → for each
  `MaterialQuantity` in `r.getInput()`, find a slot in `input` whose stack satisfies it via
  `CraftingManager.matches(ingredient, stack)` with enough quantity; all must match (no double-counting a
  single slot across two ingredients — track consumed-per-slot tentatively).
- `static boolean consumeInputs(CraftingRecipe r, ItemContainer input, int tier)` → atomically remove one
  ingredient set; return false (and roll back) if any ingredient can't be fully removed.
- `static List<ItemStack> outputs(CraftingRecipe r)` → wraps `CraftingManager.getOutputItemStacks(r)`.

**Testing:** these are thin engine wrappers (like the existing `VanillaBenchBridge` methods) — covered by
the Phase-4 integration test + in-game smoke, not unit tests (no headless engine). Keep each method a
one-liner delegating to the confirmed vanilla call, with a `// signature:` comment as the existing bridge
does.

**Commit:** `git commit -m "feat(forge): VanillaCraftBridge — recipe pool, match, consume, outputs"`

### Task 3 (optional, recommended): testable input-match helper

If `inputsPresent`/`consumeInputs` ingredient-vs-slot logic is non-trivial (multi-ingredient, quantity,
no slot double-count), extract the PURE multiset logic into `CraftSelection` or a sibling pure class
(`IngredientMatch`) over a simple `(itemId -> count)` map, and TDD it (recipe needs 2 iron + 1 stick;
buffer has 3 iron + 1 stick → present; 1 iron → absent; consume leaves 1 iron + 0 stick). The bridge then
adapts the live container to that map. This keeps the hard accounting headless-tested.

---

## Phase 3 — Forge block-state (own containers + cursor + card)

Crafting has no vanilla bench block, so the Forge holds its OWN state. Either add fields to
`MachineBlockState` (gated so non-crafting machines ignore them) OR create a dedicated
`ForgeCraftState` component. **Recommended: dedicated component** to avoid bloating `MachineBlockState`;
register it like `MachineBlockState` is registered in `ChonbosChemistry.java`.

### Task 4: ForgeCraftState component (codec-persisted)

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/ForgeCraftState.java`
- Modify: `src/main/java/com/chonbosmods/chemistry/ChonbosChemistry.java` (register component, mirror the
  `MachineBlockState` registration ~line 102)

Mirror `MachineBlockState`'s `BuilderCodec` pattern. Fields:
- `EnergyBuffer energy` (key `"Energy"`) — same as MachineBlockState.
- `PortConfig ports` (key `"Ports"`).
- `SimpleItemContainer input` (key `"Input"`) — OUR own input buffer (verify `SimpleItemContainer.CODEC`
  via the `hytale-inventory` skill / javap; size = max ingredient count across the bench cluster, default 9).
- `SimpleItemContainer output` (key `"Output"`) — our output buffer (default 4 slots).
- `ItemStack card` (key `"Card"`, optional) — the inserted recipe card; null = no card.
- `float progress` (key `"Progress"`) — accumulated craft time.
- `String lastSelectedId` (key `"Cursor"`, optional) — the round-robin cursor.
- `boolean enabled` (key `"Enabled"`, default true) — on/off, same as MachineBlockState.

Accessors mirror MachineBlockState (`energy()`, `ports()`, `isEnabled()`/`setEnabled`, etc.).

**Testing:** a codec round-trip test (encode → decode → fields equal), mirroring
`MachineBlockState`'s existing test style if present; at minimum a `ForgeCraftStateCodecTest` asserting
the cursor + progress + card survive encode/decode.

**Commit:** `git commit -m "feat(forge): ForgeCraftState — own I/O containers, card slot, cursor (codec)"`

---

## Phase 4 — Autonomous-craft tick

Drive the Forge each tick: compute craftable → select → energy-gate → progress → consume + produce.

### Task 5: The craft driver (energy-gated)

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/ForgeTickSystem.java` (or extend
  `MachineTickSystem` with a `driveCraft` branch keyed on the component type — prefer a separate system to
  keep `driveBench` untouched)
- Reference (mirror): `src/main/java/com/chonbosmods/chemistry/impl/block/MachineTickSystem.java:152-262`
  (`driveBench`: ref/world/blockType resolution, energy gate, visual state, the NEVER-throw contract)

Per-tick logic (the design's engine steps):
1. Resolve the Forge's bench cluster recipe pool once (cache): `Weapon_Bench` (Crafting), `Armor_Bench`
   + `ArmorBench` (Crafting), `Armory` (DiagramCrafting) via `VanillaCraftBridge.benchRecipes`. Build the
   STABLE `List<String> order` of `recipeId`s (sorted deterministically, e.g. by id) and an id→recipe map.
2. `Set<String> allow` = card present ? parse the card's allow-set : null.
3. `Set<String> craftable` = ids where `CraftSelection.allowed(id, allow)` AND
   `VanillaCraftBridge.inputsPresent(recipe, state.input(), tier)` AND output has room for
   `VanillaCraftBridge.outputs(recipe)` (backpressure: full output stalls).
4. `String pick = CraftSelection.selectNext(order, craftable, state.lastSelectedId())`. Null → idle:
   set progress 0, visual `"default"`, return.
5. Energy gate (reuse `SmelterEnergy.affordableDt(stored, FORGE_DRAW, dt)`); `powered = affordable > 0`.
   Not powered → hold (retain progress), visual per `MachineVisualState`, return.
6. Advance `progress += affordable`; drain `SmelterEnergy.drainFor`. If `progress >= craftDuration`:
   `VanillaCraftBridge.consumeInputs(recipe)` then add `VanillaCraftBridge.outputs(recipe)` to
   `state.output()`; set `state.lastSelectedId(pick)`; `progress -= craftDuration`.
7. Visual state via `MachineVisualState.desired(powered, progressedThisTick)` + the change-guard, exactly
   like the Smelter.

Constants: `FORGE_DRAW` (per-second energy, placeholder like `SMELTER_DRAW`), `craftDuration` (seconds per
craft, placeholder — e.g. recipe-independent flat value for v1).

**Testing:** extract the orchestration's pure decision into a testable helper where possible (e.g. a
`CraftStep` pure function: given craftable set + cursor + powered + progress + duration → {pick, newProgress,
didComplete, newCursor}) and TDD it (idle when empty; holds when unpowered; completes at duration; advances
cursor only on completion). The live container/energy/world wiring is in-game-verified (Phase 6), matching
how `driveBench` is verified. NEVER-throw contract: wrap the drive in the same try/catch as `driveBench`.

**Commit (split):** one commit for the pure `CraftStep` + tests, one for the live `ForgeTickSystem` wiring.

### Task 6: Register the tick system

**Files:** Modify `src/main/java/com/chonbosmods/chemistry/ChonbosChemistry.java` — register
`ForgeTickSystem` on the world tick the same way `MachineTickSystem` is registered. Confirm the
registration site by reading the file.

**Commit:** `git commit -m "feat(forge): register autonomous-craft tick system"`

---

## Phase 5 — Assets, ports, registration, GUI

### Task 7: Port defaults + JSON ports

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/ForgePorts.java` (mirror
  `ReclaimerPorts.java`: item-out East/face 0, item-in West/face 1, power-in face 5 — all on anchor for a
  single-cell footprint)
- Test: `ForgePortsTest` + a JSON-ports guard test mirroring `ReclaimerJsonPortsTest`

**Commit:** `git commit -m "feat(forge): default port layout (TDD)"`

### Task 8: Block/item JSON + template + hitbox

**Files:**
- Create: `src/main/resources/Server/Item/Items/ChonbosMods/CC_Forge.json` (mirror `CC_Reclaimer.json`:
  `CustomModel` = `Blocks/ChonbosMods/Machines/CC_Forge.blockymodel`; `Interactions.Use → OpenCustomUI
  CC_ForgePanel`; `BlockEntity.Components.ForgeCraftState` with `Energy` + `Ports`; NO vanilla `Bench`
  block — the Forge does NOT wrap a ProcessingBench, it has its own state; State.Definitions Processing /
  ProcessCompleted for the visual states)
- Create: `src/main/resources/Server/Item/CustomConnectedBlockTemplates/CC_ForgeTemplate.json` (mirror
  `CC_ReclaimerTemplate.json`; power FaceTag on **`South`** = -Z rear — REMEMBER the Z-inversion:
  template South == port Face 5; guard with the `MachineTemplateFaceTagTest` pattern)
- Create: `src/main/resources/Server/Item/Block/Hitboxes/ChonbosMods/CC_Forge.json`
- Copy `CC_Forge.blockymodel` + textures from `models/_cc_machines` into
  `src/main/resources/Common/Blocks/ChonbosMods/Machines/` (the dual-copy rule)

Extend `MachineTemplateFaceTagTest` to include `CC_Forge` so the power-face Z-inversion can't regress.

**Commit:** `git commit -m "feat(forge): CC_Forge block/item JSON, template, hitbox, model"`

### Task 9: GUI (reuse BenchMachinePanel + card slot)

**Files:**
- Modify/extend `src/main/java/com/chonbosmods/chemistry/impl/block/ui/BenchMachinePanelPage.java` (or a
  Forge subclass) to read `ForgeCraftState` (input grid, output grid, power bar, status) and add a
  **card slot** (`ItemSlot #CardSlot`) bound to `state.card()`. Reuse `CC_BenchMachinePanel.ui`; add the
  card slot to the layout (or a Forge variant `.ui` importing the shared tokens per `cc-machine-gui-template`).
- Register the page id `"CC_ForgePanel"` in `ChonbosChemistry.java` next to `CC_ReclaimerPanel`.

**Testing:** GUI is in-game-verified. Keep any text/format helpers (e.g. craft status line) in a pure
`*PanelText` method with a unit test, like `BenchMachinePanelText`.

**Commit:** `git commit -m "feat(forge): CC_ForgePanel GUI with recipe-card slot"`

---

## Phase 6 — Verify

### Task 10: Full suite + in-game

- Run the full suite in the worktree: `./gradlew test` → expect all green (baseline was 611).
- **Sync to the main checkout for in-game** (worktree builds don't reach the game): coordinate with the
  user (devServer DOWN before any gradle on the main checkout — see memory). Place a Forge, pipe in
  ingredients for two same-input recipes (e.g. axe + shovel), power it, and confirm:
  - auto-crafts with no card; **even rotation** across the collision (not fixated);
  - energy-gates (idle when unpowered; speed scales with power fed);
  - **stalls** on a full output buffer;
  - a hand-seeded card narrows the set to its allow-list;
  - survives save/reload (cursor + progress + card persist).
- Then use `superpowers:finishing-a-development-branch` to land `feature/cc-forge`.

---

## Notes / invariants to honor
- **NEVER-throw** in the tick (the `driveBench` try/catch contract — a craft error must not kill the WorldThread).
- **Endpoint dedup / same-cell ports**: the Forge's item-in + item-out share the anchor cell — the fixes
  `ItemEndpoints` (per-port dedup) and `MachineAwareContainerLookup` (insert→input/extract→output) already
  handle this; do not regress them. (memory: endpoint-dedup-per-port-not-per-cell)
- **Template Z-inversion**: power port Face 5 ↔ template `South`. (memory: template-facetag-z-inversion)
- **Machine model dual-copy**: edit in `models/_cc_machines`, re-copy to `src/main/resources`. (memory)
- **No gradle on the main checkout while its devServer is live.** (memory)
