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
            {"key": "Dd", "hex": "#0A0A0A", "exempt": "iconic dark"},  # exempt: ok (distinct from Aa)
        ]
        errors = check_gates(rows, min_v=0.45, max_v=0.92, min_s=0.10, min_dist=28)
        joined = "\n".join(errors)
        self.assertIn("Aa", joined)
        self.assertIn("Cc", joined)
        self.assertNotIn("Dd", joined)
        self.assertEqual(len([e for e in errors if "Bb" in e and "Cc" not in e]), 0)


if __name__ == "__main__":
    unittest.main()
