# Chemistry source data

Raw scientific reference data for Chonbo's Chemistry. This is the **source of truth** the
substance model deserializes into. It is curated reference data, not yet trimmed to the
gameplay MVP roster: expect a curation pass before it feeds `api.substance`.

## Contents

| File | Records | Notes |
|---|---|---|
| `elements.json` | 118 | All elements, Z 1–118. |
| `isotopes/batchA_Z1-20.json` | 64 | |
| `isotopes/batchB_Z21-40.json` | 123 | |
| `isotopes/batchC_Z41-60.json` | 153 | |
| `isotopes/batchD_Z61-80.json` | 175 | |
| `isotopes/batchE_Z81-100.json` | 109 | Actinides; fissile/fertile flags and RTG decay heat. |
| `isotopes/batchF_Z101-118.json` | 49 | Superheavies; all `estimated` confidence. |
| **Total isotopes** | **673** | Every isotope referenced by `elements.json` `isotopeSymbols` exists here (verified, no orphans). |
| `compounds.json` | 153 | Acids, bases, salts, oxides, alloys, organics + notable poisons. Molar mass verified consistent with composition; includes the toxicity lens (route/potency/effect/onset/antidote) and isotopically-specific radioactive forms (UO₂, UF₆, PuO₂, Cs-137 chloride, …). |

## Provenance

Compiled via guided research (claude.ai) against authoritative sources: NIST, IUPAC, the
CRC Handbook, NUBASE2020 / AME2020 (nuclear data), and PubChem. Every record carries a
`value_confidence` of `measured` or `estimated`; estimated values cluster on the synthetic
superheavies (batch F) plus a few predicted properties. Each batch was validated for schema
conformance, link integrity, internal consistency, and accuracy spot-checks before landing here.

## Schema notes & conventions

- **`representativeColorHex`** is a first-class, required field, not cosmetic: it is the
  generative key for texture tinting (see design doc §7).
- **`suggestedRadiationVector`** (`penetrating` | `contact-only` | `none`) is a **documented
  game hint, not a shielding calculation.** It maps real decay physics to the design's one-bit
  simplification: alpha and low-energy beta → contact-only; gamma, neutron, high-energy beta,
  X-ray → penetrating; gamma-significant nuclides get a curated `penetrating` override. It does
  not model every associated daughter gamma — treat any β⁻/EC `contact-only` as "the charged
  particle is the dominant near-field source; verify gamma before trusting it for shielding."
- **`decayEnergy_MeV`** is the total decay Q-value for every mode (consistent definition,
  computed from AME2020 atomic masses).
- **`stability`** follows the NUBASE "observationally stable" convention: a nuclide stays
  `stable` until its decay is firmly measured. This is **non-monotonic in half-life** (e.g.
  V-50 at 1.5×10¹⁷ y is `radioactive`, while W-180 at 1.8×10¹⁸ y is `stable`). Downstream code
  must key off the `stability` flag directly and must NOT assume `radioactive ⇒ shorter-lived`.
- **Be-10 half-life** uses the modern 1.387 My value (Korschinek/Chmeleff 2010), superseding
  the older 1.51 My figure; `specificActivity` and `decayHeat` are derived accordingly.

- **`compoundType`** is typed by functional category (acid/base/salt/oxide/alloy/organic),
  preferring the specific descriptor over the generic `molecular`/`ionic` — so those two enum
  values go unused (H₂O→oxide, NH₃→base, CH₄→organic, NaCl→salt). `other` holds notable poisons
  that fit no functional bucket. Downstream code should not expect `molecular`/`ionic` to appear.
- **`isRadioactive`** on compounds is isotope-aware, not element-aware: a compound is flagged
  radioactive when its *form* is (e.g. Cs-137 chloride is `true` though elemental Cs has a stable
  isotope). Do not infer it from constituent element symbols alone.

## Pending

- **Curation** — trim to the MVP substance roster; the full set is intentionally broad.
