# ITEM Pipe Channel (§13.4) — Design

*Status: validated 2026-06-06. Discrete item transport, Mekanism-logistical-transporter-faithful, against vanilla storage containers of any kind. Builds directly on the per-face flow states (`docs/plans/2026-06-05-pipe-flow-states-design.md`); executes stacked on `feat/pipe-flow-states` so both features verify in one in-game pass (user decision).*

## Decisions (user-validated)

- **Face rules on ITEM faces** (containers are passive; extraction is always explicit):
  - `NORMAL`: deliver-into allowed + accepts machine ejections (future); never auto-extracts.
  - `PULL`: auto-extracts from the container at a rate; deliveries blocked through this face.
  - `PUSH`: insert-only (deliveries allowed, nothing ever comes out).
  - `NONE`: invisible.
- **Routing: nearest-first** (shortest pipe path wins). Round-robin/priority are later per-face options (§13.4 reserves them).
- **Stranded ladder:** re-route if possible → else return toward origin → origin gone/full → **pop out as a ground drop** at the stack's current pipe. Breaking a pipe with a stack inside drops it. Never voids, never idles.
- **v1 endpoints: vanilla containers only** (any block entity with an engine `ItemContainer`). Machine ITEM ports join at the machines milestone (their `ResourceBuffer` lacks ItemStack identity: wrong shape to bridge now).
- **Filter stub (user request):** an `ItemFilter` seam checked at extraction, destination qualification, AND every junction hop during pathfinding; v1 = allow-all. The later tag/type filters at pipe intersections only implement the lookup: no routing rewrite.
- **No extraction without a destination:** a PULL face extracts only after the network confirms a valid, admitting, accepting destination (Mekanism sorter rule).
- **NO `_On` state for item pipes (user decision):** single texture, off is the default. `itempipe_on.png` deleted; the visual driver passes `energized=false` for ITEM networks; `CC_ItemPipe.json` ships 32 states (26 topology + 6 indicators, no twins, no bare `On`).

## Architecture

**Pure (headless-TDD):**
- `ContainerLookup` seam (pure interface; world impl wraps the engine `ItemContainer` access, §16.6 primitives `getContainerState`/`moveItemStackFromSlot`/`canAddItemStackToSlot`).
- `ItemEndpoints`: OFFSETS walk over member pipes collecting `(containerPos, viaPipe, faceState)` endpoints; qualification = the pipe face's flow state alone (containers have no ports).
- `ItemPathfinder`: BFS shortest path entry-pipe → destination over member pipes, edges gated by `PipeConnectivity.connects` AND the filter seam (a rejecting junction is impassable for that stack); returns nearest-first candidates.
- `ItemFilter`/`FilterLookup`: the stub. `admits(stack, pipeKey, viaFace)`; v1 `AllowAll`.
- `TravelingStack`: `(ItemStack bson, path, segmentIndex, progressTicks, originKey, destKey)`.
- Re-route ladder as a pure state machine (advance/arrive/re-route/return/pop-out decisions).
- Extraction eligibility: PULL scan, first extractable + admitted stack, destination confirmation, per-pull cap, network-saturation backpressure.

**State & persistence:** traveling stacks ride a new OPTIONAL `PipeNode` codec key (`InTransit`; absent = none: same no-migration trick as `FlowStates`). A stack is owned by exactly the segment it occupies: chunk save/load and the H8 wipe-snapshot system extend naturally (snapshot-worthiness widens to pipes with in-transit stacks).

**Tick driver:** new `ItemTransferSystem` (per-network dedup like `NetworkTickSystem`): phase 1 advance/arrive/re-route, phase 2 PULL extractions. `NetworkTransfer.distribute` skips ITEM networks (separate subsystem; shares only discovery/caching per §13.4).

## Visuals, wrench, panel

- **Chest stubbing:** vanilla chests advertise no `CC_ItemFace`, so the engine never welds arms toward them. `PipeVisualStates.effectiveMask` gains container awareness (the `ContainerLookup` seam, face-state gated) while `physicalMask` stays tag-based: **effective > physical** drives the same programmatic state swap that suppression uses, just adding arms instead of dropping them.
- **Indicators:** PULL/PUSH single-arm ends render the landed `ItemPipe_*_push/_pull` models (6 indicator states, no `_On`).
- **Wrench:** target resolution gains the container check (a chest is a MACHINE-style 4-cycle target).
- **Panel:** ITEM networks have no shared buffer: the gauge row becomes `In transit: N stacks • Pipes: M`, destinations count on row 2, faces row unchanged.

## Assets

Regen `CC_ItemConnectedBlockTemplate` from the power template (`CC_ItemFace`, 26 + 6 indicator shapes, indicators pattern-less, NO on-twins), add `CC_ItemMachineTemplate`, author `CC_ItemPipe.json` (32 states) against the landed `ItemPipe_*` v2 models, delete `itempipe_on.png`. Test rig: vanilla chests + `CC_ItemPipe` + `CC_Wrench`: nothing new needed.

## `[TUNE]` placeholders

Pull interval ~20 ticks; per-pull cap ~16 items; travel ~5 ticks/segment; max in-transit per network = segment count (saturation also blocks extraction).

## Testing

Pure: pathfinder (shortest, filter-gated, NONE-respect, nearest order, mid-path re-route), endpoints qualification, re-route ladder, TravelingStack codec + absent default, extraction eligibility, mask container-awareness (both divergence directions). Engine glue (real containers, ground drops, wrench-on-chest) verifies in-game together with the flow-states checklist.
