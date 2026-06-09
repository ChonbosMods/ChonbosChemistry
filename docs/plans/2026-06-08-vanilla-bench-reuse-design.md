> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# Vanilla Bench Reuse as the Machine Substrate — Design

*Status: validated 2026-06-08 (decompile-backed, not yet spiked in-game). Proposes reusing Hytale's built-in bench engine as the substrate for the §3 machines instead of building a processing engine from scratch. Decision recorded: build by **compose + delegate**, fuel→energy via a no-fuel config, non-invasive. The composition spike (devServer) is the gating next step before committing. Cross-refs: `machines-and-power-design.md` §3 (seven machines), §5.4/§5.5 (ports/buffers), §8 (rules), §9 (recipe schema), §16 (impl API).*

## Why

The machine milestone (`machines-and-power-design.md` §0 roadmap item 1) needs a per-machine work loop: read inputs, match a recipe, advance progress, gate on resources, produce outputs, drive ON/OFF visuals + sounds, persist across save/reload, and expose a GUI. Hytale's built-in **processing benches** (Furnace, Campfire, Cooking, Lumbermill) already implement *all* of that. Rather than reimplement it (and the "undecided machine work-state + GUI" gap), we reuse it and layer our additions: energy, pipe I/O, programmable recipes, and the chemistry/nuclear rules.

## Evidence (decompiled `HytaleServer.jar`, `builtin.crafting.*`)

- **`ProcessingBenchBlock implements Component<ChunkStore>`** — a block-entity component, same family as our `MachineBlockState`. Codec-persisted fields: `InputContainer`, `FuelContainer`, `OutputContainer` (`ItemContainer`), `Progress`, `FuelTime`, `Active`, `RecipeId`, `LastTickGameTime`.
- **`BenchSystems.ProcessingBenchTick extends EntityTickingSystem<ChunkStore>`**, query = the component type → **runs on every processing bench every tick, no player or open window required**. Open windows only *receive* progress (`sendProgress`). It auto-detects the recipe (`checkForRecipeUpdate`), checks output room, calls `advanceProcessing(dt, …)`, consumes inputs/fuel, sets block state (`"Processing"`/`"ProcessCompleted"`/`"default"`) + sounds, and uses *game-time* elapsed (back-fills after chunk reload, capped at `MAX_UNLOAD_ELAPSED_SECONDS = 86400`).
- **Public surface we can call:** class is `public`, non-`final`, no-arg ctor; methods `advanceProcessing`, `consumeFuelForDuration`, `checkForRecipeUpdate`, `setActive`, `setInputProgress`, `initializeBenchConfig(BlockType)`, `setupSlots(…)`, `getInput/Fuel/OutputContainer()`, `getRecipe`, `getRecipeTimeSeconds(tier)`, `setBlockInteractionState` are all public. `ItemContainer` exposes public `addItemStack`, `addItemStackToSlot`, `getItemStack`, `removeItemStackFromSlot`, `canAddItemStacks`. Component type via public `CraftingPlugin.get().getProcessingBenchBlockComponentType()`.
- **`advanceProcessing` fuel gate is conditional:** `hasFuelSlots = processingBench.getFuel() != null`. If `hasFuelSlots && !active → return 0`, and fuel caps completions and is consumed. **If a bench config has no fuel slots, none of that runs** — it advances on `dt` + input availability + output room alone.
- **Crafting benches are different:** no autonomous tick. Crafting is `CraftingManager.craftItem(ref, accessor, recipe, qty, container)` — a `Component<EntityStore>` driven by a `CraftRecipeAction` packet — firing cancellable `CraftRecipeEvent.Pre` then `Post`. But `CraftingManager.getInputMaterials(recipe)` and `getOutputItemStacks(recipe)` are **public static**, so an autonomous crafter can be built without a player or a `CraftingManager` instance.
- **Lookup already exists:** our `WorldMachineLookup` does `BlockModule.getBlockEntity(world,x,y,z)` → `store.getComponent(ref, type)`; targeting a bench is the same call with the bench component type.

## Decisions

1. **Compose + delegate, not `extends`.** Two reasons literal inheritance is the wrong tool: (a) ECS systems tick by *exact* `ComponentType`, so a subclass under a new type isn't auto-ticked by vanilla anyway; (b) the parent's private fields + its own `BuilderCodec` make subclass persistence painful. Instead, **our own component (`CCMachineState`) holds a `ProcessingBenchBlock` + an `EnergyBuffer`**, registered under *our* component type and driven by *our* tick system. Vanilla's lifecycle/tick never run on our block ⇒ non-invasive by construction; reuse the vanilla `ProcessingBenchBlock.CODEC` for the held instance.
2. **Fuel → energy via a no-fuel config.** Each machine block declares a `ProcessingBench` config with input/output slots and **no `Fuel` slots**. `advanceProcessing` then skips the active/fuel gate and never calls `consumeFuelForDuration`. Our tick: if `EnergyBuffer` can pay, call `advanceProcessing`; drain energy ∝ work done (recipe `energy` field, §9; signed energy supported — exothermic recipes credit instead of debit).
3. **Energy cost on crafting.** Our crafting machine performs crafts in our tick using the public `getInputMaterials`/`getOutputItemStacks` + `ItemContainer` remove/add, charging `EnergyBuffer` first. To tax *player-driven* vanilla crafts (optional, separate), hook `CraftRecipeEvent.Pre` (cancellable) to deduct or deny.
4. **Autonomous crafting.** Add our own `EntityTickingSystem` (we already have `MachineTickSystem`) that drives crafts on our component — no player/window. This is the "robust inherited crafting bench" the brief asks for.
5. **Recipe-script item (programmable recipes).** Our component stores a selected `recipeId` (precedent: `ProcessingBenchBlock.recipeId` + `checkForRecipeUpdate`). A "recipe card" item inserted by the player sets it; we resolve the `CraftingRecipe` via `CraftingPlugin.getBenchRecipes(...)`. Processing machines may auto-pick from inputs instead; programmable machines force the scripted recipe.
6. **One bridge class.** All calls into `builtin.crafting.*` go through a single `VanillaBenchBridge` (lookup, setup, advance, container I/O). `compileOnly` against the server jar. A Hytale update that shifts an internal signature breaks one file, guarded by a devServer smoke test.

## Pipe + port integration

The machine's `inputContainer` / `outputContainer` become network endpoints (§5.4 ports, §13). The ITEM channel already pushes/pulls vanilla containers (chests); a machine port is the same target resolved to a `ProcessingBenchBlock` container instead of a block inventory. PULL on an output face drains finished goods; PUSH on an input face feeds ingredients; the existing flow-state filter applies. FLUID/GAS recipes (e.g. water synthesis) feed/drain the machine's tank-backed buffers (§5.5) rather than item slots.

## Mapping to the seven machines (§3)

Each chemistry/nuclear machine is "a no-fuel `ProcessingBench` with custom `CraftingRecipe` assets, energy-gated":
- **Synthesizer / Decomposer / Converter** — processing machines; recipes auto-derived/curated (§9.2); signed energy via Decision 2.
- **Irradiator / Transmuter** — processing machines that *also* debit the neutron buffer (§6) in our tick before `advanceProcessing`.
- **Reactor** — produces (credits) energy + neutron flux; same loop inverted (output to networks).
- **Decay Vault** — not recipe-driven; stays its own passive system (§9.2), unaffected.

This reuses vanilla's slot layout, ingredient filters, progress math, `Processing`/`ProcessCompleted` visual states, sounds, and the bench GUI window — closing the §0 "machine work-state + GUI" gap.

## Risks & mitigations

- **`builtin.*` is internal, not a stability-guaranteed API.** Public signatures can shift per Hytale version. → All access behind `VanillaBenchBridge`; devServer smoke test in CI-of-record.
- **`advanceProcessing` side effects.** It sets block visual state + plays sounds + can eject/extra-output. → Our machine `State.Definitions` must define `"Processing"`/`"ProcessCompleted"`/`"default"` (free visuals/sounds), or we suppress by config. `initializeBenchConfig` requires `blockType.getBench()` to be a `ProcessingBench`, so the machine asset must carry that config (which also hands us the slot/filter system for free).
- **Double-tick / ownership.** Our block must NOT declare the vanilla component type (only ours), or vanilla would tick it too. Verified: vanilla adds/ticks only its own type.
- **Recipe registration.** Our recipes must be discoverable as `CraftingRecipe` assets for `checkForRecipeUpdate`/`getBenchRecipes`, or we set the recipe directly. Reuses vanilla `CraftingRecipe` either way.

## Open questions (fold into the comprehensive doc)

1. Energy-only vs. optional fuel tiers — do any machines keep a fuel slot (hybrid), or is energy universal? (Decision 2 assumes energy-only.)
2. Player-driven craft taxing — do we gate manual use of *our* machines via the `CraftRecipeEvent` hook, or are our machines automation-only with no player window?
3. Recipe-card UX — one card = one recipe, or a multi-recipe/program card; and where it sits in the machine GUI (a config slot vs. our own page).
4. Multiblock interaction — the §5.4 "≥4×4 multiblock" model vs. a bench being a single block entity: does the machine *anchor* carry the `CCMachineState`, with faces mapped to ports? (Ties to the still-undesigned multiblock assembly.)

## Spike (gating next step)

`CCMachineState` wrapping a no-fuel `ProcessingBenchBlock`: register our component type + lifecycle (call `initializeBenchConfig` + `setupSlots`), a tick that energy-gates + delegates to `advanceProcessing`, and a test machine block with a trivial recipe. Success = it processes under *our* component type with vanilla untouched, energy drains, output appears, survives save/reload. Proves the substrate before any machine content is authored.

## Testing

Headless where possible: `CCMachineState` codec round-trip (held `ProcessingBenchBlock` + `EnergyBuffer`); energy-gate logic as a pure function (pay/skip/credit on signed energy); recipe-resolution from a recipe-card id. Engine glue (`advanceProcessing` under our type, visuals, container I/O, persistence) verifies on devServer per the spike.
