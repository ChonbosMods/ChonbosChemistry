# Forge Pull-Crafter Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or subagent-driven-development) to implement this plan task-by-task.

**Goal:** Convert the Forge from a push-fed buffer crafter to a demand-driven puller: it sources exactly
one recipe's ingredients from its input pipe network, crafts, repeats — making clog structurally impossible.

**Architecture:** Keep the round-robin selection, energy gate, recipe pool, output side, and network
integration. Replace the input: drop the 9-slot push buffer; the Forge holds only the active craft's pulled
ingredients; a new engine helper resolves the input network, aggregates its chests, and atomically extracts
a recipe's set. Single-threaded tick = atomic pulls, no locks. Design:
`docs/plans/2026-06-24-forge-pull-crafter-design.md` (LOCKED).

**Tech stack:** Java 25, Hytale Server 0.5.3, Gradle, JUnit 5. Worktree `.worktrees/cc-forge` on
`feature/cc-forge`; `./gradlew test` is worktree-isolated (safe). In-game verify needs a merge to `main`.

---

## Phase 1 — State + pure decision (headless TDD)

### Task 1: ForgeCraftState — held list + current recipe (replace the input buffer)
**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/ForgeCraftState.java`
- Modify: `src/test/java/com/chonbosmods/chemistry/impl/block/craft/ForgeCraftStateCodecTest.java`

Changes (mirror the existing codec field style exactly):
- REMOVE the `input` `SimpleItemContainer` (key `"Input"`) and its accessor.
- ADD `held` `SimpleItemContainer` (key `"Held"`), default `new SimpleItemContainer((short) 27)` — holds
  the ACTIVE craft's pulled ingredients (generous size; the GUI scrolls). Accessor `held()`.
- ADD `currentRecipeId` `String` (key `"Current"`, optional 3-arg not-required, like `"Cursor"`) — the
  recipe being crafted, `null` = idle. Accessors `currentRecipeId()` / `setCurrentRecipeId(String)`.
- KEEP `output`, `energy`, `ports`, `card`, `enabled`, `progress`, `lastSelectedId`.
- Update `ForgeCraftStateCodecTest`: round-trip now asserts `held` contents + `currentRecipeId` survive
  (drop the old `input` assertions). Use the existing `setItemStackForSlot(slot, stack, false)` +
  `ItemStack.CODEC.decode(...)` test pattern (AssetStore is unloaded in tests).
- TDD: update the test first (red), then the state (green). **Commit:** `feat(forge): ForgeCraftState holds the active craft's pulled ingredients + currentRecipeId`.

### Task 2: Pure pull-craft decision (`PullCraftStep`)
**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/PullCraftStep.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/craft/PullCraftStepTest.java`
- Remove: `CraftStep.java` + `CraftStepTest.java` (superseded by the pull state machine).

The pure decision — engine-free, over `List`/`Set`/primitives. Models BOTH phases:
```java
public final class PullCraftStep {
    public enum Action { IDLE, START, ADVANCE, COMPLETE }
    public record Decision(Action action, String pick, float newProgress, String newCursor) {}

    /**
     * @param crafting      whether a craft is active (currentRecipeId != null)
     * @param currentId     the active recipe id (null when !crafting)
     * @param progress      accumulated craft seconds
     * @param duration      seconds one craft takes
     * @param powered       whether energy afforded work this tick
     * @param affordableDt  craft seconds affordable this tick
     * @param stableOrder   all recipe ids (stable order) — for round-robin when idle
     * @param craftable     ids currently makeable (card-allowed AND full set available across sources AND output room)
     * @param cursor        round-robin cursor (lastSelectedId)
     */
    public static Decision decide(boolean crafting, String currentId, float progress, float duration,
            boolean powered, float affordableDt, List<String> stableOrder, Set<String> craftable, String cursor) {
        if (crafting) {
            if (!powered) return new Decision(Action.ADVANCE, currentId, progress, cursor); // hold: progress unchanged
            float np = progress + affordableDt;
            if (np >= duration) return new Decision(Action.COMPLETE, currentId, np - duration, currentId); // cursor advances
            return new Decision(Action.ADVANCE, currentId, np, cursor);
        }
        // idle
        if (!powered) return new Decision(Action.IDLE, null, 0f, cursor); // never reserve when unpowered
        String pick = CraftSelection.selectNext(stableOrder, craftable, cursor);
        if (pick == null) return new Decision(Action.IDLE, null, 0f, cursor);
        return new Decision(Action.START, pick, 0f, cursor); // engine then atomically pulls pick's ingredients
    }
}
```
TDD cases: idle+unpowered→IDLE; idle+powered+empty craftable→IDLE; idle+powered+craftable→START(round-robin
pick); crafting+unpowered→ADVANCE hold (progress unchanged); crafting+powered+below duration→ADVANCE
(progress+dt); crafting+powered+at duration→COMPLETE (remainder, cursor=currentId); rotation across cycles.
Write red, implement, green. **Commit:** `feat(forge): pure pull-craft state machine (TDD)`.

---

## Phase 2 — Engine: network pull + tick

### Task 3: `ForgeSourcePull` — resolve input network, aggregate, atomic extract
**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/ForgeSourcePull.java`
- READ FIRST: `src/main/java/com/chonbosmods/chemistry/impl/block/net/NetworkService.java` (per-world
  network cache + how a network is resolved from a pipe cell), `.../net/item/ItemEndpoints.java` +
  `.../net/item/ContainerLookup.java` + `ItemContainerView.java` (container enumeration + `firstExtractable`
  / `extract`), `.../net/NetworkTickSystem.java` (how it resolves the network at a cell, builds the
  `MachineAwareContainerLookup`, and drives transfer — mirror its resolution), `VanillaCraftBridge`
  (`getInputMaterials`, `canRemoveMaterials`).

Provide (engine glue — confine `builtin.crafting.*`/inventory calls; verify signatures via javap):
- `static ItemContainer aggregate(World world, NetworkService svc, int inputFaceCellX/Y/Z...)` — resolve
  the item pipe network adjacent to the Forge's input face, iterate its container endpoints, and copy their
  contents into a fresh `SimpleItemContainer` snapshot (big enough; sum stacks). Returns null if no network.
- `static boolean available(ItemContainer snapshot, CraftingRecipe r)` →
  `snapshot.canRemoveMaterials(getInputMaterials(r, 1), true, false)` (reuse the bridge's batch=1 + surplus
  semantics; the FFI materials are never read directly).
- `static List<ItemStack> tryPull(... the real network containers ..., CraftingRecipe r)` — with
  availability already confirmed against the snapshot, extract `r`'s ingredients from the REAL containers
  (per ingredient, `firstExtractable`/`extract` across containers until the material quantity is met);
  return the extracted `ItemStack`s, or null if (racing) it couldn't fully satisfy (then nothing committed
  — extract into a temp list and only commit/keep if all satisfied; if partial, RETURN the partial extras
  to the chests or simulate-first). PREFER: simulate the whole extraction, then commit, to stay atomic.

NOTE the atomicity guarantee (design): the Forge tick is single-threaded, so aggregate→available→extract
never interleaves with another Forge. Still, write `tryPull` to not leave a partial extraction on failure.

Testing: engine-bound (network + inventory + AssetStore) — NOT headless-unit-tested (same as
`WorldMachineLookup`/`VanillaBenchBridge`); gate = clean compile + the Phase-3 in-game smoke. If any pure
multiset/aggregation bit emerges, factor + TDD it. **Commit:** `feat(forge): ForgeSourcePull — input-network aggregate + atomic recipe extract`.

### Task 4: Rewrite `ForgeTickSystem` to the pull-craft loop
**Files:** Modify `src/main/java/com/chonbosmods/chemistry/impl/block/craft/ForgeTickSystem.java`.

Replace the push-buffer drive with: resolve context (unchanged) → energy gate → build `craftable` (when
IDLE: `ForgeSourcePull.aggregate` + `available(snapshot, r)` per recipe + card-allow + output room) →
`PullCraftStep.decide(...)` → apply:
- `IDLE` → progress 0, currentRecipeId null, visual `default`.
- `START(pick)` → `ForgeSourcePull.tryPull(... , pool.map.get(pick))`; if non-null, store into `node.held()`,
  set `currentRecipeId = pick`, progress 0; else stay idle (lost the set). Drain energy only when actually
  crafting (ADVANCE/START with work).
- `ADVANCE` → set progress; drain energy when powered.
- `COMPLETE` → clear `node.held()`, `VanillaCraftBridge.addOutputs(node.output(), outputs(recipe))`, set
  `lastSelectedId = pick`, clear `currentRecipeId`, progress 0.
Keep the never-throw guard + the per-recipe try/catch in the craftable scan. Keep `FORGE_DRAW`/
`FORGE_DURATION`. Pool-size log stays. **Commit:** `feat(forge): pull-craft tick (idle->pull->craft->repeat)`.

### Task 5: Input is a pull-window (no push)
**Files:** Modify `src/main/java/com/chonbosmods/chemistry/impl/block/net/WorldMachineLookup.java`.

In `forgeItems(forge)`: INPUT → `null` (the Forge is NOT a push destination — it pulls), OUTPUT →
`forge.output()` (push results, unchanged). The West input port stays in `ports()` (so the pipe still
connects + the West arm isn't suppressed); only the pushable container is removed. Confirm the push
transport no-ops on a null input container (it already null-guards). Full suite stays green.
**Commit:** `fix(forge): input port is a pull-window, not a push destination`.

---

## Phase 3 — GUI + verify

### Task 6: GUI — held ingredients as a scroll list
**Files:** Modify `src/main/resources/Common/UI/Custom/Pages/CC_ForgePanel.ui` +
`src/main/java/com/chonbosmods/chemistry/impl/block/ui/ForgePanelPage.java`.

Replace the input `ItemGrid #InputGrid` with a scroll-view list `#HeldList` bound to `node.held()` (display
filled slots only; read-only). Status text → "Crafting <item>" when `currentRecipeId != null` else "Idle".
Output grid / power / card / toggle / eject unchanged. Check the `.ui` scroll-list element name against
existing usages (grep `ScrollView`/`ReorderableList`/`ItemGrid` in `Common/UI`); if no scroll primitive
fits, a tall `ItemGrid` is an acceptable v1 fallback (note it). GUI is in-game-verified. **Commit:** `feat(forge): GUI shows the active craft's pulled ingredients (held list)`.

### Task 7: Verify
- `./gradlew test` in the worktree → all green.
- Merge `feature/cc-forge` → `main` (devServer DOWN), rebuild, restart. In-game smoke (design "Testing"):
  chests of ingredients on the input network → Forge pulls one recipe's set, crafts, repeats, even rotation
  across available recipes; a recipe missing an ingredient is skipped; two Forges → one wins the contested
  set, the other idles; mid-craft reload keeps held + progress; break ejects held + output; no junk ever
  enters (no filter needed).
- Then `superpowers:finishing-a-development-branch`.

## Invariants
- NEVER-throw tick; single-threaded ⇒ atomic pulls. Same-cell ports + Z-inversion + dual-copy + no-gradle-
  while-live all still apply. The output/push side and power intake are unchanged — don't regress them.
