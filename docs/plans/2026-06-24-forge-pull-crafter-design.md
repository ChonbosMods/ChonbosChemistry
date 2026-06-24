# Forge pull-crafter: demand-driven ingredient sourcing (v1 lock)

Date: 2026-06-24. Branch: `feature/cc-forge` (worktree).
Context: The Forge (machine #3) works end-to-end as a **push-fed** auto-crafter: pipes push ingredients
into a fixed 9-slot input buffer; the even round-robin crafts whatever's craftable. In-game testing
surfaced the structural flaw the buffer model can't escape: a passive multi-recipe buffer **clogs** —
mixed ingredients for different recipes occupy slots and never form a complete set, and the recipe is only
decided at craft-time from whatever happens to be sitting there. This redesign flips the input from
push-fed to **demand-driven pull**: the Forge sources exactly one recipe's ingredients from its connected
inventories, crafts, repeats. Clog becomes structurally impossible.

## Supersedes / keeps

**Supersedes** (from `2026-06-23-forge-autonomous-craft-design.md`): the input half — the push-fed 9-slot
buffer, and the planned input ingredient-filter (no filter needed: the Forge only ever pulls valid,
complete sets, so nothing junk enters).

**Keeps, unchanged:** the even round-robin selection (`CraftSelection`/`CraftStep`), the energy gate
(`SmelterEnergy`, power-fed throughput), the recipe pool (`Weapon_Bench`/`Armor_Bench`/`ArmorBench`/
`Armory`, ~85 recipes), `VanillaCraftBridge` match/consume/outputs, the network integration
(`WorldMachineLookup` recognizing `ForgeCraftState`), ports/template/hitbox/model, and the **output**
side (results buffer → output port → network, push as before). Only the input *acquisition* changes.

## Locked decisions (v1)

1. **Sourcing** — pull from ANY inventory reachable on the Forge's input pipe network (reuses the
   network's existing container enumeration).
2. **Contention** — atomic first-come. A Forge that pulls a set has *extracted* it (reserved by removal),
   so it's gone from the chests; a competitor for the *same* limited ingredients finds them absent and
   stays idle until replenished. Exact-same-tick ties resolve by tick order (the ECS tick is
   single-threaded, so pulls never interleave — see Atomicity). Forges crafting non-overlapping
   ingredients run concurrently.
3. **Cadence** — strictly sequential: pull ONE full recipe set → craft → pull the next. No pre-staging.
4. **Input container** — drop the fixed 9-slot grid. The Forge HOLDS only the ingredients pulled for the
   ACTIVE craft (empty when idle, populated during a craft, cleared on completion). The GUI shows them as
   a **scroll-view list** — defensive headroom for a future recipe with many ingredients; it never holds
   more than one recipe's worth.

## The pull-craft loop

Per tick, for each Forge node (NEVER throws — same guard as the bench drive):

- **CRAFTING** (`currentRecipeId != null`): the ingredients are already held. Energy-gate the progress
  (`affordableDt`); when powered, advance `progress` and drain energy. On `progress >= FORGE_DURATION`:
  **consume** the held ingredients (clear the held list — they were already extracted from the world),
  **produce** the recipe's outputs into the output buffer, advance the round-robin cursor to this recipe,
  clear `currentRecipeId`, reset progress → back to IDLE. Unpowered = hold (progress + held set retained).
- **IDLE** (`currentRecipeId == null`):
  1. Energy gate: if not powered (disabled / empty buffer), stay idle, pull NOTHING (never reserve
     ingredients we can't craft). Visual = `default`.
  2. Resolve the **input network**: the item pipe network adjacent to the Forge's input face.
  3. **Aggregate** the network's reachable container contents into a virtual snapshot.
  4. `craftable` = recipes where: card-allowed (`CraftSelection.allowed`) AND the full ingredient set is
     available in the snapshot AND the output buffer has room for the result (backpressure).
  5. `pick = CraftSelection.selectNext(stableOrder, craftable, cursor)` (the locked even round-robin, now
     over "available across the chests" instead of "in the local buffer"). Null → idle.
  6. **Atomic pull**: extract `pick`'s full ingredient set from the network's chests; hold the extracted
     items; set `currentRecipeId = pick`, `progress = 0` → CRAFTING.

So the recipe is decided **at the start of each craft cycle**, from what's available — then exactly that
set is pulled. The throughput knob (power → speed) still lives in the craft phase.

## Architecture

### Input is a pull-window, not a push-destination
The Forge's item-IN port (West) stays for two reasons only: the pipe connects to it (template
`CC_ItemFace`) and the per-cell port keeps the West arm from being suppressed. But it is NOT a push
destination: `WorldMachineLookup`'s Forge item-container view returns the input container only for the
network's *resolution*, and the Forge does not advertise an insertable input to the push transport
(`itemContainer(INPUT)` → no pushable buffer), so the generic push transport never deposits random items.
The Forge drives its own input by PULL.

### The network-pull (the one genuinely new piece)
A helper (e.g. `ForgeSourcePull`) given the world + the Forge's input network:
- **aggregate()** — iterate the network's container endpoints, copy their contents into a virtual
  `SimpleItemContainer` snapshot. Lets us reuse vanilla `canRemoveMaterials(getInputMaterials(recipe, 1))`
  for the availability check (the FFI `MaterialQuantity` is never read directly — same discipline as
  `VanillaCraftBridge`).
- **tryPull(recipe)** — having confirmed availability, extract the recipe's ingredients from the REAL
  chests (per-ingredient `firstExtractable`/`extract` across containers until satisfied), returning the
  extracted `ItemStack`s for the held list. Resolves the input network via `NetworkService` (the existing
  per-world cache) and the network's container endpoints via the existing `ContainerLookup`/`ItemEndpoints`.

### Atomicity & contention (no locks needed)
The Forge tick system is single-threaded (`isParallel = false`, like `MachineTickSystem`). So within one
Forge's tick, aggregate → availability-check → extract runs with no other Forge interleaving: the snapshot
can't go stale mid-pull. Forge A extracts and commits before Forge B's tick even reads the chests. That IS
decision #2 — first-come by tick order, reservation = extraction, no half-pulls.

### State (`ForgeCraftState`)
- Keep: `energy`, `ports`, `output` (results buffer), `card`, `enabled`, `lastSelectedId` (cursor).
- Replace the 9-slot `input` with: `held` (a `SimpleItemContainer` holding the pulled ingredients of the
  active craft — sized generously, ~27 slots, to cover any real recipe; the GUI scroll-list shows the
  filled rows) + `currentRecipeId` (the active recipe, null = idle; needed to know what to produce).
- Keep `progress`. All codec-persisted so a mid-craft Forge (holding reserved ingredients) survives reload.
- On block break: eject `held` + `output` (the reserved ingredients are real items, not lost).

### GUI
Input grid → a `#HeldList` scroll-view list showing the held container's filled slots (the active craft's
ingredients). Output grid, power bar, status (Idle / Crafting <item>), card slot, on/off, eject — unchanged.

## Testing
- **Pure / headless TDD**: extend the per-tick decision (`CraftStep`) into the pull-craft state machine
  (idle vs crafting; pull-gate on powered+craftable; complete at duration; cursor advances on completion).
  The even round-robin stays `CraftSelection` (already tested). Any pure multiset availability helper.
- **Engine-bound, in-game verified** (like the rest of the bridge + network glue): the network resolve,
  aggregate snapshot, vanilla availability check, and multi-chest extraction. Smoke: two chests of
  ingredients on the input network, Forge pulls exactly one recipe's worth, crafts, repeats; a recipe with
  a missing ingredient is skipped; two Forges competing for the last set → one wins, the other idles;
  mid-craft reload keeps the held set + progress; break ejects held + output.

## Deferred / rejected
- **Push-fed buffer + ingredient filter** — REJECTED (the clog this redesign exists to kill).
- **Pre-staging the next recipe while crafting** — deferred (decision #3: strictly sequential v1).
- **True randomness on contention ties** — deferred; tick-order suffices (effectively arbitrary) for v1.
- **`target: N` keep-stocked card field** — still deferred (additive on the card schema), unchanged.
