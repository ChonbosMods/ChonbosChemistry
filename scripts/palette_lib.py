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
            if a.get("exempt") and b.get("exempt"):
                continue
            d = rgb_distance(parse_hex(a["hex"]), parse_hex(b["hex"]))
            if d < min_dist:
                errors.append(f"{a['key']} vs {b['key']}: distance {d:.0f} < {min_dist}")
    return errors
