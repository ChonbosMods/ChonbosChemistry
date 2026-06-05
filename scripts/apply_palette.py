"""Apply a palette CSV onto a data JSON, with quality gates.

Usage: python3 apply_palette.py <palette.csv> <data.json> <key_field> [min_dist]
The data JSONs are never hand-edited: this script is the only writer (design section 5).

Output formatting MUST match the existing data files so the recolor diff stays
Color/Notes-only: json.dumps(indent=2) with default ensure_ascii=True, plus a
trailing newline. (compounds.json escapes a non-ASCII em dash as \\u2014, which
proves the files were written with ensure_ascii=True.)
"""
import csv
import json
import re
import sys
from pathlib import Path
from palette_lib import check_gates

MIN_V, MAX_V, MIN_S = 0.45, 0.92, 0.10

# Strip the entire stale "colorless in pure form (white hex is a placeholder for
# colorless)" clause, not just the parenthetical: once an element is recolored the
# whole phrase is misleading. [^();] keeps the match from leaking across clause
# boundaries (';') or unrelated parentheses.
STALE_NOTE = re.compile(r"colorless in pure form\s*\(?[^();]*placeholder for colorless\)?\.?")


def strip_placeholder_note(note):
    """Remove the stale colorless/placeholder clause and tidy leftover separators."""
    out = STALE_NOTE.sub("", note)
    out = re.sub(r"\s*;\s*;\s*", "; ", out)  # collapse '; ;' left by an embedded clause
    out = re.sub(r"\s{2,}", " ", out)        # squeeze any doubled whitespace
    return out.strip(" ;,")


def apply(table_path, data_path, key_field, min_dist):
    with open(table_path, newline="") as fh:
        rows = list(csv.DictReader(fh))
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
            rec["Notes"] = strip_placeholder_note(note)
    Path(data_path).write_text(json.dumps(records, indent=2) + "\n")
    print(f"applied {len(rows)} colors -> {data_path}")


if __name__ == "__main__":
    apply(sys.argv[1], sys.argv[2], sys.argv[3],
          int(sys.argv[4]) if len(sys.argv) > 4 else 28)
