# Cooker Machine Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Build the **Cooker** machine as a PURE auto-craft machine that drives BOTH the vanilla Campfire
*processing* recipes AND the Cooking *crafting* recipes through the one `AutoCraftEngine` — by treating the
Campfire processing recipes as crafting recipes (no held `ProcessingBenchBlock`, no dual-half). It cooks each
recipe for its own time (`getTimeSeconds()`), energy-gated, fuel-free.

**Key insight (verified):** Campfire (`BenchType.Processing`, id `"Campfire"`) and Cooking
(`BenchType.Crafting`, id `"Cookingbench"`) recipes are BOTH `CraftingRecipe` objects, and
`CraftingManager.getInputMaterials`/`getOutputItemStacks` read input→output off either uniformly. So the
Cooker's recipe pool is just the UNION of both benches, run through the existing `VanillaCraftBridge`. Per-recipe
time comes from `CraftingRecipe.getTimeSeconds()` (confirmed `public float`) wired to the engine's existing
`Spec.craftDuration` hook. This SUPERSEDES `design.md` §7's held-bench hybrid plan for the Cooker (update the doc).

**Architecture:** The Cooker mirrors the **Forge** (a pure auto-craft machine), reusing everything built in the
autocraft-engine encapsulation: `AutoCraftEngine`, `AutoCraftNode`, `PoweredMachineNode`, `MachineDriveContext`,
`NetworkRecipeSource`, `RecipePool`, `PullCraftStep`, `CraftSelection`, `VanillaCraftBridge`. New per-machine
pieces (component, ports, tick+spec, JSON, GUI, model) are near-copies of the Forge's, plus the recipe-pool
union + per-recipe timing. No byproducts (energy is the fuel).

**Tech stack:** Java 25, Hytale Server 0.5.3, Gradle, JUnit 5. Worktree `.worktrees/cc-cooker` on branch
`feature/cc-cooker`; `./gradlew test` there is isolated/safe. In-game verify needs a merge to `main`.
Baseline suite: **654 tests** green. Behavior of the Forge/Smelter/Reclaimer must NOT regress.

**Mirror references (read these per task):** Forge — `impl/block/craft/{ForgeCraftState,ForgePorts,ForgeTickSystem}.java`,
`impl/block/ui/ForgePanelPage.java`, `ChonbosChemistry.java`, `impl/block/net/WorldMachineLookup.java`,
`impl/block/CarryBreakEventSystem.java`, `impl/block/BlockHolderCarry.java`,
`resources/Server/Item/Items/ChonbosMods/CC_Forge.json`, `resources/Server/Item/Block/Hitboxes/ChonbosMods/CC_Forge.json`,
`resources/Server/Item/CustomConnectedBlockTemplates/CC_ForgeTemplate.json`, `resources/Common/UI/Custom/Pages/CC_ForgePanel.ui`.
Smelter (for the 2-wide footprint/hitbox) — `CC_Smelter.json` + its hitbox.

---

## Phase 1 — Engine: per-recipe craft duration (one shared seam)

### Task 1: `VanillaCraftBridge.recipeTimeSeconds(recipe)`
**Files:** Modify `src/main/java/com/chonbosmods/chemistry/impl/block/bench/VanillaCraftBridge.java`; Test:
`src/test/.../bench/` only if a pure bit emerges (this is a thin engine wrapper — gate is compile + suite).
Add a thin wrapper exposing the recipe's cook time (the single place the engine reads per-recipe timing):
```java
/** The recipe's processing/cook time in seconds (vanilla {@code CraftingRecipe.getTimeSeconds()}). A
 *  crafting recipe with no timed processing returns 0; the caller supplies a default. */
public static float recipeTimeSeconds(CraftingRecipe r) {
    return r.getTimeSeconds();  // verified: public float on CraftingRecipe (Server-0.5.3)
}
```
Confine the `builtin.crafting`/asset read here, matching the bridge's style. **Commit:** `feat(cooker): VanillaCraftBridge.recipeTimeSeconds (per-recipe cook time)`.

---

## Phase 2 — Cooker state + ports + tick

### Task 2: `CookerState` component (mirror `ForgeCraftState`)
**Files:** Create `src/main/java/com/chonbosmods/chemistry/impl/block/craft/CookerState.java`; Test:
`src/test/.../craft/CookerStateCodecTest.java`.
A near-verbatim copy of `ForgeCraftState`: same fields (energy/ports/held(27)/output(4)/card/progress/cursor/
currentRecipeId/transient craftDelay), same `BuilderCodec` (rename keys' OWNER only — keep the SAME codec key
STRINGS "Energy"/"Ports"/"Held"/"Output"/"Card"/"Progress"/"Cursor"/"Current"/"Enabled"), `implements
Component<ChunkStore>, AutoCraftNode` with all `@Override` accessors, `clone()` via codec round-trip. Mirror the
codec test (`CookerStateCodecTest` = copy of `ForgeCraftStateCodecTest`). NOTE: a distinct class is REQUIRED
(ECS keys components by class, so the network/tick can tell a Cooker from a Forge) — this duplication is
expected; a future pass may extract a shared base, but NOT now (keep the Cooker additive + low-risk).
**Commit:** `feat(cooker): CookerState component (AutoCraftNode) + codec test`.

### Task 3: `CookerPorts` (mirror `ForgePorts`)
**Files:** Create `src/main/java/com/chonbosmods/chemistry/impl/block/craft/CookerPorts.java`; Test:
`src/test/.../craft/CookerPortsTest.java` + (if the Forge has one) a JSON-ports guard test.
Copy `ForgePorts`: the 2-wide default layout — item OUTPUT on anchor East (face 0, cell 0,0,0), item INPUT on
the West cell (face 1, cell -1,0,0), power INPUT on anchor North (face 5). (Matches the Cooker model's
Port_ItemIn/Port_ItemOut/Port_Power.) Mirror the Forge's ports test. **Commit:** `feat(cooker): CookerPorts default layout + guard test`.

### Task 4: `CookerTickSystem` + `COOKER_SPEC` (thin client of AutoCraftEngine)
**Files:** Create `src/main/java/com/chonbosmods/chemistry/impl/block/craft/CookerTickSystem.java`.
A near-copy of `ForgeTickSystem` (the thin post-encapsulation version): constructor
`(ComponentType<ChunkStore,CookerState> cookerType, ComponentType<ChunkStore,PipeNode> pipeType, NetworkService
networkService)`, `isParallel=false`, `getQuery()=cookerType`, never-throw `tick()`, the
`MachineDriveContext.resolve(...)` prologue, build `AutoCraftEngine.Context`, call
`AutoCraftEngine.drive(node, ctx, COOKER_SPEC)`.
`COOKER_SPEC` (an `AutoCraftEngine.Spec`):
- `recipePool()` → cached `RecipePool.union(List.of(new RecipePool.BenchRef(BenchType.Processing, "Campfire"),
  new RecipePool.BenchRef(BenchType.Crafting, "Cookingbench")))`. Keep the once-logged pool-size diagnostic
  (`"Cooker recipe pool built: N recipes."`) — if N is 0 in-game, a bench id is wrong (verify in Phase 5).
- `energyDraw()` → a `COOKER_DRAW` constant (start at 200L like the Forge; [TUNE]).
- `postCraftDelayTicks()` → 2 (the visual cadence beat).
- `craftDuration(r)` → `float t = VanillaCraftBridge.recipeTimeSeconds(r); return t > 0f ? t : COOKER_DEFAULT_DURATION;`
  (Campfire raw-cooks use their real time; Cooking dishes — instant in vanilla, time 0 — fall back to
  `COOKER_DEFAULT_DURATION`, a [TUNE] constant ~4.0f). Keep `COOKER_DEFAULT_DURATION` public if the GUI needs a
  fallback fraction (the panel derives progress per-recipe — see Task 8; if simplest, the panel reads the same
  `recipeTimeSeconds`-or-default for the active recipe).
- `allowSet(node)` → `AutoCraftEngine.cardAllowSet(node.card())` (same card scripting as the Forge).
**Commit:** `feat(cooker): CookerTickSystem + COOKER_SPEC (Campfire ∪ Cooking pool, per-recipe cook time)`.

---

## Phase 3 — Network recognition + carry + registration

### Task 5: `WorldMachineLookup` recognizes `CookerState` (mirror the forge branch)
**Files:** Modify `src/main/java/com/chonbosmods/chemistry/impl/block/net/WorldMachineLookup.java`.
Add a `cookerType` field + ctor param, and a 4th branch in `at(...)` (after the forge branch): resolve a
`CookerState`, project its ports, and `adapt(cell, model, cooker.energy(), channel -> null, cookerItems(cooker))`.
`cookerItems(cooker)` mirrors `forgeItems`: INPUT → `null` (pull-window — the Cooker pulls via
`NetworkRecipeSource`, the network must NOT push into `held`), OUTPUT → `cooker.output()`. **Commit:** `feat(cooker): network recognizes CookerState (item I/O pull-window + power)`.

### Task 6: carry `CookerState` on break (mirror the forge carry)
**Files:** Modify `BlockHolderCarry.java` (add `shouldCarry(CookerState)` overload, same predicate as the Forge:
energy>0 OR held/output non-empty) + `CarryBreakEventSystem.java` (thread a `cookerType` ctor param + a 4th
carry branch). **Commit:** `feat(cooker): carry held+output on break (BlockHolder carry path)`.

### Task 7: register everything in `ChonbosChemistry`
**Files:** Modify `src/main/java/com/chonbosmods/chemistry/ChonbosChemistry.java`.
Mirror the Forge wiring: register the `CookerState` component (`registerComponent(CookerState.class,
"CookerState", CookerState.CODEC)`) → `cookerComponentType` field + accessor; register
`new CookerTickSystem(cookerComponentType, pipeComponentType, networkService)` AFTER `networkService` exists;
thread `cookerComponentType` into the `WorldMachineLookup` construction sites (it's built inside
`NetworkTickSystem` — so `NetworkTickSystem`'s ctor gains a `cookerType` param and passes it through; update
that ctor + its call) and into `CarryBreakEventSystem`. Register the `CookerPanelPage` page id (mirror the
ForgePanelPage registration). Update the registration log lines. **Commit:** `feat(cooker): register CookerState + CookerTickSystem + thread cookerType through network/carry`.

---

## Phase 4 — Assets: JSON, hitbox, template, model, GUI

### Task 8: GUI — `CC_CookerPanel.ui` + `CookerPanelPage` (mirror the Forge panel)
**Files:** Create `src/main/resources/Common/UI/Custom/Pages/CC_CookerPanel.ui` (copy `CC_ForgePanel.ui`,
retitle) + `src/main/java/com/chonbosmods/chemistry/impl/block/ui/CookerPanelPage.java` (copy `ForgePanelPage`,
read `cookerType` instead of `forgeType`, title "Cooker"). Keep the held-ingredients scroll list, output grid,
power/progress bars, status (Off/Idle/Active via the truthful `currentRecipeId != null`), the `lastSentEnabled`
button-diff, the card slot, eject. Progress fraction: derive against the ACTIVE recipe's duration
(`recipeTimeSeconds`-or-`COOKER_DEFAULT_DURATION`) — if cleanly accessible — else against
`COOKER_DEFAULT_DURATION` (note it; per-recipe-accurate bar can follow). NOTE (future, not now): the Forge +
Cooker panels are near-identical — a shared `AutoCraftPanelPage` (like `BenchMachinePanelPage` shares
Smelter/Reclaimer) is the eventual cleanup; copy for v1 to keep the Forge GUI untouched. **Commit:** `feat(cooker): CC_CookerPanel GUI + CookerPanelPage`.

### Task 9: block/item JSON + hitbox + template + model copy
**Files:**
- Create `src/main/resources/Server/Item/Items/ChonbosMods/CC_Cooker.json` (copy `CC_Forge.json`):
  `Component = CookerState` (NOT a vanilla Bench block — the Cooker owns its containers), the ports block
  (item-out anchor East, item-in West cell -1, power North), `HitboxType = CC_Cooker`, States Processing/
  default (Light/effects like the Forge, using the Cooker textures), `ConnectedBlockRuleSet → CC_CookerTemplate`,
  the model `CC_Cooker` (DrawType Model), and the GUI interaction opening `CC_CookerPanel`. NO vanilla `Bench {}`
  block (the recipes come from the engine pool, not a held bench).
- Create `src/main/resources/Server/Item/Block/Hitboxes/ChonbosMods/CC_Cooker.json`: the 2-wide hitbox —
  COPY the SMELTER's hitbox (`CC_Smelter.json` hitbox: the 2-box stepped shape), since the Cooker is "the same
  size as the Smelter". (The Forge is also 2-wide; either works — match the Smelter per the user.)
- Create `src/main/resources/Server/Item/CustomConnectedBlockTemplates/CC_CookerTemplate.json` (copy
  `CC_ForgeTemplate.json`): South→CC_PowerFace, West/East→CC_ItemFace (the per-cell face tags driving arm
  suppression + pipe connection). Confirm the Z-inversion (rear power tags template "South" per the
  template-facetag-z-inversion convention).
- Copy the model + textures into resources (the dual-copy: source in `models/_cc_machines`, game copy in
  `resources`): `models/_cc_machines/CC_Cooker.blockymodel` → `src/main/resources/Common/Blocks/ChonbosMods/Machines/CC_Cooker.blockymodel`,
  and `CC_Cooker_Texture.png` + `CC_Cooker_Texture_Off.png` → the same dir. (Mirror where `CC_Forge.blockymodel`
  + its two textures live.) Re-copy after any model edit (per the machine-model-asset-copy rule).
**Commit:** `feat(cooker): CC_Cooker block/item JSON, hitbox (2-wide), template, model + textures`.

---

## Phase 5 — Verify + finish

### Task 10: verify + merge + finish branch
- `./gradlew test` in the worktree → green (654 + the new CookerState/CookerPorts codec/ports tests).
- Final holistic review: the Cooker is a faithful Forge-mirror + the union pool + per-recipe timing; Forge/
  Smelter/Reclaimer unaffected; the network/carry/registration wiring is symmetric with the Forge; no pure-helper
  regressions.
- Merge `feature/cc-cooker` → `main` (devServer down for the user's rebuild). In-game smoke:
  - The "Cooker recipe pool built: N recipes" log shows N > 0 (confirms `getBenchRecipes(Processing,"Campfire")`
    + `(Crafting,"Cookingbench")` resolve — if 0, fix the bench id).
  - Place a Cooker (2-wide), pipe raw food (e.g. raw meat/veg) into the West input from a chest, power it →
    it pulls one recipe's ingredients, cooks for the recipe's `getTimeSeconds()`, outputs the cooked item;
    rotates across craftable recipes; junk it can't cook is not pulled (the same filter/extraction behavior).
  - On/Off, eject, status (Off/Idle/Active + "Processing N%"), break-carry, the visual Processing/default swap.
- Then `superpowers:finishing-a-development-branch` (merge, remove the worktree, delete the branch).

## Invariants / notes
- NEVER-throw ticks; single-threaded; same-cell ports + Z-inversion + dual-copy + no-gradle-while-live all apply.
- The Cooker is pure auto-craft: NO held `ProcessingBenchBlock`, NO vanilla fuel (energy-gated), NO byproducts.
- Distinct `CookerState`/`CookerPorts`/`CookerTickSystem`/`CookerPanelPage` classes are required (per-machine ECS
  types + GUI); the near-duplication vs the Forge is expected. Future cleanups (NOT now): a shared auto-craft
  component base, a shared `AutoCraftPanelPage`, and a generalized machine-lookup that recognizes any
  `AutoCraftNode` without a per-machine branch.
