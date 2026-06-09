# Chonbo's Chemistry : Model & Texture Asset List

*Working checklist for the art pass. Derived from the authoritative design [docs/design.md](../design.md) (sections cited per row). This is the "what do I open Blockbench and make" list, not a design doc: design lives in `design.md`, this tracks the concrete `.blockymodel` + `.png` deliverables and their status.*

## How to read this

Two kinds of deliverable, called out per row so effort is never overcounted:

- **AUTHOR** : a model you build by hand in Blockbench (a hero block, an item, or a small source-geometry set).
- **GEN** : a model the build pipeline produces from an authored source. You author the source once; `scripts/gen_pipe_tips.py`-style merges or `SubstanceAssetGenerator` multiply it. The count in parentheses is what the pipeline emits, *not* what you draw.

Most machine-class blocks are `DrawType: Model` block-entities that need **three block-interaction states** (`default` / `Processing` / `ProcessCompleted`, §7.3) so the vanilla bench tick's visual swaps have something to show. Where that applies the row says "**+3 states**" : same model, three state variants (usually emissive/animated deltas, not three full rebuilds).

**Status legend:** ✅ done/in-game · 🟡 partial · ⬜ not started · ⏸ deferred.

---

## 1. Substance jars (solids) : §3.4

| Asset | Type | Deliverable | Status | Notes |
|---|---|---|---|---|
| `Solid.blockymodel` | block | **AUTHOR** (1 shared) | ✅ | Shared jar; liquid region recolored per substance at build time. |
| `SolidGlow.blockymodel` | block | **AUTHOR** (1 shared) | ✅ | Glow variant for radioactive/curated-glow substances. |
| ~205 element/compound jars | block + item | **GEN** (~205 models + tinted textures + 64px icons) | 🟡 | `SubstanceAssetGenerator` (`./gradlew generateSolidSubstanceAssets`). **Color derivation validated but not yet applied** : textures regenerate once the game-color table lands. Glow tier auto-derived (`GlowDeriver`). |

**Your art work here:** none new : the two shared models exist and the rest is scripted. Only re-run generation after the color table is applied.

---

## 2. Transport : pipes, filters, wrench (§6, §6.8)

| Asset | Type | Deliverable | Status | Notes |
|---|---|---|---|---|
| Power cable family | block | **AUTHOR** base + arms → **GEN** (26 topology shapes) | ✅ | `ConnectedBlockRuleSet`, 26 shapes/family. Shipped. |
| Fluid pipe family | block | **AUTHOR** base + arms → **GEN** (26) | ✅ | Shipped. |
| Gas tube family | block | **AUTHOR** base + arms → **GEN** (26) | ✅ | Shipped. |
| Item pipe family | block | **AUTHOR** base + arms → **GEN** (26) | ✅ | Shipped. |
| ITEM push/pull tips | block states | **AUTHOR** tip geometry → **GEN** (~604 states) | ✅ | `gen_pipe_tips.py`. ITEM full set ships. |
| Fluid/Gas/Power push/pull tips | block states | **AUTHOR** tip geometry → **GEN** (~604/family) | ⏸ | Deferred to a **pipe-art redo** : the 4 families' source models diverge in encoding (see `pipe-model-encodings` memory). Re-author to a common encoding first, then run the tip pipeline per family. |
| **Neutron pipe** family | block | **AUTHOR** base + arms → **GEN** (26) | ⬜ | Designed (§6.9), not started. Same family pattern as the other four. |
| **`CC_ItemFilter`** | block | **AUTHOR** (1) | ⬜ | New (D48). Inline filter, connects via ITEM FaceTags. Small readable "gated" silhouette. |
| **`CC_FluidFilter`** | block | **AUTHOR** (1) | ⬜ | New (D48). FLUID FaceTags. |
| **`CC_GasFilter`** | block | **AUTHOR** (1) | ⬜ | New (D48). GAS FaceTags. Filters could share a base mesh + 3 channel retextures. |
| `CC_Wrench` | item | **AUTHOR** (1) | ✅ | Shipped. |

---

## 3. Storage : tanks & bins (§7.5, §7.6)

Three capacity tiers each (Basic / Advanced / Elite), capacity-only, not stackable, carry-with-contents. Tiers are usually one model + a tier accent (trim/colour), not three rebuilds.

| Asset | Type | Deliverable | Status | Notes |
|---|---|---|---|---|
| Fluid tank ×3 tiers | block | **AUTHOR** (1 base + tier accents) | ⬜ | Substance-locked, level-readable window ideal. |
| Gas tank ×3 tiers | block | **AUTHOR** (1 base + tier accents) | ⬜ | Pressurized look to read as gas, not fluid. |
| **`CC_SolidBin`** ×3 tiers | block | **AUTHOR** (1 base + tier accents) | ⬜ | New (D49). Bulk solid count store; reads as a hopper/bin, not a chest. |

---

## 4. Machines : chemistry & nuclear (§7.1)

Each is a multi-cell footprint block (single anchor + filler, §7.5), `DrawType: Model`, **+3 states**. Footprint size per machine is deferred to its own design : model to a sensible default (most are ~2×2-class).

| Machine | Layer | Deliverable | Status | Notes |
|---|---|---|---|---|
| Synthesizer | chemistry | **AUTHOR** (1 + 3 states) | ⬜ | elements → compound. |
| Decomposer | chemistry | **AUTHOR** (1 + 3 states) | ⬜ | compound → elements (electrolysis/thermolysis). |
| Converter | chemistry | **AUTHOR** (1 + 3 states) | ⬜ | compound A → B. |
| Irradiator | nuclear | **AUTHOR** (1 + 3 states) | ⬜ | neutron capture; reads neutron pipe. |
| Cyclotron | nuclear | **AUTHOR** (1 + 3 states) | ⬜ | proton capture; consumes H2 gas. Iconic ring silhouette. |
| Decay Vault | nuclear | **AUTHOR** (1 + 3 states) | ⬜ | passive decay + containment; should read "shielded." |

---

## 5. Generators & converters (§7.8, §7.6)

Same multi-cell + **+3 states** convention where it processes.

| Asset | Role | Deliverable | Status | Notes |
|---|---|---|---|---|
| Combustion Generator | direct | **AUTHOR** (1 + 3 states) | ⬜ | solid fuel → power. |
| Fluid Combustion Generator | direct | **AUTHOR** (1 + 3 states) | ⬜ | gas/liquid fuel → power. [DECIDE] player-facing name (§11.11). |
| Solar Panel | direct (env) | **AUTHOR** (1) | ⬜ | flat top-facing; likely no process states. |
| Geothermal Generator | heat source | **AUTHOR** (1 + 3 states) | ⬜ | lava-adjacent. |
| Boiler | heat source | **AUTHOR** (1 + 3 states) | ⬜ | combustible → steam. |
| Fission Reactor | heat source | **AUTHOR** (1 + 3 states) | ⬜ | hero block; fuel + coolant → steam + flux + spent fuel. Likely larger footprint. |
| Decay Generator / RTG | direct (passive) | **AUTHOR** (1 + 3 states) | ⬜ | decaying isotope → trickle power; endgame relevance (eats spent fuel). |
| Turbine | converter | **AUTHOR** (1 + 3 states) | ⬜ | hot coolant → power + liquid coolant. The loop's power export. |
| Thermoelectric Generator | converter | **AUTHOR** (1 + 3 states) | ⬜ | heat → power, no plumbing; bolts onto a hot block. |
| Phase Changer | converter (refrig.) | **AUTHOR** (1 + 3 states) | ⬜ | §7.6. Refrigeration/liquefaction (cryo + dense storage). |

*Deferred (do not model yet):* Wind turbine, Water wheel, Fusion Reactor + its cryo loop (§7.8.2).

---

## 6. Automated vanilla benches (§7.10, D47)

Eight themed auto-machines wrapping vanilla benches. Each is our own block-entity (**+3 states**), non-invasive (vanilla benches stay usable).

| Machine | Wraps | Deliverable | Status |
|---|---|---|---|
| Smelter | Furnace | **AUTHOR** (1 + 3 states) | ⬜ |
| Cooker | Campfire + Cooking | **AUTHOR** (1 + 3 states) | ⬜ |
| Outfitter | Tannery + Loom | **AUTHOR** (1 + 3 states) | ⬜ |
| Reclaimer | Salvage | **AUTHOR** (1 + 3 states) | ⬜ |
| Forge | Weapon/Armour/Armory | **AUTHOR** (1 + 3 states) | ⬜ |
| Alembic | Alchemy/Arcane | **AUTHOR** (1 + 3 states) | ⬜ |
| Assembler | Workbench/Furniture/Builders | **AUTHOR** (1 + 3 states) | ⬜ |
| Cultivator | Farming/Trough | **AUTHOR** (1 + 3 states) | ⬜ |

**[DECIDE] before this pass:** custom themed models vs **retexturing the vanilla bench models** (much cheaper, and vanilla models are in `models/references/`). Recommend retexture-first for the auto-benches, save bespoke modeling for the chemistry/nuclear machines and generators.

---

## 7. Circuitry components (§8.2, §8.3)

All ⬜ (designed, not started). Mostly small single-block models; the wiring is a generated family like the pipes.

| Component | Type | Deliverable | Notes |
|---|---|---|---|
| Power feed | block | **AUTHOR** (1) | energy grid → circuit rail. |
| Energy storage block | block | **AUTHOR** (1) | on the energy network; has a battery charge slot (**+3 states** optional for charge level). |
| Battery | item | **AUTHOR** (1) | portable charge; tier/charge variants optional. |
| Capacitor | block | **AUTHOR** (1) | dual-role (energy buffer / circuit edge-former). |
| Lever | block | **AUTHOR** (1 + on/off states) | SPST toggle. |
| Button | block | **AUTHOR** (1 + pressed state) | momentary. |
| Pressure plate | block | **AUTHOR** (1 + pressed state) | entity sensor. |
| Inverter | block | **AUTHOR** (1 + on/off) | 1→1 NOT; the most-placed element. |
| Gate block | block | **AUTHOR** (1 + type/state) | 2→1; type toggled on block (AND/OR/XOR/NAND/NOR/XNOR). One model, type shown via state/label, not 6 models. |
| Comparator | block | **AUTHOR** (1 + state) | analog→digital threshold. |
| Delay | block | **AUTHOR** (1 + state) | adjustable lag (re-themed repeater). |
| Oscillator | block | **AUTHOR** (1 + state) | free-running clock. |
| D-latch | block | **AUTHOR** (1 + state) | one stored bit. |
| Lamp | block | **AUTHOR** (1 + on/off) | indicator/load. |
| Machine I/O bridge | block | **AUTHOR** (1 + state) | reads machine state out / writes control in. The layer's keystone. |
| Electrical wiring | block | **AUTHOR** one planar face-skin set → **GEN** (256 arm-combos / 70 rotation-classes) | Face-attached (sculk-vein model, §8.3). Author one face's worth; pipeline orients to all 6 faces + tints the ~3 colored lanes. Same `gen_pipe_tips.py`-style merge. |

---

## 8. Items & misc

| Asset | Type | Deliverable | Status | Notes |
|---|---|---|---|---|
| Recipe card | item | **AUTHOR** (1) | ⬜ | Sets a machine's selected recipe (§7.3). [DECIDE] one-card-per-recipe vs program card (§11.4). |
| `CC_Wrench` | item | n/a | ✅ | (listed in §2). |
| Battery | item | n/a | ⬜ | (listed in §7). |

*Not models:* `.ui` templates (machine GUIs, live-refresh panel) follow the Natural20 minimal pattern (§10) and are authored as templates, not Blockbench assets.

---

## Hand-authored work summary (what's actually left to draw)

**Generated / done (little or no drawing):** substance jars (2 shared models, scripted), the 4 shipped pipe families, the wrench, electrical wiring (1 authored face-skin set → 256 generated).

**To author by hand, roughly in milestone order:**
1. **New transport bits:** 3 substance filters (share a base + 3 retextures), neutron pipe family (1 base+arms), + the deferred pipe-tip re-author for Fluid/Gas/Power.
2. **Storage:** fluid tank, gas tank, solid bin (3 base models + tier accents).
3. **Machines (6) + generators/converters (10):** ~16 hero blocks, each +3 states. The largest art block; sequenced with the machine milestone.
4. **Auto-benches (8):** prefer retexturing vanilla bench models over bespoke builds (pending the §6 [DECIDE]).
5. **Circuitry (15 blocks + 1 item + wiring):** mostly small single-block models with on/off or type states.
6. **Items:** recipe card, battery.

Open art-relevant [DECIDE]s: auto-bench custom-vs-retexture (§6 here); filter config-UI surface (§11.13); recipe-card form (§11.4); Fluid Combustion Generator name (§11.11).
