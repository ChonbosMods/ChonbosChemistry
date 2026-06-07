"""Pytest for the pure helpers of gen_pipe_tips: mask->arms, naming, assignment
enumeration (counts per arity), and the yaw rotation math.

The full generation + round-trip proof live in the script's --self-test (they
need the real model corpus); these tests pin the pure combinatorics + geometry
that PipeShapes must reproduce independently."""
import copy
import json
import unittest
from pathlib import Path

from gen_pipe_tips import (
    mask_to_arms,
    tip_assignments,
    model_name,
    state_key,
    yaw_position,
    yaw_size,
    yaw_texture_layout,
    yaw_node,
    YAW_FOR_FACE,
    mask_for_shape,
)

# Authored topology corpus (the rotated-UV ground truth). Present in the repo; the
# proof test below skips cleanly if it has been relocated.
CORPUS = (Path(__file__).resolve().parent.parent
          / "src/main/resources/Common/Blocks/ChonbosMods/Pipes")


class MaskArmsTest(unittest.TestCase):
    def test_mask_to_arms_ascending(self):
        # +X=0,-X=1,+Z=4 set -> 0b010011
        self.assertEqual(mask_to_arms(0b010011), [0, 1, 4])

    def test_mask_to_arms_empty(self):
        self.assertEqual(mask_to_arms(0), [])

    def test_mask_to_arms_all(self):
        self.assertEqual(mask_to_arms(0b111111), [0, 1, 2, 3, 4, 5])

    def test_mask_for_shape_reads_includes(self):
        # Minimal template: end = +Z only (Include South at Z=1).
        tpl = {"Shapes": {"end": {"PatternsToMatchAnyOf": [{"RulesToMatch": [
            {"Position": {"X": 0, "Y": 0, "Z": 1}, "IncludeOrExclude": "Include"},
            {"Position": {"X": 0, "Y": 0, "Z": -1}, "IncludeOrExclude": "Exclude"},
            {"Position": {"X": 1, "Y": 0, "Z": 0}, "IncludeOrExclude": "Exclude"},
        ]}]}}}
        self.assertEqual(mask_for_shape(tpl, "end"), 1 << 4)  # +Z = bit 4


class AssignmentCountTest(unittest.TestCase):
    def _counts(self, k):
        arms = list(range(k))
        a = tip_assignments(arms)
        ones = [x for x in a if len(x) == 1]
        twos = [x for x in a if len(x) == 2]
        return len(ones), len(twos), len(a)

    def test_arity_counts_match_design_formula(self):
        # per shape with k arms: 2k one-tip, 2k(k-1) two-tip.
        for k in range(1, 7):
            ones, twos, total = self._counts(k)
            self.assertEqual(ones, 2 * k, f"k={k} one-tip")
            self.assertEqual(twos, 2 * k * (k - 1), f"k={k} two-tip")
            self.assertEqual(total, 2 * k + 2 * k * (k - 1), f"k={k} total")

    def test_two_tip_faces_strictly_ascending(self):
        for asg in tip_assignments([5, 2, 0, 4]):  # unsorted input
            faces = [f for f, _ in asg]
            self.assertEqual(faces, sorted(faces))
            self.assertEqual(len(set(faces)), len(faces))  # no arm tipped twice

    def test_kinds_are_push_or_pull(self):
        for asg in tip_assignments([0, 1, 2]):
            for _f, kind in asg:
                self.assertIn(kind, ("p", "l"))

    def test_total_604_over_template_distribution(self):
        # The authoritative template distribution {1:3,2:5,3:8,4:6,5:3,6:1}.
        dist = {1: 3, 2: 5, 3: 8, 4: 6, 5: 3, 6: 1}
        total = sum(n * (2 * k + 2 * k * (k - 1)) for k, n in dist.items())
        self.assertEqual(total, 604)


class NamingTest(unittest.TestCase):
    def test_model_name_one_tip(self):
        self.assertEqual(model_name("ItemPipe", "end", ((4, "p"),)),
                         "ItemPipe_end_t4p")

    def test_model_name_two_tip(self):
        self.assertEqual(model_name("ItemPipe", "tee", ((0, "p"), (4, "l"))),
                         "ItemPipe_tee_t0p_t4l")

    def test_state_key_matches_design_example(self):
        # design: Tee_T0p_T4l
        self.assertEqual(state_key("Tee", ((0, "p"), (4, "l"))), "Tee_T0p_T4l")

    def test_state_key_one_tip(self):
        self.assertEqual(state_key("Vertical_End_Up", ((2, "p"),)),
                         "Vertical_End_Up_T2p")


class YawMathTest(unittest.TestCase):
    def test_yaw_for_face_table(self):
        # +Z is the base arm (yaw 0); yaw carries it onto each horizontal face.
        self.assertEqual(YAW_FOR_FACE, {4: 0, 0: 1, 5: 2, 1: 3})

    def test_yaw_position_carries_plus_z_to_each_face(self):
        # Stub_N centre (0,16,12) about block centre (0,16,0).
        n = {"x": 0, "y": 16, "z": 12}
        self.assertEqual(yaw_position(n, 0), {"x": 0, "y": 16, "z": 12})   # +Z (N)
        self.assertEqual(yaw_position(n, 1), {"x": 12, "y": 16, "z": 0})   # +X (E)
        self.assertEqual(yaw_position(n, 2), {"x": 0, "y": 16, "z": -12})  # -Z (S)
        self.assertEqual(yaw_position(n, 3), {"x": -12, "y": 16, "z": 0})  # -X (W)

    def test_yaw_position_preserves_y(self):
        self.assertEqual(yaw_position({"x": 3, "y": 20, "z": 5}, 1)["y"], 20)

    def test_yaw_size_swaps_xz_on_odd_steps(self):
        s = {"x": 16, "y": 16, "z": 8}
        self.assertEqual(yaw_size(s, 0), {"x": 16, "y": 16, "z": 8})
        self.assertEqual(yaw_size(s, 1), {"x": 8, "y": 16, "z": 16})
        self.assertEqual(yaw_size(s, 2), {"x": 16, "y": 16, "z": 8})
        self.assertEqual(yaw_size(s, 3), {"x": 8, "y": 16, "z": 16})

    def test_yaw_position_is_periodic(self):
        p = {"x": 5, "y": 16, "z": 12}
        self.assertEqual(yaw_position(p, 4), p)

    def test_yaw_texture_layout_cycles_side_faces(self):
        tl = {f: {"offset": {"x": i, "y": 0}, "mirror": {"x": False, "y": False},
                  "angle": 0}
              for i, f in enumerate(["back", "front", "left", "right", "top", "bottom"])}
        out = yaw_texture_layout(tl, 1)
        # back->left, left->front, front->right, right->back
        self.assertEqual(out["left"]["offset"]["x"], tl["back"]["offset"]["x"])
        self.assertEqual(out["front"]["offset"]["x"], tl["left"]["offset"]["x"])
        self.assertEqual(out["right"]["offset"]["x"], tl["front"]["offset"]["x"])
        self.assertEqual(out["back"]["offset"]["x"], tl["right"]["offset"]["x"])

    def test_yaw_texture_layout_caps_remap_off_original(self):
        # The caps do NOT ride the side cycle: k=1/3 take a side-band texel onto both
        # caps; k=2 swaps top<->bottom. Sourced off the ORIGINAL layout (not the
        # already-cycled one). Pins the cap-face bug fix.
        tl = {f: {"offset": {"x": i, "y": 0}, "mirror": {"x": False, "y": False},
                  "angle": 0}
              for i, f in enumerate(["back", "front", "left", "right", "top", "bottom"])}
        k1 = yaw_texture_layout(tl, 1)
        self.assertEqual(k1["top"], tl["left"])
        self.assertEqual(k1["bottom"], tl["left"])
        k2 = yaw_texture_layout(tl, 2)
        self.assertEqual(k2["top"], tl["bottom"])
        self.assertEqual(k2["bottom"], tl["top"])
        k3 = yaw_texture_layout(tl, 3)
        self.assertEqual(k3["top"], tl["right"])
        self.assertEqual(k3["bottom"], tl["right"])

    def test_yaw_texture_layout_k0_identity(self):
        tl = {f: {"offset": {"x": i, "y": i}, "mirror": {"x": True, "y": False},
                  "angle": 90}
              for i, f in enumerate(["back", "front", "left", "right", "top", "bottom"])}
        self.assertEqual(yaw_texture_layout(tl, 0), tl)

    def test_yaw_texture_layout_four_steps_identity(self):
        tl = {f: {"offset": {"x": i, "y": i}, "mirror": {"x": False, "y": True},
                  "angle": 90}
              for i, f in enumerate(["back", "front", "left", "right", "top", "bottom"])}
        self.assertEqual(yaw_texture_layout(tl, 4), tl)


class CollarCapInvarianceTest(unittest.TestCase):
    """A COLLAR is a flat ring whose top/bottom are real caps: under a yaw about +Y
    those faces are geometrically invariant, so they MUST keep their own texel (the
    uniform rim band) verbatim while only the 4 side faces cycle. This is the bug the
    stub-tube CAP_SOURCE remap got wrong (it remapped a side-band texel onto the caps).

    Pinned against the authored +Z pull/push collars (off 10,50 / 0,50 ang0 on caps)
    yawed onto the horizontal faces E/W/S (k = 1/3/2)."""

    @classmethod
    def setUpClass(cls):
        cls.collars = {}
        for kind, fn in (("l", "ItemPipe_end_pull.blockymodel"),
                         ("p", "ItemPipe_end_push.blockymodel")):
            path = CORPUS / fn
            if not path.exists():
                raise unittest.SkipTest(f"authored corpus not found at {CORPUS}")
            model = json.loads(path.read_text())
            cls.collars[kind] = next(n for n in model["nodes"]
                                     if n["name"] == "Collar")

    def test_collar_node_caps_invariant_under_yaw(self):
        # yaw_node keys off the node name "Collar": caps stay verbatim.
        for kind, collar in self.collars.items():
            src_tl = collar["shape"]["textureLayout"]
            for k in (1, 2, 3):  # E(1), S(2), W(3) horizontal faces
                out = yaw_node(collar, k)["shape"]["textureLayout"]
                self.assertEqual(out["top"], src_tl["top"],
                                 f"{kind} collar top changed under yaw{k}")
                self.assertEqual(out["bottom"], src_tl["bottom"],
                                 f"{kind} collar bottom changed under yaw{k}")

    def test_authored_pull_collar_caps_value(self):
        # The data-proven pull-collar cap value: off(10,50) ang0, unchanged by yaw.
        want = {"offset": {"x": 10, "y": 50},
                "mirror": {"x": False, "y": False}, "angle": 0}
        for k in (1, 2, 3):
            out = yaw_node(self.collars["l"], k)["shape"]["textureLayout"]
            self.assertEqual(out["top"], want, f"pull collar top at yaw{k}")
            self.assertEqual(out["bottom"], want, f"pull collar bottom at yaw{k}")

    def test_pull_collar_side_faces_cycle(self):
        # The 4 side faces still cycle (this part was already correct). At E (k=1):
        #   back/front = off(10,52) ang270, left/right = off(10,40) ang0 (the opening).
        out = yaw_node(self.collars["l"], 1)["shape"]["textureLayout"]
        side_band = {"offset": {"x": 10, "y": 52},
                     "mirror": {"x": False, "y": False}, "angle": 270}
        opening = {"offset": {"x": 10, "y": 40},
                   "mirror": {"x": False, "y": False}, "angle": 0}
        self.assertEqual(out["back"], side_band)
        self.assertEqual(out["front"], side_band)
        self.assertEqual(out["left"], opening)
        self.assertEqual(out["right"], opening)

    def test_texture_layout_caps_invariant_flag(self):
        # The pure transform honours the caps_invariant flag (caps verbatim, sides cycle).
        tl = {f: {"offset": {"x": i, "y": i}, "mirror": {"x": False, "y": False},
                  "angle": 0}
              for i, f in enumerate(["back", "front", "left", "right", "top", "bottom"])}
        for k in (1, 2, 3):
            out = yaw_texture_layout(tl, k, caps_invariant=True)
            self.assertEqual(out["top"], tl["top"], f"cap-invariant top at k={k}")
            self.assertEqual(out["bottom"], tl["bottom"], f"cap-invariant bottom at k={k}")
        # And the side cycle still runs.
        out1 = yaw_texture_layout(tl, 1, caps_invariant=True)
        self.assertEqual(out1["left"], tl["back"])
        self.assertEqual(out1["front"], tl["left"])


class RotatedUVProofTest(unittest.TestCase):
    """The proof that was missing: a yaw of the authored +Z stub must BYTE-MATCH the
    artist-authored Stub_E / Stub_S / Stub_W (all six textureLayout faces, plus
    position + size). This is what catches the cap-face / angle / mirror bug class
    that the +Z round-trip (yaw 0) cannot see."""

    @classmethod
    def setUpClass(cls):
        cross = CORPUS / "ItemPipe_cross.blockymodel"
        if not cross.exists():
            raise unittest.SkipTest(f"authored corpus not found at {CORPUS}")
        cls.model = json.loads(cross.read_text())
        cls.stubs = {n["name"]: n for n in cls.model["nodes"]
                     if n["name"].startswith("Stub_")}

    def _assert_yaw_matches(self, target_name, k):
        src = self.stubs["Stub_N"]
        got = yaw_node(src, k)
        want = self.stubs[target_name]
        # Ignore id + name (the generator canonicalises those); geometry +
        # textureLayout (offset, mirror, angle on every face) must byte-match.
        def norm(n):
            c = copy.deepcopy(n)
            c.pop("id", None)
            c.pop("name", None)
            return c
        self.assertEqual(norm(got), norm(want),
                         f"yaw{k}(Stub_N) != authored {target_name}")

    def test_yaw1_matches_authored_stub_e(self):
        self._assert_yaw_matches("Stub_E", 1)

    def test_yaw2_matches_authored_stub_s(self):
        self._assert_yaw_matches("Stub_S", 2)

    def test_yaw3_matches_authored_stub_w(self):
        self._assert_yaw_matches("Stub_W", 3)

    def test_every_face_textureLayout_matches(self):
        # Explicit per-face assertion so a regression names the offending face.
        faces = ["back", "front", "left", "right", "top", "bottom"]
        for target, k in (("Stub_E", 1), ("Stub_S", 2), ("Stub_W", 3)):
            got = yaw_node(self.stubs["Stub_N"], k)["shape"]["textureLayout"]
            want = self.stubs[target]["shape"]["textureLayout"]
            for f in faces:
                self.assertEqual(got[f], want[f],
                                 f"{target} face '{f}' mismatch under yaw{k}")


if __name__ == "__main__":
    unittest.main()
