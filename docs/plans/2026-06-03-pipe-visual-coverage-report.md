# Pipe Visual Coverage Report (engine connected-block transformations)

Date: 2026-06-03. Investigation deliverable: why pipe auto-connect visuals fail for some directions.
Ground truth: decompiled Server-0.5.3 (PatternRotationDefinition, CustomConnectedBlockPattern, CustomConnectedBlockTemplateAsset).

## TL;DR

The engine supports ONLY Y-axis cardinal rotation (IsCardinallyRotatable) plus MirrorX/MirrorZ on patterns: NO pitch/roll. So the canonical "10 rotationally-unique shapes" assumption is wrong: 20 of 64 neighbour combinations have no matching pattern, all of them vertical+horizontal mixes. straight_vertical was the first of this class; 11 more authored shapes are needed for full coverage.

## Missing combinations (20)

- Single horizontal + single vertical (8): {U,N} {U,S} {U,E} {U,W} {D,N} {D,S} {D,E} {D,W}
- Opposite horizontal pair + vertical (4): {U,N,S} {U,E,W} {D,N,S} {D,E,W}
- Three horizontals + vertical (8): {U + 3-of-NSEW} x4, {D + 3-of-NSEW} x4
- Both verticals + opposite pair (2): {U,D,N,S} {U,D,E,W}

Also missing: end pointing straight Up or Down ({U} alone, {D} alone) are part of category counts via vertical_end shapes below.

## Fix list

1. Remove the invalid "MaterialName" key from each CC_*ConnectedBlockTemplate.json (engine codec keys are: DontUpdateAfterInitialPlacement, ConnectsToOtherMaterials, DefaultShape, Shapes). It is silently ignored today (the boot WARN).
2. Author 12 new .blockymodel orientation variants (user's modeling pass):
   vertical_end_up, vertical_end_down, vertical_elbow_up, vertical_elbow_down,
   vertical_tee_up_ns, vertical_tee_up_ew, vertical_tee_down_ns, vertical_tee_down_ew,
   vertical_3horiz_up, vertical_3horiz_down, vertical_straight_ud_ns, vertical_straight_ud_ew
3. Add the 12 shape patterns to the template (concrete JSON below), the 12 State.Definitions entries, and the 12 TemplateShapeBlockPatterns entries on the pipe block.

## Engine transformation semantics

- IsCardinallyRotatable: 4 Y-rotations (cycles N->E->S->W); Up/Down unchanged.
- MirrorX: swaps E/W. MirrorZ: swaps N/S. Up/Down unchanged.
- rotationPitch/rotationRoll are never set by pattern transformations (CustomConnectedBlockPattern.getConnectedBlockTypeKey applies yaw only).

## Concrete pattern JSON (all 12 shapes)

```json
CONCRETE JSON PATTERN SPECIFICATIONS FOR ALL 12 NEW SHAPES
===========================================================

Shape 1: vertical_end_up
FaceTags: {Up: CC_PowerFace}
RulesToMatch:
  - Position(0,1,0) Include Down
  - Position(0,0,1) Exclude South
  - Position(0,0,-1) Exclude North
  - Position(1,0,0) Exclude West
  - Position(-1,0,0) Exclude East
  - Position(0,-1,0) Exclude Up

Shape 2: vertical_end_down
FaceTags: {Down: CC_PowerFace}
RulesToMatch:
  - Position(0,-1,0) Include Up
  - Position(0,0,1) Exclude South
  - Position(0,0,-1) Exclude North
  - Position(1,0,0) Exclude West
  - Position(-1,0,0) Exclude East
  - Position(0,1,0) Exclude Down

Shape 3: vertical_elbow_up (Cardinal rotation: 4 variants)
FaceTags: {North: CC_PowerFace, Up: CC_PowerFace}
AllowedPatternTransformations: {IsCardinallyRotatable: true}
RulesToMatch:
  - Position(0,0,1) Include South
  - Position(0,0,-1) Exclude North
  - Position(1,0,0) Exclude West
  - Position(-1,0,0) Exclude East
  - Position(0,1,0) Include Down
  - Position(0,-1,0) Exclude Up

Shape 4: vertical_elbow_down (Cardinal rotation: 4 variants)
FaceTags: {North: CC_PowerFace, Down: CC_PowerFace}
AllowedPatternTransformations: {IsCardinallyRotatable: true}
RulesToMatch:
  - Position(0,0,1) Include South
  - Position(0,0,-1) Exclude North
  - Position(1,0,0) Exclude West
  - Position(-1,0,0) Exclude East
  - Position(0,-1,0) Include Up
  - Position(0,1,0) Exclude Down

Shape 5: vertical_tee_up_ns
FaceTags: {North: CC_PowerFace, South: CC_PowerFace, Up: CC_PowerFace}
AllowedPatternTransformations: {} (no rotation)
RulesToMatch:
  - Position(0,0,1) Include South
  - Position(0,0,-1) Include North
  - Position(1,0,0) Exclude West
  - Position(-1,0,0) Exclude East
  - Position(0,1,0) Include Down
  - Position(0,-1,0) Exclude Up

Shape 6: vertical_tee_up_ew
FaceTags: {East: CC_PowerFace, West: CC_PowerFace, Up: CC_PowerFace}
AllowedPatternTransformations: {} (no rotation)
RulesToMatch:
  - Position(0,0,1) Exclude South
  - Position(0,0,-1) Exclude North
  - Position(1,0,0) Include West
  - Position(-1,0,0) Include East
  - Position(0,1,0) Include Down
  - Position(0,-1,0) Exclude Up

Shape 7: vertical_tee_down_ns
FaceTags: {North: CC_PowerFace, South: CC_PowerFace, Down: CC_PowerFace}
AllowedPatternTransformations: {} (no rotation)
RulesToMatch:
  - Position(0,0,1) Include South
  - Position(0,0,-1) Include North
  - Position(1,0,0) Exclude West
  - Position(-1,0,0) Exclude East
  - Position(0,-1,0) Include Up
  - Position(0,1,0) Exclude Down

Shape 8: vertical_tee_down_ew
FaceTags: {East: CC_PowerFace, West: CC_PowerFace, Down: CC_PowerFace}
AllowedPatternTransformations: {} (no rotation)
RulesToMatch:
  - Position(0,0,1) Exclude South
  - Position(0,0,-1) Exclude North
  - Position(1,0,0) Include West
  - Position(-1,0,0) Include East
  - Position(0,-1,0) Include Up
  - Position(0,1,0) Exclude Down

Shape 9: vertical_3horiz_up (Cardinal rotation + MirrorX: 8 variants)
FaceTags: {North: CC_PowerFace, South: CC_PowerFace, East: CC_PowerFace, Up: CC_PowerFace}
AllowedPatternTransformations: {IsCardinallyRotatable: true, MirrorX: true}
RulesToMatch:
  - Position(0,0,1) Include South
  - Position(0,0,-1) Include North
  - Position(1,0,0) Include West
  - Position(-1,0,0) Exclude East
  - Position(0,1,0) Include Down
  - Position(0,-1,0) Exclude Up
Generates: {N,S,E,U}, {E,W,S,U}, {S,N,W,U}, {W,N,E,U}, {N,S,W,U}, {E,N,W,U}, {S,E,W,U}, {W,S,N,U}

Shape 10: vertical_3horiz_down (Cardinal rotation + MirrorX: 8 variants)
FaceTags: {North: CC_PowerFace, South: CC_PowerFace, East: CC_PowerFace, Down: CC_PowerFace}
AllowedPatternTransformations: {IsCardinallyRotatable: true, MirrorX: true}
RulesToMatch:
  - Position(0,0,1) Include South
  - Position(0,0,-1) Include North
  - Position(1,0,0) Include West
  - Position(-1,0,0) Exclude East
  - Position(0,-1,0) Include Up
  - Position(0,1,0) Exclude Down
Generates: {N,S,E,D}, {E,W,S,D}, {S,N,W,D}, {W,N,E,D}, {N,S,W,D}, {E,N,W,D}, {S,E,W,D}, {W,S,N,D}

Shape 11: vertical_straight_ud_ns
FaceTags: {North: CC_PowerFace, South: CC_PowerFace, Up: CC_PowerFace, Down: CC_PowerFace}
AllowedPatternTransformations: {} (no rotation)
RulesToMatch:
  - Position(0,0,1) Include South
  - Position(0,0,-1) Include North
  - Position(1,0,0) Exclude West
  - Position(-1,0,0) Exclude East
  - Position(0,1,0) Include Down
  - Position(0,-1,0) Include Up
Covers: {U,D,N,S}

Shape 12: vertical_straight_ud_ew
FaceTags: {East: CC_PowerFace, West: CC_PowerFace, Up: CC_PowerFace, Down: CC_PowerFace}
AllowedPatternTransformations: {} (no rotation)
RulesToMatch:
  - Position(0,0,1) Exclude South
  - Position(0,0,-1) Exclude North
  - Position(1,0,0) Include West
  - Position(-1,0,0) Include East
  - Position(0,1,0) Include Down
  - Position(0,-1,0) Include Up
Covers: {U,D,E,W}

SUMMARY OF COVERAGE:
====================
Shape 1-2: 2 combinations (U only, D only with no horizontals) - actually already covered by end if rotatable
Shape 3-4: 8 combinations (vertical elbows: 1 horiz + 1 vertical)
Shape 5-8: 4 combinations (vertical tees: 2 opposite horiz + 1 vertical)
Shape 9-10: 16 combinations (vertical 3-of-4: 3 horiz + 1 vertical)
Shape 11-12: 2 combinations (vertical straight: opposite pair + both verticals)
Total: 32 new combinations covering all 20 gaps

BLOCKYMODEL FILES NEEDED:
=========================
1. Pipe_vertical_end_up.blockymodel
2. Pipe_vertical_end_down.blockymodel
3. Pipe_vertical_elbow_up.blockymodel (cardinal rotates to all 4 horizontal directions)
4. Pipe_vertical_elbow_down.blockymodel (cardinal rotates to all 4 horizontal directions)
5. Pipe_vertical_tee_up_ns.blockymodel (North-South-Up)
6. Pipe_vertical_tee_up_ew.blockymodel (East-West-Up)
7. Pipe_vertical_tee_down_ns.blockymodel (North-South-Down)
8. Pipe_vertical_tee_down_ew.blockymodel (East-West-Down)
9. Pipe_vertical_3horiz_up.blockymodel (cardinal + mirror rotates)
10. Pipe_vertical_3horiz_down.blockymodel (cardinal + mirror rotates)
11. Pipe_vertical_straight_ud_ns.blockymodel (North-South-Up-Down)
12. Pipe_vertical_straight_ud_ew.blockymodel (East-West-Up-Down)

Total: 12 files (or potentially fewer if some can share models via clever mirroring)
```
