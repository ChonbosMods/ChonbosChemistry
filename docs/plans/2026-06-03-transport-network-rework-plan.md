# Transport Network Rework Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the adjacency push transport with **cached fungible pipe-networks** (energy / fluid / gas) that share one network buffer, distribute by max-min fair-split, and type-lock fluid/gas — and extend the energy API with internal/external transfer paths, per-buffer rate caps, and ratio helpers.

**Architecture:** Two layers, mirroring the prior slice. **(1) Pure logic (headless TDD):** a `Network` aggregate (membership + shared buffer = Σ pipe capacities + min-tier throughput + fluid/gas type-lock), a `FairSplitDistributor` (max-min allocation), a `NetworkTransfer` pass (pull providers → buffer → fair-split to acceptors), and an upgraded `EnergyHandler`/`EnergyBuffer`. All plain Java + `BuilderCodec`, tested against mock grids. **(2) Hytale integration (spike → implement → devServer):** a `PipeNode` ECS component on `ChunkStore`; a `NetworkManager` that BFS-discovers networks over connected pipes, **caches** them, and **invalidates on `PlaceBlockEvent`/`BreakBlockEvent`/`ChunkUnloadEvent`** (never per-tick rebuild); a ticking system that runs the distributor per cached network; pipe rig blocks; and a network-aware GUI gauge.

**Tech Stack:** Java 25 (SDKMAN `.sdkmanrc`), Hytale Server API (`ChunkStore` ECS, `EntityTickingSystem`, `EntityEventSystem`, `BuilderCodec`), JUnit 5, Gradle. Decompiled server ground-truth at `~/Development/Hytale/patcher/hytale-server/`; curated API at `~/Development/Hytale/Hytale-Server-Unpacked/HYTALE_CORE_API.md`.

**Governing design:** `docs/machines-and-power-design.md` §13 (transport architecture), §7 + §16.1 (energy API), §5.4 (pipes required), §8 (constraint rules). This plan implements the **transport core only**; items (§13.4), neutron pipes (§13.5), redstone (§14), and electrified benches (§15) are deferred to their own later plans (design §10).

---

## The pivot (read before starting)

The prior slice (`docs/plans/2026-06-03-energy-io-plumbing-status.md`) built adjacency push and is ~80% done. **Decision: pivot now, supersede.** Concretely:

**Keep (reused unchanged or lightly touched):**
- `api/energy/EnergyHandler` + `impl/block/EnergyBuffer` — already migrated to `long` ✅; this plan extends them (Task N1).
- `impl/block/ResourceBuffer` (type-locked, `int`), `ChanneledResource`, `Port`, `PortConfig`, `api/io/{PortChannel,PortDirection}`.
- `impl/block/{MachineBlockState,TankBlockState}` ECS components, registration in `ChonbosChemistry.setup()`.
- The ticking scaffold (`EntityTickingSystem<ChunkStore>`, block world-position decode via `BlockStateInfo`+`BlockChunk`+`ChunkUtil`) and `impl/block/ui/MachinePanelPage`.

**Retire (superseded by the network layer):**
- `impl/block/TransportEngine` adjacency push (`pushEnergy`/`pushResources`) → replaced by `NetworkTransfer` + the distributor.
- `impl/block/TransferNode` + `NeighborView` as the *transport contract* → the network layer addresses pipes/ports directly. (Keep the `BlockModule.getBlockEntity` neighbor-resolution *technique* — it moves into `NetworkManager`.)
- `src/test/java/.../CableHopTest` (tests the bucket-brigade per-cable buffer crawl, which §13.2 removes) → delete in Task H8.
- The per-tick neighbor push inside `MachineTickSystem` → replaced by the per-network distribution tick (Task H4).

**Fold in from the old plan:** B5 (tank carry-with-contents) → Task H6; B6 (remove TEMP debug logging) → Task H8.

**Minor decisions resolved by default (flag if you disagree):**
- **Resource amounts stay `int`** (fluid/gas quantities are small; only energy needed `long`). The network buffer reuses the existing buffer beans: a POWER network owns an `EnergyBuffer` (`long`, never type-locked); a FLUID/GAS network owns a `ResourceBuffer` (`int`, type-locked). This reuse is why N2 is small.
- **Pipe is its own ECS component** `PipeNode` (channel + tier), distinct from machine/tank — a pipe is not a machine.
- **Network objects are transient + cached**, keyed by a lexicographic-min anchor position; the **buffer contents persist** by distributing each pipe's share back into its `PipeNode` component on chunk-unload/save and re-pooling on load (Task H6). No per-tick persistence.
- **`PortChannel.NEUTRON` is NOT added in this plan** (neutron is a later plan); this plan touches POWER/FLUID/GAS only.

---

## Conventions (match the existing foundation exactly)

- **api/impl split:** `api` = contracts only, never imports `impl`. New transport classes live in `impl.block` (promotion to `api` deferred until third parties need it, same as today).
- **TDD, headless first:** every Phase-N task is red→green→commit with JUnit 5; no live server. `./gradlew test --rerun-tasks` (gradle caches aggressively).
- **Codecs:** `BuilderCodec.builder(...).append(new KeyedCodec<>("Key", Codec.X), setter, getter).add()...`; add `.addValidator(...)` + `.documentation(...)` per the zkiller pattern (design §16.1).
- **No translation keys** — UI text uses `Message.raw(...)` (`server.lang` is generated + gitignored).
- **Spike before integrating:** for every Phase-H task, read the real signature in `patcher/hytale-server/` before writing against it.
- **Commits:** frequent, one per task minimum; never push (user rule).
- **Branch:** do this in a dedicated worktree off the current branch (use `superpowers:using-git-worktrees`); the branch also carries the asset-generator work — do not disturb `impl/assetgen`, `impl/texture`.

---

## PHASE N — Pure logic (full TDD, headless)

### Task N1: `EnergyHandler` v2 — internal/external paths, rate caps, ratio helpers

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/api/energy/EnergyHandler.java`
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/EnergyBuffer.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/EnergyBufferTest.java`

**Step 1 — Write failing tests** for the new behavior (append to `EnergyBufferTest`):

```java
@Test
void externalReceiveRespectsMaxReceive() {
    EnergyBuffer b = EnergyBuffer.withCapacityAndRates(1000, 10, 10); // cap, maxReceive, maxExtract
    assertEquals(10, b.receiveEnergy(100, false), "external receive clamps to maxReceive");
    assertEquals(10, b.getStored());
}

@Test
void internalReceiveBypassesRateCap() {
    EnergyBuffer b = EnergyBuffer.withCapacityAndRates(1000, 10, 10);
    assertEquals(100, b.receiveEnergyInternal(100, false), "internal fill ignores maxReceive");
    assertEquals(100, b.getStored());
}

@Test
void externalExtractRespectsMaxExtract() {
    EnergyBuffer b = EnergyBuffer.withCapacityAndRates(1000, 1000, 5);
    b.receiveEnergyInternal(100, false);
    assertEquals(5, b.extractEnergy(100, false), "external extract clamps to maxExtract");
}

@Test
void ratioHelpers() {
    EnergyBuffer b = EnergyBuffer.withCapacityAndRates(100, 100, 100);
    assertTrue(b.isEmpty());
    b.receiveEnergyInternal(50, false);
    assertEquals(0.5f, b.getFillRatio(), 1e-6);
    b.receiveEnergyInternal(50, false);
    assertTrue(b.isFull());
}

@Test
void backCompatWithCapacityDefaultsToUncappedRates() {
    EnergyBuffer b = EnergyBuffer.withCapacity(100); // existing factory keeps working
    assertEquals(100, b.receiveEnergy(500, false), "no explicit cap → external == internal");
}
```

**Step 2 — Run, verify red:** `./gradlew test --rerun-tasks --tests EnergyBufferTest` → FAIL (methods undefined).

**Step 3 — Extend the interface** (`EnergyHandler.java`) with the new methods (keep existing four):

```java
/** Adds energy bypassing the external rate cap — for a block filling its OWN buffer. */
long receiveEnergyInternal(long amount, boolean simulate);

/** Removes energy bypassing the external rate cap — for a block draining its OWN buffer. */
long extractEnergyInternal(long amount, boolean simulate);

/** Max accepted per external {@link #receiveEnergy} call (0 = no external input). */
long getMaxReceive();

/** Max provided per external {@link #extractEnergy} call (0 = no external output). */
long getMaxExtract();

/** stored / max, in [0,1]; 0 when capacity is 0. */
float getFillRatio();

boolean isFull();

boolean isEmpty();
```

**Step 4 — Implement in `EnergyBuffer`:** add `long maxReceive, maxExtract` fields (codec keys `"MaxReceive"`/`"MaxExtract"`, `Codec.LONG`, `Validators.greaterThanOrEqual(0L)`, `.documentation(...)`); a `withCapacityAndRates(long capacity, long maxReceive, long maxExtract)` factory; keep `withCapacity(capacity)` defaulting `maxReceive=maxExtract=Long.MAX_VALUE` (uncapped, back-compat). `receiveEnergy` clamps to `maxReceive` then delegates to the internal math; `receiveEnergyInternal` skips the clamp. Symmetric for extract. `getFillRatio`/`isFull`/`isEmpty` per §16.1.

**Step 5 — Run, verify green:** `./gradlew test --rerun-tasks --tests EnergyBufferTest` → PASS (and the existing energy tests still pass).

**Step 6 — Commit:** `feat(energy): internal/external transfer paths, rate caps, ratio helpers (design §16.1)`

---

### Task N2: `Network` aggregate (membership, shared buffer, type-lock, throughput)

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/net/Network.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/net/NetworkTest.java`

**Design:** a `Network` is one channel's virtual tank/battery (§13.2). It owns a **buffer bean by channel** — `EnergyBuffer` for POWER (no lock), `ResourceBuffer` for FLUID/GAS (type-locked) — and aggregates from its member pipes: `capacity = Σ member capacity`, `throughput = min member throughput` (bottleneck). Membership is a list of opaque pipe keys (`long` packed positions; the pure test uses ints). Pure data + `recomputeAggregates()`; no ECS.

**Steps (TDD):**
1. Failing tests: a POWER network with two pipes (cap 100 + 25) reports capacity 125 and throughput = min tier; `insert`/`extract` route to the buffer; a FLUID network locked to `"oxygen"` rejects `"helium"` until drained (delegates to `ResourceBuffer`); empty network insert/extract are no-ops; `recomputeAggregates` updates after `addMember`/`removeMember`.
2. Run → red.
3. Implement `Network` with `PortChannel channel`, member list, the channel-appropriate buffer, `addMember(long capacity, int throughput)`, `recomputeAggregates()`, `insert(String resourceId, long amt, boolean sim)`, `extract(long amt, boolean sim)`, `stored()`, `capacity()`, `throughput()`, `fillRatio()`. For POWER, `insert` ignores `resourceId`.
4. Run → green.
5. Commit: `feat(net): Network aggregate buffer + type-lock + bottleneck throughput (design §13.2/§13.3)`

---

### Task N3: `FairSplitDistributor` — max-min allocation (§13.7)

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/net/FairSplitDistributor.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/net/FairSplitDistributorTest.java`

**Design:** pure function. Input: `long amount`, `long[] remainingCapacities`. Output: `long[] allocation` summing to `min(amount, Σcap)`, where every acceptor gets an equal share or as much as it can hold, leftover from near-full acceptors redistributed (design §13.7 steps 1–5). Deterministic, order-independent.

**Steps (TDD):** tests for — even split (90 over 3 equal → 30/30/30); near-full redistribution (90 over caps [10,1000,1000] → 10/40/40); zero acceptors → empty; single acceptor; amount exceeds total capacity (allocate all caps); amount 0. Then implement the iterative remainder loop. Run red→green. Commit: `feat(net): max-min fair-split distributor (design §13.7)`.

---

### Task N4: `NetworkTransfer` — the per-network distribution pass

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/net/NetworkTransfer.java`
- Create (test seams): small interfaces `Provider` / `Acceptor` in the same package (a provider exposes "extract up to X of resource"; an acceptor exposes "remaining capacity" + "insert up to X").
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/net/NetworkTransferTest.java`

**Design:** `distribute(Network net, List<Provider> providers, List<Acceptor> acceptors)` (§13.2 ordering): (1) pull from each provider into `net` (respecting type-lock + remaining network capacity + per-provider rate), (2) compute fair-split (N3) over acceptors' remaining capacity bounded by what the network holds + per-acceptor rate, (3) commit inserts, decrementing the network buffer. Simulate-then-commit at each boundary (lossless).

**Steps (TDD):** mock providers/acceptors — single provider → 2 equal acceptors splits evenly; near-full acceptor gets capped, remainder to the other; type-locked fluid network rejects a wrong-resource provider; full network applies backpressure (providers stop). Implement using N2 + N3. Run red→green. Commit: `feat(net): provider→buffer→fair-split acceptor pass (design §13.2)`.

---

### Task N5: type-lock + backpressure integration test (pure)

**Files:**
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/net/NetworkScenarioTest.java`

A higher-level pure scenario over N2–N4 asserting the §8 rules: a FLUID network type-locks to the first inserted substance and rejects others until drained (rule 1); a full network stops upstream pulls without voiding (rule 7, backpressure); multi-source fills one buffer and balances automatically (§13.2). No new production code expected — if a test fails, fix the relevant N2–N4 class. Commit: `test(net): type-lock + backpressure + multi-source scenarios (design §8)`.

---

## PHASE H — Hytale integration (spike → implement → devServer smoke)

> Each task: **spike** the real signatures in `patcher/hytale-server/`, **implement**, **verify** boot-clean via `./gradlew devServer` (user runs in-game). Reference `@hytale-events`, `@hytale-ecs`, `@hytale-codec-config`, `@hytale-ui` skills as needed.

### Phase H reconciliation with the pipe-model design (2026-06-03)
This phase now integrates the user's parallel `docs/plans/2026-06-03-transmitter-pipe-models-design.md` (10 topology `.blockymodel` files already shipped under `src/main/resources/Common/Blocks/ChonbosMods/Pipes/`). Decisions:
- **Pipes carry a dedicated `PipeNode` component** (DECIDED), not `MachineBlockState`. `PipeNode` = `{channel, tier, persisted bufferShare}`; ports live only on machines/tanks. The pipe-model doc's "MachineBlockState" wording referred to today's placeholder; `PipeNode` inherits onto the 9 shape `State.Definitions` exactly the same way (any `BlockEntity.Components` does).
- **Visual auto-connect is engine-driven** via `BlockType.ConnectedBlockRuleSet` (`CustomTemplate` + `TemplateShapeAssetId` + per-family `FaceTags`), synced server→client. **Phase H writes NO manual pipe visual-state code** — the planned `setBlockInteractionState` for pipe connection shapes is dropped. The logical `NetworkManager` BFS enforces same-channel connection independently, matching the FaceTag visual layer.
- **Four families map 1:1 to `PortChannel {POWER, FLUID, GAS, ITEM}`** with per-family FaceTags (`CC_PowerFace`/`CC_FluidFace`/`CC_GasFace`/`CC_ItemFace`) so a family connects only to its own kind. This plan wires POWER/FLUID/GAS; ITEM is the later item-pipe plan.
- **H5 now owns the block-def integration** the pipe-model doc delegated to "the network-logic instance": upgrade `CC_PowerCable` to `DrawType: Model` + `State.Definitions` (the 10 shapes) + `ConnectedBlockRuleSet`, swap its placeholder `MachineBlockState` → `PipeNode`, and author the 4 `…ConnectedBlockTemplate.json` assets (one per family FaceTag). Create the FLUID/GAS pipe base blocks likewise.
- **`PipeNode.bufferShare` is `long`** for all channels (energy needs `long`; fluid/gas amounts fit), with a `resourceId` for the fluid/gas type-lock on the share. Keeps one component shape across channels (resolved from H6's persistence need).

### Task H1: `PipeNode` ECS component
- Create `impl/block/net/PipeNode implements Component<ChunkStore>`: fields `PortChannel channel`, `int tier`, plus the persisted **buffer share** (`long energyShare` OR a `ResourceBuffer` for fluid/gas — see H6) and a transient cached network handle. `BuilderCodec` + `clone()`. Register in `ChonbosChemistry.setup()` via `getChunkStoreRegistry().registerComponent(PipeNode.class, "PipeNode", PipeNode.CODEC)`; expose the `ComponentType`.
- **Spike:** mirror the existing `MachineBlockState` registration.
- **Verify:** boot-clean.

### Task H2: `NetworkManager` — BFS discovery + cache
- Create `impl/block/net/NetworkManager`: given a seed pipe position, BFS over face-adjacent `PipeNode`s **of the same channel and (for fluid/gas) compatible type-lock**, build a `Network` (N2), key it by lexicographic-min anchor, and cache `Map<anchorKey, Network>` + `Map<pipeKey, anchorKey>`. Neighbor lookup reuses the `BlockModule.getBlockEntity(world,x,y,z) → Ref<ChunkStore>` technique from the retired `MachineTickSystem.neighborNode`.
- **Spike:** confirm `BlockModule.getBlockEntity` + `store.getComponent(ref, pipeType)` against the decompiled source.
- **Verify:** a unit-ish test that builds a network over a hand-mocked grid if feasible; else devServer log of discovered network size.

### Task H3: Cache invalidation on structural events
- Register `EntityEventSystem` handlers for `com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent` and `BreakBlockEvent` (both expose the target block `Vector3i`): on a pipe place/break, invalidate (drop) the affected cached network(s) so they rebuild lazily — **split on break, merge on place** handled by simple drop-and-rebuild initially (optimize later). Register `com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkUnloadEvent` to flush + drop networks touching the unloaded chunk (ties to H6 persistence).
- **Spike:** read `PlaceBlockEvent`/`BreakBlockEvent`/`ChunkUnloadEvent` signatures in `patcher/hytale-server/.../event/events/ecs/`; confirm the `EntityEventSystem<EntityStore, …>` registration shape (see `org.pfc.redstone` place/break systems as a working example, design §16.3).
- **Verify:** devServer — place/break a pipe, log shows the network rebuilds; no per-tick rebuild.

### Task H4: Distribution tick (supersede `TransportEngine`)
- Create `impl/block/net/NetworkTickSystem extends EntityTickingSystem<ChunkStore>` (query = `PipeNode`, dedup so each cached network ticks once): for each cached `Network`, collect adjacent machine/tank **ports** (provider = OUTPUT port whose face touches a network pipe; acceptor = INPUT port likewise), wrap them as N4 `Provider`/`Acceptor`, run `NetworkTransfer.distribute`. Delete `TransportEngine.pushEnergy/pushResources` calls from `MachineTickSystem` (the machine tick keeps only creative-refill + the work-pass stub).
- **Spike:** port-face → world-direction mapping (the existing `OFFSETS` approximation; real face indices remain a known gap, design status §5).
- **Verify:** devServer — power/fluid flows **through a pipe run**, not adjacency; far machines no longer starve (fair-split).

### Task H5: Pipe rig blocks
- Create `src/main/resources/Server/Item/Items/ChonbosMods/CC_EnergyPipe.json`, `CC_FluidPipe.json`, `CC_GasPipe.json`, each attaching `PipeNode` via `BlockType.BlockEntity.Components` (`"PipeNode"` + codec data: channel, tier). Update the existing rig README/smoke so the power/fluid path is `PowerCell → EnergyPipe → … → EnergySink` (pipes now required, design §5.4).
- **Verify:** boot-clean; in-game placeable; transport works only through pipes.

### Task H6: Buffer persistence + tank carry (folds in old B5)
- On `ChunkUnloadEvent`/save: distribute the network buffer's contents back into member `PipeNode` shares (so a split network on reload re-pools the right totals); on load/first-tick: re-pool member shares into the rebuilt `Network`. Then the **tank carry-with-contents** (old B5, design §5.2): break serializes a tank/machine `ResourceBuffer` into the dropped item's metadata; place rehydrates.
- **Spike:** `ItemStack` metadata API (`withMetadata`, `@hytale-ui-items`) and break/place drop hooks (`@hytale-events`).
- **Verify:** devServer — fill a network, unload/reload the chunk, totals survive; fill a tank, break, replace, contents survive.

### Task H7: Network-aware GUI gauge
- Extend `MachinePanelPage` (and/or a `PipePanelPage`) to show the **network aggregate** — `stored / capacity (fillRatio%)` from the `Network`, the "free aggregate gauge" (§13.2) — using `Message.raw(...)` rows per the Nat20 `.ui` pattern (design status §4).
- **Verify:** boot-clean; right-click a pipe/machine shows the grid total.

### Task H8: Cleanup, retire dead code, end-to-end smoke
- Remove ALL `// TEMP B6-remove` logging from `MachineTickSystem` (old B6). Delete `TransportEngine` + `TransferNode`/`NeighborView` if no longer referenced (or reduce to the neighbor-lookup helper now in `NetworkManager`). Delete `CableHopTest`; update `TransportEngineTest` (retire or repoint to `NetworkTransferTest` coverage).
- Full devServer end-to-end smoke (design §8 rules): power flow through pipes, fluid type-lock on a pipe network, full-network backpressure, multi-source balance, tank carry.
- `./gradlew test --rerun-tasks` + `compileJava` green. Final commit.

---

## Done criteria

- [ ] Phase N: `EnergyHandler` v2, `Network`, `FairSplitDistributor`, `NetworkTransfer`, scenarios — all green headless.
- [ ] Phase H: pipes carry energy/fluid/gas via **cached, event-invalidated** networks; adjacency push gone; fair-split distribution verified in-game; type-lock + backpressure + multi-source confirmed; buffers persist across reload; GUI shows network totals.
- [ ] `TransportEngine`/`CableHopTest` retired; no TEMP logging; `./gradlew test` + `compileJava` green; final commit (not pushed).

## Deferred (own later plans — design §10)

- **Item pipes** (discrete pathfinding, §13.4) — separate model, do not force onto the fungible buffer.
- **Neutron pipes** (fungible + hard length cap N, §13.5).
- **Redstone & circuitry** (§14) — including adding `PortChannel`/signal channels.
- **Electrified vanilla workbenches** (§15).
- **Real block-face index reconciliation** (the `OFFSETS` approximation) and split/merge network optimization (vs drop-and-rebuild).

---

## Execution handoff

Built via the `superpowers` workflow used for the prior slice: `using-git-worktrees` → `subagent-driven-development` (fresh implementer per task + spec/quality review; spike signatures against `patcher/hytale-server/` before integrating; verify boot-clean via devServer; user does in-game smoke). Start at **Task N1**.
