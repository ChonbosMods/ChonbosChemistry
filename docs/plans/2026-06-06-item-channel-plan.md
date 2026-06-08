# ITEM Channel Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or subagent-driven-development) task-by-task.
> Design doc (read first): `docs/plans/2026-06-06-item-channel-design.md`. Branch: `feat/item-channel`, stacked on `feat/pipe-flow-states` (user decision: both features verify in one in-game pass).

**Goal:** Discrete item transport over `CC_ItemPipe` against vanilla containers: PULL extraction, nearest-first routing, re-route→return→pop-out ladder, filter stub, chest arm visuals, wrench + panel integration.

**Conventions:** TDD per superpowers:test-driven-development; full suite green before every commit (baseline 398); `:` not em-dash; no Co-Authored-By; pure logic first, engine glue last; every pure class follows the net-layer style (final, private ctor or record, javadoc citing the design doc).

---

### Task 1: `ItemFilter` + `FilterLookup` stub
**Files:** create `impl/block/net/item/ItemFilter.java`, `impl/block/net/item/FilterLookup.java`; test `ItemFilterTest`.
`ItemFilter.admits(stackBson/desc, long pipeKey, int viaFace)`: v1 ships `ItemFilter.ALLOW_ALL` and `FilterLookup.NONE` (returns ALLOW_ALL for every pipe). Tiny TDD pass: the seam exists so Tasks 4/6 consume it and the future intersection-filter feature only swaps the lookup. Note: avoid engine `ItemStack` in the pure signature: define a minimal `ItemKey` (id string + count) the pure layer uses; the glue converts. Commit: `feat(item): ItemFilter/FilterLookup seam (allow-all stub)`.

### Task 2: `ContainerLookup` seam + `ItemEndpoints`
**Files:** create `impl/block/net/item/ContainerLookup.java` (pure interface: `ContainerView at(x,y,z)` where `ContainerView` exposes `canAccept(ItemKey, int amount) -> int accepted (simulate)`, `firstExtractable(ItemFilter, int cap) -> ItemKey or null`, `extract(slotHint/ItemKey, int, simulate) -> int`, `insert(ItemKey, int, simulate) -> int`); create `ItemEndpoints.java` (OFFSETS walk over member pipes per the design: face state alone qualifies: NORMAL/PUSH = destination, PULL = source, NONE = skip; dedup neighbour positions, NONE dedup-neutral like NetworkEndpoints). Tests with a fake lookup: qualification per face state, dedup, NONE-neutrality. Commit: `feat(item): ContainerLookup seam + ItemEndpoints face-state qualification`.

### Task 3: `TravelingStack` + `PipeNode.InTransit` codec key
**Files:** modify `PipeNode.java`; create `impl/block/net/item/TravelingStack.java`; extend `PipeNodeTest`.
`TravelingStack` record: item id, count, raw metadata BsonDocument (nullable, carried opaquely so engine ItemStacks round-trip), path (packed-key array), segmentIndex, progressTicks, originKey, destKey + CODEC. `PipeNode` gains OPTIONAL `"InTransit"` list key (absent = none: byte-identical old saves: same pattern as `FlowStates`), accessors add/remove/list (defensive copies). Tests: round-trip incl. metadata doc, absent-key default, clone independence. Commit: `feat(item): TravelingStack + PipeNode InTransit persistence (absent = none)`.

### Task 4: `ItemPathfinder`
**Files:** create `impl/block/net/item/ItemPathfinder.java`; test.
BFS shortest path from an entry pipe over member pipes; edges gated by `PipeConnectivity.connects(a, face, b)` AND `FilterLookup` (a junction whose filter rejects the ItemKey is impassable). Candidate destinations supplied by `ItemEndpoints`; return nearest-first order (path length, tie-break deterministic by packed key). Also `pathFrom(currentSegment, ...)` for mid-transit re-route and `pathBack(origin)` for the return leg. Tests: shortest wins, NONE blocks, filter blocks a junction (stack-specific), tie determinism, no-destination empty, mid-path re-route, return-leg path. Commit: `feat(item): nearest-first filter-gated ItemPathfinder`.

### Task 5: re-route ladder + advance logic (pure)
**Files:** create `impl/block/net/item/ItemTransit.java`; test.
Pure per-tick step over one TravelingStack given seams: `advance(stack, speedTicks)` (progress/segment moves), arrival (`insert` simulate-commit; partial fit → remainder re-routes), the ladder: dest invalid → `ItemPathfinder` re-route from current → none → return toward origin → origin gone/full → POP_OUT decision (the glue spawns the drop). Output = a small decision enum + updated stack, no world access. Tests: every ladder rung, partial-fit remainder, return-leg arrival re-inserts into origin, pop-out only when origin truly gone/full. Commit: `feat(item): ItemTransit advance + re-route ladder (re-route, return, pop out)`.

### Task 6: extraction eligibility (pure)
**Files:** create `impl/block/net/item/ItemExtraction.java`; test.
Per PULL endpoint per pull-interval: `firstExtractable` under filter → confirm a destination admits+accepts (simulate via pathfinder + ContainerView) → extract up to cap `[TUNE 16]` → produce TravelingStack with path. No destination → NO extraction. Network saturation (in-transit count >= member count `[TUNE]`) → no extraction. Tests: no-dest no-pull, filter consulted, cap, saturation, happy path produces correct path/origin/dest. Commit: `feat(item): PULL extraction eligibility (no destination, no extraction)`.

### Task 7: ENGINE SPIKE + `ItemTransferSystem` glue
First spike (no production code until verified): decompile the 0.5.3 container access (§16.6 names came from HyProTech: confirm `MachineItemAccess`/`ItemContainerBlockState`/`ItemContainer.moveItemStackFromSlot` exist or find the real path; confirm engine `ItemStack` id+count+metadata round-trip). Append findings to the design doc; commit `docs: item-channel spike findings`.
Then: create `impl/block/net/item/WorldContainerLookup.java` (engine impl of the seam) + `ItemTransferSystem` (per-network dedup; phase 1 `ItemTransit` over every in-transit stack incl. ground-drop on POP_OUT via the `CarryBreakEventSystem.spawnDrop` pattern; phase 2 `ItemExtraction` per pull interval; `markNeedsSaving` after mutations). Wire in `ChonbosChemistry`; `NetworkTransfer.distribute` callers skip ITEM-channel networks (one guard in `NetworkTickSystem` + comment). Engine glue: no unit tests; in-game verified. Commit: `feat(item): ItemTransferSystem: discrete transport over vanilla containers`.

### Task 8: assets: `CC_ItemPipe` + template regen
Regen `CC_ItemConnectedBlockTemplate.json` from the POWER template: `CC_PowerFace`→`CC_ItemFace`, DELETE all `_on`-twin shapes, keep/add the 6 indicator shapes (pattern-less); create `CC_ItemMachineTemplate.json`; author `CC_ItemPipe.json` from `CC_GasPipe.json`: `GasTube_`→`ItemPipe_`, `gastube_off`→`itempipe_off`, DELETE all `_On` states + the bare `On` (32 states total: 26 + 6 indicators), channel `item`, template refs; delete `src/main/resources/Common/Blocks/ChonbosMods/Pipes/itempipe_on.png`. Validate: JSON parses, every model path exists, NO `_On` keys remain, pattern map keys match states. Commit: `feat(assets): CC_ItemPipe: 32 states, single texture, item face tags`.

### Task 9: visuals: container arms + no-energize
**Files:** modify `PipeVisualStates.java` (+test), `NetworkTickSystem.java`.
`effectiveMask`/`physicalMask` gain the `ContainerLookup` seam: ITEM-channel faces count a container neighbour (effective: face-state gated incl. the classify-overlap rules; physical: container present at all): effective>physical now drives ADDING arms via the same swap. Driver passes `energized=false` for ITEM networks (no `_On` states exist) and PipeShapes indicator path for PULL/PUSH ends toward containers. Tests: container in both masks under NORMAL, PULL-only effective rules, NONE drops, non-ITEM channels ignore containers, arm-ADD divergence resolves to the bigger shape. Commit: `feat(net): chest arms render: container-aware visual masks (effective > physical adds arms)`.

### Task 10: wrench-on-chest + panel item variant
**Files:** modify `WrenchInteraction.java` (container check in target resolution: chest = MACHINE-style 4-cycle), `PanelSnapshot.java` (+test), `MachinePanelPage.java`.
Panel: ITEM networks render `In transit: N stacks • Pipes: M` + destinations row (counts threaded from the network/in-transit data available to the page: keep the snapshot pure: pass counts in). Tests: item-network snapshot rows + faces row coexistence; non-item unchanged. Commit: `feat(ui): item network panel (in-transit/destinations) + wrench cycles chest faces`.

### Task 11: wipe-snapshot extension for in-transit stacks
**Files:** modify `PipeNodeSnapshots.java`, `PipeSnapshotScan.java`; extend `WipeRecoveryTest`.
Snapshot-worthiness widens: pipes with in-transit stacks are snapshot-worthy; snapshots carry + restore the `InTransit` list on the wipe signature (never-stomp: only restore onto an empty in-transit list). Tests mirror the flow-state 5b cases. Commit: `fix(net): in-transit item stacks survive the connected-block wipe`.

### Task 12: docs + final review
Main design doc: §13.4 marked implemented (v1: vanilla containers, pull-driven), §0 status entry, `[TUNE]` items listed. Whole-feature review (cross-task seams: transit persistence ↔ wipe ↔ visuals ↔ extraction). Then the COMBINED in-game checklist (flow-states + item channel) gates the merge of both.
