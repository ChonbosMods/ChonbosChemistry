# Container liquid masks + masters (Task 5A spec for the container-override generator)

Inputs the container generator (next task) codes against. For each container we have:
a neutral grayscale **master** PNG (liquid region desaturated, everything else vanilla),
the exact **`LiquidMask(x, y, w, h)`** rectangle, the model + base texture the filled state
references, and the empty/`BrokenItem` id. `SubstanceLiquidTinter.tint(master, mask, color)`
multiplies the substance color into ONLY the mask rectangle (grayscale ramp -> shaded hue);
everything outside the rectangle stays vanilla.

All masters and masks were derived by `tools/derive_masks.py` (empty-vs-water diff for
tankard/bucket; model-UV + texture scan for the mug). Coordinates are top-left origin pixels.

## Tankard
- master: `tankard_master.png`
- texture dimensions: 32 x 32
- `LiquidMask(2, 2, 6, 6)`  (diff fill 88.9%: rounded-corner well, clean)
- filled-state model: `Blocks/Miscellaneous/Tankard.blockymodel`
- filled-state base texture: `Blocks/Miscellaneous/Tankard_Texture_Water.png`
- master built from: `Tankard_Texture_Water.png`
- empty / `BrokenItem`: `Deco_Tankard`  (vanilla template `Deco_Tankard.vanilla.json`)
- vanilla Filled_* states to preserve: `Filled_Water`

## Bucket
- master: `bucket_master.png`
- texture dimensions: 64 x 64
- `LiquidMask(4, 5, 24, 24)`  (diff fill 95.8%: top water surface of the "Middle" box)
- NOTE: the raw empty-vs-water diff bbox is (4,0,60,64) at only 25% fill: it unions the
  water SURFACE with faint shading diffs on side faces + a thin right-edge strip. The correct,
  clean mask is the top surface square (4,5,24,24); side-face shading is left vanilla.
- filled-state model: `Blocks/Decorative_Sets/Village/Bucket_Full.blockymodel`
- filled-state base texture: `Blocks/Decorative_Sets/Village/Bucket_Texture_Water.png`
- master built from: Village `Bucket_Texture_Water.png` (64x64)
  - WARNING: there is ALSO a `Blocks/Miscellaneous/Bucket_Texture_Water.png` (64x32, different
    content). The filled state references the **Village** path/model, so use the Village texture.
- empty / `BrokenItem`: `Container_Bucket`  (vanilla template `Container_Bucket.vanilla.json`)
- vanilla Filled_* states to preserve: `Filled_Water`, `Filled_Milk`, `Filled_Mosshorn_Milk`

## Mug
- master: `mug_master.png`
- texture dimensions: 64 x 64
- `LiquidMask(37, 41, 12, 12)`  (fill 100%: solid bluish swatch, neighbors transparent)
- NOTE: the mug has no per-fluid texture (one shared `Mug_Texture.png`). The liquid surface is
  the "Liquid" quad in the model; its UV offset (49,41) is MIRRORED (mirror.x=true), so the
  sampled region starts at 49-12=37. Confirmed by texture scan: the only opaque bluish 12x12
  swatch is exactly x:37..48, y:41..52.
- filled-state model: `Blocks/Miscellaneous/Mug.blockymodel`
  (filled state uses `CustomModelAnimation: Blocks/Miscellaneous/Mug_Full.blockyanim`)
- filled-state base texture: `Blocks/Miscellaneous/Mug_Texture.png`  (shared empty+full texture)
- master built from: `Mug_Texture.png`
- empty / `BrokenItem`: `Deco_Mug`  (vanilla template `Deco_Mug.vanilla.json`)
- vanilla Filled_* states to preserve: `Filled_Water`

## Vanilla template files captured (override bases)
- `Deco_Mug.vanilla.json`        <- Server/Item/Items/Deco/Deco_Mug.json
- `Deco_Tankard.vanilla.json`    <- Server/Item/Items/Deco/Deco_Tankard.json
- `Container_Bucket.vanilla.json`<- Server/Item/Items/Container/Container_Bucket.json

The generator must inject `Filled_<substance>` states while PRESERVING the vanilla Filled_*
states listed above (do not drop Filled_Water / Filled_Milk / Filled_Mosshorn_Milk).

## How to add a new fluid container

The whole filled-container system (tinted liquid textures, filled-state JSON, tinted container
icons, AND item name + description lang) is driven by the `FluidContainers.ALL` registry. Adding a
new container type is a **registry entry + art masters only** : NO generator/renderer code edits.

`FluidContainers.FluidContainer` is the single source of truth for a container: `id`, `displayName`,
`model`, `animation`, `fillMode` (DRINK/POUR), `masterFile`, `liquidMask`, `iconMasterFile`,
`iconMaskFile`, `tintedTextureDir`/`tintedTexturePrefix`, `brokenItem`, `vanillaTemplate`,
`itemOutputPath`, `preservedStates`, and the carried vanilla render extras. Everything the generator
loops over comes from this record : nothing about a specific container is hardcoded elsewhere.

Steps:

1. **Capture the vanilla item template.** Copy the vanilla `<Id>.json` item into this directory as
   `<Id>.vanilla.json` (the override base). Note its real asset-pack path : that is the
   `itemOutputPath`. Also note its vanilla display name from `server.lang`
   (`items.<Id>.name`) : that is the `displayName` (used verbatim for `<displayName> (<Fluid>)`).
2. **Derive the liquid master + `LiquidMask`** with `tools/derive_masks.py` (neutral grayscale
   master PNG + the `LiquidMask(x, y, w, h)` rectangle the tinter recolors).
3. **Derive the icon master + mask** with `tools/derive_icon_masks.py`
   (`<Id>_icon_master.png` + `<Id>_icon_mask.png` : grayscale container-icon master + per-pixel
   liquid mask, tinted per substance into the filled state's `Icon`).
4. **Add one `FluidContainer` entry** to `FluidContainers.ALL` with `id`, `displayName`, `model`,
   `animation` (null if none), `fillMode` (DRINK = drinkable mug/tankard, POUR = bucket that spills
   into the world), the master/mask files, tinted-texture dir/prefix, `brokenItem`, `vanillaTemplate`,
   `itemOutputPath`, `preservedStates` (the vanilla `Filled_*` states to keep), and the carried
   vanilla render extras.
5. **Re-run** `./gradlew generateFluidContainers`.

The generated filled item reads `<displayName> (<Fluid name>)`, e.g.
`Wooden Bucket (Hydrogen peroxide)`, mimicking vanilla `Wooden Bucket (Water)`. The description is
`Contains <color is="#ffffff"><Fluid></color> that can be {placed in the world | drunk}.`, chosen by
`fillMode`.
