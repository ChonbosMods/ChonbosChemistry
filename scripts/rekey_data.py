#!/usr/bin/env python3
"""
Re-key data/*.json from the research-format lowercase/camelCase keys to the PascalCase keys
Hytale's KeyedCodec requires (uppercase-first), per docs/plans/2026-06-01-api-substance-schema-design.md.

- Renames object keys via an explicit map (recursing nested objects and arrays).
- Preserves `composition` sub-keys verbatim (those are element symbols, i.e. data, not field names).
- Drops null-valued keys, so "absent" cleanly means "unknown" for the optional codec fields.

Validation: record counts unchanged, and the multiset of non-null leaf values unchanged.
Idempotent-ish: running twice is a no-op for already-PascalCase keys (they map to themselves).
"""
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

DATA = Path(__file__).resolve().parent.parent / "data"

KEYMAP = {
    # shared
    "name": "Name", "symbol": "Symbol", "formula": "Formula", "notes": "Notes",
    "value_confidence": "ValueConfidence", "appearance": "Appearance",
    "phaseAtSTP": "Phase", "density_g_per_cm3": "Density",
    "representativeColorHex": "Color",
    # element
    "atomicNumber": "AtomicNumber", "standardAtomicWeight_u": "StandardAtomicWeight",
    "group": "Group", "period": "Period", "block": "Block", "category": "Category",
    "meltingPoint_K": "MeltingPoint", "boilingPoint_K": "BoilingPoint",
    "electronegativity_pauling": "Electronegativity", "commonOxidationStates": "OxidationStates",
    "reactivityNote": "ReactivityNote", "abundanceNote": "AbundanceNote",
    "isotopeSymbols": "IsotopeSymbols",
    # isotope
    "parentSymbol": "ParentSymbol", "parentZ": "ParentZ", "massNumber": "MassNumber",
    "isotopeSymbol": "IsotopeSymbol", "naturalAbundance_percent": "NaturalAbundance",
    "stability": "Stability", "halfLife": "HalfLife", "halfLife_seconds": "HalfLifeSeconds",
    "decayModes": "DecayModes", "dominantEmission": "DominantEmission",
    "suggestedRadiationVector": "RadiationVector",
    "specificActivity_Bq_per_g": "SpecificActivity", "decayHeat_W_per_g": "DecayHeat",
    "nuclearFlags": "NuclearFlags",
    # decay mode
    "mode": "Mode", "branching_percent": "Branching", "daughter": "Daughter",
    "decayEnergy_MeV": "DecayEnergy",
    # nuclear flags
    "fissile": "Fissile", "fertile": "Fertile", "fusionable": "Fusionable",
    # compound
    "composition": "Composition", "molarMass_g_per_mol": "MolarMass",
    "compoundType": "CompoundType", "waterSolubility": "WaterSolubility",
    "propertyFlags": "PropertyFlags", "toxicity": "Toxicity", "isRadioactive": "IsRadioactive",
    # property flags
    "corrosive": "Corrosive", "flammable": "Flammable", "oxidizer": "Oxidizer",
    "volatile": "Volatile", "explosive": "Explosive", "waterSoluble": "WaterSoluble",
    # toxicity
    "route": "Route", "potencyNote": "PotencyNote", "effect": "Effect", "onset": "Onset",
    "durationNote": "DurationNote", "bioaccumulation": "Bioaccumulation", "antidoteNote": "AntidoteNote",
}

# Keys whose VALUE is a map of data (element symbols), not field names: preserve sub-keys verbatim.
OPAQUE_KEYS = {"composition"}

unknown_keys = set()


def rekey(obj, opaque=False):
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if v is None:
                continue  # drop null-valued keys
            if opaque:
                out[k] = v  # preserve data keys verbatim
                continue
            if k in KEYMAP:
                nk = KEYMAP[k]
            elif k[:1].isupper():
                nk = k  # already PascalCase (idempotent)
            else:
                unknown_keys.add(k)
                nk = k
            out[nk] = rekey(v, opaque=(k in OPAQUE_KEYS))
        return out
    if isinstance(obj, list):
        return [rekey(x, opaque=opaque) for x in obj]
    return obj


def leaf_values(obj):
    """Multiset of non-null leaf scalar values (keys ignored)."""
    out = Counter()
    if isinstance(obj, dict):
        for v in obj.values():
            out += leaf_values(v)
    elif isinstance(obj, list):
        for x in obj:
            out += leaf_values(x)
    elif obj is not None:
        out[repr(obj)] += 1
    return out


def process(path):
    records = json.loads(path.read_text())
    before_vals = leaf_values(records)
    transformed = [rekey(r) for r in records]
    after_vals = leaf_values(transformed)
    if before_vals != after_vals:
        diff_removed = before_vals - after_vals
        diff_added = after_vals - before_vals
        sys.exit(f"VALUE MISMATCH in {path.name}: removed={list(diff_removed)[:5]} added={list(diff_added)[:5]}")
    path.write_text(json.dumps(transformed, indent=2) + "\n")
    return len(records)


def main():
    files = [DATA / "elements.json", DATA / "compounds.json"] + sorted((DATA / "isotopes").glob("*.json"))
    total = 0
    for f in files:
        n = process(f)
        total += n
        print(f"  {f.relative_to(DATA.parent)}: {n} records re-keyed")
    print(f"total records: {total}")
    if unknown_keys:
        print(f"WARNING: keys not in KEYMAP (left unchanged): {sorted(unknown_keys)}")
    else:
        print("all keys mapped (no unknowns)")


if __name__ == "__main__":
    main()
