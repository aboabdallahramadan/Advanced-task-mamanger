# SP4 — Native Android App — Design

**Date:** 2026-06-18
**Status:** Approved (design)
**Part of:** `2026-06-01-online-multiplatform-roadmap.md` (the 5th and final sub-project)

> Produced through the brainstorming flow: memory + codebase exploration (feature surface
> of `packages/app/src/components`, the live `/api/v1` contract in `packages/api-client/openapi.json`,
> and the SP3 sync engine in `packages/app/src/data`), four scope/architecture decisions locked
> with the user, three judgment calls confirmed, then the full design approved. Builds on the
> EXISTING, LIVE .NET backend (`https://api-tasks.qmindtech.net`) — **no backend changes are
> planned**. The app is a brand-new native Kotlin codebase; the TS/React clients are a UX +
> protocol reference only, not shared code.

## Goal

A native Android app — Kotlin + Jetpack Compose — that lets a signed-in user run their day
from their phone: capture to the inbox, see today's plan, edit and complete tasks, manage
projects, and receive local reminders. It is **offline-first**: the local Room store is the
source of truth, all reads and writes hit it instantly, and a background engine reconciles
with the server over the existing `/api/v1/sync` change-feed — converging with the desktop and
web clients exactly as they converge with each other. It speaks the same protocol the SP3
engine speaks; it does not reuse SP3's TypeScript code.

## Locked decisions (brainstorming)

1. **v1 scope = "Daily-driver companion."** Auth, Today, Inbox quick-capture, Backlog,
   All-Tasks (search/filter/sort/group), the full task editor (incl. subtasks), Projects CRUD,
   and local reminders — all offline-first. The planning ritual, focus/pomodoro, notes,
   reports, and recurrence *authoring* are out of v1 (see §1). Recurring tasks still appear
   (the server materializes instances; they are ordinary task rows).
2. **Offline = full SP3 mirror.** The complete bidirectional engine (FIFO op-replay push with
   idempotent-by-id / 409-adopt / 5xx-park / rejection-recovery, overlap-cursor delta pull,
   full-resync directive, tombstone-aware), not a read-cache or a staged subset. Room is the
   UI's source of truth.
3. **Reminders = full local notifications.** `reminderMinutes`/`dueDate` fire as local
   notifications even when offline or the app is closed, with exact alarms, the notification +
   exact-alarm permission flows, a boot-reschedule receiver, and re-arming on every sync delta
   or local edit.
4. **API layer = hand-written Retrofit.** Retrofit2 + OkHttp + kotlinx.serialization, with a
   Bearer `Interceptor` and a mutex-serialized 401 `Authenticator` for the body-based,
   rotation/reuse-detecting refresh. Not generated from OpenAPI; not Ktor.

Three judgment calls the user confirmed:

- **Room v1 models only the entities v1 uses** (`tasks`, `subtasks`, `projects`, `settings`) —
  notes/focus/daily-plans/recurrence-rules are skipped on pull now and backfilled by a
  schema-version-triggered full-resync when a later version adds them (§3.3).
- **Stack defaults accepted** — Hilt, Navigation Compose, MVVM + unidirectional data flow,
  `minSdk 26` (§2).
- **Local data is KEPT on definitive logout** (re-login on the same account restores instantly;
  a full-resync reconciles) — not wiped (§5.3).

## Pinned constants (mirrors SP3 for protocol fidelity)

| Constant | Value | Where used |
|---|---|---|
| Pull page size (`limit`) | 500 | §4.2 |
| Cursor overlap per cycle | 5000 seqs | §4.2 |
| Post-write push debounce | 2 s | §4.4 |
| Periodic sync interval | ~15 min (WorkManager floor) + on-event expedited | §4.4 |
| 5xx retries per cycle | 3 (backoff 1 s / 2 s / 4 s) | §4.1 |
| Poison-op park threshold | 10 total attempts across cycles | §4.1 |
| Min / target / compile SDK | 26 / 35 / 35 | §2 |
| Task priority range | 1–4 | §3.1 |
| Note content cap (if/when notes land) | 100 KB | n/a v1 |

> Note on the periodic interval: WorkManager's minimum periodic period is 15 minutes, so the
> "60 s online poll" SP3 used on desktop becomes **event-driven** on Android (expedited one-shot
> on connectivity regain + debounced after each local write), with the 15-min periodic worker as
> a safety net. This is the platform-correct equivalent, not a behavior change.

## 1. Scope

**In v1:**

| Area | What ships |
|---|---|
| Auth | Login / register; body-based JWT; secure refresh; authed-offline cold start |
| Today | The day's scheduled + planned tasks, time-ordered (read-focused timeline; no drag timebox) |
| Inbox | Quick-capture (title-only → `status=inbox`); the primary mobile flow |
| Backlog | Unplanned tasks list |
| All Tasks | Search + filter (status / priority / project / date range) + group + sort |
| Task editor | Full field set: title, notes, project, labels, priority, status, plannedDate, scheduledStart/End, durationMinutes, actualTimeMinutes, reminderMinutes, dueDate, subtasks (inline CRUD), mark-done, soft-delete |
| Projects | List + create / edit / delete + reorder + filter-by-project |
| Reminders | Local notifications for `reminderMinutes` / `dueDate` |
| Settings | Timezone, work hours, notifications toggle |
| Sync | Full offline-first engine (§4); sync-status surface |

**Out of v1** (data still syncs and is preserved; just not surfaced in the UI):

- The 4-phase planning ritual (Review → Choose → Timebox → Commit) and `daily-plans`.
- Focus / pomodoro mode and `focus-sessions`.
- Notes and note-groups.
- The reports dashboard.
- **Authoring** recurrence rules (recurring task *instances* still appear and are editable as
  ordinary tasks; "edit series / detach" actions are deferred).

## 2. Architecture & stack

**Pattern.** MVVM with unidirectional data flow. Each screen has a `ViewModel` that exposes a
single immutable UI-state `data class` via `StateFlow`, collected with
`collectAsStateWithLifecycle`. The UI is stateless and emits events upward (MVI-lite). UI state
is derived from Room `Flow`s — the network is never called from the UI.

**Libraries.**

- **UI:** Jetpack Compose (Compose BOM) + Material 3; dark-only theme; full RTL.
- **DI:** Hilt.
- **Navigation:** Navigation Compose with type-safe routes.
- **Async:** Kotlin Coroutines + Flow.
- **Local DB:** Room (via KSP).
- **Background:** WorkManager.
- **Network:** Retrofit2 + OkHttp + kotlinx.serialization (Retrofit converter).
- **Storage:** Jetpack DataStore (Preferences) for the encrypted token blob + sync flags.
- **Build:** Kotlin 2.x, KSP, Gradle Kotlin DSL, a `gradle/libs.versions.toml` version catalog;
  `minSdk 26`, `compile/target SDK 35`.

**Module layout.** A single Gradle `:app` module — right-sized for a solo v1 — layered by
package:

```
android/
  app/
    src/main/java/net/qmindtech/tmap/
      data/
        local/        Room: entities, DAOs, AppDatabase, type converters
        remote/       Retrofit API interfaces, wire DTOs, auth interceptor + authenticator
        repository/   per-aggregate repositories; expose Room Flows; write-through to outbox
        sync/         SyncEngine, Outbox, PushWorker, PullWorker, sync state
        auth/         token store (Keystore), AuthRepository, session state
      domain/         lightweight app models + mappers (DTO <-> entity <-> ui)
      ui/
        theme/        colors (surface-*/accent/...), typography, RTL helpers
        navigation/   NavHost, routes
        today/ inbox/ backlog/ alltasks/ tasks/ projects/ auth/ settings/
        components/    shared composables (TaskRow, PriorityBadge, etc.)
      notifications/  ReminderScheduler, AlarmReceiver, BootReceiver, channels
      di/             Hilt modules
```

A multi-module split (`core` / `data` / `feature-*`) is a deliberate later optimization, not a
v1 need.

## 3. Data layer (Room)

### 3.1 Entities modeled in v1

Room mirrors the server's wire shape for the four entity types v1 uses. Each carries the sync
bookkeeping columns so pull/convergence works:

- **`tasks`** — id (UUID, PK), title, notes, projectId, labels (JSON), source, status (enum),
  plannedDate, scheduledStart, scheduledEnd, durationMinutes, actualTimeMinutes, priority (1–4),
  reminderMinutes, rank, dueDate, recurrenceRuleId, isRecurrenceTemplate, recurrenceDetached,
  recurrenceOriginalDate, completedAt, createdAt, updatedAt, **`changeSeq`**, **`deletedAt`**.
- **`subtasks`** — id (PK), taskId (FK/index), title, completed, sortOrder, createdAt,
  updatedAt, `changeSeq`, `deletedAt`.
- **`projects`** — id (PK), name, color, emoji, rank, actualTimeMinutes, createdAt, updatedAt,
  `changeSeq`, `deletedAt`.
- **`settings`** — key (PK), value, `changeSeq`, `deletedAt`; plus the user's `timeZoneId`.

`status` is stored as the PascalCase string the server uses (`Inbox|Backlog|Planned|Scheduled
|Done|Archived`) and parsed case-insensitively. Recurrence-template rows are hidden from list
queries (`isRecurrenceTemplate = false` filter), matching the clients.

### 3.2 Engine tables

- **`outbox`** — local-seq (PK, autoincrement), entityType, entityId (UUID), opType
  (create/update/delete/reorder), payload (JSON, wire-shaped request body), attempts, parkedAt
  (nullable), createdAt. FIFO by local-seq.
- **`sync_state`** — single row: `lastSeq` (cursor), `initialSyncComplete` (bool),
  `schemaVersion` (bumped when new entity types are added → triggers full-resync), lastSyncAt,
  lastError.

### 3.3 Selective modeling & the backfill rule

The pull response carries rows for all nine entity types. v1 **applies only** tasks, subtasks,
projects, settings and **ignores** notes/note-groups/focus-sessions/daily-plans/recurrence-rules
rows while still advancing the global `lastSeq` cursor past them. Consequence: when a later
version starts modeling one of those types, it would otherwise have skipped historical rows. We
handle this deterministically: the app stores a local `schemaVersion`; bumping it (because new
tables were added) forces a **one-time full-resync** (`reset local store → re-pull from
cursor=0`) on next launch, backfilling everything. Recurring task *instances* need no rule rows
to render, so recurrence-rules being unmodeled does not affect v1 display.

### 3.4 Repositories

One repository per aggregate (`TaskRepository`, `ProjectRepository`, `SubtaskRepository`,
`SettingsRepository`). Reads return Room `Flow`s. Writes are **write-through**: in one Room
transaction they (a) apply the optimistic change to the entity table and (b) append the
corresponding op to `outbox`, then nudge the sync engine (debounced 2 s). Client UUIDs are
generated locally for creates so the op is idempotent-by-id.

## 4. Sync engine (the SP3 mirror)

The engine is a coroutine-driven service plus two WorkManager workers. Room is authoritative for
the UI; the engine only moves data between Room and the server.

### 4.1 Push (`PushWorker` / outbox replay)

- Replays `outbox` ops **FIFO** by local-seq through the existing REST endpoints (the endpoints
  own cascade tombstones, recurrence logic, validation, RLS — we do not reimplement them).
- **Idempotent-by-id:** creates send the client UUID → a replay returns 200, never a duplicate.
- **5xx → park & retry:** keep the op, exponential backoff (1 s / 2 s / 4 s), 3 attempts per
  cycle; an op that exceeds **10 total attempts** is parked (poison-op) and surfaced.
- **409 → adopt-existing:** the server returns `existingId`; the client remaps its local id to
  the server's and continues (no wedge).
- **Definitive 4xx → rejection-recovery:** drop the offending op, surface it to the user
  (discardable), and **never wedge the queue** — later ops still drain (SP3 AC10).

### 4.2 Pull (`PullWorker`)

- `GET /api/v1/sync?since={lastSeq - overlap}&cursor={lastSeq}&limit=500`, paginating while
  `hasMore`, applying rows by id (upsert; `deletedAt != null` → delete locally), advancing
  `lastSeq` to `nextSince`. A **5000-seq overlap** guards the page boundary; idempotent apply
  makes the overlap harmless.
- **`fullResyncRequired: true` → reset local store + re-pull from `cursor=0`,** *drain-gated*:
  only when the `outbox` is empty (no pending local ops), exactly as SP3 does, so a pending
  write is never lost to a resync.

### 4.3 Convergence rules

- **Shadow rule:** a pulled server row must NOT clobber a local entity row that still has a
  pending `outbox` op — the local optimistic value wins until its op syncs. This is what makes
  two-device editing converge.
- **LWW on changeSeq** for rows with no pending op: the server row is authoritative.

### 4.4 Triggers

- **Expedited one-shot** sync on (a) connectivity regained (a `NetworkCallback` /
  `WorkManager` network constraint) and (b) after each local write (debounced 2 s).
- **Periodic** sync worker at the 15-min WorkManager floor as a safety net.
- Network constraint on the workers; exponential backoff on failure.
- A **sync-status surface** (idle / syncing / offline / error) exposed as state for a small UI
  indicator, mirroring the desktop pill.

## 5. Auth & secure token storage

### 5.1 Tokens

- **Access token:** held in memory (session scope).
- **Refresh token:** encrypted at rest via the **Android Keystore** — a Keystore-resident
  AES/GCM key encrypts the token; the ciphertext + IV live in DataStore. (We deliberately avoid
  the deprecated `EncryptedSharedPreferences`.)

### 5.2 Request auth & refresh

- An OkHttp `Interceptor` attaches `Authorization: Bearer {accessToken}`.
- A **mutex-serialized `Authenticator`** handles 401: exactly one refresh runs at a time
  (`POST /api/v1/auth/refresh` with `{refreshToken}` in the **body** — native path, no CSRF
  header); concurrent 401s await the same result. This avoids the backend's rotation/reuse
  detection treating two racing refreshes as a replay and revoking the token family.
- On success, the rotated refresh token replaces the stored one atomically.

### 5.3 Failure handling

- **Transient / offline** refresh failure → stay signed in, keep operating offline against Room
  (authed-offline cold start). Retry on next connectivity.
- **Definitive** failure (`401 invalid_grant` — token genuinely revoked/expired) → route to
  login but **KEEP local data**; on re-login with the same account the UI is instantly populated
  and a full-resync reconciles. (No keychain/data wipe.)

## 6. Reminders / local notifications

- **`ReminderScheduler`** arms an `AlarmManager` exact alarm per task that has a future
  `reminderMinutes` (relative to `scheduledStart`/`plannedDate`) or `dueDate`. An
  `AlarmReceiver` (BroadcastReceiver) posts the notification on a dedicated Notification Channel
  (required on API 26+).
- **Permissions:** runtime `POST_NOTIFICATIONS` (Android 13+) requested in-context;
  `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` handled per Android 12+ policy with a graceful
  fallback to inexact if denied.
- **Boot resilience:** a `BOOT_COMPLETED` receiver re-arms all future alarms after reboot.
- **Sync coupling:** the engine re-arms / cancels a task's alarm whenever a pull delta or local
  edit changes its time, completion (`completedAt`), or deletion (`deletedAt`). Completing or
  deleting a task cancels its pending reminder.

## 7. Navigation & screens

A single-activity Compose app. Bottom navigation across the primary destinations — **Today**,
**Inbox**, **All Tasks**, **Projects** — with **Backlog** reachable from All-Tasks' status
filter (or its own entry if it reads better), plus a **Settings** route and an **Auth** graph
(login / register) gating the app. The task editor is a full-screen route (or large bottom
sheet) reused for create + edit, including inline subtask CRUD. A FAB on Today/Inbox triggers
quick-capture.

## 8. Repo placement, build, theming

- A new **top-level `android/` Gradle project** — *not* under the npm `apps/*` workspaces (those
  are JS/Vite). The monorepo stays one git repo.
- Built on a dedicated **`feat/sp4-android` branch / worktree**, never directly on `main` (per
  the concurrent-sessions HEAD-race hazard).
- **Dark-only Material 3** theme echoing the desktop palette (`surface-*` 50–950, `accent`,
  `success`, `warning`, `danger`); RTL-correct layouts (the user's data includes Arabic, e.g.
  the project "حجوزات عيادات").
- The committed `packages/api-client/openapi.json` is the contract reference (OpenAPI is 404 in
  Production — never fetched live).
- **No backend changes are planned.** If a genuine gap appears, an endpoint is added
  deliberately as a scoped follow-up, not assumed.

## 9. Acceptance criteria

- **AC1** A new user can register and an existing user can log in; the session survives app
  restart without re-entering credentials (refresh token persisted, Keystore-encrypted).
- **AC2** With the network off, the user can create (inbox quick-capture), edit, complete, and
  delete tasks and projects; the UI updates instantly from Room.
- **AC3** On reconnect, queued ops drain FIFO; creates are idempotent (no duplicates on replay);
  a definitive 4xx is dropped + surfaced without wedging later ops.
- **AC4** Changes made on the web/desktop client appear on Android after a sync, and vice-versa;
  a row edited on two devices converges (shadow rule honored — a local pending edit is not
  clobbered by a pull).
- **AC5** A cold start while offline (but previously signed in) renders local data and does not
  force logout.
- **AC6** `fullResyncRequired` resets and re-pulls from cursor=0 only when the outbox is empty;
  no pending write is lost.
- **AC7** A reminder fires as a local notification at the right time, including when the app is
  closed and after a reboot; completing/deleting/rescheduling a task updates its alarm.
- **AC8** All Tasks supports search + filter (status/priority/project/date range) + group + sort
  consistent with the desktop AllTasksView.
- **AC9** Recurring task instances appear and are editable as ordinary tasks (no recurrence
  authoring UI in v1).
- **AC10** The app is dark-only and renders RTL content (Arabic) correctly.

## 10. Risks & mitigations

- **Refresh-token race / family revoke** — mitigated by the mutex-serialized Authenticator
  (§5.2). This is the highest-risk auth detail; it gets explicit tests.
- **Exact-alarm policy churn (Android 12–14)** — exact-alarm permission can be denied/revoked;
  mitigate with the inexact fallback and a clear in-app rationale.
- **Selective-modeling cursor gap** — addressed by the schema-version-triggered full-resync
  (§3.3); documented so future contributors don't add a table without bumping the version.
- **Sync correctness is the hard part** — mirror SP3's behavior precisely and port its scenario
  coverage (FIFO drain, 409-adopt, rejection-recovery, full-resync drain-gating, two-device
  convergence) as instrumented/integration tests.
- **WorkManager timing ≠ desktop 60 s poll** — accepted and documented (§ Pinned constants);
  event-driven expedited sync covers the latency the periodic floor can't.

## 11. Open questions (to resolve during planning, not blocking)

- Exact min-SDK confirmation vs. any device the user carries (assumed 26).
- Whether Backlog gets its own bottom-nav entry or lives inside All-Tasks' filter.
- Test strategy depth for the engine (Robolectric + Room in-memory + a fake `/sync` server,
  mirroring SP3's `fakeSyncServer`) — to be locked in the implementation plan.

---

*Next step after this spec is reviewed: the `writing-plans` skill produces the phased
implementation plan (`docs/superpowers/plans/2026-06-18-sp4-android-native-app.md`), executed
with the ultracode + subagent-driven, per-phase green-gate harness used for SP3/SP5.*
