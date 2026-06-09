> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# BlockHolder Contents Carry (tanks + machines) — Design

*Status: validated and implemented 2026-06-05. Third transport quick-win (branch `chore/transport-quick-wins`). Supersedes the H7 `CC_StoredEnergy` single-field energy carry.*

## Decision

**One unified carry path for tanks AND machines (user decision)**, built on the engine's native
`"BlockHolder"` item-metadata mechanism instead of per-field custom metadata. A broken block with
preservable contents drops an item carrying its FULL encoded block entity; placing that item restores
every component. This implements §5.2 "tanks relocate with contents preserved" and upgrades machines
from energy-only carry to full-state carry (energy + resource buffers + type-locks + work progress).

## Engine mechanism (verified by decompile, Server 0.5.3)

- **Restore (native, zero mod code):** `BlockPlaceUtils.onPlaceBlockSuccess` reads item metadata key
  `"BlockHolder"` (`ItemStack.Metadata.BLOCK_HOLDER`), decodes a `Holder<ChunkStore>` via
  `ChunkStore.REGISTRY.getEntityCodec()`, and applies it with `WorldChunk.setState(x, y, z, blockType,
  rotationIndex, holder)`.
- **Capture (ours, break-side):** `WorldChunk.getBlockComponentHolder(x, y, z)` → encode with the same
  entity codec → stamp via `ItemStack.withMetadata("BlockHolder", doc)`.

## Implementation

- **`BlockHolderCarry`** (pure, unit-tested): BSON stamping seam (`stamp`/`read`, clone-never-mutate)
  + carry predicates: a machine carries when energy > 0 or any resource buffer is non-empty; a tank
  carries when its buffer holds anything. Empty blocks stay fully vanilla (drops/sounds/particles).
- **`CarryBreakEventSystem`** (engine glue, replaces `MachineBreakEventSystem`): the proven H7
  cancel + deferred custom-break flow, now machine+tank aware. Captures and encodes the entity
  IMMEDIATELY before `setBlock(EMPTY)` (the encode snapshots all components to BSON), keeps the H8b
  connected-block reshape + pipe re-snapshot, then spawns the stamped drop. An encode failure degrades
  to a plain drop (block never lost).
- **Retired:** `MachinePlaceEventSystem` (the engine restores natively), `MachineEnergyMetadata` (+
  its 12 tests). Dev-stage note: items already stamped with the old `CC_StoredEnergy` key lose their
  charge: acceptable, no migration shim.

## In-game verification checklist (next dev-server session)

1. Charge a `CC_EnergyStorage`, break it, place it: charge intact (replaces the old verified flow).
2. Fill a `CC_FluidTank` with bromine, break, place: contents AND type-lock intact; pipes reshape on
   both break and place.
3. Break an EMPTY tank/machine: plain vanilla drop, no metadata.
4. Place a carried tank next to a locked fluid line: network type-lock interplay sane.
