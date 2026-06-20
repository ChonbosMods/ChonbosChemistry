# CC Smelter: Multi-Cell Footprint & Connected-Block FaceTag Research

*Status: research notes captured 2026-06-20 during the CC Smelter in-game asset bring-up. Documents what we learned (and the hard limitations we hit) about Hytale's connected-block FaceTag system as it applies to multi-cell machine footprints, so a future session can resume the "restrict pipe attachment to specific footprint cells/faces" problem without re-doing the decompile + in-game experiments. Parent design: [docs/design.md](../design.md) §7.5 (footprint/ports). Feature plan: [2026-06-19-cc-smelter-implementation-plan.md](2026-06-19-cc-smelter-implementation-plan.md).*

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

## Decompiled reference (Server-0.5.3.jar, `...universe.world.connectedblocks.*`)

For a future attempt, the relevant classes + facts (via `javap -p` and class-string extraction):

- `ConnectedBlockFaceTags`: holds `Map<Vector3ic, HashSet<String>> blockFaceTags` (tags keyed by face DIRECTION). `public static final CODEC`, `EMPTY`. Methods `contains`, `getBlockFaceTags(Vector3i)`, `getDirections`.
- `ConnectedBlockPatternRule`: a single match rule. Codec JSON keys: **`Position`** (field `relativePosition`, a Vector3ic neighbor offset), **`BlockTypes`** (`HashSet<String>` of block ids to match), **`FaceTags`** (neighbor face tags), **`IncludeOrExclude`** (`"Include"`/`"Exclude"`), **`PlacementNormals`**, plus `BlockTypeLists`/`ShapeBlockTypeKeys`. So rules CAN match a neighbor by block id or by face tags. (This part works; it just only drives the ANCHOR's shape.)
- `CustomConnectedBlockPattern`: has `ConnectedBlockPatternRule[] rulesToMatch`, `requireFaceTagsMatchingRoll`; `getConnectedBlockTypeKey(blockId, store, Vector3ic, ruleSet, blockType, int, Vector3ic, boolean)` resolves a shape for a position.
- `ConnectedBlocksUtil.getDesiredConnectedBlockType(store, Vector3ic, BlockType, int, Vector3ic, boolean)`: positional shape resolution entry point. `setConnectedBlockAndNotifyNeighbors(...)`, `notifyNeighborsAndCollectChanges(...)`.
- `CustomConnectedBlockTemplateAsset`: codec keys `ConnectsToOtherMaterials`, `DefaultShape`, `Shapes`. Each shape: `FaceTags` + `PatternsToMatchAnyOf` (a `ConnectedBlockShape`).
- `FillerBlockUtil`: `public static int pack(int,int,int)`, `unpackX/Y/Z(int)`, `NO_FILLER`, `forEachFillerBlock(...)`, `setFillerBlocksAt(...)`, `removeFillerBlocksAt(...)`. (Anchor = pos − unpack(fillerValue). This is the engine API for the filler footprint; use it directly, do not re-implement.)

## Options to restrict pipe attachment to the base cell (for next session)

Since per-cell tags are out, the realistic levers are:

1. **Accept the upper stub (lowest effort).** It is purely cosmetic: the real input/output filtering lives in `PortConfig` keyed by (footprint-cell-offset, face), so only the base port actually transfers (Tasks 12-14, not yet built). Ship the smelter functional; revisit visuals later.
2. **Single-cell footprint (Y=0 only).** Shrink the hitbox to one cell so there is no upper cell to inherit tags. UNVERIFIED RISK: whether a `DrawType: Model` block culls/clips its CustomModel to the footprint (would hide the upper half of the model). Many engines render the full model regardless (fences/tall plants) — **a 1-minute in-game test would settle this** and is the cheapest next experiment. Also loses collision on the upper half.
3. **Two-block placement (keeps full collision + clean attachment).** Place a 1-cell `CC_Smelter` base (component + ports + tags) and auto-place a separate tagless `CC_Smelter_Top` collision/visual block above it on placement; remove it on break/carry. Pipes only ever see the base's tags. Cost: custom multi-block place/break/carry logic (NOT the anchor+filler system), and splitting/handling the model across two blocks. This is the most robust but most work.

Recommended next-session order: test option 2's model-cull question first; if the model renders full-height, option 2 is the clean answer; else go to option 3 or accept (option 1).

## Other gotchas captured this session

- **Model lives in two places and desyncs.** Source `models/_cc_machines/CC_Smelter.blockymodel` (edited in Blockbench) vs the game-loaded copy `src/main/resources/Common/Blocks/ChonbosMods/Machines/CC_Smelter.blockymodel`. Editing the source does nothing in-game until re-copied. Memory: [[machine-model-asset-copy]]. Consider a Gradle copy task / symlink so the asset tree auto-syncs.
- **EnergyBuffer asset keys** are `Stored`/`Capacity` (NOT `Amount`, which is `ResourceBuffer`/tank).
- **Block support** field is `Support: { "Down": [{ "FaceType": "Full" }] }` (mirrored from vanilla benches); `DrawType: Model` blocks expose no support faces unless declared.
- **Bench recipe matching** is by `BenchRequirement: [{ "Type": "Processing", "Id": <benchId> }]`; the held bench config must use **`Id: "Furnace"`** to surface the 28 vanilla furnace smelting recipes (recipes are defined on the OUTPUT item, e.g. `Ingredient_Bar_Cobalt.json`).
- **VariantRotation NESW + FaceTags**: in the as-placed (default) orientation the X tags mapped to the intended physical faces; whether tags rotate correctly under all four NESW variants was not exhaustively verified (item worked in the test orientation). Worth a rotation sweep when revisiting.

## Open questions for next session

1. Does a single-cell-footprint `DrawType: Model` block render its full (taller-than-1-cell) model, or cull it? (cheap in-game test; gates option 2)
2. Two-block placement: can we cleanly auto-place/remove a companion `CC_Smelter_Top` block, and carry it with the base via the existing `BlockHolder` path? (option 3)
3. Do FaceTags rotate correctly across all four NESW variants for asymmetric port layouts?
4. Functional path still TODO regardless of visuals: Tasks 12-16 (placement init, filler->anchor in transport/wrench, item ports -> bench containers, display-only GUI, end-to-end rig).
