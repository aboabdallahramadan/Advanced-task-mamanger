# TMap — Planning Canvas Redesign + Reports

**Date:** 2026-05-31
**Status:** Approved design, ready for implementation planning

## 1. Overview

Two related features that share one data-capture foundation:

- **A. Planning Canvas** — replace the current read-only 3-step planning wizard with a **guided 3-column canvas** ("Sources | Today | Timeline") fronted by a phase bar (Review → Choose → Timebox → Commit). The goal is that finishing the ritual produces a *committed, time-blocked day*, with a live capacity check.
- **B. Reports** — a new top-level **Reports** view showing **Throughput** (tasks completed per day + completion rate) and **Time by project**, over Day/Week/Month/Year ranges.

Both depend on a one-time DB migration that begins capturing data the app does not record today. **Reports only reflect data from when capture starts — there is no historical backfill.**

### Decisions locked during brainstorming
- Planning layout: **guided canvas** (everything visible, phase bar guides).
- Reports scope: **Completion & throughput** + **Time by project** only. (Estimate-accuracy and streaks were explicitly deferred.)
- Data capture: **full** — `completed_at`, a focus-session log, and a daily-plan snapshot.
- Time-by-project visualization: **horizontal bars**.
- Charting library: **Recharts**.
- **Commit** writes a daily snapshot (not just close); **no** auto-launch each morning; tracked time = Focus-mode sessions only.

## 2. Current state (as explored)

- The planning ritual is `PlanningFlowOverlay` + `StepReviewYesterday` / `StepTriageInbox` / `StepTimebox`, driven only by `planningFlow = { isOpen, step: 0|1|2 }` in the store (`startPlanningFlow` `store.ts:816`, `setPlanningStep` `:817`, `closePlanningFlow` `:818`). Steps are read-only filters over the global `tasks` array; mutations happen via the shared `TaskItem`. Triage's "move to today" interaction does not exist; "Finish" writes nothing.
- Entry points: Sidebar "Plan Today" (`Sidebar.tsx:196`), tray item → IPC `navigate`→`plan-today` (`electron/main.ts:242-247`, listener `App.tsx:56-60`).
- Task mutations: `scheduleTask` (`store.ts:354`), `unscheduleTask` (`:364`), `markDone` (`:373`, sets `plannedDate=today`, **no completion timestamp**), `moveToToday` (`:379`, imported by `TaskItem` but never surfaced in its menu), `moveToBacklog` (`:385`), `archiveTask` (`:390`), `reorderTasks` (`:338`).
- Capacity: `freeMinutesRemaining()` (`store.ts:1006`) and `workStartHour`/`workEndHour`/`timeIncrement` (persisted to `settings.json`).
- Focus mode: `startFocusSession`/`pauseFocusSession`/`stopFocusSession` (`store.ts:873-929`) accrue elapsed minutes into `task.actualTimeMinutes` (DB col `actual_time_minutes`, migration `002`). **No per-session record is persisted** — only the rolling total.
- Rollover **bug**: `loadTasks` (`store.ts ~228-291`) sets past `scheduled` tasks to status `'todo'`, which is **not** a member of the TS `Status` union nor the DB `CHECK` constraint.
- Views are switched by `currentView: ViewMode` (`types.ts:53`, default `'board'` `store.ts:200`); `App.tsx:127-151` is a ternary chain; `setCurrentView` (`store.ts:772-777`). Sidebar `navItems` (`Sidebar.tsx:40-47`) → `setCurrentView` (`:210`). **No router.**
- **No charting library** installed. Modals are hand-rolled (`ProjectDialog.tsx:81-102` is the canonical shell). Dark theme, custom Tailwind palettes (`surface-*`, `accent-*`, `success/warning/danger`) and `@layer` classes in `index.css`.
- `tasks.project` stores the project **name string**, not an id (no FK).

## 3. Data foundation — migration `003`

A new migration in `electron/database.ts` (follow the existing migration pattern; record in `migrations` table).

### 3.1 `tasks.completed_at`
- `ALTER TABLE tasks ADD COLUMN completed_at TEXT` (ISO datetime, nullable).
- Set when a task transitions **to** `done`; cleared (set `NULL`) when it leaves `done`.
- Implemented in the store's `markDone` (stamp `completedAt = new Date().toISOString()`) and any un-done path, persisted via `taskService.update`. `rowToTask`/`update` in `electron/taskService.ts` must read/write the new field. Add `completedAt?: string` to the `Task` interface.

### 3.2 `focus_sessions` table
```
id            TEXT PRIMARY KEY
task_id       TEXT            -- FK -> tasks(id) ON DELETE SET NULL (keep history if task deleted)
project       TEXT            -- denormalized project NAME at session time (see rename caveat)
started_at    TEXT NOT NULL   -- ISO
ended_at      TEXT NOT NULL   -- ISO
minutes       INTEGER NOT NULL
date          TEXT NOT NULL   -- YYYY-MM-DD of started_at (local), for range grouping
created_at    TEXT NOT NULL
```
- Written when a focus session ends (`pauseFocusSession`/`stopFocusSession`) **in addition to** the existing `actualTimeMinutes` accrual. Reuse the existing `elapsedMinutes > 0` guard so sub-minute sessions are still dropped consistently.
- `project` is captured from the task at write time (denormalized) because tasks reference projects by name; this keeps historical attribution stable even if the live task's project changes. **Rename caveat:** renaming a project will not retroactively rename past sessions — acceptable for v1; note it.

### 3.3 `daily_plans` table
```
date            TEXT PRIMARY KEY   -- YYYY-MM-DD the plan is for
committed_at    TEXT NOT NULL      -- ISO when "Commit day" was pressed
planned_task_ids TEXT NOT NULL     -- JSON array of task ids committed for the day
planned_minutes INTEGER NOT NULL   -- sum of durationMinutes of committed tasks at commit time
```
- Upserted by the planning flow's **Commit** step. Re-committing the same day overwrites the row.
- Serves three purposes: (1) denominator for completion rate, (2) persistence that the day was planned (the ritual now "sticks"), (3) future streak support.

### 3.4 Rollover bug fix
- In `loadTasks`, change the invalid `status = 'todo'` assignment to a valid status. Past unfinished `scheduled` tasks rolled to today should become `'planned'` with scheduled times cleared (consistent with `unscheduleTask`), and `plannedDate` moved to today. Confirm no other site writes `'todo'`.

## 4. Backend services & IPC

Follow the existing service + `window.api.*` bridge pattern (`preload.ts` exposes; `main.ts` registers handlers). Current task IPC is only `getAll/getByDate/getByStatus/search` (`types.ts:93-102`).

- **`focusSessionService.ts`**: `add(session)`, `getByRange(startDate, endDate)`. IPC: `window.api.focusSessions.add/getByRange`.
- **`dailyPlanService.ts`**: `upsert(plan)`, `get(date)`, `getByRange(startDate, endDate)`. IPC: `window.api.dailyPlans.*`.
- **`reportService.ts`**: SQL aggregates so results don't depend on which loader populated the in-memory array:
  - `getThroughput(startDate, endDate)` → per-day `{ date, completedCount, plannedCount, completedOfPlanned }` (joins `daily_plans` for denominator; `completedCount` from `tasks.completed_at`).
  - `getTimeByProject(startDate, endDate)` → `[{ project, minutes }]` from `focus_sessions GROUP BY project`.
  - `getSummary(startDate, endDate)` (+ previous comparable period) → `{ completed, completionRate, focusMinutes, topProject, deltas }`.
  - IPC: `window.api.reports.*`.

## 5. Planning Canvas (feature A)

Replace `PlanningFlowOverlay` and the three `Step*` components with:

- **`components/planning/PlanningCanvas.tsx`** — full-screen overlay (reuse the modal backdrop pattern, mounted in `App.tsx`). Three columns:
  - **Sources** (left): leftovers + inbox + backlog, emphasis varies by phase.
  - **Today** (middle): tasks planned for the target date, with a **capacity meter** at the top (planned minutes vs work-hours capacity, warning state on overcommit). Drag/click to add from Sources.
  - **Timeline** (right): embeds `DayTimeline`; drag Today's tasks onto slots (reuses the App-level `DndContext` → `scheduleTask`).
- **Phase bar** (Review → Choose → Timebox → Commit): highlights the current phase and the relevant column, but no column is hidden. Linear Prev/Next; phases are data-driven (replace the brittle `0|1|2` casts with a typed phase list).
  - **Review** — Sources shows leftovers (overdue, not done). Per-task: *Plan for today* / Done / Backlog / Archive.
  - **Choose** — Sources shows inbox + backlog; *Plan for today* moves into Today; capacity meter live-updates.
  - **Timebox** — drag Today → Timeline; meter reflects scheduled vs available.
  - **Commit** — summary panel (task count, total estimate vs available capacity, overcommit warning). *Commit day* → `dailyPlans.upsert` then close.

### Store changes
- New action **`commitDay(date)`** — builds and upserts the `daily_plans` snapshot from the current Today set, then closes the flow.
- Replace `planningFlow: { isOpen, step }` with `{ isOpen, phase, targetDate }` so the ritual operates on one explicit **target date** (fixes the `selectedDate` vs hardcoded-`today` mismatch that currently makes mutations land on the wrong day).
- Promote the inline step filters into reusable selectors: `leftoverTasks(beforeDate)`, `plannedForDate(date)`, `unscheduledPlannedTasks(date)`. Memoize where the old steps re-filtered on every render.
- `markDone` stamps `completedAt` (see 3.1).

### `TaskItem` change
- Add a **"Plan for today"** item to the actions menu, wired to the existing `moveToToday` (currently imported but never surfaced). Available in the canvas and elsewhere.

### Entry points
- Keep the three triggers (Sidebar button, tray, IPC); they now open `PlanningCanvas` with `targetDate = today`.

## 6. Reports (feature B)

- **`components/ReportsView.tsx`** — new top-level view following the standard pattern (sticky header with icon + title, scrollable body, reads from `useStore`, `pt-10` for the titlebar). Layout matches the approved mockup:
  - **Range tabs**: Day / Week / Month / Year (default Week). Drives the start/end dates sent to `reportService`.
  - **Summary cards**: Completed, Completion rate, Focus time, Top project — each with a delta vs the previous comparable period.
  - **Throughput** panel: Recharts vertical `BarChart` of tasks completed per day across the range, with an average reference line; current day highlighted.
  - **Time by project** panel: Recharts **horizontal** `BarChart` (exact hours per project), using project colors from the `projects` table where available.
- **Definitions**:
  - **Throughput(day)** = count of tasks with `completed_at` on that day (all completions, incl. ad-hoc).
  - **Completion rate(day)** = committed tasks (`daily_plans.planned_task_ids`) whose `completed_at` is on that day ÷ committed tasks that day. Days with no `daily_plans` row show no rate (not 0%).
  - **Time by project(range)** = `SUM(minutes)` from `focus_sessions` grouped by `project`.
- **Store**: reports slice with `reportRange`, cached results, and actions calling `window.api.reports.*`. Load on view mount / range change.

### Navigation wiring
- Add `'reports'` to the `ViewMode` union (`types.ts:53`).
- Add a branch in `App.tsx:127-151` → `<ReportsView />`.
- Add a `navItems` entry in `Sidebar.tsx:40-47` (lucide `BarChart3`/`LineChart` icon) → `setCurrentView('reports')`.

### Dependency
- Add **`recharts`** to `package.json`.

## 7. Types (`src/types.ts`)
- `Task.completedAt?: string`.
- `FocusSession`, `DailyPlan`, and report DTOs (`ThroughputPoint`, `ProjectTime`, `ReportSummary`).
- `ViewMode` += `'reports'`.
- Extend the `window.api` type surface for the new IPC namespaces.

## 8. Edge cases & risks
- **Sparse early data** — set expectations in the UI (e.g., an empty/low-data state on charts) since there's no backfill.
- **Completion rate denominator** — only days with a `daily_plans` row have a rate; render those without one as "—", not 0%.
- **Project rename** — historical `focus_sessions.project` keeps the old name (denormalized). Acceptable for v1; documented.
- **Deleted task with sessions** — `ON DELETE SET NULL` keeps the session's minutes/project for time-by-project totals.
- **Migration safety** — additive only (new columns/tables); existing rows get `completed_at = NULL`. No destructive changes.
- **Loader interaction** — reports use SQL aggregates via `reportService`, not the in-memory `tasks` array, so `loadTasks` vs `loadTasksByDate` state differences don't affect them.
- **Timezone** — `date` columns use the local calendar day of the timestamp; be consistent between writing (`focus_sessions.date`, `completed_at`-derived day) and range queries.

## 9. Out of scope (YAGNI)
Estimate-accuracy and streak reports (schema will support them later), auto-launch-once-per-day, evening shutdown ritual, label-level time breakdown, exporting reports.

## 10. Suggested build order
1. **Data foundation** — migration `003`, `completed_at` wiring in `markDone`/`taskService`, focus-session logging, `daily_plans` service, rollover bug fix. Invisible but unblocks both features.
2. **Planning Canvas** — `PlanningCanvas` + phase bar + capacity meter + `commitDay`; retire old overlay/steps; add `TaskItem` "Plan for today".
3. **Reports** — `reportService` aggregates + IPC, `ReportsView` with Recharts, nav wiring, summary cards.
