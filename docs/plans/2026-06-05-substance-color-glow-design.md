# Substance Color & Glow Enrichment — Design

**Date:** 2026-06-05
**Status:** Validated with user (brainstorming session), ready for implementation planning
**Scope:** `data/elements.json` (118), `data/compounds.json` (153), the solid-substance asset
generator, and the jar model. No isotope changes, no schema changes.

## Problem

The current dataset is visually a wall of grey and white:

- Elements: 75/118 (64%) share `#C0C0C0` silver; with the other near-greys and whites,
  ~80% of the periodic table is indistinguishable.
- Compounds: 81/153 (53%) are pure `#FFFFFF`; with `#FAFAFA`, `#FFFFF0` and friends,
  ~70% are white-ish.
- Isotopes: no color field, by design (see Non-changes below).
- Glow: no per-substance glow exists anywhere. The jar model's `Liquid` cube is
  `shadingMode: "fullbright"` uniformly for all 205 jars, the generated item JSON carries
  no `BlockType.Light`, and nothing in the data drives any visual besides `Color`.

The data is honest (colorless things really are colorless) but `Color` is the generative
key for the entire forms matrix (design doc §7.1): jars today; ingots, dusts, blocks,
fluids, gas particles, and GUI gauges tomorrow. A near-white color produces a near-white
*everything*.

## Goal

Every substance gets a deliberate, explainable color: real-world-grounded where reality
offers one, association-derived (a "fantasy pass") where reality offers only white or
grey. Radioactive substances additionally glow, derived purely from existing nuclear data.

## Decisions (locked with user)

1. **Overwrite `Color` in place** in the data JSONs. No new fields, no overlay file.
2. **No isotope changes.** Isotopes having no color is intentional: `Color` lives on
   `Substance` (Element + Compound only); isotopes are nuclear metadata. Isotope-specific
   visible forms (e.g. Cs-137 chloride) live in `compounds.json` and are covered there.
3. **Glow is derived from nuclear data** at generation time. No schema change.
4. **Elements:** real associations first, systematic fallback for the characterless rest.
5. **Compounds:** full hand-curated association pass over all 153.
6. **Nothing is grandfathered.** Existing color values, including the ~15 "already
   distinctive" ones, are at most a hint, never an answer: each is re-derived from its
   real reference (e.g. P `#B22222` assumes red phosphorus when white phosphorus is the
   iconic allotrope; At `#5A4A45` is pure guesswork).

## Section 1: Data policy & color rules

`Color`'s meaning shifts from "real-world appearance" to "game color:
real-world-grounded with a fantasy association pass". Two docs move with it:

- `data/README.md`: update the `representativeColorHex` bullet to document the new
  convention and the derivation-source tiers (spectral, flame, mineral, evocation,
  constituent, systematic).
- Element `Notes` that describe the old color (e.g. "white hex is a placeholder for
  colorless") are updated where stale.

Quality rules, driven by the tint pipeline (it *multiplies* a 175–255 luminance ramp:
dark colors go near-black, white shows no tint):

1. **Luminance band:** value roughly 45–85% so the jar liquid neither blacks out nor
   disappears. Iconic darks (carbon, oxides) may sit lower deliberately, on a documented
   exception list.
2. **Saturation floor:** no new near-greys. Greys survive only as deliberate identity
   (iron, lead) and even those get pushed apart (cold blue-grey vs warm grey).
3. **Distinctness check:** a script asserts minimum pairwise color distance within
   elements and within compounds.

## Section 2: Element palette (118)

Priority ladder: first tier that applies wins; every choice recorded in a derivation
table so it is explainable.

1. **Real distinctive color, re-verified (~15):** Au gold, Cu copper, S yellow, C black,
   Br rust, I violet, Cl yellow-green... verified against reality, then adjusted into the
   Section 1 bands. Failures fall through the ladder.
2. **Spectral/etymology (~10):** elements named for a color: Rb deep red, Cs sky blue,
   In indigo, Tl green-shoot, Rh rose, Pr leek green, Ir iridescent violet-teal, Cr a
   strong green ("the color element").
3. **Flame test (~8):** Li crimson, Na warm orange, K lilac, Ca brick, Sr scarlet,
   Ba apple green, Ra fantasy radium green (doubles as its glow hue).
4. **Gas discharge (~7):** H pale ice-blue or pink-violet (pick per vibe), He peach,
   Ne red-orange, Ar violet, Kr ghostly green-white, Xe ice blue, Rn ominous pale teal.
5. **Ion/mineral association (~20):** Co cobalt blue, Ni apple-green, Mn rose-violet,
   V teal-blue, U uranium-glass green, Ti white-violet, W steel-blue, Mo dusty blue, etc.
6. **Systematic fallback (~55):**
   - Lanthanides: a pastel hue wheel sweeping La→Lu (each distinct, family-recognizable).
   - Actinides: the same wheel darkened + irradiated (glow does heavy lifting).
   - Superheavies (Z≥104): exotic "synthetic" palette of desaturated neons/magentas.
   - Remaining transition/post-transition metals: spaced metallic tints (warm
     bronze-greys, cool cyan-steels, violet-steels) at guaranteed pairwise distance.

## Section 3: Compound curation (153)

Full hand pass, one deliberate color each, derivation recorded. Association sources in
priority order:

1. **Genuine known color, re-verified (~25):** copper sulfate blue, permanganate deep
   violet, dichromate orange, rust red, NO₂ brown... then pushed into the luminance band
   (e.g. permanganate `#4B0F52` is likely too dark to survive the tint).
2. **Evocation (~30):** what the substance summons: citric → citrus green-gold,
   lactic → cream, formic → ant-amber, caffeine → coffee-brown (kept light enough to
   read), hydrogen peroxide → fizzy pale aqua, hypochlorite → chlorinated pool cyan.
3. **Constituent chemistry (~40):** salts carry their metal's flame/ion hue diluted
   toward crystalline pallor (NaCl faint warm orange-white, KCl faint lilac-white);
   acids carry their anion's character (HCl sharp pale green, H₂SO₄ oily yellow-tinge,
   HNO₃ fuming amber). Still light, still "white-family", but distinct whites that echo
   their parents.
4. **Functional fallback:** anything still characterless gets a subtle family tint by
   `compoundType`, spaced by the distinctness check.

Radioactive compounds (UO₂, PuO₂, Cs-137 chloride...) keep their real dark-oxide
identities where true: glow, not color, carries their danger signal.

## Section 4: Glow derivation (nuclear data → light)

No new data fields. A pure derivation function (`impl.assetgen`, TDD) computes a
**glow tier** per substance from what the registry already holds:

- **Elements** (via their isotope list): any stable isotope → **none**. No stable
  isotopes but geologically long-lived (Th, U) → **faint**. Properly hot
  (Tc, Pm, Po, Rn, Ra, Ac...) → **strong**. Synthetic short-lived (roughly Z≥100) →
  **fierce**. Exception (user decision 2026-06-05): phosphorus glows FAINT as a curated
  chemiluminescent exception: the only non-nuclear glow: implemented as an explicit
  override map in GlowDeriver, not data.
- **Compounds** (`IsRadioactive`, already isotope-aware): false → none; true → tier from
  the referenced isotope's stability data when resolvable, else strong.

**Glow hue = the substance's own `Color`.** No second color to maintain: the palette
already carries the fantasy (radium green, uranium-glass green), so glow stays coherent
and data-driven.

Tier maps to two render levers:

| Tier   | Liquid texture                              | Placed-jar light                  |
|--------|---------------------------------------------|-----------------------------------|
| none   | normal tint, liquid drops to `flat` shading | no `Light`                        |
| faint  | tint + mild luminance boost, fullbright     | dim `BlockType.Light`, substance color |
| strong | boosted saturation + luminance, fullbright  | clear radius, substance color     |
| fierce | near-white-hot core ramp, fullbright        | bright, substance color           |

The first row is the sleeper change: today every jar's liquid is fullbright, so glow
means nothing. Ship **two model variants** (`Solid.blockymodel` with flat liquid,
`SolidGlow.blockymodel` with fullbright liquid: a one-cube `shadingMode` difference);
the generator picks the model and emits the `Light` block per tier.

## Section 5: Validation & deliverables

**Single source of truth for the curation:** a derivation table (markdown or CSV under
`docs/plans/`): one row per substance: id, tier/source, hex, one-line rationale. A script
applies it to `elements.json`/`compounds.json`: the JSONs are never hand-edited and the
pass is re-runnable and reviewable as a diff.

Automated checks:

1. **Coverage:** all 118 + 153 have a table row.
2. **Quality gates:** luminance band, saturation floor, minimum pairwise distance, with
   a documented exception list.
3. **Schema integrity:** the existing full-dataset decode test (944 records) stays
   green; round-trip preserves everything but `Color`.
4. **Glow unit tests:** `GlowDeriver` pinned on the tricky cases: Bi → none (the dataset
   marks Bi-209 observationally stable, same rule as W-180: the design's original
   'Bi → faint' assumption was contradicted by the data), Tc/Pm (no stable isotopes),
   W-180-style "observationally stable"
   stays none (the README's non-monotonicity warning applies), compounds with and
   without resolvable isotopes.

**Eyeball check before the game:** a contact sheet: all 205 jar textures (and icons)
tiled in one PNG, sorted by atomic number / compound type. One glance answers "is this
varied enough" before any devServer cycle.

**In-game:** regenerate assets, clear stale `build/resources/main`, devServer boot
clean, then spot-check: a glow jar of each tier in a dark room; distinctness of
former-grey neighbors (Fe vs Ni vs Co).

**Deliverables:**

1. Palette derivation table + apply script
2. Recolored `elements.json` + `compounds.json`
3. `data/README.md` + stale `Notes` updates
4. `GlowDeriver` + generator changes (model pick, `Light` emission, tier texture boost)
5. `SolidGlow.blockymodel` second model variant
6. Contact sheet generator
7. Tests (quality gates, glow tiers, schema round-trip)

## Non-changes (explicit)

- Isotope records: untouched (no color field, by design).
- Substance schema / Java beans / codecs: untouched.
- The 673-isotope dataset and all nuclear data: untouched.
- `Solid.blockymodel` geometry/UVs: untouched (only the new variant's one
  `shadingMode` differs).
