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
