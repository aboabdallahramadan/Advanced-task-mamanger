# Design: Recurring-task creation on Android

**Date:** 2026-07-05
**Surface:** Android client only (`android/`)
**Status:** Approved — ready for implementation planning

## Summary

The native Android app can **display** recurring tasks that were created on desktop/web
(occurrences sync down and render as ordinary tasks), but it has **no way to create,
edit, or delete a recurring series** — the recurrence data path exists only inbound.
This project adds recurring-task **creation and series management** to the Android client.

Scope agreed with the user: **Create + manage series** —

- Create a daily/weekly recurring task from the New Task editor.
- Delete a series from mobile, with a **This occurrence / This and future / All
  occurrences** scope prompt.
- Edit the series' rule (frequency / interval / days / end condition) from mobile.

The backend already fully supports client-created recurrence (`POST /recurrence` creates
the rule + template task in one call, client may supply GUIDs; instances are materialized
server-side via `POST /recurrence/ensure-instances`). **There are no backend, DTO, or
sync-protocol changes** — all work is in the Android client, mirroring the shared desktop
client (`packages/app` `LocalDataClient` recurrence flow).

## Goals

- A user on Android can create a daily or weekly recurring task and have its occurrences
  appear on every device.
- A user on Android can remove a series (with occurrence/future/all scope) and edit its
  recurrence rule.
- The rule model, wire contract, and enum casing exactly match the existing product so
  data round-trips cleanly with desktop/web and the backend.
- The feature honors the app's offline-first write-through + outbox model.

## Non-Goals

- **No new frequencies.** Backend + product support **Daily and Weekly only** — no
  monthly, yearly, weekday-preset, or custom rules. Out of scope (would need backend work).
- **No local occurrence-generation engine.** Instances are materialized **server-side**;
  Android never generates occurrence rows locally. (Rejected alternative — see Approach.)
- **No per-occurrence "this occurrence only" _edit_ / detach** (editing a single instance's
  fields off the series). Deleting a single occurrence *is* supported (plain tombstone).
- **No "convert an existing non-recurring task into a recurring one."** Recurrence is set
  only at create time, on a new task. (Deferred; the clean backend path is create-time.)
- **No `recurrence_exceptions` storage on the client** — it is server-internal and never
  synced. Skips are handled by the server + instance tombstones.
- No recurrence in Quick Capture / widgets / voice for now (New Task editor only).
- No backend or sync-protocol changes.

## Approach

**Thin, server-authoritative client — mirror the desktop `LocalDataClient` recurrence
flow.** The rule + a hidden template task are written to Room and queued in the outbox;
the **server** materializes instances; the app calls `ensure-instances` on the task-list
refresh and pulls occurrences down.

Rejected alternatives:

- **Port the occurrence generator to Kotlin (local generation)** — would show occurrences
  instantly offline, but the server *also* generates them (keyed on `recurrenceOriginalDate`),
  risking duplicate/divergent instances, and it contradicts the server-authoritative model
  the rest of the system commits to.
- **Online-only recurrence (bypass the outbox, call REST directly)** — simplest, but breaks
  the offline-first write-through invariant every other mutation honors.

## The recurrence model (replicate exactly)

Identical across backend (`RecurrenceRule.cs`), desktop (`packages/app/src/types.ts`), and
the old electron engine. Android must mirror it field-for-field.

| Field | Type / values | Notes |
|---|---|---|
| `frequency` | `daily` \| `weekly` | Only two. Wire form capitalized: `Daily` / `Weekly`. |
| `interval` | int ≥ 1 | "every N days/weeks"; UI cap 52. |
| `daysOfWeek` | `List<Int>` | **Sunday = 0 … Saturday = 6.** Weekly only, ≥1 required; empty `[]` for daily. |
| `endType` | `never` \| `count` \| `date` | Wire form capitalized: `Never` / `Count` / `Date`. |
| `endCount` | int? (1–365) | Used only when `endType = count`. |
| `endDate` | `YYYY-MM-DD`? | Used only when `endType = date`. |
| `generatedUntil` | date? | Server bookkeeping high-water-mark; not user-facing. |

- **Series anchor / start date:** there is **no separate anchor field**. The start is the
  **template task's planned date** (`recurrenceOriginalDate = plannedDate`). The "When"
  chosen in the editor (default: today) is the series start.
- **Template vs instance:** the template is a task with `isRecurrenceTemplate = true` that
  carries `recurrenceRuleId` and the canonical field values; it is a hidden generator row
  (already filtered from all Android lists via `isRecurrenceTemplate = 0`). Instances are
  ordinary task rows with `isRecurrenceTemplate = false`, the same `recurrenceRuleId`, and
  `recurrenceOriginalDate` = the occurrence date.

### Backend validators to satisfy (client-side pre-validation mirrors these)

- `interval ≥ 1`
- `daysOfWeek` items in 0–6
- `weekly` ⇒ at least one day
- `endType = count` ⇒ `endCount > 0`
- `endType = date` ⇒ `endDate` set

## Existing patterns reused

| Concern | Reused from |
|---|---|
| Offline write-through + outbox + expedited sync | `data/repository/TaskRepository.kt` (`create`), `data/sync/OutboxRepository.kt` |
| Wire DTOs via kotlinx.serialization | `data/remote/dto/TaskDtos.kt`, `data/sync/Mappers.kt` |
| Op → endpoint routing on push | `data/sync/PushRunner.kt` |
| Pull → entity mapping | `data/sync/PullRunner.kt`, `data/sync/Mappers.kt` (`toEntity`) |
| Room entity + DAO + migration | `data/local/entities/*`, `data/local/dao/*`, `data/local/AppDatabase.kt` |
| Task editor sheet + view model + UI state | `ui/taskeditor/TaskEditorSheet.kt`, `TaskEditorViewModel.kt`, `TaskEditorUiState.kt` |
| Desktop recurrence UI reference | `packages/app/src/components/RecurrenceEditor.tsx`, `RecurrenceActionDialog.tsx` |
| Pure, JVM-unit-tested logic (no `android.*`) | `ui/capture/QuickCaptureParser.kt` |
| Rolling `ensureInstances(today, today+14)` on refresh | desktop `packages/app/src/store.ts:489-493` |

## Architecture

### 1. Data layer — store recurrence rules locally

- **`RecurrenceRuleEntity`** (new) + **`RecurrenceRuleDao`** (new) — columns per the model
  table above plus sync fields (`deletedAt`, `changeSeq`). `daysOfWeek` stored via the
  existing `Converters` (Room `List<Int>` ↔ JSON), matching how `TaskEntity.labels` is
  handled.
- **`AppDatabase` v3 → v4**: register `RecurrenceRuleEntity`; add a **real
  `Migration(3, 4)`** that `CREATE TABLE recurrence_rules` (so existing installs are not
  destructively wiped — `fallbackToDestructiveMigration` stays as the backstop only).
- **Not added:** any `recurrence_exceptions` table (server-internal, unsynced).

### 2. Create/edit model

- New value type **`RecurrenceDraft`** (`frequency`, `interval`, `daysOfWeek`, `endType`,
  `endCount?`, `endDate?`) — a pure Kotlin data class.
- Extend **`TaskDraft`** with `recurrence: RecurrenceDraft? = null` (default null ⇒ current
  behavior unchanged, preserving the pinned-contract field order by *appending*).
- New pure **`RecurrenceMapper`** (JVM-testable): `RecurrenceDraft` → wire DTOs with
  capitalized enums and Sunday=0 `daysOfWeek`; and `RecurrenceRuleSyncRow` → entity inbound.

### 3. Wire DTOs (new, serializable)

- `CreateRecurringTaskRequest { task: RecurringTaskInput, rule: RecurrenceRuleInput }`
  (both accept optional client-supplied `id`).
- `UpdateRuleRequest` (whole-rule field replace, for edit-rule).
- Reuse existing task-delete op for single-occurrence delete.

### 4. Repository + outbox routing

New methods (on a focused **`RecurrenceRepository`**, or added to `TaskRepository`):

- `createRecurring(draft: TaskDraft /* with recurrence */): String`
  — mints client GUIDs for rule + template, writes the rule row + template task
  (`isRecurrenceTemplate = true`, `recurrenceOriginalDate = plannedDate`) in **one Room
  transaction**, enqueues `RECURRENCE_CREATE`, arms nothing (template isn't a live reminder),
  requests expedited sync.
- `updateRule(ruleId, RecurrenceDraft)` → enqueues `RECURRENCE_UPDATE_RULE`.
- `deleteSeries(occurrenceTaskId, scope)` where `scope ∈ {ThisOccurrence, ThisAndFuture,
  All}`. The occurrence task supplies both `recurrenceRuleId` and its
  `recurrenceOriginalDate` (the `fromDate` for the future case):
  - `All` → `RECURRENCE_DELETE_ALL` (rule id).
  - `ThisAndFuture` → `RECURRENCE_DELETE_FUTURE` (rule id + `fromDate = recurrenceOriginalDate`).
  - `ThisOccurrence` → **existing** task-delete op on that instance (tombstone; server
    won't regenerate that `recurrenceOriginalDate`).

**New outbox op types** routed by `PushRunner` to the recurrence endpoints (PushRunner
currently only knows task/note/project/subtask routes — it must learn these):

| Op | Method + path | Body |
|---|---|---|
| `RECURRENCE_CREATE` | `POST /recurrence/` | `CreateRecurringTaskRequest` |
| `RECURRENCE_UPDATE_RULE` | `PATCH /recurrence/rules/{id}` | `UpdateRuleRequest` |
| `RECURRENCE_DELETE_ALL` | `DELETE /recurrence/rules/{id}` | — |
| `RECURRENCE_DELETE_FUTURE` | `POST /recurrence/rules/{id}/delete-future` | `{ fromDate }` |

### 5. Instance materialization

- Add **`ensureInstances(today, today + 14)`** to the task-list refresh path (mirroring
  desktop `store.ts:493`) so occurrences roll forward on the same rolling 14-day horizon as
  every other client. It POSTs `/recurrence/ensure-instances?start=&end=`; the server
  materializes rows which then arrive via the normal pull. Offline it is a no-op ("coasts
  on the horizon").

### 6. Pull side

- `PullRunner` maps incoming `RecurrenceRuleSyncRow` → `RecurrenceRuleEntity` (currently
  dropped by `ignoreUnknownKeys`). Templates remain filtered from lists (unchanged);
  instances arrive as normal task rows.

### 7. UI

- **`RecurrenceEditor` composable** inside `TaskEditorSheet`, matching desktop's control:
  **Off / On** toggle → **Daily / Weekly** pills → **Every [N]** stepper → **S M T W T F S**
  day toggles (weekly only; ≥1 enforced, cannot deselect the last) → **Never / After [N] /
  On [date]**.
  - Defaults: `daily`, interval 1, end `never`; when switching to weekly, pre-select the
    **planned date's weekday** (friendlier than desktop's fixed `[0]`).
- Opening the editor on an existing **recurring instance** shows a "Repeats every…"
  summary plus **Edit rule** and **Delete series** actions. Delete raises a bottom-sheet
  with **This occurrence / This and future / All occurrences** (mirrors desktop
  `RecurrenceActionDialog`).

### 8. Offline behavior (accepted trade-off)

Because instances are server-generated, a recurring task created **offline** writes fine
locally but its occurrences do not appear until the device reconnects (POST → ensure-
instances → pull). The hidden template is not shown. This matches desktop exactly. A subtle
"will appear once synced" affordance is shown rather than building a second (local) engine.

## Data flow (create, happy path online)

```
New Task editor (recurrence ON)
  → TaskEditorViewModel.save()
  → RecurrenceRepository.createRecurring(draft)
      → Room txn: insert RecurrenceRuleEntity + template TaskEntity (isRecurrenceTemplate=1)
      → outbox.enqueue(RECURRENCE_CREATE, CreateRecurringTaskRequest)
      → syncScheduler.requestExpeditedSync()
  → PushRunner: POST /recurrence/  (rule + template created server-side)
  → next refresh: ensureInstances(today, today+14) → POST /recurrence/ensure-instances
      → server materializes occurrence rows
  → PullRunner: GET /sync → rule row + instance task rows land in Room
  → Today/Inbox/Browse lists render the occurrences
```

## Testing (follows the app's JVM-unit-test convention)

Pure-logic unit tests in `app/src/test` (no `android.*`), matching how `QuickCaptureParser`
etc. are tested:

- **`RecurrenceMapper`**: draft → wire DTO enum casing (`Daily`/`Weekly`, `Never`/`Count`/
  `Date`), `daysOfWeek` Sunday=0 JSON, daily clears days; `RecurrenceRuleSyncRow` → entity.
- **Outbox op serialization**: `RECURRENCE_CREATE` produces the exact `POST /recurrence`
  body; delete/update ops serialize correctly.
- **`PullRunner`** ingests `recurrenceRules` into the new table (regression: no longer
  dropped).
- **`RecurrenceEditor` state reducer**: day-toggle keeps ≥1 selected; switching Daily↔Weekly
  clears/pre-fills days; end-type switching gates `endCount`/`endDate`.
- **Client-side validators** mirror the backend (weekly ≥1 day, `count > 0`, `date` set,
  `interval ≥ 1`).
- **`RecurrenceRuleDao`** + **Migration(3,4)** DAO/migration tests (Robolectric/in-memory
  Room, as existing DAO tests do).

## Open decisions (resolved)

1. **Offline behavior** — mirror desktop (occurrences appear on reconnect); no local
   generator. **Resolved: yes.**
2. **Manage-series entry point** — inside the task editor sheet opened on a recurring task.
   **Resolved: yes.**
3. **Scope** — Create + manage series (create, delete-with-scope, edit-rule); defer
   per-occurrence detach edits and convert-existing. **Resolved: yes.**
