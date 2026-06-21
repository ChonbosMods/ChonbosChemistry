# Generalize bench-machine substrate + build the Reclaimer (machine #2)

Date: 2026-06-21. Branch: `feature/cc-reclaimer` (off `feature/cc-smelter`).
Context: CC Smelter MVP is done (machine #1 of the 8 vanilla-bench auto-machines, D47/§7.10). The
substrate (`MachineBlockState`, `MachineTickSystem`, `VanillaBenchBridge`) is already machine-agnostic;
the smelter-specific bits are the GUI page, ports, and the block JSON. Reclaimer wraps the vanilla
**Salvage** bench (`Bench.Type: Processing`, `Bench.Id: "Salvagebench"`, 1 input / 4 output) — confirmed
a Processing bench, so it reuses the substrate with no engine changes.

## Decisions
- Machine #2 = **Reclaimer** (Salvage). Approach = **generalize first**, then build #2.
- GUI: ONE shared panel `.ui` + ONE page class, parameterized per machine (title + active verb), so
  machines #2–#8 are just a registration line + assets + recipes. Follows `cc-machine-gui-template`.

## Phase A — generalize the bench-machine GUI (this commit)
1. `SmelterPanelPage` → **`BenchMachinePanelPage`** (machine-agnostic). Add ctor params `String title`,
   `String verb`. `build()` appends the shared `.ui` and sets `#PanelTitle.Text = title`. All bench/
   energy/eject/toggle logic is unchanged (already generic).
2. `CC_SmelterPanel.ui` → **`CC_BenchMachinePanel.ui`**. Make the title a settable `Label #PanelTitle`
   (Style `$C.@TitleStyle`) instead of the static `$C.@Title { @Text="CC Smelter" }`, so one file serves
   all machines. Body unchanged (vanilla-measured layout, dark-gray theme, colored bars).
3. `SmelterPanelText` → **`BenchMachinePanelText`**; `status(enabled, active, frac, verb)` takes the verb
   (smelter "Smelting", reclaimer "Salvaging"). TDD: update the test first.
4. Registration in `ChonbosChemistry`: keep page id `"CC_SmelterPanel"` →
   `new BenchMachinePanelPage(p, b, "Smelter", "Smelting")` (CC_Smelter.json unchanged).
5. Keep `SmelterPorts`/`SmelterEnergy` names for now (generic enough); revisit naming when a 3rd machine
   lands. Verify smelter still builds + tests green (no behavior change).

## Phase B — build the Reclaimer (next commit)
1. **Block/item JSON** `Server/Item/Items/ChonbosMods/CC_Reclaimer.json`: mirror `CC_Smelter.json` but
   `Bench.Type: Processing`, `Bench.Id: "Salvagebench"` (inherits all vanilla Salvage recipes),
   1 input slot, OutputSlotsCount 4, no fuel; `Interactions.Use → OpenCustomUI Page.Id "CC_ReclaimerPanel"`;
   Processing/ProcessCompleted states.
2. **Assets** (retexture-first per asset-list §6 [DECIDE]): reuse the vanilla `Salvage.blockymodel`
   retextured to CC identity, or ship as-is for the functional MVP and retexture in an art pass. Hitbox +
   3 state textures as needed.
3. **Ports**: `ReclaimerPorts` (or generic default) — item-in / item-out / power-in on a sensible
   footprint (start 1×1 anchor-only, the degenerate multi-cell case; expand if the model needs it).
4. **Registration**: page id `"CC_ReclaimerPanel"` → `new BenchMachinePanelPage(p,b,"Reclaimer","Salvaging")`;
   placement path attaches `MachineBlockState` + a `ProcessingBenchBlock` initialized from the block type
   (same path as the smelter).
5. **Verify**: `./gradlew test`; devServer smoke (user runs) — place, pipe-feed a salvageable item, confirm
   it processes, power-gates, GUI shows status/bars, eject works.

## Notes
- Recipes: NO new recipe files needed — wrapping `Bench.Id "Salvagebench"` reuses vanilla Salvage recipes.
  CC-specific salvage recipes can be added later as `BenchRequirement Id: "Salvagebench"` assets.
- Energy draw uses the shared placeholder (`SmelterEnergy.SMELTER_DRAW`) until per-machine costs land.
