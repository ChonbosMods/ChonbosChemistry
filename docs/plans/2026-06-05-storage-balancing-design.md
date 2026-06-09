> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# Storage Balancing (battery↔battery / tank↔tank) — Design

*Status: validated and implemented 2026-06-05. Second transport quick-win (branch `chore/transport-quick-wins`). Closes the "battery↔battery balancing" deferred item from the H6/H8 transport work.*

## Decisions

- **Water-fill semantics (user decision).** Balancing is **capacity-blind**: every storage on a network converges to the same absolute level L; capacity matters only as a clamp. A 5000-cap and a 1000-cap battery fill at the same speed; when the 1000-cap clamps full, the 5000-cap absorbs its share. "The system only cares if there are sources and consumers", not sizes. Formally: targets are `min(cap_i, L)` where L is the floor level with `Σ min(cap_i, L) ≤ totalStored` (`WaterFill.targets`).
- **All fungible channels.** POWER batteries and FLUID/GAS tanks balance with one code path; the type-lock already guarantees a fluid/gas network carries one substance.
- **Leftover budget only.** Balancing runs as **Phase 3** of `NetworkTransfer.distribute`, bounded by `min(unspent pull budget, unspent deliver budget)`. Real sources and sinks always outrank balancing; an idle network balances at full throughput.

## Why it cannot churn

The naive alternative (always pull from storage into the buffer, let fair-split hand it back) oscillates forever: that is exactly the self-churn H6 FIX 2 eliminated. This design is convergent by construction:

1. Targets are a pure function of (total stored, capacities); internal moves never change the total, so targets are stable within a balancing episode.
2. Movement requires a donor ≥1 above target AND a recipient ≥1 below target (integer floor deadband; the sub-N remainder stays on donors).
3. The pull is capped at the total **deliverable** deficit, so balancing never strands resource in the net buffer for phase 2 to redistribute off-target next tick.
4. Recipients are filled fair-split **by deficit** (equal per-tick rates, rotation for the remainder), mirroring how charging feels.

## Implementation

- `WaterFill` (new, pure): integer water-fill targets. Ascending-capacity walk: clamp the smallest, re-split the rest.
- `StorageEndpoint` (new interface): the paired storage view: `stored()`, `capacity()`, `provider()`, `acceptor()`. Built by `EndpointAdapters.powerStorage`/`resourceStorage`; `NetworkEndpoints.collect` now emits it for BOTH-port neighbours (the same instance's halves feed the buffer-provider/acceptor lists).
- `NetworkTransfer.balance(...)`: the Phase 3 step. Type-lock aware; deficits are computed against the substance actually being balanced (the net lock, or the first donor's resource when the net is empty: `capacityFor(null)` on a holding tank is 0 and would otherwise stall after one step).
- `NetworkEndpoints.Endpoints` record gained `storages`; the `distribute(net, endpoints)` overload passes them, so the live `NetworkTickSystem` path balances with no further wiring.

## Testing

`WaterFillTest` (9 cases: clamping, order-independence, remainder, degenerate) and `NetworkBalancingTest` (7 cases: converge-and-stop fixpoint, capacity clamp, equal per-tick recipient fill, demand-starves-balancing priority both ways, type-locked fluid, degenerate sets) — all against real `EnergyBuffer`/`ResourceBuffer` endpoints, no mocks. Plus a `NetworkEndpointsTest` case for the paired-storage classification. In-game: two `CC_EnergyStorage` blocks on one cable run, one charged: watch them level out via the (now live) pipe panel.
