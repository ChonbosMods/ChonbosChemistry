# Recipe-Script : Phase 1-2 Implementation Plan

> Executes Phases 1-2 of `2026-06-26-recipe-script-selector-design.md` (engine + per-machine card slot/display). **GUI-agnostic core first, then the one small interactive slot.** Build RAW (no HyUI : Phase 0 decision). Reference for the slot/tooltip patterns: `2026-06-26-phase0-ui-feasibility-findings.md` + vanilla `EntitySpawnPage`.

**Architecture:** a recipe script is an immutable `RecipeScript` value carried in the card item's metadata (one `KeyedCodec`). The shared `AutoCraftEngine` reads it, restricts the craftable set, picks ordered/unordered, tracks per-machine made-counts, and idles on completion. Cards stay immutable; progress lives on the machine and resets on eject.

**Key APIs (verified):** `ItemStack.withMetadata(KeyedCodec<T>, T)` / `getFromMetadataOrNull(KeyedCodec<T>)` over a `BsonDocument`. `AutoCraftNode` is the engine's view of a machine (today: `card()`, `currentRecipeId()`, `progress()`, `lastSelectedId()`, `craftDelay()` + energy/ports/enabled). Engine selection today: `craftable` set → `CraftSelection.topByPriority` → `selectNext` (even rotation). The seam is `Spec.allowSet(node)` → `cardAllowSet(card)`; the Sculptor overrides with `scriptGateAllowSet` (deny-all when no card).

---

## Task 1 : `RecipeScript` model + metadata codec + `cardAllowSet`/`cardScript` (Phase 1a, pure/TDD)

**Files:** new `impl/block/craft/RecipeScript.java`; modify `AutoCraftEngine.java`; new `RecipeScriptTest.java`.

- `RecipeScript`: immutable value = `boolean ordered` + `List<Entry>` where `Entry = { String recipeId, int count }`, **count <= 0 means INFINITE** (permanent whitelist member). Add a `BuilderCodec<RecipeScript>` (nested `Entry` codec; mirror `MaterialQuantity.CODEC` / `PageData.CODEC` construction : `KeyedCodec` fields, `Codec.list`, etc.).
- Helpers on `RecipeScript`: `boolean isInfinite(Entry)`, `Set<String> recipeIds()`, `boolean isEmpty()`.
- In `AutoCraftEngine`: define `KeyedCodec<RecipeScript> CC_RECIPE_SCRIPT` (replaces the flat `CARD_ALLOW` String[]). `cardScript(@Nullable ItemStack card)` → `RecipeScript` or null (null card / no metadata / empty entries → null). Reimplement `cardAllowSet(card)` = `cardScript(card) == null ? null : script.recipeIds()` (preserves: no/blank card → null → allow-all; programmed → the id set, for the deny-all gate).
- **TDD:** round-trip a script through `withMetadata`→`getFromMetadataOrNull` (ordered+counts survive); blank card → null; empty-entry script → null; infinite (count 0/-1) vs finite; `recipeIds()` set; mixed. Use `new ItemStack(id, qty).withMetadata(CC_RECIPE_SCRIPT, script)`.

**Gate:** suite green; new `RecipeScriptTest` covers round-trip + edge cases.

---

## Task 2 : pure script-selection logic (Phase 1b, pure/TDD)

**Files:** new `impl/block/craft/ScriptSelection.java`; new `ScriptSelectionTest.java`.

Pure functions over `(RecipeScript script, Map<String,Integer> progress, Set<String> craftableNow, String lastSelectedId)`:
- `Set<String> activeSet(script, progress, craftableNow)` : ids that are (infinite OR `progress.getOrDefault(id,0) < count`) AND in `craftableNow` AND in the script. (The script restricts; progress retires finite entries.)
- `String orderedPick(script, active, ...)` : first script entry whose id is in `active` (list order).
- `boolean isComplete(script, progress)` : no infinite entries AND every finite entry met (`progress >= count`) : signals the machine to idle.
- Unordered selection REUSES the engine's existing `CraftSelection.topByPriority` + `selectNext` over `active` (no new logic : Task 4 just feeds `active` instead of the raw craftable set).

**TDD:** active-set with finite retirement + infinite persistence + craftable intersection; ordered first-pick respects list order and skips a momentarily-uncraftable head (default : skip, don't stall : per design §3.3); completion true only when all finite met + no infinite.

---

## Task 3 : per-machine progress state + `AutoCraftNode` (Phase 1c/2a)

**Files:** modify `AutoCraftNode.java` + all 7 `*State.java` (+ codecs) + the 7 `*StateCodecTest.java`.

- Extend `AutoCraftNode` with: `Map<String,Integer> scriptProgress()`, `void putScriptProgress(String id, int made)` (or `incrementScriptProgress(id)`), `void clearScriptProgress()`, and a **card-signature** accessor (`String lastCardSig()` / `void setLastCardSig(String)`) used to detect a card change → reset. (Simplest signature: the script's `recipeIds()+ordered+counts` hashed, or the raw metadata string; any change ⇒ different card ⇒ reset.)
- Add the backing fields + codec entries (a `Map<String,Integer>` + a `String`) to all 7 auto-crafter States; mirror the existing field pattern. Reset on a `clearScriptProgress`.
- **TDD:** extend each `*StateCodecTest` to round-trip the progress map + signature.

---

## Task 4 : `AutoCraftEngine` integration (Phase 1d)

**Files:** modify `AutoCraftEngine.java`; touch the 7 `*TickSystem` only if their `Spec.allowSet` needs the generalized gate.

In `drive()`:
- Resolve `script = cardScript(node.card())`. Compute a **card signature**; if it differs from `node.lastCardSig()` ⇒ `node.clearScriptProgress()` + set the new sig (this is the "reset on eject/swap").
- Build `craftable` as today, then: if `script != null` ⇒ restrict to `ScriptSelection.activeSet(script, node.scriptProgress(), craftable)`; pick via `script.ordered() ? ScriptSelection.orderedPick(...) : existing topByPriority+selectNext`. If `script == null` ⇒ today's behavior (priority+rotation over full craftable).
- On a **COMPLETE** craft, `node.incrementScriptProgress(recipeId)` (only when a script is active).
- If `script != null && ScriptSelection.isComplete(script, progress)` ⇒ idle (no pick).
- **Gating:** generalize : `allowSet`/the gate already covers Sculptor (no card → deny-all) vs others (no card → null/allow-all). Confirm the Sculptor's `scriptGateAllowSet` still returns deny-all on a blank card and the script path runs on a programmed one.

**Gate:** full suite green; the existing engine behavior (no card) is unchanged; with a hand-stamped card the machine whitelists + counts + idles. Verifiable in-game by hand-setting `CC_RECIPE_SCRIPT` metadata (no GUI yet).

---

## Task 5 : interactive card slot + remaining display on the 7 machine panels (Phase 2 GUI)

**Files:** the 7 `*PanelPage.java` + their `*Panel.ui` (or the shared bits); mirror vanilla `EntitySpawnPage`.

- Make `#CardSlot` accept a hand-dragged card: bind `CustomUIEventBindingType.Dropped` on `#CardSlot`; in `handleDataEvent`, read the dropped `ItemStackId`, build the card `ItemStack`, set `node.setCard(...)` (add the setter to the node/state), and write the slot back via `.set("#CardSlot.Slots", ...)`. **Insert resets progress** (new card sig).
- **Eject**: a clear affordance returns the card to the player inventory + `node.setCard(null)` + `clearScriptProgress`. (The existing Eject button already drains containers : extend it or add a card-specific take-back.)
- **Remaining readout**: per-rebuild, set `#CardSlot.TooltipText` (or a small list) to the per-entry `count - made` in a red `Message` span (infinite → ∞). Reuse the live `PanelRefreshService`.
- Mirror across all 7 panels (they are clones).

**Gate:** suite green; in-game you can drag a programmed card into any machine, see it run + the remaining readout, and eject to reset.

---

## Sequencing & method

Subagent-driven, one task at a time, spec + quality review between. Tasks 1-2 are pure/TDD (fast, high-confidence). 3-4 are the engine wiring. 5 is the only GUI. Phases 1 (Tasks 1-4) delivers a working hand-programmed card system on every machine including the Sculptor; Phase 2 (Task 5) makes the slot hand-usable. The Phase-3 programmer bench (the big browser) is a separate later effort against the `EntitySpawnPage` template.
