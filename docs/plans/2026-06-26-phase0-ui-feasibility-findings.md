# Phase 0 : Recipe-Script Selector UI Feasibility : Findings

> Closes Phase 0 of `2026-06-26-recipe-script-selector-design.md` §8. **Verdict: all six required UI primitives are first-class engine features and feasible.** One residual risk (list virtualization at ~911 rows) with a known mitigation. One open decision for the user (adopt the HyUI toolkit vs build raw : §5).

Researched by four parallel agents across: (1) our own 13 panel pages + 9 `.ui` files; (2) the Hytale Server-0.5.3 UI framework (decompiled); (3) the `ReferenceJars/UI/` third-party mod jars (incl. the **HyUI** toolkit); (4) the decompiled-vanilla **`EntitySpawnPage`** + the engine `UIGallery` widget catalogue.

---

## 1. Headline

Vanilla ships a **near-exact structural twin** of the programmer bench we need: `EntitySpawnPage` is a **tabbed, searchable, item-droppable, count-entry browser**. It proves every primitive raw (no toolkit), and is the copy-ready blueprint for Phase 3.

- Java: `Hytale-Server-Unpacked/com/hypixel/hytale/server/npc/pages/EntitySpawnPage.java`
- Markup: `models/references/Common/UI/Custom/Pages/EntitySpawnPage.ui`
- Widget catalogue: `models/references/Common/UI/Custom/Pages/UIGallery/Categories/*.ui`
- Best third-party end-to-end raw reference: `ReferenceJars/UI/MMOSkillTree-0.13.3.jar` → `QuestPage` (tabs + dropdown filter + scroll list + search-as-you-type + click-select in one page).

**Correction to a prior assumption:** the local **Natural20** mod only exercises `Activating` (button clicks) : it is a good reference for page *lifecycle/codec boilerplate* but NOT for the interactive primitives. The authoritative proofs are vanilla `EntitySpawnPage` + `MMOSkillTree`.

## 2. Capability matrix

| Primitive | Feasible? | Raw mechanism (engine) | Evidence |
|---|---|---|---|
| **Tabs** | **YES** | Either the `TabNavigation`+`TabButton` widget with the `SelectedTabChanged` event, OR (vanilla's choice) a button-bar: N `SecondaryTextButton`s each firing `Activating` with a `Tab` literal, server toggles content-group `Visible` + button `Style`. | `EntitySpawnPage.java:138-140,156-168,362-375`; `UIGallery/.../NavigationContent.ui` |
| **Numeric count entry** | **YES** | `NumberField` widget : `Format:(Step,MinValue,MaxValue,MaxDecimalPlaces)` + `Value`, **built-in steppers + clamp**. Value read on demand via an `@Count`→`#Count.Value` binding expression typed `Codec.INTEGER`. (`Slider`/`FloatSlider` also exist.) | `EntitySpawnPage.ui:214-223`; `.java:132-137,829`; `Common.ui:470-478` |
| **Hover tooltips** | **YES** | Declarative `TooltipText:` + `TextTooltipStyle: $C.@DefaultTextTooltipStyle` on ANY element : no event needed; update per-rebuild via `.set("#El.TooltipText", str)`. (`MouseEntered`/`MouseExited` events exist for lazy/dynamic content.) | `UIGallery/.../TooltipsContent.ui:27-32`; `Common.ui:915-920` |
| **Scrollable list (runtime-built)** | **YES** (see risk) | Container `LayoutMode: TopScrolling` + `ScrollbarStyle`; rows cloned in via `cmd.append("#List","Common/TextButton.ui")` in a loop, each row gets an indexed selector `#List[i] #Button` + its own `Activating` binding carrying its id. | `EntitySpawnPage.ui:49-59`; `.java:397,415-425`; `Common/TextButton.ui` |
| **Interactive item slot (drag-in / take-back)** | **YES** | `ItemGrid`/`ItemSlot`; the `Dropped` event fires on a hand-dragged inventory item and the client auto-populates codec key `ItemStackId` (`Codec.STRING`); server writes the slot back with `.set("#Slot.Slots", new ItemGridSlot[]{ new ItemGridSlot(new ItemStack(id,1)) })`. "Eject/clear" = a button that sets `.Slots` to `{ new ItemGridSlot() }`. A `HitTestVisible:false` drop-hint overlay is optional. | `EntitySpawnPage.ui:86-101`; `.java:141,524-541,268-277` |
| **Click-to-select row + live re-filter** | **YES** | Per-row `Activating` (id in payload) selects; a `CompactTextField #SearchInput` fires `ValueChanged` → `@SearchQuery`/`Codec.STRING`; handler re-runs the filter + rebuilds the list (`clear` + re-`append`) + `sendUpdate`. | `EntitySpawnPage.java:116,147-152,399-413,638-649` |

**Bonus engine events relevant to the design:** `ElementReordered` (drag-reorder list rows : directly serves the §3.2 "reorder entries when `ordered`"), `Validating`, `KeyDown`, `FocusGained/FocusLost`, full slot-drag family (`SlotMouseDragCompleted`, `SlotClicking`, …).

**Full `CustomUIEventBindingType` set (24):** `Activating, RightClicking, DoubleClicking, MouseEntered, MouseExited, ValueChanged, ElementReordered, Validating, Dismissing, FocusGained, FocusLost, KeyDown, MouseButtonReleased, SlotClicking, SlotDoubleClicking, SlotMouseEntered, SlotMouseExited, DragCancelled, Dropped, SlotMouseDragCompleted, SlotMouseDragExited, SlotClickReleaseWhileDragging, SlotClickPressWhileDragging, SelectedTabChanged`.
**`CustomUICommandType` set (7):** `Append, AppendInline, InsertBefore, InsertBeforeInline, Remove, Set, Clear`. Settable element properties are an **open string namespace** (not an enum) : any `.ui` property is settable at runtime via `.set`.

## 3. The mechanism, in one paragraph (for whoever builds Phase 3)

A page extends `InteractiveCustomUIPage<EventData>` and passes its event-record `BuilderCodec` to `super`. `build()` runs once: `cmd.append("Pages/<X>.ui")` instantiates the markup tree, then static `addEventBinding(type, "#sel", EventData, locksInterface)` calls wire controls. **`EventData` values can be binding expressions** : `EventData.of("@SearchQuery","#SearchInput.Value")` makes the client resolve a live widget value at fire-time into the codec key `@SearchQuery`. The client serializes the fired binding's map to JSON; `InteractiveCustomUIPage` decodes it via the codec and calls your typed `handleDataEvent(ref, store, record)`. Handlers build fresh `UICommandBuilder`/`UIEventBuilder`, mutate only the touched selectors (`.set`/`.clear`/`.append`), and `sendUpdate(...)` pushes a partial DOM update. **All ECS mutation runs on the world thread** (`sendUpdate` itself wraps in `world.execute`); since our panels open from interactions and our existing pages already follow this, it carries over. Dynamic-list per-row bindings are (re)registered inside the list-build method, not in `build()`.

## 4. The one residual risk : list scale (~911 Sculptor recipes)

**No source demonstrates true list virtualization.** Every visible row is a real cloned sub-tree appended to the container; each rebuild is `clear` + full re-append + per-row binding (cost O(visible rows) in clones + bindings + packet size, every keystroke). Vanilla bounds this by **hard-capping filtered results at 20** (`.limit(20L)`, `EntitySpawnPage.java:409`) but renders the *entire* set on an empty query (fine for ~dozens of NPCs, **not** for 911 recipes).

**Mitigation (already the design's intent, now mandatory):**
- Never render the full pool. The **tab (per-bench) + item filter + search** must narrow *before* render.
- **Cap the visible list** (e.g. top-N) even on an empty query, and/or **paginate** (HyUI's `DynamicPaneContainer.onScrolled` or a server-side "page N" via `clear`+`append`).
- The Sculptor's worst case (Builders tab, no filter) must be validated against a live server before the Phase-3 layout is locked.

This is the only item to actively prototype; everything else is proven.

## 5. Open decision : adopt HyUI, or build raw?

`ReferenceJars/UI/HyUI-0.9.4-all.jar` is a **MIT-licensed UI toolkit** (`au.ellie.hyui`) layered on the same `InteractiveCustomUIPage`. It wraps every primitive in fluent builders (`TabNavigationBuilder`, `NumberFieldBuilder`, `ItemSlotBuilder` + `withAreItemsDraggable`, `ReorderableListBuilder`, `TextFieldBuilder`+`VALUE_CHANGED`, `withTooltipTextSpans`, `DynamicPaneContainerBuilder.onScrolled`), with typed event payloads (`DroppedEventData.getItemStackId()`) and runtime `editById` mutation.

| | **Raw `InteractiveCustomUIPage`** (recommended) | **HyUI toolkit** |
|---|---|---|
| Dependency | none (already our stack; 13 pages) | a new pre-1.0 runtime jar, version-coupled to the engine |
| All 6 primitives | proven raw by `EntitySpawnPage` (incl. drag/drop slots) | proven, fluent builders |
| Boilerplate | string-keyed `append`/`set`/`EventData` (verbose) | lower : typed builders + `editById` |
| Reorder UI | raw `ElementReordered` | `ReorderableListBuilder` (turnkey) |
| Consistency | matches our existing panels exactly | a second UI idiom in the codebase |

**Recommendation: build RAW, mirroring `EntitySpawnPage`.** Every primitive : including the headline drag/drop slots, which no *third-party* mod shows but *vanilla* does : is proven without a toolkit, and our entire UI layer is already raw `InteractiveCustomUIPage`. Adding a pre-1.0 dependency for fluency isn't worth the version-coupling + a split idiom. Keep HyUI as a fallback if the Phase-3 list/reorder boilerplate proves painful in practice. (This is the user's call to confirm.)

## 6. Locked affordances (recommended, pending §5)

- **Tabs** → button-bar (one `SecondaryTextButton` per vanilla bench) + server-toggled `Visible`/`Style`, per `EntitySpawnPage` (full server control; integrates with our existing button+refresh patterns).
- **Per-entry count** → `NumberField` (`Step:1, MinValue:0`); **blank/0 = infinite** (a `0` value, or a small "∞" `CheckBox` per entry, renders as no-count).
- **Remaining readout** → declarative `TooltipText` with colored `Message` spans, set per-rebuild on the inserted card / each entry row.
- **Recipe list** → `TopScrolling` container, rows cloned from a row template, **filtered + capped/paginated** (never all 911).
- **Slots** → `ItemGrid` (1 slot each) with `Dropped` bindings for slot A (blank card) + slot B (item or source card), output slot written by the server; "take back" via a clear/eject affordance.
- **Search + bench tabs + slot-B item** → each fires its event (`ValueChanged` / `Activating` / `Dropped`) and triggers one list rebuild; compose by narrowing the same query.
- **Ordered-mode reorder** → `ElementReordered` on the built script list.

## 7. Effect on the design's phasing

Phase 0 is **complete** (this doc). The §10 phases are unblocked, with two notes:
- **Phase 1-2 (data model + engine + machine slot/display)** were already GUI-agnostic : proceed independently, TDD'd headless.
- **Phase 3 (programmer bench GUI)** now has a concrete blueprint (`EntitySpawnPage`) and a locked affordance list (§6). The single thing to prototype live first is the **filtered list at Sculptor scale** (§4).
