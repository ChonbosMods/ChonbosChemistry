#!/usr/bin/env python3
"""Derive per-container ICON masters + per-pixel liquid masks for the filled-container icons (BUG 4).

Vanilla ships a per-fluid container icon (e.g. Container_Bucket_Water.png) AND an empty-container
icon (Container_Bucket.png), both 64x64 3D renders. The liquid pixels are exactly the set that
differ between empty and water. For each container we emit:

  <id>_icon_master.png  : the _Water icon with the liquid pixels desaturated to luminance
                          (everything else verbatim) -> SubstanceIcon.tint multiplies the
                          substance color into the masked pixels, reproducing the in-world look.
  <id>_icon_mask.png    : a transparent image whose ONLY opaque pixels are the liquid pixels
                          (alpha 255). Mirrors assets-src/icon_liquid_mask.png so SubstanceIcon
                          can reuse its per-pixel masked-multiply path verbatim.

These are the filled-container-icon analog of the body-texture masters in derive_masks.py.
"""
from PIL import Image
import numpy as np

REF = "/home/keroppi/Development/Hytale/models/references/Common/Icons/ItemsGenerated"
OUT = "/home/keroppi/Development/Hytale/ChonbosChemistry/assets-src/containers"

# container id -> (empty icon, water icon)
PAIRS = {
    "Container_Bucket": ("Container_Bucket", "Container_Bucket_Water"),
    "Deco_Mug": ("Deco_Mug", "Deco_Mug_Water"),
    "Deco_Tankard": ("Deco_Tankard", "Deco_Tankard_Water"),
}


def load(name):
    return np.asarray(Image.open(f"{REF}/{name}.png").convert("RGBA"), dtype=np.uint8)


def main():
    for cid, (empty_n, water_n) in PAIRS.items():
        empty = load(empty_n)
        water = load(water_n)
        assert empty.shape == water.shape, f"{cid}: shape mismatch"
        H, W = water.shape[:2]

        # Liquid pixels = pixels that differ between empty and water icon.
        diff = np.any(empty.astype(int) != water.astype(int), axis=2)
        n = int(diff.sum())

        # Master: water icon with liquid pixels desaturated to luminance (alpha preserved).
        master = water.copy()
        box = master[diff].astype(float)
        lum = 0.299 * box[..., 0] + 0.587 * box[..., 1] + 0.114 * box[..., 2]
        lum = np.clip(np.round(lum), 0, 255).astype(np.uint8)
        m = master.copy()
        idx = np.where(diff)
        m[idx[0], idx[1], 0] = lum
        m[idx[0], idx[1], 1] = lum
        m[idx[0], idx[1], 2] = lum
        Image.fromarray(m, "RGBA").save(f"{OUT}/{cid}_icon_master.png")

        # Mask: transparent everywhere, opaque (white, alpha 255) on liquid pixels only.
        mask = np.zeros((H, W, 4), dtype=np.uint8)
        mask[idx[0], idx[1]] = (255, 255, 255, 255)
        Image.fromarray(mask, "RGBA").save(f"{OUT}/{cid}_icon_mask.png")

        print(f"{cid}: {W}x{H} liquid_px={n} -> {cid}_icon_master.png + {cid}_icon_mask.png")


if __name__ == "__main__":
    main()
