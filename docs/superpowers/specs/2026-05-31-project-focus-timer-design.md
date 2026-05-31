# Project Focus Timer — Design

**Date:** 2026-05-31
**Status:** Approved (design); pending implementation plan
**Author:** brainstorming session

## Summary

Today the focus timer can only run against a single task. This feature lets a
user start a focus session on **a whole project** instead. Elapsed time
accumulates toward a per-project total (a new "actual time" on the project),
independent of any individual task.

The timer is **open-ended** for projects — it counts up with no planned target
and no progress bar. A project focus session is launched from the **project view
header** and can also be started/switched from a **project picker inside the
focus overlay**.

## Goals

- Start a focus session targeting a project (no specific task selected).
- Accumulate session time onto a persistent per-project total.
- Reuse the existing overlay / always-on-top widget / tray plumbing.
- Preserve the existing single-session model: exactly one focus session at a
  time, targeting either a task or a project.

## Non-goals (YAGNI)

- No planned target / progress bar for project sessions (explicitly open-ended).
- No task "queue/playlist" that auto-advances through a project's tasks.
- No manual project time budget field.
- No "complete"/done concept for projects (projects are not marked done).
- No reporting/analytics views beyond showing a project's accumulated time in
  its header.

## Current state (grounding facts)

- `focusMode` state in `src/store.ts` is `{ activeTaskId: string | null;
  isPlaying: boolean; sessionStartTime: number | null }` (initial state ~L222,
  type ~L68). It is **in-memory only** — not persisted to settings — so there is
  no saved-state migration to perform.
- `startFocusSession(taskId)` sets the session; `pauseFocusSession()` /
  `stopFocusSession()` compute elapsed minutes from `sessionStartTime` and add
  them to the task's `actualTimeMinutes` via `updateTask`.
- `FocusModeOverlay.tsx` resolves `activeTask` from `focusMode.activeTaskId`,
  renders title + elapsed + a progress bar (only when `plannedMinutes > 0`),
  and pushes state to the Electron widget and tray. It also wires the
  `focus:togglePlayPause` / `focus:stop` / `focus:done` / `focus:resyncWidget`
  IPC events.
- "Start timer" entry points read `focusMode.activeTaskId`:
  `src/components/TaskItem.tsx`, `src/components/ProjectView.tsx`,
  `src/components/WeeklyBoardView.tsx`, and the overlay itself.
- Electron main (`electron/main.ts`) renders an always-on-top focus widget and a
  tray indicator. Both consume a generic `taskTitle` + `plannedMinutes` +
  `accumulatedMinutes`; neither is task-specific beyond the field names.
- A `Project` (`src/types.ts` ~L55, `electron/projectService.ts`) has
  `id, name, color, emoji, order, createdAt, updatedAt` and **no time field**.
- **Tasks link to projects by name**: `Task.project` holds the project *name*
  string (see `ProjectService.delete`: `UPDATE tasks SET project = '' WHERE
  project = ?`). Not needed for this feature (no target derived from tasks), but
  noted to avoid confusion.
- DB migrations (`electron/database.ts`) are an append-only array applied by
  name; column adds use `ALTER TABLE ... ADD COLUMN` (e.g. `002` added
  `tasks.actual_time_minutes`).

## Chosen approach

**Unified focus target.** Replace `focusMode.activeTaskId` with a target
descriptor that can point at either a task or a project. This keeps one source
of truth and the single-session model, versus a parallel `activeProjectId` field
(two fields every consumer must check) or a separate project-timer subsystem
(duplicated plumbing + two concurrent timers).

## Design

### 1. Data model

- Add `actualTimeMinutes?: number` to `Project` in **`src/types.ts`** and to the
  `Project` interface in **`electron/projectService.ts`**.
- New migration in **`electron/database.ts`** (next sequential name):
  `ALTER TABLE projects ADD COLUMN actual_time_minutes INTEGER DEFAULT 0`.
- `ProjectService.mapRow` reads `actual_time_minutes` →
  `actualTimeMinutes`. `ProjectService.update` accepts and writes
  `actualTimeMinutes` (maps to `actual_time_minutes`). `create` defaults to 0
  (column default handles existing rows).
- Extend the project IPC update payload types in **`electron/preload.ts`** and
  the store's `updateProject` typing to include `actualTimeMinutes`.

### 2. Store state and actions (`src/store.ts`)

New shape:

```ts
focusMode: {
  targetType: 'task' | 'project' | null;
  targetId: string | null;
  isPlaying: boolean;
  sessionStartTime: number | null;
}
```

Actions:

- `startFocusSession(taskId: string)` — kept as the task entry point; sets
  `{ targetType: 'task', targetId: taskId, isPlaying: true, sessionStartTime:
  now }`. **Flushes any currently-running session first.**
- `startProjectFocus(projectId: string)` — new; sets
  `{ targetType: 'project', targetId: projectId, ... }`. Flushes first.
- `pauseFocusSession()` / `stopFocusSession()` — branch on `targetType`:
  - `task` → add elapsed minutes to `task.actualTimeMinutes` via `updateTask`
    (existing behavior).
  - `project` → add elapsed minutes to `project.actualTimeMinutes` via
    `updateProject`.
- **Switching targets while playing flushes the current target's accrued time,
  then starts fresh on the new target.** (Factor the "flush accrued minutes"
  logic into a shared helper used by pause/stop/switch.)
- Selectors/helpers `isTaskFocused(id)` and `isProjectFocused(id)` to keep
  consumer call sites clean.

### 3. Overlay (`src/components/focus/FocusModeOverlay.tsx`)

- Resolve the target generically into `{ title, accumulatedMinutes,
  plannedMinutes }`:
  - task → `task.title`, `task.actualTimeMinutes`, `task.durationMinutes`.
  - project → `emoji + name`, `project.actualTimeMinutes`, `0` (no planned).
- Elapsed seconds = `accumulatedMinutes*60 + currentSessionMs/1000` (unchanged
  formula, generic source).
- Progress bar already renders only when `plannedMinutes > 0`, so project mode
  shows no bar automatically.
- **Hide the Complete (✓ `markDone`) button in project mode**; keep
  Pause/Resume + Stop. `markDone` operates on a task, so in project mode the
  `focus:done` IPC handler calls `stopFocusSession()` only (no `markDone`).
- **Project switcher**: a small dropdown trigger in the overlay listing all
  projects. Selecting one calls `startProjectFocus(id)` (which flushes + switches
  the focus target). This satisfies "change the focused project from anywhere."
- Update the existing IPC handlers (`focus:togglePlayPause`, `focus:resyncWidget`)
  to read the unified target instead of `activeTaskId`.

### 4. Launch points

- **`ProjectView.tsx` header**: add a "Focus" play button next to the project
  title → `startProjectFocus(project.id)`. Reflect active state (pulsing /
  filled icon) when `isProjectFocused(project.id)`. Display the project's
  accumulated focus time (e.g. `2h 15m focused`) in the header as feedback.
- The existing per-task "start timer" buttons (TaskItem / ProjectView task rows
  / WeeklyBoardView) are unchanged in behavior; only their
  `focusMode.activeTaskId === task.id` checks become `isTaskFocused(task.id)`.
- The overlay project switcher (section 3).

### 5. Electron widget + tray (`electron/main.ts`, overlay sender)

- The overlay's `sendWidgetState` / `updateTray` calls pass the resolved generic
  title (project name, emoji-prefixed, in project mode), `plannedMinutes: 0`,
  and `accumulatedMinutes = project.actualTimeMinutes`. The widget/tray already
  handle a generic title and a no-planned (`0`) value.
- The tray/widget **"Done" action is hidden / no-op in project mode**. Pass a
  `canComplete` boolean in the `updateTray` payload; omit the "Done" tray menu
  item and (optionally) the widget "Done" button when false.

### 6. Edge cases

- **Deleting the focused project** (or the focused task): stop the session and
  clear `focusMode` so no orphaned `isPlaying` remains. Add a guard in
  `deleteProject` (and confirm the existing task-delete path) that stops focus if
  it targets the deleted entity. The overlay already returns `null` when the
  target can't be resolved, but state must also be cleared.
- **Target switch** always flushes accrued time before switching.
- **No persisted-state migration**: `focusMode` resets on launch; only
  `actualTimeMinutes` (task and project) persists in the DB.

## Affected files

| File | Change |
|------|--------|
| `electron/database.ts` | New migration: add `projects.actual_time_minutes` |
| `electron/projectService.ts` | `Project` type + `mapRow`/`update` carry `actualTimeMinutes` |
| `electron/preload.ts` | Project update payload type includes `actualTimeMinutes` |
| `electron/main.ts` | Tray "Done" hidden in project mode (`canComplete` flag) |
| `src/types.ts` | `Project.actualTimeMinutes?`; `focusMode` target shape |
| `src/store.ts` | Unified `focusMode`, `startProjectFocus`, branch flush logic, helpers, delete guard |
| `src/components/focus/FocusModeOverlay.tsx` | Generic target resolution, hide Complete, project switcher, IPC reads |
| `src/components/ProjectView.tsx` | Header Focus button + accumulated time; `isTaskFocused` |
| `src/components/TaskItem.tsx` | `isTaskFocused(task.id)` |
| `src/components/WeeklyBoardView.tsx` | `isTaskFocused(task.id)` |

## Testing / verification (manual — no test framework configured)

- Start a project focus from the ProjectView header → overlay shows emoji+name,
  counts up, no progress bar, no Complete button.
- Pause/Stop → project header accumulated time increases by the session length;
  value persists across app restart (DB column).
- Switch projects via the overlay picker mid-session → current project's time is
  flushed, new project starts at its own accumulated total.
- Switch from a task session to a project session (and back) → each target's
  `actualTimeMinutes` is credited correctly; only one session ever runs.
- Always-on-top widget and tray show the project name and no "Done" action.
- Delete the focused project → session stops cleanly, overlay disappears.
- Existing task focus (TaskItem / board / project rows) behaves exactly as before.
