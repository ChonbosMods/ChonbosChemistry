# Forge: autonomous-craft engine + crafting selection model

Date: 2026-06-23. Branch: `feature/cc-forge` (worktree, off `feature/cc-fluids-mugtastic-party`).
Context: Machines #1 Smelter (Furnace) and #2 Reclaimer (Salvage) are done — both wrap a vanilla
**Processing** bench (`ProcessingBenchBlock`, autonomous + timed). The Forge is machine #3 (D47/§7.10)
and the first **pure-crafting** machine. We build it next deliberately: it forces the **autonomous-craft
engine** that the hybrids (Cooker, Outfitter) and the other crafting machines (Alembic, Assembler,
Cultivator) all depend on. Resolves design.md open Q#4 (recipe-card UX).

## What the Forge wraps

Crafting benches are player-driven + instant (no per-block state, no autonomous tick) — the opposite of
the Processing benches we already wrap. The Forge clusters three vanilla crafting benches:

| Bench Id | BenchType | ~recipes |
|---|---|---|
| `Weapon_Bench` | `Crafting` | 51 |
| `Armor_Bench` (+ alt `ArmorBench`) | `Crafting` | ~34 |
| `Armory` | `DiagramCrafting` | 19 |

Recipe pool = union of `CraftingPlugin.getBenchRecipes(type, id)` over that cluster (~100 recipes),
pulled at runtime.

## Vanilla API (decompile-confirmed)

- **No vanilla autonomous crafting block** exists (unlike `ProcessingBenchBlock` for Processing). So the
  Forge cannot "hold a bench" the way the Smelter does.
- `CraftingManager.craftItem(...)` requires a **player entity + inventory** → not usable for a machine.
- Reusable, player-free pieces:
  - `CraftingPlugin.getBenchRecipes(BenchType, String benchId)` → `List<CraftingRecipe>` (registry keyed
    by bench id; built automatically on recipe asset load).
  - `CraftingManager.matches(MaterialQuantity ingredient, ItemStack stack)` (static) — ingredient match.
  - `CraftingManager.getOutputItemStacks(recipe[, count])` (static) — result stacks.
  - `CraftingRecipe`: `getInput()` (`MaterialQuantity[]`), `getOutputs()`/`getPrimaryOutput()`,
    `getBenchRequirement()`, `getTimeSeconds()`, `isRestrictedByBenchTierLevel(id, tier)`.
- Conclusion: **we compose our own engine** from these helpers — own item containers, our own match +
  consume + produce, driven on our energy-gated tick. Vanilla crafting carries `TimeSeconds == 0`
  (instant), so WE impose the craft duration.

## Crafting selection model (v1 — LOCKED)

Base behavior is **input-driven and card-optional**. The machine already knows its full bench recipe set.

- **No card inserted:** may craft any of the bench's recipes, gated only by which inputs are present in
  the input buffer.
- **Card inserted:** a pure **permission filter** that narrows the allowed set. The card is an
  **unordered allow-set** — no slot priority, no ordering semantics.

**Selection runs each tick:**

1. `craftable = (bench recipes ∩ card allow-set, or all bench recipes if no card)` filtered to those
   whose **full inputs are present** in the input buffer.
2. `craftable` empty → **idle**.
3. exactly one → **craft it**.
4. several → pick via a **round-robin cursor** that advances only across *currently-craftable* recipes, so
   output rotates evenly and never fixates on one.

This is the sanctioned resolution for **same-input-bag collisions** (e.g. axe and shovel sharing identical
ingredients): the machine alternates evenly across all tied/craftable outputs rather than locking onto
whichever sorts first.

- Cursor is a **deterministic** position, NOT per-tick RNG — guaranteed evenness, no streaks,
  reproducible, consistent with the rest of the system.
- **Value/priority plays no role** in v1 selection; rotation is purely even.
- The cursor **skips non-craftable recipes**, so it never idles while something is makeable.

**Backpressure (unchanged from the Processing machines):** a full output buffer **stalls** the machine.
"Make N then stop" stays a downstream buffer-sizing concern for v1, not a machine setting.

## Engine architecture (follows the §7.3 / Smelter substrate)

- **Own item containers.** Because there is no vanilla bench block to borrow, the machine's block-state
  holds its **own** input + output `ItemContainer`s (codec-persisted), plus a **card slot**. This is the
  one architectural addition over the Processing machines.
- **Per-tick drive** (mirrors `MachineTickSystem.driveBench`'s energy gate):
  1. compute `craftable` set (above);
  2. select one via the round-robin cursor;
  3. energy gate — only advance when `affordableDt(stored, DRAW, dt) > 0` (reuse `SmelterEnergy`); the
     power fed sets the speed (overclock, D32/D33);
  4. accumulate progress over the imposed craft duration; on completion **consume** the ingredient set
     (no partial commits) and **produce** `getOutputItemStacks` into the output container; **drain** energy;
  5. block visual state via the existing `MachineVisualState` path (Processing / default).
- **Ports + GUI reuse:** item-in / item-out / power-in ports (same port system, fixed in the bench work)
  and the `BenchMachinePanel` GUI, parameterized — adding an input grid, output grid, **card slot**, and
  the existing power bar.
- **Footprint/model:** `CC_Forge.blockymodel` exists in `models/_cc_machines`; footprint + default ports
  decided in the asset pass (start single-cell anchor unless the model needs multi-cell).

## Recipe card item

- Optional item; when present in the card slot it supplies the **unordered allow-set** of `recipeId`s the
  machine may craft. Read each tick by the selection step above.
- **Schema (kept open):** an allow-set of recipe ids. Persisted on the item. Designed so the deferred
  `target: N` field is an **additive** key on the same schema, not a fork.
- **Card programming UX is DEFERRED** (a future recipe-card programming system). v1 ships with the
  no-card behavior fully functional (auto-craft from inputs, round-robin); the card layer reads an
  allow-set if one is present. Test cards can be hand-authored / debug-seeded until the programming UX lands.

## Deferred (do NOT build now; do not paint the card schema into a corner)

- **Automated insert / remove / replace of cards** in machines (programmatic card swapping).
- **`target: N`** field on the card enabling keep-stocked / output-driven behavior — additive field on the
  existing schema once output sensing exists, NOT a schema fork.

## Settled / rejected (do NOT reopen)

- **Output-driven targets as the v1 paradigm** — deferred above; requires output sensing.
- **Blacklist cards** — a small allow-set is the correct default; a blacklist gives the wrong default and
  doesn't resolve collisions.
- **Slot-order priority as the tie-breaker** — superseded by even round-robin (this replaces the earlier
  design.md:296 "selected recipeId" priority sketch).
- **Multi-recipe "program card"** — deferred; if factory-scale loadout cloning ever justifies it, it
  compiles down to the same permission set (one engine, two config front-ends).

## Testing

- **Headless TDD** for the pure selection logic: `craftable`-set computation (bench ∩ allow-set ∩
  inputs-present), the round-robin cursor (evenness, skips non-craftable, never idles while makeable,
  deterministic), consume/produce accounting. These are pure functions over fakes — no engine.
- **In-game verify** (sync worktree → main checkout; worktree builds don't reach the running game): place
  a Forge, pipe ingredients, confirm it auto-crafts with even rotation across collisions, energy-gates,
  stalls on full output, and that a hand-seeded card narrows the set.
