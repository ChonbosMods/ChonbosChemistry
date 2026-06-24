# Autonomous-Craft Engine Encapsulation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Lift the autonomous-crafting loop and the shared powered-machine skeleton out of `ForgeTickSystem`
into reusable, composable pieces, so (a) new crafting machines (Cooker/Outfitter) plug in with a thin tick +
a state component, and (b) improvements to the craft process (per-recipe craft time, recipe scripting) OR to
the shared machine infra (context resolution, energy, visual) are made in ONE place and apply to every
machine — Forge, Cooker, AND the existing processing machines (Smelter, Reclaimer).

**Architecture (composition, two layers):**
- **Layer 2 — `AutoCraftEngine`**: owns the pull→craft→produce loop for crafting machines. Operates on the
  `AutoCraftNode` interface. The two improvement axes are engine-owned, overridable hooks: `craftDuration(recipe)`
  and `allowSet(node)` (recipe scripting). `NetworkRecipeSource` (renamed from `ForgeSourcePull`) does the
  network sourcing.
- **Layer 1 — shared powered-machine skeleton**: the context-resolution prologue + never-throw guard +
  visual-state swap, extracted to shared helpers used by BOTH `ForgeTickSystem` (auto-craft) and
  `MachineTickSystem` (processing: Smelter/Reclaimer). Node contract: `PoweredMachineNode` (energy/ports/
  enabled), extended by `AutoCraftNode`.
- The work pass stays distinct: processing machines delegate to a vanilla `ProcessingBenchBlock`; crafting
  machines run the `AutoCraftEngine`. That difference is real and stays.

**Tech stack:** Java 25, Hytale Server 0.5.3, Gradle, JUnit 5. Worktree `.worktrees/cc-forge` on branch
`feature/autocraft-engine` (forked from current `main`, so it has fluids + the full Forge + the post-craft
pause). `./gradlew test` in the worktree is isolated/safe. In-game verify needs a merge to `main`.

**Invariant for the WHOLE plan:** behavior is preserved EXACTLY for the Forge, Smelter, and Reclaimer. This
is a re-homing of verified logic, not a behavior change. The suite (currently **649 tests**) stays green after
every task. Pure helpers (`CraftSelection`, `PullCraftStep`, `VanillaCraftBridge`, the renamed
`NetworkRecipeSource` diff helper) are NOT modified — they are already generic.

---

## Phase 1 — AutoCraft engine + node (crafting reuse; ZERO processing-machine risk)

### Task 1: `AutoCraftNode` interface + `ForgeCraftState implements` it
**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/AutoCraftNode.java`
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/ForgeCraftState.java`

`AutoCraftNode` is the state contract the engine needs — every method ALREADY exists on `ForgeCraftState`:
```java
public interface AutoCraftNode {
    com.chonbosmods.chemistry.api.energy.EnergyHandler energy();
    com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer held();
    com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer output();
    com.hypixel.hytale.server.core.inventory.ItemStack card();
    com.chonbosmods.chemistry.impl.block.PortConfig ports();
    boolean isEnabled();
    String currentRecipeId();   void setCurrentRecipeId(String id);
    float progress();           void setProgress(float p);
    String lastSelectedId();    void setLastSelectedId(String id);
    int craftDelay();           void setCraftDelay(int d);
}
```
Add `implements AutoCraftNode` to `ForgeCraftState` (it already satisfies every method — `@Override` the
accessors for clarity). No behavior change. **Gate:** `./gradlew test` green (649). **Commit:** `refactor(autocraft): AutoCraftNode state contract; ForgeCraftState implements it`.

### Task 2: `AutoCraftEngine` — extract the craft loop from `ForgeTickSystem`
**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/AutoCraftEngine.java`
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/RecipePool.java` (promote the private
  `ForgeTickSystem.RecipePool` record to a shared top-level type: `record RecipePool(List<String> stableOrder, Map<String,CraftingRecipe> map)`).
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/craft/ForgeTickSystem.java`

Move the ENTIRE craft loop (everything in `driveForge` AFTER the context prologue: the `crafting` flag,
energy gate, post-craft delay, snapshot+craftable build, `PullCraftStep.decide`, the START/ADVANCE/COMPLETE/
IDLE apply incl. `tryPull`/`loadHeld`/produce/`clearHeld`/energy-drain, the input-pipe-cell projection, and
the visual-state swap) into `AutoCraftEngine`. Surface:
```java
public final class AutoCraftEngine {
    /** One craft step for `node`: source → decide → produce → drain energy → swap visual. Never throws
     *  (the caller's catch-Throwable is the backstop; guard internally too). */
    public static void drive(AutoCraftNode node, Context ctx, Spec spec) { ... }

    /** Live per-tick context resolved by the machine's tick (Phase 2 will share the resolver). */
    public record Context(World world, Store<ChunkStore> store, int x, int y, int z, BlockType blockType,
            float dt, EnergyBuffer energy,
            NetworkService networkService, ComponentType<ChunkStore, PipeNode> pipeType) {}

    /** Per-machine knobs + the two improvement hooks. */
    public interface Spec {
        RecipePool recipePool();                  // which benches feed the pool (machine-specific)
        long energyDraw();                        // per-second draw while crafting
        int postCraftDelayTicks();                // the visual cadence pause
        float craftDuration(CraftingRecipe r);    // IMPROVEMENT AXIS: per-recipe craft time (default: a constant)
        Set<String> allowSet(AutoCraftNode node); // IMPROVEMENT AXIS: recipe scripting (default: card CC_ForgeAllow)
    }
}
```
- Move `loadHeld`/`clearHeld`/`inputPipeCell`/`applyVisualState`/`cardAllowSet`/the `CARD_ALLOW` codec/the
  `recipePool()` builder + `addBench` into `AutoCraftEngine` (or a `ForgeSpec` for the machine-specific ones —
  see below). `cardAllowSet` becomes the DEFAULT body of `Spec.allowSet`. The fixed `FORGE_DURATION` passed to
  `PullCraftStep` becomes `spec.craftDuration(recipe)` (default impl returns the constant — this is the single
  place per-recipe timing will later compute from ingredient count).
- `ForgeTickSystem` becomes a THIN client: keep its constructor (forgeType, pipeType, networkService), the
  query, the never-throw `tick()`, and the CONTEXT-RESOLUTION prologue (Phase 2 shares it); then build an
  `AutoCraftEngine.Context` + call `AutoCraftEngine.drive(node, ctx, FORGE_SPEC)`. Define `FORGE_SPEC` as a
  `Spec` whose `recipePool()` = the Weapon/Armor/ArmorBench/Armory pool, `energyDraw()` = `FORGE_DRAW` (200),
  `postCraftDelayTicks()` = 2, `craftDuration(r)` = `FORGE_DURATION` (4.0), `allowSet(node)` = read the card.
  Keep `FORGE_DURATION` public (the GUI reads it) — have it delegate to the spec's default, or keep the constant
  and have the spec return it.
- BEHAVIOR MUST BE IDENTICAL. The diff is pure relocation. Engine-glue (not headlessly unit-testable beyond
  compile) — gate = clean compile + suite green (649) + Phase-4 in-game smoke. If a genuinely pure sub-helper
  falls out, TDD it. **Commit:** `refactor(autocraft): AutoCraftEngine owns the craft loop; ForgeTickSystem is a thin client`.

### Task 3: rename `ForgeSourcePull` → `NetworkRecipeSource`
**Files:** Rename `ForgeSourcePull.java` → `NetworkRecipeSource.java` (+ `git mv`), update the class name and
all references (in `AutoCraftEngine` after Task 2, and `ForgeSourcePullDiffTest` → `NetworkRecipeSourceDiffTest`
class name + the tested method references). It is already fully generic; this is a pure rename. `grep -rn
"ForgeSourcePull" src/` → zero after. **Gate:** suite green (649). **Commit:** `refactor(autocraft): rename ForgeSourcePull -> NetworkRecipeSource (already generic)`.

---

## Phase 2 — shared powered-machine skeleton (Forge onto it)

### Task 4: `PoweredMachineNode` interface
**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/PoweredMachineNode.java`
- Modify: `AutoCraftNode` (extend it), `MachineBlockState.java` (implement it).

```java
public interface PoweredMachineNode {
    com.chonbosmods.chemistry.api.energy.EnergyHandler energy();
    com.chonbosmods.chemistry.impl.block.PortConfig ports();
    boolean isEnabled();
}
```
Make `AutoCraftNode extends PoweredMachineNode` (drop the now-inherited energy/ports/isEnabled decls from
`AutoCraftNode`). Add `implements PoweredMachineNode` to `MachineBlockState` (it already has `energy()`,
`ports()`, `isEnabled()` — verify the signatures match exactly; `@Override`). No behavior change. **Gate:**
suite green (649). **Commit:** `refactor(machine): PoweredMachineNode contract shared by MachineBlockState + AutoCraftNode`.

### Task 5: extract the shared drive-context resolver + visual helper; Forge uses them
**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/MachineDriveContext.java`
- Modify: `ForgeTickSystem.java` (use the shared resolver).
- READ FIRST: `MachineTickSystem.driveBench` prologue (lines ~150-210) + `ForgeTickSystem.driveForge`
  prologue — they are the SAME guarded chain (verified): `getReferenceTo` → `BlockStateInfo` → `getChunkRef`
  → `BlockChunk` → block coords (`ChunkUtil` + `<<5`) → `getExternalData().getWorld()` → `resolveBlockType`.

Provide a shared resolver returning a small record (or null on any missing piece), plus the block-type
resolver + the visual swap (currently `ForgeTickSystem.resolveBlockType` + `applyVisualState`):
```java
public final class MachineDriveContext {
    public record Resolved(World world, int x, int y, int z, BlockType blockType) {}
    /** The shared guarded context prologue (ref/stateInfo/chunk/coords/world/blockType). Null if any piece
     *  is missing (chunk unloaded, block gone, etc.). Used by every machine tick. */
    public static Resolved resolve(int index, ArchetypeChunk<ChunkStore> chunk, Store<ChunkStore> store,
            ComponentType<ChunkStore, BlockModule.BlockStateInfo> blockInfoType,
            ComponentType<ChunkStore, BlockChunk> blockChunkType) { ... }
    /** Swap the placed block's interaction state to `desired`, reading current first so a packet is issued
     *  only on a real transition (today's ForgeTickSystem.applyVisualState, verbatim). */
    public static void applyVisualState(World world, int x, int y, int z, BlockType blockType, String desired) { ... }
}
```
Migrate `ForgeTickSystem` (and `AutoCraftEngine` for the visual) onto these. `ForgeTickSystem.tick()` keeps the
never-throw guard; the prologue becomes `MachineDriveContext.resolve(...)`. BEHAVIOR IDENTICAL. **Gate:** suite
green (649). **Commit:** `refactor(machine): shared MachineDriveContext resolver + visual swap; Forge uses it`.

---

## Phase 3 — migrate the processing machines (ONE at a time, each verified)

### Task 6: migrate `MachineTickSystem` (Smelter + Reclaimer) onto the shared resolver
**Files:** Modify `src/main/java/com/chonbosmods/chemistry/impl/block/MachineTickSystem.java`.

Replace `MachineTickSystem.driveBench`'s copy-pasted prologue with `MachineDriveContext.resolve(...)`, and its
`resolveBlockType` with the shared one. KEEP everything else (the creative-refill, energy drain, the vanilla
`ProcessingBenchBlock` work pass via `VanillaBenchBridge`, the bench-driven visual swap which goes through the
HELD BENCH not the accessor — do NOT change that path, it is processing-specific). The Smelter and Reclaimer
must behave EXACTLY as before. This is the one task that touches verified processing code: keep the diff
minimal (prologue + block-type only). **Gate:** suite green (649) + Phase-4 in-game smoke for BOTH Smelter and
Reclaimer. **Commit:** `refactor(machine): MachineTickSystem uses shared MachineDriveContext (Smelter + Reclaimer)`.

---

## Phase 4 — verify + finish

### Task 7: full verify + merge + finish branch
- `./gradlew test` in the worktree → 649 green.
- Final holistic review (cross-task: behavior preserved for Forge/Smelter/Reclaimer; the two improvement hooks
  are single-point; no pure-helper regressions; `grep -rn "ForgeSourcePull" src/` empty).
- Merge `feature/autocraft-engine` → `main` (devServer down for the user's rebuild). In-game smoke:
  **Forge** crafts as before (pull → ~4s → pause → next), **Smelter** smelts as before, **Reclaimer** reclaims
  as before — no behavior change anywhere.
- Then `superpowers:finishing-a-development-branch`.

## Acceptance (the whole point)
- Adding a crafting machine = a `…State` component `implements AutoCraftNode` + a ~30-line tick that resolves
  context (`MachineDriveContext.resolve`) and calls `AutoCraftEngine.drive(node, ctx, itsSpec)` + its model/GUI.
- Improving craft time = edit `AutoCraftEngine` / the default `Spec.craftDuration` ONCE → all crafting machines.
- Improving recipe scripting = edit the default `Spec.allowSet` ONCE → all crafting machines.
- Improving shared machine infra (context, visual) = edit `MachineDriveContext` ONCE → Forge + Smelter +
  Reclaimer + future machines.

## Invariants / gotchas
- NEVER-throw ticks; single-threaded (`isParallel=false`). Same-cell ports, Z-inversion, dual-copy, and
  no-gradle-while-live still apply. The Smelter/Reclaimer visual swap goes through the held bench (not the
  accessor) — do not "unify" that with the Forge's accessor swap; they are legitimately different.
- Pure helpers untouched. Every task is behavior-preserving; if any test changes value, STOP — that's a
  regression, not an expected diff.
