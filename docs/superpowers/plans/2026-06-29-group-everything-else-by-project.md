# Group "Everything else" by project — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In the daily planning ritual's Choose / Pick-Today step, split the flat "Everything else" task pool into one group per project, ordered by project sort order with a "No Project" group last.

**Architecture:** Pure presentation change. Web + Desktop share `packages/app`, so a new pure helper (`groupByProject`) plus a render change in `PlanningCanvas` covers both. Android mirrors the behavior in `PlanningViewModel` (state-shape change) + `PickTodayStep` (render). No backend, DB, or sync change. The selector deciding *which* tasks are "Everything else" is untouched; grouping is applied just before render.

**Tech Stack:** TypeScript + React + Zustand + Tailwind + vitest (web/desktop); Kotlin + Jetpack Compose + Hilt + JUnit/Turbine (Android).

## Global Constraints

- **Group order:** follow the user's project sort order. Web: the store's `projects` array is already `order`-sorted. Android: `ProjectDao.observeAll()` already returns `ORDER BY rank IS NULL, rank`, so `projectRepo.observeAll()` arrives pre-sorted — preserve that order, do not re-sort.
- **No Project group:** tasks with `projectId == null` OR a `projectId` whose project is absent from the projects list (deleted project) collect into a single group labeled **"No Project"**, rendered **last**, and only when it has tasks.
- **Headers replace the label:** the single "Everything else" label is removed; each project becomes its own header (color dot + name). "No Project" renders dot-less.
- **Within-group order:** preserve the input task order inside each group.
- **Scope:** only the "Everything else" section changes. Inbox & Backlog, carry-over, Reflect, and Timebox sections are untouched.
- **Formatting:** Prettier — 100 char width, single quotes, trailing commas, 2-space indent (TS). Match surrounding Kotlin style.

---

## File Structure

| File | Responsibility | Change |
|------|----------------|--------|
| `packages/app/src/lib/groupByProject.ts` | Pure: bucket tasks into ordered project groups | Create |
| `packages/app/src/__tests__/groupByProject.test.ts` | Unit tests for the helper | Create |
| `packages/app/src/components/PlanningCanvas.tsx` | Render groups in the Choose column; `ProjectGroupLabel` | Modify |
| `android/.../ui/planning/PlanningUiState.kt` | Add `PlanProjectGroupUi`; change `everythingElse` type | Modify |
| `android/.../ui/planning/PlanningViewModel.kt` | Build grouped `everythingElse` | Modify |
| `android/.../ui/planning/steps/PickTodayStep.kt` | Render per-project headers + rows; preview | Modify |
| `android/.../test/.../planning/PlanningViewModelTest.kt` | Update 2 tests + add grouping test + helper param | Modify |

---

## Task 1: `groupByProject` helper (web + desktop)

**Files:**
- Create: `packages/app/src/lib/groupByProject.ts`
- Test: `packages/app/src/__tests__/groupByProject.test.ts`

**Interfaces:**
- Consumes: `Task`, `Project` from `packages/app/src/types.ts`.
- Produces: `groupByProject(tasks: Task[], projects: Project[]): TaskProjectGroup[]` and `interface TaskProjectGroup { projectId: string | null; name: string; color: string | null; tasks: Task[] }`. Consumed by Task 2.

- [ ] **Step 1: Write the failing test**

Create `packages/app/src/__tests__/groupByProject.test.ts`:

```ts
import { describe, it, expect } from 'vitest';
import { groupByProject } from '../lib/groupByProject';
import type { Task, Project } from '../types';

// Minimal factories (mirror otherPlannableTasks.test.ts).
function makeTask(overrides: Partial<Task>): Task {
  return {
    id: 't1', title: 'Test', notes: '', projectId: null,
    labels: [], source: 'manual',
    status: 'planned', plannedDate: null, scheduledStart: null, scheduledEnd: null,
    durationMinutes: 30, priority: null, reminderMinutes: null, subtasks: [],
    order: 0, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null,
    createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z', changeSeq: 0,
    ...overrides,
  };
}

function makeProject(overrides: Partial<Project>): Project {
  return {
    id: 'p1', name: 'Project', color: '#6ea8fe', emoji: '', order: 0,
    createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

describe('groupByProject', () => {
  const projWork = makeProject({ id: 'work', name: 'Work', color: '#111111', order: 0 });
  const projHealth = makeProject({ id: 'health', name: 'Health', color: '#222222', order: 1 });
  const projEmpty = makeProject({ id: 'empty', name: 'Empty', color: '#333333', order: 2 });

  it('orders groups by project order and puts No Project last', () => {
    const tasks = [
      makeTask({ id: 'h1', projectId: 'health' }),
      makeTask({ id: 'w1', projectId: 'work' }),
      makeTask({ id: 'n1', projectId: null }),
    ];
    const groups = groupByProject(tasks, [projWork, projHealth]);
    expect(groups.map((g) => g.name)).toEqual(['Work', 'Health', 'No Project']);
    expect(groups.map((g) => g.projectId)).toEqual(['work', 'health', null]);
    expect(groups[0].color).toBe('#111111');
    expect(groups[2].color).toBeNull();
  });

  it('routes null and unknown/deleted projectId into No Project, in input order', () => {
    const tasks = [
      makeTask({ id: 'a', projectId: null }),
      makeTask({ id: 'b', projectId: 'deleted-id' }),
    ];
    const groups = groupByProject(tasks, [projWork]);
    expect(groups).toHaveLength(1);
    expect(groups[0].projectId).toBeNull();
    expect(groups[0].tasks.map((t) => t.id)).toEqual(['a', 'b']);
  });

  it('skips projects with no matching tasks', () => {
    const tasks = [makeTask({ id: 'w1', projectId: 'work' })];
    const groups = groupByProject(tasks, [projWork, projEmpty]);
    expect(groups.map((g) => g.name)).toEqual(['Work']);
  });

  it('preserves task order within a group', () => {
    const tasks = [
      makeTask({ id: 'w2', projectId: 'work' }),
      makeTask({ id: 'w1', projectId: 'work' }),
      makeTask({ id: 'w3', projectId: 'work' }),
    ];
    const groups = groupByProject(tasks, [projWork]);
    expect(groups[0].tasks.map((t) => t.id)).toEqual(['w2', 'w1', 'w3']);
  });

  it('omits the No Project group when every task has a known project', () => {
    const tasks = [makeTask({ id: 'w1', projectId: 'work' })];
    const groups = groupByProject(tasks, [projWork]);
    expect(groups.some((g) => g.projectId === null)).toBe(false);
  });

  it('returns empty array for empty input', () => {
    expect(groupByProject([], [projWork])).toEqual([]);
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd packages/app && npx vitest run src/__tests__/groupByProject.test.ts`
Expected: FAIL — cannot resolve `../lib/groupByProject` (module not found).

- [ ] **Step 3: Write minimal implementation**

Create `packages/app/src/lib/groupByProject.ts`:

```ts
import { Task, Project } from '../types';

export interface TaskProjectGroup {
  projectId: string | null; // null = the "No Project" bucket
  name: string; // project name, or "No Project"
  color: string | null; // project color, or null for "No Project"
  tasks: Task[];
}

/**
 * Bucket `tasks` by project for display in the planning "Everything else" section.
 *
 * Group order follows the `projects` array (already maintained in the user's project sort order);
 * projects with no matching task are skipped. Tasks with no project — null `projectId`, or a
 * `projectId` whose project is absent from `projects` (e.g. deleted) — collect into a single
 * "No Project" group appended last, only when it has tasks. Task order within each group is
 * preserved (input order).
 */
export function groupByProject(tasks: Task[], projects: Project[]): TaskProjectGroup[] {
  const known = new Set(projects.map((p) => p.id));
  const byProject = new Map<string, Task[]>();
  const noProject: Task[] = [];

  for (const task of tasks) {
    if (task.projectId && known.has(task.projectId)) {
      const bucket = byProject.get(task.projectId);
      if (bucket) bucket.push(task);
      else byProject.set(task.projectId, [task]);
    } else {
      noProject.push(task);
    }
  }

  const groups: TaskProjectGroup[] = [];
  for (const p of projects) {
    const bucket = byProject.get(p.id);
    if (bucket && bucket.length > 0) {
      groups.push({ projectId: p.id, name: p.name, color: p.color, tasks: bucket });
    }
  }
  if (noProject.length > 0) {
    groups.push({ projectId: null, name: 'No Project', color: null, tasks: noProject });
  }
  return groups;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd packages/app && npx vitest run src/__tests__/groupByProject.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add packages/app/src/lib/groupByProject.ts packages/app/src/__tests__/groupByProject.test.ts
git commit -m "feat(planning): groupByProject helper for Everything-else grouping

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Render project groups in `PlanningCanvas` (web + desktop)

**Files:**
- Modify: `packages/app/src/components/PlanningCanvas.tsx`

**Interfaces:**
- Consumes: `groupByProject` + `TaskProjectGroup` from Task 1; `projects` from the Zustand store; existing `everythingElse` flat array (kept as-is — grouping happens at render).
- Produces: nothing consumed downstream.

- [ ] **Step 1: Add the import**

In `packages/app/src/components/PlanningCanvas.tsx`, add after the existing relative imports (e.g. below the `import { capacityStatus } ...` line, line 5):

```ts
import { groupByProject } from '../../lib/groupByProject';
```

- [ ] **Step 2: Pull `projects` from the store**

In the `useStore()` destructure (currently lines 30–43), add `projects,` (place it right after `backlogTasks,`):

```ts
  const {
    planningFlow,
    closePlanningFlow,
    setPlanningPhase,
    commitDay,
    leftoverTasks,
    inboxTasks,
    backlogTasks,
    projects,
    otherPlannableTasks,
    plannedForDate,
    workStartHour,
    workEndHour,
    setSelectedDate,
  } = useStore();
```

- [ ] **Step 3: Replace the flat "Everything else" render with grouped render**

Replace this block (currently lines 138–145):

```tsx
                {everythingElse.length > 0 && (
                  <>
                    <GroupLabel>Everything else</GroupLabel>
                    {everythingElse.map((t) => (
                      <TaskItem key={t.id} task={t} />
                    ))}
                  </>
                )}
```

with:

```tsx
                {everythingElse.length > 0 &&
                  groupByProject(everythingElse, projects).map((group) => (
                    <React.Fragment key={group.projectId ?? '__none__'}>
                      <ProjectGroupLabel name={group.name} color={group.color} />
                      {group.tasks.map((t) => (
                        <TaskItem key={t.id} task={t} />
                      ))}
                    </React.Fragment>
                  ))}
```

- [ ] **Step 4: Add the `ProjectGroupLabel` component**

At the end of the file, directly after the existing `GroupLabel` function (currently ends at line 295), add:

```tsx
function ProjectGroupLabel({ name, color }: { name: string; color: string | null }) {
  return (
    <div className="flex items-center gap-1.5 text-2xs uppercase tracking-wide text-surface-500 font-medium pt-2 pb-0.5 first:pt-0">
      {color && (
        <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: color }} />
      )}
      <span className="truncate">{name}</span>
    </div>
  );
}
```

- [ ] **Step 5: Typecheck**

Run: `cd packages/app && npm run typecheck`
Expected: PASS (no errors). This typechecks `PlanningCanvas.tsx` against the new helper and `projects`.

- [ ] **Step 6: Run the package test suite (regression)**

Run: `cd packages/app && npx vitest run`
Expected: PASS (all existing tests + Task 1's new tests).

- [ ] **Step 7: Manual verification**

Run the web app: `cd apps/web && npm run dev`, open the planning ritual, advance to the **Choose** step. Confirm: the "Everything else" pool shows a project header (color dot + uppercase name) per project in sidebar order, "No Project" last, "Inbox & Backlog" group unchanged above it. (Desktop uses the same shared component; no separate check required.)

- [ ] **Step 8: Commit**

```bash
git add packages/app/src/components/PlanningCanvas.tsx
git commit -m "feat(planning): group Everything-else pool by project (web/desktop)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Group "Everything else" by project (Android)

**Files:**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningUiState.kt`
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningViewModel.kt`
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/planning/steps/PickTodayStep.kt`
- Test: `android/app/src/test/java/net/qmindtech/tmap/ui/planning/PlanningViewModelTest.kt`

**Interfaces:**
- Produces: `data class PlanProjectGroupUi(projectId: String?, projectName: String?, projectColor: Long?, items: List<PlanItemUi>)`; `PlanningUiState.everythingElse: List<PlanProjectGroupUi>`.
- Consumes: existing `PlanItemUi`, `parseProjectColor`, `ProjectDot`, `SectionLabel`.

> **Note on TDD order:** This is one cohesive task because changing the `everythingElse` type cascades through the VM, the Compose step, and the test — the Gradle test task compiles the whole `main` source set, so the unit test only goes green once all of `PlanningUiState` + `PlanningViewModel` + `PickTodayStep` compile against the new shape. Write the test first (red = compile failure), then make all three source files compile, then go green.

- [ ] **Step 1: Update the test to expect the grouped shape (write the failing test)**

In `PlanningViewModelTest.kt`:

(a) Add the `projects` parameter to the `vm(...)` helper (currently lines 110–126). Replace the helper body with:

```kotlin
  private fun vm(
    yesterdayTasks: List<TaskEntity> = emptyList(),
    inbox: List<TaskEntity> = emptyList(),
    backlog: List<TaskEntity> = emptyList(),
    other: List<TaskEntity> = emptyList(),
    settings: List<net.qmindtech.tmap.data.local.entities.SettingEntity> = emptyList(),
    projects: List<net.qmindtech.tmap.data.local.entities.ProjectEntity> =
      listOf(fakeProject(id = "p1", name = "Work", color = "#6ea8fe")),
  ): Triple<PlanningViewModel, FakeTaskRepo, FakeDailyPlanRepo> {
    // The VM derives every section (carry-over/inbox/backlog/everything-else) from observeAll(),
    // so seed the whole task pool through a single flow here. [other] = the "Everything else" pool
    // (Planned-elsewhere / Scheduled / undated tasks).
    val task = FakeTaskRepo(all = MutableStateFlow(yesterdayTasks + inbox + backlog + other))
    val projectRepo = FakeProjectRepo().apply { setAll(projects) }
    val daily = FakeDailyPlanRepo()
    val set = FakeSettingsRepo().apply { set(settings) }
    val vm = PlanningViewModel(task, projectRepo, daily, set, FixedClock(Instant.parse("2026-06-21T06:00:00Z")))
    return Triple(vm, task, daily)
  }
```

(b) In `pickToday_everythingElse_lists_planned_and_scheduled_elsewhere_with_hints` (currently lines 230–240), replace the assertion block inside `vm.uiState.test { ... }` with:

```kotlin
    vm.uiState.test {
      val s = expectMostRecentItem()
      val elseItems = s.everythingElse.flatMap { it.items }
      // Only the two elsewhere-living actionable tasks surface, in pool order.
      assertEquals(listOf("pf", "sc"), elseItems.map { it.id })
      // Inbox/backlog/carry-over tasks are NOT duplicated into Everything else.
      assertEquals(true, elseItems.none { it.id in listOf("u", "i", "b") })
      // Locator hints.
      assertEquals("Planned · Jun 25", elseItems.single { it.id == "pf" }.hint)
      assertEquals("Scheduled · Jun 23", elseItems.single { it.id == "sc" }.hint)
      cancelAndIgnoreRemainingEvents()
    }
```

(c) In `toggleAdd_everythingElse_counts_capacity_and_commit_replans_to_today` (currently lines 248–266), replace the two `s.everythingElse...` assertions:

Change `assertEquals(listOf("pf"), s.everythingElse.map { it.id })` → 
```kotlin
      assertEquals(listOf("pf"), s.everythingElse.flatMap { it.items }.map { it.id })
```
and `assertEquals(true, s.everythingElse.single { it.id == "pf" }.added)` →
```kotlin
      assertEquals(true, s.everythingElse.flatMap { it.items }.single { it.id == "pf" }.added)
```

(d) Add a new grouping test (place it right after the `pickToday_everythingElse_lists_...` test):

```kotlin
  @Test fun pickToday_everythingElse_groups_by_project_in_order_with_no_project_last() = runTest {
    val date = LocalDate.of(2026, 6, 25)
    val (vm, _, _) = vm(
      other = listOf(
        fakeTask(id = "h1", status = TaskStatus.Planned, plannedDate = date, projectId = "p2"),
        fakeTask(id = "w1", status = TaskStatus.Planned, plannedDate = date, projectId = "p1"),
        fakeTask(id = "n1", status = TaskStatus.Planned, plannedDate = date, projectId = null),
        fakeTask(id = "del", status = TaskStatus.Planned, plannedDate = date, projectId = "gone"),
      ),
      projects = listOf(
        fakeProject(id = "p1", name = "Work", color = "#6ea8fe"),
        fakeProject(id = "p2", name = "Health", color = "#f0a868"),
      ),
    )
    vm.uiState.test {
      val s = expectMostRecentItem()
      // Groups follow project order; "No Project" is last.
      assertEquals(listOf("Work", "Health", "No Project"), s.everythingElse.map { it.projectName ?: "No Project" })
      assertEquals(listOf("p1", "p2", null), s.everythingElse.map { it.projectId })
      // Each bucket holds its task; null + deleted-project tasks fall into "No Project" in pool order.
      assertEquals(listOf("w1"), s.everythingElse[0].items.map { it.id })
      assertEquals(listOf("h1"), s.everythingElse[1].items.map { it.id })
      assertEquals(listOf("n1", "del"), s.everythingElse[2].items.map { it.id })
      cancelAndIgnoreRemainingEvents()
    }
  }
```

- [ ] **Step 2: Run the test to verify it fails (compile failure = red)**

Run: `cd android && ./gradlew testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningViewModelTest"`
Expected: FAIL — compilation error (unresolved `it.items` / `PlanProjectGroupUi`; `everythingElse` is still `List<PlanItemUi>`).

- [ ] **Step 3: Update `PlanningUiState.kt` — add the group type, change the field**

In `PlanningUiState.kt`, add after the `PlanItemUi` data class (after line 47):

```kotlin
/**
 * A project bucket within PickToday's "Everything else" list. [projectId]/[projectName]/
 * [projectColor] are null for the trailing "No Project" group.
 */
data class PlanProjectGroupUi(
    val projectId: String?,
    val projectName: String?,
    val projectColor: Long?,
    val items: List<PlanItemUi>,
)
```

Then change the `everythingElse` field (currently line 63) from:

```kotlin
    val everythingElse: List<PlanItemUi> = emptyList(),
```

to:

```kotlin
    val everythingElse: List<PlanProjectGroupUi> = emptyList(),
```

- [ ] **Step 4: Update `PlanningViewModel.kt` — build grouped `everythingElse`**

In `PlanningViewModel.kt`, the `project(...)` function currently computes `val everythingElse = allTasks.filter { ... }` (lines 90–95) and then returns `everythingElse = everythingElse.map { item(it, hint = hintFor(it)) }` (line 109).

(a) Immediately after the `val everythingElse = allTasks.filter { ... }` block (after line 95), add the grouping:

```kotlin
    // Group "Everything else" by project: project order (projects arrive ordered by rank from the
    // DAO), "No Project" last and only when non-empty. Item order within a group is preserved.
    val elseByProject = everythingElse.groupBy { it.projectId }
    val everythingElseGroups = buildList {
      for (p in projects) {
        val bucket = elseByProject[p.id]
        if (!bucket.isNullOrEmpty()) {
          add(
            PlanProjectGroupUi(
              projectId = p.id,
              projectName = p.name,
              projectColor = parseProjectColor(p.color),
              items = bucket.map { item(it, hint = hintFor(it)) },
            ),
          )
        }
      }
      val noProject = everythingElse.filter { it.projectId == null || byId[it.projectId] == null }
      if (noProject.isNotEmpty()) {
        add(
          PlanProjectGroupUi(
            projectId = null,
            projectName = null,
            projectColor = null,
            items = noProject.map { item(it, hint = hintFor(it)) },
          ),
        )
      }
    }
```

(b) Change the return assignment (line 109) from:

```kotlin
      everythingElse = everythingElse.map { item(it, hint = hintFor(it)) },
```

to:

```kotlin
      everythingElse = everythingElseGroups,
```

- [ ] **Step 5: Update `PickTodayStep.kt` — render per-project headers + rows, and the preview**

(a) Replace the "Everything else" block (currently lines 106–118):

```kotlin
        if (state.everythingElse.isNotEmpty()) {
            item {
                SectionLabel(
                    text = "Everything else",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(state.everythingElse, key = { "else_${it.id}" }) { item ->
                PlanRow(item = item, onToggleAdd = { onToggleAdd(item.id) })
            }
        }
```

with:

```kotlin
        state.everythingElse.forEach { group ->
            val groupKey = group.projectId ?: "none"
            item(key = "else_header_$groupKey") {
                ProjectGroupLabel(
                    name = group.projectName ?: "No Project",
                    colorArgb = group.projectColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(group.items, key = { "else_${groupKey}_${it.id}" }) { item ->
                PlanRow(item = item, onToggleAdd = { onToggleAdd(item.id) })
            }
        }
```

(b) Add the `ProjectGroupLabel` composable directly after the `PlanRow` function (after line 223, before the `@Preview`):

```kotlin
/**
 * Section header for an "Everything else" project group: an optional [colorArgb] dot followed by the
 * uppercase project [name] (via [SectionLabel]). The "No Project" group passes [colorArgb] = null
 * and renders dot-less.
 */
@Composable
private fun ProjectGroupLabel(name: String, colorArgb: Long?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (colorArgb != null) {
            ProjectDot(colorArgb = colorArgb)
        }
        SectionLabel(text = name)
    }
}
```

(c) Update the `@Preview` sample (currently the `everythingElse = listOf(PlanItemUi(...), PlanItemUi(...))` block, lines 258–269) to the grouped shape:

```kotlin
                everythingElse = listOf(
                    PlanProjectGroupUi(
                        projectId = "work", projectName = "Work", projectColor = 0xFF6EA8FE,
                        items = listOf(
                            PlanItemUi(
                                id = "5", title = "Prep onboarding deck",
                                projectName = "Work", projectColor = 0xFF6EA8FE,
                                durationMinutes = 30, added = false, hint = "Planned · Jun 30",
                            ),
                        ),
                    ),
                    PlanProjectGroupUi(
                        projectId = null, projectName = null, projectColor = null,
                        items = listOf(
                            PlanItemUi(
                                id = "6", title = "1:1 with Sam",
                                projectName = null, projectColor = null,
                                durationMinutes = 30, added = false, hint = "Scheduled · Jul 2",
                            ),
                        ),
                    ),
                ),
```

(All identifiers used — `Row`, `Arrangement`, `Alignment`, `ProjectDot`, `SectionLabel`, `Modifier`, `padding`, `fillMaxWidth`, `dp` — are already imported in this file; no new imports needed.)

- [ ] **Step 6: Run the test to verify it passes (green)**

Run: `cd android && ./gradlew testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningViewModelTest"`
Expected: PASS (all `PlanningViewModelTest` tests, including the new grouping test). This compiles the full `main` source set, so it also confirms `PickTodayStep.kt` and `PlanningUiState.kt` compile.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningUiState.kt \
        android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningViewModel.kt \
        android/app/src/main/java/net/qmindtech/tmap/ui/planning/steps/PickTodayStep.kt \
        android/app/src/test/java/net/qmindtech/tmap/ui/planning/PlanningViewModelTest.kt
git commit -m "feat(android): group Everything-else pool by project in planning

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Group by project in "Everything else" only → Task 1 (helper), Task 2 (web/desktop render), Task 3 (Android). ✓
- Headers replace the "Everything else" label → Task 2 Step 3, Task 3 Step 5a. ✓
- Color dot + name header; "No Project" dot-less → Task 2 Step 4, Task 3 Step 5b. ✓
- Group order = project sort order → helper iterates `projects` (Task 1); VM iterates `projects` (Task 3 Step 4); both pre-sorted (Global Constraints). ✓
- "No Project" last, only when non-empty → Task 1 impl + tests; Task 3 impl + test. ✓
- Deleted-project task → "No Project" → Task 1 test + impl (`known.has`), Task 3 impl (`byId[it.projectId] == null`) + test (`del`/`gone`). ✓
- Within-group order preserved → Task 1 test, Task 3 test. ✓
- Other sections untouched → only the everything-else branch is changed in each renderer. ✓
- No backend/sync/setting change → no such files touched. ✓

**Placeholder scan:** No TBD/TODO/"handle edge cases"; all steps contain concrete code/commands. ✓

**Type consistency:** `groupByProject` / `TaskProjectGroup { projectId, name, color, tasks }` used identically in Tasks 1–2. `PlanProjectGroupUi { projectId, projectName, projectColor, items }` defined in Task 3 Step 3 and used identically in Steps 4–5 and the test (Step 1). `ProjectGroupLabel(name, color)` (web) / `ProjectGroupLabel(name, colorArgb, modifier)` (Android) each consistent within their file. ✓
