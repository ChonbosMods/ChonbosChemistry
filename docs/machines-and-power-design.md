# Hytale Chemistry Mod — Design Document

*Status: working draft. Captures locked-in decisions on architecture, rules, constraints, and interactions. Numbers marked `[TUNE]` are placeholders for balancing.*

---

## 0. Integration Status (2026-06-02)

**This is not a separate downstream mod.** The seven machines, the energy standard, the neutron economy, and the heat/cooling loop are **folded directly into ChonbosChemistry** and ship in the single player install. Rationale: anyone who installs the foundation gets radioactive elements and reactive compounds; leaving them with no machines to transmute, contain, or power anything would be a broken half-feature, exactly the way radiation-without-shielding would be. This extends the foundation doc's §2.2 "hazard and counterplay are one mechanic" rule to "matter and the machines that act on it are one mechanic."

This **supersedes the foundation doc's §10 family table**, which had pushed Processing and Nuclear engineering into separate downstream layers. They are now core. The "family on top" still exists for further extension (medicine, hazards/fallout, additional reaction sets), each depending on the foundation.

Boundary resolutions tracked in the brainstorm (see also foundation doc):
- **Energy standard** → contract in `api.energy`, values in `impl`. *(pending confirmation)*
- **Phase Changer (§11) vs. foundation §6.2 phase-transition machines** → reconcile to one machine. *(pending)*
- **Decay model** → owned by foundation hooks; Decay Vault (§3) *consumes* them. *(pending)*

---

## 1. Scope & Pillars

A chemistry + nuclear physics mod built on a comprehensive real-world dataset (all elements, all isotopes, 150+ compounds). Two transformation layers:

- **Chemistry layer** — operates on *electrons and bonds*. Combines/breaks/converts compounds. Never touches the nucleus, so it can never change an element or its isotope. Cheaper, earlier-game.
- **Nuclear layer** — operates on *the nucleus*. Changes protons or neutrons (transmutation, activation, decay, fission). High-energy, late-game, irreversible by ordinary chemistry.

The **Reactor** bridges the two: it is the sole source of free neutrons and the anchor of the power economy.

Design philosophy: **limitations by default, automation if you earn it.** Adjacency does the work; cables and pipes are optional convenience, never required to play.

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

| Phase | Storage block | Transport ("cable") | Manual move |
|---|---|---|---|
| Solids / items | Chest (already in game) | Item transporter | carry the stack |
| Fluids | Fluid tank | Fluid pipe | **pick up the tank** |
| Gases | Gas tank | Gas tube | **pick up the tank** |
| Power | (in-machine / cable) | Power cable | — |
| **Neutron flux** | internal buffer only | **neutron guide** (short, lossy) | — |

### 5.2 Tanks
- **One substance type per tank**, locked until emptied. A water tank rejects oxygen. This forces dedicated, labeled tank farms (intended).
- Fixed **capacity** per tank.
- **No pressurized-gas simulation.** A gas is mechanically identical to a fluid — a number up to a capacity. "Pressurized" is flavor only.
- **Three tiers** (e.g., Basic / Advanced / Elite). **Tier affects storage capacity only** — no other differences. **Tanks are not stackable** in any tier. Higher tiers gated behind the mod's own refined materials, looping chemistry back into infrastructure.
- **Tanks relocate with contents preserved.** Picking up and moving a full tank *is* the manual transport method — there is no separate portable-container item (see Section 5.6).

### 5.3 The four cable types
Item transporter, fluid pipe, gas tube, and power cable. All four share one behavior:
- **Each cable stores a buffered amount internally** (it is not instantaneous teleport-on-a-wire). Throughput and internal buffer scale with cable tier.
- Cables are **optional** — adjacency already connects a tank to a machine.

### 5.4 Configurable ports
Machines are large enough that **a single side can carry two or more ports**, so configuration is per-**port**, not per-face: each port on a machine's surface is individually assigned a **phase** (item / fluid / gas / power) and a **direction** (input / output / closed). Tanks, being simpler, configure per face (input / output / closed). Adjacency is the connection — a tank output touching a matching machine input just flows; cables extend this beyond adjacency.

Ports ship with **sensible defaults most players never touch**, and can be reconfigured two ways (Mekanism does both, and Hytale workbench GUIs make the in-menu version cheap): with a **wrench** on the block face, and/or via the **machine GUI**. Defaults-with-optional-config keeps the HCI cost near zero for casual players while leaving full control available.

> Note: because sides are multi-port and freely assignable, **face scarcity is a non-issue** — this is what let us drop hand-held canisters and any in-GUI injection slot entirely (see Section 5.6).

### 5.5 Machine internal buffers
Every machine holds small per-phase internal buffers (a few operations' worth), shown as gauges in the GUI. Adjacent tanks/cables top these up between cycles. This smooths supply and defines run-dry behavior (Section 8).

### 5.6 No hand-held canisters (decided)
There is **no separate canister/portable-container item class**, and **no in-GUI fill/empty injection slot**. Fluid and gas movement is: adjacent tank, cable, or carry the whole tank (contents preserved).

Rationale — both surviving justifications for a canister were eliminated by other decisions:
- *Face scarcity* — removed, because multi-port sides give machines abundant I/O surface (Section 5.4), so no GUI injection slot is needed to compensate.
- *Stacking for hauling* — removed, because tanks are not stackable in any tier, so a small tank offers nothing a canister would.

A canister would therefore be a redundant method for a task adjacency/tank-carry already covers, adding a "which method?" tax (Hick's Law) and GUI clutter for no new capability. One mental model: **tanks, in sizes.**

---

## 6. Neutron Flux Rules

- Neutrons are a **separate currency**, used only by nuclear machines. **Not** tankable and **not** carried in normal pipes.
- Default propagation is **adjacency only** — the Reactor must physically touch the Irradiator/Transmuter, forcing a tight, hot, shielded core (this is realistic: free neutrons are absorbed fast and travel short distances).
- **Neutron guide**: an optional short-range conduit that extends flux a few blocks at an **efficiency loss**. Provides build customization without becoming a full pipe network. The strain of clustering remains.
- Sources of free neutrons: the **Reactor** (fission, bulk) and rare **spontaneous-fission isotopes** placed in a Decay Vault (trickle, high-tier).

---

## 7. Energy Standard

The mod defines its own Hytale energy standard (no FE-equivalent exists yet). Design goal: **maximize third-party adoption** so it becomes *the* standard.

- Minimal capability interface any block can implement in a few lines: `receiveEnergy`, `extractEnergy`, `getStored`, `getMaxStored`.
- **Lossless** transfer by default (at most one optional distance-loss toggle).
- **Push-based** distribution.
- Cable tiers differ **only in throughput** (and internal buffer). **No voltage tiers, no burn-your-machine penalty** — that complexity kills standards.
- Power flows both ways: Reactor exports; Decay Vaults and decay generators (RTGs) trickle power back.

---

## 8. Interaction & Constraint Rules

These cross-cutting rules define how the pieces behave together.

1. **Type-lock enforcement.** A tank accepts only its locked substance. A machine output that targets a full or wrong-substance tank **stalls** — it pauses and stages the product in its internal buffer. It never voids output (same as a full chest stopping a furnace). Predictable, never punishing.
2. **Run-dry behavior.** If an input buffer empties mid-cycle, the *current* operation pauses cleanly and resumes when supply returns. No corruption, no wasted inputs.
3. **Layer boundary.** Chemistry machines reject any operation that would change protons/neutrons; nuclear machines reject any pure formula edit. Enforced at the data-model level.
4. **Decay is always-on.** An unstable item's half-life timer ticks even in inventory — leave a reactive isotope too long and it silently becomes its daughter (use-it-or-lose-it). The Decay Vault is how you *control and harvest* that, not a prerequisite for decay to occur.
5. **Radiation & containment.** Each decay tick emits radiation. A vault's containment rating absorbs up to `[TUNE]` per tick; any excess **leaks** into the world and triggers the existing radiation hazard. Better shielding absorbs more but costs more. A breached/neglected vault dumps its accumulated radiation at once.
6. **Decay yields matter, not just energy.** Primary capturable output of any decay is the **daughter element** (decay = transmutation by patience). Alpha emitters additionally yield **helium gas**. Spontaneous-fission isotopes yield **free neutrons**.
7. **Cable buffering.** A cable that fills (downstream blocked) stops pulling upstream rather than voiding. Internal buffer smooths bursts.
8. **Port conflicts.** Two output ports meeting do nothing; an input port only pulls from an adjacent output port (or cable). Closed ports never connect.
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

1. **Energy export interface.** Is the Reactor's exported power our own native type only, or do we also publish an adapter so host/other tech mods can consume it? *Affects cross-mod adoption.*
2. **Cable buffer sizing & throughput per tier** — `[TUNE]`.
3. **Neutron flux numbers** — reactor output, per-operation consumption, neutron-guide range and loss %, buffer sizes — `[TUNE]`.
4. **Containment math** — absorption per tier, leak threshold, breach-dump magnitude — `[TUNE]`.
5. **Coolant economy numbers** — coolant consumed per cycle per intensity tier, latent-heat → energy-cost scaling for the Phase Changer — `[TUNE]`.
6. **Dual-state roster** — confirm the final list of substances that get both gas and liquid forms (Section 11.4).

*Resolved:* hand-held canisters (dropped, Section 5.6); state-change approach (coolant-as-material, Section 11); phase config model (per-port, Section 5.4).

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
| **Neutron guide** | Short, lossy neutron conduit (Section 6). |
| **Decay generator (RTG)** | Passive trickle power from a decaying isotope; feeds power back to the grid (Section 7). |
