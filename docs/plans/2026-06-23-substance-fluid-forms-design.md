# Substance Fluid Forms: Design

Date: 2026-06-23
Status: Design accepted (this doc). Plan + implementation not yet written.
Branch context: authored off `feature/cc-reclaimer` work; fluids are a standalone feature.

---

## §0. Decisions locked

| # | Decision |
|---|----------|
| F1 | **Scope = liquids + liquefied gases.** Fluid set is every `Phase==LIQUID` substance (39) plus a generated `Liquid <Name>` form for every `Phase==GAS` substance (27). ~66 fluids. Molten solids deferred. |
| F2 | **No schema change.** `Phase` stays the STP phase; the gas->liquid forms are *generator-derived*, not new data records. |
| F3 | **One authored base, everything else generated.** A single neutral master liquid texture is tinted per substance via the existing `SubstanceLiquidTinter`. Color, fog, particles, light/glow, viscosity, and hazards are all generated overlays. |
| F4 | **Hazards are composable.** A fluid applies *all* hazards its data triggers (radioactive + corrosive both fire), assembled into the engine's contact `Interactions.Collision` chain and the drink `Effect.Interactions` array. |
| F5 | **Viscosity/physics = generated, edit-in-place values.** The generator stamps default numeric values into each fluid's JSON; the author bulk-edits the generated files. No template buckets, no side-table. |
| F6 | **Affliction integration = immediate effects now, seam later.** v1 ships pure-JSON instant contact/drink effects + registers fluids as `RadiationSource`. The accumulating-dose affliction engine (designed, not built) plugs into a clean hook when it lands. |
| F7 | **Containers: override every water-holding vanilla container.** Mug, Tankard, Bucket (and any future water-capable container) get generated `Filled_<substance>` states. Standard for all future fluid forms: a new fluid registers itself into all fluid-capable containers. |
| F8 | **Fluids are naturally placeable world blocks**, not just a pipe payload. Worldgen pools (mercury/acid springs, etc.) are a future extension, not v1. |

---

## §1. Goal

Give every chemical fluid a first-class fluid form that behaves like vanilla water/lava/slime: a placeable world liquid you can wade through, with contact hazards; fillable into any water-capable container; drinkable with substance-appropriate effects. Achieve breadth (~66 fluids) by *generating* each from one master and the substance's existing data (chiefly `color()`), not by hand-authoring per fluid.

Non-goals (v1): molten-solid fluids; worldgen placement of fluid pools; the full accumulating affliction engine.

---

## §2. How Hytale models fluids (research findings)

Fluids are **100% data-driven JSON**: there is no code-side fluid registration. The engine discovers fluid assets from the asset pack. Source: decompiled `Server-0.5.3.jar`, classes under `com/hypixel/hytale/server/core/asset/type/fluid/` and `com/hypixel/hytale/builtin/fluid/`.

### 2.1 Files per vanilla fluid

| File | Role |
|------|------|
| `Server/BlockTypeList/Fluids.json` | Master list; add the fluid's id here |
| `Server/Item/Block/Fluids/<Name>_Source.json` | Source block: `MaxFluidLevel: 1`, spreads |
| `Server/Item/Block/Fluids/<Name>.json` | Flowing block: `Parent: <Name>_Source`, `MaxFluidLevel: 8`, demotes |
| `Server/Item/Block/FluidFX/<Name>.json` | Camera/underwater FX: fog, distortion, swim/sink physics |
| `Common/BlockTextures/Fluid_<Name>.png` | 32x32 surface texture (optionally `Transition_Fluid_<Name>.png` for edges) |
| `Server/Item/Items/Fluid/Fluid_<Name>.json` | The "fluid in hand" placement item: `Interactions.Secondary -> PlaceFluid: <Name>_Source` |

### 2.2 Where color lives (the key finding)

- **Surface appearance = baked into the PNG.** There is NO runtime tint for the fluid surface. To vary surface color per substance we tint a master texture at **build time** (exactly the existing solid/pipe pipeline).
- **JSON hex fields** drive the rest, swappable per substance:
  - `ParticleColor` (e.g. lava `#f94e11`) — break/splash particle tint.
  - `Light: { Color, Radius }` (e.g. lava `#e90`) — block light emission.
  - FluidFX `FogColor` (e.g. lava `#ff2d00`) — underwater fog (when `Fog: "Color"`/`"ColorLight"`; water uses `"EnvironmentTint"`).

### 2.3 Behavior fields

- **Physics / feel:** `Ticker.FlowRate` (lava 2.0, tar 12.0), `Ticker.CanDemote`, `Ticker.SupportedBy`, `Ticker.SpreadFluid`, `Ticker.Collisions` (fluid-fluid, e.g. lava+water->cobble). FluidFX `MovementSettings` (`SwimUpSpeed`, `SwimDownSpeed`, `SinkSpeed`, `HorizontalSpeedMultiplier`, `FieldOfViewMultiplier`), `DistortionAmplitude/Frequency`.
- **Contact hazard:** `Fluid.damageToEntities` (per-tick), and richer `Interactions.Collision` chains. Lava's collision applies a movement-slow effect then `EffectId: "Lava_Burn"`, gated `RequiredGameMode`, with a `Cooldown`. This is the composability hook: `Interactions` chain via `Next`, and `Cooldown` rate-limits.
- **Misc:** `Effect` (visual effect set name), `Opacity` (`Transparent`), `BlockParticleSetId`, `BlockSoundSetId`, `FluidFXId`, `Tags: { Fluid: [...] }`.

### 2.4 Runtime placement API

Fluids live in `FluidSection` per chunk section: `setFluid(x,y,z, fluid|id, level)`, `getFluid`, `getFluidLevel`. `FluidState` = `(fluidLevel, verticalFill)`. Spreading is driven by a `FluidTicker` subclass (`DefaultFluidTicker`, `FiniteFluidTicker`, `FireFluidTicker`) chosen per fluid asset. There is **no high-level bucket/fill API** — fill/empty is done in JSON via `RefillContainer` interactions (see §6) or, for code-placed fluids, via `FluidSection.setFluid`.

### 2.5 Color/Light protocol note

`protocol.Color` is **RGB only, no alpha**. Transparency is `Opacity` + `requiresAlphaBlending` + PNG alpha. `ColorLight` = radius + RGB. Our `Color` record maps cleanly (`color().r/g/b`, `toHex()`).

---

## §3. The fluid set (F1, F2)

Derived at generation time from the registry, no data change:

```
fluids = registry.all().filter(phase == LIQUID)                  // 39, natural liquids
       ∪ registry.all().filter(phase == GAS).map(asLiquefied)    // 27, "Liquid <Name>"
```

- Natural liquids keep their name: `Mercury`, `Sulfuric acid`, `Hydrogen peroxide`.
- Liquefied gases get a `Liquid <Name>` display label and a `Liquefied` id segment: e.g. helium -> `Liquid Helium`. Same `color()`, plus cryogenic hazard (§5) and likely a `coolant` role (§8).

### 3.1 Id / naming conventions

Mirror the solid convention (`Chem_Solid_{Element|Compound}_{Name}`):

| Artifact | Id pattern |
|----------|------------|
| World fluid block (source) | `Fluid_{Element\|Compound}_{Name}_Source` |
| World fluid block (flowing) | `Fluid_{Element\|Compound}_{Name}` |
| Placement item | `Chem_Fluid_{Element\|Compound}_{Name}` |
| FluidFX | reuse a small set of shared FX bases keyed by hazard family + per-fluid `FogColor` override |
| Container filled state | `Filled_{Element\|Compound}_{Name}` (per container, §6) |
| Liquefied gas | same patterns with a `Liquefied_` prefix on `{Name}`, label `Liquid <Name>` |

`{Name}` is the PascalCase sanitized substance name, matching `SolidSubstanceAssets`. Liquefied gases are keyed on the gas substance's identity (no new registry record).

---

## §4. Asset model: one base, generated overlays (F3, F5)

### 4.1 Master texture + tint

One authored `Fluid_Master.png` (clear/neutral liquid with a luminance ramp + a `FluidCoreMask` marking the tintable region), analogous to the solid jar master and `fluidpipe_on.png`. Per substance:

```
surface = SubstanceLiquidTinter.tint(Fluid_Master, FluidCoreMask, substance.color())
surface = GlowBoost.apply(surface, FluidCoreMask, GlowDeriver.tierFor(substance, registry))   // when tier > NONE
write Common/BlockTextures/Fluid_{id}.png
```

Reuses `SubstanceLiquidTinter`, `GlowBoost`, `GlowDeriver`, `SubstanceIcon` unchanged.

### 4.2 Generated, edit-in-place numeric values (F5)

The generator stamps a default into each fluid's `<Name>_Source.json` / `FluidFX/<Name>.json`; the author bulk-edits the produced files. Defaults derive from data where sensible, but are *values, not formulas baked into the engine*:

| Field | Default source |
|-------|----------------|
| `Ticker.FlowRate` | from `density` bucketed (denser -> slower) or a flat default; hand-tunable |
| FluidFX `SinkSpeed` / swim speeds | default water-like; cryo/dense overrides |
| `FogColor`, `ParticleColor` | `substance.color().toHex()` |
| `Light` | `substance.color()` when `GlowTier > NONE`, radius by tier |
| `Opacity` | `Transparent` default |

Because these land in plain JSON, the author can grep-and-edit across all fluids without touching the generator.

---

## §5. Composable hazard modules (F4, F6)

The generator inspects each substance and assembles a hazard list. Each module contributes (a) a `Interactions.Collision` segment on the world block and (b) an `Effect.Interactions` entry on every container's drink interaction. Modules compose via the engine's `Next` chaining and the drink array.

| Module | Trigger (data) | Contact effect | Drink effect | Notes |
|--------|----------------|----------------|--------------|-------|
| **Ignite** | `propertyFlags.flammable` | apply burn (reuse vanilla `Burn_Template`/`Lava_Burn`-style) | burn / damage | fire feedback is vanilla-native |
| **Radiation** | `isRadioactive` or `GlowTier > NONE` | instant radiation effect + block implements `RadiationSource` | radiation effect | RadiationSource lets the Geiger + future affliction see it |
| **Corrosive/Toxic** | `propertyFlags.corrosive`/`oxidizer`, or `toxicity` present | acid/poison damage (scaled by `toxicity.potency`) | poison effect (honor `toxicity.antidote` later) | compounds carry route/potency/effect/onset/antidote |
| **Cryo** | `Phase==GAS` (liquefied) | cold: `HorizontalSpeedMultiplier` slow + freeze damage | cold damage | also drives `coolant` role (§8) |
| **Benign** | none of the above | none | safe; optional mild thirst/heal | default |

### 5.1 v1 affliction stance (F6)

Radiation/toxicity are, by design, *accumulating afflictions* (`api/affliction`: "radiation poisoning and chemical toxicity are the same accumulating-harm mechanism"). That engine is **designed, not built** (only a `package-info`). So v1:

- Applies **immediate JSON effects** (instant burn/poison/cold) for in-game feedback now.
- **Registers radioactive fluid blocks as `RadiationSource`** (`api/radiation`) so the Geiger reads them and the future affliction engine has a source to sample.
- Leaves a single seam: a `FluidHazardExposure` hook (contact tick + on-drink) that v1 wires to immediate effects and the affliction engine later swaps to dose accumulation. No rework of the generated assets required when it lands — only the hook's body changes.

### 5.2 Cooldown / stacking discipline

Contact damage is rate-limited by the `Interactions.Collision.Cooldown` (as lava does). When multiple modules stack, each gets its own cooldown id so e.g. radiation and corrosion tick independently rather than starving each other.

---

## §6. Containers: override every water-capable container (F7)

### 6.1 The standard

Vanilla containers that can hold water — **Mug (`Deco_Mug`), Tankard (`Deco_Tankard`), Bucket (`Container_Bucket`)**, and any future water-capable container — are overridden with our asset pack. For each container we generate a `Filled_{id}` state per fluid. This is a deliberate, standardized **override** of vanilla assets (a conscious exception to the usual non-invasive reuse preference): the user wants substance fluids to behave exactly like water in the survival loop.

**Rule for all future fluid forms:** adding a fluid must register it into every fluid-capable container. The generator owns a `FLUID_CONTAINERS` registry (id -> base model/texture/master/liquid-mask + fill/drink interaction templates) and emits the cartesian product `containers × fluids`.

### 6.2 Per-fluid container mechanics (from vanilla)

Vanilla's filled-container pattern (researched from `Deco_Mug.json` / `Container_Bucket.json`):

- **Fill:** `Interactions.Secondary -> RefillContainer` with `States: { Filled_X: { AllowedFluids: ["X_Source"], TransformFluid: "Empty" } }`. We generate one `Filled_{id}` entry per fluid; `AllowedFluids` = that substance's source block.
- **State payload:** `BlockType.CustomModel` (the container model) + `CustomModelTexture` (per-fluid tinted texture, §6.3) + optional `CustomModelAnimation` (mug uses `Mug_Full.blockyanim`).
- **Drink:** `Interactions.Secondary -> Root_Secondary_Consume_Drink`; `InteractionVars.Effect.Interactions[]` = the composed hazard drink-effects (§5); `ConsumeSFX`/`ConsumedSFX`; `DurabilityModify` returns the empty container (`BrokenItem: Deco_Mug`). `Consumable: true`, `MaxDurability: 1`.
- **Empty/place:** the placement item (`Chem_Fluid_*`) carries `PlaceFluid: {id}_Source` so a filled container or hand can place the world fluid.

### 6.3 Container liquid textures

Vanilla buckets/tankards use a **separate texture per fluid** (`Bucket_Texture_Water.png`, `Tankard_Texture_Water.png`); mug reuses one texture. Since we have ~66 fluids, we generate per-(container, substance) textures by tinting each container's **liquid region** from a container master + liquid mask — same `SubstanceLiquidTinter` call as the surface. So each container needs: a base master texture + a `LiquidMask` marking the fillable region. Output: `Common/Blocks/Miscellaneous/{Container}_Texture_{id}.png` (or our asset path mirror).

### 6.4 Counts

3 containers × ~66 fluids = ~198 generated filled-states + ~198 tinted container textures, plus 66 world blocks (×2 source/flowing) + 66 FluidFX + 66 placement items + 66 surface textures + 66 icons. All generated; the only hand-authored inputs are the masters + masks + FX bases.

---

## §7. World presence (F8)

Fluids are full placeable world blocks (the `Fluid_*` source/flowing assets of §2). v1 placement vectors: the placement item (hand/filled container `PlaceFluid`), buckets, and the transport-layer FLUID payload emptying into the world. Code paths that place fluids programmatically use `FluidSection.setFluid`.

**Future (not v1):** worldgen seeding of fluid pools (mercury lakes, acid springs) via the biome/feature system. Called out so the block ids and tags are chosen to not preclude it.

---

## §8. Power-system tie-in (D34b)

Design D34b: a `coolant` tag is applied to fluids; each tagged fluid carries `heat_capacity`, `condensation_temp`, `latent_heat`, consumed by the converters (Turbine for above-ambient self-condensing coolants; Phase Changer for below-ambient cryo/liquefaction). Liquefied gases (liquid helium, liquid sodium later) are exactly these coolants.

The fluid generator should **stamp `coolant` tags + heat fields** onto fluids where the substance data supplies them, so the fluids feature and the (next-milestone) machine/power layer share one source of truth. Where the dataset lacks heat fields, the tag is omitted (no coolant role) — no blocking dependency on the power layer for v1.

---

## §9. Generator architecture

New code mirrors the proven `SubstanceAssetGenerator` / `FluidPipeTextureGenerator` pattern (build-time, Gradle-driven, no runtime tint).

### 9.1 New classes (impl/assetgen)

| Class | Role |
|-------|------|
| `WorldFluidGenerator` | For each fluid in the set: tint surface, write source+flowing block JSON, FluidFX, placement item, icon, register in `Fluids.json`, compose contact hazards, stamp physics + coolant tags, localization. |
| `FluidContainerGenerator` | For each `(container, fluid)`: tint container liquid texture, write `Filled_{id}` state into the container override, compose drink hazards, fill/empty interactions, localization. Driven by a `FLUID_CONTAINERS` registry. |
| `FluidAssets` | Static template helpers (id/path builders, block JSON, FluidFX JSON, item JSON, filled-state JSON) — the `SolidSubstanceAssets` analog. |
| `FluidHazardComposer` | Substance -> ordered hazard module list -> contact `Interactions.Collision` chain + drink `Effect.Interactions` array. Shared by both generators. |

Reused unchanged: `SubstanceLiquidTinter`, `GlowBoost`, `GlowDeriver`, `SubstanceIcon`, `Color`, `Phase`, `SubstanceRegistry`.

### 9.2 Gradle tasks

`generateWorldFluids`, `generateFluidContainers` (and a `generateFluidAssets` aggregate), alongside the existing `generateSolidSubstanceAssets` / `generateFluidPipeTextures`.

### 9.3 Authored inputs (the only hand work)

- `Fluid_Master.png` + `FluidCoreMask`.
- Per container: base master texture + `LiquidMask` (Mug, Tankard, Bucket).
- A small set of shared `FluidFX` bases (benign / hot / cryo) that the generator clones with a per-fluid `FogColor`.
- Hazard effect definitions reused/authored: vanilla `Burn_Template` (fire), new immediate radiation/corrosion/cryo `Status` effects.

### 9.4 Localization

Each generated id adds a `server.items.<id>.name` / block name entry to `Server/Languages/en-US/server.lang`, as the solid generator does. Liquefied gases localize to `Liquid <Name>`.

---

## §10. Risks / open questions

1. **Vanilla container override fragility.** Overriding `Deco_Mug`/`Deco_Tankard`/`Container_Bucket` means our asset pack must reproduce the vanilla state tree (incl. `Filled_Water`, milk variants) and re-merge on game updates. Mitigation: generate from a captured copy of the vanilla file + our injected states; diff against vanilla on update.
2. **Asset volume.** ~66 fluids fan out to ~600+ generated files. Keep them in a clearly-namespaced output tree and `.gitignore`-or-track decision consistent with how solid assets are handled.
3. **FluidFX base count.** Vanilla reuses FX (poison uses lava FX, tar uses water FX). Decide how many FX bases we author vs how much we override per fluid (probably 3 bases + `FogColor` swap).
4. **Cryo physics correctness.** Liquefied gases as wadeable blocks is unusual; confirm in-game that cold-slow + freeze reads well and doesn't soft-lock movement.
5. **Affliction seam shape.** The `FluidHazardExposure` hook's interface must be agreed with the affliction engine design (§4.4/§5.5) so v1 immediate-effects and later dose-accumulation share one call site.
6. **Coolant data coverage.** Whether the dataset currently carries `heat_capacity`/`condensation_temp` for the liquefied gases, or whether those are added with the power milestone. v1 omits where absent.
7. **Source-block spread for hazards.** Tune `FlowRate`/`CanDemote` so hazardous fluids don't grief unboundedly when placed.

---

## §11. Build + verify plan (high level)

1. Author masters/masks + FX bases + immediate hazard `Status` effects.
2. Implement `FluidAssets`, `FluidHazardComposer`, `WorldFluidGenerator`; wire `generateWorldFluids`. Verify a handful (water-like benign, a corrosive acid, a radioactive, a liquefied gas) place + render + hazard in-game (headless log + in-game per [[devserver-logs]] / [[user-controls-devservers]]).
3. Implement `FluidContainerGenerator` + `FLUID_CONTAINERS` registry; override Mug first, verify fill->carry->drink loop, then Tankard + Bucket.
4. Stamp `coolant` tags for liquefied gases (where data exists) for the power milestone.
5. Regenerate full set; spot-check counts + localization; commit generated assets.

Implementation plan (step-by-step, TDD where the composer/id logic is testable) to be written separately via `superpowers:writing-plans`.
