# SP3 — Offline-First Sync Engine — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make TMap offline-first on desktop (incl. cold start) and in an open web tab: a per-user IndexedDB store is the source of truth, all writes queue as wire-shaped ops, and a background SyncEngine replays the queue over the existing REST API then pulls deltas from a new `GET /api/v1/sync` endpoint — converging multi-device state under the LWW semantics SP1 locked.

**Architecture:** `LocalDataClient` (new `DataClient` implementation in `packages/app`) reads/writes Dexie and enqueues ops with diff-at-enqueue + local rank authority; `SyncEngine` owns the single-flight push→(ensure-instances)→pull cycle, failure taxonomy, recovery, and status; the backend gains one additive `Features/Sync` slice + idempotency/ordering touch-ups. `HttpDataClient` retires; the SQL.js dead code is deleted.

**Tech Stack:** React 18 · TypeScript strict · Zustand · Dexie 4 (IndexedDB) · fake-indexeddb (tests) · vitest · @tmap/api-client (openapi-fetch) · .NET 10 + EF Core 10 + Npgsql (R0) · xUnit + Testcontainers · npm workspaces.

**Source spec:** `docs/superpowers/specs/2026-06-11-sp3-offline-sync-design.md` · **Branch:** `feat/sp3-offline-sync` (from `main`; mind concurrent-session HEAD races — prefer a git worktree).

**Phase order (dependency-ordered):** R0 backend sync slice + touch-ups + client regen → R1 LocalStore (Dexie foundation) → R2 LocalDataClient (reads/writes/diff/ranks/reports) → R3 SyncEngine push (replay + failure taxonomy + triggers) → R4 SyncEngine pull (cursor + applyPull + recovery + gate flag) → R5 auth touch-ups + store/AppRoot/UI wiring → R6 two-device convergence suite + SQL.js/HttpDataClient deletion + final gates. Every phase ends green (backend `dotnet test` from `backend/`; `npm run test:app`; `npm test --workspace @tmap/web`; `npm run build:apps` where relevant).

---

## Cross-phase contracts (authoritative — overrides any individual phase where they differ)

These symbols are shared across phases. Phase authors/executors MUST use these exact names and shapes.

### C1. Pinned constants — `packages/app/src/sync/constants.ts` (created in R3)

```ts
export const PULL_LIMIT = 500;
export const CURSOR_OVERLAP = 5000;
export const PUSH_DEBOUNCE_MS = 2000;
export const PERIODIC_SYNC_MS = 60_000;
export const CYCLE_5XX_RETRIES = 3;
export const CYCLE_5XX_BACKOFF_MS = [1000, 2000, 4000];
export const PARK_THRESHOLD = 10;
export const ENSURE_HORIZON_DAYS = 14;
```

### C2. Op record + issue record — `packages/app/src/sync/types.ts` (created in R1, used by R2+)

```ts
export type OpKind = 'create' | 'other';

export interface SyncOp {
  seq?: number;                 // Dexie ++seq PK (absent before insert)
  method: 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  path: string;                 // resolved absolute API path, e.g. '/api/v1/tasks/3f2a…'
  body?: unknown;               // wire-shaped JSON body (request DTO), or undefined
  entityKeys: string[];         // '<table>:<key>' — 'tasks:<uuid>', 'dailyPlans:2026-06-11', 'settings:workStartHour'
  kind: OpKind;                 // 'create' enables adopt-existing + ghost-row recovery
  regenAfterPush?: true;        // set on recurrence create + updateRule ops (GeneratedUntil reset → §4.3 trigger)
  attempts: number;             // total attempts across cycles (park at PARK_THRESHOLD)
  lastError?: string;
}

export interface SyncIssue {
  id?: number;                  // Dexie ++id
  at: string;                   // ISO timestamp
  op: SyncOp;                   // snapshot of the dropped/parked op
  reason: string;               // human-readable (HTTP status + ProblemDetails title when present)
  status: 'dropped' | 'parked'; // parked ops can be retried or discarded from the popover
}

export interface SyncStatus {
  online: boolean;
  syncing: boolean;
  pendingOps: number;
  lastSyncedAt: string | null;
  issues: SyncIssue[];
  initialSyncComplete: boolean;
}
```

For creates, `entityKeys[0]` is the created row's own key (adopt-existing rewrites it). Cascade ops list every affected key (e.g. task delete lists `tasks:<id>` plus each `subtasks:<id>`).

### C3. LocalStore — `packages/app/src/data/local/LocalStore.ts` (R1)

Dexie database named `` `tmap-${userId}` ``, schema version 1:

```ts
this.version(1).stores({
  tasks: 'id, plannedDate, status, recurrenceRuleId',
  subtasks: 'id, taskId',
  projects: 'id',
  noteGroups: 'id',
  notes: 'id, groupId, projectId',
  recurrenceRules: 'id',
  focusSessions: 'id, date',
  dailyPlans: 'date',
  settings: 'key',
  ops: '++seq, *entityKeys',
  issues: '++id',
  meta: 'key',
});
```

Row types are the **server sync-DTO wire shapes** (regenerated `@tmap/api-client` `components['schemas']['TaskSyncRow']` etc. — see C7). Locally-created rows synthesize the same shape with `changeSeq: 0` and `deletedAt: null`. Local tables hold live rows only.

Meta keys (table `meta`, rows `{ key, value }`): `syncCursor: number` · `initialSyncComplete: boolean` · `lastSyncedAt: string` · `lastUser: { id, email, timeZoneId }`.

Class surface: `class LocalStore extends Dexie` with helpers `getMeta<T>(key): Promise<T | undefined>`, `setMeta(key, value)`, `static open(userId): LocalStore`, `static async wipe(userId)`, `close()`. Global pointer helpers (module functions, same file): `getLastUserId(): string | null` / `setLastUserId(id: string | null)` over `localStorage['tmap:lastUserId']`.

### C4. LocalDataClient — `packages/app/src/data/local/LocalDataClient.ts` (R2)

```ts
export interface SyncBridge {
  nudge(): void;                                            // op enqueued → debounce a push
  online(): boolean;
  ensureInstances(start: string, end: string): Promise<TaskSyncRow[]>; // server call; throws offline
}

export class LocalDataClient implements DataClient {
  constructor(private store: LocalStore, private bridge: SyncBridge) {}
}
```

Every mutation = one Dexie rw-transaction over the entity tables + `ops`. Reads sort rank-bearing collections by `(rank, id)` ordinal byte compare. Update methods diff against the current local row and enqueue only changed fields (explicit-null-to-clear preserved). The local `ReportData` builder lives in `packages/app/src/data/local/localReports.ts` as `buildReportData(rows: { tasks; focusSessions; dailyPlans; projects }, start: string, end: string): ReportData` and `LocalDataClient.reports.getData` delegates to it.

### C5. SyncEngine — `packages/app/src/sync/SyncEngine.ts` (R3 push, R4 pull)

```ts
export interface SyncTransport {                            // packages/app/src/sync/SyncTransport.ts (R3)
  send(op: SyncOp): Promise<{ status: number; body?: unknown }>;  // throws TypeError/NetworkError on network failure
  pull(since: number, limit: number): Promise<SyncResponse>;      // GET /api/v1/sync
  ensureInstances(start: string, end: string): Promise<TaskSyncRow[]>;
}

export class SyncEngine implements SyncBridge {            // structurally satisfies SyncBridge (nudge/online/ensureInstances)
  constructor(deps: { store: LocalStore; transport: SyncTransport });
  start(): void;                          // subscribes connectivity + periodic timer + runs first cycle
  stop(): void;
  nudge(): void;                          // SyncBridge.nudge — debounced push (PUSH_DEBOUNCE_MS). `requestSync()` is a kept alias calling nudge()
  requestSync(): void;                    // alias of nudge() (back-compat name); both schedule the debounced cycle
  syncNow(): Promise<void>;               // immediate cycle (manual retry); no-op if a cycle is running
  online(): boolean;
  ensureInstances(start, end): Promise<TaskSyncRow[]>;   // SyncBridge passthrough to transport
  getStatus(): SyncStatus;                // SYNCHRONOUS current snapshot (for AppRoot initial gate + Sign-out pendingOps read)
  retryIssue(id: number): Promise<void>;  // re-queue a parked op
  discardIssue(id: number): Promise<void>;// drop a parked op + run rejection recovery
  subscribe(cb: (s: SyncStatus) => void): () => void;     // push updates; fire once immediately with getStatus()
  onChangesApplied(cb: () => void): () => void;
  onFirstCycleSettled(cb: () => void): () => void;        // rollover deferral hook (fires once per start(), success OR terminal failure)
}
```

Cycle (single-flight per device AND cross-tab via `navigator.locks.request('tmap-sync-' + userId, …)`; falls back to plain in-process mutex when `navigator.locks` is unavailable): **push FIFO → post-push ensure-instances if any replayed op had `regenAfterPush` → pull paged → recovery pull if scheduled → notify**. Pull always runs even when push aborted.

**Pinned engine internals (R3 defines, R4 extends — both MUST use these exact names):**
- Status emit method: `private emitStatus(): void` (pushes a fresh `getStatus()` to all `subscribe` callbacks). There is no `emit()`.
- Recovery flag: **persisted** in meta as `pendingRecovery: boolean` (`store.setMeta('pendingRecovery', …)` / `getMeta('pendingRecovery')`). NOT an in-memory field. Set by R3 (`scheduleRecovery()`, definitive-rejection + `discardIssue`); read+cleared by R4's pull phase.
- Post-push ensure rows buffer: instance field `private pendingEnsureRows: TaskSyncRow[] | null = null`. R3's post-push step assigns it (`this.pendingEnsureRows = rows`); R4's pull phase drains it through `applyPullPage` before the delta pull, then nulls it. R3's `onEnsuredInstances` hook is removed in favor of this buffer.
- Cycle entry: `private async runCycle(): Promise<void>` (push → ensure → `pullPhase()` → notify). `pullPhase()` is a stub in R3 and is fully implemented in R4 — R4 replaces the method body, it does not re-anchor against `emit()`.
- Callback registries: `private changesAppliedCbs: Array<() => void>` and `private firstCycleSettledCbs: Array<() => void>`.

### C5a. Fake sync server harness — `packages/app/src/sync/__tests__/fakeSyncServer.ts` (R3 defines; R4 + R6 reuse)

Single authored surface (R3-3). R4/R6 use ONLY these methods — no inventing:
```ts
export interface FakeSyncServer {
  transport: SyncTransport;                 // hand to the engine
  seed(table: string, rows: unknown[]): void;       // preload server rows (assigns change_seq)
  apply(op: SyncOp): { status: number; body?: unknown }; // internal REST semantics (also used by transport.send)
  tombstone(table: string, key: string): void;      // server-side soft-delete (bumps change_seq, sets deletedAt)
  bumpSeq(table: string, key: string, patch?: object): void; // server-side field change (bumps change_seq)
  failNext(match: (op: SyncOp) => boolean, fault: { status: number; body?: unknown } | 'network'): void; // one-shot injection
  pullCount(): number;                      // number of pull() calls served (assertion helper)
  latency(ms: number): void;                // optional artificial delay
}
```
`rejectNext(status, detail)` is NOT part of the surface — R4 tests express rejections via `failNext(matcher, { status, body })`.

### C6. Backend sync slice — `backend/src/Tmap.Api/Features/Sync/` (R0)

`GET /api/v1/sync?since={long}&limit={int}` (auth required; limit default+max 500):

```csharp
public sealed record SyncResponse(SyncChanges Changes, long NextSince, bool HasMore);
public sealed record SyncChanges(
    List<TaskSyncRow> Tasks, List<SubtaskSyncRow> Subtasks, List<ProjectSyncRow> Projects,
    List<NoteGroupSyncRow> NoteGroups, List<NoteSyncRow> Notes,
    List<RecurrenceRuleSyncRow> RecurrenceRules, List<FocusSessionSyncRow> FocusSessions,
    List<DailyPlanSyncRow> DailyPlans, List<SettingSyncRow> Settings);
```

Each `*SyncRow` mirrors the corresponding `*Response` field set **minus nested children** (TaskSyncRow has no Subtasks list) **plus `long ChangeSeq` and `DateTimeOffset? DeletedAt`**. `SettingSyncRow(string Key, string Value, long ChangeSeq, DateTimeOffset? DeletedAt)`. `DailyPlanSyncRow(DateOnly Date, DateTimeOffset CommittedAt, List<Guid> PlannedTaskIds, int PlannedMinutes, long ChangeSeq, DateTimeOffset? DeletedAt)`. `recurrence_exceptions` is NOT synced. The handler opens ONE serializable-snapshot transaction (`IsolationLevel.RepeatableRead`) for all 9 table queries (`IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])`, Tenant filter stays ON), merges by global `change_seq`, cuts at `limit`, `NextSince` = max returned seq (or `since` when empty), `HasMore` = more rows existed beyond the cut.

Migration `ChangeSeqIndexes`: `(user_id, change_seq)` index on all 10 synced tables.

### C7. Backend touch-ups (R0) — contracts the client relies on

1. **Idempotent creates:** PK conflict on an owned row → `200 OK` with the existing row's response DTO (tasks, subtasks, projects, note-groups, notes, focus-sessions, recurrence create). Implemented as a pre-insert lookup by id (`IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])`): live row → 200 + DTO; tombstoned row → 200 + DTO of the tombstone (client recovery reconciles); no row → proceed.
2. **Unique-name 409:** project create/rename hitting `projects (user_id, name) WHERE deleted_at IS NULL` → `409` ProblemDetails with `extensions.existingId` (the conflicting live project's id). Pre-check by `(UserId, Name)` before SaveChanges.
3. **Focus sessions:** `CreateFocusSessionRequest` gains `Guid? Id`; same idempotent-create rule.
4. **Recurrence:** `RecurrenceRuleInput` gains `Guid? Id` (rule id) on `POST /api/v1/recurrence`.
5. **Ordering:** every rank-ordered list read orders by `(Rank COLLATE "C", Id)` — tasks (`TasksEndpoints` list + subtask join ordering stays `SortOrder` then `Id`), projects (already `"C"`, add `Id` tiebreak), notes, note-groups.
6. After R0 is green: `npm run gen:api-client` regenerates `packages/api-client/src/schema.d.ts` (new `/sync` path + SyncRow schemas + changed create DTOs).

### C8. Client auth touch-ups (R5)

1. `refreshClient.ts`: on 401 → `refresh()`; **resolved null** (true 401) → `doLogout()` + throw `'Session expired — refresh failed'`; **rejected/thrown** (transient network/5xx) → re-throw WITHOUT logout (the engine treats it like a push network error). Remove `.catch(() => null)`.
2. Desktop IPC `secureStore:refreshAndGetAccess` returns a discriminated result:
   `{ ok: true, accessToken: string, expiresIn: number, user: {...} } | { ok: false, reason: 'unauthorized' | 'transient' }` — keychain token cleared ONLY on `unauthorized`. `DesktopPlatform.refreshAndGetAccess` maps `transient` → `throw Object.assign(new Error('refresh transient failure'), { name: 'NetworkError' })`, `unauthorized` → `null` (matching `WebPlatform`'s SF-3 contract). `preload.ts` + `electron-api.d.ts` updated to the new shape.
3. `authStore.bootstrap`: network-error branch (existing `isNetworkError`) additionally consults `getLastUserId()` + that DB's `meta.lastUser`; when present → state `authed` with `user = lastUser`, `accessToken = null`, `networkError: true` (authed-offline). True-401 branch keeps DB + queue (no wipe), clears `tmap:lastUserId`? **NO — pointer persists on session expiry** (re-login reuses the DB); pointer cleared only by explicit logout.

### C9. Store changes (R5) — the ONLY store changes

1. New action `refreshFromLocal(): Promise<void>` — re-reads tasks/projects/noteGroups (and settings via `loadSettings`-equivalent local read) from the seam; sets state; NO rollover, NO ensureInstances.
2. `updateRecurrenceSeries` / `deleteRecurrenceSeries` / `deleteRecurrenceSeriesFuture`: replace their `await get().loadTasks()` tails with `await get().refreshFromLocal()`.
3. Rollover split: extract the rollover pass from `loadTasks` into exported action `runRolloverOnce(): Promise<void>` (idempotent per session via module flag); `loadTasks` no longer rolls over. AppRoot calls `runRolloverOnce()` either when `engine.onFirstCycleSettled` fires (session started online) or immediately after `initialLoad()` (started offline). `loadTasks` keeps its ensure-instances horizon call (it is read-through; offline returns `[]`).
4. Banner: rollover/settings `setOnlineError` call sites removed (paths are local now); the banner remains mounted for sync-issue summaries + initial-sync failure (set by AppRoot from engine status).

### C10. Wiring (R5) — `AppRoot.tsx`

`onAuthed(user)` → `setLastUserId(user.id)` → `LocalStore.open(user.id)` → write `meta.lastUser` → `new SyncEngine({ store, transport: new HttpSyncTransport(refreshClient) })` → `new LocalDataClient(store, engine)` (the engine IS the `SyncBridge` — it exposes `nudge`/`online`/`ensureInstances`, C5) → `setDataClient(localDataClient)` → **`engine.onChangesApplied(() => { void useStore.getState().refreshFromLocal(); })`** (the remote-pull→UI-refresh path required by spec §1/AC3 — MUST be wired here) → `engine.onFirstCycleSettled(() => { void useStore.getState().runRolloverOnce(); })` when started online (else call `runRolloverOnce()` immediately after `initialLoad()`) → `engine.start()` → gate: if `engine.getStatus().initialSyncComplete` is false, render `<InitialSyncGate/>` (spinner + error/retry + renders-partial-after-failure per spec §7.4) until a subscribed status reports `initialSyncComplete` → `initialLoad()`. `onLoggedOut` → `engine.stop()` → `store.close()`. Explicit logout (new `Sign out` button in `SettingsDialog` + confirm dialog warning when `engine.getStatus().pendingOps > 0`) → `LocalStore.wipe(userId)` + `setLastUserId(null)` then `authStore.logout()`. The pill: `src/components/SyncStatusPill.tsx`, mounted in `App.tsx` titlebar region, fed by `engine.subscribe` (initial value from `engine.getStatus()`).

Wiring logic is extracted into a pure factory `createSyncWiring(deps)` (unit-tested) so AppRoot stays thin; the factory MUST register the `onChangesApplied → refreshFromLocal` and `onFirstCycleSettled → runRolloverOnce` subscriptions and return them for teardown.

### C11. Conventions (apply to every task)

- TDD cadence per task: write failing test (full code) → run, expect FAIL (state expected error) → minimal impl (full code) → run, expect PASS → commit (exact `git add` + message).
- Commit messages end with: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- Run commands: backend `dotnet test` from `backend/` (Docker must be up; restart Docker Desktop if the daemon dropped); client `npm run test:app` (all) or `npx vitest run <file>` from `packages/app`; builds `npm run build:apps`.
- Client tests import `fake-indexeddb/auto` FIRST (before Dexie) in every Dexie-touching test file.
- Do not modify anything under `docs/` except adding this plan. Never touch `packages/api-client/src/schema.d.ts` by hand — regen only.
- Phase end: green-gate task (run the full relevant suites + builds; HARD GATE).

---

## File structure map

**Created:** `backend/src/Tmap.Api/Features/Sync/{SyncEndpoints.cs,SyncDtos.cs}` · `backend/src/Tmap.Api/Common/CreateConflict.cs` (idempotent-create/409 helper) · migration `ChangeSeqIndexes` · `backend/tests/Tmap.Api.Tests/SyncTests.cs` · `packages/app/src/sync/{types.ts,constants.ts,SyncEngine.ts,SyncTransport.ts,connectivity.ts}` · `packages/app/src/data/local/{LocalStore.ts,LocalDataClient.ts,applyPull.ts,localReports.ts,rows.ts}` · `packages/app/src/components/SyncStatusPill.tsx` · `packages/app/src/components/InitialSyncGate.tsx` · client test files per phase (`packages/app/src/data/local/__tests__/*`, `packages/app/src/sync/__tests__/*` incl. `fakeSyncServer.ts` harness).

**Modified:** backend endpoint files per C7 + their per-slice tests · `packages/app/package.json` (dexie, fake-indexeddb) · `packages/app/src/auth/{refreshClient.ts,authStore.ts}` (+ their tests) · `packages/app/src/store.ts` (C9) · `packages/app/src/AppRoot.tsx` (C10) · `packages/app/src/components/{App.tsx,SettingsDialog.tsx}` · `apps/desktop/electron/{main.ts,preload.ts}` · `apps/desktop/src/electron-api.d.ts` · `apps/desktop/src/platform/DesktopPlatform.ts` · `apps/desktop/package.json` (drop sql.js + wasm extraResources, R6).

**Deleted (R6):** `apps/desktop/electron/{database.ts,taskService.ts,noteService.ts,projectService.ts,dailyPlanService.ts,focusSessionService.ts,reportService.ts,recurrenceUtils.ts,seed.ts,sql.js.d.ts}` · `packages/app/src/data/HttpDataClient.ts` + `HttpDataClient.test.ts`.

---

## Phase R0 — Backend: sync slice + touch-ups + client regen

This phase makes the backend able to serve offline-first sync: a hand-authored EF migration adds a `(user_id, change_seq)` index to all 10 synced tables; a new `Features/Sync` slice exposes `GET /api/v1/sync` with snapshot-consistent, tombstone-bearing, tenant-isolated paged reads; existing create endpoints become idempotent on owned-PK conflict; project create/rename returns a `409` carrying the existing id; focus-session and recurrence-rule creates accept a client id; and every rank-ordered list read converges on `(rank COLLATE "C", id)`. It ends by regenerating `@tmap/api-client` from the new OpenAPI document and gating on the full backend + client suites and both app builds.

**Created:**
- `backend/src/Tmap.Api/Migrations/20260613090000_ChangeSeqIndexes.cs` (hand-authored)
- `backend/src/Tmap.Api/Features/Sync/SyncDtos.cs`
- `backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs`
- `backend/src/Tmap.Api/Common/CreateConflict.cs`
- `backend/tests/Tmap.Api.Tests/SyncTests.cs`

**Modified:**
- `backend/src/Tmap.Api/Program.cs` (register `MapSyncEndpoints`)
- `backend/src/Tmap.Api/Features/Tasks/TasksEndpoints.cs` (idempotent create + `(rank, id)` ordering)
- `backend/src/Tmap.Api/Features/Subtasks/SubtasksEndpoints.cs` (idempotent create)
- `backend/src/Tmap.Api/Features/Projects/ProjectEndpoints.cs` (idempotent create + 409 unique-name on create/rename + `(rank, id)` ordering)
- `backend/src/Tmap.Api/Features/Projects/ProjectDtos.cs` (`UniqueNameConflictResponse` — n/a, conflict carried in ProblemDetails extension)
- `backend/src/Tmap.Api/Features/NoteGroups/NoteGroupsEndpoints.cs` (idempotent create + `(rank, id)` ordering)
- `backend/src/Tmap.Api/Features/Notes/NotesEndpoints.cs` (idempotent create + `(rank, id)` ordering)
- `backend/src/Tmap.Api/Features/FocusSessions/FocusSessionsDtos.cs` (`Guid? Id`)
- `backend/src/Tmap.Api/Features/FocusSessions/FocusSessionsEndpoints.cs` (idempotent create)
- `backend/src/Tmap.Api/Features/Recurrence/RecurrenceDtos.cs` (`RecurrenceRuleInput.Id`)
- `backend/src/Tmap.Api/Features/Recurrence/RecurrenceEndpoints.cs` (use client rule id)
- `packages/api-client/src/schema.d.ts` (regenerated — never hand-edited)

**Tests (modified):**
- `backend/tests/Tmap.Api.Tests/PersistenceTests.cs` (index-exists assertion)
- `backend/tests/Tmap.Api.Tests/TasksTests.cs` (idempotent create, `(rank, id)` ordering)
- `backend/tests/Tmap.Api.Tests/SubtasksTests.cs` (idempotent create)
- `backend/tests/Tmap.Api.Tests/ProjectsTests.cs` (idempotent create, 409 unique-name on create + rename, `(rank, id)` ordering)
- `backend/tests/Tmap.Api.Tests/NoteGroupsTests.cs` (idempotent create, `(rank, id)` ordering)
- `backend/tests/Tmap.Api.Tests/NotesTests.cs` (idempotent create, `(rank, id)` ordering)
- `backend/tests/Tmap.Api.Tests/FocusSessionsTests.cs` (client-id idempotent create)
- `backend/tests/Tmap.Api.Tests/RecurrenceTests.cs` (client rule id round-trip)

> All commands run with cwd `backend/` unless noted. Docker must be running (Testcontainers Postgres). The contract source of truth is the skeleton's §C6/§C7; where this phase and a contract disagree, the contract wins.

---

### Task R0-1: EF migration `ChangeSeqIndexes` — `(user_id, change_seq)` on all 10 synced tables

**Files:**
- Create: `backend/src/Tmap.Api/Migrations/20260613090000_ChangeSeqIndexes.cs`
- Test (modify): `backend/tests/Tmap.Api.Tests/PersistenceTests.cs`

The pull handler (R0-3) filters `change_seq > since` and orders by `change_seq` **within a single tenant**, so a composite `(user_id, change_seq)` index is the supporting access path. Authored by hand exactly like `20260604092750_SyncTriggersAndRls.cs` (raw SQL over a `SyncedTables` array; no model-snapshot change because the index is created via `CREATE INDEX` SQL, not an EF-modeled `HasIndex`).

- [ ] **Write failing test** — append a new test class to `backend/tests/Tmap.Api.Tests/PersistenceTests.cs` (after the final `RlsCrossTenantTests` class, before EOF):

```csharp
[Collection("db")]
public class ChangeSeqIndexMigrationTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    [Theory]
    [InlineData("tasks")]
    [InlineData("subtasks")]
    [InlineData("projects")]
    [InlineData("note_groups")]
    [InlineData("notes")]
    [InlineData("recurrence_rules")]
    [InlineData("recurrence_exceptions")]
    [InlineData("focus_sessions")]
    [InlineData("daily_plans")]
    [InlineData("user_settings")]
    public async Task UserId_ChangeSeq_Index_Exists_On_Every_Synced_Table(string table)
    {
        await using var ctx = NewElevatedDbContext();

        // pg_indexes.indexdef is the canonical CREATE INDEX text; assert both columns are present
        // in order. The migration names every index ix_{table}_user_id_change_seq.
        var indexName = $"ix_{table}_user_id_change_seq";
        var def = await ctx.Database
            .SqlQuery<string?>(
                $"SELECT indexdef AS \"Value\" FROM pg_indexes WHERE tablename = {table} AND indexname = {indexName}")
            .SingleOrDefaultAsync();

        def.Should().NotBeNull($"{indexName} should exist after the ChangeSeqIndexes migration");
        def!.Should().Contain("user_id");
        def.Should().Contain("change_seq");
    }
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~ChangeSeqIndexMigrationTests"`
  Expected: all 10 `[InlineData]` cases fail with `def.Should().NotBeNull()` — `Expected def not to be <null> ... because ix_<table>_user_id_change_seq should exist after the ChangeSeqIndexes migration`.

- [ ] **Minimal impl** — create `backend/src/Tmap.Api/Migrations/20260613090000_ChangeSeqIndexes.cs`:

```csharp
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Tmap.Api.Migrations;

/// <inheritdoc />
public partial class ChangeSeqIndexes : Migration
{
    // The 10 synced tables (same set the change_seq trigger covers in SyncTriggersAndRls).
    // recurrence_exceptions is NOT pulled by /sync, but it IS a synced (trigger-bearing) table,
    // so it gets the index too for parity with the trigger set.
    private static readonly string[] SyncedTables =
    [
        "tasks", "subtasks", "projects", "note_groups", "notes",
        "recurrence_rules", "recurrence_exceptions", "focus_sessions",
        "daily_plans", "user_settings"
    ];

    /// <inheritdoc />
    protected override void Up(MigrationBuilder migrationBuilder)
    {
        // Composite (user_id, change_seq) index supports the per-tenant pull access path
        // (WHERE user_id = current AND change_seq > since ORDER BY change_seq).
        foreach (var table in SyncedTables)
        {
            migrationBuilder.Sql(
                $@"CREATE INDEX IF NOT EXISTS ""ix_{table}_user_id_change_seq"" ON ""{table}"" (""user_id"", ""change_seq"");");
        }
    }

    /// <inheritdoc />
    protected override void Down(MigrationBuilder migrationBuilder)
    {
        foreach (var table in SyncedTables)
        {
            migrationBuilder.Sql($@"DROP INDEX IF EXISTS ""ix_{table}_user_id_change_seq"";");
        }
    }
}
```

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~ChangeSeqIndexMigrationTests"`
  Expected: 10 passed.

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Migrations/20260613090000_ChangeSeqIndexes.cs backend/tests/Tmap.Api.Tests/PersistenceTests.cs
git commit -m "$(cat <<'EOF'
feat(sync): R0-1 — ChangeSeqIndexes migration ((user_id, change_seq) on all synced tables)

Hand-authored migration mirroring SyncTriggersAndRls: raw CREATE INDEX over the
10 synced tables, supporting the per-tenant change_seq>since pull access path.
PersistenceTests asserts every ix_<table>_user_id_change_seq exists via pg_indexes.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task R0-2: `Features/Sync` skeleton — `GET /api/v1/sync` returns empty arrays + 401 anon

**Files:**
- Create: `backend/src/Tmap.Api/Features/Sync/SyncDtos.cs`
- Create: `backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs`
- Modify: `backend/src/Tmap.Api/Program.cs`
- Test (create): `backend/tests/Tmap.Api.Tests/SyncTests.cs`

Per contract C6: `GET /api/v1/sync?since={long}&limit={int}` returns `SyncResponse(SyncChanges, long NextSince, bool HasMore)`. This task stands up the endpoint with auth, default/empty behavior, and the DTOs; R0-3 fills in the per-table queries. `recurrence_exceptions` is NOT synced.

- [ ] **Write failing test** — create `backend/tests/Tmap.Api.Tests/SyncTests.cs`:

```csharp
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Xunit;

namespace Tmap.Api.Tests;

[Collection("db")]
public class SyncTests(PostgresFixture fixture) : IntegrationTestBase(fixture)
{
    // Local DTOs mirroring the C6 sync wire shape. Kept private to the test file.
    private sealed record SyncResponseDto(SyncChangesDto Changes, long NextSince, bool HasMore);

    private sealed record SyncChangesDto(
        List<TaskSyncRowDto> Tasks,
        List<SubtaskSyncRowDto> Subtasks,
        List<ProjectSyncRowDto> Projects,
        List<NoteGroupSyncRowDto> NoteGroups,
        List<NoteSyncRowDto> Notes,
        List<RecurrenceRuleSyncRowDto> RecurrenceRules,
        List<FocusSessionSyncRowDto> FocusSessions,
        List<DailyPlanSyncRowDto> DailyPlans,
        List<SettingSyncRowDto> Settings);

    private sealed record TaskSyncRowDto(Guid Id, string Title, string Rank, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record SubtaskSyncRowDto(Guid Id, Guid TaskId, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record ProjectSyncRowDto(Guid Id, string Name, string Rank, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record NoteGroupSyncRowDto(Guid Id, string Name, string Rank, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record NoteSyncRowDto(Guid Id, string Rank, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record RecurrenceRuleSyncRowDto(Guid Id, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record FocusSessionSyncRowDto(Guid Id, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record DailyPlanSyncRowDto(DateOnly Date, int PlannedMinutes, long ChangeSeq, DateTimeOffset? DeletedAt);
    private sealed record SettingSyncRowDto(string Key, string Value, long ChangeSeq, DateTimeOffset? DeletedAt);

    [Fact]
    public async Task Sync_RequiresAuthentication()
    {
        var resp = await Client.GetAsync("/api/v1/sync?since=0");
        resp.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Sync_EmptyDb_ReturnsEmptyArrays_NextSinceEqualsSince_HasMoreFalse()
    {
        var user = await RegisterAsync();

        var resp = await user.Client.GetAsync("/api/v1/sync?since=0");
        resp.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await resp.Content.ReadFromJsonAsync<SyncResponseDto>();

        body.Should().NotBeNull();
        body!.Changes.Tasks.Should().BeEmpty();
        body.Changes.Subtasks.Should().BeEmpty();
        body.Changes.Projects.Should().BeEmpty();
        body.Changes.NoteGroups.Should().BeEmpty();
        body.Changes.Notes.Should().BeEmpty();
        body.Changes.RecurrenceRules.Should().BeEmpty();
        body.Changes.FocusSessions.Should().BeEmpty();
        body.Changes.DailyPlans.Should().BeEmpty();
        body.Changes.Settings.Should().BeEmpty();
        body.NextSince.Should().Be(0, "empty pull leaves the cursor where it was");
        body.HasMore.Should().BeFalse();
    }
}
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~SyncTests"`
  Expected: both tests fail with `404 Not Found` (route `/api/v1/sync` is unmapped) — `Expected resp.StatusCode to be HttpStatusCode.Unauthorized {...}, but found HttpStatusCode.NotFound` and the empty-DB test's `body.Should().NotBeNull()` fails.

- [ ] **Minimal impl (DTOs)** — create `backend/src/Tmap.Api/Features/Sync/SyncDtos.cs`:

```csharp
using Tmap.Api.Infrastructure.Entities;
using TaskStatus = Tmap.Api.Infrastructure.Entities.TaskStatus;

namespace Tmap.Api.Features.Sync;

// Each *SyncRow mirrors its *Response field set MINUS nested children, PLUS ChangeSeq + DeletedAt.
// recurrence_exceptions is intentionally NOT synced.

public sealed record TaskSyncRow(
    Guid Id,
    string Title,
    string Notes,
    Guid? ProjectId,
    List<string> Labels,
    string Source,
    TaskStatus Status,
    DateOnly? PlannedDate,
    DateTimeOffset? ScheduledStart,
    DateTimeOffset? ScheduledEnd,
    int DurationMinutes,
    int ActualTimeMinutes,
    int? Priority,
    int? ReminderMinutes,
    string Rank,
    DateOnly? DueDate,
    Guid? RecurrenceRuleId,
    bool IsRecurrenceTemplate,
    bool RecurrenceDetached,
    DateOnly? RecurrenceOriginalDate,
    DateTimeOffset? CompletedAt,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record SubtaskSyncRow(
    Guid Id,
    Guid TaskId,
    string Title,
    bool Completed,
    int SortOrder,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record ProjectSyncRow(
    Guid Id,
    string Name,
    string Color,
    string Emoji,
    string Rank,
    int ActualTimeMinutes,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record NoteGroupSyncRow(
    Guid Id,
    string Name,
    string Emoji,
    Guid? ProjectId,
    string Rank,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record NoteSyncRow(
    Guid Id,
    Guid? GroupId,
    Guid? ProjectId,
    string Title,
    string Content,
    string Rank,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record RecurrenceRuleSyncRow(
    Guid Id,
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate,
    DateOnly? GeneratedUntil,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record FocusSessionSyncRow(
    Guid Id,
    Guid? TaskId,
    string Project,
    DateTimeOffset StartedAt,
    DateTimeOffset EndedAt,
    int Minutes,
    DateOnly Date,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record DailyPlanSyncRow(
    DateOnly Date,
    DateTimeOffset CommittedAt,
    List<Guid> PlannedTaskIds,
    int PlannedMinutes,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record SettingSyncRow(
    string Key,
    string Value,
    long ChangeSeq,
    DateTimeOffset? DeletedAt);

public sealed record SyncChanges(
    List<TaskSyncRow> Tasks,
    List<SubtaskSyncRow> Subtasks,
    List<ProjectSyncRow> Projects,
    List<NoteGroupSyncRow> NoteGroups,
    List<NoteSyncRow> Notes,
    List<RecurrenceRuleSyncRow> RecurrenceRules,
    List<FocusSessionSyncRow> FocusSessions,
    List<DailyPlanSyncRow> DailyPlans,
    List<SettingSyncRow> Settings);

public sealed record SyncResponse(SyncChanges Changes, long NextSince, bool HasMore);
```

- [ ] **Minimal impl (endpoint skeleton)** — create `backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs`. R0-3 replaces the body; the skeleton returns empties:

```csharp
using Microsoft.AspNetCore.Http.HttpResults;
using Tmap.Api.Common;
using Tmap.Api.Infrastructure;

namespace Tmap.Api.Features.Sync;

public static class SyncEndpoints
{
    // Default and maximum page size; clamped in the handler.
    private const int DefaultLimit = 500;
    private const int MaxLimit = 500;

    public static IEndpointRouteBuilder MapSyncEndpoints(this IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("sync").RequireAuthorization();
        group.MapGet("/", Pull).WithName("Sync");
        return app;
    }

    private static async Task<Ok<SyncResponse>> Pull(
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct,
        long since = 0,
        int? limit = null)
    {
        var pageSize = Math.Clamp(limit ?? DefaultLimit, 1, MaxLimit);
        _ = pageSize; // used in R0-3
        _ = since;
        _ = db;
        _ = user;
        await Task.CompletedTask;

        var changes = new SyncChanges(
            Tasks: [], Subtasks: [], Projects: [], NoteGroups: [], Notes: [],
            RecurrenceRules: [], FocusSessions: [], DailyPlans: [], Settings: []);
        return TypedResults.Ok(new SyncResponse(changes, since, false));
    }
}
```

- [ ] **Minimal impl (registration)** — edit `backend/src/Tmap.Api/Program.cs`. Add the using next to the other feature usings (after `using Tmap.Api.Features.Settings;`):

Replace:
```csharp
using Tmap.Api.Features.Settings;
using Tmap.Api.Features.Subtasks;
```
with:
```csharp
using Tmap.Api.Features.Settings;
using Tmap.Api.Features.Subtasks;
using Tmap.Api.Features.Sync;
```

Then replace:
```csharp
apiV1.MapReportsEndpoints();
apiV1.MapRecurrenceEndpoints();
```
with:
```csharp
apiV1.MapReportsEndpoints();
apiV1.MapRecurrenceEndpoints();
apiV1.MapSyncEndpoints();
```

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~SyncTests"`
  Expected: 2 passed.

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Features/Sync/SyncDtos.cs backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs backend/src/Tmap.Api/Program.cs backend/tests/Tmap.Api.Tests/SyncTests.cs
git commit -m "$(cat <<'EOF'
feat(sync): R0-2 — Features/Sync slice skeleton (GET /api/v1/sync)

SyncDtos per C6 (every *SyncRow = *Response minus children, plus ChangeSeq +
DeletedAt); SyncResponse(Changes, NextSince, HasMore). Endpoint requires auth,
clamps limit to [1,500], returns empties for now. Registered in Program.cs.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task R0-3: sync returns changed rows — per-table queries, tombstones, snapshot tx, pagination, cursor

**Files:**
- Modify: `backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs`
- Test (modify): `backend/tests/Tmap.Api.Tests/SyncTests.cs`

Per C6/§4: query each of the 9 pulled tables for `change_seq > since` with `IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])` so tombstones flow (Tenant filter stays ON); all reads run inside ONE `RepeatableRead` transaction for snapshot consistency; rows merge across tables by global `change_seq`; cut at `limit`; `NextSince` = max returned seq (or `since` when empty); `HasMore` = rows existed beyond the cut.

- [ ] **Write failing test** — append to `SyncTests.cs` (inside the class, after the empty-DB test). These reuse the private DTOs already declared in R0-2:

```csharp
    [Fact]
    public async Task Sync_Since0_IncludesLiveRows_And_Tombstones_WithDeletedAtSet()
    {
        var user = await RegisterAsync();

        // Create a project + task via HTTP, then soft-delete the task.
        var proj = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "P", color = "#111111", emoji = "📁", rank = "a0" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();
        var task = await (await user.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "T", rank = "a0" }))
            .Content.ReadFromJsonAsync<TaskSyncRowDto>();
        var delResp = await user.Client.DeleteAsync($"/api/v1/tasks/{task!.Id}");
        delResp.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");

        body!.Changes.Projects.Should().ContainSingle(p => p.Id == proj!.Id && p.DeletedAt == null);
        var pulledTask = body.Changes.Tasks.Should().ContainSingle(t => t.Id == task.Id).Subject;
        pulledTask.DeletedAt.Should().NotBeNull("a soft-deleted task is delivered as a tombstone");
        body.NextSince.Should().BeGreaterThan(0);
        body.HasMore.Should().BeFalse();
    }

    [Fact]
    public async Task Sync_SinceCursor_ReturnsOnlyNewerRows()
    {
        var user = await RegisterAsync();

        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "First", color = "#111111", emoji = "📁", rank = "a0" });
        var cut = await user.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");
        var cursor = cut!.NextSince;

        // A second write after the cursor.
        var second = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "Second", color = "#222222", emoji = "📁", rank = "a1" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();

        var body = await user.Client.GetFromJsonAsync<SyncResponseDto>($"/api/v1/sync?since={cursor}");
        body!.Changes.Projects.Should().ContainSingle(p => p.Id == second!.Id);
        body.Changes.Projects.Should().NotContain(p => p.Name == "First");
        body.NextSince.Should().BeGreaterThan(cursor);
    }

    [Fact]
    public async Task Sync_Paginates_HasMoreTrue_Then_ChainsToCompletion()
    {
        var user = await RegisterAsync();

        // 7 projects; pull with limit=3 chains 3 + 3 + 1.
        for (var i = 0; i < 7; i++)
        {
            await user.Client.PostAsJsonAsync("/api/v1/projects",
                new { name = $"P{i}", color = "#111111", emoji = "📁", rank = $"a{i}" });
        }

        var seen = new HashSet<Guid>();
        long since = 0;
        var pages = 0;
        bool hasMore;
        do
        {
            var page = await user.Client.GetFromJsonAsync<SyncResponseDto>($"/api/v1/sync?since={since}&limit=3");
            page!.Changes.Projects.Count.Should().BeLessThanOrEqualTo(3);
            foreach (var p in page.Changes.Projects)
            {
                seen.Add(p.Id);
            }
            page.NextSince.Should().BeGreaterThanOrEqualTo(since);
            since = page.NextSince;
            hasMore = page.HasMore;
            pages++;
            pages.Should().BeLessThan(10, "pagination must terminate");
        } while (hasMore);

        seen.Should().HaveCount(7, "every project is delivered exactly once across pages");
    }

    [Fact]
    public async Task Sync_OversizedLimit_IsClampedTo500()
    {
        var user = await RegisterAsync();
        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "P", color = "#111111", emoji = "📁", rank = "a0" });

        // limit=99999 must not error; it is clamped. With one row HasMore is false.
        var resp = await user.Client.GetAsync("/api/v1/sync?since=0&limit=99999");
        resp.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await resp.Content.ReadFromJsonAsync<SyncResponseDto>();
        body!.Changes.Projects.Should().ContainSingle();
        body.HasMore.Should().BeFalse();
    }
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~SyncTests"`
  Expected: the 4 new tests fail (the skeleton returns empties) — e.g. `Sync_Since0_IncludesLiveRows_And_Tombstones_WithDeletedAtSet` fails at `body!.Changes.Projects.Should().ContainSingle(...)` with `Expected ... to contain a single item ... but the collection is empty`. The 2 prior tests still pass.

- [ ] **Minimal impl** — replace the entire body of `Pull` in `backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs`. Replace this current method:

```csharp
    private static async Task<Ok<SyncResponse>> Pull(
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct,
        long since = 0,
        int? limit = null)
    {
        var pageSize = Math.Clamp(limit ?? DefaultLimit, 1, MaxLimit);
        _ = pageSize; // used in R0-3
        _ = since;
        _ = db;
        _ = user;
        await Task.CompletedTask;

        var changes = new SyncChanges(
            Tasks: [], Subtasks: [], Projects: [], NoteGroups: [], Notes: [],
            RecurrenceRules: [], FocusSessions: [], DailyPlans: [], Settings: []);
        return TypedResults.Ok(new SyncResponse(changes, since, false));
    }
```

with:

```csharp
    private static async Task<Ok<SyncResponse>> Pull(
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct,
        long since = 0,
        int? limit = null)
    {
        var pageSize = Math.Clamp(limit ?? DefaultLimit, 1, MaxLimit);

        // One REPEATABLE READ snapshot for all per-table reads so the page is internally
        // consistent (no torn view of a multi-row bulk write committing mid-read).
        await using var tx = await db.Database.BeginTransactionAsync(
            System.Data.IsolationLevel.RepeatableRead, ct);

        // Each table read: change_seq > since, SoftDelete filter OFF (tombstones included),
        // Tenant filter ON (RLS + EF tenant scope keep cross-user rows out). Take pageSize+1
        // PER TABLE bounds memory; the global merge + cut decides what is actually returned.
        var taskRows = await db.Tasks
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(t => t.ChangeSeq > since)
            .OrderBy(t => t.ChangeSeq)
            .Take(pageSize + 1)
            .Select(t => new TaskSyncRow(
                t.Id, t.Title, t.Notes, t.ProjectId, t.Labels, t.Source, t.Status,
                t.PlannedDate, t.ScheduledStart, t.ScheduledEnd, t.DurationMinutes,
                t.ActualTimeMinutes, t.Priority, t.ReminderMinutes, t.Rank, t.DueDate,
                t.RecurrenceRuleId, t.IsRecurrenceTemplate, t.RecurrenceDetached,
                t.RecurrenceOriginalDate, t.CompletedAt, t.CreatedAt, t.UpdatedAt,
                t.ChangeSeq, t.DeletedAt))
            .ToListAsync(ct);

        var subtaskRows = await db.Subtasks
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(s => s.ChangeSeq > since)
            .OrderBy(s => s.ChangeSeq)
            .Take(pageSize + 1)
            .Select(s => new SubtaskSyncRow(
                s.Id, s.TaskId, s.Title, s.Completed, s.SortOrder,
                s.CreatedAt, s.UpdatedAt, s.ChangeSeq, s.DeletedAt))
            .ToListAsync(ct);

        var projectRows = await db.Projects
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(p => p.ChangeSeq > since)
            .OrderBy(p => p.ChangeSeq)
            .Take(pageSize + 1)
            .Select(p => new ProjectSyncRow(
                p.Id, p.Name, p.Color, p.Emoji, p.Rank, p.ActualTimeMinutes,
                p.CreatedAt, p.UpdatedAt, p.ChangeSeq, p.DeletedAt))
            .ToListAsync(ct);

        var noteGroupRows = await db.NoteGroups
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(g => g.ChangeSeq > since)
            .OrderBy(g => g.ChangeSeq)
            .Take(pageSize + 1)
            .Select(g => new NoteGroupSyncRow(
                g.Id, g.Name, g.Emoji, g.ProjectId, g.Rank,
                g.CreatedAt, g.UpdatedAt, g.ChangeSeq, g.DeletedAt))
            .ToListAsync(ct);

        var noteRows = await db.Notes
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(n => n.ChangeSeq > since)
            .OrderBy(n => n.ChangeSeq)
            .Take(pageSize + 1)
            .Select(n => new NoteSyncRow(
                n.Id, n.GroupId, n.ProjectId, n.Title, n.Content, n.Rank,
                n.CreatedAt, n.UpdatedAt, n.ChangeSeq, n.DeletedAt))
            .ToListAsync(ct);

        var ruleRows = await db.RecurrenceRules
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(r => r.ChangeSeq > since)
            .OrderBy(r => r.ChangeSeq)
            .Take(pageSize + 1)
            .Select(r => new RecurrenceRuleSyncRow(
                r.Id, r.Frequency, r.IntervalValue, r.DaysOfWeek, r.EndType,
                r.EndCount, r.EndDate, r.GeneratedUntil, r.CreatedAt, r.UpdatedAt,
                r.ChangeSeq, r.DeletedAt))
            .ToListAsync(ct);

        var focusRows = await db.FocusSessions
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(f => f.ChangeSeq > since)
            .OrderBy(f => f.ChangeSeq)
            .Take(pageSize + 1)
            .Select(f => new FocusSessionSyncRow(
                f.Id, f.TaskId, f.Project, f.StartedAt, f.EndedAt, f.Minutes, f.Date,
                f.CreatedAt, f.UpdatedAt, f.ChangeSeq, f.DeletedAt))
            .ToListAsync(ct);

        var planRows = await db.DailyPlans
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(p => p.ChangeSeq > since)
            .OrderBy(p => p.ChangeSeq)
            .Take(pageSize + 1)
            .Select(p => new DailyPlanSyncRow(
                p.Date, p.CommittedAt, p.PlannedTaskIds, p.PlannedMinutes,
                p.ChangeSeq, p.DeletedAt))
            .ToListAsync(ct);

        var settingRows = await db.UserSettings
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .Where(s => s.ChangeSeq > since)
            .OrderBy(s => s.ChangeSeq)
            .Take(pageSize + 1)
            .Select(s => new SettingSyncRow(s.Key, s.Value, s.ChangeSeq, s.DeletedAt))
            .ToListAsync(ct);

        await tx.CommitAsync(ct);

        // Global merge by change_seq, then cut at pageSize. Track each row's seq so the cut
        // is decided once across every table; HasMore = any table had a row beyond the cut.
        var tagged = new List<(long Seq, int Table)>(
            taskRows.Count + subtaskRows.Count + projectRows.Count + noteGroupRows.Count +
            noteRows.Count + ruleRows.Count + focusRows.Count + planRows.Count + settingRows.Count);

        void Tag(IEnumerable<long> seqs, int table)
        {
            foreach (var seq in seqs)
            {
                tagged.Add((seq, table));
            }
        }

        Tag(taskRows.Select(r => r.ChangeSeq), 0);
        Tag(subtaskRows.Select(r => r.ChangeSeq), 1);
        Tag(projectRows.Select(r => r.ChangeSeq), 2);
        Tag(noteGroupRows.Select(r => r.ChangeSeq), 3);
        Tag(noteRows.Select(r => r.ChangeSeq), 4);
        Tag(ruleRows.Select(r => r.ChangeSeq), 5);
        Tag(focusRows.Select(r => r.ChangeSeq), 6);
        Tag(planRows.Select(r => r.ChangeSeq), 7);
        Tag(settingRows.Select(r => r.ChangeSeq), 8);

        tagged.Sort((a, b) => a.Seq.CompareTo(b.Seq));

        var hasMore = tagged.Count > pageSize;
        var kept = hasMore ? tagged.Take(pageSize).ToList() : tagged;

        // The seq of the last kept row is the cut boundary; everything <= it (per table) is returned.
        var cutSeq = kept.Count > 0 ? kept[^1].Seq : since;

        var changes = new SyncChanges(
            Tasks: taskRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            Subtasks: subtaskRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            Projects: projectRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            NoteGroups: noteGroupRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            Notes: noteRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            RecurrenceRules: ruleRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            FocusSessions: focusRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            DailyPlans: planRows.Where(r => r.ChangeSeq <= cutSeq).ToList(),
            Settings: settingRows.Where(r => r.ChangeSeq <= cutSeq).ToList());

        var nextSince = kept.Count > 0 ? cutSeq : since;
        return TypedResults.Ok(new SyncResponse(changes, nextSince, hasMore));
    }
```

> Contract-gap note: the merge cuts on the **seq of the last kept row** rather than blindly taking exactly `pageSize` tagged entries. This guarantees no row is split across pages by `change_seq` (two rows can never share a seq — the trigger draws from a single global sequence), so `cutSeq` cleanly partitions returned vs. deferred rows and a `since=cutSeq` follow-up never re-sees or skips a row. `HasMore` stays true whenever any tagged row sat beyond the cut. This realizes C6's "merge by global change_seq, cut at limit, NextSince = max returned seq" exactly.

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~SyncTests"`
  Expected: 6 passed.

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Features/Sync/SyncEndpoints.cs backend/tests/Tmap.Api.Tests/SyncTests.cs
git commit -m "$(cat <<'EOF'
feat(sync): R0-3 — sync delta reads (tombstones, snapshot tx, pagination, cursor)

Pull queries 9 synced tables for change_seq>since with SoftDelete filter off
(tombstones flow, Tenant filter on) inside one RepeatableRead transaction;
merges by global change_seq; cuts on the last-kept seq; NextSince = that seq;
HasMore when rows existed beyond the cut; limit clamped to [1,500].

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task R0-4: tenant isolation — user A's sync never contains user B's rows (RLS FORCE'd)

**Files:**
- Test (modify): `backend/tests/Tmap.Api.Tests/SyncTests.cs`

`PostgresFixture` runs the app as a NON-SUPERUSER, NOBYPASSRLS role and `FORCE`s RLS, so the Tenant EF filter plus the DB policy together guarantee isolation. Proven by data only — no production change.

- [ ] **Write failing test** — append to `SyncTests.cs`:

```csharp
    [Fact]
    public async Task Sync_IsTenantIsolated_UserA_NeverSees_UserB_Rows()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();

        // B creates a project + task.
        var bProj = await (await b.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "B-secret", color = "#111111", emoji = "🔒", rank = "a0" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();
        var bTask = await (await b.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "B-task", rank = "a0" }))
            .Content.ReadFromJsonAsync<TaskSyncRowDto>();

        // A creates one of their own.
        var aProj = await (await a.Client.PostAsJsonAsync("/api/v1/projects",
            new { name = "A-own", color = "#222222", emoji = "📁", rank = "a0" }))
            .Content.ReadFromJsonAsync<ProjectSyncRowDto>();

        var aSync = await a.Client.GetFromJsonAsync<SyncResponseDto>("/api/v1/sync?since=0");

        aSync!.Changes.Projects.Should().Contain(p => p.Id == aProj!.Id);
        aSync.Changes.Projects.Should().NotContain(p => p.Id == bProj!.Id,
            "RLS + Tenant filter must keep userB's project out of userA's sync");
        aSync.Changes.Tasks.Should().NotContain(t => t.Id == bTask!.Id,
            "RLS + Tenant filter must keep userB's task out of userA's sync");
    }
```

- [ ] **Run, expect PASS** (no source change — R0-3 already enforces tenancy) — `dotnet test --filter "FullyQualifiedName~SyncTests"`
  Expected: 7 passed. (If this fails it indicates an isolation bug introduced in R0-3 — STOP and fix R0-3, do not relax the test.)

- [ ] **Commit**:

```bash
git add backend/tests/Tmap.Api.Tests/SyncTests.cs
git commit -m "$(cat <<'EOF'
test(sync): R0-4 — sync tenant isolation under FORCE'd RLS

Asserts userA's /sync never contains userB's project or task rows. No source
change — R0-3's Tenant filter plus the non-superuser RLS-FORCE'd test role
already guarantee isolation; this pins it.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task R0-5: `Common/CreateConflict.cs` + idempotent PK-conflict creates (tasks, subtasks, projects, note-groups, notes)

**Files:**
- Create: `backend/src/Tmap.Api/Common/CreateConflict.cs`
- Modify: `backend/src/Tmap.Api/Features/Tasks/TasksEndpoints.cs`
- Modify: `backend/src/Tmap.Api/Features/Subtasks/SubtasksEndpoints.cs`
- Modify: `backend/src/Tmap.Api/Features/Projects/ProjectEndpoints.cs`
- Modify: `backend/src/Tmap.Api/Features/NoteGroups/NoteGroupsEndpoints.cs`
- Modify: `backend/src/Tmap.Api/Features/Notes/NotesEndpoints.cs`
- Test (modify): `TasksTests.cs`, `SubtasksTests.cs`, `ProjectsTests.cs`, `NoteGroupsTests.cs`, `NotesTests.cs`

Per C7.1: a create whose client id already exists (own a live OR tombstoned row) returns `200 OK` with the existing row's DTO — a pre-insert lookup with `IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])`. The return type changes from `Created<T>` to `Results<Created<T>, Ok<T>, …>`. `CreateConflict` is a tiny shared helper for the lookup.

- [ ] **Write failing tests** — add to each per-slice test file:

In `backend/tests/Tmap.Api.Tests/TasksTests.cs` (add inside the existing `TasksTests` class):
```csharp
    [Fact]
    public async Task Create_SameClientId_Twice_Returns200_SameId_NoDuplicate()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new { id, title = "Replayed", rank = "a0" };

        var first = await user.Client.PostAsJsonAsync("/api/v1/tasks", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);

        var second = await user.Client.PostAsJsonAsync("/api/v1/tasks", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK, "replaying a create is idempotent");
        var dto = await second.Content.ReadFromJsonAsync<TaskResponse>();
        dto!.Id.Should().Be(id);

        var list = await user.Client.GetFromJsonAsync<List<TaskResponse>>("/api/v1/tasks");
        list!.Count(t => t.Id == id).Should().Be(1, "no duplicate row");
    }
```

In `backend/tests/Tmap.Api.Tests/SubtasksTests.cs` (add inside the class):
```csharp
    [Fact]
    public async Task Create_SameClientId_Twice_Returns200_SameId_NoDuplicate()
    {
        var user = await RegisterAsync();
        var task = await (await user.Client.PostAsJsonAsync("/api/v1/tasks",
            new { title = "parent", rank = "a0" }))
            .Content.ReadFromJsonAsync<Tmap.Api.Features.Tasks.TaskResponse>();
        var subId = Guid.CreateVersion7();
        var body = new { id = subId, title = "S" };

        var first = await user.Client.PostAsJsonAsync($"/api/v1/tasks/{task!.Id}/subtasks", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);

        var second = await user.Client.PostAsJsonAsync($"/api/v1/tasks/{task.Id}/subtasks", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto = await second.Content.ReadFromJsonAsync<Tmap.Api.Features.Subtasks.SubtaskResponse>();
        dto!.Id.Should().Be(subId);

        var tasks = await user.Client.GetFromJsonAsync<List<Tmap.Api.Features.Tasks.TaskResponse>>("/api/v1/tasks");
        tasks!.Single(t => t.Id == task.Id).Subtasks.Count(s => s.Id == subId).Should().Be(1);
    }
```

In `backend/tests/Tmap.Api.Tests/ProjectsTests.cs` (add inside the class):
```csharp
    [Fact]
    public async Task Create_SameClientId_Twice_Returns200_SameId_NoDuplicate()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new CreateProjectRequest("Replayed", "#111111", "📁", "a0", id);

        var first = await user.Client.PostAsJsonAsync("/api/v1/projects", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);

        var second = await user.Client.PostAsJsonAsync("/api/v1/projects", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto = await second.Content.ReadFromJsonAsync<ProjectResponse>();
        dto!.Id.Should().Be(id);

        var list = await user.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        list!.Count(p => p.Id == id).Should().Be(1);
    }
```

In `backend/tests/Tmap.Api.Tests/NoteGroupsTests.cs` (add inside the class):
```csharp
    [Fact]
    public async Task Create_SameClientId_Twice_Returns200_SameId_NoDuplicate()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new { id, name = "G", emoji = "📝", projectId = (Guid?)null, rank = "a0" };

        var first = await user.Client.PostAsJsonAsync("/api/v1/note-groups", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);

        var second = await user.Client.PostAsJsonAsync("/api/v1/note-groups", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto = await second.Content.ReadFromJsonAsync<NoteGroupResponse>();
        dto!.Id.Should().Be(id);

        var list = await user.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        list!.Count(g => g.Id == id).Should().Be(1);
    }
```

In `backend/tests/Tmap.Api.Tests/NotesTests.cs` (add inside the class):
```csharp
    [Fact]
    public async Task Create_SameClientId_Twice_Returns200_SameId_NoDuplicate()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new { id, groupId = (Guid?)null, projectId = (Guid?)null, title = "N", content = "", rank = "a0" };

        var first = await user.Client.PostAsJsonAsync("/api/v1/notes", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);

        var second = await user.Client.PostAsJsonAsync("/api/v1/notes", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK);
        var dto = await second.Content.ReadFromJsonAsync<NoteResponse>();
        dto!.Id.Should().Be(id);

        var list = await user.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        list!.Count(n => n.Id == id).Should().Be(1);
    }
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~Create_SameClientId_Twice"`
  Expected: all 5 fail at the second POST — `Expected ... to be HttpStatusCode.OK ... but found HttpStatusCode.Created` (note-groups/notes/projects today insert a duplicate-PK row → actually `500`/`Created` depending; the assertion `Be(OK)` fails regardless). For projects with a duplicate PK the DB raises a unique violation surfaced as `500` — also a fail of `Be(OK)`.

- [ ] **Minimal impl (helper)** — create `backend/src/Tmap.Api/Common/CreateConflict.cs`:

```csharp
using Microsoft.EntityFrameworkCore;
using Tmap.Api.Infrastructure;

namespace Tmap.Api.Common;

/// <summary>
/// Idempotent-create support: a replayed create (same client id) must return the existing
/// row instead of inserting a duplicate or 500'ing. Looks up by id with the soft-delete
/// filter OFF (a tombstoned row also counts as "exists"; the client's recovery reconciles
/// the tombstone). The Tenant filter stays ON, so a foreign id is treated as "not found"
/// and the create proceeds normally (the ownership interceptor stamps the caller's id).
/// </summary>
public static class CreateConflict
{
    public static async Task<TEntity?> FindExistingByIdAsync<TEntity>(
        DbSet<TEntity> set,
        System.Linq.Expressions.Expression<Func<TEntity, bool>> idMatch,
        CancellationToken ct)
        where TEntity : class
    {
        return await set
            .IgnoreQueryFilters([AppDbContext.SoftDeleteFilter])
            .FirstOrDefaultAsync(idMatch, ct);
    }
}
```

- [ ] **Minimal impl (tasks)** — in `TasksEndpoints.cs`, change the signature and add the pre-insert lookup. Replace:

```csharp
    internal static async Task<Results<Created<TaskResponse>, ValidationProblem>> CreateAsync(
        CreateTaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        // WRITE-side ownership: tenant-filtered Projects means a non-match = not the caller's.
        if (req.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }
```

with:

```csharp
    internal static async Task<Results<Created<TaskResponse>, Ok<TaskResponse>, ValidationProblem>> CreateAsync(
        CreateTaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        // Idempotent replay: an existing owned row with this id (live or tombstoned) → 200 + its DTO.
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existing = await CreateConflict.FindExistingByIdAsync(
                db.Tasks.Include(t => t.Subtasks), t => t.Id == reqId, ct);
            if (existing is not null)
            {
                var existingSubtasks = existing.Subtasks
                    .Where(st => st.DeletedAt == null)
                    .OrderBy(st => st.SortOrder)
                    .Select(ToSubtaskResponse)
                    .ToList();
                return TypedResults.Ok(ToResponse(existing, existingSubtasks));
            }
        }

        // WRITE-side ownership: tenant-filtered Projects means a non-match = not the caller's.
        if (req.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }
```

- [ ] **Minimal impl (subtasks)** — in `SubtasksEndpoints.cs`, replace:

```csharp
    internal static async Task<Results<Created<SubtaskResponse>, NotFound, ValidationProblem>> CreateAsync(
        Guid taskId,
        CreateSubtaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        // parent must be visible to the caller (tenant filter) else 404
        var parentExists = await db.Tasks.AnyAsync(t => t.Id == taskId, ct);
        if (!parentExists)
        {
            return TypedResults.NotFound();
        }
```

with:

```csharp
    internal static async Task<Results<Created<SubtaskResponse>, Ok<SubtaskResponse>, NotFound, ValidationProblem>> CreateAsync(
        Guid taskId,
        CreateSubtaskRequest req,
        AppDbContext db,
        ICurrentUser user,
        CancellationToken ct)
    {
        // Idempotent replay: an existing owned subtask with this id (live or tombstoned) → 200 + its DTO.
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existing = await CreateConflict.FindExistingByIdAsync(
                db.Subtasks, s => s.Id == reqId, ct);
            if (existing is not null)
            {
                return TypedResults.Ok(TasksEndpoints.ToSubtaskResponse(existing));
            }
        }

        // parent must be visible to the caller (tenant filter) else 404
        var parentExists = await db.Tasks.AnyAsync(t => t.Id == taskId, ct);
        if (!parentExists)
        {
            return TypedResults.NotFound();
        }
```

Add the using at the top of `SubtasksEndpoints.cs` (it already has `using Tmap.Api.Common;`, so `CreateConflict` resolves — no change needed).

- [ ] **Minimal impl (projects)** — in `ProjectEndpoints.cs`, replace the `Create` signature/preamble. Replace:

```csharp
    private static async Task<Created<ProjectResponse>> Create(
        CreateProjectRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Rank is optional — when omitted, server appends after the user's current max rank.
        var rank = !string.IsNullOrEmpty(req.Rank)
```

with:

```csharp
    private static async Task<Results<Created<ProjectResponse>, Ok<ProjectResponse>>> Create(
        CreateProjectRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Idempotent replay: an existing owned project with this id (live or tombstoned) → 200 + its DTO.
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existingById = await CreateConflict.FindExistingByIdAsync(
                db.Projects, p => p.Id == reqId, ct);
            if (existingById is not null)
            {
                return TypedResults.Ok(ToResponse(existingById));
            }
        }

        // Rank is optional — when omitted, server appends after the user's current max rank.
        var rank = !string.IsNullOrEmpty(req.Rank)
```

And update the success return. Replace:
```csharp
        db.Projects.Add(project);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created($"/api/v1/projects/{project.Id}", ToResponse(project));
    }
```
with:
```csharp
        db.Projects.Add(project);
        await db.SaveChangesAsync(ct);

        return TypedResults.Created($"/api/v1/projects/{project.Id}", ToResponse(project));
    }
```
(no change to the success line itself — `Created<T>` is still one of the union members).

> Note: `ProjectEndpoints.cs` already imports `using Tmap.Api.Common;`, so `CreateConflict` resolves. The unique-name `409` is added in R0-6 on top of this signature.

- [ ] **Minimal impl (note-groups)** — in `NoteGroupsEndpoints.cs`, replace:

```csharp
    private static async Task<Results<Created<NoteGroupResponse>, ValidationProblem>> Create(
        CreateNoteGroupRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // WRITE-side ownership: tenant-filtered Projects means a non-match = not the caller's.
        if (req.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }
```

with:

```csharp
    private static async Task<Results<Created<NoteGroupResponse>, Ok<NoteGroupResponse>, ValidationProblem>> Create(
        CreateNoteGroupRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Idempotent replay: an existing owned note-group with this id (live or tombstoned) → 200 + its DTO.
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existing = await CreateConflict.FindExistingByIdAsync(
                db.NoteGroups, g => g.Id == reqId, ct);
            if (existing is not null)
            {
                return TypedResults.Ok(ToResponse(existing));
            }
        }

        // WRITE-side ownership: tenant-filtered Projects means a non-match = not the caller's.
        if (req.ProjectId is { } pid && !await db.Projects.AnyAsync(p => p.Id == pid, ct))
        {
            return ProjectNotOwned();
        }
```

- [ ] **Minimal impl (notes)** — in `NotesEndpoints.cs`, replace:

```csharp
    private static async Task<Results<Created<NoteResponse>, ValidationProblem>> Create(
        CreateNoteRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // WRITE-side ownership: tenant-filtered NoteGroups/Projects mean a non-match = not the caller's.
        if (req.GroupId is { } gid && !await db.NoteGroups.AnyAsync(g => g.Id == gid, ct))
        {
            return GroupNotOwned();
        }
```

with:

```csharp
    private static async Task<Results<Created<NoteResponse>, Ok<NoteResponse>, ValidationProblem>> Create(
        CreateNoteRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Idempotent replay: an existing owned note with this id (live or tombstoned) → 200 + its DTO.
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existing = await CreateConflict.FindExistingByIdAsync(
                db.Notes, n => n.Id == reqId, ct);
            if (existing is not null)
            {
                return TypedResults.Ok(ToResponse(existing));
            }
        }

        // WRITE-side ownership: tenant-filtered NoteGroups/Projects mean a non-match = not the caller's.
        if (req.GroupId is { } gid && !await db.NoteGroups.AnyAsync(g => g.Id == gid, ct))
        {
            return GroupNotOwned();
        }
```

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~Create_SameClientId_Twice"`
  Expected: 5 passed.

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Common/CreateConflict.cs backend/src/Tmap.Api/Features/Tasks/TasksEndpoints.cs backend/src/Tmap.Api/Features/Subtasks/SubtasksEndpoints.cs backend/src/Tmap.Api/Features/Projects/ProjectEndpoints.cs backend/src/Tmap.Api/Features/NoteGroups/NoteGroupsEndpoints.cs backend/src/Tmap.Api/Features/Notes/NotesEndpoints.cs backend/tests/Tmap.Api.Tests/TasksTests.cs backend/tests/Tmap.Api.Tests/SubtasksTests.cs backend/tests/Tmap.Api.Tests/ProjectsTests.cs backend/tests/Tmap.Api.Tests/NoteGroupsTests.cs backend/tests/Tmap.Api.Tests/NotesTests.cs
git commit -m "$(cat <<'EOF'
feat(sync): R0-5 — idempotent PK-conflict creates (C7.1)

CreateConflict helper: pre-insert lookup by client id with SoftDelete filter off
(tombstone counts), Tenant filter on. tasks/subtasks/projects/note-groups/notes
creates now return 200 + existing DTO on replay instead of duplicating or 500'ing.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task R0-6: projects unique-name conflict → 409 ProblemDetails with `extensions.existingId` (create + rename)

**Files:**
- Modify: `backend/src/Tmap.Api/Features/Projects/ProjectEndpoints.cs`
- Test (modify): `backend/tests/Tmap.Api.Tests/ProjectsTests.cs`

Per C7.2: a project create or rename that would collide on the partial unique index `projects (user_id, name) WHERE deleted_at IS NULL` returns `409` ProblemDetails carrying `extensions.existingId` (the conflicting live project's id). Pre-check by `(UserId, Name)` before `SaveChanges`. This enables the client's adopt-existing policy (§3.3).

- [ ] **Write failing test** — add to `ProjectsTests.cs`:

```csharp
    [Fact]
    public async Task Create_DuplicateName_Returns409_WithExistingId()
    {
        var user = await RegisterAsync();
        var first = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Dupe", "#111111", "📁", "a0")))
            .Content.ReadFromJsonAsync<ProjectResponse>();

        // Different client id, same name → 409 carrying the live project's id.
        var resp = await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Dupe", "#222222", "📁", "a1"));
        resp.StatusCode.Should().Be(HttpStatusCode.Conflict);

        using var doc = System.Text.Json.JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        doc.RootElement.GetProperty("existingId").GetGuid().Should().Be(first!.Id);
    }

    [Fact]
    public async Task Rename_ToExistingName_Returns409_WithExistingId()
    {
        var user = await RegisterAsync();
        var a = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Alpha", "#111111", "📁", "a0")))
            .Content.ReadFromJsonAsync<ProjectResponse>();
        var b = await (await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Beta", "#222222", "📁", "a1")))
            .Content.ReadFromJsonAsync<ProjectResponse>();

        // Rename B → "Alpha" collides with A.
        var resp = await user.Client.PatchAsJsonAsync($"/api/v1/projects/{b!.Id}",
            new UpdateProjectRequest(Name: "Alpha"));
        resp.StatusCode.Should().Be(HttpStatusCode.Conflict);

        using var doc = System.Text.Json.JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        doc.RootElement.GetProperty("existingId").GetGuid().Should().Be(a!.Id);
    }

    [Fact]
    public async Task Create_DuplicateName_DoesNotCollideAcrossUsers()
    {
        var a = await RegisterAsync();
        var b = await RegisterAsync();
        await a.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Shared", "#111111", "📁", "a0"));
        // B can use the same name — uniqueness is per-user.
        var bResp = await b.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Shared", "#222222", "📁", "a0"));
        bResp.StatusCode.Should().Be(HttpStatusCode.Created);
    }
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~ProjectsTests"`
  Expected: `Create_DuplicateName_Returns409_WithExistingId` and `Rename_ToExistingName_Returns409_WithExistingId` fail — the duplicate insert/update hits the DB unique index and surfaces as `500` (`Be(HttpStatusCode.Conflict)` fails). The cross-user test passes already.

- [ ] **Minimal impl** — in `ProjectEndpoints.cs`, add a `409` helper and pre-checks. First widen the `Create` return type to include `Conflict<...>`. Replace the `Create` signature line:

```csharp
    private static async Task<Results<Created<ProjectResponse>, Ok<ProjectResponse>>> Create(
```
with:
```csharp
    private static async Task<Results<Created<ProjectResponse>, Ok<ProjectResponse>, ProblemHttpResult>> Create(
```

Add the name pre-check inside `Create`, immediately AFTER the idempotent-id block and BEFORE the `rank` line. Replace:

```csharp
        // Rank is optional — when omitted, server appends after the user's current max rank.
        var rank = !string.IsNullOrEmpty(req.Rank)
            ? req.Rank
            : await NextRankAsync(db, currentUser.Id, ct);
```
with:
```csharp
        // Unique-name pre-check among the caller's LIVE projects (mirrors the partial unique index).
        var nameClashId = await db.Projects
            .Where(p => p.Name == req.Name)
            .Select(p => (Guid?)p.Id)
            .FirstOrDefaultAsync(ct);
        if (nameClashId is { } clashId)
        {
            return NameConflict(clashId);
        }

        // Rank is optional — when omitted, server appends after the user's current max rank.
        var rank = !string.IsNullOrEmpty(req.Rank)
            ? req.Rank
            : await NextRankAsync(db, currentUser.Id, ct);
```

Widen `Update`'s return type. Replace:
```csharp
    private static async Task<Results<Ok<ProjectResponse>, NotFound>> Update(
        Guid id,
        UpdateProjectRequest req,
        AppDbContext db,
        CancellationToken ct)
    {
        var project = await db.Projects.FirstOrDefaultAsync(p => p.Id == id, ct);
        if (project is null)
        {
            return TypedResults.NotFound();
        }

        if (req.Name is not null)
        {
            project.Name = req.Name;
        }
```
with:
```csharp
    private static async Task<Results<Ok<ProjectResponse>, NotFound, ProblemHttpResult>> Update(
        Guid id,
        UpdateProjectRequest req,
        AppDbContext db,
        CancellationToken ct)
    {
        var project = await db.Projects.FirstOrDefaultAsync(p => p.Id == id, ct);
        if (project is null)
        {
            return TypedResults.NotFound();
        }

        if (req.Name is not null && req.Name != project.Name)
        {
            // Rename must not collide with another LIVE project of the same user.
            var clashId = await db.Projects
                .Where(p => p.Name == req.Name && p.Id != id)
                .Select(p => (Guid?)p.Id)
                .FirstOrDefaultAsync(ct);
            if (clashId is { } existing)
            {
                return NameConflict(existing);
            }

            project.Name = req.Name;
        }
```

Add the `NameConflict` helper to the `// --- helpers ---` section (after the `NextRankAsync` helper):

```csharp
    /// <summary>RFC 9457 409 for a duplicate live project name; carries the existing row's id
    /// in extensions.existingId so the client can adopt-existing (SP3 §3.3).</summary>
    private static ProblemHttpResult NameConflict(Guid existingId) =>
        TypedResults.Problem(
            title: "A project with this name already exists.",
            statusCode: StatusCodes.Status409Conflict,
            extensions: new Dictionary<string, object?> { ["existingId"] = existingId });
```

Add the required using at the top of `ProjectEndpoints.cs`. It already has `using Microsoft.AspNetCore.Http.HttpResults;` (provides `ProblemHttpResult`) and `Microsoft.EntityFrameworkCore`. `StatusCodes` lives in `Microsoft.AspNetCore.Http`, which is in the implicit-usings/global namespace for the web SDK — no extra using needed.

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~ProjectsTests"`
  Expected: all ProjectsTests pass (including the 3 new + the R0-5 idempotent-create test).

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Features/Projects/ProjectEndpoints.cs backend/tests/Tmap.Api.Tests/ProjectsTests.cs
git commit -m "$(cat <<'EOF'
feat(sync): R0-6 — project unique-name 409 with existingId (create + rename)

Pre-checks (user_id, name) among live rows before SaveChanges on create and on
rename PATCH; returns 409 ProblemDetails carrying extensions.existingId so the
client can adopt-existing (§3.3). Per-user uniqueness preserved across users.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task R0-7: focus sessions accept client `Id` + idempotent replay

**Files:**
- Modify: `backend/src/Tmap.Api/Features/FocusSessions/FocusSessionsDtos.cs`
- Modify: `backend/src/Tmap.Api/Features/FocusSessions/FocusSessionsEndpoints.cs`
- Test (modify): `backend/tests/Tmap.Api.Tests/FocusSessionsTests.cs`

Per C7.3: `CreateFocusSessionRequest` gains `Guid? Id`; the endpoint uses it (instead of always server-generating) and applies the same idempotent-create rule. Today the comment at `FocusSessionsEndpoints.cs:28-32` explicitly refuses client ids — replace it.

- [ ] **Write failing test** — add to `FocusSessionsTests.cs`:

```csharp
    [Fact]
    public async Task Add_SameClientId_Twice_Returns200_OneRow()
    {
        var user = await RegisterAsync();
        var id = Guid.CreateVersion7();
        var body = new CreateFocusSessionRequest(
            Id: id,
            TaskId: null,
            Project: "Deep Work",
            StartedAt: DateTimeOffset.Parse("2026-06-01T09:00:00Z"),
            EndedAt: DateTimeOffset.Parse("2026-06-01T09:25:00Z"),
            Minutes: 25,
            Date: new DateOnly(2026, 6, 1));

        var first = await user.Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        first.StatusCode.Should().Be(HttpStatusCode.Created);
        var firstDto = await first.Content.ReadFromJsonAsync<FocusSessionResponse>();
        firstDto!.Id.Should().Be(id);

        var second = await user.Client.PostAsJsonAsync("/api/v1/focus-sessions", body);
        second.StatusCode.Should().Be(HttpStatusCode.OK, "replaying a focus-session create is idempotent");
        var secondDto = await second.Content.ReadFromJsonAsync<FocusSessionResponse>();
        secondDto!.Id.Should().Be(id);

        await using var db = NewElevatedDbContext();
        var count = await db.Set<FocusSession>().CountAsync(f => f.Id == id);
        count.Should().Be(1, "no duplicate session");
    }
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~FocusSessionsTests"`
  Expected: compile error first — `CreateFocusSessionRequest` has no `Id` parameter — `error CS1739: The best overload for 'CreateFocusSessionRequest' does not have a parameter named 'Id'`.

- [ ] **Minimal impl (DTO)** — in `FocusSessionsDtos.cs`, replace:

```csharp
public sealed record CreateFocusSessionRequest(
    Guid? TaskId,
    string Project,
    DateTimeOffset StartedAt,
    DateTimeOffset EndedAt,
    int Minutes,
    DateOnly Date);
```
with:
```csharp
public sealed record CreateFocusSessionRequest(
    Guid? TaskId,
    string Project,
    DateTimeOffset StartedAt,
    DateTimeOffset EndedAt,
    int Minutes,
    DateOnly Date,
    Guid? Id = null);
```

- [ ] **Minimal impl (endpoint)** — in `FocusSessionsEndpoints.cs`, replace:

```csharp
    private static async Task<Created<FocusSessionResponse>> Add(
        CreateFocusSessionRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // FocusSessions are append-only telemetry; the client never needs to replay a specific Id,
        // so Id is always server-generated and client-supplied Id is intentionally not supported.
        var entity = new FocusSession
        {
            Id = Guid.CreateVersion7(),
            UserId = currentUser.Id, // interceptor also stamps; explicit for clarity
```
with:
```csharp
    private static async Task<Results<Created<FocusSessionResponse>, Ok<FocusSessionResponse>>> Add(
        CreateFocusSessionRequest req,
        AppDbContext db,
        ICurrentUser currentUser,
        CancellationToken ct)
    {
        // Idempotent replay: a focus session with this client id already exists → 200 + its DTO.
        // (Without this, the offline op queue replaying a create would silently duplicate the session.)
        if (req.Id is { } reqId && reqId != Guid.Empty)
        {
            var existing = await CreateConflict.FindExistingByIdAsync(
                db.FocusSessions, f => f.Id == reqId, ct);
            if (existing is not null)
            {
                return TypedResults.Ok(ToResponse(existing));
            }
        }

        var entity = new FocusSession
        {
            Id = req.Id is { } id && id != Guid.Empty ? id : Guid.CreateVersion7(),
            UserId = currentUser.Id, // interceptor also stamps; explicit for clarity
```

The `Add` method must also import `Results`/`Ok` — `using Microsoft.AspNetCore.Http.HttpResults;` is already present, and `using Tmap.Api.Common;` is already present (provides `CreateConflict`). No new usings.

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~FocusSessionsTests"`
  Expected: 4 passed.

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Features/FocusSessions/FocusSessionsDtos.cs backend/src/Tmap.Api/Features/FocusSessions/FocusSessionsEndpoints.cs backend/tests/Tmap.Api.Tests/FocusSessionsTests.cs
git commit -m "$(cat <<'EOF'
feat(sync): R0-7 — focus sessions accept client Id + idempotent replay (C7.3)

CreateFocusSessionRequest gains Guid? Id; endpoint honors it and returns 200 +
existing DTO on replay (was: ignore client id, generate a new one — replays
silently duplicated). One row per client id.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task R0-8: recurrence rule accepts a client `Id` (round-trips on `POST /recurrence`)

**Files:**
- Modify: `backend/src/Tmap.Api/Features/Recurrence/RecurrenceDtos.cs`
- Modify: `backend/src/Tmap.Api/Features/Recurrence/RecurrenceEndpoints.cs`
- Test (modify): `backend/tests/Tmap.Api.Tests/RecurrenceTests.cs`

Per C7.4: `RecurrenceRuleInput` gains `Guid? Id`; `Create` uses it for the rule's id so offline-created series can be referenced by later queued ops. (The template task already honors `req.Task.Id`.)

- [ ] **Write failing test** — add to `RecurrenceTests.cs` (uses the file-scoped `RecurrenceTaskResponse` DTO already defined at the bottom of the file):

```csharp
    [Fact]
    public async Task Create_WithClientRuleId_RoundTrips()
    {
        var user = await RegisterAsync();
        var ruleId = Guid.CreateVersion7();

        var req = new CreateRecurringTaskRequest(
            new RecurringTaskInput(
                Title: "Standup", Notes: "", ProjectId: null, Labels: new(), Source: "local",
                PlannedDate: new DateOnly(2026, 6, 1), DurationMinutes: 15, Priority: null,
                ReminderMinutes: 0),
            new RecurrenceRuleInput(
                Frequency: RecurrenceFrequency.Weekly, Interval: 1, DaysOfWeek: new List<int> { 1, 3, 5 },
                EndType: RecurrenceEndType.Never, EndCount: null, EndDate: null, Id: ruleId));

        var resp = await user.Client.PostAsJsonAsync("/api/v1/recurrence", req);
        resp.StatusCode.Should().Be(HttpStatusCode.Created);
        var created = await resp.Content.ReadFromJsonAsync<RecurrenceTaskResponse[]>();
        created![0].RecurrenceRuleId.Should().Be(ruleId, "the client-supplied rule id is honored");

        // The rule is fetchable at the client-supplied id.
        var rule = await user.Client.GetFromJsonAsync<RecurrenceRuleResponse>(
            $"/api/v1/recurrence/rules/{ruleId}");
        rule!.Id.Should().Be(ruleId);
    }
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~RecurrenceTests.Create_WithClientRuleId_RoundTrips"`
  Expected: compile error — `RecurrenceRuleInput` has no `Id` parameter — `error CS1739: ... does not have a parameter named 'Id'`.

- [ ] **Minimal impl (DTO)** — in `RecurrenceDtos.cs`, replace:

```csharp
public sealed record RecurrenceRuleInput(
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate);
```
with:
```csharp
public sealed record RecurrenceRuleInput(
    RecurrenceFrequency Frequency,
    int Interval,
    List<int> DaysOfWeek,
    RecurrenceEndType EndType,
    int? EndCount,
    DateOnly? EndDate,
    Guid? Id = null);
```

- [ ] **Minimal impl (endpoint)** — in `RecurrenceEndpoints.cs` `Create`, replace:

```csharp
        var rule = new RecurrenceRule
        {
            Id = Guid.CreateVersion7(),
            UserId = userId,
```
with:
```csharp
        var rule = new RecurrenceRule
        {
            Id = req.Rule.Id is { } rid && rid != Guid.Empty ? rid : Guid.CreateVersion7(),
            UserId = userId,
```

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~RecurrenceTests"`
  Expected: all RecurrenceTests pass (including the new one).

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Features/Recurrence/RecurrenceDtos.cs backend/src/Tmap.Api/Features/Recurrence/RecurrenceEndpoints.cs backend/tests/Tmap.Api.Tests/RecurrenceTests.cs
git commit -m "$(cat <<'EOF'
feat(sync): R0-8 — recurrence rule accepts client Id (C7.4)

RecurrenceRuleInput gains Guid? Id; POST /recurrence uses it for the rule's id
so offline-created series can be referenced by later queued ops. Round-trips:
the rule is fetchable at the client-supplied id.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task R0-9: ordering unification — `(Rank COLLATE "C", Id)` on tasks, projects, notes, note-groups

**Files:**
- Modify: `backend/src/Tmap.Api/Features/Tasks/TasksEndpoints.cs`
- Modify: `backend/src/Tmap.Api/Features/Projects/ProjectEndpoints.cs`
- Modify: `backend/src/Tmap.Api/Features/Notes/NotesEndpoints.cs`
- Modify: `backend/src/Tmap.Api/Features/NoteGroups/NoteGroupsEndpoints.cs`
- Test (modify): `TasksTests.cs`, `ProjectsTests.cs`, `NotesTests.cs`, `NoteGroupsTests.cs`

Per C7.5/§5.1: every rank-ordered list read sorts by `(Rank COLLATE "C", Id)` so tied ranks render id-ordered and stable. Tasks today use default collation, no tiebreak; projects/notes/note-groups already `COLLATE "C"` but with no id tiebreak. Subtask join ordering stays `SortOrder` then `Id` (subtasks use integer `sortOrder`, not rank).

- [ ] **Write failing tests** — add to each list-read test file. These seed two rows with the SAME rank (the guaranteed-tie case) and assert id-ascending order.

In `TasksTests.cs`:
```csharp
    [Fact]
    public async Task List_TiedRanks_AreOrderedById_Stably()
    {
        var user = await RegisterAsync();
        // Two ids in known order; both rank "a0" (the tie case).
        var idLo = new Guid("00000000-0000-0000-0000-000000000001");
        var idHi = new Guid("00000000-0000-0000-0000-000000000002");
        // Insert hi first to prove order comes from id, not insertion.
        await user.Client.PostAsJsonAsync("/api/v1/tasks", new { id = idHi, title = "Hi", rank = "a0" });
        await user.Client.PostAsJsonAsync("/api/v1/tasks", new { id = idLo, title = "Lo", rank = "a0" });

        var list1 = await user.Client.GetFromJsonAsync<List<TaskResponse>>("/api/v1/tasks");
        var tied1 = list1!.Where(t => t.Id == idLo || t.Id == idHi).Select(t => t.Id).ToList();
        tied1.Should().Equal(idLo, idHi, "tied ranks order by id ascending");

        // Stable across a repeated read.
        var list2 = await user.Client.GetFromJsonAsync<List<TaskResponse>>("/api/v1/tasks");
        var tied2 = list2!.Where(t => t.Id == idLo || t.Id == idHi).Select(t => t.Id).ToList();
        tied2.Should().Equal(tied1, "ordering is stable across list reads");
    }
```

In `ProjectsTests.cs`:
```csharp
    [Fact]
    public async Task List_TiedRanks_AreOrderedById_Stably()
    {
        var user = await RegisterAsync();
        var idLo = new Guid("00000000-0000-0000-0000-000000000011");
        var idHi = new Guid("00000000-0000-0000-0000-000000000012");
        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Hi", "#111111", "📁", "a0", idHi));
        await user.Client.PostAsJsonAsync("/api/v1/projects",
            new CreateProjectRequest("Lo", "#222222", "📁", "a0", idLo));

        var list = await user.Client.GetFromJsonAsync<List<ProjectResponse>>("/api/v1/projects");
        var tied = list!.Where(p => p.Id == idLo || p.Id == idHi).Select(p => p.Id).ToList();
        tied.Should().Equal(idLo, idHi);
    }
```

In `NotesTests.cs`:
```csharp
    [Fact]
    public async Task List_TiedRanks_AreOrderedById_Stably()
    {
        var user = await RegisterAsync();
        var idLo = new Guid("00000000-0000-0000-0000-000000000021");
        var idHi = new Guid("00000000-0000-0000-0000-000000000022");
        await user.Client.PostAsJsonAsync("/api/v1/notes",
            new { id = idHi, groupId = (Guid?)null, projectId = (Guid?)null, title = "Hi", content = "", rank = "a0" });
        await user.Client.PostAsJsonAsync("/api/v1/notes",
            new { id = idLo, groupId = (Guid?)null, projectId = (Guid?)null, title = "Lo", content = "", rank = "a0" });

        var list = await user.Client.GetFromJsonAsync<List<NoteResponse>>("/api/v1/notes");
        var tied = list!.Where(n => n.Id == idLo || n.Id == idHi).Select(n => n.Id).ToList();
        tied.Should().Equal(idLo, idHi);
    }
```

In `NoteGroupsTests.cs`:
```csharp
    [Fact]
    public async Task List_TiedRanks_AreOrderedById_Stably()
    {
        var user = await RegisterAsync();
        var idLo = new Guid("00000000-0000-0000-0000-000000000031");
        var idHi = new Guid("00000000-0000-0000-0000-000000000032");
        await user.Client.PostAsJsonAsync("/api/v1/note-groups",
            new { id = idHi, name = "Hi", emoji = "📝", projectId = (Guid?)null, rank = "a0" });
        await user.Client.PostAsJsonAsync("/api/v1/note-groups",
            new { id = idLo, name = "Lo", emoji = "📝", projectId = (Guid?)null, rank = "a0" });

        var list = await user.Client.GetFromJsonAsync<List<NoteGroupResponse>>("/api/v1/note-groups");
        var tied = list!.Where(g => g.Id == idLo || g.Id == idHi).Select(g => g.Id).ToList();
        tied.Should().Equal(idLo, idHi);
    }
```

- [ ] **Run, expect FAIL** — `dotnet test --filter "FullyQualifiedName~List_TiedRanks_AreOrderedById_Stably"`
  Expected: all 4 fail with non-deterministic / wrong order (no id tiebreak; tasks additionally lack `COLLATE "C"`). Typical message: `Expected collection to be equal to {00000000-...-0001, ...-0002} ... but {...-0002, ...-0001} differs at index 0`.

- [ ] **Minimal impl (tasks)** — in `TasksEndpoints.cs` `GetAsync`, replace:

```csharp
        var rows = await query.OrderBy(t => t.Rank).ToListAsync(ct);
```
with:
```csharp
        var rows = await query
            .OrderBy(t => EF.Functions.Collate(t.Rank, "C"))
            .ThenBy(t => t.Id)
            .ToListAsync(ct);
```

- [ ] **Minimal impl (projects)** — in `ProjectEndpoints.cs` `GetAll`, replace:

```csharp
        var projects = await db.Projects
            .OrderBy(p => EF.Functions.Collate(p.Rank, "C"))
            .Select(p => ToResponse(p))
            .ToListAsync(ct);
```
with:
```csharp
        var projects = await db.Projects
            .OrderBy(p => EF.Functions.Collate(p.Rank, "C"))
            .ThenBy(p => p.Id)
            .Select(p => ToResponse(p))
            .ToListAsync(ct);
```

- [ ] **Minimal impl (notes)** — in `NotesEndpoints.cs` `GetAll`, replace:

```csharp
        var notes = await query
            .OrderBy(n => EF.Functions.Collate(n.Rank, "C"))
            .ToListAsync(ct);
```
with:
```csharp
        var notes = await query
            .OrderBy(n => EF.Functions.Collate(n.Rank, "C"))
            .ThenBy(n => n.Id)
            .ToListAsync(ct);
```

- [ ] **Minimal impl (note-groups)** — in `NoteGroupsEndpoints.cs` `GetAll`, replace:

```csharp
        var groups = await query
            .OrderBy(g => EF.Functions.Collate(g.Rank, "C"))
            .ToListAsync(ct);
```
with:
```csharp
        var groups = await query
            .OrderBy(g => EF.Functions.Collate(g.Rank, "C"))
            .ThenBy(g => g.Id)
            .ToListAsync(ct);
```

- [ ] **Run, expect PASS** — `dotnet test --filter "FullyQualifiedName~List_TiedRanks_AreOrderedById_Stably"`
  Expected: 4 passed.

- [ ] **Commit**:

```bash
git add backend/src/Tmap.Api/Features/Tasks/TasksEndpoints.cs backend/src/Tmap.Api/Features/Projects/ProjectEndpoints.cs backend/src/Tmap.Api/Features/Notes/NotesEndpoints.cs backend/src/Tmap.Api/Features/NoteGroups/NoteGroupsEndpoints.cs backend/tests/Tmap.Api.Tests/TasksTests.cs backend/tests/Tmap.Api.Tests/ProjectsTests.cs backend/tests/Tmap.Api.Tests/NotesTests.cs backend/tests/Tmap.Api.Tests/NoteGroupsTests.cs
git commit -m "$(cat <<'EOF'
feat(sync): R0-9 — unify list ordering to (Rank COLLATE "C", Id) (C7.5)

tasks/projects/notes/note-groups list reads now tiebreak tied ranks by Id under
the C collation, so the guaranteed-tie case (all recurrence instances rank 'a0',
deterministic offline appends) renders the same order on every device.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task R0-10: regenerate `@tmap/api-client` from the new OpenAPI document

**Files:**
- Modify: `packages/api-client/src/schema.d.ts` (regenerated — NEVER hand-edited)

Mechanical regen. `npm run gen:api-client` runs `emit:openapi` (which `dotnet build`s the API with `OpenApiGenerateDocumentsOnBuild=true`), `copy:openapi`, then `gen:types`. After R0-2..R0-9 the document now has the `/api/v1/sync` path, the `*SyncRow`/`SyncResponse` schemas, the new `Id` fields on focus-session + recurrence-rule create DTOs, and the new `Ok`/`Conflict` responses. Uses Do/Verify/Commit (no TDD — generated output).

- [ ] **Do** — from the repo root: `npm run gen:api-client`
  This first builds the API (so the OpenAPI JSON reflects R0's endpoints), copies it to `packages/api-client/openapi.json`, then emits `packages/api-client/src/schema.d.ts`. Expected console tail: `Copied ...Tmap.Api.json -> ...openapi.json` then `openapi-typescript` writing `./src/schema.d.ts`.

- [ ] **Verify (schema content)** — confirm the regen produced the new surface. From the repo root:
  - `Grep` `"/api/v1/sync"` in `packages/api-client/src/schema.d.ts` → expect a `paths` entry with a `get` operation that has `query: { since?: number; limit?: number }`.
  - `Grep` `TaskSyncRow` in the same file → expect a `components["schemas"]["TaskSyncRow"]` definition (and likewise `SyncResponse`, `SyncChanges`, `ProjectSyncRow`, `SettingSyncRow`, `DailyPlanSyncRow`).
  - `Grep` `CreateFocusSessionRequest` → its schema now includes an optional `id` property.

- [ ] **Verify (client typecheck green)** — from the repo root:
  `npx tsc -b packages/app`
  Expected: exit 0, no errors (the regenerated schema is structurally compatible; no client code consumes `/sync` yet — that lands in R3/R4).

- [ ] **Commit**:

```bash
git add packages/api-client/src/schema.d.ts packages/api-client/openapi.json packages/api-client/Tmap.Api.json
git commit -m "$(cat <<'EOF'
chore(api-client): R0-10 — regenerate schema from SP3 R0 OpenAPI

npm run gen:api-client (builds the API first). schema.d.ts now has the
/api/v1/sync path + TaskSyncRow/SyncResponse/SyncChanges/*SyncRow schemas and the
new client-Id fields on focus-session + recurrence-rule create DTOs. Generated
output only — never hand-edited. tsc -b packages/app green.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

> If `emit:openapi` fails because the Docker daemon is needed (it is not — emit only `dotnet build`s; tests need Docker, the OpenAPI emit does not), the failure is a plain build error — fix the build, do not retry blindly. If `git add packages/api-client/Tmap.Api.json` errors because the emitted file landed at `Tmap.Api_v1.json` instead, add that path instead (the copy script handles either name).

---

### Task R0-11: GREEN GATE — full backend suite, full client suite, both builds

**Files:** none (verification only).

- [ ] **Do (backend)** — from `backend/`: `dotnet test`
  Expected: **157 + N passed, 0 failed**, where N = the new tests added in this phase:
  - PersistenceTests `ChangeSeqIndexMigrationTests`: +10 (Theory cases)
  - SyncTests: +7 (1 anon + 1 empty + 4 R0-3 + 1 R0-4)
  - idempotent-create (R0-5): +5 (tasks, subtasks, projects, note-groups, notes)
  - project 409 (R0-6): +3
  - focus client-id (R0-7): +1
  - recurrence client-id (R0-8): +1
  - tied-rank ordering (R0-9): +4
  - **N = 31 → expected total 188 passed.**
  HARD GATE: if the count is not exactly **188 passed, 0 failed**, STOP and reconcile (a missing test or an unexpected regression) before proceeding. Do not weaken any assertion to make the count fit.

- [ ] **Do (client suite)** — from the repo root: `npm run test:app`
  Expected: **113 passed, 0 failed** (R0 touches no client source; this proves the regenerated schema did not break any existing client test). HARD GATE: exactly 113 passed.

- [ ] **Do (builds)** — from the repo root: `npm run build:apps`
  Expected: `build:desktop` then `build:web` both exit 0 (TypeScript compile + Vite bundle + electron-builder for desktop; Vite for web). HARD GATE: both builds green.

- [ ] **Do (client typecheck, belt-and-suspenders)** — from the repo root: `npx tsc -b packages/app`
  Expected: exit 0.

- [ ] **Commit (gate marker — only if any uncommitted formatting/lock changes remain; otherwise skip)**:

```bash
git add -A
git commit -m "$(cat <<'EOF'
chore(sync): R0-11 — green gate (backend 188, client 113, both builds)

Full dotnet test (157 existing + 31 new = 188), npm run test:app (113), and
npm run build:apps all green. Phase R0 (backend sync slice + idempotency/409 +
client-id touch-ups + ordering unification + client regen) complete.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

> HARD GATE for the phase: all three suites/builds above must be green at the stated counts before R1 begins. The backend total is stated as "157 + 31 = 188"; if the baseline "157" differs in the executor's checkout, recompute the baseline with `dotnet test` BEFORE R0-1 and carry "baseline + 31" forward — never adjust by deleting or skipping tests.

## Phase R1 — LocalStore foundation (Dexie per-user local database)

This phase lays the storage foundation every later SP3 phase builds on: the `dexie` + `fake-indexeddb` dependencies in `@tmap/app`, the C2 sync-op type contract, the C6 sync-row wire-shape re-exports, and the C3 `LocalStore` Dexie subclass (per-user DB `tmap-{userId}`, pinned version-1 schema, meta helpers, wipe, and the `localStorage['tmap:lastUserId']` bootstrap pointer). No existing file changes behavior; everything here is additive and the existing 14-file / 113-test client suite (count at SP3 plan time, before R0 deltas) stays untouched. Files:

**Created:**
- `packages/app/src/sync/types.ts` — `SyncOp` / `OpKind` / `SyncIssue` / `SyncStatus` (contract C2) + `SyncTable` union + `entityKey(table, key)` builder
- `packages/app/src/sync/__tests__/types.test.ts` — entityKey composition + C2 shape tests
- `packages/app/src/data/local/rows.ts` — type-only re-exports of the regenerated `@tmap/api-client` sync row schemas (contract C6 names)
- `packages/app/src/data/local/LocalStore.ts` — contract C3: Dexie subclass, version(1) stores, `getMeta`/`setMeta`, `static open`/`static wipe`, `getLastUserId`/`setLastUserId`
- `packages/app/src/data/local/__tests__/LocalStore.test.ts` — DB creation/isolation, meta roundtrip, pinned indexes, ops multiEntry queries, wipe, lastUserId pointer

**Modified:**
- `packages/app/package.json` — add `dexie` (dependency), `fake-indexeddb` (devDependency)
- `package-lock.json` (repo root — npm workspaces lockfile)

**Preconditions:** R0 is merged on this branch — `packages/api-client/src/schema.d.ts` has been regenerated and contains the nine `*SyncRow` schemas plus `SyncResponse`/`SyncChanges` (verified explicitly in Task R1-3).

---

### Task R1-1: Install `dexie` + `fake-indexeddb` in workspace `@tmap/app`

**Files:**
- Modify: `packages/app/package.json`
- Modify: `package-lock.json` (root)

Pure-mechanical (no TDD): dependency installation.

- [ ] **Do** — from the repo root (`C:/Users/aboab/Desktop/Projects/sunsama clone`), run:

```bash
npm install dexie@^4.4.3 --workspace @tmap/app
npm install --save-dev fake-indexeddb@^6.2.5 --workspace @tmap/app
```

- [ ] **Verify** — from the repo root:

```bash
npm ls dexie fake-indexeddb --workspace @tmap/app
```

Expected: a tree showing `dexie@4.x` and `fake-indexeddb@6.x` resolved under `@tmap/app` with no `missing` / `invalid` markers. Also open `packages/app/package.json` and confirm `"dexie": "^4.4.3"` is in `dependencies` (it is a runtime dep — the local store ships in the apps) and `"fake-indexeddb": "^6.2.5"` is in `devDependencies` (test-only).

- [ ] **Verify suite still green** — from the repo root:

```bash
npm run test:app
```

Expected: same counts as at R1 entry (14 files / 113 tests at plan time, plus any R0 delta), all passing.

- [ ] **Commit**

```bash
git add packages/app/package.json package-lock.json
git commit -m "chore(app): add dexie + fake-indexeddb for the SP3 local store

dexie is a runtime dependency (the per-user IndexedDB store ships in both
apps); fake-indexeddb is dev-only for vitest. Spec §10 new dependencies.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R1-2: `src/sync/types.ts` — C2 op/issue/status types + `entityKey` builder

**Files:**
- Create: `packages/app/src/sync/types.ts`
- Test: `packages/app/src/sync/__tests__/types.test.ts`

The op queue's record shapes are pinned by contract C2 — every later phase (`LocalDataClient` enqueue in R2, `SyncEngine` replay in R3, shadow rule in R4, status pill in R5) imports from this module. The only runtime export is `entityKey(table, key)`, the canonical `'<table>:<key>'` composer used by `SyncOp.entityKeys`, the `ops` multiEntry index queries, and the §4.2 shadow rule. No Dexie involvement — plain node test, no `fake-indexeddb` import needed.

- [ ] **Write failing test** — create `packages/app/src/sync/__tests__/types.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import {
  entityKey,
  type SyncOp,
  type SyncIssue,
  type SyncStatus,
  type SyncTable,
} from '../types';

describe('entityKey', () => {
  it('composes table:key for uuid entities', () => {
    const table: SyncTable = 'tasks';
    expect(entityKey(table, '3f2a0b1c-7d4e-4a89-9c01-5e6f7a8b9c0d')).toBe(
      'tasks:3f2a0b1c-7d4e-4a89-9c01-5e6f7a8b9c0d',
    );
  });

  it('composes date keys for dailyPlans', () => {
    expect(entityKey('dailyPlans', '2026-06-11')).toBe('dailyPlans:2026-06-11');
  });

  it('composes setting keys for settings', () => {
    expect(entityKey('settings', 'workStartHour')).toBe('settings:workStartHour');
  });

  it('feeds a SyncOp entityKeys array (C2 shape: seq absent before insert)', () => {
    const op: SyncOp = {
      method: 'DELETE',
      path: '/api/v1/tasks/abc',
      entityKeys: [entityKey('tasks', 'abc'), entityKey('subtasks', 'def')],
      kind: 'other',
      attempts: 0,
    };
    expect(op.entityKeys).toEqual(['tasks:abc', 'subtasks:def']);
    expect(op.seq).toBeUndefined();
    expect(op.body).toBeUndefined();
  });

  it('SyncIssue and SyncStatus accept C2-conformant literals', () => {
    const issue: SyncIssue = {
      at: '2026-06-11T12:00:00.000Z',
      op: {
        method: 'POST',
        path: '/api/v1/projects',
        body: { name: 'X' },
        entityKeys: [entityKey('projects', 'p1')],
        kind: 'create',
        attempts: 10,
        lastError: 'HTTP 500',
      },
      reason: '409 Conflict — duplicate project name',
      status: 'parked',
    };
    const status: SyncStatus = {
      online: true,
      syncing: false,
      pendingOps: 0,
      lastSyncedAt: null,
      issues: [issue],
      initialSyncComplete: false,
    };
    expect(status.issues[0].status).toBe('parked');
    expect(status.issues[0].op.kind).toBe('create');
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/types.test.ts
```

Expected failure: module-resolution error — `Error: Failed to resolve import "../types" from "src/sync/__tests__/types.test.ts". Does the file exist?` (the test file is the only thing in `src/sync/` so far).

- [ ] **Minimal impl** — create `packages/app/src/sync/types.ts`:

```ts
/**
 * Sync op-queue types — cross-phase contract C2 of the SP3 plan.
 * The queue stores wire-shaped requests resolved at enqueue time (spec §3.1);
 * replay (R3) is a dumb FIFO loop over these records.
 */

export type OpKind = 'create' | 'other';

export interface SyncOp {
  /** Dexie `++seq` primary key — absent before insert. */
  seq?: number;
  method: 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  /** Resolved absolute API path, e.g. '/api/v1/tasks/3f2a…'. */
  path: string;
  /** Wire-shaped JSON body (request DTO), or undefined for body-less ops. */
  body?: unknown;
  /** '<table>:<key>' — 'tasks:<uuid>', 'dailyPlans:2026-06-11', 'settings:workStartHour'. */
  entityKeys: string[];
  /** 'create' enables adopt-existing + ghost-row recovery (spec §3.3). */
  kind: OpKind;
  /** Set on recurrence create + updateRule ops (GeneratedUntil reset → spec §4.3 trigger). */
  regenAfterPush?: true;
  /** Total attempts across cycles (parked at PARK_THRESHOLD, spec §3.3). */
  attempts: number;
  lastError?: string;
}

export interface SyncIssue {
  /** Dexie `++id` primary key — absent before insert. */
  id?: number;
  /** ISO timestamp of when the op was dropped/parked. */
  at: string;
  /** Snapshot of the dropped/parked op. */
  op: SyncOp;
  /** Human-readable reason (HTTP status + ProblemDetails title when present). */
  reason: string;
  /** Parked ops can be retried or discarded from the popover (spec §8). */
  status: 'dropped' | 'parked';
}

export interface SyncStatus {
  online: boolean;
  syncing: boolean;
  pendingOps: number;
  lastSyncedAt: string | null;
  issues: SyncIssue[];
  initialSyncComplete: boolean;
}

/** The nine synced Dexie tables (spec §2) — the table half of an entity key. */
export type SyncTable =
  | 'tasks'
  | 'subtasks'
  | 'projects'
  | 'noteGroups'
  | 'notes'
  | 'recurrenceRules'
  | 'focusSessions'
  | 'dailyPlans'
  | 'settings';

/**
 * Canonical entity key used in SyncOp.entityKeys, the ops multiEntry index,
 * and the §4.2 pull shadow rule. Key is each table's §2 primary key:
 * uuid for uuid entities, the date string for dailyPlans, the setting key for settings.
 */
export function entityKey(table: SyncTable, key: string): string {
  return `${table}:${key}`;
}
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/types.test.ts
```

Expected: 1 file passed, 5 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/sync/types.ts packages/app/src/sync/__tests__/types.test.ts
git commit -m "feat(sync): SyncOp/SyncIssue/SyncStatus types + entityKey builder (C2)

Pins the op-queue record shapes every later SP3 phase imports, and the
canonical '<table>:<key>' composer used by entityKeys, the ops multiEntry
index, and the pull shadow rule. Spec §3.1.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R1-3: `src/data/local/rows.ts` — re-export the regenerated sync row wire shapes

**Files:**
- Create: `packages/app/src/data/local/rows.ts`

Local rows are stored in **server sync-DTO wire shape** (spec §2), so the row types are type-only aliases over the R0-regenerated `@tmap/api-client` schema — the same `components['schemas'][…]` pattern `packages/app/src/data/mappers.ts:22-33` already uses for the `*Response` types. Pure-mechanical (type-only module; vitest/esbuild strips types without checking, so the honest gate is `tsc --noEmit`, which resolves the indexed-access keys against `schema.d.ts` and fails on any wrong name).

- [ ] **Do — verify the R0 regen precondition** — from the repo root (PowerShell):

```powershell
Select-String -Path packages/api-client/src/schema.d.ts -Pattern 'TaskSyncRow:|SubtaskSyncRow:|ProjectSyncRow:|NoteGroupSyncRow:|NoteSyncRow:|RecurrenceRuleSyncRow:|FocusSessionSyncRow:|DailyPlanSyncRow:|SettingSyncRow:|SyncResponse:|SyncChanges:' | Select-Object -ExpandProperty Line
```

Expected: eleven distinct schema declarations (`TaskSyncRow:`, `SubtaskSyncRow:`, `ProjectSyncRow:`, `NoteGroupSyncRow:`, `NoteSyncRow:`, `RecurrenceRuleSyncRow:`, `FocusSessionSyncRow:`, `DailyPlanSyncRow:`, `SettingSyncRow:`, `SyncResponse:`, `SyncChanges:`). If any name is missing, STOP — R0's `npm run gen:api-client` step did not complete or its DTO names diverged from contract C6; fix R0 first (the contract wins — do not invent alternative names here).

- [ ] **Do — create** `packages/app/src/data/local/rows.ts`:

```ts
/**
 * rows.ts — the wire shapes local rows are stored in (spec §2, contract C6).
 *
 * Type-only re-exports of the R0-regenerated sync DTO schemas. Each *SyncRow
 * mirrors its *Response minus nested children (TaskSyncRow has no subtasks
 * list) plus `changeSeq` and `deletedAt`. Locally-created rows synthesize the
 * same shape with `changeSeq: 0` and `deletedAt: null` (contract C3).
 * NEVER edit schema.d.ts by hand — regen only (`npm run gen:api-client`).
 */

import type { components } from '@tmap/api-client';

export type TaskSyncRow = components['schemas']['TaskSyncRow'];
export type SubtaskSyncRow = components['schemas']['SubtaskSyncRow'];
export type ProjectSyncRow = components['schemas']['ProjectSyncRow'];
export type NoteGroupSyncRow = components['schemas']['NoteGroupSyncRow'];
export type NoteSyncRow = components['schemas']['NoteSyncRow'];
export type RecurrenceRuleSyncRow = components['schemas']['RecurrenceRuleSyncRow'];
export type FocusSessionSyncRow = components['schemas']['FocusSessionSyncRow'];
export type DailyPlanSyncRow = components['schemas']['DailyPlanSyncRow'];
export type SettingSyncRow = components['schemas']['SettingSyncRow'];

// Pull envelope shapes (contract C6) — consumed by SyncTransport.pull / applyPull in R3/R4.
export type SyncResponse = components['schemas']['SyncResponse'];
export type SyncChanges = components['schemas']['SyncChanges'];
```

> Note: the two envelope aliases (`SyncResponse`, `SyncChanges`) are additive to the phase brief's nine row types — C5's `SyncTransport.pull` returns `Promise<SyncResponse>`, so pinning the import site here stops R3/R4 from re-deriving them differently. No contract conflicts.

- [ ] **Verify** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: `tsc --noEmit` exits 0. (This is the real gate: a wrong schema key fails with `error TS2339: Property 'TaskSyncRow' does not exist on type …` — vitest alone would not catch it.)

- [ ] **Commit**

```bash
git add packages/app/src/data/local/rows.ts
git commit -m "feat(app): re-export sync row wire shapes from @tmap/api-client (C6)

Local Dexie rows are stored in server sync-DTO shape (spec §2); these
type-only aliases over the R0-regenerated schema are the single import
site for row types in LocalStore/LocalDataClient/applyPull.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R1-4: `src/data/local/LocalStore.ts` — Dexie subclass per contract C3

**Files:**
- Create: `packages/app/src/data/local/LocalStore.ts`
- Test: `packages/app/src/data/local/__tests__/LocalStore.test.ts`

The per-user database `tmap-{userId}` with the pinned version-1 schema, typed table properties, `getMeta`/`setMeta`, `static open(userId)` / `static wipe(userId)`, and the module-level `getLastUserId`/`setLastUserId` pointer over `localStorage['tmap:lastUserId']` (spec §2, §7.2). `close()` is inherited from `Dexie` and needs no override — it satisfies the C3 class surface as-is. The lastUserId helpers take an optional injected `Storage` using the exact `resolveStorage` trick from `packages/app/src/lib/localPrefs.ts:24-31` (vitest runs in `environment: 'node'` — `packages/app/vitest.config.ts:13` — where `localStorage` is absent); this optional parameter is additive to C3's zero-arg signatures, and production callers (C8/C10) use the zero-arg form.

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/LocalStore.test.ts` (note: `fake-indexeddb/auto` MUST be the first import — it installs the `indexedDB`/`IDBKeyRange` globals Dexie needs before anything touches Dexie):

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import Dexie from 'dexie';
import { LocalStore, getLastUserId, setLastUserId } from '../LocalStore';
import { entityKey, type SyncOp } from '../../../sync/types';
import type { TaskSyncRow, SubtaskSyncRow } from '../rows';

// fake-indexeddb keeps one global registry for the whole test file, so every
// test uses a unique userId (unique DB name) for isolation.
let nextUser = 0;
function freshUserId(): string {
  return `u${Date.now()}-${nextUser++}`;
}

let openStores: LocalStore[] = [];
function open(userId: string): LocalStore {
  const s = LocalStore.open(userId);
  openStores.push(s);
  return s;
}

afterEach(() => {
  for (const s of openStores) s.close();
  openStores = [];
});

function taskRow(id: string, overrides: Partial<TaskSyncRow> = {}): TaskSyncRow {
  return {
    id,
    title: `Task ${id}`,
    notes: '',
    projectId: null,
    labels: [],
    source: 'manual',
    status: 'Planned',
    plannedDate: '2026-06-11',
    scheduledStart: null,
    scheduledEnd: null,
    durationMinutes: 30,
    actualTimeMinutes: 0,
    priority: null,
    reminderMinutes: null,
    rank: 'a0',
    dueDate: null,
    recurrenceRuleId: null,
    isRecurrenceTemplate: false,
    recurrenceDetached: false,
    recurrenceOriginalDate: null,
    completedAt: null,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...overrides,
  };
}

function subtaskRow(id: string, taskId: string): SubtaskSyncRow {
  return {
    id,
    taskId,
    title: `Sub ${id}`,
    completed: false,
    sortOrder: 0,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
  };
}

function memoryStorage(): Storage {
  let m: Record<string, string> = {};
  return {
    get length() {
      return Object.keys(m).length;
    },
    clear: () => {
      m = {};
    },
    getItem: (k: string) => (k in m ? m[k] : null),
    key: (i: number) => Object.keys(m)[i] ?? null,
    removeItem: (k: string) => {
      delete m[k];
    },
    setItem: (k: string, v: string) => {
      m[k] = v;
    },
  };
}

describe('LocalStore.open', () => {
  it('creates a per-user database named tmap-{userId}', async () => {
    const userId = freshUserId();
    const store = open(userId);
    await store.setMeta('probe', 1); // force the lazy DB open/create
    expect(await Dexie.exists(`tmap-${userId}`)).toBe(true);
  });

  it('isolates two userIds into separate databases', async () => {
    const a = open(freshUserId());
    const b = open(freshUserId());
    await a.tasks.put(taskRow('t1'));
    expect(await a.tasks.count()).toBe(1);
    expect(await b.tasks.count()).toBe(0);
  });
});

describe('meta', () => {
  it('round-trips scalars and objects; returns undefined for missing keys', async () => {
    const store = open(freshUserId());
    expect(await store.getMeta('syncCursor')).toBeUndefined();
    await store.setMeta('syncCursor', 42);
    expect(await store.getMeta<number>('syncCursor')).toBe(42);
    await store.setMeta('syncCursor', 99); // overwrite
    expect(await store.getMeta<number>('syncCursor')).toBe(99);
    const lastUser = { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' };
    await store.setMeta('lastUser', lastUser);
    expect(await store.getMeta('lastUser')).toEqual(lastUser);
    await store.setMeta('initialSyncComplete', true);
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true);
  });
});

describe('entity table indexes (pinned C3 schema)', () => {
  it('queries tasks by plannedDate/status and subtasks by taskId', async () => {
    const store = open(freshUserId());
    await store.tasks.bulkPut([
      taskRow('t1', { plannedDate: '2026-06-11', status: 'Planned' }),
      taskRow('t2', { plannedDate: '2026-06-12', status: 'Done' }),
    ]);
    await store.subtasks.put(subtaskRow('s1', 't1'));
    const onThe11th = await store.tasks.where('plannedDate').equals('2026-06-11').toArray();
    expect(onThe11th.map((t) => t.id)).toEqual(['t1']);
    const done = await store.tasks.where('status').equals('Done').toArray();
    expect(done.map((t) => t.id)).toEqual(['t2']);
    expect(await store.subtasks.where('taskId').equals('t1').count()).toBe(1);
  });
});

describe('ops queue', () => {
  it('auto-increments seq in FIFO insertion order', async () => {
    const store = open(freshUserId());
    const op = (path: string, key: string): SyncOp => ({
      method: 'PATCH',
      path,
      body: { title: 'x' },
      entityKeys: [key],
      kind: 'other',
      attempts: 0,
    });
    await store.ops.add(op('/api/v1/tasks/a', entityKey('tasks', 'a')));
    await store.ops.add(op('/api/v1/tasks/b', entityKey('tasks', 'b')));
    const all = await store.ops.orderBy('seq').toArray();
    expect(all.map((o) => o.path)).toEqual(['/api/v1/tasks/a', '/api/v1/tasks/b']);
    expect(all[0].seq!).toBeLessThan(all[1].seq!);
  });

  it('finds ops by any member of entityKeys (multiEntry index)', async () => {
    const store = open(freshUserId());
    await store.ops.add({
      method: 'DELETE',
      path: '/api/v1/tasks/x',
      entityKeys: [
        entityKey('tasks', 'x'),
        entityKey('subtasks', 's1'),
        entityKey('subtasks', 's2'),
      ],
      kind: 'other',
      attempts: 0,
    });
    await store.ops.add({
      method: 'PUT',
      path: '/api/v1/plans/2026-06-11',
      body: { date: '2026-06-11', taskIds: [] },
      entityKeys: [entityKey('dailyPlans', '2026-06-11')],
      kind: 'other',
      attempts: 0,
    });
    expect(await store.ops.where('entityKeys').equals('tasks:x').count()).toBe(1);
    expect(await store.ops.where('entityKeys').equals('subtasks:s2').count()).toBe(1);
    expect(await store.ops.where('entityKeys').equals('dailyPlans:2026-06-11').count()).toBe(1);
    expect(await store.ops.where('entityKeys').equals('tasks:absent').count()).toBe(0);
  });
});

describe('LocalStore.wipe', () => {
  it('deletes the per-user database; a reopen starts empty', async () => {
    const userId = freshUserId();
    const store = open(userId);
    await store.tasks.put(taskRow('t1'));
    store.close();
    await LocalStore.wipe(userId);
    expect(await Dexie.exists(`tmap-${userId}`)).toBe(false);
    const again = open(userId);
    expect(await again.tasks.count()).toBe(0);
  });
});

describe('lastUserId pointer', () => {
  it('round-trips through storage and clears on null', () => {
    const s = memoryStorage();
    expect(getLastUserId(s)).toBeNull();
    setLastUserId('user-1', s);
    expect(getLastUserId(s)).toBe('user-1');
    expect(s.getItem('tmap:lastUserId')).toBe('user-1');
    setLastUserId(null, s);
    expect(getLastUserId(s)).toBeNull();
    expect(s.getItem('tmap:lastUserId')).toBeNull();
  });

  it('never throws when storage is unavailable or throws', () => {
    const blocked: Storage = {
      ...memoryStorage(),
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    };
    expect(() => setLastUserId('u', blocked)).not.toThrow();
    expect(getLastUserId(blocked)).toBeNull();
    expect(() => setLastUserId(null, blocked)).not.toThrow();
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/LocalStore.test.ts
```

Expected failure: module-resolution error — `Error: Failed to resolve import "../LocalStore" from "src/data/local/__tests__/LocalStore.test.ts". Does the file exist?`

- [ ] **Minimal impl** — create `packages/app/src/data/local/LocalStore.ts`:

```ts
/**
 * LocalStore — per-user Dexie (IndexedDB) database `tmap-{userId}`.
 * Cross-phase contract C3 of the SP3 plan; spec §2.
 *
 * Tables hold LIVE rows only, in server sync-DTO wire shape (rows.ts):
 * applying a pulled tombstone deletes the local row; a local delete removes
 * the row immediately (the op queue carries the intent). Locally-created
 * rows synthesize the wire shape with `changeSeq: 0`, `deletedAt: null`.
 * `close()` is inherited from Dexie.
 */

import Dexie, { type Table } from 'dexie';
import type { SyncIssue, SyncOp } from '../../sync/types';
import type {
  DailyPlanSyncRow,
  FocusSessionSyncRow,
  NoteGroupSyncRow,
  NoteSyncRow,
  ProjectSyncRow,
  RecurrenceRuleSyncRow,
  SettingSyncRow,
  SubtaskSyncRow,
  TaskSyncRow,
} from './rows';

/** meta rows: syncCursor · initialSyncComplete · lastSyncedAt · lastUser {id,email,timeZoneId}. */
export interface MetaRow {
  key: string;
  value: unknown;
}

export class LocalStore extends Dexie {
  tasks!: Table<TaskSyncRow, string>;
  subtasks!: Table<SubtaskSyncRow, string>;
  projects!: Table<ProjectSyncRow, string>;
  noteGroups!: Table<NoteGroupSyncRow, string>;
  notes!: Table<NoteSyncRow, string>;
  recurrenceRules!: Table<RecurrenceRuleSyncRow, string>;
  focusSessions!: Table<FocusSessionSyncRow, string>;
  dailyPlans!: Table<DailyPlanSyncRow, string>;
  settings!: Table<SettingSyncRow, string>;
  ops!: Table<SyncOp, number>;
  issues!: Table<SyncIssue, number>;
  meta!: Table<MetaRow, string>;

  private constructor(userId: string) {
    super(`tmap-${userId}`);
    this.version(1).stores({
      tasks: 'id, plannedDate, status, recurrenceRuleId',
      subtasks: 'id, taskId',
      projects: 'id',
      noteGroups: 'id',
      notes: 'id, groupId, projectId',
      recurrenceRules: 'id',
      focusSessions: 'id, date',
      dailyPlans: 'date',
      settings: 'key',
      ops: '++seq, *entityKeys',
      issues: '++id',
      meta: 'key',
    });
  }

  static open(userId: string): LocalStore {
    return new LocalStore(userId);
  }

  /** Deletes the per-user database entirely (explicit logout, spec §7.2). */
  static async wipe(userId: string): Promise<void> {
    await Dexie.delete(`tmap-${userId}`);
  }

  async getMeta<T>(key: string): Promise<T | undefined> {
    const row = await this.meta.get(key);
    return row === undefined ? undefined : (row.value as T);
  }

  async setMeta(key: string, value: unknown): Promise<void> {
    await this.meta.put({ key, value });
  }
}

// ---------------------------------------------------------------------------
// Global pointer: which per-user DB to open at offline bootstrap (spec §2, §7.2).
// Lives OUTSIDE the per-user DB by design — it breaks the chicken-and-egg of
// needing a userId before any network identity is available.
// ---------------------------------------------------------------------------

const LAST_USER_KEY = 'tmap:lastUserId';

/** Same storage-resolution trick as lib/localPrefs.ts — node tests inject a Storage. */
function resolveStorage(explicit?: Storage): Storage | null {
  if (explicit) return explicit;
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch {
    return null;
  }
}

/** Last successfully-authed user id, or null. Never throws. */
export function getLastUserId(storage?: Storage): string | null {
  const s = resolveStorage(storage);
  if (!s) return null;
  try {
    return s.getItem(LAST_USER_KEY);
  } catch {
    return null;
  }
}

/**
 * Written on every successful auth; cleared (pass null) ONLY on explicit
 * logout — session expiry keeps the pointer so re-login reuses the DB (C8.3).
 * Never throws.
 */
export function setLastUserId(id: string | null, storage?: Storage): void {
  const s = resolveStorage(storage);
  if (!s) return;
  try {
    if (id === null) s.removeItem(LAST_USER_KEY);
    else s.setItem(LAST_USER_KEY, id);
  } catch {
    // storage full / blocked → ignore
  }
}
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/LocalStore.test.ts
```

Expected: 1 file passed, 9 tests passed.

- [ ] **Run typecheck** — from the repo root (the new module is inside the `tsc --noEmit` include set; tests are excluded by `packages/app/tsconfig.json:25`):

```bash
npm run typecheck --workspace @tmap/app
```

Expected: exits 0.

- [ ] **Commit**

```bash
git add packages/app/src/data/local/LocalStore.ts packages/app/src/data/local/__tests__/LocalStore.test.ts
git commit -m "feat(app): LocalStore Dexie database per contract C3

Per-user DB tmap-{userId} with the pinned version-1 schema (12 tables incl.
ops '++seq, *entityKeys' and issues '++id'), typed Table properties over the
sync row wire shapes, getMeta/setMeta, static open/wipe, and the
localStorage['tmap:lastUserId'] offline-bootstrap pointer with the
localPrefs resolveStorage trick for node tests. Spec §2, §7.2.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R1-5: Phase R1 green gate

**Files:** none (verification only — no commit unless a fix is needed, in which case fix forward with its own TDD loop before passing the gate).

- [ ] **Run the full client suite** — from the repo root:

```bash
npm run test:app
```

Expected: **16 test files / 127 tests, all passing** — the 14 files / 113 tests present at SP3 plan time, plus `src/sync/__tests__/types.test.ts` (5 tests) and `src/data/local/__tests__/LocalStore.test.ts` (9 tests). If R0 added or changed client tests, the totals shift by exactly R0's delta — the gate condition is **zero failures, zero skips**, with both new R1 files present and green.

- [ ] **Run the app typecheck** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: exits 0 (proves `rows.ts` resolves every C6 schema name and `LocalStore.ts` composes under strict mode).

- [ ] **Run both app builds** — from the repo root:

```bash
npm run build:apps
```

Expected: desktop (`tmap`) and web (`@tmap/web`) builds both succeed — dexie is a runtime dependency now and must bundle cleanly into both shells.

- [ ] **Gate** — backend tests are not run here: R1 touches no backend file (R0 already gated the backend slice).

**HARD GATE: do not start Phase R2 until all three commands above are green. If any fails, fix forward within R1 (test-first for behavior fixes), re-run the full gate from the top, and only then proceed.**

## Phase R2 — LocalDataClient (DataClient over Dexie)

This phase implements the `DataClient` seam against the R1 `LocalStore`: every read sorts rank-bearing collections by `(rank, id)` ordinal compare; every mutation runs in one Dexie rw-transaction over the entity tables + `ops`, resolving wire-shaped request bodies (domain→DTO via `mappers.ts`, ranks via `ranking.ts`, client UUIDs via the `newId()` fallback copied from `HttpDataClient`) and enqueuing a `SyncOp` with `bridge.nudge()`. Updates use **diff-at-enqueue** (spec §3.1) — tasks send only changed keys (true PATCH presence, explicit-null clears preserved); projects/notes/noteGroups send changed keys with real values plus the rest as `null` (server null=leave-unchanged); deletes apply the same local cascades the server applies (task→subtasks tombstone, noteGroup→notes tombstone, project→null-out `projectId` on children). Reorders port `HttpDataClient.computeReorder` against local rows with tie-aware re-ranking of equal-rank runs (spec §5.1). Recurrence ops insert/patch/delete the local approximation and queue the wire op (with `regenAfterPush` on series-create + updateRule per C2). `localReports.ts` builds the `ReportData` projection locally (spec §6), and `reports.getData` delegates to it. `HttpDataClient.ts` is **mirrored, never modified** — its mapper usage and request bodies are the source of truth for the queued op wire shapes.

**Created:**
- `packages/app/src/data/local/LocalDataClient.ts` — contract C4: `SyncBridge` interface + `LocalDataClient implements DataClient`
- `packages/app/src/data/local/localReports.ts` — `buildReportData(rows, start, end): ReportData` (spec §6)
- `packages/app/src/data/local/__tests__/helpers.ts` — shared seed-row factories + a fake `SyncBridge` used by every R2 test file
- `packages/app/src/data/local/__tests__/reads.test.ts` — R2-1/R2-2 read ordering + joins
- `packages/app/src/data/local/__tests__/creates.test.ts` — R2-3 creates + op enqueue + atomicity
- `packages/app/src/data/local/__tests__/updates.test.ts` — R2-4 diff-at-enqueue (incl. clear-to-null + no-op)
- `packages/app/src/data/local/__tests__/deletes.test.ts` — R2-5 local cascades
- `packages/app/src/data/local/__tests__/reorder.test.ts` — R2-6 reorder + tie repair
- `packages/app/src/data/local/__tests__/misc.test.ts` — R2-7 dailyPlans/settings/focusSessions
- `packages/app/src/data/local/__tests__/recurrence.test.ts` — R2-8 recurrence group
- `packages/app/src/data/local/__tests__/localReports.test.ts` — R2-9 report projection

**Modified:** none (the phase is purely additive; `HttpDataClient.ts`, `mappers.ts`, `ranking.ts`, `DataClient.ts`, `rows.ts`, `LocalStore.ts`, `sync/types.ts` are all read-only inputs here).

**Preconditions:** R1 is merged on this branch — `LocalStore` (C3), `sync/types.ts` (C2, with `entityKey`), and `rows.ts` (C6) exist and are green. The regenerated `@tmap/api-client` carries the `*SyncRow` schemas (verified in R1-3).

> **Contract-gap note:** C4 declares `SyncBridge.ensureInstances` but the `DataClient.recurrence.ensureInstances` read-through (spec §6) also needs to map returned `TaskSyncRow`s and upsert them locally. I keep the bridge signature exactly as C4 pins it (`ensureInstances(start, end): Promise<TaskSyncRow[]>`); the upsert + domain mapping live inside `LocalDataClient.recurrence.ensureInstances`, not in the bridge. No deviation from C4.

---

### Task R2-1: Construction + `tasks` reads (getAll / getByDate)

**Files:**
- Create: `packages/app/src/data/local/LocalDataClient.ts`
- Create: `packages/app/src/data/local/__tests__/helpers.ts`
- Test: `packages/app/src/data/local/__tests__/reads.test.ts`

`LocalDataClient` reads tasks by joining live subtask rows (sorted `(sortOrder, id)`) onto each task row, ordering tasks by `(rank, id)` ordinal compare, then mapping via `mappers.toTask`. `toTask` (mappers.ts:131) expects a `TaskResponse`-shaped object with a nested `subtasks` array, so we synthesize `{ ...taskRow, subtasks }` (the `TaskSyncRow` has no nested subtasks — spec §2). `getByDate` filters via the `plannedDate` index. The container rank-sort assigns sequential `order` (mirroring `HttpDataClient.withOrder`, HttpDataClient.ts:83-86) and `(rank, id)` resolves ties (spec §5.1).

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/helpers.ts` (shared by every R2 test file):

```ts
import 'fake-indexeddb/auto';
import { LocalStore } from '../LocalStore';
import type { SyncBridge } from '../LocalDataClient';
import type {
  TaskSyncRow,
  SubtaskSyncRow,
  ProjectSyncRow,
  NoteGroupSyncRow,
  NoteSyncRow,
  RecurrenceRuleSyncRow,
  FocusSessionSyncRow,
  DailyPlanSyncRow,
  SettingSyncRow,
} from '../rows';

let nextUser = 0;
export function freshUserId(): string {
  return `u${Date.now()}-${nextUser++}`;
}

const stores: LocalStore[] = [];
export function openStore(userId = freshUserId()): LocalStore {
  const s = LocalStore.open(userId);
  stores.push(s);
  return s;
}
export function closeAll(): void {
  for (const s of stores.splice(0)) s.close();
}

/** A fake SyncBridge that records nudges and lets tests script online/ensureInstances. */
export function fakeBridge(opts: {
  online?: boolean;
  ensureInstances?: (start: string, end: string) => Promise<TaskSyncRow[]>;
} = {}): SyncBridge & { nudges: number } {
  return {
    nudges: 0,
    nudge() {
      this.nudges++;
    },
    online() {
      return opts.online ?? false;
    },
    ensureInstances:
      opts.ensureInstances ?? (async () => []),
  };
}

export function taskRow(id: string, o: Partial<TaskSyncRow> = {}): TaskSyncRow {
  return {
    id,
    title: `Task ${id}`,
    notes: '',
    projectId: null,
    labels: [],
    source: 'manual',
    status: 'Planned',
    plannedDate: '2026-06-11',
    scheduledStart: null,
    scheduledEnd: null,
    durationMinutes: 30,
    actualTimeMinutes: 0,
    priority: null,
    reminderMinutes: null,
    rank: 'n',
    dueDate: null,
    recurrenceRuleId: null,
    isRecurrenceTemplate: false,
    recurrenceDetached: false,
    recurrenceOriginalDate: null,
    completedAt: null,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function subtaskRow(id: string, taskId: string, o: Partial<SubtaskSyncRow> = {}): SubtaskSyncRow {
  return {
    id,
    taskId,
    title: `Sub ${id}`,
    completed: false,
    sortOrder: 0,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function projectRow(id: string, o: Partial<ProjectSyncRow> = {}): ProjectSyncRow {
  return {
    id,
    name: `Project ${id}`,
    color: '#6366f1',
    emoji: '📁',
    rank: 'n',
    actualTimeMinutes: 0,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function noteGroupRow(id: string, o: Partial<NoteGroupSyncRow> = {}): NoteGroupSyncRow {
  return {
    id,
    name: `Group ${id}`,
    emoji: '🗂️',
    projectId: null,
    rank: 'n',
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function noteRow(id: string, o: Partial<NoteSyncRow> = {}): NoteSyncRow {
  return {
    id,
    groupId: null,
    projectId: null,
    title: `Note ${id}`,
    content: '',
    rank: 'n',
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function ruleRow(id: string, o: Partial<RecurrenceRuleSyncRow> = {}): RecurrenceRuleSyncRow {
  return {
    id,
    frequency: 'Daily',
    interval: 1,
    daysOfWeek: [],
    endType: 'Never',
    endCount: null,
    endDate: null,
    generatedUntil: null,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function focusRow(id: string, o: Partial<FocusSessionSyncRow> = {}): FocusSessionSyncRow {
  return {
    id,
    taskId: null,
    project: '',
    startedAt: '2026-06-11T09:00:00Z',
    endedAt: '2026-06-11T09:25:00Z',
    minutes: 25,
    date: '2026-06-11',
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function planRow(date: string, o: Partial<DailyPlanSyncRow> = {}): DailyPlanSyncRow {
  return {
    date,
    committedAt: '2026-06-11T08:00:00Z',
    plannedTaskIds: [],
    plannedMinutes: 0,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}

export function settingRow(key: string, value: string, o: Partial<SettingSyncRow> = {}): SettingSyncRow {
  return {
    key,
    value,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...o,
  };
}
```

Then create `packages/app/src/data/local/__tests__/reads.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, taskRow, subtaskRow } from './helpers';

afterEach(closeAll);

describe('LocalDataClient.tasks reads', () => {
  it('orders tasks by (rank, id) ordinal, breaking tied ranks by id', async () => {
    const store = openStore();
    // Two rows share rank 'a0' (the recurrence-instance tie, spec §5.1). 'b1' is the deterministic
    // tie-break loser by id (string compare 'b1' < 'c2'); 'm' sorts before all by rank.
    await store.tasks.bulkPut([
      taskRow('c2', { rank: 'a0' }),
      taskRow('b1', { rank: 'a0' }),
      taskRow('z9', { rank: 'm' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    const got = await dc.tasks.getAll();
    expect(got.map((t) => t.id)).toEqual(['z9', 'b1', 'c2']);
    expect(got.map((t) => t.order)).toEqual([0, 1, 2]);
  });

  it('joins live subtasks sorted by (sortOrder, id) and maps them as domain subtasks', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1'));
    await store.subtasks.bulkPut([
      subtaskRow('s2', 't1', { sortOrder: 1, title: 'second' }),
      subtaskRow('s1', 't1', { sortOrder: 0, title: 'first' }),
      subtaskRow('sx', 'other', { sortOrder: 0, title: 'foreign' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    const [t] = await dc.tasks.getAll();
    expect(t.subtasks.map((s) => s.title)).toEqual(['first', 'second']);
    expect(t.subtasks.map((s) => s.order)).toEqual([0, 1]);
  });

  it('getByDate filters via the plannedDate index', async () => {
    const store = openStore();
    await store.tasks.bulkPut([
      taskRow('t1', { plannedDate: '2026-06-11', rank: 'a' }),
      taskRow('t2', { plannedDate: '2026-06-12', rank: 'b' }),
      taskRow('t3', { plannedDate: '2026-06-11', rank: 'c' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    const got = await dc.tasks.getByDate('2026-06-11');
    expect(got.map((t) => t.id)).toEqual(['t1', 't3']);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/reads.test.ts
```

Expected failure: module-resolution error — `Error: Failed to resolve import "../LocalDataClient" from "src/data/local/__tests__/reads.test.ts". Does the file exist?` (no implementation yet).

- [ ] **Minimal impl** — create `packages/app/src/data/local/LocalDataClient.ts`:

```ts
/**
 * LocalDataClient — DataClient over the per-user Dexie LocalStore (contract C4, spec §1).
 *
 * Reads: rank-bearing collections sort by (rank, id) ordinal (spec §5.1); tasks join
 * live subtasks (sorted (sortOrder, id)) and map via mappers.toTask.
 * Writes: one Dexie rw-transaction over the entity tables + `ops`. Request bodies are
 * resolved at enqueue (domain→DTO mapping, ranks, client UUIDs) — replay (R3) is a dumb
 * FIFO loop. Updates diff against the current local row and enqueue only changed fields
 * (diff-at-enqueue, spec §3.1). Deletes apply the same cascades the server applies.
 *
 * mappers.ts / ranking.ts are reused verbatim — they are byte-compatible with the server.
 * HttpDataClient.ts is mirrored (its mapper usage + wire bodies), never imported.
 */

import type {
  Task,
  Subtask,
  Project,
  NoteGroup,
  Note,
  FocusSession,
  DailyPlan,
  ReportData,
  RecurrenceRule,
} from '../../types';
import type { DataClient, ReorderInput, RecurrenceRuleInput } from '../DataClient';
import type { LocalStore } from './LocalStore';
import type {
  TaskSyncRow,
  SubtaskSyncRow,
  ProjectSyncRow,
  NoteGroupSyncRow,
  NoteSyncRow,
  RecurrenceRuleSyncRow,
} from './rows';
import type { SyncOp } from '../../sync/types';
import { entityKey } from '../../sync/types';
import { rankAfter, rankBetween } from '../ranking';
import {
  toTask,
  toSubtask,
  toProject,
  toNoteGroup,
  toNote,
  toFocusSession,
  toDailyPlan,
  toRecurrenceRule,
  fromServerStatus,
  toServerStatus,
  toServerFrequency,
  toServerEndType,
} from '../mappers';
import { buildReportData } from './localReports';

/** crypto.randomUUID with a safe fallback — copied verbatim from HttpDataClient.ts:34-43. */
function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/** Ordinal (byte) string compare — never the locale collator. */
function ordinal(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Sort rank-bearing rows by (rank, id) — the §5.1 total order shared with the server. */
function byRankId<T extends { id: string; rank: string }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => ordinal(a.rank, b.rank) || ordinal(a.id, b.id));
}

/** Max rank string among rows, or null (for rankAfter-append on create). */
function maxRank(rows: { rank: string }[]): string | null {
  let m: string | null = null;
  for (const r of rows) if (m === null || r.rank > m) m = r.rank;
  return m;
}

/** The SyncEngine surface LocalDataClient needs at enqueue/read-through time. */
export interface SyncBridge {
  /** An op was enqueued → debounce a push. */
  nudge(): void;
  online(): boolean;
  /** Server ensure-instances call; throws when offline (caller guards with online()). */
  ensureInstances(start: string, end: string): Promise<TaskSyncRow[]>;
}

export class LocalDataClient implements DataClient {
  constructor(
    private store: LocalStore,
    private bridge: SyncBridge,
  ) {}

  // ── helpers ────────────────────────────────────────────────
  /** Map a live task row + its joined subtask rows into a domain Task via mappers.toTask. */
  private mapTask(row: TaskSyncRow, subs: SubtaskSyncRow[]): Task {
    const subtasks = byRankIdSubtasks(subs);
    // toTask expects a TaskResponse-shaped object with a nested `subtasks` array (mappers.ts:131-163);
    // TaskSyncRow has no nested subtasks (spec §2), so synthesize one.
    return toTask({ ...(row as unknown as Record<string, unknown>), subtasks } as never);
  }

  private async liveSubtasks(taskId: string): Promise<SubtaskSyncRow[]> {
    return this.store.subtasks.where('taskId').equals(taskId).toArray();
  }

  // ── tasks ──────────────────────────────────────────────────
  tasks = {
    getAll: async (): Promise<Task[]> => {
      const rows = byRankId(await this.store.tasks.toArray());
      const allSubs = await this.store.subtasks.toArray();
      const byTask = new Map<string, SubtaskSyncRow[]>();
      for (const s of allSubs) {
        const arr = byTask.get(s.taskId);
        if (arr) arr.push(s);
        else byTask.set(s.taskId, [s]);
      }
      return rows.map((r, i) => ({ ...this.mapTask(r, byTask.get(r.id) ?? []), order: i }));
    },
    getByDate: async (date: string): Promise<Task[]> => {
      const filtered = byRankId(
        await this.store.tasks.where('plannedDate').equals(date).toArray(),
      );
      return Promise.all(
        filtered.map(async (r, i) => ({
          ...this.mapTask(r, await this.liveSubtasks(r.id)),
          order: i,
        })),
      );
    },
    create: async (t: Partial<Task>): Promise<Task> => {
      throw new Error('not implemented in R2-1');
    },
    update: async (id: string, u: Partial<Task>): Promise<Task> => {
      throw new Error('not implemented in R2-1');
    },
    delete: async (id: string): Promise<void> => {
      throw new Error('not implemented in R2-1');
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      throw new Error('not implemented in R2-1');
    },
  };

  // The remaining DataClient groups are filled in by later R2 tasks.
  subtasks!: DataClient['subtasks'];
  projects!: DataClient['projects'];
  noteGroups!: DataClient['noteGroups'];
  notes!: DataClient['notes'];
  recurrence!: DataClient['recurrence'];
  focusSessions!: DataClient['focusSessions'];
  dailyPlans!: DataClient['dailyPlans'];
  reports!: DataClient['reports'];
  settings!: DataClient['settings'];
}

/** Subtasks sort by (sortOrder, id) — the server's join order (TasksEndpoints.cs:114). */
function byRankIdSubtasks(subs: SubtaskSyncRow[]): SubtaskSyncRow[] {
  return [...subs].sort(
    (a, b) => Number(a.sortOrder) - Number(b.sortOrder) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0),
  );
}
```

And create a stub `packages/app/src/data/local/localReports.ts` so the import resolves (the real builder lands in R2-9):

```ts
import type { ReportData } from '../../types';
import type {
  TaskSyncRow,
  FocusSessionSyncRow,
  DailyPlanSyncRow,
  ProjectSyncRow,
} from './rows';

export interface ReportRows {
  tasks: TaskSyncRow[];
  focusSessions: FocusSessionSyncRow[];
  dailyPlans: DailyPlanSyncRow[];
  projects: ProjectSyncRow[];
}

export function buildReportData(_rows: ReportRows, _start: string, _end: string): ReportData {
  return { completedTasks: [], sessions: [], dailyPlans: [] };
}
```

> The `not implemented` throwers and the `!`-asserted group fields are scaffolding consumed entirely within this phase — every later R2 task replaces a thrower or a `!` field with its real implementation, and the R2-10 green gate proves none survive. The unused imports (`rankAfter`, `rankBetween`, `entityKey`, `SyncOp`, the `to*`/`from*` mappers, `RecurrenceRule`, etc.) are wired up by R2-3…R2-9; vitest/esbuild strips unused type imports and tolerates unused value imports, and the phase typechecks clean only at R2-10 once every group is implemented. To keep each intermediate task's `tsc` honest, import only what each task uses — see each task's impl for the exact import list it adds.

To keep R2-1 self-contained and typecheck-clean, trim the R2-1 imports to only what reads use. Replace the import block above with exactly:

```ts
import type {
  Task,
  Subtask,
  Project,
  NoteGroup,
  Note,
  FocusSession,
  DailyPlan,
  ReportData,
  RecurrenceRule,
} from '../../types';
import type { DataClient, ReorderInput, RecurrenceRuleInput } from '../DataClient';
import type { LocalStore } from './LocalStore';
import type { TaskSyncRow, SubtaskSyncRow } from './rows';
import { toTask } from '../mappers';
```

(The later tasks add their own imports as they implement each group; `Subtask`, `Project`, `NoteGroup`, `Note`, `FocusSession`, `DailyPlan`, `ReportData`, `RecurrenceRule`, `ReorderInput`, `RecurrenceRuleInput` are referenced by the `DataClient['…']` field types and the method signatures filled in later, so they stay imported from R2-1 on.)

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/reads.test.ts
```

Expected: 1 file passed, 3 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/localReports.ts packages/app/src/data/local/__tests__/helpers.ts packages/app/src/data/local/__tests__/reads.test.ts
git commit -m "feat(app): LocalDataClient construction + tasks reads (R2-1)

getAll/getByDate read live task rows, join live subtasks sorted (sortOrder,id),
order tasks by (rank,id) ordinal (the §5.1 total order that resolves the 'a0'
recurrence-instance ties), and map via mappers.toTask by synthesizing the nested
subtasks array TaskSyncRow lacks. Shared test seed-factories + fake SyncBridge.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R2-2: projects / noteGroups / notes reads + settings.get + dailyPlans.get

**Files:**
- Modify: `packages/app/src/data/local/LocalDataClient.ts`
- Test: `packages/app/src/data/local/__tests__/reads.test.ts`

Projects/noteGroups/notes reads sort `(rank, id)` and map via the `to*` mappers (which carry `_rank`; we strip it like `HttpDataClient` does). `noteGroups.getByProject` / `notes.getByGroup` / `notes.getByProject` filter via the indexes; `notes.getById` returns one mapped note or null. `settings.get` reads the `settings` rows into a map (mirroring `mappers.parseSettings` semantics applied via the same parser) and reads `timeZoneId` from `meta.lastUser`. `dailyPlans.get` reads one plan by date and maps via `toDailyPlan`.

- [ ] **Write failing test** — append to `packages/app/src/data/local/__tests__/reads.test.ts`:

```ts
import { projectRow, noteGroupRow, noteRow, planRow, settingRow } from './helpers';

describe('LocalDataClient.projects/noteGroups/notes reads', () => {
  it('projects.getAll orders by (rank, id) and assigns sequential order', async () => {
    const store = openStore();
    await store.projects.bulkPut([
      projectRow('p-c', { rank: 'a0' }),
      projectRow('p-a', { rank: 'a0' }),
      projectRow('p-b', { rank: 'm' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    const got = await dc.projects.getAll();
    expect(got.map((p) => p.id)).toEqual(['p-b', 'p-a', 'p-c']);
    expect(got.map((p) => p.order)).toEqual([0, 1, 2]);
    expect((got[0] as Record<string, unknown>)._rank).toBeUndefined();
  });

  it('noteGroups.getAll / getByProject filter + order', async () => {
    const store = openStore();
    await store.noteGroups.bulkPut([
      noteGroupRow('g1', { projectId: 'P', rank: 'b' }),
      noteGroupRow('g2', { projectId: null, rank: 'a' }),
      noteGroupRow('g3', { projectId: 'P', rank: 'c' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    expect((await dc.noteGroups.getAll()).map((g) => g.id)).toEqual(['g2', 'g1', 'g3']);
    expect((await dc.noteGroups.getByProject('P')).map((g) => g.id)).toEqual(['g1', 'g3']);
  });

  it('notes.getAll / getByGroup / getByProject / getById', async () => {
    const store = openStore();
    await store.notes.bulkPut([
      noteRow('n1', { groupId: 'G', rank: 'b' }),
      noteRow('n2', { projectId: 'P', rank: 'a' }),
      noteRow('n3', { groupId: 'G', rank: 'c' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    expect((await dc.notes.getAll()).map((n) => n.id)).toEqual(['n2', 'n1', 'n3']);
    expect((await dc.notes.getByGroup('G')).map((n) => n.id)).toEqual(['n1', 'n3']);
    expect((await dc.notes.getByProject('P')).map((n) => n.id)).toEqual(['n2']);
    expect((await dc.notes.getById('n2'))?.id).toBe('n2');
    expect(await dc.notes.getById('absent')).toBeNull();
  });
});

describe('LocalDataClient.settings.get + dailyPlans.get', () => {
  it('settings.get parses synced numeric keys and reads timeZoneId from meta.lastUser', async () => {
    const store = openStore();
    await store.settings.bulkPut([
      settingRow('workStartHour', '8'),
      settingRow('workEndHour', '18'),
      settingRow('timeIncrement', '15'),
    ]);
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'America/New_York' });
    const dc = new LocalDataClient(store, fakeBridge());
    const { settings, timeZoneId } = await dc.settings.get();
    expect(settings).toEqual({ workStartHour: 8, workEndHour: 18, timeIncrement: 15 });
    expect(timeZoneId).toBe('America/New_York');
  });

  it('settings.get defaults timeZoneId to UTC when meta.lastUser is absent', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    expect((await dc.settings.get()).timeZoneId).toBe('UTC');
  });

  it('dailyPlans.get returns the mapped plan or null', async () => {
    const store = openStore();
    await store.dailyPlans.put(planRow('2026-06-11', { plannedMinutes: 120, plannedTaskIds: ['t1'] }));
    const dc = new LocalDataClient(store, fakeBridge());
    const plan = await dc.dailyPlans.get('2026-06-11');
    expect(plan).toEqual({
      date: '2026-06-11',
      committedAt: '2026-06-11T08:00:00Z',
      plannedTaskIds: ['t1'],
      plannedMinutes: 120,
    });
    expect(await dc.dailyPlans.get('2026-06-12')).toBeNull();
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/reads.test.ts
```

Expected failure: `TypeError: dc.projects.getAll is not a function` (the `projects`/`noteGroups`/`notes`/`settings`/`dailyPlans` groups are still the `!`-asserted placeholders from R2-1).

- [ ] **Minimal impl** — in `LocalDataClient.ts`, add the `parseSettings`/`toProject`/`toNoteGroup`/`toNote`/`toDailyPlan` imports to the existing `mappers` import line so it reads:

```ts
import {
  toTask,
  toProject,
  toNoteGroup,
  toNote,
  toDailyPlan,
  parseSettings,
} from '../mappers';
```

Then replace these placeholder fields:

```ts
  subtasks!: DataClient['subtasks'];
  projects!: DataClient['projects'];
  noteGroups!: DataClient['noteGroups'];
  notes!: DataClient['notes'];
  recurrence!: DataClient['recurrence'];
  focusSessions!: DataClient['focusSessions'];
  dailyPlans!: DataClient['dailyPlans'];
  reports!: DataClient['reports'];
  settings!: DataClient['settings'];
```

with the read implementations now and keep the still-unimplemented groups as placeholders:

```ts
  // ── projects ───────────────────────────────────────────────
  projects = {
    getAll: async (): Promise<Project[]> => {
      const rows = byRankId(await this.store.projects.toArray());
      return rows.map((r, i) => stripRank({ ...toProject(r), order: i }));
    },
    create: async (i: { name: string; color?: string; emoji?: string }): Promise<Project> => {
      throw new Error('not implemented in R2-2');
    },
    update: async (id: string, u: Partial<Project>): Promise<Project> => {
      throw new Error('not implemented in R2-2');
    },
    delete: async (id: string): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
  };

  // ── note groups ────────────────────────────────────────────
  noteGroups = {
    getAll: async (): Promise<NoteGroup[]> => {
      const rows = byRankId(await this.store.noteGroups.toArray());
      return rows.map((r, i) => stripRank({ ...toNoteGroup(r, i) }));
    },
    getByProject: async (projectId: string): Promise<NoteGroup[]> => {
      const rows = byRankId(
        await this.store.noteGroups.where('projectId').equals(projectId).toArray(),
      );
      return rows.map((r, i) => stripRank({ ...toNoteGroup(r, i) }));
    },
    create: async (i: { name: string; emoji?: string; projectId?: string }): Promise<NoteGroup> => {
      throw new Error('not implemented in R2-2');
    },
    update: async (id: string, u: Partial<NoteGroup>): Promise<NoteGroup> => {
      throw new Error('not implemented in R2-2');
    },
    delete: async (id: string): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
  };

  // ── notes ──────────────────────────────────────────────────
  notes = {
    getAll: async (): Promise<Note[]> => {
      const rows = byRankId(await this.store.notes.toArray());
      return rows.map((r, i) => stripRank({ ...toNote(r, i) }));
    },
    getByGroup: async (groupId: string): Promise<Note[]> => {
      const rows = byRankId(await this.store.notes.where('groupId').equals(groupId).toArray());
      return rows.map((r, i) => stripRank({ ...toNote(r, i) }));
    },
    getByProject: async (projectId: string): Promise<Note[]> => {
      const rows = byRankId(await this.store.notes.where('projectId').equals(projectId).toArray());
      return rows.map((r, i) => stripRank({ ...toNote(r, i) }));
    },
    getById: async (id: string): Promise<Note | null> => {
      const row = await this.store.notes.get(id);
      return row ? stripRank({ ...toNote(row) }) : null;
    },
    create: async (i: {
      groupId?: string;
      projectId?: string;
      title?: string;
      content?: string;
    }): Promise<Note> => {
      throw new Error('not implemented in R2-2');
    },
    update: async (
      id: string,
      u: Partial<{ title: string; content: string; groupId: string; projectId: string; order: number }>,
    ): Promise<Note> => {
      throw new Error('not implemented in R2-2');
    },
    delete: async (id: string): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
  };

  // ── daily plans ────────────────────────────────────────────
  dailyPlans = {
    upsert: async (p: {
      date: string;
      plannedTaskIds: string[];
      plannedMinutes: number;
    }): Promise<DailyPlan> => {
      throw new Error('not implemented in R2-2');
    },
    get: async (date: string): Promise<DailyPlan | null> => {
      const row = await this.store.dailyPlans.get(date);
      return row ? toDailyPlan(row as never) : null;
    },
  };

  // ── settings ───────────────────────────────────────────────
  settings = {
    get: async (): Promise<{ settings: Record<string, unknown>; timeZoneId: string }> => {
      const rows = await this.store.settings.toArray();
      const map: Record<string, string> = {};
      for (const r of rows) map[r.key] = r.value;
      const lastUser = await this.store.getMeta<{ timeZoneId?: string }>('lastUser');
      return { settings: parseSettings(map), timeZoneId: lastUser?.timeZoneId ?? 'UTC' };
    },
    save: async (s: Record<string, unknown>, timeZoneId?: string): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
  };

  // Still placeholders (filled in by later R2 tasks):
  subtasks!: DataClient['subtasks'];
  recurrence!: DataClient['recurrence'];
  focusSessions!: DataClient['focusSessions'];
  reports!: DataClient['reports'];
```

And add the `stripRank` helper at module scope (next to `byRankId`):

```ts
/** Drop the `_rank` the to* mappers attach (HttpDataClient strips it the same way). */
function stripRank<T extends { _rank?: string }>(v: T): Omit<T, '_rank'> {
  const { _rank, ...rest } = v;
  void _rank;
  return rest;
}
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/reads.test.ts
```

Expected: 1 file passed, 9 tests passed (3 from R2-1 + 6 new).

- [ ] **Commit**

```bash
git add packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/__tests__/reads.test.ts
git commit -m "feat(app): LocalDataClient projects/noteGroups/notes reads + settings.get + dailyPlans.get (R2-2)

Rank-bearing reads sort (rank,id) and strip the mapper _rank; getByProject/
getByGroup use the Dexie indexes; settings.get parses the synced numeric keys
and reads timeZoneId from meta.lastUser (UTC fallback); dailyPlans.get maps via
toDailyPlan or returns null.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R2-3: creates (tasks / projects / noteGroups / notes / subtasks)

**Files:**
- Modify: `packages/app/src/data/local/LocalDataClient.ts`
- Test: `packages/app/src/data/local/__tests__/creates.test.ts`

Each create runs in one rw-transaction over the entity table + `ops`: synthesize the sync-row shape (`changeSeq: 0`, `deletedAt: null`, server-default fields matching what `HttpDataClient` sends + `mappers.from*` produce), assign a client UUID via `newId()`, set `rank = rankAfter(maxRank(localRows))` (append-after-local-max), put the row, then enqueue a `create` op whose wire body is **identical to what `HttpDataClient` POSTs today** (explicit `id` + `rank`), with `entityKeys[0]` = the row's own key, then `bridge.nudge()`. Returns the mapped domain entity. Atomicity: a thrown op-append aborts the whole transaction, leaving no row (proven by forcing an op-table write to throw).

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/creates.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, taskRow, projectRow } from './helpers';
import { rankAfter } from '../../ranking';

afterEach(closeAll);

describe('LocalDataClient.tasks.create', () => {
  it('inserts a synthesized row, appends rank after the local max, enqueues a create op, nudges', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('seed', { rank: 'n' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);

    const created = await dc.tasks.create({ title: 'Buy milk', durationMinutes: 45 });

    expect(created.title).toBe('Buy milk');
    expect(created.durationMinutes).toBe(45);
    const row = await store.tasks.get(created.id);
    expect(row).toBeTruthy();
    expect(row!.rank).toBe(rankAfter('n')); // append after the seed
    expect(row!.changeSeq).toBe(0);
    expect(row!.deletedAt).toBeNull();
    expect(row!.status).toBe('Planned'); // domain default 'planned' → server 'Planned'

    const ops = await store.ops.toArray();
    expect(ops).toHaveLength(1);
    expect(ops[0].method).toBe('POST');
    expect(ops[0].path).toBe('/api/v1/tasks');
    expect(ops[0].kind).toBe('create');
    expect(ops[0].entityKeys).toEqual([`tasks:${created.id}`]);
    const body = ops[0].body as Record<string, unknown>;
    expect(body.id).toBe(created.id);
    expect(body.rank).toBe(row!.rank);
    expect(body.title).toBe('Buy milk');
    expect(body.durationMinutes).toBe(45);
    expect(bridge.nudges).toBe(1);
  });

  it('seeds rank "n" for the first task in an empty container', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    const created = await dc.tasks.create({ title: 'first' });
    expect((await store.tasks.get(created.id))!.rank).toBe('n');
  });
});

describe('LocalDataClient.projects.create', () => {
  it('synthesizes defaults (color/emoji) matching HttpDataClient and queues the create', async () => {
    const store = openStore();
    await store.projects.put(projectRow('seed', { rank: 'n' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    const created = await dc.projects.create({ name: 'Work' });
    expect(created.name).toBe('Work');
    expect(created.color).toBe('#6366f1');
    expect(created.emoji).toBe('📁');
    const ops = await store.ops.toArray();
    expect(ops[0].path).toBe('/api/v1/projects');
    const body = ops[0].body as Record<string, unknown>;
    expect(body).toEqual({
      name: 'Work',
      color: '#6366f1',
      emoji: '📁',
      rank: rankAfter('n'),
      id: created.id,
    });
    expect(ops[0].entityKeys).toEqual([`projects:${created.id}`]);
    expect(bridge.nudges).toBe(1);
  });
});

describe('LocalDataClient.noteGroups.create / notes.create', () => {
  it('noteGroups.create queues id + rank + null emoji/projectId defaults', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    const g = await dc.noteGroups.create({ name: 'Ideas' });
    const op = (await store.ops.toArray())[0];
    expect(op.path).toBe('/api/v1/note-groups');
    expect(op.body).toEqual({ name: 'Ideas', emoji: null, projectId: null, rank: 'n', id: g.id });
  });

  it('notes.create queues null group/project, empty title default, null content', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    const n = await dc.notes.create({});
    const op = (await store.ops.toArray())[0];
    expect(op.path).toBe('/api/v1/notes');
    expect(op.body).toEqual({
      groupId: null,
      projectId: null,
      title: '',
      content: null,
      rank: 'n',
      id: n.id,
    });
  });
});

describe('LocalDataClient.subtasks.create', () => {
  it('inserts a subtask row with client id + sortOrder after the local max, queues POST under the parent', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1'));
    const dc = new LocalDataClient(store, fakeBridge());
    const s1 = await dc.subtasks.create('t1', 'first');
    const s2 = await dc.subtasks.create('t1', 'second');
    const rows = await store.subtasks.where('taskId').equals('t1').sortBy('sortOrder');
    expect(rows.map((r) => r.title)).toEqual(['first', 'second']);
    expect(rows.map((r) => Number(r.sortOrder))).toEqual([0, 1]);
    const ops = await store.ops.toArray();
    expect(ops[0].method).toBe('POST');
    expect(ops[0].path).toBe('/api/v1/tasks/t1/subtasks');
    expect(ops[0].body).toEqual({ id: s1.id, title: 'first' });
    expect(ops[0].entityKeys).toEqual([`subtasks:${s1.id}`]);
    expect(ops[1].body).toEqual({ id: s2.id, title: 'second' });
  });
});

describe('create atomicity', () => {
  it('rolls back the row when the op append fails (transaction abort)', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    // Force the ops table write to throw mid-transaction.
    const orig = store.ops.add.bind(store.ops);
    (store.ops as unknown as { add: unknown }).add = () => {
      throw new Error('boom');
    };
    await expect(dc.tasks.create({ title: 'doomed' })).rejects.toThrow('boom');
    (store.ops as unknown as { add: typeof orig }).add = orig;
    expect(await store.tasks.count()).toBe(0); // row rolled back with the op
    expect(await store.ops.count()).toBe(0);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/creates.test.ts
```

Expected failure: `Error: not implemented in R2-1` (tasks.create) and `Error: not implemented in R2-2` (projects/noteGroups/notes.create) thrown by the placeholders; `dc.subtasks.create is not a function` for the subtasks group.

- [ ] **Minimal impl** — in `LocalDataClient.ts`, extend the `mappers` import to add the `from*`/`toSubtask`/`toFocusSession` mappers used here and later, and add `entityKey` + `rankAfter`/`maxRank` usage. Update the imports block to:

```ts
import type { SyncOp } from '../../sync/types';
import { entityKey } from '../../sync/types';
import { rankAfter, rankBetween } from '../ranking';
import {
  toTask,
  toSubtask,
  toProject,
  toNoteGroup,
  toNote,
  toDailyPlan,
  parseSettings,
} from '../mappers';
```

Add a transaction helper method to the class (above the `tasks` group):

```ts
  /** Run `fn` in one rw-transaction over the named entity tables + the ops table, then nudge. */
  private async writeTx<T>(
    tables: Parameters<LocalStore['transaction']>[1][],
    fn: () => Promise<T>,
  ): Promise<T> {
    const result = await this.store.transaction('rw', [...tables, this.store.ops], fn);
    this.bridge.nudge();
    return result;
  }
```

> Note on `writeTx`: Dexie's `transaction('rw', table[], fn)` accepts a table array; we pass the entity table(s) plus `this.store.ops`. `bridge.nudge()` fires only after the transaction commits (a thrown body rejects before the nudge), so a rolled-back write never nudges — matching the atomicity test.

Replace the R2-1 `tasks.create` thrower with:

```ts
    create: async (t: Partial<Task>): Promise<Task> => {
      const id = t.id ?? newId();
      const existing = await this.store.tasks.toArray();
      const rank = rankAfter(maxRank(existing));
      const row: TaskSyncRow = {
        id,
        title: t.title ?? '',
        notes: t.notes ?? '',
        projectId: t.projectId ?? null,
        labels: t.labels ?? [],
        source: t.source ?? 'manual',
        status: toServerStatus(t.status ?? 'planned'),
        plannedDate: t.plannedDate ?? null,
        scheduledStart: t.scheduledStart ?? null,
        scheduledEnd: t.scheduledEnd ?? null,
        durationMinutes: t.durationMinutes ?? 30,
        actualTimeMinutes: t.actualTimeMinutes ?? 0,
        priority: t.priority ?? null,
        reminderMinutes: t.reminderMinutes ?? null,
        rank,
        dueDate: null,
        recurrenceRuleId: t.recurrenceRuleId ?? null,
        isRecurrenceTemplate: t.isRecurrenceTemplate ?? false,
        recurrenceDetached: t.recurrenceDetached ?? false,
        recurrenceOriginalDate: t.recurrenceOriginalDate ?? null,
        completedAt: t.completedAt ?? null,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      // Wire body identical to HttpDataClient.tasks.create (fromTask(t) + explicit id + rank).
      const body = { ...fromTaskCreate(t), id, rank };
      await this.writeTx([this.store.tasks], async () => {
        await this.store.tasks.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/tasks',
          body,
          entityKeys: [entityKey('tasks', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return this.mapTask(row, []);
    },
```

Replace the `projects.create` thrower with:

```ts
    create: async (i: { name: string; color?: string; emoji?: string }): Promise<Project> => {
      const id = newId();
      const existing = await this.store.projects.toArray();
      const rank = rankAfter(maxRank(existing));
      const color = i.color ?? '#6366f1';
      const emoji = i.emoji ?? '📁';
      const row: ProjectSyncRow = {
        id,
        name: i.name,
        color,
        emoji,
        rank,
        actualTimeMinutes: 0,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = { name: i.name, color, emoji, rank, id };
      await this.writeTx([this.store.projects], async () => {
        await this.store.projects.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/projects',
          body,
          entityKeys: [entityKey('projects', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return stripRank({ ...toProject(row), order: 0 });
    },
```

Replace the `noteGroups.create` thrower with:

```ts
    create: async (i: { name: string; emoji?: string; projectId?: string }): Promise<NoteGroup> => {
      const id = newId();
      const existing = await this.store.noteGroups.toArray();
      const rank = rankAfter(maxRank(existing));
      const emoji = i.emoji ?? null;
      const projectId = i.projectId ?? null;
      const row: NoteGroupSyncRow = {
        id,
        name: i.name,
        emoji: emoji ?? '',
        projectId,
        rank,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = { name: i.name, emoji, projectId, rank, id };
      await this.writeTx([this.store.noteGroups], async () => {
        await this.store.noteGroups.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/note-groups',
          body,
          entityKeys: [entityKey('noteGroups', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return stripRank({ ...toNoteGroup(row) });
    },
```

> `NoteGroupResponse.emoji` is a non-null `string` (schema.d.ts:1477) while the create wire body sends `emoji: null` for the default (HttpDataClient.ts:296). We store `''` in the row so the mapped `NoteGroup.emoji` stays a string, but queue `emoji: null` in the body exactly as HttpDataClient does — the server fills its own default. This mirrors HttpDataClient precisely.

Replace the `notes.create` thrower with:

```ts
    create: async (i: {
      groupId?: string;
      projectId?: string;
      title?: string;
      content?: string;
    }): Promise<Note> => {
      const id = newId();
      const existing = await this.store.notes.toArray();
      const rank = rankAfter(maxRank(existing));
      const groupId = i.groupId ?? null;
      const projectId = i.projectId ?? null;
      const title = i.title ?? '';
      const content = i.content ?? null;
      const row: NoteSyncRow = {
        id,
        groupId,
        projectId,
        title,
        content: content ?? '',
        rank,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = { groupId, projectId, title, content, rank, id };
      await this.writeTx([this.store.notes], async () => {
        await this.store.notes.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/notes',
          body,
          entityKeys: [entityKey('notes', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return stripRank({ ...toNote(row) });
    },
```

Replace the `subtasks!: DataClient['subtasks'];` placeholder with the implemented group (create only; update/delete land in R2-4/R2-5):

```ts
  // ── subtasks ───────────────────────────────────────────────
  subtasks = {
    create: async (taskId: string, title: string): Promise<Subtask> => {
      const id = newId();
      const existing = await this.store.subtasks.where('taskId').equals(taskId).toArray();
      const sortOrder = existing.reduce((m, s) => Math.max(m, Number(s.sortOrder) + 1), 0);
      const row: SubtaskSyncRow = {
        id,
        taskId,
        title,
        completed: false,
        sortOrder,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = { id, title };
      await this.writeTx([this.store.subtasks], async () => {
        await this.store.subtasks.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: `/api/v1/tasks/${taskId}/subtasks`,
          body,
          entityKeys: [entityKey('subtasks', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return toSubtask(row as never, sortOrder);
    },
    update: async (
      id: string,
      u: { title?: string; completed?: boolean; order?: number },
    ): Promise<void> => {
      throw new Error('not implemented in R2-4');
    },
    delete: async (id: string): Promise<void> => {
      throw new Error('not implemented in R2-5');
    },
  };
```

Add these module-scope helpers next to `newId`:

```ts
/** ISO timestamp for synthesized rows (server re-stamps on push; advisory locally). */
function nowIso(): string {
  return new Date().toISOString();
}

/**
 * fromTask for the CREATE path — mirrors HttpDataClient.tasks.create: maps the domain
 * partial to the wire body. mappers.fromTask only forwards present keys and case-folds
 * status; the create endpoint defaults the rest server-side. We add id + rank at the call site.
 */
function fromTaskCreate(t: Partial<Task>): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  const { order: _order, id: _id, ...rest } = t as Record<string, unknown>;
  void _order;
  void _id;
  for (const [k, v] of Object.entries(rest)) {
    if (k === 'status' && typeof v === 'string') body[k] = toServerStatus(v as Task['status']);
    else body[k] = v;
  }
  return body;
}
```

Add the `toServerStatus` import to the `mappers` import block (it is needed by `fromTaskCreate` and the create row synthesis):

```ts
import {
  toTask,
  toSubtask,
  toProject,
  toNoteGroup,
  toNote,
  toDailyPlan,
  parseSettings,
  toServerStatus,
} from '../mappers';
```

> Contract-gap note (id in create body): `HttpDataClient.tasks.create` builds the body via `fromTask(t)` then sets `body.id` / `body.rank` only when undefined. We always set `id` + `rank` explicitly (the create path always provides both), which is wire-equivalent — `fromTaskCreate` drops any caller-supplied `id` so it never appears twice. The spec §3.1 requires "explicit id + rank" in the create body, satisfied here.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/creates.test.ts
```

Expected: 1 file passed, 7 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/__tests__/creates.test.ts
git commit -m "feat(app): LocalDataClient creates with op enqueue + atomicity (R2-3)

tasks/projects/noteGroups/notes/subtasks creates synthesize the sync-row shape
(changeSeq 0, deletedAt null, server-default fields mirroring HttpDataClient +
mappers), assign a client UUID + rankAfter(local max), put the row and enqueue a
create op with the exact wire body HttpDataClient POSTs today (explicit id+rank),
entityKeys[0]=own key, then bridge.nudge() — all in one Dexie rw-transaction so a
failed op append rolls back the row.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R2-4: updates with diff-at-enqueue (tasks / projects / noteGroups / notes / subtasks)

**Files:**
- Modify: `packages/app/src/data/local/LocalDataClient.ts`
- Test: `packages/app/src/data/local/__tests__/updates.test.ts`

Per C4/§3.1: each update maps the domain patch to wire fields, diffs **field-by-field against the current local row**, and enqueues only changed fields into **both** the row update and the PATCH body. Explicit-null clears flow as `null` (SF-2: a field diffing non-null→null is sent as `null`). An empty diff queues no op and does not nudge. **Tasks** use true PATCH presence (the body has *only* changed keys). **Projects/notes/noteGroups** use the server's null=leave-unchanged convention: the body carries changed keys with real values and **all other keys as `null`** (matching `HttpDataClient.{projects,notes,noteGroups}.update`, which always sends every key with null=unchanged), while `rank` is always `null` (reorder owns rank). **Subtasks** map `order`→`sortOrder` (integer semantics, §5).

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/updates.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import {
  openStore,
  closeAll,
  fakeBridge,
  taskRow,
  projectRow,
  noteGroupRow,
  noteRow,
  subtaskRow,
} from './helpers';

afterEach(closeAll);

describe('LocalDataClient.tasks.update (true PATCH presence)', () => {
  it('enqueues only changed keys and updates only those columns', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1', { title: 'old', notes: 'keep', durationMinutes: 30 }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);

    const updated = await dc.tasks.update('t1', { title: 'new', durationMinutes: 30 });

    expect(updated.title).toBe('new');
    const row = await store.tasks.get('t1');
    expect(row!.title).toBe('new');
    expect(row!.notes).toBe('keep');
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/tasks/t1');
    expect(op.kind).toBe('other');
    expect(op.entityKeys).toEqual(['tasks:t1']);
    expect(op.body).toEqual({ title: 'new' }); // durationMinutes unchanged → omitted
    expect(bridge.nudges).toBe(1);
  });

  it('case-folds status and sends only it', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1', { status: 'Planned' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.tasks.update('t1', { status: 'done' });
    expect((await store.tasks.get('t1'))!.status).toBe('Done');
    expect((await store.ops.toArray())[0].body).toEqual({ status: 'Done' });
  });

  it('clears a field to null when the patch sets it null (SF-2)', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1', { projectId: 'P', plannedDate: '2026-06-11' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.tasks.update('t1', { projectId: null });
    expect((await store.tasks.get('t1'))!.projectId).toBeNull();
    expect((await store.ops.toArray())[0].body).toEqual({ projectId: null });
  });

  it('no-op diff queues nothing and does not nudge', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1', { title: 'same' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.tasks.update('t1', { title: 'same' });
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});

describe('LocalDataClient.projects/noteGroups/notes.update (null=unchanged convention)', () => {
  it('projects.update: changed key real value, all others null, rank null', async () => {
    const store = openStore();
    await store.projects.put(projectRow('p1', { name: 'old', color: '#111111', emoji: '📁' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.projects.update('p1', { name: 'New' });
    expect((await store.projects.get('p1'))!.name).toBe('New');
    expect((await store.projects.get('p1'))!.color).toBe('#111111');
    const op = (await store.ops.toArray())[0];
    expect(op.body).toEqual({ name: 'New', color: null, emoji: null, rank: null });
  });

  it('noteGroups.update: changed projectId, others null', async () => {
    const store = openStore();
    await store.noteGroups.put(noteGroupRow('g1', { projectId: null, name: 'G' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.noteGroups.update('g1', { projectId: 'P' });
    expect((await store.noteGroups.get('g1'))!.projectId).toBe('P');
    const op = (await store.ops.toArray())[0];
    expect(op.body).toEqual({ name: null, emoji: null, projectId: 'P', rank: null });
  });

  it('notes.update: changed content, others null', async () => {
    const store = openStore();
    await store.notes.put(noteRow('n1', { content: 'a', title: 'T' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.notes.update('n1', { content: 'b' });
    expect((await store.notes.get('n1'))!.content).toBe('b');
    const op = (await store.ops.toArray())[0];
    expect(op.body).toEqual({ groupId: null, projectId: null, title: null, content: 'b', rank: null });
  });

  it('projects.update no-op diff queues nothing', async () => {
    const store = openStore();
    await store.projects.put(projectRow('p1', { name: 'same' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.projects.update('p1', { name: 'same' });
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});

describe('LocalDataClient.subtasks.update (order→sortOrder)', () => {
  it('maps order to sortOrder and sends only changed fields', async () => {
    const store = openStore();
    await store.subtasks.put(subtaskRow('s1', 't1', { title: 'a', completed: false, sortOrder: 0 }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.subtasks.update('s1', { completed: true, order: 2 });
    const row = await store.subtasks.get('s1');
    expect(row!.completed).toBe(true);
    expect(Number(row!.sortOrder)).toBe(2);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/subtasks/s1');
    expect(op.body).toEqual({ completed: true, sortOrder: 2 });
  });

  it('subtasks.update no-op diff queues nothing', async () => {
    const store = openStore();
    await store.subtasks.put(subtaskRow('s1', 't1', { title: 'a' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.subtasks.update('s1', { title: 'a' });
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/updates.test.ts
```

Expected failure: `Error: not implemented in R2-1` (tasks.update) / `Error: not implemented in R2-2` (projects/noteGroups/notes.update) / `Error: not implemented in R2-4` (subtasks.update).

- [ ] **Minimal impl** — in `LocalDataClient.ts`, add a generic diff helper at module scope:

```ts
/**
 * Diff a wire-shaped patch against the current row's wire-shaped value, returning the
 * subset of keys whose value actually changed (deep-equal by JSON for array/object
 * fields like labels/plannedTaskIds). Explicit null in the patch is a real value (clear).
 */
function diffFields(
  current: Record<string, unknown>,
  patch: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(patch)) {
    if (!valueEqual(current[k], v)) out[k] = v;
  }
  return out;
}

function valueEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (Array.isArray(a) || Array.isArray(b) || (typeof a === 'object' && a !== null)) {
    return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
  }
  return false;
}
```

Replace the `tasks.update` thrower with the diff-at-enqueue implementation (tasks use true PATCH presence — body = changed keys only):

```ts
    update: async (id: string, u: Partial<Task>): Promise<Task> => {
      const row = await this.store.tasks.get(id);
      if (!row) throw new Error(`task ${id} not found`);
      // Map the domain patch to wire shape (case-fold status, drop order), then diff vs the row.
      const patch = fromTaskCreate(u);
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      const subs = await this.liveSubtasks(id);
      if (Object.keys(changed).length === 0) {
        return { ...this.mapTask(row, subs), order: 0 };
      }
      const next = { ...row, ...changed, updatedAt: nowIso() } as TaskSyncRow;
      await this.writeTx([this.store.tasks], async () => {
        await this.store.tasks.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/tasks/${id}`,
          body: changed,
          entityKeys: [entityKey('tasks', id)],
          kind: 'other',
          attempts: 0,
        });
      });
      return { ...this.mapTask(next, subs), order: 0 };
    },
```

Replace the `projects.update` thrower with (null=unchanged wire convention; body = full key set, changed keys real, rest null, rank always null):

```ts
    update: async (id: string, u: Partial<Project>): Promise<Project> => {
      const row = await this.store.projects.get(id);
      if (!row) throw new Error(`project ${id} not found`);
      const patch: Record<string, unknown> = {};
      if (u.name !== undefined) patch.name = u.name;
      if (u.color !== undefined) patch.color = u.color;
      if (u.emoji !== undefined) patch.emoji = u.emoji;
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      if (Object.keys(changed).length === 0) {
        return stripRank({ ...toProject(row), order: 0 });
      }
      const next = { ...row, ...changed, updatedAt: nowIso() } as ProjectSyncRow;
      // Wire body uses the server null=unchanged convention: changed keys real, rest null.
      const body = {
        name: 'name' in changed ? changed.name : null,
        color: 'color' in changed ? changed.color : null,
        emoji: 'emoji' in changed ? changed.emoji : null,
        rank: null,
      };
      await this.writeTx([this.store.projects], async () => {
        await this.store.projects.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/projects/${id}`,
          body,
          entityKeys: [entityKey('projects', id)],
          kind: 'other',
          attempts: 0,
        });
      });
      return stripRank({ ...toProject(next), order: 0 });
    },
```

Replace the `noteGroups.update` thrower with:

```ts
    update: async (id: string, u: Partial<NoteGroup>): Promise<NoteGroup> => {
      const row = await this.store.noteGroups.get(id);
      if (!row) throw new Error(`noteGroup ${id} not found`);
      const patch: Record<string, unknown> = {};
      if (u.name !== undefined) patch.name = u.name;
      if (u.emoji !== undefined) patch.emoji = u.emoji;
      if (u.projectId !== undefined) patch.projectId = u.projectId;
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      if (Object.keys(changed).length === 0) {
        return stripRank({ ...toNoteGroup(row) });
      }
      const next = { ...row, ...changed, updatedAt: nowIso() } as NoteGroupSyncRow;
      const body = {
        name: 'name' in changed ? changed.name : null,
        emoji: 'emoji' in changed ? changed.emoji : null,
        projectId: 'projectId' in changed ? changed.projectId : null,
        rank: null,
      };
      await this.writeTx([this.store.noteGroups], async () => {
        await this.store.noteGroups.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/note-groups/${id}`,
          body,
          entityKeys: [entityKey('noteGroups', id)],
          kind: 'other',
          attempts: 0,
        });
      });
      return stripRank({ ...toNoteGroup(next) });
    },
```

Replace the `notes.update` thrower with:

```ts
    update: async (
      id: string,
      u: Partial<{ title: string; content: string; groupId: string; projectId: string; order: number }>,
    ): Promise<Note> => {
      const row = await this.store.notes.get(id);
      if (!row) throw new Error(`note ${id} not found`);
      const patch: Record<string, unknown> = {};
      if (u.groupId !== undefined) patch.groupId = u.groupId;
      if (u.projectId !== undefined) patch.projectId = u.projectId;
      if (u.title !== undefined) patch.title = u.title;
      if (u.content !== undefined) patch.content = u.content;
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      if (Object.keys(changed).length === 0) {
        return stripRank({ ...toNote(row) });
      }
      const next = { ...row, ...changed, updatedAt: nowIso() } as NoteSyncRow;
      const body = {
        groupId: 'groupId' in changed ? changed.groupId : null,
        projectId: 'projectId' in changed ? changed.projectId : null,
        title: 'title' in changed ? changed.title : null,
        content: 'content' in changed ? changed.content : null,
        rank: null,
      };
      await this.writeTx([this.store.notes], async () => {
        await this.store.notes.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/notes/${id}`,
          body,
          entityKeys: [entityKey('notes', id)],
          kind: 'other',
          attempts: 0,
        });
      });
      return stripRank({ ...toNote(next) });
    },
```

> `notes.update` ignores `u.order` (rank-bearing reorder owns position — `HttpDataClient.notes.update` never forwards `order` either, sending `rank: null`). The diff is computed only over the four content keys.

Replace the `subtasks.update` thrower with (order→sortOrder, true PATCH presence on the three keys):

```ts
    update: async (
      id: string,
      u: { title?: string; completed?: boolean; order?: number },
    ): Promise<void> => {
      const row = await this.store.subtasks.get(id);
      if (!row) throw new Error(`subtask ${id} not found`);
      const patch: Record<string, unknown> = {};
      if (u.title !== undefined) patch.title = u.title;
      if (u.completed !== undefined) patch.completed = u.completed;
      if (u.order !== undefined) patch.sortOrder = u.order;
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      if (Object.keys(changed).length === 0) return;
      const next = { ...row, ...changed, updatedAt: nowIso() } as SubtaskSyncRow;
      await this.writeTx([this.store.subtasks], async () => {
        await this.store.subtasks.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/subtasks/${id}`,
          body: changed,
          entityKeys: [entityKey('subtasks', id)],
          kind: 'other',
          attempts: 0,
        });
      });
    },
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/updates.test.ts
```

Expected: 1 file passed, 10 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/__tests__/updates.test.ts
git commit -m "feat(app): LocalDataClient updates with diff-at-enqueue (R2-4)

Every update diffs the wire-mapped patch field-by-field against the current
local row and enqueues only changed fields into both the row update and the
PATCH body (spec §3.1). Tasks/subtasks use true PATCH presence; projects/notes/
noteGroups use the server null=unchanged convention (changed keys real, rest
null, rank null). Explicit null clears flow as null (SF-2); an empty diff queues
no op and does not nudge. Subtask order maps to integer sortOrder (§5).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R2-5: deletes with local cascades (tasks / projects / noteGroups / notes / subtasks)

**Files:**
- Modify: `packages/app/src/data/local/LocalDataClient.ts`
- Test: `packages/app/src/data/local/__tests__/deletes.test.ts`

Per spec §2 the local cascades mirror what server reconciliation produces (verified in the server endpoints): **task delete** removes the task row + all its subtask rows (`entityKeys` lists the task plus every subtask — `TasksEndpoints.DeleteAsync` tombstones the task and its live subtasks); **noteGroup delete** removes the group + all its note rows (`NoteGroupsEndpoints.Delete` cascade-tombstones the group's live notes); **project delete** nulls `projectId` on local tasks / notes / noteGroups (`ProjectEndpoints.Delete` does a set-null cascade, not a tombstone). The queued op is always the single DELETE to the entity's own path (the server runs its own cascade); `entityKeys` cover every locally-affected key so the §4.2 shadow rule protects them. Subtask delete removes one subtask row.

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/deletes.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import {
  openStore,
  closeAll,
  fakeBridge,
  taskRow,
  subtaskRow,
  projectRow,
  noteGroupRow,
  noteRow,
} from './helpers';

afterEach(closeAll);

describe('LocalDataClient.tasks.delete (cascade subtasks locally)', () => {
  it('removes the task + its subtasks; op DELETE lists all affected keys', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1'));
    await store.subtasks.bulkPut([subtaskRow('s1', 't1'), subtaskRow('s2', 't1')]);
    await store.subtasks.put(subtaskRow('sx', 'other'));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);

    await dc.tasks.delete('t1');

    expect(await store.tasks.get('t1')).toBeUndefined();
    expect(await store.subtasks.where('taskId').equals('t1').count()).toBe(0);
    expect(await store.subtasks.get('sx')).toBeTruthy(); // foreign subtask untouched
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('DELETE');
    expect(op.path).toBe('/api/v1/tasks/t1');
    expect(new Set(op.entityKeys)).toEqual(new Set(['tasks:t1', 'subtasks:s1', 'subtasks:s2']));
    expect(bridge.nudges).toBe(1);
  });
});

describe('LocalDataClient.noteGroups.delete (cascade notes locally)', () => {
  it('removes the group + its notes; op lists all keys', async () => {
    const store = openStore();
    await store.noteGroups.put(noteGroupRow('g1'));
    await store.notes.bulkPut([noteRow('n1', { groupId: 'g1' }), noteRow('n2', { groupId: 'g1' })]);
    await store.notes.put(noteRow('n3', { groupId: 'other' }));
    const dc = new LocalDataClient(store, fakeBridge());

    await dc.noteGroups.delete('g1');

    expect(await store.noteGroups.get('g1')).toBeUndefined();
    expect(await store.notes.where('groupId').equals('g1').count()).toBe(0);
    expect(await store.notes.get('n3')).toBeTruthy();
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('DELETE');
    expect(op.path).toBe('/api/v1/note-groups/g1');
    expect(new Set(op.entityKeys)).toEqual(new Set(['noteGroups:g1', 'notes:n1', 'notes:n2']));
  });
});

describe('LocalDataClient.projects.delete (set-null cascade locally)', () => {
  it('nulls projectId on tasks/notes/noteGroups; op lists every reparented key', async () => {
    const store = openStore();
    await store.projects.put(projectRow('p1'));
    await store.tasks.put(taskRow('t1', { projectId: 'p1' }));
    await store.notes.put(noteRow('n1', { projectId: 'p1' }));
    await store.noteGroups.put(noteGroupRow('g1', { projectId: 'p1' }));
    await store.tasks.put(taskRow('t2', { projectId: 'other' }));
    const dc = new LocalDataClient(store, fakeBridge());

    await dc.projects.delete('p1');

    expect(await store.projects.get('p1')).toBeUndefined();
    expect((await store.tasks.get('t1'))!.projectId).toBeNull();
    expect((await store.notes.get('n1'))!.projectId).toBeNull();
    expect((await store.noteGroups.get('g1'))!.projectId).toBeNull();
    expect((await store.tasks.get('t2'))!.projectId).toBe('other'); // unaffected
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('DELETE');
    expect(op.path).toBe('/api/v1/projects/p1');
    expect(new Set(op.entityKeys)).toEqual(
      new Set(['projects:p1', 'tasks:t1', 'notes:n1', 'noteGroups:g1']),
    );
  });
});

describe('LocalDataClient.notes.delete / subtasks.delete', () => {
  it('notes.delete removes the row + queues DELETE', async () => {
    const store = openStore();
    await store.notes.put(noteRow('n1'));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.notes.delete('n1');
    expect(await store.notes.get('n1')).toBeUndefined();
    const op = (await store.ops.toArray())[0];
    expect(op.path).toBe('/api/v1/notes/n1');
    expect(op.entityKeys).toEqual(['notes:n1']);
  });

  it('subtasks.delete removes the row + queues DELETE', async () => {
    const store = openStore();
    await store.subtasks.put(subtaskRow('s1', 't1'));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.subtasks.delete('s1');
    expect(await store.subtasks.get('s1')).toBeUndefined();
    const op = (await store.ops.toArray())[0];
    expect(op.path).toBe('/api/v1/subtasks/s1');
    expect(op.entityKeys).toEqual(['subtasks:s1']);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/deletes.test.ts
```

Expected failure: `Error: not implemented in R2-1` (tasks.delete) / `Error: not implemented in R2-2` (projects/noteGroups/notes.delete) / `Error: not implemented in R2-5` (subtasks.delete).

- [ ] **Minimal impl** — replace the `tasks.delete` thrower:

```ts
    delete: async (id: string): Promise<void> => {
      const subs = await this.store.subtasks.where('taskId').equals(id).toArray();
      const keys = [entityKey('tasks', id), ...subs.map((s) => entityKey('subtasks', s.id))];
      await this.writeTx([this.store.tasks, this.store.subtasks], async () => {
        await this.store.tasks.delete(id);
        await this.store.subtasks.where('taskId').equals(id).delete();
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/tasks/${id}`,
          entityKeys: keys,
          kind: 'other',
          attempts: 0,
        });
      });
    },
```

Replace the `projects.delete` thrower (set-null cascade mirroring `ProjectEndpoints.Delete`):

```ts
    delete: async (id: string): Promise<void> => {
      const tasks = await this.store.tasks.where('projectId').equals(id).toArray();
      const notes = await this.store.notes.where('projectId').equals(id).toArray();
      const groups = await this.store.noteGroups.where('projectId').equals(id).toArray();
      const keys = [
        entityKey('projects', id),
        ...tasks.map((t) => entityKey('tasks', t.id)),
        ...notes.map((n) => entityKey('notes', n.id)),
        ...groups.map((g) => entityKey('noteGroups', g.id)),
      ];
      await this.writeTx(
        [this.store.projects, this.store.tasks, this.store.notes, this.store.noteGroups],
        async () => {
          await this.store.projects.delete(id);
          for (const t of tasks) await this.store.tasks.put({ ...t, projectId: null });
          for (const n of notes) await this.store.notes.put({ ...n, projectId: null });
          for (const g of groups) await this.store.noteGroups.put({ ...g, projectId: null });
          await this.store.ops.add({
            method: 'DELETE',
            path: `/api/v1/projects/${id}`,
            entityKeys: keys,
            kind: 'other',
            attempts: 0,
          });
        },
      );
    },
```

Replace the `noteGroups.delete` thrower (cascade-remove notes mirroring `NoteGroupsEndpoints.Delete`):

```ts
    delete: async (id: string): Promise<void> => {
      const notes = await this.store.notes.where('groupId').equals(id).toArray();
      const keys = [entityKey('noteGroups', id), ...notes.map((n) => entityKey('notes', n.id))];
      await this.writeTx([this.store.noteGroups, this.store.notes], async () => {
        await this.store.noteGroups.delete(id);
        await this.store.notes.where('groupId').equals(id).delete();
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/note-groups/${id}`,
          entityKeys: keys,
          kind: 'other',
          attempts: 0,
        });
      });
    },
```

Replace the `notes.delete` thrower:

```ts
    delete: async (id: string): Promise<void> => {
      await this.writeTx([this.store.notes], async () => {
        await this.store.notes.delete(id);
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/notes/${id}`,
          entityKeys: [entityKey('notes', id)],
          kind: 'other',
          attempts: 0,
        });
      });
    },
```

Replace the `subtasks.delete` thrower:

```ts
    delete: async (id: string): Promise<void> => {
      await this.writeTx([this.store.subtasks], async () => {
        await this.store.subtasks.delete(id);
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/subtasks/${id}`,
          entityKeys: [entityKey('subtasks', id)],
          kind: 'other',
          attempts: 0,
        });
      });
    },
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/deletes.test.ts
```

Expected: 1 file passed, 5 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/__tests__/deletes.test.ts
git commit -m "feat(app): LocalDataClient deletes with local cascades (R2-5)

task delete removes the task + its subtask rows; noteGroup delete removes the
group + its notes; project delete nulls projectId on local tasks/notes/noteGroups
— each mirroring the server's exact cascade (tombstone vs set-null). The queued op
is the single DELETE to the entity's own path (server cascades itself); entityKeys
cover every locally-affected key so the §4.2 shadow rule protects them.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R2-6: reorders with tie-aware re-ranking (tasks / projects / noteGroups / notes)

**Files:**
- Modify: `packages/app/src/data/local/LocalDataClient.ts`
- Test: `packages/app/src/data/local/__tests__/reorder.test.ts`

Per §5.1: `reorder(items[{id,order}])` ports `HttpDataClient.computeReorder` (HttpDataClient.ts:104-146) but operates on a rank map built from the **local rows** (not an in-memory cache). The minimal-change algorithm is preserved verbatim; the one addition is **tie repair**: when the chosen `prev`/`next` neighbors tie (`prev === next`), `rankBetween(prev, next)` would produce a rank sorting after both, so the tied run is re-ranked with fresh distinct ranks and every re-ranked row enters the payload. The op body is `[{id, rank}]` to the existing reorder endpoints; local rows update in the same transaction. An empty payload queues nothing and does not nudge.

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/reorder.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, taskRow } from './helpers';

afterEach(closeAll);

// Helper: read the final (rank,id)-sorted id order straight from the store.
async function order(store: ReturnType<typeof openStore>): Promise<string[]> {
  const rows = await store.tasks.toArray();
  return rows
    .sort((a, b) => (a.rank < b.rank ? -1 : a.rank > b.rank ? 1 : a.id < b.id ? -1 : 1))
    .map((r) => r.id);
}

describe('LocalDataClient.tasks.reorder', () => {
  it('simple move: minimal payload re-ranks only the moved row', async () => {
    const store = openStore();
    await store.tasks.bulkPut([
      taskRow('a', { rank: 'a' }),
      taskRow('b', { rank: 'b' }),
      taskRow('c', { rank: 'c' }),
    ]);
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    // Move 'c' to the front: desired order [c, a, b].
    await dc.tasks.reorder([
      { id: 'c', order: 0 },
      { id: 'a', order: 1 },
      { id: 'b', order: 2 },
    ]);
    expect(await order(store)).toEqual(['c', 'a', 'b']);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/tasks/reorder');
    const body = op.body as { id: string; rank: string }[];
    expect(body.map((e) => e.id)).toEqual(['c']); // only the moved row
    expect(body[0].rank < 'a').toBe(true);
    expect(bridge.nudges).toBe(1);
  });

  it('moving between tied neighbors re-ranks the tied run with fresh distinct ranks', async () => {
    const store = openStore();
    // Three rows tie on 'a0' (the recurrence-instance population, §5.1) + one mover at the end.
    await store.tasks.bulkPut([
      taskRow('p', { rank: 'a0' }),
      taskRow('q', { rank: 'a0' }),
      taskRow('r', { rank: 'a0' }),
      taskRow('z', { rank: 'm' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    // Desired order: p, z, q, r — insert z between the tied p and q.
    await dc.tasks.reorder([
      { id: 'p', order: 0 },
      { id: 'z', order: 1 },
      { id: 'q', order: 2 },
      { id: 'r', order: 3 },
    ]);
    expect(await order(store)).toEqual(['p', 'z', 'q', 'r']);
    const body = (await store.ops.toArray())[0].body as { id: string; rank: string }[];
    // Every row in the payload must have a distinct rank, and the final order must hold.
    const ranks = new Map(body.map((e) => [e.id, e.rank]));
    const finalRank = (id: string) =>
      ranks.get(id) ?? { p: 'a0', q: 'a0', r: 'a0', z: 'm' }[id]!;
    expect(finalRank('p') < finalRank('z')).toBe(true);
    expect(finalRank('z') < finalRank('q')).toBe(true);
    expect(finalRank('q') < finalRank('r')).toBe(true);
    expect(new Set(body.map((e) => e.rank)).size).toBe(body.length); // all distinct
  });

  it('no-op reorder (already in order) queues nothing and does not nudge', async () => {
    const store = openStore();
    await store.tasks.bulkPut([
      taskRow('a', { rank: 'a' }),
      taskRow('b', { rank: 'b' }),
    ]);
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.tasks.reorder([
      { id: 'a', order: 0 },
      { id: 'b', order: 1 },
    ]);
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/reorder.test.ts
```

Expected failure: `Error: not implemented in R2-1` thrown by `tasks.reorder`.

- [ ] **Minimal impl** — add a private reorder engine to the class (it builds the rank map from local rows, ports `computeReorder`, and adds tie repair), then wire the four `reorder` methods to it.

Add this method to the class (e.g. just below `writeTx`):

```ts
  /**
   * Port of HttpDataClient.computeReorder against LOCAL rows, with §5.1 tie repair.
   * Returns the minimal [{id, rank}] payload AND mutates `ranks` to the post-move state
   * so the caller can persist the same ranks to the rows in one transaction.
   */
  private computeReorderLocal(
    ranks: Map<string, string>,
    items: ReorderInput[],
  ): { id: string; rank: string }[] {
    const ordered = [...items].sort((a, b) => a.order - b.order);
    const result: { id: string; rank: string }[] = [];
    const working = new Map(ranks);
    let prevRank: string | null = null;

    for (let i = 0; i < ordered.length; i++) {
      const cur = ordered[i];
      const curRank = working.get(cur.id) ?? null;

      // Upper bound = nearest later item whose current rank sorts strictly above prevRank.
      let nextRank: string | null = null;
      for (let j = i + 1; j < ordered.length; j++) {
        const r = working.get(ordered[j].id) ?? null;
        if (r !== null && (prevRank === null || r > prevRank)) {
          nextRank = r;
          break;
        }
      }

      const inOrder =
        curRank !== null &&
        (prevRank === null || prevRank < curRank) &&
        (nextRank === null || curRank < nextRank);

      if (inOrder) {
        prevRank = curRank;
        continue;
      }

      // §5.1 tie repair: rankBetween(prev, next) with prev === next sorts after BOTH.
      // When the bounding neighbors tie (or prev would equal next), append after prev so
      // the fresh rank is strictly greater than prevRank and below the next distinct rank.
      let newRank: string;
      if (prevRank !== null && nextRank !== null && prevRank >= nextRank) {
        newRank = rankAfter(prevRank);
      } else {
        newRank = rankBetween(prevRank, nextRank);
      }
      working.set(cur.id, newRank);
      ranks.set(cur.id, newRank);
      result.push({ id: cur.id, rank: newRank });
      prevRank = newRank;
    }
    return result;
  }

  /** Shared reorder driver for every rank-bearing table. */
  private async reorderTable<R extends { id: string; rank: string }>(
    table: Parameters<LocalStore['transaction']>[1],
    keyTable: Parameters<typeof entityKey>[0],
    path: string,
    items: ReorderInput[],
  ): Promise<void> {
    const rows = (await table.toArray()) as R[];
    const ranks = new Map(rows.map((r) => [r.id, r.rank]));
    const payload = this.computeReorderLocal(ranks, items);
    if (payload.length === 0) return;
    const byId = new Map(rows.map((r) => [r.id, r]));
    await this.writeTx([table], async () => {
      for (const { id, rank } of payload) {
        const row = byId.get(id);
        if (row) await (table as { put(v: R): Promise<unknown> }).put({ ...row, rank });
      }
      await this.store.ops.add({
        method: 'PATCH',
        path,
        body: payload,
        entityKeys: payload.map((e) => entityKey(keyTable, e.id)),
        kind: 'other',
        attempts: 0,
      });
    });
  }
```

Replace the `tasks.reorder` thrower with:

```ts
    reorder: async (items: ReorderInput[]): Promise<void> => {
      await this.reorderTable(this.store.tasks, 'tasks', '/api/v1/tasks/reorder', items);
    },
```

Replace the `projects.reorder` thrower with:

```ts
    reorder: async (items: ReorderInput[]): Promise<void> => {
      await this.reorderTable(this.store.projects, 'projects', '/api/v1/projects/reorder', items);
    },
```

Replace the `noteGroups.reorder` thrower with:

```ts
    reorder: async (items: ReorderInput[]): Promise<void> => {
      await this.reorderTable(
        this.store.noteGroups,
        'noteGroups',
        '/api/v1/note-groups/reorder',
        items,
      );
    },
```

Replace the `notes.reorder` thrower with:

```ts
    reorder: async (items: ReorderInput[]): Promise<void> => {
      await this.reorderTable(this.store.notes, 'notes', '/api/v1/notes/reorder', items);
    },
```

> The reorder `entityKeys` list every re-ranked row's key (so the shadow rule shields a mid-pull reorder), and the body matches the existing `ReorderItem[]` wire shape (`{ id, rank }`, schema.d.ts:1574-1578). Tie repair only ever *adds* payload entries (the re-ranked tied run), never reorders the minimal-change logic — a non-tied move still emits exactly one entry (proven by the simple-move test's `['c']`).

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/reorder.test.ts
```

Expected: 1 file passed, 3 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/__tests__/reorder.test.ts
git commit -m "feat(app): LocalDataClient reorders with tie-aware re-ranking (R2-6)

Ports HttpDataClient.computeReorder against local rows (minimal [{id,rank}]
payload), adding §5.1 tie repair: when bounding neighbors tie, rankAfter the
previous fresh rank instead of rankBetween(prev,prev) (which sorts after both),
re-ranking the minimal tied run and including every re-ranked row in the payload.
Local rows + op enqueue in one transaction; empty payload queues nothing.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R2-7: dailyPlans.upsert + settings.save + focusSessions.add

**Files:**
- Modify: `packages/app/src/data/local/LocalDataClient.ts`
- Test: `packages/app/src/data/local/__tests__/misc.test.ts`

`dailyPlans.upsert` writes a row keyed by `date` (stamping `committedAt = local now ISO`; server re-stamps on apply, spec §6), queues a `PUT /api/v1/daily-plans/{date}` op with `entityKeys ['dailyPlans:<date>']`, returns the mapped plan. `settings.save` diffs against current `settings` rows (per C9/§3.1): only changed keys enter the `PUT /api/v1/settings` body; `timeZoneId` is included **only when changed** (compared against `meta.lastUser.timeZoneId`); local `settings` rows update and `meta.lastUser.timeZoneId` updates. `focusSessions.add` inserts a row with a client UUID and queues a `POST /api/v1/focus-sessions` create op (kind `create`).

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/misc.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, planRow, settingRow } from './helpers';

afterEach(closeAll);

describe('LocalDataClient.dailyPlans.upsert', () => {
  it('writes the row keyed by date, stamps committedAt locally, queues a PUT', async () => {
    const store = openStore();
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    const plan = await dc.dailyPlans.upsert({
      date: '2026-06-11',
      plannedTaskIds: ['t1', 't2'],
      plannedMinutes: 90,
    });
    expect(plan.date).toBe('2026-06-11');
    expect(plan.plannedTaskIds).toEqual(['t1', 't2']);
    expect(typeof plan.committedAt).toBe('string');
    const row = await store.dailyPlans.get('2026-06-11');
    expect(row!.plannedMinutes).toBe(90);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PUT');
    expect(op.path).toBe('/api/v1/daily-plans/2026-06-11');
    expect(op.body).toEqual({ plannedTaskIds: ['t1', 't2'], plannedMinutes: 90 });
    expect(op.entityKeys).toEqual(['dailyPlans:2026-06-11']);
    expect(bridge.nudges).toBe(1);
  });

  it('overwrites the existing plan for the same date', async () => {
    const store = openStore();
    await store.dailyPlans.put(planRow('2026-06-11', { plannedMinutes: 10 }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.dailyPlans.upsert({ date: '2026-06-11', plannedTaskIds: [], plannedMinutes: 200 });
    expect((await store.dailyPlans.get('2026-06-11'))!.plannedMinutes).toBe(200);
  });
});

describe('LocalDataClient.settings.save (diffed PUT)', () => {
  it('sends only changed keys; updates local rows', async () => {
    const store = openStore();
    await store.settings.bulkPut([settingRow('workStartHour', '8'), settingRow('workEndHour', '18')]);
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.settings.save({ workStartHour: 9, workEndHour: 18 });
    expect((await store.settings.get('workStartHour'))!.value).toBe('9');
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PUT');
    expect(op.path).toBe('/api/v1/settings');
    expect(op.body).toEqual({ settings: { workStartHour: '9' } }); // only the changed key
    expect(op.entityKeys).toEqual(['settings:workStartHour']);
    expect(bridge.nudges).toBe(1);
  });

  it('includes timeZoneId only when changed and updates meta.lastUser', async () => {
    const store = openStore();
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.settings.save({ workStartHour: 8 }, 'America/New_York');
    const op = (await store.ops.toArray())[0];
    expect(op.body).toEqual({
      settings: { workStartHour: '8' },
      timeZoneId: 'America/New_York',
    });
    const lastUser = await store.getMeta<{ timeZoneId: string }>('lastUser');
    expect(lastUser!.timeZoneId).toBe('America/New_York');
  });

  it('no-op save (nothing changed, same timeZoneId) queues nothing', async () => {
    const store = openStore();
    await store.settings.put(settingRow('workStartHour', '8'));
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.settings.save({ workStartHour: 8 }, 'UTC');
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});

describe('LocalDataClient.focusSessions.add', () => {
  it('inserts a row with a client id and queues a create op', async () => {
    const store = openStore();
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    const s = await dc.focusSessions.add({
      taskId: null,
      project: 'Work',
      startedAt: '2026-06-11T09:00:00Z',
      endedAt: '2026-06-11T09:25:00Z',
      minutes: 25,
      date: '2026-06-11',
    });
    expect(s.id).toBeTruthy();
    expect(s.project).toBe('Work');
    const row = await store.focusSessions.get(s.id);
    expect(row!.minutes).toBe(25);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('POST');
    expect(op.path).toBe('/api/v1/focus-sessions');
    expect(op.kind).toBe('create');
    expect(op.entityKeys).toEqual([`focusSessions:${s.id}`]);
    expect(op.body).toEqual({
      id: s.id,
      taskId: null,
      project: 'Work',
      startedAt: '2026-06-11T09:00:00Z',
      endedAt: '2026-06-11T09:25:00Z',
      minutes: 25,
      date: '2026-06-11',
    });
    expect(bridge.nudges).toBe(1);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/misc.test.ts
```

Expected failure: `Error: not implemented in R2-2` (dailyPlans.upsert + settings.save) and `dc.focusSessions.add is not a function` (the `focusSessions` group is still the `!` placeholder).

- [ ] **Minimal impl** — add `toFocusSession` to the `mappers` import block:

```ts
import {
  toTask,
  toSubtask,
  toProject,
  toNoteGroup,
  toNote,
  toDailyPlan,
  toFocusSession,
  parseSettings,
  toServerStatus,
} from '../mappers';
```

Replace the `dailyPlans.upsert` thrower:

```ts
    upsert: async (p: {
      date: string;
      plannedTaskIds: string[];
      plannedMinutes: number;
    }): Promise<DailyPlan> => {
      const committedAt = nowIso();
      const existing = await this.store.dailyPlans.get(p.date);
      const row = {
        date: p.date,
        committedAt,
        plannedTaskIds: p.plannedTaskIds,
        plannedMinutes: p.plannedMinutes,
        createdAt: existing?.createdAt ?? committedAt,
        updatedAt: committedAt,
        changeSeq: existing?.changeSeq ?? 0,
        deletedAt: null,
      };
      await this.writeTx([this.store.dailyPlans], async () => {
        await this.store.dailyPlans.put(row as never);
        await this.store.ops.add({
          method: 'PUT',
          path: `/api/v1/daily-plans/${p.date}`,
          body: { plannedTaskIds: p.plannedTaskIds, plannedMinutes: p.plannedMinutes },
          entityKeys: [entityKey('dailyPlans', p.date)],
          kind: 'other',
          attempts: 0,
        });
      });
      return toDailyPlan(row as never);
    },
```

Replace the `settings.save` thrower:

```ts
    save: async (s: Record<string, unknown>, timeZoneId?: string): Promise<void> => {
      // The three synced numeric keys, stringified (mirrors mappers.stringifySettings).
      const SYNCED = ['workStartHour', 'workEndHour', 'timeIncrement'] as const;
      const rows = await this.store.settings.toArray();
      const current: Record<string, string> = {};
      for (const r of rows) current[r.key] = r.value;
      const changedSettings: Record<string, string> = {};
      for (const k of SYNCED) {
        const v = s[k];
        if (v === undefined || v === null) continue;
        const str = String(v);
        if (current[k] !== str) changedSettings[k] = str;
      }
      const lastUser = await this.store.getMeta<{ id: string; email: string; timeZoneId: string }>(
        'lastUser',
      );
      const tzChanged = timeZoneId !== undefined && timeZoneId !== lastUser?.timeZoneId;
      if (Object.keys(changedSettings).length === 0 && !tzChanged) return;

      const body: { settings: Record<string, string>; timeZoneId?: string } = {
        settings: changedSettings,
      };
      if (tzChanged) body.timeZoneId = timeZoneId;

      const tables = [this.store.settings, this.store.meta];
      await this.writeTx(tables, async () => {
        const now = nowIso();
        for (const [k, v] of Object.entries(changedSettings)) {
          const ex = await this.store.settings.get(k);
          await this.store.settings.put({
            key: k,
            value: v,
            createdAt: ex?.createdAt ?? now,
            updatedAt: now,
            changeSeq: ex?.changeSeq ?? 0,
            deletedAt: null,
          } as never);
        }
        if (tzChanged && lastUser) {
          await this.store.meta.put({ key: 'lastUser', value: { ...lastUser, timeZoneId } });
        }
        await this.store.ops.add({
          method: 'PUT',
          path: '/api/v1/settings',
          body,
          entityKeys: Object.keys(changedSettings).map((k) => entityKey('settings', k)),
          kind: 'other',
          attempts: 0,
        });
      });
    },
```

> Settings entityKeys list only the changed setting keys; when only `timeZoneId` changed (no setting key), `entityKeys` is `[]` — acceptable because `timeZoneId` is not a synced table (spec §6: it arrives via the auth refresh response, not pull) so it needs no shadow protection.

Replace the `focusSessions!: DataClient['focusSessions'];` placeholder with the implemented group:

```ts
  // ── focus sessions ─────────────────────────────────────────
  focusSessions = {
    add: async (s: {
      taskId: string | null;
      project: string;
      startedAt: string;
      endedAt: string;
      minutes: number;
      date: string;
    }): Promise<FocusSession> => {
      const id = newId();
      const row = {
        id,
        taskId: s.taskId,
        project: s.project,
        startedAt: s.startedAt,
        endedAt: s.endedAt,
        minutes: s.minutes,
        date: s.date,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = {
        id,
        taskId: s.taskId,
        project: s.project,
        startedAt: s.startedAt,
        endedAt: s.endedAt,
        minutes: s.minutes,
        date: s.date,
      };
      await this.writeTx([this.store.focusSessions], async () => {
        await this.store.focusSessions.add(row as never);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/focus-sessions',
          body,
          entityKeys: [entityKey('focusSessions', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return toFocusSession(row as never);
    },
  };
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/misc.test.ts
```

Expected: 1 file passed, 6 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/__tests__/misc.test.ts
git commit -m "feat(app): LocalDataClient dailyPlans.upsert + settings.save + focusSessions.add (R2-7)

dailyPlans.upsert writes the date-keyed row (local committedAt; server re-stamps),
queues PUT; settings.save diffs synced keys + timeZoneId (changed-only PUT per
C9/§3.1) updating local rows + meta.lastUser.timeZoneId; focusSessions.add inserts
a row with a client uuid + queues a create op (backend touch-up idempotency).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R2-8: recurrence group (create / updateSeries / deleteSeries / deleteSeriesFuture / updateRule / detachInstance / getRule / ensureInstances)

**Files:**
- Modify: `packages/app/src/data/local/LocalDataClient.ts`
- Test: `packages/app/src/data/local/__tests__/recurrence.test.ts`

The recurrence group queues each wire op and applies a local approximation of the server effect (spec §6; the post-push pull is authoritative). Mirroring `RecurrenceEndpoints.cs`:
- **create**: insert a template task row (client task id, `isRecurrenceTemplate: true`, `rank: 'a0'`, `status: 'Planned'`, `recurrenceOriginalDate = plannedDate ?? today`, defaults matching the server's `Create`) + a rule row (client rule id, `generatedUntil: null`); queue `POST /api/v1/recurrence` with the wire body (both ids), `kind: 'create'`, `regenAfterPush: true`; **return the full local task list** (§6 store contract at store.ts:667).
- **updateSeries**: patch the template + future, non-detached, not-done live instances locally (mirrors `UpdateSeries`); queue `PATCH .../series` (no `regenAfterPush`).
- **deleteSeries**: remove the rule row + all its task rows locally; queue `DELETE .../rules/{id}`.
- **deleteSeriesFuture**: remove future, not-done, **non-template** instances (detached included, §6 pinned) on/after `from`; queue `POST .../delete-future`.
- **updateRule**: update the rule row + remove future non-detached not-done instances locally (mirrors `UpdateRule`); queue `PATCH .../rules/{id}` with `regenAfterPush: true`.
- **detachInstance**: set `recurrenceDetached: true` locally; queue `PATCH .../instances/{taskId}/detach`.
- **getRule**: local read → mapped rule or null.
- **ensureInstances**: read-through — `bridge.online()` ? `bridge.ensureInstances(start,end)` then upsert returned rows locally + return mapped : `[]`.

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/recurrence.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, taskRow, ruleRow } from './helpers';
import type { RecurrenceRuleInput } from '../../DataClient';

afterEach(closeAll);

const dailyRule: RecurrenceRuleInput = {
  frequency: 'daily',
  interval: 1,
  daysOfWeek: [],
  endType: 'never',
};

describe('LocalDataClient.recurrence.create', () => {
  it('inserts template task + rule, queues POST /recurrence with both ids + regenAfterPush, returns full task list', async () => {
    const store = openStore();
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    const tasks = await dc.recurrence.create(
      { title: 'Standup', plannedDate: '2026-06-11', durationMinutes: 15 },
      dailyRule,
    );
    expect(tasks).toHaveLength(1);
    const tmpl = tasks[0];
    expect(tmpl.title).toBe('Standup');
    expect(tmpl.isRecurrenceTemplate).toBe(true);
    const row = await store.tasks.get(tmpl.id);
    expect(row!.rank).toBe('a0');
    expect(row!.isRecurrenceTemplate).toBe(true);
    expect(row!.recurrenceOriginalDate).toBe('2026-06-11');
    const rules = await store.recurrenceRules.toArray();
    expect(rules).toHaveLength(1);
    expect(row!.recurrenceRuleId).toBe(rules[0].id);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('POST');
    expect(op.path).toBe('/api/v1/recurrence');
    expect(op.kind).toBe('create');
    expect(op.regenAfterPush).toBe(true);
    const body = op.body as { task: Record<string, unknown>; rule: Record<string, unknown> };
    expect(body.task.id).toBe(tmpl.id);
    expect(body.task.title).toBe('Standup');
    expect(body.rule.id).toBe(rules[0].id);
    expect(body.rule.frequency).toBe('Daily');
    expect(new Set(op.entityKeys)).toEqual(
      new Set([`tasks:${tmpl.id}`, `recurrenceRules:${rules[0].id}`]),
    );
    expect(bridge.nudges).toBe(1);
  });
});

describe('LocalDataClient.recurrence.updateSeries', () => {
  it('patches the template + future not-done non-detached instances; queues PATCH series', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R'));
    await store.tasks.bulkPut([
      taskRow('tmpl', { recurrenceRuleId: 'R', isRecurrenceTemplate: true, title: 'old' }),
      taskRow('fut', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Planned', title: 'old' }),
      taskRow('done', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Done', title: 'old' }),
      taskRow('past', { recurrenceRuleId: 'R', plannedDate: '2000-01-01', status: 'Planned', title: 'old' }),
      taskRow('det', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', recurrenceDetached: true, title: 'old' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.updateSeries('R', { title: 'new' });
    expect((await store.tasks.get('tmpl'))!.title).toBe('new');
    expect((await store.tasks.get('fut'))!.title).toBe('new');
    expect((await store.tasks.get('done'))!.title).toBe('old');
    expect((await store.tasks.get('past'))!.title).toBe('old');
    expect((await store.tasks.get('det'))!.title).toBe('old');
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/recurrence/rules/R/series');
    expect(op.regenAfterPush).toBeUndefined();
    expect(op.body).toMatchObject({ title: 'new' });
  });
});

describe('LocalDataClient.recurrence.deleteSeries', () => {
  it('removes the rule + all its tasks; queues DELETE', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R'));
    await store.tasks.bulkPut([
      taskRow('tmpl', { recurrenceRuleId: 'R', isRecurrenceTemplate: true }),
      taskRow('i1', { recurrenceRuleId: 'R' }),
      taskRow('other', { recurrenceRuleId: null }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.deleteSeries('R');
    expect(await store.recurrenceRules.get('R')).toBeUndefined();
    expect(await store.tasks.where('recurrenceRuleId').equals('R').count()).toBe(0);
    expect(await store.tasks.get('other')).toBeTruthy();
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('DELETE');
    expect(op.path).toBe('/api/v1/recurrence/rules/R');
  });
});

describe('LocalDataClient.recurrence.deleteSeriesFuture', () => {
  it('removes future not-done NON-TEMPLATE instances incl. detached; queues POST delete-future', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R'));
    await store.tasks.bulkPut([
      taskRow('tmpl', { recurrenceRuleId: 'R', isRecurrenceTemplate: true, plannedDate: '2999-01-01' }),
      taskRow('fut', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Planned' }),
      taskRow('futDet', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', recurrenceDetached: true }),
      taskRow('futDone', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Done' }),
      taskRow('pastP', { recurrenceRuleId: 'R', plannedDate: '2000-01-01', status: 'Planned' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.deleteSeriesFuture('R', '2026-06-11');
    expect(await store.tasks.get('fut')).toBeUndefined();
    expect(await store.tasks.get('futDet')).toBeUndefined(); // detached included (§6)
    expect(await store.tasks.get('futDone')).toBeTruthy(); // done kept
    expect(await store.tasks.get('tmpl')).toBeTruthy(); // template kept
    expect(await store.tasks.get('pastP')).toBeTruthy(); // past kept
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('POST');
    expect(op.path).toBe('/api/v1/recurrence/rules/R/delete-future');
    expect(op.body).toEqual({ fromDate: '2026-06-11' });
  });
});

describe('LocalDataClient.recurrence.updateRule', () => {
  it('updates the rule row + removes future non-detached not-done instances; PATCH rules/{id} regenAfterPush', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R', { interval: 1 }));
    await store.tasks.bulkPut([
      taskRow('tmpl', { recurrenceRuleId: 'R', isRecurrenceTemplate: true, plannedDate: '2999-01-01' }),
      taskRow('fut', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Planned' }),
      taskRow('futDet', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', recurrenceDetached: true }),
      taskRow('futDone', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Done' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.updateRule('R', { ...dailyRule, interval: 3 });
    expect(Number((await store.recurrenceRules.get('R'))!.interval)).toBe(3);
    expect(await store.tasks.get('fut')).toBeUndefined();
    expect(await store.tasks.get('futDet')).toBeTruthy(); // detached kept (non-detached filter)
    expect(await store.tasks.get('futDone')).toBeTruthy(); // done kept
    expect(await store.tasks.get('tmpl')).toBeTruthy();
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/recurrence/rules/R');
    expect(op.regenAfterPush).toBe(true);
    expect(op.body).toMatchObject({ frequency: 'Daily', interval: 3 });
  });
});

describe('LocalDataClient.recurrence.detachInstance + getRule', () => {
  it('detachInstance sets recurrenceDetached + queues PATCH detach', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('i1', { recurrenceRuleId: 'R', recurrenceDetached: false }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.detachInstance('i1');
    expect((await store.tasks.get('i1'))!.recurrenceDetached).toBe(true);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/recurrence/instances/i1/detach');
    expect(op.entityKeys).toEqual(['tasks:i1']);
  });

  it('getRule reads the local rule or null', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R', { frequency: 'Weekly', interval: 2 }));
    const dc = new LocalDataClient(store, fakeBridge());
    const rule = await dc.recurrence.getRule('R');
    expect(rule!.frequency).toBe('weekly');
    expect(rule!.interval).toBe(2);
    expect(await dc.recurrence.getRule('absent')).toBeNull();
  });
});

describe('LocalDataClient.recurrence.ensureInstances (read-through)', () => {
  it('offline returns [] and writes nothing', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge({ online: false }));
    expect(await dc.recurrence.ensureInstances('2026-06-11', '2026-06-25')).toEqual([]);
    expect(await store.tasks.count()).toBe(0);
  });

  it('online upserts returned rows locally and returns them mapped', async () => {
    const store = openStore();
    const returned = [taskRow('gen1', { rank: 'a0' }), taskRow('gen2', { rank: 'a0' })];
    const bridge = fakeBridge({
      online: true,
      ensureInstances: async () => returned,
    });
    const dc = new LocalDataClient(store, bridge);
    const got = await dc.recurrence.ensureInstances('2026-06-11', '2026-06-25');
    expect(got.map((t) => t.id).sort()).toEqual(['gen1', 'gen2']);
    expect(await store.tasks.get('gen1')).toBeTruthy();
    expect(await store.tasks.get('gen2')).toBeTruthy();
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/recurrence.test.ts
```

Expected failure: `dc.recurrence.create is not a function` (the `recurrence` group is still the `!` placeholder).

- [ ] **Minimal impl** — add `toRecurrenceRule`, `toServerFrequency`, `toServerEndType` to the `mappers` import block:

```ts
import {
  toTask,
  toSubtask,
  toProject,
  toNoteGroup,
  toNote,
  toDailyPlan,
  toFocusSession,
  toRecurrenceRule,
  toServerFrequency,
  toServerEndType,
  parseSettings,
  toServerStatus,
} from '../mappers';
```

Add the `RecurrenceRuleSyncRow` type import to the `rows` import block:

```ts
import type {
  TaskSyncRow,
  SubtaskSyncRow,
  ProjectSyncRow,
  NoteGroupSyncRow,
  NoteSyncRow,
  RecurrenceRuleSyncRow,
} from './rows';
```

Add a `todayIso()` module helper next to `nowIso()`:

```ts
/** Today's date as YYYY-MM-DD in UTC (mirrors the server's DateOnly.FromDateTime(DateTime.UtcNow)). */
function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}
```

Replace the `recurrence!: DataClient['recurrence'];` placeholder with the implemented group:

```ts
  // ── recurrence ─────────────────────────────────────────────
  recurrence = {
    create: async (task: Partial<Task>, rule: RecurrenceRuleInput): Promise<Task[]> => {
      const taskId = task.id ?? newId();
      const ruleId = newId();
      const startDate = task.plannedDate ?? todayIso();
      const ruleRowVal: RecurrenceRuleSyncRow = {
        id: ruleId,
        frequency: toServerFrequency(rule.frequency),
        interval: rule.interval < 1 ? 1 : rule.interval,
        daysOfWeek: rule.daysOfWeek,
        endType: toServerEndType(rule.endType),
        endCount: rule.endType === 'count' ? (rule.endCount ?? null) : null,
        endDate: rule.endType === 'date' ? (rule.endDate ?? null) : null,
        generatedUntil: null,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const templateRow: TaskSyncRow = {
        id: taskId,
        title: task.title ?? '',
        notes: task.notes ?? '',
        projectId: task.projectId ?? null,
        labels: task.labels ?? [],
        source: task.source ?? 'manual',
        status: 'Planned',
        plannedDate: startDate,
        scheduledStart: null,
        scheduledEnd: null,
        durationMinutes: task.durationMinutes && task.durationMinutes > 0 ? task.durationMinutes : 30,
        actualTimeMinutes: 0,
        priority: task.priority ?? null,
        reminderMinutes: task.reminderMinutes ?? 0,
        rank: 'a0',
        dueDate: null,
        recurrenceRuleId: ruleId,
        isRecurrenceTemplate: true,
        recurrenceDetached: false,
        recurrenceOriginalDate: startDate,
        completedAt: null,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      // Wire body mirrors HttpDataClient.recurrence.create + the C7.4 rule id touch-up.
      const body = {
        task: {
          title: task.title ?? '',
          notes: task.notes ?? '',
          projectId: task.projectId ?? null,
          labels: task.labels ?? [],
          source: task.source ?? 'manual',
          plannedDate: task.plannedDate ?? null,
          durationMinutes: task.durationMinutes ?? 30,
          priority: task.priority ?? null,
          reminderMinutes: task.reminderMinutes ?? null,
          id: taskId,
        },
        rule: {
          frequency: toServerFrequency(rule.frequency),
          interval: rule.interval,
          daysOfWeek: rule.daysOfWeek,
          endType: toServerEndType(rule.endType),
          endCount: rule.endCount ?? null,
          endDate: rule.endDate ?? null,
          id: ruleId,
        },
      };
      await this.writeTx([this.store.tasks, this.store.recurrenceRules], async () => {
        await this.store.recurrenceRules.add(ruleRowVal);
        await this.store.tasks.add(templateRow);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/recurrence',
          body,
          entityKeys: [entityKey('tasks', taskId), entityKey('recurrenceRules', ruleId)],
          kind: 'create',
          regenAfterPush: true,
          attempts: 0,
        });
      });
      // §6 store contract (store.ts:667): return the full local task list.
      return this.tasks.getAll();
    },

    updateSeries: async (ruleId: string, u: Partial<Task>): Promise<void> => {
      const today = todayIso();
      const all = await this.store.tasks.where('recurrenceRuleId').equals(ruleId).toArray();
      const targets = all.filter(
        (t) =>
          t.isRecurrenceTemplate ||
          (!t.recurrenceDetached &&
            t.status !== 'Done' &&
            t.plannedDate != null &&
            t.plannedDate >= today),
      );
      // Apply the same fields the server's UpdateSeries propagates (non-null only).
      const body = {
        title: u.title ?? null,
        notes: u.notes ?? null,
        projectId: u.projectId === undefined ? null : u.projectId,
        labels: u.labels ?? null,
        durationMinutes: u.durationMinutes ?? null,
        priority: u.priority ?? null,
        reminderMinutes: u.reminderMinutes ?? null,
      };
      await this.writeTx([this.store.tasks], async () => {
        for (const t of targets) {
          const next = { ...t };
          if (u.title !== undefined) next.title = u.title;
          if (u.notes !== undefined) next.notes = u.notes;
          if (u.projectId !== undefined) next.projectId = u.projectId;
          if (u.labels !== undefined) next.labels = u.labels;
          if (u.durationMinutes !== undefined) next.durationMinutes = u.durationMinutes;
          if (u.priority !== undefined) next.priority = u.priority;
          if (u.reminderMinutes !== undefined) next.reminderMinutes = u.reminderMinutes;
          next.updatedAt = nowIso();
          await this.store.tasks.put(next);
        }
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/recurrence/rules/${ruleId}/series`,
          body,
          entityKeys: targets.map((t) => entityKey('tasks', t.id)),
          kind: 'other',
          attempts: 0,
        });
      });
    },

    deleteSeries: async (ruleId: string): Promise<void> => {
      const tasks = await this.store.tasks.where('recurrenceRuleId').equals(ruleId).toArray();
      const keys = [
        entityKey('recurrenceRules', ruleId),
        ...tasks.map((t) => entityKey('tasks', t.id)),
      ];
      await this.writeTx([this.store.recurrenceRules, this.store.tasks], async () => {
        await this.store.recurrenceRules.delete(ruleId);
        await this.store.tasks.where('recurrenceRuleId').equals(ruleId).delete();
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/recurrence/rules/${ruleId}`,
          entityKeys: keys,
          kind: 'other',
          attempts: 0,
        });
      });
    },

    deleteSeriesFuture: async (ruleId: string, from: string): Promise<void> => {
      const all = await this.store.tasks.where('recurrenceRuleId').equals(ruleId).toArray();
      // §6 pinned: future, not-done, NON-template instances — detached included.
      const victims = all.filter(
        (t) =>
          !t.isRecurrenceTemplate &&
          t.status !== 'Done' &&
          t.plannedDate != null &&
          t.plannedDate >= from,
      );
      await this.writeTx([this.store.tasks], async () => {
        for (const t of victims) await this.store.tasks.delete(t.id);
        await this.store.ops.add({
          method: 'POST',
          path: `/api/v1/recurrence/rules/${ruleId}/delete-future`,
          body: { fromDate: from },
          entityKeys: victims.map((t) => entityKey('tasks', t.id)),
          kind: 'other',
          attempts: 0,
        });
      });
    },

    updateRule: async (ruleId: string, u: RecurrenceRuleInput): Promise<void> => {
      const today = todayIso();
      const rule = await this.store.recurrenceRules.get(ruleId);
      const all = await this.store.tasks.where('recurrenceRuleId').equals(ruleId).toArray();
      // Mirror server UpdateRule: tombstone future, non-detached, not-done, non-template instances.
      const victims = all.filter(
        (t) =>
          !t.isRecurrenceTemplate &&
          !t.recurrenceDetached &&
          t.status !== 'Done' &&
          t.plannedDate != null &&
          t.plannedDate >= today,
      );
      const body = {
        frequency: toServerFrequency(u.frequency),
        interval: u.interval,
        daysOfWeek: u.daysOfWeek,
        endType: toServerEndType(u.endType),
        endCount: u.endCount ?? null,
        endDate: u.endDate ?? null,
      };
      await this.writeTx([this.store.recurrenceRules, this.store.tasks], async () => {
        if (rule) {
          await this.store.recurrenceRules.put({
            ...rule,
            frequency: toServerFrequency(u.frequency),
            interval: u.interval,
            daysOfWeek: u.daysOfWeek,
            endType: toServerEndType(u.endType),
            endCount: u.endType === 'count' ? (u.endCount ?? null) : null,
            endDate: u.endType === 'date' ? (u.endDate ?? null) : null,
            generatedUntil: null,
            updatedAt: nowIso(),
          });
        }
        for (const t of victims) await this.store.tasks.delete(t.id);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/recurrence/rules/${ruleId}`,
          body,
          entityKeys: [
            entityKey('recurrenceRules', ruleId),
            ...victims.map((t) => entityKey('tasks', t.id)),
          ],
          kind: 'other',
          regenAfterPush: true,
          attempts: 0,
        });
      });
    },

    detachInstance: async (taskId: string): Promise<void> => {
      const row = await this.store.tasks.get(taskId);
      await this.writeTx([this.store.tasks], async () => {
        if (row) await this.store.tasks.put({ ...row, recurrenceDetached: true, updatedAt: nowIso() });
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/recurrence/instances/${taskId}/detach`,
          entityKeys: [entityKey('tasks', taskId)],
          kind: 'other',
          attempts: 0,
        });
      });
    },

    ensureInstances: async (start: string, end: string): Promise<Task[]> => {
      if (!this.bridge.online()) return [];
      const rows = await this.bridge.ensureInstances(start, end);
      if (rows.length > 0) {
        await this.store.tasks.bulkPut(rows);
      }
      const subsByTask = new Map<string, SubtaskSyncRow[]>();
      // Generated instances carry no subtasks; map each row with an empty subtask list.
      return rows.map((r) => this.mapTask(r, subsByTask.get(r.id) ?? []));
    },

    getRule: async (ruleId: string): Promise<RecurrenceRule | null> => {
      const row = await this.store.recurrenceRules.get(ruleId);
      return row ? toRecurrenceRule(row as never) : null;
    },
  };
```

> `ensureInstances` upserts the returned `TaskSyncRow`s with `bulkPut` outside `writeTx` (it is a read-through that does not enqueue an op — the server already generated them; spec §6/C4). `bridge.ensureInstances` throws when offline, but `online()` guards it. The `subsByTask` map is always empty here (fresh instances have no subtasks) — kept for symmetry with `mapTask`'s signature.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/recurrence.test.ts
```

Expected: 1 file passed, 9 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/__tests__/recurrence.test.ts
git commit -m "feat(app): LocalDataClient recurrence group with local approximations (R2-8)

create inserts template task (client id, rank 'a0') + rule (client id) and queues
POST /recurrence (both ids, regenAfterPush) returning the full local task list
(store.ts:667 contract); updateSeries/deleteSeries/deleteSeriesFuture/updateRule/
detachInstance each queue the wire op and apply the server-mirroring local effect
(delete-future includes detached, updateRule keeps detached, §6); getRule reads
local; ensureInstances is read-through (online → server + local upsert, offline → []).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R2-9: localReports.ts — buildReportData + reports.getData delegation

**Files:**
- Modify: `packages/app/src/data/local/localReports.ts`
- Modify: `packages/app/src/data/local/LocalDataClient.ts`
- Test: `packages/app/src/data/local/__tests__/localReports.test.ts`

Per spec §6, `buildReportData(rows, start, end)` builds the `ReportData` projection (mirroring `ReportsEndpoints.GetData`): `completedTasks` from task rows with non-null `completedAt`, day-bucketed into the device's **local** timezone (known v1 divergence from server account-tz, spec §6), filtered to `[start, end]`, with the project name resolved from local projects (`''` when no project); `sessions` from focus rows filtered by `date ∈ [start, end]` (the `date` is already the local day); `dailyPlans` from plan rows filtered by `date ∈ [start, end]`. `LocalDataClient.reports.getData` reads the four tables and delegates. The `ReportData` shape matches `types.ts:117-121` (so `lib/reports.ts` aggregates it unchanged).

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/localReports.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { buildReportData } from '../localReports';
import { LocalDataClient } from '../LocalDataClient';
import {
  openStore,
  closeAll,
  fakeBridge,
  taskRow,
  projectRow,
  focusRow,
  planRow,
} from './helpers';

afterEach(closeAll);

// CompletedAt at local-noon avoids any tz boundary ambiguity in the date bucket.
function completed(id: string, date: string, projectId: string | null): ReturnType<typeof taskRow> {
  return taskRow(id, { completedAt: `${date}T12:00:00`, projectId });
}

describe('buildReportData', () => {
  it('buckets completedAt into local days, resolves project names, filters to range', () => {
    const projects = [projectRow('P', { name: 'Work' })];
    const tasks = [
      completed('t1', '2026-06-11', 'P'),
      completed('t2', '2026-06-12', null),
      completed('t3', '2026-06-20', 'P'), // out of range
      taskRow('t4', { completedAt: null }), // never completed
    ];
    const data = buildReportData(
      { tasks, projects, focusSessions: [], dailyPlans: [] },
      '2026-06-11',
      '2026-06-15',
    );
    expect(data.completedTasks).toEqual([
      { id: 't1', project: 'Work', date: '2026-06-11' },
      { id: 't2', project: '', date: '2026-06-12' },
    ]);
  });

  it('filters sessions and dailyPlans by date range; sessions carry name + minutes', () => {
    const sessions = [
      focusRow('f1', { project: 'Work', minutes: 30, date: '2026-06-11' }),
      focusRow('f2', { project: '', minutes: 10, date: '2026-06-20' }), // out
    ];
    const plans = [
      planRow('2026-06-11', { plannedTaskIds: ['t1'], plannedMinutes: 60 }),
      planRow('2026-06-20', { plannedMinutes: 5 }), // out
    ];
    const data = buildReportData(
      { tasks: [], projects: [], focusSessions: sessions, dailyPlans: plans },
      '2026-06-11',
      '2026-06-15',
    );
    expect(data.sessions).toEqual([{ project: 'Work', minutes: 30, date: '2026-06-11' }]);
    expect(data.dailyPlans).toEqual([
      { date: '2026-06-11', committedAt: '2026-06-11T08:00:00Z', plannedTaskIds: ['t1'], plannedMinutes: 60 },
    ]);
  });

  it('includes boundary dates (inclusive start and end)', () => {
    const sessions = [
      focusRow('f1', { minutes: 1, date: '2026-06-11' }), // == start
      focusRow('f2', { minutes: 2, date: '2026-06-15' }), // == end
    ];
    const data = buildReportData(
      { tasks: [], projects: [], focusSessions: sessions, dailyPlans: [] },
      '2026-06-11',
      '2026-06-15',
    );
    expect(data.sessions.map((s) => s.date)).toEqual(['2026-06-11', '2026-06-15']);
  });
});

describe('LocalDataClient.reports.getData', () => {
  it('reads the local tables and delegates to buildReportData', async () => {
    const store = openStore();
    await store.projects.put(projectRow('P', { name: 'Work' }));
    await store.tasks.put(taskRow('t1', { completedAt: '2026-06-11T12:00:00', projectId: 'P' }));
    await store.focusSessions.put(focusRow('f1', { project: 'Work', minutes: 25, date: '2026-06-11' }));
    await store.dailyPlans.put(planRow('2026-06-11', { plannedTaskIds: ['t1'], plannedMinutes: 30 }));
    const dc = new LocalDataClient(store, fakeBridge());
    const data = await dc.reports.getData('2026-06-11', '2026-06-15');
    expect(data.completedTasks).toEqual([{ id: 't1', project: 'Work', date: '2026-06-11' }]);
    expect(data.sessions).toEqual([{ project: 'Work', minutes: 25, date: '2026-06-11' }]);
    expect(data.dailyPlans[0].plannedTaskIds).toEqual(['t1']);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/localReports.test.ts
```

Expected failure: the `buildReportData` stub returns empty arrays, so `expect(data.completedTasks).toEqual([…])` fails with `expected [] to deeply equal [ { id: 't1', … } ]`; and `dc.reports.getData is not a function` (the `reports` group is still the `!` placeholder).

- [ ] **Minimal impl** — replace the entire stub `packages/app/src/data/local/localReports.ts` with the real builder:

```ts
/**
 * localReports.ts — the offline ReportData projection (spec §6, contract C4).
 *
 * Mirrors the server Reports slice (ReportsEndpoints.GetData): completedTasks from
 * task rows with a non-null completedAt, day-bucketed into the DEVICE's LOCAL timezone
 * (known v1 divergence from the server's account timeZoneId, spec §6), filtered to the
 * [start, end] inclusive range, project name resolved from local projects ('' when none);
 * sessions + dailyPlans filtered by their already-local `date`. The output shape matches
 * types.ts ReportData, so lib/reports.ts aggregates it unchanged.
 */

import type { ReportData } from '../../types';
import type {
  TaskSyncRow,
  FocusSessionSyncRow,
  DailyPlanSyncRow,
  ProjectSyncRow,
} from './rows';

export interface ReportRows {
  tasks: TaskSyncRow[];
  focusSessions: FocusSessionSyncRow[];
  dailyPlans: DailyPlanSyncRow[];
  projects: ProjectSyncRow[];
}

/** YYYY-MM-DD of an ISO timestamp in the device's LOCAL timezone. */
function localDay(iso: string): string {
  const d = new Date(iso);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function buildReportData(rows: ReportRows, start: string, end: string): ReportData {
  const projectName = new Map(rows.projects.map((p) => [p.id, p.name]));

  const completedTasks = rows.tasks
    .filter((t) => t.completedAt != null)
    .map((t) => ({
      id: t.id,
      project: t.projectId != null ? (projectName.get(t.projectId) ?? '') : '',
      date: localDay(t.completedAt as string),
    }))
    .filter((t) => t.date >= start && t.date <= end);

  const sessions = rows.focusSessions
    .filter((f) => f.date >= start && f.date <= end)
    .map((f) => ({ project: f.project ?? '', minutes: Number(f.minutes), date: f.date }));

  const dailyPlans = rows.dailyPlans
    .filter((p) => p.date >= start && p.date <= end)
    .map((p) => ({
      date: p.date,
      committedAt: p.committedAt,
      plannedTaskIds: p.plannedTaskIds,
      plannedMinutes: Number(p.plannedMinutes),
    }));

  return { completedTasks, sessions, dailyPlans };
}
```

Then in `LocalDataClient.ts`, add the `buildReportData` import (top, near the other local imports):

```ts
import { buildReportData } from './localReports';
```

And replace the `reports!: DataClient['reports'];` placeholder with the implemented group:

```ts
  // ── reports ────────────────────────────────────────────────
  reports = {
    getData: async (start: string, end: string): Promise<ReportData> => {
      const [tasks, focusSessions, dailyPlans, projects] = await Promise.all([
        this.store.tasks.toArray(),
        this.store.focusSessions.toArray(),
        this.store.dailyPlans.toArray(),
        this.store.projects.toArray(),
      ]);
      return buildReportData({ tasks, focusSessions, dailyPlans, projects }, start, end);
    },
  };
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/localReports.test.ts
```

Expected: 1 file passed, 4 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/data/local/localReports.ts packages/app/src/data/local/LocalDataClient.ts packages/app/src/data/local/__tests__/localReports.test.ts
git commit -m "feat(app): localReports buildReportData + reports.getData delegation (R2-9)

buildReportData mirrors the server Reports slice: completedTasks day-bucketed into
the device LOCAL tz (known v1 divergence, §6), project names from local projects
('' when none), sessions + dailyPlans filtered by their local date; reports.getData
reads the four tables and delegates. Output matches types.ts ReportData so
lib/reports.ts aggregates it unchanged.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R2-10: Phase R2 green gate

**Files:** none (verification only — if a check fails, fix forward with its own TDD loop before passing the gate).

- [ ] **Run the full R2 suite** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/reads.test.ts src/data/local/__tests__/creates.test.ts src/data/local/__tests__/updates.test.ts src/data/local/__tests__/deletes.test.ts src/data/local/__tests__/reorder.test.ts src/data/local/__tests__/misc.test.ts src/data/local/__tests__/recurrence.test.ts src/data/local/__tests__/localReports.test.ts
```

Expected: **8 files passed, 53 tests passed** (reads 9 + creates 7 + updates 10 + deletes 5 + reorder 3 + misc 6 + recurrence 9 + localReports 4). Zero failures, zero skips.

- [ ] **Run the full client suite** — from the repo root:

```bash
npm run test:app
```

Expected: every file green — the 16 files / 127 tests at R1 exit (plus any R0 delta) **plus the 8 new R2 test files / 53 tests**. The gate condition is **zero failures, zero skips**, and `LocalDataClient.ts` + `localReports.ts` present and exercised. `HttpDataClient.test.ts` is still present and green (it is not deleted until R6).

- [ ] **Run the app typecheck** — from the repo root (proves every group is implemented — no surviving `!`-asserted placeholder field or `not implemented` thrower remains uncovered, and every wire body typechecks against the row/DTO shapes under strict mode):

```bash
npm run typecheck --workspace @tmap/app
```

Expected: `tsc --noEmit` exits 0. If it reports an unused import (e.g. a `to*` mapper or `SyncOp` type that no implemented method references), remove that single import and re-run — the implementation set is complete at this point, so any unused import is dead and safe to drop.

- [ ] **Run both app builds** — from the repo root:

```bash
npm run build:apps
```

Expected: desktop (`tmap`) and web (`@tmap/web`) builds both succeed — `LocalDataClient` + `localReports` bundle cleanly into both shells.

- [ ] **Gate** — backend tests are not run here: R2 touches no backend file (R0 already gated the backend slice).

**HARD GATE: do not start Phase R3 until all four commands above are green. If any fails, fix forward within R2 (test-first for behavior fixes — every LocalDataClient method already has a dedicated test file to extend), re-run the full gate from the top, and only then proceed.** `LocalDataClient` now fully implements `DataClient` over Dexie with diff-at-enqueue, local rank authority + tie repair, server-mirroring cascades, and the offline report projection — R3 builds the `SyncEngine` that drains the `ops` queue this phase fills.

## Phase R3 — SyncEngine push side (replay + failure taxonomy + triggers + transport)

This phase builds the **push half** of the SP3 sync cycle: the pinned constants (C1), a connectivity adapter, the in-memory **`fakeSyncServer` test harness** that R4 + R6 reuse, the production **`HttpSyncTransport`** over the refresh-wrapped client (C5), and the **`SyncEngine`** skeleton (C5) with its full push-phase failure taxonomy (spec §3.3): FIFO drain, network abort, the 5xx in-cycle retry/backoff/parking ladder, definitive-rejection drop + ghost-row recovery scheduling, 409 adopt-existing id rewrite (§3.3/§3.4), the post-push ensure-instances trigger (§4.3), and 401 terminal classification (§7.3 — the engine's classification half; the `refreshClient` change itself is R5). The **pull phase is a no-op stub** here (`pullPhase()`), structured so R4 fills it in without reshaping the cycle. Files this phase creates/modifies/tests:

**Created:**
- `packages/app/src/sync/constants.ts` — contract C1 pinned constants
- `packages/app/src/sync/connectivity.ts` — `createConnectivity()` over `navigator.onLine` + window `online`/`offline` events, safe when `window`/`navigator` are absent (node tests)
- `packages/app/src/sync/__tests__/connectivity.test.ts`
- `packages/app/src/sync/__tests__/fakeSyncServer.ts` — **shared test harness** (NOT production): in-memory `SyncTransport` implementation + server tables + global `changeSeq` + REST semantics + failure/latency injection (reused by R4 + R6)
- `packages/app/src/sync/__tests__/fakeSyncServer.test.ts` — proves the harness semantics R3+ rely on
- `packages/app/src/sync/SyncTransport.ts` — `SyncTransport` interface (C5) + `HttpSyncTransport` production impl over the refresh-wrapped `MinimalClient`
- `packages/app/src/sync/__tests__/SyncTransport.test.ts`
- `packages/app/src/sync/SyncEngine.ts` — contract C5 engine (push side; `pullPhase()` no-op stub for R4)
- `packages/app/src/sync/__tests__/SyncEngine.push.test.ts`

**Modified:** none (everything additive; R1's `types.ts`/`LocalStore.ts`/`rows.ts` and R2's `LocalDataClient.ts` are imported, never edited).

**Preconditions:** R1 (`sync/types.ts`, `data/local/LocalStore.ts`, `data/local/rows.ts`) and R2 (`data/local/LocalDataClient.ts`, `SyncBridge`) are merged on this branch. The `SyncTransport` interface C5 references `SyncResponse`/`TaskSyncRow` — re-exported from `data/local/rows.ts` (R1 task R1-3). `fake-indexeddb` + `dexie` are installed (R1 task R1-1).

> **Contract-gap note (transport mechanism, R3-4).** Contract C5 types `SyncTransport.send/pull/ensureInstances` but does not pin *how* `HttpSyncTransport` reaches the API. The phase brief asks to "pick the simplest correct mechanism … likely keeping a baseUrl+fetch wrapper sharing the same middleware." After reading `packages/api-client/src/client.ts` (openapi-fetch needs path *templates* + `params.path`) and `packages/app/src/auth/refreshClient.ts` (its `MinimalClient` passes `(path, init)` straight through to the wrapped client), the chosen mechanism is: **`HttpSyncTransport` calls the refresh-wrapped `MinimalClient`'s `GET/POST/PATCH/PUT/DELETE` with the op's already-concrete `path` and `{ body }`** — query strings for `pull`/`ensureInstances` are baked into the path string. openapi-fetch fetches a concrete path literally when no `params` are supplied, so no template substitution is needed and the refresh/abort/Bearer middleware is shared automatically (one refresh path for all replayed ops — the SP2 invariant). This is pinned in code in R3-4. No contract conflict: C5's `SyncTransport` shape is honored exactly.

> **Contract-gap note (engine deps, R3-5).** C5 types the constructor as `new SyncEngine({ store, transport })` but the engine also needs the **userId** for the `navigator.locks` lock name (`'tmap-sync-' + userId`) and a **connectivity** source for the `online`/`offline` triggers (§3.4). Both are additive optional deps with safe fallbacks: `userId` defaults to the store's DB-name suffix when omitted; `connectivity` defaults to `createConnectivity()`. The two-arg `{ store, transport }` form from C5/R6 keeps working unchanged.

---

### Task R3-1: `src/sync/constants.ts` — pinned constants (C1)

**Files:**
- Create: `packages/app/src/sync/constants.ts`

Pure-mechanical (no TDD): contract C1 fixes these eight values verbatim. They have a single home so the 5000-seq overlap, debounce, backoff ladder, and park threshold live in exactly one place (spec "Pinned constants" table). The honest gate is `tsc --noEmit` resolving the imports in later tasks; a standalone test would only re-assert literals, so a Do/Verify/Commit cadence is used per the C11 mechanical-work allowance.

- [ ] **Do** — create `packages/app/src/sync/constants.ts`:

```ts
/**
 * SP3 sync constants — cross-phase contract C1. Single source of truth for the
 * spec's "Pinned constants" table; every value lives here and nowhere else.
 */

/** Pull page size (GET /api/v1/sync?limit=) — spec §4. */
export const PULL_LIMIT = 500;

/** Cursor overlap per cycle: first page requests since = max(0, cursor − this) — spec §4.1. */
export const CURSOR_OVERLAP = 5000;

/** Post-write push debounce (ms) — spec §3.4(c). */
export const PUSH_DEBOUNCE_MS = 2000;

/** Periodic sync interval while online (ms) — spec §3.4(d). */
export const PERIODIC_SYNC_MS = 60_000;

/** 5xx retries within a single cycle before aborting the push phase — spec §3.3. */
export const CYCLE_5XX_RETRIES = 3;

/** Backoff (ms) before the 1st/2nd/3rd in-cycle 5xx retry — spec §3.3 (1 s / 2 s / 4 s). */
export const CYCLE_5XX_BACKOFF_MS = [1000, 2000, 4000];

/** Total attempts across cycles after which a poison op is parked — spec §3.3. */
export const PARK_THRESHOLD = 10;

/** Standing horizon (days) for the post-push ensure-instances call — spec §4.3. */
export const ENSURE_HORIZON_DAYS = 14;
```

- [ ] **Verify** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: `tsc --noEmit` exits 0 (the module compiles under strict mode; the array literal's element type is `number[]`).

- [ ] **Commit**

```bash
git add packages/app/src/sync/constants.ts
git commit -m "feat(sync): pinned SP3 constants (C1)

Single home for the spec Pinned-constants table: PULL_LIMIT,
CURSOR_OVERLAP (5000), PUSH_DEBOUNCE_MS, PERIODIC_SYNC_MS,
CYCLE_5XX_RETRIES + CYCLE_5XX_BACKOFF_MS, PARK_THRESHOLD,
ENSURE_HORIZON_DAYS.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-2: `src/sync/connectivity.ts` — `navigator.onLine` + online/offline events

**Files:**
- Create: `packages/app/src/sync/connectivity.ts`
- Test: `packages/app/src/sync/__tests__/connectivity.test.ts`

The engine's trigger (b) — the `online` connectivity event (§3.4) — and its `online()` status snapshot need a tiny adapter over `navigator.onLine` + the window `online`/`offline` events. It MUST be safe in the vitest `environment: 'node'` (`packages/app/vitest.config.ts:13`), where neither `window` nor `navigator` exists: `online()` returns `true` when `navigator` is absent (the offline-first store works regardless; "assume online" only affects whether the engine *tries* a cycle), and `subscribe` is a no-op unsubscribe when `window` is absent. The test injects a fake `window`/`navigator` pair to drive both branches deterministically.

- [ ] **Write failing test** — create `packages/app/src/sync/__tests__/connectivity.test.ts`:

```ts
import { describe, it, expect, vi } from 'vitest';
import { createConnectivity } from '../connectivity';

// A minimal fake of the two globals connectivity reads.
function fakeEnv(initialOnline: boolean) {
  const listeners: Record<string, Set<() => void>> = { online: new Set(), offline: new Set() };
  const nav = { onLine: initialOnline };
  const win = {
    addEventListener: (type: string, cb: () => void) => {
      (listeners[type] ??= new Set()).add(cb);
    },
    removeEventListener: (type: string, cb: () => void) => {
      listeners[type]?.delete(cb);
    },
  };
  function emit(type: 'online' | 'offline') {
    nav.onLine = type === 'online';
    for (const cb of listeners[type]) cb();
  }
  return { nav: nav as unknown as Navigator, win: win as unknown as Window, listeners, emit };
}

describe('createConnectivity', () => {
  it('reports navigator.onLine through online()', () => {
    const { nav, win } = fakeEnv(true);
    const c = createConnectivity({ nav, win });
    expect(c.online()).toBe(true);

    const { nav: nav2, win: win2 } = fakeEnv(false);
    const c2 = createConnectivity({ nav: nav2, win: win2 });
    expect(c2.online()).toBe(false);
  });

  it('fires the callback on online and offline events with the current state', () => {
    const env = fakeEnv(false);
    const c = createConnectivity({ nav: env.nav, win: env.win });
    const cb = vi.fn();
    c.subscribe(cb);

    env.emit('online');
    expect(cb).toHaveBeenLastCalledWith(true);
    env.emit('offline');
    expect(cb).toHaveBeenLastCalledWith(false);
    expect(cb).toHaveBeenCalledTimes(2);
  });

  it('unsubscribe removes both listeners (no further callbacks)', () => {
    const env = fakeEnv(true);
    const c = createConnectivity({ nav: env.nav, win: env.win });
    const cb = vi.fn();
    const unsub = c.subscribe(cb);
    unsub();
    env.emit('offline');
    env.emit('online');
    expect(cb).not.toHaveBeenCalled();
    expect(env.listeners.online.size).toBe(0);
    expect(env.listeners.offline.size).toBe(0);
  });

  it('is safe when navigator/window are absent (node): online() true, subscribe a no-op', () => {
    const c = createConnectivity({ nav: undefined, win: undefined });
    expect(c.online()).toBe(true); // assume online — only gates whether a cycle is attempted
    const cb = vi.fn();
    const unsub = c.subscribe(cb);
    expect(() => unsub()).not.toThrow();
    expect(cb).not.toHaveBeenCalled();
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/connectivity.test.ts
```

Expected failure: module-resolution error — `Error: Failed to resolve import "../connectivity" from "src/sync/__tests__/connectivity.test.ts". Does the file exist?`

- [ ] **Minimal impl** — create `packages/app/src/sync/connectivity.ts`:

```ts
/**
 * connectivity.ts — `navigator.onLine` + window `online`/`offline` events,
 * the source for the engine's online-event trigger (spec §3.4(b)) and the
 * `online` field of the status snapshot.
 *
 * Safe in non-DOM environments (vitest node, SSR): with no `navigator`,
 * `online()` returns true (offline-first reads never depend on this — it only
 * gates whether the engine *attempts* a cycle); with no `window`, `subscribe`
 * is a no-op. Production callers use the zero-arg form; tests inject fakes.
 */

export interface Connectivity {
  /** Current connectivity per `navigator.onLine` (true when navigator is absent). */
  online(): boolean;
  /** Subscribe to online/offline transitions; the callback receives the new state. Returns an unsubscribe. */
  subscribe(cb: (online: boolean) => void): () => void;
}

export interface ConnectivityDeps {
  nav?: Navigator;
  win?: Window;
}

function resolveNav(explicit?: Navigator | undefined): Navigator | undefined {
  if (explicit !== undefined) return explicit;
  return typeof navigator !== 'undefined' ? navigator : undefined;
}

function resolveWin(explicit?: Window | undefined): Window | undefined {
  if (explicit !== undefined) return explicit;
  return typeof window !== 'undefined' ? window : undefined;
}

export function createConnectivity(deps: ConnectivityDeps = {}): Connectivity {
  // Distinguish "not passed" (resolve from globals) from "passed undefined" (absent env).
  const nav = 'nav' in deps ? deps.nav : resolveNav();
  const win = 'win' in deps ? deps.win : resolveWin();

  return {
    online(): boolean {
      return nav ? nav.onLine : true;
    },
    subscribe(cb: (online: boolean) => void): () => void {
      if (!win) return () => {};
      const onOnline = () => cb(true);
      const onOffline = () => cb(false);
      win.addEventListener('online', onOnline);
      win.addEventListener('offline', onOffline);
      return () => {
        win.removeEventListener('online', onOnline);
        win.removeEventListener('offline', onOffline);
      };
    },
  };
}
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/connectivity.test.ts
```

Expected: 1 file passed, 4 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/sync/connectivity.ts packages/app/src/sync/__tests__/connectivity.test.ts
git commit -m "feat(sync): connectivity adapter (navigator.onLine + online/offline)

Source for the engine's online-event trigger (spec §3.4b) and the online
status field. Safe in node (no window/navigator): online() assumes true,
subscribe is a no-op. Tests inject a fake window/navigator pair.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-3: `src/sync/__tests__/fakeSyncServer.ts` — shared in-memory `SyncTransport` harness

**Files:**
- Create (test harness, NOT production): `packages/app/src/sync/__tests__/fakeSyncServer.ts`
- Test: `packages/app/src/sync/__tests__/fakeSyncServer.test.ts`

This is the scripted in-memory backend every later SP3 sync test runs against (the R6 convergence suite and R4's pull/recovery tests import it — see `.sp3-plan-parts/07-r6.md:19` for the surface it relies on). It is a **test harness**, not production code, but it lives under `__tests__/` and is NOT a `*.test.ts` file, so it is excluded from the vitest `include` glob (`src/**/*.test.ts`) yet **included in `tsc --noEmit`** (`packages/app/tsconfig.json` excludes only `*.test.ts`/`*.test.tsx`). It must therefore compile strict-clean.

Surface (the C5a contract — R4 + R6 use ONLY these): `new FakeSyncServer()`, `server.seed(table, row)`, `server.transport(): SyncTransport`, `server.apply(op)` (internal REST semantics, also used by `transport.send`), failure injection `server.failNext(matcher, fault)`, server-side mutators `server.tombstone(table, key)` (soft-delete + cascade, bumps `changeSeq`) and `server.bumpSeq(table, key, patch?)` (field change, bumps `changeSeq` — simulates "another device wrote"), the `server.pullCount()` assertion helper (number of `pull()` calls served), and `server.latency(ms)` (artificial per-call delay). There is no `rejectNext` — rejections are expressed via `failNext(matcher, { status, body })`. The transport applies an op by `method + path`:
- **POST creates** (`/api/v1/tasks`, `/projects`, `/note-groups`, `/notes`, `/focus-sessions`, subtasks): PK-idempotent (re-send of the same id → `200` + the existing row, no duplicate); project name collision → `409` ProblemDetails with `extensions.existingId`.
- **PATCH** `/{table}/{id}`: field-merge onto the live row (only the body's present fields), bumping `changeSeq`; missing/tombstoned target → `404`.
- **PATCH** `/{table}/reorder`: write each `{id, rank}`, bumping `changeSeq`.
- **DELETE** `/{table}/{id}`: tombstone (`deletedAt` set) + cascade (task delete tombstones its subtasks; note-group delete tombstones its notes; project delete clears `projectId` on children); already-missing → `404`.
- **PUT** `/daily-plans/{date}`: whole-day upsert, re-stamping `committedAt`. **PUT** `/settings`: per-key upsert of the body's `settings` map.
- **Recurrence:** `POST /recurrence` stores the rule + template task with **zero** instances and `generatedUntil = null`; `PATCH /recurrence/rules/{id}` (updateRule) resets `generatedUntil = null`; `ensureInstances(start, end)` materializes daily/weekly instance rows at rank `'a0'` up to the horizon and sets `generatedUntil`.
- **pull(since, limit):** merges all live + tombstoned rows with `changeSeq > since` across tables by global `changeSeq`, returns the first `limit` as a `SyncResponse` page with `nextSince` + `hasMore`.

- [ ] **Write failing test** — create `packages/app/src/sync/__tests__/fakeSyncServer.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { FakeSyncServer } from './fakeSyncServer';
import type { SyncOp } from '../types';

function op(o: Partial<SyncOp> & Pick<SyncOp, 'method' | 'path'>): SyncOp {
  return { entityKeys: [], kind: 'other', attempts: 0, ...o };
}

function taskBody(id: string, over: Record<string, unknown> = {}) {
  return {
    id, title: `t-${id}`, notes: '', projectId: null, labels: [], source: 'manual',
    status: 'Inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
    durationMinutes: 0, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
    rank: 'a0', dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
    createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z', ...over,
  };
}

describe('FakeSyncServer transport — REST semantics', () => {
  it('POST create is PK-idempotent: re-send returns 200 + existing row, no duplicate', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    const r1 = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    expect(r1.status).toBe(201);
    const r2 = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    expect(r2.status).toBe(200);
    const page = await t.pull(0, 500);
    expect(page.changes.tasks.filter((x) => x.id === 't1')).toHaveLength(1);
  });

  it('POST project with a duplicate name → 409 + extensions.existingId', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/projects',
      body: { id: 'p1', name: 'Inbox-Zero', color: '#fff', emoji: '📁', rank: 'a0' }, kind: 'create' }));
    const dup = await t.send(op({ method: 'POST', path: '/api/v1/projects',
      body: { id: 'p2', name: 'Inbox-Zero', color: '#000', emoji: '📦', rank: 'a1' }, kind: 'create' }));
    expect(dup.status).toBe(409);
    expect((dup.body as { extensions: { existingId: string } }).extensions.existingId).toBe('p1');
  });

  it('PATCH field-merges and bumps changeSeq; 404 on a missing target', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1', { title: 'orig' }), kind: 'create' }));
    const r = await t.send(op({ method: 'PATCH', path: '/api/v1/tasks/t1', body: { title: 'edited' } }));
    expect(r.status).toBe(200);
    const page = await t.pull(0, 500);
    const row = page.changes.tasks.find((x) => x.id === 't1')!;
    expect(row.title).toBe('edited');
    expect(row.changeSeq).toBeGreaterThan(1);

    const miss = await t.send(op({ method: 'PATCH', path: '/api/v1/tasks/absent', body: { title: 'x' } }));
    expect(miss.status).toBe(404);
  });

  it('DELETE tombstones + cascades to subtasks; pull includes the tombstones', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    await t.send(op({ method: 'POST', path: '/api/v1/tasks/t1/subtasks',
      body: { id: 's1', taskId: 't1', title: 'sub', completed: false, sortOrder: 0 }, kind: 'create' }));
    const del = await t.send(op({ method: 'DELETE', path: '/api/v1/tasks/t1' }));
    expect(del.status).toBe(204);
    const page = await t.pull(0, 500);
    expect(page.changes.tasks.find((x) => x.id === 't1')!.deletedAt).not.toBeNull();
    expect(page.changes.subtasks.find((x) => x.id === 's1')!.deletedAt).not.toBeNull();
    const reDel = await t.send(op({ method: 'DELETE', path: '/api/v1/tasks/t1' }));
    expect(reDel.status).toBe(404); // already tombstoned
  });

  it('reorder writes ranks; PUT daily-plan re-stamps committedAt; PUT settings is per-key', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1', { rank: 'a0' }), kind: 'create' }));
    await t.send(op({ method: 'PATCH', path: '/api/v1/tasks/reorder', body: [{ id: 't1', rank: 'b5' }] }));
    let page = await t.pull(0, 500);
    expect(page.changes.tasks.find((x) => x.id === 't1')!.rank).toBe('b5');

    await t.send(op({ method: 'PUT', path: '/api/v1/daily-plans/2026-06-11',
      body: { plannedTaskIds: ['t1'], plannedMinutes: 30 } }));
    page = await t.pull(0, 500);
    const plan = page.changes.dailyPlans.find((p) => p.date === '2026-06-11')!;
    expect(plan.plannedTaskIds).toEqual(['t1']);
    expect(plan.committedAt).toBeTruthy();

    await t.send(op({ method: 'PUT', path: '/api/v1/settings',
      body: { settings: { workStartHour: '6' } } }));
    page = await t.pull(0, 500);
    expect(page.changes.settings.find((x) => x.key === 'workStartHour')!.value).toBe('6');
  });

  it('recurrence: POST stores rule+template with zero instances; ensureInstances materializes them at a0', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/recurrence', kind: 'create', regenAfterPush: true,
      body: { task: taskBody('tmpl', { isRecurrenceTemplate: true, recurrenceRuleId: 'r1' }),
              rule: { id: 'r1', frequency: 'Daily', interval: 1, daysOfWeek: [], endType: 'Never',
                      endCount: null, endDate: null } } }));
    let page = await t.pull(0, 500);
    expect(page.changes.tasks.filter((x) => x.recurrenceRuleId === 'r1' && !x.isRecurrenceTemplate)).toHaveLength(0);
    expect(page.changes.recurrenceRules.find((x) => x.id === 'r1')).toBeDefined();

    const created = await t.ensureInstances('2026-06-01', '2026-06-14');
    expect(created.length).toBeGreaterThanOrEqual(7);
    expect(created.every((r) => r.rank === 'a0')).toBe(true);
    page = await t.pull(0, 500);
    expect(
      page.changes.tasks.filter((x) => x.recurrenceRuleId === 'r1' && !x.isRecurrenceTemplate).length,
    ).toBeGreaterThanOrEqual(7);
  });

  it('pull paginates by changeSeq with nextSince + hasMore; seed stamps the next seq', async () => {
    const s = new FakeSyncServer();
    s.seed('tasks', taskBody('a'));
    s.seed('tasks', taskBody('b'));
    s.seed('tasks', taskBody('c'));
    const t = s.transport();
    const p1 = await t.pull(0, 2);
    expect(p1.changes.tasks).toHaveLength(2);
    expect(p1.hasMore).toBe(true);
    const p2 = await t.pull(p1.nextSince, 2);
    expect(p2.changes.tasks).toHaveLength(1);
    expect(p2.hasMore).toBe(false);
  });

  it('failNext injects a status fault then clears; latency hook is awaited', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    s.failNext((o) => o.method === 'POST', { status: 503 });
    const r1 = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    expect(r1.status).toBe(503);
    const r2 = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    expect(r2.status).toBe(201); // fault consumed (next-only)

    s.failNext(() => true, 'network');
    await expect(t.send(op({ method: 'PATCH', path: '/api/v1/tasks/t1', body: { title: 'z' } }))).rejects.toThrow();
  });

  it('server mutators: tombstone soft-deletes + cascades, bumpSeq advances a row, pullCount + latency work', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    await t.send(op({ method: 'POST', path: '/api/v1/tasks/t1/subtasks',
      body: { id: 's1', taskId: 't1', title: 'sub', completed: false, sortOrder: 0 }, kind: 'create' }));

    // pullCount starts at 0, counts each served pull.
    expect(s.pullCount()).toBe(0);
    const before = await t.pull(0, 500);
    expect(s.pullCount()).toBe(1);
    const baseSeq = before.changes.tasks.find((x) => x.id === 't1')!.changeSeq;

    // bumpSeq advances the row's changeSeq (and applies the patch) without an op.
    s.bumpSeq('tasks', 't1', { title: 'remote edit' });
    const afterBump = await t.pull(0, 500);
    expect(s.pullCount()).toBe(2);
    const bumped = afterBump.changes.tasks.find((x) => x.id === 't1')!;
    expect(bumped.title).toBe('remote edit');
    expect(bumped.changeSeq).toBeGreaterThan(baseSeq);

    // tombstone soft-deletes the task and cascades to the subtask.
    s.tombstone('tasks', 't1');
    const afterTs = await t.pull(0, 500);
    expect(afterTs.changes.tasks.find((x) => x.id === 't1')!.deletedAt).not.toBeNull();
    expect(afterTs.changes.subtasks.find((x) => x.id === 's1')!.deletedAt).not.toBeNull();

    // latency(ms) makes calls take measurable time.
    s.latency(20);
    const t0 = Date.now();
    await t.pull(0, 500);
    expect(Date.now() - t0).toBeGreaterThanOrEqual(15);
    expect(s.pullCount()).toBe(4);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/fakeSyncServer.test.ts
```

Expected failure: module-resolution error — `Error: Failed to resolve import "./fakeSyncServer" from "src/sync/__tests__/fakeSyncServer.test.ts". Does the file exist?`

- [ ] **Minimal impl** — create `packages/app/src/sync/__tests__/fakeSyncServer.ts`:

```ts
/**
 * fakeSyncServer.ts — in-memory scripted backend for SP3 sync tests (HARNESS, not
 * production). Reused by R3 push, R4 pull/recovery, and R6 convergence (see
 * .sp3-plan-parts/07-r6.md). Lives under __tests__/ but is NOT a *.test.ts file,
 * so vitest does not run it as a suite, yet tsc --noEmit DOES compile it — keep it
 * strict-clean. Surface relied on elsewhere (C5a): new FakeSyncServer(), seed,
 * transport, apply, tombstone, bumpSeq, failNext, pullCount, latency. (No
 * rejectNext — rejections go through failNext(matcher, { status, body }).)
 *
 * Rows are stored in server sync-DTO wire shape (rows.ts): each table is a Map by
 * primary key; a global changeSeq counter stamps every write; tombstones set
 * deletedAt and keep the row (pull delivers tombstones). The op→effect mapping
 * mirrors the REST semantics the engine replays against (spec §3, §4.3).
 */

import type { SyncOp } from '../types';
import type { SyncResponse, TaskSyncRow } from '../../data/local/rows';

type Row = Record<string, unknown> & { id?: string; changeSeq: number; deletedAt: string | null };
type Fault = { status: number } | 'network';
type Matcher = (op: SyncOp) => boolean;

const PULL_TABLES = [
  'tasks', 'subtasks', 'projects', 'noteGroups', 'notes',
  'recurrenceRules', 'focusSessions', 'dailyPlans', 'settings',
] as const;
type PullTable = (typeof PULL_TABLES)[number];

interface Rule {
  id: string;
  frequency: string;
  interval: number;
  daysOfWeek: number[];
  endType: string;
  endDate: string | null;
  generatedUntil: string | null;
}

export class FakeSyncServer {
  private seq = 0;
  private tables: Record<PullTable, Map<string, Row>> = {
    tasks: new Map(), subtasks: new Map(), projects: new Map(), noteGroups: new Map(),
    notes: new Map(), recurrenceRules: new Map(), focusSessions: new Map(),
    dailyPlans: new Map(), settings: new Map(),
  };
  private rules = new Map<string, Rule>();
  private faults: { match: Matcher; fault: Fault }[] = [];
  private latencyMs = 0;
  private pulls = 0;

  private next(): number {
    return ++this.seq;
  }

  /** Awaited before every send/pull/ensureInstances (default no delay). */
  private async delay(): Promise<void> {
    if (this.latencyMs > 0) await new Promise((r) => setTimeout(r, this.latencyMs));
  }

  /** Set an artificial per-call delay (ms) for send/pull/ensureInstances (C5a). */
  latency(ms: number): void {
    this.latencyMs = ms;
  }

  /** Number of pull() calls served so far (assertion helper, C5a). */
  pullCount(): number {
    return this.pulls;
  }

  private keyOf(table: PullTable): string {
    if (table === 'dailyPlans') return 'date';
    if (table === 'settings') return 'key';
    return 'id';
  }

  /** Insert a wire-shaped row, stamping the next changeSeq (test setup helper). */
  seed(table: PullTable, row: Record<string, unknown>): void {
    const k = this.keyOf(table);
    const stamped: Row = { ...(row as object), changeSeq: this.next(), deletedAt: null } as Row;
    this.tables[table].set(String((stamped as Record<string, unknown>)[k]), stamped);
  }

  /** Queue a single-use fault matched against the next op that satisfies `match`. */
  failNext(match: Matcher, fault: Fault): void {
    this.faults.push({ match, fault });
  }

  /**
   * Server-side soft-delete (C5a): tombstone an existing row by key + cascade,
   * stamping a fresh changeSeq (simulates a remote device deleting the row).
   */
  tombstone(table: PullTable, key: string): void {
    const row = this.tables[table].get(key);
    if (!row || row.deletedAt !== null) return;
    const ts = new Date().toISOString();
    this.tables[table].set(key, { ...row, deletedAt: ts, changeSeq: this.next() });
    this.cascadeDelete(table, key, ts);
  }

  /**
   * Server-side field change (C5a): apply a patch (or a no-op touch) to an existing
   * row and stamp a fresh changeSeq — simulates "another device wrote" without an op.
   */
  bumpSeq(table: PullTable, key: string, patch: Record<string, unknown> = {}): void {
    const row = this.tables[table].get(key);
    if (!row) return;
    this.tables[table].set(key, { ...row, ...patch, changeSeq: this.next() });
  }

  private takeFault(op: SyncOp): Fault | null {
    const i = this.faults.findIndex((f) => f.match(op));
    if (i === -1) return null;
    const [{ fault }] = this.faults.splice(i, 1);
    return fault;
  }

  transport() {
    const self = this;
    return {
      async send(op: SyncOp): Promise<{ status: number; body?: unknown }> {
        await self.delay();
        const fault = self.takeFault(op);
        if (fault === 'network') throw Object.assign(new TypeError('Failed to fetch'), { name: 'TypeError' });
        if (fault) return { status: fault.status, body: { title: `HTTP ${fault.status}` } };
        return self.apply(op);
      },
      async pull(since: number, limit: number): Promise<SyncResponse> {
        await self.delay();
        self.pulls += 1;
        return self.pullPage(since, limit);
      },
      async ensureInstances(start: string, end: string): Promise<TaskSyncRow[]> {
        await self.delay();
        return self.ensure(start, end);
      },
    };
  }

  // ── op application ─────────────────────────────────────────
  /** Internal REST semantics for one op (C5a); also used by transport.send. */
  apply(op: SyncOp): { status: number; body?: unknown } {
    const { method, path } = op;
    const segs = path.replace(/^\/api\/v1\//, '').split('/');

    // POST /recurrence (series create)
    if (method === 'POST' && path === '/api/v1/recurrence') {
      const b = op.body as { task: Record<string, unknown>; rule: Record<string, unknown> };
      const rule = b.rule;
      const ruleId = String(rule.id);
      this.rules.set(ruleId, {
        id: ruleId,
        frequency: String(rule.frequency),
        interval: Number(rule.interval ?? 1),
        daysOfWeek: (rule.daysOfWeek as number[]) ?? [],
        endType: String(rule.endType),
        endDate: (rule.endDate as string | null) ?? null,
        generatedUntil: null,
      });
      this.tables.recurrenceRules.set(ruleId, {
        ...rule, id: ruleId, generatedUntil: null, changeSeq: this.next(), deletedAt: null,
      } as Row);
      const tid = String(b.task.id);
      const existed = this.tables.tasks.get(tid);
      if (existed && existed.deletedAt === null) return { status: 200, body: existed };
      this.tables.tasks.set(tid, { ...b.task, id: tid, changeSeq: this.next(), deletedAt: null } as Row);
      return { status: 201, body: this.tables.tasks.get(tid) };
    }

    // PATCH /recurrence/rules/{id} (updateRule → resets generatedUntil)
    if (method === 'PATCH' && segs[0] === 'recurrence' && segs[1] === 'rules' && segs.length === 3) {
      const ruleId = segs[2];
      const r = this.rules.get(ruleId);
      const row = this.tables.recurrenceRules.get(ruleId);
      if (!r || !row || row.deletedAt !== null) return { status: 404, body: { title: 'Not found' } };
      r.generatedUntil = null;
      this.tables.recurrenceRules.set(ruleId, {
        ...row, ...(op.body as object), generatedUntil: null, changeSeq: this.next(),
      } as Row);
      return { status: 200, body: this.tables.recurrenceRules.get(ruleId) };
    }

    // subtask create: POST /tasks/{taskId}/subtasks
    if (method === 'POST' && segs[0] === 'tasks' && segs[2] === 'subtasks') {
      return this.create('subtasks', op.body as Record<string, unknown>);
    }

    // reorder: PATCH /{table}/reorder
    if (method === 'PATCH' && segs.length === 2 && segs[1] === 'reorder') {
      const table = this.tableFromSeg(segs[0]);
      if (!table) return { status: 404, body: { title: 'Unknown table' } };
      for (const { id, rank } of op.body as { id: string; rank: string }[]) {
        const row = this.tables[table].get(id);
        if (row && row.deletedAt === null) {
          this.tables[table].set(id, { ...row, rank, changeSeq: this.next() });
        }
      }
      return { status: 200 };
    }

    // create: POST /{table}
    if (method === 'POST' && segs.length === 1) {
      const table = this.tableFromSeg(segs[0]);
      if (!table) return { status: 404, body: { title: 'Unknown table' } };
      if (table === 'projects') {
        const body = op.body as Record<string, unknown>;
        for (const ex of this.tables.projects.values()) {
          if (ex.deletedAt === null && ex.name === body.name && ex.id !== body.id) {
            return { status: 409, body: { title: 'Duplicate name', extensions: { existingId: ex.id } } };
          }
        }
      }
      return this.create(table, op.body as Record<string, unknown>);
    }

    // PUT /daily-plans/{date}
    if (method === 'PUT' && segs[0] === 'daily-plans' && segs.length === 2) {
      const date = segs[1];
      const body = op.body as { plannedTaskIds: string[]; plannedMinutes: number };
      this.tables.dailyPlans.set(date, {
        date, plannedTaskIds: body.plannedTaskIds, plannedMinutes: body.plannedMinutes,
        committedAt: new Date().toISOString(), changeSeq: this.next(), deletedAt: null,
      } as Row);
      return { status: 200 };
    }

    // PUT /settings (per-key)
    if (method === 'PUT' && path === '/api/v1/settings') {
      const body = op.body as { settings?: Record<string, string> };
      for (const [key, value] of Object.entries(body.settings ?? {})) {
        this.tables.settings.set(key, { key, value, changeSeq: this.next(), deletedAt: null } as Row);
      }
      return { status: 200 };
    }

    // PATCH /{table}/{id}
    if (method === 'PATCH' && segs.length === 2) {
      const table = this.tableFromSeg(segs[0]);
      if (!table) return { status: 404, body: { title: 'Unknown table' } };
      const row = this.tables[table].get(segs[1]);
      if (!row || row.deletedAt !== null) return { status: 404, body: { title: 'Not found' } };
      const patch = op.body as Record<string, unknown>;
      const merged: Row = { ...row, changeSeq: this.next() };
      for (const [k, v] of Object.entries(patch)) merged[k] = v; // explicit null = clear
      this.tables[table].set(segs[1], merged);
      return { status: 200, body: merged };
    }

    // DELETE /{table}/{id}
    if (method === 'DELETE' && segs.length === 2) {
      const table = this.tableFromSeg(segs[0]);
      if (!table) return { status: 404, body: { title: 'Unknown table' } };
      const row = this.tables[table].get(segs[1]);
      if (!row || row.deletedAt !== null) return { status: 404, body: { title: 'Not found' } };
      const ts = new Date().toISOString();
      this.tables[table].set(segs[1], { ...row, deletedAt: ts, changeSeq: this.next() });
      this.cascadeDelete(table, segs[1], ts);
      return { status: 204 };
    }

    return { status: 404, body: { title: `Unhandled ${method} ${path}` } };
  }

  private tableFromSeg(seg: string): PullTable | null {
    switch (seg) {
      case 'tasks': return 'tasks';
      case 'projects': return 'projects';
      case 'note-groups': return 'noteGroups';
      case 'notes': return 'notes';
      case 'focus-sessions': return 'focusSessions';
      case 'subtasks': return 'subtasks';
      case 'recurrence-rules': return 'recurrenceRules';
      default: return null;
    }
  }

  private create(table: PullTable, body: Record<string, unknown>): { status: number; body?: unknown } {
    const k = this.keyOf(table);
    const id = String(body[k]);
    const existing = this.tables[table].get(id);
    if (existing) return { status: 200, body: existing }; // PK-idempotent (incl. tombstoned)
    const row: Row = { ...body, changeSeq: this.next(), deletedAt: null } as Row;
    this.tables[table].set(id, row);
    return { status: 201, body: row };
  }

  private cascadeDelete(table: PullTable, id: string, ts: string): void {
    if (table === 'tasks') {
      for (const [sid, st] of this.tables.subtasks) {
        if (st.taskId === id && st.deletedAt === null) {
          this.tables.subtasks.set(sid, { ...st, deletedAt: ts, changeSeq: this.next() });
        }
      }
    } else if (table === 'noteGroups') {
      for (const [nid, nt] of this.tables.notes) {
        if (nt.groupId === id && nt.deletedAt === null) {
          this.tables.notes.set(nid, { ...nt, deletedAt: ts, changeSeq: this.next() });
        }
      }
    } else if (table === 'projects') {
      for (const map of [this.tables.tasks, this.tables.notes, this.tables.noteGroups]) {
        for (const [cid, ch] of map) {
          if (ch.projectId === id && ch.deletedAt === null) {
            map.set(cid, { ...ch, projectId: null, changeSeq: this.next() });
          }
        }
      }
    }
  }

  // ── recurrence ensure-instances ────────────────────────────
  private ensure(start: string, end: string): TaskSyncRow[] {
    const created: TaskSyncRow[] = [];
    for (const rule of this.rules.values()) {
      const template = [...this.tables.tasks.values()].find(
        (t) => t.recurrenceRuleId === rule.id && t.isRecurrenceTemplate && t.deletedAt === null,
      );
      if (!template) continue;
      const from = rule.generatedUntil && rule.generatedUntil > start ? rule.generatedUntil : start;
      for (const date of datesInRange(from, end, rule)) {
        const id = `${rule.id}:${date}`;
        if (this.tables.tasks.has(id)) continue;
        const row: Row = {
          ...(template as object), id, isRecurrenceTemplate: false, plannedDate: date,
          recurrenceOriginalDate: date, rank: 'a0', completedAt: null,
          changeSeq: this.next(), deletedAt: null,
        } as Row;
        this.tables.tasks.set(id, row);
        created.push(row as unknown as TaskSyncRow);
      }
      rule.generatedUntil = end;
      const ruleRow = this.tables.recurrenceRules.get(rule.id);
      if (ruleRow) {
        this.tables.recurrenceRules.set(rule.id, { ...ruleRow, generatedUntil: end, changeSeq: this.next() });
      }
    }
    return created;
  }

  // ── pull ───────────────────────────────────────────────────
  private pullPage(since: number, limit: number): SyncResponse {
    const all: { table: PullTable; row: Row }[] = [];
    for (const table of PULL_TABLES) {
      for (const row of this.tables[table].values()) {
        if (row.changeSeq > since) all.push({ table, row });
      }
    }
    all.sort((a, b) => a.row.changeSeq - b.row.changeSeq);
    const hasMore = all.length > limit;
    const page = all.slice(0, limit);
    const empty = (): Row[] => [];
    const changes: Record<PullTable, Row[]> = {
      tasks: empty(), subtasks: empty(), projects: empty(), noteGroups: empty(), notes: empty(),
      recurrenceRules: empty(), focusSessions: empty(), dailyPlans: empty(), settings: empty(),
    };
    for (const { table, row } of page) changes[table].push(row);
    const nextSince = page.length > 0 ? page[page.length - 1].row.changeSeq : since;
    return { changes, nextSince, hasMore } as unknown as SyncResponse;
  }
}

/** Enumerate occurrence dates in [from, end] for a (daily|weekly) rule. */
function datesInRange(from: string, end: string, rule: Rule): string[] {
  const out: string[] = [];
  const start = new Date(`${from}T00:00:00Z`);
  const last = new Date(`${end}T00:00:00Z`);
  const daily = rule.frequency.toLowerCase() === 'daily';
  const interval = Math.max(1, rule.interval);
  for (let d = new Date(start); d <= last; d.setUTCDate(d.getUTCDate() + 1)) {
    const iso = d.toISOString().slice(0, 10);
    if (iso <= from && from !== start.toISOString().slice(0, 10)) continue;
    if (daily) {
      out.push(iso);
    } else {
      const dow = d.getUTCDay();
      if (rule.daysOfWeek.length === 0 || rule.daysOfWeek.includes(dow)) {
        if (((d.getTime() - start.getTime()) / 86_400_000) % (7 * interval) >= 0) out.push(iso);
      }
    }
  }
  return out;
}
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/fakeSyncServer.test.ts
```

Expected: 1 file passed, 9 tests passed.

- [ ] **Run typecheck** — from the repo root (the harness is in the `tsc --noEmit` include set):

```bash
npm run typecheck --workspace @tmap/app
```

Expected: exits 0 (proves `fakeSyncServer.ts` compiles strict-clean against the C5/C6 row types).

- [ ] **Commit**

```bash
git add packages/app/src/sync/__tests__/fakeSyncServer.ts packages/app/src/sync/__tests__/fakeSyncServer.test.ts
git commit -m "test(sync): in-memory FakeSyncServer harness (C5a — shared by R3/R4/R6)

Scripted SyncTransport over per-table Maps + a global changeSeq: PK-idempotent
creates (200 on re-send), project-name 409 with extensions.existingId, PATCH
field-merge + 404, DELETE tombstone + cascades, reorder rank writes, PUT
daily-plan/settings, POST /recurrence (zero instances) + updateRule
generatedUntil reset + ensureInstances materialization, paged tombstone-
inclusive pull. C5a surface: seed/apply/transport plus server mutators
tombstone(table,key) + bumpSeq(table,key,patch?), failNext(matcher,fault),
pullCount(), and latency(ms). No rejectNext — rejections use failNext. Proven
by fakeSyncServer.test.ts.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-4: `src/sync/SyncTransport.ts` — `SyncTransport` interface (C5) + `HttpSyncTransport`

**Files:**
- Create: `packages/app/src/sync/SyncTransport.ts`
- Test: `packages/app/src/sync/__tests__/SyncTransport.test.ts`

The production transport maps a `SyncOp {method, path, body}` to the refresh-wrapped client's `GET/POST/PATCH/PUT/DELETE(path, init)` calls, mirrors `send`'s `{status, body}` contract, and surfaces network failures as a throw (the engine classifies them, §3.3/§7.3). `pull` is `GET /api/v1/sync?since=&limit=` and `ensureInstances` is `POST /api/v1/recurrence/ensure-instances?start=&end=` — both with the query baked into the concrete path (see the transport-mechanism Contract-gap note above). The wrapped client is the `MinimalClient` shape from `refreshClient.ts` — methods return `{ data?, error?, response: { status } }` and **already throw** on network failure / terminal 401 (R5 makes the 401 throw terminal; here we just propagate). The test drives it with a fake `MinimalClient`.

- [ ] **Write failing test** — create `packages/app/src/sync/__tests__/SyncTransport.test.ts`:

```ts
import { describe, it, expect, vi } from 'vitest';
import { HttpSyncTransport } from '../SyncTransport';
import type { MinimalClient } from '../../auth/refreshClient';
import type { SyncOp } from '../types';

type Call = { method: string; path: string; init: unknown };

function fakeClient(
  reply: (method: string, path: string, init: unknown) => { data?: unknown; error?: unknown; response: { status: number } } | Promise<never>,
) {
  const calls: Call[] = [];
  const make = (method: string) => (path: string, init: unknown) => {
    calls.push({ method, path, init });
    return Promise.resolve(reply(method, path, init)) as never;
  };
  const client: MinimalClient = {
    GET: make('GET'), POST: make('POST'), PATCH: make('PATCH'),
    PUT: make('PUT'), DELETE: make('DELETE'),
  };
  return { client, calls };
}

function op(o: Partial<SyncOp> & Pick<SyncOp, 'method' | 'path'>): SyncOp {
  return { entityKeys: [], kind: 'other', attempts: 0, ...o };
}

describe('HttpSyncTransport.send', () => {
  it('maps method+path+body to the wrapped client and returns {status, body}', async () => {
    const { client, calls } = fakeClient(() => ({ data: { ok: true }, response: { status: 201 } }));
    const t = new HttpSyncTransport(client);
    const r = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: { id: 't1' }, kind: 'create' }));
    expect(r).toEqual({ status: 201, body: { ok: true } });
    expect(calls).toEqual([{ method: 'POST', path: '/api/v1/tasks', init: { body: { id: 't1' } } }]);
  });

  it('returns the error body when the client reports a non-2xx result', async () => {
    const { client } = fakeClient(() => ({
      error: { title: 'Conflict', extensions: { existingId: 'p1' } },
      response: { status: 409 },
    }));
    const t = new HttpSyncTransport(client);
    const r = await t.send(op({ method: 'POST', path: '/api/v1/projects', body: { id: 'p2', name: 'X' }, kind: 'create' }));
    expect(r.status).toBe(409);
    expect((r.body as { extensions: { existingId: string } }).extensions.existingId).toBe('p1');
  });

  it('propagates a thrown network/terminal error from the wrapped client', async () => {
    const { client } = fakeClient(() => Promise.reject(Object.assign(new TypeError('Failed to fetch'), { name: 'TypeError' })));
    const t = new HttpSyncTransport(client);
    await expect(t.send(op({ method: 'DELETE', path: '/api/v1/tasks/t1' }))).rejects.toThrow('Failed to fetch');
  });

  it('DELETE without a body sends no body key', async () => {
    const { client, calls } = fakeClient(() => ({ data: undefined, response: { status: 204 } }));
    const t = new HttpSyncTransport(client);
    await t.send(op({ method: 'DELETE', path: '/api/v1/tasks/t1' }));
    expect(calls[0].init).toEqual({});
  });
});

describe('HttpSyncTransport.pull / ensureInstances — query in the concrete path', () => {
  it('pull issues GET /api/v1/sync?since=&limit= and returns the data envelope', async () => {
    const env = { changes: {}, nextSince: 42, hasMore: false };
    const { client, calls } = fakeClient((method, path) => {
      expect(method).toBe('GET');
      return { data: env, response: { status: 200 } };
    });
    const t = new HttpSyncTransport(client);
    const res = await t.pull(7, 500);
    expect(calls[0].path).toBe('/api/v1/sync?since=7&limit=500');
    expect(res).toBe(env);
  });

  it('ensureInstances issues POST /api/v1/recurrence/ensure-instances?start=&end=', async () => {
    const rows = [{ id: 'i1' }];
    const { client, calls } = fakeClient(() => ({ data: rows, response: { status: 200 } }));
    const t = new HttpSyncTransport(client);
    const res = await t.ensureInstances('2026-06-01', '2026-06-14');
    expect(calls[0]).toMatchObject({
      method: 'POST',
      path: '/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-14',
    });
    expect(res).toBe(rows);
  });

  it('pull throws when the client returns an error result', async () => {
    const { client } = fakeClient(() => ({ error: { title: 'boom' }, response: { status: 500 } }));
    const t = new HttpSyncTransport(client);
    await expect(t.pull(0, 500)).rejects.toBeTruthy();
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncTransport.test.ts
```

Expected failure: module-resolution error — `Error: Failed to resolve import "../SyncTransport" from "src/sync/__tests__/SyncTransport.test.ts". Does the file exist?`

- [ ] **Minimal impl** — create `packages/app/src/sync/SyncTransport.ts`:

```ts
/**
 * SyncTransport — the engine's only outbound seam (contract C5). The production
 * impl wraps the refresh-wrapped MinimalClient (refreshClient.ts): one shared
 * 401→refresh + abort + Bearer middleware path for every replayed op (the SP2
 * invariant). Ops carry already-concrete paths (LocalDataClient resolved them at
 * enqueue), so we call the typed method with the literal path and NO openapi-fetch
 * `params` — openapi-fetch fetches a concrete path verbatim. pull/ensureInstances
 * bake their query string into the path for the same reason (see the R3-4
 * transport-mechanism note in the plan).
 */

import type { MinimalClient } from '../auth/refreshClient';
import type { SyncOp } from './types';
import type { SyncResponse, TaskSyncRow } from '../data/local/rows';

export interface SyncTransport {
  /** Replay one op. Returns {status, body}; THROWS on network failure / terminal 401. */
  send(op: SyncOp): Promise<{ status: number; body?: unknown }>;
  /** GET /api/v1/sync?since=&limit= — one page of remote changes. */
  pull(since: number, limit: number): Promise<SyncResponse>;
  /** POST /api/v1/recurrence/ensure-instances?start=&end= — materialize instances. */
  ensureInstances(start: string, end: string): Promise<TaskSyncRow[]>;
}

type Result = { data?: unknown; error?: unknown; response: { status: number } };

export class HttpSyncTransport implements SyncTransport {
  constructor(private readonly client: MinimalClient) {}

  async send(op: SyncOp): Promise<{ status: number; body?: unknown }> {
    const init = op.body === undefined ? {} : { body: op.body };
    let res: Result;
    switch (op.method) {
      case 'POST':
        res = await this.client.POST(op.path, init);
        break;
      case 'PATCH':
        res = await this.client.PATCH(op.path, init);
        break;
      case 'PUT':
        res = await this.client.PUT(op.path, init);
        break;
      case 'DELETE':
        res = await this.client.DELETE(op.path, init);
        break;
    }
    return { status: res.response.status, body: res.error ?? res.data };
  }

  async pull(since: number, limit: number): Promise<SyncResponse> {
    const res = (await this.client.GET(
      `/api/v1/sync?since=${since}&limit=${limit}`,
      {},
    )) as Result;
    if (res.error) throw res.error;
    return res.data as SyncResponse;
  }

  async ensureInstances(start: string, end: string): Promise<TaskSyncRow[]> {
    const res = (await this.client.POST(
      `/api/v1/recurrence/ensure-instances?start=${start}&end=${end}`,
      {},
    )) as Result;
    if (res.error) throw res.error;
    return (res.data as TaskSyncRow[]) ?? [];
  }
}
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncTransport.test.ts
```

Expected: 1 file passed, 7 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/sync/SyncTransport.ts packages/app/src/sync/__tests__/SyncTransport.test.ts
git commit -m "feat(sync): SyncTransport interface (C5) + HttpSyncTransport

HttpSyncTransport maps SyncOp{method,path,body} onto the refresh-wrapped
MinimalClient (shared 401/abort/Bearer middleware); ops carry concrete paths
so no openapi-fetch params are needed. pull = GET /api/v1/sync?since=&limit=,
ensureInstances = POST /recurrence/ensure-instances?start=&end=. Network/
terminal errors propagate as throws for the engine to classify.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-5: `src/sync/SyncEngine.ts` — skeleton (C5): lifecycle, triggers, single-flight, cycle shape

**Files:**
- Create: `packages/app/src/sync/SyncEngine.ts`
- Test: `packages/app/src/sync/__tests__/SyncEngine.push.test.ts`

The engine's structural shell per contract C5: `start/stop`, `subscribe`/synchronous `getStatus()` snapshots, the `nudge()` debounce (`PUSH_DEBOUNCE_MS`, with `requestSync()` kept as a delegating alias), the periodic timer (`PERIODIC_SYNC_MS`), the online-event trigger, `syncNow`, and single-flight (`navigator.locks` when available — feature-detected — else an in-process mutex; tests exercise the mutex path). The class declares `implements SyncBridge` so AppRoot can pass the engine itself as the `SyncBridge` (C4 — it structurally exposes `nudge`/`online`/`ensureInstances`). The **cycle is structured now** — `pushPhase()` then `pullPhase()` then notify — but `pushPhase` here only drains success/already-done ops (full failure taxonomy lands in R3-6…R3-11) and `pullPhase()` is a **no-op stub** R4 fills (per the brief). `getStatus()` is built synchronously from a cache that `emitStatus()`/`refreshSnapshot()` keep current from the `ops`/`issues`/`meta` tables.

This task's test file `SyncEngine.push.test.ts` is the home for every push-side test in R3-5…R3-11; this first slice asserts the skeleton's triggers, single-flight, status, and the push-then-pull-stub cycle.

- [ ] **Write failing test** — create `packages/app/src/sync/__tests__/SyncEngine.push.test.ts`:

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { LocalStore } from '../../data/local/LocalStore';
import { SyncEngine } from '../SyncEngine';
import type { SyncTransport } from '../SyncTransport';
import type { SyncOp, SyncStatus } from '../types';
import type { SyncResponse, TaskSyncRow } from '../../data/local/rows';
import { PUSH_DEBOUNCE_MS, PERIODIC_SYNC_MS } from '../constants';

let n = 0;
const stores: LocalStore[] = [];
function freshStore(): LocalStore {
  const s = LocalStore.open(`eng-${Date.now()}-${n++}`);
  stores.push(s);
  return s;
}
afterEach(() => {
  for (const s of stores.splice(0)) s.close();
  vi.useRealTimers();
});

/** A transport spy that records sent ops and replies per a scripted function. */
function spyTransport(
  reply: (op: SyncOp) => { status: number; body?: unknown } | Promise<{ status: number; body?: unknown }> = () => ({ status: 200 }),
): SyncTransport & { sent: SyncOp[]; pulls: number; ensured: [string, string][] } {
  const sent: SyncOp[] = [];
  const ensured: [string, string][] = [];
  let pulls = 0;
  const emptyPage: SyncResponse = { changes: {}, nextSince: 0, hasMore: false } as unknown as SyncResponse;
  return {
    sent,
    ensured,
    get pulls() { return pulls; },
    async send(op: SyncOp) { sent.push(op); return reply(op); },
    async pull(): Promise<SyncResponse> { pulls++; return emptyPage; },
    async ensureInstances(start: string, end: string): Promise<TaskSyncRow[]> { ensured.push([start, end]); return []; },
  } as SyncTransport & { sent: SyncOp[]; pulls: number; ensured: [string, string][] };
}

function op(o: Partial<SyncOp> & Pick<SyncOp, 'method' | 'path'>): SyncOp {
  return { entityKeys: [], kind: 'other', attempts: 0, ...o };
}

describe('SyncEngine skeleton — lifecycle, triggers, single-flight, cycle shape', () => {
  it('syncNow drains a queued op (2xx → deleted) then runs the pull stub', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 200 }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(t.sent).toHaveLength(1);
    expect(await store.ops.count()).toBe(0);
    expect(t.pulls).toBe(1); // pull phase always runs (R4 fills it; stub here)
  });

  it('requestSync debounces: many calls collapse into ONE cycle after PUSH_DEBOUNCE_MS', async () => {
    vi.useFakeTimers();
    const store = freshStore();
    const t = spyTransport();
    const engine = new SyncEngine({ store, transport: t });
    await store.ops.add(op({ method: 'DELETE', path: '/api/v1/tasks/a', entityKeys: ['tasks:a'] }));

    engine.requestSync();
    engine.requestSync();
    engine.requestSync();
    expect(t.pulls).toBe(0); // nothing fired yet
    await vi.advanceTimersByTimeAsync(PUSH_DEBOUNCE_MS);
    await vi.waitFor(() => expect(t.pulls).toBe(1));
    expect(t.sent).toHaveLength(1);
  });

  it('start runs a first cycle and arms the periodic timer (PERIODIC_SYNC_MS)', async () => {
    vi.useFakeTimers();
    const store = freshStore();
    const t = spyTransport();
    const engine = new SyncEngine({ store, transport: t });
    engine.start();
    await vi.waitFor(() => expect(t.pulls).toBe(1)); // first cycle on start
    await vi.advanceTimersByTimeAsync(PERIODIC_SYNC_MS);
    await vi.waitFor(() => expect(t.pulls).toBe(2)); // periodic tick
    engine.stop();
    await vi.advanceTimersByTimeAsync(PERIODIC_SYNC_MS * 2);
    expect(t.pulls).toBe(2); // stopped → no more ticks
  });

  it('single-flight: a second syncNow while one is in flight does not start a concurrent cycle', async () => {
    const store = freshStore();
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let sends = 0;
    const t = spyTransport(async () => {
      sends++;
      await gate; // hold the first cycle open
      return { status: 200 };
    });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    const first = engine.syncNow();
    const second = engine.syncNow(); // must no-op (a cycle is running)
    release();
    await Promise.all([first, second]);
    expect(sends).toBe(1); // the op was sent exactly once
  });

  it('subscribe fires getStatus() once immediately, then a fresh snapshot; emits on change', async () => {
    const store = freshStore();
    const t = spyTransport();
    await store.ops.add(op({ method: 'DELETE', path: '/api/v1/tasks/a', entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    const seen: SyncStatus[] = [];
    engine.subscribe((s) => seen.push(s));
    expect(seen.length).toBeGreaterThanOrEqual(1); // synchronous immediate fire (getStatus())
    expect(typeof seen[0].online).toBe('boolean');

    // The async cache refresh queued by subscribe delivers a snapshot reflecting the op.
    await vi.waitFor(() => expect(seen[seen.length - 1].pendingOps).toBe(1));
    expect(engine.getStatus().pendingOps).toBe(1); // synchronous getStatus() now reflects the cache

    await engine.syncNow();
    const last = seen[seen.length - 1];
    expect(last.pendingOps).toBe(0); // op drained
    expect(last.syncing).toBe(false);
    expect(engine.getStatus().pendingOps).toBe(0);
  });

  it('online() reflects the injected connectivity', () => {
    const store = freshStore();
    const t = spyTransport();
    const engine = new SyncEngine({
      store,
      transport: t,
      connectivity: { online: () => false, subscribe: () => () => {} },
    });
    expect(engine.online()).toBe(false);
  });

  it('an online connectivity event triggers a cycle', async () => {
    const store = freshStore();
    const t = spyTransport();
    let fire: (online: boolean) => void = () => {};
    const engine = new SyncEngine({
      store,
      transport: t,
      connectivity: { online: () => true, subscribe: (cb) => { fire = cb; return () => {}; } },
    });
    engine.start();
    await vi.waitFor(() => expect(t.pulls).toBe(1));
    fire(true); // online event
    await vi.waitFor(() => expect(t.pulls).toBe(2));
    engine.stop();
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts
```

Expected failure: module-resolution error — `Error: Failed to resolve import "../SyncEngine" from "src/sync/__tests__/SyncEngine.push.test.ts". Does the file exist?`

- [ ] **Minimal impl** — create `packages/app/src/sync/SyncEngine.ts`. This file is built incrementally across R3-5…R3-11; **this slice writes the complete file** with the push-phase taxonomy already structured (the later tasks only ADD tests that this implementation already satisfies — the impl is written once here in full to avoid elision):

```ts
/**
 * SyncEngine — single-flight push→(ensure-instances)→pull cycle (contract C5).
 * Push side: spec §3 (op replay, failure taxonomy, recovery, triggers, status).
 * Pull side: pullPhase() is a NO-OP STUB here — R4 fills it (cursor + applyPull +
 * recovery + initialSyncComplete gate). The cycle is structured now so R4 only
 * implements pullPhase without reshaping start/stop/single-flight.
 */

import type { LocalStore } from '../data/local/LocalStore';
import type { TaskSyncRow } from '../data/local/rows';
import type { SyncTransport } from './SyncTransport';
import type { SyncBridge } from '../data/local/LocalDataClient';
import type { SyncIssue, SyncOp, SyncStatus, SyncTable } from './types';
import { entityKey } from './types';
import { createConnectivity, type Connectivity } from './connectivity';
import {
  CYCLE_5XX_BACKOFF_MS,
  CYCLE_5XX_RETRIES,
  ENSURE_HORIZON_DAYS,
  PARK_THRESHOLD,
  PERIODIC_SYNC_MS,
  PUSH_DEBOUNCE_MS,
} from './constants';

export interface SyncEngineDeps {
  store: LocalStore;
  transport: SyncTransport;
  /** Defaults to createConnectivity(); injected in tests. */
  connectivity?: Connectivity;
  /** Lock name suffix; defaults to the store's DB-name tail. */
  userId?: string;
  /** Injected for fake timers in tests; defaults to setTimeout/clearTimeout. */
  setTimer?: (cb: () => void, ms: number) => ReturnType<typeof setTimeout>;
  clearTimer?: (h: ReturnType<typeof setTimeout>) => void;
  /** Awaitable delay (fake-timer friendly); defaults to setTimeout-based sleep. */
  sleep?: (ms: number) => Promise<void>;
}

/** Outcome of replaying one op, classified per spec §3.3. */
type OpOutcome =
  | { kind: 'done' }                 // 2xx (or 404-delete) → delete op, continue
  | { kind: 'network' }              // throw → abort push phase, queue intact
  | { kind: 'terminal' }             // 401 terminal (refresh dead) → stop engine
  | { kind: 'retry5xx' }             // 5xx, attempts under threshold → retry in-cycle
  | { kind: 'park' }                 // attempts >= PARK_THRESHOLD → move to issues
  | { kind: 'drop'; recover: boolean } // definitive 4xx → drop + issue (+recovery)
  | { kind: 'adopted' };             // 409 adopt-existing handled → op already removed

const TERMINAL_MESSAGE = 'Session expired — refresh failed';

export class SyncEngine implements SyncBridge {
  private readonly store: LocalStore;
  private readonly transport: SyncTransport;
  private readonly connectivity: Connectivity;
  private readonly userId: string;
  private readonly setTimer: (cb: () => void, ms: number) => ReturnType<typeof setTimeout>;
  private readonly clearTimer: (h: ReturnType<typeof setTimeout>) => void;
  private readonly sleep: (ms: number) => Promise<void>;

  private running = false;          // start() called, stop() not yet
  private terminal = false;         // 401-dead; no further cycles
  private cyclePromise: Promise<void> | null = null; // in-process single-flight
  private debounceHandle: ReturnType<typeof setTimeout> | null = null;
  private periodicHandle: ReturnType<typeof setTimeout> | null = null;
  private unsubConnectivity: (() => void) | null = null;
  private firstCycleSettled = false;
  /** Post-push ensure-instances rows buffered for R4's pull phase to drain (spec §4.3). */
  private pendingEnsureRows: TaskSyncRow[] | null = null;

  private statusListeners = new Set<(s: SyncStatus) => void>();
  private changesAppliedCbs: Array<() => void> = [];
  private firstCycleSettledCbs: Array<() => void> = [];

  /** Current snapshot fields not derived from tables on every emit (synchronous getStatus). */
  private syncing = false;
  /** Cached counts/values so getStatus() is synchronous (refreshed on every cycle/mutation). */
  private pendingOpsCount = 0;
  private cachedIssues: SyncIssue[] = [];
  private cachedLastSyncedAt: string | null = null;
  private cachedInitialSyncComplete = false;

  constructor(deps: SyncEngineDeps) {
    this.store = deps.store;
    this.transport = deps.transport;
    this.connectivity = deps.connectivity ?? createConnectivity();
    this.userId = deps.userId ?? deps.store.name.replace(/^tmap-/, '');
    this.setTimer = deps.setTimer ?? ((cb, ms) => setTimeout(cb, ms));
    this.clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h));
    this.sleep = deps.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    // Prime the synchronous-getStatus cache from the store (best-effort; emitStatus refreshes it).
    void this.refreshSnapshot();
  }

  // ── lifecycle ──────────────────────────────────────────────
  start(): void {
    if (this.running) return;
    this.running = true;
    this.unsubConnectivity = this.connectivity.subscribe((online) => {
      if (online) void this.syncNow();
    });
    this.armPeriodic();
    void this.syncNow(); // first cycle
  }

  stop(): void {
    this.running = false;
    if (this.debounceHandle) {
      this.clearTimer(this.debounceHandle);
      this.debounceHandle = null;
    }
    if (this.periodicHandle) {
      this.clearTimer(this.periodicHandle);
      this.periodicHandle = null;
    }
    this.unsubConnectivity?.();
    this.unsubConnectivity = null;
  }

  private armPeriodic(): void {
    if (!this.running) return;
    this.periodicHandle = this.setTimer(() => {
      void this.syncNow();
      this.armPeriodic();
    }, PERIODIC_SYNC_MS);
  }

  // ── triggers ───────────────────────────────────────────────
  /** SyncBridge.nudge — the debounced push trigger (PUSH_DEBOUNCE_MS). Primary name. */
  nudge(): void {
    if (this.debounceHandle) this.clearTimer(this.debounceHandle);
    this.debounceHandle = this.setTimer(() => {
      this.debounceHandle = null;
      void this.syncNow();
    }, PUSH_DEBOUNCE_MS);
  }

  /** Kept back-compat alias of nudge() — both schedule the debounced cycle. */
  requestSync(): void {
    this.nudge();
  }

  online(): boolean {
    return this.connectivity.online();
  }

  // ── single-flight cycle ────────────────────────────────────
  /** Immediate cycle (manual retry / triggers). No-op if a cycle is already running. */
  syncNow(): Promise<void> {
    if (this.terminal) return Promise.resolve();
    if (this.cyclePromise) return this.cyclePromise;
    this.cyclePromise = this.withLock(() => this.runCycle()).finally(() => {
      this.cyclePromise = null;
    });
    return this.cyclePromise;
  }

  /**
   * Cross-tab single-flight via navigator.locks; falls back to the in-process
   * mutex (cyclePromise) when navigator.locks is unavailable (node tests).
   */
  private withLock(fn: () => Promise<void>): Promise<void> {
    const locks = (globalThis as { navigator?: { locks?: LockManager } }).navigator?.locks;
    if (locks && typeof locks.request === 'function') {
      return locks.request(`tmap-sync-${this.userId}`, () => fn());
    }
    return fn();
  }

  private async runCycle(): Promise<void> {
    this.syncing = true;
    this.emitStatus();
    try {
      const replayed = await this.pushPhase();
      if (this.terminal) return; // 401 killed the engine mid-push
      if (replayed.some((o) => o.regenAfterPush)) {
        await this.runEnsureInstances();
      }
      await this.pullPhase(); // R4 fills this in
    } catch {
      // network aborts surface here as resolved (push returns), so a throw is
      // unexpected — swallow to keep the engine alive for the next trigger.
    } finally {
      this.syncing = false;
      if (!this.firstCycleSettled) {
        this.firstCycleSettled = true;
        for (const cb of this.firstCycleSettledCbs) cb();
      }
      this.emitStatus();
    }
  }

  // ── push phase (spec §3.3) ─────────────────────────────────
  /** Drain the ops table FIFO; returns the ops successfully replayed (for §4.3). */
  private async pushPhase(): Promise<SyncOp[]> {
    const replayed: SyncOp[] = [];
    // FIFO by seq; re-read the head each iteration (recovery may have mutated the queue).
    for (;;) {
      const head = await this.store.ops.orderBy('seq').first();
      if (!head) break;

      let attempt = 0;
      let outcome = await this.replayOnce(head);

      // In-cycle 5xx retry ladder.
      while (outcome.kind === 'retry5xx' && attempt < CYCLE_5XX_RETRIES) {
        await this.sleep(CYCLE_5XX_BACKOFF_MS[attempt] ?? CYCLE_5XX_BACKOFF_MS[CYCLE_5XX_BACKOFF_MS.length - 1]);
        attempt++;
        outcome = await this.replayOnce(head);
      }

      switch (outcome.kind) {
        case 'done':
          await this.store.ops.delete(head.seq!);
          replayed.push(head);
          continue;
        case 'adopted':
          // adopt-existing already dropped this create op + rewrote the queue.
          continue;
        case 'network':
          return replayed; // abort push phase; queue intact; pull still runs
        case 'terminal':
          this.terminal = true;
          this.stop();
          return replayed;
        case 'retry5xx':
          // exhausted in-cycle retries; persist attempts, abort phase, resume next trigger
          return replayed;
        case 'park':
          await this.parkOp(head);
          continue;
        case 'drop':
          await this.dropOp(head, outcome.recover);
          continue;
      }
    }
    return replayed;
  }

  /** Replay one op once and classify the result (no retry loop here). */
  private async replayOnce(op: SyncOp): Promise<OpOutcome> {
    let res: { status: number; body?: unknown };
    try {
      res = await this.transport.send(op);
    } catch (e) {
      return this.classifyThrow(e);
    }
    const s = res.status;

    if (s >= 200 && s < 300) return { kind: 'done' };

    // 404 on a delete op = already tombstoned → success (spec §3.2).
    if (s === 404 && op.method === 'DELETE') return { kind: 'done' };

    if (s === 409 && op.kind === 'create') {
      const existingId = this.existingIdFrom(res.body);
      if (existingId) {
        await this.adoptExisting(op, existingId);
        return { kind: 'adopted' };
      }
      // 409 without an existingId: treat as definitive drop + recovery.
      return { kind: 'drop', recover: true };
    }

    if (s >= 500) {
      const attempts = (op.attempts ?? 0) + 1;
      await this.store.ops.update(op.seq!, { attempts, lastError: `HTTP ${s}` });
      op.attempts = attempts;
      return attempts >= PARK_THRESHOLD ? { kind: 'park' } : { kind: 'retry5xx' };
    }

    // Definitive 4xx (400/404-on-non-delete/403/etc., except the 401 path).
    if (s === 401) return { kind: 'terminal' };
    return { kind: 'drop', recover: op.kind === 'create' };
  }

  /** Classify a thrown transport error: terminal 401 vs transient network (§7.3). */
  private classifyThrow(e: unknown): OpOutcome {
    const err = e as { name?: string; message?: string };
    if (err?.message === TERMINAL_MESSAGE) return { kind: 'terminal' };
    // NetworkError / TypeError (fetch) / transient refresh failure → network abort.
    return { kind: 'network' };
  }

  private existingIdFrom(body: unknown): string | null {
    const ext = (body as { extensions?: { existingId?: unknown } } | undefined)?.extensions;
    const id = ext?.existingId;
    return typeof id === 'string' ? id : null;
  }

  // ── definitive rejection: drop + ghost-row recovery (spec §3.3) ─────────
  private async dropOp(op: SyncOp, recover: boolean): Promise<void> {
    await this.recordIssue(op, 'dropped', op.lastError ?? `HTTP rejection`);
    if (op.kind === 'create') {
      await this.recoverGhostRows(op);
    }
    await this.store.ops.delete(op.seq!);
    if (recover) await this.scheduleRecovery();
  }

  /** Delete local rows a rejected create produced + drop queued ops referencing them. */
  private async recoverGhostRows(op: SyncOp): Promise<void> {
    for (const key of op.entityKeys) {
      const [table, id] = this.splitKey(key);
      if (!table) continue;
      await (this.store as unknown as Record<string, { delete(id: string): Promise<void> }>)[table]
        .delete(id)
        .catch(() => {});
      // Drop other queued ops that reference this ghost key (each recorded).
      const dependents = await this.store.ops.where('entityKeys').equals(key).toArray();
      for (const dep of dependents) {
        if (dep.seq === op.seq) continue;
        await this.recordIssue(dep, 'dropped', `ghost-row recovery: ${op.lastError ?? 'rejected create'}`);
        await this.store.ops.delete(dep.seq!);
      }
    }
  }

  /** Schedule a recovery pull from since=0 at the end of the cycle (R4 honors the flag). */
  private async scheduleRecovery(): Promise<void> {
    await this.store.setMeta('pendingRecovery', true);
  }

  // ── 409 adopt-existing (spec §3.3) ─────────────────────────
  /** Rewrite ghost create id → existingId across local rows, queued ops, then drop the create. */
  private async adoptExisting(createOp: SyncOp, existingId: string): Promise<void> {
    const [table, ghostId] = this.splitKey(createOp.entityKeys[0]);
    if (!table || !ghostId) {
      await this.store.ops.delete(createOp.seq!);
      return;
    }
    await this.store.transaction('rw', this.store.tables, async () => {
      // 1. Move the ghost row's data under existingId is unnecessary — the existing
      //    server row wins; we delete the ghost local row and repoint FKs.
      await (this.store as unknown as Record<string, { delete(id: string): Promise<void> }>)[table]
        .delete(ghostId)
        .catch(() => {});
      // 2. Repoint FKs on local rows (table-specific).
      await this.repointForeignKeys(table as SyncTable, ghostId, existingId);
      // 3. Rewrite queued op bodies/paths/entityKeys referencing the ghost id.
      const ghostKey = entityKey(table as SyncTable, ghostId);
      const newKey = entityKey(table as SyncTable, existingId);
      const ops = await this.store.ops.toArray();
      for (const o of ops) {
        if (o.seq === createOp.seq) continue;
        let changed = false;
        const newKeys = o.entityKeys.map((k) => {
          if (k === ghostKey) { changed = true; return newKey; }
          return k;
        });
        const newPath = o.path.includes(ghostId)
          ? o.path.split(ghostId).join(existingId)
          : o.path;
        const newBody = this.rewriteBodyIds(o.body, ghostId, existingId);
        if (changed || newPath !== o.path || newBody !== o.body) {
          await this.store.ops.update(o.seq!, { entityKeys: newKeys, path: newPath, body: newBody });
        }
      }
      // 4. Drop the create op itself.
      await this.store.ops.delete(createOp.seq!);
    });
    await this.recordIssue(
      createOp,
      'dropped',
      `409 — adopted existing ${table} ${existingId}`,
    );
  }

  /** Repoint child FKs from a ghost id to the adopted existingId. */
  private async repointForeignKeys(table: SyncTable, ghostId: string, existingId: string): Promise<void> {
    if (table === 'projects') {
      await this.repoint(this.store.tasks, 'projectId', ghostId, existingId);
      await this.repoint(this.store.notes, 'projectId', ghostId, existingId);
      await this.repoint(this.store.noteGroups, 'projectId', ghostId, existingId);
    } else if (table === 'noteGroups') {
      await this.repoint(this.store.notes, 'groupId', ghostId, existingId);
    } else if (table === 'tasks') {
      await this.repoint(this.store.subtasks, 'taskId', ghostId, existingId);
    } else if (table === 'recurrenceRules') {
      await this.repoint(this.store.tasks, 'recurrenceRuleId', ghostId, existingId);
    }
  }

  private async repoint<T>(
    tbl: { toArray(): Promise<T[]>; update(key: string, c: Partial<T>): Promise<number> },
    field: keyof T,
    from: string,
    to: string,
  ): Promise<void> {
    const rows = await tbl.toArray();
    for (const r of rows) {
      const rec = r as unknown as Record<string, unknown> & { id?: string };
      if (rec[field as string] === from && rec.id) {
        await tbl.update(rec.id, { [field]: to } as unknown as Partial<T>);
      }
    }
  }

  /** Deep-rewrite any string === ghostId to existingId in a queued op body. */
  private rewriteBodyIds(body: unknown, ghostId: string, existingId: string): unknown {
    if (body === ghostId) return existingId;
    if (Array.isArray(body)) return body.map((v) => this.rewriteBodyIds(v, ghostId, existingId));
    if (body && typeof body === 'object') {
      const out: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(body as Record<string, unknown>)) {
        out[k] = this.rewriteBodyIds(v, ghostId, existingId);
      }
      return out;
    }
    return body;
  }

  // ── poison-op parking (spec §3.3) ──────────────────────────
  private async parkOp(op: SyncOp): Promise<void> {
    await this.recordIssue(op, 'parked', op.lastError ?? `parked after ${op.attempts} attempts`);
    await this.store.ops.delete(op.seq!);
  }

  private async recordIssue(op: SyncOp, status: SyncIssue['status'], reason: string): Promise<void> {
    const issue: SyncIssue = { at: new Date().toISOString(), op: { ...op }, reason, status };
    await this.store.issues.add(issue);
  }

  // ── parked-op retry/discard (C5) ───────────────────────────
  async retryIssue(id: number): Promise<void> {
    const issue = await this.store.issues.get(id);
    if (!issue) return;
    const { seq, ...rest } = issue.op;
    await this.store.ops.add({ ...rest, attempts: 0, lastError: undefined });
    await this.store.issues.delete(id);
    this.emitStatus();
  }

  async discardIssue(id: number): Promise<void> {
    const issue = await this.store.issues.get(id);
    if (!issue) return;
    await this.store.issues.delete(id);
    if (issue.op.kind === 'create') await this.recoverGhostRows(issue.op);
    await this.scheduleRecovery();
    this.emitStatus();
  }

  // ── post-push ensure-instances (spec §4.3) ─────────────────
  private async runEnsureInstances(): Promise<void> {
    const today = new Date().toISOString().slice(0, 10);
    const end = new Date(Date.now() + ENSURE_HORIZON_DAYS * 86_400_000).toISOString().slice(0, 10);
    try {
      const rows = await this.transport.ensureInstances(today, end);
      // Buffer the returned instances; R4's pull phase drains pendingEnsureRows
      // through applyPull (shadow rule honored), then nulls it.
      this.pendingEnsureRows = rows;
    } catch {
      // ensure-instances failure is non-fatal; the next cycle retries.
    }
  }

  // ── pull phase (R4) ────────────────────────────────────────
  /** NO-OP STUB — R4 implements cursor + applyPull + recovery + initialSyncComplete. */
  protected async pullPhase(): Promise<void> {
    await this.transport.pull(0, 0).then(
      () => {},
      () => {},
    );
  }

  // ── status + notifications ─────────────────────────────────
  subscribe(cb: (s: SyncStatus) => void): () => void {
    this.statusListeners.add(cb);
    cb(this.getStatus()); // fire once immediately with the current snapshot (synchronous)
    // Refresh the cache from the store, then emit again so the first delivery is fresh.
    void this.refreshSnapshot().then(() => cb(this.getStatus()));
    return () => {
      this.statusListeners.delete(cb);
    };
  }

  onChangesApplied(cb: () => void): () => void {
    this.changesAppliedCbs.push(cb);
    return () => {
      this.changesAppliedCbs = this.changesAppliedCbs.filter((c) => c !== cb);
    };
  }

  onFirstCycleSettled(cb: () => void): () => void {
    this.firstCycleSettledCbs.push(cb);
    return () => {
      this.firstCycleSettledCbs = this.firstCycleSettledCbs.filter((c) => c !== cb);
    };
  }

  /** SyncBridge passthrough (C4) — server call; throws offline. */
  ensureInstances(start: string, end: string): Promise<TaskSyncRow[]> {
    return this.transport.ensureInstances(start, end);
  }

  /**
   * SYNCHRONOUS current snapshot (C5) — built from the cached table-derived fields
   * refreshed by refreshSnapshot()/emitStatus(). Used by AppRoot's initial gate and
   * the Sign-out pendingOps read, which both need a value without awaiting Dexie.
   */
  getStatus(): SyncStatus {
    return {
      online: this.connectivity.online(),
      syncing: this.syncing,
      pendingOps: this.pendingOpsCount,
      lastSyncedAt: this.cachedLastSyncedAt,
      issues: this.cachedIssues,
      initialSyncComplete: this.cachedInitialSyncComplete,
    };
  }

  /** Re-read the table-derived status fields into the synchronous-getStatus cache. */
  private async refreshSnapshot(): Promise<void> {
    const [pendingOps, issues, lastSyncedAt, initialSyncComplete] = await Promise.all([
      this.store.ops.count(),
      this.store.issues.toArray(),
      this.store.getMeta<string>('lastSyncedAt'),
      this.store.getMeta<boolean>('initialSyncComplete'),
    ]);
    this.pendingOpsCount = pendingOps;
    this.cachedIssues = issues;
    this.cachedLastSyncedAt = lastSyncedAt ?? null;
    this.cachedInitialSyncComplete = initialSyncComplete ?? false;
  }

  private emitStatus(): void {
    // Refresh the cache, then push a fresh getStatus() to every subscriber.
    void this.refreshSnapshot().then(() => {
      const s = this.getStatus();
      for (const cb of this.statusListeners) cb(s);
    });
  }

  private splitKey(key: string | undefined): [SyncTable | null, string] {
    if (!key) return [null, ''];
    const i = key.indexOf(':');
    if (i === -1) return [null, ''];
    return [key.slice(0, i) as SyncTable, key.slice(i + 1)];
  }
}

/** Minimal Web Locks shape (lib.dom may not include it in node typings). */
interface LockManager {
  request(name: string, cb: () => Promise<void>): Promise<void>;
}
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts
```

Expected: 1 file passed, 7 tests passed.

- [ ] **Run typecheck** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: exits 0.

- [ ] **Commit**

```bash
git add packages/app/src/sync/SyncEngine.ts packages/app/src/sync/__tests__/SyncEngine.push.test.ts
git commit -m "feat(sync): SyncEngine skeleton (C5) — lifecycle, triggers, single-flight, cycle shape

implements SyncBridge: start/stop, debounced nudge (PUSH_DEBOUNCE_MS) with
requestSync() as a delegating alias, periodic timer (PERIODIC_SYNC_MS),
online-event trigger, syncNow with in-process single-flight (navigator.locks
when available), subscribe + synchronous getStatus() snapshots, and the
push→ensure-instances→pull cycle with pullPhase() as a no-op stub R4 fills.
The full push-phase failure taxonomy ships in this file and is exercised by
the R3-6..R3-11 tests added next.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-6: Push loop — FIFO drain, 2xx→delete, network throw aborts the phase

**Files:**
- Test: `packages/app/src/sync/__tests__/SyncEngine.push.test.ts` (append)

The skeleton (R3-5) already implements FIFO drain + network abort; this task adds the behavioral tests that pin them: ops replay in `seq` order, each 2xx deletes its op, a `network` throw mid-drain leaves the remaining ops intact and still lets the pull phase run (spec §3.3 "a halted push never blocks pull"), and the engine remains usable on the next trigger.

- [ ] **Write failing test** — append to `packages/app/src/sync/__tests__/SyncEngine.push.test.ts` (inside the existing top-level `describe` or a new sibling `describe`; the helpers `freshStore`, `spyTransport`, `op` are already in scope):

```ts
describe('push loop — FIFO drain + network abort (spec §3.3)', () => {
  it('replays ops in strict seq (FIFO) order', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 200 }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: {}, entityKeys: ['tasks:a'] }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/b', body: {}, entityKeys: ['tasks:b'] }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/c', body: {}, entityKeys: ['tasks:c'] }));
    const engine = new SyncEngine({ store, transport: t });
    await engine.syncNow();
    expect(t.sent.map((o) => o.path)).toEqual([
      '/api/v1/tasks/a', '/api/v1/tasks/b', '/api/v1/tasks/c',
    ]);
    expect(await store.ops.count()).toBe(0);
  });

  it('a network throw aborts the push phase: the failing op and its successors stay queued', async () => {
    const store = freshStore();
    const t = spyTransport((o) => {
      if (o.path === '/api/v1/tasks/b') {
        throw Object.assign(new TypeError('Failed to fetch'), { name: 'TypeError' });
      }
      return { status: 200 };
    });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: {}, entityKeys: ['tasks:a'] }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/b', body: {}, entityKeys: ['tasks:b'] }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/c', body: {}, entityKeys: ['tasks:c'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    // 'a' drained; 'b' (network) + 'c' remain, in order.
    const remaining = await store.ops.orderBy('seq').toArray();
    expect(remaining.map((o) => o.path)).toEqual(['/api/v1/tasks/b', '/api/v1/tasks/c']);
  });

  it('pull still runs even when push aborted on a network error (§3.3)', async () => {
    const store = freshStore();
    const t = spyTransport(() => {
      throw Object.assign(new TypeError('offline'), { name: 'TypeError' });
    });
    await store.ops.add(op({ method: 'DELETE', path: '/api/v1/tasks/a', entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });
    await engine.syncNow();
    expect(t.pulls).toBe(1);                 // pull phase ran despite push abort
    expect(await store.ops.count()).toBe(1); // op intact for the next trigger
  });

  it('the engine recovers on the next trigger after a transient network failure', async () => {
    const store = freshStore();
    let offline = true;
    const t = spyTransport(() => {
      if (offline) throw Object.assign(new TypeError('offline'), { name: 'TypeError' });
      return { status: 200 };
    });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: {}, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();                 // fails: op intact
    expect(await store.ops.count()).toBe(1);
    offline = false;
    await engine.syncNow();                 // succeeds now
    expect(await store.ops.count()).toBe(0);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts -t "push loop"
```

> If R3-5's full impl already satisfies these four assertions (it does — FIFO drain + `return replayed` on `network` are implemented there), this step instead **confirms they pass immediately** with no edit. Treat the "FAIL" expectation as satisfied if the only reason a test would have failed (the behavior not existing) is absent; per C11 the cadence is preserved by running the filter and seeing the new tests reported. If any of the four FAIL, fix `pushPhase`/`classifyThrow` until green — do not weaken the test.

Expected: the four new `push loop` tests **PASS** against the R3-5 implementation.

- [ ] **Minimal impl** — none required: the behavior was implemented in `SyncEngine.ts` at R3-5 (`pushPhase` FIFO loop + `case 'network': return replayed` + the `runCycle` ordering that always calls `pullPhase()`). If a test failed above, the fix is in the existing `pushPhase`/`classifyThrow`; otherwise no code change.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts
```

Expected: 1 file passed, 11 tests passed (7 from R3-5 + 4 here).

- [ ] **Commit**

```bash
git add packages/app/src/sync/__tests__/SyncEngine.push.test.ts
git commit -m "test(sync): push-loop FIFO drain + network-abort taxonomy (§3.3)

Pins replay order, per-op 2xx deletion, network-throw abort leaving the
failing op + successors queued, pull-runs-when-push-halts, and next-trigger
recovery. Behavior already shipped in SyncEngine R3-5; these lock it.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-7: 5xx policy — in-cycle retries + backoff, attempts persisted, park at threshold; retry/discard

**Files:**
- Test: `packages/app/src/sync/__tests__/SyncEngine.push.test.ts` (append)

Spec §3.3's 5xx ladder: up to `CYCLE_5XX_RETRIES` retries within the cycle with `CYCLE_5XX_BACKOFF_MS` waits (fake timers), `attempts` persisted on the op across cycles, the push phase aborts after the in-cycle retries are exhausted, and once `attempts >= PARK_THRESHOLD` the op is **parked** (moved to `issues`, removed from `ops`). `retryIssue` re-queues a parked op (attempts reset); `discardIssue` removes it + runs rejection recovery (here: schedules the `pendingRecovery` meta flag R4 honors).

- [ ] **Write failing test** — append to `packages/app/src/sync/__tests__/SyncEngine.push.test.ts`:

```ts
describe('5xx policy — in-cycle retry/backoff + parking (spec §3.3)', () => {
  it('retries a 5xx within the cycle up to CYCLE_5XX_RETRIES with backoff, then aborts the phase', async () => {
    vi.useFakeTimers();
    const store = freshStore();
    let attempts = 0;
    // Always 503: the op is sent 1 + CYCLE_5XX_RETRIES times within one cycle.
    const t = spyTransport(() => {
      attempts++;
      return { status: 503, body: { title: 'unavailable' } };
    });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    const cycle = engine.syncNow();
    // 1 immediate + 3 retries = 4 sends, gated behind 1s/2s/4s backoff.
    await vi.advanceTimersByTimeAsync(1000 + 2000 + 4000 + 10);
    await cycle;

    expect(attempts).toBe(1 + 3); // CYCLE_5XX_RETRIES = 3
    const head = await store.ops.orderBy('seq').first();
    expect(head).toBeDefined();           // op survived (phase aborted)
    expect(head!.attempts).toBeGreaterThanOrEqual(3); // attempts persisted
    expect(head!.lastError).toContain('503');
  });

  it('parks an op once attempts reach PARK_THRESHOLD (moved to issues, removed from ops)', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 500, body: { title: 'boom' } }));
    // Pre-seed attempts so a single 500 pushes it to the threshold.
    await store.ops.add(op({
      method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' },
      entityKeys: ['tasks:a'], attempts: 9, // +1 this send → 10 = PARK_THRESHOLD
    }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.ops.count()).toBe(0); // parked → off the queue
    const issues = await store.issues.toArray();
    expect(issues).toHaveLength(1);
    expect(issues[0].status).toBe('parked');
    expect(issues[0].op.path).toBe('/api/v1/tasks/a');
  });

  it('retryIssue re-queues a parked op with attempts reset and clears the issue', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 200 }));
    await store.issues.add({
      at: new Date().toISOString(),
      op: op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'], attempts: 10, lastError: 'HTTP 500' }),
      reason: 'parked', status: 'parked',
    });
    const issue = (await store.issues.toArray())[0];
    const engine = new SyncEngine({ store, transport: t });

    await engine.retryIssue(issue.id!);

    expect(await store.issues.count()).toBe(0);
    const requeued = await store.ops.orderBy('seq').first();
    expect(requeued!.path).toBe('/api/v1/tasks/a');
    expect(requeued!.attempts).toBe(0);          // reset
    expect(requeued!.lastError).toBeUndefined();
  });

  it('discardIssue removes a parked op and schedules rejection recovery', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 200 }));
    await store.issues.add({
      at: new Date().toISOString(),
      op: op({ method: 'POST', path: '/api/v1/projects', body: { id: 'p1', name: 'X' }, entityKeys: ['projects:p1'], kind: 'create', attempts: 10 }),
      reason: 'parked', status: 'parked',
    });
    const issue = (await store.issues.toArray())[0];
    const engine = new SyncEngine({ store, transport: t });

    await engine.discardIssue(issue.id!);

    expect(await store.issues.count()).toBe(0);
    expect(await store.getMeta<boolean>('pendingRecovery')).toBe(true); // recovery scheduled (R4 honors)
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts -t "5xx policy"
```

As in R3-6, the R3-5 implementation already covers the 5xx ladder, parking, and `retryIssue`/`discardIssue`; run the filter to confirm the four new tests are reported and PASS. If any FAIL, fix `pushPhase` (retry loop), `parkOp`, `retryIssue`, or `discardIssue` until green — never weaken a test.

Expected: the four new `5xx policy` tests PASS.

- [ ] **Minimal impl** — none required (shipped in `SyncEngine.ts` at R3-5: the `while (outcome.kind === 'retry5xx' …)` ladder with `this.sleep(CYCLE_5XX_BACKOFF_MS[attempt])`, the `attempts >= PARK_THRESHOLD ? 'park' : 'retry5xx'` branch, `parkOp`, `retryIssue`, `discardIssue → scheduleRecovery`). Fix forward only if a test failed.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts
```

Expected: 1 file passed, 15 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/sync/__tests__/SyncEngine.push.test.ts
git commit -m "test(sync): 5xx in-cycle retry/backoff + poison-op parking + retry/discard (§3.3)

Locks the 1s/2s/4s backoff ladder (fake timers), persisted attempts across
cycles, parking at PARK_THRESHOLD, retryIssue re-queue (attempts reset), and
discardIssue → pendingRecovery scheduling (R4 honors the flag).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-8: Definitive rejection — drop + issue + ghost-row recovery + 404-delete success

**Files:**
- Test: `packages/app/src/sync/__tests__/SyncEngine.push.test.ts` (append)

Spec §3.3: a definitive 4xx (400 / 404-on-non-delete / other 4xx except 401 and 409-create) **drops** the op, records a `dropped` issue, and — when the op is a `create` — deletes the local rows it created (each `entityKeys` row) and drops every queued op referencing any of those keys (each recorded), then schedules a recovery pull (the `pendingRecovery` meta flag). A **404 on a DELETE op is a success** (already tombstoned — §3.2), recording no issue.

- [ ] **Write failing test** — append to `packages/app/src/sync/__tests__/SyncEngine.push.test.ts`:

```ts
describe('definitive rejection — drop + ghost-row recovery (spec §3.3, §3.2)', () => {
  it('404 on a DELETE op is treated as success: op removed, no issue', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 404, body: { title: 'Not found' } }));
    await store.ops.add(op({ method: 'DELETE', path: '/api/v1/tasks/gone', entityKeys: ['tasks:gone'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.ops.count()).toBe(0);   // dequeued as done
    expect(await store.issues.count()).toBe(0); // no issue (§3.2)
  });

  it('404 on a PATCH (edit-vs-delete) drops the op, records an issue, schedules recovery', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 404, body: { title: 'Not found' } }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/x', body: { title: 'edited' }, entityKeys: ['tasks:x'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.ops.count()).toBe(0);
    const issues = await store.issues.toArray();
    expect(issues).toHaveLength(1);
    expect(issues[0].status).toBe('dropped');
    expect(issues[0].op.method).toBe('PATCH');
    expect(await store.getMeta<boolean>('pendingRecovery')).toBe(true);
  });

  it('400 on a rejected CREATE deletes the ghost local row + drops dependent queued ops', async () => {
    const store = freshStore();
    // The create gets a 400; later PATCH against the same ghost id must be dropped too.
    const t = spyTransport((o) => (o.method === 'POST' ? { status: 400, body: { title: 'Bad' } } : { status: 200 }));
    // Ghost local row that the create produced:
    await store.tasks.put({
      id: 'ghost', title: 'Ghost', notes: '', projectId: null, labels: [], source: 'manual',
      status: 'Inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
      durationMinutes: 0, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
      rank: 'a0', dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
      recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
      createdAt: 'a', updatedAt: 'b', changeSeq: 0, deletedAt: null,
    } as never);
    await store.ops.add(op({ method: 'POST', path: '/api/v1/tasks', body: { id: 'ghost' }, entityKeys: ['tasks:ghost'], kind: 'create' }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/ghost', body: { title: 'edit' }, entityKeys: ['tasks:ghost'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.tasks.get('ghost')).toBeUndefined(); // ghost row deleted
    expect(await store.ops.count()).toBe(0);                // both ops gone
    const issues = await store.issues.toArray();
    // one for the create, one for the dependent PATCH (each recorded)
    expect(issues.length).toBe(2);
    expect(issues.some((i) => i.op.method === 'POST')).toBe(true);
    expect(issues.some((i) => i.op.method === 'PATCH')).toBe(true);
    expect(await store.getMeta<boolean>('pendingRecovery')).toBe(true);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts -t "definitive rejection"
```

The R3-5 impl already implements `dropOp` / `recoverGhostRows` / the `404 && DELETE → done` short-circuit; run the filter to confirm the three tests are reported and PASS. Fix forward only on a failure (in `replayOnce` classification or `recoverGhostRows`).

Expected: the three new `definitive rejection` tests PASS.

- [ ] **Minimal impl** — none required (shipped at R3-5: `replayOnce` returns `{ kind: 'done' }` for `404 && DELETE`, `{ kind: 'drop', recover: op.kind === 'create' }` for other definitive 4xx; `dropOp` records the issue, calls `recoverGhostRows` for creates, deletes the op, and `scheduleRecovery` sets `pendingRecovery`). Fix forward only if a test failed.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts
```

Expected: 1 file passed, 18 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/sync/__tests__/SyncEngine.push.test.ts
git commit -m "test(sync): definitive-rejection drop + ghost-row recovery + 404-delete success (§3.3, §3.2)

404 on DELETE = success (no issue); 404 on PATCH (edit-vs-delete) drops +
records + schedules recovery; a rejected create deletes its ghost local row
and drops dependent queued ops (each recorded) + schedules recovery.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-9: 409 adopt-existing — id rewrite across local tables + queued ops (spec §3.3)

**Files:**
- Test: `packages/app/src/sync/__tests__/SyncEngine.push.test.ts` (append)

Spec §3.3 adopt-existing: a `create` op that gets `409` with `extensions.existingId` triggers a ghost→existing id rewrite **across all local tables** (the row itself + FK fields: `tasks.projectId`, `notes.projectId`, `noteGroups.projectId`, `notes.groupId`, `subtasks.taskId`, `tasks.recurrenceRuleId`) **and all queued op bodies/paths/entityKeys**, then drops the create op and records an informational issue. The end-to-end scenario is a project-name collision: device queued a `POST /projects` for a ghost project plus a task referencing it; on push the server returns 409+existingId, and the ghost is adopted into the existing project with the task repointed.

- [ ] **Write failing test** — append to `packages/app/src/sync/__tests__/SyncEngine.push.test.ts`:

```ts
describe('409 adopt-existing — ghost→existing id rewrite (spec §3.3)', () => {
  it('project name collision: adopts existing id across local rows + queued ops, drops the create', async () => {
    const store = freshStore();
    // Server says the ghost project name already exists as 'existing-proj'.
    const t = spyTransport((o) => {
      if (o.method === 'POST' && o.path === '/api/v1/projects') {
        return { status: 409, body: { title: 'Duplicate name', extensions: { existingId: 'existing-proj' } } };
      }
      return { status: 200 };
    });

    // Local ghost project row + a task filed under it.
    await store.projects.put({
      id: 'ghost-proj', name: 'Inbox-Zero', color: '#fff', emoji: '📁',
      rank: 'a0', createdAt: 'a', updatedAt: 'b', changeSeq: 0, deletedAt: null,
    } as never);
    await store.tasks.put({
      id: 'task-1', title: 'filed', notes: '', projectId: 'ghost-proj', labels: [], source: 'manual',
      status: 'Inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
      durationMinutes: 0, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
      rank: 'a0', dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
      recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
      createdAt: 'a', updatedAt: 'b', changeSeq: 0, deletedAt: null,
    } as never);

    // Queued: the project create (ghost) + a task create whose body references the ghost projectId.
    await store.ops.add(op({
      method: 'POST', path: '/api/v1/projects',
      body: { id: 'ghost-proj', name: 'Inbox-Zero', color: '#fff', emoji: '📁', rank: 'a0' },
      entityKeys: ['projects:ghost-proj'], kind: 'create',
    }));
    await store.ops.add(op({
      method: 'POST', path: '/api/v1/tasks',
      body: { id: 'task-1', title: 'filed', projectId: 'ghost-proj', rank: 'a0' },
      entityKeys: ['tasks:task-1'], kind: 'create',
    }));

    const engine = new SyncEngine({ store, transport: t });
    await engine.syncNow();

    // Ghost project row is gone; the task is repointed to the existing project.
    expect(await store.projects.get('ghost-proj')).toBeUndefined();
    expect((await store.tasks.get('task-1'))!.projectId).toBe('existing-proj');

    // The task create op had its body.projectId rewritten and was replayed (no ghost reference).
    const sentTaskCreate = t.sent.find((o) => o.path === '/api/v1/tasks');
    expect((sentTaskCreate!.body as { projectId: string }).projectId).toBe('existing-proj');

    // The project create op was dropped; an informational issue recorded.
    expect(await store.ops.count()).toBe(0);
    const issues = await store.issues.toArray();
    expect(issues).toHaveLength(1);
    expect(issues[0].reason).toContain('existing-proj');
    expect(issues[0].op.path).toBe('/api/v1/projects');
  });

  it('rewrites a queued op path + entityKeys that reference the ghost id', async () => {
    const store = freshStore();
    const t = spyTransport((o) => {
      if (o.method === 'POST' && o.path === '/api/v1/projects') {
        return { status: 409, body: { extensions: { existingId: 'P-real' } } };
      }
      return { status: 200 };
    });
    await store.projects.put({
      id: 'P-ghost', name: 'Dup', color: '#fff', emoji: '📁',
      rank: 'a0', createdAt: 'a', updatedAt: 'b', changeSeq: 0, deletedAt: null,
    } as never);
    await store.ops.add(op({
      method: 'POST', path: '/api/v1/projects',
      body: { id: 'P-ghost', name: 'Dup' }, entityKeys: ['projects:P-ghost'], kind: 'create',
    }));
    // A later PATCH against the ghost project's own path + entityKey.
    await store.ops.add(op({
      method: 'PATCH', path: '/api/v1/projects/P-ghost',
      body: { color: '#123' }, entityKeys: ['projects:P-ghost'],
    }));

    const engine = new SyncEngine({ store, transport: t });
    await engine.syncNow();

    const sentPatch = t.sent.find((o) => o.method === 'PATCH');
    expect(sentPatch!.path).toBe('/api/v1/projects/P-real');         // path rewritten
    expect(sentPatch!.entityKeys).toEqual(['projects:P-real']);      // entityKeys rewritten
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts -t "409 adopt-existing"
```

The R3-5 impl ships `adoptExisting` (row delete + `repointForeignKeys` + queued-op path/body/entityKeys rewrite + create-op drop + issue). Run the filter to confirm the two tests are reported and PASS. Fix forward only on failure (in `adoptExisting`, `repointForeignKeys`, or `rewriteBodyIds`).

Expected: the two new `409 adopt-existing` tests PASS.

- [ ] **Minimal impl** — none required (shipped at R3-5). Fix forward only if a test failed.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts
```

Expected: 1 file passed, 20 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/sync/__tests__/SyncEngine.push.test.ts
git commit -m "test(sync): 409 adopt-existing id rewrite across rows + queued ops (§3.3)

Project name collision end-to-end: ghost project deleted, FK-repointed task
repointed to the existing id, queued op bodies/paths/entityKeys rewritten, the
create op dropped, an informational issue recorded — queue never wedges (AC10).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-10: Post-push ensure-instances trigger (spec §4.3)

**Files:**
- Test: `packages/app/src/sync/__tests__/SyncEngine.push.test.ts` (append)

Spec §4.3: when a push phase successfully replays **any** op with `regenAfterPush` (a `POST /recurrence` series create or a `PATCH /recurrence/rules/{id}` updateRule — both reset `GeneratedUntil`), the engine makes **one** `transport.ensureInstances(today, today + ENSURE_HORIZON_DAYS)` call **before** the pull phase. No such op → no ensure-instances call. This phase asserts the call happens (and its horizon); R4 applies the returned rows.

- [ ] **Write failing test** — append to `packages/app/src/sync/__tests__/SyncEngine.push.test.ts`:

```ts
import { ENSURE_HORIZON_DAYS } from '../constants';

describe('post-push ensure-instances trigger (spec §4.3)', () => {
  it('calls ensureInstances ONCE when a replayed op had regenAfterPush, before pull', async () => {
    const store = freshStore();
    const order: string[] = [];
    const t = spyTransport((o) => { order.push(`send:${o.path}`); return { status: 200 }; });
    const origEnsure = t.ensureInstances.bind(t);
    t.ensureInstances = async (s: string, e: string) => { order.push('ensure'); return origEnsure(s, e); };
    const origPull = t.pull.bind(t);
    (t as { pull: () => Promise<unknown> }).pull = async () => { order.push('pull'); return origPull(); };

    await store.ops.add(op({
      method: 'POST', path: '/api/v1/recurrence',
      body: { task: { id: 'tmpl' }, rule: { id: 'r1', frequency: 'Daily' } },
      entityKeys: ['tasks:tmpl', 'recurrenceRules:r1'], kind: 'create', regenAfterPush: true,
    }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(t.ensured).toHaveLength(1);                 // exactly one ensure call
    // Ordering: the create was sent, then ensure, then pull.
    expect(order.indexOf('ensure')).toBeGreaterThan(order.indexOf('send:/api/v1/recurrence'));
    expect(order.indexOf('pull')).toBeGreaterThan(order.indexOf('ensure'));

    // Horizon = [today, today + ENSURE_HORIZON_DAYS].
    const [start, end] = t.ensured[0];
    const days = (Date.parse(`${end}T00:00:00Z`) - Date.parse(`${start}T00:00:00Z`)) / 86_400_000;
    expect(Math.round(days)).toBe(ENSURE_HORIZON_DAYS);
  });

  it('does NOT call ensureInstances when no replayed op had regenAfterPush', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 200 }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });
    await engine.syncNow();
    expect(t.ensured).toHaveLength(0);
  });

  it('does NOT call ensureInstances when the regenAfterPush op failed to replay (network abort)', async () => {
    const store = freshStore();
    const t = spyTransport(() => { throw Object.assign(new TypeError('offline'), { name: 'TypeError' }); });
    await store.ops.add(op({
      method: 'POST', path: '/api/v1/recurrence', body: { task: { id: 'tmpl' }, rule: { id: 'r1' } },
      entityKeys: ['tasks:tmpl', 'recurrenceRules:r1'], kind: 'create', regenAfterPush: true,
    }));
    const engine = new SyncEngine({ store, transport: t });
    await engine.syncNow();
    expect(t.ensured).toHaveLength(0); // op never succeeded → no regen
    expect(t.pulls).toBe(1);           // pull still ran (§3.3)
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts -t "ensure-instances"
```

The R3-5 `runCycle` already gates `runEnsureInstances()` on `replayed.some((o) => o.regenAfterPush)` and sequences it before `pullPhase()`. Run the filter to confirm the three tests are reported and PASS. Fix forward only on failure (in `runCycle` ordering or `runEnsureInstances` horizon math).

Expected: the three new `ensure-instances` tests PASS.

- [ ] **Minimal impl** — none required (shipped at R3-5: `runCycle` → `if (replayed.some(o => o.regenAfterPush)) await runEnsureInstances()` before `pullPhase()`; `runEnsureInstances` computes `[today, today + ENSURE_HORIZON_DAYS]`). Fix forward only if a test failed.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts
```

Expected: 1 file passed, 23 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/sync/__tests__/SyncEngine.push.test.ts
git commit -m "test(sync): post-push ensure-instances trigger before pull (§4.3)

One ensureInstances(today, today+ENSURE_HORIZON_DAYS) call when (and only when)
a successfully-replayed op carried regenAfterPush; sequenced after push and
before pull; not fired when the regen op failed to replay.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-11: 401 handling — terminal stop vs transient network (spec §7.3)

**Files:**
- Test: `packages/app/src/sync/__tests__/SyncEngine.push.test.ts` (append)

Spec §7.3 (engine's classification half): when the transport throws the refreshClient terminal error (`'Session expired — refresh failed'`), the engine **stops** (no further cycles; the op stays queued for a later re-login). A transient `NetworkError` thrown from the refresh path (or any non-terminal throw) is treated like a **network abort** — push aborts, queue intact, the engine remains usable. A direct `401` status from `transport.send` (no terminal-message throw) is also terminal (the refresh wrapper would have already attempted refresh; a surfaced 401 means it's dead). The full `refreshClient` change is R5; here the engine classifies by error name/message only.

- [ ] **Write failing test** — append to `packages/app/src/sync/__tests__/SyncEngine.push.test.ts`:

```ts
describe('401 handling — terminal vs transient (spec §7.3)', () => {
  it('a terminal refresh failure stops the engine; the op stays queued', async () => {
    const store = freshStore();
    const t = spyTransport(() => { throw new Error('Session expired — refresh failed'); });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.ops.count()).toBe(1); // op intact — re-login can push it later
    // Engine is terminal: a further syncNow is a no-op (no more sends).
    const sentBefore = t.sent.length;
    await engine.syncNow();
    expect(t.sent.length).toBe(sentBefore);
  });

  it('a surfaced 401 status (refresh wrapper exhausted) is terminal too', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 401, body: { title: 'Unauthorized' } }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.ops.count()).toBe(1);
    const sentBefore = t.sent.length;
    await engine.syncNow();
    expect(t.sent.length).toBe(sentBefore); // terminal → no further sends
  });

  it('a transient NetworkError from refresh is a network abort, not terminal', async () => {
    const store = freshStore();
    let fail = true;
    const t = spyTransport(() => {
      if (fail) throw Object.assign(new Error('refresh transient failure'), { name: 'NetworkError' });
      return { status: 200 };
    });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();                 // transient → abort, op intact, NOT terminal
    expect(await store.ops.count()).toBe(1);
    fail = false;
    await engine.syncNow();                 // engine still alive → drains now
    expect(await store.ops.count()).toBe(0);
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts -t "401 handling"
```

The R3-5 impl ships `classifyThrow` (message === `TERMINAL_MESSAGE` → terminal, else network) and `replayOnce` (`status === 401 → { kind: 'terminal' }`), with `pushPhase` setting `this.terminal = true; this.stop()` on terminal and `syncNow` early-returning when `terminal`. Run the filter to confirm the three tests are reported and PASS. Fix forward only on failure (in `classifyThrow` or the terminal handling in `pushPhase`/`syncNow`).

Expected: the three new `401 handling` tests PASS.

- [ ] **Minimal impl** — none required (shipped at R3-5). Fix forward only if a test failed.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.push.test.ts
```

Expected: 1 file passed, 26 tests passed.

- [ ] **Commit**

```bash
git add packages/app/src/sync/__tests__/SyncEngine.push.test.ts
git commit -m "test(sync): 401 terminal-stop vs transient-network classification (§7.3)

Terminal refresh failure ('Session expired — refresh failed') or a surfaced
401 status stops the engine with the op intact (re-login pushes later); a
transient NetworkError from refresh is a plain network abort and the engine
stays alive for the next trigger.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R3-12: Phase R3 green gate (HARD GATE)

**Files:** none (verification only — no commit unless a fix is needed, in which case fix forward with its own TDD loop before re-running the gate from the top).

- [ ] **Run the four new R3 suites** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/connectivity.test.ts src/sync/__tests__/fakeSyncServer.test.ts src/sync/__tests__/SyncTransport.test.ts src/sync/__tests__/SyncEngine.push.test.ts
```

Expected: **4 files passed**, **46 tests passed** — connectivity (4) + fakeSyncServer (9) + SyncTransport (7) + SyncEngine.push (26). Zero failures, zero skips.

- [ ] **Run the full client suite** — from the repo root:

```bash
npm run test:app
```

Expected: all R1/R2 suites stay green at their counts, plus the four new R3 files (connectivity, fakeSyncServer, SyncTransport, SyncEngine.push = 46 tests). `constants.ts` adds no test file (mechanical task). Gate condition: **zero failures, zero skips**, with all four new R3 test files present and green; the suite total is the R2-green-gate count **+ 46**.

- [ ] **Run the app typecheck** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: `tsc --noEmit` exits 0. This is the real gate for the two non-test modules in the include set that vitest does not strictly type-check at runtime: `constants.ts` and the `fakeSyncServer.ts` harness (excluded from vitest's run, included in `tsc`). It also proves `SyncEngine.ts`/`SyncTransport.ts`/`connectivity.ts` compose under strict mode against R1's `LocalStore`/`types`/`rows` and R2's `LocalDataClient`.

- [ ] **Run both app builds** — from the repo root:

```bash
npm run build:apps
```

Expected: desktop (`tmap`) and web (`@tmap/web`) builds both succeed — the new `src/sync/*` modules bundle into both shells (the harness under `__tests__/` is not imported by production code, so it never reaches the bundle).

- [ ] **Gate** — backend tests are NOT run here: R3 touches no backend file (R0 already gated the backend sync slice).

**HARD GATE: do not start Phase R4 until all four commands above are green. If any fails, fix forward within R3 (test-first for behavior fixes; the `SyncEngine.ts` impl was written in full at R3-5, so a regression is fixed there, never by weakening a test), re-run the full gate from the top, and only then proceed.** Note for R4: `pullPhase()` is the intentional no-op seam (it currently issues a harmless `transport.pull(0,0)` so the cycle's pull-always-runs assertions hold); R4 replaces its body with the real cursor/applyPull/recovery/initialSyncComplete logic without changing the cycle structure, `start/stop`, single-flight, or any push-side behavior pinned here.

## Phase R4 — SyncEngine pull side + applyPull (cursor, shadow rule, recovery, gate flag, first-cycle settle)

This phase completes the SyncEngine cycle: it adds the **pull half** on top of the R3 push half. The cycle becomes the full single-flight **push → post-push ensure-instances → pull (paged) → recovery pull (if scheduled) → notify** (contract C5; spec §4). New production module `applyPull.ts` writes a pulled page into the local store in one Dexie rw transaction (tombstone-delete or upsert per row, with the §4.2 shadow rule). The engine gains: cursor-overlap paged pulling that never regresses `meta.syncCursor`; `meta.initialSyncComplete` set once at `hasMore=false`; the `meta.lastSyncedAt` cycle stamp; the `changesApplied` emission; full-from-`since=0` **recovery pull** when R3 scheduled one (dropped/discarded op); pull-phase application of the **ensure-instances** rows returned by the R3 post-push hook; and `onFirstCycleSettled` firing exactly once per `start()` (success OR a cycle that cannot even begin offline). Every behavior is proven against the shared R3 `fakeSyncServer` harness.

**Created:**
- `packages/app/src/data/local/applyPull.ts` — `applyPullPage(store, changes)`: per-table, per-row tombstone-delete / upsert in one rw tx, shadow rule via the `ops` multiEntry index (contract C5, spec §4.2)
- `packages/app/src/data/local/__tests__/applyPull.test.ts` — upserts, tombstone deletes, shadow skip for uuid/date/key entities, mixed page, empty page

**Modified:**
- `packages/app/src/sync/SyncEngine.ts` — add the pull phase, cursor protocol, `initialSyncComplete`, `lastSyncedAt`, `changesApplied`, recovery pull, ensure-instances application, and `onFirstCycleSettled` (the R3 file already owns `start`/`stop`/`requestSync`/`syncNow`/the push loop/`subscribe`/the recovery-scheduled flag/the `regenAfterPush` post-push hook)
- `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts` — created here; pull-side unit + integration tests against `fakeSyncServer`

**Preconditions:** R1 (`LocalStore`, `rows.ts`, `sync/types.ts` incl. `entityKey`), R2 (`LocalDataClient`), and R3 (`SyncEngine` push half, `SyncTransport`, `constants.ts`, `connectivity.ts`, and the shared `packages/app/src/sync/__tests__/fakeSyncServer.ts`) are merged on this branch.

> **Contract-gap note (R3 internal engine surface).** The skeleton pins both the engine's *public* surface (C5) and its *pinned internals* (C5 "Pinned engine internals" block); R3 implements them with these exact names. R4 reaches into R3's cycle using them — the **behavior** (cursor math, shadow rule, gate flag, recovery trigger, settle-once) is authoritative and from the contracts/spec, and must not be weakened:
> - `private async runCycle(): Promise<void>` — the single-flight body R3 wraps in the Web Lock / in-process mutex. It already calls `await this.pullPhase()` after the push loop and the `regenAfterPush` ensure-instances hook; R4 fills in the `pullPhase()` body only (it does NOT edit `runCycle`).
> - `protected async pullPhase(): Promise<void>` — a no-op stub in R3; R4 replaces the method body with the real cursor/applyPull/recovery/gate logic.
> - Recovery flag: the **persisted `pendingRecovery` meta key** (`store.setMeta('pendingRecovery', true)` / `getMeta('pendingRecovery')`) — set by R3's `scheduleRecovery()` on a dropped/definitively-rejected op and by `discardIssue` (spec §3.3). R4 reads it and clears it (`setMeta('pendingRecovery', false)`) in the pull phase. It is NOT an in-memory field.
> - `private pendingEnsureRows: TaskSyncRow[] | null` — R3's `runEnsureInstances` (R3-10) stores the `TaskSyncRow[]` returned by `transport.ensureInstances(...)` here when any replayed op had `regenAfterPush`; null when none. R4 applies these via `applyPull` **before** the pull phase, then nulls it.
> - `private store: LocalStore`, `private transport: SyncTransport`, `private emitStatus(): void` (refreshes the status cache + pushes a fresh `getStatus()` to `subscribe` callbacks — there is no `emit()`), `private changesAppliedCbs: Array<() => void>`, `private firstCycleSettledCbs: Array<() => void>` (callback arrays for `onChangesApplied` / `onFirstCycleSettled`). All exist in R3; R4 only re-arms `firstCycleSettled` in `start()` (R4-5).
> - `online(): boolean` (C5) already exists from R3 (delegates to `connectivity`).
> - `meta` keys are the C3 strings verbatim: `'syncCursor'` (number), `'initialSyncComplete'` (boolean), `'lastSyncedAt'` (ISO string), `'pendingRecovery'` (boolean). `getMeta`/`setMeta` are the C3 helpers.

> **Contract-gap note (fakeSyncServer surface).** R4 uses ONLY the C5a harness surface that R3 already ships (R3-3): `new FakeSyncServer()`, `server.transport(): SyncTransport`, `server.seed(table, row): void` (stamps the next global `changeSeq`), `server.tombstone(table, key): void`, `server.bumpSeq(table, key, patch?): void` (apply a patch — or a no-op touch — to an existing row and stamp a fresh `changeSeq`, simulating "another device wrote" without an op), `server.failNext(matcher, fault): void` (one-shot fault; rejections use `{ status, body }` — there is NO `rejectNext`), `server.pullCount(): number`, and `server.latency(ms): void`. All are implemented and proven in `fakeSyncServer.ts`/`fakeSyncServer.test.ts` (R3); R4 does NOT add or modify harness methods.

---

### Task R4-1: `applyPull.ts` — apply a pulled page (tombstone-delete / upsert / shadow rule)

**Files:**
- Create: `packages/app/src/data/local/applyPull.ts`
- Test: `packages/app/src/data/local/__tests__/applyPull.test.ts`

`applyPullPage(store, changes)` writes one pulled page (`SyncChanges`) into the local store in **one Dexie rw transaction** over all nine entity tables plus `ops` (the shadow read). Per table, per row (spec §4.2):
- `deletedAt != null` → **delete the local row by that table's key** (tombstones are removed locally — local tables hold live rows only, spec §2).
- else → **`put`** the row (idempotent upsert-by-key).
- **Shadow rule:** skip any row whose `entityKey('<table>', key)` appears in *any* pending op's `entityKeys` — read the `ops` table once per page and build the shadow set from the multiEntry-indexed `entityKeys`, so a write made mid-pull is not clobbered by an older pulled value; the next cycle reconciles it.

Per-table primary key: `id` for the seven uuid tables (`tasks`, `subtasks`, `projects`, `noteGroups`, `notes`, `recurrenceRules`, `focusSessions`), `date` for `dailyPlans`, `key` for `settings`. The function returns whether the page applied any row (used by the engine's per-cycle `changesApplied` gate).

- [ ] **Write failing test** — create `packages/app/src/data/local/__tests__/applyPull.test.ts` (`fake-indexeddb/auto` MUST be the first import):

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalStore } from '../LocalStore';
import { applyPullPage } from '../applyPull';
import { entityKey, type SyncOp } from '../../../sync/types';
import type {
  TaskSyncRow,
  DailyPlanSyncRow,
  SettingSyncRow,
  SyncChanges,
} from '../rows';

let nextUser = 0;
const openStores: LocalStore[] = [];
function openFresh(): LocalStore {
  const s = LocalStore.open(`pull-u${Date.now()}-${nextUser++}`);
  openStores.push(s);
  return s;
}
afterEach(() => {
  for (const s of openStores.splice(0)) s.close();
});

function taskRow(id: string, overrides: Partial<TaskSyncRow> = {}): TaskSyncRow {
  return {
    id,
    title: `Task ${id}`,
    notes: '',
    projectId: null,
    labels: [],
    source: 'manual',
    status: 'Planned',
    plannedDate: '2026-06-11',
    scheduledStart: null,
    scheduledEnd: null,
    durationMinutes: 30,
    actualTimeMinutes: 0,
    priority: null,
    reminderMinutes: null,
    rank: 'a0',
    dueDate: null,
    recurrenceRuleId: null,
    isRecurrenceTemplate: false,
    recurrenceDetached: false,
    recurrenceOriginalDate: null,
    completedAt: null,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 1,
    deletedAt: null,
    ...overrides,
  } as TaskSyncRow;
}

function planRow(date: string, overrides: Partial<DailyPlanSyncRow> = {}): DailyPlanSyncRow {
  return {
    date,
    committedAt: '2026-06-11T08:00:00Z',
    plannedTaskIds: [],
    plannedMinutes: 0,
    changeSeq: 1,
    deletedAt: null,
    ...overrides,
  } as DailyPlanSyncRow;
}

function settingRow(key: string, value: string, overrides: Partial<SettingSyncRow> = {}): SettingSyncRow {
  return {
    key,
    value,
    changeSeq: 1,
    deletedAt: null,
    ...overrides,
  } as SettingSyncRow;
}

/** An empty SyncChanges with all nine tables present (the wire envelope shape, C6). */
function emptyChanges(): SyncChanges {
  return {
    tasks: [],
    subtasks: [],
    projects: [],
    noteGroups: [],
    notes: [],
    recurrenceRules: [],
    focusSessions: [],
    dailyPlans: [],
    settings: [],
  } as SyncChanges;
}

async function enqueue(store: LocalStore, op: Omit<SyncOp, 'seq' | 'attempts'>): Promise<void> {
  await store.ops.add({ attempts: 0, ...op } as SyncOp);
}

describe('applyPullPage — upserts', () => {
  it('inserts new rows and overwrites existing rows by key across uuid/date/key tables', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1', { title: 'old', changeSeq: 1 }));

    const changes = emptyChanges();
    changes.tasks = [
      taskRow('t1', { title: 'new', changeSeq: 5 }), // overwrite
      taskRow('t2', { title: 'fresh', changeSeq: 6 }), // insert
    ];
    changes.dailyPlans = [planRow('2026-06-11', { plannedMinutes: 90, changeSeq: 7 })];
    changes.settings = [settingRow('workStartHour', '6', { changeSeq: 8 })];

    const applied = await applyPullPage(store, changes);

    expect(applied).toBe(true);
    expect((await store.tasks.get('t1'))!.title).toBe('new');
    expect((await store.tasks.get('t1'))!.changeSeq).toBe(5);
    expect((await store.tasks.get('t2'))!.title).toBe('fresh');
    expect((await store.dailyPlans.get('2026-06-11'))!.plannedMinutes).toBe(90);
    expect((await store.settings.get('workStartHour'))!.value).toBe('6');
  });

  it('an empty page applies nothing and reports applied=false', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1'));
    const applied = await applyPullPage(store, emptyChanges());
    expect(applied).toBe(false);
    expect(await store.tasks.count()).toBe(1);
  });
});

describe('applyPullPage — tombstone deletes', () => {
  it('deletes the local row when deletedAt != null, by each table key', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1'));
    await store.dailyPlans.put(planRow('2026-06-11'));
    await store.settings.put(settingRow('workStartHour', '8'));

    const changes = emptyChanges();
    changes.tasks = [taskRow('t1', { deletedAt: '2026-06-12T00:00:00Z', changeSeq: 9 })];
    changes.dailyPlans = [planRow('2026-06-11', { deletedAt: '2026-06-12T00:00:00Z', changeSeq: 10 })];
    changes.settings = [settingRow('workStartHour', '8', { deletedAt: '2026-06-12T00:00:00Z', changeSeq: 11 })];

    const applied = await applyPullPage(store, changes);

    expect(applied).toBe(true);
    expect(await store.tasks.get('t1')).toBeUndefined();
    expect(await store.dailyPlans.get('2026-06-11')).toBeUndefined();
    expect(await store.settings.get('workStartHour')).toBeUndefined();
  });

  it('a tombstone for an already-absent row is a harmless no-op', async () => {
    const store = openFresh();
    const changes = emptyChanges();
    changes.tasks = [taskRow('ghost', { deletedAt: '2026-06-12T00:00:00Z' })];
    const applied = await applyPullPage(store, changes);
    expect(applied).toBe(true); // the page carried a row even though delete was a no-op
    expect(await store.tasks.get('ghost')).toBeUndefined();
  });
});

describe('applyPullPage — shadow rule (skip rows with a pending op on the same entity key)', () => {
  it('skips an upsert for a uuid entity shadowed by a pending op', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1', { title: 'local edit', changeSeq: 1 }));
    // Pending op touches tasks:t1 — a mid-pull local write.
    await enqueue(store, {
      method: 'PATCH',
      path: '/api/v1/tasks/t1',
      body: { title: 'local edit' },
      entityKeys: [entityKey('tasks', 't1')],
      kind: 'other',
    });

    const changes = emptyChanges();
    changes.tasks = [
      taskRow('t1', { title: 'stale remote', changeSeq: 4 }), // shadowed → skipped
      taskRow('t2', { title: 'remote t2', changeSeq: 5 }), // not shadowed → applied
    ];

    const applied = await applyPullPage(store, changes);

    expect(applied).toBe(true); // t2 applied
    expect((await store.tasks.get('t1'))!.title).toBe('local edit'); // NOT clobbered
    expect((await store.tasks.get('t2'))!.title).toBe('remote t2');
  });

  it('shadow rule also blocks a tombstone delete for a shadowed row', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1', { title: 'kept' }));
    await enqueue(store, {
      method: 'PATCH',
      path: '/api/v1/tasks/t1',
      body: { title: 'kept' },
      entityKeys: [entityKey('tasks', 't1')],
      kind: 'other',
    });
    const changes = emptyChanges();
    changes.tasks = [taskRow('t1', { deletedAt: '2026-06-12T00:00:00Z', changeSeq: 9 })];
    await applyPullPage(store, changes);
    expect(await store.tasks.get('t1')).toBeDefined(); // tombstone shadowed → row kept
  });

  it('skips a shadowed date entity (dailyPlans) and a shadowed key entity (settings)', async () => {
    const store = openFresh();
    await store.dailyPlans.put(planRow('2026-06-11', { plannedMinutes: 15 }));
    await store.settings.put(settingRow('workStartHour', '7'));
    await enqueue(store, {
      method: 'PUT',
      path: '/api/v1/daily-plans/2026-06-11',
      body: { date: '2026-06-11', plannedTaskIds: [], plannedMinutes: 15 },
      entityKeys: [entityKey('dailyPlans', '2026-06-11')],
      kind: 'other',
    });
    await enqueue(store, {
      method: 'PUT',
      path: '/api/v1/settings',
      body: { workStartHour: 7 },
      entityKeys: [entityKey('settings', 'workStartHour')],
      kind: 'other',
    });

    const changes = emptyChanges();
    changes.dailyPlans = [planRow('2026-06-11', { plannedMinutes: 99, changeSeq: 4 })];
    changes.settings = [settingRow('workStartHour', '5', { changeSeq: 5 })];

    await applyPullPage(store, changes);

    expect((await store.dailyPlans.get('2026-06-11'))!.plannedMinutes).toBe(15); // shadowed
    expect((await store.settings.get('workStartHour'))!.value).toBe('7'); // shadowed
  });

  it('a shadowed page where EVERY row is skipped reports applied=false', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1'));
    await enqueue(store, {
      method: 'PATCH',
      path: '/api/v1/tasks/t1',
      body: { title: 'x' },
      entityKeys: [entityKey('tasks', 't1')],
      kind: 'other',
    });
    const changes = emptyChanges();
    changes.tasks = [taskRow('t1', { title: 'stale', changeSeq: 9 })];
    const applied = await applyPullPage(store, changes);
    expect(applied).toBe(false); // nothing actually applied
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/applyPull.test.ts
```

Expected failure: module-resolution error — `Error: Failed to resolve import "../applyPull" from "src/data/local/__tests__/applyPull.test.ts". Does the file exist?` (the `applyPull.ts` module does not exist yet).

- [ ] **Minimal impl** — create `packages/app/src/data/local/applyPull.ts`:

```ts
/**
 * applyPull — apply one pulled page (SyncChanges) into the local store
 * in a single Dexie rw transaction. Spec §4.2, contract C5.
 *
 * Per table, per row:
 *   - deletedAt != null  → delete the local row by that table's key (tombstone;
 *                          local tables hold live rows only, spec §2);
 *   - otherwise          → put (idempotent upsert-by-key).
 *
 * Shadow rule: skip any row whose entity key ('<table>:<key>') appears in ANY
 * pending op's entityKeys. The cycle is push-then-pull so the queue is normally
 * empty here, but this protects a write made mid-pull; the next cycle reconciles
 * it. (A shadow-skipped tombstone whose op is later dropped is re-delivered by
 * the rejection-recovery pull from since=0, spec §3.3.)
 *
 * Returns true if any row was actually written or deleted (used by the engine to
 * emit `changesApplied` at most once per cycle).
 */

import type { LocalStore } from './LocalStore';
import { entityKey, type SyncTable } from '../../sync/types';
import type { SyncChanges } from './rows';

/** A pulled row carries at least a key field, changeSeq, and deletedAt. */
interface PulledRow {
  changeSeq?: number;
  deletedAt?: string | null;
  [field: string]: unknown;
}

/**
 * Map each SyncChanges field to its Dexie table name and the property that holds
 * the table's primary key. Mirrors the C3 schema: id for the seven uuid tables,
 * `date` for dailyPlans, `key` for settings.
 */
const TABLE_SPECS: ReadonlyArray<{
  field: keyof SyncChanges;
  table: SyncTable;
  keyProp: 'id' | 'date' | 'key';
}> = [
  { field: 'tasks', table: 'tasks', keyProp: 'id' },
  { field: 'subtasks', table: 'subtasks', keyProp: 'id' },
  { field: 'projects', table: 'projects', keyProp: 'id' },
  { field: 'noteGroups', table: 'noteGroups', keyProp: 'id' },
  { field: 'notes', table: 'notes', keyProp: 'id' },
  { field: 'recurrenceRules', table: 'recurrenceRules', keyProp: 'id' },
  { field: 'focusSessions', table: 'focusSessions', keyProp: 'id' },
  { field: 'dailyPlans', table: 'dailyPlans', keyProp: 'date' },
  { field: 'settings', table: 'settings', keyProp: 'key' },
];

export async function applyPullPage(store: LocalStore, changes: SyncChanges): Promise<boolean> {
  // All nine entity tables + ops (the shadow read) in one rw transaction.
  return store.transaction(
    'rw',
    store.tasks,
    store.subtasks,
    store.projects,
    store.noteGroups,
    store.notes,
    store.recurrenceRules,
    store.focusSessions,
    store.dailyPlans,
    store.settings,
    store.ops,
    async () => {
      // Build the shadow set once per page from the ops multiEntry index.
      const shadow = new Set<string>();
      await store.ops.each((op) => {
        for (const k of op.entityKeys) shadow.add(k);
      });

      let applied = false;

      for (const spec of TABLE_SPECS) {
        const rows = (changes[spec.field] ?? []) as PulledRow[];
        if (rows.length === 0) continue;
        const dexieTable = store.table(spec.table);

        for (const row of rows) {
          const keyValue = row[spec.keyProp] as string;
          if (shadow.has(entityKey(spec.table, keyValue))) {
            continue; // shadow rule — a pending op owns this entity key
          }
          if (row.deletedAt != null) {
            await dexieTable.delete(keyValue);
          } else {
            await dexieTable.put(row);
          }
          applied = true;
        }
      }

      return applied;
    },
  );
}
```

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/applyPull.test.ts
```

Expected: `Test Files  1 passed (1)` · `Tests  9 passed (9)`.

- [ ] **Run typecheck** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: `tsc --noEmit` exits 0 (the new module resolves `SyncChanges`/`SyncTable`/`entityKey` and the `store.table(...)` dynamic accessor under strict mode).

- [ ] **Commit**

```bash
git add packages/app/src/data/local/applyPull.ts packages/app/src/data/local/__tests__/applyPull.test.ts
git commit -m "feat(sync): applyPullPage — upsert/tombstone-delete a pulled page with the shadow rule (C5, §4.2)

One Dexie rw transaction over the nine entity tables + ops: deletedAt != null
deletes by table key (id/date/key), else put; rows whose '<table>:<key>' is in
any pending op's entityKeys are shadow-skipped so a mid-pull local write is not
clobbered. Returns whether any row applied (engine changesApplied gate).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R4-2: Pull pagination + cursor protocol in the engine (overlap, non-regressing cursor, lastSyncedAt, changesApplied)

**Files:**
- Modify: `packages/app/src/sync/SyncEngine.ts`
- Test: `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts` (create)

The pull phase runs at the end of every cycle (even when push aborted — spec §3.3). The first page of a cycle requests `since = max(0, cursor − CURSOR_OVERLAP)` (spec §4.1); subsequent pages chain `nextSince` exactly while `hasMore`. Each page is applied via `applyPullPage`, then `meta.syncCursor` advances to `max(prior, nextSince)` — **the cursor never regresses** (the overlap re-read must not pull it backwards). At cycle end `meta.lastSyncedAt` is stamped (ISO now). `changesApplied` is emitted **once per cycle** when **any** page had rows applied.

This task uses a hand-rolled fake transport (a scripted `pull`) so the page-chaining and cursor math are asserted precisely; R4-7 then exercises the same code against the real `fakeSyncServer`.

> **Contract-gap note.** This task imports `CURSOR_OVERLAP` and `PULL_LIMIT` from `../constants` (contract C1, created in R3). R3 already calls `await this.pullPhase()` from `runCycle` (after push + the `regenAfterPush` ensure-instances hook, before the `finally` that runs `emitStatus()`) and ships `pullPhase()` as a `protected async` no-op stub. R4 swaps the **stub body** only — it does NOT touch `runCycle`. The *position* (pull is last, after push and ensure-instances) is the contract (C5) and is already correct in R3.

- [ ] **Write failing test** — create `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts` (`fake-indexeddb/auto` first):

```ts
import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { LocalStore } from '../../data/local/LocalStore';
import { SyncEngine } from '../SyncEngine';
import { CURSOR_OVERLAP, PULL_LIMIT } from '../constants';
import type { SyncTransport } from '../SyncTransport';
import type { SyncResponse, SyncChanges, TaskSyncRow } from '../../data/local/rows';

let nextUser = 0;
const openStores: LocalStore[] = [];
function openFresh(): LocalStore {
  const s = LocalStore.open(`eng-pull-u${Date.now()}-${nextUser++}`);
  openStores.push(s);
  return s;
}
afterEach(() => {
  for (const s of openStores.splice(0)) s.close();
  vi.restoreAllMocks();
});

function emptyChanges(): SyncChanges {
  return {
    tasks: [], subtasks: [], projects: [], noteGroups: [], notes: [],
    recurrenceRules: [], focusSessions: [], dailyPlans: [], settings: [],
  } as SyncChanges;
}

function taskRow(id: string, changeSeq: number): TaskSyncRow {
  return {
    id, title: `Task ${id}`, notes: '', projectId: null, labels: [], source: 'manual',
    status: 'Planned', plannedDate: '2026-06-11', scheduledStart: null, scheduledEnd: null,
    durationMinutes: 30, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
    rank: 'a0', dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
    createdAt: '2026-06-11T00:00:00Z', updatedAt: '2026-06-11T00:00:00Z',
    changeSeq, deletedAt: null,
  } as TaskSyncRow;
}

/** A transport whose `pull` returns scripted pages; push/ensure are inert (no ops queued). */
function scriptedTransport(pages: SyncResponse[]): {
  transport: SyncTransport;
  pullCalls: Array<{ since: number; limit: number }>;
} {
  const pullCalls: Array<{ since: number; limit: number }> = [];
  let i = 0;
  const transport: SyncTransport = {
    send: vi.fn(),
    ensureInstances: vi.fn().mockResolvedValue([]),
    pull: vi.fn(async (since: number, limit: number) => {
      pullCalls.push({ since, limit });
      const page = pages[i] ?? { changes: emptyChanges(), nextSince: since, hasMore: false };
      i += 1;
      return page;
    }),
  };
  return { transport, pullCalls };
}

describe('SyncEngine pull — pagination + cursor protocol', () => {
  it('first page requests since = max(0, cursor - CURSOR_OVERLAP) at PULL_LIMIT', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', CURSOR_OVERLAP + 12345); // > overlap
    const { transport, pullCalls } = scriptedTransport([
      { changes: emptyChanges(), nextSince: CURSOR_OVERLAP + 12345, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    expect(pullCalls[0]).toEqual({ since: 12345, limit: PULL_LIMIT });
  });

  it('clamps the overlap floor at 0 when the cursor is below CURSOR_OVERLAP', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 100); // < overlap
    const { transport, pullCalls } = scriptedTransport([
      { changes: emptyChanges(), nextSince: 100, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    expect(pullCalls[0].since).toBe(0);
  });

  it('chains nextSince across pages while hasMore, applying each page', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const p1 = { changes: emptyChanges(), nextSince: 500, hasMore: true } as SyncResponse;
    p1.changes.tasks = [taskRow('t1', 500)];
    const p2 = { changes: emptyChanges(), nextSince: 1000, hasMore: true } as SyncResponse;
    p2.changes.tasks = [taskRow('t2', 1000)];
    const p3 = { changes: emptyChanges(), nextSince: 1200, hasMore: false } as SyncResponse;
    p3.changes.tasks = [taskRow('t3', 1200)];
    const { transport, pullCalls } = scriptedTransport([p1, p2, p3]);
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    expect(pullCalls.map((c) => c.since)).toEqual([0, 500, 1000]); // first=max(0,0-overlap)=0, then chained
    expect(await store.tasks.get('t1')).toBeDefined();
    expect(await store.tasks.get('t2')).toBeDefined();
    expect(await store.tasks.get('t3')).toBeDefined();
    expect(await store.getMeta<number>('syncCursor')).toBe(1200); // final nextSince
  });

  it('never regresses the cursor: a smaller nextSince keeps max(prior, nextSince)', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 9000);
    // Overlap pull re-reads from 4000 and the page reports a smaller nextSince than 9000.
    const { transport } = scriptedTransport([
      { changes: emptyChanges(), nextSince: 4200, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    expect(await store.getMeta<number>('syncCursor')).toBe(9000); // max(9000, 4200) — no regression
  });

  it('stamps meta.lastSyncedAt at cycle end', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const { transport } = scriptedTransport([
      { changes: emptyChanges(), nextSince: 0, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    const before = Date.now();
    await engine.syncNow();
    const stamp = await store.getMeta<string>('lastSyncedAt');

    expect(stamp).toBeTypeOf('string');
    expect(Date.parse(stamp!)).toBeGreaterThanOrEqual(before);
  });

  it('emits changesApplied once per cycle when any page had rows; not when all pages were empty', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const withRows = { changes: emptyChanges(), nextSince: 10, hasMore: true } as SyncResponse;
    withRows.changes.tasks = [taskRow('t1', 10)];
    const emptyTail = { changes: emptyChanges(), nextSince: 10, hasMore: false } as SyncResponse;
    const { transport } = scriptedTransport([withRows, emptyTail]);
    const engine = new SyncEngine({ store, transport });

    const applied = vi.fn();
    engine.onChangesApplied(applied);
    await engine.syncNow();
    expect(applied).toHaveBeenCalledTimes(1); // once, despite two pages

    // A second cycle with only empty pages must not emit.
    const applied2 = vi.fn();
    engine.onChangesApplied(applied2);
    await engine.syncNow();
    expect(applied2).not.toHaveBeenCalled();
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts
```

Expected failure: the assertions fail because R3's `syncNow` runs no pull phase yet — `pullCalls` is empty, so `expect(pullCalls[0]).toEqual({ since: 12345, limit: PULL_LIMIT })` throws `AssertionError: expected undefined to deeply equal { since: 12345, limit: 500 }`, and `syncCursor` / `lastSyncedAt` / `changesApplied` assertions fail likewise.

- [ ] **Minimal impl** — edit `packages/app/src/sync/SyncEngine.ts`.

First, ensure the pull-side imports are present. At the top of the file, R3 already imports `LocalStore`, `SyncTransport`, `SyncOp`, and the constants it uses. Add the pull imports next to the existing `constants` import. The R3 import line reads (anchor):

```ts
import { PUSH_DEBOUNCE_MS, PERIODIC_SYNC_MS, CYCLE_5XX_RETRIES, CYCLE_5XX_BACKOFF_MS, PARK_THRESHOLD } from './constants';
```

Replace it with (adds `PULL_LIMIT`, `CURSOR_OVERLAP`):

```ts
import {
  PUSH_DEBOUNCE_MS,
  PERIODIC_SYNC_MS,
  CYCLE_5XX_RETRIES,
  CYCLE_5XX_BACKOFF_MS,
  PARK_THRESHOLD,
  PULL_LIMIT,
  CURSOR_OVERLAP,
} from './constants';
```

Add the `applyPullPage` import beneath the existing `./SyncTransport` import (anchor — R3's import of the transport type):

```ts
import type { SyncTransport } from './SyncTransport';
```

becomes:

```ts
import type { SyncTransport } from './SyncTransport';
import { applyPullPage } from '../data/local/applyPull';
```

Now fill in the pull phase. R3 already calls `await this.pullPhase();` inside `runCycle` (after the push loop and the `regenAfterPush` ensure-instances hook, before the `finally` that runs `emitStatus()`), and `pullPhase()` is a **`protected async` no-op stub** R4 replaces. Do NOT edit `runCycle` — only swap the stub body. R3's stub reads (anchor):

```ts
  // ── pull phase (R4) ────────────────────────────────────────
  /** NO-OP STUB — R4 implements cursor + applyPull + recovery + initialSyncComplete. */
  protected async pullPhase(): Promise<void> {
    await this.transport.pull(0, 0).then(
      () => {},
      () => {},
    );
  }
```

Replace the stub with the real paged pull (the recovery pull and ensure-row application from R4-4/R4-6 are added in their own tasks at the marked points):

```ts
  // ── pull phase (spec §4) ───────────────────────────────────
  /**
   * Paged pull (spec §4). First page of the cycle re-reads from
   * since = max(0, cursor − CURSOR_OVERLAP) (overlap mitigation, §4.1); pages
   * chain nextSince while hasMore. Each page is applied via applyPullPage; the
   * cursor advances to max(prior, nextSince) and never regresses. changesApplied
   * fires once per cycle if any page applied rows; lastSyncedAt is stamped at the end.
   */
  protected async pullPhase(): Promise<void> {
    const prior = (await this.store.getMeta<number>('syncCursor')) ?? 0;
    let since = Math.max(0, prior - CURSOR_OVERLAP);
    let cursor = prior;
    let anyApplied = false;
    let hasMore = true;

    while (hasMore) {
      const page = await this.transport.pull(since, PULL_LIMIT);
      const applied = await applyPullPage(this.store, page.changes);
      anyApplied = anyApplied || applied;
      cursor = Math.max(cursor, page.nextSince);
      await this.store.setMeta('syncCursor', cursor);
      since = page.nextSince;
      hasMore = page.hasMore;
    }

    await this.store.setMeta('lastSyncedAt', new Date().toISOString());

    if (anyApplied) {
      for (const cb of this.changesAppliedCbs) cb();
    }
  }
```

> Note: `changesAppliedCbs` and the `onChangesApplied(cb)` registration backing it already exist in R3 (the array is `private changesAppliedCbs: Array<() => void> = []`). The loop above iterates that array directly. The pull always runs because `runCycle` calls `this.pullPhase()` even when the push phase aborted (spec §3.3) — R4 changes the method body, never the `runCycle` ordering.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts
```

Expected: `Tests  6 passed (6)` (the six pagination/cursor tests in this task; R4-3..R4-6 add more `describe` blocks to the same file in later tasks).

- [ ] **Run typecheck** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: exits 0.

- [ ] **Commit**

```bash
git add packages/app/src/sync/SyncEngine.ts packages/app/src/sync/__tests__/SyncEngine.pull.test.ts
git commit -m "feat(sync): engine pull phase — overlap paging, non-regressing cursor, lastSyncedAt, changesApplied (§4.1, C5)

First page re-reads from since=max(0, cursor-CURSOR_OVERLAP); pages chain
nextSince while hasMore; each applied via applyPullPage; syncCursor advances to
max(prior, nextSince) and never regresses; lastSyncedAt stamped per cycle;
changesApplied emitted once when any page had rows. Pull always runs (even when
push aborted, §3.3).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R4-3: `initialSyncComplete` — set true once when a pull reaches `hasMore=false`

**Files:**
- Modify: `packages/app/src/sync/SyncEngine.ts`
- Test: `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts` (extend)

`meta.initialSyncComplete` is the first-render gate (spec §4.1, §7.4) — distinct from cursor presence (a crash mid-initial-sync leaves a partial, FK-inconsistent cursor). It is set to `true` the first time a pull phase **finishes a page chain with `hasMore=false`** and the flag was previously falsy; it is **never unset**, and an interrupted initial sync (a page chain that errors before `hasMore=false`) leaves it false so the gate keeps waiting. It is also surfaced in `SyncStatus.initialSyncComplete` (C2) — R3's `refreshSnapshot()` already reads `meta.initialSyncComplete` into the `getStatus()` cache, so this task only writes the meta flag and asserts the status reflects it.

> **Contract-gap note (status read).** C2's `SyncStatus.initialSyncComplete` is built by R3's synchronous `getStatus()` from the `cachedInitialSyncComplete` field, which `refreshSnapshot()` (called by `emitStatus()` and on subscribe) reads from `meta.initialSyncComplete`. So once `pullPhase` writes the meta flag, the next `emitStatus()` propagates it — no extra wiring needed. The subscribe-callback assertion below is the gate that this is wired.

- [ ] **Write failing test** — append to `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts` (inside the same file, a new `describe`; reuse the helpers already defined at the top):

```ts
describe('SyncEngine pull — initialSyncComplete gate flag', () => {
  it('stays false through paged pages and is set true only when hasMore reaches false', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const p1 = { changes: emptyChanges(), nextSince: 500, hasMore: true } as SyncResponse;
    const p2 = { changes: emptyChanges(), nextSince: 900, hasMore: false } as SyncResponse;
    const { transport } = scriptedTransport([p1, p2]);
    const engine = new SyncEngine({ store, transport });

    expect(await store.getMeta<boolean>('initialSyncComplete')).toBeUndefined();
    await engine.syncNow();
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true);
  });

  it('leaves the flag false when the initial page chain is interrupted before hasMore=false', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const transport: SyncTransport = {
      send: vi.fn(),
      ensureInstances: vi.fn().mockResolvedValue([]),
      pull: vi
        .fn()
        // first page says hasMore, then the second page throws (interrupted initial sync)
        .mockResolvedValueOnce({ changes: emptyChanges(), nextSince: 500, hasMore: true })
        .mockRejectedValueOnce(Object.assign(new TypeError('network down'), { name: 'NetworkError' })),
    };
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow(); // the engine swallows the cycle error; the flag must stay unset

    expect(await store.getMeta<boolean>('initialSyncComplete')).toBeFalsy();
  });

  it('never unsets the flag once set, and exposes it in subscribe() status', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    await store.setMeta('initialSyncComplete', true); // already complete from a prior session
    const { transport } = scriptedTransport([
      { changes: emptyChanges(), nextSince: 0, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    const seen: boolean[] = [];
    engine.subscribe((s) => seen.push(s.initialSyncComplete));
    await engine.syncNow();

    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true); // still true
    // emitStatus refreshes the status cache asynchronously; wait for it to land.
    await vi.waitFor(() => expect(seen.some((v) => v === true)).toBe(true)); // status reflects it
    expect(engine.getStatus().initialSyncComplete).toBe(true); // synchronous getStatus too
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "initialSyncComplete"
```

Expected failure: `expected undefined to be true` on the first test — the pull phase from R4-2 does not yet write `initialSyncComplete`.

- [ ] **Minimal impl** — edit `packages/app/src/sync/SyncEngine.ts`. In `pullPhase`, after the `while (hasMore)` loop and before stamping `lastSyncedAt`, set the gate flag when the chain completed (`hasMore` is now false because the loop exited) and the flag was previously falsy. Current anchor (the tail added in R4-2):

```ts
    await this.store.setMeta('lastSyncedAt', new Date().toISOString());

    if (anyApplied) {
      for (const cb of this.changesAppliedCbs) cb();
    }
  }
```

becomes:

```ts
    // Initial-sync gate (spec §4.1): set once when a pull completes the page chain
    // (the while-loop only exits at hasMore=false; an interrupted chain throws and
    // skips this). Never unset.
    const already = (await this.store.getMeta<boolean>('initialSyncComplete')) ?? false;
    if (!already) {
      await this.store.setMeta('initialSyncComplete', true);
    }

    await this.store.setMeta('lastSyncedAt', new Date().toISOString());

    if (anyApplied) {
      for (const cb of this.changesAppliedCbs) cb();
    }
  }
```

> No status-builder change is needed: R3's `refreshSnapshot()` already reads `meta.initialSyncComplete` into `cachedInitialSyncComplete`, which the synchronous `getStatus()` returns. Writing the meta flag here (and the `emitStatus()` that `runCycle` runs in its `finally`) propagates it to subscribers automatically — the meta read is the single source of truth.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "initialSyncComplete"
```

Expected: `Tests  3 passed`.

- [ ] **Commit**

```bash
git add packages/app/src/sync/SyncEngine.ts packages/app/src/sync/__tests__/SyncEngine.pull.test.ts
git commit -m "feat(sync): set initialSyncComplete once at hasMore=false; surface in status (§4.1, §7.4)

The first-render gate flag is set true only when a pull completes its page chain
(an interrupted chain leaves it false so the gate keeps waiting), never unset,
and is exposed via subscribe() status (C2). Distinct from cursor presence.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R4-4: Recovery pull — full paged pull from `since=0` when a recovery is scheduled

**Files:**
- Modify: `packages/app/src/sync/SyncEngine.ts`
- Test: `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts` (extend)

When R3 scheduled a recovery (the **persisted `pendingRecovery` meta flag** — set by R3's `scheduleRecovery()` on a definitively-rejected/dropped op in the push phase, or by `discardIssue`, spec §3.3), the engine runs a **full paged pull from `since=0`** at the end of the cycle (after the normal pull phase), then sets `cursor = max(prior, final nextSince)` and clears the flag. This snaps local state back to server truth, including **tombstones whose pull was shadow-skipped earlier** because a pending op owned the key, then that op was dropped (spec §4.2). Because the dropped op is no longer in `ops`, the shadow set no longer protects the row, so the `since=0` recovery delivers and applies the tombstone.

> **Contract-gap note.** R3 persists the recovery flag in `meta` as `pendingRecovery` (`store.setMeta('pendingRecovery', true)` / `getMeta('pendingRecovery')`) — NOT an in-memory field. R4 **reads and clears** it in the pull phase (`getMeta('pendingRecovery')` → run the since=0 pull → `setMeta('pendingRecovery', false)`). This task also factors the page-chain loop into a reusable `pullFrom(since): Promise<{ applied; nextSince }>` so both the normal cursor-overlap pull and the `since=0` recovery share one implementation.

- [ ] **Write failing test** — append the `describe` block below to `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts`, and **hoist the two new imports into the file's existing top-of-file import group** (next to the imports added in R4-2 — do not place a second `import` statement mid-file, which would either duplicate a binding or read as a stray statement):

```ts
// ── add to the top-of-file imports (R4-2 already imports LocalStore/SyncEngine/etc.) ──
import { LocalDataClient } from '../../data/local/LocalDataClient';
import { FakeSyncServer } from './fakeSyncServer';

// ── append this describe block at the end of the file ──
describe('SyncEngine pull — recovery pull (since=0) after a dropped op (§3.3, §4.2)', () => {
  it("a 400-dropped op's ghost edit is reverted by the recovery pull", async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    const engine = new SyncEngine({ store, transport: server.transport() });
    const client = new LocalDataClient(store, {
      nudge: () => {},
      online: () => true,
      ensureInstances: (s: string, e: string) => engine.ensureInstances(s, e),
    });

    // A clean task exists on both server and local.
    const created = await client.tasks.create({ title: 'Clean' });
    await engine.syncNow(); // create lands; pull is a no-op for own write

    // Force a definitive rejection: the fake server fails the next PATCH with 400.
    // We drive the edit through the client so the local ghost edit exists too.
    await client.tasks.update(created.id, { title: 'ghost edit' });
    server.failNext((o) => o.method === 'PATCH', { status: 400, body: { title: 'bad request' } });

    await engine.syncNow(); // op dropped + pendingRecovery → since=0 recovery snaps the title back

    const back = (await client.tasks.getAll()).find((t) => t.id === created.id);
    expect(back!.title).toBe('Clean'); // server truth restored — the ghost edit is gone
    const issues = await store.issues.toArray();
    expect(issues).toHaveLength(1);
    expect(issues[0].status).toBe('dropped');
  });

  it('a shadow-skipped tombstone is delivered by the recovery pull after its pending op is dropped (spec §4.2)', async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    const engine = new SyncEngine({ store, transport: server.transport() });
    const client = new LocalDataClient(store, {
      nudge: () => {},
      online: () => true,
      ensureInstances: (s: string, e: string) => engine.ensureInstances(s, e),
    });

    const created = await client.tasks.create({ title: 'DoomedRemotely' });
    await engine.syncNow(); // create lands on the server

    // Remote device deletes the task (server tombstones it, advancing changeSeq).
    server.tombstone('tasks', created.id);

    // This device edits the same task offline → a pending op now shadows tasks:<id>,
    // so the tombstone pulled this cycle is shadow-skipped (the row survives locally).
    await client.tasks.update(created.id, { title: 'edited while deleted remotely' });
    // The edit pushes as a PATCH → server 404 (tombstoned) → drop op + pendingRecovery.
    await engine.syncNow();

    // The since=0 recovery, run with the op already dropped (no shadow), deletes the row.
    const after = (await client.tasks.getAll()).find((t) => t.id === created.id);
    expect(after).toBeUndefined(); // delete wins via recovery (§3.3, §4.2)
    const issues = await store.issues.toArray();
    expect(issues.some((i) => i.reason.includes('404'))).toBe(true);
  });

  it('clears pendingRecovery after the recovery pull so the next clean cycle does not re-run since=0', async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    const engine = new SyncEngine({ store, transport: server.transport() });
    const client = new LocalDataClient(store, {
      nudge: () => {},
      online: () => true,
      ensureInstances: (s: string, e: string) => engine.ensureInstances(s, e),
    });
    const created = await client.tasks.create({ title: 'X' });
    await engine.syncNow();
    await client.tasks.update(created.id, { title: 'ghost' });
    server.failNext((o) => o.method === 'PATCH', { status: 400, body: { title: 'bad' } });
    await engine.syncNow(); // recovery runs once

    const pullSpy = vi.spyOn(server, 'pullCount'); // harness: total pull() invocations counter
    const before = server.pullCount();
    await engine.syncNow(); // clean cycle — must NOT trigger a second since=0 recovery
    const delta = server.pullCount() - before;
    // A clean cycle pulls exactly one chain (1+ pages); a lingering recovery would double it
    // by re-pulling from since=0. With a tiny dataset both are single-page, so assert the
    // recovery is not re-armed by checking the flag indirectly: exactly one normal pull chain.
    expect(delta).toBe(1);
    pullSpy.mockRestore();
  });
});
```

> **Contract-gap note (harness mutators used here).** All three are part of R3's C5a harness surface (`.sp3-plan-parts/04-r3.md` R3-3): `server.failNext(matcher, { status, body })` forces the next matching `send()` to resolve `{ status, body }` (a definitive rejection is expressed this way — there is NO `rejectNext`); `server.tombstone(table, key)` soft-deletes a row + cascades, stamping a fresh `changeSeq`; `server.pullCount()` returns the total `pull()` calls served. They are harness-only and already implemented in `fakeSyncServer.ts`.

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "recovery pull"
```

Expected failure: `expected 'ghost edit' to be 'Clean'` — the engine has no `since=0` recovery yet, so a dropped op leaves the local ghost edit in place.

- [ ] **Minimal impl** — edit `packages/app/src/sync/SyncEngine.ts`. Refactor `pullPhase` to share a page-chain loop, and add the recovery pull. Replace the entire `pullPhase` method (the body added in R4-2 + R4-3) with:

```ts
  /**
   * Pull a full page chain starting at `since`, applying each page. Returns
   * whether any row applied and the final nextSince of the chain. Used by both
   * the normal cursor-overlap pull and the since=0 recovery pull (spec §4, §3.3).
   */
  private async pullFrom(since: number): Promise<{ applied: boolean; finalNextSince: number }> {
    let cursor = since;
    let anyApplied = false;
    let hasMore = true;
    let next = since;
    while (hasMore) {
      const page = await this.transport.pull(next, PULL_LIMIT);
      const applied = await applyPullPage(this.store, page.changes);
      anyApplied = anyApplied || applied;
      cursor = Math.max(cursor, page.nextSince);
      next = page.nextSince;
      hasMore = page.hasMore;
    }
    return { applied: anyApplied, finalNextSince: cursor };
  }

  /**
   * Pull phase (spec §4). Normal pull re-reads from max(0, cursor − CURSOR_OVERLAP)
   * (overlap mitigation, §4.1). If a recovery was scheduled this cycle (the persisted
   * `pendingRecovery` meta flag, set by R3 on a dropped op or discardIssue, §3.3), a
   * full since=0 pull runs afterward to snap local state back to server truth —
   * including a tombstone whose earlier pull was shadow-skipped by the now-dropped op
   * (§4.2). The flag is read and cleared here. The cursor advances to
   * max(prior, final nextSince) and never regresses; the gate flag is set once at
   * a completed chain; lastSyncedAt is stamped; changesApplied fires once if any
   * page applied rows.
   */
  protected async pullPhase(): Promise<void> {
    const prior = (await this.store.getMeta<number>('syncCursor')) ?? 0;

    const normal = await this.pullFrom(Math.max(0, prior - CURSOR_OVERLAP));
    let cursor = Math.max(prior, normal.finalNextSince);
    let anyApplied = normal.applied;
    let reachedEnd = true; // the normal chain ran to hasMore=false (pullFrom only returns then)

    const pendingRecovery = (await this.store.getMeta<boolean>('pendingRecovery')) ?? false;
    if (pendingRecovery) {
      const recovery = await this.pullFrom(0);
      cursor = Math.max(cursor, recovery.finalNextSince);
      anyApplied = anyApplied || recovery.applied;
      await this.store.setMeta('pendingRecovery', false); // read-and-clear
    }

    await this.store.setMeta('syncCursor', cursor);

    if (reachedEnd) {
      const already = (await this.store.getMeta<boolean>('initialSyncComplete')) ?? false;
      if (!already) {
        await this.store.setMeta('initialSyncComplete', true);
      }
    }

    await this.store.setMeta('lastSyncedAt', new Date().toISOString());

    if (anyApplied) {
      for (const cb of this.changesAppliedCbs) cb();
    }
  }
```

> Note: `reachedEnd` is always `true` here because `pullFrom` only returns once its `while (hasMore)` exits at `hasMore=false` — an interrupted chain throws out of `pullFrom`, the exception propagates to `runCycle`'s try/catch (R3), and `pullPhase` never reaches the gate-flag write. The variable documents the invariant the R4-3 interrupted-sync test relies on; keep it so the intent survives if the loop is later refactored.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "recovery pull"
```

Expected: `Tests  3 passed`. Re-run the whole file to confirm no regression in R4-2/R4-3:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts
```

Expected: all prior tests still pass (12 so far: 6 + 3 + 3).

- [ ] **Run typecheck** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: exits 0.

- [ ] **Commit**

```bash
git add packages/app/src/sync/SyncEngine.ts packages/app/src/sync/__tests__/SyncEngine.pull.test.ts
git commit -m "feat(sync): recovery pull from since=0 when a recovery is scheduled (§3.3, §4.2)

A dropped/discarded op sets the persisted pendingRecovery meta flag; the engine
reads-and-clears it in the pull phase, running a full since=0 page chain after
the normal pull, advancing the cursor to max(prior, final nextSince). Reverts a
dropped op's ghost edit and delivers a tombstone whose earlier pull was
shadow-skipped by the now-dropped op. Factored a shared pullFrom(since) loop.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R4-5: `onFirstCycleSettled` — fire exactly once per `start()` (success OR terminal/can't-start failure)

**Files:**
- Modify: `packages/app/src/sync/SyncEngine.ts`
- Test: `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts` (extend; uses fake timers)

`onFirstCycleSettled` is the rollover-deferral hook (C5, spec §7.1.3): AppRoot runs rollover against reconciled rows only after the first cycle settles. It fires **exactly once per `start()`**, after the first cycle finishes — **success OR terminal failure**, including the offline case where the cycle **cannot even begin** (started offline with no network → that counts as a settled failure so rollover still runs against local rows per §7.1.3). It must not fire again on the second or any later cycle of the same `start()` session, and a fresh `start()` re-arms it.

> **Contract-gap note (start/cycle scheduling).** R3 already ships the whole hook: the `firstCycleSettledCbs: Array<() => void>` registry, the `onFirstCycleSettled(cb)` registration, the once-guard `firstCycleSettled: boolean`, and the settle itself in `runCycle`'s `finally` (it fires the callbacks once whenever a cycle runs — success OR an aborted/terminal cycle). R3's `start()` always calls `void this.syncNow()` (it does NOT gate the first cycle on `online()`), so even an offline start runs `runCycle`, which aborts on the network throw and still settles in `finally` — the §7.1.3 "can't-start" case is covered without a separate branch. The ONLY gap R4 fills is **re-arming the guard on a fresh `start()`** so a stop()→start() session can settle again; R4 adds `this.firstCycleSettled = false;` at the top of `start()`. Fake timers are used because `start()` schedules async work (periodic timer) that must be flushed deterministically.

- [ ] **Write failing test** — append to `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts`:

```ts
describe('SyncEngine — onFirstCycleSettled (fires once per start(), success or failure)', () => {
  it('fires once after a successful first cycle and not on later cycles', async () => {
    vi.useFakeTimers();
    try {
      const store = openFresh();
      await store.setMeta('syncCursor', 0);
      const { transport } = scriptedTransport([
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
      ]);
      const engine = new SyncEngine({ store, transport });
      const settled = vi.fn();
      engine.onFirstCycleSettled(settled);

      engine.start(); // online (connectivity defaults online in node fake-idb env)
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();

      expect(settled).toHaveBeenCalledTimes(1);

      // A second explicit cycle must not re-fire.
      await engine.syncNow();
      expect(settled).toHaveBeenCalledTimes(1);
      engine.stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('fires once when the first cycle fails terminally (pull throws)', async () => {
    vi.useFakeTimers();
    try {
      const store = openFresh();
      await store.setMeta('syncCursor', 0);
      const transport: SyncTransport = {
        send: vi.fn(),
        ensureInstances: vi.fn().mockResolvedValue([]),
        pull: vi.fn().mockRejectedValue(Object.assign(new TypeError('down'), { name: 'NetworkError' })),
      };
      const engine = new SyncEngine({ store, transport });
      const settled = vi.fn();
      engine.onFirstCycleSettled(settled);

      engine.start();
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();

      expect(settled).toHaveBeenCalledTimes(1); // a failed cycle still settles
      engine.stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('fires once when started offline and the cycle cannot even begin (§7.1.3)', async () => {
    vi.useFakeTimers();
    try {
      const store = openFresh();
      const { transport } = scriptedTransport([
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
      ]);
      const engine = new SyncEngine({ store, transport });
      // Force offline for this engine instance.
      vi.spyOn(engine, 'online').mockReturnValue(false);
      const settled = vi.fn();
      engine.onFirstCycleSettled(settled);

      engine.start(); // offline → no cycle runs, but the hook must still settle
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();

      expect(settled).toHaveBeenCalledTimes(1);
      engine.stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('re-arms on a fresh start() so the hook can fire again next session', async () => {
    vi.useFakeTimers();
    try {
      const store = openFresh();
      await store.setMeta('syncCursor', 0);
      const { transport } = scriptedTransport([
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
      ]);
      const engine = new SyncEngine({ store, transport });
      const settled = vi.fn();
      engine.onFirstCycleSettled(settled);

      engine.start();
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();
      engine.stop();
      expect(settled).toHaveBeenCalledTimes(1);

      engine.start(); // new session
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();
      engine.stop();
      expect(settled).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "onFirstCycleSettled"
```

Expected failure: `expected "spy" to be called 1 times, but got 0 times` — the once-guard + offline-can't-start settle path is not implemented (or, if R3 stubbed `onFirstCycleSettled` to no-op, the callback array is never invoked).

- [ ] **Minimal impl** — edit `packages/app/src/sync/SyncEngine.ts`.

R3 already declares `firstCycleSettledCbs`, `firstCycleSettled`, the `onFirstCycleSettled(cb)` registration, and settles the hook in `runCycle`'s `finally` (anchor — R3's existing `finally`):

```ts
    } finally {
      this.syncing = false;
      if (!this.firstCycleSettled) {
        this.firstCycleSettled = true;
        for (const cb of this.firstCycleSettledCbs) cb();
      }
      this.emitStatus();
    }
```

That settle path is correct and stays as-is — no new method is needed. The only missing behavior is **re-arming the guard each `start()`** so a fresh session can settle again. R3's `start()` reads (anchor):

```ts
  start(): void {
    if (this.running) return;
    this.running = true;
    this.unsubConnectivity = this.connectivity.subscribe((online) => {
      if (online) void this.syncNow();
    });
    this.armPeriodic();
    void this.syncNow(); // first cycle
  }
```

Replace it with a version that re-arms `firstCycleSettled` at the top (offline is already covered — R3 always runs `runCycle`, which settles in `finally` even when the push aborts on a network throw, per §7.1.3):

```ts
  start(): void {
    if (this.running) return;
    this.running = true;
    this.firstCycleSettled = false; // re-arm the settle guard per start() session
    this.unsubConnectivity = this.connectivity.subscribe((online) => {
      if (online) void this.syncNow();
    });
    this.armPeriodic();
    void this.syncNow(); // first cycle — runCycle's finally settles onFirstCycleSettled once
  }
```

> Note: `syncNow()` (C5) is single-flight and resolves once the cycle finishes; R3's `runCycle` swallows push/pull errors into the issue/abort paths and does not reject, so its `finally` fires the settle exactly once whether the cycle succeeded or aborted (including an offline start where the push aborts immediately). The once-guard (`if (!this.firstCycleSettled)`) makes later cycles (periodic, debounced, manual) no-ops for this hook; the re-arm line lets the next `start()` fire it again.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "onFirstCycleSettled"
```

Expected: `Tests  4 passed`.

- [ ] **Run typecheck** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: exits 0.

- [ ] **Commit**

```bash
git add packages/app/src/sync/SyncEngine.ts packages/app/src/sync/__tests__/SyncEngine.pull.test.ts
git commit -m "feat(sync): onFirstCycleSettled fires once per start() — success, failure, or offline can't-start (§7.1.3, C5)

The rollover-deferral hook settles after the first cycle of each start()
session: a completed cycle, a terminally-failed cycle, and an offline start
where no cycle runs all settle exactly once; a fresh start() re-arms it. Driven
with fake timers in tests.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R4-6: Apply the post-push ensure-instances rows via `applyPull` before the pull phase

**Files:**
- Modify: `packages/app/src/sync/SyncEngine.ts`
- Test: `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts` (extend)

When a replayed op had `regenAfterPush` (recurrence create or updateRule, spec §4.3), R3's post-push hook calls `transport.ensureInstances(start, end)` and gets back `TaskSyncRow[]` (the freshly-generated instances). Those rows must be written into the local store **via `applyPull`** (so the shadow rule applies — an instance whose key is currently owned by a pending op is not clobbered), **before** the normal pull phase. Wrapping the returned rows in a `SyncChanges` (only `tasks` populated) reuses the exact upsert/shadow path.

> **Contract-gap note.** R3 stores the returned rows in the instance field `private pendingEnsureRows: TaskSyncRow[] | null` (assigned in `runEnsureInstances` as `this.pendingEnsureRows = rows;`). R4 drains and clears it at the start of `pullPhase`, applying the rows through `applyPull` so the shadow rule protects any instance a pending op currently owns. This test asserts both: the ensure rows are upserted, the shadowed one is not clobbered, and the buffer is nulled afterward.

- [ ] **Write failing test** — append the `describe` block below, and **hoist the new `entityKey` import into the file's top-of-file import group** (it is not yet imported there; R4-2's top imports do not include it). Do not place the `import` mid-file.

```ts
// ── add to the top-of-file imports ──
import { entityKey } from '../types';

// ── append this describe block at the end of the file ──
describe('SyncEngine — post-push ensure-instances rows applied via applyPull (§4.3)', () => {
  it('upserts returned ensure rows before the pull phase, honoring the shadow rule', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);

    const ensureRows: TaskSyncRow[] = [
      taskRow('inst-1', 100),
      taskRow('inst-2', 101),
    ];
    // Shadow inst-2 with a pending op — ensure-application must skip it.
    await store.ops.add({
      method: 'PATCH',
      path: '/api/v1/tasks/inst-2',
      body: { title: 'local' },
      entityKeys: [entityKey('tasks', 'inst-2')],
      kind: 'other',
      attempts: 0,
    });
    await store.tasks.put(taskRow('inst-2', 1)); // the local (shadowed) version
    await store.tasks.update('inst-2', { title: 'LOCAL OWNS THIS' });

    const transport: SyncTransport = {
      send: vi.fn(),
      ensureInstances: vi.fn().mockResolvedValue(ensureRows),
      pull: vi.fn().mockResolvedValue({ changes: emptyChanges(), nextSince: 0, hasMore: false }),
    };
    const engine = new SyncEngine({ store, transport });

    // Simulate R3's post-push hook having buffered the ensure rows for this cycle.
    // (Internal field per the engine-internals contract-gap note.)
    (engine as unknown as { pendingEnsureRows: TaskSyncRow[] | null }).pendingEnsureRows = ensureRows;

    await engine.syncNow();

    expect((await store.tasks.get('inst-1'))!.title).toBe('Task inst-1'); // ensure row applied
    expect((await store.tasks.get('inst-2'))!.title).toBe('LOCAL OWNS THIS'); // shadowed → not clobbered
    // The buffer is cleared after application so a later cycle does not re-apply stale rows.
    expect((engine as unknown as { pendingEnsureRows: TaskSyncRow[] | null }).pendingEnsureRows).toBeNull();
  });
});
```

- [ ] **Run, expect FAIL** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "ensure-instances rows applied"
```

Expected failure: `expected undefined to be 'Task inst-1'` — `pullPhase` does not yet drain `pendingEnsureRows`, so `inst-1` is never written.

- [ ] **Minimal impl** — edit `packages/app/src/sync/SyncEngine.ts`.

R3 already declares the buffer (`private pendingEnsureRows: TaskSyncRow[] | null = null;`) and assigns it in `runEnsureInstances` (`this.pendingEnsureRows = rows;`). No new field is needed — R4 only drains it.

At the very start of `pullPhase`, drain the buffer through `applyPullPage` before computing `prior`. Current anchor (the first two lines of the `pullPhase` body after R4-4 — note it is `protected`):

```ts
  protected async pullPhase(): Promise<void> {
    const prior = (await this.store.getMeta<number>('syncCursor')) ?? 0;
```

becomes:

```ts
  protected async pullPhase(): Promise<void> {
    // Apply post-push ensure-instances rows first (spec §4.3), through applyPull
    // so the shadow rule protects any instance a pending op currently owns. Then
    // clear the buffer so a later cycle does not re-apply stale rows.
    if (this.pendingEnsureRows && this.pendingEnsureRows.length > 0) {
      await applyPullPage(this.store, {
        tasks: this.pendingEnsureRows,
        subtasks: [],
        projects: [],
        noteGroups: [],
        notes: [],
        recurrenceRules: [],
        focusSessions: [],
        dailyPlans: [],
        settings: [],
      });
    }
    this.pendingEnsureRows = null;

    const prior = (await this.store.getMeta<number>('syncCursor')) ?? 0;
```

> Note: the `SyncChanges` literal is built inline (all nine fields, only `tasks` populated) rather than importing a builder — `applyPullPage` reads each field with `?? []`, so the explicit empties are belt-and-suspenders for the C6 wire shape. The `TaskSyncRow[]` element type matches `SyncChanges.tasks`, so no cast is needed at the call site.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "ensure-instances rows applied"
```

Expected: `Tests  1 passed`.

- [ ] **Run typecheck** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: exits 0.

- [ ] **Commit**

```bash
git add packages/app/src/sync/SyncEngine.ts packages/app/src/sync/__tests__/SyncEngine.pull.test.ts
git commit -m "feat(sync): apply post-push ensure-instances rows via applyPull before the pull phase (§4.3)

The TaskSyncRow[] returned by the regenAfterPush ensure-instances hook is
upserted through applyPullPage (wrapped as a tasks-only SyncChanges) so the
shadow rule protects any instance a pending op owns, then the buffer is cleared.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R4-7: Cycle integration tests against `fakeSyncServer`

**Files:**
- Modify: `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts` (extend)

The unit tests above scripted the transport; this task exercises the **real** push→ensure→pull cycle end-to-end against the shared `FakeSyncServer`, proving the four spec-named scenarios: cold start (`since=0` multi-page), warm-pull overlap re-read idempotence, push-then-pull confirms own writes with no flicker (`changeSeq` advances), and a user write mid-pull (shadow) reconciled on the next cycle.

> **Contract-gap note (harness mutators).** This task uses `server.seed(table, row)` and `server.bumpSeq(table, key, patch?)` — both already part of R3's C5a harness surface (R3-3). `bumpSeq` applies a patch to an existing row and stamps a fresh `changeSeq` (simulating "another device wrote"). No harness change is needed here.
> The multi-page cold start uses a `PULL_LIMIT`-exceeding seed count; with `PULL_LIMIT = 500` (C1) seeding 600 tasks forces `hasMore` and proves page chaining against a real merge-by-changeSeq.

- [ ] **Write failing test** — append to `packages/app/src/sync/__tests__/SyncEngine.pull.test.ts`:

```ts
function serverTaskRow(id: string, rank: string): Record<string, unknown> {
  return {
    id, title: `srv-${id}`, notes: '', projectId: null, labels: [], source: 'manual',
    status: 'Inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
    durationMinutes: 0, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
    rank, dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
    createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z', deletedAt: null,
  };
}

describe('SyncEngine cycle integration (real fakeSyncServer)', () => {
  it('cold start from since=0 pulls a multi-page dataset into an empty store', async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    // > PULL_LIMIT rows to force page chaining.
    for (let i = 0; i < PULL_LIMIT + 100; i++) {
      server.seed('tasks', serverTaskRow(`t${String(i).padStart(4, '0')}`, `a${i}`));
    }
    const engine = new SyncEngine({ store, transport: server.transport() });

    expect(await store.getMeta<boolean>('initialSyncComplete')).toBeUndefined();
    await engine.syncNow();

    expect(await store.tasks.count()).toBe(PULL_LIMIT + 100); // all pages applied
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true);
    const cursor = await store.getMeta<number>('syncCursor');
    expect(cursor).toBeGreaterThanOrEqual(PULL_LIMIT + 100);
  });

  it('warm pull overlap re-read is idempotent (no duplicates, cursor does not regress)', async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    server.seed('tasks', serverTaskRow('t1', 'a0'));
    server.seed('tasks', serverTaskRow('t2', 'a1'));
    const engine = new SyncEngine({ store, transport: server.transport() });

    await engine.syncNow(); // initial pull
    const cursorAfter1 = await store.getMeta<number>('syncCursor');

    await engine.syncNow(); // warm pull re-reads the overlap window (since = max(0, cursor-5000) = 0 here)
    expect(await store.tasks.count()).toBe(2); // idempotent upsert-by-key — no dupes
    const cursorAfter2 = await store.getMeta<number>('syncCursor');
    expect(cursorAfter2).toBeGreaterThanOrEqual(cursorAfter1!); // never regresses
  });

  it('push-then-pull confirms own writes with no flicker: changeSeq advances on the pulled row', async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    const engine = new SyncEngine({ store, transport: server.transport() });
    const client = new LocalDataClient(store, {
      nudge: () => {},
      online: () => true,
      ensureInstances: (s: string, e: string) => engine.ensureInstances(s, e),
    });

    const created = await client.tasks.create({ title: 'Mine' });
    expect((await store.tasks.get(created.id))!.changeSeq).toBe(0); // local create starts at 0 (C3)

    await engine.syncNow(); // push the create, then pull confirms it

    const confirmed = await store.tasks.get(created.id);
    expect(confirmed).toBeDefined();
    expect(confirmed!.title).toBe('Mine'); // no flicker — title unchanged
    expect(confirmed!.changeSeq).toBeGreaterThan(0); // server-stamped changeSeq adopted
  });

  it('a user write mid-pull is shadow-protected and reconciles on the next cycle', async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    const engine = new SyncEngine({ store, transport: server.transport() });
    const client = new LocalDataClient(store, {
      nudge: () => {},
      online: () => true,
      ensureInstances: (s: string, e: string) => engine.ensureInstances(s, e),
    });

    const created = await client.tasks.create({ title: 'Base' });
    await engine.syncNow(); // create lands on server

    // Another device advances the row on the server (new changeSeq).
    server.bumpSeq('tasks', created.id, { title: 'remote title' });

    // This device writes the SAME row locally just before the pull — a pending op
    // now shadows tasks:<id>, so this cycle's pull skips the remote 'remote title'.
    await client.tasks.update(created.id, { title: 'local title' });
    await engine.syncNow(); // pushes local PATCH (local title wins as later arrival), pull shadowed

    // After the push delivered 'local title' and the next cycle pulls cleanly:
    await engine.syncNow();
    const final = await store.tasks.get(created.id);
    expect(final!.title).toBe('local title'); // local edit (later arrival) is authoritative
  });
});
```

- [ ] **Run, expect FAIL (then iterate to PASS — these are integration tests over already-built behavior)** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "cycle integration"
```

Expected outcome: these exercise code already implemented in R4-1..R4-6 + R2/R3 against the C5a harness surface R3 already ships (`seed`/`tombstone`/`bumpSeq`/`failNext`/`pullCount`/`latency`), so there is no harness gap to fill. Any failure here is a genuine assertion failure pointing at a regression in the owning task (cold-start paging → R4-2; overlap idempotence → R4-1 upsert-by-key + R4-2 cursor; own-write confirm → R3 push + R4 pull; shadow reconcile → R4-1 shadow rule), fixed there, not by weakening the test.

- [ ] **Run, expect PASS** — from `packages/app`:

```bash
npx vitest run src/sync/__tests__/SyncEngine.pull.test.ts -t "cycle integration"
```

Expected: `Tests  4 passed`.

- [ ] **Commit:**

```bash
git add packages/app/src/sync/__tests__/SyncEngine.pull.test.ts
git commit -m "test(sync): engine cycle integration over the real fakeSyncServer

Cold start since=0 multi-page (>PULL_LIMIT rows), warm-pull overlap idempotence
(no dupes, cursor never regresses), push-then-pull own-write confirmation
(changeSeq advances, no flicker), and mid-pull user write shadow-protected then
reconciled next cycle. Uses only the C5a harness surface R3 already ships.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R4-8: Phase R4 green gate (HARD GATE)

**Files:** none (verification only — no commit unless a fix is needed, in which case fix forward within R4 with its own TDD loop before re-running the gate from the top).

- [ ] **Run the two R4-owned suites** — from `packages/app`:

```bash
npx vitest run src/data/local/__tests__/applyPull.test.ts src/sync/__tests__/SyncEngine.pull.test.ts
```

Expected: **2 test files passed.** `applyPull.test.ts` = **9 tests**; `SyncEngine.pull.test.ts` = **21 tests** (R4-2: 6, R4-3: 3, R4-4: 3, R4-5: 4, R4-6: 1, R4-7: 4). The gate condition is **zero failures, zero skips**, with both files present and green; the exact total is 9 + 21 = **30 R4 tests**.

- [ ] **Run the full client suite** — from the repo root:

```bash
npm run test:app
```

Expected: **all tests pass, 0 failed, 0 skips.** Total = R3's green-gate count **+ 30** (the two R4 files), with no regression in any R1/R2/R3 suite. The two R4 files (`applyPull.test.ts`, `SyncEngine.pull.test.ts`) must both appear in the run. (If R4-7 extended `fakeSyncServer.ts`, R3's own push tests that import the harness must still be green — confirm `SyncEngine` push-side suites are unchanged in count and all passing.)

- [ ] **Run the app typecheck** — from the repo root:

```bash
npm run typecheck --workspace @tmap/app
```

Expected: `tsc --noEmit` exits 0 (proves `applyPull.ts` and the engine pull additions compose under strict mode against the C6 row types and C1 constants).

- [ ] **Run both app builds** — from the repo root:

```bash
npm run build:apps
```

Expected: desktop (`tmap`) and web (`@tmap/web`) builds both succeed — the engine pull phase + `applyPull` bundle cleanly into both shells.

- [ ] **Gate** — backend tests are not run here: R4 touches no backend file (R0 already gated the `/sync` slice; R4 only consumes its wire shapes via the regenerated client types).

**HARD GATE: do not start Phase R5 until all four commands above are green. If any fails, fix forward within R4 (test-first for behavior fixes; harness-only fixes in `fakeSyncServer.ts` are allowed since it is shared test code), re-run the full gate from the top, and only then proceed.**

## Phase R5 — Auth touch-ups + store + AppRoot/UI wiring

This phase makes the SP3 offline-first engine (built in R1–R4) actually drive the running app. It wires the per-user `LocalStore` + `SyncEngine` + `LocalDataClient` into `AppRoot`, splits/trims the Zustand store per contract C9, fixes the two SP2 auth components that conflate transient refresh failures with a dead session (C8), and adds the two small UI surfaces the spec allows (the sync-status pill, §8; an explicit-logout button in Settings, §7.2). Because the repo's vitest environment is `node` (no jsdom) and there is no React component test convention, every piece of logic is extracted into a pure factory/reducer and unit-tested there; React shells stay thin and are covered by `tsc` + the R6 live gate. The desktop main-process change has no test runner at all (Electron main is not under vitest); its contract is enforced by `electron-api.d.ts` (so `tsc` rejects a wrong shape) and the R6 live gate.

**Files this phase touches:**

- **Modify** `packages/app/src/auth/refreshClient.ts` (R5-1, C8.1) and **modify** its test `packages/app/src/auth/__tests__/refreshClient.test.ts`.
- **Modify** `apps/desktop/electron/main.ts` (R5-2, C8.2 discriminated IPC result), `apps/desktop/electron/preload.ts`, `apps/desktop/src/electron-api.d.ts`, `apps/desktop/src/platform/DesktopPlatform.ts`.
- **Modify** `packages/app/src/auth/authStore.ts` (R5-3, C8.3 authed-offline bootstrap) and its test `packages/app/src/auth/__tests__/authStore.test.ts`.
- **Modify** `packages/app/src/store.ts` (R5-4, C9: `refreshFromLocal`, `runRolloverOnce`, series-action tails, banner-site removals) and **create** test `packages/app/src/__tests__/storeRefreshRollover.test.ts`. `packages/app/src/store.settingsSplit.test.ts` stays green untouched.
- **Create** `packages/app/src/wiring/createSyncWiring.ts` (R5-5, pure wiring factory) + test `packages/app/src/wiring/__tests__/createSyncWiring.test.ts`; **create** `packages/app/src/components/InitialSyncGate.tsx`; **modify** `packages/app/src/AppRoot.tsx` to consume them.
- **Create** `packages/app/src/sync/useSyncStatus.ts` (R5-6, hook + extractable reducer) + test `packages/app/src/sync/__tests__/useSyncStatus.test.ts`; **create** `packages/app/src/components/SyncStatusPill.tsx`; **modify** `packages/app/src/App.tsx` (mount the pill) and `packages/app/src/components/SettingsDialog.tsx` (Sign-out surface). The Sign-out confirm logic is extracted to `packages/app/src/components/signOutConfirm.ts` + test `packages/app/src/components/__tests__/signOutConfirm.test.ts`.
- **R5-7** green gate: `npm run test:app`, `npm test --workspace @tmap/web`, `npm run build:apps`, backend untouched.

This phase consumes, but does not define, the R1–R4 symbols: `LocalStore` (C3), `LocalDataClient` (C4), `SyncEngine` + `SyncTransport` + `HttpSyncTransport` (C5), `SyncStatus`/`SyncOp`/`SyncIssue` (C2). Where this phase references their surface it uses the exact C2/C3/C4/C5 shapes.

> **Contract-gap note (Radix AlertDialog):** the brief asks for a "window-confirm-style Radix AlertDialog" in `SettingsDialog`. `@radix-ui/react-alert-dialog` is **not** a dependency of `packages/app` (only `react-dialog`/`react-popover`/etc. are — see `packages/app/package.json`). C10 itself only mandates "a confirm dialog warning when `pendingOps > 0`", not a specific primitive, and the brief also calls it "window-confirm-style". To avoid adding a runtime dependency mid-phase, the confirm uses the same self-contained fixed-overlay modal idiom `SettingsDialog` already uses (no new package). The decision logic (`shouldConfirmSignOut`, the warning copy) is extracted to `signOutConfirm.ts` and unit-tested; the modal is a thin shell. If the project later wants the Radix primitive, swapping the shell is mechanical and does not touch the tested logic.

---

### Task R5-1: `refreshClient` — transient refresh failure must NOT log out (C8.1)

**Files:**
- Modify `packages/app/src/auth/refreshClient.ts`
- Test `packages/app/src/auth/__tests__/refreshClient.test.ts`

Today `ensureRefresh()` runs `refresh().catch(() => null)`, so a network drop or 5xx *during* refresh resolves to `null`, which `call()` treats identically to a true 401 → `doLogout()` + throw. C8.1 requires: **resolved null** (true 401) → `doLogout()` + throw `'Session expired — refresh failed'`; **thrown/rejected** (transient) → re-throw the original error UNCHANGED, with NO logout (the sync cycle then aborts exactly like a push network error, queue intact).

- [ ] **Write failing test** — replace the existing `'never recurses: a refresh that throws logs out exactly once'` test (lines 101–112) with a transient-throw test, and add a second case proving the thrown error propagates verbatim. Apply this exact edit to `packages/app/src/auth/__tests__/refreshClient.test.ts`:

  Replace:

  ```ts
  it('never recurses: a refresh that throws logs out exactly once', async () => {
    fakeClient = buildClient(99);
    refresh = vi.fn(async () => {
      throw new Error('refresh endpoint 401');
    });
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    await expect(rc.GET('/api/v1/tasks', {})).rejects.toThrow();
    expect(refresh).toHaveBeenCalledTimes(1); // no recursion
    expect(onLogout).toHaveBeenCalledTimes(1);
  });
  ```

  with:

  ```ts
  it('transient refresh failure (refresh throws) re-throws WITHOUT logout (C8.1)', async () => {
    // A network drop / 5xx DURING refresh must NOT be conflated with a dead session.
    // The error propagates so the sync cycle aborts like a push network error; the
    // queue + keychain token survive; no logout.
    fakeClient = buildClient(99); // first GET is 401 → triggers refresh
    const transient = new TypeError('Failed to fetch');
    refresh = vi.fn(async () => {
      throw transient;
    });
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    await expect(rc.GET('/api/v1/tasks', {})).rejects.toBe(transient); // same error object, verbatim
    expect(refresh).toHaveBeenCalledTimes(1); // no recursion
    expect(onLogout).not.toHaveBeenCalled(); // NO logout on a transient failure
  });

  it('true 401 from refresh (resolves null) logs out once and throws the session-expired error (C8.1)', async () => {
    fakeClient = buildClient(99); // always 401
    refresh = vi.fn(async () => null); // resolved null === a real 401 from the refresh endpoint
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    await expect(rc.GET('/api/v1/tasks', {})).rejects.toThrow('Session expired — refresh failed');
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(onLogout).toHaveBeenCalledTimes(1);
  });
  ```

  The pre-existing `'rejects the whole queue and logs out when refresh fails (returns null)'` test (lines 90–99) already covers resolved-null → logout and stays as-is; the new pair makes the transient-vs-null distinction explicit.

- [ ] **Run, expect FAIL** — from `packages/app`:

  ```
  npx vitest run src/auth/__tests__/refreshClient.test.ts
  ```

  Expect FAIL: the transient test fails because the current `ensureRefresh()` swallows the throw with `.catch(() => null)`, so `call()` runs `doLogout()` and throws `'Session expired — refresh failed'` instead of re-throwing the `TypeError`. Vitest reports for `'transient refresh failure …'`: `expected promise to reject with <TypeError: Failed to fetch> but it rejected with <Error: Session expired — refresh failed>` and `expected "onLogout" to not have been called`.

- [ ] **Minimal impl** — edit `packages/app/src/auth/refreshClient.ts`.

  First, change `ensureRefresh()` to stop swallowing throws (so a transient rejection propagates) while still clearing the in-flight slot. Replace lines 92–102:

  ```ts
  function ensureRefresh(): Promise<AuthTokenResponse | null> {
    if (refreshPromise) return refreshPromise;

    refreshPromise = refresh()
      .catch(() => null)
      .finally(() => {
        refreshPromise = null;
      });

    return refreshPromise;
  }
  ```

  with:

  ```ts
  function ensureRefresh(): Promise<AuthTokenResponse | null> {
    if (refreshPromise) return refreshPromise;

    // NOTE (C8.1): do NOT swallow a thrown refresh into null. A throw means a
    // transient failure (network drop / 5xx during refresh) — it must propagate so
    // the caller re-throws it WITHOUT logging out. Only a *resolved* null is a true
    // 401 (dead session). The in-flight slot is cleared either way via finally.
    refreshPromise = refresh().finally(() => {
      refreshPromise = null;
    });

    return refreshPromise;
  }
  ```

  Then make `call()` re-throw a transient unchanged. Replace lines 134–141:

  ```ts
    // Await the single shared refresh.
    const newAuth = await ensureRefresh();

    if (!newAuth) {
      // refresh returned null or threw — session is dead.
      doLogout();
      throw new Error('Session expired — refresh failed');
    }
  ```

  with:

  ```ts
    // Await the single shared refresh. A THROW here is a transient failure
    // (network/5xx during refresh): re-throw it verbatim, NO logout — the engine
    // aborts the cycle like a push network error and the queue stays intact (C8.1).
    let newAuth: AuthTokenResponse | null;
    try {
      newAuth = await ensureRefresh();
    } catch (e) {
      throw e;
    }

    if (!newAuth) {
      // refresh RESOLVED null === a true 401 from the refresh endpoint: session is dead.
      doLogout();
      throw new Error('Session expired — refresh failed');
    }
  ```

  > The `try/catch` that re-throws looks like a no-op, and it is functionally: it exists only to make the C8.1 contract legible at the call site (a thrown refresh bypasses `doLogout()` by construction). The behavioural change is entirely in `ensureRefresh()` no longer catching.

- [ ] **Run, expect PASS** — from `packages/app`:

  ```
  npx vitest run src/auth/__tests__/refreshClient.test.ts
  ```

  Expect PASS: all tests green (the original 7 minus the replaced one, plus the 2 new ones = 8 tests).

- [ ] **Commit**

  ```
  git add packages/app/src/auth/refreshClient.ts packages/app/src/auth/__tests__/refreshClient.test.ts
  git commit -m "$(cat <<'EOF'
fix(app): R5-1 — refreshClient transient refresh failure re-throws without logout (C8.1)

ensureRefresh() no longer swallows a thrown refresh into null. A throw is a
transient failure (network drop / 5xx during refresh) and propagates verbatim so
the caller re-throws it with NO logout — the sync cycle aborts like a push
network error, queue + keychain token intact. Only a *resolved* null is a true
401 (dead session) → doLogout() + 'Session expired — refresh failed'.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task R5-2: Desktop IPC discriminated refresh result + DesktopPlatform mapping (C8.2)

**Files:**
- Modify `apps/desktop/electron/main.ts`
- Modify `apps/desktop/electron/preload.ts`
- Modify `apps/desktop/src/electron-api.d.ts`
- Modify `apps/desktop/src/platform/DesktopPlatform.ts`

Today `secureStore:refreshAndGetAccess` (main.ts:258–294) returns `null` for 401, 5xx, *and* network errors alike, and `DesktopPlatform.refreshAndGetAccess` (DesktopPlatform.ts:24–27) returns that `null` straight through — so `authStore.bootstrap` clears the keychain token on a transient offline failure (the desktop-cold-start session-destruction bug, spec §7.3). C8.2 requires the IPC result to be a discriminated union — `{ ok: true, accessToken, expiresIn, user } | { ok: false, reason: 'unauthorized' | 'transient' }` — with the keychain token cleared ONLY on `unauthorized`; `DesktopPlatform` maps `transient → throw NetworkError`, `unauthorized → null` (matching `WebPlatform`'s SF-3 contract so `authStore.bootstrap`'s existing `isNetworkError` branch fires).

> **No vitest covers the Electron main process** — `apps/desktop` has no test runner. The main-process change is therefore covered behaviourally by the R6 live gate (desktop cold start with the backend down, R6-5). The *shape* is enforced statically: `electron-api.d.ts` declares the discriminated return type, so `apps/desktop`'s `tsc` (run in `npm run build:apps`, R5-7) rejects any main/preload/DesktopPlatform code that doesn't conform. The `DesktopPlatform` mapping itself is the renderer-side, type-checked translation; it lives in `apps/desktop` (no vitest), so it too is gated by `tsc` + the live gate rather than a unit test. Complete code for all four files follows.

- [ ] **Do — edit `apps/desktop/electron/main.ts`** — replace the handler body at lines 258–294:

  Replace:

  ```ts
  ipcMain.handle('secureStore:refreshAndGetAccess', async () => {
    const refreshToken = readRefreshToken();
    if (!refreshToken) return null;
    try {
      const res = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // Native path: refresh token in the body (SP1 §3.4).
        body: JSON.stringify({ refreshToken }),
      });
      if (res.status === 401) {
        // Refresh token rejected/rotated-away → forget it; renderer logs out.
        clearRefreshToken();
        return null;
      }
      if (!res.ok) {
        // Network/5xx — do NOT destroy a possibly-valid token; signal "couldn't refresh".
        return null;
      }
      const data = (await res.json()) as {
        accessToken: string;
        expiresIn: number;
        refreshToken?: string;
        user: { id: string; email: string; timeZoneId: string };
      };
      // Rotation: store the new refresh token if one was returned.
      if (data.refreshToken) persistRefreshToken(data.refreshToken);
      return {
        accessToken: data.accessToken,
        expiresIn: data.expiresIn,
        user: data.user,
      };
    } catch (e) {
      console.error('refreshAndGetAccess failed:', e);
      return null;
    }
  });
  ```

  with:

  ```ts
  ipcMain.handle('secureStore:refreshAndGetAccess', async () => {
    // C8.2: discriminated result. Only a TRUE 401 clears the keychain token; a 5xx
    // or a network error is `transient` and KEEPS the token so a desktop offline
    // cold start can re-render authed-offline and retry on reconnect (spec §7.3).
    const refreshToken = readRefreshToken();
    if (!refreshToken) return { ok: false, reason: 'unauthorized' as const };
    try {
      const res = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // Native path: refresh token in the body (SP1 §3.4).
        body: JSON.stringify({ refreshToken }),
      });
      if (res.status === 401) {
        // Refresh token rejected/rotated-away → forget it; renderer goes anonymous.
        clearRefreshToken();
        return { ok: false, reason: 'unauthorized' as const };
      }
      if (!res.ok) {
        // 5xx / other transport failure — do NOT destroy a possibly-valid token.
        return { ok: false, reason: 'transient' as const };
      }
      const data = (await res.json()) as {
        accessToken: string;
        expiresIn: number;
        refreshToken?: string;
        user: { id: string; email: string; timeZoneId: string };
      };
      // Rotation: store the new refresh token if one was returned.
      if (data.refreshToken) persistRefreshToken(data.refreshToken);
      return {
        ok: true as const,
        accessToken: data.accessToken,
        expiresIn: data.expiresIn,
        user: data.user,
      };
    } catch (e) {
      // Network error (DNS down, offline, connection refused) — transient, keep token.
      console.error('refreshAndGetAccess failed:', e);
      return { ok: false, reason: 'transient' as const };
    }
  });
  ```

- [ ] **Do — edit `apps/desktop/electron/preload.ts`** — replace the `refreshAndGetAccess` passthrough typing at lines 30–35:

  Replace:

  ```ts
    /** Performs POST /auth/refresh in main using the stored refresh token. */
    refreshAndGetAccess: (): Promise<{
      accessToken: string;
      expiresIn: number;
      user: { id: string; email: string; timeZoneId: string };
    } | null> => ipcRenderer.invoke('secureStore:refreshAndGetAccess'),
  ```

  with:

  ```ts
    /**
     * Performs POST /auth/refresh in main using the stored refresh token. Returns a
     * discriminated result (C8.2): the keychain token is cleared in main ONLY on a
     * true 401 (`unauthorized`); 5xx / network failures are `transient` and keep it.
     */
    refreshAndGetAccess: (): Promise<
      | { ok: true; accessToken: string; expiresIn: number; user: { id: string; email: string; timeZoneId: string } }
      | { ok: false; reason: 'unauthorized' | 'transient' }
    > => ipcRenderer.invoke('secureStore:refreshAndGetAccess'),
  ```

- [ ] **Do — edit `apps/desktop/src/electron-api.d.ts`** — replace the `refreshAndGetAccess` member at lines 24–28:

  Replace:

  ```ts
    refreshAndGetAccess(): Promise<{
      accessToken: string;
      expiresIn: number;
      user: { id: string; email: string; timeZoneId: string };
    } | null>;
  ```

  with:

  ```ts
    refreshAndGetAccess(): Promise<
      | { ok: true; accessToken: string; expiresIn: number; user: { id: string; email: string; timeZoneId: string } }
      | { ok: false; reason: 'unauthorized' | 'transient' }
    >;
  ```

- [ ] **Do — edit `apps/desktop/src/platform/DesktopPlatform.ts`** — replace the `refreshAndGetAccess` mapping at lines 24–27:

  Replace:

  ```ts
    refreshAndGetAccess: async (): Promise<AuthTokenResponse | null> => {
      const res = await window.api.secureStore.refreshAndGetAccess();
      return res;
    },
  ```

  with:

  ```ts
    refreshAndGetAccess: async (): Promise<AuthTokenResponse | null> => {
      // C8.2 mapping to the SF-3 contract WebPlatform already honours:
      //   ok           → AuthTokenResponse
      //   unauthorized → null (bootstrap clears the keychain token via platform.auth.clear)
      //   transient    → throw NetworkError (bootstrap's network branch keeps the token + DB)
      const res = await window.api.secureStore.refreshAndGetAccess();
      if (res.ok) {
        return { accessToken: res.accessToken, expiresIn: res.expiresIn, user: res.user };
      }
      if (res.reason === 'transient') {
        throw Object.assign(new Error('refresh transient failure'), { name: 'NetworkError' });
      }
      return null; // unauthorized
    },
  ```

- [ ] **Verify** — from repo root:

  ```
  npm run build:desktop
  ```

  Expect the desktop `tsc` step to PASS: `main.ts`, `preload.ts`, `electron-api.d.ts`, and `DesktopPlatform.ts` all agree on the discriminated shape. A regression (e.g. forgetting `as const` so `reason` widens to `string`) would surface here as a TS2322/TS2367 type error. (`build:desktop` runs `tsc` + Vite + electron-builder bundling for the renderer/main; the green compile is the gate.)

- [ ] **Commit**

  ```
  git add apps/desktop/electron/main.ts apps/desktop/electron/preload.ts apps/desktop/src/electron-api.d.ts apps/desktop/src/platform/DesktopPlatform.ts
  git commit -m "$(cat <<'EOF'
fix(desktop): R5-2 — discriminated refresh IPC result; keep keychain token on transient (C8.2)

secureStore:refreshAndGetAccess now returns
{ ok:true, ... } | { ok:false, reason:'unauthorized'|'transient' }. Keychain token
is cleared ONLY on a true 401 (unauthorized); 5xx and network errors are transient
and KEEP the token — fixing desktop offline cold-start session destruction (§7.3).
DesktopPlatform maps transient→throw NetworkError, unauthorized→null, matching
WebPlatform's SF-3 contract so authStore.bootstrap's existing branches just work.
Shape enforced by electron-api.d.ts (tsc); behaviour by the R6 live gate.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task R5-3: `authStore.bootstrap` — authed-offline branch + 401-keeps-pointer (C8.3)

**Files:**
- Modify `packages/app/src/auth/authStore.ts`
- Test `packages/app/src/auth/__tests__/authStore.test.ts`

C8.3: in `bootstrap`'s network-error branch, additionally consult `getLastUserId()` + that DB's `meta.lastUser`; when present, transition to `authed` with `user = lastUser`, `accessToken = null`, `networkError: true` (authed-offline render from local data). The true-401 branch keeps the DB + queue (no wipe) and **does not** clear the `tmap:lastUserId` pointer — re-login as the same user reuses the DB. To keep `authStore` decoupled from `LocalStore` (which lives in `data/local` and pulls in Dexie), the lookup is injected as an optional dep `resolveLastUser?: () => Promise<AuthUser | null>` on `AuthStoreDeps` (AppRoot wires it to `getLastUserId()` + `LocalStore.open(id).getMeta('lastUser')` in R5-5). When the dep is absent (e.g. web before wiring, or any non-offline path) the branch falls back to today's behaviour.

- [ ] **Write failing test** — append to `packages/app/src/auth/__tests__/authStore.test.ts` (after the last `describe2` block, end of file):

  ```ts
  // --- R5-3: authed-offline bootstrap + 401 keeps the pointer (C8.3) ---

  describe2('authStore — authed-offline bootstrap (C8.3)', () => {
    function ok4(data: unknown) {
      return { data, error: undefined, response: { status: 200 } };
    }
    function makePlatform4(refreshImpl: () => Promise<any>) {
      return {
        auth: {
          refreshAndGetAccess: vi2.fn(refreshImpl),
          clear: vi2.fn(async () => {}),
          logout: vi2.fn(async () => {}),
        },
      };
    }
    const baseClient = () => ({
      POST: vi2.fn(async () => ok4({})),
      GET: vi2.fn(),
      PATCH: vi2.fn(),
      PUT: vi2.fn(),
      DELETE: vi2.fn(),
    });

    it2(
      'transient refresh failure WITH a known last user → authed-offline (accessToken null, networkError true)',
      async () => {
        const platform = makePlatform4(async () => {
          throw new TypeError('Failed to fetch'); // transient (network/5xx grouped per SF-3)
        });
        const lastUser = { id: 'u-offline', email: 'off@line.dev', timeZoneId: 'UTC' };
        const resolveLastUser = vi2.fn(async () => lastUser);
        const onAuthed = vi2.fn();
        const store = createAuthStore({
          client: baseClient(),
          platform,
          onAuthed,
          onLoggedOut: vi2.fn(),
          resolveLastUser,
        });

        await store.getState().bootstrap();
        const s = store.getState();
        expect2(s.status).toBe('authed'); // renders from local data
        expect2(s.user).toEqual(lastUser);
        expect2(s.accessToken).toBeNull(); // no token offline; engine idles until reconnect
        expect2(s.networkError).toBe(true);
        expect2(platform.auth.clear).not.toHaveBeenCalled(); // DB + token survive
        expect2(onAuthed).toHaveBeenCalledTimes(1); // AppRoot still opens the store + engine
      },
    );

    it2(
      'transient refresh failure with NO known last user → anonymous + networkError (today\'s behaviour)',
      async () => {
        const platform = makePlatform4(async () => {
          throw new TypeError('Failed to fetch');
        });
        const resolveLastUser = vi2.fn(async () => null); // no prior local user
        const store = createAuthStore({
          client: baseClient(),
          platform,
          onAuthed: vi2.fn(),
          onLoggedOut: vi2.fn(),
          resolveLastUser,
        });

        await store.getState().bootstrap();
        const s = store.getState();
        expect2(s.status).toBe('anonymous');
        expect2(s.networkError).toBe(true);
        expect2(platform.auth.clear).not.toHaveBeenCalled(); // still never wipe on transient
      },
    );

    it2('true 401 → anonymous, clears the keychain token but KEEPS DB + pointer (no wipe)', async () => {
      // resolveLastUser is irrelevant on a true 401; the pointer is NOT cleared here
      // (re-login as the same user reuses the DB). Only explicit logout clears it.
      const platform = makePlatform4(async () => null); // resolved null === true 401
      const resolveLastUser = vi2.fn(async () => ({ id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' }));
      const clearPointer = vi2.fn();
      const store = createAuthStore({
        client: baseClient(),
        platform,
        onAuthed: vi2.fn(),
        onLoggedOut: vi2.fn(),
        resolveLastUser,
        clearLastUserPointer: clearPointer,
      });

      await store.getState().bootstrap();
      const s = store.getState();
      expect2(s.status).toBe('anonymous');
      expect2(s.networkError).toBe(false);
      expect2(platform.auth.clear).toHaveBeenCalledTimes(1); // dead access token cleared
      expect2(clearPointer).not.toHaveBeenCalled(); // pointer persists on session expiry
      expect2(resolveLastUser).not.toHaveBeenCalled(); // only consulted on the transient branch
    });
  });
  ```

- [ ] **Run, expect FAIL** — from `packages/app`:

  ```
  npx vitest run src/auth/__tests__/authStore.test.ts
  ```

  Expect FAIL: `AuthStoreDeps` has no `resolveLastUser`/`clearLastUserPointer`, so TypeScript flags the test's `createAuthStore({ … resolveLastUser })` calls (vitest reports a transform/type error), and at runtime the transient branch still sets `anonymous` regardless of `resolveLastUser` — the first new test fails with `expected 'anonymous' to be 'authed'`.

- [ ] **Minimal impl** — edit `packages/app/src/auth/authStore.ts`.

  Add the two optional deps to `AuthStoreDeps`. Replace lines 37–44:

  ```ts
  export interface AuthStoreDeps {
    client: Record<Verb, FetchLike>;
    platform: Pick<Platform, 'auth'>;
    /** Called after a successful authed transition (register/login/bootstrap). */
    onAuthed: (auth: AuthTokenResponse) => void;
    /** Called after logout completes (status -> anonymous). */
    onLoggedOut: () => void;
  }
  ```

  with:

  ```ts
  export interface AuthStoreDeps {
    client: Record<Verb, FetchLike>;
    platform: Pick<Platform, 'auth'>;
    /** Called after a successful authed transition (register/login/bootstrap). */
    onAuthed: (auth: AuthTokenResponse) => void;
    /** Called after logout completes (status -> anonymous). */
    onLoggedOut: () => void;
    /**
     * C8.3 offline bootstrap: resolve the last-authed user from the global pointer
     * (`getLastUserId()`) + that DB's `meta.lastUser`. Consulted ONLY on the
     * transient-refresh-failure branch to enter authed-offline. Absent → today's
     * behaviour (anonymous + networkError). Wired by AppRoot.
     */
    resolveLastUser?: () => Promise<AuthUser | null>;
    /** Clears `tmap:lastUserId`. NOT called on session expiry — only by explicit logout. */
    clearLastUserPointer?: () => void;
  }
  ```

  Then implement the branches. The transient branch is the `isNetworkError(e)` arm; the true-401 arm is the `else` of the `if (auth)` inside `bootstrap`. Replace lines 65–66:

  ```ts
  export function createAuthStore(deps: AuthStoreDeps): StoreApi<AuthState> {
    const { client, platform, onAuthed, onLoggedOut } = deps;
  ```

  with:

  ```ts
  export function createAuthStore(deps: AuthStoreDeps): StoreApi<AuthState> {
    const { client, platform, onAuthed, onLoggedOut, resolveLastUser } = deps;
  ```

  Then replace the whole `bootstrap` body (lines 176–210):

  ```ts
    async bootstrap() {
      set({ status: 'loading', error: null, networkError: false });
      try {
        const auth = await get().refresh();
        if (auth) {
          set({
            status: 'authed',
            user: auth.user,
            accessToken: auth.accessToken,
            networkError: false,
          });
          onAuthed(auth);
        } else {
          // refresh returned null === a real 401: stored token is dead, clear it.
          try {
            await platform.auth.clear();
          } catch {
            /* ignore */
          }
          set({ status: 'anonymous', user: null, accessToken: null, networkError: false });
        }
      } catch (e) {
        if (isNetworkError(e)) {
          // Do NOT destroy a (possibly valid) stored token on a transient network failure.
          set({ status: 'anonymous', user: null, accessToken: null, networkError: true });
        } else {
          try {
            await platform.auth.clear();
          } catch {
            /* ignore */
          }
          set({ status: 'anonymous', user: null, accessToken: null, networkError: false });
        }
      }
    },
  ```

  with:

  ```ts
    async bootstrap() {
      set({ status: 'loading', error: null, networkError: false });
      try {
        const auth = await get().refresh();
        if (auth) {
          set({
            status: 'authed',
            user: auth.user,
            accessToken: auth.accessToken,
            networkError: false,
          });
          onAuthed(auth);
        } else {
          // refresh RESOLVED null === a real 401: stored access token is dead, clear it.
          // C8.3: KEEP the local DB + op queue and the tmap:lastUserId pointer — re-login
          // as the same user reuses them. Only explicit logout wipes/clears the pointer.
          try {
            await platform.auth.clear();
          } catch {
            /* ignore */
          }
          set({ status: 'anonymous', user: null, accessToken: null, networkError: false });
        }
      } catch (e) {
        if (isNetworkError(e)) {
          // Transient (network drop / refresh 5xx, grouped per SF-3). Do NOT destroy the
          // stored token. C8.3: if a last-authed local user exists, render authed-OFFLINE
          // (no access token; the engine idles until connectivity, then refreshes + syncs).
          const lastUser = resolveLastUser ? await resolveLastUser().catch(() => null) : null;
          if (lastUser) {
            set({
              status: 'authed',
              user: lastUser,
              accessToken: null, // no token offline
              networkError: true,
            });
            onAuthed({ accessToken: '', expiresIn: 0, user: lastUser });
          } else {
            set({ status: 'anonymous', user: null, accessToken: null, networkError: true });
          }
        } else {
          try {
            await platform.auth.clear();
          } catch {
            /* ignore */
          }
          set({ status: 'anonymous', user: null, accessToken: null, networkError: false });
        }
      }
    },
  ```

  > The authed-offline path calls `onAuthed` with a synthetic `AuthTokenResponse` (`accessToken: ''`, `expiresIn: 0`) so AppRoot's `onAuthed` runs its full sequence (open `LocalStore`, build `LocalDataClient`, start the engine) — the engine sees `online() === false` and idles. The empty access token never reaches the wire: every request is refresh-wrapped, and the store's `accessToken` is `null`, so the Bearer header is omitted offline.

- [ ] **Run, expect PASS** — from `packages/app`:

  ```
  npx vitest run src/auth/__tests__/authStore.test.ts
  ```

  Expect PASS: the original bootstrap/logout/desktop-persist tests stay green (they pass no `resolveLastUser`, so the transient branch keeps its anonymous fallback), and the three new C8.3 tests pass.

- [ ] **Commit**

  ```
  git add packages/app/src/auth/authStore.ts packages/app/src/auth/__tests__/authStore.test.ts
  git commit -m "$(cat <<'EOF'
feat(app): R5-3 — authStore authed-offline bootstrap + 401 keeps pointer (C8.3)

bootstrap's transient-refresh branch now consults injected resolveLastUser()
(getLastUserId + meta.lastUser); when a last-authed user exists it renders
authed-OFFLINE (accessToken null, networkError true, onAuthed fired) so AppRoot
opens the local store + engine. True-401 keeps the DB + op queue and the
tmap:lastUserId pointer (re-login reuses them); only explicit logout clears it.
resolveLastUser/clearLastUserPointer are optional deps wired by AppRoot in R5-5.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task R5-4: Store changes — `refreshFromLocal`, `runRolloverOnce`, series tails, banner-site removal (C9)

**Files:**
- Modify `packages/app/src/store.ts`
- Create test `packages/app/src/__tests__/storeRefreshRollover.test.ts`
- (`packages/app/src/store.settingsSplit.test.ts` stays green, untouched)

C9 in full:
1. New `refreshFromLocal(): Promise<void>` — re-reads tasks/projects/noteGroups (and settings via the existing local settings read) from the seam; **no rollover, no ensureInstances**.
2. The three series actions replace their `await get().loadTasks()` tails with `await get().refreshFromLocal()`.
3. Extract the rollover pass out of `loadTasks` into exported action `runRolloverOnce()` (idempotent per session via a module flag, reset by `store.reset()`); `loadTasks` no longer rolls over but KEEPS its ensure-instances horizon call.
4. Remove the rollover + settings `setOnlineError` call sites (those paths are local now). The banner stays mounted for sync-issue summaries + initial-sync failure.

- [ ] **Write failing test** — create `packages/app/src/__tests__/storeRefreshRollover.test.ts`:

  ```ts
  // packages/app/src/__tests__/storeRefreshRollover.test.ts
  import { describe, it, expect, beforeEach, vi } from 'vitest';
  import { useStore, setDataClient } from '../store';
  import type { DataClient } from '../data/DataClient';
  import type { Task } from '../types';
  import { format, addDays } from 'date-fns';

  // A LocalDataClient-shaped fake: records which seam methods get called so we can
  // prove refreshFromLocal has NO side effects (no rollover update, no ensureInstances)
  // and that runRolloverOnce is once-per-session.
  function makeFakeClient(tasks: Task[]) {
    const calls = {
      tasksGetAll: 0,
      tasksUpdate: 0,
      projectsGetAll: 0,
      noteGroupsGetAll: 0,
      ensureInstances: 0,
      settingsGet: 0,
    };
    const client = {
      tasks: {
        getAll: vi.fn(async () => {
          calls.tasksGetAll += 1;
          return tasks;
        }),
        update: vi.fn(async (id: string, updates: Partial<Task>) => {
          calls.tasksUpdate += 1;
          return { ...(tasks.find((t) => t.id === id) as Task), ...updates };
        }),
      },
      projects: { getAll: vi.fn(async () => { calls.projectsGetAll += 1; return []; }) },
      noteGroups: { getAll: vi.fn(async () => { calls.noteGroupsGetAll += 1; return []; }) },
      recurrence: {
        ensureInstances: vi.fn(async () => { calls.ensureInstances += 1; return []; }),
      },
      settings: { get: vi.fn(async () => { calls.settingsGet += 1; return { settings: {}, timeZoneId: 'UTC' }; }) },
    } as unknown as DataClient;
    return { client, calls };
  }

  function staleTask(): Task {
    // A past, not-done, non-recurring task — the rollover trigger.
    const yesterday = format(addDays(new Date(), -1), 'yyyy-MM-dd');
    return {
      id: 't-stale',
      title: 'stale',
      status: 'planned',
      plannedDate: yesterday,
      order: 0,
    } as unknown as Task;
  }

  describe('refreshFromLocal (C9.1) — no side effects', () => {
    beforeEach(() => {
      useStore.getState().reset();
    });

    it('re-reads collections but NEVER rolls over or ensures instances', async () => {
      const { client, calls } = makeFakeClient([staleTask()]);
      setDataClient(client);

      await useStore.getState().refreshFromLocal();

      expect(calls.tasksGetAll).toBe(1);
      expect(calls.projectsGetAll).toBe(1);
      expect(calls.noteGroupsGetAll).toBe(1);
      // The defining assertions: a stale task is present yet NO rollover update fires,
      // and ensureInstances is never called from refreshFromLocal.
      expect(calls.tasksUpdate).toBe(0);
      expect(calls.ensureInstances).toBe(0);
      // The stale task is surfaced verbatim (no plannedDate rewrite).
      const t = useStore.getState().tasks.find((x) => x.id === 't-stale')!;
      expect(t.plannedDate).toBe(staleTask().plannedDate);
    });
  });

  describe('runRolloverOnce (C9.3) — once per session, resets on reset()', () => {
    beforeEach(() => {
      useStore.getState().reset();
    });

    it('rolls a stale task forward exactly once across repeated calls, then re-arms after reset()', async () => {
      const { client, calls } = makeFakeClient([staleTask()]);
      setDataClient(client);
      // Seed the store with the stale task (rollover reads from store state).
      await useStore.getState().refreshFromLocal();
      expect(calls.tasksUpdate).toBe(0);

      await useStore.getState().runRolloverOnce();
      await useStore.getState().runRolloverOnce(); // idempotent — no second pass
      // Let the bounded-concurrency background updates settle.
      await new Promise((r) => setTimeout(r, 0));
      expect(calls.tasksUpdate).toBe(1); // exactly one rollover update

      // reset() clears the once-flag so a fresh session can roll over again.
      useStore.getState().reset();
      const second = makeFakeClient([staleTask()]);
      setDataClient(second.client);
      await useStore.getState().refreshFromLocal();
      await useStore.getState().runRolloverOnce();
      await new Promise((r) => setTimeout(r, 0));
      expect(second.calls.tasksUpdate).toBe(1);
    });
  });

  describe('loadTasks (C9.3) — keeps ensureInstances, no longer rolls over', () => {
    beforeEach(() => {
      useStore.getState().reset();
    });

    it('does not roll over but still calls ensureInstances for the horizon', async () => {
      const { client, calls } = makeFakeClient([staleTask()]);
      setDataClient(client);

      await useStore.getState().loadTasks();
      await new Promise((r) => setTimeout(r, 0));
      expect(calls.tasksUpdate).toBe(0); // rollover moved out of loadTasks
      expect(calls.ensureInstances).toBe(1); // horizon ensure call stays
    });
  });
  ```

- [ ] **Run, expect FAIL** — from `packages/app`:

  ```
  npx vitest run src/__tests__/storeRefreshRollover.test.ts
  ```

  Expect FAIL: `useStore.getState().refreshFromLocal` and `runRolloverOnce` are `undefined` (TypeError: `refreshFromLocal is not a function`), and the `loadTasks` test fails because today's `loadTasks` rolls over (`calls.tasksUpdate` is `1`, not `0`).

- [ ] **Minimal impl** — edit `packages/app/src/store.ts`.

  **(a)** Add a module-level rollover once-flag near the `_dataClient` seam. After line 85 (`let _dataClient: DataClient | null = null;`), add:

  ```ts
  // Rollover runs at most once per app session (C9.3). AppRoot calls runRolloverOnce()
  // either when the engine's first cycle settles (online start) or right after
  // initialLoad() (offline start). reset() re-arms it for the next session.
  let _rolloverDone = false;
  ```

  **(b)** Add the two new action signatures to `AppState`. Replace lines 401–402:

  ```ts
    /** Load all data from the server (tasks, projects, notes, settings). Called once after auth. */
    initialLoad: () => Promise<void>;
  ```

  with:

  ```ts
    /** Load all data from the server (tasks, projects, notes, settings). Called once after auth. */
    initialLoad: () => Promise<void>;
    /** Re-read collections from the seam after a remote pull. NO rollover, NO ensureInstances (C9.1). */
    refreshFromLocal: () => Promise<void>;
    /** Run the auto-rollover pass once per session (idempotent; re-armed by reset()) (C9.3). */
    runRolloverOnce: () => Promise<void>;
  ```

  **(c)** Rewrite `loadTasks` to drop the rollover pass (keeping the ensure-instances horizon call), and add the two new actions plus `runRolloverOnce` carrying the extracted rollover. Replace the entire `loadTasks` action (lines 467–548):

  ```ts
    loadTasks: async () => {
      set({ loading: true });
      try {
        const tasks = await dc().tasks.getAll();
        const today = format(new Date(), 'yyyy-MM-dd');

        // Auto-rollover: move past unfinished tasks to today
        // For recurring instances, archive past missed ones instead of rolling forward
        const rolloverActions: Array<() => Promise<unknown>> = [];
        const updatedTasks = tasks.map((task: Task) => {
          if (
            task.plannedDate &&
            task.plannedDate < today &&
            task.status !== 'done' &&
            task.status !== 'archived' &&
            task.status !== 'backlog'
          ) {
            // Recurring instances: archive past missed ones
            if (task.recurrenceRuleId && task.recurrenceOriginalDate) {
              rolloverActions.push(() => dc().tasks.update(task.id, { status: 'archived' }));
              return { ...task, status: 'archived' as Task['status'] };
            }

            const updates: Partial<Task> = {
              plannedDate: today,
              scheduledStart: null,
              scheduledEnd: null,
            };
            // If task was scheduled, unschedule it (the old time slot is in the past)
            if (task.status === 'scheduled') {
              updates.status = 'planned';
            }
            rolloverActions.push(() => dc().tasks.update(task.id, updates));
            return {
              ...task,
              ...updates,
              status: (task.status === 'scheduled' ? 'planned' : task.status) as Task['status'],
            };
          }
          return task;
        });

        // Run rollover updates in the background (don't block UI) with BOUNDED concurrency,
        // and SURFACE any failure via the online-error banner (spec §7: no silent fire-and-forget).
        if (rolloverActions.length > 0) {
          void (async () => {
            const CHUNK = 5;
            const failures: unknown[] = [];
            for (let i = 0; i < rolloverActions.length; i += CHUNK) {
              const results = await Promise.allSettled(rolloverActions.slice(i, i + CHUNK).map((fn) => fn()));
              for (const r of results) if (r.status === 'rejected') failures.push(r.reason);
            }
            if (failures.length > 0) {
              console.error('Rollover update failed:', failures);
              get().setOnlineError(
                `Couldn’t sync ${failures.length} rolled-over task${failures.length === 1 ? '' : 's'} to the server — check your connection.`,
              );
            }
          })();
        }

        set({ tasks: updatedTasks, loading: false });

        // Ensure recurring task instances are generated for the next 2 weeks
        try {
          const twoWeeks = format(addDays(new Date(), 14), 'yyyy-MM-dd');
          const newInstances = await dc().recurrence.ensureInstances(today, twoWeeks);
          if (newInstances && newInstances.length > 0) {
            // ensureInstances returns instances all at order:0 (the mapper can't run the
            // container rank-sort over a partial set), so appending them verbatim would
            // tie them with each other and the existing order:0 task. Give them trailing,
            // distinct orders after the current max to keep the UI sort stable.
            set((s) => ({ tasks: appendWithTrailingOrder(s.tasks, newInstances) }));
          }
        } catch (e) {
          console.error('Failed to ensure recurrence instances:', e);
        }
      } catch (e) {
        console.error('Failed to load tasks:', e);
        set({ loading: false });
      }
    },
  ```

  with:

  ```ts
    loadTasks: async () => {
      set({ loading: true });
      try {
        const tasks = await dc().tasks.getAll();
        const today = format(new Date(), 'yyyy-MM-dd');
        // C9.3: rollover moved out of loadTasks into runRolloverOnce (AppRoot schedules it
        // after the first sync cycle settles, or immediately when the session starts offline).
        set({ tasks, loading: false });

        // Ensure recurring task instances are generated for the next 2 weeks. Read-through:
        // online → server call; offline → returns [] (coast on the horizon, spec §6).
        try {
          const twoWeeks = format(addDays(new Date(), 14), 'yyyy-MM-dd');
          const newInstances = await dc().recurrence.ensureInstances(today, twoWeeks);
          if (newInstances && newInstances.length > 0) {
            // ensureInstances returns instances all at order:0 (the mapper can't run the
            // container rank-sort over a partial set), so appending them verbatim would
            // tie them with each other and the existing order:0 task. Give them trailing,
            // distinct orders after the current max to keep the UI sort stable.
            set((s) => ({ tasks: appendWithTrailingOrder(s.tasks, newInstances) }));
          }
        } catch (e) {
          console.error('Failed to ensure recurrence instances:', e);
        }
      } catch (e) {
        console.error('Failed to load tasks:', e);
        set({ loading: false });
      }
    },

    refreshFromLocal: async () => {
      // C9.1: re-read collections from the seam with NO side effects — no rollover,
      // no ensureInstances. Driven by the engine after a remote pull (changesApplied).
      try {
        const [tasks, projects, noteGroups] = await Promise.all([
          dc().tasks.getAll(),
          dc().projects.getAll(),
          dc().noteGroups.getAll(),
        ]);
        set({ tasks, projects, noteGroups });
        // Synced settings are local rows now; re-read them via the existing action
        // (its local-prefs half is cheap and idempotent).
        await get().loadSettings();
      } catch (e) {
        console.error('Failed to refresh from local:', e);
      }
    },

    runRolloverOnce: async () => {
      // C9.3: idempotent per session. Operates on tasks ALREADY in store state (seeded
      // by initialLoad/refreshFromLocal), so it never re-reads or races a pull.
      if (_rolloverDone) return;
      _rolloverDone = true;
      const today = format(new Date(), 'yyyy-MM-dd');
      const rolloverActions: Array<() => Promise<unknown>> = [];
      const updatedTasks = get().tasks.map((task: Task) => {
        if (
          task.plannedDate &&
          task.plannedDate < today &&
          task.status !== 'done' &&
          task.status !== 'archived' &&
          task.status !== 'backlog'
        ) {
          // Recurring instances: archive past missed ones instead of rolling forward.
          if (task.recurrenceRuleId && task.recurrenceOriginalDate) {
            rolloverActions.push(() => dc().tasks.update(task.id, { status: 'archived' }));
            return { ...task, status: 'archived' as Task['status'] };
          }
          const updates: Partial<Task> = {
            plannedDate: today,
            scheduledStart: null,
            scheduledEnd: null,
          };
          if (task.status === 'scheduled') {
            updates.status = 'planned';
          }
          rolloverActions.push(() => dc().tasks.update(task.id, updates));
          return {
            ...task,
            ...updates,
            status: (task.status === 'scheduled' ? 'planned' : task.status) as Task['status'],
          };
        }
        return task;
      });
      set({ tasks: updatedTasks });
      // Apply the rollover updates locally with BOUNDED concurrency. These now hit the
      // LocalDataClient (instant local write + enqueue), so banner surfacing is dropped
      // (C9.4): a failed local write is a programming error, logged not bannered.
      if (rolloverActions.length > 0) {
        const CHUNK = 5;
        for (let i = 0; i < rolloverActions.length; i += CHUNK) {
          const results = await Promise.allSettled(
            rolloverActions.slice(i, i + CHUNK).map((fn) => fn()),
          );
          for (const r of results) {
            if (r.status === 'rejected') console.error('Rollover update failed:', r.reason);
          }
        }
      }
    },
  ```

  **(d)** Switch the three series-action tails. Replace lines 676–701:

  ```ts
    updateRecurrenceSeries: async (ruleId, updates) => {
      try {
        await dc().recurrence.updateSeries(ruleId, updates);
        await get().loadTasks();
      } catch (e) {
        console.error('Failed to update recurrence series:', e);
      }
    },

    deleteRecurrenceSeries: async (ruleId) => {
      try {
        await dc().recurrence.deleteSeries(ruleId);
        await get().loadTasks();
      } catch (e) {
        console.error('Failed to delete recurrence series:', e);
      }
    },

    deleteRecurrenceSeriesFuture: async (ruleId, fromDate) => {
      try {
        await dc().recurrence.deleteSeriesFuture(ruleId, fromDate);
        await get().loadTasks();
      } catch (e) {
        console.error('Failed to delete future recurrence instances:', e);
      }
    },
  ```

  with:

  ```ts
    updateRecurrenceSeries: async (ruleId, updates) => {
      try {
        await dc().recurrence.updateSeries(ruleId, updates);
        // C9.2: refreshFromLocal (NOT loadTasks) — the local approximation already
        // updated Dexie; loadTasks would re-run rollover + an ensure-instances upsert
        // that can transiently resurrect just-deleted instances.
        await get().refreshFromLocal();
      } catch (e) {
        console.error('Failed to update recurrence series:', e);
      }
    },

    deleteRecurrenceSeries: async (ruleId) => {
      try {
        await dc().recurrence.deleteSeries(ruleId);
        await get().refreshFromLocal();
      } catch (e) {
        console.error('Failed to delete recurrence series:', e);
      }
    },

    deleteRecurrenceSeriesFuture: async (ruleId, fromDate) => {
      try {
        await dc().recurrence.deleteSeriesFuture(ruleId, fromDate);
        await get().refreshFromLocal();
      } catch (e) {
        console.error('Failed to delete future recurrence instances:', e);
      }
    },
  ```

  **(e)** Remove the two settings `setOnlineError` call sites (C9.4: settings are local rows now). Replace lines 1174–1177:

  ```ts
      } catch (e) {
        console.error('Failed to load synced settings:', e);
        get().setOnlineError?.('Couldn’t load your settings from the server.');
      }
    },
  ```

  with:

  ```ts
      } catch (e) {
        // C9.4: settings are local rows now — a read failure is logged, not bannered.
        console.error('Failed to load synced settings:', e);
      }
    },
  ```

  and replace lines 1188–1193:

  ```ts
      try {
        await dc().settings.save(settings, tz);
      } catch (e) {
        console.error('Failed to save synced settings:', e);
        get().setOnlineError?.('Couldn’t save your settings to the server.');
      }
    },
  ```

  with:

  ```ts
      try {
        await dc().settings.save(settings, tz);
      } catch (e) {
        // C9.4: settings save is a local write + enqueue now — logged, not bannered.
        console.error('Failed to save synced settings:', e);
      }
    },
  ```

  **(f)** Re-arm the rollover flag inside `reset()`. Replace lines 1503–1505:

  ```ts
    reset: () => {
      // Clear the module-level DataClient reference so stale calls fail loudly.
      _dataClient = null;
  ```

  with:

  ```ts
    reset: () => {
      // Clear the module-level DataClient reference so stale calls fail loudly.
      _dataClient = null;
      // Re-arm rollover for the next session (C9.3).
      _rolloverDone = false;
  ```

- [ ] **Run, expect PASS** — from `packages/app`:

  ```
  npx vitest run src/__tests__/storeRefreshRollover.test.ts src/store.settingsSplit.test.ts
  ```

  Expect PASS: the new rollover/refresh tests pass and `store.settingsSplit.test.ts` (which only imports `splitSyncedSettings`/`applyLoadedSettings`, untouched) stays green.

- [ ] **Commit**

  ```
  git add packages/app/src/store.ts packages/app/src/__tests__/storeRefreshRollover.test.ts
  git commit -m "$(cat <<'EOF'
feat(app): R5-4 — store refreshFromLocal + runRolloverOnce; series tails; drop banner sites (C9)

Adds refreshFromLocal() (re-reads tasks/projects/noteGroups/settings, NO rollover,
NO ensureInstances — C9.1) and runRolloverOnce() (rollover extracted from
loadTasks, idempotent per session via a module flag re-armed by reset() — C9.3).
loadTasks keeps only its ensure-instances horizon call. The three series actions
switch their tails from loadTasks() to refreshFromLocal() (C9.2). Settings
load/save setOnlineError sites removed — those paths are local now (C9.4); the
banner stays mounted for sync-issue/initial-sync summaries.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task R5-5: `createSyncWiring` factory + `InitialSyncGate` + AppRoot rewiring (C10)

**Files:**
- Create `packages/app/src/wiring/createSyncWiring.ts`
- Create test `packages/app/src/wiring/__tests__/createSyncWiring.test.ts`
- Create `packages/app/src/components/InitialSyncGate.tsx`
- Modify `packages/app/src/AppRoot.tsx`

C10 sequence on `onAuthed(user)`: `setLastUserId(user.id)` → `LocalStore.open(user.id)` → write `meta.lastUser` → build `SyncEngine` + `LocalDataClient` (the engine IS the `SyncBridge` — `new LocalDataClient(store, engine)`, no nudge adapter) → `setDataClient(...)` → **subscribe `engine.onChangesApplied(() => store.refreshFromLocal())`** (the mandated remote-pull→UI-refresh path, spec §1/AC3) → `engine.start()` → gate on `meta.initialSyncComplete` → `initialLoad()` → rollover per C9.4 (on `onFirstCycleSettled` when online, or immediately when connectivity says offline at start). The `onChangesApplied` and `onFirstCycleSettled` unsubscribers are tracked and drained in `onLoggedOut`. `onLoggedOut` → drain subscriptions → `engine.stop()` → `store.close()` → `resetStore()`. The repo's vitest is `node`-env with no component test convention, so the wiring **logic** is extracted into a pure `createSyncWiring(deps)` factory (no React, fully injectable) and unit-tested; `AppRoot` and `InitialSyncGate` are thin shells covered by `tsc` + the R6 live gate. `GatedApp` reads the engine's status via the typed synchronous `engine.getStatus()` + `engine.subscribe()` (C5).

- [ ] **Write failing test** — create `packages/app/src/wiring/__tests__/createSyncWiring.test.ts`:

  ```ts
  // packages/app/src/wiring/__tests__/createSyncWiring.test.ts
  import { describe, it, expect, vi, beforeEach } from 'vitest';
  import { createSyncWiring } from '../createSyncWiring';

  // Minimal fakes for the injected collaborators. We assert the ORDER, the
  // online-vs-offline rollover scheduling, and the onChangesApplied→refreshFromLocal
  // subscription (C10) — all the logic the factory owns.
  function makeDeps(opts: { online: boolean; initialSyncComplete: boolean }) {
    const order: string[] = [];
    let firstCycleCb: (() => void) | null = null;
    let changesAppliedCb: (() => void) | null = null;

    const store = {
      open: vi.fn((id: string) => {
        order.push(`open:${id}`);
        return {
          setMeta: vi.fn(async (k: string) => { order.push(`setMeta:${k}`); }),
          getMeta: vi.fn(async (k: string) =>
            k === 'initialSyncComplete' ? opts.initialSyncComplete : undefined,
          ),
          close: vi.fn(() => { order.push('store.close'); }),
        };
      }),
    };
    const engine = {
      start: vi.fn(() => { order.push('engine.start'); }),
      stop: vi.fn(() => { order.push('engine.stop'); }),
      online: vi.fn(() => opts.online),
      onChangesApplied: vi.fn((cb: () => void) => {
        changesAppliedCb = cb;
        return () => { order.push('unsub:changesApplied'); };
      }),
      onFirstCycleSettled: vi.fn((cb: () => void) => {
        firstCycleCb = cb;
        return () => { order.push('unsub:firstCycle'); };
      }),
    };
    const buildEngine = vi.fn((_store: any) => { order.push('buildEngine'); return engine; });
    const buildDataClient = vi.fn((_store: any, _engine: any) => {
      order.push('buildDataClient');
      return { kind: 'local' };
    });

    const deps = {
      setLastUserId: vi.fn((id: string | null) => { order.push(`setLastUserId:${id}`); }),
      openStore: (id: string) => store.open(id),
      writeLastUserMeta: vi.fn(async (s: any, user: any) => {
        order.push(`writeLastUserMeta:${user.id}`);
        await s.setMeta('lastUser', user);
      }),
      buildEngine,
      buildDataClient,
      setDataClient: vi.fn((_c: any) => { order.push('setDataClient'); }),
      initialLoad: vi.fn(async () => { order.push('initialLoad'); }),
      refreshFromLocal: vi.fn(async () => { order.push('refreshFromLocal'); }),
      runRolloverOnce: vi.fn(async () => { order.push('runRolloverOnce'); }),
      resetStore: vi.fn(() => { order.push('resetStore'); }),
    };
    return {
      deps,
      order,
      engine,
      store,
      fireFirstCycle: () => firstCycleCb?.(),
      fireChangesApplied: () => changesAppliedCb?.(),
    };
  }

  describe('createSyncWiring.onAuthed', () => {
    it('runs the C10 sequence in order and DEFERS rollover to onFirstCycleSettled when online', async () => {
      const { deps, order, engine, fireFirstCycle } = makeDeps({ online: true, initialSyncComplete: true });
      const wiring = createSyncWiring(deps as any);

      await wiring.onAuthed({ id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });

      // Order through initialLoad; rollover NOT yet run (deferred online).
      expect(order).toEqual([
        'setLastUserId:u1',
        'open:u1',
        'writeLastUserMeta:u1',
        'setMeta:lastUser',
        'buildEngine',
        'buildDataClient',
        'setDataClient',
        'engine.start',
        'initialLoad',
      ]);
      expect(engine.onFirstCycleSettled).toHaveBeenCalledTimes(1);
      expect(deps.runRolloverOnce).not.toHaveBeenCalled();

      // When the first cycle settles, rollover runs against reconciled rows.
      fireFirstCycle();
      await Promise.resolve();
      expect(deps.runRolloverOnce).toHaveBeenCalledTimes(1);
    });

    it('runs rollover IMMEDIATELY (not deferred) when connectivity says offline at start', async () => {
      const { deps, engine } = makeDeps({ online: false, initialSyncComplete: true });
      const wiring = createSyncWiring(deps as any);

      await wiring.onAuthed({ id: 'u2', email: 'o@ff.line', timeZoneId: 'UTC' });

      // Offline start: the session's queued writes are the newest intent, so roll over now.
      expect(deps.runRolloverOnce).toHaveBeenCalledTimes(1);
      expect(engine.onFirstCycleSettled).not.toHaveBeenCalled();
    });

    it('reports needsInitialSyncGate from meta.initialSyncComplete', async () => {
      const a = makeDeps({ online: true, initialSyncComplete: false });
      const wiringA = createSyncWiring(a.deps as any);
      const resA = await wiringA.onAuthed({ id: 'u3', email: 'x@y.z', timeZoneId: 'UTC' });
      expect(resA.needsInitialSyncGate).toBe(true);

      const b = makeDeps({ online: true, initialSyncComplete: true });
      const wiringB = createSyncWiring(b.deps as any);
      const resB = await wiringB.onAuthed({ id: 'u4', email: 'x@y.z', timeZoneId: 'UTC' });
      expect(resB.needsInitialSyncGate).toBe(false);
    });

    it('subscribes onChangesApplied → refreshFromLocal (the remote-pull→UI-refresh path, C10)', async () => {
      const { deps, engine, fireChangesApplied } = makeDeps({ online: true, initialSyncComplete: true });
      const wiring = createSyncWiring(deps as any);

      await wiring.onAuthed({ id: 'u5', email: 'a@b.c', timeZoneId: 'UTC' });

      // The subscription is registered during onAuthed (before the first cycle can apply pages).
      expect(engine.onChangesApplied).toHaveBeenCalledTimes(1);
      expect(deps.refreshFromLocal).not.toHaveBeenCalled();

      // When the engine applies pulled changes, the store re-reads from local.
      fireChangesApplied();
      await Promise.resolve();
      expect(deps.refreshFromLocal).toHaveBeenCalledTimes(1);
    });
  });

  describe('createSyncWiring.onLoggedOut', () => {
    it('drains subscriptions, stops the engine, closes the store, resets the store', async () => {
      const { deps, order } = makeDeps({ online: true, initialSyncComplete: true });
      const wiring = createSyncWiring(deps as any);
      await wiring.onAuthed({ id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
      order.length = 0;

      wiring.onLoggedOut();
      // Online start registered BOTH onChangesApplied + onFirstCycleSettled; their
      // unsubscribers are drained (in registration order) before engine.stop/teardown.
      expect(order).toEqual([
        'unsub:changesApplied',
        'unsub:firstCycle',
        'engine.stop',
        'store.close',
        'resetStore',
      ]);
    });

    it('onLoggedOut before any onAuthed is a safe no-op (no engine/store yet)', () => {
      const { deps } = makeDeps({ online: true, initialSyncComplete: true });
      const wiring = createSyncWiring(deps as any);
      expect(() => wiring.onLoggedOut()).not.toThrow();
    });
  });
  ```

- [ ] **Run, expect FAIL** — from `packages/app`:

  ```
  npx vitest run src/wiring/__tests__/createSyncWiring.test.ts
  ```

  Expect FAIL: `Cannot find module '../createSyncWiring'` (the file does not exist yet).

- [ ] **Minimal impl (1/2)** — create `packages/app/src/wiring/createSyncWiring.ts`:

  ```ts
  // packages/app/src/wiring/createSyncWiring.ts
  // Pure wiring logic for AppRoot's authed lifecycle (C10), extracted so it can be
  // unit-tested in the node vitest env without React. AppRoot injects real
  // collaborators (LocalStore.open, new SyncEngine, new LocalDataClient, the store
  // actions); this owns only the SEQUENCE and the online-vs-offline rollover scheduling.
  import type { AuthUser } from '../auth/types';
  import type { SyncStatus } from '../sync/types';

  // Structural surfaces — only the members this factory touches (subset of C3/C5/C4).
  export interface WiringStore {
    setMeta(key: string, value: unknown): Promise<void>;
    getMeta<T>(key: string): Promise<T | undefined>;
    close(): void;
  }
  // The members AppRoot/App's shells read off the live engine. A structural subset of
  // the C5 SyncEngine surface (getStatus/subscribe/syncNow/retryIssue/discardIssue +
  // the lifecycle + callback hooks) so the React shells are typed without `as any`.
  export interface WiringEngine {
    start(): void;
    stop(): void;
    online(): boolean;
    getStatus(): SyncStatus;
    subscribe(cb: (s: SyncStatus) => void): () => void;
    syncNow(): Promise<void>;
    retryIssue(id: number): Promise<void>;
    discardIssue(id: number): Promise<void>;
    onChangesApplied(cb: () => void): () => void;
    onFirstCycleSettled(cb: () => void): () => void;
  }

  export interface SyncWiringDeps {
    /** Global pointer write (C3 setLastUserId over localStorage). */
    setLastUserId(id: string | null): void;
    /** LocalStore.open(userId) (C3). */
    openStore(userId: string): WiringStore;
    /** Persist meta.lastUser { id, email, timeZoneId }. */
    writeLastUserMeta(store: WiringStore, user: AuthUser): Promise<void>;
    /** new SyncEngine({ store, transport }) (C5). */
    buildEngine(store: WiringStore): WiringEngine;
    /** new LocalDataClient(store, engine) (C4). */
    buildDataClient(store: WiringStore, engine: WiringEngine): unknown;
    /** store.setDataClient(localDataClient). */
    setDataClient(client: unknown): void;
    /** store.initialLoad(). */
    initialLoad(): Promise<void>;
    /** store.refreshFromLocal() (C9.1) — driven by the engine after a remote pull. */
    refreshFromLocal(): Promise<void>;
    /** store.runRolloverOnce() (C9.3). */
    runRolloverOnce(): Promise<void>;
    /** store.reset(). */
    resetStore(): void;
  }

  export interface OnAuthedResult {
    /** True when meta.initialSyncComplete is falsy → AppRoot renders <InitialSyncGate>. */
    needsInitialSyncGate: boolean;
  }

  export interface SyncWiring {
    onAuthed(user: AuthUser): Promise<OnAuthedResult>;
    onLoggedOut(): void;
    /** Current engine (for AppRoot to feed SyncStatusPill via engine.subscribe); null before onAuthed. */
    getEngine(): WiringEngine | null;
  }

  export function createSyncWiring(deps: SyncWiringDeps): SyncWiring {
    let store: WiringStore | null = null;
    let engine: WiringEngine | null = null;
    const subscriptions: Array<() => void> = []; // unsubscribers, drained on logout

    return {
      async onAuthed(user: AuthUser): Promise<OnAuthedResult> {
        deps.setLastUserId(user.id);
        store = deps.openStore(user.id);
        await deps.writeLastUserMeta(store, user);
        engine = deps.buildEngine(store);
        const dataClient = deps.buildDataClient(store, engine);
        deps.setDataClient(dataClient);

        // C10: the remote-pull → UI-refresh path (spec §1/AC3). When the engine applies
        // pulled changes to the local store it fires onChangesApplied; re-read collections
        // into the Zustand store so the UI reflects converged multi-device state. Registered
        // BEFORE engine.start() so the very first cycle's applied changes are not missed.
        subscriptions.push(
          engine.onChangesApplied(() => {
            void deps.refreshFromLocal();
          }),
        );

        engine.start();

        const initialSyncComplete = (await store.getMeta<boolean>('initialSyncComplete')) === true;

        // C9.3 rollover scheduling: online → defer until the first cycle settles (reconciled
        // rows); offline → run now (queued writes are the newest intent).
        if (engine.online()) {
          subscriptions.push(
            engine.onFirstCycleSettled(() => {
              void deps.runRolloverOnce();
            }),
          );
          await deps.initialLoad();
        } else {
          await deps.initialLoad();
          await deps.runRolloverOnce();
        }

        return { needsInitialSyncGate: !initialSyncComplete };
      },

      onLoggedOut(): void {
        for (const unsub of subscriptions.splice(0)) {
          try {
            unsub();
          } catch {
            /* ignore */
          }
        }
        engine?.stop();
        store?.close();
        deps.resetStore();
        engine = null;
        store = null;
      },

      getEngine(): WiringEngine | null {
        return engine;
      },
    };
  }
  ```

  > The `onChangesApplied → refreshFromLocal` subscription (C10) is registered in BOTH the online and offline branches (it sits before the connectivity split) and BEFORE `engine.start()` so the first cycle's applied changes trigger a UI refresh. In the online case `engine.onFirstCycleSettled(...)` is registered **before** `initialLoad()` so the deferral hook is armed before the first cycle (which `engine.start()` kicks off) can settle. Both subscriptions' unsubscribers are tracked in `subscriptions` and drained in `onLoggedOut` for clean teardown. The test asserts the registrations happen during `onAuthed`, that firing onChangesApplied invokes `refreshFromLocal`, and that firing onFirstCycleSettled runs rollover exactly once.

- [ ] **Minimal impl (2/2a)** — create `packages/app/src/components/InitialSyncGate.tsx`:

  ```tsx
  // packages/app/src/components/InitialSyncGate.tsx
  import React from 'react';
  import { RefreshCw, CloudOff } from 'lucide-react';
  import type { SyncStatus } from '../sync/types';

  /**
   * First-render gate (spec §7.4 / C10). While the initial full sync is incomplete:
   *   - show a spinner;
   *   - if the engine reports an error/issue AND the initial sync is still incomplete,
   *     show a retry button (calls syncNow) over whatever partial pages were applied;
   *   - once initialSyncComplete flips true, render children.
   * Never blocks render indefinitely: a failed/interrupted initial pull leaves the
   * retry affordance and resumes on the next trigger.
   */
  export function InitialSyncGate({
    status,
    onRetry,
    children,
  }: {
    status: SyncStatus;
    onRetry: () => void;
    children: React.ReactNode;
  }) {
    if (status.initialSyncComplete) return <>{children}</>;

    const failed = status.issues.length > 0 || (!status.syncing && !status.online);

    return (
      <div className="h-screen flex flex-col items-center justify-center bg-surface-950 text-surface-400 select-none">
        <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
        {failed ? (
          <>
            <CloudOff className="w-8 h-8 text-warning-400" />
            <p className="mt-4 text-sm text-surface-300">Couldn’t finish the first sync.</p>
            <button
              onClick={onRetry}
              className="mt-4 flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-lg bg-accent-600 hover:bg-accent-500 text-white transition-colors"
            >
              <RefreshCw className="w-4 h-4" />
              Retry
            </button>
          </>
        ) : (
          <>
            <div className="w-8 h-8 rounded-full border-2 border-surface-700 border-t-accent-500 animate-spin" />
            <p className="mt-4 text-sm">Syncing your data…</p>
          </>
        )}
      </div>
    );
  }
  ```

- [ ] **Minimal impl (2/2b)** — rewire `packages/app/src/AppRoot.tsx`. Replace the whole file with:

  ```tsx
  // packages/app/src/AppRoot.tsx
  import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
  import App from './App';
  import { useStore } from './store';
  import { LocalDataClient } from './data/local/LocalDataClient';
  import { LocalStore, getLastUserId, setLastUserId } from './data/local/LocalStore';
  import { SyncEngine } from './sync/SyncEngine';
  import { HttpSyncTransport } from './sync/SyncTransport';
  import { InitialSyncGate } from './components/InitialSyncGate';
  import { createSyncWiring, type WiringStore, type WiringEngine } from './wiring/createSyncWiring';
  import type { Platform } from './platform/Platform';
  import type { TmapClient } from '@tmap/api-client';
  import type { AuthUser } from './auth/types';
  import type { SyncStatus } from './sync/types';
  import {
    initAuthStore,
    getAuthStore,
    useAuthStore,
    createRefreshClient,
    LoginView,
    RegisterView,
    type AuthState,
  } from './auth';

  export interface AppRootProps {
    /** Host capabilities + token storage + events. */
    platform: Platform;
    /**
     * Raw typed client (no refresh wrapping). AppRoot wraps it ONCE with the 401→refresh
     * layer and routes BOTH auth POSTs (register/login/logout) AND the sync transport
     * through that single wrapped client — one shared refresh path.
     */
    tmapClient: TmapClient;
  }

  // ─── Platform context ───────────────────────────────────────
  const PlatformContext = createContext<Platform | null>(null);
  export function usePlatform(): Platform {
    const p = useContext(PlatformContext);
    if (!p) throw new Error('usePlatform must be used within <AppRoot>');
    return p;
  }
  export { PlatformContext };

  // The live SyncEngine for the current authed session (read by App's SyncStatusPill).
  // Set by the wiring factory's onAuthed; cleared on logout.
  const EngineContext = createContext<WiringEngine | null>(null);
  export function useEngine(): WiringEngine | null {
    return useContext(EngineContext);
  }

  type AnonScreen = 'login' | 'register';

  export function AppRoot({ platform, tmapClient }: AppRootProps) {
    const initialized = useRef(false);
    const [anonScreen, setAnonScreen] = useState<AnonScreen>('login');
    const [engine, setEngine] = useState<WiringEngine | null>(null);
    const [needsGate, setNeedsGate] = useState(false);

    if (!initialized.current) {
      initialized.current = true;

      // ONE refresh wrapper around the raw client (401→refresh→retry); see C8.1.
      const refreshClient = createRefreshClient({
        client: tmapClient as any,
        refresh: () => getAuthStore().getState().refresh(),
        onLogout: () => {
          void getAuthStore().getState().logout();
        },
      });
      refreshClient.setAbortController(new AbortController());

      // Pure wiring factory (C10). Injects real LocalStore/SyncEngine/LocalDataClient
      // builders + the store actions. The transport is the refresh-wrapped client.
      const wiring = createSyncWiring({
        setLastUserId: (id) => setLastUserId(id),
        openStore: (userId) => LocalStore.open(userId) as unknown as WiringStore,
        writeLastUserMeta: async (store, user) => {
          await store.setMeta('lastUser', {
            id: user.id,
            email: user.email,
            timeZoneId: user.timeZoneId,
          });
        },
        buildEngine: (store) =>
          new SyncEngine({
            store: store as unknown as LocalStore,
            transport: new HttpSyncTransport(refreshClient),
          }) as unknown as WiringEngine,
        buildDataClient: (store, eng) =>
          new LocalDataClient(store as unknown as LocalStore, eng as unknown as any),
        setDataClient: (client) => useStore.getState().setDataClient(client as any),
        initialLoad: () => useStore.getState().initialLoad(),
        // C10: the engine's onChangesApplied → store.refreshFromLocal() is wired by the
        // factory; AppRoot supplies the store action here (read getState() lazily so the
        // call always hits the live store, even after a reset()).
        refreshFromLocal: () => useStore.getState().refreshFromLocal(),
        runRolloverOnce: () => useStore.getState().runRolloverOnce(),
        resetStore: () => useStore.getState().reset(),
      });

      initAuthStore({
        client: tmapClient as any, // raw: auth 401s are real errors, not refresh triggers
        platform,
        // C8.3: resolve last user from the global pointer + that DB's meta.lastUser,
        // consulted only on the transient-refresh branch (authed-offline).
        resolveLastUser: async () => {
          const id = getLastUserId();
          if (!id) return null;
          const store = LocalStore.open(id);
          try {
            const lu = await store.getMeta<AuthUser>('lastUser');
            return lu ?? null;
          } finally {
            store.close();
          }
        },
        onAuthed: (auth) => {
          refreshClient.reactivate();
          void (async () => {
            const { needsInitialSyncGate } = await wiring.onAuthed(auth.user);
            setEngine(wiring.getEngine());
            setNeedsGate(needsInitialSyncGate);
          })();
        },
        onLoggedOut: () => {
          refreshClient.signOut();
          wiring.onLoggedOut();
          setEngine(null);
          setNeedsGate(false);
        },
      });
    }

    const status = useAuthStore((s: AuthState) => s.status);

    useEffect(() => {
      void getAuthStore().getState().bootstrap();
    }, []);

    // Web only: request Notification permission once so client-side reminders can fire.
    useEffect(() => {
      if (platform.capabilities.tray) return; // desktop → OS notifications via main
      if (
        typeof window !== 'undefined' &&
        'Notification' in window &&
        window.Notification.permission === 'default'
      ) {
        void window.Notification.requestPermission().catch(() => {});
      }
    }, []);

    const content = useMemo(() => {
      if (status === 'loading') {
        return (
          <div className="h-screen flex flex-col items-center justify-center bg-surface-950 text-surface-400 select-none">
            <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
            <div className="w-8 h-8 rounded-full border-2 border-surface-700 border-t-accent-500 animate-spin" />
            <p className="mt-4 text-sm">Loading…</p>
          </div>
        );
      }
      if (status === 'anonymous') {
        return anonScreen === 'login' ? (
          <LoginView onSwitchToRegister={() => setAnonScreen('register')} />
        ) : (
          <RegisterView onSwitchToLogin={() => setAnonScreen('login')} />
        );
      }
      return (
        <PlatformContext.Provider value={platform}>
          <EngineContext.Provider value={engine}>
            <GatedApp engine={engine} needsGate={needsGate} />
          </EngineContext.Provider>
        </PlatformContext.Provider>
      );
    }, [status, anonScreen, platform, engine, needsGate]);

    return content;
  }

  /**
   * Wraps <App/> in the InitialSyncGate while the first full sync is incomplete.
   * Subscribes to the engine's status so the gate re-renders as pages apply.
   */
  function GatedApp({ engine, needsGate }: { engine: WiringEngine | null; needsGate: boolean }) {
    // C5: getStatus() is a SYNCHRONOUS snapshot — no optional-call / `as any` cast needed.
    const [status, setStatus] = useState<SyncStatus | null>(() =>
      engine ? engine.getStatus() : null,
    );

    useEffect(() => {
      if (!engine) return;
      // SyncEngine.subscribe (C5) fires once immediately with getStatus() then on each
      // change, and returns an unsubscribe fn. The gate re-renders as pages apply and
      // lifts once a subscribed status reports initialSyncComplete (C10).
      return engine.subscribe((s) => setStatus(s));
    }, [engine]);

    if (!needsGate || !engine || !status) return <App />;

    return (
      <InitialSyncGate status={status} onRetry={() => void engine.syncNow()}>
        <App />
      </InitialSyncGate>
    );
  }

  export default AppRoot;
  ```

  > AppRoot no longer imports `HttpDataClient` (deleted in R6); R6's deletion task confirms AppRoot is HttpDataClient-free after R5. `SyncEngine`/`SyncTransport`/`HttpSyncTransport`/`LocalStore`/`LocalDataClient` are the R1–R4 modules; their import paths match the C3/C5/C4 file map. The `SyncEngine` structurally satisfies `SyncBridge` (it exposes `nudge`/`online`/`ensureInstances`, C5), so `new LocalDataClient(store, engine)` passes the engine **directly** as the bridge — no nudge adapter/wrapper. `GatedApp` reads the engine's status with the typed synchronous `engine.getStatus()` and `engine.subscribe(...)`/`engine.syncNow()` (C5) — no `as any`/`?.()` casts on the status surface. The remaining `as unknown as` casts (e.g. `store as unknown as LocalStore`, `eng as unknown as any` into `LocalDataClient`) are the deliberate boundary between the React-free pure factory (typed against the structural `WiringStore`/`WiringEngine` subsets, so it stays unit-testable) and AppRoot's concrete classes.

- [ ] **Run, expect PASS** — from `packages/app`:

  ```
  npx vitest run src/wiring/__tests__/createSyncWiring.test.ts
  ```

  Expect PASS: all 7 wiring tests green (online deferral, offline-immediate rollover, gate flag, onChangesApplied→refreshFromLocal, logout teardown order, no-op-before-authed).

- [ ] **Commit**

  ```
  git add packages/app/src/wiring/createSyncWiring.ts packages/app/src/wiring/__tests__/createSyncWiring.test.ts packages/app/src/components/InitialSyncGate.tsx packages/app/src/AppRoot.tsx
  git commit -m "$(cat <<'EOF'
feat(app): R5-5 — createSyncWiring factory + InitialSyncGate + AppRoot rewiring (C10)

AppRoot's authed lifecycle is extracted into a pure, React-free createSyncWiring()
factory (node-vitest-testable): onAuthed runs the C10 sequence (setLastUserId →
LocalStore.open → meta.lastUser → SyncEngine + LocalDataClient → setDataClient →
subscribe engine.onChangesApplied → store.refreshFromLocal (the remote-pull→UI
refresh path, spec §1/AC3) → engine.start → gate on meta.initialSyncComplete →
initialLoad → rollover) and defers rollover to onFirstCycleSettled when online /
runs it immediately when offline (C9.3). Both subscriptions' unsubscribers are
tracked and drained on logout. onLoggedOut stops the engine, closes the store,
resets the store. InitialSyncGate shows a spinner, or a Retry (syncNow) button on
error+!initialSyncComplete, and renders children once complete (§7.4); the gate
reads engine.getStatus()/subscribe() typed (C5 — no `as any`). AppRoot is now a
thin shell over the factory + the LocalStore/SyncEngine modules; the engine IS the
SyncBridge passed to LocalDataClient (no nudge adapter); no more HttpDataClient
import. resolveLastUser is wired for C8.3 authed-offline bootstrap.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task R5-6: `useSyncStatus` hook + `SyncStatusPill` + popover + Sign-out surface (§8, C10)

**Files:**
- Create `packages/app/src/sync/useSyncStatus.ts`
- Create test `packages/app/src/sync/__tests__/useSyncStatus.test.ts`
- Create `packages/app/src/components/SyncStatusPill.tsx`
- Create `packages/app/src/components/signOutConfirm.ts`
- Create test `packages/app/src/components/__tests__/signOutConfirm.test.ts`
- Modify `packages/app/src/App.tsx` (mount the pill)
- Modify `packages/app/src/components/SettingsDialog.tsx` (Sign-out)

§8: one pill fed by `engine.subscribe`'s `SyncStatus`, with a popover (last-synced time, pending count, issues list with retry/discard, "retry now"). C10: a "Sign out" button in `SettingsDialog` with a confirm warning when `pendingOps > 0`, wired to `LocalStore.wipe` + `setLastUserId(null)` + `authStore.logout`. The renderer presentation logic that's worth testing is (a) the pill's visual state derived from a `SyncStatus`, and (b) whether sign-out must confirm. Both are extracted to pure functions and unit-tested; the React shells (pill markup, popover, the confirm modal) rely on `tsc` + the live gate.

- [ ] **Write failing test (1/2)** — create `packages/app/src/sync/__tests__/useSyncStatus.test.ts`:

  ```ts
  // packages/app/src/sync/__tests__/useSyncStatus.test.ts
  import { describe, it, expect } from 'vitest';
  import { derivePillState } from '../useSyncStatus';
  import type { SyncStatus } from '../types';

  function status(p: Partial<SyncStatus>): SyncStatus {
    return {
      online: true,
      syncing: false,
      pendingOps: 0,
      lastSyncedAt: null,
      issues: [],
      initialSyncComplete: true,
      ...p,
    };
  }

  describe('derivePillState (§8)', () => {
    it('synced + idle → quiet ok', () => {
      expect(derivePillState(status({}))).toEqual({ kind: 'ok', label: 'Synced', pending: 0 });
    });

    it('a running cycle → syncing spinner (takes precedence over pending count)', () => {
      const s = derivePillState(status({ syncing: true, pendingOps: 3 }));
      expect(s.kind).toBe('syncing');
    });

    it('offline with pending → offline state showing the pending count', () => {
      const s = derivePillState(status({ online: false, pendingOps: 2 }));
      expect(s.kind).toBe('offline');
      expect(s.pending).toBe(2);
    });

    it('non-empty issues → warning (even when online and idle)', () => {
      const s = derivePillState(
        status({ issues: [{ at: 'now', op: {} as any, reason: '400', status: 'dropped' }] }),
      );
      expect(s.kind).toBe('warning');
    });

    it('incomplete initial sync → warning', () => {
      const s = derivePillState(status({ initialSyncComplete: false }));
      expect(s.kind).toBe('warning');
    });

    it('syncing precedence: a running cycle beats a warning issue list', () => {
      const s = derivePillState(
        status({ syncing: true, issues: [{ at: 'n', op: {} as any, reason: 'x', status: 'parked' }] }),
      );
      expect(s.kind).toBe('syncing');
    });
  });
  ```

- [ ] **Write failing test (2/2)** — create `packages/app/src/components/__tests__/signOutConfirm.test.ts`:

  ```ts
  // packages/app/src/components/__tests__/signOutConfirm.test.ts
  import { describe, it, expect } from 'vitest';
  import { shouldConfirmSignOut, signOutWarning } from '../signOutConfirm';

  describe('signOut confirm gating (C10)', () => {
    it('requires confirmation when there are pending ops', () => {
      expect(shouldConfirmSignOut(1)).toBe(true);
      expect(shouldConfirmSignOut(7)).toBe(true);
    });

    it('does not require confirmation when the queue is empty', () => {
      expect(shouldConfirmSignOut(0)).toBe(false);
    });

    it('warning copy names the count of unsynced changes that will be lost', () => {
      expect(signOutWarning(1)).toMatch(/1 unsynced change/);
      expect(signOutWarning(3)).toMatch(/3 unsynced changes/);
    });
  });
  ```

- [ ] **Run, expect FAIL** — from `packages/app`:

  ```
  npx vitest run src/sync/__tests__/useSyncStatus.test.ts src/components/__tests__/signOutConfirm.test.ts
  ```

  Expect FAIL: `Cannot find module '../useSyncStatus'` and `Cannot find module '../signOutConfirm'` — neither file exists yet.

- [ ] **Minimal impl (1/4)** — create `packages/app/src/sync/useSyncStatus.ts`:

  ```ts
  // packages/app/src/sync/useSyncStatus.ts
  import { useEffect, useState } from 'react';
  import type { SyncStatus } from './types';

  export type PillKind = 'ok' | 'syncing' | 'offline' | 'warning';

  export interface PillState {
    kind: PillKind;
    label: string;
    pending: number;
  }

  /**
   * Pure projection of a SyncStatus to the pill's visual state (§8). Precedence:
   * a running cycle (syncing) > warning (issues OR initial sync incomplete) >
   * offline (with pending count) > quiet ok.
   */
  export function derivePillState(s: SyncStatus): PillState {
    if (s.syncing) return { kind: 'syncing', label: 'Syncing…', pending: s.pendingOps };
    if (s.issues.length > 0 || !s.initialSyncComplete)
      return { kind: 'warning', label: 'Sync issue', pending: s.pendingOps };
    if (!s.online) return { kind: 'offline', label: 'Offline', pending: s.pendingOps };
    return { kind: 'ok', label: 'Synced', pending: s.pendingOps };
  }

  /** Minimal structural surface of the SyncEngine this hook needs (C5 subset). */
  export interface StatusSource {
    subscribe(cb: (s: SyncStatus) => void): () => void;
  }

  const EMPTY: SyncStatus = {
    online: true,
    syncing: false,
    pendingOps: 0,
    lastSyncedAt: null,
    issues: [],
    initialSyncComplete: true,
  };

  /** Subscribes to the engine's status stream; returns the latest SyncStatus. */
  export function useSyncStatus(engine: StatusSource | null): SyncStatus {
    const [status, setStatus] = useState<SyncStatus>(EMPTY);
    useEffect(() => {
      if (!engine) return;
      return engine.subscribe(setStatus);
    }, [engine]);
    return status;
  }
  ```

- [ ] **Minimal impl (2/4)** — create `packages/app/src/components/signOutConfirm.ts`:

  ```ts
  // packages/app/src/components/signOutConfirm.ts
  /** C10: an explicit sign-out must confirm only when unsynced work would be lost. */
  export function shouldConfirmSignOut(pendingOps: number): boolean {
    return pendingOps > 0;
  }

  /** Warning copy for the confirm dialog when pendingOps > 0. */
  export function signOutWarning(pendingOps: number): string {
    const noun = pendingOps === 1 ? 'unsynced change' : 'unsynced changes';
    return `You have ${pendingOps} ${noun} that haven’t reached the server yet. Signing out will erase your local data and discard them. Continue?`;
  }
  ```

- [ ] **Minimal impl (3/4)** — create `packages/app/src/components/SyncStatusPill.tsx`:

  ```tsx
  // packages/app/src/components/SyncStatusPill.tsx
  import React, { useState } from 'react';
  import * as Popover from '@radix-ui/react-popover';
  import { Cloud, CloudOff, RefreshCw, AlertTriangle, Check, X, Trash2 } from 'lucide-react';
  import { clsx } from 'clsx';
  import { useEngine } from '../AppRoot';
  import { useSyncStatus, derivePillState } from '../sync/useSyncStatus';
  import type { SyncIssue } from '../sync/types';

  /**
   * Sync-status pill (spec §8). Mounted in App's titlebar region. Reads the live
   * SyncEngine status via the engine context (engine.subscribe). Click → popover
   * with last-synced time, pending count, the persisted issues list (retry/discard),
   * and a "retry now" button. Hidden in the quiet-ok state to stay unobtrusive.
   */
  export function SyncStatusPill() {
    // C5: useEngine() is the typed SyncEngine surface (subscribe/syncNow/retryIssue/
    // discardIssue) — no `as any` cast. It structurally satisfies useSyncStatus's
    // StatusSource (it has subscribe).
    const engine = useEngine();
    const status = useSyncStatus(engine);
    const pill = derivePillState(status);
    const [open, setOpen] = useState(false);

    // Quiet-ok with no pending and no issues → stay out of the way.
    if (pill.kind === 'ok' && pill.pending === 0) return null;

    const Icon =
      pill.kind === 'syncing'
        ? RefreshCw
        : pill.kind === 'offline'
          ? CloudOff
          : pill.kind === 'warning'
            ? AlertTriangle
            : Cloud;

    const tone =
      pill.kind === 'warning'
        ? 'text-warning-300 border-warning-500/40 bg-warning-600/10'
        : pill.kind === 'offline'
          ? 'text-surface-300 border-surface-700/60 bg-surface-800/60'
          : 'text-surface-300 border-surface-700/60 bg-surface-800/40';

    return (
      <Popover.Root open={open} onOpenChange={setOpen}>
        <Popover.Trigger asChild>
          <button
            className={clsx(
              'flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-xs font-medium transition-colors no-drag',
              tone,
            )}
            title="Sync status"
          >
            <Icon className={clsx('w-3.5 h-3.5', pill.kind === 'syncing' && 'animate-spin')} />
            <span>{pill.label}</span>
            {pill.pending > 0 && (
              <span className="ml-0.5 px-1.5 py-px rounded-full bg-surface-700/80 text-2xs text-surface-200">
                {pill.pending}
              </span>
            )}
          </button>
        </Popover.Trigger>
        <Popover.Portal>
          <Popover.Content
            sideOffset={6}
            align="end"
            className="z-[9999] w-80 rounded-xl border border-surface-700/60 bg-surface-900 shadow-2xl p-3 text-sm text-surface-200 animate-scale-in"
          >
            <div className="flex items-center justify-between mb-2">
              <span className="font-semibold text-surface-100">Sync</span>
              <button
                onClick={() => void engine?.syncNow()}
                className="flex items-center gap-1 px-2 py-1 rounded-md text-xs text-accent-300 hover:text-accent-100 hover:bg-surface-800 transition-colors"
              >
                <RefreshCw className="w-3 h-3" />
                Retry now
              </button>
            </div>
            <div className="text-xs text-surface-400 mb-3">
              {status.lastSyncedAt
                ? `Last synced ${new Date(status.lastSyncedAt).toLocaleString()}`
                : 'Not synced yet'}
              {status.pendingOps > 0 && ` · ${status.pendingOps} pending`}
            </div>
            {status.issues.length === 0 ? (
              <div className="flex items-center gap-2 text-xs text-success-300">
                <Check className="w-3.5 h-3.5" />
                No issues.
              </div>
            ) : (
              <ul className="space-y-2 max-h-60 overflow-y-auto">
                {status.issues.map((iss: SyncIssue) => (
                  <li
                    key={iss.id}
                    className="rounded-lg border border-surface-800 bg-surface-950/60 p-2"
                  >
                    <div className="flex items-start gap-2">
                      <AlertTriangle className="w-3.5 h-3.5 mt-0.5 flex-shrink-0 text-warning-400" />
                      <div className="flex-1 min-w-0">
                        <p className="text-xs text-surface-300 truncate">{iss.reason}</p>
                        <p className="text-2xs text-surface-500 truncate">
                          {iss.op.method} {iss.op.path}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center justify-end gap-1 mt-1.5">
                      {iss.status === 'parked' && (
                        <button
                          onClick={() => iss.id != null && void engine?.retryIssue(iss.id)}
                          className="flex items-center gap-1 px-2 py-0.5 rounded-md text-2xs text-accent-300 hover:bg-surface-800 transition-colors"
                        >
                          <RefreshCw className="w-3 h-3" />
                          Retry
                        </button>
                      )}
                      <button
                        onClick={() => iss.id != null && void engine?.discardIssue(iss.id)}
                        className="flex items-center gap-1 px-2 py-0.5 rounded-md text-2xs text-danger-300 hover:bg-surface-800 transition-colors"
                      >
                        <Trash2 className="w-3 h-3" />
                        Discard
                      </button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
            <Popover.Close asChild>
              <button
                aria-label="Close"
                className="absolute top-2 right-2 p-1 rounded-md text-surface-500 hover:text-surface-200 hover:bg-surface-800 transition-colors"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </Popover.Close>
          </Popover.Content>
        </Popover.Portal>
      </Popover.Root>
    );
  }
  ```

- [ ] **Minimal impl (4/4a)** — mount the pill in `packages/app/src/App.tsx`. Add the import after line 31 (`import { OnlineErrorBanner } from './components/OnlineErrorBanner';`):

  Replace:

  ```tsx
  import { OnlineErrorBanner } from './components/OnlineErrorBanner';
  ```

  with:

  ```tsx
  import { OnlineErrorBanner } from './components/OnlineErrorBanner';
  import { SyncStatusPill } from './components/SyncStatusPill';
  ```

  Then mount it in the titlebar region. Replace lines 134–136:

  ```tsx
        {/* Title bar drag region */}
        <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
        <OnlineErrorBanner />
  ```

  with:

  ```tsx
        {/* Title bar drag region */}
        <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
        {/* Sync-status pill (§8), top-right of the titlebar chrome. */}
        <div className="fixed top-1.5 right-3 z-[60]">
          <SyncStatusPill />
        </div>
        <OnlineErrorBanner />
  ```

- [ ] **Minimal impl (4/4b)** — add the Sign-out surface to `packages/app/src/components/SettingsDialog.tsx`.

  Replace the import block (lines 1–5):

  ```tsx
  import React, { useState, useEffect } from 'react';
  import { useStore } from '../store';
  import { X, Save, Clock, Power } from 'lucide-react';
  import { clsx } from 'clsx';
  import { usePlatform } from '../AppRoot';
  ```

  with:

  ```tsx
  import React, { useState, useEffect } from 'react';
  import { useStore } from '../store';
  import { X, Save, Clock, Power, LogOut } from 'lucide-react';
  import { clsx } from 'clsx';
  import { usePlatform, useEngine } from '../AppRoot';
  import { getAuthStore } from '../auth';
  import { LocalStore, getLastUserId, setLastUserId } from '../data/local/LocalStore';
  import { shouldConfirmSignOut, signOutWarning } from './signOutConfirm';
  ```

  Add sign-out state + handler. Replace lines 24–29:

  ```tsx
    const [start, setStart] = useState(workStartHour);
    const [end, setEnd] = useState(workEndHour);
    const [increment, setIncrement] = useState(timeIncrement);
    const [autoLaunch, setAutoLaunch] = useState(false);

    const autoLaunchSupported = platform.capabilities.autoLaunch && !!platform.autoLaunch;
  ```

  with:

  ```tsx
    const [start, setStart] = useState(workStartHour);
    const [end, setEnd] = useState(workEndHour);
    const [increment, setIncrement] = useState(timeIncrement);
    const [autoLaunch, setAutoLaunch] = useState(false);
    const [confirmSignOut, setConfirmSignOut] = useState(false);

    const autoLaunchSupported = platform.capabilities.autoLaunch && !!platform.autoLaunch;
    // C5: useEngine() returns the typed SyncEngine surface; getStatus() is a synchronous
    // SyncStatus snapshot — read pendingOps directly, no `as any`/`?.()` cast.
    const engine = useEngine();

    // Wipe the local DB + clear the global pointer, THEN drive the store logout
    // (which stops the engine + closes the store via AppRoot.onLoggedOut). C10/§7.2.
    const doSignOut = async () => {
      const userId = getLastUserId();
      try {
        if (userId) await LocalStore.wipe(userId);
      } catch {
        /* best-effort — sign-out proceeds regardless */
      }
      setLastUserId(null);
      setConfirmSignOut(false);
      setSettingsOpen(false);
      await getAuthStore().getState().logout();
    };

    const onSignOutClick = () => {
      // C10: the Sign-out confirm reads engine.getStatus().pendingOps to decide whether
      // to warn. The only guard is a null engine (signed-out shell); the engine ALWAYS
      // provides getStatus() synchronously when present (C5).
      const pending = engine ? engine.getStatus().pendingOps : 0;
      if (shouldConfirmSignOut(pending)) {
        setConfirmSignOut(true);
      } else {
        void doSignOut();
      }
    };

    const pendingForWarning = engine ? engine.getStatus().pendingOps : 0;
  ```

  Add `setSettingsOpen` to the destructured store (it's already used elsewhere in the file at line 49). Replace lines 13–21:

  ```tsx
    const {
      settingsOpen,
      setSettingsOpen,
      workStartHour,
      workEndHour,
      timeIncrement,
      setWorkHours,
      setTimeIncrement,
    } = useStore();
  ```

  (unchanged — `setSettingsOpen` is already destructured; this confirms it. No edit needed here.)

  Add the Sign-out button to the Body, after the Time Increment block. Replace lines 184–186:

  ```tsx
            </div>
          </div>
          {/* Data export/import section intentionally removed in SP2 (online-only; spec §6/§7). */}
        </div>
  ```

  with:

  ```tsx
            </div>
          </div>

          {/* Account / Sign out (SP3 §7.2). */}
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
              Account
            </h3>
            <button
              onClick={onSignOutClick}
              className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium border border-danger-500/40 text-danger-300 hover:bg-danger-600/15 transition-colors"
            >
              <LogOut className="w-4 h-4" />
              Sign out
            </button>
          </div>
        </div>
  ```

  Finally, render the confirm modal when `confirmSignOut`. It must sit inside the outermost overlay so it renders within `SettingsDialog`'s tree. The file's tail is line 203 `</div>` (footer close), 204 `</div>` (inner card close), 205 `</div>` (outer overlay close), 206 `);`, 207 `}`. Replace lines 204–207 (inner-card close through end of component):

  ```tsx
      </div>
    </div>
  );
}
  ```

  with:

  ```tsx
      </div>

        {confirmSignOut && (
          <div
            className="fixed inset-0 z-[60] bg-black/70 backdrop-blur-sm flex items-center justify-center p-4"
            onClick={(e) => {
              if (e.target === e.currentTarget) setConfirmSignOut(false);
            }}
          >
            <div className="w-full max-w-sm bg-surface-900 border border-danger-500/40 rounded-2xl shadow-2xl p-6 animate-scale-in">
              <div className="flex items-center gap-2 mb-3">
                <LogOut className="w-5 h-5 text-danger-400" />
                <h3 className="text-base font-semibold text-surface-100">Sign out?</h3>
              </div>
              <p className="text-sm text-surface-300 mb-5">{signOutWarning(pendingForWarning)}</p>
              <div className="flex items-center justify-end gap-3">
                <button
                  onClick={() => setConfirmSignOut(false)}
                  className="px-4 py-2 text-sm text-surface-300 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                >
                  Cancel
                </button>
                <button
                  onClick={() => void doSignOut()}
                  className="px-4 py-2 text-sm font-medium rounded-lg bg-danger-600 hover:bg-danger-500 text-white transition-colors"
                >
                  Sign out &amp; erase
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }
  ```

  > The confirm modal reuses SettingsDialog's own fixed-overlay idiom (no new dependency — see the Contract-gap note). `engine.getStatus()` is the synchronous `SyncStatus` snapshot accessor the SyncEngine provides per C5 (the synchronous companion to `subscribe`, used here for a one-shot `pendingOps` read) — it is a pinned part of the engine surface, so no cast or optional call is needed and no rename is possible. The decision logic in `signOutConfirm.ts` (`shouldConfirmSignOut`/`signOutWarning`) is pure and unit-tested independently of the engine; its test asserts the warning shows when `pendingOps > 0` and is skipped when `0`.

- [ ] **Run, expect PASS** — from `packages/app`:

  ```
  npx vitest run src/sync/__tests__/useSyncStatus.test.ts src/components/__tests__/signOutConfirm.test.ts
  ```

  Expect PASS: the 6 `derivePillState` cases and the 3 `signOutConfirm` cases all green.

- [ ] **Commit**

  ```
  git add packages/app/src/sync/useSyncStatus.ts packages/app/src/sync/__tests__/useSyncStatus.test.ts packages/app/src/components/SyncStatusPill.tsx packages/app/src/components/signOutConfirm.ts packages/app/src/components/__tests__/signOutConfirm.test.ts packages/app/src/App.tsx packages/app/src/components/SettingsDialog.tsx
  git commit -m "$(cat <<'EOF'
feat(app): R5-6 — SyncStatusPill + popover + useSyncStatus + Sign-out surface (§8, C10)

derivePillState (pure, tested) projects a SyncStatus to the pill's visual state
with precedence syncing > warning(issues|!initialSync) > offline > ok; useSyncStatus
subscribes to engine.subscribe. SyncStatusPill (mounted in App's titlebar) shows
the state and a popover with last-synced time, pending count, the persisted issues
list (retry/discard) and "retry now". SettingsDialog gains a Sign out button:
shouldConfirmSignOut/signOutWarning (pure, tested) gate a confirm modal when
pendingOps > 0; confirmed sign-out wipes the local DB + clears tmap:lastUserId,
then authStore.logout (AppRoot.onLoggedOut stops the engine + closes the store).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

### Task R5-7: Phase green gate (HARD GATE)

**Files:** none created — this task only runs suites/builds and must not modify source.

This gate proves R5 left the full client suites green, both app shells compile with the new wiring (the only way the desktop main-process IPC change and the AppRoot/LocalStore/SyncEngine imports are validated, since those have no unit tests), and the backend is untouched.

- [ ] **Run the full client app suite** — from repo root:

  ```
  npm run test:app
  ```

  Expect PASS with no failures. New/changed files contribute: `refreshClient.test.ts` (8 tests), `authStore.test.ts` (original 11 + 3 new C8.3 = 14), `storeRefreshRollover.test.ts` (3), `createSyncWiring.test.ts` (7), `useSyncStatus.test.ts` (6), `signOutConfirm.test.ts` (3), plus all pre-existing app suites (`store.settingsSplit`, `mappers`, `ranking`, `lib/*`, `reminders/*`, `platform`, `projectName`) AND the R1–R4 suites (`LocalStore`, `LocalDataClient`, `SyncEngine`, `applyPull`, `localReports`, `fakeSyncServer`-driven engine tests). HARD GATE: every test passes; zero failures, zero unhandled rejections. (`HttpDataClient.test.ts` is still present and green here — it is deleted in R6, not R5.)

- [ ] **Run the web suite** — from repo root:

  ```
  npm test --workspace @tmap/web
  ```

  Expect PASS: `WebPlatform.test.ts` (5 tests — the SF-3 contract DesktopPlatform now mirrors) plus any other web suites. HARD GATE: zero failures.

- [ ] **Build both app shells** — from repo root:

  ```
  npm run build:apps
  ```

  Expect PASS for both `build:desktop` and `build:web`. This is the static gate for the untested surfaces:
  - desktop `tsc` validates `main.ts`/`preload.ts`/`electron-api.d.ts`/`DesktopPlatform.ts` all agree on the C8.2 discriminated `{ ok } | { ok:false, reason }` shape;
  - both `tsc` runs validate the rewired `AppRoot.tsx` against the R1–R4 module signatures (`LocalStore.open`, `new SyncEngine({store, transport})`, `new LocalDataClient(store, engine)`, `new HttpSyncTransport(refreshClient)`), `InitialSyncGate`, `SyncStatusPill`, `useSyncStatus`, and the new store actions (`refreshFromLocal`, `runRolloverOnce`).

  HARD GATE: both builds exit 0. If desktop `tsc` reports a shape mismatch on the IPC union, or either shell fails to resolve an R1–R4 import, the gate FAILS — fix before proceeding to R6.

- [ ] **Confirm the backend is untouched** — from `backend/` (Docker must be up):

  ```
  dotnet test
  ```

  Expect PASS at its prior count — R5 changes no backend file. HARD GATE: backend suite green; if Docker's daemon dropped, restart Docker Desktop and re-run (per C11). This step is a guard, not a change: a failure here means an unrelated regression, not R5 work.

- [ ] **Commit** (gate marker only — no source changes; skip if `git status` is clean):

  ```
  git commit --allow-empty -m "$(cat <<'EOF'
test(app): R5-7 — phase R5 green gate (client suites + both shells build + backend untouched)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
  ```

---

**Phase R5 complete.** Auth no longer destroys a session on a transient refresh failure (web throw + desktop discriminated IPC, C8.1/C8.2); `authStore.bootstrap` renders authed-offline from `meta.lastUser` and keeps the DB + pointer on session expiry (C8.3); the store gained `refreshFromLocal` (no-side-effect re-read) and `runRolloverOnce` (deferred/once-per-session), the series actions refresh-not-reload, and the local-now paths dropped their banner sites (C9); `AppRoot` drives the per-user `LocalStore` + `SyncEngine` + `LocalDataClient` through a unit-tested wiring factory with a first-render gate (C10); and the §8 pill + Sign-out surface are mounted. R6 next: two-device convergence suite, SQL.js + `HttpDataClient` deletion, and the final live gate.

## Phase R6 — Two-device convergence suite, dead-code deletion, final gates, acceptance mapping

This phase proves the marquee property of SP3 — two devices converge after interleaved offline edits — then deletes the two dead layers (the dormant SQL.js Electron services and the retired `HttpDataClient`), runs the full cross-suite green gate, executes the live manual gate against the real backend, and closes with the acceptance-criteria traceability table. Files this phase creates, modifies, deletes, and tests:

**Created (test only — this phase adds no production code):**
- `packages/app/src/sync/__tests__/convergence.test.ts` — 7 two-device convergence scenarios over the shared R3 `fakeSyncServer` harness

**Deleted:**
- `apps/desktop/electron/database.ts`, `apps/desktop/electron/taskService.ts`, `apps/desktop/electron/noteService.ts`, `apps/desktop/electron/projectService.ts`, `apps/desktop/electron/dailyPlanService.ts`, `apps/desktop/electron/focusSessionService.ts`, `apps/desktop/electron/reportService.ts`, `apps/desktop/electron/recurrenceUtils.ts`, `apps/desktop/electron/seed.ts`, `apps/desktop/electron/sql.js.d.ts` (the 10-file SQL.js layer per the skeleton file map)
- `packages/app/src/data/HttpDataClient.ts`, `packages/app/src/data/HttpDataClient.test.ts`

**Modified:**
- `apps/desktop/package.json` — drop `sql.js` dependency, drop the `sql-wasm.wasm` `build.extraResources` entry, drop the now-dangling `uuid`/`@types/uuid` (used only by the deleted services)
- `package-lock.json` — regenerated by `npm install` after the dependency drops
- `packages/app/src/store.ts`, `packages/app/src/data/DataClient.ts`, `apps/web/src/main.tsx`, `apps/desktop/src/main.tsx` — comment-only scrub of stale `HttpDataClient` mentions (whichever R2/R5 did not already rewrite)

**Verified unchanged:** `apps/desktop/tsconfig.electron.json` (its `include` is the glob `"electron/**/*"` — see `tsconfig.electron.json:18` — so no per-file entry dangles after the deletions); `packages/app/src/data/mappers.ts` + `ranking.ts` + their tests stay (consumed by `LocalDataClient` since R2).

> **Contract-gap note (fakeSyncServer surface).** The skeleton file map names `packages/app/src/sync/__tests__/fakeSyncServer.ts` as the shared R3 harness but does not pin its exported surface. R6-1 is written against this minimal assumed API, which R3's own push/pull tests already require: `new FakeSyncServer()` (one in-memory backend: per-table rows in sync-DTO wire shape + a global `changeSeq` counter), `server.transport(): SyncTransport` (C5 shape — replays POST/PATCH/PUT/DELETE ops with idempotent creates, 404 on missing/tombstoned PATCH/DELETE targets, whole-day `PUT /api/v1/daily-plans/{date}` with `committedAt` re-stamp, per-key `PUT /api/v1/settings` upsert, `POST /api/v1/recurrence` storing rule + template with zero instances; `pull(since, limit)` returns tombstone-inclusive rows merged by `changeSeq`; `ensureInstances(start, end)` materializes daily/weekly instances at rank `'a0'`), and `server.seed(table, row): void` (insert a wire-shaped row, stamping the next `changeSeq`). If R3's final harness differs in names only, adapt the import and call sites in `convergence.test.ts` mechanically — the scenario logic and assertions are authoritative and must not be weakened. If R3's harness is missing one of these behaviors, extend `fakeSyncServer.ts` (it is a shared test harness, not production code).

> **Contract-gap note (TDD cadence, C11).** This phase adds no production code, so the fail-first step is meaningless here: R6-1 is a verification suite over R1–R5's implementation (a failure is a regression to fix in the owning phase, never a reason to weaken a test), and R6-2/R6-3 are mechanical deletions. Per the C11 allowance for mechanical work, tasks below use Write-test→Run-expect-PASS or Do/Verify/Commit cadence.

> **Contract-gap note (task-id citations in R6-6).** Phases R0–R5 were authored in parallel with this one, so their per-task numbering was not fixed at authoring time. The acceptance table cites phase ids + the contract section + the artifact (file/test suite) that proves each criterion; for tests this phase owns, exact test names are cited. The executor should backfill exact `R<n>-<k>` ids when assembling the final plan if desired — the artifact citations are already unambiguous.

---

### Task R6-1: Two-device convergence suite

**Files:**
- Create (test): `packages/app/src/sync/__tests__/convergence.test.ts`

Two real devices = two `LocalStore` instances (unique Dexie DB name per device), each with its own `LocalDataClient` (C4) and `SyncEngine` (C5), sharing one `FakeSyncServer`. Engines are never `start()`ed — every cycle is driven manually with `engine.syncNow()` so the interleavings are exact and deterministic ("offline" is simply "no cycle has run yet"). The `SyncBridge` passed to each `LocalDataClient` uses a no-op `nudge` so no debounced background cycle can fire mid-test. Note the file matches the vitest include glob `src/**/*.test.ts` (`packages/app/vitest.config.ts:14`).

- [ ] **Write test** — create `packages/app/src/sync/__tests__/convergence.test.ts`:

```ts
// Two-device convergence: the SP3 marquee suite (spec §11, AC4/AC5).
// Each test interleaves offline edits on devices A and B of the same user and
// asserts both converge to the identical state after their cycles run.
import 'fake-indexeddb/auto';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { LocalStore } from '../../data/local/LocalStore';
import { LocalDataClient } from '../../data/local/LocalDataClient';
import { SyncEngine } from '../SyncEngine';
import { FakeSyncServer } from './fakeSyncServer';
import type { DataClient } from '../../data/DataClient';
import type { SyncIssue } from '../types';
import type { Task } from '../../types';

interface Device {
  store: LocalStore;
  client: DataClient;
  engine: SyncEngine;
  /** Run one full push→(ensure-instances)→pull cycle. Engines are never start()ed here. */
  sync: () => Promise<void>;
  issues: () => Promise<SyncIssue[]>;
}

const openStores: LocalStore[] = [];

function makeDevice(userId: string, server: FakeSyncServer): Device {
  // Unique Dexie DB per device — two devices of the same user must NOT share local state.
  const store = LocalStore.open(`${userId}-${Math.random().toString(36).slice(2, 10)}`);
  openStores.push(store);
  const engine = new SyncEngine({ store, transport: server.transport() });
  const client = new LocalDataClient(store, {
    nudge: () => {}, // cycles are driven manually via sync(); no debounced auto-push in tests
    online: () => true,
    ensureInstances: (start: string, end: string) => engine.ensureInstances(start, end),
  });
  return {
    store,
    client,
    engine,
    sync: () => engine.syncNow(),
    issues: () => store.table('issues').toArray() as Promise<SyncIssue[]>,
  };
}

/** Server sync-DTO wire shape (TaskSyncRow = TaskResponse minus subtasks, plus deletedAt).
 *  changeSeq is stamped by FakeSyncServer.seed. */
function serverTaskRow(id: string, rank: string): Record<string, unknown> {
  return {
    id, title: `task-${id.slice(0, 8)}`, notes: '', projectId: null, labels: [],
    source: 'manual', status: 'Inbox', plannedDate: null, scheduledStart: null,
    scheduledEnd: null, durationMinutes: 0, actualTimeMinutes: 0, priority: null,
    reminderMinutes: null, rank, dueDate: null, recurrenceRuleId: null,
    isRecurrenceTemplate: false, recurrenceDetached: false, recurrenceOriginalDate: null,
    completedAt: null, createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z',
    deletedAt: null,
  };
}

async function taskOn(d: Device, id: string): Promise<Task | undefined> {
  return (await d.client.tasks.getAll()).find((t) => t.id === id);
}

describe('two-device convergence (LocalStore + LocalDataClient + SyncEngine over one fake server)', () => {
  let server: FakeSyncServer;

  beforeEach(() => {
    server = new FakeSyncServer();
  });

  afterEach(() => {
    for (const s of openStores.splice(0)) s.close();
  });

  it('(a) different-field offline edits merge via diff-at-enqueue: A title + B plannedDate both survive on both', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    const created = await a.client.tasks.create({ title: 'Draft' });
    await a.sync(); // create lands on the server
    await b.sync(); // B pulls it

    // Interleaved offline edits — no cycle runs between the two writes.
    await a.client.tasks.update(created.id, { title: 'Final title' });
    await b.client.tasks.update(created.id, { plannedDate: '2026-06-12' });

    await a.sync(); // pushes ONLY { title } (diff-at-enqueue)
    await b.sync(); // pushes ONLY { plannedDate } — must not carry B's stale title — then pulls A's title
    await a.sync(); // A pulls B's plannedDate

    for (const d of [a, b]) {
      const t = await taskOn(d, created.id);
      expect(t).toBeDefined();
      expect(t!.title).toBe('Final title');       // A's edit survived B's push
      expect(t!.plannedDate).toBe('2026-06-12');  // B's edit survived A's push
    }
  });

  it('(b) same-field offline edits: later arrival at the server wins on both (arrival-LWW)', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    const created = await a.client.tasks.create({ title: 'Original' });
    await a.sync();
    await b.sync();

    await a.client.tasks.update(created.id, { title: 'from-A' });
    await b.client.tasks.update(created.id, { title: 'from-B' });

    await a.sync(); // 'from-A' arrives first
    await b.sync(); // 'from-B' arrives later → wins the field
    await a.sync(); // A pulls the winner

    expect((await taskOn(a, created.id))!.title).toBe('from-B');
    expect((await taskOn(b, created.id))!.title).toBe('from-B');
  });

  it('(c) edit-vs-delete: delete wins — 404 drops the edit, recovery pull removes the row on A, issue recorded', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    const created = await a.client.tasks.create({ title: 'Doomed' });
    await a.sync();
    await b.sync();

    await a.client.tasks.update(created.id, { title: 'edited while B deletes' }); // A offline edit
    await b.client.tasks.delete(created.id); // B offline delete

    await b.sync(); // server tombstones the task
    await a.sync(); // A's PATCH → 404 → drop op + record issue + recovery pull from since=0 removes the row

    expect(await taskOn(a, created.id)).toBeUndefined();
    expect(await taskOn(b, created.id)).toBeUndefined();

    const issues = await a.issues();
    expect(issues).toHaveLength(1);
    expect(issues[0].status).toBe('dropped');
    expect(issues[0].reason).toContain('404');
    expect(issues[0].op.method).toBe('PATCH');
    expect(await b.issues()).toEqual([]); // B's delete replayed cleanly
  });

  it('(d) daily plans: whole-day LWW — the later-arriving PUT replaces the day on both (no merge)', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    const t1 = await a.client.tasks.create({ title: 'plan-A task' });
    const t2 = await a.client.tasks.create({ title: 'plan-B task' });
    await a.sync();
    await b.sync();

    await a.client.dailyPlans.upsert({ date: '2026-06-11', plannedTaskIds: [t1.id], plannedMinutes: 60 });
    await b.client.dailyPlans.upsert({ date: '2026-06-11', plannedTaskIds: [t2.id], plannedMinutes: 30 });

    await a.sync(); // A's day arrives first
    await b.sync(); // B's day arrives later → whole-day replace wins
    await a.sync(); // A pulls the winner

    for (const d of [a, b]) {
      const plan = await d.client.dailyPlans.get('2026-06-11');
      expect(plan).not.toBeNull();
      expect(plan!.plannedTaskIds).toEqual([t2.id]); // NOT merged with [t1.id]
      expect(plan!.plannedMinutes).toBe(30);
    }
  });

  it('(e) settings: per-key LWW — A changes workStartHour, B changes workEndHour, both survive on both', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    // Baseline rows on the server and on both devices.
    await a.client.settings.save({ workStartHour: 8, workEndHour: 20, timeIncrement: 15 });
    await a.sync();
    await b.sync();

    // Whole-map saves (the store's real call shape) — diff-at-enqueue must reduce each
    // to a single-key PUT, or one device's save would clobber the other's key.
    await a.client.settings.save({ workStartHour: 6, workEndHour: 20, timeIncrement: 15 });
    await b.client.settings.save({ workStartHour: 8, workEndHour: 22, timeIncrement: 15 });

    await a.sync();
    await b.sync();
    await a.sync();

    for (const d of [a, b]) {
      const { settings } = await d.client.settings.get();
      expect(settings.workStartHour).toBe(6);  // A's key survived B's save
      expect(settings.workEndHour).toBe(22);   // B's key survived A's save
      expect(settings.timeIncrement).toBe(15); // untouched key unchanged
    }
  });

  it("(f) interleaved offline reorders seeded with tied 'a0' ranks converge to an identical (rank, id) order", async () => {
    const T1 = '11111111-1111-4111-8111-111111111111';
    const T2 = '22222222-2222-4222-8222-222222222222';
    const T3 = '33333333-3333-4333-8333-333333333333';
    // The real degenerate population: every recurrence instance copies rank 'a0' (spec §5.1).
    server.seed('tasks', serverTaskRow(T1, 'a0'));
    server.seed('tasks', serverTaskRow(T2, 'a0'));
    server.seed('tasks', serverTaskRow(T3, 'a0'));

    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);
    await a.sync();
    await b.sync();

    // Sanity: the tied seed renders in the same deterministic (rank, id) order on both.
    const before = (await a.client.tasks.getAll()).map((t) => t.id);
    expect(before).toEqual([T1, T2, T3]); // equal ranks → ordinal id tie-break
    expect((await b.client.tasks.getAll()).map((t) => t.id)).toEqual(before);

    // Interleaved offline reorders. Tie-aware insertion (§5.1.2) must re-rank the tied
    // run instead of producing a rank that sorts after both 'a0' neighbors.
    await a.client.tasks.reorder([
      { id: T2, order: 0 },
      { id: T1, order: 1 },
      { id: T3, order: 2 },
    ]);
    await b.client.tasks.reorder([
      { id: T3, order: 0 },
      { id: T2, order: 1 },
      { id: T1, order: 2 },
    ]);

    await a.sync();
    await b.sync();
    await a.sync();

    const orderA = (await a.client.tasks.getAll()).map((t) => t.id);
    const orderB = (await b.client.tasks.getAll()).map((t) => t.id);
    expect(orderA).toEqual(orderB);                          // identical (rank, id) order everywhere
    expect([...orderA].sort()).toEqual([T1, T2, T3]);        // same three rows, no dupes/losses
  });

  it('(g) a series created offline gains server-generated instances on both devices after sync (regenAfterPush)', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);
    await a.sync();
    await b.sync();

    // Created OFFLINE: returns the full local task list (store contract, spec §6) —
    // template present, zero instances until reconnect.
    const listAfterCreate = await a.client.recurrence.create(
      { title: 'Standup', durationMinutes: 15 },
      { frequency: 'daily', interval: 1, daysOfWeek: [], endType: 'never' },
    );
    const template = listAfterCreate.find((t) => t.isRecurrenceTemplate);
    expect(template).toBeDefined();
    expect(template!.recurrenceRuleId).not.toBeNull(); // client rule id (C7.4)
    expect(
      listAfterCreate.filter((t) => t.recurrenceRuleId !== null && !t.isRecurrenceTemplate),
    ).toHaveLength(0);

    await a.sync(); // push POST /recurrence (op carries regenAfterPush) → ensure-instances → pull delivers instances
    await b.sync(); // pull-only: same instances arrive on B

    const instancesOf = (tasks: Task[]) =>
      tasks.filter((t) => t.recurrenceRuleId === template!.recurrenceRuleId && !t.isRecurrenceTemplate);

    const allA = await a.client.tasks.getAll();
    const allB = await b.client.tasks.getAll();
    const instA = instancesOf(allA);
    const instB = instancesOf(allB);

    expect(instA.length).toBeGreaterThanOrEqual(7); // ~14-day horizon, daily rule
    expect(instA.map((t) => t.id).sort()).toEqual(instB.map((t) => t.id).sort());
    expect(allA.filter((t) => t.isRecurrenceTemplate)).toHaveLength(1); // adopt nothing — no ghost/duplicate template
    expect(allB.filter((t) => t.isRecurrenceTemplate)).toHaveLength(1);
    expect(await a.issues()).toEqual([]); // no drops, no adopt-existing rewrites needed
  });
});
```

- [ ] **Run, expect PASS** — from `packages/app`:

```
npx vitest run src/sync/__tests__/convergence.test.ts
```

Expected: `Test Files  1 passed (1)` · `Tests  7 passed (7)`.

This suite verifies R1–R5; a failure here is a regression, never a test to weaken. Triage by scenario: (a)/(e) failing → R2 diff-at-enqueue (`LocalDataClient` update/settings diffing); (b)/(d) failing → R4 `applyPull` upsert-by-key or the fake server's arrival ordering; (c) failing → R3 404-drop + issue recording or R4 rejection-recovery pull; (f) failing → R2 tie-aware insertion / `(rank, id)` read ordering; (g) failing → R3 `regenAfterPush` post-push trigger or R4 pull application of instances.

- [ ] **Commit:**

```
git add "packages/app/src/sync/__tests__/convergence.test.ts"
git commit -m "test(sync): two-device convergence suite — field merge, arrival-LWW, edit-vs-delete recovery, plan/settings LWW, tied-rank reorder, offline series

Seven scenarios over two real LocalStore+LocalDataClient+SyncEngine devices
sharing one fake server, proving spec AC4 and AC5 end to end.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task R6-2: Delete the dormant SQL.js layer (Do/Verify/Commit)

**Files:**
- Delete: `apps/desktop/electron/database.ts` · `apps/desktop/electron/taskService.ts` · `apps/desktop/electron/noteService.ts` · `apps/desktop/electron/projectService.ts` · `apps/desktop/electron/dailyPlanService.ts` · `apps/desktop/electron/focusSessionService.ts` · `apps/desktop/electron/reportService.ts` · `apps/desktop/electron/recurrenceUtils.ts` · `apps/desktop/electron/seed.ts` · `apps/desktop/electron/sql.js.d.ts`
- Modify: `apps/desktop/package.json`, `package-lock.json`

SP2's Q5 already unwired `main.ts`/`preload.ts` from these services — a pre-deletion grep (verified against current `main`) shows the only references to `sql.js`, `sql-wasm`, the service classes, or `uuid` inside `apps/desktop` live in the 10 doomed files themselves plus `apps/desktop/package.json:23` (`"sql.js": "^1.12.0"`) and `apps/desktop/package.json:69-74` (the `extraResources` wasm entry). `tsconfig.electron.json:18` includes by glob (`"include": ["electron/**/*"]`), so no include entry dangles. `apps/desktop/vite.config.ts` references only `electron/main.ts` and `electron/preload.ts` (lines 12, 29) — both kept.

- [ ] **Do** — from the repo root, delete the 10 files:

```
git rm apps/desktop/electron/database.ts apps/desktop/electron/taskService.ts apps/desktop/electron/noteService.ts apps/desktop/electron/projectService.ts apps/desktop/electron/dailyPlanService.ts apps/desktop/electron/focusSessionService.ts apps/desktop/electron/reportService.ts apps/desktop/electron/recurrenceUtils.ts apps/desktop/electron/seed.ts apps/desktop/electron/sql.js.d.ts
```

- [ ] **Do** — edit `apps/desktop/package.json`. Two exact edits.

Edit 1 — dependencies: remove `sql.js` and the now-dangling `uuid` (its only importers were `taskService.ts`, `projectService.ts`, `noteService.ts`, `focusSessionService.ts` — all just deleted), plus `@types/uuid` from devDependencies. Current block (lines 17–25):

```json
  "dependencies": {
    "@tmap/api-client": "*",
    "@tmap/app": "*",
    "electron-updater": "^6.3.9",
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "sql.js": "^1.12.0",
    "uuid": "^11.1.0"
  },
```

New:

```json
  "dependencies": {
    "@tmap/api-client": "*",
    "@tmap/app": "*",
    "electron-updater": "^6.3.9",
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
```

And in `devDependencies`, delete the single line:

```json
    "@types/uuid": "^10.0.0",
```

Edit 2 — `build` section: remove the whole `extraResources` entry (the installer must no longer ship `sql-wasm.wasm` — AC11). Current (lines 65–74):

```json
    "files": [
      "dist/**/*",
      "dist-electron/**/*"
    ],
    "extraResources": [
      {
        "from": "../../node_modules/sql.js/dist/sql-wasm.wasm",
        "to": "sql-wasm.wasm"
      }
    ]
```

New:

```json
    "files": [
      "dist/**/*",
      "dist-electron/**/*"
    ]
```

- [ ] **Do** — refresh the lockfile and clear stale compiled output (PowerShell, repo root):

```
npm install
if (Test-Path apps/desktop/dist-electron) { Remove-Item -Recurse -Force apps/desktop/dist-electron }
if (Test-Path apps/desktop/tsconfig.electron.tsbuildinfo) { Remove-Item -Force apps/desktop/tsconfig.electron.tsbuildinfo }
```

(`dist-electron/` is gitignored build output; clearing it proves the next build compiles cleanly from only the surviving sources and leaves no stale `taskService.js` etc. for electron-builder to package.)

- [ ] **Verify** — all three checks, from the repo root:

```
git grep -nE "sql\.js|sql-wasm" -- apps/desktop
git grep -nE "\b(taskService|noteService|projectService|dailyPlanService|focusSessionService|reportService|recurrenceUtils|seedDemoData|database)\b" -- apps/desktop
npm run build:desktop
```

Expected: both greps print **nothing** (exit code 1 — no matches in tracked files); `npm run build:desktop` exits 0 (its final step `tsc -p tsconfig.electron.json` now compiles only `config.ts`, `main.ts`, `preload.ts`).

- [ ] **Commit:**

```
git add apps/desktop/package.json package-lock.json
git commit -m "chore(desktop): delete dormant SQL.js layer (10 electron files) + drop sql.js/uuid deps and wasm extraResources

The renderer has gone through the DataClient seam since SP2; SP3's local
store is Dexie in packages/app (spec §10). The installer no longer ships
sql-wasm.wasm (AC11).

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

(The 10 deletions are already staged by `git rm`.)

---

### Task R6-3: Retire `HttpDataClient` + its tests; scrub stale comment references (Do/Verify/Commit)

**Files:**
- Delete: `packages/app/src/data/HttpDataClient.ts`, `packages/app/src/data/HttpDataClient.test.ts` (5 tests)
- Modify (comments only): `packages/app/src/store.ts`, `packages/app/src/data/DataClient.ts`, `apps/web/src/main.tsx`, `apps/desktop/src/main.tsx`

R5 rewired `AppRoot.tsx` to build `LocalDataClient` + `SyncEngine` (C10), so the import at `AppRoot.tsx:5` and construction at `AppRoot.tsx:75` are already gone. What can remain are comment mentions at `store.ts:81`, `store.ts:103`, `store.ts:666`, `DataClient.ts:29-32`, `apps/web/src/main.tsx:19-20`, and `apps/desktop/src/main.tsx:17-18` (line numbers as of current `main`; R2/R5 may have already rewritten some — the Verify grep below is the authority: apply each edit only where the old text still exists, and the grep must end empty either way). `mappers.ts` and `ranking.ts` are NOT deleted — they are `LocalDataClient`'s mapping/ranking layer since R2.

- [ ] **Do** — delete the class and its tests:

```
git rm packages/app/src/data/HttpDataClient.ts packages/app/src/data/HttpDataClient.test.ts
```

- [ ] **Do** — comment scrub, exact old → new (skip any site R2/R5 already rewrote):

`packages/app/src/store.ts` line 81 —

```
// The host (AppRoot, built per app entry) injects the concrete HttpDataClient
```
→
```
// The host (AppRoot, built per app entry) injects the concrete LocalDataClient
```

`packages/app/src/store.ts` line 103 —

```
 * rule). Data IPC is gone — these reach the same injected HttpDataClient the store
```
→
```
 * rule). Data IPC is gone — these reach the same injected DataClient the store
```

`packages/app/src/store.ts` line 666 —

```
      // HttpDataClient returns the full task list (targeted reload) after create.
```
→
```
      // The DataClient returns the full task list after create (recurrence contract, spec §6).
```

`packages/app/src/data/DataClient.ts` lines 29–33 —

```
/**
 * The data seam the Zustand store talks to. SP2 has exactly one implementation
 * (HttpDataClient over @tmap/api-client). SP3 will wrap it in a CachingDataClient
 * without changing the store. Only methods the store actually calls are present.
 */
```
→
```
/**
 * The data seam the Zustand store talks to. SP3 has exactly one implementation
 * (LocalDataClient over Dexie/IndexedDB); a background SyncEngine replays queued
 * ops to the REST API. Only methods the store actually calls are present.
 */
```

`apps/web/src/main.tsx` lines 19–20 —

```
// the in-memory authStore. AppRoot wraps this once with the 401→refresh layer and
// builds the HttpDataClient over that wrapped client — one shared refresh path.
```
→
```
// the in-memory authStore. AppRoot wraps this once with the 401→refresh layer; the
// SyncEngine pushes/pulls over that wrapped client — one shared refresh path.
```

`apps/desktop/src/main.tsx` lines 17–18 —

```
// AppRoot wraps this once with the 401→refresh layer and builds the HttpDataClient
// over that wrapped client — one shared refresh path for all data calls.
```
→
```
// AppRoot wraps this once with the 401→refresh layer; the SyncEngine pushes/pulls
// over that wrapped client — one shared refresh path for all replayed ops.
```

- [ ] **Verify** — from the repo root:

```
git grep -n "HttpDataClient" -- packages apps
git grep -nE "from '\.\./(mappers|ranking)'" -- packages/app/src/data/local
```

Expected: the first grep prints **nothing** (exit 1) — zero references outside `docs/` history (`docs/superpowers/**` keeps its historical mentions; gitignored `packages/app/dist-types/` build output is regenerated on next build and is invisible to `git grep`). The second prints at least one `LocalDataClient.ts` import each for `../mappers` and `../ranking`, proving the survivors are consumed.

Then from `packages/app`:

```
npx tsc --noEmit
npx vitest run
```

Expected: typecheck clean; the suite total drops by exactly 5 relative to the R6-1 run (the deleted `HttpDataClient.test.ts` tests — 14 pre-SP3 test files become 13, `mappers.test.ts` 28 and `ranking.test.ts` 9 still green), 0 failures.

- [ ] **Commit:**

```
git add packages/app/src/store.ts packages/app/src/data/DataClient.ts apps/web/src/main.tsx apps/desktop/src/main.tsx
git commit -m "chore(app): retire HttpDataClient and its tests; LocalDataClient is the sole DataClient

Its reorder/create logic depended on an in-memory id->rank cache populated by
its own reads, and in SP3 it performs no reads (spec locked decision 5).
mappers.ts and ranking.ts live on under LocalDataClient. Stale comments scrubbed.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

(The two deletions are already staged by `git rm`.)

---

### Task R6-4: Full green gate — every suite, every build (HARD GATE)

**Files:** none (verification only).

- [ ] **Run backend suite** — from `backend/` (Docker Desktop must be running; restart it if the daemon dropped):

```
dotnet test
```

Expected: **all tests pass, 0 failed, 0 skipped.** Total = the count recorded at R0's green gate — the 157 pre-SP3 baseline (152 `[Fact]` + 1 `[Theory]` × 5 `[InlineData]`, verified on current `main`) plus every R0 addition (`SyncTests.cs` + the idempotent-create/409/client-id/ordering touch-up tests). Any number below the R0-gate count is a regression.

- [ ] **Run client app suite** — from the repo root:

```
npm run test:app
```

Expected: **all tests pass, 0 failed.** Total = the count recorded at R5's green gate **+ 7** (R6-1 convergence) **− 5** (R6-3's deleted `HttpDataClient.test.ts`). Since the SP2 baseline was 113 and R1–R5 added every LocalStore/LocalDataClient/SyncEngine/applyPull/auth suite, the total is **strictly greater than 113** (AC11: existing suites green minus deliberately deleted files). The convergence file `src/sync/__tests__/convergence.test.ts` must appear in the run with 7 passed.

- [ ] **Run web suite** — from the repo root:

```
npm test --workspace @tmap/web
```

Expected: **all tests pass, 0 failed** — at least the 6 `WebPlatform.test.ts` tests on current `main` plus any R5 additions.

- [ ] **Run both builds** — from the repo root:

```
npm run build:apps
```

Expected: exit 0 for both `build:desktop` (which typechecks `packages/app`, the desktop renderer, bundles, and compiles `tsconfig.electron.json` — now only `config.ts`/`main.ts`/`preload.ts`) and `build:web`.

- [ ] **Installer spot-check (AC11, once)** — from `apps/desktop`:

```
npm run package
```

Then verify (PowerShell): `Test-Path apps/desktop/out/win-unpacked/resources/sql-wasm.wasm` → **False**. The packaged app no longer ships SQL.js/wasm.

**HARD GATE:** Do **not** proceed to R6-5, mark SP3 complete, merge, or push until every command above is green exactly as stated. A failure anywhere is fixed in the owning phase's terms (see the R6-1 triage table for convergence failures) and then the **full set of commands in this task is re-run from the top** — partial re-runs do not satisfy the gate.

---

### Task R6-5: Live manual gate (no code) — desktop + web against the real backend

**Files:** none. This is an operator checklist (SP2-style end-of-project verification). Record the outcome of each step (pass/fail + notes) in the PR description.

**Environment setup (in order):**

1. Repo root: `docker compose up -d postgres` (Postgres 16 on `localhost:5432`).
2. `backend/`: `dotnet run --project src/Tmap.Api` — wait for `curl http://localhost:5000/health` → `{"status":"ok"}`.
3. `apps/desktop`: `npm run dev` (Electron shell).
4. `apps/web`: `npm run dev` → open `http://localhost:5174` in a browser.
5. Register/sign in as the same account on both clients; create a few tasks, a project, and a daily plan while online; let the pill settle to its synced state.

**Checklist (each step cites the spec acceptance criteria it proves):**

1. **Desktop cold start with backend stopped** *(AC1, AC2-pill)* — quit the desktop app fully (tray → exit). Stop the backend process (leave Postgres up or down — irrelevant). Relaunch the desktop app. Expected: last-authed user's data renders from the local store with no logout and no keychain wipe; create + edit + reorder + plan tasks; the pill shows offline (`cloud-off`) with a growing pending count.
2. **Backend returns → auto-drain** *(AC3)* — restart `dotnet run --project src/Tmap.Api`. Within the periodic-cycle window (≤ 60 s, or click "retry now" in the pill popover) the queue drains FIFO, the pill clears to its synced state, and the edits from step 1 are visible in the web tab after its next cycle. Confirm no rollover ran before the first cycle settled (no task got re-dated mid-drain).
3. **Web tab survives mid-session network loss** *(AC2, AC3)* — in the open web tab, DevTools → Network → "Offline". Keep working: edit titles, complete tasks, reorder. Expected: every action applies instantly, pill shows offline + pending. Restore the network. Expected: drain + pull; the web tab and desktop converge to identical lists.
4. **Two-client interleaved edits converge** *(AC4)* — go offline on both (stop backend). On desktop edit task X's title; in web move task X to tomorrow; reorder a shared list differently on each. Restart the backend, let both drain. Expected: X has the new title AND the new date on both; both clients show the identical task order; the daily plan reflects whichever client pushed last (whole-day LWW).
5. **Duplicate project name offline on both → adopt-existing** *(AC7, AC10)* — with the backend stopped, create a project named `Inbox-Zero` on desktop AND a project named `Inbox-Zero` in web. Restart the backend. Expected: the first arrival creates it; the second client's create gets the 409-with-existing-id and adopts — both clients end with exactly ONE `Inbox-Zero` project, any tasks filed under the loser's ghost project now reference the survivor, the queue is NOT wedged (subsequent edits still sync), and the pill popover shows one informational issue on the adopting client.
6. **Explicit sign-out with pending ops** *(AC9)* — go offline, make one edit (pending ≥ 1), then Settings → Sign out. Expected: a confirmation dialog warns that pending changes will be discarded; cancelling keeps the session; confirming wipes the local DB + queue + `tmap:lastUserId` and lands on the login screen. Re-login online shows only server truth (the discarded edit is gone — that is the documented semantics of confirming).
7. **Desktop cold start fully offline after prior login** *(AC1, AC8)* — sign in on desktop online, quit, stop the backend AND disable the OS network adapter. Launch the desktop app. Expected: authed-offline render of the full dataset (the `initialSyncComplete` gate passed in a prior session, so first render is instant and local); no session destruction; re-enabling the network later drains and refreshes without a restart.

**Gate:** all 7 steps must pass as described. Any deviation is triaged against the owning phase (auth behavior → R5/C8; queue behavior → R3; pull/gate behavior → R4; adopt-existing → R0+R3) and the failing step re-run after the fix, followed by re-running R6-4.

---

### Task R6-6: Acceptance-criteria traceability table (documentation, no code)

**Files:** none — this table is part of the plan itself; copy it into the SP3 completion notes / PR description when closing the project.

Verify every row's artifact exists and is green (they all are if R6-4 and R6-5 passed), then record the table:

| AC | Criterion (spec §Acceptance) | Proven by |
|----|------------------------------|-----------|
| AC1 | Desktop cold-starts offline with last-authed data; no session destruction; full CRUD queues | R5 (C8.2 desktop IPC `unauthorized`/`transient` discrimination + `DesktopPlatform` NetworkError re-throw tests; C8.3 `authStore.bootstrap` authed-offline tests over `getLastUserId()` + `meta.lastUser`) · live gate R6-5 steps 1 & 7 |
| AC2 | Open web tab survives network loss incl. transient refresh failure; pill shows offline + pending | R5 (C8.1 `refreshClient` transient-rethrow-without-logout tests) · R3 (push network-error abort, queue-intact test) · R5 (`SyncStatusPill` state rendering) · live gate R6-5 step 3 |
| AC3 | Reconnect drains FIFO, pulls deltas, refreshes via `refreshFromLocal()` with no side effects; cursor persists; rollover deferred until first cycle settles | R3 (FIFO replay-ordering + trigger-set tests; `onFirstCycleSettled`) · R4 (cursor persistence across engine restarts; `applyPull` tests) · R5 (C9.1/C9.2 `refreshFromLocal` no-rollover/no-ensure tests; C9.3 `runRolloverOnce` deferral tests) · live gate R6-5 step 2 |
| AC4 | Two devices converge: different-field merge, same-field arrival-LWW, edit-vs-delete, whole-day plan LWW, per-key settings LWW, reorder incl. tied ranks | R6-1 tests (a) `different-field offline edits merge via diff-at-enqueue…`, (b) `same-field offline edits: later arrival…wins`, (c) `edit-vs-delete: delete wins…`, (d) `daily plans: whole-day LWW…`, (e) `settings: per-key LWW…`, (f) `interleaved offline reorders seeded with tied 'a0' ranks…` · R2 (diff-at-enqueue + explicit-null + tie-repair unit tests) · live gate R6-5 step 4 |
| AC5 | Series created offline gains server-generated instances on reconnect (§4.3); instances editable/completable/deletable offline | R6-1 test (g) `a series created offline gains server-generated instances on both devices after sync` · R3 (`regenAfterPush` post-push ensure-instances engine test) · R2 (offline series-op local approximations + instance CRUD via `LocalDataClient`) |
| AC6 | `GET /api/v1/sync`: tombstones, RLS tenant isolation, `change_seq` pagination, snapshot pages, overlap tolerance | R0 `backend/tests/Tmap.Api.Tests/SyncTests.cs` (tombstones-included, user-A-never-sees-user-B under real RLS, page chaining + `hasMore`, snapshot-consistent pages, overlap re-read) · R4 (client overlap `since = max(0, cursor − 5000)` test) |
| AC7 | Replayed creates with client ids never duplicate (incl. focus sessions); PK conflict → existing row; duplicate-name project → adopt-existing, no wedge | R0 (C7.1 idempotent-create tests, C7.2 409-with-`existingId` test, C7.3 focus-session client-id test, C7.4 recurrence client-rule-id test) · R3 (adopt-existing local id-rewrite-across-rows-and-queue test) · live gate R6-5 step 5 |
| AC8 | Fresh device populates via paged full sync; `initialSyncComplete` gates first render; interrupted initial sync resumes; later launches render locally instantly | R4 (`initialSyncComplete` set only at `hasMore=false`; resume-from-cursor-after-interrupt test) · R5 (C10 `InitialSyncGate` render-gate + partial-failure-renders-with-warning wiring) · live gate R6-5 step 7 |
| AC9 | Explicit logout wipes (confirming when pending ops exist); session expiry keeps data + queue; same-user re-login pushes the queue | R5 (C10 Sign-out surface + `pendingOps > 0` confirm dialog + `LocalStore.wipe` + `setLastUserId(null)` tests; C8.3 true-401 keeps DB + queue) · live gate R6-5 step 6 |
| AC10 | No failure mode wedges sync: definitive rejections drop + recover; poison ops park with discard; pull runs when push halts | R3 (full §3.3 failure-taxonomy tests: 400/404 drop + issue, rejection recovery incl. ghost-row cleanup, `PARK_THRESHOLD` parking + retry/discard, pull-runs-when-push-aborts) · R6-1 test (c) (recovery proven cross-device) · live gate R6-5 step 5 |
| AC11 | Existing suites green at current counts minus deliberately deleted files; both apps build; installer ships no SQL.js/wasm | R6-2 (deletion + greps + `build:desktop`) · R6-3 (deletion + greps + count-drop-by-5 verification) · R6-4 (all four suites/builds + `sql-wasm.wasm` absent from the packaged app) |

- [ ] **Verify** — every artifact cited above exists in the merged branch and was green in the R6-4 run; the two live-gate steps cited per row were checked off in R6-5.

This is the final task of SP3. With R6-4 and R6-5 green and this table recorded, the phase — and the sub-project — is complete; proceed to the merge/PR ritual per the repo's finishing conventions (mind the concurrent-sessions worktree hazard noted in the plan header).
