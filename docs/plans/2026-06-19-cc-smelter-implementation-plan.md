# CC Smelter Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship the Smelter — the first automated machine — as a placeable multi-cell block that smelts vanilla furnace recipes, runs only on piped-in power, takes/gives items only through ITEM pipes, lights its coils while working, and opens a furnace-shaped display-only GUI.

**Architecture:** Our `MachineBlockState` ECS component holds a real vanilla `ProcessingBenchBlock` (recipe matching/slots/progress/states) behind one `VanillaBenchBridge`; our `MachineTickSystem` gates its `advanceProcessing` on an `EnergyBuffer`. The block uses Hytale's anchor+filler footprint (copied from the furnace hitbox); pipes attach via per-face FaceTags + our `PortConfig`, with a filler→anchor resolution as the only new transport plumbing.

**Tech Stack:** Java 25, Hytale Server SDK 0.5.3 (`com.hypixel.hytale.builtin.crafting.*`), Hytale codecs, JUnit 5, Gradle (`./gradlew test`, `./gradlew runServer`). Design: [docs/design.md](../design.md) §7.3/§7.5/§7.10; this feature's design: [2026-06-19-cc-smelter-design.md](2026-06-19-cc-smelter-design.md); decompile detail: [2026-06-08-vanilla-bench-reuse-design.md](2026-06-08-vanilla-bench-reuse-design.md).

**Conventions:** Use `:` not `—`. Commit locally after each task (no push, no Co-Authored-By). Relevant skills: @hytale-assets (block/item JSON), @hytale-ecs (components/systems), @hytale-ui-items (slots), @hytale-commands (the spike probe), @superpowers:test-driven-development, @superpowers:systematic-debugging.

**Two test modes:**
- **Headless (JUnit):** pure logic — energy/progress math, filler↔anchor packing, port resolution, snapshot render model. Strict TDD.
- **Integration (devServer):** anything touching `builtin.crafting.*`, block placement, pipes, or GUI. `CraftingPlugin.get()` needs a running server, so these are verified in `./gradlew runServer` with explicit manual steps. There is no in-process server harness.

**Face index reference** (`FaceNames`, OFFSETS order): `0 +X East`, `1 -X West`, `2 +Y Up`, `3 -Y Down`, `4 +Z South`, `5 -Z North`. Model front (window) = +Z South. **Default ports: Face 1 West = ITEM input, Face 0 East = ITEM output, Face 5 North = POWER input.**

---

## Phase 1 — De-risk the bridge (gating spike)

Prove `ProcessingBenchBlock` is drivable headlessly-in-server before building any asset on top. This is throwaway scaffolding removed in Task 4.

### Task 1: `VanillaBenchBridge` skeleton

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/bench/VanillaBenchBridge.java`

**Step 1:** Create the bridge as the single class importing `com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock` and `com.hypixel.hytale.builtin.crafting.CraftingPlugin`. Methods (resolve exact signatures against the Server jar / the 2026-06-08 decompile doc §"Public surface"):

```java
public final class VanillaBenchBridge {
    /** The vanilla component type, for codec reuse + held-instance persistence. */
    public static ComponentType<ChunkStore, ProcessingBenchBlock> benchComponentType() {
        return CraftingPlugin.get().getProcessingBenchBlockComponentType();
    }
    /** A fresh bench configured no-fuel (2 in / 4 out) from the given BlockType. */
    public static ProcessingBenchBlock create(BlockType blockType) {
        ProcessingBenchBlock b = new ProcessingBenchBlock();
        b.initializeBenchConfig(blockType);   // reads our CC_Smelter Bench config
        b.setupSlots(/* per decompile */);
        return b;
    }
    public static boolean advance(ProcessingBenchBlock b, double dtSeconds) {
        b.checkForRecipeUpdate();
        return b.advanceProcessing(dtSeconds /* + args per decompile */);
    }
    public static ItemContainer input(ProcessingBenchBlock b)  { return b.getInputContainer(); }
    public static ItemContainer output(ProcessingBenchBlock b) { return b.getOutputContainer(); }
}
```

**Step 2:** Compile only. Run: `./gradlew compileJava -q`. Expected: PASS (classes resolve — verified present in `Server-0.5.3.jar`). Fix signatures against the decompiled jar until it compiles. **Do not guess silently** — if a method signature differs, open the class in the jar (`javap -p -classpath <Server jar> com.hypixel.hytale.builtin.crafting.component.ProcessingBenchBlock`) and match it.

**Step 3:** Commit: `git add -A && git commit -m "feat(smelter): VanillaBenchBridge skeleton over ProcessingBenchBlock"`

### Task 2: Spike probe command

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/bench/BenchSpikeCommand.java` (temporary)
- Modify: `src/main/java/com/chonbosmods/chemistry/ChonbosChemistry.java` (register the command; mark `// TEMP spike, remove in Task 4`)

**Step 1:** Per @hytale-commands, add `/ccbenchspike`: construct a bench via `VanillaBenchBridge.create(...)` for the vanilla furnace `BlockType`, place a known smeltable into `input()`, loop `advance(b, 1.0)` ~ recipe-duration times, then log `output()` contents via `HytaleLogger`.

**Step 2:** Run: `./gradlew runServer`, join, run `/ccbenchspike`. Expected: log shows the smelted output stack (e.g. an ingot). If `advanceProcessing` needs the active/fuel gate satisfied, confirm the no-fuel path advances on input+output-room alone (design D32); if not, capture the exact gate in the log and adjust `create(...)` config.

**Step 3:** This is the **gating decision point.** If the bench cannot be driven headlessly-in-server, STOP and revisit D-S1 (fall back to extending `WorkState`). If it smelts: record the working call shape in a comment in `VanillaBenchBridge`, then commit: `git commit -am "test(smelter): spike proves headless ProcessingBenchBlock drive"`

---

## Phase 2 — Headless logic (strict TDD)

### Task 3: Filler↔anchor offset packing

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/FillerOffset.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/FillerOffsetTest.java`

**Step 1 (failing test):** Per §7.5 the engine packs a relative anchor offset as 5 bits per axis. Mirror its pack/unpack so endpoint code can resolve `anchor = pos − unpack(filler)`.

```java
@Test void packUnpackRoundTrips() {
    for (int[] o : new int[][]{{0,0,0},{1,0,0},{0,1,2},{3,1,0}}) {
        int packed = FillerOffset.pack(o[0], o[1], o[2]);
        assertArrayEquals(o, new int[]{FillerOffset.unpackX(packed), FillerOffset.unpackY(packed), FillerOffset.unpackZ(packed)});
    }
}
@Test void anchorZeroIsTheAnchorCell() { assertEquals(0, FillerOffset.pack(0,0,0)); }
```

**Step 2:** Run `./gradlew test --tests '*FillerOffsetTest' -q`. Expected: FAIL (class missing).

**Step 3:** Implement `pack`/`unpackX/Y/Z` (5-bit fields). **Verify the bit layout against the engine's `FillerBlockUtil` in the Server jar** before trusting it (the design names `FillerBlockUtil.forEachFillerBlock` + the unpack one-liner) — match its shift/mask exactly, or in-world filler resolution will be wrong.

**Step 4:** Run the test. Expected: PASS.

**Step 5:** Commit: `git commit -am "feat(smelter): filler offset pack/unpack (engine-matched)"`

### Task 4: Remove the spike

**Files:** Delete `BenchSpikeCommand.java`; revert its registration in `ChonbosChemistry.java`.

**Step 1:** Delete + unregister. Step 2: `./gradlew compileJava -q` (PASS). Step 3: `git commit -am "chore(smelter): remove bench spike scaffolding"`

### Task 5: Energy-gated tick math

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/SmelterEnergy.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/SmelterEnergyTest.java`

**Step 1 (failing test):** Pure function: how much `dt` can we afford, and how much energy that costs. 1× cap for v1 (overclock hook left as the `min` with `realDt`).

```java
@Test void affordsFullDtWhenEnergyAmple() {
    assertEquals(1.0, SmelterEnergy.affordableDt(/*stored*/1000, /*costPerSec*/200, /*realDt*/1.0), 1e-9);
}
@Test void clampsDtToStoredEnergy() {
    assertEquals(0.5, SmelterEnergy.affordableDt(100, 200, 1.0), 1e-9); // 100/200 = 0.5s
}
@Test void zeroEnergyFreezes() {
    assertEquals(0.0, SmelterEnergy.affordableDt(0, 200, 1.0), 1e-9);
}
@Test void drainMatchesWorkDone() {
    assertEquals(100, SmelterEnergy.drainFor(0.5, 200)); // 0.5s * 200 = 100
}
```

**Step 2:** Run `./gradlew test --tests '*SmelterEnergyTest' -q`. Expected: FAIL.
**Step 3:** Implement `affordableDt(stored, costPerSec, realDt) = min(realDt, stored/costPerSec)` and `drainFor(dt, costPerSec) = round(dt*costPerSec)`.
**Step 4:** Run. Expected: PASS.
**Step 5:** Commit: `git commit -am "feat(smelter): energy-gated dt budget + drain math"`

### Task 6: Smelter default port layout helper

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/PortConfig.java` (add nothing if `portsFor` suffices) — instead add a factory `SmelterPorts.defaults()`.
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/SmelterPorts.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/SmelterPortsTest.java`

**Step 1 (failing test):**
```java
@Test void defaultPorts() {
    PortConfig pc = SmelterPorts.defaults();
    assertEquals(List.of(Port.of(1, PortChannel.ITEM,  PortDirection.INPUT)),  pc.portsFor(PortChannel.ITEM,  PortDirection.INPUT));
    assertEquals(List.of(Port.of(0, PortChannel.ITEM,  PortDirection.OUTPUT)), pc.portsFor(PortChannel.ITEM,  PortDirection.OUTPUT));
    assertEquals(List.of(Port.of(5, PortChannel.POWER, PortDirection.INPUT)),  pc.portsFor(PortChannel.POWER, PortDirection.INPUT));
}
```
(Confirm `PortDirection` enum constant names against the source before finalizing.)

**Step 2:** Run. Expected: FAIL. **Step 3:** Implement `SmelterPorts.defaults()` returning those three ports. **Step 4:** PASS. **Step 5:** Commit: `git commit -am "feat(smelter): default port layout (W in, E out, N power)"`

---

## Phase 3 — Wire the held bench into the component

### Task 7: Hold a `ProcessingBenchBlock` on `MachineBlockState`

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/MachineBlockState.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/MachineBlockStateBenchCodecTest.java`

**Step 1 (failing test):** Add an optional held bench field persisted via the **vanilla bench codec** (reused, not re-authored). Test that a state with a held bench survives a codec round-trip and that `clone()` (codec round-trip) yields an independent bench instance.

```java
@Test void heldBenchRoundTripsAndClonesIndependently() {
    MachineBlockState s = MachineBlockState.machine(/*existing factory*/);
    s.setHeldBench(/* a configured ProcessingBenchBlock */);
    MachineBlockState copy = s.clone();
    assertNotSame(s.heldBench(), copy.heldBench());
}
```

**Step 2:** Run. Expected: FAIL.
**Step 3:** Add the field + accessor + a codec entry using `ProcessingBenchBlock`'s own codec (obtain via the vanilla component type / decompile). Keep it nullable (tanks/pipes never set it). **Caution:** the vanilla codec may require server-registered context to decode; if a pure JUnit round-trip can't construct a bench, downgrade this to a devServer persistence check (place, smelt partway, save, reload, confirm progress retained) and note it in the test file.
**Step 4:** Run (or devServer fallback). Expected: PASS.
**Step 5:** Commit: `git commit -am "feat(smelter): MachineBlockState holds a vanilla ProcessingBenchBlock"`

### Task 8: Energy-gated WORK pass in `MachineTickSystem`

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/MachineTickSystem.java` (replace the stub WORK pass)

**Step 1:** Replace the stubbed work pass with: if `heldBench != null` → `dt = SmelterEnergy.affordableDt(energy.getStored(), SMELTER_DRAW, realDt)`; if `dt > 0` → `VanillaBenchBridge.advance(heldBench, dt)` then `energy.extractEnergyInternal(SmelterEnergy.drainFor(dt, SMELTER_DRAW), false)`. Keep the existing creative-refill branch. Honor the defensive contract (never throw on missing component/chunk; mutate in place, never `clone()`).
**Step 2:** `./gradlew compileJava -q`. Expected: PASS.
**Step 3:** Commit: `git commit -am "feat(smelter): tick drives held bench, gated + drained by energy"`

*(End-to-end behavior of Tasks 7–8 is verified in Phase 6.)*

---

## Phase 4 — Block asset (devServer-verified)

Per @hytale-assets. Mirror `models/references/Server/Item/Items/Bench/Bench_Furnace.json`.

### Task 9: Hitbox `CC_Smelter`
**Files:** Create `src/main/resources/Server/Item/Block/Hitboxes/ChonbosMods/CC_Smelter.json` — copy the furnace's two boxes verbatim (X −0.9…0.9, Z 0…0.95, Y 0…1.3; and X −0.55…0.55, Z 0.05…0.85, Y 1.3…2).
**Verify:** `./gradlew compileJava -q` (resources copy). Commit: `git commit -am "feat(smelter): CC_Smelter hitbox (furnace footprint)"`

### Task 10: Connection template `CC_SmelterTemplate`
**Files:** Create `src/main/resources/Server/Item/CustomConnectedBlockTemplates/CC_SmelterTemplate.json`. FaceTags mapping model nubs: `North:[CC_PowerFace]` (back), `West:[CC_ItemFace]` (in), `East:[CC_ItemFace]` (out); leave `South/Up/Down` untagged (no stub). Pattern: `{ "machine": "CC_Smelter" }`.
**Commit:** `git commit -am "feat(smelter): connection template (N power, E/W item faces)"`

### Task 11: Block/item `CC_Smelter`
**Files:** Create `src/main/resources/Server/Item/Items/ChonbosMods/CC_Smelter.json` per the design's asset block (Section "Block asset"): `DrawType: Model`, `CustomModel`/textures (Off default), `VariantRotation: NESW`, `HitboxType: CC_Smelter`, explicit `Supporting` faces (Model blocks give none otherwise — see memory `model-blocks-need-explicit-supporting`), `Interactions.Use → OpenCustomUI CC_MachinePanel`, `BlockEntity.Components.MachineBlockState` with `Energy` buffer (capacity `[TUNE]` e.g. 40000), the held-bench config (2 in / 4 out / no fuel / `Id: CC_Smelter`), `Ports` = the Task 6 default layout, and `ConnectedBlockRuleSet → CustomTemplate CC_SmelterTemplate`. Define `State.Definitions` `Processing` + `ProcessCompleted` (looping, `Light #B72`, on-texture) so `advanceProcessing` can set them.
**Caveat:** copy the model + both textures into the asset tree (`Common/Blocks/.../CC_Smelter.blockymodel` + PNGs) from `models/_cc_machines/`; align the `CustomModel`/`CustomModelTexture` paths.
**Verify (devServer):** `./gradlew runServer`; give yourself `CC_Smelter`, place it. Expected: renders at furnace scale, claims filler cells, NESW rotation works, no obstruction crash, Interact-F opens the (old) panel. Commit: `git commit -am "feat(smelter): CC_Smelter block/item asset"`

### Task 12: Place-time bench config
**Files:** Likely modify the place path (a `PlaceEventSystem` or the component's post-decode init) so a freshly placed `MachineBlockState` for `CC_Smelter` gets a `VanillaBenchBridge.create(blockType)` held bench + `SmelterPorts.defaults()` if absent. Check how tanks initialize their buffers on place for the existing pattern.
**Verify (devServer):** place, Interact-F, confirm the component has a held bench (log it). Commit: `git commit -am "feat(smelter): initialize held bench + ports on placement"`

---

## Phase 5 — Pipe I/O (filler-aware)

### Task 13: Filler→anchor in endpoint collection + wrench
**Files:** Modify the endpoint collectors (`impl/block/net/NetworkEndpoints.java` / `EndpointAdapters.java` / `WorldMachineLookup.java`) and `WrenchInteraction.java`: before reading a neighbor cell's `MachineBlockState`, if `BlockModule.getBlockEntity` is null, resolve `anchor = pos − FillerOffset.unpack(getFiller(pos))` and read the anchor. Use the engine's `getFiller`/`FillerBlockUtil` accessor (confirm in jar).
**Step 1 (test):** unit-test the resolution helper (`AnchorResolver.resolve(world, pos)`) with a faked grid if feasible; otherwise devServer-verify in Task 14.
**Verify:** covered by Task 14. Commit: `git commit -am "feat(smelter): resolve filler cells to anchor for transport + wrench"`

### Task 14: Bind ITEM ports → bench containers, POWER → buffer
**Files:** Modify the ITEM endpoint adapter so an INPUT port targets `VanillaBenchBridge.input(heldBench)` and an OUTPUT port targets `output(heldBench)`; confirm the POWER adapter already targets `EnergyBuffer` (it does for the network). Reuse existing fair-split/endpoint machinery.
**Verify (devServer):** ITEM pipe on West feeds input; ITEM pipe on East drains output to a chest/tank; POWER pipe (creative source) on North charges. A wrong-channel pipe on a port face idles. Commit: `git commit -am "feat(smelter): item pipes feed/drain held bench, power pipe charges buffer"`

---

## Phase 6 — GUI (display-only) and end-to-end

### Task 15: Furnace-shaped display-only page
**Files:** Modify `src/main/resources/Common/UI/Custom/Pages/CC_MachinePanel.ui`, `impl/block/ui/MachinePanelPage.java`, `impl/block/ui/PanelSnapshot.java`. Per @hytale-ui-items, add 2 input + 4 output `ItemSlot`s (read from `input()/output()` containers), a smelt-progress bar (bench progress / recipe duration), and an energy gauge (`EnergyBuffer.getFillRatio()`). **Display-only:** no click handlers that move items (D-S4). Reuse the live-refresh snapshot/delta path; every row sets visibility each pass.
**Step 1 (test):** extend `PanelSnapshot`'s unit test with a smelter snapshot asserting the slot/progress/energy fields render from a given state.
**Verify (devServer):** Interact-F shows slots tracking pipe-fed items, progress advancing, energy draining; slots reject hand-insert. Commit: `git commit -am "feat(smelter): furnace-shaped display-only GUI"`

### Task 16: Full end-to-end run (design's test plan)
**Step 1:** `./gradlew runServer`. Build a rig: creative-source power block → POWER pipe → Smelter North; ITEM pipe (from a chest of ore) → Smelter West; Smelter East → ITEM pipe → output chest.
**Step 2:** Verify in order (design §"Test plan"): (1) coils light + `Processing` state while powered+fed; (2) ingots appear in output and flow out the East pipe; (3) cut power → freeze, progress retained; restore → resume; (4) break the anchor → `BlockHolder` carry retains state (existing carry path); (5) GUI tracks everything; (6) wrong-channel pipe idles.
**Step 3:** `./gradlew test -q` — full suite green.
**Step 4:** Commit: `git commit -am "test(smelter): end-to-end rig verified"`

---

## Done criteria
- `./gradlew test` green; `./gradlew runServer` rig smelts ore using only piped power + item I/O; GUI is monitor-only; no hand-insert; progress survives power loss and block carry.
- Then: @superpowers:requesting-code-review, then @superpowers:finishing-a-development-branch.

## Deferred (not this plan)
Overclock (D33), signed/exothermic recipe energy, bench tiers, Eject button + On/Off switch (v1.1), the other seven benches, real generators.
```
