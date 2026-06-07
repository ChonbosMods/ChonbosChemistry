# ITEM Pipe Channel (§13.4) — Design

*Status: validated 2026-06-06. Discrete item transport, Mekanism-logistical-transporter-faithful, against vanilla storage containers of any kind. Builds directly on the per-face flow states (`docs/plans/2026-06-05-pipe-flow-states-design.md`); executes stacked on `feat/pipe-flow-states` so both features verify in one in-game pass (user decision).*

## Decisions (user-validated)

- **Face rules on ITEM faces** (containers are passive; extraction is always explicit):
  - `NORMAL`: deliver-into allowed + accepts machine ejections (future); never auto-extracts.
  - `PULL`: auto-extracts from the container at a rate; deliveries blocked through this face.
  - `PUSH`: insert-only (deliveries allowed, nothing ever comes out).
  - `NONE`: invisible.
- **Routing: nearest-first** (shortest pipe path wins). Round-robin/priority are later per-face options (§13.4 reserves them).
- **Stranded ladder:** re-route if possible → else return toward origin → origin gone/full → **pop out as a ground drop** at the stack's current pipe. Breaking a pipe with a stack inside drops it. Never voids, never idles.
- **v1 endpoints: vanilla containers only** (any block entity with an engine `ItemContainer`). Machine ITEM ports join at the machines milestone (their `ResourceBuffer` lacks ItemStack identity: wrong shape to bridge now).
- **Filter stub (user request):** an `ItemFilter` seam checked at extraction, destination qualification, AND every junction hop during pathfinding; v1 = allow-all. The later tag/type filters at pipe intersections only implement the lookup: no routing rewrite.
- **No extraction without a destination:** a PULL face extracts only after the network confirms a valid, admitting, accepting destination (Mekanism sorter rule).
- **NO `_On` state for item pipes (user decision):** single texture, off is the default. `itempipe_on.png` deleted; the visual driver passes `energized=false` for ITEM networks; `CC_ItemPipe.json` ships 32 states (26 topology + 6 indicators, no twins, no bare `On`).

## Architecture

**Pure (headless-TDD):**
- `ContainerLookup` seam (pure interface; world impl wraps the engine `ItemContainer` access, §16.6 primitives `getContainerState`/`moveItemStackFromSlot`/`canAddItemStackToSlot`).
- `ItemEndpoints`: OFFSETS walk over member pipes collecting `(containerPos, viaPipe, faceState)` endpoints; qualification = the pipe face's flow state alone (containers have no ports).
- `ItemPathfinder`: BFS shortest path entry-pipe → destination over member pipes, edges gated by `PipeConnectivity.connects` AND the filter seam (a rejecting junction is impassable for that stack); returns nearest-first candidates.
- `ItemFilter`/`FilterLookup`: the stub. `admits(stack, pipeKey, viaFace)`; v1 `AllowAll`.
- `TravelingStack`: `(ItemStack bson, path, segmentIndex, progressTicks, originKey, destKey)`.
- Re-route ladder as a pure state machine (advance/arrive/re-route/return/pop-out decisions).
- Extraction eligibility: PULL scan, first extractable + admitted stack, destination confirmation, per-pull cap, network-saturation backpressure.

**State & persistence:** traveling stacks ride a new OPTIONAL `PipeNode` codec key (`InTransit`; absent = none: same no-migration trick as `FlowStates`). A stack is owned by exactly the segment it occupies: chunk save/load and the H8 wipe-snapshot system extend naturally (snapshot-worthiness widens to pipes with in-transit stacks).

**Tick driver:** new `ItemTransferSystem` (per-network dedup like `NetworkTickSystem`): phase 1 advance/arrive/re-route, phase 2 PULL extractions. `NetworkTransfer.distribute` skips ITEM networks (separate subsystem; shares only discovery/caching per §13.4).

## Visuals, wrench, panel

- **Chest stubbing:** vanilla chests advertise no `CC_ItemFace`, so the engine never welds arms toward them. `PipeVisualStates.effectiveMask` gains container awareness (the `ContainerLookup` seam, face-state gated) while `physicalMask` stays tag-based: **effective > physical** drives the same programmatic state swap that suppression uses, just adding arms instead of dropping them.
- **Indicators:** PULL/PUSH single-arm ends render the landed `ItemPipe_*_push/_pull` models (6 indicator states, no `_On`).
- **Wrench:** target resolution gains the container check (a chest is a MACHINE-style 4-cycle target).
- **Panel:** ITEM networks have no shared buffer: the gauge row becomes `In transit: N stacks • Pipes: M`, destinations count on row 2, faces row unchanged.

## Assets

Regen `CC_ItemConnectedBlockTemplate` from the power template (`CC_ItemFace`, 26 + 6 indicator shapes, indicators pattern-less, NO on-twins), add `CC_ItemMachineTemplate`, author `CC_ItemPipe.json` (32 states) against the landed `ItemPipe_*` v2 models, delete `itempipe_on.png`. Test rig: vanilla chests + `CC_ItemPipe` + `CC_Wrench`: nothing new needed.

## `[TUNE]` placeholders

Pull interval ~20 ticks; per-pull cap ~16 items; travel ~5 ticks/segment; max in-transit per network = segment count (saturation also blocks extraction).

## Testing

Pure: pathfinder (shortest, filter-gated, NONE-respect, nearest order, mid-path re-route), endpoints qualification, re-route ladder, TravelingStack codec + absent default, extraction eligibility, mask container-awareness (both divergence directions). Engine glue (real containers, ground drops, wrench-on-chest) verifies in-game together with the flow-states checklist.

## Spike findings (Task 7)

*Decompiled from Server 0.5.3 (`/tmp/bh-spike`, CFR + javap) on 2026-06-06. The §16.6 container claims came from HyProTech's source, which targets an OLDER engine: they are STALE for 0.5.3 and are superseded below.*

### Container access path: REFACTORED since HyProTech

HyProTech's `MachineItemAccess` reaches containers through `meta/state/ItemContainerState` + `ItemContainerBlockState` + `BlockStateModule`. **None of those classes exist in 0.5.3.** The container state moved to a plain `ChunkStore` block-entity COMPONENT, exactly the shape our `PipeNode`/`MachineBlockState` already use:

- `com.hypixel.hytale.server.core.modules.block.components.ItemContainerBlock` `implements Component<ChunkStore>`.
  - `public static ComponentType<ChunkStore, ItemContainerBlock> getComponentType()` (also `BlockModule.get().getItemContainerBlockComponentType()`).
  - `@Nonnull SimpleItemContainer getItemContainer()` — **lazily allocates** `new SimpleItemContainer(capacity)` on first call, so it is never null once the component is present.
  - `short getCapacity()`, `String getDroplist()`.
- Resolution seam (same as `WorldPipeGridView`/`WorldMachineLookup`): `Ref<ChunkStore> ref = BlockModule.getBlockEntity(world, x, y, z)` then `store.getComponent(ref, ItemContainerBlock.getComponentType())`. A null ref / null component == "no container here". This is how we detect "this block has a container" from a world position: **component presence**, no block-state interface needed.

### `ItemContainer` (engine 0.5.3) — the real insert/extract/read surface

`com.hypixel.hytale.server.core.inventory.container.ItemContainer` (super of `SimpleItemContainer`). Verified methods:

- **Read:** `short getCapacity()`; `ItemStack getItemStack(short slot)`.
- **Insert (whole-container, first-available, auto-stacks):** `ItemStackTransaction addItemStack(ItemStack stack, boolean allOrNothing, boolean fullStacks, boolean filter)`. Returns a transaction with `getQuery()` (requested) and `getRemainder()` (what did not fit) ⇒ **accepted = query.qty − remainder.qty**. There is **NO simulate/dry-run flag** on `addItemStack`.
- **Insert dry-run (boolean only):** `boolean canAddItemStack(ItemStack, boolean fullStacks, boolean filter)` returns whether the WHOLE stack would fit; it does NOT report a partial count. The engine's partial-count test helpers (`InternalContainerUtilItemStack.testAddToEmptySlots/testAddToExistingItemStacks`) are `protected` ⇒ **not callable from our package.**
- **Extract a slot:** `ItemStackSlotTransaction removeItemStackFromSlot(short slot, int quantityToRemove, boolean allOrNothing, boolean filter)`. `getOutput()` is the removed stack **with its metadata**. We pass `allOrNothing=false` (take what's there up to the cap) and `filter=false` (pipe extraction is gated by OUR filter layer, not the container's slot filters).
- Per-slot insert also exists (`canAddItemStackToSlot(slot, stack, allOrNothing, filter)` / `addItemStackToSlot(slot, stack, allOrNothing, filter)`) but the whole-container `addItemStack` is the right grain for "deliver into a chest".

### `ItemStack` (engine 0.5.3) accessors/ctors — confirmed

`new ItemStack(String id, int qty, BsonDocument metadata)`, `new ItemStack(id, qty)`, `new ItemStack(id)`; `String getItemId()`, `int getQuantity()`, `BsonDocument getMetadata()`, `boolean isEmpty()` + static `ItemStack.isEmpty(stack)`, `ItemStack withMetadata(BsonDocument)`, `Item getItem()`; static `boolean isSameItemType(a, b)`. `Item.getMaxStack()` gives the per-item stack ceiling.

### Honest-simulate decision for `WorldContainerLookup`

Because `addItemStack` has no dry-run that yields a partial count, **simulate insert is computed by a read-only slot scan** using only public accessors (`getCapacity`, `getItemStack(slot)`, `isSameItemType`, `Item.getMaxStack`): sum the room in same-type non-full slots plus empty-slot capacity, capped at the requested amount. The **commit** path uses `addItemStack(...,false,false,false)` and reads `accepted = query − remainder`, so any divergence between the scan estimate and the real engine fit is reconciled at commit time — and the pure layer already trusts the actual returned amount (T2 staleness contract). Simulate never mutates.

> **Correction (2026-06-06 CRITICAL review fix): the insert seam carries metadata.** The `ContainerView.insert` signature is now `insert(ItemKey key, BsonDocument metadata, int amount, boolean simulate)` and `firstExtractable` returns a `Peek(ItemKey key, BsonDocument metadata)`. On COMMIT the metadata is attached to the delivered stack (`new ItemStack(id, amount).withMetadata(metadata.clone())`, mirroring DropSink) so a damaged/enchanted/BlockHolder item round-trips byte-for-byte instead of being stripped on the happy delivery path. On SIMULATE the slot scan counts an occupied same-id slot as room ONLY when its metadata matches (`Objects.equals`), mirroring the engine's `isStackableWith` (id AND durability AND metadata) — conservatively, since the engine's hidden durability field is not on the public `ItemStack` surface: a same-id different-metadata slot is never counted (no over-promise/launch-then-bounce), and the durability residual under-promises (a later re-route finds the real room, no churn). Empty slots always count.

### Vanilla chest verification ✓ ("any vanilla container")

Vanilla chests declare the SAME component in their BlockType JSON, e.g. `Server/Item/Items/Container/Furniture_Kweebec_Chest_Large.json` and `Server/Item/Items/Furniture/Ancient/Furniture_Ancient_Chest_Small.json`:

```json
"BlockEntity": { "Components": { "ItemContainerBlock": { "Capacity": 36 } } }
```

So a placed vanilla chest resolves through the exact `BlockModule.getBlockEntity` → `getComponent(ItemContainerBlock.getComponentType())` → `getItemContainer()` path above. The "any block entity with an engine `ItemContainer`" requirement is met by component presence; no per-block allow-list is needed.

### Persistence

The chest's contents persist via the engine's own `ItemContainerBlock` codec (not ours). We never persist container contents; our only new persisted state is the `TravelingStack`s on `PipeNode.InTransit` (already landed). After mutating a chest (insert/extract commit) we mark the chest's chunk needing-save via the existing `BlockComponentChunk.markNeedsSaving()` pattern (`WrenchInteraction.markNeedsSaving`), and likewise mark pipe chunks after any `inTransit` mutation.

## Spike findings (overlay tip entities)

*Decompile spike 2026-06-07 (Server-0.5.3). Goal: can a tiny static model entity act as a per-face "push/pull tip" overlay on pipe blocks, since block states only allow one CustomModel per state? Every claim below is cited to a decompiled class + method.*

**VERDICT: VIABLE-WITH-CAVEATS.** A static prop entity renders an arbitrary blockymodel at sub-block coords with full rotation + scale, is click-through when spawned without a hitbox, and can be made non-persistent with one built-in marker. The one non-trivial caveat: the model must be a **registered `ModelAsset`** (a standalone tip `.blockymodel` registered through our asset pack), not a raw path handed to the entity at spawn time. The single best precedent is the engine's own deployable entity, which is functionally identical to what we want.

### 1. Minimal static-prop archetype (the exact recipe)

The engine's `DeployablesUtils.spawnDeployable` (com.hypixel.hytale.builtin.deployables.DeployablesUtils) is a near-exact template: it builds a `Holder<EntityStore>` via `EntityStore.REGISTRY.newHolder()`, adds model + transform + networking + a non-serialized marker, and commits with `commandBuffer.addEntity(holder, AddReason.SPAWN)`.

To **exist + render + be networked** an entity needs only:
- `TransformComponent` (com.hypixel...entity.component.TransformComponent) — `Position` (Vector3d) + `Rotation` (Rotation3f). See §3.
- `ModelComponent` (...entity.component.ModelComponent) — wraps a `Model`; `EntityTrackerSystems$EntityModel.queueUpdatesFor` sends `ModelUpdate(model.toPacket(), scale)` to every viewer.
- `NetworkId` (...entity.tracker.NetworkId) — `new NetworkId(store.getExternalData().takeNextNetworkId())`. Required for any per-entity client packet.
- `BoundingBox` (...entity.component.BoundingBox) — server-side spatial AABB; `new BoundingBox(model.getBoundingBox())`. **Not** networked on its own (no tracker system sends it; only `HitboxCollisionSystems$EntityTrackerUpdate` sends hittability — see §4).
- `Visible` tracker component — `holder.ensureComponent(EntityModule.get().getVisibleComponentType())`. Every networked tracker system queries `Query.and(visibleComponentType, ...)`, so without it nothing is sent.
- `PropComponent` (...entity.component.PropComponent) — marks the entity a static prop. `EntityTrackerSystems$EntityModel.queueUpdatesFor` additionally sends a `PropUpdate` when `isProp`. `ModelSystems$AssignNetworkIdToProps` + `EnsurePropsPrefabCopyable` are the only prop-specific systems and only add NetworkId/PrefabCopyable (both harmless).

`ItemComponent.generateItemDrops` builds the drop archetype via `ItemSystems$EnsureRequiredComponents.onEntityAdd`: ItemComponent + NetworkId + **ItemPhysicsComponent** + BoundingBox + ModelComponent (+ DynamicLight). A drop is almost our prop. We **OMIT**: `ItemComponent`, `ItemPhysicsComponent` (gravity/physics), `PickupItemComponent`/pickup collision, and any `DespawnComponent` (unless we want auto-expiry). What remains is exactly the deployable recipe minus its gameplay components (EntityStatMap, DeployableComponent, Invulnerable, AudioComponent, HeadRotation are all optional for a pure visual).

### 2. Model source — registered ModelAsset, not arbitrary path

`Model` (...asset.type.model.config.Model) `toPacket()` writes BOTH `packet.assetId` and `packet.path`, and defaults `texture` to `path.replace(".blockymodel",".png")` — so the wire format *can* carry a raw path. BUT the only public constructors of a usable `Model` go through `Model.createScaledModel(ModelAsset, scale, ...)` / `Model.createStaticScaledModel(ModelAsset, scale)`, which require a `ModelAsset` to source the bounding box, eye height, particles, etc. `Model.ModelReference.toModel()` resolves `ModelAsset.getAssetMap().getAsset(modelAssetId)` by id. The `.blockymodel` path itself lives on the `ModelAsset` (`ModelAsset.model` field, codec key `"Model"`; `"Texture"` key for the png).

**Therefore:** author a standalone tip `.blockymodel` (e.g. `ItemPipe_end_push` tip extracted to its own model) and register it as a `ModelAsset` through our asset pack (GenerateAssetsEvent / ModelAsset asset store — see hytale-assets skill). Then spawn with `Model.createStaticScaledModel(tipModelAsset, scale)`. `createStaticScaledModel` sets `staticModel=true` (`Static` codec flag on `ModelReference`), which the `toReference()` path also infers when `animationSetMap == null` — i.e. an animation-less tip is treated as static automatically.

### 3. Scale / rotation / anchor

- **Scale:** `Model` carries a `scale` float; `Model.createScaledModel(asset, scale, ...)` scales bounding box, eye height, camera, physics uniformly. Pass scale at spawn.
- **Rotation:** `TransformComponent.rotation` is `Rotation3f` = full yaw/pitch/roll (`teleportRotation` sets all three). More than enough to orient a tip toward any of the 6 faces.
- **Anchor:** position is `Vector3d` (double), so sub-block placement at a face center (block + 0.5 + faceNormal*0.5) is trivial. Convention: props render **centered on position** (deployables set `TransformComponent.setPosition(spawnPos)` directly with no feet offset, unlike player models which use eyeHeight/feet). So place the tip at the exact face-tip world point.

### 4. Click-through (the kill-or-keep question) — PASSES

Hittability and interactability are **both opt-in components**, sent by dedicated tracker systems; absence = the client never registers the entity as a target:
- **Hitbox / attack target:** `HitboxCollision` (...entity.hitboxcollision.HitboxCollision) is a separate component. `HitboxCollisionSystems$EntityTrackerUpdate.queueUpdatesFor` sends `HitboxCollisionUpdate` only for entities that *have* it. `DeployablesUtils.spawnDeployable` adds it **conditionally** (`if (config.getHitboxCollisionConfigIndex() != -1)`) — a deployable with no hitbox config is hitbox-less. A prop without `HitboxCollision` is not raycast-hittable for attack/damage.
- **Interaction (press-F):** `Interactable` (...entity.component.Interactable) is opt-in; `EntityInteractableSystems$EntityTrackerUpdate` sends `InteractableUpdate` only for entities that have it. No `Interactable` = no interact prompt, no `UseEntityInteraction` target.
- This mirrors how nametag/hologram-style UI is attached (`UIComponentList` via `EntityUIModule`) without making the carrier hittable.

So: spawn the tip with **neither `HitboxCollision` nor `Interactable`** and it is fully click-through — clicks/attacks pass to the pipe block behind it. (Caveat to verify in-game: client-side soft selection/highlight outline behavior on a model with a server `BoundingBox` but no networked hitbox — server targeting is provably safe; the visual hover outline is the only unknown and is the single reason this is VIABLE-*WITH-CAVEATS* rather than unconditionally VIABLE.)

### 5. Persistence — solved with a built-in marker

`PersistentModel` (...entity.component.PersistentModel) HAS a codec and IS saved; `ModelSystems$SetRenderedModel` rebuilds the `ModelComponent` from it on `AddReason.LOAD`. **Do not add `PersistentModel`** if we want a transient tip.

The built-in no-save flag is `NonSerialized`: `ComponentRegistry` registers `nonSerializedComponentType = registerComponent(NonSerialized.class, NonSerialized::get)`, exposed as `EntityStore.REGISTRY.getNonSerializedComponentType()`. `DeployablesUtils` adds exactly this: `holder.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType())`. Adding it makes our tip exist only in memory — no disk save, no reload, no dedupe problem. This is the recommended path: spawn tips fresh during our visual pass each session.

(Fallback if we ever want persistent tips: register our own EntityStore marker component carrying the owning pipe `BlockPos` and dedupe on load. `getEntityStoreRegistry().registerComponent(...)` works for us exactly as our existing ChunkStore registrations do — `ChonbosChemistry.java` already registers `MachineBlockState`/`TankBlockState`/`PipeNode` on the chunk registry, and registers systems on `getEntityStoreRegistry()`. Not needed if we use `NonSerialized`.)

### 6. Despawn / remove

From any tick system: `commandBuffer.removeEntity(ref, RemoveReason.REMOVE)` (or `tryRemoveEntity` for a safe no-throw variant). Confirmed in `DespawnSystem.tick`: `commandBuffer.removeEntity(entityRef, RemoveReason.REMOVE)`. `CommandBuffer.addEntity(holder, AddReason.SPAWN)` returns the `Ref` we hold to remove later. Optional auto-expiry: add `DespawnComponent(despawnAt)` and the engine's `DespawnSystem` removes it for us.

### Probe command (`/cc-tipspike`) — exact spawn recipe

```java
// Prereq: a tip ModelAsset must be registered (asset pack). For the probe, any small
// registered ModelAsset id works to prove the mechanism (e.g. reuse an existing item/prop model id).
ModelAsset tip = ModelAsset.getAssetMap().getAsset("<tipModelAssetId>");
Model model = Model.createStaticScaledModel(tip, /*scale*/ 0.5f);

Holder<EntityStore> h = EntityStore.REGISTRY.newHolder();
h.addComponent(TransformComponent.getComponentType(),
        new TransformComponent(faceTipWorldPos /*Vector3d*/, faceRotation /*Rotation3f*/));
h.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
h.addComponent(PropComponent.getComponentType(), PropComponent.get());
h.addComponent(BoundingBox.getComponentType(), new BoundingBox(model.getBoundingBox()));
h.addComponent(NetworkId.getComponentType(),
        new NetworkId(store.getExternalData().takeNextNetworkId()));
h.ensureComponent(EntityModule.get().getVisibleComponentType());
h.ensureComponent(EntityStore.REGISTRY.getNonSerializedComponentType()); // transient: no disk save
// DELIBERATELY OMITTED: HitboxCollision, Interactable, ItemComponent, ItemPhysicsComponent,
//                       PersistentModel, PickupItemComponent  -> click-through, non-persistent, no physics.
Ref<EntityStore> ref = commandBuffer.addEntity(h, AddReason.SPAWN);
// Later (cleanup / face toggled off): commandBuffer.removeEntity(ref, RemoveReason.REMOVE);
```

Probe goals to confirm the one open caveat: (a) tip renders at the face tip with correct orientation/scale; (b) attacking/pressing-F through the tip hits the pipe block, not the tip; (c) tip is gone after relog (proves `NonSerialized`).
