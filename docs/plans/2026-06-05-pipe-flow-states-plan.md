> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# Pipe Flow States + I/O Wrench Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
> Design doc (read first): `docs/plans/2026-06-05-pipe-flow-states-design.md`.

**Goal:** Per-face pipe flow states (NORMAL/PUSH/PULL/NONE) with auto-NONE between different-substance lines, face-precise endpoint filtering, programmatic suppressed-arm visuals, and the CC_Wrench that configures pipe faces and machine ports.

**Architecture:** Pure logic first (enum, PipeNode faces, connection predicate, BFS gating, endpoint filter, wrench cycles: all headless-TDD), then engine glue (interaction spike, wrench item, programmatic shapes, panel row). Face indexing everywhere uses the `OFFSETS` convention `+X,-X,+Y,-Y,+Z,-Z`; `opposite(face) == face ^ 1`.

**Tech stack:** Hytale Server 0.5.3 ECS + codecs; JUnit 5 headless suite (`./gradlew test`); existing net-layer fakes (`FakePipeGrid`, `FakeLookup`).

**Conventions:** TDD per superpowers:test-driven-development (RED → verify → GREEN → verify → commit). Run a single test class with `./gradlew test --tests "*ClassName*" --console=plain`. Full suite must stay green before every commit. Commit messages: no Co-Authored-By, `:` not em-dash.

---

### Task 1: `FlowState` enum + codec

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/api/io/FlowState.java`
- Test: `src/test/java/com/chonbosmods/chemistry/api/io/FlowStateTest.java`

**Step 1: failing test.** Mirror `PortChannel`'s codec pattern (look at `api/io/PortChannel.java` for the `StringMappedCodec` usage). Test: 4 values exist; codec round-trips each (`FlowState.CODEC.decode(FlowState.CODEC.encode(v, EmptyExtraInfo.EMPTY), EmptyExtraInfo.EMPTY) == v`); encoded form is the lowercase name (consistency with PortChannel/PortDirection).

**Step 2:** run, expect compile failure. **Step 3:** implement enum `NORMAL, PUSH, PULL, NONE` + `CODEC` (copy PortChannel's codec shape exactly). **Step 4:** green. **Step 5:** commit `feat(io): FlowState enum (normal/push/pull/none) + codec`.

### Task 2: `PipeNode` per-face flow states

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/net/PipeNode.java`
- Test: extend `src/test/java/com/chonbosmods/chemistry/impl/block/net/PipeNodeTest.java`

**Step 1: failing tests** (three):
1. `flowStatesDefaultToNormalAndAbsentKeyDecodesAsAllNormal`: fresh node → `flowState(i) == NORMAL` for i 0..5; encode a node with defaults → decode → still all NORMAL; ALSO decode a hand-built BsonDocument WITHOUT the "FlowStates" key (take an encoded doc and `doc.remove("FlowStates")`) → all NORMAL. This guards the no-migration guarantee.
2. `flowStatesRoundTripThroughCodec`: set face 2 PUSH, face 5 NONE → encode/decode → preserved, others NORMAL.
3. `cloneCopiesFlowStatesIndependently`: clone, mutate original face, clone unchanged.

**Step 3: implementation.** Field `FlowState[] flowStates = allNormal()`; accessors `flowState(int face)`, `setFlowState(int face, FlowState s)` (clamp/ignore out-of-range defensively). Codec: OPTIONAL key `"FlowStates"` as a list codec (check how a list/array codec is built: look at `Codec` for `listOf`/array support, or encode as 6 chars/strings; follow whatever `OptionalLongCodec`-style precedent fits: the key MUST be optional, 3-arg `KeyedCodec(..., false)`, so absent leaves the all-NORMAL default, exactly like `ResourceId`). Encode: omit the key when all NORMAL (keeps existing saves byte-identical).

**Step 5:** commit `feat(net): PipeNode per-face flow states, optional codec key (absent = all normal)`.

### Task 3: connection predicate `PipeConnectivity`

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/net/PipeConnectivity.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/net/PipeConnectivityTest.java`

**Step 1: failing tests.** Pure static `boolean connects(PipeNode a, int faceFromAToB, PipeNode b)`. Truth table:
- both NORMAL, both resourceId null → true
- both NORMAL, same resourceId → true
- both NORMAL, a="hydrogen" b=null → true (empty line joins a locked one)
- both NORMAL, a="hydrogen" b="carbon_dioxide" → **false** (the gas bug)
- a's face NONE → false regardless; b's OPPOSITE face (`faceFromAToB ^ 1`) NONE → false
- PUSH/PULL on a pipe-pipe face behaves as NORMAL (design: treated as NORMAL)
- different channel → false (keep the existing check here too so the BFS has one gate)
- `opposite()` helper: `opposite(0)==1, opposite(2)==3, opposite(4)==5`

**Step 3:** implement (~25 lines). **Step 5:** commit `feat(net): PipeConnectivity face-pair predicate: flow states + substance compat (auto-none on mismatch)`.

### Task 4: BFS gating in `NetworkManager`

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/net/NetworkManager.java` (the flood-fill at ~ln 129-170: replace the bare `neighbour.channel() != channel` check with `PipeConnectivity.connects(node, faceIdx, neighbour)`; the OFFSETS loop index IS the face index)
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/net/NetworkSplitTest.java` (new)

**Step 1: failing tests** (use `FakePipeGrid` from `NetworkBufferPersistenceTest`: consider extracting it to a shared test helper `net/TestPipeGrids.java` first if duplication bothers: small, copy is fine):
1. `noneFaceSplitsRun`: 3-pipe row, middle pipe's +X face NONE → `getOrBuildNetwork(0,0,0)` has 2 members; `getOrBuildNetwork(2,0,0)` has 1.
2. `differentSubstancesDoNotMerge`: two 2-pipe FLUID runs adjacent, left pair resourceId "element:bromine" shares, right pair "compound:ethanol" → networks stay separate (2 members each), locks intact.
3. `emptyLineJoinsLockedLine`: left pair locked, right pair empty (null ids) → ONE 4-member network, lock = left's substance.
4. `oppositeFaceNoneAlsoSplits`: NONE set on the receiving side's face.

**Step 3:** swap the gate in the BFS (also in the `collectEndpoints`-side adjacency if the same walk exists elsewhere: grep for `channel() != channel`). **Step 4:** FULL suite (WipeRecovery + persistence tests must stay green). **Step 5:** commit `feat(net): BFS honors flow states + substance compatibility: different-substance lines no longer merge`.

### Task 5: drain-remerge invalidation

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/net/NetworkManager.java` (`writeBackShares`: return or expose whether the network's lock CLEARED this write-back: previous non-null → now null)
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/net/NetworkTickSystem.java` (on lock-clear: invalidate the network's member positions so next access rebuilds and merges)
- Test: extend `NetworkSplitTest`

**Step 1: failing test.** `drainedLineRemergesAfterLockClears`: two adjacent locked-different runs (separate networks); drain the right network to 0 via `extract`, run write-back; assert manager invalidated (e.g. `cachedNetworkCount()` drops / next `getOrBuildNetwork` returns ONE merged network). Design the seam so the pure part is testable without the tick system: e.g. `NetworkManager.writeBackShares` returns `boolean lockCleared`, and a new `NetworkManager.invalidateMembers(Network)` is called by the caller: test those two directly.

**Step 5:** commit `feat(net): drained fluid/gas networks re-merge: lock-clear during write-back invalidates members`.

### Task 5b (ADDED during execution): flow states survive the engine's connected-block wipe

**Why (found during Task 2):** the engine's connected-block neighbour pass replaces re-resolved pipes' block entities with fresh template clones (the H8 wipe). `PipeSnapshotScan`/`PipeNodeSnapshots` snapshot+restore only `bufferShare`/`resourceId`: a fresh clone has all-NORMAL `flowStates`, so ANY place/break near a wrenched pipe would silently factory-reset its face config.

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/net/PipeNodeSnapshots.java` (snapshot records gain the 6 flow states; restore re-applies them)
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/net/PipeSnapshotScan.java` (capture flow states; widen the snapshot-worthiness test: a pipe with any non-NORMAL face is snapshot-worthy even with share==0/resourceId==null)
- Test: extend `src/test/java/com/chonbosmods/chemistry/impl/block/net/WipeRecoveryTest.java`

**Steps (TDD):** failing test first: snapshot a pipe with face 0 NONE + share 0; simulate the wipe (fresh all-NORMAL node, share 0, resourceId null); restore; assert face 0 NONE again AND that a never-wrenched wiped pipe behaves exactly as before (no regression in the 8 existing WipeRecovery cases). CAREFUL with the restore signature: the wipe signature for SHARES stays `share==0 && resourceId==null`; flow-state restore applies on the same signature but only overwrites faces when the snapshot carries a non-all-NORMAL config (never stomp a legitimately re-wrenched pipe with a stale all-NORMAL snapshot). Commit: `fix(net): wrenched pipe flow states survive the connected-block wipe`.

### Task 6: face-precise endpoint collection + flow filter

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/net/NetworkEndpoints.java` (collect: it already walks `OFFSETS` per member pipe: the loop index is the pipe's face toward the neighbour; machine's facing port = port whose `Face` equals the OPPOSITE index. Replace "any matching-channel port qualifies" with the face port; then apply the pipe face's FlowState filter)
- Test: extend `src/test/java/com/chonbosmods/chemistry/impl/block/net/NetworkEndpointsTest.java`

**Step 1: failing tests:**
1. `portOnWrongFaceDoesNotQualify`: machine at +X of pipe with its only OUTPUT port on face index 0 (+X side: i.e. facing AWAY from the pipe) → not collected. (Pick indices carefully: pipe at (5,5,5), machine at (6,5,5): pipe reaches it via face 0 (+X); the machine's port facing the pipe is face 1 (-X).)
2. `noneFaceHidesMachine`: pipe face 0 = NONE → machine not collected at all.
3. `pushFaceKeepsAcceptorHalfOnly`: storage machine (BOTH directions on the facing port? after this task a face has ONE port: use an INPUT port machine and an OUTPUT port machine variants) → with PUSH: acceptor collected, provider NOT. With PULL: inverse.
4. `storagePairsOnlyWithNormalTwoWayAccess`: a tank reached via PULL face appears in `bufferProviders` (or pure? decide: provider-only, NOT in `storages()`); via NORMAL + port BOTH-capable → full `StorageEndpoint`.

NOTE: today "storage" = a neighbour with both an OUTPUT and an INPUT port (any faces). Under one-port-per-face, a storage block exposes... decide in implementation: a block whose facing port is declared with `PortDirection` BOTH? Check `PortDirection` values: if no BOTH exists, storage rigs declare TWO ports on... they can't (one per face). **Resolution (do this first in this task):** add `PortDirection.BOTH` if absent, update `CC_EnergyStorage`/`CC_FluidTank`/`CC_GasTank` rig JSONs so each face's single port is `both`, and classify: facing port `both` → storage endpoint; `output` → provider; `input` → acceptor. Update `NetworkEndpointsTest` fixtures accordingly. This is the real shape of the one-port-per-face revision: surface it in the commit message.

**Step 4:** FULL suite: priority/balancing tests use `NetworkEndpoints.Endpoints` fixtures: update fixture port configs where they relied on any-port-qualifies.

**Step 5:** commit `feat(net): face-precise endpoint collection: facing port only, flow-state filter (push=acceptor-only, pull=provider-only), PortDirection.BOTH for storage faces`.

### Task 7: wrench cycle logic (pure)

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/WrenchCycles.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/WrenchCyclesTest.java`

**Step 1: failing tests:**
1. Pipe toward machine: `next(NORMAL, toward=MACHINE)` → PUSH → PULL → NONE → NORMAL; `previous(...)` reverses.
2. Pipe toward pipe/air: NORMAL → NONE → NORMAL (skip push/pull).
3. Machine capability cycle: given a capability list built from a `MachineBlockState` (energy buffer → POWER, each non-null resource buffer → its channel), `nextPort(current)` walks `(ch1,in) → (ch1,out) → (ch2,in) → ... → closed → wrap`. Closed modeled as... check how `Port`/`PortConfig` represents absence: likely remove the port or a CLOSED direction: pick what `PortConfig` supports (look at `PortDirection`: if INPUT/OUTPUT/BOTH only, "closed" = port removed from config; the cycle function returns an `Optional<Port>`).

**Step 5:** commit `feat(block): WrenchCycles pure cycle logic for pipe faces and machine capability ports`.

### Task 8: ENGINE SPIKE: clicked face + sneak (no production code)

**Files:** none (findings go into the next tasks + a NOTE appended to the design doc).

**Steps:** decompile in `/tmp/bh-spike` (already extracted; else re-unzip the server jar):
1. `java -jar ~/.local/share/cfr/cfr.jar /tmp/bh-spike/com/hypixel/hytale/server/core/modules/interaction/InteractionContext.class --comments false` → look for clicked face / hit normal / block face fields.
2. Decompile HyProTech's `interaction/CableSideToolInteraction` (full source in `ReferenceJars/HyProTech/src/...`: just read it): how it derives the clicked side.
3. Check sneak: grep `InteractionContext`/player component for sneak/crouch accessors.
4. Append findings (exact accessor names) to `docs/plans/2026-06-05-pipe-flow-states-design.md` under a "Spike findings" heading; commit `docs: flow-states spike findings: clicked face + sneak accessors`.

### Task 9: CC_Wrench item + interaction glue

**Files:**
- Create: `src/main/resources/Server/Item/Items/ChonbosMods/CC_Wrench.json` (plain tool item; copy a simple existing item's frame; `Interactions.Use` wiring per how OpenCustomUI items do it, but pointing at the new interaction: follow the §16.5 registration pattern: `RegisterAssetStoreEvent` → register interaction under a codec name; see HyProTech `interaction/*` registration)
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/WrenchInteraction.java` (`extends SimpleBlockInteraction`: resolve target block entity: PipeNode → face cycle via `WrenchCycles` + persist + `networkService.forWorld(world).invalidate(...)` + chat `Message.raw("Pipe face East: PUSH")`; MachineBlockState → port cycle + persist; everything defensive/no-throw)
- Modify: `src/main/java/com/chonbosmods/chemistry/ChonbosChemistry.java` (registration)

Engine glue: no unit tests (in-game verification). Keep ALL decision logic in `WrenchCycles` (tested); this class only: resolve face (spike findings), read component, call pure function, write back, invalidate, message. **Commit:** `feat(block): CC_Wrench item + interaction: cycles pipe face flow states and machine ports`.

### Task 10: effective-topology shape resolution (pure)

**Files:**
- Create: `src/main/java/com/chonbosmods/chemistry/impl/block/net/PipeShapes.java`
- Test: `src/test/java/com/chonbosmods/chemistry/impl/block/net/PipeShapesTest.java`

**Step 1: failing tests.** Pure mapping `String stateFor(int connectedFacesBitmask, boolean energized)` → the pipe item state key (the `TemplateShapeBlockPatterns` keys: `node`, `straight`, `elbow`, `tee`, ..., `_on` twins). Build the 64-entry table from the 26 shapes (the pattern JSON in `CC_PowerCable.json` is the source of truth: read it to extract which face-set each shape+orientation covers: orientations matter: see CAVEAT). Tests: known cases (`0b000000`→node, `+X|-X`→straight, `+X|-X|+Y`→a vertical-tee variant, all six→six) + exhaustive: every 6-bit mask resolves to SOME state, no nulls.

**CAVEAT (resolve during implementation):** programmatic states may not carry orientation: H8's `_on` flips reuse the block's existing rotation. Investigate how `setBlockInteractionState` + the 53-state map encode orientation (the pattern map has one key per shape, orientation comes from pattern matching). If orientation CANNOT be set programmatically: **fallback ladder:** (a) use `ConnectedBlocksUtil.setConnectedBlockAndNotifyNeighbors` to re-trigger engine resolution and accept that suppressed arms only display correctly for orientation-free shapes (node, six, straight pairs); (b) visual-only compromise: logic disconnects (already done in Task 4) but geometry stays welded until a model pass: document whichever lands in the design doc. Do NOT block the feature on visuals: transport correctness shipped in Tasks 3-6 regardless.

**Commit:** `feat(net): PipeShapes effective-topology state table (+ orientation findings)`.

### Task 11: programmatic suppressed-arm visuals (glue)

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/net/PipePowerStates.java` (or a sibling `PipeVisualStates`): on invalidation/rebuild (the same H8 hook points: place/break/wrench events + `NetworkTickSystem` energized flips), for any pipe whose effective connectivity differs from its physical neighbours (NONE faces or substance mismatch), set the `PipeShapes` state; all-NORMAL unmismatched pipes stay pattern-driven.

In-game verification (no unit tests): two different-gas lines side by side do not weld visually; wrench-NONE drops the arm; drain → arms reconnect after the remerge event. **Commit:** `feat(net): programmatic suppressed-arm pipe visuals (none faces + substance mismatch)`.

### Task 12: end-stub push/pull indicator states

**Files:**
- Modify: `src/main/resources/Server/Item/Items/ChonbosMods/CC_PowerCable.json`, `CC_FluidPipe.json`, `CC_GasPipe.json`: add 12 states each (`end_push`, `end_pull`, `vertical_end_up_push`, `vertical_end_up_pull`, `vertical_end_down_push`, `vertical_end_down_pull` + `_on` twins). Until the art lands, point them at the EXISTING end models (placeholder: state exists, looks like a plain end): the user's 6 new models per family then replace the model paths in place.
- Modify: `PipeShapes`/visual glue: when an end-shape pipe's single arm faces a machine and that face is PUSH/PULL, emit the indicator state instead of plain end.

**Commit:** `feat(assets): push/pull end-stub indicator states (placeholder models pending art)`.

### Task 13: panel per-face row

**Files:**
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/ui/PanelSnapshot.java` (pipe snapshot gains a faces row on the spare `#GasLabel` selector: `Faces: N pull · S none` listing only non-NORMAL faces, or `Faces: all normal`)
- Modify: `src/main/java/com/chonbosmods/chemistry/impl/block/ui/MachinePanelPage.java` (pass the `PipeNode` into `PanelSnapshot.forNetwork(...)` → new overload `forNetwork(Network, PipeNode)`)
- Test: extend `src/test/java/com/chonbosmods/chemistry/impl/block/ui/PanelSnapshotTest.java` (faces row text incl. all-normal case; row hidden... no: always visible for pipes)

TDD as usual. Face names: derive from the OFFSETS order: `E,W,U,D,S,N`? **Use the OFFSETS-order names `+X east, -X west, +Y up, -Y down, +Z south, -Z north`** and KEEP CONSISTENT with whatever the wrench chat feedback uses (extract a shared `FaceNames.name(int)` helper into `api/io` or `impl/block`). **Commit:** `feat(ui): pipe panel shows per-face flow states`.

### Task 14: main design-doc edits + status

**Files:**
- Modify: `docs/machines-and-power-design.md`: (1) §5.4 rewrite: one port per face, multiblock machines (≥4×4) make faces plentiful, wrench cycles capability pairs, GUI later; (2) §13 add flow-state subsection; (3) §0: face-index alignment done via this feature; add flow-states to the status block.

**Commit:** `docs: flow states integrated: §5.4 one-port-per-face revision, §13 flow-state subsection, §0 status`.

### Task 15: final verification

Full suite green; in-game checklist (user): different-gas lines stay separate (logic + visuals), wrench cycles all faces with correct feedback + sneak reverse, push/pull endpoint filtering visible in machine fill behavior, drain-remerge works, restart preserves flow states, end-stub indicator states render (placeholder geometry). Then merge per superpowers:finishing-a-development-branch.
