# CC Smelter GUI: furnace-style read-only panel with On/Off + Eject

*Status: design validated 2026-06-20, ready to implement. Reshapes the smelter's interaction UI into a furnace-style **read-only** view (no hand drag-drop; pipes do the loading) plus two controls (On/Off toggle, Eject) and live power-buffer + progress displays. Parent design: [docs/design.md](../design.md) §7.5 (machines). Builds on the now-working item I/O + held-bench init (see [2026-06-19-cc-smelter-implementation-plan.md](2026-06-19-cc-smelter-implementation-plan.md) Tasks 12/14 + [2026-06-20-cc-smelter-footprint-facetag-research.md](2026-06-20-cc-smelter-footprint-facetag-research.md)).*

## Decompile finding: the vanilla furnace GUI is client-side, not a reusable template

Traced through the decompiled source (`patcher/work/decompile`):

- `OpenProcessingBenchInteraction` opens a `ProcessingBenchWindow extends BenchWindow extends BlockWindow` with **`WindowType.Processing`**.
- The window builds a **`windowData` JSON blob** (`active`, `progress`, `fuelTime`/`maxFuel`, `input`/`output` slot configs, `inventoryHints`); the **client renders the native furnace UI** from it. No `.ui` template ships for it (it's compiled into the client; none found in `Assets.zip`).
- It is an `ItemContainerWindow` → the client gives it **built-in drag-drop** item movement (server-handled). The on/off toggle exists natively (`handleAction` → `SetActiveAction` → `benchState.setActive`).

**Conclusion:** the native window can't meet our requirements — we can't strip its drag-drop, and it has no power buffer or eject. So we **replicate the furnace's look as a custom server UI page** (the path the existing `CC_MachinePanel` already uses: `CustomUIPage` + `.ui` template + live refresh).

## Decisions

- **Read-only slots.** The player cannot put/pull items by hand (`ItemIcon`/`ItemSlot` are read-only; grids use `AreItemsDraggable: false`). Pipes feed/drain the machine.
- **Two controls:** an **On/Off toggle** and an **Eject** button.
- **Eject scope:** drain **both** input (ingredients) and output (results) → player inventory; overflow dropped at the player's feet.
- **Default state:** **On** (processes as soon as it has power + input once placed).
- **Background:** medium gray.
- **Circuit-ready:** the On/Off `enabled` flag IS the circuit system's "run/halt" control line (see Section 1).
- **Dedicated page:** `CC_MachinePanel` is the SHARED info panel for every CC block (pipes/tanks/sources/smelter); it stays untouched. The smelter gets a NEW `CC_SmelterPanel` page; only `CC_Smelter.json` repoints to it.

## Section 1 — machine-side behavior & data

**`MachineBlockState.enabled`** — new boolean, codec-persisted, **default `true`** (legacy data without the key → `true`).
- `MachineTickSystem.driveBench`: after lazily creating/holding the bench, if `!enabled` → skip the energy-gate + `advance`. The bench keeps its loaded input/output and the buffer keeps its power; it just doesn't process. Power *intake* from the network is unaffected — "off" stops only *consumption/processing*.
- Behavior: ON + power + input → smelts; ON + missing power or input → idles (existing behavior); OFF → frozen.

**On/Off as the circuit run/halt line.** The circuit design's *Machine I/O bridge* ([2026-06-08-tech-circuit-system-design.md](2026-06-08-tech-circuit-system-design.md) §Output) "writes digital control in (run/halt, …)" — that maps directly to `setEnabled(boolean)`. So the toggle logic is NOT UI-bound: it lives behind `MachineBlockState.isEnabled()/setEnabled()`, and the UI button is just one caller. The future I/O bridge writes the same flag (it may later layer its own override semantics on top; the flag is the shared seam). `driveBench` reads only this one flag.

**Eject action** (player-only; the circuit bridge does not eject): drain both `VanillaBenchBridge.input(bench)` and `output(bench)` slot-by-slot, inserting each stack into the interacting player's inventory (`getCombinedHotbarFirst`); whatever doesn't fit is dropped as a ground item at the player's position (reusing the `ItemComponent.generateItemDrops` path from `ItemTransferSystem.spawnDrop`). Metadata preserved.

## Section 2 — the smelter page & layout

**New `Common/UI/Custom/Pages/CC_SmelterPanel.ui`** + **`SmelterPanelPage extends CustomUIPage`** (reuses the `PanelRefreshService` live-refresh infra). `CC_Smelter.json`'s `OpenCustomUI` switches `CC_MachinePanel` → `CC_SmelterPanel`.

Layout (furnace-mirroring, medium-gray container):
- **Header:** title `CC Smelter` + **On/Off `Button`** `#PowerToggle` (label reflects state).
- **Process row:** **input `ItemGrid`** `#InputGrid` (2 slots, from JSON `Input`) → **progress `ProgressBar`** `#Progress` → **output `ItemGrid`** `#OutputGrid` (4 slots, `OutputSlotsCount`). Both grids `AreItemsDraggable: false`, `DisplayItemQuantity: true` (set from Java).
- **Power gauge:** `ProgressBar` `#PowerBar` + `Label` `#PowerText` (`stored / capacity`).
- **Footer:** **Eject `Button`** `#EjectBtn`.

**Read-only item rendering:** grids are populated from Java each refresh — iterate the held bench's input/output `ItemContainer` slots, build `ItemGridSlot`s (`setItem(ItemStack)` carries id + count + metadata), `cmd.set("#InputGrid.Slots", …)`. `InventorySectionId` is NOT used (it doesn't auto-populate in a `CustomUIPage`); no drag since `AreItemsDraggable:false`. `ItemGridStyle(...)` wraps `SlotSize`/`SlotIconSize`/`SlotSpacing`/`SlotBackground`. A `Slot.png` is copied into the plugin's `Common/UI/Custom/Pages/` (plugin templates can't reference vanilla textures by relative path; `@2x` auto-resolves).

## Section 3 — page class (build, refresh, events)

`SmelterPanelPage extends CustomUIPage implements PanelRefreshService.LivePanel`, constructor `(PlayerRef, Ref<ChunkStore> blockRef)` (mirrors `MachinePanelPage`).

- **`build(ref, cmd, events, store)`:** `cmd.append("Pages/CC_SmelterPanel.ui")`; push initial state (title, grid `Slots`, progress, power bar + text, toggle label); `events.addEventBinding(Clicking, "#PowerToggle")` and `(Clicking, "#EjectBtn")`; register with the refresh service.
- **`refresh()` (live):** recompute a snapshot from the held bench + `MachineBlockState` (grid contents, `progress` = inputProgress/recipeTime, `enabled`, energy `stored/capacity`) and `sendUpdate` a throttled delta of `set` commands — same pattern as the existing panel.
- **`handleDataEvent(ref, store, rawData)`:** dispatch by selector — `#PowerToggle` → `setEnabled(!enabled)` via the machine-control helper + mark chunk needs-save; `#EjectBtn` → run eject. Both then `sendUpdate`.
- **Threading:** `build`/`refresh` run on the WorldThread (like `MachinePanelPage`). `handleDataEvent` may fire off the WorldThread (cf. the off-thread command-handler gotcha), so the toggle/eject ECS mutations are wrapped in `world.execute(Runnable)`.

**Registration:** register `SmelterPanelPage` in `ChonbosChemistry` alongside `MachinePanelPage`. The exact `CustomUIEventBindingType` for a button click and the `rawData` selector parsing are confirmed against `CustomUIEventBindingType` / existing pages at implementation time.

## Section 4 — test plan

**Unit (pure, TDD):**
- `MachineBlockState.enabled` codec round-trip; default `true`; legacy JSON without the key → `true`.
- `driveBench` gate as a pure predicate (`shouldProcess = enabled && affordable > 0`) tested directly.
- Eject helper (pure seam): given fake input/output containers + a fake inventory sink, it drains all stacks and returns the overflow list. Cases: all-fits → no overflow; partial → correct overflow; empty bench → no-op.

**Integration / devServer (manual):**
- Panel opens: furnace layout, medium-gray background, read-only slots (drag does nothing).
- Live: piped-in ore shows in input grid; progress advances; bars appear in output grid; power gauge tracks the buffer.
- On/Off: toggles; OFF freezes processing (input/power retained); ON resumes.
- Eject: empties both grids → inventory; overflow drops at the player's feet.

## Open questions / follow-ups

- **Panel endpoint-count fix (deferred from item I/O):** `MachinePanelPage` still uses the machine-unaware 3-arg `ItemEndpoints.collect` (cosmetic under-count). Not relevant to the new smelter page; address if/when the generic panel is revisited.
- **`CustomUIEventBindingType` for buttons:** confirm the exact click binding type + `rawData` payload shape at implementation (the item skill documents `SlotClicking` for grids; buttons likely `Clicking`).
- **Circuit I/O bridge:** not built here — only the `enabled` seam is made circuit-ready.
- **`ProgressBar` availability:** the existing panel notes "Nat20 uses no @ProgressBar" (text gauges). If `@ProgressBar` proves unavailable in custom pages, fall back to a textured fill or a text readout for progress/power.
