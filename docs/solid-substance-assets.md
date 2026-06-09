> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](design.md) (see its §0 supersession map).

# Solid-substance assets (jar items, textures, icons)

Every solid element/compound (`phase() == SOLID`, ~205 of them) ships as a **placeable
model-block**: the shared `Solid` jar with its liquid recolored to the substance's `color()`.
Item JSON, tinted texture, and inventory icon are **generated at build time** from the substance
data — there is no runtime texture tint in Hytale, so color is baked into real files.

## Generate / regenerate

```bash
./gradlew generateSolidSubstanceAssets
```

This runs `impl.assetgen.SubstanceAssetGenerator`, which for each solid writes into
`src/main/resources/`:

| Output | Path |
|--------|------|
| Item def (model-block) | `Server/Item/Items/Chemistry/<id>.json` |
| Tinted jar texture | `Common/Items/Chemistry/Substance_Textures/<id>.png` |
| Inventory icon (64px) | `Common/Icons/ItemsGenerated/<id>.png` |
| Names | `Server/Languages/en-US/server.lang` |

`<id>` = `Chem_Solid_{Element|Compound}_<Name>` (PascalCase per segment; keyed on **name**, not
formula — polymers like Cellulose/Starch share `(C6H10O5)n`). The generated tree is gitignored.

The devServer loads the mod from the **classpath** (`build/resources/main`), and `processResources`
is incremental, so after changing ids **clear stale build copies** before reloading:

```bash
rm -rf build/resources/main/{Common/Items/Chemistry,Common/Icons,Server/Item/Items/Chemistry,Server/Languages}
```

There is **no hot-reload** — restart the devServer to pick up regenerated assets.

## Inputs (committed, in `assets-src/`)

| File | What it is | Regenerate when |
|------|-----------|-----------------|
| `master_white.png` | 32×64 jar texture, liquid desaturated to a 175–255 gray ramp | model UVs change |
| `icon_master.png` | Blockbench render of the jar with the white master | model geometry changes |
| `icon_liquid_mask.png` | same render with only the `Liquid` cube visible | model geometry changes |

The generator multiplies the **liquid region only** by the substance color:
- Texture: a fixed UV rect `LiquidMask(15, 0, 14, 20)` (the `Liquid` node's UV box on the 32×64 atlas).
- Icon: the pixels marked opaque in `icon_liquid_mask.png` (`SubstanceIcon.render` → tint, square-crop, scale to 64px).

## Sizing

Placed-block size is `BlockType.CustomModelScale` in `SolidSubstanceAssets.itemJson`.
Current value: **`0.45`** (tuned in-game; 0.6 = mug parity was slightly too big). Change the constant
and regenerate.

## When you edit the model (`~/Development/Hytale/models/props/solid.bbmodel`)

The shipped `.blockymodel`, the icon renders, and (if UVs moved) the texture mask all derive from the
model. After editing in Blockbench, re-derive them via the MCP:

1. **Load** the updated `solid.bbmodel`, **apply** `master_white.png` to its texture, and **export**:
   ```js
   Codecs.project.load(JSON.parse(require('fs').readFileSync('<solid.bbmodel>','utf8')), {path:'<...>'});
   Texture.all[0].updateSource('data:image/png;base64,' + require('fs').readFileSync('<master_white.png>').toString('base64'));
   require('fs').writeFileSync('<Solid.blockymodel>', Codecs.blockymodel.compile({}));
   ```
   Copy the result to `src/main/resources/Common/Items/Chemistry/Solid.blockymodel`.

2. **Re-render** both icon inputs from the same camera, **`{crop:false}`** so they align (the default
   auto-crops to content and the two frames won't line up):
   ```js
   // full jar:
   Preview.selected.screenshot({crop:false}, url => fs.writeFileSync('<icon_master.png>', Buffer.from(url.split(',')[1],'base64')));
   // liquid only (hide the other cubes, screenshot, then restore visibility):
   Cube.all.forEach(c => { if (c.name !== 'Liquid') c.visibility = false; }); Canvas.updateAll();
   Preview.selected.screenshot({crop:false}, url => { fs.writeFileSync('<icon_liquid_mask.png>', ...); /* restore visibility */ });
   ```
   Copy to `assets-src/icon_master.png` and `assets-src/icon_liquid_mask.png`.

3. **Check the liquid UV.** If the `Liquid` node's UV box moved, update `LiquidMask` in
   `SubstanceAssetGenerator` (currently `(15, 0, 14, 20)` on the 32×64 atlas). If UVs are unchanged,
   `master_white.png` and the texture mask are still valid.

4. **Regenerate** (and clear stale build copies), then restart the devServer.

## Why it's a block, not a plain item

A plain item (`Model`/`Texture` at top level) renders a flat icon and can't be held or placed. The
jar is defined like vanilla `Deco_Mug`: `BlockType { DrawType: "Model", CustomModel, CustomModelTexture,
CustomModelScale, Opacity: "Transparent", ... }` + `Block_Primary`/`Block_Secondary` interactions +
`Categories: ["Blocks.Deco"]`. Names use `server.items.<id>.name` in the item but `items.<id>.name`
in `server.lang`. Icons must be shipped (a missing `Icon` renders a "?").
