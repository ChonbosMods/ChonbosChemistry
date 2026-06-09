> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# Wire Face-Travel & Visual-Connection Layer — Design

*Status: brainstormed + user-validated 2026-06-08. Scope is the GEOMETRY/STATE/RENDERING contract for a redstone-style "dust" wire that travels across Hytale block faces. Signal semantics (strength/boolean/propagation, components, logic) are explicitly OUT of scope and owned by separate agents working the rules; this doc fixes the visual + state contract they build on. Reuses the pipe per-face machinery (`FlowState[6]`, `rotationIndex`, `gen_pipe_tips.py` composite-merge) wholesale.*

## Capability finding (why this is buildable at all)

Hytale supports thin per-face geometry on ALL SIX faces of a block (top, sides, bottom), not just the top. The existing pipe system already proves it: pipes carry per-face flow state and render generated composite tip "collar" geometry on arbitrary faces. The redstone-dust analogue is the same primitive: a block that hugs faces, selected programmatically from per-face data.

The one approach that does NOT work (spiked DEAD in 0.5.3, per `2026-06-07-tip-rendering-design.md`): a separate **entity** per face/indicator. The client raycasts any networked model entity, so an overlay entity steals the cursor and breaks Interact/break on the host block. **Wires are blocks, never entities.**

## Decisions (user-validated)

1. **Occupancy: sculk-vein / glow-lichen model.** A wire is a single block type (`DrawType: Model`) occupying one cell, rendered as thin skins hugging host faces. It is logically dependent on a supporting surface (needs a face to mount on; pops when the host is removed). Voxels are full-cell in Hytale, so even a "surface layer" is its own cell-occupying block — it just renders as a thin decal and depends on the host.

2. **Multi-face per cell.** One wire block stores a 6-face presence set; each present face is an independent dust skin. The floor, a wall, and the ceiling of the same cell can all carry dust in ONE block. Placing dust on a second face of an occupied cell sets another bit on the EXISTING block rather than spawning a new one. Faces use the canonical `+X,−X,+Y,−Y,+Z,−Z` order, matching `PipeNode`.

3. **Per-face arm descriptor.** Each present face has 4 arms (toward its 4 edges, fixed winding). Each arm ∈ `{none, coplanar, outside-wrap, inside-wrap}`. The face "shape" (dot / end / line / elbow / T / cross) is emergent from how many arms are non-`none` — it is NOT a separate axis.

4. **Connection topology — all three, auto-derived from neighbors (MC-style, no player config):**
   - **Coplanar straight run** — arm joins a skin on the SAME face of the adjacent cell.
   - **Outside-corner wrap** — arm wraps a convex edge to the perpendicular face (dust climbing down the side of the block below: the classic redstone visual).
   - **Inside-corner wrap** — arm bends into a concave edge to the perpendicular face (floor dust turning up a wall it butts against).

5. **Colored lanes — color-keyed connectivity (Project Red "insulated wire" idiom).** Up to *C* colored wires share a face as **parallel lanes** (ribbon-cable stripes, offset perpendicular to the run direction). Same color connects; different colors **cross without forming a junction**. This is the feature that lets multiple signals converge through one space without conflict.

6. **Turns accept visual overlap.** A lane's offset axis is perpendicular to its run direction, so a color that turns a corner must migrate across the face and crosses the other lanes. Straight buses render as clean stripes; at a turn the color bends toward its target edge, visually passing over the other lanes. Color-keyed logic keeps the signals separate, so this is functionally clean — only cosmetically busy at corners. No woven over/under geometry.

7. **Visual ceiling ~3 colors, logic works at any *C*.** 2–3 tinted lanes read cleanly side-by-side; 4 gets cramped and hard to trace. The connectivity logic is color-count-agnostic; the cap is purely readability. Lane assignment is deterministic by color (e.g. red→lane 0) so lanes line up cell-to-cell.

## Why the model count stays small (three independent compositing axes)

The escape hatch is that the wire's three combinatorial axes are all **independent** and therefore **composited, never combined**:

- **Faces are independent** — a floor skin does not reshape a wall skin (unlike pipe arms, which weld and force the ~604 baked whole-block composites per family). The renderer looks up 6 face-skins separately.
- **Colors/lanes are independent** — each colored lane is the same geometry at a runtime offset + tint.
- A planar dust-skin is the same shape on any face — a rigid transform orients the one library to all 6 faces.

So the baked library is **one face's worth**, reused across all faces and colors. Nothing multiplies; only per-block STATE grows (cheap codec data).

## State-count math (the maintained library)

Per face, the skin is fully described by 4 arms × 4 states each:

- **4⁴ = 256** arm-combinations if every rotation is baked.
- **70** rotation-distinct pieces under the face's 4-fold symmetry (Burnside: `(4⁴ + 4¹ + 4² + 4¹)/4 = 280/4 = 70`), if authored as rotation-base + spun via the existing `rotationIndex` machinery.

That 256/70 library is shared across all 6 faces (orient) and all colors (offset + tint). **Colors and faces do NOT multiply it.** Total ≈ one pipe family's worth, generated by the same `gen_pipe_tips.py`-style script — not exponentially worse than the pipes.

Knobs that move the count (if wrap flavors are trimmed):

| Per-arm states | Meaning | Per face (baked) | Rotation classes |
|---|---|---|---|
| 4 | coplanar + outside-wrap + inside-wrap | **256** | 70 |
| 3 | coplanar + one wrap flavor | 81 | 24 |
| 2 | coplanar only (dot/end/line/elbow/T/cross) | 16 | 6 |

Per-block STATE grows with colors (each present face stores up to *C* colored arm-descriptors instead of one), but that is codec data, not models — an extension of the `FlowState[6]` → `FaceSkin[6]` pattern.

## State model (extends the pipe codec pattern)

Where `PipeNode` carries `FlowState[6]`, the wire carries `FaceSkin[6]`:

- `FaceSkin` = `present: bool` + up to *C* colored lanes.
- each lane = `color` + `arm[4] ∈ {none, coplanar, outsideWrap, insideWrap}`.
- Empty faces / lanes encode to nothing: optional codec keys are OMITTED when absent (the omit-default trick `PipeNode` uses for all-NORMAL), keeping world saves byte-identical for simple wires.

## Generation approach

Same merge-and-composite pipeline as `scripts/gen_pipe_tips.py`:

1. Author the small set of base arm pieces (a straight arm, an outside-wrap, an inside-wrap; the dot center).
2. Generate the 256 (or 70 + rotation) per-face library by enumerating the 4-arm combinations.
3. At render time, composite the present faces × present lanes for a block: look up each lane's per-face piece, orient to its face, translate to its lane offset, tint to its color.

Naming follows the existing PascalCase convention; the generator and the runtime selector derive the same key independently (as `PipeShapes` and the generator already do).

## Boundary / out of scope

- **Signal semantics** (analog 0–15 vs boolean, decay, propagation, recompute) — other agents.
- **Components** (sources, repeaters, comparators, gates, lamps) — other agents.
- **Network discovery / tick driver** — to be decided with the signal-rules work; this layer only fixes the geometry + per-face/per-color state contract those rules read and write.

## Open items (deferred, not blocking the contract)

- Exact lane offsets + tint palette (visual tuning) — settle during asset authoring.
- Whether the inside/outside wraps need a mirrored variant for handedness — resolve when the generator is written (engine orient/reflect likely covers it).
- UV polish on yaw-rotated wrap pieces may inherit the same authored-ground-truth fix path noted in `2026-06-07-tip-rendering-design.md` (author one east-oriented reference, derive the rest by 180° mirror rather than 90° UV math).
