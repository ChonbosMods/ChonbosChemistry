> **HISTORICAL / SUPERSEDED.** This doc is retained as detail/derivation only. The authoritative design is [docs/design.md](../design.md) (see its §0 supersession map).

# Live-Refresh Machine/Pipe Panel — Design

*Status: validated 2026-06-05. First of the transport quick-wins (branch `chore/transport-quick-wins`). Upgrades the snapshot-only `MachinePanelPage` (Task B4) to a live panel.*

## Decisions

- **Cadence: every 10 ticks** (~2 updates/sec). Live feel without a packet per tick per open panel.
- **Stale block: auto-close.** If the viewed block (or a pipe's network) disappears while the panel is open, the panel closes itself via `CustomUIPage.close()`. No lingering stale UI.

## Architecture

Three pieces:

1. **`PanelRefreshService`** (plugin-scoped, lives next to `NetworkService`): a per-world registry of currently-open `MachinePanelPage` instances. Pages self-register at the end of a successful `build()` (passing their world, resolved once from `blockRef`'s external `ChunkStore`) and deregister in `onDismiss()`.

2. **`PanelRefreshSystem extends TickingSystem<EntityStore>`**: a query-less system (plain `tick(dt, idx, store)`) registered on the entity-store registry, so it pulses once per world per tick on that world's WorldThread — the same thread `build()` runs on, so the existing safety reasoning (incl. `NetworkManager.getOrBuildNetwork` on the WorldThread) carries over unchanged. Every 10th tick (per-world counter, `IdentityHashMap`, same pattern as `NetworkTickSystem`'s per-world tick tracking) it calls `service.refreshWorld(world)`.

3. **`MachinePanelPage.refresh()`**: constructs a fresh `UICommandBuilder` (public no-arg ctor, verified against Server 0.5.3), re-runs the populate path *without* the template `append`, and calls `sendUpdate(builder)` — a pure delta of `set` commands. Invalid `blockRef` or unresolvable pipe network → `close()` + deregister.

**Rejected alternatives:** piggybacking `NetworkTickSystem`/`MachineTickSystem` (only ticks where pipes/machines are loaded → a panel can silently freeze; mixes UI into transport); wall-clock executor + `World.execute` (the §16.8 anti-pattern).

**Required refactor:** populate methods must set each row's visibility explicitly (true *and* false) every pass. Today rows are only toggled on — fine for a one-shot snapshot, stale for live (a buffer that empties to null would leave its old row frozen).

## Lifecycle

- `build()` succeeds → register with service. Empty-state pages ("not a chemistry block", invalid ref at open) never register; they stay static snapshots.
- Every 10th tick per world: `PanelRefreshSystem` → `service.refreshWorld(world)` → each page's `refresh()`.
- `onDismiss()` → deregister. `refresh()` failure path → `close()` + deregister (idempotent in either order).

## Edge cases

- **Block broken mid-view** → ref invalid → auto-close.
- **Player disconnect** → engine dismisses pages → `onDismiss()` → deregistered. Guard: refresh also drops pages whose `PlayerRef` is invalid.
- **Never throws:** refresh wraps the same null-guarded populate path; any failure = close, not crash.

## Testing

- `PanelRefreshService`: pure registry logic (register/deregister/refresh-world, idempotency, per-world isolation) — unit tests.
- Row population: extract into a pure renderer so tests assert every row gets an explicit visible true/false each pass.
- `TickingSystem` driver + `sendUpdate`: thin engine glue, verified in-game (open panel on the power-cell rig, watch the sink drain live; break the machine while open, panel closes).
