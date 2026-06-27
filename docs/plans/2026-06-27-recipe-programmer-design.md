# Recipe Programmer Bench : Design (Phase 3)

> Phase 3 of the recipe-script system (`2026-06-26-recipe-script-selector-design.md`). The engine + the card lifecycle (insert/swap/eject via right-click, run scripts) are DONE + verified in-game (Phases 1-2). This adds the **player-facing authoring tool**: a bench to build/edit/duplicate recipe-script cards without the `/cc-script` debug command. Build RAW against the vanilla `EntitySpawnPage` template (Phase-0 findings: `2026-06-26-phase0-ui-feasibility-findings.md`).

**The hard constraint that shaped this:** custom panels cannot drag-and-drop from the player inventory (no inventory grid is shown behind an Interact-F panel). Proven in-game. So EVERY interaction here is either a **right-click** (load a card, like the machines) or a **GUI click/search** (select recipes, set counts) : NO drag. The selection half (tabs/search/click-to-add/+- counts/ordered toggle) is all click-based and fully proven by `EntitySpawnPage`; drag was only ever needed to get a card INTO the bench, which right-click insert solves.

**Goal:** a player loads a blank card into the Programmer, picks recipes (filtered by machine + a search box), sets per-recipe counts + an ordered flag, and takes out a programmed card : plus duplicates cards by consuming blanks from their inventory.

---

## 1. The block

A new non-powered **Recipe Programmer** block (`CC_RecipeProgrammer`), 1x1x1, reusing the **`CC_PowerCell`** model/textures as a placeholder (real art in a later pass, like the other placeholder machines). It is a pure authoring station: no energy, no pipes. Its block JSON has `Interactions.Use -> OpenCustomUI (Page.Id: CC_RecipeProgrammerPanel)` (F opens the GUI) and the right-click card-load interaction (below). It carries a small ECS state component holding ONE loaded card `ItemStack`.

## 2. Loading / removing a card (right-click, no drag)

Reuse the shipped machine pattern (`RecipeCardInteraction` / `cc_recipe_card` Secondary): **right-click the Programmer holding a `CC_RecipeScript` card** loads it into the Programmer's single card slot (insert if empty, swap if occupied : the stored card returns to the player). The card's icon shows in the panel via the metadata-stripped display (the program shows in the entries list, not on the icon). **Take Card** is a GUI button (empty-hand right-click can't be hooked : engine limit), returning the loaded card to the player. The Programmer reuses `AutoCraftNode`-style `card()/setCard()` accessors or a minimal equivalent on its own state.

## 3. Editing model : live, no Save button

The loaded card is the single source of truth. On open, the GUI reads its program via `AutoCraftEngine.cardScript(card)` and renders the entries + ordered flag. EVERY GUI action rebuilds the `RecipeScript` and **re-stamps the loaded card** via `AutoCraftEngine.writeScript(card, script)` (the same path `/cc-script` uses). `PanelRefreshService` re-renders. No "did I save?" : pulling the card out gives exactly what's on screen. Re-stamp is a cheap, click-paced metadata write.

## 4. The GUI (raw `InteractiveCustomUIPage`, mirror `EntitySpawnPage`)

Panel `CC_RecipeProgrammerPanel`, three regions:

**(a) Filters (top).**
- **Machine filter**: a button-bar / tabs of the recipe-bearing machines (Forge, Cooker, Outfitter, Alembic, Assembler, Cultivator, Sculptor). Selecting one scopes the browser to THAT machine's recipe pool (its `RecipePool` : reuse each TickSystem's cached pool, or a shared lookup). A card may hold recipes from several machines (switch the filter, add from each); each machine at runtime only runs its own subset. Default: the first machine, or "all".
- **Search box** (`CompactTextField` + `ValueChanged`): live-filters the scoped list by recipe id / output-item name. Composes with the machine filter.

**(b) Recipe browser (middle, scrolling).** A `TopScrolling` list; rows cloned per recipe (`append("#List","Common/TextButton.ui")`), each showing the recipe's output name/icon + its ingredient count, bound `Activating` carrying the recipe id. **Click a row -> add that recipe to the program** (default infinite count). CRITICAL (Phase-0 scale finding): the Sculptor pool is ~911 : the list MUST render only the FILTERED subset and CAP/paginate (vanilla caps at 20), never all rows.

**(c) Program entries (side/bottom).** The loaded card's current entries: each row = recipe name + a **count stepper (+/-)** (`NumberField` or two `TextButton`s; blank/0 = infinite, shown as `inf`) + a **remove** button + **up/down reorder** controls (only meaningful when ordered; `ElementReordered` is also available). An **ordered / unordered** toggle button. A **Duplicate xN** button. The **Take Card** button. A read-only card-slot icon.

## 5. Duplicate (consume blanks from inventory)

**Duplicate** reads the loaded card's program, scans `player.getInventory().getCombinedHotbarFirst()` (spans hotbar + backpack) for blank `CC_RecipeScript` cards (id match AND `cardScript == null`), consumes up to N, stamps each via `writeScript`, and returns the copies (drop overflow at feet). Feedback if no blanks are found. This is pure server-side container work (the `handleLoadRecipe`/eject pattern) : no drag, no second slot.

## 6. Blank cards

Plain `CC_RecipeScript` items with no script metadata (the item already exists). Obtained via a simple crafting recipe (e.g. paper + binder) or creative for now. Duplicate consumes them. (A dedicated non-tool card item with a real model/icon + stackability is a separate polish task : currently the card is the hammer-parented placeholder.)

## 7. What is proven vs new

- **Proven (reuse):** right-click card load/swap/eject (machines), `cardScript`/`writeScript`, the panel lifecycle + `PanelRefreshService` + button `Activating` events, the inventory-sink/consume pattern (eject, `/cc-script`).
- **New (all click/search, proven by `EntitySpawnPage`, untested by US):** the machine-filter button-bar + content swap, the live search `ValueChanged`, the runtime-built scrolling recipe list with per-row click bindings, the count steppers, the ordered toggle + reorder, the Duplicate scan. Each maps 1:1 onto an `EntitySpawnPage` mechanism (Phase-0 doc).

## 8. Risks

- **List scale (the one real risk):** ~911 Sculptor recipes, no list virtualization. MUST filter (machine + search) before render and cap/paginate. Prototype the Sculptor worst case first.
- **Recipe display names:** recipe ids are ugly (`Food_Kebab_Meat_Recipe_Generated_0`). Resolve each to its OUTPUT item's display name + icon for the browser/entries (via `CraftingManager.getOutputItemStacks(recipe)` -> the output item). Fallback to the id.
- **Reorder UX** when ordered (up/down buttons are the safe, proven path; `ElementReordered` drag is a nicer optional upgrade).

## 9. Phasing

- **P3.1 : the block + state + card load/eject + an empty panel** (place it, right-click a card in, F opens a stub GUI showing the loaded card's raw program, Take Card out). Proves the bench + the load loop end-to-end with the engine.
- **P3.2 : the recipe browser** (machine filter + search + the scrolling filtered/capped list + click-to-add). The core authoring. Prototype the Sculptor scale here.
- **P3.3 : the entries panel** (counts, remove, ordered toggle, reorder) : full editing.
- **P3.4 : Duplicate + blank-card recipe + polish** (output-name resolution, feedback, batch duplicate).

P3.1 is independently testable + de-risks the bench; P3.2's list-at-scale is the gate.
