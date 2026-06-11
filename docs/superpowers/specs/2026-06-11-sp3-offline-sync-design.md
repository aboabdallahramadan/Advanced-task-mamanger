# SP3 — Offline-First Sync Engine — Design

**Date:** 2026-06-11
**Status:** Approved (design, v2 after adversarial review)
**Part of:** `2026-06-01-online-multiplatform-roadmap.md`

> Produced through the brainstorming flow: codebase/docs exploration, five scope decisions
> locked with the user, four design sections approved individually, then a three-lens
> adversarial review (codebase facts, distributed-systems soundness, completeness) whose
> confirmed findings produced v2 — changes listed at the end. Builds directly on the sync
> primitives SP1 shipped (`change_seq` trigger, tombstones, fractional `rank`, client UUIDs)
> and the `DataClient` seam SP2 froze for exactly this purpose.

## Goal

A signed-in user keeps working with full CRUD while offline — on the desktop app (including
cold start) and in an already-open web tab — and the app reconciles automatically when the
network returns. Both clients converge to the same state across devices using the LWW
semantics SP1 locked per entity. No UI redesign: existing components are untouched except
two small additions (the sync-status pill, §8, and an explicit-logout surface, §7.2); the
Zustand store changes are narrow and enumerated (§7.1).

## Locked decisions (brainstorming)

1. **Offline scope: desktop + open web tab.** One shared offline store in `packages/app`
   serves both shells. Desktop works fully offline (file:// shell launches from disk). Web
   keeps working if the network drops while the app is open; cold-starting the web app
   offline (PWA/service worker) is deferred to SP5 with hosting.
2. **Offline-first architecture.** The local store is the source of truth at all times. All
   reads come from it; all writes apply to it instantly and enqueue for push. One code path
   online or offline — no mode switching.
3. **Push = op replay over the existing REST API.** A durable FIFO op queue is replayed
   through the existing endpoints, which already own the hard semantics (cascade tombstones,
   recurrence series logic, validation, RLS). The batch `POST /api/v1/sync` from the SP1
   sketch is not built; SP1 explicitly marked it deferred/additive.
4. **Recurrence: coast on the horizon.** Instance generation stays server-only. Online, the
   app maintains the existing rolling ~14-day pre-materialized window; offline stretches
   coast on it. A new series created offline queues; its instances appear on reconnect
   (via the post-push ensure-instances trigger, §4.3).
5. **Engine placement: `LocalDataClient` + `SyncEngine` in `packages/app`** behind the
   existing `DataClient` seam (the insertion point SP2 designed). Refinement over SP2's
   "wrap `HttpDataClient`" forward note: `HttpDataClient` is **retired**, not wrapped — its
   reorder/create logic depends on an in-memory id→rank cache populated by its own reads,
   and in SP3 it performs no reads. `LocalDataClient` takes over mapping + rank assignment
   (reusing `mappers.ts` / `ranking.ts`); the queue stores wire-shaped requests.

## Pinned constants

| Constant | Value | Where used |
|---|---|---|
| Pull page size | 500 | §4 |
| Cursor overlap per cycle | 5000 seqs | §4.1 |
| Post-write push debounce | 2 s | §3.4 |
| Periodic sync interval (online) | 60 s | §3.4 |
| 5xx retries per cycle | 3 (backoff 1 s / 2 s / 4 s) | §3.3 |
| Poison-op park threshold | 10 total attempts across cycles | §3.3 |

## 1. Architecture

```
Zustand store ──▶ DataClient seam ──▶ LocalDataClient ──▶ LocalStore (Dexie/IndexedDB, per-user DB)
                                            │                    ▲
                                            └─ enqueue op ─▶ ops table (same Dexie transaction)
                                                                 │
                       SyncEngine: push ops FIFO via refresh-wrapped TmapClient ──▶ REST API
                                   (post-push ensure-instances when needed, §4.3)
                                   then pull GET /api/v1/sync?since=cursor (paged)
                                   apply deltas ──▶ LocalStore ──▶ notify ──▶ store.refreshFromLocal()
```

New modules (all in `packages/app` unless noted):

| Module | Responsibility |
|---|---|
| `src/data/local/LocalStore.ts` | Dexie database `tmap-{userId}`; tables, transactions, meta |
| `src/data/local/LocalDataClient.ts` | Implements `DataClient`; reads/writes Dexie; maps domain⇄DTO at the edge; owns rank assignment + tie repair; **diff-at-enqueue** for updates; builds the local `ReportData` projection |
| `src/data/local/applyPull.ts` | Applies a pull page (upsert/tombstone-delete by per-table key, shadow rule) |
| `src/sync/SyncEngine.ts` | Single-flight sync cycle (Web Locks cross-tab), triggers, failure handling, recovery, status |
| `src/sync/connectivity.ts` | `navigator.onLine` + `online`/`offline` events + sync outcomes |
| `backend Features/Sync/` | `GET /api/v1/sync` endpoint + sync DTOs + `(user_id, change_seq)` index migration |

Data flow, local action (e.g. rename a task): store action → `LocalDataClient.tasks.update`
→ diff against the current local row → Dexie row updated + op enqueued (one transaction) →
updated entity returned to the store. Identical online or offline. ~2 s later the engine
pushes; the next pull is a no-op for it.

Data flow, remote change: pull delivers rows with `changeSeq >` cursor (tombstones included)
→ `applyPull` → engine emits `changesApplied` → AppRoot dispatches the new store action
`refreshFromLocal()`, which re-reads collections through the seam **without side effects**.
Rollover and ensure-instances are never triggered by `refreshFromLocal()`; where they live
and when they run is pinned in §7.1.

`HttpDataClient.ts` (and its tests) are deleted; `mappers.ts` and `ranking.ts` live on as
`LocalDataClient`'s mapping/ranking layer. The `DataClient` interface itself is unchanged.

## 2. Local store

Dexie database `tmap-{userId}` (multi-account segregation by name), schema version 1:

| Table | Key | Indexes | Notes |
|---|---|---|---|
| `tasks` | `id` | `plannedDate`, `status`, `recurrenceRuleId` | Server-DTO-shaped rows, no nested subtasks |
| `subtasks` | `id` | `taskId` | Joined onto tasks at read time, like the server does |
| `projects` | `id` | — | Rank strings stored verbatim |
| `noteGroups` | `id` | — | |
| `notes` | `id` | `groupId`, `projectId` | |
| `recurrenceRules` | `id` | — | |
| `focusSessions` | `id` | `date` | Local creations + pulled history (feeds offline reports) |
| `dailyPlans` | `date` | — | |
| `settings` | `key` | — | The three synced keys as rows |
| `ops` | `seq` (auto-inc) | `*entityKeys` (multiEntry) | Durable write queue |
| `issues` | `id` (auto-inc) | — | Persisted record of dropped/parked ops (§3.3) |
| `meta` | `key` | — | `syncCursor`, `initialSyncComplete`, `lastSyncedAt`, `lastUser {id,email,timeZoneId}` |

One value lives **outside** the per-user DB: `localStorage['tmap:lastUserId']`, written on
every successful auth and cleared on explicit logout. It breaks the chicken-and-egg at
offline bootstrap (§7.2): the app must know which `tmap-{userId}` DB to open before any
network identity is available.

Rows are stored in **server DTO shape** (the `schema.d.ts` wire shape: rank strings,
PascalCase enums, server timestamps, `changeSeq`). This is what makes the existing mappers
and the byte-compatible `ranking.ts` directly reusable, and makes pull application a plain
upsert with no translation.

Local tables hold **live rows only**: applying a pulled tombstone deletes the local row;
a local delete removes the row immediately (the op queue carries the intent to the server).

**Atomicity:** every local mutation commits its entity-table change(s) and its op append in
one Dexie transaction. There is no state where the UI shows a change the queue doesn't know
about. Cascades are applied locally to match what server reconciliation will produce: task
delete tombstones its subtasks; note-group delete removes its notes; project delete clears
`projectId` on children.

**Web storage durability:** the engine requests `navigator.storage.persist()` on web. If the
browser still evicts the DB, the device behaves like a fresh one (full re-sync from
`since=0`); unpushed ops are lost — accepted v1 risk, listed under Risks.

## 3. Op queue and replay

### 3.1 Op shape and enqueue rules

An op is a **wire-shaped request** recorded at enqueue time:

```
{ seq, method: 'POST'|'PATCH'|'PUT'|'DELETE', path: '/api/v1/...', body?,
  entityKeys: string[],   // e.g. 'tasks:<uuid>', 'dailyPlans:2026-06-11', 'settings:workStartHour'
  attempts: number, lastError?: string }
```

`entityKeys` uses each table's §2 primary key (uuid for uuid entities, `date` for
dailyPlans, `key` for settings) — the same keys the §4 apply/shadow rules match on.

`LocalDataClient` resolves everything request-shaped at enqueue: domain→DTO mapping
(`mappers.ts`), rank strings for creates and reorders (`ranking.ts` over local neighbor
rows, §5.1), and client UUIDs for creates. Replay is then a dumb loop: send each request
through the refresh-wrapped `TmapClient`. No re-mapping at push time, no stateful caches.

**Diff-at-enqueue (the rule that makes field-level LWW real):** for every update,
`LocalDataClient` computes the true field diff against the current local row inside the same
Dexie transaction and enqueues **only changed fields**, preserving the SF-2
explicit-null-to-clear semantics (a cleared field diffs non-null→null and is sent as `null`).
Without this, `TaskDetailDialog`'s whole-form save would freeze a stale snapshot of every
field into the queue and revert other devices' edits on push. The same rule applies to
settings: the queued `PUT /settings` carries only changed keys (and `timeZoneId` only when
changed), which is what makes SP1's per-key settings LWW actually hold.

No coalescing in v1 — redundant replayed PATCHes are harmless; coalescing is a deferred
optimization.

### 3.2 Idempotent replay

Crash/network loss between server-apply and op-dequeue is the canonical hazard; every op
type must tolerate being sent twice:

| Op kind | Why replay is safe |
|---|---|
| Creates | Same client UUID; backend touch-up returns 200 + existing row on owned-PK conflict (today: unmapped 500) |
| Updates | Re-PATCHing the same fields is a no-op |
| Deletes | 404 treated as success (already tombstoned) |
| Reorders | Re-sending the same ranks is a no-op |
| `PUT` upserts (plans, settings) | Naturally idempotent |
| Focus-session creates | Client id (backend touch-up); today the endpoint silently *ignores* client ids and generates its own (`FocusSessionsEndpoints.cs:28-32`), so a replay would silently duplicate the session — the strongest argument for the touch-up |

### 3.3 Push failure taxonomy

FIFO, single-flight; order is never violated. **A halted push never blocks pull:** every
cycle runs its pull phase even when the push phase aborted — the shadow rule (§4.2) makes
pulling with pending ops safe.

| Failure | Handling |
|---|---|
| Network error | Abort push phase; queue intact; retry on next trigger |
| 401 | Refresh wrapper handles (§7.3); only a true 401 from the refresh endpoint is terminal |
| 409 unique-name conflict on a create (projects) | **Adopt the existing row:** the 409 body carries the existing row's id (backend touch-up §9.1); the client rewrites the ghost local id → existing id across local rows and all queued op bodies/paths/entityKeys, drops the create op, and records an issue. The user's offline work lands in the server's same-named entity instead of wedging the queue |
| 400 / 404 / other definitive rejections | Drop the op, record a **persisted issue** (§2 `issues` table), then run **rejection recovery**: for a rejected create, delete the local rows it created and drop queued ops that reference them (each recorded as an issue); in all cases schedule a **recovery pull from `since=0`** at the end of the cycle (idempotent, cheap at this scale; the stored cursor never regresses — it ends at `max(prior, final nextSince)`). This snaps local state back to server truth, including tombstones whose pull was shadow-skipped earlier (§4.2). 404 on an update/delete is the edit-vs-delete conflict: the entity was deleted remotely — **delete wins** |
| 5xx | Up to 3 retries within the cycle (backoff 1 s / 2 s / 4 s), then abort the push phase; resume on the next trigger. After 10 total attempts across cycles the op is **parked**: moved to `issues` with retry/discard affordances in the §8 popover (discard runs rejection recovery). Parking prevents a deterministic 500 from freezing the queue forever |

### 3.4 Sync triggers

The engine runs a cycle on: (a) engine start after auth; (b) the `online` connectivity
event; (c) a 2 s debounce after any op enqueue; (d) a 60 s periodic timer while online —
this is the liveness mechanism that delivers remote changes to an *idle* device and retries
after aborted cycles; (e) manual "retry now" from the §8 popover. Cycles are single-flight
per device and cross-tab (`navigator.locks.request` around the cycle), so two same-browser
tabs cannot interleave replays of the shared queue.

## 4. Pull protocol

**New endpoint** — `GET /api/v1/sync?since={changeSeq}&limit={n}` (new `Features/Sync/`
slice; default/max `limit` 500):

- Queries each synced table for `change_seq > since` with
  `IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])` — **tombstones included, Tenant
  filter and RLS stay on** (the exact named-filter split SP1 built for this).
- **All per-table queries and the limit cut run in one snapshot** (a single
  REPEATABLE READ transaction per page), so a page is internally consistent.
- `recurrence_exceptions` is excluded: it exists for server-side generation only, which
  stays server-only (locked decision 4). `refresh_tokens`/Identity tables were never synced.
- Rows merge across tables by global `change_seq`; the first `limit` rows are returned:

```
{ changes: { tasks: [], subtasks: [], projects: [], noteGroups: [], notes: [],
             recurrenceRules: [], focusSessions: [], dailyPlans: [], settings: [] },
  nextSince: long, hasMore: bool }
```

- Every sync row carries `changeSeq` and `deletedAt` (new sync DTOs; existing response DTOs
  are not modified).
- One migration adds the `(user_id, change_seq)` index to all synced tables (the
  `sp1-followups.md` item).

### 4.1 Cursor protocol

Implements SP1 §2.2's documented commit-order hazard mitigation. The client persists
`meta.syncCursor`. Each sync **cycle's** first page requests
`since = max(0, cursor − 5000)`; subsequent pages within a cycle chain `nextSince` exactly.
Idempotent upsert-by-key makes re-seen rows harmless, and the overlap re-read is cheap by
construction: only rows whose **current** `change_seq` falls inside the window are
re-delivered (superseded seqs don't exist anymore), i.e. at most the rows touched by the
last 5000 writes.

The overlap is sized in sequence-distance because the hazard is bounded by **bulk-op
cardinality, not write rate**: a single `ExecuteUpdate` (delete-series over a years-long
daily series, ensure-instances' explicit transaction) can assign hundreds of consecutive
seqs that become visible atomically — a concurrent reader passing that range mid-flight
would otherwise permanently skip them. 5000 covers any realistic personal-scale bulk write;
the residual race (a single >5000-row transaction in flight from *another* device at the
exact moment a pull crosses it) is documented under Risks.

Initial sync is `since = 0`, paged; the cursor advances after each applied page, so an
interrupted initial sync resumes where it stopped. **Bootstrap completeness is its own
flag:** `meta.initialSyncComplete` is set when the first sync reaches `hasMore = false`
(cursor presence is *not* the signal — a crash mid-initial-sync leaves a cursor with a
partial, FK-inconsistent prefix).

### 4.2 Apply rules

`applyPull`, one Dexie transaction per page: tombstone (`deletedAt != null`) → delete the
local row; otherwise upsert. The match key is each table's §2 primary key (id / `date` /
`key`). **Shadow rule:** skip any pulled row whose entity key appears in a pending op's
`entityKeys` — the cycle is push-then-pull so the queue is normally empty at pull time, but
this protects writes made mid-pull; the next cycle reconciles them. (A shadow-skipped
tombstone whose pending op is later *dropped* is re-delivered by the rejection-recovery pull
from `since=0`, §3.3 — the cursor alone would have moved past it.)

### 4.3 Post-push ensure-instances trigger

Server-side, creating a rule generates **zero** instances (`GeneratedUntil = null`);
instances exist only when a client calls ensure-instances, and `refreshFromLocal()`
deliberately never does. So: when a push phase successfully replays any
`GeneratedUntil`-resetting op — `POST /recurrence` (series create) or
`PATCH /recurrence/rules/{id}` (updateRule, which also tombstones future instances) — the
engine makes one ensure-instances call for the standing ~14-day horizon **before** the pull
phase. The pull then delivers the fresh instances. Without this, a series created offline
would show no instances until some unrelated `loadTasks` ran. (`updateSeries` and
`deleteSeriesFuture` do not reset `GeneratedUntil` and need no trigger.)

## 5. Conflict semantics (realizing SP1's locked table)

`change_seq` is the sole ordering authority; client clocks remain untrusted and advisory.

- **Field-level LWW** for tasks, subtasks, projects, notes, note-groups, rules — made real
  by diff-at-enqueue (§3.1): two devices editing *different* fields of the same row both
  win. Editing the *same* field, the later **arrival at the server** wins — a device offline
  for a week pushes late and its stale value wins that field. Accepted v1 LWW semantics.
- **Edit vs delete:** delete wins (mechanically, via 404-drop + recovery, §3.3).
- **`daily_plans`:** whole-day LWW (PUT replaces the day; server re-stamps `committedAt`).
- **`user_settings`:** per-key LWW (holds because of diff-at-enqueue, §3.1).
- **`focus_sessions`:** append-only, no conflicts; idempotent by client UUID.
- **Ordering (rank-bearing entities: tasks, projects, notes, note-groups):** `rank` strings
  mean a concurrent move touches one row per device; interleaved offline reorders converge
  to a consistent (if surprising) order. Subtasks are the deliberate SP1 exception: integer
  `sortOrder`, no reorder surface in the live UI (only title/completed edits) — field-level
  LWW applies and no rank machinery is involved.

### 5.1 Rank ties and total order

Ties are not theoretical: every recurrence instance copies the template's hardcoded rank
`'a0'` today, and two offline devices appending against the same synced tail compute the
identical rank string (`ranking.ts` is deterministic). Two rules close the hole:

1. **Deterministic total order everywhere:** every ordered read sorts by
   `(rank, id)` with plain byte/ordinal comparison — in `LocalDataClient` reads and (as a
   §9 touch-up) in the server list endpoints, which today disagree with each other
   (tasks: default collation, no tie-break; projects: `COLLATE "C"`). Same ranks can then
   never render in different orders on different devices.
2. **Tie-aware insertion:** when a local insert/move lands between neighbors with equal
   ranks, `LocalDataClient` re-ranks the minimal tied run (assigning fresh distinct ranks,
   enqueuing the corresponding reorder ops) instead of calling `rankBetween(prev, next)`
   with `prev == next` — which would produce a rank sorting *after both* neighbors.

## 6. Offline behavior per DataClient group

| Group | Behavior |
|---|---|
| tasks / subtasks / projects / noteGroups / notes | Full CRUD + reorder locally (subtasks: no reorder surface; integer `sortOrder` semantics, §5); ranks local; ops queued; local cascades mirror server cascades |
| `recurrence.create` | Insert template task locally (client task id + client **rule** id — backend touch-up) + queue `POST /recurrence`; **returns the full local task list** (the store replaces its whole `tasks` array with the return value — preserving the HttpDataClient contract at `store.ts:667`); instances appear after reconnect via §4.3 |
| `updateSeries` / `deleteSeries` / `deleteSeriesFuture` / `updateRule` / `detachInstance` | Queue the op + apply a local approximation of the server's effect. Pinned to match the server: delete-future removes future, not-done, **non-template** instances — detached included (`RecurrenceEndpoints.DeleteSeriesFuture` has no detached filter; the non-detached filter belongs to updateSeries/updateRule). Approximations need not be perfect — the post-push pull is authoritative and corrects drift |
| `ensureInstances` | Read-through: online → server call, upsert returned instances locally, return them; offline → return `[]`, coast on horizon |
| `focusSessions.add` | Insert locally with client id (backend touch-up) + queue |
| `dailyPlans` | Local upsert by date + queue `PUT`; `committedAt` stamped locally, server re-stamps on apply |
| `settings` | Apply to local rows + queue a diffed `PUT /settings` (changed keys only, §3.1). Edge: `timeZoneId` lives on the user record (not a synced table); another device's change arrives via the auth refresh response, not via pull — accepted |
| `reports.getData` | Always computed locally: `LocalDataClient` **builds the `ReportData` projection** from local tasks (`completedAt` day-bucketing), focusSessions, and dailyPlans rows, resolving project names from local projects (new code, mirroring the server Reports slice — `lib/reports.ts` only *aggregates* an already-built projection). Known v1 divergence: bucketing uses the device's local timezone, where the server endpoint used account `timeZoneId` |

## 7. Store, auth, and lifecycle

### 7.1 Store changes (enumerated — nothing else changes)

1. New action `refreshFromLocal()`: re-reads collections from the seam; **no side effects**.
2. The three series actions (`updateRecurrenceSeries`, `deleteRecurrenceSeries`,
   `deleteRecurrenceSeriesFuture`) stop calling `loadTasks()` and call `refreshFromLocal()`
   instead — their local approximations (§6) already updated Dexie, and re-running
   `loadTasks` would fire rollover + an ensure-instances upsert that can transiently
   resurrect just-deleted instances. (Today `loadTasks` runs at app start *and* after these
   three actions, plus `ensureRecurrenceInstances` fires on weekly-board navigation — the
   "app start only" framing in v1 was wrong.)
3. **Rollover deferral:** when a session starts online, the rollover pass inside `loadTasks`
   is deferred until the session's first sync cycle settles (success or terminal failure),
   then runs against reconciled rows — otherwise every warm cold-start would roll over
   *stale* local rows and enqueue automated writes that clobber fresher remote state (e.g.
   re-dating a task another device moved, archiving an instance another device completed).
   A session that starts offline runs rollover immediately — its queued writes are then
   legitimately the newest intent.
4. `setOnlineError` remains for sync-issue summaries; the SP2 settings-load/save and
   rollover banner cases disappear (those paths are local now). The banner's remaining
   customers: sync issues (§8) and initial-full-sync failure (§7.4).

### 7.2 Offline bootstrap (authed-offline)

`localStorage['tmap:lastUserId']` selects the `tmap-{userId}` DB to open. If refresh fails
with a **transient failure** (network error or refresh-endpoint 5xx — grouped per SF-3) and
that DB's `meta.lastUser` exists, enter **authed-offline**: render from local data with no
access token; the engine idles until connectivity, then refreshes and syncs. A true **401**
(refresh token revoked/expired, e.g. the >60-day case) means `anonymous`, but the local DB
and op queue are **kept**: re-login as the same user reuses them and pushes pending work.
Re-login as a different user opens a different DB — no cross-account leakage. Explicit
logout (new UI surface in SP3) wipes the local DB + queue and clears `tmap:lastUserId`, with
a confirmation dialog when `pendingOps > 0`.

### 7.3 Client auth touch-ups (required, not optional)

The 401-vs-transient distinction §7.2 relies on does not survive the current plumbing; SP3
changes two SP2 components:

1. **`refreshClient`** today runs `refresh().catch(() => null)` and calls `doLogout()` on
   any null — conflating a network drop or 5xx *during* refresh with a dead session (and on
   desktop, destroying a still-valid keychain token). SP3: only a true 401 from the refresh
   endpoint is terminal; transient refresh failures propagate as retryable errors — the sync
   cycle aborts exactly like a push network error, queue intact, no logout.
2. **Desktop IPC contract**: `secureStore:refreshAndGetAccess` returns `null` for 401, 5xx,
   *and* network errors alike, and `authStore.bootstrap` then clears the keychain token —
   today a desktop offline cold start would destroy the session. SP3: the IPC result
   discriminates `unauthorized` vs `transient` (matching web's SF-3 throw contract);
   `DesktopPlatform` re-throws transients as `NetworkError` so `bootstrap`'s existing
   branches work; the keychain token is cleared only on true 401.

### 7.4 AppRoot wiring and the first-render gate

`onAuthed` → open `LocalStore(userId)` → build `LocalDataClient` → `setDataClient(...)` →
start `SyncEngine` → `initialLoad()`. First render is gated on
`meta.initialSyncComplete` (§4.1) — not cursor presence. If the gated initial pull fails or
is interrupted, render whatever pages were applied with the pill in its warning state and
resume on the next trigger — never block render indefinitely; rollover and ensure-instances
stay deferred until `initialSyncComplete` is set. `onLoggedOut` → stop engine → close DB
(wipe only on explicit logout, §7.2).

## 8. Sync-status UI

One pill in the existing titlebar/sidebar chrome, fed by engine state
`{ online, syncing, pendingOps, lastSyncedAt, issues }`:

- Hidden (or a quiet check) when synced; `cloud-off + n pending` when offline; spinner while
  a cycle runs; warning state when `issues` is non-empty or the initial sync is incomplete.
- Click → small popover: last-synced time, pending count, the persisted issues list
  (dropped + parked ops, §3.3) with retry/discard affordances, and "retry now".
- Issues persist in the §2 `issues` table (they are the only surviving record that a user's
  edit was discarded — they must not vanish on restart); entries clear on user dismissal.

## 9. Backend touch-ups (all additive, one small phase)

1. `Features/Sync/SyncEndpoints.cs` + sync DTOs + `(user_id, change_seq)` index migration;
   snapshot-transaction page reads (§4).
2. Idempotent creates: PK conflict on a row **owned by the same user** → 200 with the
   existing row (today: unmapped 500). **Unique-name conflicts** (projects `(user_id, name)`
   partial index) → 409 ProblemDetails carrying the existing row's id, enabling the client's
   adopt-existing policy (§3.3). Applies to all client-id creates.
3. `POST /focus-sessions`: accept optional client `Id` (idempotent) — today the endpoint
   silently ignores client ids, so a replayed create would duplicate the session.
4. `POST /recurrence`: accept optional client rule `Id`, so offline-created series can be
   referenced by later queued ops.
5. List-endpoint ordering unification: order by `(rank, id)` with `COLLATE "C"` everywhere
   (tasks today use default collation with no tie-break; projects already use `"C"`) — §5.1.

## 10. Cleanup in scope

The dormant SQL.js layer is deleted: `apps/desktop/electron/{database.ts, taskService.ts,
noteService.ts, projectService.ts, dailyPlanService.ts, focusSessionService.ts,
reportService.ts, recurrenceUtils.ts, seed.ts, sql.js.d.ts}`, the `sql.js` dependency, and
the `sql-wasm.wasm` electron-builder `extraResources` entry. SP2's note said "SP3 revives a
local store" — it does, as IndexedDB in `packages/app`, not SQL.js in main.
`HttpDataClient.ts` (+ its tests) is removed per §1; `mappers.ts`/`ranking.ts` survive.

New dependencies: `dexie` (runtime, `packages/app`); `fake-indexeddb` (dev/test only).

## 11. Testing

- **Backend (xUnit + Testcontainers, per-slice convention):** `SyncTests.cs` — tombstones
  included; tenant isolation under real RLS (user A's pull never sees user B's rows);
  pagination + cursor chaining + `hasMore`; snapshot-consistent pages; overlap re-read
  windows; idempotent-create + 409-with-existing-id touch-ups; focus-session/rule client
  ids; `(rank, id)` ordering; index migration applies.
- **Client (vitest + fake-indexeddb):**
  - `LocalDataClient` unit tests: CRUD, rank assignment + tie repair (§5.1), local
    cascades, subtask joins, DTO mapping reuse, **diff-at-enqueue** (incl. explicit-null
    clears and settings key diffs), op enqueue atomicity, the local `ReportData` builder.
  - `SyncEngine` tests against a scripted fake server: replay ordering, the full §3.3
    failure taxonomy (including adopt-existing id rewrite, rejection recovery, poison-op
    parking, pull-runs-when-push-halts), trigger set (§3.4), overlap re-read, shadow rule
    (uuid *and* date/key entities), cursor persistence, `initialSyncComplete` gating,
    post-push ensure-instances (§4.3), rollover deferral (§7.1), cross-tab single-flight.
  - **Two-device convergence** (the marquee suite): two LocalStore+engine instances against
    one in-memory fake server; interleaved offline edits; assert convergence for field
    merges, edit-vs-delete, whole-day plan LWW, per-key settings, reorder interleaving
    **seeded with tied ranks** (the `'a0'` instance population), offline series creation.
- **Live gate:** manual desktop + web session against the real backend with network toggled
  on/off (SP2-style end-of-project verification), including a desktop cold start with the
  backend down.

## Acceptance criteria (definition of done)

1. Desktop cold-starts with no network and shows the last-authed user's data (no session
   destruction — §7.3); all CRUD, reorder, planning, and focus flows work; writes queue.
2. An open web tab survives network loss mid-session — including a 401→refresh that fails
   transiently (§7.3): actions keep working locally; the pill shows offline + pending count.
3. On reconnect the queue drains FIFO, deltas pull, and the UI refreshes via
   `refreshFromLocal()` with no rollover/ensure-instances side effects; the cursor persists
   across restarts; when a session starts online, rollover runs only after the first cycle
   settles (§7.1).
4. Two devices converge after interleaved offline edits: different-field merges (proving
   diff-at-enqueue), same-field arrival-LWW, edit-vs-delete (delete wins, including via
   rejection recovery), whole-day plan LWW, per-key settings LWW, and reorder convergence
   for rank-bearing entities including tied-rank seeds (§5.1).
5. A recurring series created offline appears with server-generated instances after
   reconnect via the post-push ensure-instances trigger (§4.3); existing instances are
   editable/completable/deletable offline.
6. `GET /api/v1/sync` returns tombstones, enforces tenant isolation under RLS, paginates by
   `change_seq` with snapshot-consistent pages, and tolerates overlap re-reads (proven by
   tests).
7. Replayed creates with the same client id produce no duplicates anywhere (including focus
   sessions); the PK-conflict path returns the existing row; a duplicate-name project create
   resolves via adopt-existing (§3.3) without wedging the queue.
8. A fresh device populates via paged full sync from `since=0`; `initialSyncComplete` gates
   first render; an interrupted initial sync resumes and is never presented as complete;
   subsequent launches render from local instantly.
9. Explicit logout wipes local data (confirming when pending ops exist); session expiry
   keeps data + queue; re-login as the same user pushes the queue.
10. No failure mode wedges sync permanently: definitive rejections drop + recover, poison
    ops park with a discard affordance, and pull continues when push halts (§3.3).
11. Existing suites stay green at their current counts minus deliberately deleted files
    (HttpDataClient tests go with HttpDataClient); both apps build; the installer no longer
    ships SQL.js/wasm.

## Explicitly deferred

- PWA/service worker for offline web cold-start (SP5, with hosting).
- Tombstone purge job + purge-horizon full-resync enforcement (SP5; nothing purges yet).
- Client-side occurrence generation (revisit only if the 14-day horizon proves too short).
- Op coalescing.
- Per-field timestamps / vector ordering; any conflict UI beyond the issues list.
- Reports timezone parity (local-tz bucketing accepted offline and online-local).
- Access-token kill switch (`sp1-followups.md`, MAJOR) — unchanged scope, still open.
- Android (SP4) consumes this engine later; nothing here is Android-specific.

## Risks / watch-items

- **Stale-arrival LWW surprise:** a long-offline device can overwrite a fresher same-field
  edit on push. Accepted v1; the mitigation candidates (per-field `updated_at` comparison)
  are documented deferred. Diff-at-enqueue (§3.1) confines this to fields the user actually
  changed.
- **Overlap residual race:** the 5000-seq overlap covers any realistic single-transaction
  bulk write; a single >5000-row transaction in flight from another device at the exact
  moment a pull crosses its range could still be skipped permanently. Accepted as
  negligible at personal scale; the constant lives in one place and the rejection-recovery
  full pull (§3.3) is a manual escape hatch (retriggerable via the popover).
- **IndexedDB eviction on web:** mitigated by `navigator.storage.persist()`; eviction loses
  unpushed ops and forces a full re-sync. Desktop (Electron partition) is not subject to
  browser eviction pressure.
- **Local approximations of series ops** can transiently diverge from the server's exact
  effect until the post-push pull corrects them; convergence tests cover the cases.
- **Dexie schema migrations** become a permanent discipline (version bumps + upgrade fns)
  the moment v1 ships.
- **Recovery pull cost** grows with lifetime tombstone count (nothing purges until SP5);
  acceptable now, revisit with the purge job.
- **`refreshFromLocal` vs in-flight UI state:** applying remote changes mid-edit (e.g. a
  task open in `TaskDetailDialog`) follows current SP2 behavior (state refresh); no new
  conflict UI in v1.

## Changes from v1 (audit trail — adversarial review, 23 confirmed findings)

1. **(CRITICAL)** Duplicate project name → unmapped 500 → permanent head-of-line sync wedge:
   added 409-with-existing-id touch-up (§9.2), client adopt-existing policy (§3.3),
   poison-op parking, and pull-decoupled-from-halted-push (§3.3); AC10 added.
2. **(MAJOR)** `refreshClient` + desktop IPC conflate transient refresh failures with 401
   (false logout offline; desktop wipes the keychain token): §7.3 client auth touch-ups
   added; §7.2 groups refresh 5xx with network errors per SF-3.
3. **(MAJOR)** Whole-form `TaskDetailDialog` PATCH defeated field-level LWW; whole-map
   settings PUT defeated per-key LWW: diff-at-enqueue rule added (§3.1).
4. **(MAJOR)** Startup rollover on stale local rows clobbers fresher remote state: rollover
   deferral until the first cycle settles when online (§7.1.3).
5. **(MAJOR)** Nothing triggered ensure-instances after queued series ops pushed (AC5 was
   unsatisfiable); pushed updateRule made instances vanish: post-push trigger added (§4.3).
6. **(MAJOR)** Rank ties are guaranteed today (all recurrence instances rank `'a0'`;
   deterministic offline appends collide) with no defined tie-break: §5.1 total order
   `(rank, id)`, tie-aware insertion, server ordering unification (§9.5), tied-rank seeds
   in tests.
7. **(MAJOR)** Sync triggers were never enumerated (idle devices never pulled; aborted
   cycles never retried): §3.4 added with pinned constants.
8. **(MAJOR)** 400/409 drops left permanent local divergence (incl. ghost created rows and
   shadow-skipped tombstones lost past the cursor): rejection recovery added (§3.3).
9. **(MAJOR)** 100-seq overlap defeated by single bulk transactions; pages lacked snapshot
   consistency: overlap → 5000 + every-cycle application, REPEATABLE READ pages (§4.1),
   honest Risks rewrite (cardinality-bounded, not rate-bounded).
10. **(MINOR)** `meta.lastUser` chicken-and-egg at offline bootstrap: global
    `localStorage['tmap:lastUserId']` pointer added (§2, §7.2).
11. **(MINOR)** Cursor presence was the wrong first-render gate (partial bootstrap rendered
    as complete; failure behavior undefined): `meta.initialSyncComplete` + gate-failure
    behavior (§4.1, §7.4).
12. **(MINOR)** "Rollover/ensure-instances live in loadTasks at app start only" was false
    (three series actions re-invoke `loadTasks`; weekly-board navigation calls
    ensure-instances): §7.1 corrects the inventory and switches the series actions to
    `refreshFromLocal()`.
13. **(MINOR)** Issues list had no persistence home: `issues` table added (§2, §8).
14. **(MINOR)** Shadow rule/upsert keys were undefined for id-less tables (dailyPlans,
    settings): `entityKeys` with per-table keys (§3.1, §4.2).
15. **(MINOR)** Two-tab "idempotency makes this safe" overstated (cross-engine retry
    reordering loses updates): Web Locks single-flight pulled into v1 (§3.4).
16. **(MINOR)** Reports row wrongly implied `lib/reports.ts` could build the projection:
    §6 names the new local builder; §11 tests it.
17. **(MINOR)** `recurrence.create` return contract was unspecified (store replaces its
    whole tasks array with the return value): pinned in §6.
18. **(MINOR/NIT)** Wording/consistency: focus-sessions endpoint *ignores* (not rejects)
    client ids — replay silently duplicates today (§3.2, §9.3); delete-future matches the
    server (detached instances included) (§6); subtasks use integer `sortOrder`, excluded
    from rank-based reorder convergence claims (§5, AC4); ops index renamed `*entityKeys`
    multiEntry (§2); stale "settings load" banner customer dropped (§7.1.4); AC11 reworded
    for the deliberately deleted HttpDataClient tests.
