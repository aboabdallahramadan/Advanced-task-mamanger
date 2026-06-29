# Group "Everything else" by project (planning ritual)

**Date:** 2026-06-29
**Status:** Approved (design) → ready for implementation plan
**Surfaces:** Web, Desktop, Android

## Problem

In the daily planning ritual, the Choose / Pick-Today step surfaces an **"Everything else"**
pool: actionable tasks that live outside Inbox/Backlog/carry-over (planned for other days,
scheduled, or undated) that can still be pulled into the day being planned. Today this pool is a
single flat list. When a user has many such tasks across several projects, the list is hard to
scan. The user wants these tasks **grouped by project**.

## Goal

Inside the **"Everything else" section only**, split the flat task list into one group per project.

- Each group header shows the project **color dot + name**.
- Groups are ordered by the user's **project sort order** (the same order as the sidebar / project
  list).
- Tasks with **no project** (null `projectId`, or a `projectId` whose project was deleted) collect
  into a single **"No Project"** group rendered **last**.
- Task order **within** a group is preserved (current pool order).
- The project headers **replace** the single "Everything else" label (chosen layout).
- Even a single non-empty project shows its header (no special-casing for "only one group").

## Non-goals / out of scope

- No change to the Inbox & Backlog group, carry-over, Reflect, or Timebox sections.
- No change to which tasks qualify as "Everything else" (the existing selector logic is unchanged).
- No backend, database, or sync change — this is pure presentation.
- No new setting or toggle; grouping is always on for this section.

## Affected surfaces & code

### Web + Desktop — shared `packages/app`

Both `apps/desktop` and `apps/web` render the same shared `PlanningCanvas`, so one change covers
both. The store's `projects` array is already maintained in `order` order
(`LocalDataClient` sorts on read; reorder uses `arrayMove`), so iterating `projects` yields the
correct group order directly.

1. **New pure helper** `packages/app/src/lib/groupByProject.ts`

   ```ts
   export interface TaskProjectGroup {
     projectId: string | null;   // null = "No Project" bucket
     name: string;               // project name, or "No Project"
     color: string | null;       // project color, or null for "No Project"
     tasks: Task[];
   }

   // Order: follows the `projects` array order (already order-sorted). The "No Project"
   // bucket (tasks with null projectId OR a projectId not present in `projects`) is appended
   // last, and only when it has tasks. Within-group task order is preserved (input order).
   export function groupByProject(tasks: Task[], projects: Project[]): TaskProjectGroup[];
   ```

   Implementation notes:
   - Build a `Set`/`Map` of known project ids from `projects`.
   - Bucket each task by `projectId` when known, else into the "No Project" bucket.
   - Emit groups by walking `projects` in order, skipping projects with zero matching tasks,
     then append the "No Project" bucket last if non-empty.

2. **`packages/app/src/components/PlanningCanvas.tsx`**
   - Replace the `everythingElse.length > 0` branch that renders a single
     `<GroupLabel>Everything else</GroupLabel>` + flat `everythingElse.map(...)`.
   - New rendering: `groupByProject(everythingElse, projects).map(group => ...)` — for each group
     emit a `ProjectGroupLabel` (color dot + name) followed by the group's `TaskItem`s.
   - `projects` is pulled from the store (already imported pattern via `useStore`).
   - Add a small local `ProjectGroupLabel({ name, color })` presentational component, styled to
     match the existing `GroupLabel` (uppercase tracked, `text-2xs`, same spacing) but prefixed
     with an inline color dot. The "No Project" group renders the label with **no** dot.

3. **Test** `packages/app/src/__tests__/groupByProject.test.ts` (vitest), mirroring the existing
   `otherPlannableTasks.test.ts` factory style. Cases:
   - Groups ordered by project order; "No Project" last.
   - Tasks with null `projectId` and tasks whose project id is absent from `projects` both land in
     "No Project".
   - Empty projects produce no group.
   - Within-group task order preserved.
   - Empty input → empty array.

### Android — `android/app`

1. **`PlanningUiState.kt`**
   - Add `data class PlanProjectGroupUi(val projectId: String?, val projectName: String?,
     val projectColor: Long?, val items: List<PlanItemUi>)`.
   - Change `everythingElse: List<PlanItemUi>` → `everythingElse: List<PlanProjectGroupUi>`.
   - Update the `PlanItemUi` doc comment / `PlanningUiState` comment to reflect the grouped shape.

2. **`PlanningViewModel.kt` (`project(...)`)**
   - After computing the flat `everythingElse` task list, group it: bucket by `projectId`, order
     groups by project `order` (sort `projects` by `order` or rely on DAO ordering — verify in
     implementation), append a "No Project" bucket last when non-empty. Each item keeps its
     `hint = hintFor(it)`.
   - Build `List<PlanProjectGroupUi>`; `projectName`/`projectColor` come from the same `byId`
     lookup already used (`parseProjectColor`). "No Project" group: `projectName = null`,
     `projectColor = null` (the row header renders dot-less).

3. **`PickTodayStep.kt`**
   - Replace the single "Everything else" `SectionLabel` + flat `items(state.everythingElse)` with
     an iteration over the groups: for each group emit a header row (`ProjectDot(group.projectColor)`
     + group name; "No Project" / null name → label "No Project" with no dot) followed by the
     group's `PlanRow`s.
   - Keep `key`s stable and unique (e.g. `"else_${projectId ?: "none"}_${item.id}"`).
   - Update the empty-state guard (`state.everythingElse.isEmpty()` still works since an empty pool
     yields an empty group list).
   - Update the `@Preview` sample data to the grouped shape.

4. **`PlanningViewModelTest.kt`**
   - Update the two assertions that read `s.everythingElse.map { it.id }` /
     `.single { it.id == ... }` to read through `.flatMap { it.items }` (or assert on a specific
     group's `items`).
   - Add an assertion: tasks are grouped by project and groups are in project order with
     "No Project" last.

## Data flow (unchanged except shape)

`Tasks (Room / store) → existing "everything else" selector → groupByProject (new) → grouped UI`.
The selector that decides *which* tasks qualify is untouched on both platforms; grouping is a pure
post-processing transform applied just before render.

## Testing strategy

- **Web/desktop:** unit-test the pure `groupByProject` helper (vitest). Component wiring is a
  thin map and is covered by manual verification (run the app, open planning, confirm grouped
  headers + ordering + "No Project" last).
- **Android:** unit-test the grouping in `PlanningViewModelTest` (Turbine, as today). Visual
  confirmation via the Compose `@Preview`.

## Risks / edge cases

- **Deleted project:** a task referencing a now-deleted project must fall into "No Project", not
  vanish or crash. Covered explicitly by the web helper (id-not-in-projects → No Project) and the
  Android `byId` lookup (null → No Project group).
- **Project order source:** web `projects` is pre-sorted; Android must sort by `order` (or confirm
  DAO ordering) so the group sequence matches the sidebar.
- **Single group:** still renders its project header (no flat-list fallback) for consistency.
