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
Au,verified,#E0B838,,gold
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
        self.assertEqual(out[1]["Color"], "#E0B838")

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

    # --- real-data Notes patterns (see Task 2 mandatory check) ---
    # The placeholder phrase appears in three real positions: standalone, leading
    # within a ';'-joined list, and embedded mid-list. Stripping must remove the
    # whole "colorless in pure form (...)" clause and leave clean separators.

    def _clean_note(self, note):
        recs = [{"Name": "X", "Symbol": "Fe", "Color": "#C0C0C0", "Notes": note},
                {"Name": "Y", "Symbol": "Au", "Color": "#FFD700", "Notes": "keep me"}]
        return self._run(records=recs)[0]["Notes"]

    def test_strips_standalone_clause_to_empty(self):
        note = "colorless in pure form (white hex is a placeholder for colorless)"
        self.assertEqual(self._clean_note(note), "")

    def test_strips_leading_clause_keeps_rest(self):
        note = ("colorless in pure form (white hex is a placeholder for colorless); "
                "unavailable/undefined values (null): electronegativity")
        self.assertEqual(
            self._clean_note(note),
            "unavailable/undefined values (null): electronegativity",
        )

    def test_strips_embedded_clause_no_double_separator(self):
        note = ("does not solidify at 1 atm at any temperature (requires elevated "
                "pressure); colorless in pure form (white hex is a placeholder for "
                "colorless); unavailable/undefined values (null): meltingPoint, "
                "electronegativity")
        out = self._clean_note(note)
        self.assertNotIn("placeholder for colorless", out)
        self.assertNotIn("colorless in pure form", out)
        self.assertNotIn(";;", out)
        self.assertNotIn(";  ", out)
        self.assertEqual(
            out,
            "does not solidify at 1 atm at any temperature (requires elevated "
            "pressure); unavailable/undefined values (null): meltingPoint, "
            "electronegativity",
        )


if __name__ == "__main__":
    unittest.main()
