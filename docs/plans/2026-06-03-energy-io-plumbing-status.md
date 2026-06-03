# Energy + I/O Plumbing — Status & Handoff (2026-06-03)

> **For the next Claude:** This is a mid-flight handoff for the "Energy + I/O plumbing" slice of ChonbosChemistry. Read this top-to-bottom, then continue at **§7 Next steps** (Tasks B5 + B6). The slice is the shared block infrastructure (energy standard, phased resource buffers, ports, adjacency/cable transport, ticking, block GUI) that all future machines plug into.

## 1. TL;DR

The slice is **~80% done and proven working in-game.** Phase A (pure logic) + B1–B4 (Hytale integration: ECS components, tick system, rig blocks, block GUI) are complete, committed, and verified — transport (energy + fluid) moves between adjacent blocks live, and right-clicking a block opens a working gauge panel. **Remaining: B5 (tank carry-with-contents) and B6 (end-to-end smoke + remove temp debug logging).**

## 2. Where the code lives

- **Branch:** `feat/solid-substance-assets` in the main checkout `~/Development/Hytale/ChonbosChemistry` (the build worktree was merged out and removed). This branch ALSO contains a parallel, unrelated effort — the user's **solid-substance jar asset generator** (`impl/assetgen`, `impl/texture`, commit `a074f74`). Don't disturb it; it's the source of real block/item models that will later replace our placeholder visuals.
- **`main` is untouched** (`f0383b0`). Nothing has been pushed (per user rules: never push unless told).
- **Design + plan docs (read these):**
  - `docs/machines-and-power-design.md` — the whole gameplay mod (7 machines, power, neutron, heat) folded into ChonbosChemistry.
  - `docs/plans/2026-06-02-energy-io-plumbing-design.md` — the validated slice design.
  - `docs/plans/2026-06-02-energy-io-plumbing-plan.md` — the TDD implementation plan (Phase A tasks A1–A6, Phase B tasks B1–B6). **B5/B6 specs are here.**

## 3. What's done (with verification level)

Commits (oldest→newest): Phase A `a2a2555..040312c`; B1 `6ec43b4`; B2 `88a64fd`; B3 `df4f518`; merge `a5431bb`; log-cleanup `92f0a0b`; B4 `fefabc0`; UI fix `3975cd2`.

| Part | What | Verified |
|---|---|---|
| **Phase A** (`api.energy`, `api.io`, `impl.block`) | `EnergyHandler` contract; `PortChannel`/`PortDirection`; `EnergyBuffer`, `ResourceBuffer` (type-locked), `Port`/`PortConfig`, `WorkState` (dt-accumulated), `TransferNode`/`NeighborView`/`TransportEngine` (lossless push) | ~50 headless JUnit tests, all green (`./gradlew test`) |
| **B1** | `MachineBlockState` + `TankBlockState` ECS `Component<ChunkStore>` wrapping the Phase A beans; codec-round-trip `clone()` (per-block independence); registered in `setup()` | Unit tests + devServer boot-clean |
| **B2** | `MachineTickSystem extends EntityTickingSystem<ChunkStore>`: per tick → creative-refill → transport (push energy+resources over a `NeighborView` built from `BlockAccessor`+`BlockModule.getBlockEntity`) → work-pass stub | Boot-clean + **in-game: values change on adjacent blocks** |
| **B3** | 6 rig blocks at `src/main/resources/Server/Item/Items/ChonbosMods/CC_*.json` (PowerCell, PowerCable, EnergySink, FluidSource, FluidSink, FluidTank); component attached via `BlockType.BlockEntity.Components` | Boot-clean (all load) + **in-game: placeable, transport works** |
| **B4** | Block GUI: right-click → `MachinePanelPage` (snapshot) showing energy + per-channel buffer text readouts; `.ui` at `Common/UI/Custom/Pages/CC_MachinePanel.ui` | Boot-clean + **in-game: panel opens and renders** |

## 4. Key architecture & decisions (so you don't relitigate them)

- **api/impl split is the governing rule.** `api` = contracts only, never imports `impl`. Energy contract is `api.energy.EnergyHandler`; transport channel + direction enums are `api.io.PortChannel {ITEM,FLUID,GAS,POWER}` / `api.io.PortDirection`. `PortChannel` is deliberately NOT the substance `Phase` enum — power is a transport channel, not a phase.
- **Transport** is push-based, lossless, simulate-then-commit. `throughput(channel)` is **per output port** per push. Cable hop/buffer/stop-pull is **emergent** from `TransferNode`+`EnergyBuffer` (no `CableBuffer` class). `TransferNode`/`NeighborView`/`TransportEngine` live in `impl.block` (not api) for now.
- **Block entities** = ECS components on `ChunkStore`, attached via the block asset's `BlockType.BlockEntity.Components` map (key = registered component id `"MachineBlockState"`/`"TankBlockState"`, value = codec data). Registered via `getChunkStoreRegistry().registerComponent(Class,"Id",CODEC)`; ComponentTypes exposed on the plugin (`ChonbosChemistry.getInstance().machineComponentType()/tankComponentType()`).
- **Ticking** = our own `EntityTickingSystem<ChunkStore>` (NOT the deprecated `BlockState`/`TickableBlockState` path the furnace uses), adopting furnace dt-accumulation semantics. Block world-position resolved via `BlockStateInfo` + **`BlockChunk`** (`getX()<<5 | ChunkUtil.xFromBlockInColumn(idx)`), copied verbatim from the engine's `ItemContainerStateSpatialSystem`.
- **Block→GUI**: `OpenCustomUIInteraction.registerBlockEntityCustomPage(this, MachinePanelPage.class, "CC_MachinePanel", MachinePanelPage::new)` in `setup()`; block JSON `Interactions.Use` = inline `{"Interactions":[{"Type":"OpenCustomUI","Page":{"Id":"CC_MachinePanel"}}]}` + `Flags.IsUsable:true`.
- **`.ui` templates MUST follow the Natural20 pattern** (`~/Development/Hytale/Natural20/src/main/resources/Common/UI/Custom/Pages/*.ui`): only `$C.@PageOverlay {}` + `$C.@Container { #Content { …Label rows… } }`. The base game's `Common/UI/Custom/Common.ui` (in `Assets.zip`) defines the macros, but Nat20 only uses those two — richer macros (`@DecoratedContainer`/`@Title`/`@ProgressBar`/`@BackButton`) exist but are easy to mis-nest and broke the first attempt. Gauges are text labels (no `@ProgressBar` in Nat20 to copy).
- **No translation keys.** `server.lang` is generated + gitignored by the user's asset generator, so UI/text uses `Message.raw(...)` literals.

## 5. Known limitations / gotchas (carry forward)

- **TEMP debug logging** in `MachineTickSystem` (marked `// TEMP B6-remove`): change-only per-block log lines (`[CC] (x,y,z) energy=…(+Δ)`). **B6 must remove these** (the `logAccumulator`, `lastReadout` map, `logActivity`, `appendChannel`, and the call site).
- **Face geometry is approximate.** `MachineTickSystem.OFFSETS` maps faceIndex 0..5 → ±X/±Y/±Z but is NOT aligned to Hytale's real block face indices. The rig sidesteps this by putting ports on ALL 6 faces. Real directional machines will need this reconciled.
- **Work pass is a no-op stub** (no recipe system yet — intentional; don't invent one).
- **GUI is snapshot-only** (reads once on open). Live refresh path is noted in `MachinePanelPage#build`. The change-only console log covers the live view for now.
- **Placeholder visuals**: rig blocks reuse `BlockTextures/Cloth_White.png`. Real models come from the solid-substance asset work (see the model-priority list the user has: translucent tank w/ fill level, emissive energy cell/lamp, connecting cables tinted per channel are the highest-value).
- **Tanks are passive** (not ticked; only `MachineBlockState` is queried). They participate as transport neighbors.
- **Multi-sink transport fairness** (round-robin) deferred; buffers absorb it.

## 6. How to build / test / run

- Compile: `./gradlew compileJava`. Headless tests: `./gradlew test` (use `--rerun-tasks` to force real execution; gradle caches aggressively).
- Run server: `./gradlew devServer` **from the main checkout** (the user runs it; the worktree could boot but the user tests in-game from here). Hot-reload is disabled under U5 → full restart per change.
- **Killing devServer needs `dangerouslyDisableSandbox`** (pkill is sandbox-blocked); the leftover Gradle daemon is harmless. Foreground `sleep` is blocked.
- Smoke test (in-game): place `CC_PowerCell` adjacent to `CC_EnergySink` → sink energy climbs (console + right-click panel); `CC_FluidSource` adjacent to `CC_FluidSink` → fluid climbs; right-click any rig block → gauge panel.
- Decompiled server (API ground truth) for spikes: `~/Development/Hytale/patcher/hytale-server/`. Built-in assets: `~/.var/app/com.hypixel.HytaleLauncher/data/Hytale/install/release/package/game/latest/Assets.zip`.

## 7. Next steps (the plan)

Continue with the **subagent-driven-development** process used so far (fresh implementer per task + spec review + quality review; spike API signatures against the decompiled source before implementing; verify boot-clean via devServer; the user does in-game smoke). Detailed task specs are in `docs/plans/2026-06-02-energy-io-plumbing-plan.md`.

### Task B5 — Tank carry-with-contents
Goal: breaking a tank (or machine) drops an item carrying its stored contents; placing it rehydrates the block entity. Design §5.2 ("tanks relocate with contents preserved" — the manual transport method).
- **Spike first:** how block break/place events fire for a plugin (`@hytale-events`), and the `ItemStack` metadata API (`withMetadata` / `@hytale-ui-items`) for serializing a `ResourceBuffer`/component state into the dropped item and reading it back on placement.
- Implement: on break, serialize the block's `ResourceBuffer` (and relevant component state) into the dropped item's metadata; on place, rehydrate the new block entity's component from it.
- Verify: compile + devServer; in-game = fill a tank, break it, replace it, contents survive.

### Task B6 — End-to-end smoke + cleanup
- Remove ALL `// TEMP B6-remove` debug logging from `MachineTickSystem` (see §5).
- Full devServer end-to-end smoke of the rig (power flow, cable buffering, fluid fill + type-lock, tank carry, stall/run-dry).
- Confirm `./gradlew test` + `compileJava` green. Final commit.

### After the slice
- `superpowers:finishing-a-development-branch` to decide how `feat/solid-substance-assets` (slice + asset generator) integrates toward `main`.
- Then the real machines (Synthesizer/Decomposer/Converter first — chemistry layer, auto-derived recipes from the 153 compounds) build on this skeleton; nuclear machines later.

## 8. Process notes

- Built via `superpowers` workflow: brainstorming → writing-plans → using-git-worktrees → subagent-driven-development (two-stage review per task) → finishing-a-development-branch. Reviews caught real gaps (the `PortChannel` correction, the empty-buffer codec tests, per-port throughput semantics, and the `.ui` macro misuse).
- Persistent project memory: `~/.claude/projects/-home-keroppi-Development-Hytale/memory/project_chonbos_chemistry.md` (auto-loaded each session; mirrors this status).
