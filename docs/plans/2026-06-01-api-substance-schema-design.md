> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# api.substance Schema Design

**Date:** 2026-06-01
**Status:** Validated, ready to implement
**Scope:** The `com.chonbosmods.chemistry.api.substance` type layer + codecs that `data/*.json` deserializes into. The registry (loading, reference resolution, tag queries) is a separate follow-up.

---

## 1. Context

`data/` holds a validated reference dataset: 118 elements, 673 isotopes, 153 compounds (PascalCase re-keyed, see §7). This deliverable stands up the typed contract that data deserializes into, under the foundation's governing rule (`api` defines, `impl` decides). The types are the contract third parties build against; they carry zero concrete values.

---

## 2. Decisions & rationale

### 2.1 Mutable beans, getters-only (not records)
Hytale's `BuilderCodec` is built around a no-arg constructor plus field setters (`BuilderCodec.builder(T.class, T::new).append(key, setter, getter)`). Records have neither. Records + Hytale codecs would force a mutable `Builder` bean per type plus a deprecated `FunctionCodec` adapter: *more* boilerplate than records were meant to save. So the substance types are `final` classes with private fields, **public getters only**, and package-private setters the codec drives. Effectively immutable to consumers, idiomatic Hytale, zero adapter boilerplate.

Exception: value types serialized via a single-string adapter (not field-by-field `BuilderCodec`) may stay immutable records. `Color` is the case (§6).

### 2.2 Hytale Codecs for serialization
Chosen over plain JSON (Gson/Jackson) because the foundation's pitch is data-driven + third-party-extensible + code-free authoring. Codecs unlock JSON schema generation (IDE autocomplete/validation for third-party substance files), asset-pipeline integration, and platform-native validation. The coupling to Hytale's volatile codec API is exactly what `api.shim` (§2.3 of the foundation design) exists to buffer.

### 2.3 Re-key data to PascalCase
`KeyedCodec` enforces uppercase-first JSON keys (`"name"` throws). The reference data uses lowercase/camelCase. Resolution: a one-time transform (§7) re-keys `data/*.json` to PascalCase, so the reference data *is* the canonical Hytale asset format: directly codec-loadable, schema-gen works, third parties extend in the same format. Data *values* stay human-readable (see §5 enum mapping); only *keys* change.

### 2.4 Type hierarchy: `sealed abstract class Substance permits Element, Compound`; `Isotope` standalone
**`Isotope` is element metadata, not a `Substance`.** The holding reason is **field divergence + ontology**, not forms-generator behavior: an isotope shares ~none of `Substance`'s state. `phase`, `color`, `density`, `molarMass` are bulk properties of the *parent element's material*, not of a nuclide. Inheriting `Substance` would mean borrowing or nulling six fields, then bolting on the nuclear data that is the isotope's actual identity: the textbook signal for composition over inheritance. Ontologically an isotope is a *refinement of one Element*, not a peer of Element/Compound. It is stored as metadata referenced by `parentSymbol`.

(The "673 isotopes would generate junk item forms" argument is deliberately *not* the rationale: that is a property of how the forms generator is written, not of the type hierarchy, and is dissolved by §2.5 regardless.)

**`Substance` is an abstract class, not an interface,** because it carries state: the §3.1 shared fields plus getters. Java interfaces cannot hold instance fields, so an interface would push that storage down into Element and Compound, duplicating it and defeating the uniform-operation goal the base exists to serve.

**Sealed.** Three reasons: (1) the design's extension model is data-driven (new elements/compounds as JSON) and interface-driven (implement `RadiationSource`/payload/containment contracts), never new `Substance` *subtypes*; (2) exhaustiveness in derivation/forms/codec switches; (3) it is the semver-safe direction: on a strictly-versioned `api`, sealed→unsealed is a non-breaking relaxation available later if a real need appears, while unsealed→sealed is a breaking change that locks out existing subclasses. Sealing now keeps the reversible door.

**Escape hatch (not built now):** if a substance type ever needs a different parent (e.g. a `Mixture`), migrate to interface-plus-skeleton (`interface Substance` for the contract + `abstract class AbstractSubstance implements Substance` for shared state, à la `AbstractList`).

### 2.5 `producesItemForm` is NOT on the bean
Whether a nuclide earns its own item is a curation/gameplay decision, not nuclear data, so it would violate the pure-data principle of the bean. It lives in the **forms-generator config (`impl`)** as a `Map<String, ItemFormSpec>` keyed by isotope symbol. It is expected to outgrow a boolean immediately (Co-60 wants a "source" form; H-3 wants a display name that is not "H-3"), so `ItemFormSpec` is a small object (which forms, name override), not a flag. This concern is entirely outside `api.substance` and outside this deliverable.

### 2.6 Codec lives in `api.substance`
Package-private setters force each `CODEC` into the same package as its bean. This is intended (the codec is part of the contract), but note plainly: the beans are "pure data" *relative to a package-mate codec*, not in an absolute sense.

---

## 3. Type catalog

All in `com.chonbosmods.chemistry.api.substance` unless noted.

### `Substance` (sealed abstract base, §3.1 shared attributes)
| Field | Type | JSON key (per subtype) |
|---|---|---|
| name | String | `Name` |
| formula | String | `Symbol` (Element) / `Formula` (Compound) |
| phase | `Phase` | `Phase` |
| color | `Color` | `Color` |
| density | Double (nullable) | `Density` |
| molarMass | double | `StandardAtomicWeight` (Element) / `MolarMass` (Compound) |

`molarMass` is one shared field; for an element it is the standard atomic weight (numerically identical), for a compound it is derived from composition. Domain-meaningful key names are preserved per subtype (the KeyedCodec key and the bean field are independent).

### `Element extends Substance`
`atomicNumber` (int), `group` (Integer, nullable for f-block), `period` (int), `block` (`PeriodicBlock`), `category` (`ElementCategory`), `oxidationStates` (List<Integer>), `electronegativity` (Double, nullable), `meltingPoint` (Double, nullable), `boilingPoint` (Double, nullable), `appearance` (String), `reactivityNote` (String), `abundanceNote` (String), `isotopeSymbols` (List<String>), `notes` (String), `valueConfidence` (`ValueConfidence`).

### `Compound extends Substance`
`composition` (Map<String,Integer>, element-symbol→count), `compoundType` (`CompoundType`), `propertyFlags` (`PropertyFlags`), `toxicity` (`Toxicity`), `waterSolubility` (String), `isRadioactive` (boolean), `appearance` (String), `notes` (String), `valueConfidence` (`ValueConfidence`).

### `Isotope` (standalone metadata)
`parentSymbol` (String), `parentZ` (int), `massNumber` (int), `isotopeSymbol` (String), `naturalAbundance` (double), `stability` (`Stability`), `halfLife` (String, human-readable), `halfLifeSeconds` (Double, nullable for stable), `decayModes` (List<`DecayMode`>), `dominantEmission` (`DominantEmission`), `radiationVector` (`RadiationVector`), `specificActivity` (Double, nullable), `decayHeat` (Double, nullable), `nuclearFlags` (`NuclearFlags`), `notes` (String), `valueConfidence` (`ValueConfidence`).

**Reference handling:** beans store raw symbol strings (`isotopeSymbols`, `composition` keys, `DecayMode.daughter`, `parentSymbol`). Resolving symbol→object is the registry's job (next deliverable), keeping these beans free of load-order coupling.

---

## 4. Enums

`api` enums, each carrying an explicit `jsonValue`:

- `Phase`: solid, liquid, gas
- `ElementCategory` (10): alkali metal, alkaline earth metal, transition metal, post-transition metal, metalloid, nonmetal, halogen, noble gas, lanthanide, actinide
- `PeriodicBlock`: s, p, d, f
- `CompoundType` (9): molecular, ionic, acid, base, salt, oxide, alloy, organic, other (molecular/ionic currently unused by the data but part of the contract)
- `Stability`: stable, radioactive
- `RadiationVector`: penetrating, contact-only, none
- `DominantEmission`: alpha, beta-minus, beta-plus, gamma, neutron, electron-capture, X-ray, none
- `ExposureRoute`: inhale, ingest, contact
- `Onset`: acute, delayed, chronic, acute/chronic (the last is a composite "both" value; the real compound data uses all four — discovered during the integration test)
- `ValueConfidence`: measured, estimated

The wire values are human-readable and do not match Java constant names. Each enum carries an explicit `jsonValue` and maps via a reusable **`StringMappedCodec<E>`** in `api.shim` (wraps `Codec.STRING` + a `Map<String,E>` lookup; avoids the deprecated `FunctionCodec`). Data stays readable; the Java side stays typed.

**Confirmed (de-risk spike, 2026-06-01):** `EnumCodec`'s default `CAMEL_CASE` style formats constants to PascalCase concatenations (`SOLID`→`"Solid"`, `BETA_MINUS`→`"BetaMinus"`, `CONTACT_ONLY`→`"ContactOnly"`) and matches case-sensitively. The spike showed it accepts only `"Solid"` and rejects `"solid"`/`"SOLID"`. It cannot read our readable wire values, so `StringMappedCodec` is the chosen path (not a fallback).

---

## 5. Nested value types

- **`DecayMode`** (bean): `mode` (String — composite values like `"electron-capture + minor beta-plus"` exist, so not an enum), `branching` (double), `daughter` (String), `decayEnergy` (double). List via `ArrayCodec`.
- **`NuclearFlags`** (bean): `fissile`, `fertile`, `fusionable` (booleans).
- **`PropertyFlags`** (bean): `corrosive`, `flammable`, `oxidizer`, `volatile`, `explosive`, `waterSoluble` (booleans).
- **`Toxicity`** (bean): `route` (List<`ExposureRoute`>), `potencyNote` (String), `effect` (String), `onset` (`Onset`), `durationNote` (String), `bioaccumulation` (String), `antidoteNote` (String).
- **`Color`** (**record**, not a bean): the generative key. Mapped from a single hex string via a string-codec (`StringMappedCodec`-style), not field-by-field `BuilderCodec`, so the beans-not-records rule does not apply. Exposes `r/g/b` for texture tinting. Generalizes: single-string-adapted values stay immutable records.
- `composition` → `Map<String,Integer>` via `MapCodec` (map *keys* are element symbols, i.e. data values, so the uppercase-key rule does not apply to them); `oxidationStates`/`isotopeSymbols` → array codecs.

---

## 6. Codecs

Each bean exposes `public static final BuilderCodec<T> CODEC`, in `api.substance`. `Color` and the enums use `StringMappedCodec`. `Substance`'s shared fields go in an abstract parent codec via **`BuilderCodec.abstractBuilder(Substance.class)`** (confirmed to exist in 0.5.3), and each leaf is `BuilderCodec.builder(Element.class, Element::new, Substance.ABSTRACT_CODEC).append(...)`. This uses Hytale's own parent-codec inheritance for the shared fields rather than a hand-rolled helper.

---

## 7. Re-keying transform

Explicit PascalCase per field, units dropped from keys into doc comments. Representative map (full map lives in the transform script):

| Old (data) | New key |
|---|---|
| name | Name |
| symbol | Symbol |
| formula | Formula |
| atomicNumber | AtomicNumber |
| standardAtomicWeight_u | StandardAtomicWeight |
| molarMass_g_per_mol | MolarMass |
| phaseAtSTP | Phase |
| density_g_per_cm3 | Density |
| meltingPoint_K | MeltingPoint |
| boilingPoint_K | BoilingPoint |
| representativeColorHex | Color |
| electronegativity_pauling | Electronegativity |
| commonOxidationStates | OxidationStates |
| reactivityNote | ReactivityNote |
| abundanceNote | AbundanceNote |
| isotopeSymbols | IsotopeSymbols |
| value_confidence | ValueConfidence |
| halfLife_seconds | HalfLifeSeconds |
| naturalAbundance_percent | NaturalAbundance |
| specificActivity_Bq_per_g | SpecificActivity |
| decayHeat_W_per_g | DecayHeat |
| dominantEmission | DominantEmission |
| suggestedRadiationVector | RadiationVector |
| nuclearFlags | NuclearFlags |
| decayModes[].mode/branching_percent/daughter/decayEnergy_MeV | Mode / Branching / Daughter / DecayEnergy |
| propertyFlags{...} | PropertyFlags{Corrosive, Flammable, Oxidizer, Volatile, Explosive, WaterSoluble} |
| toxicity{route/potencyNote/effect/onset/durationNote/bioaccumulation/antidoteNote} | Toxicity{Route, PotencyNote, Effect, Onset, DurationNote, Bioaccumulation, AntidoteNote} |
| isRadioactive | IsRadioactive |
| waterSolubility | WaterSolubility |
| composition (keys) | unchanged (element symbols, not codec field keys) |

The transform recurses into nested objects and arrays. Validation: record counts unchanged, and a content hash of the *values* (keys excluded) unchanged pre/post. Then re-commit `data/`.

---

## 8. Testing (TDD, written first)

For each `data/*.json` file, decode every record through its `CODEC` and assert:
1. **Counts:** 118 elements / 673 isotopes / 153 compounds.
2. **Spot values:** Fe `atomicNumber`=26 and category transition metal; U-235 `fissile`=true; H₂O `molarMass`≈18.015; Be-10 `halfLife`="1.387 My"; CuSO₄ `Color` ≈ blue.
3. **Round-trip:** decode→encode→decode on a sample of each type, asserting symmetry.

**Confirmed (de-risk spike, 2026-06-01):** the standalone decode entry point is `codec.decodeJson(RawJsonReader.fromJsonString(json) | RawJsonReader.fromPath(path, buffer), EmptyExtraInfo.EMPTY)`. The spike decoded a `BuilderCodec` bean in a plain run with no server bootstrap (and `encode` round-tripped). The 2-arg `decodeJson(reader, extraInfo)` is the non-deprecated form; `EmptyExtraInfo.EMPTY` is deprecated but functional for tests/loading. No special harness needed. (Keep validators minimal: asset-cross-reference validators would reach for registries; plain fields do not.)

---

## 9. Out of scope (follow-ups)

- **Registry** (`api.registry` contract + `impl`): loading `data/`, resolving symbol references, tag queries ("all fissile", "all corrosive").
- **Forms generator** + `ItemFormSpec` curation (§2.5).
- **Derivation** logic (molar mass / compound radioactivity / toxicity from constituents) beyond what the stored data already carries.
- **Curation** to the MVP roster.
