# CC Smelter — First Machine Implementation Design

*Status: validated 2026-06-19 (brainstorming session). The Smelter is the first of the eight automated vanilla benches (design.md §7.10) to be implemented, chosen as the proving ground for the whole machine substrate (§7.3): the held-bench bridge, energy gating, multi-cell footprint, pipe I/O, and the furnace-shaped GUI. Authoritative parent: [docs/design.md](../design.md) §7.3, §7.5, §7.10. Decompile detail: [2026-06-08-vanilla-bench-reuse-design.md](2026-06-08-vanilla-bench-reuse-design.md). Asset deliverables: [2026-06-08-asset-creation-list.md](2026-06-08-asset-creation-list.md).*

## Goal

Ship the **Smelter** (wraps the vanilla Furnace; pure-processing, §7.10) end to end so all machine-layer logic is proven once: a placeable multi-cell block that smelts furnace recipes, runs only on piped-in **power**, takes items from an **ITEM input pipe** and pushes results to an **ITEM output pipe**, lights its coils while working, and opens a furnace-shaped monitor/control GUI on Interact-F. Items move **only** through pipes — the GUI never lets you hand-insert.

## What already exists (do not rebuild)

- **Transport layer is DONE** (design.md §0.3): POWER and ITEM pipe networks, endpoints, fair-split distribution, the wrench, the live-refresh panel.
- **Machine scaffolding** (`impl/block/`): `MachineBlockState` (component holding `EnergyBuffer` + per-channel `ResourceBuffer`s + `PortConfig` + a `WorkState` stub + a `CreativeSource` flag), `MachineTickSystem` (ticks it; WORK pass currently stubbed), `Port`/`PortConfig`, `EnergyBuffer` (full `EnergyHandler`).
- **GUI**: `MachinePanelPage` (`CustomUIPage`) + `Common/UI/Custom/Pages/CC_MachinePanel.ui`, opened on Interact-F, with the live-refresh delta-update system.
- **Model + textures**: `models/_cc_machines/CC_Smelter.blockymodel` with named port nubs (`Port_ItemIn` west, `Port_ItemOut` east, `Port_Power` back), glowing `Coil1-3`, `ControlPanel`, `Window_Glass`; `CC_Smelter_Texture.png` (on) + `CC_Smelter_Texture_Off.png` (off).

## Decisions (this session)

- **D-S1. Work engine = vanilla bench bridge** (design D31). `MachineBlockState` gains a held `ProcessingBenchBlock`; the `WorkState` stub's role is retired (kept only if still useful for the energy/progress math the bench doesn't expose). A single `VanillaBenchBridge` (`compileOnly` against the server jar) is the only class touching `builtin.crafting.*`.
- **D-S2. Footprint = the furnace's.** The Smelter was modelled to the furnace's shape/size deliberately. We ship `CC_Smelter` hitbox copying `Bench_Furnace`'s two boxes (X −0.9…0.9 ≈ **2 wide**, Z 0…0.95 **1 deep**, Y 0…2 **2 tall**), `VariantRotation: NESW`. Multi-cell from day one.
- **D-S3. Furnace-shaped custom GUI**, not the vanilla window. We intercept Interact-F ourselves, so the vanilla bench window (bound to a vanilla bench entity) is not reused; we build a furnace-shaped page from Hytale UI item elements wired to the held bench containers.
- **D-S4. Display-only slots (pipe-only contract).** The GUI is monitor + control, never a back-door inventory. Input/output slots render the held containers but cannot be clicked to move items. The only item movement is pipes (or carrying the block). Planned v1.1 controls that respect this: an **Eject** button (flush output to the output network / drop) and an **On/Off** switch (manual run gate).
- **D-S5. Overclock + signed recipe energy deferred.** v1 advances at real `dt` (1× cap) with a flat energy debit; the `dtBudget` scale hook is left in place for D33.
- **D-S6. Test power = the existing `CreativeSource` flag.** A creative-source block on the POWER network drives the Smelter until real generators exist.

## Architecture

### Composed component (`MachineBlockState` as `CCMachineState`)

```
MachineBlockState (Component<ChunkStore>, anchor cell only)
├── EnergyBuffer          energy (POWER)          [exists]
├── PortConfig            (cell-offset, face) → channel + direction  [exists]
├── held ProcessingBenchBlock   item slots + recipe match + progress + states  [NEW, via VanillaBenchBridge]
└── CreativeSource flag   test power feed         [exists]
```

`VanillaBenchBridge` (the only `builtin.crafting.*` chokepoint):
- construct + `initializeBenchConfig(BlockType)` + `setupSlots(...)` a `ProcessingBenchBlock` with **2 Input / 4 Output / 0 Fuel**;
- `advanceProcessing(dt, ...)` and `checkForRecipeUpdate()` (auto-pick furnace recipe from inputs);
- `getInput/Output Container()` for the endpoint adapters + GUI;
- component type via `CraftingPlugin.get().getProcessingBenchBlockComponentType()`.
- Covered by a devServer smoke test (contains the version-drift risk, D31).

### Tick loop (`MachineTickSystem`, replacing the stub WORK pass)

```
cost/sec  = SMELTER_DRAW                       // [TUNE] flat, e.g. 200 e/s
dtBudget  = min(realDt, stored / cost/sec)     // 1× cap for v1 (overclock hook here)
work      = bridge.advanceProcessing(heldBench, dtBudget)
energy.extractEnergyInternal(round(dtBudget * cost/sec), false)
```
- No energy → `dtBudget = 0` → bench freezes, progress retained (run-dry, §7.9). Same path as no-input.
- `advanceProcessing` sets `"Processing"` / `"ProcessCompleted"` / `"default"` block states → coil glow follows automatically.
- Anchor-only: filler cells are skipped (resolved to anchor first).

## Block asset (`CC_Smelter`, mirrored from `Bench_Furnace.json`)

```jsonc
"BlockType": {
  "Material": "Solid", "DrawType": "Model", "Opacity": "Transparent",
  "CustomModel": "Blocks/Machines/CC_Smelter.blockymodel",
  "CustomModelTexture": [{ "Texture": ".../CC_Smelter_Texture_Off.png", "Weight": 1 }],
  "VariantRotation": "NESW",
  "HitboxType": "CC_Smelter",                 // copy of the 2-box furnace hitbox
  "Supporting": [ /* explicit faces — Model blocks give none otherwise */ ],
  "BlockEntity": { "Components": { "CCMachineState": {} } },   // OUR component, not BenchBlock+ProcessingBenchBlock
  "State": { "Definitions": {
    "Processing":       { "Looping": true, "Light": {"Color":"#B72"}, "CustomModelTexture":[{"Texture":".../CC_Smelter_Texture.png"}] },
    "ProcessCompleted": { "Looping": true, "Light": {"Color":"#B72"}, "CustomModelTexture":[{"Texture":".../CC_Smelter_Texture.png"}] }
  }}                                          // default = Off texture, no light
}
```
- Held-bench config: 2 `Input` (`FilterValidIngredients`), `OutputSlotsCount: 4`, **no Fuel, no ExtraOutput** (the vanilla charcoal-per-fuel). `Id: "CC_Smelter"`.
- Recipes: vanilla **furnace** smelting set via `CraftingPlugin.getBenchRecipes(...)`, auto-picked from inputs (no recipe card).
- States must exist or the bench throws when `advanceProcessing` sets them.

## Ports & pipe I/O (faces from the model)

Window/control panel on **+Z (front, player face)**:

| Face | Model nub | Channel | Direction |
|---|---|---|---|
| **−X** west (left) | `Port_ItemIn` | ITEM | input |
| **+X** east (right) | `Port_ItemOut` | ITEM | output |
| **−Z** north (back) | `Port_Power` | POWER | input |
| +Z front, ±Y | — | — | closed |

- **FaceTags** (POWER + ITEM) advertise where a matching pipe *may* stub; they are uniform across all footprint cells (engine limitation). The real filtering is **`PortConfig` keyed by (footprint-cell-offset, face)**, resolved on the anchor. A pipe on a non-port face idles (no endpoint).
- ITEM input ports → bench `getInputContainer()`; ITEM output ports → `getOutputContainer()`; POWER port → `EnergyBuffer`. All reuse the existing endpoint/fair-split machinery.
- **Only genuinely new plumbing (§7.5):** `BlockModule.getBlockEntity` returns null on a filler, so endpoint collection **and** the wrench apply `anchor = pos − unpack(getFiller(pos))` before reading the anchor's `MachineBlockState`.

## GUI (`MachinePanelPage` + `CC_MachinePanel.ui`, extended)

Furnace-shaped page wired to the held bench containers, all via the existing live-refresh snapshot/delta system:
- **2 input + 4 output `ItemSlot`s** — **display-only** (D-S4): rendered from `getInput/OutputContainer()`, no click-to-move.
- **Smelt progress** bar/arrow from bench progress / recipe duration.
- **Energy gauge** from `EnergyBuffer.getFillRatio()` (reuse the label-gauge style).
- **Port readout** — the three configured faces.
- **Planned v1.1:** Eject button + On/Off switch (D-S4).

Read-only gauges are trivial (snapshot pattern already does this). Interactive controls (eject/switch) are the only non-trivial UI work and are deferred.

## Test plan

devServer smoke + manual in-world, in dependency order:
1. **Bridge** constructs/configures a no-fuel `ProcessingBenchBlock` and calls `advanceProcessing` with no player/window. (Contains the `builtin.*` coupling risk.)
2. **Placement** — places at anchor, claims furnace-shaped fillers, NESW rotation, obstruction-safe.
3. **Filler→anchor** — interact / wrench / pipe-stub from any cell resolves to the anchor's state.
4. **Smelt on power** — creative POWER feed → insert ore → coils light, progress runs, ingot in output; cut power → freeze + retain; restore → resume.
5. **Pipe I/O** — ITEM in on −X feeds input, ITEM out on +X drains output, POWER on −Z charges; wrong-channel pipe on a port face idles.
6. **GUI** — Interact-F opens; slots, progress, energy track live state; slots reject hand-insert.

Unit-testable headless: energy/progress math, `PortConfig` face resolution, filler→anchor arithmetic. The bench bridge needs the devServer (integration).

## Out of scope (this milestone)

Overclock (D33), signed recipe energy/exothermic credit (§7.4), bench tier upgrades, eject/on-off controls (v1.1), the other seven benches (§7.10), real generators (the `CreativeSource` flag stands in).
