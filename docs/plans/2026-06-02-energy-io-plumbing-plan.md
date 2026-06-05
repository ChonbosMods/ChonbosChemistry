# Energy + I/O Plumbing Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build the shared block-infrastructure skeleton (energy standard, phased resource buffers, configurable ports, adjacency/cable transport, deterministic ticking, block GUI) that every chemistry/nuclear machine will plug into, proven end-to-end by a no-chemistry test rig.

**Architecture:** Two layers. (1) **Pure logic** — `EnergyHandler` contract + `EnergyBuffer`, `ResourceBuffer`, `PortConfig`, `WorkState` beans + a `TransportEngine` push algorithm, all plain Java with `BuilderCodec` serialization, fully unit-tested headlessly (no live server). (2) **Hytale integration** — wrap the beans in an ECS block-entity component registered on `ChunkStore`, drive them with our own `EntityTickingSystem<ChunkStore>` (furnace `dt`-accumulation semantics, modern non-deprecated path), define the test-rig blocks as asset JSON, and bind a `CustomUIPage` GUI. The logic layer abstracts neighbor access behind a `NeighborView` interface so the transport algorithm is testable against a mock grid before any block API exists.

**Tech Stack:** Java 25, Hytale server plugin API (U5 / ScaffoldIt 0.2.16, `useVersion("0.5.3")`), Hytale `Codec`/`BuilderCodec`/`KeyedCodec`/`ArrayCodec`, JUnit 5 (existing headless harness). Design: `docs/plans/2026-06-02-energy-io-plumbing-design.md`. Parent: `docs/machines-and-power-design.md`.

---

## Conventions (match the existing foundation exactly)

- **Beans:** `public final class`, a `public static final BuilderCodec<T> CODEC`, private fields, **bare-name getters** (`stored()` not `getStored()`), private no-arg constructor used by the codec, builder appends as `.append(new KeyedCodec<>("Key", Codec.X), (o,v)->o.f=v, o->o.f).add()`. See `api/substance/NuclearFlags.java`.
- **Enums:** `public static final Codec<E> CODEC = StringMappedCodec.ofEnum(E.class, E::jsonValue);` with a `jsonValue` field. See `api/substance/Phase.java`.
- **api/impl split (governing rule):** `api` defines contracts only, zero concrete values; `api` must never import `impl`. `EnergyHandler`, `PortDirection`, `NeighborView` → `api.energy` / `api.io`. All beans with concrete behavior/values → `impl`.
- **Tests:** JUnit 5, package-private `class XTest`, headless. Codec round-trips use the helper `static <T> T decode(Codec<T> c, String json) { return c.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY); }`. See `api/substance/NestedValueTypesTest.java`.
- **Build/test commands:** compile `./gradlew compileJava`; run one test `./gradlew test --tests "com.chonbosmods.chemistry.impl.block.EnergyBufferTest"`; run all `./gradlew test`. Dev server (integration) `./gradlew devServer` (full restart per change — hot-reload disabled under U5).
- **Commits:** Conventional Commits, no `Co-Authored-By`, do not push. Commit after every green step.
- **Reuse:** `api.substance.Phase` (SOLID/LIQUID/GAS) is the existing phase enum — reuse it, do not redefine.

---

## PHASE A — Pure logic (full TDD, headless)

Everything here compiles and tests without a running server, exactly like the existing substance/registry code.

### Task A1: `EnergyHandler` contract + `EnergyBuffer`

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/api/energy/EnergyHandler.java`
- Create: `src/main/java/com/chonbosmods/chemistry/api/energy/package-info.java`
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/EnergyBuffer.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/EnergyBufferTest.java`

**Step 1: Write the failing test**

```java
package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.junit.jupiter.api.Test;

class EnergyBufferTest {

    private static <T> T decode(Codec<T> c, String json) throws Exception {
        return c.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void receiveClampsToCapacityAndReturnsAccepted() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        assertEquals(60, b.receiveEnergy(60, false)); // accepts 60
        assertEquals(40, b.receiveEnergy(80, false)); // only 40 fits
        assertEquals(100, b.getStored());
    }

    @Test
    void receiveSimulateDoesNotMutate() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        assertEquals(100, b.receiveEnergy(500, true)); // simulate: would accept 100
        assertEquals(0, b.getStored());                // unchanged
    }

    @Test
    void extractClampsToStoredAndReturnsProvided() {
        EnergyBuffer b = EnergyBuffer.withCapacity(100);
        b.receiveEnergy(50, false);
        assertEquals(50, b.extractEnergy(80, false)); // only 50 available
        assertEquals(0, b.getStored());
    }

    @Test
    void codecRoundTripsStoredAndCapacity() throws Exception {
        EnergyBuffer b = decode(EnergyBuffer.CODEC, "{\"Stored\":30,\"Capacity\":100}");
        assertEquals(30, b.getStored());
        assertEquals(100, b.getMaxStored());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.chonbosmods.chemistry.impl.block.EnergyBufferTest"`
Expected: FAIL — `EnergyBuffer` / `EnergyHandler` do not exist (compile error).

**Step 3: Write minimal implementation**

`api/energy/EnergyHandler.java`:
```java
package com.chonbosmods.chemistry.api.energy;

/**
 * The published energy-capability contract (the Hytale FE-equivalent). Any block may implement
 * this in a few lines; cables and machines transfer against it. Lossless, push-based (design §7).
 * Amounts are non-negative integers in the mod's own energy unit; "simulate" performs no mutation.
 */
public interface EnergyHandler {

    /** @return amount actually accepted (≤ amount, ≤ free space). */
    int receiveEnergy(int amount, boolean simulate);

    /** @return amount actually provided (≤ amount, ≤ stored). */
    int extractEnergy(int amount, boolean simulate);

    int getStored();

    int getMaxStored();
}
```

`api/energy/package-info.java`:
```java
/**
 * The energy-capability contract third parties build against ({@link EnergyHandler}).
 * Defines only; concrete buffers, tiers, and transport live in {@code impl}.
 */
package com.chonbosmods.chemistry.api.energy;
```

`impl/block/EnergyBuffer.java`:
```java
package com.chonbosmods.chemistry.impl.block;

import com.chonbosmods.chemistry.api.energy.EnergyHandler;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/** A concrete {@link EnergyHandler}: a stored amount bounded by a fixed capacity. */
public final class EnergyBuffer implements EnergyHandler {

    public static final BuilderCodec<EnergyBuffer> CODEC = BuilderCodec.builder(EnergyBuffer.class, EnergyBuffer::new)
        .append(new KeyedCodec<>("Stored", Codec.INTEGER), (o, v) -> o.stored = v, o -> o.stored).add()
        .append(new KeyedCodec<>("Capacity", Codec.INTEGER), (o, v) -> o.capacity = v, o -> o.capacity).add()
        .build();

    private int stored;
    private int capacity;

    private EnergyBuffer() {
    }

    public static EnergyBuffer withCapacity(int capacity) {
        EnergyBuffer b = new EnergyBuffer();
        b.capacity = capacity;
        return b;
    }

    @Override
    public int receiveEnergy(int amount, boolean simulate) {
        int accepted = Math.max(0, Math.min(amount, capacity - stored));
        if (!simulate) {
            stored += accepted;
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int amount, boolean simulate) {
        int provided = Math.max(0, Math.min(amount, stored));
        if (!simulate) {
            stored -= provided;
        }
        return provided;
    }

    @Override
    public int getStored() {
        return stored;
    }

    @Override
    public int getMaxStored() {
        return capacity;
    }
}
```

> Note `Codec.INTEGER` is the assumed int codec name; if compile fails, grep the decompiled server for the int codec constant (`grep -rn "Codec.INT" ~/Development/Hytale/patcher/hytale-server` — the foundation used `Codec.DOUBLE`/`Codec.BOOLEAN`, so `Codec.INTEGER` is the expected sibling). Fix the name if needed; this is the only uncertain token in Phase A.

**Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "com.chonbosmods.chemistry.impl.block.EnergyBufferTest"`
Expected: PASS (4 tests).

**Step 5: Commit**

```bash
git add src/main/java/com/chonbosmods/chemistry/api/energy src/main/java/com/chonbosmods/chemistry/impl/block/EnergyBuffer.java src/test/java/com/chonbosmods/chemistry/impl/block/EnergyBufferTest.java
git commit -m "feat(energy): EnergyHandler contract + EnergyBuffer with codec"
```

---

### Task A2: `ResourceBuffer` (phased, type-locked, capped)

A single phase's stored resource: `resourceId` + `amount`, bounded by `capacity`, **type-locked** to the first resource inserted until emptied (design §5.2 / rule 1). Models "gas == fluid == a number." Headless — does not depend on Hytale's `ResourceQuantity`.

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/ResourceBuffer.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/ResourceBufferTest.java`

**Step 1: Write the failing test**

```java
package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import org.junit.jupiter.api.Test;

class ResourceBufferTest {

    private static <T> T decode(Codec<T> c, String json) throws Exception {
        return c.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void insertLocksToFirstResource() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        assertEquals(300, b.insert("compound:water", 300, false));
        assertEquals("compound:water", b.resourceId());
        assertEquals(0, b.insert("element:oxygen", 100, false)); // wrong type rejected (rule 1)
        assertEquals(300, b.amount());
    }

    @Test
    void insertClampsToCapacity() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        assertEquals(1000, b.insert("compound:water", 1500, false));
    }

    @Test
    void extractEmptyingUnlocks() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        b.insert("compound:water", 300, false);
        assertEquals(300, b.extract(500, false));   // only 300 available
        assertNull(b.resourceId());                  // emptied → unlocked
        assertEquals(200, b.insert("element:oxygen", 200, false)); // now accepts a new type
    }

    @Test
    void simulateDoesNotMutate() {
        ResourceBuffer b = ResourceBuffer.withCapacity(1000);
        assertEquals(1000, b.insert("compound:water", 1000, true));
        assertEquals(0, b.amount());
        assertNull(b.resourceId());
    }

    @Test
    void codecRoundTrips() throws Exception {
        ResourceBuffer b = decode(ResourceBuffer.CODEC,
            "{\"ResourceId\":\"compound:water\",\"Amount\":42,\"Capacity\":1000}");
        assertEquals("compound:water", b.resourceId());
        assertEquals(42, b.amount());
        assertEquals(1000, b.capacity());
    }
}
```

**Step 2: Run to verify it fails** — `./gradlew test --tests "...ResourceBufferTest"` → FAIL (class missing).

**Step 3: Minimal implementation**

```java
package com.chonbosmods.chemistry.impl.block;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * One phase's stored resource: a {@code resourceId} + integer {@code amount} bounded by
 * {@code capacity}, type-locked to the first inserted resource until emptied (design §5.2).
 * A wrong-type or over-capacity insert accepts only what fits and never voids (rule 1).
 */
public final class ResourceBuffer {

    public static final BuilderCodec<ResourceBuffer> CODEC = BuilderCodec.builder(ResourceBuffer.class, ResourceBuffer::new)
        .append(new KeyedCodec<>("ResourceId", Codec.STRING), (o, v) -> o.resourceId = v, o -> o.resourceId).add()
        .append(new KeyedCodec<>("Amount", Codec.INTEGER), (o, v) -> o.amount = v, o -> o.amount).add()
        .append(new KeyedCodec<>("Capacity", Codec.INTEGER), (o, v) -> o.capacity = v, o -> o.capacity).add()
        .build();

    private String resourceId; // null when empty/unlocked
    private int amount;
    private int capacity;

    private ResourceBuffer() {
    }

    public static ResourceBuffer withCapacity(int capacity) {
        ResourceBuffer b = new ResourceBuffer();
        b.capacity = capacity;
        return b;
    }

    /** @return amount accepted (0 if locked to a different resource). */
    public int insert(String id, int qty, boolean simulate) {
        if (resourceId != null && !resourceId.equals(id)) {
            return 0;
        }
        int accepted = Math.max(0, Math.min(qty, capacity - amount));
        if (!simulate && accepted > 0) {
            resourceId = id;
            amount += accepted;
        }
        return accepted;
    }

    /** @return amount removed; empties → unlocks. */
    public int extract(int qty, boolean simulate) {
        int removed = Math.max(0, Math.min(qty, amount));
        if (!simulate && removed > 0) {
            amount -= removed;
            if (amount == 0) {
                resourceId = null;
            }
        }
        return removed;
    }

    public String resourceId() {
        return resourceId;
    }

    public int amount() {
        return amount;
    }

    public int capacity() {
        return capacity;
    }
}
```

> If `Codec.STRING` is misnamed, the foundation already uses string codecs — grep `api/substance` codecs or the server for the constant and adjust.

**Step 4: Run** → PASS (5 tests). **Step 5: Commit** `feat(io): ResourceBuffer — type-locked, capped phase storage`.

---

### Task A3: `PortDirection` enum + `Port` + `PortConfig`

Per-port `{phase, direction}` config (design §5.4). A `Port` is one configurable surface slot; `PortConfig` holds the list and answers "which ports output phase X."

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/api/io/PortDirection.java`
- Create: `src/main/java/com/chonbosmods/chemistry/api/io/package-info.java`
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/Port.java`
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/PortConfig.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/PortConfigTest.java`

**Step 1: Failing test**

```java
package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import com.chonbosmods.chemistry.api.io.PortDirection;
import com.chonbosmods.chemistry.api.substance.Phase;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.EmptyExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortConfigTest {

    private static <T> T decode(Codec<T> c, String json) throws Exception {
        return c.decodeJson(RawJsonReader.fromJsonString(json), EmptyExtraInfo.EMPTY);
    }

    @Test
    void filtersOutputsByPhase() {
        PortConfig cfg = PortConfig.of(List.of(
            Port.of(0, Phase.GAS, PortDirection.INPUT),
            Port.of(1, Phase.GAS, PortDirection.OUTPUT),
            Port.of(2, Phase.LIQUID, PortDirection.OUTPUT),
            Port.of(3, Phase.GAS, PortDirection.CLOSED)));
        List<Port> gasOut = cfg.portsFor(Phase.GAS, PortDirection.OUTPUT);
        assertEquals(1, gasOut.size());
        assertEquals(1, gasOut.get(0).faceIndex());
    }

    @Test
    void portCodecRoundTrips() throws Exception {
        Port p = decode(Port.CODEC, "{\"Face\":2,\"Phase\":\"liquid\",\"Direction\":\"output\"}");
        assertEquals(2, p.faceIndex());
        assertEquals(Phase.LIQUID, p.phase());
        assertEquals(PortDirection.OUTPUT, p.direction());
    }
}
```

**Step 2: Run** → FAIL.

**Step 3: Implement.**

`api/io/PortDirection.java` (enum, `StringMappedCodec.ofEnum` pattern):
```java
package com.chonbosmods.chemistry.api.io;

import com.chonbosmods.chemistry.api.shim.StringMappedCodec;
import com.hypixel.hytale.codec.Codec;

/** Flow role assigned to a port or tank face (design §5.4). */
public enum PortDirection {
    INPUT("input"),
    OUTPUT("output"),
    CLOSED("closed");

    public static final Codec<PortDirection> CODEC = StringMappedCodec.ofEnum(PortDirection.class, PortDirection::jsonValue);

    private final String jsonValue;

    PortDirection(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    public String jsonValue() {
        return jsonValue;
    }
}
```

`api/io/package-info.java`:
```java
/** Transport contracts: port direction and neighbor access. Defines only; impl decides values. */
package com.chonbosmods.chemistry.api.io;
```

`impl/block/Port.java` — bean with `int faceIndex`, `Phase phase`, `PortDirection direction`; `CODEC` using `new KeyedCodec<>("Phase", Phase.CODEC)` and `("Direction", PortDirection.CODEC)`; static `of(face, phase, dir)` factory. Follow `NuclearFlags` bean shape.

`impl/block/PortConfig.java` — holds `List<Port> ports` (codec via `new ArrayCodec<>(Port.CODEC, Port[]::new)` keyed under `"Ports"`, as `InMemorySubstanceRegistry` uses `ArrayCodec`); static `of(List<Port>)`; method `List<Port> portsFor(Phase, PortDirection)` filtering the list.

**Step 4: Run** → PASS. **Step 5: Commit** `feat(io): PortDirection + Port + PortConfig with codecs`.

---

### Task A4: `WorkState` (dt-accumulated progress)

Furnace semantics: accumulate `dt` seconds while inputs present; complete when `progress > duration`; **freeze (retain) progress on run-dry** (design §8, rule 2 — deliberate divergence from the furnace's reset).

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/WorkState.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/WorkStateTest.java`

**Step 1: Failing test**

```java
package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkStateTest {

    @Test
    void completesWhenProgressExceedsDuration() {
        WorkState w = new WorkState();
        assertFalse(w.advance(1.0f, 2.0f, true)); // 1.0 of 2.0s, inputs present
        assertTrue(w.advance(1.5f, 2.0f, true));   // 2.5 > 2.0 → complete
        assertEquals(0.5f, w.progress(), 1e-6);    // carry the remainder
    }

    @Test
    void runDryFreezesProgress() {
        WorkState w = new WorkState();
        w.advance(1.0f, 2.0f, true);
        assertFalse(w.advance(1.0f, 2.0f, false)); // no inputs → no progress, no loss
        assertEquals(1.0f, w.progress(), 1e-6);
    }
}
```

**Step 2: Run** → FAIL.

**Step 3: Implement** — `progress` (float), `active` (bool); `boolean advance(float dt, float duration, boolean hasInputs)` that adds dt only when `hasInputs`, returns true and subtracts `duration` (retaining remainder) on completion, sets `active`. Plus a `CODEC` (`Codec.FLOAT` for progress, `Codec.BOOLEAN` for active) for persistence. Bare-name getters `progress()`, `active()`.

**Step 4: Run** → PASS. **Step 5: Commit** `feat(block): WorkState dt-accumulated progress with run-dry freeze`.

---

### Task A5: `NeighborView` + `TransportEngine` (the push algorithm)

The heart of the slice, tested against a **mock 6-neighbor grid** (design §9). `NeighborView` abstracts "what block-state is adjacent in direction d" so the algorithm is pure; Phase B implements it over `BlockAccessor`.

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/api/io/NeighborView.java`
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/TransportEngine.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/TransportEngineTest.java`

**Design of the algorithm (energy first; resources identical shape, added in a later step):**
- A transferable node exposes an `EnergyHandler` and a `PortConfig`.
- `TransportEngine.pushEnergy(source, NeighborView)`: for each `OUTPUT` port on `source`, look up the neighbor in that port's direction; if the neighbor has a matching `INPUT` port, transfer `min(sourceThroughput, extractable, receivable)` via simulate-then-commit. A blocked/full neighbor or absent neighbor (unloaded → `null`) transfers 0 (rules 7, 8; chunk-unload edge case).

**Step 1: Failing test** (illustrative core cases — expand as you implement):

```java
package com.chonbosmods.chemistry.impl.block;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.chonbosmods.chemistry.api.io.NeighborView;
import org.junit.jupiter.api.Test;

class TransportEngineTest {

    @Test
    void pushesEnergyToAdjacentInputUpToReceiverSpace() {
        EnergyNode src = EnergyNode.outputting(/*stored*/100, /*cap*/100, /*throughput*/40);
        EnergyNode dst = EnergyNode.inputting(/*stored*/0, /*cap*/25);
        NeighborView view = NeighborView.single(/*direction*/0, dst); // dst sits in direction 0
        TransportEngine.pushEnergy(src, view);
        assertEquals(25, dst.buffer().getStored());   // receiver space caps it
        assertEquals(75, src.buffer().getStored());
    }

    @Test
    void throughputCapsTransfer() {
        EnergyNode src = EnergyNode.outputting(100, 100, 40);
        EnergyNode dst = EnergyNode.inputting(0, 1000);
        TransportEngine.pushEnergy(src, NeighborView.single(0, dst));
        assertEquals(40, dst.buffer().getStored());   // throughput caps it
    }

    @Test
    void absentNeighborTransfersNothing() {
        EnergyNode src = EnergyNode.outputting(100, 100, 40);
        TransportEngine.pushEnergy(src, NeighborView.empty()); // unloaded chunk → null neighbor
        assertEquals(100, src.buffer().getStored());
    }

    @Test
    void blockedReceiverLeavesSourceUntouched() {
        EnergyNode src = EnergyNode.outputting(100, 100, 40);
        EnergyNode dst = EnergyNode.inputting(25, 25); // already full
        TransportEngine.pushEnergy(src, NeighborView.single(0, dst));
        assertEquals(100, src.buffer().getStored());
    }
}
```

> `EnergyNode` is a tiny test fixture (in the test source tree) implementing the transferable-node interface with an `EnergyBuffer` + a `PortConfig`. `NeighborView.single`/`empty` are test factories on the interface (or a sibling test helper). Define the production `NeighborView` interface minimally: `Transferable neighborIn(int direction)` returning `null` when absent. Keep `Transferable` (energy handler + port config accessor) in `impl.block`.

**Step 2: Run** → FAIL.
**Step 3:** Implement `NeighborView` (api.io, the abstraction) and `TransportEngine` (impl) with the simulate-then-commit transfer. Implement the 6-direction iteration; for the slice, direction indices 0–5 map to ±X/±Y/±Z (document the mapping; Phase B aligns it to Hytale's face indexing).
**Step 4: Run** → PASS.
**Step 5: Commit** `feat(transport): NeighborView + TransportEngine push algorithm (energy)`.

**Step 6 (same task, second TDD cycle):** add `pushResources(source, NeighborView)` mirroring the energy path but over `ResourceBuffer`/phase-matched ports; add its tests; commit `feat(transport): resource push by matching phase`.

---

### Task A6: `CableBuffer` semantics (internal buffer, stop-pull-when-full)

A cable is a transferable node with its own small `EnergyBuffer`/`ResourceBuffer` that fills from upstream and drains downstream — "not instantaneous teleport-on-a-wire" (design §5.3), and stops pulling when full (rule 7). With A1–A5 done this is mostly composition.

**Files:** Create `impl/block/CableBuffer.java` (or prove a cable is just a node whose buffer participates in `TransportEngine`); Test `CableBufferTest.java` asserting a 3-node line (source → cable → sink) moves energy one hop per `pushEnergy` pass and the cable holds a buffered amount mid-transit.

TDD as above; commit `feat(transport): cable internal-buffer hop semantics`.

---

**End of Phase A.** At this point all transport/storage/port logic is proven headlessly. Run the full suite (`./gradlew test`) and confirm green before Phase B.

---

## PHASE B — Hytale integration (spike → implement → devServer smoke)

These tasks touch APIs whose exact signatures must be confirmed against the decompiled server first. **Each integration task starts with a spike step** (read the precedent, confirm the signature, write a one-line note in the task), then implements, then verifies in `devServer`. Do not fabricate signatures — verify them. Skills: @hytale-assets (block/item asset JSON), @hytale-ui (CustomUIPage/PageManager/UICommandBuilder), @hytale-ecs (ChunkStore/components/systems), @hytale-events (block place/break), @hytale-codec-config (component codec registration).

Precedent files (read these first):
- Component on a block entity + GUI: `builtin/portals/ui/PortalDevicePageSupplier.java`, the `PortalDevice` component.
- Plugin registering ChunkStore components **and** ticking systems: `server/spawning/SpawningPlugin.java:266,373,385`.
- dt-accumulated processing block: `builtin/crafting/state/ProcessingBenchState.java:242,413,423`.
- Block-entity retrieval / ensure: `server/core/modules/block/BlockModule.java:96-115,199-214`.
- Neighbor read: `server/core/universe/world/accessor/BlockAccessor.java`.

### Task B1: ECS block-entity component wrapping the beans

**Spike:** Read `ProcessingBenchState` and a `PortalDevice`-style component to confirm: the component base type/interface, how its `CODEC` is declared, and the exact `getChunkStoreRegistry().registerComponent(...)` signature (`SpawningPlugin.java:266`). Note findings in the task.

**Implement:** `impl/block/MachineBlockState` (holds `EnergyBuffer`, a `List<ResourceBuffer>` by phase, `PortConfig`, `WorkState` — all the Phase A beans — with a combined `CODEC`) and a thinner `TankBlockState` (one `ResourceBuffer` + per-face `PortConfig`). Register both component types in `ChonbosChemistry.setup()` via `getChunkStoreRegistry().registerComponent(...)`; nothing to clear (component registry lives with the plugin), but verify against shutdown expectations.

**Verify:** `./gradlew compileJava` passes; `./gradlew devServer` boots and logs registration without error. **Commit** `feat(block): ChunkStore block-entity components for machines and tanks`.

### Task B2: `EntityTickingSystem<ChunkStore>` — the two-pass tick

**Spike:** Read `SpawningPlugin`'s `...TickingState`/`TickHeartbeat` systems and `BlockStateModule.LegacyTickingBlockStateSystem` to confirm the `EntityTickingSystem<ChunkStore>` superclass, the `getQuery()` contract, the `tick(float dt, int index, ArchetypeChunk<ChunkStore>, Store<ChunkStore>, CommandBuffer<ChunkStore>)` signature, and how `dt` is supplied. Confirm `registerSystem` ordering controls (so transport runs before work — possibly two systems or one system with two internal passes over a snapshot).

**Implement:** A system (or pair) that, per tick: (1) transport pass — builds a `NeighborView` over `BlockAccessor` + `BlockModule.getBlockEntity` and calls `TransportEngine.pushEnergy/pushResources`; (2) work pass — calls `WorkState.advance(dt, duration, hasInputs)`. Register in `setup()`.

**Verify (`devServer`):** place a Creative Power Cell beside an Energy Sink (no cable) and confirm energy flows by adjacency (temporary log line in the sink each tick). **Commit** `feat(block): ChunkStore ticking system — transport then work`.

### Task B3: Test-rig block + item assets (JSON)

**Spike:** Use @hytale-assets to confirm the asset-pack directory layout, `BlockType` JSON schema, how `blockEntity` references a component holder, and how `interactions` map `UseBlock` → an `OpenCustomUIInteraction`. Confirm item assets for the placeable blocks.

**Implement (asset JSON under the mod's asset pack):**
- **Creative Power Cell** — block with `MachineBlockState`, an `EnergyBuffer` pre-filled / refilled each tick by a flag (creative source), one OUTPUT energy port.
- **Power Cable** — block with a buffered energy node, all faces auto-connecting.
- **Energy Sink (Lamp)** — block with `MachineBlockState`, one INPUT energy port; lights/logs when `stored > 0`.
- **Fluid Tank** — block with `TankBlockState`; per-face config.
- **Fill Source** — block that inserts a fixed fluid (`compound:water`) into an adjacent tank each tick (exercises `ResourceBuffer`).

**Verify:** assets load on `devServer` boot; blocks are placeable. **Commit** `feat(assets): energy/fluid test-rig blocks and items`.

### Task B4: Block GUI (`CustomUIPage`)

**Spike:** Read `PortalDevicePageSupplier` + @hytale-ui to confirm `CustomPageSupplier`/`CustomUIPage.build`/`handleDataEvent` signatures, `UICommandBuilder`/`UIEventBuilder` usage, and how to read the target block + its component inside the supplier.

**Implement:** A `CustomUIPage` that renders energy + per-phase buffer gauges and a per-port phase/direction selector, reading/writing the block's component; wire it via the `OpenCustomUIInteraction` referenced in B3's block JSON.

**Verify (`devServer`):** right-click a machine → GUI opens, gauges reflect live state, changing a port's direction updates `PortConfig` (observe transport behavior change). **Commit** `feat(ui): machine/tank block GUI with gauges and port config`.

### Task B5: Tank carry-with-contents (break/place metadata)

**Spike:** Confirm block place/break event hooks (@hytale-events) and `ItemStack` metadata API (`withMetadata` / @hytale-ui-items) for serializing a `ResourceBuffer` into the dropped item and rehydrating on place.

**Implement:** On tank break, write `ResourceBuffer` (id/amount/capacity) into the dropped item's metadata; on place, rehydrate the new `TankBlockState`'s buffer from item metadata.

**Verify (`devServer`):** fill a tank, break it, replace it — contents survive (design §5.2). **Commit** `feat(block): tanks relocate with contents preserved`.

### Task B6: End-to-end smoke + cleanup

**Steps:**
1. `devServer`: build the full rig — Power Cell → Cable → Lamp (lights), and Fill Source → Tank (fills), then break/move the full Tank (contents preserved). Confirm each design rule observably holds (stall on full sink, run-dry pause, adjacency with zero cables).
2. Remove temporary debug log lines added during B2/B3.
3. `./gradlew test` (all green) and `./gradlew compileJava`.
4. **Commit** `chore: remove debug logging; energy+IO plumbing slice complete`.

---

## Done criteria

- All Phase A unit tests green headlessly (`./gradlew test`).
- `devServer` rig demonstrates: energy push by adjacency and through buffered cable; phased resource fill with type-lock; per-port config via GUI; tank carry-with-contents; stall/run-dry behavior.
- No `api → impl` imports; energy/port/neighbor contracts live in `api`.
- No new `[TUNE]` numbers hardcoded in `api`; concrete capacities/throughput live in `impl`/assets.

## Deferred (not in this slice)

Multi-sink transport fairness (round-robin); wrench-based port config; neutron flux; any real chemistry/nuclear recipe or machine. These build on this skeleton in later slices.
