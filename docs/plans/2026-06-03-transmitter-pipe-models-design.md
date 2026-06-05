# Transmitter Pipe Models (visual auto-connect) — Design

Date: 2026-06-03
Scope: **modelling + visual connection only.** No transport logic (that is being built in
parallel; this layer produces the blocks/models it will attach behavior to). Looks/textures
are placeholder for now and polished in a later pass ("get them functional, then edit looks").

## Goal

Four Mekanism-style transmitter families that auto-connect to their own kind and swap their
block model based on which neighbours connect:

| Family | Channel | Mekanism analogue |
|--------|---------|-------------------|
| Power Cable | `power` | universal_cable |
| Fluid Pipe  | `fluid` | mechanical_pipe |
| Gas Tube    | `gas`   | pressurized_tube |
| Item Transporter | `item` | logistical_transporter |

These map 1:1 onto the existing `PortChannel {POWER, FLUID, GAS, ITEM}`.

## How Hytale does connected blocks (verified against decompiled U5 server)

Each visual state is a **separate block type with its own model**; the engine swaps the placed
block to the right state + rotation automatically and re-evaluates neighbours. No runtime code
needed from us. Mechanism:

- `BlockType.ConnectedBlockRuleSet` (`Type: "CustomTemplate"`) →
  `TemplateShapeAssetId` (a `CustomConnectedBlockTemplate` asset) +
  `TemplateShapeBlockPatterns` (shape-name → block-pattern key).
- Shapes are declared as `BlockType.State.Definitions.<ShapeName>.CustomModel`.
- The template matches neighbours by **FaceTags**; `AllowedPatternTransformations.IsCardinallyRotatable`
  lets one authored pattern cover its rotations (engine applies the rotation to the result block too).
- Server-driven, synced to client by block id. Works from a server plugin asset pack.

Reference vanilla files (copy these):
- `models/references/Server/Item/CustomConnectedBlockTemplates/{Wall,Rails,Branch}ConnectedBlockTemplate.json`
- `models/references/Server/Item/Items/Rail/Rail.json` (full block-def with `State.Definitions` + `ConnectedBlockRuleSet`)

## The 10 topology shapes (rotationally-unique 6-way connector)

The engine rotates each, so we author one canonical orientation per topology.
Face/axis convention (matches `OFFSETS {+X,-X,+Y,-Y,+Z,-Z}` and the Wall template):
`+X=East −X=West +Y=Up −Y=Down +Z=North −Z=South`.

| # | Shape id | Connected faces (canonical) | Notes |
|---|----------|------------------------------|-------|
| 0 | `node`    | (none)                       | floating centre cube, unconnected |
| 1 | `end`     | N                            | single stub |
| 2 | `straight`| N, S                         | opposite pair |
| 3 | `elbow`   | N, E                         | adjacent pair |
| 4 | `tee`     | N, S, E                      | straight + 1 (planar) |
| 5 | `tripod`  | N, E, Up                     | three mutually-adjacent |
| 6 | `cross`   | N, S, E, W                   | planar 4 |
| 7 | `fourbent`| Up, Down, N, E               | non-planar 4 (vertical run + adjacent pair) |
| 8 | `five`    | N, S, E, W, Up               | all but Down |
| 9 | `six`     | all 6                        | full junction |

## Geometry (native Hytale nodes, `hytale_prop`, 32-unit block)

Built once, **shared across all four families** for the functional pass (diameter/texture
differentiation is the later looks pass). Two primitives, assembled procedurally:

- **Centre cube**: 16×16×16 box centred in the block (the floating `node`).
- **Stub**: 8-long × 8×8 box from the centre face out to the block edge, one per connected face,
  rotated onto the faces each topology needs.

Each of the 10 `.blockymodel` files = centre + the stubs for that topology's faces, authored in
the canonical orientation above. Shipped at:
`src/main/resources/Common/Blocks/ChonbosMods/Pipes/Pipe_<Shape>.blockymodel`

Proportions are placeholder (Mekanism-derived); refined in the looks pass.

## Block definitions & integration with the network layer

- Each family is one base `BlockType` whose default state is `node`, with `State.Definitions`
  for the other 9 shapes (each overriding only `CustomModel` + the per-family `CustomModelTexture`).
- **State definitions inherit the base block's properties** (incl. `BlockEntity.Components`), so the
  network layer's `MachineBlockState` + ports added to the base block carry onto every shape
  variant automatically — the model swap does not drop the component.
- `power` already has a placeholder block `CC_PowerCable` (DrawType `Cube`, owns `MachineBlockState`).
  Integration = upgrade it to `DrawType: Model` + add `State`/`ConnectedBlockRuleSet`. **This edit is
  left to the network-logic instance to avoid clobbering concurrent work**; this doc + the template
  files specify exactly what to add. Fluid/Gas/Item transmitter blocks do not exist yet and will be
  created when wired.
- FaceTags are per-family (`CC_PowerFace`, `CC_FluidFace`, `CC_GasFace`, `CC_ItemFace`) so families
  only connect to their own kind. → 4 near-identical template assets (one tag each).

## Deliverables of THIS (modelling) pass

1. 10 shared topology `.blockymodel` files (the core deliverable).
2. 4 `…ConnectedBlockTemplate.json` assets (one per family, differing by FaceTag).
3. Reference block-def snippet (Rail.json-shaped) showing the `State.Definitions` +
   `ConnectedBlockRuleSet` wiring, for the network instance to merge in.
4. This design doc as the contract.

Out of scope: transport logic, final textures/diameters, recipes, GUI.
