"""Generate data/palette/elements.csv: curated associations + systematic fallback.

Single source of truth for element colors (design doc section 2). Re-run to regenerate;
hand-edit CURATED here, never the CSV.
"""
import csv
import json
import math
import colorsys
from pathlib import Path
from palette_lib import to_hex, parse_hex, hsv_hex

# symbol: (source, hex, exempt_reason_or_empty, rationale)
CURATED = {
    # -- gases: discharge / liquid-phase colors --
    "H":  ("discharge", "#BFE3FF", "", "pale ice blue, lightest gas"),
    "He": ("discharge", "#FFD9A8", "", "peach discharge glow"),
    "N":  ("evocation", "#9AD6D2", "", "cryogenic frost blue"),
    "O":  ("verified",  "#8FB8E8", "", "liquid oxygen is genuinely blue"),
    "F":  ("verified",  "#D8E85A", "", "pale acid yellow-green gas"),
    "Ne": ("discharge", "#FF6B3D", "", "neon sign red-orange"),
    "Ar": ("discharge", "#B48CFF", "", "violet discharge"),
    "Kr": ("discharge", "#BCEAC6", "", "ghostly green-white discharge"),
    "Xe": ("discharge", "#88D8FF", "", "ice blue discharge"),
    "Rn": ("discharge", "#5FC4A8", "", "ominous pale teal, the radioactive gas"),
    "Cl": ("verified",  "#B8D832", "", "yellow-green gas (chloros)"),
    # -- nonmetals / metalloids --
    "C":  ("verified",  "#2B2B30", "iconic dark", "graphite near-black"),
    "P":  ("verified",  "#E0E8A8", "", "white phosphorus waxy pale yellow"),
    "S":  ("verified",  "#F0D020", "", "bright sulfur yellow"),
    "Se": ("verified",  "#B05248", "", "red selenium allotrope"),
    "B":  ("mineral",   "#9A7A50", "", "brown amorphous boron"),
    "Si": ("mineral",   "#6678B6", "", "blue-grey crystalline sheen"),
    "Ge": ("mineral",   "#98A8B8", "", "cool silver-blue semimetal"),
    "As": ("evocation", "#7FA86A", "", "Paris green, the poisoner's pigment"),
    "Br": ("verified",  "#B84A1E", "", "rust-red fuming liquid, brightened to band"),
    "I":  ("verified",  "#8A5FAF", "", "violet vapor (iodes), brightened to band"),
    "At": ("fantasy",   "#6B4585", "", "dusk-purple void halogen, never seen in bulk"),
    # -- alkali: flame tests + etymology --
    "Li": ("flame",     "#E83A6A", "", "crimson flame"),
    "Na": ("flame",     "#FFA53D", "", "sodium-lamp orange"),
    "K":  ("flame",     "#C89AE8", "", "lilac flame"),
    "Rb": ("spectral",  "#C03A4A", "", "rubidus: deep red spectral lines"),
    "Cs": ("spectral",  "#5FA0EE", "", "caesius: sky blue spectral lines"),
    "Fr": ("fantasy",   "#A04A78", "", "doomed alkali, magenta-ash"),
    # -- alkaline earth: flame tests + minerals --
    "Be": ("mineral",   "#6AD28C", "", "emerald/beryl green"),
    "Mg": ("flame",     "#AAC2FD", "", "blinding white flame, icy cast"),
    "Ca": ("flame",     "#E0915A", "", "brick-red flame"),
    "Sr": ("flame",     "#E84A30", "", "scarlet flare red"),
    "Ba": ("flame",     "#7EBE3E", "", "apple green flame"),
    "Ra": ("fantasy",   "#84F0A8", "", "radium-dial green, doubles as glow hue"),
    # -- transition metals: ions / minerals / anodizing --
    "Ti": ("mineral",   "#D0B8EE", "", "titania white-violet"),
    "V":  ("ion",       "#4A9AA8", "", "vanadyl teal-blue"),
    "Cr": ("spectral",  "#4AB850", "", "chroma: the color element, emerald"),
    "Mn": ("ion",       "#C06A9A", "", "permanganate rose-violet"),
    "Fe": ("verified",  "#9A8578", "", "warm grey with rust undertone"),
    "Co": ("ion",       "#3A6AC8", "", "cobalt blue"),
    "Ni": ("ion",       "#9AD060", "", "Ni(II) apple green"),
    "Cu": ("verified",  "#C88040", "", "copper metal"),
    "Zn": ("verified",  "#A4B6D2", "", "bluish-white cast"),
    "Zr": ("mineral",   "#6AC8DA", "", "zircon gemstone blue"),
    "Nb": ("evocation", "#8A58E4", "", "anodized niobium violet"),
    "Mo": ("mineral",   "#7E86BE", "", "dusty molybdenite blue"),
    "Tc": ("fantasy",   "#6AA8A0", "", "synthetic cyan-ash, the first artificial element"),
    "Rh": ("spectral",  "#E08AA0", "", "rhodon: rose"),
    "Ag": ("verified",  "#E8E8F0", "low-sat metal", "bright silver"),
    "Cd": ("evocation", "#D89A28", "", "cadmium yellow pigment"),
    "In": ("spectral",  "#5A4AC8", "", "indigo spectral line"),
    "Sn": ("verified",  "#C0B8A8", "low-sat metal", "warm pewter"),
    "Ta": ("evocation", "#48A8E0", "", "anodized tantalum blue"),
    "W":  ("mineral",   "#647496", "", "wolframite steel-blue"),
    "Os": ("verified",  "#7A8A9A", "", "genuinely blue-grey metal"),
    "Ir": ("spectral",  "#9A80DA", "", "iris: rainbow salts, violet lean"),
    "Pt": ("verified",  "#D8E0E8", "low-sat metal", "pale icy platinum"),
    "Au": ("verified",  "#FFD24A", "", "gold"),
    "Hg": ("verified",  "#C0C8D0", "low-sat metal", "quicksilver"),
    # -- post-transition --
    "Al": ("verified",  "#C8D0DC", "low-sat metal", "cool aluminium white"),
    "Tl": ("spectral",  "#88E03E", "", "thallos: green shoot"),
    "Pb": ("verified",  "#505A7E", "", "dense cold blue-grey"),
    "Bi": ("evocation", "#DA7EC6", "", "iridescent hopper-crystal pink"),
    "Po": ("fantasy",   "#D2602C", "", "alpha-heat ember"),
    # -- lanthanides with real associations --
    "Ce": ("mineral",   "#E8D88A", "", "ceria pale gold"),
    "Pr": ("spectral",  "#62CE3C", "", "praseo: leek green"),
    "Nd": ("evocation", "#B87AC8", "", "neodymium glass violet-pink"),
    "Eu": ("evocation", "#F04A88", "", "the red phosphor of CRT screens"),
    "Tb": ("evocation", "#3EC078", "", "the green phosphor"),
    "Er": ("evocation", "#F0A0BC", "", "erbium-pink glass"),
    # -- actinides with real associations --
    "U":  ("evocation", "#A8F050", "", "uranium-glass green"),
    "Np": ("spectral",  "#4A6AE8", "", "Neptune blue"),
    "Pu": ("evocation", "#76C0F4", "", "Cherenkov blue"),
}

# Elements absent from CURATED get a systematic color (design section 2 tier 6).
# Each tier has a (saturation, value) signature that gives the family its read:
#   lanthanide pastel wheel  -- bright, low-mid saturation pastels
#   actinide irradiated wheel -- darker, more saturated "irradiated" tones
#   synthetic exotic palette  -- vivid, high-saturation exotics for the superheavies
#   spaced metallic tint      -- muted tints for the leftover plain metals
# Hues come off a golden-angle sequence so consecutive elements never share a hue.
# Because the curated colors already occupy scattered points in RGB space, a flat
# wheel collides with them; we therefore place each fallback color greedily, rotating
# its hue (and, as a last resort, nudging value/saturation) by a deterministic search
# until it clears MIN_DIST from every color placed so far. Same inputs -> same output.
LANTHANIDE_Z = range(57, 72)   # La..Lu
ACTINIDE_Z = range(89, 104)    # Ac..Lr
GOLDEN_ANGLE = 137.508 / 360.0
MIN_DIST = 28.0                # must match apply_palette's element min distance

# tier -> (base_saturation, base_value, rationale)
TIERS = {
    "lanthanide": (0.42, 0.84, "lanthanide pastel wheel"),
    "actinide":   (0.54, 0.60, "actinide irradiated wheel"),
    "superheavy": (0.62, 0.74, "synthetic exotic palette"),
    "metal":      (0.30, 0.74, "spaced metallic tint"),
}

# Deterministic perturbation ladder (dv, ds) tried at each hue rotation, smallest
# departure from the tier signature first.
_NUDGES = [(0, 0), (0.08, 0), (-0.08, 0), (0, 0.06), (0.12, 0.06), (-0.12, 0.06)]


def tier_of(z):
    if z in LANTHANIDE_Z:
        return "lanthanide"
    if z in ACTINIDE_Z:
        return "actinide"
    if z >= 104:
        return "superheavy"
    return "metal"


def place(base_h, s0, v0, placed):
    """Pick the first golden-angle-seeded color clearing MIN_DIST from `placed`.

    Rotates the hue in fine steps and, within each step, tries small value/saturation
    nudges before widening. Deterministic: depends only on base_h and placed.
    """
    for step in range(240):
        h = base_h + step / 240.0
        for dv, ds in _NUDGES:
            v = min(0.91, max(0.46, v0 + dv))
            s = min(0.90, max(0.12, s0 + ds))
            hexv = hsv_hex(h, s, v)
            rgb = parse_hex(hexv)
            if all(math.dist(rgb, p) >= MIN_DIST for p in placed):
                return hexv, rgb
    # Exhausted the search (should not happen for 118 elements): take the base color.
    hexv = hsv_hex(base_h, s0, v0)
    return hexv, parse_hex(hexv)


def build_rows(elements):
    """Return CSV rows (key, source, hex, exempt, rationale) for every element."""
    ordered = sorted(elements, key=lambda e: e["AtomicNumber"])
    # Curated colors anchor the layout; fallback colors are placed around them.
    placed = [parse_hex(CURATED[e["Symbol"]][1])
              for e in ordered if e["Symbol"] in CURATED]
    rows = {}
    gi = 0
    for e in ordered:
        sym, z = e["Symbol"], e["AtomicNumber"]
        if sym in CURATED:
            rows[sym] = (sym, *CURATED[sym])
            continue
        tier = tier_of(z)
        s0, v0, rationale = TIERS[tier]
        hexv, rgb = place(gi * GOLDEN_ANGLE, s0, v0, placed)
        placed.append(rgb)
        rows[sym] = (sym, "systematic", hexv, "", rationale)
        gi += 1
    return [rows[e["Symbol"]] for e in ordered]


def main():
    # Anchor on the repo root (parent of scripts/) so the generator works whether
    # invoked from the repo root or from scripts/ (the plan's run command cds in).
    root = Path(__file__).resolve().parent.parent
    elements = json.loads((root / "data/elements.json").read_text())
    out = root / "data/palette/elements.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["key", "source", "hex", "exempt", "rationale"])
        for row in build_rows(elements):
            w.writerow(row)
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
