# Substance Color & Glow Enrichment Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Recolor all 118 elements + 153 compounds with association-derived palettes (overwriting `Color` in place) and derive per-substance glow tiers from existing nuclear data, rendered as liquid-texture boost + `BlockType.Light` on the jar items.

**Architecture:** Python scripts generate reviewable palette CSVs (curated tables + deterministic fallbacks) and apply them to the data JSONs with quality gates. Java side adds a pure `GlowDeriver` (registry data → tier), a `GlowBoost` texture transform, a second jar model variant, and `Light` emission in the generated item JSON. Design doc: `docs/plans/2026-06-05-substance-color-glow-design.md`.

**Tech Stack:** Python 3 stdlib + Pillow (scripts), Java 25 + JUnit 5 (`./gradlew test`), Gradle `generateSolidSubstanceAssets` task.

**Working directory:** `.worktrees/substance-color-glow` (branch `feat/substance-color-glow`). Baseline: 262 tests, 0 failures.

**Conventions reminder:** commit messages use `:` not em dash, NO Co-Authored-By, do not push.

---

## Verified facts the implementer needs

- **Tint pipeline** (`impl/texture/SubstanceLiquidTinter.java`): `out = master * (color/255)` over `LiquidMask(15, 0, 14, 20)` on a 32x64 texture whose liquid is a 175–255 luminance ramp. Dark colors → near-black liquid; white → no visible tint.
- **Generator** (`impl/assetgen/SubstanceAssetGenerator.main`): filters `phase()==SOLID` elements+compounds (205 items), writes texture + icon + item JSON + lang. Gradle task `generateSolidSubstanceAssets` passes `assets-src/master_white.png`, `src/main/resources`, `assets-src/icon_master.png`, `assets-src/icon_liquid_mask.png`.
- **Item JSON** (`impl/assetgen/SolidSubstanceAssets.itemJson`): text-block template; `BlockType` currently has no `Light`.
- **Vanilla `BlockType.Light` shape** (surveyed 223 vanilla items): `{"Color": "#rgb"}` — 3-digit hex, one 0–15 digit per channel (72 also carry `"Radius"`; we omit it).
- **Model:** `src/main/resources/Common/Items/Chemistry/Solid.blockymodel`; the `Liquid` node (id 5) has `"shadingMode": "fullbright"`, all other cubes `"flat"`.
- **Nuclear data:** `Isotope` bean has `stability()` (`STABLE`/`RADIOACTIVE`), `halfLifeSeconds()` (nullable `Double`). `Compound` has `isRadioactive()`, `composition()` (`Map<String,Integer>` keyed by element symbol), `notes()`. `SubstanceRegistry` has `isotopesOf(Element)`, `element(String symbol)`, `isotope(String symbol)` (e.g. `"Cs-137"`). 9 radioactive compounds; all but the U/Th oxides name a specific nuclide (`Cs-137`, `Ra-226`, `Am-241`, `Pu-238`) in `Notes`.
- **Data JSONs** use PascalCase keys (`Color`, `Notes`, `IsRadioactive`...). `data/` is a resource srcDir; the full-dataset decode test will exercise recolored files automatically.
- Existing tests live under `src/test/java/com/chonbosmods/chemistry/impl/{assetgen,texture}/` — match their style.

## Locked policy values

| Policy | Value |
|---|---|
| Luminance band (HSV value) | 0.45–0.92, `exempt` column for deliberate darks (C, oxides) |
| Saturation floor (HSV) | ≥ 0.10 unless `exempt` |
| Min pairwise RGB distance | elements ≥ 28, compounds ≥ 10 (Euclidean) |
| Glow tiers | NONE / FAINT / STRONG / FIERCE |
| Element tier rule | any stable isotope → NONE; else Z≥100 → FIERCE; else longest `halfLifeSeconds` ≥ 1e16 → FAINT; else STRONG |
| Compound tier rule | `!isRadioactive` → NONE; else max(constituent element tiers, tiers of isotopes referenced in name/notes); if still NONE → STRONG |
| Isotope tier (for refs) | stable → NONE; halfLife ≥ 1e16 s → FAINT; else STRONG |
| Texture boost | FAINT `min(255, px*115/100)`; STRONG `min(255, px*135/100)`; FIERCE `px + (255-px)*35/100` |
| Light digit cap per tier | FAINT 5, STRONG 10, FIERCE 15 (scale color so max channel digit == cap) |

Sanity anchors for the tier rule: Bi → FAINT (Bi-209 ~6e26 s), Tc/Pm → STRONG, Th/U → FAINT, Ra/Po/Rn → STRONG, Fm(100)+ → FIERCE, K/W → NONE (have observationally-stable isotopes — do NOT key off half-life). UO₂ → FAINT (via U), Cs-137 chloride → STRONG (via notes ref; elemental Cs is NONE), PuO₂ → STRONG (Pu longest ~2.5e15 s < 1e16).

---

### Task 1: Palette gate library (Python, TDD)

**Files:**
- Create: `scripts/palette_lib.py`
- Create: `scripts/test_palette_lib.py`

**Step 1: Write the failing tests**

```python
# scripts/test_palette_lib.py
import unittest
from palette_lib import parse_hex, to_hex, hsv, rgb_distance, check_gates

class PaletteLibTest(unittest.TestCase):
    def test_parse_and_format_hex(self):
        self.assertEqual(parse_hex("#C0FFEE"), (192, 255, 238))
        self.assertEqual(to_hex((192, 255, 238)), "#C0FFEE")

    def test_hsv_value_and_saturation(self):
        h, s, v = hsv((255, 0, 0))
        self.assertAlmostEqual(s, 1.0)
        self.assertAlmostEqual(v, 1.0)
        _, s2, v2 = hsv((128, 128, 128))
        self.assertAlmostEqual(s2, 0.0)
        self.assertAlmostEqual(v2, 128 / 255)

    def test_rgb_distance(self):
        self.assertAlmostEqual(rgb_distance((0, 0, 0), (3, 4, 0)), 5.0)

    def test_gates_flag_violations(self):
        rows = [
            {"key": "Aa", "hex": "#202020", "exempt": ""},   # too dark, too grey
            {"key": "Bb", "hex": "#80C040", "exempt": ""},   # fine
            {"key": "Cc", "hex": "#81C041", "exempt": ""},   # too close to Bb
            {"key": "Dd", "hex": "#101010", "exempt": "iconic dark"},  # exempt: ok
        ]
        errors = check_gates(rows, min_v=0.45, max_v=0.92, min_s=0.10, min_dist=28)
        joined = "\n".join(errors)
        self.assertIn("Aa", joined)
        self.assertIn("Cc", joined)
        self.assertNotIn("Dd", joined)
        self.assertEqual(len([e for e in errors if "Bb" in e and "Cc" not in e]), 0)
```

**Step 2: Run to verify failure**

Run: `cd scripts && python3 -m unittest test_palette_lib -v`
Expected: FAIL / ImportError (`palette_lib` not found)

**Step 3: Implement**

```python
# scripts/palette_lib.py
"""Shared palette helpers: hex parsing, HSV, quality gates (design doc section 1)."""
import colorsys
import math


def parse_hex(s):
    s = s.lstrip("#")
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16))


def to_hex(rgb):
    return "#%02X%02X%02X" % rgb


def hsv(rgb):
    return colorsys.rgb_to_hsv(rgb[0] / 255, rgb[1] / 255, rgb[2] / 255)


def rgb_distance(a, b):
    return math.dist(a, b)


def check_gates(rows, min_v, max_v, min_s, min_dist):
    """rows: dicts with key/hex/exempt. Returns list of human-readable violations."""
    errors = []
    for r in rows:
        if r.get("exempt"):
            continue
        _, s, v = hsv(parse_hex(r["hex"]))
        if not (min_v <= v <= max_v):
            errors.append(f"{r['key']}: value {v:.2f} outside [{min_v}, {max_v}] ({r['hex']})")
        if s < min_s:
            errors.append(f"{r['key']}: saturation {s:.2f} below {min_s} ({r['hex']})")
    for i, a in enumerate(rows):
        for b in rows[i + 1:]:
            d = rgb_distance(parse_hex(a["hex"]), parse_hex(b["hex"]))
            if d < min_dist:
                errors.append(f"{a['key']} vs {b['key']}: distance {d:.0f} < {min_dist}")
    return errors
```

**Step 4: Run tests to verify pass**

Run: `cd scripts && python3 -m unittest test_palette_lib -v`
Expected: 4 tests PASS

**Step 5: Commit**

```bash
git add scripts/palette_lib.py scripts/test_palette_lib.py
git commit -m "feat: palette gate library (hex/hsv helpers + quality gates)"
```

---

### Task 2: Apply script (Python, TDD)

**Files:**
- Create: `scripts/apply_palette.py`
- Create: `scripts/test_apply_palette.py`

CSV format (both palettes): `key,source,hex,exempt,rationale`. For elements `key` = `Symbol`; for compounds `key` = `Name` (names are unique, formulas are not — same rule as `SolidSubstanceAssets.assetId`).

**Step 1: Write the failing tests**

```python
# scripts/test_apply_palette.py
import json
import tempfile
import unittest
from pathlib import Path
from apply_palette import apply

ELEMENTS = [
    {"Name": "Iron", "Symbol": "Fe", "Color": "#C0C0C0",
     "Notes": "colorless in pure form (white hex is a placeholder for colorless)"},
    {"Name": "Gold", "Symbol": "Au", "Color": "#FFD700", "Notes": "keep me"},
]
CSV = """key,source,hex,exempt,rationale
Fe,mineral,#9A8578,,warm iron grey
Au,verified,#F0C040,,gold
"""

class ApplyPaletteTest(unittest.TestCase):
    def _run(self, csv_text=CSV, records=ELEMENTS):
        d = Path(tempfile.mkdtemp())
        data = d / "elements.json"
        table = d / "elements.csv"
        data.write_text(json.dumps(records))
        table.write_text(csv_text)
        apply(table, data, key_field="Symbol", min_dist=10)
        return json.loads(data.read_text())

    def test_overwrites_color_in_place(self):
        out = self._run()
        self.assertEqual(out[0]["Color"], "#9A8578")
        self.assertEqual(out[1]["Color"], "#F0C040")

    def test_strips_stale_placeholder_note(self):
        out = self._run()
        self.assertNotIn("placeholder for colorless", out[0]["Notes"])
        self.assertEqual(out[1]["Notes"], "keep me")

    def test_fails_on_missing_coverage(self):
        with self.assertRaises(SystemExit):
            self._run(csv_text="key,source,hex,exempt,rationale\nFe,mineral,#9A8578,,x\n")

    def test_fails_on_unknown_key(self):
        bad = CSV + "Xx,mineral,#808080,,ghost row\n"
        with self.assertRaises(SystemExit):
            self._run(csv_text=bad)

    def test_fails_on_gate_violation(self):
        bad = CSV.replace("#9A8578", "#0A0A0A")  # too dark, not exempt
        with self.assertRaises(SystemExit):
            self._run(csv_text=bad)
```

**Step 2: Run to verify failure**

Run: `cd scripts && python3 -m unittest test_apply_palette -v`
Expected: FAIL (ImportError)

**Step 3: Implement**

```python
# scripts/apply_palette.py
"""Apply a palette CSV onto a data JSON, with quality gates.

Usage: python3 apply_palette.py <palette.csv> <data.json> <key_field> [min_dist]
The data JSONs are never hand-edited: this script is the only writer (design section 5).
"""
import csv
import json
import re
import sys
from pathlib import Path
from palette_lib import check_gates

MIN_V, MAX_V, MIN_S = 0.45, 0.92, 0.10
STALE_NOTE = re.compile(r"\s*\(?[^().]*placeholder for colorless\)?\.?")


def apply(table_path, data_path, key_field, min_dist):
    rows = list(csv.DictReader(open(table_path)))
    records = json.loads(Path(data_path).read_text())

    keys_in_data = {r[key_field] for r in records}
    keys_in_table = [r["key"] for r in rows]
    dupes = {k for k in keys_in_table if keys_in_table.count(k) > 1}
    missing = keys_in_data - set(keys_in_table)
    unknown = set(keys_in_table) - keys_in_data
    errors = []
    if dupes:
        errors.append(f"duplicate table keys: {sorted(dupes)}")
    if missing:
        errors.append(f"records with no palette row: {sorted(missing)}")
    if unknown:
        errors.append(f"palette rows matching no record: {sorted(unknown)}")
    errors += check_gates(rows, MIN_V, MAX_V, MIN_S, min_dist)
    if errors:
        sys.exit("PALETTE REJECTED:\n  " + "\n  ".join(errors))

    by_key = {r["key"]: r for r in rows}
    for rec in records:
        rec["Color"] = by_key[rec[key_field]]["hex"]
        note = rec.get("Notes") or ""
        if "placeholder for colorless" in note:
            rec["Notes"] = STALE_NOTE.sub("", note).strip(" ;,")
    Path(data_path).write_text(json.dumps(records, indent=1, ensure_ascii=False) + "\n")
    print(f"applied {len(rows)} colors -> {data_path}")


if __name__ == "__main__":
    apply(sys.argv[1], sys.argv[2], sys.argv[3],
          int(sys.argv[4]) if len(sys.argv) > 4 else 28)
```

**Step 4: Run tests to verify pass**

Run: `cd scripts && python3 -m unittest test_apply_palette -v`
Expected: 5 tests PASS

> **Check before relying on it:** confirm the data JSON's existing indent style (`python3 -c "print(open('data/elements.json').read()[:200])"`). If it differs from `indent=1`, match the existing style in `apply()` so the recolor diff is Color/Notes-only. If the existing file is minified or differently formatted, adjust `json.dumps` args accordingly and note it in the commit message.

**Step 5: Commit**

```bash
git add scripts/apply_palette.py scripts/test_apply_palette.py
git commit -m "feat: palette apply script with coverage + quality gates"
```

---

### Task 3: Element palette generation + recolor

**Files:**
- Create: `scripts/gen_element_palette.py`
- Create: `data/palette/elements.csv` (generated, committed for review)
- Modify: `data/elements.json` (via apply script only)

**Step 1: Write the generator.** CURATED holds every hand-derived element (design section 2 ladder, tiers re-verified — nothing grandfathered). Everything absent falls to the systematic generator. The full curated table:

```python
# scripts/gen_element_palette.py
"""Generate data/palette/elements.csv: curated associations + systematic fallback.

Single source of truth for element colors (design doc section 2). Re-run to regenerate;
hand-edit CURATED here, never the CSV.
"""
import csv
import json
import colorsys
from pathlib import Path
from palette_lib import to_hex

# symbol: (source, hex, exempt_reason_or_empty, rationale)
CURATED = {
    # -- gases: discharge / liquid-phase colors --
    "H":  ("discharge", "#BFE3FF", "", "pale ice blue, lightest gas"),
    "He": ("discharge", "#FFD9A8", "", "peach discharge glow"),
    "N":  ("evocation", "#AFC8DC", "", "cryogenic frost blue"),
    "O":  ("verified",  "#8FB8E8", "", "liquid oxygen is genuinely blue"),
    "F":  ("verified",  "#D8E85A", "", "pale acid yellow-green gas"),
    "Ne": ("discharge", "#FF6B3D", "", "neon sign red-orange"),
    "Ar": ("discharge", "#B48CFF", "", "violet discharge"),
    "Kr": ("discharge", "#C8F0D8", "", "ghostly green-white discharge"),
    "Xe": ("discharge", "#A8D8FF", "", "ice blue discharge"),
    "Rn": ("discharge", "#6FB8A8", "", "ominous pale teal, the radioactive gas"),
    "Cl": ("verified",  "#B8D832", "", "yellow-green gas (chloros)"),
    # -- nonmetals / metalloids --
    "C":  ("verified",  "#2B2B30", "iconic dark", "graphite near-black"),
    "P":  ("verified",  "#E0E8A8", "", "white phosphorus waxy pale yellow"),
    "S":  ("verified",  "#F0D020", "", "bright sulfur yellow"),
    "Se": ("verified",  "#C86A50", "", "red selenium allotrope"),
    "B":  ("mineral",   "#9A7A50", "", "brown amorphous boron"),
    "Si": ("mineral",   "#7A85A0", "", "blue-grey crystalline sheen"),
    "Ge": ("mineral",   "#98A8B8", "", "cool silver-blue semimetal"),
    "As": ("evocation", "#7FA86A", "", "Paris green, the poisoner's pigment"),
    "Br": ("verified",  "#B84A1E", "", "rust-red fuming liquid, brightened to band"),
    "I":  ("verified",  "#8A5FAF", "", "violet vapor (iodes), brightened to band"),
    "At": ("fantasy",   "#6B4585", "", "dusk-purple void halogen, never seen in bulk"),
    # -- alkali: flame tests + etymology --
    "Li": ("flame",     "#E8485A", "", "crimson flame"),
    "Na": ("flame",     "#FFA53D", "", "sodium-lamp orange"),
    "K":  ("flame",     "#C89AE8", "", "lilac flame"),
    "Rb": ("spectral",  "#C03A4A", "", "rubidus: deep red spectral lines"),
    "Cs": ("spectral",  "#6FA8E8", "", "caesius: sky blue spectral lines"),
    "Fr": ("fantasy",   "#A04A78", "", "doomed alkali, magenta-ash"),
    # -- alkaline earth: flame tests + minerals --
    "Be": ("mineral",   "#6ACB8A", "", "emerald/beryl green"),
    "Mg": ("flame",     "#D0E0F0", "", "blinding white flame, icy cast"),
    "Ca": ("flame",     "#D88A50", "", "brick-red flame"),
    "Sr": ("flame",     "#E84A30", "", "scarlet flare red"),
    "Ba": ("flame",     "#8AC850", "", "apple green flame"),
    "Ra": ("fantasy",   "#7AE89A", "", "radium-dial green, doubles as glow hue"),
    # -- transition metals: ions / minerals / anodizing --
    "Ti": ("mineral",   "#C8C0E0", "", "titania white-violet"),
    "V":  ("ion",       "#4A9AA8", "", "vanadyl teal-blue"),
    "Cr": ("spectral",  "#4AB850", "", "chroma: the color element, emerald"),
    "Mn": ("ion",       "#C06A9A", "", "permanganate rose-violet"),
    "Fe": ("verified",  "#9A8578", "", "warm grey with rust undertone"),
    "Co": ("ion",       "#3A6AC8", "", "cobalt blue"),
    "Ni": ("ion",       "#8FBF6A", "", "Ni(II) apple green"),
    "Cu": ("verified",  "#C88040", "", "copper metal"),
    "Zn": ("verified",  "#BFC8D8", "", "bluish-white cast"),
    "Zr": ("mineral",   "#7AB8D8", "", "zircon gemstone blue"),
    "Nb": ("evocation", "#8A6AD8", "", "anodized niobium violet"),
    "Mo": ("mineral",   "#7A8AB8", "", "dusty molybdenite blue"),
    "Tc": ("fantasy",   "#6AA8A0", "", "synthetic cyan-ash, the first artificial element"),
    "Rh": ("spectral",  "#E08AA0", "", "rhodon: rose"),
    "Ag": ("verified",  "#E8E8F0", "low-sat metal", "bright silver"),
    "Cd": ("evocation", "#D89A28", "", "cadmium yellow pigment"),
    "In": ("spectral",  "#5A4AC8", "", "indigo spectral line"),
    "Sn": ("verified",  "#C0B8A8", "low-sat metal", "warm pewter"),
    "Ta": ("evocation", "#5A8AD8", "", "anodized tantalum blue"),
    "W":  ("mineral",   "#6A7A99", "", "wolframite steel-blue"),
    "Os": ("verified",  "#7A8A9A", "", "genuinely blue-grey metal"),
    "Ir": ("spectral",  "#9A7AC8", "", "iris: rainbow salts, violet lean"),
    "Pt": ("verified",  "#D8E0E8", "low-sat metal", "pale icy platinum"),
    "Au": ("verified",  "#FFD24A", "", "gold"),
    "Hg": ("verified",  "#C0C8D0", "low-sat metal", "quicksilver"),
    # -- post-transition --
    "Al": ("verified",  "#C8D0DC", "low-sat metal", "cool aluminium white"),
    "Tl": ("spectral",  "#7AC85A", "", "thallos: green shoot"),
    "Pb": ("verified",  "#687088", "", "dense cold blue-grey"),
    "Bi": ("evocation", "#C87AB8", "", "iridescent hopper-crystal pink"),
    "Po": ("fantasy",   "#C05A3A", "", "alpha-heat ember"),
    # -- lanthanides with real associations --
    "Ce": ("mineral",   "#E8D88A", "", "ceria pale gold"),
    "Pr": ("spectral",  "#7AC86A", "", "praseo: leek green"),
    "Nd": ("evocation", "#B87AC8", "", "neodymium glass violet-pink"),
    "Eu": ("evocation", "#E8506A", "", "the red phosphor of CRT screens"),
    "Tb": ("evocation", "#50C87A", "", "the green phosphor"),
    "Er": ("evocation", "#E89AB0", "", "erbium-pink glass"),
    # -- actinides with real associations --
    "U":  ("evocation", "#9AE85A", "", "uranium-glass green"),
    "Np": ("spectral",  "#4A6AE8", "", "Neptune blue"),
    "Pu": ("evocation", "#6A9AE8", "", "Cherenkov blue"),
}

# Lanthanides/actinides absent from CURATED sweep a pastel hue wheel (design section 2 tier 6).
LANTHANIDE_Z = range(57, 72)   # La..Lu
ACTINIDE_Z = range(89, 104)    # Ac..Lr
SUPERHEAVY_HUES = [300, 180, 90, 330, 210, 60, 270, 150, 30, 240, 120, 0, 285, 165, 45]


def fallback(symbol, z, leftover_index):
    if z in LANTHANIDE_Z:
        h = (z - 57) / 15
        return ("systematic", hsv_hex(h, 0.35, 0.80), "", "lanthanide pastel wheel")
    if z in ACTINIDE_Z:
        h = (z - 89) / 15
        return ("systematic", hsv_hex(h, 0.45, 0.62), "", "actinide irradiated wheel")
    if z >= 104:
        h = SUPERHEAVY_HUES[(z - 104) % len(SUPERHEAVY_HUES)] / 360
        return ("systematic", hsv_hex(h, 0.55, 0.70), "", "synthetic exotic palette")
    # remaining metals: spaced metallic tints via golden-angle hue sequence
    h = (leftover_index * 137.508) % 360 / 360
    return ("systematic", hsv_hex(h, 0.18, 0.72), "", "spaced metallic tint")


def hsv_hex(h, s, v):
    r, g, b = colorsys.hsv_to_rgb(h, s, v)
    return to_hex((round(r * 255), round(g * 255), round(b * 255)))


def main():
    elements = json.loads(Path("data/elements.json").read_text())
    out = Path("data/palette/elements.csv")
    out.parent.mkdir(parents=True, exist_ok=True)
    leftover = 0
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["key", "source", "hex", "exempt", "rationale"])
        for e in sorted(elements, key=lambda e: e["AtomicNumber"]):
            sym, z = e["Symbol"], e["AtomicNumber"]
            if sym in CURATED:
                row = CURATED[sym]
            else:
                row = fallback(sym, z, leftover)
                if row[3] == "spaced metallic tint":
                    leftover += 1
            w.writerow([sym, *row])
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
```

**Step 2: Generate and gate-check**

```bash
cd scripts && python3 gen_element_palette.py && cd ..
python3 scripts/apply_palette.py data/palette/elements.csv data/elements.json Symbol 28
```

Expected: either `applied 118 colors` or `PALETTE REJECTED` listing pairwise-distance collisions. **Iterate:** nudge CURATED hexes / fallback parameters in `gen_element_palette.py` (never the CSV) and re-run until clean. Likely collisions to expect: golden-angle tints vs curated pale metals — adjust s/v of the leftover formula first.

**Step 3: Review the diff**

Run: `git diff --stat data/elements.json && git diff data/elements.json | head -40`
Expected: only `Color` lines (plus stripped placeholder `Notes`). If anything else changed, STOP and fix the apply script's JSON formatting to match.

**Step 4: Verify schema integrity**

Run: `./gradlew test --tests '*substance*' --tests '*registry*' -q` then full `./gradlew test -q`
Expected: BUILD SUCCESSFUL, 262 tests, 0 failures (data is decoded by the full-dataset integration test).

**Step 5: Commit**

```bash
git add scripts/gen_element_palette.py data/palette/elements.csv data/elements.json
git commit -m "feat: recolor all 118 elements: association ladder + systematic fallback"
```

---

### Task 4: Compound palette generation + recolor

**Files:**
- Create: `scripts/gen_compound_palette.py`
- Create: `data/palette/compounds.csv`
- Modify: `data/compounds.json` (via apply script only)

Same shape as Task 3. CURATED dict keyed by compound `Name`. Rules (design section 3):

1. **Verified colors (re-checked, then pushed into the luminance band):** copper sulfate `#2A6FD8`, potassium permanganate `#7A2A8A` (brightened from `#4B0F52`), potassium dichromate `#E8702A`, iron(III) oxide `#B0472A`, iron(II) sulfate `#7FB77E` (keep), nitrogen dioxide `#B05A2A`, chromium(III) oxide `#3A7A2A`, lead(II) oxide `#E8B23A` (keep), water `#CFE8FF` (keep). Dark oxides (`Fe3O4`, `CuO`, `MnO2`, `UO2`, `PuO2`, `AmO2`, `U3O8`, `PbO2`, cast iron) stay dark with `exempt=iconic dark`.
2. **Evocation:** citric → `#C8E060`, lactic → `#F0E8D0`, formic → `#E0B060`, caffeine-class organics → warm coffee/cream tones, hydrogen peroxide → `#C8F0E8`, hypochlorites → pool cyan `#B8E8D8`, sulfuric acid → oily `#E8E0A0`, nitric → fuming amber `#E8C870`, hydrochloric → sharp pale green `#D0E8C0`, HF → etched frost `#D8F0E8`.
3. **Constituent echo (the ~40 white salts/bases):** compute in the generator, not by hand: `blend(base_white, parent_hue, 0.22)` where `base_white = #F2EFE6`, parent = the metal constituent's NEW color from `data/palette/elements.csv` (read it; Task 3 must land first). NaCl → faint warm orange-white, KCl → faint lilac-white, CaCl₂ → faint brick-white, automatically consistent with the element palette.
4. **Functional fallback** for anything left: per-`CompoundType` tint dict blended 12% over base white (acid `#D8E85A`, base `#8FB8E8`, salt `#C8C0A8`, oxide `#B09078`, organic `#E0C890`, alloy `#A8B0B8`, other `#C0A8C8`).

Implementation skeleton (same I/O pattern as Task 3 — CURATED first, then rule 3 for salts/bases whose composition names a curated-or-fallback metal, then rule 4):

```python
def blend(base_hex, tint_hex, pct):
    b, t = parse_hex(base_hex), parse_hex(tint_hex)
    return to_hex(tuple(round(bb + (tt - bb) * pct) for bb, tt in zip(b, t)))
```

**Steps:**

1. Write `scripts/gen_compound_palette.py` (CURATED ~60 rows covering rules 1–2 + every alloy and radioactive compound; rules 3–4 computed). Print a coverage summary by rule so the curation balance is visible.
2. `python3 scripts/gen_compound_palette.py` then `python3 scripts/apply_palette.py data/palette/compounds.csv data/compounds.json Name 10`. Iterate on REJECTED until clean (min_dist=10: distinct whites are intentionally close).
3. `git diff data/compounds.json | head -40` — Color-only diff.
4. `./gradlew test -q` — 262 green.
5. Commit: `git commit -m "feat: recolor all 153 compounds: curated associations + constituent echo"`

---

### Task 5: README + docs update

**Files:**
- Modify: `data/README.md` (the `representativeColorHex` bullet, line ~31)

**Step 1:** Replace the bullet with:

```markdown
- **`representativeColorHex`** is a first-class, required field, not cosmetic: it is the
  generative key for texture tinting (see design doc §7). As of 2026-06 it carries the
  **game color**, not strict real-world appearance: real-world-grounded where reality
  offers a color, association-derived elsewhere (spectral/etymology, flame test, gas
  discharge, ion/mineral, evocation, constituent echo, or systematic fallback). The
  derivation per substance lives in `data/palette/*.csv` (generated by
  `scripts/gen_*_palette.py`, applied by `scripts/apply_palette.py`: the JSONs are never
  hand-edited). See `docs/plans/2026-06-05-substance-color-glow-design.md`.
```

**Step 2:** Commit: `git commit -m "docs: document game-color convention for representativeColorHex"`

---

### Task 6: GlowTier + GlowDeriver (Java, TDD)

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/GlowTier.java`
- Create: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/GlowDeriver.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/assetgen/GlowDeriverTest.java`

**Step 1: Write the failing test** (uses the real registry — it loads from resources in other tests already; see `InMemorySubstanceRegistry.loadFromResources()` usage in `impl/registry` tests):

```java
package com.chonbosmods.chemistry.impl.assetgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.impl.registry.InMemorySubstanceRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GlowDeriverTest {

    private static SubstanceRegistry registry;

    @BeforeAll
    static void load() {
        registry = InMemorySubstanceRegistry.loadFromResources();
    }

    private GlowTier element(String symbol) {
        return GlowDeriver.tierFor(registry.element(symbol).orElseThrow(), registry);
    }

    private GlowTier compound(String formula) {
        return GlowDeriver.tierFor(registry.compound(formula).orElseThrow(), registry);
    }

    @Test
    void stableElementsDoNotGlow() {
        assertEquals(GlowTier.NONE, element("Fe"));
        assertEquals(GlowTier.NONE, element("Au"));
        // observationally-stable isotopes count as stable: half-life must NOT be consulted
        assertEquals(GlowTier.NONE, element("W"));
        assertEquals(GlowTier.NONE, element("K"));
    }

    @Test
    void geologicallyLongLivedGlowFaint() {
        assertEquals(GlowTier.FAINT, element("Bi"));
        assertEquals(GlowTier.FAINT, element("Th"));
        assertEquals(GlowTier.FAINT, element("U"));
    }

    @Test
    void hotElementsGlowStrong() {
        assertEquals(GlowTier.STRONG, element("Tc"));
        assertEquals(GlowTier.STRONG, element("Pm"));
        assertEquals(GlowTier.STRONG, element("Ra"));
        assertEquals(GlowTier.STRONG, element("Po"));
    }

    @Test
    void syntheticSuperheaviesGlowFierce() {
        assertEquals(GlowTier.FIERCE, element("Fm"));
        assertEquals(GlowTier.FIERCE, element("Og"));
    }

    @Test
    void nonRadioactiveCompoundsDoNotGlow() {
        assertEquals(GlowTier.NONE, compound("H2O"));
        assertEquals(GlowTier.NONE, compound("NaCl"));
    }

    @Test
    void uraniumOxideGlowsFaintViaConstituent() {
        assertEquals(GlowTier.FAINT, compound("UO2"));
    }

    @Test
    void isotopeReferenceInNotesEscalatesTier() {
        // elemental Cs is stable -> NONE, but the compound's notes name Cs-137 (30 y)
        assertEquals(GlowTier.STRONG, compound("CsCl"));
    }

    @Test
    void plutoniumDioxideGlowsStrong() {
        // Pu's longest-lived isotope (~2.5e15 s) sits below the FAINT threshold
        assertEquals(GlowTier.STRONG, compound("PuO2"));
    }
}
```

**Step 2: Run to verify failure**

Run: `./gradlew test --tests '*GlowDeriverTest' -q`
Expected: compilation FAILS (GlowTier/GlowDeriver missing)

**Step 3: Implement**

```java
// GlowTier.java
package com.chonbosmods.chemistry.impl.assetgen;

/** Visual glow intensity derived from nuclear data (design doc section 4). Order matters: ordinal = severity. */
public enum GlowTier {
    NONE,
    FAINT,
    STRONG,
    FIERCE
}
```

```java
// GlowDeriver.java
package com.chonbosmods.chemistry.impl.assetgen;

import com.chonbosmods.chemistry.api.registry.SubstanceRegistry;
import com.chonbosmods.chemistry.api.substance.Compound;
import com.chonbosmods.chemistry.api.substance.Element;
import com.chonbosmods.chemistry.api.substance.Isotope;
import com.chonbosmods.chemistry.api.substance.Stability;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure derivation of a substance's {@link GlowTier} from nuclear data already in the registry.
 * No data-schema additions: elements key off their isotope list, compounds off
 * {@code isRadioactive} plus constituent elements and any nuclide referenced in name/notes.
 */
public final class GlowDeriver {

    /** Geologic threshold: longest-lived isotope at/above this half-life reads as FAINT (Th, U, Bi). */
    static final double FAINT_HALF_LIFE_SECONDS = 1e16;
    /** Synthetic short-lived superheavies (Fm and up) read as FIERCE. */
    static final int FIERCE_MIN_Z = 100;

    private static final Pattern ISOTOPE_REF = Pattern.compile("\\b([A-Z][a-z]?-\\d{1,3})\\b");

    private GlowDeriver() {}

    public static GlowTier tierFor(Element element, SubstanceRegistry registry) {
        List<Isotope> isotopes = registry.isotopesOf(element);
        if (isotopes.isEmpty() || isotopes.stream().anyMatch(i -> i.stability() == Stability.STABLE)) {
            return GlowTier.NONE;
        }
        if (element.atomicNumber() >= FIERCE_MIN_Z) {
            return GlowTier.FIERCE;
        }
        double longest = isotopes.stream()
            .map(Isotope::halfLifeSeconds)
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0);
        return longest >= FAINT_HALF_LIFE_SECONDS ? GlowTier.FAINT : GlowTier.STRONG;
    }

    public static GlowTier tierFor(Compound compound, SubstanceRegistry registry) {
        if (!compound.isRadioactive()) {
            return GlowTier.NONE;
        }
        GlowTier best = GlowTier.NONE;
        for (String symbol : compound.composition().keySet()) {
            GlowTier t = registry.element(symbol).map(e -> tierFor(e, registry)).orElse(GlowTier.NONE);
            best = best.compareTo(t) >= 0 ? best : t;
        }
        String text = compound.name() + " " + (compound.notes() == null ? "" : compound.notes());
        Matcher m = ISOTOPE_REF.matcher(text);
        while (m.find()) {
            GlowTier t = registry.isotope(m.group(1)).map(GlowDeriver::isotopeTier).orElse(GlowTier.NONE);
            best = best.compareTo(t) >= 0 ? best : t;
        }
        // radioactive but nothing resolvable: be loud rather than silent (design section 4)
        return best == GlowTier.NONE ? GlowTier.STRONG : best;
    }

    private static GlowTier isotopeTier(Isotope isotope) {
        if (isotope.stability() == Stability.STABLE) {
            return GlowTier.NONE;
        }
        Double halfLife = isotope.halfLifeSeconds();
        return halfLife != null && halfLife >= FAINT_HALF_LIFE_SECONDS ? GlowTier.FAINT : GlowTier.STRONG;
    }
}
```

**Step 4: Run tests**

Run: `./gradlew test --tests '*GlowDeriverTest' -q`
Expected: 8 tests PASS. If a specific element lands on the wrong side of a threshold, inspect its actual data (`python3 -c "...print isotopes of X..."`) before touching the threshold — the data is the truth, the anchor table above is the intent.

**Step 5: Commit**

```bash
git add src/main/java/com/chonbosmods/chemistry/impl/assetgen/Glow* src/test/java/com/chonbosmods/chemistry/impl/assetgen/GlowDeriverTest.java
git commit -m "feat: GlowTier + GlowDeriver: glow tiers derived from nuclear data"
```

---

### Task 7: GlowBoost texture transform (Java, TDD)

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/texture/GlowBoost.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/texture/GlowBoostTest.java`

**Step 1: Failing test** (mirror `SubstanceLiquidTinterTest` style — tiny synthetic images, exact pixel asserts):

```java
package com.chonbosmods.chemistry.impl.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.impl.assetgen.GlowTier;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class GlowBoostTest {

    private static BufferedImage onePixel(int rgb) {
        BufferedImage img = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFF000000 | rgb); // inside mask
        img.setRGB(1, 0, 0xFF000000 | rgb); // outside mask
        return img;
    }

    private static final LiquidMask MASK = new LiquidMask(0, 0, 1, 1);

    @Test
    void noneIsIdentity() {
        BufferedImage out = GlowBoost.apply(onePixel(0x646464), MASK, GlowTier.NONE);
        assertEquals(0x646464, out.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void faintScales115Percent() {
        BufferedImage out = GlowBoost.apply(onePixel(0x646464), MASK, GlowTier.FAINT);
        assertEquals(0x737373, out.getRGB(0, 0) & 0xFFFFFF); // 100*115/100 = 115 = 0x73
        assertEquals(0x646464, out.getRGB(1, 0) & 0xFFFFFF); // outside mask untouched
    }

    @Test
    void strongScales135PercentAndClamps() {
        BufferedImage out = GlowBoost.apply(onePixel(0xC8C8C8), MASK, GlowTier.STRONG);
        assertEquals(0xFFFFFF, out.getRGB(0, 0) & 0xFFFFFF); // 200*135/100 = 270 -> 255
    }

    @Test
    void fierceBlendsTowardWhite() {
        BufferedImage out = GlowBoost.apply(onePixel(0x006400), MASK, GlowTier.FIERCE);
        // 0 + 255*35/100 = 89 = 0x59 ; 100 + 155*35/100 = 154 (singular: 100+54) = 0x9A
        assertEquals(0x599A59, out.getRGB(0, 0) & 0xFFFFFF);
    }
}
```

**Step 2:** Run `./gradlew test --tests '*GlowBoostTest' -q` — compilation failure expected.

**Step 3: Implement**

```java
package com.chonbosmods.chemistry.impl.texture;

import com.chonbosmods.chemistry.impl.assetgen.GlowTier;
import java.awt.image.BufferedImage;

/**
 * Tier-based brightness boost on the liquid region of an already-tinted texture (design doc
 * section 4 table): FAINT scales 1.15x, STRONG 1.35x, FIERCE blends 35% toward white-hot.
 * Pixels outside the mask are untouched. Returns a new image.
 */
public final class GlowBoost {

    private GlowBoost() {}

    public static BufferedImage apply(BufferedImage src, LiquidMask mask, GlowTier tier) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int argb = src.getRGB(x, y);
                if (tier != GlowTier.NONE && mask.contains(x, y)) {
                    int a = (argb >>> 24) & 0xFF;
                    argb = (a << 24)
                        | (boost((argb >> 16) & 0xFF, tier) << 16)
                        | (boost((argb >> 8) & 0xFF, tier) << 8)
                        | boost(argb & 0xFF, tier);
                }
                out.setRGB(x, y, argb);
            }
        }
        return out;
    }

    private static int boost(int channel, GlowTier tier) {
        int v = switch (tier) {
            case NONE -> channel;
            case FAINT -> channel * 115 / 100;
            case STRONG -> channel * 135 / 100;
            case FIERCE -> channel + (255 - channel) * 35 / 100;
        };
        return Math.min(255, v);
    }
}
```

**Step 4:** Run `./gradlew test --tests '*GlowBoostTest' -q` — 4 PASS. (Verify the FIERCE expected value against the integer math before trusting the test constant: `0 + 255*35/100 = 89`, `100 + 155*35/100 = 100+54 = 154`.)

**Step 5: Commit**

```bash
git add src/main/java/com/chonbosmods/chemistry/impl/texture/GlowBoost.java src/test/java/com/chonbosmods/chemistry/impl/texture/GlowBoostTest.java
git commit -m "feat: GlowBoost: tier-based liquid brightness boost"
```

---

### Task 8: Light JSON + model selection in item template (Java, TDD)

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/SolidSubstanceAssets.java`
- Modify: `src/test/java/com/chonbosmods/chemistry/impl/assetgen/SolidSubstanceAssetsTest.java`

**Step 1: Read the existing test file**, then add failing tests:

```java
@Test
void lightJsonScalesColorToTierCap() {
    // radium green #7AE89A: max channel 0xE8=232 -> digit cap; FAINT cap 5
    assertEquals("#235", SolidSubstanceAssets.lightJson(new Color(0x7A, 0xE8, 0x9A), GlowTier.FAINT));
    assertEquals("#5A6", SolidSubstanceAssets.lightJson(new Color(0x7A, 0xE8, 0x9A), GlowTier.STRONG));
    assertEquals("#8F9", SolidSubstanceAssets.lightJson(new Color(0x7A, 0xE8, 0x9A), GlowTier.FIERCE));
    assertNull(SolidSubstanceAssets.lightJson(new Color(0x7A, 0xE8, 0x9A), GlowTier.NONE));
}

@Test
void itemJsonEmitsLightAndGlowModelForGlowingTiers() {
    String json = SolidSubstanceAssets.itemJson("X", "tex.png", GlowTier.STRONG, "#5A6");
    assertTrue(json.contains("\"Light\": { \"Color\": \"#5A6\" }"));
    assertTrue(json.contains(SolidSubstanceAssets.MODEL_GLOW));
}

@Test
void itemJsonOmitsLightForNone() {
    String json = SolidSubstanceAssets.itemJson("X", "tex.png", GlowTier.NONE, null);
    assertFalse(json.contains("\"Light\""));
    assertTrue(json.contains(SolidSubstanceAssets.MODEL));
}
```

Math for the expected values (verify, don't trust): digit = `round(c*15/255)`, then rescale so max digit == cap: `#7AE89A` → digits (7, 14, 9); FAINT cap 5 → (round(7*5/14)=3 — wait: `7*5/14 = 2.5`, banker's vs half-up matters. **Use integer arithmetic `(d * cap + maxd / 2) / maxd`** = `(7*5+7)/14 = 3`? `42/14 = 3` exactly. Channels: r `(7*5+7)/14=3`→ hmm `7*5=35, +7=42, /14=3`; g `(14*5+7)/14=5`; b `(9*5+7)/14=3`(`52/14=3`). That yields `#353`. Recompute from raw channels instead: digit_i = `(c_i * cap + max/2) / max` with c=raw 0-255, max=232: r `(122*5+116)/232 = 726/232 = 3`; g `(232*5+116)/232 = 5`; b `(154*5+116)/232 = 886/232 = 3` → `#353`. **The test constants above are illustrative: compute the real expected values with this exact formula while writing the test, and assert those.**

**Step 2:** Run `./gradlew test --tests '*SolidSubstanceAssetsTest' -q` — FAIL (no such methods).

**Step 3: Implement.** In `SolidSubstanceAssets`:

```java
public static final String MODEL_GLOW = "Items/Chemistry/SolidGlow.blockymodel";

/** 4-bit-per-channel light color, scaled so the brightest channel hits the tier's cap. Null for NONE. */
public static String lightJson(Color c, GlowTier tier) {
    int cap = switch (tier) {
        case NONE -> 0;
        case FAINT -> 5;
        case STRONG -> 10;
        case FIERCE -> 15;
    };
    if (cap == 0) {
        return null;
    }
    int max = Math.max(c.r(), Math.max(c.g(), c.b()));
    if (max == 0) {
        return null;
    }
    return "#%X%X%X".formatted(
        (c.r() * cap + max / 2) / max,
        (c.g() * cap + max / 2) / max,
        (c.b() * cap + max / 2) / max);
}
```

Change `itemJson(String id, String texturePath)` to `itemJson(String id, String texturePath, GlowTier tier, String lightColor)`: pick `MODEL_GLOW` vs `MODEL`, and inside `BlockType` insert `"Light": { "Color": "%s" },` only when `lightColor != null` (build the optional line as a local `String light = lightColor == null ? "" : "\n    \"Light\": { \"Color\": \"%s\" },".formatted(lightColor);` and splice it into the text block). Update ALL existing callers/tests of the 2-arg form to pass `GlowTier.NONE, null` — or keep a 2-arg overload delegating with NONE (preferred: existing tests stay untouched).

**Step 4:** Run `./gradlew test --tests '*SolidSubstanceAssetsTest' -q` — all PASS, then full `./gradlew test -q`.

**Step 5: Commit**

```bash
git add -A src/main/java/com/chonbosmods/chemistry/impl/assetgen src/test/java/com/chonbosmods/chemistry/impl/assetgen
git commit -m "feat: jar item JSON emits BlockType.Light + glow model per tier"
```

---

### Task 9: Model variants (Solid = flat liquid, SolidGlow = fullbright)

**Files:**
- Modify: `src/main/resources/Common/Items/Chemistry/Solid.blockymodel`
- Create: `src/main/resources/Common/Items/Chemistry/SolidGlow.blockymodel`

The sleeper change from design section 4: today every jar's liquid is fullbright, so glow means nothing. After this task only glow-tier jars keep fullbright liquid.

**Step 1: Script the split** (one-shot, run from repo root):

```bash
python3 - << 'EOF'
import json, shutil
src = "src/main/resources/Common/Items/Chemistry/Solid.blockymodel"
glow = "src/main/resources/Common/Items/Chemistry/SolidGlow.blockymodel"
shutil.copyfile(src, glow)  # glow variant keeps fullbright liquid verbatim
m = json.load(open(src))
def fix(n):
    if n.get("name") == "Liquid":
        assert n["shape"]["shadingMode"] == "fullbright", "unexpected base state"
        n["shape"]["shadingMode"] = "flat"
        return 1
    return sum(fix(c) for c in n.get("children", []))
roots = m.get("nodes", m.get("children", []))
assert sum(fix(n) for n in roots) == 1, "expected exactly one Liquid node"
json.dump(m, open(src, "w"), separators=(",", ":"))
print("ok")
EOF
```

> **Caveat:** before writing, check how the existing file is serialized (`head -c 200 Solid.blockymodel`) and match it (indentation/compact). If the original is compact JSON, `separators=(",", ":")` is right; otherwise mirror it. The diff on `Solid.blockymodel` must be the single `shadingMode` value.

**Step 2: Verify**

```bash
git diff --stat src/main/resources/Common/Items/Chemistry/
python3 -c "
import json
for f in ['Solid', 'SolidGlow']:
    m = json.load(open(f'src/main/resources/Common/Items/Chemistry/{f}.blockymodel'))
    def find(n):
        if n.get('name') == 'Liquid': return n['shape']['shadingMode']
        for c in n.get('children', []):
            r = find(c)
            if r: return r
    roots = m.get('nodes', m.get('children', []))
    print(f, [find(n) for n in roots if find(n)])"
```
Expected: `Solid ['flat']`, `SolidGlow ['fullbright']`.

**Step 3: Commit**

```bash
git add src/main/resources/Common/Items/Chemistry/
git commit -m "feat: split jar model: flat liquid base + fullbright SolidGlow variant"
```

---

### Task 10: Wire the generator

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/assetgen/SubstanceAssetGenerator.java`
- Modify (if needed for icon boost): `src/main/java/com/chonbosmods/chemistry/impl/assetgen/SubstanceIcon.java`

**Step 1:** In the per-substance loop of `SubstanceAssetGenerator.main`:

```java
GlowTier tier = s instanceof Element e
    ? GlowDeriver.tierFor(e, registry)
    : GlowDeriver.tierFor((Compound) s, registry);

BufferedImage tinted = GlowBoost.apply(
    SubstanceLiquidTinter.tint(master, LIQUID_MASK, c), LIQUID_MASK, tier);
...
Files.writeString(itemDir.resolve(id + ".json"),
    SolidSubstanceAssets.itemJson(id, texturePath, tier, SolidSubstanceAssets.lightJson(c, tier)));
```

Icons: read `SubstanceIcon.render` first. It marks liquid pixels via the `iconMask` image — apply the same per-channel `boost` to mask-marked pixels when `tier != NONE` (add a `GlowTier` parameter; pass `tier`). Keep the boost math in `GlowBoost` (extract a public `static int boost(int channel, GlowTier tier)` — adjust Task 7's visibility if needed) so it exists in exactly one place.

Also log a tier summary at the end of `main` (counts per tier) so the run output documents what glows.

**Step 2:** `./gradlew test -q` — full suite green (existing generator has no unit test for `main`; the compile + downstream checks cover it).

**Step 3: Regenerate assets**

```bash
./gradlew generateSolidSubstanceAssets
rm -rf build/resources/main/Common build/resources/main/Server   # stale-id guard (memory: old ids linger)
```

Expected output: `Generated 205 solid-substance items` + tier summary. Sanity-check the summary against the anchor table (U/Th solids FAINT; Ra, Tc, Pm, Po STRONG; Fm+ solids FIERCE; everything else NONE — expect roughly 15-25 glowing items total).

**Step 4: Spot-check one glowing item JSON**

```bash
cat src/main/resources/Server/Item/Items/Chemistry/Chem_Solid_Element_Radium.json | python3 -m json.tool | grep -A2 -E "Light|CustomModel"
```
Expected: `"Light": {"Color": "#..."}` present, `CustomModel` = `Items/Chemistry/SolidGlow.blockymodel`. And a stable item (Iron) has no `Light` + base model.

**Step 5: Commit** (generated tree is gitignored; commit only source changes)

```bash
git add src/main/java
git commit -m "feat: wire glow tiers into asset generator: boost, light, model pick"
```

---

### Task 11: Contact sheet

**Files:**
- Create: `scripts/contact_sheet.py`

**Step 1: Implement** (Pillow is available — prior art in `models/props/chemistry/tint_substance.py`):

```python
# scripts/contact_sheet.py
"""Tile all generated jar icons into one PNG for an at-a-glance variety check.

Usage: python3 scripts/contact_sheet.py  (from repo root)
"""
import math
import sys
from pathlib import Path
from PIL import Image

ICONS = Path("src/main/resources/Common/Icons/ItemsGenerated")
OUT = Path("build/contact_sheet.png")

files = sorted(ICONS.glob("*.png"))
if not files:
    sys.exit("no icons found: run ./gradlew generateSolidSubstanceAssets first")
cols = math.ceil(math.sqrt(len(files) * 1.5))
cell = 68
rows = math.ceil(len(files) / cols)
sheet = Image.new("RGBA", (cols * cell, rows * cell), (24, 24, 28, 255))
for i, f in enumerate(files):
    img = Image.open(f).convert("RGBA")
    sheet.paste(img, ((i % cols) * cell + 2, (i // cols) * cell + 2), img)
OUT.parent.mkdir(exist_ok=True)
sheet.save(OUT)
print(f"{len(files)} icons -> {OUT}")
```

**Step 2: Run + eyeball**

```bash
python3 scripts/contact_sheet.py
```
Then **Read the PNG** (`build/contact_sheet.png`) and evaluate against the goal: no wall of grey/white; element families read as families; glowing substances visibly brighter. If anything reads flat, go back to the palette generator (Task 3/4), adjust, re-apply, re-generate, re-sheet. This is the design's explicit eyeball gate — do not skip to in-game before it passes.

**Step 3: Commit**

```bash
git add scripts/contact_sheet.py
git commit -m "feat: contact sheet script for palette eyeball check"
```

---

### Task 12: Final verification

**Step 1: Full suite**

Run: `./gradlew test -q`
Expected: BUILD SUCCESSFUL, 262 + ~15 new tests, 0 failures.

**Step 2: Boot check**

```bash
./gradlew devServer
```
Watch for: clean boot, "Loaded 118 elements, 153 compounds, 673 isotopes", "Generated/load ~207 assets", no JSON parse errors on `Light` or `SolidGlow`. Kill server after boot (needs `dangerouslyDisableSandbox` for pkill; foreground `sleep` is sandbox-blocked).

**Step 3: User smoke test (hand off — do not claim done without it)**

Ask the user to verify in-game:
1. A FAINT jar (Uranium), STRONG jar (Radium), FIERCE jar (Fermium) placed in a dark room: visible glow gradient + emitted light.
2. A NONE jar (Iron) in the same room: liquid no longer fullbright.
3. Former-grey neighbors distinct: Fe vs Ni vs Co vs Cu side by side.
4. A few whites: NaCl vs KCl vs sugar: close but distinguishable.

**Step 4:** Use superpowers:finishing-a-development-branch (merge target: coordinate with the parallel instance on `chore/transport-quick-wins` — do NOT push; Discord webhook fires on push).
