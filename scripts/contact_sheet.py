"""Tile all generated jar icons into one PNG for an at-a-glance variety check.

Ordering: elements first (by atomic number), then compounds (alphabetical). The
icon filenames are sanitized PascalCase-per-segment names (see
SolidSubstanceAssets.assetId), so we rebuild the same forward map from
data/elements.json (Name -> filename) and invert it to recover atomic numbers.
Any element icon that fails to map falls back to alphabetical at the end of the
element block, with a printed warning.

Usage: python3 scripts/contact_sheet.py  (from repo root)
"""
import json
import math
import re
import sys
from pathlib import Path
from PIL import Image

ICONS = Path("src/main/resources/Common/Icons/ItemsGenerated")
ELEMENTS_JSON = Path("data/elements.json")
OUT = Path("build/contact_sheet.png")

ELEMENT_PREFIX = "Chem_Solid_Element_"
COMPOUND_PREFIX = "Chem_Solid_Compound_"


def asset_name(identity):
    """Mirror SolidSubstanceAssets.assetId segment handling: non-alnum -> '_',
    strip edges, title-case each segment's first char (rest preserved)."""
    parts = [p for p in re.sub(r"[^A-Za-z0-9]+", "_", identity).strip("_").split("_") if p]
    return "_".join(p[0].upper() + p[1:] for p in parts)


def element_atomic_numbers():
    """filename -> atomic number, built forward from the element data."""
    if not ELEMENTS_JSON.exists():
        return {}
    mapping = {}
    for e in json.loads(ELEMENTS_JSON.read_text()):
        fn = ELEMENT_PREFIX + asset_name(e["Name"]) + ".png"
        mapping[fn] = e["AtomicNumber"]
    return mapping


def ordered_files(files):
    """Elements (by atomic number, unmapped alphabetical at the end of the
    element block) then compounds (alphabetical). Returns (ordered, unmapped)."""
    by_z = element_atomic_numbers()
    elements = [f for f in files if f.name.startswith(ELEMENT_PREFIX)]
    compounds = [f for f in files if f.name.startswith(COMPOUND_PREFIX)]
    other = [f for f in files
             if not f.name.startswith(ELEMENT_PREFIX)
             and not f.name.startswith(COMPOUND_PREFIX)]

    mapped = [f for f in elements if f.name in by_z]
    unmapped = [f for f in elements if f.name not in by_z]
    mapped.sort(key=lambda f: by_z[f.name])
    unmapped.sort(key=lambda f: f.name)
    compounds.sort(key=lambda f: f.name)
    other.sort(key=lambda f: f.name)

    return mapped + unmapped + compounds + other, unmapped


def main():
    files = sorted(ICONS.glob("*.png"))
    if not files:
        sys.exit("no icons found: run ./gradlew generateSolidSubstanceAssets first")

    files, unmapped = ordered_files(files)
    if unmapped:
        print(f"WARNING: {len(unmapped)} element icon(s) had no atomic-number "
              f"mapping, sorted alphabetically at the end of the element block:")
        for f in unmapped:
            print(f"  {f.name}")

    cols = math.ceil(math.sqrt(len(files) * 1.5))
    cell = 68
    rows = math.ceil(len(files) / cols)
    sheet = Image.new("RGBA", (cols * cell, rows * cell), (24, 24, 28, 255))
    for i, f in enumerate(files):
        img = Image.open(f).convert("RGBA")
        sheet.paste(img, ((i % cols) * cell + 2, (i // cols) * cell + 2), img)
    OUT.parent.mkdir(exist_ok=True)
    sheet.save(OUT)
    print(f"{len(files)} icons tiled ({cols}x{rows} grid, {cell}px cells) -> {OUT}")


if __name__ == "__main__":
    main()
