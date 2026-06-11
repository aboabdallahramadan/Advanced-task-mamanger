# SP3 — Offline-First Sync Engine — Design

**Date:** 2026-06-11
**Status:** Approved (design, v1 — brainstormed section-by-section with user approval)
**Part of:** `2026-06-01-online-multiplatform-roadmap.md`

> Produced through the brainstorming flow: codebase/docs exploration, five scope decisions
> locked with the user, then four design sections approved individually. Builds directly on
> the sync primitives SP1 shipped (`change_seq` trigger, tombstones, fractional `rank`,
> client UUIDs) and the `DataClient` seam SP2 froze for exactly this purpose.

## Goal

A signed-in user keeps working with full CRUD while offline — on the desktop app (including
cold start) and in an already-open web tab — and the app reconciles automatically when the
network returns. Both clients converge to the same state across devices using the LWW
semantics SP1 locked per entity. No UI redesign: the Zustand store and components are
untouched except for a narrow refresh action and a small sync-status pill.

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
   coast on it. A new series created offline queues; its instances appear on reconnect.
5. **Engine placement: `LocalDataClient` + `SyncEngine` in `packages/app`** behind the
   existing `DataClient` seam (the insertion point SP2 designed). Refinement over SP2's
   "wrap `HttpDataClient`" forward note: `HttpDataClient` is **retired**, not wrapped — its
   reorder/create logic depends on an in-memory id→rank cache populated by its own reads,
   and in SP3 it performs no reads. `LocalDataClient` takes over mapping + rank assignment
   (reusing `mappers.ts` / `ranking.ts`); the queue stores wire-shaped requests.

## 1. Architecture

```
Zustand store ──▶ DataClient seam ──▶ LocalDataClient ──▶ LocalStore (Dexie/IndexedDB, per-user DB)
                                            │                    ▲
                                            └─ enqueue op ─▶ ops table (same Dexie transaction)
                                                                 │
                       SyncEngine: push ops FIFO via refresh-wrapped TmapClient ──▶ REST API
                                   then pull GET /api/v1/sync?since=cursor (paged)
                                   apply deltas ──▶ LocalStore ──▶ notify ──▶ store.refreshFromLocal()
```

New modules (all in `packages/app` unless noted):

| Module | Responsibility |
|---|---|
| `src/data/local/LocalStore.ts` | Dexie database `tmap-{userId}`; tables, transactions, meta |
| `src/data/local/LocalDataClient.ts` | Implements `DataClient`; reads/writes Dexie; maps domain⇄DTO at the edge; owns rank assignment; enqueues ops |
| `src/data/local/applyPull.ts` | Applies a pull page (upsert/tombstone-delete, shadow rule) |
| `src/sync/SyncEngine.ts` | Single-flight sync cycle, triggers, failure handling, status |
| `src/sync/connectivity.ts` | `navigator.onLine` + `online`/`offline` events + sync outcomes |
| `backend Features/Sync/` | `GET /api/v1/sync` endpoint + DTOs + index migration |

Data flow, local action (e.g. rename a task): store action → `LocalDataClient.tasks.update`
→ Dexie row updated + op enqueued (one transaction) → updated entity returned to the store.
Identical online or offline. ~2 s later the engine pushes; the next pull is a no-op for it.

Data flow, remote change: pull delivers rows with `changeSeq >` cursor (tombstones included)
→ `applyPull` → engine emits `changesApplied` → AppRoot dispatches the new store action
`refreshFromLocal()`, which re-reads collections through the seam **without side effects**
(no rollover, no ensure-instances — those remain in `loadTasks` at app start only).

`HttpDataClient.ts` and its rank caches are deleted; `mappers.ts` and `ranking.ts` live on
as `LocalDataClient`'s mapping/ranking layer. The `DataClient` interface itself is unchanged.

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
| `ops` | `seq` (auto-inc) | `entityId` | Durable write queue |
| `meta` | `key` | — | `syncCursor`, `lastSyncedAt`, `lastUser {id,email,timeZoneId}` |

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

An op is a **wire-shaped request** recorded at enqueue time:

```
{ seq, method: 'POST'|'PATCH'|'PUT'|'DELETE', path: '/api/v1/...', body?, entityIds: string[],
  attempts: number, lastError?: string }
```

`LocalDataClient` resolves everything request-shaped at enqueue: domain→DTO mapping
(`mappers.ts`, including the Optional/null-to-clear PATCH semantics SF-2 established), rank
strings for creates and reorders (`ranking.ts` over local neighbor rows), and client UUIDs
for creates. Replay is then a dumb loop: send each request through the refresh-wrapped
`TmapClient` (401→refresh→retry already built in SP2). No re-mapping at push time, no
stateful caches, no drift between what the user saw and what gets sent.

No coalescing in v1 — redundant replayed PATCHes are harmless; coalescing is a deferred
optimization.

**Idempotent replay** (crash/network loss between server-apply and op-dequeue is the
canonical hazard; every op type must tolerate being sent twice):

| Op kind | Why replay is safe |
|---|---|
| Creates | Same client UUID; backend touch-up returns 200 + existing row on owned-PK conflict (today: 500) |
| Updates | Re-PATCHing the same fields is a no-op |
| Deletes | 404 treated as success (already tombstoned) |
| Reorders | Re-sending the same ranks is a no-op |
| `PUT` upserts (plans, settings) | Naturally idempotent |

**Push failure taxonomy** (FIFO, single-flight; order is never violated):

| Failure | Handling |
|---|---|
| Network error | Abort cycle; queue intact; retry on next trigger with backoff |
| 401 | Refresh wrapper handles; terminal logout stops the engine |
| 400 / 404 / 409 (definitive rejection) | Drop the op, record in the visible `issues` list, continue. 404 is the edit-vs-delete conflict: entity deleted remotely, stale edit dropped, pull delivers the tombstone — **delete wins** (matches server query-filter semantics) |
| 5xx | Bounded retries with exponential backoff (per-op `attempts`), then halt the cycle and resume on the next trigger. Never skip — skipping could replay an update before its create |

## 4. Pull protocol

**New endpoint** — `GET /api/v1/sync?since={changeSeq}&limit={n}` (new `Features/Sync/`
slice; default/max `limit` 500):

- Queries each synced table for `change_seq > since` with
  `IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])` — **tombstones included, Tenant
  filter and RLS stay on** (the exact named-filter split SP1 built for this).
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

**Cursor protocol** (implements SP1 §2.2's documented commit-order hazard mitigation): the
client persists `meta.syncCursor`. Each sync **cycle's** first page requests
`since = max(0, cursor − 100)` — the overlap re-read; subsequent pages within a cycle chain
`nextSince` exactly. Idempotent upsert-by-id makes re-seen rows harmless. Initial sync is
`since = 0`, paged; the cursor advances after each applied page, so an interrupted initial
sync resumes where it stopped.

**Apply rules** (`applyPull`, one Dexie transaction per page): tombstone → delete local row;
otherwise upsert by id. **Shadow rule:** skip any pulled row whose entity has pending ops in
the queue — the cycle is push-then-pull so the queue is normally empty at pull time, but
this protects writes made mid-pull; the next cycle reconciles them.

## 5. Conflict semantics (realizing SP1's locked table)

`change_seq` is the sole ordering authority; client clocks remain untrusted and advisory.

- **Field-level LWW** for tasks, subtasks, projects, notes, note-groups, rules — it falls
  out of minimal PATCHes: two devices editing *different* fields of the same row both win.
  Editing the *same* field, the later **arrival at the server** wins — a device offline for
  a week pushes late and its stale value wins that field. Accepted v1 LWW semantics.
- **Edit vs delete:** delete wins (mechanically, via 404-drop + tombstone pull).
- **`daily_plans`:** whole-day LWW (PUT replaces the day; server re-stamps `committedAt`).
- **`user_settings`:** per-key LWW.
- **`focus_sessions`:** append-only, no conflicts; idempotent by client UUID.
- **Ordering:** `rank` strings mean a concurrent move touches one row per device; interleaved
  offline reorders converge to a consistent (if surprising) order without duplication.

## 6. Offline behavior per DataClient group

| Group | Behavior |
|---|---|
| tasks / subtasks / projects / noteGroups / notes | Full CRUD + reorder locally; ranks local; ops queued; local cascades mirror server cascades |
| `recurrence.create` | Insert template task locally (client task id + client **rule** id — backend touch-up) + queue `POST /recurrence`; instances appear after reconnect |
| `updateSeries` / `deleteSeries` / `deleteSeriesFuture` / `updateRule` / `detachInstance` | Queue the op + apply a local approximation of the server effect (e.g. delete-future removes future, not-done, non-detached instances locally). Approximations need not be perfect — the post-push pull is authoritative and corrects drift |
| `ensureInstances` | Read-through: online → server call, upsert returned instances locally, return them; offline → return `[]`, coast on horizon |
| `focusSessions.add` | Insert locally with client id (backend touch-up) + queue |
| `dailyPlans` | Local upsert by date + queue `PUT`; `committedAt` stamped locally, server re-stamps on apply |
| `settings` | Apply to local rows + queue `PUT /settings`. Edge: `timeZoneId` lives on the user record (not a synced table); another device's change arrives via the auth refresh response, not via pull — accepted |
| `reports.getData` | Always computed locally from synced rows via `lib/reports.ts`. Known v1 divergence: completed-task day-bucketing uses the device's local timezone where the server endpoint used account `timeZoneId` |

Rollover keeps its current home (in `loadTasks`, app start) but now runs against the seam's
local writes — it works offline and its writes queue like any other; the SF-4 "couldn't sync
rolled-over tasks" banner case disappears.

## 7. Auth and lifecycle offline

- **Offline bootstrap (authed-offline state):** if refresh fails with a **network error**
  and `meta.lastUser` exists in the local DB, enter authed-offline: render from local data
  with no access token; the engine idles until connectivity, then refreshes and syncs.
  (Today this lands on the login screen — that changes.)
- **True 401** (refresh token revoked/expired, e.g. the >60-day offline case): `anonymous`,
  but the local DB and op queue are **kept**. Re-login as the same user reuses them and
  pushes pending work. Re-login as a different user opens a different `tmap-{userId}` DB —
  no cross-account leakage.
- **Explicit logout** wipes the local DB + queue (privacy), with a confirmation dialog when
  `pendingOps > 0` (that work is unpushed and will be lost).
- Access-token expiry mid-session: unchanged, the SP2 single-flight refresh wrapper owns it.
- Documented constraint (from SP1): a refresh token expired past 60 days triggers
  server-side family revocation — every device must re-login. Sync resumes cleanly after
  re-login because cursor + queue survived.

**AppRoot wiring:** `onAuthed` → open `LocalStore(userId)` → build `LocalDataClient` →
`setDataClient(...)` → start `SyncEngine` (gate first render on the initial full pull only
when `meta.syncCursor` is absent) → `initialLoad()`. `onLoggedOut` → stop engine → close DB
(wipe only on explicit logout, per above).

## 8. Sync-status UI

One pill in the existing titlebar/sidebar chrome, fed by engine state
`{ online, syncing, pendingOps, lastSyncedAt, issues }`:

- Hidden (or a quiet check) when synced; `cloud-off + n pending` when offline; spinner while
  a cycle runs; warning state when `issues` is non-empty.
- Click → small popover: last-synced time, pending count, dropped-op issues list, retry now.
- The SP2 `onlineError` banner remains for surfaced failures (settings load on cold start,
  sync-issue summaries) but loses its main customer since rollover now queues.

## 9. Backend touch-ups (all additive, one small phase)

1. `Features/Sync/SyncEndpoints.cs` + sync DTOs + `(user_id, change_seq)` index migration.
2. Idempotent creates: PK conflict on a row **owned by the same user** → 200 with the
   existing row (today: unmapped 500). Applies to all client-id creates.
3. `POST /focus-sessions`: accept optional client `Id` (idempotent) — resolves the SP1-spec
   contradiction (its LWW table says "upsert-by-uuid"; the endpoint currently rejects ids).
4. `POST /recurrence`: accept optional client rule `Id`, so offline-created series can be
   referenced by later queued ops (`updateSeries(ruleId)` enqueued before the create pushed).

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
  pagination + cursor chaining + `hasMore`; overlap re-read window correctness; idempotent
  create touch-ups; focus-session/rule client ids; index migration applies.
- **Client (vitest + fake-indexeddb):**
  - `LocalDataClient` unit tests: CRUD, rank assignment, local cascades, subtask joins,
    DTO mapping reuse, op enqueue atomicity.
  - `SyncEngine` tests against a scripted fake server: replay ordering, the full failure
    taxonomy, overlap re-read, shadow rule, cursor persistence, single-flight.
  - **Two-device convergence** (the marquee suite): two LocalStore+engine instances against
    one in-memory fake server; interleaved offline edits; assert convergence for field
    merges, edit-vs-delete, whole-day plan LWW, reorder interleaving, offline series
    creation.
- **Live gate:** manual desktop + web session against the real backend with network toggled
  on/off (SP2-style end-of-project verification), including a desktop cold start with the
  backend down.

## Acceptance criteria (definition of done)

1. Desktop cold-starts with no network and shows the last-authed user's data; all CRUD,
   reorder, planning, and focus flows work; writes queue.
2. An open web tab survives network loss mid-session: actions keep working locally; the
   pill shows offline + pending count.
3. On reconnect the queue drains FIFO, deltas pull, and the UI refreshes via
   `refreshFromLocal()` with no rollover/ensure-instances side effects; the cursor persists
   across restarts.
4. Two devices converge after interleaved offline edits: different-field merges, same-field
   arrival-LWW, edit-vs-delete (delete wins), whole-day plan LWW, reorder convergence.
5. A recurring series created offline appears with server-generated instances after
   reconnect; existing instances are editable/completable/deletable offline.
6. `GET /api/v1/sync` returns tombstones, enforces tenant isolation under RLS, paginates by
   `change_seq`, and tolerates overlap re-reads (proven by tests).
7. Replayed creates with the same client id produce no duplicates anywhere (including focus
   sessions); the PK-conflict path returns the existing row.
8. A fresh device populates via paged full sync from `since=0`; subsequent launches render
   from local instantly.
9. Explicit logout wipes local data (confirming when pending ops exist); session expiry
   keeps data + queue; re-login as the same user pushes the queue.
10. Existing suites stay green (backend 157+, `@tmap/app` 113+, `@tmap/web` 6+); both apps
    build; the installer no longer ships SQL.js/wasm.

## Explicitly deferred

- PWA/service worker for offline web cold-start (SP5, with hosting).
- Tombstone purge job + purge-horizon full-resync enforcement (SP5; nothing purges yet).
- Client-side occurrence generation (revisit only if the 14-day horizon proves too short).
- Op coalescing; cross-tab sync-cycle coordination via Web Locks (duplicate idempotent
  cycles are accepted in v1).
- Per-field timestamps / vector ordering; any conflict UI beyond the issues list.
- Reports timezone parity (local-tz bucketing accepted offline and online-local).
- Access-token kill switch (`sp1-followups.md`, MAJOR) — unchanged scope, still open.
- Android (SP4) consumes this engine later; nothing here is Android-specific.

## Risks / watch-items

- **Stale-arrival LWW surprise:** a long-offline device can overwrite a fresher same-field
  edit on push. Accepted v1; the mitigation candidates (per-field `updated_at` comparison)
  are documented deferred.
- **Overlap window adequacy:** the 100-seq overlap bounds the SP1 commit-order hazard by
  sequence distance, not time. At personal-app write rates this is generous; the constant
  lives in one place and is tunable.
- **IndexedDB eviction on web:** mitigated by `navigator.storage.persist()`; eviction loses
  unpushed ops and forces a full re-sync. Desktop (Electron partition) is not subject to
  browser eviction pressure.
- **Local approximations of series ops** can transiently diverge from the server's exact
  effect until the post-push pull corrects them; convergence tests cover the cases.
- **Dexie schema migrations** become a permanent discipline (version bumps + upgrade fns)
  the moment v1 ships.
- **Two same-browser tabs** both run engines against one DB; idempotency makes this safe but
  wasteful — watch, defer Web Locks coordination.
- **`refreshFromLocal` vs in-flight UI state:** applying remote changes mid-edit (e.g. a
  task open in `TaskDetailDialog`) follows current SP2 behavior (state refresh); no new
  conflict UI in v1.
