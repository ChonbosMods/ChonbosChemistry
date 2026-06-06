# Per-Face Pipe Flow States + I/O Wrench — Design

*Status: validated 2026-06-05. Triggered by an in-game find during gas testing: two adjacent pipes carrying different gases (equally: fluids) currently weld into one network. Mekanism-style per-face connection control fixes that and brings the I/O wrench forward.*

## Decisions (user-validated)

- **`FlowState` enum: `NORMAL` / `PUSH` / `PULL` / `NONE`** on every pipe face.
- **Auto-NONE rule:** two adjacent same-channel pipes whose persisted `resourceId`s are both non-null and different do NOT connect: `NORMAL` dynamically resolves to no-connection. Not stored; re-evaluated on topology events, so draining one line lets the next event merge them.
- **PUSH/PULL are endpoint semantics** (pipe↔machine faces). Pipe↔pipe faces only meaningfully cycle `NORMAL→NONE` (push/pull in data on a pipe-pipe face is treated as NORMAL).
- **Visual indicators on end-stub arms only** (combinatorial explosion otherwise: one model per state, 4^6 per-arm combos). 6 new models per pipe family + `_on` twin states. Suppressed arms (NONE/mismatch) need no new art: render the lesser topology shape.
- **Wrench cycles:** pipe face `NORMAL→PUSH→PULL→NONE` (sneak reverses, if the engine exposes sneak); machine face cycles its single port through the machine's capability pairs `(channel × input/output) → closed`.
- **No multi-port faces (design revision):** machines are ≥4×4 multiblocks: faces are plentiful, each face carries exactly one port. Supersedes §5.4's "multi-port sides" language in the main design doc.
- **Pipes drop plain** (no config carry on break): intended, matches Mekanism.

## 1. Data model & semantics

- `api/io/FlowState`: `NORMAL`, `PUSH`, `PULL`, `NONE` (+ codec).
- `PipeNode` gains `FlowState[6]`, persisted as an OPTIONAL codec key: absent = all-NORMAL, so existing placed pipes decode unchanged (no migration).
- **Face indexing locks to the `OFFSETS` convention** (`+X,-X,+Y,-Y,+Z,-Z`) everywhere: PipeNode faces, Port.Face, wrench clicked-face, endpoint collection. This graduates the deferred "real block-face-index alignment" item to required.
- **Pipe↔pipe connection test** (per face pair, same channel): either face NONE → no; otherwise connect iff substances compatible (either `resourceId` null, or equal).
- **Pipe↔machine filter:** the machine's port says what it OFFERS; the pipe face says what the network ACCEPTS through it. `NORMAL` = port's own direction; `PUSH` = acceptor-half only; `PULL` = provider-half only; `NONE` = invisible.

## 2. Network discovery, endpoints & persistence

- `NetworkManager` BFS crosses a face only if the connection test passes (flow states + substance compat from persisted shares: restart-safe).
- **Drain-remerge:** `NetworkTickSystem` write-back detects a network's lock clearing (resourceId non-null → null) and invalidates its boundary so separated lines merge on next access.
- `NetworkEndpoints.collect` becomes face-precise: (1) qualify the machine by the port ON the face touching the pipe; (2) apply the pipe face's flow-state filter. Storage pairs into a `StorageEndpoint` (balancing) only with full two-way access; a PULL-reached tank joins as provider only, PUSH-reached as acceptor only.
- Wrench writes are topology events: invalidate (with the H8 snapshot guard), visuals recompute.
- Flow states persist in PipeNode's codec; machine port changes persist via `PortConfig`. Machines/tanks carry config via BlockHolder automatically; pipes drop plain (intended).

## 3. Visuals

- Engine patterns can't see flow state → pipes with any suppressed/special arm get **programmatic shape states** (the proven H8 `setBlockInteractionState` path); all-NORMAL unmismatched pipes keep riding the pattern system for free.
- Suppressed arm = render the lesser topology (tee→straight, straight→end): all 26 shapes exist.
- New art (art track): `<Pipe>_end_push`, `<Pipe>_end_pull`, `<Pipe>_vertical_end_up_push/_pull`, `<Pipe>_vertical_end_down_push/_pull` per family (+`_on` twin states, same model + on texture: +12 states per pipe item).
- Pipe panel gains a per-face state row (e.g. `Faces: N· E push · W pull · S none`): mid-run configs always inspectable.

## 4. Wrench

- `CC_Wrench` item (placeholder icon), `SimpleBlockInteraction` glue (§16.5, HyProTech CableSideToolInteraction precedent).
- **Spike first:** confirm `InteractionContext` exposes clicked face (else derive from hit normal/eye ray) and sneak.
- Pipe face: cycle + persist + invalidate + chat feedback (`Pipe face East: PUSH`).
- Machine face: cycle capability pairs derived from buffers (energy → power, resource buffers → their channels) + `closed`; feedback names channel+direction.

## Main-doc edits

1. §5.4: remove multi-port-faces; one port per face; multiblock machines provide face abundance.
2. §13: add flow-state subsection (filter semantics, auto-NONE).
3. §0: update deferred list (face-index alignment now required/in-progress).

## Testing

Headless TDD: FlowState/PipeNode codec round-trips (absent-key default); `connects()` truth table; BFS split/merge incl. drain-remerge; endpoint filtering per face state incl. storage pairing; wrench cycle as a pure function. Engine glue (clicked face, sneak, visuals) verifies in-game.
