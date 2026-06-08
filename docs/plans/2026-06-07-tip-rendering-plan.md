# Tip Rendering + Connection Rules Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (continuing the item-channel execution mode).
> Design (read first): `docs/plans/2026-06-07-tip-rendering-design.md`. Branch: `feat/item-channel`.

**Conventions:** TDD; suite green before every commit (baseline = current); `:` not em-dash; no Co-Authored-By; pure-first.

### Task 1: endpoint connection cap (3 per pipe)
Modify `ItemEndpoints.collect` + `NetworkEndpoints.collect`: per MEMBER pipe, count qualifying endpoint neighbours in face-index order; the 4th+ is skipped entirely (not collected, not dedup-claimed). Tests: 4 containers around one item pipe → 3 collected, lowest faces win; same for machines on a power pipe; NONE faces don't count against the cap; the cap is per-pipe not per-network (two pipes × 3 each = 6 fine). ALSO: `PipeVisualStates` masks must agree (a capped-out endpoint draws no arm): same cap logic in `effectiveMask` (extract a shared helper if clean). Commit: `feat(net): pipes join at most 3 non-pipe endpoints (face-index order wins)`.

### Task 2: wrench 2-tip budget
`WrenchCycles`: pipe-face cycle gains the budget: cycling toward MACHINE skips PUSH/PULL when the pipe already has 2 push/pull faces NOT counting the face being cycled. Signature change: pass the PipeNode (or a count). `WrenchInteraction`: when the skip happens, message `Pipe direction budget: 2 faces max (this pipe already has 2)`. Tests: budget enforced, the cycled face's own current state doesn't count against itself, NORMAL→NONE still available. Commit: `feat(block): wrench enforces the 2 push/pull faces per pipe budget`.

### Task 3: the generator (`scripts/gen_pipe_tips.py`)
Python, mirrors the repo's scripts/ style. Inputs: family prefix + paths. Steps: (a) parse `F_end`/`F_end_push`/`F_end_pull` and the vertical pairs; extract tip node-sets by diffing (nodes present in the tipped model absent from the plain one); (b) for each topology shape, read its base mask (re-derive from the family's connected-block template Include rules: same parsing as PipeShapes' derivation); (c) for every ≤2-tip assignment over the shape's arms: transform the tip node-set onto each tipped arm (horizontal arms: yaw-rotate the +Z-oriented horizontal tip; ±Y arms: use the vertical tips verbatim) and merge into a composite model; (d) write models + emit a JSON-patch file (state defs, pattern map, template shapes; `_On` twins for families with on-textures). VERIFY in-script: every output parses, node counts = base + tips, texture refs correct, deterministic output (stable ordering, no timestamps). Unit-test the pure helpers (mask→arm list, naming) via a tiny pytest if the repo pattern supports it (scripts/test_*.py exists: follow it). Commit: `feat(tools): pipe tip composite generator`.

### Task 4: PipeShapes per-face indicator selection
Replace `indicatorStateFor(mask, faceState, energized)` with `tippedStateFor(int effectiveMask, FlowState[] faceStates, int endpointArmMask, boolean energized)`: returns the composite key when 1-2 endpoint arms carry PUSH/PULL (key built per the naming scheme), the plain `stateFor` key otherwise; 3+ tips → 2 lowest faces (defensive). Tests: key construction matches the generator naming for samples across arities, zero/1/2/3-tip cases, endpoint-arm gating (a PUSH face toward a PIPE arm never tips), energized twins. Driver (`NetworkTickSystem`): build the per-face inputs (it already has the pipe + neighbour kinds), call the new selector, delete the bitCount==1 path. Commit: `feat(net): per-face tip state selection (any shape, 2-tip budget)`.

### Task 5: generate the ITEM family + smoke test gate
Run the generator for ItemPipe; commit models + JSON. USER GATE: load in-game, check asset-load time + a tee with 2 pulled chests renders both arrows. Only after the gate: generate Pipe/FluidPipe/GasTube (+ on-twins). Commits: `feat(assets): generated tip composites: ItemPipe (smoke test family)` then `...: remaining families`.

### Task 6: cleanup + docs
Delete `TipSpikeCommand` + registration. Main design doc: §13.9 gains the two rules; §0 status. Whole-feature review. Commit: `chore: tip spike probe removed; docs: connection cap + tip budget recorded`.
