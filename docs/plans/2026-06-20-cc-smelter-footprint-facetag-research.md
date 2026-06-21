# CC Smelter: Multi-Cell Footprint & Connected-Block FaceTag Research

*Status: **RESOLVED 2026-06-20.** The "restrict pipe attachment to specific footprint cells/faces" problem is SOLVED and confirmed in-game (see [§RESOLVED](#resolved-2026-06-20-per-cell-pipe-attachment-shipped) below). This doc keeps the original research/limitations (the connected-block FaceTag system genuinely cannot tag filler cells per-cell) plus the code-level solution that works around it. Parent design: [docs/design.md](../design.md) §7.5 (footprint/ports). Feature plan: [2026-06-19-cc-smelter-implementation-plan.md](2026-06-19-cc-smelter-implementation-plan.md).*

## RESOLVED (2026-06-20): per-cell pipe attachment, shipped

The spurious pipe stubs (item arms on both rows of a side; power arms on all of the back) are GONE. Solution: **do the per-cell filtering in our own Java**, not in the connected-block template (which provably cannot — FINDING 2 stands). The engine still welds inherited-tag arms to every footprint cell; our existing suppressed-arm override (`NetworkTickSystem.applySuppressedArmVisuals`, comparing `PipeVisualStates.effectiveMask` vs `physicalMask`) then DROPS the arms that land on a cell/face with no real port.

**Key facts established this session (replacing the earlier guesses):**

1. **Footprint is 2(X)×2(Y)×1(Z) = 4 cells**, anchor at the East-bottom corner. (The earlier "1×1×2, one filler above" was WRONG; the furnace-derived hitbox `X −0.9..0.9, Y 0..2` claims 2 cells in X and 2 in Y.) This is why the symptom was "both Y faces on a side" (the 2-tall X faces) **and** "back all four" (the 2×2 grid of rear faces).
2. **Filler-cell resolution is Case B:** a filler cell is a real `CC_Smelter` block but `BlockModule.getBlockEntity(filler)` returns **null** — only the anchor carries the `MachineBlockState`. (Confirmed with a temporary `WorldMachineLookup` probe; both possibilities from the old "Options" were on the table, Case B won.)
3. **Ports were never actually seeded.** `SmelterPorts.defaults()` existed only in tests; the smelter's `MachineBlockState` shipped with an EMPTY `PortConfig` (the never-done "Task 12 placement init"), so nothing connected at all. Fixed by seeding `Ports` in `CC_Smelter.json`'s `BlockEntity` (the same mechanism that seeds `Energy`), guarded by `SmelterJsonPortsTest` (decodes the JSON via the real codec, asserts it equals `SmelterPorts.defaults()`).

**The implementation (all in mod code, no engine/JSON-template change):**

- `Port` gained a model-space cell offset `(cellX,cellY,cellZ)` (optional codec keys; legacy data → anchor). `PortConfig.forCell(dx,dy,dz)` returns one cell's ports.
- `SmelterPorts.defaults()` is now positioned: item-out anchor `(0,0,0)` East, **item-in West-bottom `(-1,0,0)` West**, power anchor `(0,0,0)` North. Upper cells expose nothing.
- `PortProjection.forWorldCell(model, Rotation, worldCellOffset)` projects model-space ports to world faces/cells via the engine's `Rotation.rotateY` (handles NESW placement).
- `WorldMachineLookup.at(cell)` resolves filler→anchor (`accessor.getFiller` + `FillerBlockUtil.unpackX/Y/Z`), returns the **per-cell** `ports()` (drives transport + effective mask) and an anchor-level `advertisesChannel()` (drives the physical mask = engine-weld mirror). A filler face thus has `effective < physical` → its inherited arm is dropped.

**In-game confirmation (devserver `2026-06-20_12-42-24_server.log`, footprint-probe, since removed):** a freshly placed smelter (anchor `232,122,275`, `rot=OneEighty`) resolved `off(0,0,0) cellPorts=2`, `off(1,0,0) cellPorts=1` (the West-bottom item-in, model `(-1,0,0)` rotated 180° → world `(+1,0,0)`), `off(0,1,0)/off(1,1,0) cellPorts=0`. Arms render only on the three real port faces. User-confirmed visually.

**Known follow-ups (not blockers):**
- Rotation proven for `None` (unit) and `OneEighty` (unit + in-game); **90°/270° handedness not yet observed in-game** (relies on `rotationIndex → Rotation.values()[idx]`).
- An unrelated power-cable visual bug exists (tracked separately).
- `BlockAccessor.getFiller`/`getRotationIndex` are engine-deprecated (marked for-removal) but still the live accessors.
- Item ports → bench containers (plan Task 14) is still its own task; this work fixed the attachment/visual layer + power.

---

*Original research notes (the limitation that forced the code-level solution) follow.*

## Context

The `CC_Smelter` is the first multi-cell CC machine: a `DrawType: Model` block on a furnace-shaped 2-cell-tall footprint (anchor cell Y=0 + one filler cell Y=1, via the hitbox `CC_Smelter.json` copied from `Bench_Furnace`). It connects to our pipes via a `CustomTemplate` `ConnectedBlockRuleSet` (`CC_SmelterTemplate.json`) that tags faces; pipes stub to a tagged neighbor face. Model ports: item-in nub on −X (West), item-out on +X (East), power on −Z (rear). Goal observed in testing: item/power should attach only at the BASE cell, not the upper filler.

## What worked (shipped on branch `feature/cc-smelter`)

- **Item ports**: template `West`/`East` -> `CC_ItemFace` correctly stub item pipes on the −X/+X faces. (commit history around `3a0f515`)
- **Power port on rear**: FIXED by tagging template **`South`** (NOT `North`) with `CC_PowerFace` (commit `84e9204`). See the FaceTag convention below for why.
- **Energy/recipe substrate** (held vanilla bench, energy-gated tick) is complete and reviewed (Tasks 1-8); see the plan. The smelter is verified to drive a real `ProcessingBenchBlock` to completion (spike smelted a cobalt bar).

## FINDING 1: Connected-block FaceTag direction convention (with a Z-axis flip)

A `CustomConnectedBlockTemplate` shape has `FaceTags: { <Direction>: [<tag>...] }`. A pipe decides to stub toward a neighbor by matching that neighbor's FaceTag on a direction. Empirically (from the working item/power pipe templates' `RulesToMatch`, cross-checked in-game):

| Physical face of our block | FaceNames.java name (internal) | Template FaceTag key to use |
|---|---|---|
| −X (left)  | West  | **West**  (direct) |
| +X (right) | East  | **East**  (direct) |
| −Y (down)  | Down  | **Down**  (direct) |
| +Y (up)    | Up    | **Up**    (direct) |
| −Z (rear)  | North | **South** (FLIPPED) |
| +Z (front, model window) | South | **North** (FLIPPED) |

**The Z axis is inverted between our internal `FaceNames` (+Z=South, −Z=North) and the connected-block template's North/South naming (+Z=North, −Z=South). X and Y are direct.** This is why tagging `North` for the rear power port put the tag on the front/window face and the cable wouldn't connect at the back; `South` fixed it.

Evidence: item pipe template `CC_ItemConnectedBlockTemplate.json` `Shapes.end.PatternsToMatchAnyOf[].RulesToMatch[]`:
`{Position:(0,0,+1) -> FaceTags{South}}`, `{Position:(+1,0,0) -> FaceTags{West}}`, `{Position:(0,+1,0) -> FaceTags{Down}}` etc. The pipe templates also set `"TransformRulesToOrientation": false` (match in world axes).

## FINDING 2 (the blocker): filler cells inherit the anchor's FaceTags; per-cell tagging is impossible

We wanted the upper filler cell (Y=1) to expose NO item tags while the base anchor (Y=0) keeps them. We tried a two-shape template: a `base` shape (item+power tags) selected when a `CC_Smelter` sits at +Y, and a tagless `body` default. **In-game the upper cell STILL stubbed item pipes** — it inherited the anchor's `base` tags rather than resolving its own `body` shape.

**Conclusion: filler cells of a multi-cell block do NOT run their own connected-block shape resolution; they inherit the anchor cell's resolved FaceTags. A multi-cell machine's pipe-stub faces are therefore uniform across its whole footprint.** (Consistent with design.md §7.5 "FaceTags are uniform across a machine's cells.") The experiment was reverted in commit `6c9ec02`; the flat single-shape template (with the `South` power fix) is what ships. Memory: [[filler-cells-inherit-anchor-facetags]].

> **This limitation is real and permanent — but no longer blocking.** We stopped trying to make the *engine* tag per-cell and instead drop the unwanted (inherited) arms in *our* Java suppressed-arm override, keyed by a per-cell `PortConfig`. See [§RESOLVED](#resolved-2026-06-20-per-cell-pipe-attachment-shipped) at the top.

## Decompiled reference (Server-0.5.3.jar, `...universe.world.connectedblocks.*`)

For a future attempt, the relevant classes + facts (via `javap -p` and class-string extraction):

- `ConnectedBlockFaceTags`: holds `Map<Vector3ic, HashSet<String>> blockFaceTags` (tags keyed by face DIRECTION). `public static final CODEC`, `EMPTY`. Methods `contains`, `getBlockFaceTags(Vector3i)`, `getDirections`.
- `ConnectedBlockPatternRule`: a single match rule. Codec JSON keys: **`Position`** (field `relativePosition`, a Vector3ic neighbor offset), **`BlockTypes`** (`HashSet<String>` of block ids to match), **`FaceTags`** (neighbor face tags), **`IncludeOrExclude`** (`"Include"`/`"Exclude"`), **`PlacementNormals`**, plus `BlockTypeLists`/`ShapeBlockTypeKeys`. So rules CAN match a neighbor by block id or by face tags. (This part works; it just only drives the ANCHOR's shape.)
- `CustomConnectedBlockPattern`: has `ConnectedBlockPatternRule[] rulesToMatch`, `requireFaceTagsMatchingRoll`; `getConnectedBlockTypeKey(blockId, store, Vector3ic, ruleSet, blockType, int, Vector3ic, boolean)` resolves a shape for a position.
- `ConnectedBlocksUtil.getDesiredConnectedBlockType(store, Vector3ic, BlockType, int, Vector3ic, boolean)`: positional shape resolution entry point. `setConnectedBlockAndNotifyNeighbors(...)`, `notifyNeighborsAndCollectChanges(...)`.
- `CustomConnectedBlockTemplateAsset`: codec keys `ConnectsToOtherMaterials`, `DefaultShape`, `Shapes`. Each shape: `FaceTags` + `PatternsToMatchAnyOf` (a `ConnectedBlockShape`).
- `FillerBlockUtil`: `public static int pack(int,int,int)`, `unpackX/Y/Z(int)`, `NO_FILLER`, `forEachFillerBlock(...)`, `setFillerBlocksAt(...)`, `removeFillerBlocksAt(...)`. (Anchor = pos − unpack(fillerValue). This is the engine API for the filler footprint; use it directly, do not re-implement.)

## Options to restrict pipe attachment to the base cell (HISTORICAL — superseded)

*These were the options weighed before the fix. We chose a 4th option not listed here (code-level per-cell suppression). Kept for context.*

1. **Accept the upper stub (lowest effort).** Purely cosmetic; real filtering in `PortConfig`. *(Rejected: the stubs looked broken.)*
2. **Single-cell footprint (Y=0 only).** *(Rejected: loses collision; model-cull risk.)*
3. **Two-block placement.** *(Rejected: most work; custom multi-block place/break/carry.)*
4. **CHOSEN — code-level per-cell suppression.** Keep the full anchor+filler footprint and the inherited tags; give `Port` a cell offset, seed the smelter with positioned ports, and let our existing suppressed-arm override drop the arms on portless cells/faces (resolving filler→anchor + rotation in `WorldMachineLookup`/`PortProjection`). No engine/JSON-template change. See [§RESOLVED](#resolved-2026-06-20-per-cell-pipe-attachment-shipped). This directly realizes design.md §7.5's "PortConfig keyed by (footprint-cell-offset, face)".

## Other gotchas captured this session

- **Model lives in two places and desyncs.** Source `models/_cc_machines/CC_Smelter.blockymodel` (edited in Blockbench) vs the game-loaded copy `src/main/resources/Common/Blocks/ChonbosMods/Machines/CC_Smelter.blockymodel`. Editing the source does nothing in-game until re-copied. Memory: [[machine-model-asset-copy]]. Consider a Gradle copy task / symlink so the asset tree auto-syncs.
- **EnergyBuffer asset keys** are `Stored`/`Capacity` (NOT `Amount`, which is `ResourceBuffer`/tank).
- **Block support** field is `Support: { "Down": [{ "FaceType": "Full" }] }` (mirrored from vanilla benches); `DrawType: Model` blocks expose no support faces unless declared.
- **Bench recipe matching** is by `BenchRequirement: [{ "Type": "Processing", "Id": <benchId> }]`; the held bench config must use **`Id: "Furnace"`** to surface the 28 vanilla furnace smelting recipes (recipes are defined on the OUTPUT item, e.g. `Ingredient_Bar_Cobalt.json`).
- **VariantRotation NESW + FaceTags**: in the as-placed (default) orientation the X tags mapped to the intended physical faces; whether tags rotate correctly under all four NESW variants was not exhaustively verified (item worked in the test orientation). Worth a rotation sweep when revisiting.

## Open questions

1. ~~Single-cell model cull~~ — MOOT (kept the multi-cell footprint; option 2 not taken).
2. ~~Two-block placement~~ — MOOT (option 3 not taken).
3. **Rotation across NESW for asymmetric ports** — `None`/`OneEighty` confirmed (unit + in-game); **90°/270° handedness still unverified in-game** (`rotationIndex → Rotation.values()[idx]` in `WorldMachineLookup.rotationAt`). Place a smelter at each facing and confirm item-in lands on the correct world cell.
4. **Functional path still TODO:** item ports → bench containers (plan Task 14), display-only GUI, end-to-end rig. Placement-init (ports) + filler→anchor transport are now DONE.
5. ~~Power-cable visual bug~~ — **FIXED 2026-06-20** (see below).

## Follow-up FIXED: power-cable elbow dropped the machine stub

While verifying the footprint fix we hit a separate, pre-existing visual bug: a power cable bordering the smelter's real power face stubbed correctly alone, but adding a second cable to its side made the smelter stub vanish (the cable retargeted to the new cable); a third cable brought it back. Item pipes were unaffected.

**Root cause (probe-confirmed):** the engine's connected-block matcher mis-resolves a power cable that borders **a machine power-face + a pipe** — it welds `End` (the pipe arm only) instead of `Elbow`, dropping the machine arm. Decisive probe line: `(248,122,265) eff=[-X,+Z] phys=[-X,+Z] placed=End rot=3 wantShape=Elbow wantRot=3 overridden=false`. Our suppressed-arm override (`PipeVisualStates` effective-vs-physical) skipped it because `effective == physical` — but the engine had drawn the wrong shape for a *correct* mask. (Power-specific because the power `CustomConnectedBlockTemplate` lacks that machine+pipe elbow coverage that the item template has; the 3-arm recovery is the engine resolving the tee correctly.)

**Fix (mod-side, general):** `NetworkTickSystem.engineMayOwnVisual` — the cheap "trust the engine, skip the read" fast path now excludes **endpoint-adjacent** pipes (`endpointArmMask != 0`). Any pipe touching a machine/container is reconciled to OUR shape table (`PipeShapes`, the authoritative twin), which resolves `Elbow` correctly; pure pipe-pipe runs keep the fast path. The read-before-write throttle means it only writes when the engine actually diverged (no flicker). Unit-tested in `NetworkTickResetTest` (the bug case: endpoint-adjacent + `effective == physical` must not be skipped). Confirmed in-game.
