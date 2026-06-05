"""Generate data/palette/compounds.csv: curated associations + constituent echo + fallback.

Single source of truth for compound colors (design doc section 3). Re-run to regenerate;
hand-edit CURATED here, never the CSV. Determinism is a hard requirement: same inputs
always produce byte-identical output (no randomness anywhere).

Rule priority (highest first):
  1. CURATED verified   -- real, re-checked colors pushed into the luminance band, plus
                           iconic darks (exempt) and believable alloy metal tones.
  2. CURATED evocation  -- deliberate, explainable hues for organics / notable compounds.
  3. Constituent echo   -- white salts/bases tinted toward their parent metal's NEW
                           element color (data/palette/elements.csv), computed here.
  4. Functional fallback-- per-CompoundType tint blended over a base white.

After assigning base colors, non-curated near-whites are nudged greedily (deterministic)
to clear MIN_DIST from every other row. Curated rows are anchors and never move.
"""
import csv
import colorsys
import json
import math
from pathlib import Path
from palette_lib import to_hex, parse_hex, hsv_hex

MIN_DIST = 10.0   # must match the apply_palette compound min distance (distinct whites)

# Base white for computed tints. #F2EFE6 (v=0.95) tends to leave 22%/12% blends in the
# washout zone (v>0.92, s<0.20), so the computed echo/fallback use a slightly dimmer
# white (ECHO_BASE) and richer blend percentages chosen to clear the gates while still
# reading as a faintly-tinted near-white. See gate-tuning notes in the task report.
ECHO_BASE = "#EDE7D6"   # v=0.93: blends land just under the washout ceiling

# -- Rule 0: ICONIC WHITES (highest priority) --
# Substances whose real-world identity IS white: salt, sugar, chalk, titanium-white
# and kin. These override echo/evocation/fallback so they read genuinely near-white
# instead of a tinted pale. Each carries exempt "iconic white" so it skips the band
# and saturation gates (like the iconic darks) and the exempt-vs-exempt distance gate.
# Subtle warm/cool/matte variation keeps them tellable apart at close range while all
# reading white (v>=0.90, s<=0.06). key: (hex, rationale).
ICONIC_WHITE = {
    "Titanium dioxide":   ("#F6F6F3", "the white pigment: purest white of all"),
    "Sodium chloride":    ("#F3EFE7", "warm table-salt white"),
    "Sucrose":            ("#F5F3ED", "sparkling sugar white"),
    "Calcium carbonate":  ("#EFEBE1", "matte chalk white"),
    "Sodium bicarbonate": ("#F2F0E8", "baking-soda white"),
    "Zinc oxide":         ("#F4F2EC", "sunscreen white"),
    "Starch":             ("#F0EEE4", "flour white"),
    "Potassium nitrate":  ("#F1EFE9", "saltpeter crystal white"),
    "Sodium hydroxide":   ("#EDEBE3", "caustic pellet white"),
    "Magnesium oxide":    ("#F5F2ED", "burnt-magnesia brilliant white"),
    "Barium sulfate":     ("#F4F1EE", "precipitate-test white"),
    "Silver nitrate":     ("#EFEDE7", "white crystals, light-sensitive"),
    "Arsenic trioxide":   ("#EEECE2", "'white arsenic' white powder"),
    "Naphthalene":        ("#F2F0EA", "mothball white"),
    "Calcium hydroxide":  ("#ECE9E0", "slaked-lime white"),
    "Sodium cyanide":     ("#F1EFE6", "deadly almond white"),
    "Potassium cyanide":  ("#EFEDE4", "deadly almond white"),
    "Silicon dioxide":    ("#F4F3EF", "quartz-crystal white"),
}

# -- Rule 1: CURATED verified (and iconic darks / alloys) --
# key: (source, hex, exempt_reason_or_empty, rationale)
VERIFIED = {
    # Real ionic colors, pushed into the band.
    "Copper(II) sulfate":     ("verified", "#2A6FD8", "", "copper(II) sulfate pentahydrate blue"),
    "Potassium permanganate": ("verified", "#7A2A8A", "", "permanganate violet, brightened to band"),
    "Potassium dichromate":   ("verified", "#E8702A", "", "bright dichromate orange"),
    "Iron(III) oxide":        ("verified", "#B0472A", "", "red-brown rust"),
    "Iron(II) sulfate":       ("verified", "#7FB77E", "", "pale blue-green copperas"),
    "Nitrogen dioxide":       ("verified", "#B05A2A", "", "red-brown NO2 gas"),
    "Chromium(III) oxide":    ("verified", "#3A7A2A", "", "chrome-oxide green"),
    "Lead(II) oxide":         ("verified", "#E8B23A", "", "litharge yellow"),
    "Water":                  ("verified", "#C0E0F8", "", "thick-layer water blue"),
    "Dinitrogen tetroxide":   ("verified", "#C08A5A", "", "pale-brown, in equilibrium with NO2"),
    "Sodium hypochlorite":    ("evocation", "#A8E0CC", "", "pool-cyan bleach solution"),
    "Calcium hypochlorite":   ("evocation", "#C2E8DA", "", "pool-cyan chlorine bleaching powder"),
    "Chlorine":               ("verified", "#B8D832", "", "yellow-green chlorine gas"),
    "Lead(II) nitrate":       ("evocation", "#C8C0A8", "", "white lead salt, faint warm cast"),

    # Iconic darks: stay genuinely dark, exempt from the brightness floor.
    "Iron(II,III) oxide":   ("verified", "#262A2E", "iconic dark", "black magnetite"),
    "Copper(II) oxide":     ("verified", "#242022", "iconic dark", "black cupric oxide"),
    "Manganese dioxide":    ("verified", "#2A2622", "iconic dark", "black/dark-brown pyrolusite"),
    "Uranium dioxide":      ("verified", "#241F1C", "iconic dark", "black/brown ceramic"),
    "Plutonium dioxide":    ("verified", "#3A2E1C", "iconic dark", "yellow-brown to olive ceramic, dark"),
    "Americium dioxide":    ("verified", "#222020", "iconic dark", "black ceramic"),
    "Triuranium octoxide":  ("verified", "#D4B83A", "", "yellowcake ochre: the iconic yellow (pure U3O8 is darker but the name owns the color)"),
    "Lead dioxide":         ("verified", "#3A2A22", "iconic dark", "dark-brown plattnerite"),
    "Cast iron":            ("verified", "#3A3A40", "iconic dark", "dull dark grey brittle iron"),

    # Alloys: believable metal tones. Cool greys flagged low-sat metal where needed.
    "Carbon steel":     ("verified", "#8A8E96", "low-sat metal", "lustrous neutral grey steel"),
    "Stainless steel":  ("verified", "#A8B0B8", "low-sat metal", "bright cool corrosion-resistant grey"),
    "Brass":            ("verified", "#C8A951", "", "yellow-gold brass"),
    "Bronze":           ("verified", "#A86B32", "", "reddish-brown bronze"),
    "Gunmetal":         ("verified", "#8C6A4A", "", "reddish bronze gunmetal"),
    "Solder (tin-lead)":("verified", "#9CA0A6", "low-sat metal", "dull silver low-melt solder"),
    "Pewter":           ("verified", "#9A9A9E", "low-sat metal", "dull silver-grey pewter"),
    "Sterling silver":  ("verified", "#E2E2EA", "low-sat metal", "bright white silver"),
    "White gold":       ("verified", "#DCDCE2", "low-sat metal", "white-silver precious metal"),
    "Nichrome":         ("verified", "#9EA2AA", "low-sat metal", "silver-grey resistance wire"),
    "Duralumin":        ("verified", "#C4CCD6", "low-sat metal", "light silver aluminium alloy"),
    "Cupronickel":      ("verified", "#B0AEA6", "low-sat metal", "warm silver-grey cupronickel"),
    "Wood's metal":     ("verified", "#9494A0", "low-sat metal", "dull silver fusible alloy"),
    "Dental amalgam":   ("verified", "#AEB2BA", "low-sat metal", "silver metallic amalgam"),

    # Radioactive identities: honest colors (glow carries danger in a later task).
    "Uranium hexafluoride": ("verified", "#D6D2C8", "low-sat metal", "colorless/white volatile crystals, faint warm grey"),
    "Uranyl nitrate":       ("verified", "#D8E84A", "", "fluorescent uranyl yellow-green"),
    "Thorium dioxide":      ("evocation", "#E6DEC8", "", "white thoria ceramic, warm cast"),
    "Radium chloride":      ("evocation", "#EAD4A6", "", "white-to-buff self-luminous salt"),
    "Caesium-137 chloride": ("echo",     "",        "", "white CsCl, echoes caesium golden metal"),
}

# -- Rule 2: CURATED evocation (organics + notable compounds) --
# Every hue is a real association: the smell, the use, the source, the hazard.
EVOCATION = {
    # Acids: their working character.
    "Citric acid":      ("#C8E060", "", "citrus zest yellow-green"),
    "Lactic acid":      ("#F0E8D0", "", "milky sour cream-white"),
    "Formic acid":      ("#E0B060", "", "ant/sting amber"),
    "Sulfuric acid":    ("#E8E0A0", "", "oily concentrated straw-yellow"),
    "Nitric acid":      ("#E8C870", "", "fuming-amber red nitric"),
    "Hydrochloric acid":("#D0E8C0", "", "sharp pale chlorine-green"),
    "Hydrofluoric acid":("#D8F0E8", "", "etched-glass frost"),
    "Acetic acid":      ("#E8E0B8", "", "vinegar pale straw"),
    "Phosphoric acid":  ("#E4D8C0", "", "syrupy cola-acid amber-white"),
    "Carbonic acid":    ("#CFE4EC", "", "carbonated fizz pale blue"),
    "Hydrocyanic acid": ("#E0E0C8", "", "bitter-almond pale ivory"),
    "Boric acid":       ("#E0EAD8", "", "eyewash faint green-white"),
    "Oxalic acid":      ("#E4E4D2", "", "rhubarb-sour pale"),
    "Perchloric acid":  ("#E8E2C8", "", "fuming oxidizer pale gold"),
    "Sulfurous acid":   ("#E6E4C0", "", "sharp sulfur pale yellow"),
    "Nitrous acid":     ("#CFE0EC", "", "pale-blue unstable solution"),
    "Ascorbic acid":    ("#E8E060", "", "vitamin-C citrus yellow"),

    # Bases.
    "Ammonia":            ("#CFE6F0", "", "sharp cold pale blue"),
    "Ammonium hydroxide": ("#D4E6EE", "", "household-ammonia pale blue"),

    # Hydrocarbons / fuels: gas-flame and petroleum tinges.
    "Methane":    ("#BFE0EC", "", "natural-gas flame pale blue"),
    "Ethane":     ("#C4E2EA", "", "light hydrocarbon gas blue-white"),
    "Propane":    ("#C8DEE6", "", "bottled-gas pale blue"),
    "Butane":     ("#CCDEE2", "", "lighter-fuel pale blue"),
    "Ethylene":   ("#CCE8C8", "", "ripening-agent faint green"),
    "Acetylene":  ("#D8E0B0", "", "carbide-lamp greenish white"),

    # Alcohols / ketones / ethers: clear cool solvents.
    "Ethanol":        ("#D8ECEC", "", "clear spirit cool tint"),
    "Methanol":       ("#D4EAEC", "", "wood-spirit cool clear"),
    "Isopropanol":    ("#DCEAEE", "", "rubbing-alcohol cool clear"),
    "Acetone":        ("#E0ECDE", "", "nail-polish solvent pale"),
    "Diethyl ether":  ("#DCE8E2", "", "volatile anaesthetic ether pale"),
    "Ethyl acetate":  ("#E4ECCC", "", "fruity-ester pale green-gold"),
    "Acetic anhydride":("#E6E4C4", "", "pungent acetyl pale straw"),
    "Glycerol":       ("#E8E4C8", "", "sweet syrupy warm clear"),
    "Ethylene glycol":("#D8ECC0", "", "antifreeze green tint"),

    # Sugars / biomolecules: warm sugar and bread tones.
    "Glucose":   ("#E8D8A0", "", "warm grape-sugar"),
    "Sucrose":   ("#E8DCAC", "", "warm table-sugar"),
    "Cellulose": ("#E4DEC4", "", "plant-fibre pale wood-white"),
    "Starch":    ("#ECE6CE", "", "flour-pale starch white"),
    "Urea":      ("#E8E2CE", "", "fertilizer/urine warm pale"),
    "Glycine":   ("#E6E2C2", "", "sweet amino warm pale"),

    # Aromatics / solvents: solvent and tar tinges.
    "Benzene":      ("#E0DCB0", "", "aromatic-solvent faint gold"),
    "Toluene":      ("#DEDCB4", "", "paint-thinner pale gold"),
    "Naphthalene":  ("#E6E4D0", "", "mothball waxy white"),
    "Phenol":       ("#ECD8D2", "", "carbolic white-to-pink"),
    "Formaldehyde": ("#DCE6DC", "", "preservative sharp pale"),

    # Stimulants / alkaloids: source-plant tones.
    "Caffeine":  ("#C8A878", "", "coffee-bean warm brown"),
    "Nicotine":  ("#D8C078", "", "tobacco oily amber"),
    "Strychnine":("#E4DCCA", "", "bitter-white alkaloid"),

    # Halocarbons / solvents.
    "Chloroform":          ("#DCE8E0", "", "sweet dense anaesthetic pale"),
    "Carbon tetrachloride":("#DAE6DE", "", "dense dry-cleaning solvent pale"),
    "DDT":                 ("#E4E2D2", "", "off-white pesticide crystal"),

    # Notable inorganics / hazards: honest evocations.
    "Hydrogen peroxide":  ("#C8F0E8", "", "bleaching pale aqua"),
    "Hydrogen sulfide":   ("#E0E4B0", "", "rotten-egg sulfurous pale yellow"),
    "Phosgene":           ("#E0E6D2", "", "fresh-hay war-gas pale green"),
    "Arsenic trioxide":   ("#E2E0D0", "", "white-arsenic odorless powder"),
    "Mercury(II) chloride":("#E6E2D6", "", "corrosive-sublimate white"),
    "Lead(II) acetate":   ("#E6E2CC", "", "sugar-of-lead sweet white"),
    "Tetraethyllead":     ("#E8DCB0", "", "leaded-petrol oily pale"),
    "Sodium azide":       ("#E4E4D4", "", "airbag-propellant white"),
    "Hydrazine":          ("#D6E6EC", "", "rocket-fuel fuming pale blue"),
    "Methyl isocyanate":  ("#DEE6CE", "", "sharp volatile pale"),
    "Parathion":          ("#D8C870", "", "pale-yellow garlic-odor insecticide"),
    "Nitrous oxide":      ("#E0E0C0", "", "laughing-gas faint sweet pale"),
    "Carbon monoxide":    ("#D8DEE2", "", "silent colorless gas, faint cool"),
    "Carbon dioxide":     ("#D6E2E0", "", "exhaled gas faint cool pale"),
    "Sulfur dioxide":     ("#E4E0B0", "", "burnt-match sulfurous pale"),
    "Sulfur trioxide":    ("#E6E2B6", "", "fuming acid-anhydride pale straw"),
    "Titanium dioxide":   ("#F0EEE6", "", "brilliant titanium-white pigment"),
    "Zinc oxide":         ("#ECEAE0", "", "calamine/sunscreen white"),
    "Tin(IV) oxide":      ("#E6E4D8", "", "off-white stannic oxide"),
    "Aluminium oxide":    ("#E8E6DC", "", "corundum white"),
    "Calcium oxide":      ("#ECE8DC", "", "caustic quicklime white"),
    "Magnesium oxide":    ("#ECEAE0", "", "magnesia white"),
}

# -- Rule 4: functional fallback tint per CompoundType (blended over ECHO_BASE) --
TYPE_TINT = {
    "acid":    "#D8E85A",
    "base":    "#8FB8E8",
    "salt":    "#C8C0A8",
    "oxide":   "#B09078",
    "organic": "#E0C890",
    "alloy":   "#A8B0B8",
    "other":   "#C0A8C8",
}
ECHO_PCT = 0.26       # constituent-echo blend strength (rule 3)
FALLBACK_PCT = 0.18   # functional-fallback blend strength (rule 4)

# Metals worth echoing (skip H/C/N/O/S/P/Cl etc. non-metals that name no parent color).
NONMETALS = {"H", "C", "N", "O", "S", "P", "Cl", "F", "Br", "I", "Se", "B", "Si"}


def blend(base_hex, tint_hex, pct):
    b, t = parse_hex(base_hex), parse_hex(tint_hex)
    return to_hex(tuple(round(bb + (tt - bb) * pct) for bb, tt in zip(b, t)))


# Gate band the apply step enforces (mirror of apply_palette / palette_lib constants).
GATE_MIN_V, GATE_MAX_V, GATE_MIN_S = 0.45, 0.92, 0.10
GATE_WASHOUT_S = 0.20
# Headroom so rounding to 8-bit hex can't tip a color back over a gate boundary.
BAND_MAX_V = 0.90       # below the 0.92 washout ceiling
BAND_MIN_S = 0.16       # comfortably above the 0.10 floor AND the 0.20 washout-S corner
BAND_MIN_V = 0.50


def bandify(hexv):
    """Pull a color into the gate band while preserving its hue.

    The authored near-whites are intentionally pale: most sit above the washout
    ceiling (v>0.92) or below the saturation floor (s<0.10). We keep the deliberate
    HUE but deepen saturation to BAND_MIN_S and cap value at BAND_MAX_V so every
    evocation/echo/fallback color clears the gates with rounding headroom. The result
    still reads as a faintly-tinted near-white, just a notch richer and dimmer.
    """
    h, s, v = colorsys.rgb_to_hsv(*[c / 255 for c in parse_hex(hexv)])
    s = max(s, BAND_MIN_S)
    v = min(max(v, BAND_MIN_V), BAND_MAX_V)
    return hsv_hex(h, s, v)


# Deterministic nudge ladder for separating near-white collisions: rotate hue, then
# darken slightly / deepen saturation. Smallest visible departure first.
_NUDGE_STEPS = 360
_NUDGE_SV = [
    (0.0, 0.0), (0.0, 0.03), (0.0, 0.06), (-0.02, 0.03),
    (-0.02, 0.06), (-0.04, 0.06), (-0.04, 0.10), (-0.06, 0.10),
]


def nudge(hexv, placed):
    """Return the nearest deterministic in-band variant of hexv clearing MIN_DIST.

    `hexv` must already be bandified. If it collides, rotate hue and deepen/darken in
    fixed steps, staying inside the gate band so the result always passes apply. Depends
    only on hexv and placed -> byte-identical re-runs.
    """
    if all(math.dist(parse_hex(hexv), p) >= MIN_DIST for p in placed):
        return hexv
    h0, s0, v0 = colorsys.rgb_to_hsv(*[c / 255 for c in parse_hex(hexv)])
    for step in range(_NUDGE_STEPS):
        h = h0 + step / _NUDGE_STEPS
        for dv, ds in _NUDGE_SV:
            v = min(BAND_MAX_V, max(BAND_MIN_V, v0 + dv))
            s = min(0.88, max(BAND_MIN_S, s0 + ds))
            cand = hsv_hex(h, s, v)
            if all(math.dist(parse_hex(cand), p) >= MIN_DIST for p in placed):
                return cand
    return hexv  # exhausted (not expected at 153 rows): keep base


def parent_metal(comp):
    """First metal symbol in a composition (by element order), or None."""
    for sym in comp:
        if sym not in NONMETALS:
            return sym
    return None


def build_rows(compounds, element_hex):
    """Return CSV rows (key, source, hex, exempt, rationale) for every compound."""
    rows = {}
    anchors = set()   # names whose hand-set hex must not move (all of VERIFIED)
    # Pass 1: assign curated colors (anchors). These never move in the nudge pass.
    for c in compounds:
        name = c["Name"]
        if name in ICONIC_WHITE:
            # Highest priority: iconic whites override echo/evocation/fallback. Hand-set
            # near-white hex, exempt "iconic white" (skips band + sat gates and the
            # exempt-vs-exempt distance gate), anchored (never moves in the nudge pass).
            hexv, rat = ICONIC_WHITE[name]
            rows[name] = (name, "verified", hexv, "iconic white", rat)
            anchors.add(name)
        elif name in VERIFIED:
            src, hexv, exempt, rat = VERIFIED[name]
            if src == "echo":
                # Resolve the echo target now (parent metal's element color), then band.
                pm = parent_metal(c["Composition"])
                hexv = bandify(blend(ECHO_BASE, element_hex[pm], ECHO_PCT))
            # All hexes in VERIFIED are hand-set in-band; they anchor (immovable).
            rows[name] = (name, src, hexv, exempt, rat)
            anchors.add(name)
        elif name in EVOCATION:
            hexv, exempt, rat = EVOCATION[name]
            # Pull the authored pale hue into the gate band (hue preserved).
            rows[name] = (name, "evocation", bandify(hexv), exempt, rat)

    # Pass 2: constituent echo for remaining salts/bases naming a metal we have a color
    # for; else functional fallback. Oxides/others already curated above are skipped.
    for c in compounds:
        name = c["Name"]
        if name in rows:
            continue
        ctype = c.get("CompoundType", "other")
        pm = parent_metal(c["Composition"])
        if ctype in ("salt", "base") and pm and pm in element_hex:
            hexv = blend(ECHO_BASE, element_hex[pm], ECHO_PCT)
            rows[name] = (name, "echo", bandify(hexv), "",
                          f"faint {pm}-tinted white (echoes element palette)")
        else:
            tint = TYPE_TINT.get(ctype, TYPE_TINT["other"])
            hexv = blend(ECHO_BASE, tint, FALLBACK_PCT)
            rows[name] = (name, "fallback", bandify(hexv), "",
                          f"{ctype} functional tint over base white")

    # Pass 3: greedy deterministic separation. The verified rows are exact real colors
    # and must not move, so they anchor first (in input order). Every other row keeps
    # its authored/computed HUE but is nudged off collisions. Exempt rows skip distance
    # against each other but still clear non-exempt rows.
    ordered = [c["Name"] for c in compounds]
    placed = []          # rgb tuples of non-exempt rows already locked
    placed_exempt = []   # rgb tuples of exempt rows (only matter vs non-exempt)
    final = {}

    def lock(name, hexv, exempt):
        final[name] = hexv
        if exempt:
            placed_exempt.append(parse_hex(hexv))
        else:
            placed.append(parse_hex(hexv))

    # Hand-set VERIFIED colors first as immovable anchors (real colors / iconic darks /
    # alloys / hand-tuned hypochlorites etc.).
    for name in ordered:
        if name in anchors:
            _, _, hexv, exempt, _ = rows[name]
            lock(name, hexv, exempt)
    # Then every computed/authored-pale row, nudged against all placed so far. These are
    # all non-exempt, so they must clear every locked color (exempt or not).
    for name in ordered:
        if name not in anchors:
            _, _, hexv, exempt, _ = rows[name]
            new = nudge(hexv, placed + placed_exempt)
            lock(name, new, exempt)

    out = []
    for name in ordered:
        key, src, _, exempt, rat = rows[name]
        out.append((key, src, final[name], exempt, rat))
    return out


def main():
    root = Path(__file__).resolve().parent.parent
    compounds = json.loads((root / "data/compounds.json").read_text())
    elements = list(csv.DictReader((root / "data/palette/elements.csv").open()))
    element_hex = {e["key"]: e["hex"] for e in elements}

    rows = build_rows(compounds, element_hex)

    out = root / "data/palette/compounds.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["key", "source", "hex", "exempt", "rationale"])
        for row in rows:
            w.writerow(row)

    counts = {}
    for _, src, _, _, _ in rows:
        counts[src] = counts.get(src, 0) + 1
    print(f"wrote {out}  ({len(rows)} rows)")
    for src in ("verified", "evocation", "echo", "fallback"):
        print(f"  {src:9s}: {counts.get(src, 0)}")


if __name__ == "__main__":
    main()
