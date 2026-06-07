# Push/Pull Tip Rendering + Connection Rules — Design

*Status: validated 2026-06-07. Supersedes the Task 12 end-stub-only indicators (which proved unreachable in real builds: the working end pipe always has 2+ arms). Follows the overlay-entity spike (DEAD: the client raycasts any networked model entity; no protocol exemption) and the combinatorics negotiation. Branch: continues `feat/item-channel`.*

## Decisions (user-validated)

1. ~~**Connection cap: a pipe block joins at most 3 non-pipe endpoints** (containers/machines). A 4th adjacent endpoint does not connect (deterministic selection: lowest face index wins; wrench/panel reflect reality). Applies to ALL channels (one rule everywhere).~~ **REMOVED 2026-06-07.** Superseded by the 2-PUSH/PULL-face wrench budget (Decision 2), which alone bounds the tip-model combinatorics: NORMAL-face endpoints cost zero states, so capping endpoint COUNT bought nothing. Worse, the cap was config-blind (lowest face index won, ignoring wrenched roles): an in-game bug report confirmed an unconfigured chest could evict a player's wrenched PULL face from the network. The cap is deleted entirely (code + tests); the two refactors it carried in survive: `NetworkEndpoints.overlaps` (single overlap-matrix source) and the dedup contains/add split (only collected endpoints claim a dedup slot).
2. **Config budget: a pipe block carries at most 2 PUSH/PULL faces, any shape.** The wrench cycle skips push/pull on a face when the budget is spent elsewhere (with a chat message naming the rule); NORMAL→NONE still cycles.
3. **Every legal configuration renders truthfully.** Tip models exist for every (shape × ≤2 tipped arms × push/pull) combination: **~604 generated states per family** (on-twins double power/fluid/gas; item has no on-state). Nothing configurable is ever invisible.
4. **All models are GENERATED**, zero hand-authored: tip geometry is extracted from the existing `F_end_push`/`F_end_pull` (horizontal) and `F_vertical_end_up/down_push/pull` (vertical) models by diffing against their plain counterparts, then transposed onto every arm of every topology shape.
5. **Smoke test gates the rollout**: generate ONE family (ITEM: smallest, no twins), verify in-game asset load, then generate the rest. Fallback knob if load chokes: restrict 5/6-arm shapes to 1 or 0 tips (saves ~180/~220 states) without touching the gameplay rules.
6. NORMAL faces remain free: unlimited NORMAL-face endpoints render as plain arms (no new states). PULL is the only position-locked config; multi-pull beyond the budget routes through separate segments (the Mekanism idiom).

## State count math (per family)

Per shape with k arms: `2k` one-tip + `2k(k-1)` two-tip variants. Across the 26 shapes (authoritative template masks: 3×k1, 5×k2, 8×k3, 6×k4, 3×k5, 1×k6): 6+40+144+192+150+72 = **604** (the earlier 618 estimate mis-bucketed one 3-arm shape as 4-arm; the generator self-test pins the real count).

## Naming scheme

`<Family>_<shape>_t<face><p|l>[_t<face><p|l>].blockymodel`, faces in OFFSETS index order, e.g. `GasTube_tee_t0p_t4l` = tee with a push tip on +X and a pull tip on +Z. State keys PascalCase per the existing convention: `Tee_T0p_T4l` (+`_On` twins where applicable). Deterministic: generator and `PipeShapes` derive the same key independently.

## Components

- **Transport rules (pure, TDD):** ~~endpoint cap in `ItemEndpoints` + `NetworkEndpoints` (per member pipe, qualifying endpoints beyond the 3rd skipped in face-index order)~~ (cap REMOVED 2026-06-07: see Decision 1); wrench budget in `WrenchCycles` (cycle skips push/pull when 2 are already spent on other faces) + `WrenchInteraction` message.
- **Generator (`scripts/gen_pipe_tips.py`):** reads the family's topology models + the 3 authored tip orientations; emits composite models + the JSON additions (state defs, pattern map entries, template shapes: mirroring the `_On`-twin wiring per family conventions). Verifiable offline: every emitted model parses, references the family texture, and contains the base shape's geometry plus N tip nodes.
- **`PipeShapes`:** `indicatorStateFor` is replaced by a per-face variant: given the effective mask + the pipe's per-face flow states (+ which arms face endpoints), returns the composite state key (or the plain shape when no tipped endpoint arms). The bitCount==1 restriction dies. Legacy data with 3+ tips (pre-budget saves): render the 2 lowest faces, ignore the rest (defensive; wrench can no longer produce it).
- **Driver (`NetworkTickSystem`):** passes per-face data to the new selector; rest unchanged (read-before-write throttle, rotation fallback).
- **Cleanup:** `TipSpikeCommand` + registration deleted (spike concluded); the 24 hand-authored end-stub models stay as the k=1 generated set's source material.

## Out of scope

The `[CC-item]` diagnostics stay until the combined merge (Bug B monitoring). Filter blocks, round-robin routing: later features as already reserved.
