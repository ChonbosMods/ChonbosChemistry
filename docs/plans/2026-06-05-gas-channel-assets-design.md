# GAS Pipe Channel — Design

*Status: validated and implemented 2026-06-05 (branch `feat/gas-channel-assets`). Brings the GAS channel live by cloning the in-game-verified FLUID pattern. Testing phase dropped by user decision: without final textures this is literally a copy of the fluid system, and the channel-generic network code is already covered.*

## Decision

**Zero Java changes.** `PortChannel.GAS`, the type-lock (`FLUID || GAS` in `NetworkTransfer`), balancing, endpoints, and `PipeTiers` are all channel-generic and already tested. The whole feature is asset JSON against the placeholder `GasTube_*` models (27 shapes, verified identical to the `FluidPipe_*` set), so the final gas art drops in by replacing `gastube_off/on.png` / the `.blockymodel` files in place: exactly like the fluid pipe.

## Assets

| File | Source clone | Transform |
|---|---|---|
| `CC_GasConnectedBlockTemplate.json` | fluid template (3,499 lines, incl. `_on` twins) | `CC_FluidFace` → `CC_GasFace` (replaces the stale pre-twin 2,655-line version) |
| `CC_GasMachineTemplate.json` | fluid machine template | same face-tag swap |
| `CC_GasPipe.json` | `CC_FluidPipe.json` (53 states / 54-key pattern map) | models → `GasTube_*`, textures → `gastube_*`, template ref, `PipeNode` channel `gas` |
| `CC_GasSource.json` | `CC_FluidSource.json` | channel `gas`, emits `element:hydrogen` |
| `CC_GasSink.json` / `CC_GasTank.json` | fluid counterparts | channel `gas`, `CC_GasMachineTemplate` |
| `CC_GasSourceHydrogen.json` | `CC_FluidSourceBromine.json` | `element:hydrogen`, color `#BFE3FF` (dataset) |
| `CC_GasSourceCO2.json` | `CC_FluidSourceEthanol.json` | `compound:carbon_dioxide`, color `#ACDDD5` (dataset) |

Test gases (user decision): **hydrogen + CO₂**: one element, one compound (mirrors bromine/ethanol on fluid), and hydrogen is on the §11.4 dense-storage roster so it gets reused later.

## In-game verification checklist

1. Gas pipes connect/stub toward gas machines; topologies render (placeholder art).
2. Hydrogen line type-locks: rejects CO₂ until drained; sink drains, tank fills.
3. Panel reads "Gas Pipe Network" with live gauges; tank carry (BlockHolder) preserves contents + lock.
4. Pipes light their `_on` states while the network holds/moves gas.
