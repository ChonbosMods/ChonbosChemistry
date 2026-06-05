import unittest
from palette_lib import parse_hex, to_hex, hsv, hsv_hex, rgb_distance, check_gates


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

    def test_hsv_hex(self):
        self.assertEqual(hsv_hex(0, 1, 1), "#FF0000")
        # round-trips through hsv(parse_hex(...)) back to the same hue/sat/value
        h, s, v = hsv(parse_hex(hsv_hex(0, 1, 1)))
        self.assertAlmostEqual(h, 0.0)
        self.assertAlmostEqual(s, 1.0)
        self.assertAlmostEqual(v, 1.0)

    def test_rgb_distance(self):
        self.assertAlmostEqual(rgb_distance((0, 0, 0), (3, 4, 0)), 5.0)

    def test_gates_flag_violations(self):
        rows = [
            {"key": "Aa", "hex": "#202020", "exempt": ""},   # too dark, too grey
            {"key": "Bb", "hex": "#80C040", "exempt": ""},   # fine
            {"key": "Cc", "hex": "#81C041", "exempt": ""},   # too close to Bb
            {"key": "Dd", "hex": "#0A0A0A", "exempt": "iconic dark"},  # exempt: ok (distinct from Aa)
        ]
        errors = check_gates(rows, min_v=0.45, max_v=0.92, min_s=0.10, min_dist=28)
        joined = "\n".join(errors)
        self.assertIn("Aa", joined)
        self.assertIn("Cc", joined)
        self.assertNotIn("Dd", joined)
        self.assertEqual(len([e for e in errors if "Bb" in e and "Cc" not in e]), 0)

    def test_vivid_bright_colors_pass(self):
        # High-value but saturated colors are GOOD: they must not be flagged.
        rows = [
            {"key": "Go", "hex": "#FFD24A", "exempt": ""},  # gold, v=1.0 s=0.71
            {"key": "Ne", "hex": "#FF6B3D", "exempt": ""},  # neon, v=1.0 s=0.76
        ]
        errors = check_gates(rows, min_v=0.45, max_v=0.92, min_s=0.10, min_dist=28)
        self.assertEqual(errors, [])

    def test_washed_out_near_white_fails(self):
        # Pale near-whites that vanish in the tint pipeline must be flagged.
        rows = [
            {"key": "Wh", "hex": "#FAFAFA", "exempt": ""},  # v=0.98 s=0.0
            {"key": "Cr", "hex": "#F0E8D8", "exempt": ""},  # v=0.94 s=0.10 washed out
        ]
        errors = check_gates(rows, min_v=0.45, max_v=0.92, min_s=0.10, min_dist=28)
        joined = "\n".join(errors)
        self.assertIn("Wh", joined)  # washout and/or saturation floor
        self.assertTrue(any("Cr" in e and "washed out" in e for e in errors))

    def test_exempt_pairs_skip_distance_check(self):
        # Two exempt iconic-dark oxides with near-identical hexes: allowed to crowd.
        rows = [
            {"key": "Uo", "hex": "#3A2A1A", "exempt": "iconic dark"},
            {"key": "Pu", "hex": "#3B2A1A", "exempt": "iconic dark"},
        ]
        errors = check_gates(rows, min_v=0.45, max_v=0.92, min_s=0.10, min_dist=28)
        dist_errors = [e for e in errors if "vs" in e]
        self.assertEqual(dist_errors, [])

        # Mixed pair (one exempt, one not) is still distance-checked.
        rows2 = [
            {"key": "Uo", "hex": "#3A2A1A", "exempt": "iconic dark"},
            {"key": "Nn", "hex": "#3C2A1B", "exempt": ""},
        ]
        errors2 = check_gates(rows2, min_v=0.45, max_v=0.92, min_s=0.10, min_dist=28)
        dist_errors2 = [e for e in errors2 if "vs" in e]
        self.assertEqual(len(dist_errors2), 1)
        self.assertIn("Uo", dist_errors2[0])
        self.assertIn("Nn", dist_errors2[0])


if __name__ == "__main__":
    unittest.main()
