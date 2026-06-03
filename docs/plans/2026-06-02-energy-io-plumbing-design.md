# Energy + I/O Plumbing — Design (First Vertical Slice)

**Status:** ⚠️ **PARTIALLY SUPERSEDED (2026-06-03)** by the transport-network pivot. Originally design-validated (brainstorm 2026-06-02) and built ~80% as described. Kept as the historical record of the slice that was actually shipped.

> ### Pivot notice — read before relying on this doc
> A later analysis (HyProTech / `dev.zkiller.energystorage` / `org.pfc.redstone`) reversed the transport model. The new direction is in **`docs/machines-and-power-design.md` §13** and the implementation plan **`docs/plans/2026-06-03-transport-network-rework-plan.md`**.
>
> **Superseded by the pivot:**
> - **§5 transport** — adjacency push + "adjacency flows with zero cables" is **removed**. Transport now runs entirely on **cached pipe-networks** with a shared network buffer + max-min fair-split (design §13.2/§13.7). **Pipes are required** (design §5.4); no zero-cable adjacency.
> - **§5 cable buffering** — the per-cable bucket-brigade internal buffer that "crawls" is replaced by one fungible **network** buffer (= Σ pipe capacities); no per-hop latency.
> - **§3 `EnergyHandler`** — now `long` (not `int`) and gains internal/external paths + rate caps + ratio helpers (design §7/§16.1; int→long migration done).
> - **§8** adjacency port rules (two-outputs/input-pulls-from-adjacent) → re-expressed as provider/acceptor on a network.
> - **§10** the rig's "adjacency-with-zero-cables" property is retired; rigs now route through pipes.
>
> **Still valid:** the api/impl split (§3); the ECS block-entity + own `EntityTickingSystem<ChunkStore>` substrate and ticking decision (§2, §5 work-pass); codec persistence (§4); the GUI approach (§6); tank-carry-with-contents (§7); the chunk-unload/stall/run-dry rules (§8); and the **§2 Hytale API table** (still accurate ground truth, reused by the new plan's integration phase).

**Scope:** The shared block-infrastructure skeleton every machine/tank/cable plugs into: stateful ticking blocks, the energy standard, phased resource buffers, configurable ports, adjacency/cable transport, and a block-bound GUI. No chemistry recipes yet — this slice proves the architecture end-to-end with a trivial test rig.

Parent design: [`docs/machines-and-power-design.md`](../machines-and-power-design.md) (the seven machines, folded into ChonbosChemistry — see its §0). Foundation design: `~/Downloads/chemistry-foundation-design.md`.

---

## 1. Why this slice first

All seven machines, the tanks, and the cables share one substrate: a block that holds persistent per-instance state, ticks deterministically, reads its neighbors, and opens a GUI. Building that substrate once — correctly — de-risks everything after it. Nothing in the repo touches blocks yet (the foundation is data + registry only), so this is the project's first block code.

---

## 2. Hytale API foundations (verified against decompiled U5 server)

Every load-bearing capability was confirmed in `~/Development/Hytale/patcher/hytale-server/`:

| Need | API | Reference |
|---|---|---|
| Block entity (persistent per-block state) | ECS entity in `ChunkStore`, attached via `BlockType.blockEntity: Holder<ChunkStore>`; retrieved with `BlockModule.getBlockEntity(world,x,y,z)` | `BlockModule.java:199-214`, `BlockType.java:1593-1595` |
| Custom block-entity components (plugin-registered) | `PluginBase.getChunkStoreRegistry().registerComponent(...)` (public) | `PluginBase.java:205`, `ComponentRegistryProxy.java:94` |
| Custom ticking system over block entities | `getChunkStoreRegistry().registerSystem(new EntityTickingSystem<ChunkStore>...)` | precedent: `SpawningPlugin.java:266,373,385` |
| Deterministic, world-driven progress | furnace `ProcessingBenchState` accumulates `inputProgress += dt` (seconds) every server tick; processes with no player present | `ProcessingBenchState.java:242,413,423` |
| Neighbor access | `BlockAccessor.getBlock(x,y,z)`, cross-chunk automatic | `BlockAccessor.java:26-30` |
| Block → GUI | `OpenCustomUIInteraction` → `CustomPageSupplier` → `PageManager.openCustomPage`; page reads/writes block components | template: `PortalDevicePageSupplier.java` |
| Fluid/gas = a number | `ResourceQuantity` (`resourceId` + `int quantity`) | `ResourceQuantity.java:10-50` |
| Item container | `ItemContainer` interface | `ItemContainer.java` |

**Ticking decision (locked).** The furnace uses `BlockState`/`TickableBlockState`, which the engine marks **deprecated** in favor of the modern `blockEntity` Holder + arbitrary ECS components (Portals/Beds path). Since `SpawningPlugin` proves a plugin can register its *own* `ChunkStore` components **and** ticking systems, we take the **modern component path** with our own `EntityTickingSystem<ChunkStore>`, while adopting the furnace's *semantics*: float `dt`-seconds accumulation, completion when `progress > duration`, world-driven background processing. This also gives us control over pass ordering (§5). The doc's `"duration": 40` is interpreted as seconds.

---

## 3. Package layout (honors the locked api/impl split)

- **`api.energy`** — `EnergyHandler` contract only: `receiveEnergy`, `extractEnergy`, `getStored`, `getMaxStored`. Zero values. This is the published third-party energy standard (the FE-equivalent, per machines-doc §7).
- **`api.io`** — reuses the existing `api.substance` phase enum (solid/liquid/gas); adds `PortDirection` (input/output/closed) and a minimal `ResourceHandler` contract.
- **`impl.block`** — BlockType registration, the components below, the ticking + transport system, the GUI page, and break/place hooks.

Governing rule unchanged: **api defines, impl decides.** No concrete value or gameplay logic leaks into `api`.

---

## 4. Components (ECS, on the block entity, in `impl`)

| Component | Holds | Notes |
|---|---|---|
| `EnergyBuffer` | stored / maxStored | implements `api.energy.EnergyHandler` |
| `ResourceBuffer` | `ResourceQuantity` + capacity + type-lock flag | one per phase; tanks have one, machines a few small ones |
| `PortConfig` | per-port `{phase, direction}` (machines) or per-face (tanks) | sensible defaults; most players never touch |
| `WorkState` | progress (`float` seconds), active/stall/run-dry flags | stubbed this slice; test machine exercises it |

All persist automatically via Codec — the same Hytale `BuilderCodec`/`KeyedCodec` beans the foundation already uses. No bespoke save/load code.

---

## 5. Ticking & transport (one `EntityTickingSystem<ChunkStore>`)

Each server tick, over loaded block entities carrying our components, two passes:

1. **Transport pass.** For each output port/cable, read the 6 neighbors via `BlockAccessor`, resolve each neighbor's block entity, push energy/resources into adjacent matching inputs up to throughput. Push-based, lossless (machines-doc §7). Cables hold an internal buffer (no teleport-on-wire); a full downstream buffer stops the upstream pull (rule 7). Adjacency flows with **zero cables** — a tank face touching a machine face just transfers.
2. **Work pass.** Each machine tops buffers from internal stores, advances `WorkState.progress += dt` if inputs + energy present; otherwise stalls / run-dries cleanly.

Transport precedes work so a machine sees this tick's deliveries.

---

## 6. GUI (`CustomUIPage`, per `PortalDevicePageSupplier`)

A `UseBlock` interaction → `OpenCustomUIInteraction` → `CustomPageSupplier` that pulls the target block from `InteractionContext`, resolves the block entity, and builds a page rendering **energy + per-phase buffer gauges** and a **per-port phase/direction selector**. `handleDataEvent` writes port changes back to `PortConfig`. GUI-based config first; the wrench path (machines-doc §5.4) deferred.

---

## 7. Tank-carry-with-contents (machines-doc §5.2)

Breaking a block destroys its block entity, so on **break** we serialize `ResourceBuffer` into the dropped item's metadata (`ItemStack.withMetadata`); on **place** we rehydrate the new block entity from it. This *is* the manual transport method (no separate canister — machines-doc §5.6). Needs break/place interaction hooks + an item-metadata codec — a self-contained sub-step.

---

## 8. Edge cases

- **Chunk unload:** only loaded entities tick; state persists. Transport must **not force-load** neighbors — an unloaded neighbor is treated as blocked (prevents chunk-load cascades down a cable line).
- **Stall, never void** (rule 1): output to a full/wrong-locked tank pauses and stages product in-buffer. `receiveEnergy` returns accepted amount; remainder stays upstream (rule 7).
- **Run-dry retains progress** (rule 2): if inputs vanish mid-cycle, `progress` **freezes** (we deliberately diverge from the furnace, which resets) so it resumes cleanly.
- **Pass ordering:** transport before work; two outputs meeting do nothing; input only pulls from an adjacent output/cable (rule 8). Multi-sink fairness deferred (buffers absorb it).
- **Hot-reload safety:** register in `setup()`, clean up in `shutdown()`, no static state. Dev hot-reload is disabled under U5, so full `devServer` restarts are expected.

---

## 9. Testing

- **Unit (headless JUnit 5, TDD-first):** `EnergyBuffer` receive/extract/clamp; `ResourceBuffer` type-lock + capacity; `dt` progress accumulation/completion; push algorithm against a mock 6-neighbor grid. The foundation's harness already decodes codecs without a live server.
- **Integration (`devServer` smoke):** Creative Power Cell → cable → sink lights up; fill a tank, break it, replace it, confirm contents survive.

---

## 10. Test rig (this slice's deliverable blocks — no chemistry yet)

- **Creative Power Cell** — emits fixed energy/tick.
- **Power Cable** — buffered, tiered throughput.
- **Energy Sink** (lamp/logger) — drains, visibly confirms delivery.
- **Fluid Tank** + trivial **fill source** — exercises `ResourceBuffer`, type-lock, phased I/O, and carry-with-contents.

Proves energy push, adjacency-with-zero-cables, cable buffering, port config, GUI gauges, stall/run-dry — the whole skeleton — before any real machine.

---

## 11. Open items carried forward

- Multi-sink transport fairness (round-robin) — deferred; buffers absorb for now.
- Wrench-based port config — deferred to GUI-only first.
- Neutron flux — separate currency, adjacency-only; not in this slice (machines-doc §6).
- Numbers (`[TUNE]`): cable throughput/buffer sizes per tier, energy capacities, tick cadence (every-tick vs every-N) — set during implementation balancing.
