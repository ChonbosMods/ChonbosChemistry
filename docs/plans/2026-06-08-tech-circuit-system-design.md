> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# Chonbo's Tech — Circuit Logic System

Redstone-equivalent logic layer, re-themed as electrical engineering. Runs in
parallel to the energy pipe network (Mekanism-style energy→machine transport);
the two networks are kept domain-separate by design.

---

## Governing standards

- **Digital signal.** Wires carry on/off only. Analog values exist *only* at the
  machine boundary and are converted to digital by the comparator. No analog
  0–15 strength model.
- **No signal decay.** Wires do not lose their logic level over distance. (Decay
  is a Minecraft quirk, not EE-real; removing it also removes the entire
  repeater-as-booster role.)
- **CMOS energy model.** A gate draws a small amount of energy *only when it
  switches*. Static / held inputs draw nothing. Charging is **input-edge**: a
  gate pays when one of its *inputs* transitions, regardless of whether its
  output changes — this matches CMOS (you charge the input transistor gates
  either way) and can't be gamed by arranging stable outputs.
  - Physical basis: CMOS dynamic power dissipation, P ∝ C·V²·f. Power scales
    with switching frequency; idle logic is ~free.
- **Single power source per circuit.** One connected circuit needs one power
  feed. Energy flows through the shared rail to wherever switching happens — no
  per-gate power hookup.
- **Tie-high = tap the rail.** A constant logic-1 input is just a wire from the
  powered rail. Tie-low is ground/unconnected. No dedicated constant-source
  block (see Closed Decisions). A tie-high input never transitions, so under
  input-edge charging it draws nothing — consistent, not a loophole.

---

## Component set

### Power
- **Power feed** — connects the energy grid to the circuit rail. One per
  isolated circuit. Tie-high is achieved by tapping this rail.
- **Energy storage block** — lives on the energy pipe network. Has a charge slot
  that recharges spent batteries from the grid.
- **Battery (item)** — portable finite charge source for off-grid / isolated
  circuits (doorbell, standalone trap, sensor+lamp with no grid run). Recharges
  in the energy storage block. Energy isn't matter, so an energy-carrying solid
  item is fine under the matter-state split.
- **Capacitor** — *one* component, role depends on the network it's wired into:
  - Energy domain: small fast-charge/fast-discharge buffer (the rate-distinct
    counterpart to the slow, large battery).
  - Circuit domain: rail decoupling/smoothing (local charge reserve when many
    gates switch at once) + edge/pulse-forming. Edge-detection is its
    non-redundant logic job — the one timing role delay/oscillator/latch don't
    cover. Delay and oscillator are the pre-packaged RC convenience blocks; the
    capacitor is the raw element.

### Inputs
- **Lever** — toggle / SPST switch.
- **Button** — momentary switch.
- **Pressure plate** — entity sensor.

### Logic
- **Inverter** — 1-in/1-out NOT. Its own block: cheapest and most-used element
  (the "redstone torch" of the system). Split from the gate block by arity:
  1→1 is the inverter, 2→1 is the gate.
- **Gate block** — 2-in/1-out. Gate type toggled on the block itself
  (AND / OR / XOR / NAND / NOR / XNOR — no chips). Orientation defines input vs.
  output faces. Multi-input (3+) is built by **chaining** 2-in gates (real gates
  ship in fixed fan-in).
- **Comparator** — analog→digital. Reads a machine's analog level against a
  setpoint you dial in; outputs a clean on/off. Confines analog to the sensor
  boundary.

### Timing & memory
- **Delay** — one-way, adjustable lag. (Re-themed repeater minus the boost,
  which is unneeded with decay gone.)
- **Oscillator** — free-running pulse generator / clock.
- **D latch (flip-flop)** — one stored bit. Data + clock inputs; no
  illegal-state footgun. All other sequential logic builds from it.

### Output
- **Lamp** — indicator / load.
- **Machine I/O bridge** — the link between circuit and machine: reads machine
  state out as analog (into a comparator) and writes digital control in
  (run/halt, valve, overclock). The circuit layer's reason to exist in an
  automation mod.

### Wiring
- **Electrical wiring** — face-attached, digital, no decay. Colored insulated
  runs let two or more paths share a block without mixing. Block-based routing
  is an *emergent property* of face attachment, not a separate component (see
  Closed Decisions).

---

## Worked example — automated crafting

Mental model: build a crafter, give it power, walk away. Baseline automation
must not require a logic build.

- **Baseline:** the crafter runs on its own when powered and fed. No circuit
  needed to make it go.
- **Throughput = power fed.** The crafter has a power input; how much power you
  feed sets how fast it crafts (starve → slow, pour → fast, up to a cap). This
  is the overclock mechanic and the energy sink in one knob, with an instantly
  legible model: more power in, more work out. Lives in Tech's machine layer,
  separate from the circuit layer's per-switch draw.
- **Optional circuit control on top:** when a player *wants* conditional
  behavior, they add logic — e.g. comparator reads an output-buffer fill →
  inverter → gate the crafter's enable line. This is depth available on demand,
  not a requirement for basic automation.

The two energy knobs stay clean and separate:
1. Circuit layer — CMOS per-switch draw on the shared rail (its character).
2. Machine layer — crafter's throughput-for-power curve (its overclock).

---

## Closed decisions (reasoning preserved)

- **Analog 0–15 model — rejected.** Digital makes the CMOS energy model honest
  (a clean "cost per switch" only exists for on/off transitions). Analog kept
  only at the machine boundary via the comparator. Bonus: rehabilitates the
  comparator as a real analog→digital threshold device instead of Minecraft's
  compare/subtract/container-read muddle.
- **Signal decay — removed.** Minecraft quirk, not EE-real; eliminates the
  repeater-boost role and a class of fiddliness.
- **Loadable gate chips — rejected.** Gate type is toggled on the gate block
  instead. (Earlier chip proposal is superseded; with type-on-block there's no
  asset-cost argument for chips.)
- **Electric block (constant logic-high source) — removed.** A free,
  forever-emitting source sidesteps the per-switch CMOS economy (free
  oscillation/logic). Its tie-high role is replaced by tapping the powered rail,
  which is already paid for via the power feed.
- **Redstone block as constant source — rejected.** Same reason: constant free
  energy breaks the switching-cost economy.
- **Energy conduit block — rejected.** The energy pipes are already blocks; a
  block-form "alternative" is a reskin with no new capability.
- **Wire/signal conduit block — rejected.** Wiring already attaches to any face
  of any block, so any placed block is a potential hidden wire carrier.
  Block-based routing is an emergent consequence of face-attached wiring; a
  conduit would solve a problem the wiring model already solved.
- **Capacitor duplication — rejected.** Energy-storage cap and circuit-timing
  cap are the *same* component (holds a little charge, moves it fast); role
  follows the network it's wired into. Avoids the ChemLib/Mekanism-style
  dead-end of one name meaning two things.

---

## Deferred

- **Pistons** — shelved to a future Tech version (per existing plan).
