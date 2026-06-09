> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# Transmitter Pipe — block-def wiring reference

How to wire the placeholder transport blocks to the new models + connection templates.
This is a **reference snippet** for the network-logic instance to merge into the block JSONs
(left un-applied here to avoid clobbering concurrent edits to `CC_PowerCable.json`).

Mirrors the vanilla pattern in
`models/references/Server/Item/Items/Rail/Rail.json` (`State.Definitions` + `ConnectedBlockRuleSet`).

## What changes on the base block (e.g. `CC_PowerCable`)

1. `BlockType.DrawType`: `"Cube"` → `"Model"`.
2. Add `CustomModel` (the `node` shape) + a placeholder `CustomModelTexture`.
3. Add `State.Definitions` — one entry per non-default shape, overriding `CustomModel`.
   State-definition blocks **inherit** the base block's other properties, including
   `BlockEntity.Components` (the `MachineBlockState` + ports), so the model swap does NOT
   drop the network component.
4. Add `ConnectedBlockRuleSet` → `CustomTemplate`, pointing at the per-channel template.

`DefaultShape` = `node`, so the base block itself is the unconnected centre-cube shape.

```jsonc
"BlockType": {
  "DrawType": "Model",
  "Opacity": "Transparent",
  "CustomModel": "Blocks/ChonbosMods/Pipes/Pipe_node.blockymodel",
  "CustomModelTexture": [
    { "Weight": 1, "Texture": "Blocks/ChonbosMods/Pipes/cable_pipe.png" }   // power = Mekanism cable rail sprite (placeholder-final; transparent center channel)
  ],
  "HitboxType": "Rail",
  // ... keep existing Material / Gathering / BlockEntity.Components (MachineBlockState + ports) ...

  "State": {
    "Definitions": {
      "straight": { "CustomModel": "Blocks/ChonbosMods/Pipes/Pipe_straight.blockymodel" },
      "elbow":    { "CustomModel": "Blocks/ChonbosMods/Pipes/Pipe_elbow.blockymodel" },
      "tee":      { "CustomModel": "Blocks/ChonbosMods/Pipes/Pipe_tee.blockymodel" },
      "cross":    { "CustomModel": "Blocks/ChonbosMods/Pipes/Pipe_cross.blockymodel" },
      "end":      { "CustomModel": "Blocks/ChonbosMods/Pipes/Pipe_end.blockymodel" }

      // 3D-junction shapes exist as models but are NOT yet in the template (see below):
      // "tripod":   "Pipe_tripod.blockymodel"
      // "fourbent": "Pipe_fourbent.blockymodel"
      // "five":     "Pipe_five.blockymodel"
      // "six":      "Pipe_six.blockymodel"
    }
  },

  "ConnectedBlockRuleSet": {
    "Type": "CustomTemplate",
    "TemplateShapeAssetId": "CC_PowerConnectedBlockTemplate",
    "TemplateShapeBlockPatterns": {
      "node":     "CC_PowerCable",
      "straight": "*CC_PowerCable_State_Definitions_straight",
      "elbow":    "*CC_PowerCable_State_Definitions_elbow",
      "tee":      "*CC_PowerCable_State_Definitions_tee",
      "cross":    "*CC_PowerCable_State_Definitions_cross",
      "end":      "*CC_PowerCable_State_Definitions_end"
    }
  }
}
```

Per channel, swap the template id + (later) texture:

| Block | TemplateShapeAssetId |
|-------|----------------------|
| `CC_PowerCable` (power) | `CC_PowerConnectedBlockTemplate` |
| Fluid pipe  | `CC_FluidConnectedBlockTemplate` |
| Gas tube    | `CC_GasConnectedBlockTemplate` |
| Item transporter | `CC_ItemConnectedBlockTemplate` |

## Status / known gaps (need in-game iteration)

- **Models:** all 10 topologies built and validated (`Common/Blocks/ChonbosMods/Pipes/Pipe_*.blockymodel`).
- **Templates:** wire the **planar + simple** shapes only — `node, end, straight, elbow, tee, cross`.
  These use the proven Rails idiom (`IsCardinallyRotatable` = yaw only).
- **Deferred to in-game tuning:** vertical runs (Up/Down) and the true-3D junctions
  (`tripod, fourbent, five, six`) + vertical elbows. Vanilla exposes only yaw-rotation flags
  (`IsCardinallyRotatable/MirrorX/MirrorZ`); there is no full-6-way vanilla exemplar, so pitch/roll
  coverage must be validated against the live engine. The geometry is ready; only the template
  patterns remain. Best done once a block is placeable in devServer so swaps can be observed.
- **First in-game test:** wire `CC_PowerCable` per above, place a few in a line / L / + on the
  ground, confirm they swap to straight/elbow/tee/cross. Then extend patterns for vertical + 3D.
