# Vanilla Hytale UI measurements (for machine-GUI uniformity)

Measured from the runtime `Assets.zip` (server 0.5.3): `Common/UI/Custom/Common.ui` (design system)
+ real shipping pages (ItemRepairPage 600×400, BarterPage 740×480, EntitySpawnPage, DroppedItemSlot,
BarterTradeRow, PrefabEditorSettings). Use these numbers for every CC machine GUI so they read as
native Hytale windows. Colors stay CC amber-on-slate (`CC_Master.ui`); everything else matches vanilla.

## Texture path rule (critical)
Texture paths resolve **relative to the .ui file's own location**. A page under `Pages/` must use
`"../Common/<tex>.png"`. Writing `"Common/<tex>.png"` from `Pages/` → looks in `Pages/Common/` →
missing-texture red-X. Macros (e.g. `@DecoratedContainer`) work because their paths resolve relative
to `Common.ui`, where they're defined.

## Containers
- `@DecoratedContainer` (runed) / `@Container` (plain). Header height **38**; body `ContainerPatch.png`
  Border 23; `#Content` default `Padding: (Full: 17)`, `LayoutMode: Top`.
- Real two-column page size: **740×480** (Barter). Single-column: 600×400 (ItemRepair).
- `@PageOverlay` scrim `#000000(0.45)`. `@BackButton` at `(Left:50, Bottom:50, 110×27)`.

## Item slots
- **Vanilla container item icon = 64px** (DroppedItemSlot, Barter output). Border box 68 (`Padding:2`,
  inner bg `#1a2530`), outer cell ~72. Quality-aware single slots use `ItemSlot { ShowQualityBackground:true }`.
- **ItemGrid** (only shipping one, EntitySpawnPage): `SlotSize 46, SlotIconSize 46, SlotSpacing 0,
  SlotBackground: "../Common/BlockSelectorSlotBackground.png"`. Defaults: no `AreItemsDraggable`/
  `RenderItemQualityBackground` set.
- Quantity label: bottom-right, `HorizontalAlignment:End, VerticalAlignment:End, RenderBold:true`;
  FontSize 15 (big slot) / 12 (small).
- → For a read-only multi-slot machine display via `ItemGrid`, use `SlotSize 64` + `SlotBackground` +
  `RenderItemQualityBackground:true` to match vanilla container icon size (CC choice).

## Buttons
- Primary/Secondary/Cancel: **height 44**, horizontal padding 24, patch border 12, label FontSize 17
  bold uppercase `#bfcdd5`. Small variants: height **32**, padding 16, FontSize 14.

## ProgressBar (canonical wrapped pattern)
```
Group { Anchor: (..., Height: 12); Background: "../Common/ProgressBar.png";   // track
  ProgressBar #X {
    Anchor: (Full: 0);
    BarTexturePath:    "../Common/ProgressBarFill.png";
    EffectTexturePath: "../Common/ProgressBarEffect.png";
    EffectWidth: 102; EffectHeight: 58; EffectOffset: 74;
    Value: 0.0;                                  // server sets 0..1 via set("#X.Value", f)
  }
}
```
`CircularProgressBar`: 48×48, `Value` 0..1, `Color #aa7c4a` on `#1a2030`, `MaskTexturePath:
"../Common/CircularProgressBarMask.png"`.

## Text / spacing
- Section header = `@Subtitle` style: **FontSize 15, RenderUppercase, RenderBold**, color `#96a9be`
  (CC uses amber `@LabelText`).
- Title bar: FontSize 15, uppercase, bold, `#b4c8c9`, FontName "Secondary".
- Divider = `@ContentSeparator`: `Height:1, Background:#2b3542` (CC: `@PanelDividerColor`).
- Section-to-section gap: **25** (buttons 20); sub-gaps 12 / 10 / 8.
- Default label `#96a9be`; gray caption `#878e9c`; muted `#5a6a7a`.

## Applied to CC_SmelterPanel (2026-06-21)
800×360 `@DecoratedContainer`, content `Padding Full:16`, two columns (404 / 300, gap 12). 64px
ItemGrids w/ SlotBackground; wrapped 12px ProgressBars w/ effect; 44px buttons; 15px uppercase
headers. Tokens in `Common/UI/Custom/CC_Master.ui`.
