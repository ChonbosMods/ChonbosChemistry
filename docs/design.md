# Chonbo's Chemistry : Master Design

*Status: living master document, consolidated 2026-06-08. This is the single authoritative design for the whole mod: vision, data, transport, machines, circuitry, and the energy economy that ties them together. It supersedes the per-feature plan docs in `docs/plans/` and the prior main doc `docs/machines-and-power-design.md`, which are retained as historical derivation/detail only (see the supersession map in §0). Numbers marked `[TUNE]` are balancing placeholders; items marked `[DECIDE]` are open design questions (collected in §11).*

---

## 0. Reading guide, status & decisions ledger

### 0.1 What this mod is

A chemistry + nuclear-physics tech mod for Hytale, built on a comprehensive real-world dataset (118 elements, 673 isotopes, 153 compounds). It ships as one plugin: the substance foundation, the transport network, the machines that transform matter, and the circuitry that automates them are all core, not separate downstream layers.

### 0.2 The stack (read top-down)

```
   CONTROL      Circuitry: digital logic that gates/reads machines        §8
      |         (wires, gates, sensors, machine I/O bridge)
   MACHINES     Machines + generators; reactions, transmutation & power     §7
      |         (built on Hytale's bench engine; energy-gated)
   TRANSPORT    Five pipe channels carrying resources between blocks       §6   [DONE]
      |         (energy / fluid / gas / item / neutron)
   MATTER       Substances: element / isotope / formula / phase            §2,§3
   ----------------------------------------------------------------------
   ENERGY       One economy underlying transport, machines, and circuitry  §5
```

Energy is the cross-cutting currency: transport moves it, machines spend/produce it, circuitry sips it. There is no second economy.

### 0.3 Status at a glance

| Layer | State |
|---|---|
| Substance data + schema + registry | Schema & dataset built (944-record decode test green); registry validated, ready to implement |
| Solid-substance assets (placeable jars) | Built; color/glow enrichment validated, not yet applied |
| Energy standard (`long`, internal/external, fair-split) | Core done; rate-cap/ratio helpers landed in the network rework |
| **Transport layer (POWER/FLUID/GAS/ITEM)** | **DONE, in-game verified** (networks, flow-states, wrench, balancing, carry, live panel) |
| Neutron pipe channel | Designed (fungible + length cap); not started |
| Pipe push/pull tip visuals | ITEM shipped; Fluid/Gas/Power deferred to a pipe-art redo |
| **Machine layer (six machines + generators + recipes)** | **Designed; THE NEXT MILESTONE.** Substrate strategy chosen (§7.3) |
| Circuitry / control layer | Designed (this consolidation); not started |
| Heat/cooling (coolant-as-material) | Designed; not started |

### 0.4 Supersession map

- **Adjacency auto-flow transport** (early "cables optional" draft) -> **superseded** by fixed cached pipe networks (§6). Source: `2026-06-02-energy-io-plumbing-*` (partially superseded), `2026-06-03-transport-network-rework-plan.md` (the pivot).
- **`machines-and-power-design.md` §14 (analog redstone)** -> **superseded** by the digital CMOS circuitry design (§8): analog 0-15 + signal decay are rejected; energy model is per-switch, not per-tick trickle; NOT splits out as a 1-in inverter. Sources: `2026-06-08-tech-circuit-system-design.md` (logic/energy), `2026-06-08-wire-face-travel-design.md` (geometry/state).
- **`machines-and-power-design.md` §15 (electrified vanilla workbenches)** -> **merged** into the machine substrate (§7.3): we reuse Hytale's bench engine directly rather than cloning electric variants.
- **Per-field energy carry (`CC_StoredEnergy`/`MachineEnergyMetadata`)** -> **superseded** by engine-native `BlockHolder` full-state carry (§6.7).
- **`int` energy amounts** -> **superseded** by `long` (§5).

### 0.5 Consolidated decisions ledger

Each locked decision, one line, with its owning section. Open items are in §11.

**Pillars & matter**
- D1. Two transformation layers: chemistry (electrons/bonds, formula only) and nuclear (the nucleus, protons/neutrons). The Reactor bridges them. (§1)
- D2. Hard layer boundary: chemistry edits only `Formula`; only the nuclear layer alters protons/neutrons. (§2, §9)
- D3. Every substance has a `Phase` (solid/fluid/gas) that fixes its storage + transport. (§2)
- D4. State change is depth-on-a-few: only substances whose second state has a real job (coolant, dense storage) are dual-state; ~140 compounds stay single-state. (§2, §7.6)
- D5. Philosophy: limitations by default, automation if you earn it. Transport is a deliberate build step: no free adjacency transfer. (§1, §6)

**Data**
- D6. Substance types are a `sealed abstract Substance permits Element, Compound`; `Isotope` is standalone element metadata. Mutable beans, getters-only, Hytale `BuilderCodec`. (§3)
- D7. Data is PascalCase-keyed so the reference data *is* canonical Hytale asset format; serialized with Hytale codecs to unlock JSON schema generation. (§3)
- D8. The registry is backed by Hytale `AssetStore` (third-party override/hot-reload for free); consumers code against a backing-agnostic `SubstanceRegistry`; lookups return `Optional`. (§3)
- D9. Color is a baked, build-time file property (no runtime tint); every solid ships as a placeable color-tinted jar block; glow tier is derived from nuclear data. (§3.4)

**Energy**
- D10. Ship our own energy standard (`api.energy`); defer adopting an external one until one demonstrably wins (reopen at a version junction). (§5)
- D11. `long` for all energy amounts/capacities; pipe-tier throughput stays `int`. (§5)
- D12. Internal vs external transfer paths (rate-capped external, uncapped internal self-fill); per-buffer `maxReceive`/`maxExtract`; ratio helpers. (§5)
- D13. Lossless by default; fair-split push distribution (not greedy); tiers differ only in throughput + buffer contribution (no voltage tiers, no burn-your-machine). Power flows both ways. (§5)

**Transport**
- D14. Five channels via `PortChannel {POWER, FLUID, GAS, ITEM, NEUTRON}`; four pipe families map 1:1 to the first four, connecting only to their own kind via per-family FaceTags. (§6.1)
- D15. Fungible channels (energy/fluid/gas/neutron) share ONE virtual network buffer (capacity = Σ pipe capacities, throughput = min tier); items are discrete traveling stacks. (§6.2, §6.4)
- D16. Networks are cached and event-invalidated (place/break/unload), never per-tick rebuilt; per-tick cost is O(active networks). (§6.5)
- D17. Distribution is max-min fair-split with remainder redistribution (Mekanism `SplitInfo`). (§6.3)
- D18. Fluid/gas pipe networks are type-locked to one substance until fully drained (mirrors tanks); energy/neutron not locked. (§6.6)
- D19. Per-face `FlowState {NORMAL, PUSH, PULL, NONE}` on every pipe face, optional codec key on `PipeNode` (absent = all-NORMAL, no migration). Port offers, pipe face accepts. Auto-NONE between mismatched-substance pipes, dynamic, drain-remerge. (§6.3)
- D20. `CC_Wrench` configures faces (pipe `NORMAL->PUSH->PULL->NONE`; machine face cycles `(channel x input/output) -> closed`); bare ends pre-configurable; 2 PUSH/PULL faces per pipe budget. Pipes drop plain (no config carry). (§6.3)
- D21. OFFSETS face convention (`+X,-X,+Y,-Y,+Z,-Z` = E/W/U/D/N/S) is the single locked face-indexing standard everywhere. (§6.1)
- D22. Visuals: engine pattern system for the free all-NORMAL case; programmatic shape states (`setBlockInteractionState`) for any suppressed/added/tipped arm; generated composite tip models for push/pull arrows. Overlay-entity indicators are DEAD in 0.5.3 (client raycasts model entities). (§6.8)
- D23. Machines/tanks carry full state across break/place via engine-native `BlockHolder`; pipes do not. (§6.7)
- D24. Storage balancing (battery<->battery, tank<->tank) is a leftover-budget Phase 3, capacity-blind water-fill, convergent/churn-free. (§6.3)

**Machines**
- D25. Six machines: Synthesizer, Decomposer (= electrolysis/thermolysis; no separate Electrolyzer), Converter (chemistry); Irradiator, Cyclotron, Decay Vault (nuclear). The Reactor is a heat source under Generators (§7.8). (§7.1)
- D26. Two active nuclear moves cover the chart: Irradiator = neutron capture `(Z,A+1)` (neutron-rich), Cyclotron = proton capture `(Z+1,A+1)` (proton-rich, sole path; consumes H2 gas). β/α are passive decay (Decay Vault), never forced. Transmuter cut (it only paid power to skip a decay the Vault already gives). Decay chains terminate at lead. (§7.1, §7.2)
- D27. One unified recipe schema for all machines/generators; `energy` is SIGNED (exothermic returns power, endothermic costs it); recipes are derived from the dataset, only the Converter set is hand-curated. (§7.4)
- D28. Machines are single-item multi-cell blocks on Hytale's anchor + filler footprint model (not Mekanism assembly): the anchor holds the component/model/ports, filler cells are auto-claimed from the hitbox and redirect to the anchor. One port per footprint face (channel + direction); a pipe of the channel is always required, no port-to-port. Per-machine footprint + default ports are defined with each machine. (§7.5)
- D29. Machines hold small per-channel internal buffers (decouple production/consumption cadence + define run-dry), shown as GUI gauges. (§7.5)
- D30. No hand-held canisters and no in-GUI fill slot: movement is a pipe or carry-the-tank. Tanks are not stackable; three capacity-only tiers. (§7.5)
- D31. **Machines are built on Hytale's built-in bench engine** by compose-and-delegate, not from scratch and not by subclassing: our component holds a vanilla `ProcessingBenchBlock` + `EnergyBuffer`, ticked by our own system; vanilla never ticks our block. (§7.3)
- D32. **Fuel -> energy** via a no-fuel `ProcessingBench` config: vanilla `advanceProcessing` then runs without the fuel gate, and our tick gates on energy. (§7.3)
- D33. **Overclock = power fed:** throughput scales with energy input up to a cap (more power in, faster work). This is the machine-layer energy knob, distinct from circuitry's per-switch draw. (§7.3, §8.5)
- D34. Heat is a material, not a temperature, and the coolant loop + power generation are ONE system: hot processes (and hot machines) emit hot coolant; converters turn it back into power. No temperature gauge, no meltdown timer. (§7.6)
- D34b. Coolant is a `coolant` tag on fluids carrying `heat_capacity` / `condensation_temp` / `latent_heat` from the dataset; the machine is generic, data carries the difference. Energy in hot coolant = amount boiled. (§7.6)
- D34c. Converters split derivably by `condensation_temp` vs ambient (no flag): Turbine = self-condensing power for above-ambient coolants (v1 water); Phase Changer = refrigeration/liquefaction for below-ambient (cryo + dense storage); Thermoelectric = lossy no-plumbing for sources too cold to boil. Cogeneration; recovered <= consumed by construction. (§7.6)
- D35. Neutrons are a separate currency on their own pipe, fungible shared buffer with a hard length cap (no flux beyond range), not tankable, not type-locked. (§7.7)

**Generators (power)**
- D43. Power generation splits into direct generators (fuel/environment -> power, lossy) / heat sources (-> hot coolant) / converters (hot coolant -> power); the loop is mass-closed (only fuel consumed). v1 roster of 9; Fuel Cell struck. (§7.8)
- D44. The Reactor produces no power directly: fissile + coolant -> spent fuel + steam + neutron flux; the Turbine is the power export point. Authored surface = turbine efficiency + one MeV->game scalar; all else derives from isotope tables. Neutron flux must be load-bearing in v1 (breed Pu-239). (§7.8.4)
- D45. Spent fuel is the real radioactive fission-product mix and is RTG fuel (waste is a soft logistics puzzle in v1, not a damage hazard); reprocessing deferred. Geothermal is a heat source tiered Thermoelectric -> Turbine. (§7.8.3, §7.8.5)
- D46. Reactor-surplus sink = overclock (D33) -> batteries -> reactor throttle (network backpressure, no new system); ship bounded, oversizing is safe; unbounded scaling waits on a parallel-processing sink. (§7.8.6)
- D47. Eight themed auto-machines wrap the vanilla benches (Smelter, Cooker*, Outfitter*, Reclaimer, Forge, Alembic, Assembler, Cultivator; * = processing+crafting hybrid). Count is an identity choice because all crafting recipes share one `CraftingRecipe` model tagged by `BenchType`. Non-invasive; vanilla benches stay usable. (§7.10)

**Circuitry (control)**
- D36. Digital signals only (on/off); no analog 0-15; no signal decay. Analog exists only at the machine boundary, converted by the comparator. (§8.1)
- D37. CMOS energy model: a gate pays a small amount only when one of its *inputs* transitions (input-edge charging); idle/held logic is free. (§8.1)
- D38. One power feed per isolated circuit; tie-high = tap the powered rail (no dedicated constant-source block). (§8.1)
- D39. Component set: power feed, capacitor (dual-role energy/circuit), lever/button/pressure-plate, inverter (1->1 NOT), gate (2->1, type toggled on block), comparator (analog->digital), delay, oscillator, D-latch, lamp, machine I/O bridge, electrical wiring. (§8.2)
- D40. Wiring is face-attached (sculk-vein model), digital, no decay; colored insulated lanes share a face without mixing; reuses the pipe per-face state + render machinery (`FaceSkin[6]` extends `FlowState[6]`). (§8.3)
- D41. Circuitry is its own signal network (cached/event-invalidated like resource networks), domain-separate from energy transport but drawing from the same grid via the power feed. (§8.4)
- D42. Baseline automation needs no circuit: a powered, fed machine runs on its own. Circuit control is depth on demand. (§8.5)

---

## 1. Vision & pillars

A chemistry layer operates on electrons and bonds: it combines, breaks, and converts compounds, and can never change an element or isotope (it is cheaper, earlier-game). A nuclear layer operates on the nucleus: it changes protons or neutrons (transmutation, activation, decay, fission) and is high-energy, late-game, and irreversible by ordinary chemistry. The **Reactor** is the bridge: the sole source of free neutrons and the anchor of the power economy.

The governing philosophy is **limitations by default, automation if you earn it**. Connecting two blocks is something you plan and route through pipes (§6), never a free consequence of adjacency. This makes building infrastructure a deliberate, legible act.

---

## 2. Core data model (matter)

Every element/compound item carries three fields with strict ownership:

| Field | Meaning | Who may edit it |
|---|---|---|
| **Element identity** | proton count; equals the symbol; fixed | nuclear layer only |
| **Isotope** | neutron count -> stability & half-life | nuclear layer only |
| **Formula** | compounds only | chemistry layer only |

**Hard rule (D2):** the chemistry layer edits only the formula; the nuclear layer is the only thing that may alter protons or neutrons. This single rule keeps the two systems from colliding and makes "why can't I craft plutonium in a furnace" answer itself.

**Phase** (solid/fluid/gas) is assigned per substance from the dataset and determines which storage and transport it uses (§6). **State change** is depth-on-a-few (D4): only substances whose second state has a real job (coolant cold enough for a top-tier core, or dense liquefied storage) are registered as distinct phase-tagged forms, converted by the Phase Changer (§7.6).

---

## 3. Substance dataset, schema & registry

The data is the foundation everything else derives from: 118 elements, 673 isotopes, 153 compounds.

### 3.1 Type layer (`api.substance`)

- `sealed abstract class Substance permits Element, Compound`; `Isotope` is standalone (it is element metadata, not a substance: bulk properties like phase/color/density belong to the parent element's material, not a nuclide).
- Beans are mutable internally, getters-only externally (Hytale `BuilderCodec` needs a no-arg ctor + setters; records would force a deprecated path). Effectively immutable to consumers; package-private setters the codec drives.
- `Substance` shared fields -> JSON keys: `Name`, `Symbol`/`Formula`, `Phase`, `Color`, `Density`, `StandardAtomicWeight`/`MolarMass`.
- `Element`: atomicNumber, group, period, block (`PeriodicBlock` s/p/d/f), category (`ElementCategory`, 10 values), oxidation states, electronegativity, melting/boiling points, isotopeSymbols, value confidence.
- `Compound`: composition (`Map<symbol,count>`), compoundType (9 values), propertyFlags, toxicity, waterSolubility, isRadioactive.
- `Isotope`: parentSymbol/parentZ, massNumber, naturalAbundance, stability, halfLife(+seconds), decayModes (`List<DecayMode>`), dominantEmission, radiationVector, specificActivity, decayHeat, nuclearFlags (fissile/fertile/fusionable).
- Enums carry an explicit `jsonValue` mapped via a reusable `StringMappedCodec` (the engine's `EnumCodec` only does PascalCase and cannot read human-readable wire values). `Color` is a record mapped from a single hex string, exposing r/g/b for tinting.
- Codecs live in `api.substance` (package-mate to their beans); shared fields use the engine's parent-codec inheritance (`BuilderCodec.abstractBuilder`). Standalone headless decode via `codec.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY)`.
- **Coolant model dependency (§7.6, D34b):** the derivable heat loop needs a `coolant` fluid tag plus per-fluid `heat_capacity`, `latent_heat`, and `condensation_temp`. Boiling points already exist in the dataset; **`heat_capacity` and `latent_heat` must be confirmed present or added** (the one open data prerequisite for generators). [DECIDE/data]

### 3.2 Data keying

Data is re-keyed to PascalCase one-time so the reference JSON *is* canonical Hytale asset format (D7); values stay human-readable, units move from keys into doc comments. Validation: record counts + a content hash of values (keys excluded) unchanged pre/post. A 944-record full-dataset decode/round-trip test is the schema guard.

### 3.3 Registry (`api.registry`)

`SubstanceRegistry` is the backing-agnostic runtime access layer (consumers never see `AssetStore`):
- Lookups (all `Optional`): `element(symbol)`, `element(atomicNumber)`, `compound(formula)`, `isotope(symbol)`, `isotope(Z, A)` (the single nuclide-chart primitive).
- Collections: `elements()`, `compounds()`, `isotopes()`.
- Resolution (beans hold raw symbols, the registry resolves, dangling refs skip with a warning): `isotopesOf(Element)`, `constituentsOf(Compound)`, `daughtersOf(Isotope)`, `compoundByComposition(map)` (synthesizer reverse-lookup).
- Backed by Hytale `AssetStore` (third-party override + hot-reload for free); static accessor `Chemistry.substances()` set in `setup()`, nulled in `shutdown()`.
- **Machine alignment:** Irradiator -> `isotope(Z, A+1)`; Cyclotron -> `isotope(Z+1, A+1)`; Decay Vault -> `daughtersOf` walked one step at a time; Synthesizer -> `compoundByComposition`; Decomposer -> `constituentsOf`; Converter -> melt/boil on beans + plain lookup.

### 3.4 Visual assets (solids: color & glow)

- Every solid element/compound (~205) ships as a placeable model-block: the shared `Solid` jar with its liquid region recolored to the substance's `Color`. There is no runtime tint in Hytale, so item JSON + tinted texture + 64px icon are all generated at build time (`impl.assetgen.SubstanceAssetGenerator`, `./gradlew generateSolidSubstanceAssets`). Id = `Chem_Solid_{Element|Compound}_<Name>` (keyed on name, since polymers share a formula). Placed size `CustomModelScale = 0.45`.
- **Color** is being re-derived as a "game color" (real-world-grounded with a fantasy association pass): a single derivation table (one row per substance: id, tier/source, hex, rationale) applied by script, with quality gates (luminance band ~45-85%, saturation floor, minimum pairwise distance). Validated, not yet applied.
- **Glow** is a pure function of nuclear data (`GlowDeriver`), tier none/faint/strong/fierce: elements by their isotopes' stability (stable -> none; long-lived Th/U -> faint; hot Tc/Pm/Po/Rn/Ra/Ac -> strong; synthetic Z>=100 -> fierce); compounds by `IsRadioactive` + referenced isotope. Phosphorus is a curated non-nuclear faint-glow exception. Tier drives two render levers (liquid texture ramp + placed-jar `BlockType.Light`) and picks `Solid.blockymodel` vs `SolidGlow.blockymodel`.

---

## 4. Layered architecture

See §0.2 for the stack. The key architectural commitments:

- **api/impl split is governing:** `api` defines contracts and enums (`api.energy.EnergyHandler`, `api.io.{PortChannel, PortDirection, FlowState}`, `api.substance`, `api.registry`); `impl` decides values and gameplay. Volatile Hytale asset types stay behind `api.shim`. No concrete value leaks into `api`.
- **`PortChannel` is a transport channel, not a substance `Phase`** (power is not a phase): `{POWER, FLUID, GAS, ITEM, NEUTRON}`.
- **Block entities are ECS components on the `ChunkStore`**, attached via the block asset's `BlockType.BlockEntity.Components` map, registered through `getChunkStoreRegistry().registerComponent(Class, "Id", CODEC)`. Ticking is our own `EntityTickingSystem<ChunkStore>`. This is the same substrate vanilla benches use, which is what makes §7.3 possible.
- **Everything mutating blocks marshals through `World.execute(...)` on the WorldThread**; off-thread compute is fine, mutation is not.

---

## 5. Energy standard

We ship our own energy system (`api.energy`) and do not adopt an external Hytale energy API yet (D10): the published candidates (`dev.zkiller.energystorage`, the shailist Fabric-Transfer port in HyProTech) are third-party and unmaintained by us. We steal their proven techniques and keep the switch open; the reopen condition is a deliberate version-junction bump if one standard wins ecosystem adoption.

Locked properties:
- **`long`** for all amounts/capacities (big reactors/banks exceed the 2.1B `int` ceiling; HyProTech was bitten by this). Pipe-tier throughput stays `int`. Migration done.
- **Internal vs external paths:** `receiveEnergy`/`extractEnergy` clamp to per-call `maxReceive`/`maxExtract` for network transfer; `*Internal` variants bypass the cap for a block filling/draining its own buffer.
- **Per-buffer rate caps** on the handler (codec keys `MaxReceive`/`MaxExtract`), distinct from pipe-tier throughput; factories `withCapacityAndRates(...)` / `withCapacity(...)`.
- **Ratio/health helpers** `getFillRatio()`, `isFull()`, `isEmpty()` (consumed by §6.3 distribution + GUI gauges).
- **Codec validators + `.documentation(...)` on every field** (feeds schema generation).
- Lossless by default; fair-split push (§6.3); tiers differ only in throughput + buffer contribution; power flows both ways. The export points are the **converters** (Turbine / Thermoelectric, §7.8): the Reactor itself produces no power, only steam + flux. Decay generators / RTGs trickle back directly.
- **Oversupply has no dump:** surplus cascades overclock (D33) -> batteries -> provider throttle via network backpressure (§7.8.6).

`EnergyBuffer` (impl) implements `EnergyHandler`; the `long` migration + rate caps + ratio helpers are landed.

---

## 6. Transport layer  [DONE, in-game verified]

The five channels are the only way resources move (D5/D14). A **network** is a connected set of pipes of one channel plus the ports (machine/tank faces) touching them. Modeled on Mekanism; explicitly NOT on HyProTech's per-tick flood-fill rebuild.

### 6.1 Channels & families

`PortChannel {POWER, FLUID, GAS, ITEM, NEUTRON}`. Four pipe families map 1:1 to the first four (`CC_PowerCable`/`CC_FluidPipe`/`CC_GasPipe`/`CC_ItemPipe`; model families `Pipe`/`FluidPipe`/`GasTube`/`ItemPipe`), each connecting only to its own kind via per-family FaceTags (`CC_PowerFace`/`CC_FluidFace`/`CC_GasFace`/`CC_ItemFace`). Neutron is a later plan. The **OFFSETS convention** (`+X,-X,+Y,-Y,+Z,-Z` = E/W/U/D/N/S) is the single locked face index everywhere: PipeNode faces, ports, wrench clicked-face, endpoint collection, topology models, visual templates.

### 6.2 Fungible networks = one shared buffer

POWER/FLUID/GAS/NEUTRON are fungible: the network is a single virtual tank/battery with no per-pipe location. Pipes contribute **capacity** (network buffer = Σ pipe-segment capacities) and **throughput** (per-tier rate cap; network throughput = min member tier). This dissolves four problems at once: propagation lag (the buffer is everywhere; startup lag = time to fill total capacity), fair global distribution, multi-source balancing, and the aggregate gauge (stored/capacity *is* the readout). `PipeNode` carries `bufferShare` (`long`) + `resourceId`; buffer contents persist by redistributing each pipe's share back into its `PipeNode` on unload/save and re-pooling on load.

### 6.3 Distribution, flow-states & balancing

- **Fair-split (`FairSplitDistributor`, Mekanism `SplitInfo`):** share = amount / acceptorCount; any acceptor that can take less gets what it holds and is removed, its remainder added back; recompute until stable; split leftover evenly. Max-min fairness, order-independent, no starvation. Providers fill the buffer first; one pass gives automatic multi-source balancing. `NetworkTransfer.distribute` simulates then commits at every boundary (lossless); a full network applies backpressure (providers stop, nothing voids).
- **Per-face `FlowState {NORMAL, PUSH, PULL, NONE}` (D19):** an optional `PipeNode` codec key (absent = all-NORMAL, no migration). Filter semantics: the port OFFERS (its `PortDirection`), the touching pipe face ACCEPTS: `NORMAL` honors the port's direction; `PUSH` = acceptor-half only (network pushes in); `PULL` = provider-half only (network pulls out); `NONE` = invisible. **Auto-NONE:** two adjacent same-channel pipes locked to different substances do not connect (NORMAL dynamically resolves to no-connection); not stored, re-evaluated on every topology event, so draining one line lets the next event remerge (tick write-back detects a lock clearing resourceId non-null->null and invalidates the boundary).
- **Wrench (`CC_Wrench`, D20):** cycles a pipe face `NORMAL->PUSH->PULL->NONE` (sneak reverses where exposed), a machine face through `(channel x input/output) -> closed`. Bare ends (any face not touching a pipe) get the full ring and persist pre-config; pipe<->pipe faces cycle `NORMAL<->NONE` only. Budget of 2 PUSH/PULL faces per pipe. Wrench writes are topology events (invalidate under the H8 snapshot guard, recompute visuals). Pipes drop plain (no config carry). Clicked face read via `InteractionContext` -> `InteractionSyncData.blockFace`; `UseLatestTarget: true` is mandatory in the item JSON or the face reads `None`.
- **Storage balancing (D24):** Phase 3 of distribution, leftover budget only, capacity-blind water-fill (every storage converges to the same absolute level L; capacity only clamps). Convergent and churn-free by construction (`WaterFill` + `StorageEndpoint`; a `StorageEndpoint` pairs in only with full two-way access).

### 6.4 Item networks = discrete pathfinding stacks  [DONE]

Items have identity (a specific stack to a specific place), so they are NOT a shared buffer: `NetworkTransfer.distribute` skips ITEM networks. An extracted stack becomes a `TravelingStack` routed to a destination chosen at insertion time (nearest-first; round-robin/priority reserved), physically occupying pipe segments as it travels. Mekanism rules: PULL extracts only after a valid destination is confirmed (no extraction without a home), nearest-first routing, a re-route -> return-toward-origin -> pop-out-as-ground-drop ladder (never voids, never idles). Driven by `ItemTransferSystem` (phase 1 advance/arrive/re-route, phase 2 PULL extractions). Traveling stacks ride an optional `PipeNode` codec key `InTransit`; the H8 wipe-snapshot widens to pipes carrying stacks. v1 endpoints = vanilla containers (resolved via the engine `ItemContainerBlock` component, present on vanilla chests, included by component presence, no allow-list); machine ITEM ports join at the machine milestone. The insert/extract seam carries metadata (BSON), so damaged/enchanted/BlockHolder items round-trip.

### 6.5 Network lifecycle & caching (the performance core)

Networks are persistent cached objects, built once by BFS over connected same-channel pipes, keyed by lexicographic-min anchor (`NetworkManager`). They are invalidated only on structural change: `PlaceBlockEvent` / `BreakBlockEvent` / `ChunkUnloadEvent`, caught via `EntityEventSystem` (split-on-break / merge-on-place, currently drop-and-rebuild). Per-tick cost is O(active networks) doing distribution, not O(pipes) doing discovery: this is the whole reason we avoid HyProTech's tick-timeouts and move-budgets. `NetworkTickSystem` and `ItemTransferSystem` dedup so each cached network ticks once. A dirty-queue + dedup-set + time-budget batcher (borrowed from the Redstone jar) is a rebuild-storm backstop, not the primary mechanism. Chunk-unload eviction is currently deferred (0.5.3 dispatches `ChunkUnloadEvent` from parallel workers; an `EntityEventSystem` handler on it kills the WorldThread): pipes reaching into unloaded chunks are treated as a network boundary.

### 6.6 Type-lock

A fluid/gas tank and a fluid/gas pipe network are locked to one substance id while non-empty and must be fully drained before another substance enters (Mekanism behavior; forces dedicated labeled runs). Energy and neutron networks are not locked (single fungible currency). A machine output targeting a full or wrong-substance tank/network stalls (stages product in its internal buffer), never voids.

### 6.7 Carry & persistence

Machines and tanks relocate with full state (energy, resource buffers, type-locks, work progress) via the engine-native `BlockHolder` item metadata (D23): restore is zero-mod-code (`BlockPlaceUtils.onPlaceBlockSuccess` decodes the `Holder<ChunkStore>`), capture is ours (`CarryBreakEventSystem` encodes immediately before `setBlock(EMPTY)`, keeps the H8b connected-block reshape + pipe re-snapshot, then spawns the stamped drop; encode failure degrades to a plain drop). Empty blocks stay fully vanilla. Pipes deliberately drop plain.

### 6.8 Visuals

The engine's connected-block pattern system handles the free all-NORMAL case (`BlockType.ConnectedBlockRuleSet` matching neighbor FaceTags; 26 topology shapes per family). Anything the pattern system cannot see (suppressed/added/tipped arms, flow state, energized twins) gets programmatic shape states via `World.setBlockInteractionState` (the H8 path; the swap SETS rotation, settings `198`, so added/tipped arms point at their endpoint; a position-tracking pass resets an overridden pipe to plain when undisturbed). A suppressed arm renders the lesser topology (tee->straight, straight->end); a container arm the engine refuses to weld is added (effective mask > physical mask). PUSH/PULL arms render generated composite tip models (`scripts/gen_pipe_tips.py`, ~604 states/family from authored tip geometry), bounded by the 2-PUSH/PULL-face budget. ITEM ships the full tip set; Fluid/Gas/Power tips are deferred to a pipe-art redo (the families' source models diverge in encoding: see `memory` + the deferred note). The overlay-entity approach to per-face indicators is DEAD in 0.5.3: the client raycasts any networked model entity, swallowing attack/Interact even with no hitbox.

### 6.9 Neutron channel (designed, not started)

Behaves like an energy network (fungible shared buffer) with one hard extra rule: a maximum network size N. Hard refusal: the network will not extend past N pipe blocks, and no flux exists beyond range (no per-hop falloff, just a size check at build/extend). Not type-locked. The neutron pipe *is* the old "neutron guide." Sources: the Reactor (bulk) and rare spontaneous-fission isotopes in a Decay Vault (trickle). N is `[TUNE]`.

---

## 7. Machine layer  [NEXT MILESTONE]

### 7.1 The machines (chemistry & nuclear)

| # | Machine | Layer | Function | Consumes |
|---|---|---|---|---|
| 1 | Synthesizer | Chemistry | elements -> compound (combine) | Power |
| 2 | Decomposer | Chemistry | compound -> elements (break). This IS electrolysis/thermolysis (e.g. H2O -> H2 + O2); no separate Electrolyzer | Power |
| 3 | Converter | Chemistry | compound A -> compound B (curated reactions; byproduct electrolysis like chlor-alkali lives here) | Power (signed) |
| 4 | Irradiator | Nuclear | neutron capture: (Z, A) -> (Z, A+1) (activation; the neutron-rich side) | Power + neutrons |
| 5 | Cyclotron | Nuclear | proton capture: (Z, A) -> (Z+1, A+1) (the proton-rich side) | Power + H2 gas |
| 6 | Decay Vault | Nuclear | passive decay harvest + radiation containment | passive |

The **Reactor** is a nuclear *heat source*, not a transformer; it lives with the **Generators** (§7.8).

**Full nuclide-chart coverage, no redundant machine (D26).** The two *active* machines move on the two independent chart axes: the **Irradiator** adds neutrons (rightward, neutron-rich), the **Cyclotron** adds protons at constant neutron number (upward, proton-rich, consuming hydrogen gas as the H+ source: no new resource or schema). The **Decay Vault** harvests every decay (β- climbs Z, β+/EC drops Z, α descends Z-2). Together they reach both sides of stability, and the Cyclotron is the *only* path to proton-made isotopes (C-11, F-18, O-15 class) that neutron activation can never reach. The old **Transmuter was cut**: forcing a β/α only paid power to skip a decay the Decay Vault already delivers from the same isotope with the same table data: a shortcut with no new capability. Cyclotron endgame gating is materials/cost (the real Coulomb-barrier price), not a machine tier.

### 7.2 Transmutation rules

The two *active* nuclear moves are derived from the dataset: **neutron capture** (+1n -> `(Z, A+1)`, Irradiator) and **proton capture** (+1p -> `(Z+1, A+1)`, Cyclotron). Everything else is **decay**, which is passive and harvested by the Decay Vault (β- -> element +1, β+/EC -> element -1, α -> element -2); decay is never a forced machine operation. Decay chains terminate at stable lead: a built-in dead end. **Breeding** falls out of these for free: Reactor neutron flux -> Irradiator (U-238 + n -> U-239) -> decay (U-239 ->β Np-239 ->β Pu-239), so neutron flux is load-bearing using only existing machines (§7.8.4).

### 7.3 Substrate: built on Hytale's bench engine (compose + delegate)

Rather than build a machine work-loop from scratch, machines reuse Hytale's built-in **processing-bench engine**, which already implements recipe matching, progress, run-dry, output handling, ON/OFF visual states, sounds, persistence, and a GUI window. Decompile-verified facts (`builtin.crafting.*`):

- `ProcessingBenchBlock` is an ECS `Component<ChunkStore>` (same family as our components) with public `getInput/Fuel/OutputContainer()` (`ItemContainer`, public add/remove/get) and public `advanceProcessing`, `checkForRecipeUpdate`, `setActive`, `initializeBenchConfig`, `setupSlots`. `BenchSystems.ProcessingBenchTick` ticks every processing bench every server tick with no player or window needed.
- Crafting benches differ (player-driven `CraftingManager.craftItem`, firing the cancellable `CraftRecipeEvent`), but `CraftingManager.getInputMaterials`/`getOutputItemStacks` are public static, so autonomous crafting is reimplementable without a player.

**The pattern (D31): compose, not `extends`.** ECS systems tick by exact `ComponentType`, and the parent's private fields + own codec make subclassing awkward, so a subclass would not inherit the tick anyway. Instead, our `CCMachineState` component *holds* a `ProcessingBenchBlock` + an `EnergyBuffer`, registered under OUR component type and driven by OUR tick. Vanilla never sees our block (non-invasive by construction); we reuse the vanilla codec for the held instance and call its public methods through one isolating `VanillaBenchBridge` (`compileOnly` against the server jar; a devServer smoke test contains the risk that `builtin.*` internals shift between versions).

**Fuel -> energy (D32):** configure each machine's `ProcessingBench` with NO fuel slots. `advanceProcessing` then skips the active/fuel gate (`hasFuelSlots = getFuel() != null`) and never consumes fuel; it advances on `dt` + input availability + output room alone. Our tick gates on `EnergyBuffer` and drains energy proportional to work, honoring the recipe's signed `energy` (exothermic credits, endothermic debits).

**Overclock = power fed (D33):** because the tick already scales `dt` and gates on energy, "more power in -> more dt -> faster work, up to a cap" drops straight in. This is the machine-layer energy knob and the energy sink in one legible mechanic, distinct from circuitry's per-switch draw (§8.5).

**Autonomous crafting:** crafting machines add our own tick that reads inputs + the selected recipe and performs the craft via the public recipe helpers + `ItemContainer`, charging energy first: a true auto-crafter, no player.

**Recipe-script item:** the machine component stores a selected `recipeId` (precedent: `ProcessingBenchBlock.recipeId`); a "recipe card" item the player inserts sets it, resolved via `CraftingPlugin.getBenchRecipes(...)`. Processing machines may auto-pick from inputs instead.

This substrate powers every machine and generator (each is "a no-fuel `ProcessingBench` with custom recipes, energy-gated"), and inherits the bench slot/filter/progress/visual/sound/GUI machinery, closing the old "machine work-state + GUI" gap. Side effects to honor: `advanceProcessing` sets `"Processing"`/`"ProcessCompleted"`/`"default"` block states (our machine `State.Definitions` must define them) and requires the machine asset to carry a valid `ProcessingBench` config.

### 7.4 Recipe schema & generation

One unified JSON schema for every machine and generator:

```json
{
  "machine": "synthesizer",
  "inputs":  [ { "id": "element:hydrogen", "phase": "gas",   "amount": 2 } ],
  "outputs": [ { "id": "compound:water",   "phase": "fluid", "amount": 2 } ],
  "energy": -50,        // SIGNED: negative = exothermic (returns power), positive = endothermic (costs)
  "neutrons": 0,        // consumed from the neutron buffer (nuclear only)
  "operation": null,    // nuclear only: "neutron_capture" (Irradiator) | "proton_capture" (Cyclotron)
  "duration": 40
}
```

Derive, don't hand-author: Decay Vault = zero recipes (reads decay mode/daughter/half-life from the isotope dataset); Irradiator/Cyclotron = derived (neutron/proton capture via `isotope(Z, A+1)` / `isotope(Z+1, A+1)`); Reactor = derived (fission products + energy + flux from data, §7.8.4); Synthesizer/Decomposer = auto-generated by balancing each compound's formula (both directions for free, including electrolysis like H2O <-> H2 + O2). Only the **Converter** is curated (reaction chemistry is not derivable from formulas: ship a modest hand-authored reaction set; byproduct electrolysis like chlor-alkali lives here). Signed energy turns real reaction enthalpies into the economy: exothermic syntheses generate power, endothermic ones cost it, which is what makes the chemistry side interesting rather than a flat sink.

### 7.5 Footprint, ports, buffers, storage

**Multi-cell footprint: Hytale's anchor + filler model, not Mekanism assembly (D28).** A machine is ONE block item that occupies a multi-cell footprint, mimicking how Hytale's own benches work (the Furnace is a single item spanning ~2x2). There is no structure to assemble. Decompile-verified mechanism (`FillerBlockUtil`, `WorldChunk`, `ConnectedBlocksUtil`, `SimpleBlockInteraction`):
- Placement puts the block at an **anchor cell** (`filler == 0`) which alone carries the BlockEntity components (our `CCMachineState` = held `ProcessingBenchBlock` + `EnergyBuffer` + `PortConfig`), the model (authored to span the footprint), and the single tick/GUI/`BlockHolder` target.
- The engine auto-claims **filler cells** across the block's hitbox bounding box (`FillerBlockUtil.forEachFillerBlock`, on place; cleared on break; rotation-aware via `RotatedVariantBoxes`; placement fails on obstruction via `testFillerBlocks`). Each filler carries the machine's block id plus a packed 5-bits-per-axis relative offset back to the anchor.
- **Resolve a filler to its anchor** anywhere with the engine's one-liner: `anchor = pos - (unpackX, unpackY, unpackZ)(getFiller(pos))`.
- **What works for free:** interaction/break/collision/placement are filler-aware natively (`SimpleBlockInteraction` resolves the clicked cell to the anchor before calling `interactWithBlock`, line 83 -> 124), so the GUI and wrench operate from any footprint cell; and a pipe adjacent to any footprint face auto-stubs toward the machine, because filler cells carry the machine id + its FaceTags and the connected-block matcher reads neighbor cell ids directly.
- **The only new code:** `BlockModule.getBlockEntity` returns null on a filler, so our **network endpoint collection and the wrench apply the filler->anchor one-liner** before reading the anchor's component. FaceTags are uniform across a machine's cells (they cannot differ per footprint cell), so they only mean "a pipe of this channel may stub here": the real channel/direction filtering lives in our `PortConfig`, keyed by **(footprint-cell-offset, face)**, resolved on the anchor. The wrench reads the raw clicked cell + face from the `InteractionContext` to target the right port even though `interactWithBlock` receives the anchor.
- Each machine's **footprint size and default port layout are defined in that machine's own design** (per machine), not here.

- **Ports (D28):** the footprint exposes many outer faces, so configuration stays one port per face, each a `channel` + `PortDirection {input, output, both, closed}`. `BOTH` is the storage two-way direction. A port connects only when a pipe of its channel sits on that face (no port-to-port; a pipe is always required). Configured by wrench or the machine GUI; sensible defaults most players never touch.
- **Internal buffers (D29):** small per-channel buffers (a few operations' worth) shown as GUI gauges, topped up between cycles. Their job is to decouple production cadence from consumption cadence and define run-dry behavior, not to hide latency (intra-network transfer is effectively instantaneous).
- **Tanks & storage (D30):** one substance per tank, locked until emptied; three tiers (Basic/Advanced/Elite) affecting capacity only; not stackable in any tier; relocate with contents (carry the tank). No hand-held canisters and no in-GUI fill/empty slot: the only movement methods are a pipe network or carrying the whole tank. One mental model: tanks, in sizes.

### 7.6 Heat, coolant & power conversion (one system)

Heat is a material, not a temperature (D34): no temperature number, no heat-pipe network, no meltdown gauge. The coolant loop and "heat is a material" are **one system**, and it is also the backbone of power generation (§7.8): anything that runs hot emits hot coolant, and the converters turn hot coolant back into power.

- **Coolant is a tag, not "water" (D34b).** A `coolant` tag is applied to fluids; each tagged fluid carries `heat_capacity` and `condensation_temp` (and `latent_heat`) read from the dataset. The machine stays generic; the data carries the difference. v1 = water<->steam; later sodium, liquid helium (higher heat capacity = more energy per cycle), with no new machine logic. A hot process consumes liquid coolant and returns hot gas coolant: the phase change *is* the heat exchange. Intensity without a gauge = how much coolant is boiled per cycle. **Energy carried by hot coolant = the amount boiled** (heat -> steam quantity), so low-grade heat boils little or nothing and recovers little.
- **Two converters, split derivably by `condensation_temp` vs ambient (D34c), not by hand:**
  - **Turbine** condenses steam-type coolants (condensation point above ambient) while extracting power: it is its own condenser, and steam gives up its heat for free. The v1 water loop. A source hotter than the coolant's `condensation_temp` can boil it -> Turbine (efficient).
  - **Phase Changer** is refrigeration/liquefaction ONLY: spend power to make cryogenic liquids (He, N2) for the fusion-tier loop and the dense-storage perk. Needed only for coolants whose condensation point is *below* ambient. Steam never touches the Phase Changer (this kills the old "spent coolant returns to the Phase Changer" ambiguity).
  - **Thermoelectric** is the lazy converter: heat -> power with no plumbing (Seebeck; the same principle the RTG uses), a lower ceiling than the Turbine, and it works on any gradient including sources too cold to boil coolant. So `condensation_temp` vs source temperature derives BOTH which converter closes a loop AND which converter is even available: above boiling -> Turbine; below -> Thermoelectric only.
- **Cogeneration:** hot machines feed the same converters. A machine running hot emits hot coolant -> route it to a Turbine to recover that energy; cooling and power generation become one loop. Two invariants, both by construction: (1) energy in hot coolant scales with how hot the source ran (preserving the turbine-vs-thermoelectric choice), and (2) recovered <= consumed always (every stage < 100%, enforced by signed-energy discipline per stage), so waste-heat recovery is never a perpetual loop.
- **Dense-storage perk:** a liquefied gas occupies a fraction of the volume; tank capacity multiplier = the substance's gas-to-liquid density ratio. Liquefy (Phase Changer) to store dense, regasify to use. Initial dual-state roster: coolants helium/nitrogen/sodium/water; dense-storage gases oxygen/hydrogen.

### 7.7 Neutron economy

See §6.9 for the pipe. Neutrons are a separate currency used only by nuclear machines, not tankable, on their own length-capped network. The Reactor produces flux in bulk (§7.8.4); spontaneous-fission isotopes in a Decay Vault trickle it at high tier. The Irradiator debits the neutron buffer in our tick before `advanceProcessing`. (The Cyclotron uses no neutrons: its currency is hydrogen gas.)

### 7.8 Generators & the heat -> power loop

Power generation splits into three roles; conflating them is the mistake this design avoids. **Direct generators** turn fuel or environment straight into network power (self-contained, deliberately lossy, no plumbing; early/mid game). **Heat sources** produce hot coolant, not power. **Converters** turn hot coolant back into power and condense it, closing the loop (§7.6). The Reactor, Boiler, and a hot RTG are therefore not independent generators: they are heat sources sharing the one converter set. The loop is mass-closed (the Turbine returns all coolant as liquid; only fuel is consumed).

#### 7.8.1 v1 roster

| Generator | Role | Consumes | Produces | Derives from |
|---|---|---|---|---|
| Combustion Generator | direct | solid combustible (item) | power | enthalpy of combustion |
| Fluid Combustion Generator | direct | combustible gas/liquid (payload) | power | enthalpy of combustion |
| Solar Panel | environmental direct | daylight | power | time of day / weather / altitude |
| Geothermal Generator | environmental heat source | lava adjacency | heat | siting (§7.8.3) |
| Boiler | heat source | any combustible | steam | enthalpy of combustion |
| Fission Reactor | heat source | fissile fuel (item) + coolant | steam + neutron flux + spent fuel | isotope tables (§7.8.4) |
| Decay Generator / RTG | direct (passive) | decaying isotope (item) | trickle power | decay energy from isotope tables |
| Turbine | converter | hot coolant (payload) | power + liquid coolant | one authored efficiency constant |
| Thermoelectric Generator | converter | heat (no plumbing) | power (lossy) | one authored efficiency constant |

**Combustion vs Boiler are not redundant:** all three combustion paths share the `combustible` tag + enthalpy derivation; the combustion generators are the direct, lossy path (fuel -> power), the Boiler is the efficient path (fuel -> heat -> steam -> turbine). For any fuel there is a real choice: burn it for convenience or route it through the loop for efficiency. **Fuel Cell: struck** (it reads as a generator regardless of intent; its storage role is already covered by the surplus cascade §7.8.6; and H2 provenance is uniquely exploit-prone in a chemistry mod where H2 is a byproduct of dozens of reactions; batteries beat its ~56% round-trip).

#### 7.8.2 Deferred

Wind turbine + water wheel (animation complexity); **Fusion Reactor** (structurally the same machine as fission, so it must earn its tier with a *decision* not a digit: a second cryogenic liquid-helium loop running as standing overhead AND a bred light-nuclei fuel chain (deuterium from heavy water, tritium via `Li-6 + n -> T + He-4`); if it would only be "fission with bigger numbers" it does not ship); spent-fuel reprocessing; the cryogenic loop (ships with fusion); hard/active-irradiation waste (waits on the radiation-damage system).

#### 7.8.3 Geothermal tiering (one source, two tiers)

Geothermal is a fuel-free heat source (lava adjacency), one tier above Combustion: it escapes the fuel treadmill, bounded by site lock (must reach lava) + a low ceiling. **Low tier:** bolt a **Thermoelectric Generator** onto the hot block (no plumbing, no loop) -> the one-step-above-combustion entry. **Upgrade tier:** once the coolant loop exists, re-route the same lava heat into a **Turbine** for the efficient version: same source, no new machine, both converters already exist. So geothermal is the low-stakes on-ramp that teaches the loop. Overall progression: **Solar (no plumbing, direct) -> Geothermal + Thermoelectric (fuel-free) -> Geothermal + Turbine (learn the loop) -> Reactor (the loop with consequences).**

#### 7.8.4 Fission Reactor schema

The reactor *is a Boiler* structurally (heat in, steam out) with two differences: it runs far hotter, and it emits **neutron flux** (a transmutation beam a boiler never produces). Mental model: a super-powerful boiler that also emits a transmutation beam and leaves radioactive ash. No new schema fields:

```
Reactor:  fissile fuel (item) + coolant.liquid (payload)
       -> spent fuel (item)        [WASTE, solid item-world]
        + coolant.gas (payload)     -> feeds Turbine, closes the loop
        + neutron flux (payload)    -> transmutation beam (breeding, §7.2)
Turbine:  coolant.gas -> power + coolant.liquid   (returns all mass)
```

Three output streams each land in a domain that already exists: **steam** -> Turbine -> power; **neutron flux** -> transmutation/breeding (`U-238 -> Pu-239`, `Th-232 -> U-233`); **spent fuel** -> waste (§7.8.5). Derivations read from the isotope tables: fission energy per isotope -> heat -> max coolant boiled per cycle; neutron yield per fission -> flux rate. So the reactor scales across all fissile isotopes for free. The **only authored values** are the Turbine efficiency constant and one global MeV -> game-unit scalar. Power scales on three real decisions, not one slider: fuel choice/enrichment (hotter fuel boils more), coolant choice (higher heat capacity carries more), and circulating coolant inventory (the throughput ceiling): matching reactor heat <-> turbine capacity <-> coolant volume is the intended engineering puzzle. **v1 requirement:** neutron flux must be load-bearing from day one (minimum: you must breed Pu-239 to reach the next fuel tier), so the reactor never reads as a reskinned boiler.

#### 7.8.5 Waste model (soft, v1)

Spent fuel occupies the item-world output slot; it is *not* a separate authored "waste" substance but the real fission-product mix, which the isotope tables already know is radioactive (radioactivity = the existing `intensity` value, no new data). **Spent fuel is RTG fuel:** the Decay Generator eats decaying isotopes, so waste is a trickle-power source, not a dead end (the thing you wanted to discard powers an RTG, making the RTG matter in the endgame). Reprocessing (recover unspent fissile material) is deferred but the data is present: it becomes a neutron-flux or chemistry sink later (the Irradiator/Cyclotron are the natural path). v1 waste posture is **soft**: a logistics/storage puzzle (store it or feed the RTG), not an active damage hazard; penetrating-vs-contact radiation + active irradiation wait for the radiation-damage system.

#### 7.8.6 Reactor-surplus sink (the throttle cascade)

Excess reactor power has no sanctioned dump; it cascades: **overclock (D33)** spends surplus by running the base faster (up to caps) -> **batteries** time-shift the remainder -> when both are maxed, the **reactor throttles** (it stops extracting; unused capacity = unburned fuel). The throttle is the dump, and it is the same network-backpressure mechanic as a full power network stopping a provider (§6.3, rule 7): no new system. So reactor usefulness plateaus by design and oversizing is safe (burst headroom that throttles away gracefully): comfortably exceed your demand. Unbounded reactor scaling would need a parallel-processing sink (deferred); v1 ships bounded.

Utility blocks that are not generators live elsewhere: the **Phase Changer** (refrigeration) in §7.6, **tanks** in §7.5, the **Neutron pipe** in §6.9.

### 7.9 Cross-cutting machine rules

Type-lock stall (never void); run-dry freeze/resume (progress retained, deliberately unlike the furnace which resets); layer boundary enforced at the data model; decay always-on (an unstable item's half-life ticks even in inventory, use-it-or-lose-it; the Decay Vault controls/harvests, it is not a prerequisite for decay); radiation & containment (each decay tick emits radiation; a vault absorbs up to `[TUNE]`/tick, excess leaks into the world and triggers the existing radiation hazard; a breached vault dumps its accumulation at once); decay yields matter (daughter element primary; alpha emitters also yield helium gas; spontaneous-fission isotopes yield free neutrons); network backpressure; coolant as a recipe ingredient (runs out -> standard run-dry).

### 7.10 Automated vanilla benches (D47)

The §7.3 substrate's most direct use is wrapping Hytale's own benches as energy-powered, pipe-fed, automatable machines. Vanilla classifies benches by a `Bench.Type`: **Processing** (autonomous, timed, `Fuel`/`Input` slots, runs on `ProcessingBenchBlock`: Furnace, Campfire, Salvage, Tannery) vs **Crafting / DiagramCrafting / StructuralCrafting** (player-driven, instant, all sharing ONE `CraftingRecipe` model tagged with a `BenchRequirement(BenchType)`; the sub-types differ only in UI/slot layout). Because every crafting recipe is the same type, the auto-machine COUNT is an identity choice, not a constraint: we group the vanilla benches into eight themed machines, each accepting recipes whose bench requirement falls in its cluster.

| Machine | Wraps | Kind |
|---|---|---|
| **Smelter** | Furnace | processing |
| **Cooker** | Campfire + Cooking | hybrid (processing + crafting) |
| **Outfitter** | Tannery + Loom | hybrid (processing + crafting) |
| **Reclaimer** | Salvage | processing |
| **Forge** | Weapon, Armour, Armory(diagram) | crafting |
| **Alembic** | Alchemy, Arcane | crafting |
| **Assembler** | Workbench, Furniture, Builders(structural) | crafting |
| **Cultivator** | Farming, Trough | crafting |

**Cooker** and **Outfitter** are **hybrids**: their `CCMachineState` holds a `ProcessingBenchBlock` for the processing half (Campfire raw-cook / Tannery hide-tan, timed) AND drives the crafting half (Cooking dishes / Loom cloth) via the public `CraftingRecipe` helpers (§7.3). Pure-processing machines (Smelter, Reclaimer) need only the held bench; pure-crafting machines (Forge, Alembic, Assembler, Cultivator) need only the autonomous-craft path. All eight are energy-gated and overclock by power (D32/D33), and none modify vanilla benches: the originals remain placeable and usable by hand. Per-machine footprint, default ports, and which recipes surface follow each machine's own asset pass (§7.5). (Vanilla **Lumbermill** and **Memories** benches are not in the roster: Lumbermill's config was not pinned during classification and both are left vanilla for now; either can be added later.)

---

## 8. Control layer : circuitry

A redstone-equivalent logic layer, re-themed as electrical engineering, that gates and reads machines. It runs in parallel to the energy pipe network and is kept domain-separate by design (D41), but draws from the same grid. It has two halves: the **logic/energy semantics** (this section) and the **wire geometry/state contract** (§8.3). It supersedes the old analog §14 (D36-D40).

### 8.1 Governing standards

- **Digital signal (D36):** wires carry on/off only. Analog values exist only at the machine boundary, converted to digital by the comparator. No analog 0-15 strength model (digital is what makes the energy model honest, and it rehabilitates the comparator as a real threshold device).
- **No signal decay:** wires do not lose their logic level over distance (decay is a Minecraft quirk, not EE-real; removing it also removes the repeater-as-booster role).
- **CMOS energy model (D37):** a gate draws a small amount of energy only when it switches, charged on **input-edge** (it pays when one of its inputs transitions, regardless of whether its output changes: this matches CMOS, where you charge the input transistor gates either way, and cannot be gamed by arranging stable outputs). Static/held inputs draw nothing. Physical basis: CMOS dynamic power P proportional to C*V^2*f, so idle logic is effectively free and a busy automation factory is a real (small) load.
- **Single power source per circuit (D38):** one connected circuit needs one power feed; energy flows through the shared rail to wherever switching happens (no per-gate hookup).
- **Tie-high = tap the rail:** a constant logic-1 input is just a wire from the powered rail (tie-low is ground/unconnected). No dedicated constant-source block: a tie-high input never transitions, so under input-edge charging it draws nothing.

### 8.2 Component set

- **Power:** Power feed (energy grid -> circuit rail, one per isolated circuit); Energy storage block (on the energy pipe network, with a charge slot to recharge spent batteries); Battery item (portable finite charge for off-grid circuits; energy is not matter, so an energy-carrying solid item is fine under the matter-state split); **Capacitor** (one component, role follows the network: energy domain = fast-charge/discharge buffer; circuit domain = rail decoupling + edge/pulse-forming; edge detection is its non-redundant logic job).
- **Inputs:** Lever (toggle/SPST), Button (momentary), Pressure plate (entity sensor).
- **Logic:** Inverter (1-in/1-out NOT, its own block: the cheapest, most-used element, the "redstone torch" of the system); Gate block (2-in/1-out, type toggled on the block among AND/OR/XOR/NAND/NOR/XNOR, no chips; orientation defines input vs output faces; 3+ fan-in by chaining); Comparator (reads a machine's analog level against a dialed-in setpoint, outputs clean on/off: confines analog to the sensor boundary).
- **Timing & memory:** Delay (one-way adjustable lag, the re-themed repeater minus the boost); Oscillator (free-running clock); D latch (one stored bit, data + clock, no illegal-state footgun; all other sequential logic builds from it).
- **Output:** Lamp (indicator/load); **Machine I/O bridge** (the link between circuit and machine: reads machine state out as analog into a comparator, writes digital control in: run/halt, valve, overclock). The bridge is the circuit layer's reason to exist in an automation mod, and it ties directly into §7.3 (read buffer fill ratios; write the enable line / overclock target).
- **Wiring:** Electrical wiring, face-attached, digital, no decay; colored insulated lanes let paths share a block without mixing.

Rejected (reasoning preserved): analog 0-15; signal decay; loadable gate chips (type-on-block instead); a constant logic-high "electric/redstone block" (free forever-emitting source breaks the per-switch economy; tie-high taps the paid rail instead); energy-conduit and wire-conduit blocks (pipes and face-attached wiring already cover them); capacitor duplication (one component, role follows the network). Pistons are deferred.

### 8.3 Wiring : face-travel geometry & state

The physical wire reuses the pipe per-face machinery wholesale. Capability finding: Hytale supports thin per-face geometry on all six faces (the pipe tip "collars" already prove it); wires are blocks, never entities (the overlay-entity approach is DEAD, §6.8).

- **Occupancy (sculk-vein / glow-lichen model):** a wire is one `DrawType: Model` block per cell, rendered as thin skins hugging host faces, logically dependent on a supporting surface (pops when the host is removed).
- **Multi-face per cell:** one wire block stores a 6-face presence set (canonical OFFSETS order); placing dust on a second face sets another bit on the existing block, not a new block.
- **Per-face arm descriptor:** each present face has 4 arms (toward its 4 edges), each `{none, coplanar, outside-wrap, inside-wrap}`; the face shape (dot/end/line/elbow/T/cross) is emergent from arm count. Topology auto-derives from neighbors (MC-style, no player config): coplanar straight run, outside-corner wrap (convex edge, dust climbing down a side), inside-corner wrap (concave edge, floor dust turning up a wall).
- **Colored lanes (Project Red insulated-wire idiom, D40):** up to C colored wires share a face as parallel lanes; same color connects, different colors cross without a junction. Visual ceiling ~3 colors for readability; the connectivity logic is color-count-agnostic. Lane assignment is deterministic by color so lanes line up cell-to-cell.
- **State model:** where `PipeNode` carries `FlowState[6]`, the wire carries `FaceSkin[6]` (`present: bool` + up to C colored lanes; each lane = `color` + `arm[4]`). Empty faces/lanes omit their codec keys (the same omit-default trick `PipeNode` uses), keeping simple-wire saves byte-identical.
- **Model count stays small:** the three axes are independent and composited, never combined: faces are independent (a floor skin does not reshape a wall skin, unlike welding pipe arms), colors are the same geometry at a runtime offset + tint, and one planar skin library orients to all six faces by rigid transform. The baked library is one face's worth (4^4 = 256 arm-combinations, or 70 rotation-classes spun via `rotationIndex`), shared across all faces and colors; only per-block state grows (cheap codec data). Generated by the same `gen_pipe_tips.py`-style merge pipeline.

### 8.4 Signal network

Signal networks are cached and event-invalidated exactly like resource networks (§6.5): build the wiring graph once, rebuild on wire place/break, never per-tick re-flood-fill. Packed block keys / ECS component tags for node identity (not stringified coordinates or block-id substring matching, the Redstone jar's two worst sins). Visual state via `setBlockInteractionState`. The signal network is its own thing (not a `PortChannel`): it reuses the discovery/caching layer and the per-face render layer, and draws power from the energy grid through the power feed (internal energy path, so per-tile pipe-rate caps do not choke propagation).

### 8.5 Worked example & the two clean knobs

Mental model: build a crafter, give it power, walk away. Baseline automation must not require a logic build (D42):
- **Baseline:** the crafter runs on its own when powered and fed. No circuit needed.
- **Throughput = power fed:** the crafter's power input sets its speed (starve -> slow, pour -> fast, up to a cap). This is the overclock mechanic and the energy sink in one legible knob, living in the machine layer (§7.3).
- **Optional circuit control on top:** when a player wants conditional behavior they add logic (e.g. comparator reads an output-buffer fill -> inverter -> gate the crafter's enable line). Depth on demand, not a requirement.

The two energy knobs stay separate: the circuit layer's CMOS per-switch draw (its character), and the machine layer's throughput-for-power curve (its overclock).

---

## 9. Cross-cutting rules (summary)

Type-lock stall, never void (§6.6). Run-dry freeze/resume (§7.9). Layer boundary at the data model (§2). Network backpressure (§6.3). Port participates only through a pipe network of its channel (§6.3/§7.5). Decay always-on; radiation & containment; decay yields matter (§7.9). Coolant as a recipe ingredient (§7.6). Everything block-mutating marshals through `World.execute` (§4).

---

## 10. Implementation API & techniques

Reference mods in `ReferenceJars/` (`EnergyStorage`, `Redstone`, full-source `HyProTech/`) and the decompiled `HytaleServer.jar` are read-only study material, not dependencies. Class/line numbers drift: names are the durable handle. Decompiler: CFR at `~/.local/share/cfr/cfr.jar`.

- **Energy capability (`dev.zkiller.energystorage`):** `IEnergyStorage` `long` methods, external vs internal paths, `AbstractEnergyStorage` base, component-on-both-stores pattern, codec validators + `.documentation()`. Maps onto our `EnergyHandler`/`EnergyBuffer`.
- **Network discovery (HyProTech):** the BFS flood-fill graph walk (reference only; we cache, never per-tick rebuild). The bundled `com.shailist.hytale.api.transfer.v1.*` Storage/Transaction API for transactional simulate/commit with rollback (our `simulate->commit` is the lightweight version). Tier-config tables as a pattern for our pipe tiers.
- **ECS ticking & events (Hytale core):** `EntityTickingSystem<ChunkStore>` (`getQuery()`, `isParallel()` false when touching neighbors, `tick(dt, index, ArchetypeChunk, Store, CommandBuffer)`); block events `EntityEventSystem<EntityStore, PlaceBlockEvent|BreakBlockEvent>` (`getTargetBlock() -> Vector3i`): the network-invalidation triggers. Registration via `getChunkStoreRegistry().registerComponent/registerSystem`, `getEntityStoreRegistry()`, `getEventRegistry().register`, `getCommandRegistry()`.
- **World / block / chunk access:** `BlockModule.getBlockEntity(world,x,y,z) -> Ref<ChunkStore>` then `store.getComponent(ref, type)`; position decode via `BlockStateInfo` + `BlockChunk` + `ChunkUtil`; `World.setBlockInteractionState(Vector3i, BlockType, String)` is the visual-state primitive; `getRotationIndex` for facing.
- **Multi-cell footprint (the machine §7.5 model):** `FillerBlockUtil.forEachFillerBlock(hitbox, ...)` derives the footprint cells; `WorldChunk.setBlock(..., filler, settings)` and `setFillerBlocksAt`/`removeFillerBlocksAt` claim/clear them (anchor = `filler == 0`, only the anchor gets components). Resolve any cell to its anchor with `anchor = pos - (FillerBlockUtil.unpackX/Y/Z)(WorldChunk.getFiller(pos))` (returns `pos` when `getFiller == 0`). `SimpleBlockInteraction.resolveBaseBlockPosition` already applies this before `interactWithBlock`; `BlockHarvestUtils` for break. NOTE: `getBlockEntity` does NOT resolve fillers (returns null on one), so endpoint collection + wrench must apply the one-liner.
- **Block interactions / assets:** `SimpleBlockInteraction.interactWithBlock(...)` (wrench, circuitry block cycling); `UseLatestTarget: true` mandatory for clicked-face reads; `OpenCustomUIInteraction.registerBlockEntityCustomPage(...)` + a `CustomUIPage` for block GUIs; `.ui` templates follow the Natural20 minimal pattern (`$C.@PageOverlay` + `$C.@Container`/`#Content` text rows; richer macros mis-nest).
- **Containers (engine):** the `ItemContainerBlock` `ChunkStore` component (`getItemContainer()`), present on vanilla chests; `addItemStack(...)` returns a transaction, simulate is a read-only slot scan, the seam carries BSON metadata.
- **Bench engine (`builtin.crafting.*`):** the §7.3 substrate: `ProcessingBenchBlock`, `BenchSystems.ProcessingBenchTick`, `CraftingManager`, `CraftingPlugin.get()`, `CraftRecipeEvent`.
- **Performance patterns (Redstone jar):** lift the time-budget batch queue + dedup set + cancellable scheduled outputs; avoid its mistakes (no cached network, string keys, block-id substring matching, no persistence).
- **Our existing touchpoints:** `MachineTickSystem`, `TransportEngine` (simulate->commit, evolving into the network distributor), `EnergyHandler`/`EnergyBuffer`, `api/io/{PortChannel, PortDirection, FlowState}` + `Port`/`PortConfig`/`ResourceBuffer`. `PortChannel` will gain `NEUTRON`.

---

## 11. Open questions & [TUNE] ledger

**[DECIDE] design questions**
1. ~~Multiblock assembly model.~~ **RESOLVED 2026-06-08 (§7.5):** machines use Hytale's single-item anchor + filler footprint, not assembly. Spikes confirmed (decompile): interaction/GUI/wrench/break redirect filler->anchor natively, and pipes auto-stub to footprint faces via FaceTags; the only new code is a filler->anchor one-liner in endpoint collection + wrench. Each machine's footprint size and default port layout are deferred to that machine's individual design (still open per machine, not a global blocker).
2. **Energy-only vs hybrid fuel.** §7.3 assumes energy-only (no-fuel bench config); confirm no machine keeps a fuel slot.
3. **Player-driven craft taxing.** Do our machines expose a player window taxed via the `CraftRecipeEvent` hook, or are they automation-only?
4. **Recipe-card UX.** One card = one recipe vs a multi-recipe/program card; where it sits in the machine GUI.
5. **Converter reaction set.** The actual curated reaction list, including byproduct electrolysis like chlor-alkali (§7.4).
6. **Decay-model ownership** (foundation hooks vs Decay Vault consuming them). *(Phase Changer reconciliation resolved: it is the refrigeration converter, §7.6.)*
7. **External energy-standard adoption** target + version junction (§5).
8. **Dual-state roster** final list (§7.6).
9. **Circuitry lane offsets + tint palette**; whether inside/outside wraps need a mirrored handedness variant (§8.3).
10. **Coolant data fields** `heat_capacity` / `latent_heat` present in the dataset or to-add (§3.2).
11. **Fluid Combustion Generator naming** (player-facing label for the gas/liquid burner, §7.8).
12. **Spent-fuel reprocessing path** (deferred): which of Irradiator/Cyclotron/chemistry recovers usable material later (§7.8.5).

*Resolved this pass:* multiblock assembly (#1, §7.5); geothermal output = heat source, tiered Thermoelectric -> Turbine (§7.8.3); reactor-surplus sink = overclock -> batteries -> throttle (§7.8.6); Fuel Cell struck; Electrolyzer folded into the Decomposer. Unbounded reactor scaling -> parallel-processing sink, deferred.

**[TUNE] numbers**
- Pipe network buffer sizing & per-tier throughput (tier-0 budget currently 500 cap / 5 per-tick per segment).
- Neutron pipe length cap N; neutron flux numbers (reactor output, per-op consumption, buffer sizes).
- Containment math (absorption per tier, leak threshold, breach-dump magnitude).
- Coolant economy (coolant per cycle per intensity tier; latent-heat -> energy-cost scaling for the Phase Changer).
- Overclock curve (power-in -> speed, and the cap).
- Circuitry CMOS cost per switch (flavor at small scale, noticeable at factory scale).
- Storage/tank tier capacities; machine internal-buffer sizes; recipe durations + signed-energy values.

---

## Appendix : historical detail docs

These are retained as derivation/detail; this master doc is authoritative where they conflict.

- Transport: `2026-06-03-transport-network-rework-plan.md`, `2026-06-05-pipe-flow-states-design.md`, `2026-06-05-storage-balancing-design.md`, `2026-06-06-item-channel-design.md`, `2026-06-05-gas-channel-assets-design.md`, `2026-06-05-blockholder-carry-design.md`, `2026-06-05-live-refresh-panel-design.md`, `2026-06-03-transmitter-pipe-models-design.md`, `2026-06-07-tip-rendering-design.md`.
- Substance/data: `2026-06-01-substance-registry-design.md`, `2026-06-01-api-substance-schema-design.md`, `solid-substance-assets.md`, `2026-06-05-substance-color-glow-design.md`.
- Energy (partially superseded): `2026-06-02-energy-io-plumbing-design.md`, `2026-06-03-energy-io-plumbing-status.md`.
- Machines substrate: `2026-06-08-vanilla-bench-reuse-design.md`.
- Circuitry: `2026-06-08-tech-circuit-system-design.md` (logic/energy), `2026-06-08-wire-face-travel-design.md` (geometry/state).
- Prior main doc (superseded by this file): `machines-and-power-design.md`.
