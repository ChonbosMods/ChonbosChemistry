# CC Smelter UI redo — design + plan (HyProTech-informed)

Date: 2026-06-21. Supersedes the layout in `2026-06-20-cc-smelter-gui-design.md`.
Reference: `docs/research/2026-06-21-hyprotech-machinarium-analysis.md` (HyProTech AlloySmelter
analysis) + decompile at `models/references/_mod_studies/HyProTech-1.3.3/`.

## Goal

Replace the current single-column gray `CC_SmelterPanel.ui` (with clunky 10-cell `.Visible`-toggle
bars) with a polished two-column tech-machine panel modelled on HyProTech's Alloy Smelter, keeping
our identity and our pipe-driven design.

## Locked decisions

- **Architecture unchanged.** Keep `SmelterPanelPage extends InteractiveCustomUIPage` +
  `PanelRefreshService` live refresh (same pattern as HyProTech `AlloySmelterPage`).
- **Palette:** amber-on-slate — HyProTech's bordered-panel structure, our `#ffb000`/`#ffd54a`
  amber accents over a dark slate base. Identity stays ours.
- **Bars:** drop the segmented `#PG0..9`/`#PW0..9` cells; use vanilla `@ProgressBar` (confirmed
  `.Value` float 0..1 is runtime-settable via `UICommandBuilder.set("#Bar.Value", frac)`).
- **No recipe selector.** Our smelter is pipe-fed; we deliberately OMIT HyProTech's Recipe
  Selector / Load Recipe / Take Input/Output machinery. Slots stay read-only.
- **Upgrade panel: functional.** Wire a real Upgrade button that drives the vanilla bench tier
  upgrade (Phase 2; needs a devServer smoke test).
- **On-look HUD:** net-new, Phase 3.

## What our smelter actually exposes (constraints)

- Energy: `MachineBlockState.energy()` → `EnergyHandler.getStored()/getMaxStored()`.
- On/Off: `isEnabled()`/`setEnabled()`.
- Bench: `heldBench()` (`ProcessingBenchBlock`) + `heldBenchBlock()` (`BenchBlock`).
- Tier: `heldBenchBlock().getTierLevel()` (the VANILLA bench tier — we have no custom tier).
- Progress: `VanillaBenchBridge.progressFraction(bench, tier)` (0..1), `isActive(bench)`.
- Slots: `VanillaBenchBridge.input(bench)` / `output(bench)` containers (read-only display).
- Upgrade requirements live in the BlockType's `Bench.TierLevels[].UpgradeRequirement` config
  (see OreCrusher/HyProTech analysis) — read these for the upgrade panel.

We can populate: Tier, Energy (stored/max + bar), Progress %, Status. We CANNOT honestly show
"Consumption J/s" or "Yield Nx" (no such mechanic) — omit them.

## Target layout (`CC_SmelterPanel.ui`)

```
$C.@PageOverlay {
  $C.@DecoratedContainer { Width ~720, Height ~480
    #Title { @Text = "CC Smelter" }
    #Content { LayoutMode: Left
      #LeftColumn (≈ 420)              // the I/O column
        #ProcessPanel  "Processing"
          ItemGrid #InputGrid (read-only)
          ProgressBar #ProgressBar     // .Value = smelt fraction
          ItemGrid #OutputGrid (read-only)
          Label #ProgressText          // "Smelting NN%" / "Idle" / "Off"
      #RightColumn (≈ 260)
        #StatusPanel  "Status"
          Label #TierText              // "Tier: N"
          Label #EnergyText            // "Energy: stored / max"
          ProgressBar #PowerBar        // .Value = power fraction
          Label #StatusText            // On / Idle / Smelting
          TextButton #PowerToggle      // ON / OFF
          TextButton #EjectBtn         // Eject
        #UpgradePanel "Upgrade"        // Phase 2
          Label #NextTierText
          (req rows #UpgReqSlotN/#UpgReqNameN/#UpgReqQtyN)
          TextButton #UpgradeBtn
    }
  }
}
$C.@BackButton {}
```

New token file `Common/UI/Custom/CC_Master.ui` (amber-on-slate):
`@PanelBorderColor=#3a2f1e(0.85) @PanelFillColor=#15110a(0.4) @PanelDividerColor=#4a3b22(0.75)`
`@Amber=#ffb000 @AmberLite=#ffd54a @ValueText=#e8dcc0 @LabelText=#b9a888` `@LeftW=420 @RightW=260 @Gap=10`.
(Final hex tuned during impl.)

## Phase 1 — layout + theming + real bars + status rows  ← implement now

1. Add `src/main/resources/Common/UI/Custom/CC_Master.ui` (tokens above).
2. Rewrite `Common/UI/Custom/Pages/CC_SmelterPanel.ui` to the two-column layout, importing
   `$M = "../CC_Master.ui"`. Use `@ProgressBar` for `#ProgressBar` and `#PowerBar`. Read-only
   `ItemGrid`s as today (`AreItemsDraggable:false`). Leave `#UpgradePanel` present but
   `Visible:false` (filled in Phase 2).
3. `SmelterPanelPage.applyState`: replace `setBarSegments(...)` with
   `cmd.set("#ProgressBar.Value", frac)` and `cmd.set("#PowerBar.Value", powerFrac)`; set
   `#TierText`, `#EnergyText`, `#StatusText`, `#ProgressText`. Remove `BAR_SEGMENTS`/`setBarSegments`.
   Keep the two event bindings (`#PowerToggle`, `#EjectBtn`) and the refresh/threading model.
4. Tests: extract status-line formatting into a small pure helper (e.g.
   `SmelterPanelText.status(enabled, active, frac)` → "Off"/"Idle"/"Smelting NN%") and unit-test
   it (mirrors `MachineBlockStateTest` style). Bar `.Value` clamping helper unit-tested too.
5. Verify: `./gradlew test`; then a devServer smoke (user toggles server) — confirm panel renders,
   bars move, toggle/eject still work. Check `devserver/logs` per the devServer-logs memory.

## Phase 2 — functional upgrade panel

- Read `Bench.TierLevels` for current+next tier requirements (via a new `VanillaBenchBridge`
  accessor so the `builtin.crafting` coupling stays in one file). Populate `#UpgradePanel` rows.
- `#UpgradeBtn` → new `Action.UPGRADE` in `handleDataEvent`; drive the vanilla bench upgrade
  through `VanillaBenchBridge`. Gate the button on requirements met. devServer smoke test required.

## Phase 3 — on-look HUD

- New `EntityTickingSystem<EntityStore>` (or extend `PanelRefreshService`) using
  `TargetUtil.getTargetBlock(ref, ~6.0)`; if the target is a CC machine, push a `CustomUIHud`
  (`CC_SmelterHud.ui`) or `EventTitleUtil` line with Tier / Energy / Status. Diff + throttle like
  the panel. Standalone; no panel dependency.

## Risks / notes

- `@ProgressBar.Value` runtime-set is expected to work (first-class element property, unlike
  `Anchor.Width`); confirm in the Phase 1 devServer smoke.
- Machine model/texture assets unaffected; this is GUI-only. Re-copy rule (machine-model-asset-copy
  memory) does not apply to `.ui` files under resources.
- License: re-author HyProTech patterns; do not ship their assets/source.
```
