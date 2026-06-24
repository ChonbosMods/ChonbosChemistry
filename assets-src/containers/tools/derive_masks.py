#!/usr/bin/env python3
"""Derive liquid masks (by diffing empty vs water textures) and build neutral grayscale masters.

For tankard + bucket: the liquid region is the set of pixels that differ between the empty
texture and the _Water texture. We report the tight bounding box + fill ratio so we can use a
single LiquidMask rectangle. For the mug there is no per-fluid texture (one shared texture), so
its mask is derived separately from the model UV (see MASKS.md); here we just grayscale that box.

Master = the _Water (or shared) texture with the liquid bbox converted to per-pixel luminance
(0.299R + 0.587G + 0.114B) written to all 3 channels, alpha preserved, everything else verbatim.
SubstanceLiquidTinter multiplies substance color into that grayscale box -> shaded colored liquid.
"""
import sys
from PIL import Image
import numpy as np

REF = "/home/keroppi/Development/Hytale/models/references/Common/Blocks"
OUT = "/home/keroppi/Development/Hytale/ChonbosChemistry/assets-src/containers"


def load(p):
    return np.asarray(Image.open(p).convert("RGBA"), dtype=np.uint8)


def bbox_of_diff(empty, water):
    """Return (x, y, w, h, fill_ratio, n_diff) of pixels that differ between empty and water."""
    # A pixel is part of the liquid if any RGBA channel differs.
    diff = np.any(empty.astype(int) != water.astype(int), axis=2)
    ys, xs = np.where(diff)
    if len(xs) == 0:
        return None
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    w = x1 - x0 + 1
    h = y1 - y0 + 1
    n_diff = int(diff.sum())
    fill = n_diff / (w * h)
    return (x0, y0, w, h, fill, n_diff)


def grayscale_box(img, x, y, w, h):
    """Return a copy of img with box [x,y,w,h] replaced by per-pixel luminance (alpha kept)."""
    out = img.copy()
    box = out[y:y + h, x:x + w].astype(float)
    lum = 0.299 * box[..., 0] + 0.587 * box[..., 1] + 0.114 * box[..., 2]
    lum = np.clip(np.round(lum), 0, 255).astype(np.uint8)
    out[y:y + h, x:x + w, 0] = lum
    out[y:y + h, x:x + w, 1] = lum
    out[y:y + h, x:x + w, 2] = lum
    # alpha (channel 3) untouched
    return out


def verify_only_box_changed(src, master, x, y, w, h):
    """Assert master differs from src ONLY inside [x,y,w,h]."""
    diff = np.any(src.astype(int) != master.astype(int), axis=2)
    outside = diff.copy()
    outside[y:y + h, x:x + w] = False
    n_outside = int(outside.sum())
    n_inside = int(diff[y:y + h, x:x + w].sum())
    return n_outside, n_inside


def do_diff_container(name, empty_p, water_p):
    empty = load(empty_p)
    water = load(water_p)
    assert empty.shape == water.shape, f"{name}: shape mismatch {empty.shape} vs {water.shape}"
    H, W = empty.shape[:2]
    res = bbox_of_diff(empty, water)
    if res is None:
        print(f"{name}: NO DIFF FOUND")
        return None
    x, y, w, h, fill, n = res
    print(f"{name}: texture {W}x{H}  LiquidMask(x={x}, y={y}, w={w}, h={h})  "
          f"diff_px={n}  bbox_area={w*h}  fill_ratio={fill:.3f}")
    return (W, H, x, y, w, h, fill, water)


def main():
    print("=== Step 1: derive tankard + bucket masks by diffing empty vs water ===")
    tank = do_diff_container(
        "TANKARD",
        f"{REF}/Miscellaneous/Tankard_Texture.png",
        f"{REF}/Miscellaneous/Tankard_Texture_Water.png",
    )
    buck = do_diff_container(
        "BUCKET (Village 64x64, referenced by filled state)",
        f"{REF}/Decorative_Sets/Village/Bucket_Texture.png",
        f"{REF}/Decorative_Sets/Village/Bucket_Texture_Water.png",
    )
    # The raw diff bbox unions the top water surface with faint shading diffs on the side faces
    # and a thin right-edge strip (only ~25% fill). The actual visible liquid SURFACE (the
    # "Middle" box top face, UV ~(2,3)) is a clean solid square at (4,5,24,24) with 95.8% diff
    # fill. We tint only that surface; side-face shading is left vanilla (outside the mask).
    BUCKET_MASK = (4, 5, 24, 24)

    print("\n=== Step 3: build masters ===")
    # Tankard master <- Tankard_Texture_Water.png
    W, H, x, y, w, h, fill, water = tank
    master = grayscale_box(water, x, y, w, h)
    Image.fromarray(master, "RGBA").save(f"{OUT}/tankard_master.png")
    no, ni = verify_only_box_changed(water, master, x, y, w, h)
    print(f"tankard_master.png {W}x{H}: changed_inside={ni} changed_outside={no} (outside must be 0)")

    # Bucket master <- Village Bucket_Texture_Water.png (use the curated surface mask, not raw bbox)
    W, H, _bx, _by, _bw, _bh, _bfill, water = buck
    x, y, w, h = BUCKET_MASK
    master = grayscale_box(water, x, y, w, h)
    Image.fromarray(master, "RGBA").save(f"{OUT}/bucket_master.png")
    no, ni = verify_only_box_changed(water, master, x, y, w, h)
    print(f"bucket_master.png {W}x{H}: changed_inside={ni} changed_outside={no} (outside must be 0)")

    # Mug master <- shared Mug_Texture.png, box from UV (see step 2 analysis below)
    mug_src_p = f"{REF}/Miscellaneous/Mug_Texture.png"
    mug = load(mug_src_p)
    MH, MW = mug.shape[:2]
    # Mug liquid surface: Liquid quad UV offset (49,41) is MIRRORED (mirror.x=true), so the
    # sampled region starts at 49-12=37. Confirmed by scanning the texture: the only opaque
    # bluish 12x12 swatch sits at x:37..48, y:41..52, fully solid, neighbors transparent.
    mx, my, mw, mh = 37, 41, 12, 12
    master = grayscale_box(mug, mx, my, mw, mh)
    Image.fromarray(master, "RGBA").save(f"{OUT}/mug_master.png")
    no, ni = verify_only_box_changed(mug, master, mx, my, mw, mh)
    print(f"mug_master.png {MW}x{MH}: liquid box ({mx},{my},{mw},{mh}) "
          f"changed_inside={ni} changed_outside={no} (outside must be 0)")


if __name__ == "__main__":
    main()
