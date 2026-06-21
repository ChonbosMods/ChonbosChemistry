# HyProTech / Machinarium (v1.3.3) — full decompile & analysis

Date: 2026-06-21. Source jar: `~/Downloads/HyProTech-1.3.3.jar` (author: Yoofe).
Durable copy of decompiled source + UI assets:
`~/Development/Hytale/models/references/_mod_studies/HyProTech-1.3.3/`
(`decompiled_src/` = 135 CFR-decompiled `.java`; `assets_ui/` = 19 `.ui` templates).
Contrast mod (pure asset-pack, no code): `_mod_studies/OreCrusher-1.0.5/`.

This is the single most relevant reference we have for ChonbosChemistry: a full Hytale
tech mod with powered machines, energy + item cable networks, tiered upgrades, custom
machine GUIs, and on-look HUDs. The **Alloy Smelter** is a direct analog of our CC Smelter.

---

## 0. TL;DR for our smelter UI

HyProTech's `AlloySmelterPage` is the **same architecture our `SmelterPanelPage` already uses**:
`InteractiveCustomUIPage<E>` + `WindowlessPage` + a per-tick live-refresh system + diffed
`UICommandBuilder.set("#Id.Prop", v)` updates. So we are NOT changing architecture — we are
adopting their **richer layout, theming, and UX patterns**. Concretely worth lifting:

1. **Two-column `@DecoratedContainer` layout** (left = recipe/IO/bonus, right = status/upgrade)
   over our current single-column gray box. Uses shared design tokens in a `_Master.ui`.
2. **Status panel with real text rows** (Tier / Energy / Consumption / Progress / Yield / Status)
   instead of just two segmented bars.
3. **Tier + Upgrade panel** (next-tier stats, requirement rows w/ item slots, Upgrade button).
4. **Bonus-drops panel** (chance-by-tier byproducts) — maps to slag/byproduct ideas.
5. **The diff/throttle refresh discipline** (`lastX = MIN_VALUE`, 250ms gate, `reqKey` string
   compare) — we already do a simpler version; theirs is the mature template.
6. **`Common.ui` vanilla macros**: `@PageOverlay`, `@DecoratedContainer`, `@Title`,
   `@TextButton`/`@SmallSecondaryTextButton`, `@BackButton`, `@CircularProgressBar`.

We keep our pipe-fed read-only slots + power buffer + On/Off + Eject; their layout just makes
it look like a real tech-mod machine.

---

## 1. Block → interaction → page wiring (the part OreCrusher couldn't teach us)

Block JSON (`Server/Item/Items/Machinarium_Alloy_Smelter.json`):
```json
"Interactions": { "Use": { "Interactions": [
  { "Type": "machinarium_open_ui_with_windows", "Page": { "Id": "machinarium_alloy_smelter_page" } }
]}},
"BlockEntity": { "Components": { "machinarium:energy_node": { "NodeType": "Furnace",
  "Capacity": 5000, "MaxTransfer": 5000, "Consumption": 3, "InputMask": 5, ... } } }
```
- The interaction `Type` is a **custom interaction registered in code** (`Machinarium.setup()`):
  `getCodecRegistry(Interaction.CODEC).register("machinarium_open_ui_with_windows", …, CODEC)`.
- Pages are registered with
  `OpenCustomUIInteraction.registerBlockEntityCustomPage(this, …, "machinarium_alloy_smelter_page",
   (playerRef, blockRef) -> new AlloySmelterPage(playerRef, blockRef, energyType, machineType))`.
- On `Use`: `OpenCustomUIWithWindowsInteraction.firstRun` → bail if a page is already open →
  `world.getBaseBlock(target)` (multi-cell anchor) → `page.setBlockPosition(base)` →
  `WindowlessPage` ⇒ `pageManager.openCustomPage(...)` (no inventory windows).
- `OpenPoweredBenchInteraction` is the simpler sibling that just opens the vanilla
  `ProcessingBenchWindow` — useful when you want the stock bench with no overlay.

Initial energy-node state lives in the block's `BlockEntity.Components` JSON (codec-keyed
`machinarium:energy_node`); the code reconciles it to tier each tick via `configureDefaults`.

## 2. The Alloy Smelter UI layout (`Machinarium_AlloySmelter_HyUI_v2.ui`)

`$C.@PageOverlay { $C.@DecoratedContainer { Width:1000 Height:940; #Title{...} #Content{...} } }`
`#Content` is `LayoutMode:Left` → two columns:

- **`#LeftColumn` (640w)**
  - **`#InputPanel`** "Recipe Selector": scrollable `ItemGrid #RecipeGrid` (4/row, h260) +
    `#RecipeGridHint`. A hidden `#HiddenMachineGrids` holds the actual machine `#InputGrid`(2/row)
    `-> #OutputGrid`(6/row) + Take Input / Take Output buttons.
  - **`#InventoryPanel`** "Recipe Details": output slot + name + qty, 4× requirement rows
    (`#RecipeReqSlotN`+`#RecipeReqTextN`), a qty row (`- x1 + +10 ALL` + "Load Recipe"),
    cost text. Plus a hidden `#PlayerInventoryGrid` (9/row).
  - **`#BonusPanel`** "Bonus Drops": 5× rows (`#BonusSlotN` + `#BonusNameN` + `#BonusChanceN`).
- **`#RightColumn` (320w)**
  - **`#StatusPanel`**: `#CrusherTier`, `#CrusherEnergy`, `#CrusherConsumption`,
    `#CrusherProgress`, `#CrusherYield`, `#CrusherStatus`, `#CrusherToggleButton` (TURN OFF).
  - **`#UpgradePanel`**: `#CrusherNextTier`, `#CrusherUpgradeStats`, 4× requirement rows
    (`#UpgradeReqSlotN`/`#UpgradeReqNameN`/`#UpgradeReqQtyN`), `#UpgradeButton`.

> NOTE: the Alloy Smelter UI reuses the Ore Crusher's `#Crusher*` element ids verbatim — they
> copy-pasted the OreCrusher page + .ui and reskinned. That's the intended reuse model: one
> generic "processing machine" page template, re-instantiated per machine.

**Design tokens** (`Machinarium_Master.ui`, imported via `$M`):
```
@ItemSlotSize=48 @ReqSlotSize=32 @InvSlotSize=36 @InvSlotGap=4
@PanelBorderColor=#2a3d54(0.85) @PanelFillColor=#0b1623(0.35) @PanelDividerColor=#2b3542(0.75)
@LeftColumnWidth=640 @RightColumnWidth=320 @PanelGap=10
```
Color convention: values `#d6cfb2`, secondary `#c7b59a`, muted labels `#96a9be`.
Panel idiom = bordered Group (`Background:@PanelBorderColor; Padding:(Full:1)`) wrapping a fill
Group (`Background:@PanelFillColor`), header Label + 1px divider + content. Spacing via empty
`Group { Anchor:(Height:N) }` spacers.

**Read-only slot idiom** (our pipe-fed case): bare `ItemSlot { HitTestVisible:false;
ShowQualityBackground:false; }`, fill via `update.set("#Slot.ItemId", id)` + a qty `Label`.
The simpler **Furnace** layout (`Machinarium_Furnace.ui`, 420×380) is the better starting point
for us: Energy/Consumption/Status/Progress labels + Input/Output `ItemSlotButton`s with qty
overlays + "Open Furnace Storage" + "Power: OFF" buttons. Hands off to the stock bench window
via `FurnacePage.openProcessingBench` for actual item movement.

## 3. How the page is driven (`AlloySmelterPage.java`, 2650 lines)

- `extends InteractiveCustomUIPage<AlloySmelterUiEvent> implements WindowlessPage`.
- Ctor takes `(PlayerRef, Ref<ChunkStore> blockRef, ComponentType energyType, machineType)` —
  so the page reads/writes the block's live energy + machine components.
- `build(...)`: `ui.append("Machinarium_AlloySmelter_HyUI_v2.ui")` → `initStaticUi` (seed all
  labels/slots) → `bindButtons` + `bindItemGrids` → `updateForWorld`.
- `handleDataEvent`: dispatch on `data.getAction()` (case-insensitive). Actions: ToggleEnabled,
  Upgrade, TakeInput/TakeOutput, RecipeSelect/RecipeLoad/RecipeQty±, and the full drag/click
  family for interactive grids.
- `update(node, machine, force)`: 250ms throttle, builds one `UICommandBuilder`, ORs together
  `updateEnergy|updateProgress|updateYield|updateControls|updateUpgradePanel|updateSlots|
  updateRecipeGrid|updateRecipeDetails|updateBonusPanel`, each returning a `changed` bool; only
  `sendUpdate(update)` if something changed. `reqKey`/`reqKey.equals(lastReqKey)` collapses the
  big upgrade panel into one string compare.
- Persistence: `storeMachine`/`storeNode` → `chunkStore.putComponent(blockRef, type, comp)` +
  `markNeedsSaving()`. Buttons mutate the component then re-`update`.

The per-tick refresh comes from **`PlayerUiSystem`** (`EntityTickingSystem<EntityStore>` over
`Player`), `updateCustomPage`: gets `pageManager.getCustomPage()`, `instanceof`-dispatches,
re-reads the block component, calls `page.update(...)`. Block gone / ref invalid ⇒
`IllegalStateException` caught ⇒ dismiss the page. (We do the equivalent via
`PanelRefreshService`.)

## 4. Machine framework (`com.example.plugin.machine`)

Block-component ECS (no entity-per-machine):
- **`MachineDefinition`** (stateless singleton, one per type): `id()`, `matchesBlockId(id)`,
  `createMachineComponent()`, `createEnergyNode()`, `configureDefaults(machine, energy)`,
  `tick(MachineContext, float) -> changed`.
- **`MachineComponent`** (`Component<ChunkStore>`, `BuilderCodec`): MachineId, Tier, Progress,
  ProgressMax, Enabled, Working, LastAnimTier (+ quarry area fields). `nextSoundMs` transient.
- **`MachineContext`** (per-tick value object): World/coords/component/energyNode/dirty +
  `getEnergyStorage()`, `getItemContainer()`, `markDirty()`.
- **`MachineRegistry`**: static `BY_ID` map + `DEFINITIONS` list; `findByBlockId` linear-scans
  `matchesBlockId`.
- **`MachineSystem`** (`EntityTickingSystem<ChunkStore>`, NOT parallel): query
  `Archetype.of(WorldChunk, BlockComponentChunk)`; per ticked chunk walk block holders/refs,
  resolve definition, lazily attach components, parse tier, `configureDefaults`, build context,
  `tick`; persist only if changed.
- **`MachineItemAccess`**: keeps item inventory in the engine's native `ItemContainerState`
  (lazy `ensureState` via `world.execute`, per-machine `SimpleItemContainer` sizes), NOT in the
  component. (Relevant to our pipe item I/O.)
- Registration: explicit `MachineRegistry.register(new AlloySmelterMachine())` in `setup()`.
- Anti-patterns: `MasterMachine` base is dead (nothing extends it); tier parsing uses an
  `instanceof` ladder in `MachineSystem` (should be a `MachineDefinition.parseTier`).

`AlloySmelterMachine.tick`: reconcile defaults → if disabled reset progress → find matching
recipe (4 input slots, slots 0-3) → can outputs fit (6 output slots, 4-9) → consume energy via
**transaction** (`storage.extract(cost, tx); tx.commit()`) → ++progress; at progressMax emit
outputs (× tier multiplier) + roll bonus drops, consume inputs.

## 5. Energy system (`com.doctorreborn.hytale.api.energy.*` + `com.example.plugin.energy.*`)

- The `doctorreborn` package is a shaded port of **Team Reborn Energy** (v1) on top of a
  **Fabric Transfer API** port (`com.shailist...transfer.v1`: `Transaction`,
  `TransactionContext`, `SnapshotParticipant`, `Storage`, `SingleSlotStorage`). v2 (`Energy`
  resource enum, separated producer/consumer/transferer) ships but is **unused**.
- The mod does NOT use the storage API for its own network — it balances energy with **plain
  int math** on a per-block `EnergyNodeComponent` (`Component<ChunkStore>`, codec-persisted).
  The API is only used at the **interop seam** with foreign blocks via
  `EnergyStorageLookup.find(world,x,y,z)` + `EnergyStorageUtil.move(from,to,filter,max,tx)`.
- `EnergyNodeComponent`: NodeType {SOLAR,CABLE,BATTERY,MACHINE,FURNACE,QUARRY,WIND}, int
  Energy/Capacity/MaxTransfer/Generation/Consumption, 6-bit `InputMask`/`OutputMask`
  (`EnergySide` EAST/WEST/UP/DOWN/SOUTH/NORTH, `mask = 1<<ordinal`, ALL=0b111111),
  `EnergySideMode {DISABLED,INPUT,OUTPUT,BOTH}.next()` for per-side cycling, rotation-aware mask
  rotation to the block's yaw.
- **`EnergyNetworkSystem`** ticks chunks; generators add `generation*Δ`; cables → per-tick
  **flood-fill** network solve (`buildCableNetwork`, BFS, per-tick visited-set keyed on
  `world.getTick()` so each network solves once); others → pairwise `transferToNeighbors`
  (direction = receive-priority then fill-ratio, lexicographic ordering to avoid double count).
- Cable network: `maxTransfer = min over cables` (series bottleneck), N-cable throughput
  stacking, distribution priority active-furnace > battery/machine > idle, energy re-spread
  across cables proportional to capacity for display. 6 tiers via `CableUpgradeConfig`.
- Units: nominal "hy/t"; display via `EnergyUnits` treats ints as **Joules** (`/3600`→Wh,
  J/kJ/MJ + J/s, J/t formatters).
- Display label note: the AlloySmelter block JSON sets `NodeType: "Furnace"` (so the
  ore-furnace energy rules apply), even though the machine logic is the alloy smelter.

## 6. Item transfer + cable networks (`com.shailist...transfer` + `com.example.plugin.item`)

- Transfer API = Fabric Transfer API port: `Storage<T>.insert/extract(resource, max, tx)`,
  `StorageView<T>`, `ResourceAmount<T>`, `Transaction` (outer/nested, ThreadLocal CURRENT,
  commit absorbs child snapshots, close→rollback via `SnapshotParticipant.readSnapshot`).
  Simulation = run in a nested transaction you never commit.
- BUT item cables **don't use it** — they move items with the engine's native
  `ItemContainer.moveItemStackFromSlot(...) -> MoveTransaction`. Transfer API is used by energy.
- `ItemNetworkSystem` (per-chunk BFS over `Machinarium_Item_Cable*`): collects Source/Sink
  endpoints per side (machine containers expose `MachineSlotLayout` input/output `SlotRange`),
  routes one item/slot/tick within a budget. Distribution modes: ROUND_ROBIN (rotating index
  per priority), NEAREST/FURTHEST (BFS distance over cable graph).
- **Priority (0-9) lives on the storage block, not the cable**: `ItemStorageConfigComponent`
  (block holder) with `ItemStorageConfigChunk` (`Int2ObjectMap<blockIndex→cfg>`) fallback.
  `ItemNetworkPriorityPage` BFS-walks the net, dedupes by base position, cycles priority,
  writes back + `markNeedsSaving()`.

## 7. Recipes + config

- **Recipes**: discovered at runtime from `CraftingRecipe.getAssetMap()` (match
  `BenchRequirement {type:Processing, id:"AlloySmelter"/"OreCrusher"/"Furnace"}` or heuristics),
  plus reflective `Item.recipeToGenerate`, plus hardcoded fallbacks. Cached `volatile List
  <RecipeEntry{inputItemId, inputs[], outputItemId, outputs[], quantities}>`, looked up by input.
  → **This is the "reuse vanilla bench recipes via BenchRequirement.Id" pattern, same as our
  vanilla-bench-reuse memory.**
- **Config**: per-machine static classes with 6-element per-tier arrays (TierNames, Capacity,
  ConsumptionPerSecond, OutputMultiplier, ProcessingSeconds, UpgradeRequirements[][], BonusDrops).
  Loaded JSON via `withConfig("configs/alloy-smelter", ConfigData.CODEC)` + `ConfigArrays.merge*`
  over hardcoded defaults (partial-override-safe). `BonusDrop.getChance(tier) =
  clamp01(base + perTier*tier)`, rolled per output.

## 8. UI infra patterns (`com.example.plugin.ui`)

- `WindowlessPage` (marker) = control/status panel, no container windows → `openCustomPage` only.
  `WindowProvider.createWindows()` = page that owns real `Window`s (player inv / bench). Shipped
  machines are windowless panels that **hand off to the vanilla bench window** for item drag.
- `PlayerUiSystem` also drives **on-look HUDs**: `TargetUtil.getTargetBlock(ref, 6.0, cb)` →
  classify `TargetKind {SOLAR,WIND,CABLE,BATTERY,FURNACE,ITEM_CABLE}` → render via
  `EventTitleUtil.showEventTitleToPlayer(...)` (text title), with cable/item-cable doing a BFS
  to aggregate network count/energy/tier. (DOM `CustomUIHud` overlays exist but furnace HUD is
  gated off `ENABLE_FURNACE_HUD=false`.) → a cheap "look at a machine, see its stats" feature.
- Event payloads = POJOs with static `BuilderCodec` (`Action` string + optional slot/drag
  fields). Bind via `UIEventBuilder.addEventBinding(CustomUIEventBindingType.Activating,
  "#Btn", EventData.of("Action","..."))`. Full interactive slots need the ~14-field
  `AlloySmelterUiEvent` (SlotIndex, ItemStackId/Qty, PressedMouseButton, Drag* family).

---

## 9. Prioritized lift list for ChonbosChemistry

| Want | Lift from | Effort |
|---|---|---|
| Pro two-column machine UI | `Machinarium_AlloySmelter_HyUI_v2.ui` + `_Master.ui` tokens | M |
| Status text rows (Tier/Energy/Consumption/Progress/Yield/Status) | StatusPanel + `updateEnergy/Progress/Yield/Controls` | S |
| Tier + upgrade panel & flow | UpgradePanel + `handleUpgrade`/`updateUpgradePanel` | M |
| Bonus/byproduct drops (chance-by-tier) | `*Config.BonusDrop` + `buildOutputList` | S |
| Diff/throttle refresh discipline | `update(...)` 250ms + `reqKey` compare | S (have基础) |
| On-look machine HUD | `PlayerUiSystem.updateSolarHud` + `EventTitleUtil` | M |
| JSON-tunable per-tier config | `withConfig` + `ConfigArrays.merge*` | M |
| Cross-mod energy interop | `EnergyStorageLookup` + `EnergyStorageUtil.move` | M |
| Recipe-from-vanilla-bench | `*Recipes.buildRecipeEntries` (BenchRequirement match) | S |

License caution: this is another author's GPL-ish work; we **learn patterns and re-author**,
we do NOT ship Yoofe's assets/source in our mod.
