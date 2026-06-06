# Hytale Chemistry Mod — Design Document

*Status: working draft. Captures locked-in decisions on architecture, rules, constraints, and interactions. Numbers marked `[TUNE]` are placeholders for balancing.*

---

## 0. Integration Status (2026-06-02)

**This is not a separate downstream mod.** The seven machines, the energy standard, the neutron economy, and the heat/cooling loop are **folded directly into ChonbosChemistry** and ship in the single player install. Rationale: anyone who installs the foundation gets radioactive elements and reactive compounds; leaving them with no machines to transmute, contain, or power anything would be a broken half-feature, exactly the way radiation-without-shielding would be. This extends the foundation doc's §2.2 "hazard and counterplay are one mechanic" rule to "matter and the machines that act on it are one mechanic."

This **supersedes the foundation doc's §10 family table**, which had pushed Processing and Nuclear engineering into separate downstream layers. They are now core. The "family on top" still exists for further extension (medicine, hazards/fallout, additional reaction sets), each depending on the foundation.

Boundary resolutions tracked in the brainstorm (see also foundation doc):
- **Energy standard** → our own system, contract in `api.energy`, values in `impl`. We do **not** adopt an external Hytale energy API yet (§7 records the decision and the reopen condition).
- **Phase Changer (§11) vs. foundation §6.2 phase-transition machines** → reconcile to one machine. *(pending)*
- **Decay model** → owned by foundation hooks; Decay Vault (§3) *consumes* them. *(pending)*

### Implementation status (2026-06-05) — POWER + FLUID transport complete and polished, machines next

The transport-network rework plan (`docs/plans/2026-06-03-transport-network-rework-plan.md`) is **fully executed** (Phase N + Phase H, all tasks) and **in-game verified by the user**. Everything is consolidated on local `main` (285 headless tests green; ~102 commits unpushed to origin).

**2026-06-05 quick-wins pass (all in-game verified):**
- **Live panel refresh:** machine/tank/pipe panels update every 10 ticks while open (`PanelSnapshot` pure render model + `PanelRefreshService`/`PanelRefreshSystem`; auto-close on stale block). `docs/plans/2026-06-05-live-refresh-panel-design.md`.
- **Storage balancing:** batteries AND fluid/gas tanks on one network drift to water-fill targets (capacity-blind equal levels, capacity only clamps) using leftover per-tick budget only; churn-free. `docs/plans/2026-06-05-storage-balancing-design.md`.
- **BlockHolder contents carry:** tanks + machines carry FULL state (energy, buffers, type-locks, work progress) across break/place via the engine's native `"BlockHolder"` metadata path; place-side is zero mod code. Supersedes `CC_StoredEnergy`/`MachineEnergyMetadata` (removed). `docs/plans/2026-06-05-blockholder-carry-design.md`.
- **Restart persistence:** spot-checked headlessly (codec round-trip into a fresh `NetworkManager`, fluid type-lock recovery) and in-game (server restart).
- Plus (parallel session): **CC_FluidPipe live end-to-end** (bromine/ethanol sources, type-lock, H8/H8b wipe-recovery) and the **substance color/glow pass**.

**2026-06-05 (later): per-face flow states + I/O wrench (in-game verification pending):**
- **Per-face `FlowState` (`NORMAL`/`PUSH`/`PULL`/`NONE`)** on every pipe face (§13.9), persisted as an optional `PipeNode` codec key (absent = all-`NORMAL`, no migration). **The gas bug is fixed:** different-substance same-channel lines no longer merge (auto-NONE rule, dynamic, drain-remerge via lock-clear). `docs/plans/2026-06-05-pipe-flow-states-design.md`.
- **`CC_Wrench` I/O config tool:** cycles pipe faces `NORMAL→PUSH→PULL→NONE` and machine faces through `(channel × input/output) → closed` (§5.4/§13.9).
- **Face-precise endpoint collection:** `NetworkEndpoints.collect` now qualifies the machine by the port ON the touching face and applies that pipe face's flow-state filter: this **completes** the deferred "real block-face-index alignment" item (face indexing locked to the `OFFSETS` convention `+X,−X,+Y,−Y,+Z,−Z` everywhere).
- **`PortDirection.BOTH`** added as the storage-block (two-way) direction; **wrench config survives a state wipe** (flow states persist in `PipeNode`'s codec; machine port config via `BlockHolder`).
- **In-game verification: PENDING.**

**Built and working (energy/POWER channel, end-to-end):**
- **Energy standard (§7):** `api.energy.EnergyHandler` + `impl.block.EnergyBuffer`: `long` amounts, external (`maxReceive`/`maxExtract`-capped) vs internal transfer paths, ratio helpers, optional-codec rate fields (shared `OptionalLongCodec` incl. the raw-JSON path).
- **Network core (§13.2/§13.7, `impl.block.net`):** `PipeNode` component (channel/tier/persisted `bufferShare`); `NetworkManager` cached BFS discovery (packed-key, anchor-id, invalidate on pipe place/break: chunk-unload eviction DEFERRED because the 0.5.3 engine dispatches `ChunkUnloadEvent` from parallel workers and any `EntityEventSystem` handler kills the WorldThread); `NetworkTickSystem` per-tick distribution: per-phase throughput budget (tier 0 = 500 cap / 5 per-tick per segment, `[TUNE]`), max-min fair-split with ROTATING remainder (equal consumers stay within ±1 cumulatively), **source/storage priority** (pure providers always; battery providers only under real demand; pure acceptors before battery acceptors: no self-churn), **buffer-share persistence** (pool shares on build, write back each tick: network energy survives invalidation/rebuild and block removal).
- **Cable visuals:** 26 topology models + 53-state `CC_PowerCable` (off + `On`/`_On` powered twins flipped at runtime by `energized = stored>0 || delivered>0`); engine facts hard-won: pattern transformations are Y-rotation+mirror ONLY (no pitch/roll: vertical shapes are explicit), rule face-tag lookups ignore the variant frame so **every shape advertises FaceTags on all 6 faces**, `TransformRulesToOrientation:false` everywhere (TRO folds stored rotation into matching = history-dependent flicker), powered shapes are pattern-less (programmatic only: 1-tick blink on topology change accepted). Machines advertise `CC_PowerFace` via vanilla-fence-style self-resolving rulesets + `ConnectsToOtherMaterials:true` so cables stub toward them.
- **Carry + GUI:** machines carry stored energy across break/place via item metadata (`CC_StoredEnergy`, HyProTech-style cancel+custom-drop; the engine's native `"BlockHolder"` full-entity path is noted in session memory for future full-state carry). Interact (F) panel shows machine gauges, and on pipes the NETWORK readout (stored/capacity/%, pipe count, throughput).
- **Test rig:** `CC_PowerCell` (creative source), `CC_EnergySink` (burns 2/tick), `CC_EnergyStorage` (5000 battery, both-ways), all connected by `CC_PowerCable`.

**Not started / deferred (the remaining roadmap):**
1. **§3 + §9 machines & recipes: THE NEXT MILESTONE.** The `MachineTickSystem` work pass is still a stub; everything it needs (power, transport, buffers, run-dry rules, GUI scaffold, ON/OFF state mechanism per §16-style `State.Definitions` + `setBlockInteractionState`) now exists. Chemistry trio first (Synthesizer/Decomposer/Converter, recipes auto-derived from the dataset).
2. **GAS pipes: DONE (2026-06-05, in-game verified).** `CC_GasPipe` + hydrogen/CO₂ test rig, pure asset clone of the fluid pattern (`docs/plans/2026-06-05-gas-channel-assets-design.md`); final gas art replaces the placeholder `GasTube_*`/`gastube_*` files in place. **ITEM pipes (§13.4) and NEUTRON pipes (§13.5):** own later plans.
3. **§11 heat, §14 redstone, §15 electrified benches:** designed, not started.
4. Small deferred items: pipe tooltip icon (Blockbench render staged at `~/Development/Hytale/models/staging/pipe-icon/`; NOTE pipe models use per-axis `stretch` which the Blockbench viewport ignores: bake stretch before screenshotting), chunk-unload eviction via a WorldThread-safe hook, ~~real block-face-index alignment~~ (DONE 2026-06-05, see flow-states entry above), network split/merge optimization (vs drop-and-rebuild), all `[TUNE]` numbers. *(Done 2026-06-05: live-refresh GUI, battery↔battery balancing, tank/resource carry via `BlockHolder`, restart persistence spot-check.)*

### Revision: transport architecture (supersedes the adjacency model)

Following an analysis of three reference mods (HyProTech, `dev.zkiller.energystorage`, `org.pfc.redstone` — all in `ReferenceJars/`), the transport model is rebuilt:
- **Adjacency auto-flow is removed.** Transport now runs entirely on a **fixed pipe network** (Mekanism-style). This supersedes the old "adjacency does the work; cables optional" pillar (§1) and the adjacency language in §5.4/§5.5/§8.
- **Five pipe channels:** energy / fluid / gas / item / neutron, each its own pipe and its own network.
- **Fungible channels use a shared network buffer** (the network is one virtual tank/battery), which dissolves propagation lag, distribution fairness, multi-source balancing, and aggregate-gauge problems at once (§13).
- **Items** use a separate discrete-pathfinding model (§13.4); **neutrons** are fungible but length-capped (§13.5).
- New sections: **§13 Transport Architecture**, **§14 Redstone & Circuitry**, **§15 Electrified Vanilla Workbenches**, **§16 Implementation API & Technique Reference**.

---

## 1. Scope & Pillars

A chemistry + nuclear physics mod built on a comprehensive real-world dataset (all elements, all isotopes, 150+ compounds). Two transformation layers:

- **Chemistry layer** — operates on *electrons and bonds*. Combines/breaks/converts compounds. Never touches the nucleus, so it can never change an element or its isotope. Cheaper, earlier-game.
- **Nuclear layer** — operates on *the nucleus*. Changes protons or neutrons (transmutation, activation, decay, fission). High-energy, late-game, irreversible by ordinary chemistry.

The **Reactor** bridges the two: it is the sole source of free neutrons and the anchor of the power economy.

Design philosophy: **limitations by default, automation if you earn it.** Transport is a **deliberate build step**: machines, tanks, and storage exchange resources only through **pipes** that form networks (§13). There is no free adjacency transfer: connecting two blocks is something you plan and route, not something that happens because they happen to touch. (This reverses an earlier "adjacency optional-cables" draft; see §0 revision and §13.)

---

## 2. Core Data Model

Every element/compound item carries three fields:

| Field | Meaning | Who may edit it |
|---|---|---|
| **Element identity** | Proton count. Equals the symbol. Fixed. | Nuclear layer only |
| **Isotope** | Neutron count → determines stability & half-life | Nuclear layer only |
| **Formula** | Present only on compounds | Chemistry layer only |

**Hard rule:** the chemistry layer edits *only* the formula field; the nuclear layer is the *only* thing that may alter protons or neutrons. This single rule keeps the two systems from colliding and makes "why can't I craft plutonium in a furnace" answer itself.

**Phase** is a property assigned per substance (solid / fluid / gas) from the dataset. Determines which storage and transport it uses (Section 5).

**State change.** A small set of substances exist in more than one usable state (e.g., gas helium vs liquid helium). Where a second state has a real job — coolant or dense storage — both states are registered as distinct phase-tagged forms and a machine converts between them (Section 11). Only the handful that need it are dual-state; the other ~140 compounds stay single-state. This keeps state change as *depth on a few* rather than breadth.

---

## 3. The Seven Machines

| # | Machine | Layer | Function | Consumes |
|---|---|---|---|---|
| 1 | **Synthesizer** | Chemistry | Elements → compound (combine) | Power |
| 2 | **Decomposer** | Chemistry | Compound → elements (break apart) | Power |
| 3 | **Converter** | Chemistry | Compound A → compound B (reaction) | Power (signed) |
| 4 | **Irradiator** | Nuclear | Adds neutrons → **heavier isotope of the same element** (activation) | Power + neutrons |
| 5 | **Transmuter** | Nuclear | Forces beta/alpha → **changes the element itself** | Power + neutrons |
| 6 | **Decay Vault** | Nuclear | Passive decay → harvest daughter matter; contain radiation | — (passive) |
| 7 | **Reactor** | Nuclear | Fissile fuel → power + neutron flux | Fuel |

**Irradiator vs Transmuter — the clean division:**
- **Irradiator stays *inside* one element.** It only manipulates the isotope (neutron count). Carbon-12 → Carbon-13 → Carbon-14. This is the "make it radioactive" path.
- **Transmuter *crosses* element boundaries.** It forces a decay to change the proton count: beta (element +1, climb the table) or alpha (element −2, descend toward lead).

Both draw on the neutron buffer, but for different purposes.

---

## 4. Transmutation Rules (the nuclear "recipe logic")

There are only three nuclear moves, each deterministic from the dataset:

| Operation | Mechanism | Effect | Owner machine |
|---|---|---|---|
| **Neutron capture** | +1 neutron | Same element, heavier/less stable isotope | Irradiator |
| **Beta decay** | neutron → proton | Element **+1** (up the table) | Transmuter |
| **Alpha decay** | eject 2p + 2n | Element **−2** (down the table) | Transmuter |

Decay chains naturally terminate at stable lead, which prevents infinite chains — a built-in dead end.

---

## 5. Resource Phases, Storage & Transport

### 5.1 Phase matrix

| Phase | Storage block | Transport (pipe) | Network model | Manual move |
|---|---|---|---|---|
| Solids / items | Chest (already in game) | Item pipe | discrete pathfinding (§13.4) | carry the stack |
| Fluids | Fluid tank | Fluid pipe | shared buffer, type-locked (§13.2–.3) | **pick up the tank** |
| Gases | Gas tank | Gas pipe | shared buffer, type-locked (§13.2–.3) | **pick up the tank** |
| Power | (in-machine buffer) | Energy cable | shared buffer (§13.2) | — |
| **Neutron flux** | internal buffer only | Neutron pipe | shared buffer, **length-capped** (§13.5) | — |

All five are **pipes carrying their own channel** with the same network machinery (§13); they differ only in the per-channel rules noted above. Transport requires a pipe: there is no adjacency auto-flow.

### 5.2 Tanks
- **One substance type per tank**, locked until emptied. A water tank rejects oxygen. This forces dedicated, labeled tank farms (intended).
- Fixed **capacity** per tank.
- **No pressurized-gas simulation.** A gas is mechanically identical to a fluid — a number up to a capacity. "Pressurized" is flavor only.
- **Three tiers** (e.g., Basic / Advanced / Elite). **Tier affects storage capacity only** — no other differences. **Tanks are not stackable** in any tier. Higher tiers gated behind the mod's own refined materials, looping chemistry back into infrastructure.
- **Tanks relocate with contents preserved.** Picking up and moving a full tank *is* the manual transport method — there is no separate portable-container item (see Section 5.6).
- **Tank faces ship as storage (`PortDirection.BOTH`)** on all sides (set in JSON); wrench/GUI port config for tanks is a **later refinement** (the wrench currently cycles machine faces only). Until then a tank both provides to and accepts from any connected pipe network of its channel.

### 5.3 The five pipe types
Energy cable, fluid pipe, gas pipe, item pipe, neutron pipe. They are the **only** way resources move between blocks (adjacency auto-flow is removed). All five share the same network machinery, fully specified in **§13**:
- A pipe joins whatever **network** its connected pipes form; the network — not the individual pipe — is the unit of transport.
- **Pipe tier sets throughput and contributes capacity** to the network buffer. (Pipes no longer hold a *located* per-segment buffer that crawls one block per tick; that bucket-brigade model is dropped because it was the source of propagation lag and unfair distribution — see §0 revision and §13.2.)
- **Fungible channels** (energy / fluid / gas / neutron) share one network buffer; **items** travel as discrete pathfinding stacks (§13.4); **neutron** networks are additionally length-capped (§13.5).
- **Fluid and gas pipes are type-locked** like tanks: one substance per network until drained (§13.3).
- Pipes are **required** for transport, but remain cheap, early infrastructure: the cost is routing/planning, not gating.

### 5.4 Configurable ports
Machines are **≥4×4 multiblocks**, so faces are plentiful: configuration is **one port per face** (the earlier "multi-port sides" model is superseded: see the 2026-06-05 flow-states design doc). Each face carries exactly one port, assigned a **channel** (item / fluid / gas / power / neutron) and a **`PortDirection`** (input / output / both / closed). `BOTH` is the storage-block direction (two-way access). A port connects when a **pipe of its channel** sits on that face: the port becomes a provider (output) or acceptor (input) on that pipe's network (§13). Two machines that sit flush against each other do **not** exchange: a pipe of the right channel must bridge them. **Decided: a pipe is always required; there is no direct port-to-port (zero-length-network) transfer.** One code path, no special case: building transport is always a deliberate routing step.

Ports ship with **sensible defaults most players never touch**, and can be reconfigured two ways (Mekanism does both, and Hytale workbench GUIs make the in-menu version cheap): with the **CC_Wrench** on the block face (it cycles the face's port through the machine's capability pairs `(channel × input/output) → closed`), and/or via the **machine GUI** (later). Defaults-with-optional-config keeps the HCI cost near zero for casual players while leaving full control available.

> Note: because machines are large multiblocks with abundant faces, **face scarcity is a non-issue**: this is what let us drop hand-held canisters and any in-GUI injection slot entirely (see Section 5.6).

### 5.5 Machine internal buffers
Every machine holds small per-channel internal buffers (a few operations' worth), shown as gauges in the GUI; connected pipe networks top these up between cycles. Their job is to **decouple production rate from consumption rate** (a machine that consumes a steady trickle can be fed by a source that delivers in bursts), and to define run-dry behavior (Section 8). They are **not** there to mask transport latency: under the shared-network-buffer model (§13.2) intra-network transfer is effectively instantaneous, so there is no per-hop crawl to hide.

### 5.6 No hand-held canisters (decided)
There is **no separate canister/portable-container item class**, and **no in-GUI fill/empty injection slot**. Fluid and gas movement is: a pipe network (§13), or carry the whole tank (contents preserved).

Rationale — both surviving justifications for a canister were eliminated by other decisions:
- *Face scarcity* — removed, because multi-port sides give machines abundant I/O surface (Section 5.4), so no GUI injection slot is needed to compensate.
- *Stacking for hauling* — removed, because tanks are not stackable in any tier, so a small tank offers nothing a canister would.

A canister would therefore be a redundant method for a task pipe-routing/tank-carry already covers, adding a "which method?" tax (Hick's Law) and GUI clutter for no new capability. One mental model: **tanks, in sizes.**

---

## 6. Neutron Flux Rules

- Neutrons are a **separate currency**, used only by nuclear machines. **Not** tankable and **not** carried in fluid/gas/energy pipes — they have their own **neutron pipe** and their own network.
- **Neutron pipes are length-capped (hard refusal).** A neutron network may span at most **N** pipe blocks; **decided: the network will not extend past the cap, and no flux exists beyond range** (no per-hop falloff math). This is the realistic constraint — free neutrons are absorbed fast and travel short distances. It forces a tight, hot, shielded core; the pipe just buys a few blocks of routing freedom over pure adjacency. The cap *value* N is `[TUNE]` (§10).
- Mechanically the neutron network is a **fungible shared buffer** like energy (§13.2), with the §13.5 length cap layered on top. It is **not** type-locked (neutrons are a single currency).
- Sources of free neutrons: the **Reactor** (fission, bulk) and rare **spontaneous-fission isotopes** placed in a Decay Vault (trickle, high-tier).
- The legacy "neutron guide" block is folded into this: the neutron pipe **is** the guide. The Decay Vault / Neutron-guide entry in §12 is updated accordingly.

---

## 7. Energy Standard

**Decision: we ship our own energy system; we do not adopt an external Hytale energy API yet.** There *are* published candidates already (`dev.zkiller.energystorage`, and the shailist Fabric-Transfer port bundled in HyProTech — both in `ReferenceJars/`), but Hytale modding is early and wild-west: those APIs are third-party, unmaintained by us, and not updated fast enough to bet our core on. So we build our own contract in `api.energy`, **steal the proven techniques** from those references, and keep the option to switch open.

**Reopen condition:** if one external standard demonstrably wins ecosystem adoption, we adopt or bridge to it **at that update junction** (a deliberate version bump), not reactively. Until then ours is canonical for this mod.

Wins adopted from the analysis (details + source locations in §16.1):
- **`long`, not `int`,** for all energy amounts and capacities. Big reactors and battery banks exceed the 2.1B `int` ceiling; HyProTech was bitten by exactly this. ✅ **Done** — `EnergyHandler`, `EnergyBuffer` (incl. codec `Codec.LONG`), and the `TransportEngine` energy push are migrated to `long`; full suite green (94 tests). Pipe-tier *throughput* stays `int` (a >2.1B/push cap is not meaningful). The internal/external split below is still pending.
- **Internal vs. external transfer paths.** `receiveEnergy` / `extractEnergy` respect a per-call rate cap (`maxReceive` / `maxExtract`) for *external* network transfer; `receiveEnergyInternal` / `extractEnergyInternal` bypass the cap for a block filling/draining *its own* buffer (a generator producing, a machine consuming). Our current 4-method handler lacks this split and needs it.
- **Per-buffer rate caps** (`maxReceive` / `maxExtract`) living on the handler, distinct from pipe-tier throughput: a battery can throttle its own I/O independent of the wire.
- **Ratio/health helpers** (`getFillRatio`, `isFull`, `isEmpty`) — trivial, and exactly what the §13.7 distribution and GUI gauges consume.
- **Codec validators + `.documentation(...)`** on every field (for schema generation), per the zkiller component pattern.

Retained design rules (unchanged):
- **Lossless** transfer by default (at most one optional distance-loss toggle).
- Network distribution is **fair-split push** (§13.7), not naive greedy.
- Pipe tiers differ **only in throughput** (and network-buffer contribution). **No voltage tiers, no burn-your-machine penalty** — that complexity kills standards.
- Power flows both ways: Reactor exports; Decay Vaults and decay generators (RTGs) trickle power back.

---

## 8. Interaction & Constraint Rules

These cross-cutting rules define how the pieces behave together.

1. **Type-lock enforcement.** A tank — *and a fluid/gas pipe network* (§13.3) — accepts only its locked substance until fully drained. A machine output that targets a full or wrong-substance tank/network **stalls**: it pauses and stages the product in its internal buffer. It never voids output (same as a full chest stopping a furnace). Predictable, never punishing.
2. **Run-dry behavior.** If an input buffer empties mid-cycle, the *current* operation pauses cleanly and resumes when supply returns. No corruption, no wasted inputs.
3. **Layer boundary.** Chemistry machines reject any operation that would change protons/neutrons; nuclear machines reject any pure formula edit. Enforced at the data-model level.
4. **Decay is always-on.** An unstable item's half-life timer ticks even in inventory — leave a reactive isotope too long and it silently becomes its daughter (use-it-or-lose-it). The Decay Vault is how you *control and harvest* that, not a prerequisite for decay to occur.
5. **Radiation & containment.** Each decay tick emits radiation. A vault's containment rating absorbs up to `[TUNE]` per tick; any excess **leaks** into the world and triggers the existing radiation hazard. Better shielding absorbs more but costs more. A breached/neglected vault dumps its accumulated radiation at once.
6. **Decay yields matter, not just energy.** Primary capturable output of any decay is the **daughter element** (decay = transmutation by patience). Alpha emitters additionally yield **helium gas**. Spontaneous-fission isotopes yield **free neutrons**.
7. **Network backpressure.** When a network's shared buffer (§13.2) is full because every acceptor is full, providers stop extracting rather than voiding. The buffer smooths bursts; nothing is ever lost.
8. **Port / network connection.** A port participates only through a pipe network of its channel (§13). An output port is a provider, an input port an acceptor, on that network; the network mediates all matching providers and acceptors via fair-split (§13.7). Closed ports never connect. Two flush machines with no pipe between them do not exchange.
9. **Coolant as a recipe ingredient.** A machine that runs hot lists a coolant material in `inputs` and its spent (state-changed) form in `outputs`. If coolant supply runs out, the machine follows standard run-dry behavior (rule 2) — it pauses; there is no separate temperature gauge or meltdown timer at the machine level (Section 11).

---

## 9. Recipe Schema & Generation Strategy

### 9.1 Unified schema (all seven machines)
```json
{
  "machine": "synthesizer",
  "inputs":  [ { "id": "element:hydrogen", "phase": "gas",   "amount": 2 },
               { "id": "element:oxygen",   "phase": "gas",   "amount": 1 } ],
  "outputs": [ { "id": "compound:water",   "phase": "fluid", "amount": 2 } ],
  "energy": -50,        // SIGNED: negative = exothermic (returns power), positive = endothermic (costs power)
  "neutrons": 0,        // consumed from the neutron buffer (nuclear machines only)
  "operation": null,    // nuclear only: "neutron_capture" | "force_beta" | "force_alpha"
  "duration": 40
}
```

### 9.2 Generation strategy — derive, don't hand-author
- **Decay Vault: zero recipes.** Reads decay mode, daughter product, and half-life straight from the isotope dataset.
- **Irradiator / Transmuter: derived.** Neutron capture looks up "+1 neutron"; beta/alpha look up the element shift. Deterministic from the isotope table.
- **Reactor: derived.** Fission products come from data.
- **Synthesizer / Decomposer: auto-generated** by balancing each compound's formula (water's formula yields both directions for free).
- **Converter: curated.** "Which compound reacts into which" is reaction chemistry, not derivable from formulas — ship a modest hand-authored reaction set.

The **signed energy** field turns real reaction enthalpies into the economy: exothermic syntheses generate power, endothermic ones cost it. That asymmetry is what makes the chemistry side interesting rather than a flat power sink.

---

## 10. Open Questions / To Decide

1. **External energy-standard adoption.** Which (if any) published Hytale energy API to bridge to, and at which version junction (§7 reopen condition). Until then ours is canonical; an export adapter is built only if/when a target standard is chosen.
2. **Pipe network buffer sizing & throughput per tier** — `[TUNE]` (network buffer = Σ pipe-segment capacities, §13.2).
3. **Neutron pipe length cap value N** — `[TUNE]` (mechanism resolved: hard refusal, §6/§13.5).
4. **Neutron flux numbers** — reactor output, per-operation consumption, buffer sizes — `[TUNE]`.
5. **Containment math** — absorption per tier, leak threshold, breach-dump magnitude — `[TUNE]`.
6. **Coolant economy numbers** — coolant consumed per cycle per intensity tier, latent-heat → energy-cost scaling for the Phase Changer — `[TUNE]`.
7. **Dual-state roster** — confirm the final list of substances that get both gas and liquid forms (Section 11.4).
8. **Redstone/circuitry scope & energy cost** — gate set, signal model, and the microscopic per-signal energy draw (§14) — `[DECIDE]` / `[TUNE]`, a later system (own plan).

*Resolved:* transport model (fixed pipe networks, adjacency removed — §0 revision, §13); **pipes always required, no direct port-to-port** (§5.4); **neutron cap = hard refusal** (§6/§13.5); energy standard (our own now, defer external adoption — §7); `int`→`long` migration done (§7/§16.1); hand-held canisters (dropped, Section 5.6); state-change approach (coolant-as-material, Section 11); phase config model (per-port, Section 5.4). **Implementation sequencing:** pivot now — supersede the adjacency `TransportEngine` with the network layer; first plan covers the transport core (energy/fluid/gas fungible networks) + energy internal/external API; items, neutron pipes, redstone, and electrified benches are later plans.

---

## 11. Heat, Cooling & State Change

Heat is modeled **as a material, not as a tracked temperature.** There is no temperature number, no heat-pipe network, and no machine-level meltdown gauge. A process that runs hot simply **consumes a coolant in one state and returns it in the other** — the phase change *is* the heat exchange. This rides entirely on the fluid/gas tank + cable infrastructure already defined and adds **zero new recipe fields**: coolant is one more `inputs` entry, spent coolant one more `outputs` entry.

### 11.1 The closed loop
- **Phase Changer** (utility block — see Section 12): `power + gas coolant → liquid coolant`. Energy cost = the substance's real **latent heat × amount**, pulled from the dataset. Power *is* the refrigeration, so there is no "what cools the cooler" regress.
- **Hot machines** (Reactor, high-tier Transmuter) take **liquid coolant in, gas (spent) coolant out** as part of their recipe and cannot run without it.
- Spent gas returns to the Phase Changer to be re-liquefied. Loop closes.

### 11.2 Intensity without a gauge
"How hot" a process runs is encoded as **how much coolant it consumes per cycle** — a fierce reactor recipe burns more liquid coolant per tick than a mild one. This gives graduated thermal difficulty with only a throughput number, matching Mekanism's approach.

### 11.3 Heating is the same pattern, reversed
An **endothermic** process that needs heat consumes a **hot** material and emits its **cooled** form. The one coolant-material pattern therefore covers both cooling and heating — no separate heating system is built.

### 11.4 Coolant tiers & the storage perk
Coolant quality is a tech progression:
- **Water → steam** — early, cheap, and (via a turbine) also generates power.
- **Mid-tier fluids** — better thermal capacity.
- **Cryogenic liquid helium / liquid nitrogen** — endgame coolant for the hottest cores and superconducting power cables.

Liquid helium earns the state-change mechanic because its two states do **different jobs**: gas = inert blanket/lifting gas; liquid = the only coolant cold enough for the top tier. To get it you *must* run the Phase Changer — so state change is the gate on the best coolant, not optional flavor.

**Dense-storage perk:** a liquefied gas occupies a fraction of the volume, so a tank holds far more of it. Capacity multiplier = the substance's (simplified) **gas-to-liquid density ratio**. Liquefy to store dense, regasify to use.

**Dual-state roster (initial):** coolants — helium, nitrogen, sodium, water/steam; dense-storage gases — oxygen, hydrogen. All other ~140 compounds are single-state. (Final roster: Open Question 6.)

---

## 12. Utility Blocks

Small blocks that support the seven main machines without joining the machine lock. (Like tanks and cables, they are infrastructure, not "main machines.")

| Block | Role |
|---|---|
| **Phase Changer** | Bidirectional gas↔liquid (and liquid↔solid where relevant) for the dual-state roster. Cost = latent heat × amount. The base case of the heat loop (Section 11.1). |
| **Turbine** | Converts a hot spent coolant (e.g., steam) back toward power, recovering energy from the cooling loop. |
| **Phase tanks** | Standard fluid/gas tanks (Section 5.2); a liquefied substance simply stores at higher effective capacity (Section 11.4). |
| **Neutron pipe** | The neutron channel's pipe; length-capped network (Section 6, §13.5). Replaces the old standalone "neutron guide." |
| **Decay generator (RTG)** | Passive trickle power from a decaying isotope; feeds power back to the grid (Section 7). |

---

## 13. Transport Architecture (Network Model)

The single source of truth for how the five pipe channels move resources. Modeled on Mekanism's network design, which solves (for free) the problems a naive per-cable bucket-brigade creates. **The anti-pattern to avoid is HyProTech's per-tick flood-fill rebuild** (`ReferenceJars/HyProTech`), which is why it needs tick-timeouts and move-budgets to survive; we cache networks instead (§13.6).

### 13.1 One model, five channels
Energy / fluid / gas / item / neutron each form independent networks using the same machinery. A **network** = a connected set of **pipes** of one channel, plus the **ports** (machine/tank faces, §5.4) touching those pipes. Ports are **providers** (output) or **acceptors** (input). Transport happens only across a network — adjacency auto-flow is removed (§0 revision). The channels split into two transfer disciplines:
- **Fungible** (energy, fluid, gas, neutron): shared network buffer (§13.2).
- **Discrete** (items): traveling pathfinding stacks (§13.4).

### 13.2 Fungible networks = one shared buffer
For energy/fluid/gas/neutron the **network is a single virtual tank/battery**. Contents are fungible and have **no per-pipe location**; the pipes only contribute:
- **capacity** — the network buffer size = Σ (capacity of each pipe segment), so more/bigger/higher-tier pipes = a bigger shared buffer; and
- **throughput** — the per-tier rate cap on how fast the network fills acceptors.

Because the buffer is one object spanning the whole network, four problems vanish at once:
- **No propagation lag** — the buffer is "everywhere" in the network simultaneously; nothing crawls block-by-block. (Startup lag, if desired, is only the time to fill the network's total capacity — a tunable, intentional feel, not a per-hop artifact.)
- **Fair global distribution** — the network sees *all* acceptors at once and splits fairly (§13.7); no near-acceptor-starves-far-acceptor bias.
- **Free multi-source balancing** — every provider fills the one buffer; balance across sources is automatic.
- **Free aggregate gauge** — the buffer's `stored / capacity` *is* the grid readout.

### 13.3 Type-lock on fluid/gas pipe networks
A fluid or gas **pipe network is locked to one substance id** while non-empty (mirrors the tank type-lock, §5.2). An oxygen gas network rejects helium; it must be **fully drained** (into a container/machine) before a different substance can enter. This is Mekanism's behavior and it forces dedicated, labeled pipe runs (intended).
- **Energy** and **neutron** networks are **not** type-locked — each carries a single fungible currency, so there is nothing to lock.
- **Item** networks are not single-type either; discreteness is handled per-stack (§13.4).

### 13.4 Item networks = discrete pathfinding stacks
Items are **not** a shared buffer — they have identity (a specific stack going to a specific place). Adopt Mekanism's logistical model, not HyProTech's (which forced items through the fungible flood-fill and needed round-robin indices + slot hacks + a 100 ms timeout to cope):
- An extracted stack becomes a **traveling object** routed through the pipe network to a **destination chosen at insertion time** (round-robin / nearest / priority, configurable per §5.4 port).
- The stack physically occupies pipe segments as it travels (so item pipes *do* have located contents, unlike fungible pipes).
- Filtering (whitelist/blacklist per port) and priority are evaluated when picking the destination.
- This keeps discrete routing logic out of the fungible buffer code — they are deliberately separate subsystems sharing only the network-discovery/caching layer (§13.6).

### 13.5 Neutron networks = fungible but length-capped
Neutron networks behave like energy networks (§13.2, shared buffer) with **one hard extra rule: a maximum network size.** **Decided: hard refusal** — the network will not extend past **N** pipe blocks, and no flux exists beyond range (no per-hop falloff math to tune; just a size check at network-build/extend time). The cap value N is `[TUNE]`. This keeps reactor cores tight and shielded; the pipe only buys a few blocks of routing freedom over pure adjacency.

### 13.6 Network lifecycle & caching (the performance core)
Networks are **persistent cached objects**, not rebuilt each tick:
- **Built once** by flood-fill (BFS/union-find) over connected pipes when first needed.
- **Invalidated only on structural change** — a pipe place/break, or a port reconfigure — caught via ECS block events (§16.3). On invalidation, rebuild just the affected network (split on break, merge on place), not the world.
- **Per-tick cost is O(active networks)** doing distribution, **not** O(pipes) doing discovery. This is the whole reason we don't need HyProTech's timeouts/budgets.
- **Persisted** so networks survive restart and behave deterministically across chunk load/unload. A pipe reaching into an unloaded chunk is handled explicitly (treat as a network boundary), never silently dropped the way the reference mods do.
- Borrow the **dirty-queue + dedup-set + per-tick time-budget** batcher from the Redstone jar (§16.8) as a safety valve for pathological rebuild storms — but it is a backstop, not the primary mechanism.

### 13.7 Distribution algorithm (fair-split with remainder redistribution)
The per-tick fungible distribution, ported from Mekanism's `SplitInfo`/`Target`:
1. Collect acceptors that can receive, each with its remaining capacity.
2. `share = amountToSend / acceptorCount`.
3. Any acceptor that can take **less** than `share` gets what it can hold and is removed; its unused share is **added back** to the pool.
4. Recompute `share` over the remaining acceptors; repeat until stable.
5. Split the remainder evenly among acceptors that still have room.

Result is **max-min fairness**: every acceptor gets an equal share or as much as it physically can, with no wasted capacity while any acceptor still has room. ~60 lines, order-independent, no starvation. Providers feed the buffer first; this runs once over the pooled amount, which is what makes multi-source balancing automatic.

### 13.8 What this means for machine buffers
Machine internal buffers (§5.5) exist to **decouple production cadence from consumption cadence**, not to hide transport latency (there is none intra-network). A furnace drawing a steady trickle from a reactor that pulses output every N ticks rides its buffer across the gaps.

### 13.9 Per-face flow states
Every **pipe face** carries a **`FlowState`**: `NORMAL` / `PUSH` / `PULL` / `NONE` (persisted as an optional codec key on `PipeNode`: absent = all-`NORMAL`, so placed pipes decode unchanged). This is the Mekanism-style per-side connection control, brought in to fix a gas-testing bug where two adjacent pipes carrying *different* substances welded into one network. Detail lives in `docs/plans/2026-06-05-pipe-flow-states-design.md`; the load-bearing rules:

- **Filter semantics (port = OFFER, pipe face = ACCEPT).** A machine/tank port says what it *offers* (its `PortDirection`); the touching pipe face says what the network *accepts* through it. The matrix:
  - **`NORMAL`**: honor the port's own direction (`OUTPUT` → provider, `INPUT` → acceptor, `BOTH` → both).
  - **`PUSH`**: acceptor-half only (the network pushes *into* the block: a `PUSH`-reached tank joins as acceptor only).
  - **`PULL`**: provider-half only (the network pulls *out*: a `PULL`-reached tank joins as provider only).
  - **`NONE`**: invisible (no connection through this face).

  Storage pairs into a balancing `StorageEndpoint` only with full two-way access; a `PULL`-reached tank is a provider only, `PUSH`-reached an acceptor only.
- **Auto-NONE rule (substance mismatch).** Two adjacent same-channel pipes whose persisted `resourceId`s are both non-null and **different** do not connect: `NORMAL` dynamically resolves to no-connection. This is **not stored**: it is re-evaluated on every topology event, so it is **dynamic**: draining one line clears its lock, and the next event lets the two lines **remerge** (the tick write-back detects a lock clearing `resourceId` non-null → null and invalidates the boundary). Pipe↔pipe faces only meaningfully cycle `NORMAL ↔ NONE`; push/pull data on a pipe-pipe face is treated as `NORMAL`.
- **Wrench is the config tool.** `CC_Wrench` cycles a pipe face `NORMAL → PUSH → PULL → NONE` (sneak reverses, where the engine exposes it); on a machine face it cycles the single port through the machine's capability pairs `(channel × input/output) → closed` (§5.4). Wrench writes are topology events: they invalidate the affected network (with the H8 snapshot guard) and recompute visuals.
- **Visuals are programmatic.** The engine's pattern system **cannot see flow state**, so any pipe with a suppressed or special arm gets **programmatic shape states** (the proven H8 `setBlockInteractionState` path); all-`NORMAL`, unmismatched pipes keep riding the pattern system for free. A suppressed arm (`NONE`/mismatch) renders the **lesser topology** shape (tee → straight, straight → end: all 26 shapes already exist), and `PUSH`/`PULL` endpoints get dedicated **end-stub indicator** art (`_end_push` / `_end_pull` + `_on` twins per pipe family).
- **Pipes drop plain.** Breaking a pipe carries **no flow-state config** (matches Mekanism); machines/tanks carry their port config via `BlockHolder` automatically, but pipes do not.

---

## 14. Redstone & Circuitry (our own system)

The only Hytale redstone reference (`org.pfc.redstone`, `ReferenceJars/Redstone-0.0.9.jar`) is unmaintained and architecturally an anti-pattern (re-flood-fills every event, string-keyed, unpersisted — §16.8). We need control circuitry for machine automation, so we build our own, copying **Minecraft's redstone *design concept*** while making it ours and tying it into the power economy.

### 14.1 Concept
- Keep Minecraft's mental model: **signal strength**, **logic gates**, local propagation with a short range. Players who know redstone are instantly fluent.
- **Rename for the electrical-engineering theme:**
  - redstone **dust → wiring** (a low-power signal-carrying wire).
  - redstone **block/torch → conduit / source blocks** (EE-flavored signal sources and relays).
- **Logic gates as blocks:** AND, OR, NOT, XOR, NAND, NOR, XNOR — the full set, plus repeaters/relays and comparators-equivalent for analog signal strength.

### 14.2 The differentiator: signals cost energy
Unlike Minecraft (free redstone), **circuitry runs on our energy system in microscopic amounts** (§7). A live wire/gate draws a trickle of power; **you still need an energy source** to run logic. This:
- ties circuitry into the same power grid as machines (one economy, not two);
- makes "wire up a giant control system" a real (if tiny) load, not free;
- uses the **internal** energy path (§16.1) for the micro-draw so per-tile pipe-rate caps don't choke signal propagation.
- Numbers (`[TUNE]`, §10): cost per active wire/gate per tick should be small enough to be flavor at small scale, noticeable only at automation-factory scale.

### 14.3 Architecture (reuse §13.6, not the Redstone jar's model)
- Signal networks are **cached and event-invalidated** exactly like resource networks (§13.6): build the wiring graph once, rebuild on wire place/break, never per-tick re-flood-fill.
- Signal value model can stay **per-block decrement with a short range** (Minecraft-style, ~15) since that matches player expectation; that is orthogonal to the caching strategy.
- Use packed block keys / ECS component tags for node identity, **not** stringified coordinates or block-id substring matching (the Redstone jar's two worst performance sins, §16.8).
- Visual state via `World.setBlockInteractionState` for wire connection shapes and powered/unpowered states (§16.4).

### 14.4 Integration with machines
Circuitry is the **control layer** over the transport layer: gate machine operation on/off, open/close ports, and read sensors (tank fullness, buffer level, network `getFillRatio`). This is what turns the machines + pipes into programmable automation. Detailed design deferred — this is a later system (§10 Q9).

---

## 15. Electrified Vanilla Workbenches (later)

**Planned later work, not initial scope.** Build **energy-powered versions of every Hytale workbench that currently runs on fuel** (cooking pot, smelter, and any other fuel-burning station). Each electric variant:
- consumes **our energy** (§7) from a connected energy network instead of burning fuel items;
- otherwise preserves the vanilla recipe/behavior, so it slots into existing progression;
- gives players a reason to route power to the early-game crafting stations, bridging vanilla Hytale into the mod's power economy before the seven main machines (§3) come online.

This is a breadth feature to schedule after the core machines, transport, and energy system are stable. Tracked here so the energy API (§7) and port model (§5.4) are designed with these variants in mind.

---

## 16. Implementation API & Technique Reference

A working catalog of the concrete Hytale APIs and techniques to lean on when we build the systems above, with where each was observed. **Reference mods are in `ReferenceJars/`; treat them as read-only study material, not dependencies.** Class/line numbers drift between mod versions — names are the durable handle.

### 16.0 Re-opening the references
- **Jars:** `ReferenceJars/EnergyStorage-1.0.5.jar`, `ReferenceJars/Redstone-0.0.9.jar`, and the full-source `ReferenceJars/HyProTech/` (a checked-out repo, not a jar — read `src/main/java/com/example/plugin/...` directly).
- **Decompiler:** CFR at `~/.local/share/cfr/cfr.jar`. Decompile a class with: `java -jar ~/.local/share/cfr/cfr.jar <Class.class> --comments false`. `javap -p -c` (SDKMAN current JDK) for signatures/bytecode only.
- HyProTech analysis lives in this conversation's review; the two jars were decompiled to inspect — re-decompile from the jar if needed (extracted temp dirs are not durable).

### 16.1 Energy capability API — `dev.zkiller.energystorage` (verified by decompile)
The published reference we mine for §7. Even though we ship our own, copy its **shape**:
- **`IEnergyStorage`** (interface) methods: `getEnergyStored()`, `getMaxEnergyStored()`, `getMaxReceive()`, `getMaxExtract()`, `canReceive()`, `canExtract()`, `receiveEnergy(long, boolean simulate)`, `receiveEnergyInternal(long, boolean)`, `extractEnergy(long, boolean)`, `extractEnergyInternal(long, boolean)`, `setEnergyStored(long)`, `setCapacityStored(long)`, `addEnergy(long)`, `getFillRatio()→float`, `isFull()`, `isEmpty()`. **All amounts `long`.**
- **External vs internal:** `receiveEnergy`/`extractEnergy` clamp to `maxReceive`/`maxExtract` (network transfer); the `*Internal` variants ignore those caps (self fill/drain). **Adopt this split** — it's the §7 win our current `EnergyHandler` lacks.
- **`AbstractEnergyStorage`** (base impl): fields `energyStored, maxEnergy, maxReceive, maxExtract` (long); ctor validates `maxEnergy>0`, clamps; `copyFrom(other)` for clone. Sensible default ctor `(0, 10000, 1000, 1000)`.
- **Component registration pattern:** `EnergyStorageBlockComponent implements Component<ChunkStore>` and `EnergyStorageEntityComponent implements Component<EntityStore>` — same capability on **both** block and entity stores. Registered in `setup()` via `getChunkStoreRegistry().registerComponent(Class, "EnergyStorageComponent", CODEC)` and `getEntityStoreRegistry().registerComponent(...)`. Static `getComponentType()` resolves through the plugin singleton.
- **Codec pattern:** `BuilderCodec.builder(...).append(new KeyedCodec("EnergyStored", Codec.LONG), setter, getter).addValidator(Validators.greaterThanOrEqual(0L)).documentation("Current stored energy").add()...` — **validators + `.documentation()` on every field** (feeds schema generation). Mirror this in our `EnergyBuffer` codec.
- **Maps onto our code:** our `api/energy/EnergyHandler` + `impl/block/EnergyBuffer` are now `long`-based ✅; still to add — the internal/external transfer split, per-buffer `maxReceive`/`maxExtract` caps, and the `getFillRatio`/`isFull`/`isEmpty` ratio methods.

### 16.2 Network discovery & the transfer-API bridge — HyProTech
- **Cable BFS flood-fill:** `energy/EnergyNetworkSystem.java` `buildCableNetwork(...)` (~ln 595–739) and `item/ItemNetworkSystem.java` `buildCableNetwork(...)` (~ln 407–467). Good reference for the **graph walk**; **do not copy the per-tick rebuild** driving it — we cache (§13.6).
- **Visited/dedup:** per-world `CableState.visitedByChunk : Long2ObjectMap<IntOpenHashSet>`, cleared each tick. We replace "clear each tick" with "invalidate on structural change."
- **Transfer-API port (bundled):** `com.shailist.hytale.api.transfer.v1.*` — `Storage<T>`, `StorageView`, `base/{SingleSlotStorage, CombinedSlottedStorage, Insertion/ExtractionOnlyStorage, FilteringStorage, ResourceAmount}`, `transaction/{Transaction, TransactionContext}`, `transaction/base/SnapshotParticipant`. This is a Fabric-Transfer-API port. Useful if we ever want **transactional simulate/commit with rollback** across multi-block moves; our current `simulate→commit` in `TransportEngine` is the lightweight version of the same idea.
- **Capability bridge:** `EnergyNodeStorage extends SnapshotParticipant<Integer> implements EnergyStorage, EnergyTransferer` — wraps a block component as a transfer-API storage; `getTransferRate()` returns the node's max transfer. `EnergyStorageLookup.find(world,x,y,z)` / `.register(...)` and `EnergyStorageUtil.move(src,dst,...)` are the interop entry points.
- **Tier config shape:** `energy/{Cable,Solar,Wind,Battery}UpgradeConfig.java` — static per-tier capacity/throughput tables. Pattern reference for our pipe tiers.

### 16.3 ECS ticking & events — Hytale core (our invalidation triggers)
- **Ticking system:** `extends EntityTickingSystem<ChunkStore>` with `getQuery()` (a `ComponentType` or `Archetype.of(...)`), `isParallel(...)` (return `false` when touching neighbor components — transport does), and `tick(dt, index, ArchetypeChunk, Store, CommandBuffer)`. Our `impl/block/MachineTickSystem` already follows this; the network distribution pass (§13.7) becomes a system of this shape, querying network-owning components.
- **Block events (network invalidation):** `extends EntityEventSystem<EntityStore, PlaceBlockEvent>` / `…BreakBlockEvent` (`com.hypixel.hytale.server.core.event.events.ecs.*`); `event.getTargetBlock() → Vector3i`. **These are exactly the place/break hooks §13.6 and §14.3 need** to rebuild a network on structural change. Seen in `org.pfc.redstone.RedstonePlaceEventSystem` / `RedstoneBreakEventSystem` (`getQuery()` = `Archetype.empty()` for a global handler).
- **Component/system registration:** `getChunkStoreRegistry().registerComponent(Class, "Name", CODEC)` → `ComponentType`; `getChunkStoreRegistry().registerSystem(system)`; `getEntityStoreRegistry()` for entity-store equivalents; `getEventRegistry().register(Event.class, this::handler)` for non-ECS events; `getCommandRegistry().registerCommand(...)`. Plugin lifecycle: `extends JavaPlugin`, override `setup()` / `start()` / `shutdown()`.
- **Soft deps:** `PluginManager.get().getPlugin(PluginIdentifier.fromString("Group:Name"))` — for optional integration with other Hytale mods.

### 16.4 World / block / chunk access — our `MachineTickSystem` + Redstone jar
- **Block entity / component at a position:** `BlockModule.getBlockEntity(world, x, y, z) → Ref<ChunkStore>` (null for unloaded/no-entity); then `store.getComponent(ref, type)`. (Our neighbor lookup.)
- **Position from a ticked block:** `BlockModule.BlockStateInfo` (`getChunkRef()`, `getIndex()`) + `BlockChunk` (`getX()`, `getZ()` chunk coords) + `ChunkUtil.{x,y,z}FromBlockInColumn(blockIndex)`; world coord = `(chunkX<<5) | localX` etc. (32-wide columns). Verified in our `MachineTickSystem.tick`.
- **World thread marshaling:** `World.execute(Runnable)` — **do all off-thread computation, then funnel every block mutation back through this.** Mandatory (Redstone jar pattern).
- **Block read/visual state:** `World.getBlockType(x,y,z) → BlockType` (nullable); `World.setBlockInteractionState(Vector3i, BlockType, String state)` — the mutation primitive for connection shapes / on-off visuals (pipes, wires, machine running states). `World.getChunkIfInMemory(long)` with `ChunkUtil.indexChunkFromBlock(x,z)`; `WorldChunk.getBlockType/getFiller(x,y,z)` (multiblock anchor) and `getRotationIndex(x,y,z)` (facing; Redstone maps `val*3%4`→direction).

### 16.5 Block interactions (wrench / right-click) & asset registration
- **Right-click handler:** `extends SimpleBlockInteraction` (`com.hypixel.hytale.server.core.modules.interaction.interaction.config.client`), override `interactWithBlock(World, CommandBuffer<EntityStore>, InteractionType, InteractionContext, ItemStack, Vector3i pos, CooldownHandler)` and `simulateInteractWithBlock(...)`. Use for the **wrench port-config** (§5.4) and circuitry block cycling (§14). Seen in `org.pfc.redstone.RepeaterInteraction`/`ComparatorInteraction` and HyProTech `interaction/CableSideToolInteraction`.
- **Registering interactions/assets:** handle `RegisterAssetStoreEvent` → `getAssetStore()`, register the interaction under a codec name. HyProTech `interaction/*` and `ui/*` show the open-GUI variants (`OpenPoweredBenchInteraction`, page registration).

### 16.6 Container / inventory access — HyProTech (items)
- **Find a container at a block:** `MachineItemAccess.getContainerState(world,x,y,z) → ItemContainerBlockState`; `state.getItemContainer() → ItemContainer` with `getCapacity()`, `getItemStack(slot)`.
- **Move items:** `ItemContainer.moveItemStackFromSlot(slot, count, dstContainer, …)` / `moveItemStackFromSlotToSlot(...)` returning a `MoveTransaction` with `.succeeded()`; `canAddItemStackToSlot(...)`. This is the discrete-item primitive for §13.4.
- **Anti-pattern to avoid:** HyProTech reaches into `ProcessingBenchState`'s private input/fuel/output containers by **cached reflection** (`item/ItemNetworkSystem.java`). Brittle (breaks on field rename). Prefer declaring machine slot layouts on **our own** components rather than reflecting into built-ins.

### 16.7 Generation & environment utilities — HyProTech
- **Sky access / daylight:** `energy/SunlightUtil.java` — column scan to sky, time-of-day window with sunset fade. Reusable for solar panels and any daylight-gated block.
- **Open-air check:** `energy/WindUtil.java` — N×N horizontal clearance + height check. Reusable pattern for placement validation (e.g., venting, RTG siting).

### 16.8 Performance & structural patterns — Redstone jar (`org.pfc.redstone`)
**Lift these:**
- **Batch under a time budget:** `ConcurrentLinkedQueue` work queue + `ConcurrentHashMap.newKeySet()` dedup set + fixed-rate drain capped by `TIME_BUDGET_NS` (5 ms), leftover rolls to next tick. Our §13.6 rebuild-storm backstop.
- **Cancellable scheduled outputs:** `Map<pos, ScheduledFuture<?>>`, cancel on re-trigger — for machine/gate delays and debounce.
- **Reflective identifier cache:** `Map<BlockType,String>` via `computeIfAbsent` — if we must reflect, cache it; better, tag with components.
- **Public SPI:** singleton facade + `ListenerRegistry` + `EventDispatcher` firing typed events, with `WeakReference` listeners that self-clean. Model for letting add-on mods hook our grid/circuitry.

**Avoid (the jar's mistakes):**
- No cached network — **re-flood-fills on every event** (O(network) per change). We cache + event-invalidate (§13.6).
- **`"x,y,z"` string keys** in hot BFS loops (constant concat/split/parse). Use packed `long` block keys.
- **Block-id substring matching** for type dispatch (`id.contains("redstone_dust")`). Use ECS component tags + `ComponentType` queries.
- **No persistence** — heuristic 21×11×21 rescan around each player on connect. Our network/signal state lives in persisted components/chunk data.
- Three ad-hoc wall-clock executors → prefer one game-tick-driven manager, avoiding off-thread→`world.execute` round-trips where possible.

### 16.9 Our existing touchpoints (already in-tree)
- `impl/block/MachineTickSystem` — `EntityTickingSystem<ChunkStore>`, neighbor resolution, position decode (the §16.3/§16.4 patterns, live).
- `impl/block/TransportEngine` — `simulate→commit` push (the lightweight transactional move; evolves into the §13.7 network distributor).
- `api/energy/EnergyHandler` + `impl/block/EnergyBuffer` — `long` migration ✅ done; internal/external + rate caps + ratio helpers still pending (§16.1).
- `api/io/{PortChannel, PortDirection}` + `impl/block/{Port, PortConfig, TransferNode, ResourceBuffer, ChanneledResource}` — the port/buffer model §13 builds the network layer on top of. Note `PortChannel` will gain `NEUTRON`.

---
