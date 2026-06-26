# Recipe-Script Selector : Design

> **For Claude:** This is a DESIGN doc, not an implementation plan. Implementation is GATED on the Phase 0 UI-feasibility research (§8). After that lands, use `superpowers:writing-plans` to turn the phasing (§10) into a task-by-task plan.

**Goal:** Let a player author a portable "recipe script" card : a blueprint of which recipes a machine may make, how many of each, and in what order : and insert it into any automated crafting machine to drive it. The Sculptor (911 Builders recipes) is unusable without this; every other auto-crafter gains optional whitelisting/blueprinting from the same system.

**Status:** Designed (this doc). **Phase 0 COMPLETE** : all six UI primitives proven feasible (see `2026-06-26-phase0-ui-feasibility-findings.md`); vanilla `EntitySpawnPage` is the build template. Phases 1-2 (engine, GUI-agnostic) unblocked; Phase 3 (GUI) has a locked affordance list + one live prototype to run (filtered list at Sculptor scale).

**Builds on:** the shared `AutoCraftEngine` (`Spec.allowSet(node)` → `cardAllowSet(node.card())` → `Set<String>`, gated through `CraftSelection.allowed`), the per-machine `card()` slot already in every auto-crafter's state + codec, and the Sculptor's `scriptGateAllowSet` deny-all gate. See `docs/design.md` §7.10 and memory `assembler-sculptor-split`.

---

## 1. What exists today (the substrate)

Every auto-crafter (`Forge`, `Cooker`, `Outfitter`, `Alembic`, `Assembler`, `Cultivator`, `Sculptor`) runs the one `AutoCraftEngine`. Each tick it builds a `craftable` set = (recipes in its pool) ∩ (`Spec.allowSet`) ∩ (sourceable from the network) ∩ (output has room), applies most-ingredient priority + even round-robin (`CraftSelection.topByPriority` → `selectNext`), pulls exactly one recipe's ingredients, crafts, repeats.

- `Spec.allowSet(node)` is the seam this design fills. Today it returns `cardAllowSet(node.card())` : `null` (no card → allow all) or a `Set<String>` whitelist from card metadata (`CARD_ALLOW`).
- The Sculptor overrides it with `scriptGateAllowSet`: an EMPTY set (deny-all → inert) when no card is present.
- The `card()` slot + `#CardSlot` panel widget already exist on every machine, but are display-only (no insert/eject UX yet).

This design upgrades the card from a flat id-set into a structured **recipe script**, makes the card slot interactive, adds per-machine progress tracking, and adds a **programmer bench** to author cards.

---

## 2. The recipe script (the card) : data model

A programmed card is an **immutable blueprint** carried entirely in the item's metadata. The card never mutates while a machine runs it (see §3 : progress lives on the machine). This is what makes a card copyable and shareable as a "blueprint for a set of crafts."

A script is:

- **An ordered list of entries**, each `{ recipeId: String, count: int? }`.
  - `count` absent/0 ⇒ **infinite** : a permanent whitelist member.
  - `count = N` ⇒ make N, then the entry **retires** (the machine has met it).
- **A single `ordered` flag** (card-level):
  - `unordered` ⇒ round-robin over the active (unfinished) entries : today's even-rotation behavior.
  - `ordered` ⇒ walk the list top-to-bottom (sequential).

Whitelist / amounts / mix all fall out of this one structure : there are no separate "modes," only "did you type a count" plus the order flag.

```
Example A (pure whitelist, unordered):   [ {oak_planks}, {oak_stairs}, {oak_slab} ]            ordered=false
Example B (work order, ordered):         [ {oak_planks, 64}, {oak_stairs, 32} ]                 ordered=true
Example C (mix):                         [ {oak_planks, 64}, {oak_stairs} ]  ordered=false       # 64 planks, then planks retire and it makes stairs forever
```

**Metadata schema:** a new versioned key (e.g. `CC_RecipeScript`) holding `{ version, ordered: bool, entries: [{id, count}] }`. The legacy flat `CARD_ALLOW` set is subsumed (a script with all-infinite entries == the old whitelist); `cardAllowSet` is reimplemented to read the new schema and still expose a `Set<String>` for the deny-all gate test.

---

## 3. Execution semantics (machine side)

**Progress lives on the MACHINE, not the card** (locked decision : keeps cards copyable blueprints).

- Each machine keeps an ephemeral **progress map** `recipeId → madeCount` for the currently-inserted card, persisted on the machine state (so a world reload mid-run, card still inserted, keeps its place).
- **Eject (or swap) the card → progress resets** to empty. Any change to the card slot clears the map.
- A panel **hover on the inserted card shows remaining** per entry (`count − made`) : rendered as the red "remaining" text the user described; infinite entries show ∞.

**Per-tick selection becomes:**

1. `active` = card entries whose `count` is infinite OR `made < count`, restricted to the machine's own pool (a card may list recipes this machine can't make : silently ignored) and to network availability + output room.
2. `unordered` card ⇒ apply the existing most-ingredient priority + even-rotation over `active` (smallest change : the card just narrows the set).
3. `ordered` card ⇒ pick the **first unfinished entry in list order that is currently craftable** (priority is ignored : list order is the priority). Skipping a momentarily-unsourceable entry (rather than stalling the whole queue) is the default, to avoid a deadlock on one missing ingredient; "strict wait at the head" is a tunable alternative (§9).
4. On a completed craft, increment `made[recipeId]`.
5. When every finite entry is met and no infinite entry remains ⇒ nothing `active` ⇒ machine idle. The card stays inserted and progress persists until eject (so the player sees a finished blueprint, all-red-zero).

No card present: §4 governs (unrestricted for most machines, inert for the Sculptor).

---

## 4. Scope and gating across machines

The card slot is **universal**: wired into **every automated crafting machine** (all seven `AutoCraftEngine` machines). Behavior when **no card** is present differs by machine:

- **Sculptor**: **gated** : deny-all → inert (its 911-recipe pool is meaningless unscripted). Unchanged from today's `scriptGateAllowSet`.
- **All other auto-crafters**: **unrestricted** : `allowSet` returns `null` → make anything craftable (today's behavior). A card is purely *optional* whitelisting/blueprinting.

With a card present, every machine (Sculptor included) runs the script per §3.

**Out of scope:** the **Smelter** and **Reclaimer** are vanilla `ProcessingBenchBlock` machines, not `AutoCraftEngine` crafters : their output is determined by the input you feed them (smelt the ore you pipe in), so there is no recipe to *select*. They get no card slot. (If a future need arises : e.g. filtering which inputs a Reclaimer accepts : it is a separate feature, noted in §9.)

---

## 5. The Recipe-Script Programmer (the bench)

A new interactive block : the **recipe-script programmer**. It is a programming station (no power, no pipes : it only authors cards). Its GUI is the recipe selector.

**Slots: two inputs + one output.**

| Slot | Holds | Role |
|---|---|---|
| **A : blank-script slot** | a blank script card | REQUIRED to view/select recipes. Identifies the card being authored. |
| **B : dual-purpose slot** | an **item** OR a **filled script card** | sets the bench's mode (below). |
| **Out : output slot** | the resulting programmed card | the authored/copied card lands here. |

**Mode is implied by slot B's contents:**

- **B empty or B = item ⇒ PROGRAM mode.** The GUI shows the recipe browser; the player builds the script (pick recipes, set counts, set the order flag). If B holds an item, the browser is **filtered to recipes that consume that item** (the ingredient filter). Apply → the script is written onto the blank from A → Out.
- **B = filled script card ⇒ COPY mode.** The bench stamps B's program onto the blank from A → Out (a duplicate). This is how blueprints are mass-produced and shared.

**The browser (PROGRAM mode):**

- **Tabs : one per vanilla bench** (`Workbench`, `Furniture_Bench`, `Campfire`, `Cookingbench`, `Builders`, …). Recipes listed in **Hytale native order** within each tab.
- The **item filter** (slot B item) narrows the visible recipes to those taking that item as an input : composes with the tab selection (filter by bench AND ingredient).
- Click a recipe to add it to the **script list**; set a per-entry **count** (blank = infinite); reorder entries when `ordered`; toggle **ordered/unordered**.
- The two filters (tab + item) exist purely to tame large pools (notably the Sculptor's 911) : they affect *what you browse*, never what the card stores.

The card slot on the programmer is what the existing `#CardSlot` becomes : interactive.

---

## 6. Card lifecycle

```
craft a BLANK card  ─▶  PROGRAM at the bench (slot A blank + optional slot B item filter)  ─▶  filled card
                                                                                                  │
                              COPY at the bench (slot A blank + slot B = filled source)  ◀────────┘
                                                                                                  │
                                                                            INSERT into a machine's card slot
                                                                                                  │
                                              machine runs the script (§3); hover shows remaining; eject resets progress
```

- **Blank card** (`CC_RecipeScript`, unprogrammed): a normal craftable item.
- **Program / copy**: only at the bench (§5).
- **Run**: insert into any auto-crafter; the machine reads the immutable script and tracks its own progress.

---

## 7. Engine integration and required changes

- **Metadata schema** (§2): define `CC_RecipeScript` (versioned: `ordered`, `entries[]`). Reimplement `AutoCraftEngine.cardAllowSet` to read it; keep it returning a `Set<String>` for the deny-all gate, and add a richer accessor returning the ordered entries + counts + flag.
- **Per-machine progress**: add a progress map (`recipeId → made`) + a held-card identity to each auto-crafter's state + codec; clear on card-slot change; persist while inserted. (One shared shape, mirrored like every other auto-crafter field.)
- **Selection** (`AutoCraftEngine` + `CraftSelection`): compute `active` from script + progress; branch ordered vs unordered (§3.2/3.3). Most of this is new pure, unit-testable logic (no world needed) : the bulk of Phase 1 is TDD'd headless.
- **Sculptor gate**: unchanged in spirit (no/empty card → inert); now expressed against the richer script (an empty script == deny-all).
- **Panels**: make the `#CardSlot` interactive (insert/eject) on every machine; add the hover-remaining readout. The programmer is a new page.
- **The blank card item + the programmer block**: new assets (item def + icon, block + model + GUI page + registration), mirroring existing patterns.

---

## 8. Phase 0 : UI feasibility research (PREREQUISITE, do first)

The programmer leans on custom-UI primitives we have **not** yet proven in Hytale's `InteractiveCustomUIPage` framework. This phase de-risks the GUI before any of it is built.

**Unknowns to resolve:**
- **Tabs** (a tab bar switching recipe sets) : or a fallback (a bench-picker dropdown / paged buttons).
- **Numeric count entry** : typed field vs `+/−` steppers vs a quantity dial.
- **Hover tooltips** (the red "remaining" readout on an inserted card).
- **Scrollable, filterable recipe lists** at scale (911 rows) : pagination/virtualization.
- **Interactive slots** that accept a hand-placed item/card (insert + eject), since today every machine slot is display-only.
- **Click-to-select** rows and live re-filtering as slot B / the tab changes.

**Method:**
- **Study the local `Natural20` source mod first** : it has proven, in-production Hytale UI patterns; prefer its idioms over inventing ours. Catalogue which of the primitives above it already solves and how.
- Prototype each primitive in a throwaway test panel against the running dev server; record what renders + what events fire.
- Produce a **capability matrix** (primitive → supported? → chosen affordance → fallback). This matrix feeds the final programmer layout (§5 may bend to it : e.g. steppers instead of typed counts, paged lists instead of tabs).

**Deliverable:** the capability matrix + a locked GUI affordance list. No production UI is built until this exists.

---

## 9. Risks and open questions

- **UI primitives (the big one)** : §8 may force layout changes. Everything else is designed to survive a less-capable UI (the data model + engine are GUI-agnostic and can be exercised headlessly with hand-built cards).
- **Sculptor browser scale** : 911 recipes demands the tab + item filter + pagination actually work; otherwise the Sculptor stays hard to program.
- **Ordered-queue stall vs skip** (§3.3): default is "skip a momentarily-unsourceable entry, keep order among the craftable ones." Confirm against play feel; "strict wait at the head" is the alternative.
- **Unordered + priority** (§3.2): default reuses the existing most-ingredient priority within the card's set. If that surprises players who picked an explicit list, switch unordered to pure even-rotation. Minor, tunable.
- **Progress identity** : progress is keyed to "the card currently in the slot"; any eject/swap resets it. We do not try to recognise "the same blueprint" across a swap : simplest, matches "resets on eject."
- **Persistence** : the progress map persists on the machine state across reload (card still inserted). Codec growth is one map + a flag per machine.
- **Smelter/Reclaimer** : excluded (no recipe choice). A separate input-filter feature could be designed later if wanted.

---

## 10. Phasing (post-feasibility)

- **Phase 0 : UI feasibility** (§8). Natural20 study + prototypes → capability matrix. *Blocks the GUI phases only.*
- **Phase 1 : script data model + engine execution.** `CC_RecipeScript` metadata; `cardAllowSet`/script accessor; `active`-set + ordered/unordered selection + progress tracking + completion. Pure + TDD'd headless with hand-built cards : no GUI, no new assets. Verifiable in-game by hand-stamping a card's metadata.
- **Phase 2 : universal card slot + gating + machine panel.** Interactive `#CardSlot` (insert/eject), progress reset on eject, hover-remaining readout, the optional-vs-Sculptor-gated behavior across all seven machines.
- **Phase 3 : the programmer bench + GUI.** The block + 2-in/1-out slots + the browser (tabs, item filter, count entry, order toggle, the script list) + PROGRAM/COPY modes, built to the Phase 0 affordance matrix.
- **Phase 4 : the blank card item + recipes + polish** (icons, names, copy-mass-production, balancing).

Phases 1–2 deliver a working (if hand-programmed) card system on every machine, including the Sculptor; Phase 3 makes it player-authorable.
