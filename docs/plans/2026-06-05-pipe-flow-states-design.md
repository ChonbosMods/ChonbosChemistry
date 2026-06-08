# Per-Face Pipe Flow States + I/O Wrench — Design

*Status: validated 2026-06-05. Triggered by an in-game find during gas testing: two adjacent pipes carrying different gases (equally: fluids) currently weld into one network. Mekanism-style per-face connection control fixes that and brings the I/O wrench forward.*

## Decisions (user-validated)

- **`FlowState` enum: `NORMAL` / `PUSH` / `PULL` / `NONE`** on every pipe face.
- **Auto-NONE rule:** two adjacent same-channel pipes whose persisted `resourceId`s are both non-null and different do NOT connect: `NORMAL` dynamically resolves to no-connection. Not stored; re-evaluated on topology events, so draining one line lets the next event merge them.
- **PUSH/PULL are endpoint semantics** (pipe↔machine faces). Pipe↔pipe faces only meaningfully cycle `NORMAL→NONE` (push/pull in data on a pipe-pipe face is treated as NORMAL).
- **Visual indicators on end-stub arms only** (combinatorial explosion otherwise: one model per state, 4^6 per-arm combos). 6 new models per pipe family + `_on` twin states. Suppressed arms (NONE/mismatch) need no new art: render the lesser topology shape.
- **Wrench cycles:** pipe face `NORMAL→PUSH→PULL→NONE` (sneak reverses, if the engine exposes sneak); machine face cycles its single port through the machine's capability pairs `(channel × input/output) → closed`.
- **Bare pipe ends are pre-configurable (revision 2026-06-07):** the full `NORMAL→PUSH→PULL→NONE` ring is offered on ANY face that does NOT touch another pipe: an endpoint, a solid block, OR empty air. Only a pipe↔pipe face is held to the 2-state `NORMAL↔NONE` ring. A player can thus wrench a bare end to PUSH/PULL and the config persists in `PipeNode`'s codec; the existing `PlaceBlockEvent` invalidation activates the arm + tip automatically when a chest/machine is later placed there. Before then the only feedback is the panel "Faces:" row (a disconnected end has no arm to draw a tip on: showing one would need the deferred tip-rollout art). Supersedes the earlier "PUSH/PULL toward air falls into the pipe ring" behaviour; the target rule is now `targetForNeighbour(neighbourIsPipe)` in `WrenchCycles`. The 2-PUSH/PULL-face budget still applies (pre-configured ends count against it).
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

## Spike findings (Task 8)

Engine spike against Server 0.5.3 (decompiled at `/tmp/bh-spike` with CFR 0.152). Decompile-verified unless noted. Task 9's implementer can build directly from this.

### Path corrections (plan's stated paths were wrong)
- `InteractionContext` lives at `com/hypixel/hytale/server/core/entity/InteractionContext.class` (NOT `.../modules/interaction/`).
- `SimpleBlockInteraction` (the abstract base you extend) is at `com/hypixel/hytale/server/core/modules/interaction/interaction/config/client/SimpleBlockInteraction.class`. (There is also an unrelated `com/hypixel/hytale/protocol/SimpleBlockInteraction` packet class: do NOT extend that.)
- HyProTech is at `/home/keroppi/Development/Hytale/ReferenceJars/HyProTech` (outside this repo). The side tool is `src/main/java/com/example/plugin/interaction/CableSideToolInteraction.java`.

### Clicked face: accessors (CONFIRMED)
The clicked face is carried on `InteractionSyncData`, obtained from the context:
- `InteractionContext.getState()` → `@Nonnull InteractionSyncData` (server/working state).
- `InteractionContext.getClientState()` → `@Nullable InteractionSyncData` (client-reported state).
- `InteractionContext.getServerState()` → `@Nonnull InteractionSyncData`.

On `com.hypixel.hytale.protocol.InteractionSyncData` (public fields, direct access):
- `public BlockFace blockFace = BlockFace.None;` — **the primary face accessor.**
- `public org.joml.Vector3fc raycastNormal;` — fallback hit normal (nullable). Note: joml `Vector3fc`, so use `.x()/.y()/.z()` methods, not `.x` fields.
- Also present and useful: `public BlockPosition blockPosition;`, `public org.joml.Position raycastHit;`, `public float raycastDistance;`, `public BlockRotation blockRotation;`, `public int placedBlockId;`.

`com.hypixel.hytale.protocol.BlockFace` is an enum: `None(0), Up(1), Down(2), North(3), South(4), East(5), West(6)`, with `getValue()` / `fromValue(int)` / `VALUES[]`.

**Mechanism HyProTech uses (exact):** `resolveSide` reads `context.getState()` first, then falls back to `context.getClientState()`. For each `InteractionSyncData` it tries `state.blockFace` (mapped East/West/Up/Down/South/North → side enum, ignoring `None`), and only if that yields nothing falls back to deriving the side from `state.raycastNormal` by largest-abs-component + sign. No ray math or rotation-index of its own: it just reads the engine-provided `blockFace`. (Caveat: HyProTech's `resolveSideFromNormal` is typed to take `com.hypixel.hytale.protocol.Vector3f` while the field is `org.joml.Vector3fc` — a source/decompile mismatch. Treat `blockFace` as the reliable path and only add a normal fallback if you reconcile the joml type yourself.)

**Critical wiring requirement:** `blockFace` is only populated when the interaction opts into the client's latest target. `SimpleBlockInteraction` exposes a codec field `UseLatestTarget` (boolean). HyProTech's item JSON sets `"UseLatestTarget": true` so the client-reported target+face flow through. Without it, `blockFace` stays `None`. (In simulate path, `simulateTick0` hardcodes `context.getState().blockFace = BlockFace.Up` — so the real face is a server-tick / client-state phenomenon, which is why server-side `interactWithBlock` is where you read it.)

### `SimpleBlockInteraction.interactWithBlock` signature (CONFIRMED, 0.5.3)
```java
protected abstract void interactWithBlock(
    @Nonnull  World world,
    @Nonnull  CommandBuffer<EntityStore> commandBuffer,
    @Nonnull  InteractionType type,
    @Nonnull  InteractionContext context,
    @Nullable ItemStack itemStack,
    @Nonnull  org.joml.Vector3i blockPos,   // NOTE: org.joml.Vector3i, not the math.vector one
    @Nonnull  CooldownHandler cooldownHandler);
```
Also abstract (must implement, can be a no-op for server-only tools):
```java
protected abstract void simulateInteractWithBlock(
    @Nonnull InteractionType type, @Nonnull InteractionContext context,
    @Nullable ItemStack itemStack, @Nonnull World world, @Nonnull org.joml.Vector3i blockPos);
```
The base's `tick0` does the distance check (`InteractionValidation.canPlayerInteractWithBlock`), resolves the target block (honoring `UseLatestTarget`), rejects air/blockId 0|1, then calls `interactWithBlock`. `getWaitForDataFrom()` returns `Client`; `needsRemoteSync()` returns `true`. Your subclass needs a `BuilderCodec` chained from `SimpleBlockInteraction.CODEC` (which contributes the `UseLatestTarget` key) and a public no-arg constructor calling `super()` (HyProTech pattern).

### Sneak / crouch: AVAILABLE (not via InteractionContext directly)
`InteractionContext` has **no** sneak/crouch/movement accessor. BUT a `SimpleBlockInteraction` handler can read it off the player entity:
- `context.getEntity()` → `Ref<EntityStore>` (the player).
- `commandBuffer.getComponent(ref, MovementStatesComponent.getComponentType())` → `MovementStatesComponent` (`com/hypixel/hytale/server/core/entity/movement/MovementStatesComponent`).
- `MovementStatesComponent.getMovementStates()` → `com.hypixel.hytale.protocol.MovementStates`.
- `MovementStates.crouching` (public boolean) — **this is the sneak/crouch flag.** Also `forcedCrouching` (public boolean) if you want to count forced crouch.

So: **YES, a SimpleBlockInteraction handler can know the player is sneaking** via `MovementStatesComponent → MovementStates.crouching`. Caveat for Task 9: this is the player's crouch state at the server tick the interaction runs, not necessarily latched to the exact click instant; verify in-game that it reflects "sneaking while clicking" reliably. If it proves flaky, the fallback is two items (a forward-cycle wrench and a reverse-cycle wrench) instead of sneak-to-reverse.

### Item JSON + registration shape for a custom interaction (CONFIRMED)
The wrench is an item. Two pieces:

1. **Plugin registers the interaction class** against the `Interaction.CODEC` codec registry, keyed by a string ID (from `Machinarium.java`):
```java
getCodecRegistry(Interaction.CODEC).register(
    "machinarium_toggle_cable_side",          // string id used as JSON "Type"
    CableSideToolInteraction.class,
    CableSideToolInteraction.CODEC);
```
(`Interaction.CODEC` = `com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction`.)

2. **Item JSON** (`Server/Item/Items/Machinarium_Cable_Tool.json`) wires an interaction *type* (e.g. `Secondary`) to the registered id via its `Type`:
```json
{
  "Parent": "Tool_Hammer_Crude",
  "Interactions": {
    "Primary": "Block_Primary",
    "Secondary": {
      "Interactions": [
        { "Type": "machinarium_toggle_cable_side", "UseLatestTarget": true }
      ]
    }
  }
}
```
Key points: the JSON `"Type"` value is the **string id from the codec registry**, not a class name. `UseLatestTarget: true` is the `SimpleBlockInteraction` codec field (face/target wiring). `InteractionType` enum has `Primary(0)`, `Secondary(1)`, `Use(5)` — for an item-held wrench HyProTech uses `Secondary` (the press-F / use-style action; pick whichever maps to the intended input). This is the item-interaction path, distinct from our existing block-side `"Use": { "Interactions": [{ "Type": "OpenCustomUI", ... }] }` on BlockTypes (e.g. `CC_PowerCable.json`).

### Surprises affecting Task 9's design
- **`UseLatestTarget: true` is mandatory** to get a real `blockFace`; omit it and every click reports `BlockFace.None`. This is the single biggest gotcha.
- **`blockPos` is `org.joml.Vector3i`** in the handler signature (HyProTech immediately copies into `com.hypixel.hytale.math.vector.Vector3i` for `ChunkUtil`). Don't assume the math-package type.
- **Sneak is reachable but indirect** (player component, not context) and tick-timed: plan a fallback (separate forward/reverse items) in case crouch-at-click proves unreliable.
- **Block component edits + persistence:** HyProTech reads/writes block-attached components via `world.getChunkStore().getChunkComponent(chunkIndex, BlockComponentChunk.getComponentType())`, indexes with `ChunkUtil.indexBlockInColumn(localX, y, localZ)`, mutates, then `blockComponents.markNeedsSaving()`. This is the precedent for persisting per-face flow state on our `PipeNode` block components.
- **Two separate `SimpleBlockInteraction` classes exist** (protocol packet vs config base): extend the config one (`.../interaction/config/client/SimpleBlockInteraction`).
- Nothing was undiscoverable by decompile; the only soft spot is the runtime reliability of crouch-at-click, which is an in-game verification item for Task 9, not a decompile gap.
