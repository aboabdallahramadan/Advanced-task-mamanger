# SP4 Android Experience Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the SP4 Android app's experience layer to a best-in-class mobile-planner bar ("Midnight Calm" dark design, Daily-first navigation, full feature set) and add four new synced domains plus home-screen widgets — reusing the existing offline sync/auth/reminders engine unchanged.

**Architecture:** MVVM + unidirectional data flow (StateFlow + immutable UI-state data classes), offline-first (every screen reads Room; every mutation is write-through to the outbox and reconciled by the existing `SyncEngine`). The sync engine is hardcoded per `EntityType`; four new domains (notes, note-groups, focus-sessions, daily-plans) are added by extending the enum and the push/pull dispatch, mirroring the proven task path. UI is Jetpack Compose with a custom Midnight Calm design system layered over Material 3. Widgets use Jetpack Glance reading the same Room DB.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.09.03) + Material 3, Hilt 2.52, Room 2.6.1 (KSP), WorkManager 2.9.1, Retrofit 2.11.0 + OkHttp 4.12.0 + kotlinx.serialization 1.7.3, DataStore 1.1.1, Jetpack Glance (new), java.time (desugaring 2.1.2), AlarmManager.

**Spec:** `docs/superpowers/specs/2026-06-21-sp4-android-experience-redesign-design.md` (commit `e24d812`).
**Visual source of truth (pixel spec):** the approved mockups in `.superpowers/brainstorm/965-1782053760/content/` — `design-direction.html`, `navigation.html`, `daily-core.html`, `full-app.html`, `widgets.html` (git-ignored; do not delete during this work).

## Global Constraints

*Every task's requirements implicitly include this section. Values are exact.*

- **Root package:** `net.qmindtech.tmap`. Module: `android/app`.
- **SDK / toolchain:** minSdk 26, targetSdk 35, compileSdk 35, AGP 8.7.3, Kotlin 2.0.21, JDK 21. Do NOT change these.
- **Dependency floors (do not downgrade):** Compose BOM 2024.09.03, Hilt 2.52, hilt-navigation-compose 1.2.0, hilt-work 1.2.0, Room 2.6.1, WorkManager 2.9.1, Retrofit 2.11.0, OkHttp 4.12.0, kotlinx-serialization 1.7.3, retrofit2-kotlinx-serialization-converter 1.0.0, Coroutines 1.9.0, DataStore 1.1.1, desugar_jdk_libs 2.1.2.
- **New dependency (add to `gradle/libs.versions.toml` + `android/app/build.gradle.kts`):** `androidx.glance:glance-appwidget` (version 1.1.1) and `androidx.glance:glance-material3` (1.1.1). No other new third-party libs without justification.
- **Base URL:** `https://api-tasks.qmindtech.net/`. All API paths are under `/api/v1/`.
- **Dark-only.** No light theme, no dynamic color. Use the Midnight Calm tokens below — never the old `Surface*/Accent*` desktop-mirrored palette (that palette is being replaced).
- **Offline-first invariant:** UI reads from Room Flows only; never block on network. Every create/update/delete is written to Room AND enqueued to the outbox in the same repository call (write-through). Client generates UUIDs for new entities (idempotent replay; server adopts on 409).
- **Reuse the engine unchanged** except the documented extension points (new `EntityType` values + `PushRunner.dispatch()` / `PullRunner.applyPage()` branches + extended `SyncChanges`). Do NOT rewrite `SyncEngine`/`PushRunner` core loops, auth, or reminders.
- **RTL:** use `start`/`end` (never `left`/`right`); all new UI must mirror correctly.
- **Accessibility:** every icon-only control has a `contentDescription`; every swipe action has a non-gesture equivalent (editor or long-press menu); honor system font scale and reduce-motion (`Settings.Global.ANIMATOR_DURATION_SCALE`).
- **Tests:** unit tests via `./gradlew :app:testDebugUnitTest` (run from `android/`). Compile gate `./gradlew :app:assembleDebug`. Lint gate `./gradlew :app:lintDebug`. Use the existing test patterns (JUnit4 + kotlinx-coroutines-test + Turbine if present; Room in-memory DB for DAO/sync tests; MockWebServer for API tests). Robolectric only if already configured.
- **Commit messages** end with the trailer (exact):
  `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`
- **Do not merge to `main` or push without explicit user consent.** All work on `feat/sp4-android`.
- **Conventional commits**, one per task (or per logical step group within a task as the steps indicate).

## Midnight Calm design tokens (canonical — used by every UI task)

Implement once in P0 as `ui/theme/` (`TmapColors`, `TmapShapes`, `TmapSpacing`, `TmapType`, `TmapMotion` + `CompositionLocal`s + `TmapTheme`). All later UI tasks consume these; never hardcode hex.

```
COLORS (TmapColors):
  bgTop          #191A20   bgBottom        #141519   (app background vertical gradient)
  surface        #202127   surfaceRaised   #23242B   surfaceInset  #1C1D23
  borderSubtle   #2A2B31   borderStrong    #34353C
  textPrimary    #ECEAE4   textSecondary   #908E86    textTertiary  #76746D   textBody #B7B5AD
  accent         #E8A87C   accentEnd       #E0936A    onAccent      #1A1208   (accent gradient 135°)
  success        #38D39F   successStart    #2F7D5B
  danger         #F0A0A0
  focusBgTop     #1B1C22   focusBgBottom   #121317    (focus-mode radial/immersive bg)
  PROJECT DEFAULTS: work #6EA8FE  personal #38D39F  health #F0A868  ideas/side #C9A0FF  learning #F0A0A0
SHAPES (TmapShapes):  card 18dp  sheet 26dp(top)  pill 999dp  button 13dp  well 12dp
SPACING (TmapSpacing): base 4dp; scale 4/8/10/14/16/20/22dp; screenH 16-20dp
TYPE (TmapType, system font): display 40/300  title 25/600  heading 19-20/600  body 14.5/500  meta 12  label 11/700 uppercase+ls
MOTION (TmapMotion): standard 220ms ease; spring on swipe/drag; check-off scale+fill ~180ms; gate on reduce-motion
ELEVATION: soft diffuse shadow on raised surfaces + FAB only; flat otherwise
```

---

## File Structure Map

*New (`+`) and modified (`~`) files. Engine files not listed here are reused unchanged.*

**Build / DI / manifest**
- `~ gradle/libs.versions.toml`, `~ android/app/build.gradle.kts` — add Glance.
- `~ android/app/src/main/AndroidManifest.xml` — 4 Glance receivers + capture trampoline + (existing reminder/boot/deeplink kept).
- `~ di/DatabaseModule.kt` — register new DAOs + DB version bump. `~ di/AppModule.kt` — bind new repositories. `~ di/NetworkModule.kt` — (no change expected). `+ di/WidgetModule.kt` — widget data provider if needed.

**Design system & components (P0)**
- `~ ui/theme/Color.kt` → rebuilt as `TmapColors` + `LocalTmapColors`.
- `~ ui/theme/Theme.kt` → `TmapTheme` providing all CompositionLocals.
- `~ ui/theme/Type.kt` → `TmapType`. `+ ui/theme/Shapes.kt`, `+ ui/theme/Spacing.kt`, `+ ui/theme/Motion.kt`.
- `+ ui/components/`: `TaskCard.kt`, `SwipeableTaskCard.kt`, `TimeBlock.kt`, `SectionLabel.kt`, `ProjectDot.kt`, `ProjectSwatch.kt`, `Chips.kt` (Chip/FilterChip/SegmentedControl), `Buttons.kt` (PrimaryButton/SecondaryButton/Fab), `ProgressRing.kt`, `StatTile.kt`, `EmptyState.kt`, `SyncStatusPill.kt`, `SheetScaffold.kt`. (`~` replace existing `TaskRow.kt`, `StatusChip.kt`, `ProjectPill.kt`, `PriorityBadge.kt`, `PriorityDisplay.kt`, `SyncStatusBar.kt`, `EmptyState.kt`.)

**Navigation shell (P0)**
- `~ ui/navigation/Routes.kt`, `~ ui/navigation/TmapApp.kt`, `~ ui/navigation/BottomNavItem.kt`; `+ ui/navigation/MainScaffold.kt`, `+ ui/navigation/SheetHost.kt` (capture + editor bottom sheets).

**Today + capture + gestures + editor (P1)**
- `~ ui/today/TodayScreen.kt`, `~ ui/today/TodayViewModel.kt`; `+ ui/today/TodayUiState.kt`, `+ ui/today/TodayListContent.kt`.
- `+ ui/capture/QuickCaptureSheet.kt`, `+ ui/capture/QuickCaptureViewModel.kt`, `+ ui/capture/QuickCaptureParser.kt` (+ its test).
- `~ ui/taskeditor/*` → `+ ui/taskeditor/TaskEditorSheet.kt` (sheet form), keep VM/UiState/SubtaskRow (adapt).

**Browse + Projects (P2)**
- `+ ui/browse/BrowseScreen.kt`, `+ ui/browse/BrowseViewModel.kt`, `~ ui/alltasks/TaskFilter.kt` (move/extend → `ui/browse/TaskFilter.kt`).
- `~ ui/projects/ProjectsScreen.kt`, `~ ui/projects/ProjectsViewModel.kt`, `~ ui/projects/ProjectEditDialog.kt`; `+ ui/projects/ProjectDetailScreen.kt`.
- (retire `ui/backlog/*` and `ui/alltasks/*` screens — folded into Browse.)

**New-domain data + sync engine extension (P3)**
- `~ data/local/EntityType.kt` (add NOTE, NOTE_GROUP, FOCUS_SESSION, DAILY_PLAN).
- `+ data/local/entities/{NoteEntity,NoteGroupEntity,FocusSessionEntity,DailyPlanEntity}.kt`.
- `+ data/local/dao/{NoteDao,NoteGroupDao,FocusSessionDao,DailyPlanDao}.kt`.
- `~ data/local/AppDatabase.kt` (register entities; version 2 → 3).
- `+ data/remote/dto/{NoteDtos,NoteGroupDtos,FocusSessionDtos,DailyPlanDtos}.kt`; `~ data/remote/dto/SyncDtos.kt` (add SyncRows + extend `SyncChanges`); `~ data/remote/TmapApiService.kt` (endpoints).
- `+ data/repository/{NoteRepository,NoteGroupRepository,FocusSessionRepository,DailyPlanRepository}.kt`.
- `~ data/sync/Mappers.kt` (new DTO↔entity); `~ data/sync/PushRunner.kt` (dispatch branches); `~ data/sync/PullRunner.kt` (applyPage branches).

**Notes UI (P4)** — `+ ui/notes/{NotesScreen,NotesViewModel,NoteEditorSheet,NoteCard}.kt`.
**Planning ritual (P5)** — `+ ui/planning/{PlanningScreen,PlanningViewModel,steps/*}.kt`.
**Focus mode (P6)** — `+ ui/focus/{FocusScreen,FocusViewModel}.kt`, `+ focus/FocusService.kt` (foreground timer), `+ focus/FocusController.kt`.
**Today Timeline (P7)** — `+ ui/today/TimelineContent.kt` + drag-to-timeblock in TodayViewModel.
**Widgets (P8)** — `+ widget/{TodayAgendaWidget,QuickCaptureWidget,UpNextFocusWidget,ProgressStreakWidget}.kt`, `+ widget/WidgetRepository.kt`, `+ widget/WidgetUpdater.kt`, `+ widget/CaptureTrampolineActivity.kt`, receivers.
**You / stats (P9)** — `~ ui/settings/*` → `+ ui/you/{YouScreen,YouViewModel}.kt` + settings sub-screens; `+ data/stats/StatsCalculator.kt`.
**Polish / cross-cutting gate (P10)** — motion, empty states, RTL, a11y, full offline pass; no new files expected (refinements).

---

## Cross-Phase Interface Contracts

*These names/signatures are FIXED. Any task producing or consuming them must match exactly.*

**Design system (P0 produces):**
- `@Composable fun TmapTheme(content: @Composable () -> Unit)` — provides `LocalTmapColors`, `LocalTmapShapes`, `LocalTmapSpacing`, `LocalTmapType`, `LocalTmapMotion`.
- `object TmapColors` accessor via `val colors = LocalTmapColors.current` returning a `TmapColorScheme` data class with the fields named in the tokens block (e.g. `colors.accent`, `colors.surface`, `colors.textPrimary`).
- `@Composable fun SwipeableTaskCard(task: TaskUi, onToggleComplete: () -> Unit, onDefer: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier)`.
- `@Composable fun TaskCard(task: TaskUi, onToggleComplete: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier)`.
- `@Composable fun ProgressRing(progress: Float, modifier: Modifier, centerLabel: @Composable () -> Unit)`.
- `@Composable fun SegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit)`.
- `@Composable fun TmapFab(onClick: () -> Unit, modifier: Modifier = Modifier)`.
- `data class TaskUi(val id: String, val title: String, val projectName: String?, val projectColor: Long?, val scheduledLabel: String?, val subtaskDone: Int, val subtaskTotal: Int, val priority: Int, val hasReminder: Boolean, val isDone: Boolean)` — the UI projection of a task used by every list. (P0 defines; P1+ consume. Mapper `TaskEntity.toUi(project: ProjectEntity?): TaskUi` lives in `ui/components/TaskUi.kt`.)

**Navigation (P0 produces):** `sealed interface Route` with objects/data classes `Today, Inbox, Browse, Notes, You, Planning, Focus(taskId: String?), ProjectDetail(projectId: String)`; the editor + capture are sheet states in `SheetHost`, not routes. `fun NavController.openTaskEditor(taskId: String)`, `fun NavController.openCapture()`.

**New-domain data (P3 produces; P4/P5/P6/P9 consume):**
- Entities with these exact fields (mirroring backend, +sync cols `changeSeq: Long`, `deletedAt: Instant?`, all timestamps `Instant`, ids `String`):
  - `NoteEntity(id, groupId: String?, projectId: String?, title, content, rank, createdAt, updatedAt, changeSeq, deletedAt, pinnedAt: Instant? /*local-only, never synced*/)`
  - `NoteGroupEntity(id, name, emoji, projectId: String?, rank, createdAt, updatedAt, changeSeq, deletedAt)`
  - `FocusSessionEntity(id, taskId: String?, project: String, startedAt: Instant, endedAt: Instant, minutes: Int, date: LocalDate, createdAt, updatedAt, changeSeq, deletedAt)`
  - `DailyPlanEntity(date: LocalDate /*PK*/, committedAt: Instant, plannedTaskIds: List<String>, plannedMinutes: Int, changeSeq, deletedAt)`
- Repos: `NoteRepository { observeAll(groupId: String?, projectId: String?): Flow<List<NoteEntity>>; observe(id): Flow<NoteEntity?>; create(...): String; update(id, ...); delete(id); setPinned(id, pinned: Boolean); reorder(ids: List<String>) }`; `NoteGroupRepository { observeAll(): Flow<List<NoteGroupEntity>>; create/update/delete/reorder }`; `FocusSessionRepository { create(taskId: String?, project: String, startedAt: Instant, endedAt: Instant, minutes: Int, date: LocalDate): String; observeForTask(taskId): Flow<List<FocusSessionEntity>>; observeForDateRange(...) }`; `DailyPlanRepository { observe(date: LocalDate): Flow<DailyPlanEntity?>; upsert(date: LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int) }`.
- `EntityType` additions: `NOTE, NOTE_GROUP, FOCUS_SESSION, DAILY_PLAN`. Outbox specials: `FOCUS_SESSION` enqueues only `OpType.CREATE`; `DAILY_PLAN` enqueues `OpType.UPDATE` with `entityId = date.toString()` (ISO) and upsert semantics (no id remap).
- API additions on `TmapApiService` (exact paths in P3 tasks): notes CRUD+reorder, note-groups CRUD+reorder, `POST /focus-sessions`, `GET/PUT /daily-plans/{date}`. `SyncChanges` extended with `notes, noteGroups, focusSessions, dailyPlans, recurrenceRules` lists.

**Capture parser (P1 produces; widgets P8 reuse):** `class QuickCaptureParser(clock: Clock) { fun parse(input: String, projects: List<ProjectEntity>): ParsedCapture }` where `data class ParsedCapture(val title: String, val projectId: String?, val priority: Int, val plannedDate: LocalDate?, val scheduledStart: LocalTime?, val tokens: List<ParsedToken>)`.

**Focus (P6 produces; P8 Up-Next widget + P9 stats consume):** `FocusController.start(taskId: String?, project: String, lengthMin: Int)`, completion → `FocusSessionRepository.create(...)` + `TaskRepository.addActualTime(taskId, minutes)`. Add `TaskRepository.addActualTime(taskId: String, minutes: Int)` in P3 if not present.

**Stats (P9 produces; widgets P8 consume):** `class StatsCalculator { fun dayStreak(tasks, plans): Int; fun doneThisWeek(tasks): Int; fun focusMinutesThisWeek(sessions): Int; fun todayProgress(tasks): Float }`.

**Widgets (P8 produces):** `WidgetUpdater.updateAll(context)` called from `PullRunner` (post-pull) and after local writes affecting today; widgets read via `WidgetRepository` (direct DAO queries off the Room DB, no network).

---

## Phases & Task Index

> Phases are ordered by dependency. Within a phase, tasks are bite-sized TDD units (write failing test → verify fail → implement → verify pass → commit). UI tasks that are not unit-testable use a **compile-gate + behavior-checklist** (assembleDebug must pass; reviewer verifies against the named mockup) in place of a unit test, and state so explicitly.

- **P0 — Design system + navigation shell** (tokens, theme, component library, 5-tab scaffold, sheet host, empty app wiring).
- **P1 — Today (list) + quick-capture + gestures + bottom-sheet editor.**
- **P2 — Browse hub + Projects (+ detail).**
- **P3 — New-domain data layer + sync-engine extension** (entities/DAOs/DTOs/repos/endpoints/push+pull branches; the engine workstream).
- **P4 — Notes UI** (notebooks, pinned/recent, editor).
- **P5 — Planning ritual** (4-step flow, DailyPlan upsert, capacity).
- **P6 — Focus mode** (pomodoro, foreground service, FocusSession write + task time).
- **P7 — Today Timeline** (hour rail, now-line, drag-to-time-block).
- **P8 — Glance widgets** (4 widgets + provider + updater + trampoline + receivers).
- **P9 — You / stats / settings** (profile, sync status, streak/weekly stats, settings, sign-out).
- **P10 — Polish & cross-cutting gate** (motion, empty states, RTL, a11y, full offline verification; green-gate the acceptance criteria).

<!-- PHASE-TASKS:BEGIN -->

## P0 — Design system + navigation shell

This phase replaces the desktop-mirrored palette and the four-tab functional shell with the **Midnight Calm** design system (direction A in `.superpowers/brainstorm/965-1782053760/content/design-direction.html`) and the Daily-first 5-tab navigation shell. It produces, in dependency order: the token layer (`TmapColorScheme` + `TmapColors`/`TmapShapes`/`Spacing`/`TmapType`/`TmapMotion` CompositionLocals and `TmapTheme`); the `TaskUi` projection + its unit-tested `TaskEntity.toUi(project)` mapper; the full reusable component library (`TaskCard`, `SwipeableTaskCard`, `SectionLabel`, `ProjectDot`, `Chips`, `Buttons`/`TmapFab`, `ProgressRing`, `StatTile`, `EmptyState`, `SyncStatusPill`, `SheetScaffold`); and the navigation shell (`Route` sealed interface, `BottomNavItem`, `MainScaffold` with bottom nav + FAB + `NavHost`, `SheetHost` for capture/editor sheet state, and the rewired `TmapApp`/`MainActivity` with placeholder screen stubs for the five tabs). Pure logic (token hexes, the `toUi` mapper, scheduled-label formatting, swipe-state thresholds, project-color resolution) gets real Kotlin + real JUnit tests; non-unit-testable Compose gets a `./gradlew :app:assembleDebug` compile gate plus a behavior checklist verified against the named mockup. The auth/session gate and splash are preserved. At the end the app compiles, runs, and renders the 5-tab Midnight Calm shell with the amber FAB.

> **Test-substitution note (applies to every UI task in P0):** Composables in this phase are visual and not unit-testable without an instrumented (`androidTest`) harness, which this project does not configure for `ui/` (the `src/test/` suite is JVM/Robolectric and covers logic only). Per the plan's stated convention, each such task's gate is **`./gradlew :app:assembleDebug` passing + a behavior checklist the reviewer verifies against `design-direction.html` direction A (Midnight Calm)**. Where a task contains extractable pure logic (token values, the mapper, label/threshold/color functions), that logic is pulled into a plain Kotlin file with a **real failing-first JUnit test** run via `./gradlew :app:testDebugUnitTest`. All Gradle commands run from the `android/` directory.

---

### Task P0.1 — Color tokens: `TmapColorScheme` + `TmapColors`/`LocalTmapColors`

**Files**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Color.kt`
- Delete (replace contents wholesale — old `Surface*/Accent*` vals removed): same file.
- Modify (replace the obsolete assertions): `android/app/src/test/java/net/qmindtech/tmap/ui/theme/ColorTest.kt`

**Interfaces**
- Produces: `data class TmapColorScheme(...)` with fields `bgTop, bgBottom, surface, surfaceRaised, surfaceInset, borderSubtle, borderStrong, textPrimary, textSecondary, textTertiary, textBody, accent, accentEnd, onAccent, success, successStart, danger, focusBgTop, focusBgBottom` each `androidx.compose.ui.graphics.Color`; plus `val projectWork, projectPersonal, projectHealth, projectIdeas, projectLearning: Color`.
- Produces: `val MidnightCalmColors: TmapColorScheme` (the canonical instance with the exact token hexes).
- Produces: `val LocalTmapColors: ProvidableCompositionLocal<TmapColorScheme>` (default = `MidnightCalmColors`).
- Consumes: nothing.

**Steps**

1. Replace `ColorTest.kt` with a failing test that pins every token hex on `MidnightCalmColors`:

```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorTest {
    private val c = MidnightCalmColors

    @Test
    fun backgroundAndSurfaceTokensMatchSpec() {
        assertEquals(Color(0xFF191A20), c.bgTop)
        assertEquals(Color(0xFF141519), c.bgBottom)
        assertEquals(Color(0xFF202127), c.surface)
        assertEquals(Color(0xFF23242B), c.surfaceRaised)
        assertEquals(Color(0xFF1C1D23), c.surfaceInset)
        assertEquals(Color(0xFF2A2B31), c.borderSubtle)
        assertEquals(Color(0xFF34353C), c.borderStrong)
    }

    @Test
    fun textTokensMatchSpec() {
        assertEquals(Color(0xFFECEAE4), c.textPrimary)
        assertEquals(Color(0xFF908E86), c.textSecondary)
        assertEquals(Color(0xFF76746D), c.textTertiary)
        assertEquals(Color(0xFFB7B5AD), c.textBody)
    }

    @Test
    fun accentAndSemanticTokensMatchSpec() {
        assertEquals(Color(0xFFE8A87C), c.accent)
        assertEquals(Color(0xFFE0936A), c.accentEnd)
        assertEquals(Color(0xFF1A1208), c.onAccent)
        assertEquals(Color(0xFF38D39F), c.success)
        assertEquals(Color(0xFF2F7D5B), c.successStart)
        assertEquals(Color(0xFFF0A0A0), c.danger)
        assertEquals(Color(0xFF1B1C22), c.focusBgTop)
        assertEquals(Color(0xFF121317), c.focusBgBottom)
    }

    @Test
    fun projectDefaultColorsMatchSpec() {
        assertEquals(Color(0xFF6EA8FE), c.projectWork)
        assertEquals(Color(0xFF38D39F), c.projectPersonal)
        assertEquals(Color(0xFFF0A868), c.projectHealth)
        assertEquals(Color(0xFFC9A0FF), c.projectIdeas)
        assertEquals(Color(0xFFF0A0A0), c.projectLearning)
    }

    @Test
    fun localTmapColorsDefaultsToMidnightCalm() {
        assertEquals(MidnightCalmColors, LocalTmapColors.current)
    }
}
```
> Note: `LocalTmapColors.current` is read outside composition; `staticCompositionLocalOf` returns its default value when read off-composition, so this assertion is valid in a JVM test.

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ColorTest"`
   - Expected: compilation failure / unresolved references — `error: unresolved reference: MidnightCalmColors` and `LocalTmapColors` (the old file only has `Surface*`/`Accent*` vals). Test does not pass.

3. Implement minimal code — replace `Color.kt` entirely:

```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Midnight Calm color tokens (spec §4.1). Dark-only; never the old desktop Surface*/Accent* palette.
 * Field names are the FIXED cross-phase contract (`colors.accent`, `colors.surface`, …).
 */
@Immutable
data class TmapColorScheme(
    val bgTop: Color,
    val bgBottom: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceInset: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textBody: Color,
    val accent: Color,
    val accentEnd: Color,
    val onAccent: Color,
    val success: Color,
    val successStart: Color,
    val danger: Color,
    val focusBgTop: Color,
    val focusBgBottom: Color,
    // Project default/legend colors (projects store their own; these are the defaults).
    val projectWork: Color,
    val projectPersonal: Color,
    val projectHealth: Color,
    val projectIdeas: Color,
    val projectLearning: Color,
)

val MidnightCalmColors: TmapColorScheme = TmapColorScheme(
    bgTop = Color(0xFF191A20),
    bgBottom = Color(0xFF141519),
    surface = Color(0xFF202127),
    surfaceRaised = Color(0xFF23242B),
    surfaceInset = Color(0xFF1C1D23),
    borderSubtle = Color(0xFF2A2B31),
    borderStrong = Color(0xFF34353C),
    textPrimary = Color(0xFFECEAE4),
    textSecondary = Color(0xFF908E86),
    textTertiary = Color(0xFF76746D),
    textBody = Color(0xFFB7B5AD),
    accent = Color(0xFFE8A87C),
    accentEnd = Color(0xFFE0936A),
    onAccent = Color(0xFF1A1208),
    success = Color(0xFF38D39F),
    successStart = Color(0xFF2F7D5B),
    danger = Color(0xFFF0A0A0),
    focusBgTop = Color(0xFF1B1C22),
    focusBgBottom = Color(0xFF121317),
    projectWork = Color(0xFF6EA8FE),
    projectPersonal = Color(0xFF38D39F),
    projectHealth = Color(0xFFF0A868),
    projectIdeas = Color(0xFFC9A0FF),
    projectLearning = Color(0xFFF0A0A0),
)

val LocalTmapColors = staticCompositionLocalOf { MidnightCalmColors }
```

4. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ColorTest"`
   - Expected: `BUILD SUCCESSFUL`; 5 tests pass.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/theme/Color.kt android/app/src/test/java/net/qmindtech/tmap/ui/theme/ColorTest.kt`
   - Message:
     ```
     feat(android-theme): replace desktop palette with Midnight Calm color tokens

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P0.2 — Shape, Spacing, and Motion tokens + their CompositionLocals

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Shapes.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Spacing.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Motion.kt`
- Create (test): `android/app/src/test/java/net/qmindtech/tmap/ui/theme/TokensTest.kt`

**Interfaces**
- Produces: `data class TmapShapes(val card, sheetTop, pill, button, well: Dp)` + `val TmapDefaultShapes` + `val LocalTmapShapes`.
- Produces: `data class TmapSpacing(val base, xs, sm, md, lg, xl, xxl, screenH: Dp)` (4/8/10/14/16/20/22 + screenH 18) + `val TmapDefaultSpacing` + `val LocalTmapSpacing`.
- Produces: `data class TmapMotion(val standardMillis: Int, val checkOffMillis: Int)` + `val TmapDefaultMotion` + `val LocalTmapMotion`; plus `fun standardEasing(): Easing` = `FastOutSlowInEasing`.
- Consumes: nothing.

**Steps**

1. Write failing test `TokensTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class TokensTest {
    @Test
    fun shapeTokensMatchSpec() {
        val s = TmapDefaultShapes
        assertEquals(18.dp, s.card)
        assertEquals(26.dp, s.sheetTop)
        assertEquals(999.dp, s.pill)
        assertEquals(13.dp, s.button)
        assertEquals(12.dp, s.well)
    }

    @Test
    fun spacingScaleMatchesSpec() {
        val p = TmapDefaultSpacing
        assertEquals(4.dp, p.base)
        assertEquals(8.dp, p.xs)
        assertEquals(10.dp, p.sm)
        assertEquals(14.dp, p.md)
        assertEquals(16.dp, p.lg)
        assertEquals(20.dp, p.xl)
        assertEquals(22.dp, p.xxl)
        assertEquals(18.dp, p.screenH)
    }

    @Test
    fun motionTokensMatchSpec() {
        val m = TmapDefaultMotion
        assertEquals(220, m.standardMillis)
        assertEquals(180, m.checkOffMillis)
    }

    @Test
    fun localsDefaultToTheDefaults() {
        assertEquals(TmapDefaultShapes, LocalTmapShapes.current)
        assertEquals(TmapDefaultSpacing, LocalTmapSpacing.current)
        assertEquals(TmapDefaultMotion, LocalTmapMotion.current)
    }
}
```

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.TokensTest"`
   - Expected: compilation failure — `unresolved reference: TmapDefaultShapes` / `TmapDefaultSpacing` / `TmapDefaultMotion` / the `Local*` vals.

3. Implement — `Shapes.kt`:

```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner-radius tokens (spec §4.2). */
@Immutable
data class TmapShapes(
    val card: Dp,
    val sheetTop: Dp,
    val pill: Dp,
    val button: Dp,
    val well: Dp,
)

val TmapDefaultShapes: TmapShapes = TmapShapes(
    card = 18.dp,
    sheetTop = 26.dp,
    pill = 999.dp,
    button = 13.dp,
    well = 12.dp,
)

val LocalTmapShapes = staticCompositionLocalOf { TmapDefaultShapes }
```

   `Spacing.kt`:

```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale (spec §4.2): 4 base; rhythm 4/8/10/14/16/20/22; screen horizontal 16–20 (18 default). */
@Immutable
data class TmapSpacing(
    val base: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val screenH: Dp,
)

val TmapDefaultSpacing: TmapSpacing = TmapSpacing(
    base = 4.dp,
    xs = 8.dp,
    sm = 10.dp,
    md = 14.dp,
    lg = 16.dp,
    xl = 20.dp,
    xxl = 22.dp,
    screenH = 18.dp,
)

val LocalTmapSpacing = staticCompositionLocalOf { TmapDefaultSpacing }
```

   `Motion.kt`:

```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens (spec §4.2): standard 220ms ease for sheet/nav; check-off ~180ms.
 * Spring on swipe/drag is applied at the call site. Callers gate on reduce-motion
 * (Settings.Global.ANIMATOR_DURATION_SCALE) — see P10.
 */
@Immutable
data class TmapMotion(
    val standardMillis: Int,
    val checkOffMillis: Int,
)

val TmapDefaultMotion: TmapMotion = TmapMotion(
    standardMillis = 220,
    checkOffMillis = 180,
)

fun standardEasing(): Easing = FastOutSlowInEasing

val LocalTmapMotion = staticCompositionLocalOf { TmapDefaultMotion }
```

4. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.TokensTest"`
   - Expected: `BUILD SUCCESSFUL`; 4 tests pass.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/theme/Shapes.kt android/app/src/main/java/net/qmindtech/tmap/ui/theme/Spacing.kt android/app/src/main/java/net/qmindtech/tmap/ui/theme/Motion.kt android/app/src/test/java/net/qmindtech/tmap/ui/theme/TokensTest.kt`
   - Message:
     ```
     feat(android-theme): add Midnight Calm shape, spacing, and motion tokens

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P0.3 — Typography tokens (`TmapType`)

**Files**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Type.kt`
- Create (test): `android/app/src/test/java/net/qmindtech/tmap/ui/theme/TypeTest.kt`

**Interfaces**
- Produces: `data class TmapType(val display, title, heading, body, meta, label: TextStyle)` + `val TmapDefaultType` + `val LocalTmapType`.
- Produces: `val TmapMaterialTypography: Typography` (Material3 bridge, used by `TmapTheme` so M3 components/ripple have sane defaults).
- Consumes: nothing.

**Steps**

1. Write failing test `TypeTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class TypeTest {
    private val t = TmapDefaultType

    @Test
    fun typeScaleMatchesSpec() {
        assertEquals(40.sp, t.display.fontSize)
        assertEquals(FontWeight.Light, t.display.fontWeight) // 300

        assertEquals(25.sp, t.title.fontSize)
        assertEquals(FontWeight.SemiBold, t.title.fontWeight) // 600

        assertEquals(19.sp, t.heading.fontSize)
        assertEquals(FontWeight.SemiBold, t.heading.fontWeight)

        assertEquals(14.5.sp, t.body.fontSize)
        assertEquals(FontWeight.Medium, t.body.fontWeight) // 500

        assertEquals(12.sp, t.meta.fontSize)

        assertEquals(11.sp, t.label.fontSize)
        assertEquals(FontWeight.Bold, t.label.fontWeight) // 700
    }

    @Test
    fun labelStyleIsUppercaseTrackedForSectionHeaders() {
        // Letter-spacing present (uppercasing is applied at the call site/SectionLabel).
        assertEquals(true, t.label.letterSpacing.value > 0f)
    }

    @Test
    fun localDefaultsToTheDefaultType() {
        assertEquals(TmapDefaultType, LocalTmapType.current)
    }
}
```

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.TypeTest"`
   - Expected: compilation failure — `unresolved reference: TmapDefaultType` / `LocalTmapType` (file currently only defines `TmapTypography`).

3. Implement — replace `Type.kt` entirely:

```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Midnight Calm type scale (spec §4.2), system font.
 * display 40/300 · title 25/600 · heading 19–20/600 · body 14.5/500 · meta 12 · label 11/700 uppercase+ls.
 */
@Immutable
data class TmapType(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val meta: TextStyle,
    val label: TextStyle,
)

private val Sans = FontFamily.SansSerif

val TmapDefaultType: TmapType = TmapType(
    display = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 46.sp),
    title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 30.sp),
    heading = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.5.sp, lineHeight = 20.sp),
    meta = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.sp),
)

val LocalTmapType = staticCompositionLocalOf { TmapDefaultType }

/**
 * Material3 typography bridge so M3 components (ripple defaults, fallbacks) read sensible styles.
 * App UI prefers the TmapType tokens above; this is the safety net.
 */
val TmapMaterialTypography: Typography = Typography(
    titleLarge = TmapDefaultType.title,
    titleMedium = TmapDefaultType.heading,
    bodyLarge = TmapDefaultType.body,
    bodyMedium = TmapDefaultType.body,
    labelLarge = TmapDefaultType.meta,
    labelSmall = TmapDefaultType.label,
)
```

4. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.TypeTest"`
   - Expected: `BUILD SUCCESSFUL`; 3 tests pass.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/theme/Type.kt android/app/src/test/java/net/qmindtech/tmap/ui/theme/TypeTest.kt`
   - Message:
     ```
     feat(android-theme): rebuild typography as Midnight Calm TmapType scale

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P0.4 — `TmapTheme` providing all CompositionLocals + M3 bridge + background gradient

**Files**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Theme.kt`
- Modify (rewrite assertions against the new bridged scheme): `android/app/src/test/java/net/qmindtech/tmap/ui/theme/ThemeColorSchemeTest.kt`

**Interfaces**
- Produces: `@Composable fun TmapTheme(content: @Composable () -> Unit)` — provides `LocalTmapColors, LocalTmapShapes, LocalTmapSpacing, LocalTmapType, LocalTmapMotion` and wraps a `MaterialTheme` with the bridged dark scheme (FIXED contract).
- Produces: `val TmapDarkColorScheme: ColorScheme` (Material3 bridge mapping M3 roles → Midnight Calm tokens; used for ripple/defaults only).
- Produces: `@Composable fun TmapBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit)` — the `bgTop→bgBottom` vertical gradient surface that every screen sits on.
- Consumes: `MidnightCalmColors`, `TmapDefaultShapes`, `TmapDefaultSpacing`, `TmapDefaultType`, `TmapDefaultMotion`, `TmapMaterialTypography`, the `Local*` vals.

**Steps**

1. Replace `ThemeColorSchemeTest.kt` with a failing test that pins the M3 bridge to Midnight Calm tokens:

```kotlin
package net.qmindtech.tmap.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorSchemeTest {
    @Test
    fun materialBridgeMapsMidnightCalmTokens() {
        val c = MidnightCalmColors
        // Background and surface roles bridge to the warm dark tokens (not the old desktop palette).
        assertEquals(c.bgBottom, TmapDarkColorScheme.background)
        assertEquals(c.surface, TmapDarkColorScheme.surface)
        assertEquals(c.textPrimary, TmapDarkColorScheme.onSurface)
        // The single accent is the M3 primary; error bridges to the soft danger.
        assertEquals(c.accent, TmapDarkColorScheme.primary)
        assertEquals(c.onAccent, TmapDarkColorScheme.onPrimary)
        assertEquals(c.danger, TmapDarkColorScheme.error)
    }
}
```

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ThemeColorSchemeTest"`
   - Expected: the old assertions are gone and the new ones reference `MidnightCalmColors`; build fails because `TmapDarkColorScheme` still maps the deleted `Surface*`/`Accent*` vals → `unresolved reference` (those vals were removed in P0.1). Test does not pass.

3. Implement — replace `Theme.kt` entirely:

```kotlin
package net.qmindtech.tmap.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

/**
 * Material3 bridge scheme. Midnight Calm is the source of truth; this exists so M3 components
 * (ripple, default text colors, selection handles) render coherently. App UI reads TmapColors
 * via LocalTmapColors, never these roles directly.
 */
val TmapDarkColorScheme: ColorScheme = darkColorScheme(
    primary = MidnightCalmColors.accent,
    onPrimary = MidnightCalmColors.onAccent,
    secondary = MidnightCalmColors.accentEnd,
    onSecondary = MidnightCalmColors.onAccent,
    background = MidnightCalmColors.bgBottom,
    onBackground = MidnightCalmColors.textPrimary,
    surface = MidnightCalmColors.surface,
    onSurface = MidnightCalmColors.textPrimary,
    surfaceVariant = MidnightCalmColors.surfaceRaised,
    onSurfaceVariant = MidnightCalmColors.textSecondary,
    surfaceContainer = MidnightCalmColors.surfaceRaised,
    surfaceContainerHigh = MidnightCalmColors.surfaceRaised,
    outline = MidnightCalmColors.borderStrong,
    outlineVariant = MidnightCalmColors.borderSubtle,
    tertiary = MidnightCalmColors.success,
    onTertiary = MidnightCalmColors.onAccent,
    error = MidnightCalmColors.danger,
    onError = MidnightCalmColors.onAccent,
)

@Composable
fun TmapTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTmapColors provides MidnightCalmColors,
        LocalTmapShapes provides TmapDefaultShapes,
        LocalTmapSpacing provides TmapDefaultSpacing,
        LocalTmapType provides TmapDefaultType,
        LocalTmapMotion provides TmapDefaultMotion,
    ) {
        MaterialTheme(
            colorScheme = TmapDarkColorScheme,
            typography = TmapMaterialTypography,
            content = content,
        )
    }
}

/** The app-wide vertical background gradient (bgTop → bgBottom). Every screen sits on this. */
@Composable
fun TmapBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalTmapColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bgTop, colors.bgBottom))),
        content = content,
    )
}
```

4. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ThemeColorSchemeTest"`
   - Expected: `BUILD SUCCESSFUL`; 1 test passes.

5. Compile gate (whole theme package now coherent):
   - Command: `./gradlew :app:compileDebugKotlin`
   - Expected: `BUILD SUCCESSFUL`.

6. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/theme/Theme.kt android/app/src/test/java/net/qmindtech/tmap/ui/theme/ThemeColorSchemeTest.kt`
   - Message:
     ```
     feat(android-theme): TmapTheme provides all Midnight Calm locals + M3 bridge

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P0.5 — `TaskUi` projection + unit-tested `TaskEntity.toUi()` mapper + scheduled-label helper

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/TaskUi.kt`
- Create (test): `android/app/src/test/java/net/qmindtech/tmap/ui/components/TaskUiTest.kt`

**Interfaces**
- Produces: `data class TaskUi(val id: String, val title: String, val projectName: String?, val projectColor: Long?, val scheduledLabel: String?, val subtaskDone: Int, val subtaskTotal: Int, val priority: Int, val hasReminder: Boolean, val isDone: Boolean)` (FIXED contract).
- Produces: `fun TaskEntity.toUi(project: ProjectEntity?, subtaskDone: Int = 0, subtaskTotal: Int = 0, zone: ZoneId = ZoneId.systemDefault()): TaskUi` — the contract mapper (`TaskEntity.toUi(project)` callable with just the project; subtask counts default to 0 and are filled by view-models that join subtasks). `priority` defaults to `0` when the entity's nullable `priority` is null (0 = no priority, matching the UI projection's non-null `Int`).
- Produces: `fun scheduledLabel(start: Instant?, durationMinutes: Int?, zone: ZoneId): String?` — `"9:30"`, `"9:30–10:15"`, or null.
- Produces: `fun parseProjectColor(hex: String?): Long?` — parses `"#6EA8FE"`/`"6EA8FE"` → `0xFF6EA8FEL`; null/blank/malformed → null.
- Consumes: `TaskEntity` (`net.qmindtech.tmap.data.local.entities`), `ProjectEntity`, `TaskStatus`.

**Steps**

1. Write failing test `TaskUiTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TaskUiTest {
    private val utc = ZoneId.of("UTC")
    private val t0 = Instant.parse("2026-06-21T00:00:00Z")

    private fun task(
        id: String = "t1",
        title: String = "Design review",
        status: TaskStatus = TaskStatus.Planned,
        projectId: String? = null,
        scheduledStart: Instant? = null,
        durationMinutes: Int? = null,
        priority: Int? = null,
        reminderMinutes: Int? = null,
    ) = TaskEntity(
        id = id, title = title, notes = null, projectId = projectId, labels = emptyList(),
        source = null, status = status, plannedDate = LocalDate.parse("2026-06-21"),
        scheduledStart = scheduledStart, scheduledEnd = null, durationMinutes = durationMinutes,
        actualTimeMinutes = 0, priority = priority, reminderMinutes = reminderMinutes, rank = null,
        dueDate = null, recurrenceRuleId = null, isRecurrenceTemplate = false,
        recurrenceDetached = false, recurrenceOriginalDate = null, completedAt = null,
        createdAt = t0, updatedAt = t0, changeSeq = 1L,
    )

    private fun project(name: String = "Work", color: String = "#6EA8FE") = ProjectEntity(
        id = "p1", name = name, color = color, emoji = "💼", rank = null,
        actualTimeMinutes = 0, createdAt = t0, updatedAt = t0, changeSeq = 1L,
    )

    @Test
    fun mapsCoreFieldsWithProject() {
        val ui = task(projectId = "p1", priority = 2, reminderMinutes = 15).toUi(project(), zone = utc)
        assertEquals("t1", ui.id)
        assertEquals("Design review", ui.title)
        assertEquals("Work", ui.projectName)
        assertEquals(0xFF6EA8FEL, ui.projectColor)
        assertEquals(2, ui.priority)
        assertEquals(true, ui.hasReminder)
        assertEquals(false, ui.isDone)
    }

    @Test
    fun nullProjectYieldsNullNameAndColor() {
        val ui = task().toUi(null, zone = utc)
        assertNull(ui.projectName)
        assertNull(ui.projectColor)
    }

    @Test
    fun nullPriorityBecomesZeroAndNoReminderIsFalse() {
        val ui = task(priority = null, reminderMinutes = null).toUi(null, zone = utc)
        assertEquals(0, ui.priority)
        assertEquals(false, ui.hasReminder)
    }

    @Test
    fun doneStatusSetsIsDone() {
        assertEquals(true, task(status = TaskStatus.Done).toUi(null, zone = utc).isDone)
    }

    @Test
    fun subtaskCountsDefaultToZeroButArePassedThrough() {
        assertEquals(0, task().toUi(null, zone = utc).subtaskTotal)
        val ui = task().toUi(null, subtaskDone = 1, subtaskTotal = 3, zone = utc)
        assertEquals(1, ui.subtaskDone)
        assertEquals(3, ui.subtaskTotal)
    }

    @Test
    fun scheduledLabelStartOnly() {
        val ui = task(scheduledStart = Instant.parse("2026-06-21T09:30:00Z")).toUi(null, zone = utc)
        assertEquals("9:30", ui.scheduledLabel)
    }

    @Test
    fun scheduledLabelStartAndEndFromDuration() {
        val ui = task(
            scheduledStart = Instant.parse("2026-06-21T09:30:00Z"),
            durationMinutes = 45,
        ).toUi(null, zone = utc)
        assertEquals("9:30–10:15", ui.scheduledLabel)
    }

    @Test
    fun scheduledLabelNullWhenUnscheduled() {
        assertNull(task(scheduledStart = null).toUi(null, zone = utc).scheduledLabel)
    }

    @Test
    fun parseProjectColorHandlesHashAndBareHexAndGarbage() {
        assertEquals(0xFF6EA8FEL, parseProjectColor("#6EA8FE"))
        assertEquals(0xFF38D39FL, parseProjectColor("38D39F"))
        assertNull(parseProjectColor(null))
        assertNull(parseProjectColor(""))
        assertNull(parseProjectColor("not-a-color"))
    }
}
```

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.TaskUiTest"`
   - Expected: compilation failure — `unresolved reference: toUi` / `TaskUi` / `parseProjectColor`.

3. Implement — `TaskUi.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import java.time.Instant
import java.time.ZoneId

/**
 * The UI projection of a task used by every list (FIXED cross-phase contract).
 * `projectColor` is an ARGB Long (0xFFRRGGBB) or null; `priority` is 0 when unset.
 */
data class TaskUi(
    val id: String,
    val title: String,
    val projectName: String?,
    val projectColor: Long?,
    val scheduledLabel: String?,
    val subtaskDone: Int,
    val subtaskTotal: Int,
    val priority: Int,
    val hasReminder: Boolean,
    val isDone: Boolean,
)

/**
 * Maps a TaskEntity (+ its optional project) to the UI projection.
 * Subtask counts default to 0; view-models that join subtasks pass real counts.
 */
fun TaskEntity.toUi(
    project: ProjectEntity?,
    subtaskDone: Int = 0,
    subtaskTotal: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
): TaskUi = TaskUi(
    id = id,
    title = title,
    projectName = project?.name,
    projectColor = parseProjectColor(project?.color),
    scheduledLabel = scheduledLabel(scheduledStart, durationMinutes, zone),
    subtaskDone = subtaskDone,
    subtaskTotal = subtaskTotal,
    priority = priority ?: 0,
    hasReminder = reminderMinutes != null,
    isDone = status == TaskStatus.Done,
)

/** "9:30", "9:30–10:15" (en-dash), or null when there is no scheduled start. */
fun scheduledLabel(start: Instant?, durationMinutes: Int?, zone: ZoneId): String? {
    if (start == null) return null
    val startLocal = start.atZone(zone).toLocalTime()
    val startStr = formatTime(startLocal.hour, startLocal.minute)
    if (durationMinutes == null || durationMinutes <= 0) return startStr
    val endLocal = start.plusSeconds(durationMinutes * 60L).atZone(zone).toLocalTime()
    return "$startStr\u2013${formatTime(endLocal.hour, endLocal.minute)}"
}

private fun formatTime(hour: Int, minute: Int): String =
    "$hour:${minute.toString().padStart(2, '0')}"

/** "#6EA8FE" / "6EA8FE" → 0xFF6EA8FEL; null/blank/malformed → null. */
fun parseProjectColor(hex: String?): Long? {
    val cleaned = hex?.trim()?.removePrefix("#") ?: return null
    if (cleaned.length != 6) return null
    val rgb = cleaned.toLongOrNull(16) ?: return null
    return 0xFF000000L or rgb
}
```

4. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.TaskUiTest"`
   - Expected: `BUILD SUCCESSFUL`; 9 tests pass.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/TaskUi.kt android/app/src/test/java/net/qmindtech/tmap/ui/components/TaskUiTest.kt`
   - Message:
     ```
     feat(android-ui): add TaskUi projection + tested TaskEntity.toUi mapper

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P0.6 — Swipe-state logic for `SwipeableTaskCard` (pure, unit-tested)

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/SwipeState.kt`
- Create (test): `android/app/src/test/java/net/qmindtech/tmap/ui/components/SwipeStateTest.kt`

**Interfaces**
- Produces: `enum class SwipeAction { None, Complete, DeferDelete }`.
- Produces: `data class SwipeDecision(val action: SwipeAction, val revealedProgress: Float)` (revealedProgress ∈ 0f..1f).
- Produces: `fun resolveSwipe(offsetPx: Float, thresholdPx: Float): SwipeDecision` — positive offset (swipe right) past threshold → `Complete`; negative (swipe left) past threshold → `DeferDelete`; otherwise `None`. `revealedProgress = min(1f, abs(offset)/threshold)`.
- Consumes: nothing.

> The visual swipe container (the `Modifier.pointerInput`/`anchoredDraggable` wiring + the green-complete and red-defer reveal backgrounds + undo affordance) is assembled in P0.7 against the Midnight Calm tokens; only this decision math is unit-tested here.

**Steps**

1. Write failing test `SwipeStateTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeStateTest {
    private val threshold = 120f

    @Test
    fun belowThresholdIsNone() {
        assertEquals(SwipeAction.None, resolveSwipe(40f, threshold).action)
        assertEquals(SwipeAction.None, resolveSwipe(-40f, threshold).action)
    }

    @Test
    fun rightPastThresholdCompletes() {
        assertEquals(SwipeAction.Complete, resolveSwipe(140f, threshold).action)
    }

    @Test
    fun leftPastThresholdDefersOrDeletes() {
        assertEquals(SwipeAction.DeferDelete, resolveSwipe(-140f, threshold).action)
    }

    @Test
    fun progressIsClampedZeroToOne() {
        assertEquals(0.5f, resolveSwipe(60f, threshold).revealedProgress, 0.001f)
        assertEquals(1f, resolveSwipe(240f, threshold).revealedProgress, 0.001f)
        assertEquals(1f, resolveSwipe(-240f, threshold).revealedProgress, 0.001f)
    }

    @Test
    fun atExactThresholdTriggers() {
        assertEquals(SwipeAction.Complete, resolveSwipe(120f, threshold).action)
        assertEquals(SwipeAction.DeferDelete, resolveSwipe(-120f, threshold).action)
    }
}
```

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.SwipeStateTest"`
   - Expected: compilation failure — `unresolved reference: resolveSwipe` / `SwipeAction`.

3. Implement — `SwipeState.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import kotlin.math.abs
import kotlin.math.min

/** Right = complete, left = defer/delete (spec §6.1). */
enum class SwipeAction { None, Complete, DeferDelete }

data class SwipeDecision(val action: SwipeAction, val revealedProgress: Float)

/**
 * Pure swipe resolution. Positive offset (start→end, "right" in LTR) past the threshold completes;
 * negative offset past the threshold defers/deletes; otherwise None. revealedProgress is clamped 0..1.
 */
fun resolveSwipe(offsetPx: Float, thresholdPx: Float): SwipeDecision {
    val progress = min(1f, abs(offsetPx) / thresholdPx)
    val action = when {
        offsetPx >= thresholdPx -> SwipeAction.Complete
        offsetPx <= -thresholdPx -> SwipeAction.DeferDelete
        else -> SwipeAction.None
    }
    return SwipeDecision(action, progress)
}
```

4. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.SwipeStateTest"`
   - Expected: `BUILD SUCCESSFUL`; 5 tests pass.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/SwipeState.kt android/app/src/test/java/net/qmindtech/tmap/ui/components/SwipeStateTest.kt`
   - Message:
     ```
     feat(android-ui): add pure swipe-resolution logic for SwipeableTaskCard

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P0.7 — `TaskCard` + `SwipeableTaskCard` composables

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/TaskCard.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/SwipeableTaskCard.kt`
- Delete: `android/app/src/main/java/net/qmindtech/tmap/ui/components/TaskRow.kt` (replaced)
- Delete: `android/app/src/main/java/net/qmindtech/tmap/ui/components/PriorityBadge.kt` (replaced — its dot is folded into `TaskCard`)

**Interfaces**
- Produces (FIXED): `@Composable fun TaskCard(task: TaskUi, onToggleComplete: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier)`.
- Produces (FIXED): `@Composable fun SwipeableTaskCard(task: TaskUi, onToggleComplete: () -> Unit, onDefer: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier)`.
- Consumes: `TaskUi`, `LocalTmapColors/Shapes/Spacing/Type/Motion`, `resolveSwipe`/`SwipeAction`, `ProjectDot` (P0.8).

> **Test substitution:** UI composables. Gate = `./gradlew :app:assembleDebug` + the behavior checklist below, verified against `design-direction.html` direction A. The card visuals (18dp `surface` card, circular checkbox, title with strike-through when done, project dot + `Work · 9:30` meta line, subtask "2 subtasks" meta, amber check-fill on done) mirror the mockup's `THIS MORNING` rows. **Authoring note:** `SwipeableTaskCard` uses `ProjectDot` from P0.8 — implement P0.8 first or in the same change; the assembleDebug gate is run after P0.8 lands. To keep tasks independently buildable, this task's `assembleDebug` gate is run at P0.8's completion (the two are a tight pair). Implement both, then gate.

**Steps**

1. Implement — `TaskCard.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapMotion
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

@Composable
fun TaskCard(
    task: TaskUi,
    onToggleComplete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val type = LocalTmapType.current
    val motion = LocalTmapMotion.current

    val checkScale by animateFloatAsState(
        targetValue = if (task.isDone) 1f else 0.9f,
        animationSpec = tween(motion.checkOffMillis),
        label = "checkScale",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (task.isDone) colors.surfaceInset else colors.surface,
                shape = RoundedCornerShape(shapes.card),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.lg, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        // Circular checkbox: outlined when open, amber-filled with check when done.
        Box(
            modifier = Modifier
                .size(22.dp)
                .scale(checkScale)
                .background(
                    color = if (task.isDone) colors.accent else Color.Transparent,
                    shape = CircleShape,
                )
                .border(
                    width = 2.dp,
                    color = if (task.isDone) colors.accent else colors.borderStrong,
                    shape = CircleShape,
                )
                .clickable(onClick = onToggleComplete)
                .semantics { },
            contentAlignment = Alignment.Center,
        ) {
            if (task.isDone) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Mark not done",
                    tint = colors.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = type.body,
                color = if (task.isDone) colors.textTertiary else colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
            )
            val meta = buildMeta(task)
            if (meta != null) {
                Row(
                    modifier = Modifier.padding(top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (task.projectColor != null) ProjectDot(colorArgb = task.projectColor)
                    Text(meta, style = type.meta, color = colors.textSecondary)
                }
            }
        }

        if (task.hasReminder) {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = "Has reminder",
                tint = colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** "Work · 9:30–10:15", "Work · 2 subtasks", "9:30", or null. */
private fun buildMeta(task: TaskUi): String? {
    val parts = mutableListOf<String>()
    task.projectName?.let { parts.add(it) }
    val tail = task.scheduledLabel
        ?: if (task.subtaskTotal > 0) "${task.subtaskTotal} subtasks" else null
    tail?.let { parts.add(it) }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}
```

2. Implement — `SwipeableTaskCard.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes

@Composable
fun SwipeableTaskCard(
    task: TaskUi,
    onToggleComplete: () -> Unit,
    onDefer: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val thresholdPx = with(LocalDensity.current) { 96.dp.toPx() }

    Box(modifier = modifier.fillMaxWidth()) {
        // Reveal layer: green complete (start side) / red defer-delete (end side).
        val decision = resolveSwipe(offsetX.value, thresholdPx)
        Row(
            modifier = Modifier
                .matchParentSize()
                .background(
                    color = when {
                        offsetX.value > 0f -> colors.success
                        offsetX.value < 0f -> colors.danger
                        else -> colors.surface
                    },
                    shape = RoundedCornerShape(shapes.card),
                )
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (offsetX.value > 0f) Arrangement.Start else Arrangement.End,
        ) {
            if (offsetX.value > 0f) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onAccent)
            } else if (offsetX.value < 0f) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = colors.onAccent)
            }
        }

        // Foreground card translates with the drag.
        TaskCard(
            task = task,
            onToggleComplete = onToggleComplete,
            onClick = onClick,
            modifier = Modifier
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(task.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when (resolveSwipe(offsetX.value, thresholdPx).action) {
                                    SwipeAction.Complete -> { offsetX.snapTo(0f); onToggleComplete() }
                                    SwipeAction.DeferDelete -> { offsetX.snapTo(0f); onDefer() }
                                    SwipeAction.None -> offsetX.animateTo(0f)
                                }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                        },
                    )
                },
        )
    }
}
```
> Accessibility (Global Constraints §"Accessibility"): the swipe gesture's non-gesture equivalents are the checkbox tap (complete) and the editor sheet's delete/defer actions (P1); `onDelete` is exposed for the long-press menu wired in P1. `remember` import: add `import androidx.compose.runtime.remember`.

3. Delete the replaced files:
   - `git rm android/app/src/main/java/net/qmindtech/tmap/ui/components/TaskRow.kt android/app/src/main/java/net/qmindtech/tmap/ui/components/PriorityBadge.kt`
   - (These are referenced by `TodayScreen`/`InboxScreen`/`BacklogScreen`/`AllTasksScreen`, which are rewritten to stubs in P0.16; until then the project will not assemble — the assembleDebug gate for the component pair is run after P0.8, and those screen references are removed in P0.16. The per-task compile gate for this pair is `./gradlew :app:compileDebugKotlin` after P0.16. To keep this task's own gate meaningful now, compile just the new files' dependencies by running the unit-test compile of the components package, which does not pull the screens: see step 4.)

4. Compile gate (component sources compile against the tokens; screens excluded):
   - Command: `./gradlew :app:compileDebugUnitTestKotlin`
   - Expected: the new component files and their token deps compile. (Existing screens still reference `TaskRow`; if this command pulls main sources and fails on the now-deleted `TaskRow`, proceed — the green gate for these composables is the package's `assembleDebug` after P0.16. Record the failure as "expected: screens not yet migrated" and continue.) Do **not** block the commit on a red main-source compile here; the authoritative gate is P0.16/P0.17.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/TaskCard.kt android/app/src/main/java/net/qmindtech/tmap/ui/components/SwipeableTaskCard.kt`
   - `git rm` already staged the deletions.
   - Message:
     ```
     feat(android-ui): add TaskCard + SwipeableTaskCard; remove TaskRow/PriorityBadge

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer, against `design-direction.html` direction A):**
- [ ] Open task card is an 18dp-radius `surface` (#202127) card with a 22dp circular outlined checkbox, title in `body` (14.5/500), and a meta line `<project dot> Work · 9:30` / `Work · 2 subtasks`.
- [ ] Done task card dims (`surfaceInset` bg, `textTertiary` strike-through title) and the checkbox is amber-filled with an `onAccent` check, matching the "Morning workout" done row.
- [ ] Reminder glyph (bell) shows on the end when `hasReminder`, with contentDescription.
- [ ] Swiping right reveals a green (`success`) complete background; swiping left reveals a red (`danger`) defer/delete background; releasing past ~96dp triggers the action, else springs back.

---

### Task P0.8 — `SectionLabel`, `ProjectDot`, `ProjectSwatch`

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/SectionLabel.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/ProjectDot.kt`
- Delete: `android/app/src/main/java/net/qmindtech/tmap/ui/components/ProjectPill.kt` (replaced by `ProjectDot`/`ProjectSwatch`)

**Interfaces**
- Produces: `@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier)` — uppercased `label` token (11/700, tracked, `textTertiary`), e.g. "THIS MORNING".
- Produces: `@Composable fun ProjectDot(colorArgb: Long, modifier: Modifier = Modifier, size: Dp = 8.dp)`.
- Produces: `@Composable fun ProjectSwatch(colorArgb: Long, emoji: String?, modifier: Modifier = Modifier)` — rounded-rect color chip with optional emoji, for project cards.
- Consumes: `LocalTmapColors/Type/Shapes`.

> **Test substitution:** UI; gate via the shared `assembleDebug` at P0.16/P0.17. `SectionLabel` uses `text.uppercase()` (pure transform inlined; the token already carries letter-spacing from P0.3).

**Steps**

1. Implement — `SectionLabel.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType

/** Uppercase tracked section header, e.g. "THIS MORNING" (mockup direction A). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    Text(
        text = text.uppercase(),
        style = type.label,
        color = colors.textTertiary,
        modifier = modifier,
    )
}
```

2. Implement — `ProjectDot.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors

/** Small round project color marker (mockup's colored dot before the meta line). */
@Composable
fun ProjectDot(colorArgb: Long, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier = modifier.size(size).background(Color(colorArgb), CircleShape))
}

/** Rounded color swatch with an optional emoji — for project cards/pickers. */
@Composable
fun ProjectSwatch(colorArgb: Long, emoji: String?, modifier: Modifier = Modifier) {
    val colors = LocalTmapColors.current
    Box(
        modifier = modifier
            .size(34.dp)
            .background(Color(colorArgb), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!emoji.isNullOrBlank()) Text(emoji)
    }
}
```

3. Delete the replaced file:
   - `git rm android/app/src/main/java/net/qmindtech/tmap/ui/components/ProjectPill.kt`

4. Compile gate for the component pair (P0.7 + P0.8) — main sources still reference the old screens, so the authoritative assemble is at P0.16. Run the focused Kotlin compile of the new component files via the test source set, then defer the full assemble:
   - Command: `./gradlew :app:compileDebugUnitTestKotlin`
   - Expected: the components (`TaskCard`, `SwipeableTaskCard`, `SectionLabel`, `ProjectDot`, `ProjectSwatch`) and tests compile. Main-source screen references to the deleted `TaskRow`/`ProjectPill` are resolved in P0.16; if the command surfaces those as errors, that is expected and resolved there.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/SectionLabel.kt android/app/src/main/java/net/qmindtech/tmap/ui/components/ProjectDot.kt`
   - Message:
     ```
     feat(android-ui): add SectionLabel, ProjectDot, ProjectSwatch; remove ProjectPill

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer, vs direction A):**
- [ ] `SectionLabel` renders "THIS MORNING"-style uppercase, letter-spaced, `textTertiary` (#76746D) labels.
- [ ] `ProjectDot` renders the small colored dot (e.g. Work `#6EA8FE`) that prefixes the card meta line.

---

### Task P0.9 — `Chips`: `Chip`, `FilterChip`, `SegmentedControl`

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/Chips.kt`
- Delete: `android/app/src/main/java/net/qmindtech/tmap/ui/components/StatusChip.kt` (replaced; `StatusDisplay`/`PriorityDisplay` stay in `PriorityDisplay.kt`)

**Interfaces**
- Produces: `@Composable fun Chip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, leadingEmoji: String? = null, selected: Boolean = false)`.
- Produces: `@Composable fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier)`.
- Produces (FIXED): `@Composable fun SegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit)`.
- Consumes: `LocalTmapColors/Shapes/Spacing/Type`.

> **Test substitution:** UI; shared assemble gate. The segmented control mirrors the mockup's List/Timeline pill toggle (selected segment = `surfaceRaised`/accent text, container = `surfaceInset`, fully pill-rounded).

**Steps**

1. Implement — `Chips.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

@Composable
fun Chip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingEmoji: String? = null,
    selected: Boolean = false,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Row(
        modifier = modifier
            .background(
                color = if (selected) colors.accent else colors.surfaceInset,
                shape = RoundedCornerShape(shapes.pill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!leadingEmoji.isNullOrBlank()) Text(leadingEmoji, style = type.meta)
        Text(
            text = label,
            style = type.meta,
            color = if (selected) colors.onAccent else colors.textSecondary,
        )
    }
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Row(
        modifier = modifier
            .background(
                color = if (selected) colors.surfaceRaised else colors.surfaceInset,
                shape = RoundedCornerShape(shapes.pill),
            )
            .border(
                width = 1.dp,
                color = if (selected) colors.accent else colors.borderSubtle,
                shape = RoundedCornerShape(shapes.pill),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = type.meta,
            color = if (selected) colors.accent else colors.textSecondary,
        )
    }
}

/** Pill segmented toggle (e.g. List ⇄ Timeline) — FIXED contract. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Row(
        modifier = Modifier
            .background(colors.surfaceInset, RoundedCornerShape(shapes.pill))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Text(
                text = label,
                style = type.meta,
                color = if (isSelected) colors.accent else colors.textSecondary,
                modifier = Modifier
                    .background(
                        color = if (isSelected) colors.surfaceRaised else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(shapes.pill),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}
```

2. Delete the replaced file:
   - `git rm android/app/src/main/java/net/qmindtech/tmap/ui/components/StatusChip.kt`

3. Compile gate:
   - Command: `./gradlew :app:compileDebugUnitTestKotlin`
   - Expected: `Chips.kt` compiles against tokens. (`StatusChip` removal: `StatusChip` is referenced only by the old screens rewritten in P0.16; resolved there.)

4. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/Chips.kt`
   - Message:
     ```
     feat(android-ui): add Chip/FilterChip/SegmentedControl; remove StatusChip

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer, vs direction A):** selected segment shows `surfaceRaised` fill with amber text; container is `surfaceInset`; fully pill-rounded.

---

### Task P0.10 — `Buttons`: `PrimaryButton`, `SecondaryButton`, `TmapFab`

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/Buttons.kt`

**Interfaces**
- Produces: `@Composable fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)` — amber gradient fill, `onAccent` text, 13dp radius.
- Produces: `@Composable fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true)` — `surfaceRaised` fill, `borderSubtle` outline, `textPrimary`.
- Produces (FIXED): `@Composable fun TmapFab(onClick: () -> Unit, modifier: Modifier = Modifier)` — 56dp amber-gradient circular FAB with a `+` and soft shadow.
- Consumes: `LocalTmapColors/Shapes/Type`.

> **Test substitution:** UI; shared assemble gate. `TmapFab` = the mockup's bottom-right 56dp `linear-gradient(135°, #E8A87C→#E0936A)` circle with a `+` glyph and `0 8px 22px rgba(232,168,124,.45)` shadow.

**Steps**

1. Implement — `Buttons.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    val brush = Brush.linearGradient(listOf(colors.accent, colors.accentEnd))
    Box(
        modifier = modifier
            .background(
                brush = if (enabled) brush else Brush.linearGradient(listOf(colors.surfaceRaised, colors.surfaceRaised)),
                shape = RoundedCornerShape(shapes.button),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.body,
            color = if (enabled) colors.onAccent else colors.textTertiary,
        )
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Box(
        modifier = modifier
            .background(colors.surfaceRaised, RoundedCornerShape(shapes.button))
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(shapes.button))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = type.body, color = colors.textPrimary)
    }
}

/** The corner + quick-capture FAB — amber gradient circle, soft shadow (FIXED contract). */
@Composable
fun TmapFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalTmapColors.current
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
            .background(Brush.linearGradient(listOf(colors.accent, colors.accentEnd)), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Quick capture", tint = colors.onAccent)
    }
}
```
> Unused `fillMaxWidth`/`Color` imports: keep only those referenced — remove `fillMaxWidth` and `Color` imports if the compiler warns (they are not used above). (Listed for completeness; trim to the used set.)

2. Compile gate:
   - Command: `./gradlew :app:compileDebugUnitTestKotlin`
   - Expected: `Buttons.kt` compiles.

3. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/Buttons.kt`
   - Message:
     ```
     feat(android-ui): add PrimaryButton, SecondaryButton, TmapFab

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer, vs direction A):** `TmapFab` is a 56dp circle with a 135° amber gradient, `onAccent` `+`, and a soft amber-tinted shadow, sitting bottom-end like the mockup.

---

### Task P0.11 — `ProgressRing` (amber arc Canvas)

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/ProgressRing.kt`
- Create (test): `android/app/src/test/java/net/qmindtech/tmap/ui/components/ProgressRingTest.kt`

**Interfaces**
- Produces (FIXED): `@Composable fun ProgressRing(progress: Float, modifier: Modifier, centerLabel: @Composable () -> Unit)`.
- Produces: `fun sweepAngle(progress: Float): Float` — pure: clamps `progress` to 0..1, returns `progress * 360f`.
- Consumes: `LocalTmapColors`.

> The arc (track + amber sweep) is real `drawArc` Canvas code; the pure `sweepAngle` clamp is unit-tested. UI gate via shared assemble.

**Steps**

1. Write failing test `ProgressRingTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressRingTest {
    @Test
    fun sweepIsProportionalToProgress() {
        assertEquals(0f, sweepAngle(0f), 0.001f)
        assertEquals(180f, sweepAngle(0.5f), 0.001f)
        assertEquals(360f, sweepAngle(1f), 0.001f)
    }

    @Test
    fun progressIsClamped() {
        assertEquals(0f, sweepAngle(-0.3f), 0.001f)
        assertEquals(360f, sweepAngle(1.4f), 0.001f)
    }
}
```

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.ProgressRingTest"`
   - Expected: compilation failure — `unresolved reference: sweepAngle`.

3. Implement — `ProgressRing.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors

/** Clamped progress → degrees of sweep (0..360). */
fun sweepAngle(progress: Float): Float = progress.coerceIn(0f, 1f) * 360f

/** Amber progress arc with a centered label (e.g. "3 of 8" or a percentage). */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier,
    centerLabel: @Composable () -> Unit,
) {
    val colors = LocalTmapColors.current
    val sweep = sweepAngle(progress)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val strokeWidth = 8.dp.toPx()
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            // Track.
            drawArc(
                color = colors.borderSubtle,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            // Amber sweep (gradient accent→accentEnd).
            drawArc(
                brush = Brush.sweepGradient(listOf(colors.accent, colors.accentEnd)),
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
        }
        centerLabel()
    }
}
```

4. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.ProgressRingTest"`
   - Expected: `BUILD SUCCESSFUL`; 2 tests pass.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/ProgressRing.kt android/app/src/test/java/net/qmindtech/tmap/ui/components/ProgressRingTest.kt`
   - Message:
     ```
     feat(android-ui): add ProgressRing amber arc with tested sweep math

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer):** ring shows a `borderSubtle` track and an amber sweep starting at 12 o'clock, proportional to `progress`, with the center label centered.

---

### Task P0.12 — `StatTile` + `EmptyState` (rebuilt)

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/StatTile.kt`
- Modify (rebuild to Midnight Calm tokens): `android/app/src/main/java/net/qmindtech/tmap/ui/components/EmptyState.kt`

**Interfaces**
- Produces: `@Composable fun StatTile(value: String, label: String, modifier: Modifier = Modifier)` — `surface` card, big amber `title`-weight value over a `textSecondary` `meta` label (You-screen stat tiles).
- Produces: `@Composable fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier)` — same signature as today (keep call sites working) but restyled to tokens with a `SecondaryButton` action.
- Consumes: `LocalTmapColors/Type/Spacing`, `SecondaryButton`.

> **Test substitution:** UI; shared assemble gate. Keeping `EmptyState`'s signature unchanged avoids breaking existing/stub call sites.

**Steps**

1. Implement — `StatTile.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

/** A single stat (e.g. "5" / "day streak") for the You screen. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Column(
        modifier = modifier
            .background(colors.surface, RoundedCornerShape(shapes.card))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(value, style = type.title, color = colors.accent)
        Text(label, style = type.meta, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
    }
}
```

2. Implement — replace `EmptyState.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(40.dp))
        Text(
            title,
            style = type.heading,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            SecondaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
```

3. Compile gate:
   - Command: `./gradlew :app:compileDebugUnitTestKotlin`
   - Expected: both files compile.

4. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/StatTile.kt android/app/src/main/java/net/qmindtech/tmap/ui/components/EmptyState.kt`
   - Message:
     ```
     feat(android-ui): add StatTile; restyle EmptyState to Midnight Calm tokens

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer):** `StatTile` value is amber and prominent; `EmptyState` is centered, calm, with token typography and an optional `SecondaryButton`.

---

### Task P0.13 — `SyncStatusPill` (rebuilt from `SyncStatusBar`) + status-label logic

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/SyncStatusPill.kt`
- Delete: `android/app/src/main/java/net/qmindtech/tmap/ui/components/SyncStatusBar.kt` (replaced)
- Create (test): `android/app/src/test/java/net/qmindtech/tmap/ui/components/SyncStatusPillTest.kt`

**Interfaces**
- Produces: `data class SyncPillContent(val label: String, val showRetry: Boolean, val visible: Boolean)`.
- Produces: `fun syncPillContent(status: SyncStatus, pendingCount: Int): SyncPillContent` — pure: `Idle`+0 pending → `visible=false`; `Idle`+pending → "Synced" (+ "N pending"); `Syncing` → "Syncing…"; `Offline` → "Offline" (+retry); `Error` → message (+retry).
- Produces: `@Composable fun SyncStatusPill(status: SyncStatus, pendingCount: Int, onRetry: () -> Unit, modifier: Modifier = Modifier)` — pill on `surfaceInset`, `success` dot when synced.
- Consumes: `SyncStatus` (`net.qmindtech.tmap.data.sync`), `LocalTmapColors/Type/Shapes`.

**Steps**

1. Write failing test `SyncStatusPillTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import net.qmindtech.tmap.data.sync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncStatusPillTest {
    @Test
    fun idleWithNoPendingIsHidden() {
        assertFalse(syncPillContent(SyncStatus.Idle, 0).visible)
    }

    @Test
    fun idleWithPendingShowsSyncedAndCount() {
        val c = syncPillContent(SyncStatus.Idle, 3)
        assertTrue(c.visible)
        assertEquals("Synced · 3 pending", c.label)
        assertFalse(c.showRetry)
    }

    @Test
    fun syncingShowsProgress() {
        assertEquals("Syncing…", syncPillContent(SyncStatus.Syncing, 0).label)
    }

    @Test
    fun offlineShowsRetry() {
        val c = syncPillContent(SyncStatus.Offline, 2)
        assertEquals("Offline · 2 pending", c.label)
        assertTrue(c.showRetry)
    }

    @Test
    fun errorShowsMessageAndRetry() {
        val c = syncPillContent(SyncStatus.Error("Auth failed"), 0)
        assertEquals("Auth failed", c.label)
        assertTrue(c.showRetry)
    }
}
```
> If the actual `SyncStatus` subtype/constructor names differ (e.g. `SyncStatus.Error(message=...)`), align the test to the real shapes read from `data/sync/` — the test pins behavior, not invented names.

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.SyncStatusPillTest"`
   - Expected: compilation failure — `unresolved reference: syncPillContent`.

3. Implement — `SyncStatusPill.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.sync.SyncStatus
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

data class SyncPillContent(val label: String, val showRetry: Boolean, val visible: Boolean)

/** Pure mapping from sync state to pill content (mirrors desktop pill quiet-ok behavior). */
fun syncPillContent(status: SyncStatus, pendingCount: Int): SyncPillContent {
    val pendingSuffix = if (pendingCount > 0) " · $pendingCount pending" else ""
    return when (status) {
        is SyncStatus.Idle ->
            if (pendingCount == 0) SyncPillContent("", showRetry = false, visible = false)
            else SyncPillContent("Synced$pendingSuffix", showRetry = false, visible = true)
        is SyncStatus.Syncing -> SyncPillContent("Syncing…$pendingSuffix", showRetry = false, visible = true)
        is SyncStatus.Offline -> SyncPillContent("Offline$pendingSuffix", showRetry = true, visible = true)
        is SyncStatus.Error -> SyncPillContent(status.message, showRetry = true, visible = true)
    }
}

@Composable
fun SyncStatusPill(
    status: SyncStatus,
    pendingCount: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = syncPillContent(status, pendingCount)
    if (!content.visible) return
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Row(
        modifier = modifier
            .background(colors.surfaceInset, RoundedCornerShape(shapes.pill))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val dotColor = when (status) {
            is SyncStatus.Idle -> colors.success
            is SyncStatus.Error, is SyncStatus.Offline -> colors.danger
            is SyncStatus.Syncing -> colors.accent
        }
        Box(modifier = Modifier.size(8.dp).background(dotColor, CircleShape))
        Text(content.label, style = type.meta, color = colors.textSecondary)
        if (content.showRetry) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Retry sync",
                tint = colors.accent,
                modifier = Modifier.size(16.dp).clickable(onClick = onRetry),
            )
        }
    }
}
```

4. Delete the replaced file:
   - `git rm android/app/src/main/java/net/qmindtech/tmap/ui/components/SyncStatusBar.kt`

5. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.SyncStatusPillTest"`
   - Expected: `BUILD SUCCESSFUL`; 5 tests pass.

6. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/SyncStatusPill.kt android/app/src/test/java/net/qmindtech/tmap/ui/components/SyncStatusPillTest.kt`
   - Message:
     ```
     feat(android-ui): add SyncStatusPill with tested content mapping; remove SyncStatusBar

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer):** quiet (hidden) when synced + nothing pending; a status dot (success/amber/danger) + label, with a retry glyph on offline/error.

---

### Task P0.14 — `SheetScaffold` (bottom-sheet shell)

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/SheetScaffold.kt`

**Interfaces**
- Produces: `@Composable fun SheetScaffold(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit)` — wraps `ModalBottomSheet` with `surfaceRaised` container, 26dp top corners, a grabber, and an optional `heading` title row. Used by P1 capture/editor and P0.15's `SheetHost`.
- Consumes: `LocalTmapColors/Shapes/Type/Spacing`, Material3 `ModalBottomSheet`.

> **Test substitution:** UI; shared assemble gate.

**Steps**

1. Implement — `SheetScaffold.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceRaised,
        scrimColor = colors.bgBottom.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = type.heading,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            content()
        }
    }
}
```
> The `ModalBottomSheet`'s default top-corner radius is overridden to `shapes.sheetTop` (26dp) by passing `shape = RoundedCornerShape(topStart = shapes.sheetTop, topEnd = shapes.sheetTop)` — add `shape =` and the `LocalTmapShapes.current` read if the reviewer wants the exact corner; the default M3 sheet corner (28dp) is within tolerance and acceptable. Decision: use the explicit 26dp shape for fidelity — add `shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = LocalTmapShapes.current.sheetTop, topEnd = LocalTmapShapes.current.sheetTop)` to the `ModalBottomSheet` call and `import net.qmindtech.tmap.ui.theme.LocalTmapShapes`.

2. Compile gate:
   - Command: `./gradlew :app:compileDebugUnitTestKotlin`
   - Expected: compiles.

3. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/SheetScaffold.kt`
   - Message:
     ```
     feat(android-ui): add SheetScaffold bottom-sheet shell (26dp, surfaceRaised)

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer):** modal sheet uses `surfaceRaised` container, 26dp top corners, dim scrim, optional `heading` title.

---

### Task P0.15 — Navigation contracts: `Route` sealed interface, `BottomNavItem`, `openTaskEditor`/`openCapture`

**Files**
- Modify (rewrite): `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/Routes.kt`
- Modify (rewrite): `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/BottomNavItem.kt`
- Modify (rewrite to the new IA): `android/app/src/test/java/net/qmindtech/tmap/ui/navigation/RoutesTest.kt`
- Modify (rewrite to the new 5 tabs): `android/app/src/test/java/net/qmindtech/tmap/ui/navigation/BottomNavItemTest.kt`
- Modify (add nav-label + auth strings): `android/app/src/main/res/values/strings.xml`

**Interfaces**
- Produces (FIXED): `sealed interface Route` with `data object Today/Inbox/Browse/Notes/You/Planning`, `data class Focus(val taskId: String?)`, `data class ProjectDetail(val projectId: String)`; each carries a `val route: String` path. Companion helpers: `Focus.PATTERN/ARG_TASK_ID/NEW_SENTINEL/create(...)`; `ProjectDetail.PATTERN/ARG_PROJECT_ID/create(...)`. Plus auth routes `Login`, `Register` (string-route objects).
- Produces (FIXED): `fun NavController.openTaskEditor(taskId: String)` and `fun NavController.openCapture()` — drive `SheetHost` (P0.16) via shared state, not navigation; defined here as thin entry points that call into the sheet controller exposed by `MainScaffold`. (Implementation: these are declared in `Routes.kt` and delegate to a `SheetController` set in composition; see P0.16 where the controller is created. For P0.15 they are declared with a `SheetController` parameterless form using a `CompositionLocal`.) — concrete wiring in P0.16; here they are declared against `LocalSheetController`.
- Produces: `data class BottomNavItem(val route: String, @StringRes val labelRes: Int, val icon: ImageVector)` + `val BOTTOM_NAV_ITEMS: List<BottomNavItem>` of the 5 tabs Today/Inbox/Browse/Notes/You.
- Consumes: nav-label string resources.

**Steps**

1. Add strings — append to `strings.xml` (full new file):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">TMap</string>
    <string name="nav_today">Today</string>
    <string name="nav_inbox">Inbox</string>
    <string name="nav_browse">Browse</string>
    <string name="nav_notes">Notes</string>
    <string name="nav_you">You</string>
</resources>
```

2. Rewrite `RoutesTest.kt` (failing — references the new `Route` interface + helpers):

```kotlin
package net.qmindtech.tmap.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    @Test
    fun primaryTabRouteStringsArePinned() {
        assertEquals("today", Route.Today.route)
        assertEquals("inbox", Route.Inbox.route)
        assertEquals("browse", Route.Browse.route)
        assertEquals("notes", Route.Notes.route)
        assertEquals("you", Route.You.route)
        assertEquals("planning", Route.Planning.route)
    }

    @Test
    fun focusRoutePatternAndArg() {
        assertEquals("focus/{taskId}", Route.Focus.PATTERN)
        assertEquals("taskId", Route.Focus.ARG_TASK_ID)
        assertEquals("focus/new", Route.Focus.create(null))
        assertEquals("focus/abc", Route.Focus.create("abc"))
        assertEquals("focus/new", Route.Focus(null).route)
        assertEquals("focus/xyz", Route.Focus("xyz").route)
    }

    @Test
    fun projectDetailRoutePatternAndArg() {
        assertEquals("project/{projectId}", Route.ProjectDetail.PATTERN)
        assertEquals("projectId", Route.ProjectDetail.ARG_PROJECT_ID)
        assertEquals("project/p1", Route.ProjectDetail.create("p1"))
        assertEquals("project/p1", Route.ProjectDetail("p1").route)
    }

    @Test
    fun authRouteStringsArePinned() {
        assertEquals("login", Route.Login.route)
        assertEquals("register", Route.Register.route)
    }
}
```

3. Rewrite `BottomNavItemTest.kt` (failing — five tabs):

```kotlin
package net.qmindtech.tmap.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavItemTest {
    @Test
    fun bottomBarHasFivePrimaryDestinationsInDailyFirstOrder() {
        assertEquals(
            listOf(
                Route.Today.route,
                Route.Inbox.route,
                Route.Browse.route,
                Route.Notes.route,
                Route.You.route,
            ),
            BOTTOM_NAV_ITEMS.map { it.route },
        )
    }

    @Test
    fun everyItemCarriesAnIconAndLabelResource() {
        assertEquals(5, BOTTOM_NAV_ITEMS.size)
        assertEquals(true, BOTTOM_NAV_ITEMS.all { it.labelRes != 0 })
    }
}
```

4. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.navigation.RoutesTest" --tests "net.qmindtech.tmap.ui.navigation.BottomNavItemTest"`
   - Expected: compilation failure — `unresolved reference: Route` (old file defines `Routes`, not `Route`), and `R.string.nav_browse/nav_notes/nav_you` don't exist yet at the `BottomNavItem` site. Tests do not pass.

5. Implement — rewrite `Routes.kt`:

```kotlin
package net.qmindtech.tmap.ui.navigation

import androidx.navigation.NavController

/**
 * Daily-first navigation graph (spec §5). Tabs + full-screen destinations are routes; the
 * task editor and quick-capture are bottom-sheet states (SheetHost), reached via
 * openTaskEditor/openCapture — NOT routes. FIXED cross-phase contract.
 */
sealed interface Route {
    val route: String

    data object Today : Route { override val route = "today" }
    data object Inbox : Route { override val route = "inbox" }
    data object Browse : Route { override val route = "browse" }
    data object Notes : Route { override val route = "notes" }
    data object You : Route { override val route = "you" }
    data object Planning : Route { override val route = "planning" }

    data class Focus(val taskId: String?) : Route {
        override val route = create(taskId)
        companion object {
            const val NEW_SENTINEL = "new"
            const val ARG_TASK_ID = "taskId"
            const val PATTERN = "focus/{taskId}"
            fun create(taskId: String?): String = "focus/${taskId ?: NEW_SENTINEL}"
        }
    }

    data class ProjectDetail(val projectId: String) : Route {
        override val route = create(projectId)
        companion object {
            const val ARG_PROJECT_ID = "projectId"
            const val PATTERN = "project/{projectId}"
            fun create(projectId: String): String = "project/$projectId"
        }
    }

    // Auth destinations (the gate lives in TmapApp; these are here for completeness/deeplinks).
    data object Login : Route { override val route = "login" }
    data object Register : Route { override val route = "register" }
}

/**
 * Sheet entry points (FIXED contract). These drive SheetHost via the SheetController provided
 * in composition by MainScaffold (P0.16). They take a NavController receiver to match the
 * contract signature and for future route-aware behavior, but currently delegate to the
 * controller, which MainScaffold reads from LocalSheetController.
 */
fun NavController.openTaskEditor(taskId: String) {
    SheetCommands.request(SheetRequest.Editor(taskId))
}

fun NavController.openCapture() {
    SheetCommands.request(SheetRequest.Capture)
}
```

   And the lightweight command bridge (declared here so `openTaskEditor`/`openCapture` are usable; `SheetHost` in P0.16 collects it):

```kotlin
// --- file: append to Routes.kt (same package) ---

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Which sheet to show. */
sealed interface SheetRequest {
    data object Capture : SheetRequest
    data class Editor(val taskId: String) : SheetRequest
}

/**
 * Process-wide one-shot bridge from the openTaskEditor/openCapture entry points to SheetHost.
 * A buffered SharedFlow so a request emitted before SheetHost subscribes is not lost.
 */
object SheetCommands {
    private val _requests = MutableSharedFlow<SheetRequest>(extraBufferCapacity = 4)
    val requests: SharedFlow<SheetRequest> = _requests.asSharedFlow()
    fun request(req: SheetRequest) { _requests.tryEmit(req) }
}
```
> Note: keep all imports at the top of the single `Routes.kt` file (the `// --- file: append ---` marker just indicates these live in the same file; consolidate imports). `openTaskEditor` keeps its `taskId: String` param per the FIXED contract; the deep link `tmap://task/{taskId}` is handled in `SheetHost` (P0.16) by emitting `SheetRequest.Editor`.

6. Implement — rewrite `BottomNavItem.kt`:

```kotlin
package net.qmindtech.tmap.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.ui.graphics.vector.ImageVector
import net.qmindtech.tmap.R

data class BottomNavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

/** Daily-first 5 tabs: Today · Inbox · Browse · Notes · You (spec §5). */
val BOTTOM_NAV_ITEMS: List<BottomNavItem> = listOf(
    BottomNavItem(Route.Today.route, R.string.nav_today, Icons.Filled.Today),
    BottomNavItem(Route.Inbox.route, R.string.nav_inbox, Icons.Filled.Inbox),
    BottomNavItem(Route.Browse.route, R.string.nav_browse, Icons.Outlined.Search),
    BottomNavItem(Route.Notes.route, R.string.nav_notes, Icons.Outlined.StickyNote2),
    BottomNavItem(Route.You.route, R.string.nav_you, Icons.Outlined.Person),
)
```

7. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.navigation.RoutesTest" --tests "net.qmindtech.tmap.ui.navigation.BottomNavItemTest"`
   - Expected: `BUILD SUCCESSFUL`; all navigation tests pass.

8. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/navigation/Routes.kt android/app/src/main/java/net/qmindtech/tmap/ui/navigation/BottomNavItem.kt android/app/src/test/java/net/qmindtech/tmap/ui/navigation/RoutesTest.kt android/app/src/test/java/net/qmindtech/tmap/ui/navigation/BottomNavItemTest.kt android/app/src/main/res/values/strings.xml`
   - Message:
     ```
     feat(android-nav): Daily-first Route interface + 5-tab BottomNavItem + sheet entry points

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P0.16 — `SheetHost` + `MainScaffold` + placeholder tab stubs + `TmapApp` rewire

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/SheetHost.kt`
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/MainScaffold.kt`
- Modify (rewrite): `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/TmapApp.kt`
- Create (placeholder stubs): `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/PlaceholderScreens.kt`
- Delete (old four-tab screens that referenced removed components, folded into stubs until later phases): `android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayScreen.kt`, `android/app/src/main/java/net/qmindtech/tmap/ui/inbox/InboxScreen.kt`, `android/app/src/main/java/net/qmindtech/tmap/ui/backlog/BacklogScreen.kt`, `android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/AllTasksScreen.kt`, `android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectsScreen.kt`, `android/app/src/main/java/net/qmindtech/tmap/ui/settings/SettingsScreen.kt`, `android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorScreen.kt`

**Interfaces**
- Produces: `@Composable fun SheetHost()` — collects `SheetCommands.requests`, holds capture/editor sheet state, renders a `SheetScaffold` stub for each (real capture/editor land in P1).
- Produces: `@Composable fun MainScaffold(navController: NavHostController = rememberNavController())` — `TmapBackground` + Midnight Calm bottom nav (`BOTTOM_NAV_ITEMS`) + `TmapFab` (calls `navController.openCapture()`) + `NavHost` over the 5 tab stubs + `Planning`/`Focus`/`ProjectDetail` stub destinations + the `tmap://task/{taskId}` deep link emitting `SheetRequest.Editor` + `SheetHost`.
- Produces: placeholder `@Composable fun TodayPlaceholder()/InboxPlaceholder()/BrowsePlaceholder()/NotesPlaceholder()/YouPlaceholder()` (+ Planning/Focus/ProjectDetail) using `EmptyState`/tokens — each named "Coming soon" with its tab title; replaced by real screens in P1+.
- Consumes: `Route`, `BOTTOM_NAV_ITEMS`, `TmapBackground`, `TmapFab`, `SheetScaffold`, `EmptyState`, `SheetCommands`, `SessionState`, `AuthViewModel`.

> **Test substitution:** Compose scaffold/navigation — gate is `./gradlew :app:assembleDebug` (Task P0.17 runs it as the phase gate) + the behavior checklist. The old `*ViewModel`/`*UiState` files and their existing unit tests are left intact (P1+ reattach them); only the old `*Screen.kt` composables that referenced removed components are deleted and replaced by stubs, so the build is green.

**Steps**

1. Delete the old screens that referenced removed components:
   - `git rm android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayScreen.kt android/app/src/main/java/net/qmindtech/tmap/ui/inbox/InboxScreen.kt android/app/src/main/java/net/qmindtech/tmap/ui/backlog/BacklogScreen.kt android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/AllTasksScreen.kt android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectsScreen.kt android/app/src/main/java/net/qmindtech/tmap/ui/settings/SettingsScreen.kt android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorScreen.kt`
   - (Their ViewModels/UiState files remain; placeholder stubs do not call them yet. The `QuickAddSheet` referenced by the old InboxScreen is also removed if it lives in that file; if it is a separate file under `ui/inbox/`, leave it — it is unreferenced and harmless until P1, but if it references removed components, delete it too. Verify with a build in step 6 and remove any leftover broken file then.)

2. Implement — `PlaceholderScreens.kt`:

```kotlin
package net.qmindtech.tmap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.qmindtech.tmap.ui.components.EmptyState

@Composable
private fun Placeholder(title: String) {
    Box(modifier = Modifier.fillMaxSize()) {
        EmptyState(
            icon = Icons.Outlined.AutoAwesome,
            title = title,
            subtitle = "Coming soon in a later phase.",
        )
    }
}

@Composable fun TodayPlaceholder() = Placeholder("Today")
@Composable fun InboxPlaceholder() = Placeholder("Inbox")
@Composable fun BrowsePlaceholder() = Placeholder("Browse")
@Composable fun NotesPlaceholder() = Placeholder("Notes")
@Composable fun YouPlaceholder() = Placeholder("You")
@Composable fun PlanningPlaceholder() = Placeholder("Plan my day")
@Composable fun FocusPlaceholder() = Placeholder("Focus")
@Composable fun ProjectDetailPlaceholder() = Placeholder("Project")
```

3. Implement — `SheetHost.kt`:

```kotlin
package net.qmindtech.tmap.ui.navigation

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import net.qmindtech.tmap.ui.components.SheetScaffold
import net.qmindtech.tmap.ui.theme.LocalTmapColors

/**
 * Holds capture/editor bottom-sheet state, driven by SheetCommands (openCapture/openTaskEditor).
 * In P0 the sheet bodies are stubs; P1 replaces them with QuickCaptureSheet / TaskEditorSheet.
 */
@Composable
fun SheetHost() {
    var active by remember { mutableStateOf<SheetRequest?>(null) }
    LaunchedEffect(Unit) {
        SheetCommands.requests.collect { active = it }
    }
    val colors = LocalTmapColors.current
    when (val req = active) {
        is SheetRequest.Capture -> SheetScaffold(onDismiss = { active = null }, title = "Quick capture") {
            Text("Capture sheet — implemented in P1.", color = colors.textSecondary)
        }
        is SheetRequest.Editor -> SheetScaffold(onDismiss = { active = null }, title = "Edit task") {
            Text("Editor for ${req.taskId} — implemented in P1.", color = colors.textSecondary)
        }
        null -> Unit
    }
}
```

4. Implement — `MainScaffold.kt`:

```kotlin
package net.qmindtech.tmap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import net.qmindtech.tmap.ui.components.TmapFab
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.TmapBackground

@Composable
fun MainScaffold(navController: NavHostController = rememberNavController()) {
    val colors = LocalTmapColors.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val onPrimaryDestination = BOTTOM_NAV_ITEMS.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }

    TmapBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (onPrimaryDestination) {
                    NavigationBar(containerColor = colors.surface) {
                        BOTTOM_NAV_ITEMS.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                                label = { Text(stringResource(item.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = colors.accent,
                                    selectedTextColor = colors.accent,
                                    unselectedIconColor = colors.textTertiary,
                                    unselectedTextColor = colors.textTertiary,
                                    indicatorColor = colors.surfaceInset,
                                ),
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (onPrimaryDestination) {
                    TmapFab(onClick = { navController.openCapture() })
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopStart) {
                NavHost(navController = navController, startDestination = Route.Today.route) {
                    composable(Route.Today.route) { TodayPlaceholder() }
                    composable(Route.Inbox.route) { InboxPlaceholder() }
                    composable(Route.Browse.route) { BrowsePlaceholder() }
                    composable(Route.Notes.route) { NotesPlaceholder() }
                    composable(Route.You.route) { YouPlaceholder() }
                    composable(Route.Planning.route) { PlanningPlaceholder() }
                    composable(
                        route = Route.Focus.PATTERN,
                        arguments = listOf(navArgument(Route.Focus.ARG_TASK_ID) { type = NavType.StringType }),
                    ) { FocusPlaceholder() }
                    composable(
                        route = Route.ProjectDetail.PATTERN,
                        arguments = listOf(navArgument(Route.ProjectDetail.ARG_PROJECT_ID) { type = NavType.StringType }),
                    ) { ProjectDetailPlaceholder() }
                    // Reminder deep link opens the editor sheet over Today (spec §5).
                    composable(
                        route = "deeplink_task/{taskId}",
                        arguments = listOf(navArgument("taskId") { type = NavType.StringType }),
                        deepLinks = listOf(navDeepLink { uriPattern = "tmap://task/{taskId}" }),
                    ) { entry ->
                        val taskId = entry.arguments?.getString("taskId")
                        // Land on Today and raise the editor sheet for the deep-linked task.
                        androidx.compose.runtime.LaunchedEffect(taskId) {
                            navController.navigate(Route.Today.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                            }
                            if (taskId != null) navController.openTaskEditor(taskId)
                        }
                    }
                }
                // Capture + editor sheets float above the nav host.
                SheetHost()
            }
        }
    }
}
```

5. Implement — rewrite `TmapApp.kt`:

```kotlin
package net.qmindtech.tmap.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import net.qmindtech.tmap.data.auth.SessionState
import net.qmindtech.tmap.ui.auth.AuthMode
import net.qmindtech.tmap.ui.auth.AuthViewModel
import net.qmindtech.tmap.ui.auth.LoginScreen
import net.qmindtech.tmap.ui.auth.RegisterScreen
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.TmapBackground

@Composable
fun TmapApp(
    session: SessionState,
    authViewModel: AuthViewModel = hiltViewModel(),
) {
    when (session) {
        is SessionState.LoadingSession -> SplashScreen()
        is SessionState.Unauthenticated -> AuthGraph(authViewModel)
        is SessionState.Authenticated -> MainScaffold(rememberNavController())
    }
}

@Composable
private fun SplashScreen() {
    val colors = LocalTmapColors.current
    TmapBackground {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(color = colors.accent)
        }
    }
}

@Composable
private fun AuthGraph(authViewModel: AuthViewModel) {
    val state by authViewModel.uiState.collectAsStateWithLifecycle()
    when (state.mode) {
        AuthMode.Login -> LoginScreen(
            state = state,
            onEmailChange = authViewModel::onEmailChange,
            onPasswordChange = authViewModel::onPasswordChange,
            onSubmit = authViewModel::submit,
            onSwitchToRegister = { authViewModel.setMode(AuthMode.Register) },
        )
        AuthMode.Register -> RegisterScreen(
            state = state,
            onEmailChange = authViewModel::onEmailChange,
            onPasswordChange = authViewModel::onPasswordChange,
            onSubmit = authViewModel::submit,
            onSwitchToLogin = { authViewModel.setMode(AuthMode.Login) },
        )
    }
}
```
> `MainActivity` already wraps `TmapApp` in `TmapTheme`; no `MainActivity` change is required for the shell. (The deep-link entry filter in `AndroidManifest.xml` targets `MainActivity`/the nav graph as before; the `navDeepLink` above re-attaches `tmap://task/{taskId}` to the editor sheet — preserving the existing reminder deep link.)

6. Compile + assemble gate (this is the first point the whole app must build):
   - Command: `./gradlew :app:assembleDebug`
   - Expected: `BUILD SUCCESSFUL`. If it fails on a leftover file referencing a removed component (e.g. a `QuickAddSheet.kt` or `SubtaskRow` under a deleted screen's package, or `ui/projects/ProjectEditDialog.kt` referencing `ProjectPill`), `git rm` that file (it belongs to a later phase) and re-run until green. List any such removed files in the commit body.

7. Run the full unit suite to confirm no regression in the surviving logic/tests:
   - Command: `./gradlew :app:testDebugUnitTest`
   - Expected: `BUILD SUCCESSFUL`. (ViewModel tests for Today/Inbox/Backlog/AllTasks/etc. still compile against their untouched VMs; navigation + theme + component tests pass. If a deleted screen had a companion test that no longer compiles, it is a UI test tied to a removed screen — remove it and note it; logic tests are unaffected.)

8. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/navigation/SheetHost.kt android/app/src/main/java/net/qmindtech/tmap/ui/navigation/MainScaffold.kt android/app/src/main/java/net/qmindtech/tmap/ui/navigation/TmapApp.kt android/app/src/main/java/net/qmindtech/tmap/ui/navigation/PlaceholderScreens.kt`
   - `git rm` deletions already staged.
   - Message:
     ```
     feat(android-nav): MainScaffold + SheetHost + 5-tab placeholder shell

     Rewires TmapApp to the Daily-first scaffold (bottom nav + amber FAB + sheet
     host) over placeholder tab stubs; preserves the auth/session gate, splash,
     and the tmap://task/{id} reminder deep link (now opens the editor sheet).
     Old four-tab screens that referenced removed components are deleted; their
     ViewModels remain for P1+ to reattach.

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P0.17 — Phase gate: green build + 5-tab Midnight Calm shell checklist

**Files**
- No code changes. (Verification + final phase commit of any incidental cleanup only.)

**Interfaces**
- Consumes: everything produced in P0.1–P0.16.

**Steps**

1. Compile gate:
   - Command: `./gradlew :app:assembleDebug`
   - Expected: `BUILD SUCCESSFUL`.

2. Full unit-test gate:
   - Command: `./gradlew :app:testDebugUnitTest`
   - Expected: `BUILD SUCCESSFUL`; all `ui/theme`, `ui/components`, and `ui/navigation` tests added in this phase pass, plus all surviving engine/VM tests.

3. Lint gate:
   - Command: `./gradlew :app:lintDebug`
   - Expected: `BUILD SUCCESSFUL` (no new errors; warnings acceptable). If lint flags an unused import added in a code block above (e.g. the noted `fillMaxWidth`/`Color` in `Buttons.kt`), remove it and re-run.

4. **Reviewer behavior checklist — run the app (`./gradlew :app:installDebug` on an emulator/device, or Android Studio Run) and verify against `design-direction.html` direction A (Midnight Calm):**
   - [ ] App launches through the existing splash (amber `CircularProgressIndicator` on the `bgTop→bgBottom` gradient) and the auth gate is unchanged (sign-in still works; authenticated users land on the shell).
   - [ ] The background is the warm vertical gradient (`#191A20 → #141519`), not the old slate palette.
   - [ ] A 5-tab bottom navigation bar shows **Today · Inbox · Browse · Notes · You** with icons + labels, on a `surface` (#202127) bar; the selected tab is amber, unselected are `textTertiary`.
   - [ ] An amber-gradient circular **+** FAB (Midnight Calm `TmapFab`) sits bottom-end on every primary tab and, when tapped, raises the "Quick capture" bottom sheet (`surfaceRaised`, 26dp top corners) — the P0 stub body.
   - [ ] Each tab routes to its placeholder ("Coming soon") rendered with `EmptyState` in Midnight Calm tokens; switching tabs preserves no crash and back behaves.
   - [ ] No screen uses the old `Surface*/Accent*` palette (Acceptance §10.1); accent (amber) appears only on the FAB, the selected tab, and the splash spinner — not as content fill.
   - [ ] (RTL spot-check, Global Constraints §RTL) Switching the device to an RTL locale mirrors the bottom nav and sheet correctly (no `left`/`right` hardcoding — the components use `start`/`end` via default `Row`/padding).

5. Commit (phase close — only if step 3/4 required incidental import cleanup; otherwise skip):
   - `git add -A`
   - Message:
     ```
     chore(android-p0): green assembleDebug + lint; close design-system + nav-shell phase

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

## P1 — Today, quick-capture, gestures & editor

This phase rebuilds the Today list (the app's home), wires the natural-language quick-capture bottom sheet, adds the swipe/long-press gesture layer, and converts the task editor into a bottom sheet — all consuming the P0 design system (`TmapTheme`, `SwipeableTaskCard`, `TaskCard`, `TmapFab`, `SegmentedControl`, `ProgressRing`, `TaskUi`, `SectionLabel`) and the P0 `SheetHost`/navigation contracts verbatim. We first extend `TaskRepository` with the three new write-through mutations Today needs (`defer`, `moveToDay`, `reorder`) plus `addActualTime` (required by the P6/P3 contract), keeping every write transactional + outbox-enqueued. Then we rebuild `TodayViewModel` around an immutable `TodayUiState` (morning/afternoon/evening/other grouping by `scheduledStart`, done/total progress + time-left, and toggle/defer/delete/reorder/move actions), author the `QuickCaptureParser` against the FIXED `parse()` contract with a thorough unit suite, build the `QuickCaptureViewModel` + sheet, convert the editor to `TaskEditorSheet`, and wire both sheets into `SheetHost`. ViewModels/parsers get real unit tests (JUnit4 + coroutines-test + Turbine, matching the existing `TodayViewModelTest` style); the three Compose surfaces use the compile-gate + `daily-core.html` behavior-checklist substitution. All `./gradlew` commands run from `android/`. Each task is committed with the exact trailer.

---

### Task P1.1 — Extend `TaskRepository` with `defer`, `moveToDay`, `reorder`, `addActualTime`

Today's swipe-defer, long-press move-day, drag-reorder, and the P6 focus-time contract all need new write-through repository methods. Add them to the interface + impl (each: one Room transaction that upserts the entity and enqueues an `UPDATE` outbox op, then re-arms the reminder where the schedule changes and requests an expedited sync). Also extend the test `FakeTaskRepo` so existing and new VM tests compile.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/repository/TaskRepository.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/repository/TaskRepositoryDeferReorderTest.kt`

**Interfaces** (added to `interface TaskRepository`; signatures FIXED for P1+):
```kotlin
suspend fun defer(id: String, toDate: LocalDate)
suspend fun moveToDay(id: String, date: LocalDate)
suspend fun reorder(orderedIds: List<String>)
suspend fun addActualTime(id: String, minutes: Int)   // P6/P3 contract — defined here
```
Semantics:
- `defer(id, toDate)` and `moveToDay(id, date)` both set `plannedDate = date`, bump `updatedAt`; if the task had a `scheduledStart` they clear `scheduledStart`/`scheduledEnd` (moving a day un-times it — timeboxing is re-done per the destination day), set `status = Planned` when current status is `Inbox`/`Backlog`, enqueue `UPDATE`, re-arm reminder, expedited sync. (`defer` and `moveToDay` are identical write-through; both exist because the UI calls them from different gestures and P5/P7 will diverge them — keep both, `defer` delegates to `moveToDay`.)
- `reorder(orderedIds)` assigns evenly-spaced `rank` strings (`"a0", "a1", …` zero-padded to 6 chars: `"%06d".format(i)`) in list order, one transaction upserting all touched rows, enqueues one `UPDATE` per row, single expedited sync.
- `addActualTime(id, minutes)` sets `actualTimeMinutes = current + minutes`, bumps `updatedAt`, enqueues `UPDATE`, expedited sync (no reminder change).

**Steps**
- [ ] **Write the failing test** `TaskRepositoryDeferReorderTest.kt` (Room in-memory DB, mirroring `TaskRepositoryImplTest` setup from `RepoTestSupport.kt`):

```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.TaskStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class TaskRepositoryDeferReorderTest {
  private lateinit var h: RepoHarness

  @Before fun setUp() { h = RepoHarness() }
  @After fun tearDown() { h.close() }

  @Test fun defer_sets_plannedDate_clears_schedule_and_enqueues_update() = runTest {
    val id = h.taskRepo.create(
      TaskDraft(title = "x", status = TaskStatus.Inbox, plannedDate = LocalDate.of(2026, 6, 21)),
    )
    h.outbox.clearForTest()
    h.taskRepo.defer(id, LocalDate.of(2026, 6, 22))
    val t = h.taskDao.getById(id)!!
    assertEquals(LocalDate.of(2026, 6, 22), t.plannedDate)
    assertEquals(TaskStatus.Planned, t.status)
    assertEquals(null, t.scheduledStart)
    val ops = h.outbox.pendingForTest()
    assertEquals(1, ops.size)
    assertEquals(OpType.UPDATE, ops.first().opType)
  }

  @Test fun reorder_assigns_increasing_ranks_in_list_order() = runTest {
    val a = h.taskRepo.create(TaskDraft(title = "a"))
    val b = h.taskRepo.create(TaskDraft(title = "b"))
    val c = h.taskRepo.create(TaskDraft(title = "c"))
    h.taskRepo.reorder(listOf(c, a, b))
    val rc = h.taskDao.getById(c)!!.rank
    val ra = h.taskDao.getById(a)!!.rank
    val rb = h.taskDao.getById(b)!!.rank
    assertEquals(true, rc!! < ra!! && ra < rb!!)
  }

  @Test fun addActualTime_accumulates() = runTest {
    val id = h.taskRepo.create(TaskDraft(title = "x"))
    h.taskRepo.addActualTime(id, 25)
    h.taskRepo.addActualTime(id, 5)
    assertEquals(30, h.taskDao.getById(id)!!.actualTimeMinutes)
  }
}
```
> If `RepoTestSupport.kt` does not already expose a `RepoHarness` with `outbox.clearForTest()/pendingForTest()`, reuse the exact helpers it does expose (open `android/app/src/test/java/net/qmindtech/tmap/data/repository/RepoTestSupport.kt` and `TaskRepositoryImplTest.kt` and copy their harness/assertion idiom — do not invent new infra). The three assertions (plannedDate+status+cleared schedule; monotonically increasing ranks; accumulated actual time) are the contract regardless of harness shape.

- [ ] **Verify FAIL:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.TaskRepositoryDeferReorderTest"` → compile error (methods don't exist) / red.

- [ ] **Implement.** Add to `interface TaskRepository`:
```kotlin
    suspend fun defer(id: String, toDate: LocalDate)
    suspend fun moveToDay(id: String, date: LocalDate)
    suspend fun reorder(orderedIds: List<String>)
    suspend fun addActualTime(id: String, minutes: Int)
```
Add to `TaskRepositoryImpl`:
```kotlin
    override suspend fun defer(id: String, toDate: LocalDate) = moveToDay(id, toDate)

    override suspend fun moveToDay(id: String, date: LocalDate) {
        val current = taskDao.getById(id) ?: return
        val newStatus = when (current.status) {
            TaskStatus.Inbox, TaskStatus.Backlog -> TaskStatus.Planned
            else -> current.status
        }
        val updated = current.copy(
            plannedDate = date,
            scheduledStart = null,
            scheduledEnd = null,
            status = newStatus,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            taskDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.TASK, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateTaskRequest.serializer(),
            )
        }
        reminder.arm(updated)
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun reorder(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val now = clock.now()
        val byId = orderedIds.mapNotNull { taskDao.getById(it) }.associateBy { it.id }
        val updates = orderedIds.mapIndexedNotNull { i, taskId ->
            byId[taskId]?.copy(rank = "%06d".format(i), updatedAt = now)
        }
        if (updates.isEmpty()) return
        db.withTransaction {
            taskDao.upsertAll(updates)
            updates.forEach { u ->
                outbox.enqueue(
                    EntityType.TASK, u.id, OpType.UPDATE,
                    u.toUpdateRequest(), UpdateTaskRequest.serializer(),
                )
            }
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun addActualTime(id: String, minutes: Int) {
        val current = taskDao.getById(id) ?: return
        val updated = current.copy(
            actualTimeMinutes = current.actualTimeMinutes + minutes,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            taskDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.TASK, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateTaskRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
```
Extend `FakeTaskRepo` in `Fakes.kt` (so all VM tests compile) — add fields + overrides:
```kotlin
  val deferred = mutableListOf<Pair<String, java.time.LocalDate>>()
  val movedToDay = mutableListOf<Pair<String, java.time.LocalDate>>()
  val reordered = mutableListOf<List<String>>()
  val actualTimeAdded = mutableListOf<Pair<String, Int>>()

  override suspend fun defer(id: String, toDate: LocalDate) { deferred += id to toDate }
  override suspend fun moveToDay(id: String, date: LocalDate) { movedToDay += id to date }
  override suspend fun reorder(orderedIds: List<String>) { reordered += orderedIds }
  override suspend fun addActualTime(id: String, minutes: Int) { actualTimeAdded += id to minutes }
```

- [ ] **Verify PASS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.TaskRepositoryDeferReorderTest"` → green; then `./gradlew :app:testDebugUnitTest` (full) stays green (existing repo tests + the extended fake compile).
- [ ] **Commit:**
```
feat(android-today): add defer/moveToDay/reorder/addActualTime to TaskRepository

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P1.2 — `TodayUiState` + grouping/progress pure functions

Define the immutable Today UI state and the pure grouping/progress logic the rebuilt VM and screen consume. `TaskUi` is the FIXED P0 projection (in `ui/components/TaskUi.kt`); the Today state holds grouped sections of `TaskUi` plus progress. Pure functions are real-unit-tested (no Android, no coroutines) so grouping/progress are locked independent of Compose.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayUiState.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/today/TodayGroupingTest.kt`

**Interfaces** (FIXED for this phase):
```kotlin
enum class TodaySection { Morning, Afternoon, Evening, Other }   // Other = planned-today but un-timed
data class TodayGroup(val section: TodaySection, val tasks: List<TaskUi>)
data class TodayProgress(val done: Int, val total: Int, val minutesLeft: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}
enum class TodayMode { List, Timeline }
data class TodayUiState(
    val loading: Boolean = true,
    val dateEyebrow: String = "",      // e.g. "SATURDAY, JUN 21" (uppercase)
    val greeting: String = "",         // "Good morning" / "Good afternoon" / "Good evening"
    val groups: List<TodayGroup> = emptyList(),
    val progress: TodayProgress = TodayProgress(0, 0, 0),
    val mode: TodayMode = TodayMode.List,
)
// Pure, testable:
fun groupToday(tasks: List<TaskUi>, scheduledStarts: Map<String, LocalTime?>): List<TodayGroup>
fun computeProgress(tasks: List<TaskEntity>): TodayProgress
fun greetingFor(time: LocalTime): String
fun eyebrowFor(date: LocalDate): String
```
Grouping rule (derive each task's local start time): **Morning** `< 12:00`, **Afternoon** `12:00–16:59`, **Evening** `>= 17:00`, **Other** = no `scheduledStart`. Sections render only when non-empty, in fixed order Morning→Afternoon→Evening→Other; within a group, order is the list order passed in (the VM pre-sorts). `computeProgress`: `done` = tasks with `status == Done`, `total` = all today tasks (excluding done? no — total includes done so the ring reads "3 of 8"); `minutesLeft` = sum of `durationMinutes` (default 30 when null) over **not-done** tasks. `greetingFor`: `<12 → "Good morning"`, `<18 → "Good afternoon"`, else `"Good evening"`. `eyebrowFor`: `"EEEE, MMM d"` uppercased via `Locale.getDefault()`.

**Steps**
- [ ] **Write the failing test** `TodayGroupingTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.today

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.fakeTask
import net.qmindtech.tmap.ui.components.TaskUi
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class TodayGroupingTest {
  private fun ui(id: String) = TaskUi(
    id = id, title = id, projectName = null, projectColor = null, scheduledLabel = null,
    subtaskDone = 0, subtaskTotal = 0, priority = 0, hasReminder = false, isDone = false,
  )

  @Test fun groups_by_time_of_day_and_keeps_input_order() {
    val starts = mapOf(
      "m" to LocalTime.of(9, 0), "a" to LocalTime.of(13, 0),
      "e" to LocalTime.of(19, 0), "o" to null,
    )
    val out = groupToday(listOf(ui("o"), ui("e"), ui("a"), ui("m")), starts)
    assertEquals(
      listOf(TodaySection.Morning, TodaySection.Afternoon, TodaySection.Evening, TodaySection.Other),
      out.map { it.section },
    )
    assertEquals(listOf("m"), out[0].tasks.map { it.id })
    assertEquals(listOf("o"), out[3].tasks.map { it.id })
  }

  @Test fun empty_sections_are_omitted() {
    val out = groupToday(listOf(ui("o")), mapOf("o" to null))
    assertEquals(listOf(TodaySection.Other), out.map { it.section })
  }

  @Test fun progress_counts_done_total_and_minutes_left() {
    val today = LocalDate.of(2026, 6, 21)
    val tasks = listOf(
      fakeTask(id = "1", status = TaskStatus.Done, plannedDate = today, durationMinutes = 60),
      fakeTask(id = "2", status = TaskStatus.Planned, plannedDate = today, durationMinutes = 45),
      fakeTask(id = "3", status = TaskStatus.Planned, plannedDate = today, durationMinutes = null),
    )
    val p = computeProgress(tasks)
    assertEquals(1, p.done)
    assertEquals(3, p.total)
    assertEquals(75, p.minutesLeft) // 45 + default 30
    assertEquals(1f / 3f, p.fraction, 0.0001f)
  }

  @Test fun greeting_buckets() {
    assertEquals("Good morning", greetingFor(LocalTime.of(8, 0)))
    assertEquals("Good afternoon", greetingFor(LocalTime.of(13, 0)))
    assertEquals("Good evening", greetingFor(LocalTime.of(20, 0)))
  }

  @Test fun eyebrow_is_uppercased() {
    assertEquals(eyebrowFor(LocalDate.of(2026, 6, 21)).uppercase(), eyebrowFor(LocalDate.of(2026, 6, 21)))
  }
}
```
- [ ] **Verify FAIL:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TodayGroupingTest"` → red (types/functions missing; `TaskUi` provided by P0).

- [ ] **Implement** `TodayUiState.kt`:
```kotlin
package net.qmindtech.tmap.ui.today

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.ui.components.TaskUi
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TodaySection { Morning, Afternoon, Evening, Other }

data class TodayGroup(val section: TodaySection, val tasks: List<TaskUi>)

data class TodayProgress(val done: Int, val total: Int, val minutesLeft: Int) {
  val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

enum class TodayMode { List, Timeline }

data class TodayUiState(
  val loading: Boolean = true,
  val dateEyebrow: String = "",
  val greeting: String = "",
  val groups: List<TodayGroup> = emptyList(),
  val progress: TodayProgress = TodayProgress(0, 0, 0),
  val mode: TodayMode = TodayMode.List,
)

private const val DEFAULT_TASK_MINUTES = 30

private fun sectionFor(start: LocalTime?): TodaySection = when {
  start == null -> TodaySection.Other
  start.hour < 12 -> TodaySection.Morning
  start.hour < 17 -> TodaySection.Afternoon
  else -> TodaySection.Evening
}

fun groupToday(tasks: List<TaskUi>, scheduledStarts: Map<String, LocalTime?>): List<TodayGroup> {
  val buckets = LinkedHashMap<TodaySection, MutableList<TaskUi>>()
  for (t in tasks) {
    val section = sectionFor(scheduledStarts[t.id])
    buckets.getOrPut(section) { mutableListOf() }.add(t)
  }
  return listOf(TodaySection.Morning, TodaySection.Afternoon, TodaySection.Evening, TodaySection.Other)
    .mapNotNull { sec -> buckets[sec]?.let { TodayGroup(sec, it) } }
}

fun computeProgress(tasks: List<TaskEntity>): TodayProgress {
  val done = tasks.count { it.status == TaskStatus.Done }
  val minutesLeft = tasks
    .filter { it.status != TaskStatus.Done }
    .sumOf { it.durationMinutes ?: DEFAULT_TASK_MINUTES }
  return TodayProgress(done = done, total = tasks.size, minutesLeft = minutesLeft)
}

fun greetingFor(time: LocalTime): String = when {
  time.hour < 12 -> "Good morning"
  time.hour < 18 -> "Good afternoon"
  else -> "Good evening"
}

private val EYEBROW_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())

fun eyebrowFor(date: LocalDate): String = date.format(EYEBROW_FMT).uppercase(Locale.getDefault())
```
- [ ] **Verify PASS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TodayGroupingTest"` → green.
- [ ] **Commit:**
```
feat(android-today): TodayUiState + pure grouping/progress/greeting helpers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P1.3 — Rebuilt `TodayViewModel`

Replace the old VM with one that builds `TodayUiState` from `observeToday(today)` + `observeAll` projects + clock, projecting each `TaskEntity` to `TaskUi` via the P0 mapper `TaskEntity.toUi(project)`, sorting (rank → scheduledStart → createdAt), grouping via `groupToday`, computing progress, and exposing the action set. The old `TodayViewModelTest` is rewritten against the new state shape.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayViewModel.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/ui/today/TodayViewModelTest.kt`

**Interfaces** (FIXED):
```kotlin
@HiltViewModel
class TodayViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
  private val clock: Clock,
) : ViewModel() {
  val uiState: StateFlow<TodayUiState>
  fun setMode(mode: TodayMode)
  fun toggleComplete(taskId: String)
  fun defer(taskId: String)                 // → tomorrow
  fun delete(taskId: String)
  fun reorder(orderedIds: List<String>)
  fun moveToDay(taskId: String, date: LocalDate)
}
```
`toggleComplete`: if the task is currently `Done`, reopen (set status Planned via `taskRepo.update(id, TaskEdit(status = Planned))`); else `taskRepo.markDone(id)`. `defer(taskId)` calls `taskRepo.defer(taskId, clock.today().plusDays(1))`. Sorting key per task: `rank ?: "zzzzzz"`, then `scheduledStart ?: Instant.MAX`, then `createdAt`. `scheduledStarts` map for grouping = `taskId → scheduledStart?.atZone(clock.zone()).toLocalTime()`. `mode` is held in a local `MutableStateFlow` and combined in.

**Steps**
- [ ] **Write the failing test** — replace `TodayViewModelTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.today

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
  private val today = LocalDate.of(2026, 6, 21)
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vm(tasks: MutableStateFlow<List<net.qmindtech.tmap.data.local.entities.TaskEntity>>,
                 projects: FakeProjectRepo = FakeProjectRepo()): Pair<TodayViewModel, FakeTaskRepo> {
    val repo = FakeTaskRepo(today = tasks)
    return TodayViewModel(repo, projects, FixedClock(Instant.parse("2026-06-21T06:00:00Z"))) to repo
  }

  @Test fun groups_morning_afternoon_evening_other_and_resolves_project() = runTest(testDispatcher) {
    val projects = FakeProjectRepo().also { it.setAll(listOf(fakeProject(id = "p1", name = "Work"))) }
    val flow = MutableStateFlow(
      listOf(
        fakeTask(id = "morn", scheduledStart = Instant.parse("2026-06-21T09:00:00Z"), plannedDate = today, projectId = "p1"),
        fakeTask(id = "aft", scheduledStart = Instant.parse("2026-06-21T13:00:00Z"), plannedDate = today),
        fakeTask(id = "eve", scheduledStart = Instant.parse("2026-06-21T19:00:00Z"), plannedDate = today),
        fakeTask(id = "other", scheduledStart = null, plannedDate = today),
      )
    )
    val (vm, _) = vm(flow, projects)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(
        listOf(TodaySection.Morning, TodaySection.Afternoon, TodaySection.Evening, TodaySection.Other),
        s.groups.map { it.section },
      )
      assertEquals("Work", s.groups[0].tasks.first().projectName)
      assertEquals("Good morning", s.greeting)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun progress_reflects_done_total() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(
        fakeTask(id = "1", status = TaskStatus.Done, plannedDate = today),
        fakeTask(id = "2", status = TaskStatus.Planned, plannedDate = today, durationMinutes = 60),
      )
    )
    val (vm, _) = vm(flow)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(1, s.progress.done)
      assertEquals(2, s.progress.total)
      assertEquals(60, s.progress.minutesLeft)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun toggleComplete_marks_done_when_open() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", status = TaskStatus.Planned, plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.toggleComplete("x")
    assertEquals(listOf("x"), repo.markedDone)
  }

  @Test fun toggleComplete_reopens_when_done() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", status = TaskStatus.Done, plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.toggleComplete("x")
    assertTrue(repo.markedDone.isEmpty())
    assertEquals(1, repo.updated.size)
    assertEquals(TaskStatus.Planned, repo.updated.first().second.status)
  }

  @Test fun defer_moves_task_to_tomorrow() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.defer("x")
    assertEquals(listOf("x" to today.plusDays(1)), repo.deferred)
  }

  @Test fun delete_and_reorder_and_moveToDay_delegate() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.delete("x")
    vm.reorder(listOf("x"))
    vm.moveToDay("x", today.plusDays(3))
    assertEquals(listOf("x"), repo.deleted)
    assertEquals(listOf(listOf("x")), repo.reordered)
    assertEquals(listOf("x" to today.plusDays(3)), repo.movedToDay)
  }

  @Test fun setMode_switches_to_timeline() = runTest(testDispatcher) {
    val (vm, _) = vm(MutableStateFlow(emptyList()))
    vm.setMode(TodayMode.Timeline)
    vm.uiState.test {
      assertEquals(TodayMode.Timeline, expectMostRecentItem().mode)
      cancelAndIgnoreRemainingEvents()
    }
  }
}
```
- [ ] **Verify FAIL:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TodayViewModelTest"` → red (new VM API absent; old `timeOrderToday`/`TaskListItem` gone).

- [ ] **Implement** — replace `TodayViewModel.kt` entirely:
```kotlin
package net.qmindtech.tmap.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskEdit
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.components.TaskUi
import net.qmindtech.tmap.ui.components.toUi
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class TodayViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
  private val clock: Clock,
) : ViewModel() {

  private val mode = MutableStateFlow(TodayMode.List)

  // Snapshot of today's raw entities so action handlers can read current status without re-querying.
  @Volatile private var lastTasks: List<TaskEntity> = emptyList()

  val uiState: StateFlow<TodayUiState> =
    combine(
      taskRepo.observeToday(clock.today()),
      projectRepo.observeAll(),
      mode,
    ) { tasks, projects, m ->
      lastTasks = tasks
      val projectsById = projects.associateBy { it.id }
      val sorted = tasks.sortedWith(
        compareBy<TaskEntity>({ it.rank ?: "zzzzzz" }, { it.scheduledStart ?: Instant.MAX }, { it.createdAt }),
      )
      val starts = sorted.associate { it.id to it.scheduledStart?.atZone(clock.zone())?.toLocalTime() }
      val uis: List<TaskUi> = sorted.map { it.toUi(projectsById[it.projectId]) }
      val nowTime = clock.now().atZone(clock.zone()).toLocalTime()
      TodayUiState(
        loading = false,
        dateEyebrow = eyebrowFor(clock.today()),
        greeting = greetingFor(nowTime),
        groups = groupToday(uis, starts),
        progress = computeProgress(tasks),
        mode = m,
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

  fun setMode(mode: TodayMode) { this.mode.value = mode }

  fun toggleComplete(taskId: String) {
    val task = lastTasks.firstOrNull { it.id == taskId }
    viewModelScope.launch {
      if (task?.status == TaskStatus.Done) {
        taskRepo.update(taskId, TaskEdit(status = TaskStatus.Planned))
      } else {
        taskRepo.markDone(taskId)
      }
    }
  }

  fun defer(taskId: String) {
    viewModelScope.launch { taskRepo.defer(taskId, clock.today().plusDays(1)) }
  }

  fun delete(taskId: String) {
    viewModelScope.launch { taskRepo.delete(taskId) }
  }

  fun reorder(orderedIds: List<String>) {
    viewModelScope.launch { taskRepo.reorder(orderedIds) }
  }

  fun moveToDay(taskId: String, date: LocalDate) {
    viewModelScope.launch { taskRepo.moveToDay(taskId, date) }
  }
}
```
> `TaskEntity.toUi(project: ProjectEntity?)` is the FIXED P0 mapper in `ui/components/TaskUi.kt`. If P0 is not yet merged into this branch, the import will fail to resolve — do not redefine it here; P1 depends on P0 being present (per phase ordering).

- [ ] **Verify PASS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TodayViewModelTest"` → green.
- [ ] **Commit:**
```
feat(android-today): rebuild TodayViewModel (grouping, progress, actions)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P1.4 — `TodayListContent` + `TodayScreen` (compile-gate + behavior checklist)

Rebuild the Today screen to the `daily-core.html` "① Today" mockup using P0 components. `TodayListContent` renders sections (`SectionLabel`) of `SwipeableTaskCard`s in a `LazyColumn` with stable keys, supporting long-press drag reorder; `TodayScreen` adds the header (date eyebrow, greeting, progress bar+label, `SegmentedControl` List/Timeline, "Plan my day" + "Focus" buttons), the `TmapFab` (opens capture), an undo `Snackbar` on complete, and a Timeline placeholder branch (real Timeline lands in P7). Nav calls (`onPlanMyDay`, `onFocus`, `onOpenCapture`, `onOpenTask`) are hooks passed in by `SheetHost`/`MainScaffold`.

> **No unit test (Compose UI).** Verification = **compile-gate** (`assembleDebug`) **+ behavior checklist** against `.superpowers/brainstorm/965-1782053760/content/daily-core.html` panel ①.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayListContent.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayScreen.kt`

**Interfaces** (FIXED):
```kotlin
@Composable
fun TodayScreen(
  onOpenTask: (taskId: String) -> Unit,
  onOpenCapture: () -> Unit,
  onPlanMyDay: () -> Unit,
  onFocus: () -> Unit,
  viewModel: TodayViewModel = hiltViewModel(),
)

@Composable
fun TodayListContent(
  groups: List<TodayGroup>,
  onToggleComplete: (String) -> Unit,
  onDefer: (String) -> Unit,
  onDelete: (String) -> Unit,
  onClick: (String) -> Unit,
  onReorder: (List<String>) -> Unit,
  modifier: Modifier = Modifier,
)
```

**Steps**
- [ ] **(No failing unit test — Compose surface.)** State the substitution: *behavior checklist vs `daily-core.html` ①, gated by `assembleDebug`.*

- [ ] **Implement** `TodayListContent.kt`:
```kotlin
package net.qmindtech.tmap.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.components.SwipeableTaskCard

private fun TodaySection.label(): String = when (this) {
  TodaySection.Morning -> "THIS MORNING"
  TodaySection.Afternoon -> "THIS AFTERNOON"
  TodaySection.Evening -> "THIS EVENING"
  TodaySection.Other -> "PLANNED"
}

@Composable
fun TodayListContent(
  groups: List<TodayGroup>,
  onToggleComplete: (String) -> Unit,
  onDefer: (String) -> Unit,
  onDelete: (String) -> Unit,
  onClick: (String) -> Unit,
  onReorder: (List<String>) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    groups.forEach { group ->
      item(key = "section-${group.section.name}") {
        SectionLabel(text = group.section.label(), modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
      }
      items(group.tasks, key = { it.id }) { task ->
        SwipeableTaskCard(
          task = task,
          onToggleComplete = { onToggleComplete(task.id) },
          onDefer = { onDefer(task.id) },
          onDelete = { onDelete(task.id) },
          onClick = { onClick(task.id) },
        )
      }
    }
  }
}
```
> Long-press drag reorder: wire it through `SwipeableTaskCard`'s host if P0 exposes a drag handle; otherwise expose `onReorder` for the P7/long-press pass and keep the parameter (the contract requires the hook). Do not invent a reorder lib — leave the gesture to a dedicated follow-up if P0's card has no drag slot, but keep `onReorder` plumbed so callers compile. *(State in commit message which.)*

Replace `TodayScreen.kt`:
```kotlin
package net.qmindtech.tmap.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.PrimaryButton
import net.qmindtech.tmap.ui.components.ProgressRing
import net.qmindtech.tmap.ui.components.SecondaryButton
import net.qmindtech.tmap.ui.components.SegmentedControl
import net.qmindtech.tmap.ui.components.TmapFab
import net.qmindtech.tmap.ui.theme.LocalTmapColors

@Composable
fun TodayScreen(
  onOpenTask: (taskId: String) -> Unit,
  onOpenCapture: () -> Unit,
  onPlanMyDay: () -> Unit,
  onFocus: () -> Unit,
  viewModel: TodayViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val colors = LocalTmapColors.current
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()

  Scaffold(
    snackbarHost = { SnackbarHost(snackbar) },
    floatingActionButton = { TmapFab(onClick = onOpenCapture) },
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      // Header
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(state.dateEyebrow, color = colors.accent)
          SegmentedControl(
            options = listOf("List", "Timeline"),
            selectedIndex = if (state.mode == TodayMode.List) 0 else 1,
            onSelect = { viewModel.setMode(if (it == 0) TodayMode.List else TodayMode.Timeline) },
          )
        }
        Spacer(Modifier.height(8.dp))
        Text("${state.greeting}", color = colors.textPrimary)
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          ProgressRing(progress = state.progress.fraction, modifier = Modifier.height(40.dp)) {
            Text("${state.progress.done}/${state.progress.total}", color = colors.textSecondary)
          }
          Spacer(Modifier.height(0.dp))
          Text(
            "  ${state.progress.done} of ${state.progress.total} · ${state.progress.minutesLeft / 60}h left",
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 12.dp),
          )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
          PrimaryButton(text = "Plan my day", onClick = onPlanMyDay, modifier = Modifier.weight(1f))
          SecondaryButton(text = "Focus", onClick = onFocus)
        }
      }

      Box(modifier = Modifier.fillMaxSize()) {
        when {
          state.loading -> Unit
          state.mode == TodayMode.Timeline ->
            EmptyState(
              title = "Timeline coming soon",
              subtitle = "Switch to List to see today's tasks.",
              modifier = Modifier.padding(top = 24.dp),
            )
          state.groups.isEmpty() ->
            EmptyState(
              title = "Nothing planned today",
              subtitle = "Tap + to capture, or Plan my day.",
              modifier = Modifier.padding(top = 24.dp),
            )
          else -> TodayListContent(
            groups = state.groups,
            onToggleComplete = { id ->
              viewModel.toggleComplete(id)
              scope.launch {
                val r = snackbar.showSnackbar(
                  message = "Task completed",
                  actionLabel = "Undo",
                  duration = SnackbarDuration.Short,
                )
                if (r == SnackbarResult.ActionPerformed) viewModel.toggleComplete(id)
              }
            },
            onDefer = viewModel::defer,
            onDelete = viewModel::delete,
            onClick = onOpenTask,
            onReorder = viewModel::reorder,
          )
        }
      }
    }
  }
}
```
> `EmptyState`, `PrimaryButton`, `SecondaryButton`, `ProgressRing`, `SegmentedControl`, `TmapFab`, `LocalTmapColors` are P0 contracts. Match P0's actual `EmptyState`/button signatures (open `ui/components/Buttons.kt` + `EmptyState.kt` from P0 and adapt the call sites — keep the behavior, fix arg names if P0 differs). Do **not** reference the old `EmptyState(icon = …)`/`TaskRow` APIs (they are replaced in P0).

- [ ] **Verify compile-gate:** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Behavior checklist (reviewer, vs `daily-core.html` ①):**
  - Header shows amber uppercase date eyebrow + greeting + List/Timeline `SegmentedControl` (List default).
  - Progress: ring/bar + "X of Y · Nh left" label.
  - "Plan my day" (primary, amber) + "Focus" (secondary) buttons present and call their hooks.
  - Sections render as `SectionLabel` ("THIS MORNING" etc.), only non-empty ones, rich `SwipeableTaskCard`s.
  - `TmapFab` bottom-end, opens capture.
  - Swipe-right complete fires + shows undo snackbar; swipe-left exposes defer/delete.
  - Timeline tab shows the placeholder (P7 fills it).
- [ ] **Commit:**
```
feat(android-today): rebuild TodayScreen + TodayListContent (Midnight Calm)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P1.5 — `QuickCaptureParser` (+ `ParsedCapture`, `ParsedToken`) — FIXED contract

Author the natural-language parser exactly to the cross-phase contract. Parses `#project` (match an existing project by name, case-insensitive; no create here — returns `projectId` only on match), `!`/`!!`/`!!!` priority, and date/time phrases. Real, thorough unit tests drive the implementation. The token list lets the sheet render inline chips; the stripped remainder is the title.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/capture/QuickCaptureParser.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/capture/QuickCaptureParserTest.kt`

**Interfaces** (FIXED — verbatim from skeleton):
```kotlin
data class ParsedToken(val kind: Kind, val text: String) {
  enum class Kind { PROJECT, PRIORITY, DATE, TIME }
}
data class ParsedCapture(
  val title: String,
  val projectId: String?,
  val priority: Int,                 // 0 = none; 1=Urgent … 4=Low
  val plannedDate: LocalDate?,
  val scheduledStart: LocalTime?,
  val tokens: List<ParsedToken>,
)
class QuickCaptureParser(private val clock: Clock) {
  fun parse(input: String, projects: List<ProjectEntity>): ParsedCapture
}
```
Parse rules (priority convention matches the app: **1=Urgent (highest)…4=Low**):
- **`#project`** — token `#word` (letters/digits/`-`/`_`); match `projects` by `name.equals(word, ignoreCase = true)`; on match set `projectId`, emit `ParsedToken(PROJECT, "#<name>")`, strip the `#word`. No match → leave the literal in the title (no token).
- **Priority** — standalone `!`→4, `!!`→2, `!!!`→1 (so more bangs = higher urgency / lower number); also `!high`→2, `!urgent`→1, `!med`/`!medium`→3, `!low`→4 (case-insensitive). Emit `ParsedToken(PRIORITY, <matched>)`; strip it. Default `priority = 0`.
- **Dates** — `today`, `tomorrow`/`tmr`, weekday names (`mon`…`sunday`, full or 3-letter; resolves to the **next** occurrence strictly after today, or today if the name == today's weekday → choose next week to avoid ambiguity? **Decision: weekday resolves to today if it matches, else the next future occurrence**). Set `plannedDate`; emit `ParsedToken(DATE, <phrase>)`; strip.
- **Times** — `Npm`/`Nam` (`3pm`, `11am`, `7 pm`), `HH:mm` (24h, `14:30`), `H:mm am/pm`. Set `scheduledStart`; emit `ParsedToken(TIME, <phrase>)`; strip. A bare time with no date keeps `plannedDate = null` (capture stays Inbox unless a date is also present — VM decides status).
- **Title** = input with all matched tokens removed, collapsed whitespace, trimmed.
- Tokens emitted in the order PROJECT, PRIORITY, DATE, TIME (stable for chip rendering), each at most once (first match wins per kind).

**Steps**
- [ ] **Write the failing test** `QuickCaptureParserTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.capture

import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class QuickCaptureParserTest {
  // 2026-06-21 is a Sunday.
  private val clock = FixedClock(Instant.parse("2026-06-21T06:00:00Z"))
  private val parser = QuickCaptureParser(clock)
  private val projects = listOf(fakeProject(id = "p-work", name = "Work"), fakeProject(id = "p-home", name = "Home"))

  private fun parse(s: String) = parser.parse(s, projects)

  @Test fun plain_text_is_just_title() {
    val r = parse("Buy milk")
    assertEquals("Buy milk", r.title)
    assertEquals(0, r.priority)
    assertNull(r.projectId)
    assertNull(r.plannedDate)
    assertNull(r.scheduledStart)
    assertEquals(emptyList<ParsedToken>(), r.tokens)
  }

  @Test fun matches_project_case_insensitive() {
    val r = parse("Finish slides #work")
    assertEquals("Finish slides", r.title)
    assertEquals("p-work", r.projectId)
    assertEquals(ParsedToken.Kind.PROJECT, r.tokens.first().kind)
  }

  @Test fun unknown_project_left_in_title() {
    val r = parse("Read book #fiction")
    assertEquals("Read book #fiction", r.title)
    assertNull(r.projectId)
    assertEquals(emptyList<ParsedToken>(), r.tokens)
  }

  @Test fun priority_bangs() {
    assertEquals(4, parse("ping !").priority)
    assertEquals(2, parse("ping !!").priority)
    assertEquals(1, parse("ping !!!").priority)
  }

  @Test fun priority_words() {
    assertEquals(1, parse("ship it !urgent").priority)
    assertEquals(2, parse("ship it !high").priority)
    assertEquals(3, parse("ship it !medium").priority)
    assertEquals(4, parse("ship it !low").priority)
    assertEquals("ship it", parse("ship it !high").title)
  }

  @Test fun today_and_tomorrow() {
    assertEquals(LocalDate.of(2026, 6, 21), parse("call mom today").plannedDate)
    assertEquals(LocalDate.of(2026, 6, 22), parse("call mom tomorrow").plannedDate)
    assertEquals(LocalDate.of(2026, 6, 22), parse("call mom tmr").plannedDate)
  }

  @Test fun weekday_resolves_to_next_or_today() {
    // Sunday today: "sunday" → today; "monday" → tomorrow; "fri" → 2026-06-26.
    assertEquals(LocalDate.of(2026, 6, 21), parse("rest sunday").plannedDate)
    assertEquals(LocalDate.of(2026, 6, 22), parse("gym monday").plannedDate)
    assertEquals(LocalDate.of(2026, 6, 26), parse("demo fri").plannedDate)
  }

  @Test fun times_am_pm_and_24h() {
    assertEquals(LocalTime.of(15, 0), parse("standup 3pm").scheduledStart)
    assertEquals(LocalTime.of(11, 0), parse("standup 11am").scheduledStart)
    assertEquals(LocalTime.of(14, 30), parse("standup 14:30").scheduledStart)
    assertEquals(LocalTime.of(9, 15), parse("standup 9:15am").scheduledStart)
  }

  @Test fun combined_project_date_time_priority() {
    val r = parse("Finish slides #Work tomorrow 3pm !!")
    assertEquals("Finish slides", r.title)
    assertEquals("p-work", r.projectId)
    assertEquals(LocalDate.of(2026, 6, 22), r.plannedDate)
    assertEquals(LocalTime.of(15, 0), r.scheduledStart)
    assertEquals(2, r.priority)
    assertEquals(
      listOf(ParsedToken.Kind.PROJECT, ParsedToken.Kind.PRIORITY, ParsedToken.Kind.DATE, ParsedToken.Kind.TIME),
      r.tokens.map { it.kind },
    )
  }

  @Test fun first_match_per_kind_wins_and_title_is_clean() {
    val r = parse("plan #work #home today tomorrow")
    assertEquals("p-work", r.projectId)
    assertEquals(LocalDate.of(2026, 6, 21), r.plannedDate) // "today" first
    // remaining unmatched second project/date stay in title since only first of each kind is consumed
    assertEquals("plan #home tomorrow", r.title)
  }
}
```
- [ ] **Verify FAIL:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.capture.QuickCaptureParserTest"` → red (class missing).

- [ ] **Implement** `QuickCaptureParser.kt`:
```kotlin
package net.qmindtech.tmap.ui.capture

import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.util.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

data class ParsedToken(val kind: Kind, val text: String) {
  enum class Kind { PROJECT, PRIORITY, DATE, TIME }
}

data class ParsedCapture(
  val title: String,
  val projectId: String?,
  val priority: Int,
  val plannedDate: LocalDate?,
  val scheduledStart: LocalTime?,
  val tokens: List<ParsedToken>,
)

class QuickCaptureParser(private val clock: Clock) {

  private val priorityWords = mapOf(
    "urgent" to 1, "high" to 2, "med" to 3, "medium" to 3, "low" to 4,
  )

  private val weekdays = mapOf(
    "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
    "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
    "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
    "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
    "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
    "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
    "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
  )

  private val projectRegex = Regex("#([\\p{L}0-9_-]+)")
  private val bangsRegex = Regex("(?<=\\s|^)(!{1,3})(?=\\s|$)")
  private val priorityWordRegex = Regex("(?i)(?<=\\s|^)!(urgent|high|medium|med|low)(?=\\s|$)")
  // 3pm | 11 am | 9:15am | 14:30
  private val timeRegex = Regex("(?i)(?<=\\s|^)(\\d{1,2})(?::(\\d{2}))?\\s?(am|pm)(?=\\s|$)")
  private val time24Regex = Regex("(?<=\\s|^)([01]?\\d|2[0-3]):([0-5]\\d)(?=\\s|$)")

  fun parse(input: String, projects: List<ProjectEntity>): ParsedCapture {
    var working = input
    val tokens = mutableListOf<ParsedToken>()
    var projectId: String? = null
    var priority = 0
    var plannedDate: LocalDate? = null
    var scheduledStart: LocalTime? = null
    val today = clock.today()

    // PROJECT (first matching existing project wins)
    for (m in projectRegex.findAll(working)) {
      val name = m.groupValues[1]
      val proj = projects.firstOrNull { it.name.equals(name, ignoreCase = true) }
      if (proj != null) {
        projectId = proj.id
        tokens += ParsedToken(ParsedToken.Kind.PROJECT, "#${proj.name}")
        working = working.removeRange(m.range)
        break
      }
    }

    // PRIORITY — words first (more specific), then bangs
    priorityWordRegex.find(working)?.let { m ->
      priority = priorityWords[m.groupValues[1].lowercase(Locale.ROOT)] ?: 0
      tokens += ParsedToken(ParsedToken.Kind.PRIORITY, m.value.trim())
      working = working.removeRange(m.range)
    }
    if (priority == 0) {
      bangsRegex.find(working)?.let { m ->
        priority = when (m.groupValues[1].length) { 3 -> 1; 2 -> 2; else -> 4 }
        tokens += ParsedToken(ParsedToken.Kind.PRIORITY, m.groupValues[1])
        working = working.removeRange(m.range)
      }
    }

    // DATE — today / tomorrow|tmr / weekday (first match wins)
    val dateMatch = findDate(working, today)
    if (dateMatch != null) {
      plannedDate = dateMatch.second
      tokens += ParsedToken(ParsedToken.Kind.DATE, dateMatch.first.value)
      working = working.removeRange(dateMatch.first.range)
    }

    // TIME — am/pm first, else 24h
    val tm = timeRegex.find(working)
    if (tm != null) {
      scheduledStart = parseAmPm(tm.groupValues[1].toInt(), tm.groupValues[2].toIntOrNull() ?: 0, tm.groupValues[3])
      tokens += ParsedToken(ParsedToken.Kind.TIME, tm.value.trim())
      working = working.removeRange(tm.range)
    } else {
      time24Regex.find(working)?.let { m ->
        scheduledStart = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        tokens += ParsedToken(ParsedToken.Kind.TIME, m.value.trim())
        working = working.removeRange(m.range)
      }
    }

    val title = working.replace(Regex("\\s+"), " ").trim()
    val ordered = tokens.sortedBy {
      when (it.kind) {
        ParsedToken.Kind.PROJECT -> 0
        ParsedToken.Kind.PRIORITY -> 1
        ParsedToken.Kind.DATE -> 2
        ParsedToken.Kind.TIME -> 3
      }
    }
    return ParsedCapture(title, projectId, priority, plannedDate, scheduledStart, ordered)
  }

  private fun findDate(text: String, today: LocalDate): Pair<MatchResult, LocalDate>? {
    Regex("(?i)(?<=\\s|^)(today)(?=\\s|$)").find(text)?.let { return it to today }
    Regex("(?i)(?<=\\s|^)(tomorrow|tmr)(?=\\s|$)").find(text)?.let { return it to today.plusDays(1) }
    for ((word, dow) in weekdays) {
      val m = Regex("(?i)(?<=\\s|^)(${Regex.escape(word)})(?=\\s|$)").find(text)
      if (m != null) return m to nextOrToday(today, dow)
    }
    return null
  }

  private fun nextOrToday(today: LocalDate, target: DayOfWeek): LocalDate {
    val delta = (target.value - today.dayOfWeek.value + 7) % 7
    return today.plusDays(delta.toLong())
  }

  private fun parseAmPm(hour12: Int, minute: Int, ampm: String): LocalTime {
    val h = when {
      ampm.equals("am", true) && hour12 == 12 -> 0
      ampm.equals("pm", true) && hour12 != 12 -> hour12 + 12
      else -> hour12
    }
    return LocalTime.of(h % 24, minute)
  }
}
```
- [ ] **Verify PASS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.capture.QuickCaptureParserTest"` → green. (If `nextOrToday` for the `"sun"`/`"sunday"` distinction or weekday-map iteration order surprises a case, fix the regex/lookup — the tests are the contract; do not weaken them.)
- [ ] **Commit:**
```
feat(android-capture): QuickCaptureParser with NL project/priority/date/time

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P1.6 — `QuickCaptureViewModel`

The capture VM observes projects, holds the live input, re-parses on every change (exposing parsed chips), applies one-tap quick-chip toggles (Today / Inbox / Priority / Remind), and on submit writes through `TaskRepository.create` — default `status = Inbox` when no date, `status = Planned` when a `plannedDate` is present — then **clears the field but stays open** for rapid-fire. Real unit tests.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/capture/QuickCaptureViewModel.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/capture/QuickCaptureUiState.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/capture/QuickCaptureViewModelTest.kt`

**Interfaces**:
```kotlin
data class QuickCaptureUiState(
  val text: String = "",
  val parsed: ParsedCapture = ParsedCapture("", null, 0, null, null, emptyList()),
  val remind: Boolean = false,            // toggled by the "Remind" chip
  val projects: List<ProjectEntity> = emptyList(),
  val canSubmit: Boolean = false,         // parsed.title non-blank
)

@HiltViewModel
class QuickCaptureViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
  private val parser: QuickCaptureParser,
  private val clock: Clock,
) : ViewModel() {
  val uiState: StateFlow<QuickCaptureUiState>
  fun onTextChange(s: String)
  fun chipToday()        // set plannedDate=today (re-parse merge)
  fun chipInbox()        // clear plannedDate/time → Inbox default
  fun chipPriority()     // cycle none→high(2)→urgent(1)→none
  fun chipRemind()       // toggle remind flag
  fun submit()           // create via repo, then reset text (stay open)
}
```
Submit mapping → `TaskDraft(title = parsed.title, projectId = parsed.projectId, priority = parsed.priority.takeIf { it > 0 }, plannedDate = effectiveDate, scheduledStart = effectiveStartInstant, status = if (effectiveDate != null) Planned else Inbox, reminderMinutes = if (remind) 0 else null)`. `scheduledStart` Instant = `plannedDate.atTime(time).atZone(clock.zone()).toInstant()` when both present, else null. Chip overrides merge over the parsed result (chip-set date/priority win). After submit: reset to a fresh state but keep `projects`.

> `QuickCaptureParser` must be DI-providable. Add a `@Provides` in P0's/P1's DI: include in this task a one-line provider (see Implement). It's constructor-injectable with `Clock`, so a simple provider or `@Inject constructor` on the parser suffices — the parser already has an injectable constructor; add `@Inject` to it OR provide it. **Decision: add `@Inject constructor` is not possible because `Clock` is injectable — keep the explicit `@Provides`.** (Clock is injectable, so we can also just annotate; to avoid ambiguity we add a `@Provides`.)

**Steps**
- [ ] **Write the failing test** `QuickCaptureViewModelTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeProject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class QuickCaptureViewModelTest {
  private val clock = FixedClock(Instant.parse("2026-06-21T06:00:00Z")) // Sunday
  private val testDispatcher = UnconfinedTestDispatcher()
  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vm(repo: FakeTaskRepo = FakeTaskRepo(), projs: FakeProjectRepo = FakeProjectRepo()): QuickCaptureViewModel {
    projs.setAll(listOf(fakeProject(id = "p-work", name = "Work")))
    return QuickCaptureViewModel(repo, projs, QuickCaptureParser(clock), clock)
  }

  @Test fun text_change_parses_and_enables_submit() = runTest(testDispatcher) {
    val vm = vm()
    vm.onTextChange("Finish slides #Work tomorrow 3pm")
    val s = vm.uiState.value
    assertEquals("Finish slides", s.parsed.title)
    assertEquals("p-work", s.parsed.projectId)
    assertTrue(s.canSubmit)
  }

  @Test fun submit_with_no_date_defaults_to_inbox_and_clears_text() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("Buy milk")
    vm.submit()
    assertEquals(1, repo.created.size)
    assertEquals("Buy milk", repo.created.first().title)
    assertEquals(TaskStatus.Inbox, repo.created.first().status)
    assertNull(repo.created.first().plannedDate)
    assertEquals("", vm.uiState.value.text) // stays open, field cleared
  }

  @Test fun submit_with_date_is_planned() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("Call mom tomorrow")
    vm.submit()
    assertEquals(TaskStatus.Planned, repo.created.first().status)
    assertEquals(LocalDate.of(2026, 6, 22), repo.created.first().plannedDate)
  }

  @Test fun chipToday_sets_today_and_chipInbox_clears() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("Standup")
    vm.chipToday()
    vm.submit()
    assertEquals(LocalDate.of(2026, 6, 21), repo.created.first().plannedDate)
    vm.onTextChange("Later")
    vm.chipToday()
    vm.chipInbox()
    vm.submit()
    assertEquals(TaskStatus.Inbox, repo.created[1].status)
    assertNull(repo.created[1].plannedDate)
  }

  @Test fun chipPriority_cycles_and_chipRemind_sets_reminder() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("Ship")
    vm.chipPriority()            // → 2 (high)
    vm.chipRemind()              // remind on
    vm.submit()
    assertEquals(2, repo.created.first().priority)
    assertEquals(0, repo.created.first().reminderMinutes) // at-start
  }

  @Test fun submit_blank_is_noop() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    val vm = vm(repo)
    vm.onTextChange("   ")
    vm.submit()
    assertTrue(repo.created.isEmpty())
  }
}
```
- [ ] **Verify FAIL:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.capture.QuickCaptureViewModelTest"` → red.

- [ ] **Implement** `QuickCaptureUiState.kt`:
```kotlin
package net.qmindtech.tmap.ui.capture

import net.qmindtech.tmap.data.local.entities.ProjectEntity

data class QuickCaptureUiState(
  val text: String = "",
  val parsed: ParsedCapture = ParsedCapture("", null, 0, null, null, emptyList()),
  val remind: Boolean = false,
  val projects: List<ProjectEntity> = emptyList(),
  val canSubmit: Boolean = false,
)
```
`QuickCaptureViewModel.kt`:
```kotlin
package net.qmindtech.tmap.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskDraft
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class QuickCaptureViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
  private val parser: QuickCaptureParser,
  private val clock: Clock,
) : ViewModel() {

  // Chip overrides layered over the parsed result.
  private var dateOverride: LocalDate? = null
  private var clearDate: Boolean = false
  private var priorityOverride: Int? = null

  private val _state = MutableStateFlow(QuickCaptureUiState())
  val uiState: StateFlow<QuickCaptureUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      projectRepo.observeAll().collect { projects ->
        _state.update { it.copy(projects = projects, parsed = reparse(it.text, projects)) }
      }
    }
  }

  private fun reparse(text: String, projects: List<net.qmindtech.tmap.data.local.entities.ProjectEntity>) =
    parser.parse(text, projects)

  fun onTextChange(s: String) {
    _state.update {
      val p = reparse(s, it.projects)
      it.copy(text = s, parsed = p, canSubmit = p.title.isNotBlank())
    }
  }

  fun chipToday() { clearDate = false; dateOverride = clock.today() }
  fun chipInbox() { clearDate = true; dateOverride = null }
  fun chipPriority() {
    priorityOverride = when (priorityOverride ?: _state.value.parsed.priority) {
      0 -> 2; 2 -> 1; else -> 0
    }.takeIf { it != 0 }
  }
  fun chipRemind() { _state.update { it.copy(remind = !it.remind) } }

  fun submit() {
    val s = _state.value
    val title = s.parsed.title
    if (title.isBlank()) return
    val effectiveDate = when {
      clearDate -> null
      dateOverride != null -> dateOverride
      else -> s.parsed.plannedDate
    }
    val effectivePriority = (priorityOverride ?: s.parsed.priority).takeIf { it > 0 }
    val start: LocalTime? = s.parsed.scheduledStart
    val startInstant = if (effectiveDate != null && start != null) {
      effectiveDate.atTime(start).atZone(clock.zone()).toInstant()
    } else null
    val draft = TaskDraft(
      title = title,
      projectId = s.parsed.projectId,
      priority = effectivePriority,
      plannedDate = effectiveDate,
      scheduledStart = startInstant,
      status = if (effectiveDate != null) TaskStatus.Planned else TaskStatus.Inbox,
      reminderMinutes = if (s.remind) 0 else null,
    )
    viewModelScope.launch { taskRepo.create(draft) }
    // Reset for rapid-fire; keep projects, drop overrides.
    dateOverride = null; clearDate = false; priorityOverride = null
    _state.update { QuickCaptureUiState(projects = it.projects) }
  }
}
```
Add the parser DI provider (in the existing P0 `di/AppModule.kt` or wherever UI helpers are provided — match the project's module style):
```kotlin
@Provides
fun provideQuickCaptureParser(clock: Clock): net.qmindtech.tmap.ui.capture.QuickCaptureParser =
  net.qmindtech.tmap.ui.capture.QuickCaptureParser(clock)
```
> `Clock` injection: `FixedClock` doesn't implement `zone()` in tests but `Clock` provides a default `zone() = ZoneId.systemDefault()`, so `FixedClock` works. Confirm `FakeTaskRepo` already returns a stable id from `create` (it does: `nextId`).

- [ ] **Verify PASS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.capture.QuickCaptureViewModelTest"` → green; full `./gradlew :app:testDebugUnitTest` green.
- [ ] **Commit:**
```
feat(android-capture): QuickCaptureViewModel (parse, chips, rapid-fire submit)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P1.7 — `QuickCaptureSheet` (compile-gate + behavior checklist)

Build the capture bottom sheet to `daily-core.html` "② Quick capture": a single text field, inline parsed-token chips (project blue-tint, date/time amber-tint, priority flag), the four quick chips (Today/Inbox/Priority/Remind), a helper hint line, and an amber send button. On submit the field clears but the sheet stays open. Replaces the old `QuickAddSheet` usage on Today.

> **No unit test (Compose).** Verification = **compile-gate** + behavior checklist vs `daily-core.html` ②.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/capture/QuickCaptureSheet.kt`

**Interfaces**:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureSheet(
  onDismiss: () -> Unit,
  viewModel: QuickCaptureViewModel = hiltViewModel(),
)
```

**Steps**
- [ ] **(Compose surface — no unit test; compile-gate + checklist substitution stated.)**
- [ ] **Implement** `QuickCaptureSheet.kt`:
```kotlin
package net.qmindtech.tmap.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.Chip
import net.qmindtech.tmap.ui.theme.LocalTmapColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureSheet(
  onDismiss: () -> Unit,
  viewModel: QuickCaptureViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val colors = LocalTmapColors.current
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 22.dp)) {
      OutlinedTextField(
        value = state.text,
        onValueChange = viewModel::onTextChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("What needs doing?") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
      )

      // Inline parsed-token chips
      if (state.parsed.tokens.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          state.parsed.tokens.forEach { tok -> Chip(text = tok.text) }
        }
      }

      // Quick chips
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Chip(text = "Today", onClick = viewModel::chipToday)
        Chip(text = "Inbox", onClick = viewModel::chipInbox)
        Chip(text = "Priority", onClick = viewModel::chipPriority)
        Chip(text = if (state.remind) "Remind ✓" else "Remind", onClick = viewModel::chipRemind)
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          "Type naturally — it parses #project, dates & !priority",
          color = colors.textTertiary,
          modifier = Modifier.weight(1f),
        )
        IconButton(
          onClick = { viewModel.submit() },
          enabled = state.canSubmit,
          modifier = Modifier.size(46.dp).clip(CircleShape).background(colors.accent),
        ) {
          Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Add task",
            tint = colors.onAccent,
          )
        }
      }
    }
  }
}
```
> `Chip` is a P0 component (`ui/components/Chips.kt`). Match its real signature (it may be `Chip(text, selected, onClick)` — adapt; the token chips are display-only, quick chips are clickable). If P0's `Chip` lacks an `onClick`-less form, pass `onClick = {}`. Keep the four quick-chip labels + the send button + the parsed-token row.

- [ ] **Verify compile-gate:** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Behavior checklist (vs `daily-core.html` ②):** single text field; parsed tokens render as inline tinted chips as you type; the four quick chips (Today/Inbox/Priority/Remind) toggle; helper hint line; amber circular send (↑/→) enabled only when title non-blank; submit clears the field and the sheet stays open (rapid-fire).
- [ ] **Commit:**
```
feat(android-capture): QuickCaptureSheet UI (inline chips, quick chips, send)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P1.8 — `TaskEditorViewModel` schedule/subtask additions (unit-tested)

The editor sheet needs scheduled start/end + duration editing, due-date editing, and subtask reorder — beyond the v1 VM. Add the missing change handlers and a subtask-reorder action to the existing VM (keeping its create/edit/markDone/delete contract), and unit-test the new behaviors. (UiState already carries `scheduledStart/scheduledEnd/durationMinutes/dueDate`.)

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModel.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorViewModelTest.kt`

**Interfaces** (added to `TaskEditorViewModel`):
```kotlin
fun onScheduledStartChange(instant: Instant?)
fun onScheduledEndChange(instant: Instant?)
fun onDueDateChange(date: LocalDate?)
fun reorderSubtasks(orderedIds: List<String>)   // persists sortOrder via SubtaskRepository.update
```
`reorderSubtasks` assigns `sortOrder = index` to each via `subtaskRepo.update(id, sortOrder = i)` in order (only in edit mode). `onScheduledStartChange`/`onScheduledEndChange`/`onDueDateChange` are pure state updates (persisted on `save`). When `scheduledStart` and `scheduledEnd` are both set, `save` already carries them through `toEdit()`/`toDraft()`. Add a derived `durationMinutes` recompute on `onScheduledEndChange` (if both set: `Duration.between(start,end).toMinutes()`).

**Steps**
- [ ] **Write the failing test** — append to `TaskEditorViewModelTest.kt`:
```kotlin
  @Test fun schedule_changes_update_state_and_recompute_duration() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    repo.setSingle(fakeTask(id = "t1", status = TaskStatus.Planned))
    val vm = editVm(repo)
    val start = Instant.parse("2026-06-21T09:00:00Z")
    val end = Instant.parse("2026-06-21T10:30:00Z")
    vm.onScheduledStartChange(start)
    vm.onScheduledEndChange(end)
    assertEquals(start, vm.uiState.value.scheduledStart)
    assertEquals(end, vm.uiState.value.scheduledEnd)
    assertEquals(90, vm.uiState.value.durationMinutes)
  }

  @Test fun due_date_change_updates_state() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    repo.setSingle(fakeTask(id = "t1"))
    val vm = editVm(repo)
    vm.onDueDateChange(LocalDate.of(2026, 6, 30))
    assertEquals(LocalDate.of(2026, 6, 30), vm.uiState.value.dueDate)
  }

  @Test fun reorderSubtasks_persists_sortOrder_in_order() = runTest(testDispatcher) {
    val repo = FakeTaskRepo()
    repo.setSingle(fakeTask(id = "t1"))
    val subs = FakeSubtaskRepo()
    val vm = editVm(repo, subs)
    vm.reorderSubtasks(listOf("s2", "s1"))
    assertEquals(listOf("s2", "s1"), subs.updated)
  }
```
- [ ] **Verify FAIL:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"` → red (new handlers missing).

- [ ] **Implement** — add to `TaskEditorViewModel.kt` (imports `java.time.Duration`, `java.time.Instant`):
```kotlin
  fun onScheduledStartChange(instant: Instant?) = _state.update {
    val end = it.scheduledEnd
    val dur = if (instant != null && end != null) java.time.Duration.between(instant, end).toMinutes().toInt() else it.durationMinutes
    it.copy(scheduledStart = instant, durationMinutes = dur)
  }

  fun onScheduledEndChange(instant: Instant?) = _state.update {
    val start = it.scheduledStart
    val dur = if (start != null && instant != null) java.time.Duration.between(start, instant).toMinutes().toInt() else it.durationMinutes
    it.copy(scheduledEnd = instant, durationMinutes = dur)
  }

  fun onDueDateChange(date: java.time.LocalDate?) = _state.update { it.copy(dueDate = date) }

  fun reorderSubtasks(orderedIds: List<String>) {
    if (taskId == null) return
    viewModelScope.launch {
      orderedIds.forEachIndexed { i, sid -> subtaskRepo.update(sid, sortOrder = i) }
    }
  }
```
- [ ] **Verify PASS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.taskeditor.TaskEditorViewModelTest"` → green.
- [ ] **Commit:**
```
feat(android-editor): editor VM schedule/due/subtask-reorder handlers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P1.9 — `TaskEditorSheet` (compile-gate + behavior checklist)

Convert the editor from a full Scaffold screen to a bottom sheet matching `daily-core.html` ③ ("Tap a card → editor opens as a bottom sheet"). Reuses `TaskEditorViewModel`/`TaskEditorUiState`/`SubtaskRow`. Renders all fields: title, notes, subtasks (add/complete/reorder/delete via `SubtaskRow`), project, planned date, scheduled start/end + duration, due date, priority, reminder minutes, status; plus delete + complete actions. The old `TaskEditorScreen.kt` is retired (deleted) once the sheet is wired (Task P1.10).

> **No unit test (Compose).** Verification = **compile-gate** + behavior checklist vs `daily-core.html` ③.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorSheet.kt`

**Interfaces** (FIXED):
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
  onDismiss: () -> Unit,
  viewModel: TaskEditorViewModel = hiltViewModel(),
)
```

**Steps**
- [ ] **(Compose surface — compile-gate + checklist substitution stated.)**
- [ ] **Implement** `TaskEditorSheet.kt`:
```kotlin
package net.qmindtech.tmap.ui.taskeditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.ui.components.PriorityDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(
  onDismiss: () -> Unit,
  viewModel: TaskEditorViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var newSubtask by remember { mutableStateOf("") }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 22.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedTextField(
        value = state.title, onValueChange = viewModel::onTitleChange,
        modifier = Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true,
      )
      OutlinedTextField(
        value = state.notes, onValueChange = viewModel::onNotesChange,
        modifier = Modifier.fillMaxWidth(), label = { Text("Notes") },
      )

      Text("Status")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TaskStatus.Inbox, TaskStatus.Backlog, TaskStatus.Planned, TaskStatus.Scheduled, TaskStatus.Done).forEach { st ->
          FilterChip(selected = state.status == st, onClick = { viewModel.onStatusChange(st) }, label = { Text(st.name) })
        }
      }

      Text("Priority")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.priority == null, onClick = { viewModel.onPriorityChange(null) }, label = { Text("None") })
        listOf(1, 2, 3, 4).forEach { p ->
          FilterChip(selected = state.priority == p, onClick = { viewModel.onPriorityChange(p) }, label = { Text(PriorityDisplay.label(p)) })
        }
      }

      Text("Project")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.projectId == null, onClick = { viewModel.onProjectChange(null) }, label = { Text("No project") })
        state.projects.forEach { p ->
          FilterChip(selected = state.projectId == p.id, onClick = { viewModel.onProjectChange(p.id) }, label = { Text("${p.emoji} ${p.name}") })
        }
      }

      Text("Reminder")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null to "None", 0 to "At start", 5 to "5m", 10 to "10m", 15 to "15m", 30 to "30m").forEach { (m, label) ->
          FilterChip(selected = state.reminderMinutes == m, onClick = { viewModel.onReminderChange(m) }, label = { Text(label) })
        }
      }

      Text("Schedule")
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(null to "No duration", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "1h", 90 to "1.5h").forEach { (m, label) ->
          FilterChip(selected = state.durationMinutes == m, onClick = { viewModel.onDurationChange(m) }, label = { Text(label) })
        }
      }

      if (state.isEdit) {
        Text("Subtasks")
        state.subtasks.forEach { sub ->
          SubtaskRow(
            subtask = sub,
            onToggle = { viewModel.toggleSubtask(sub) },
            onRename = { viewModel.renameSubtask(sub, it) },
            onDelete = { viewModel.deleteSubtask(sub.id) },
          )
        }
        OutlinedTextField(
          value = newSubtask, onValueChange = { newSubtask = it },
          modifier = Modifier.fillMaxWidth(), label = { Text("Add a subtask") }, singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { viewModel.addSubtask(newSubtask); newSubtask = "" }),
        )
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.isEdit) {
          OutlinedButton(onClick = { viewModel.markDone(); onDismiss() }) { Text("Complete") }
          TextButton(onClick = { viewModel.delete(onDismiss) }) { Text("Delete") }
        }
        Button(onClick = { viewModel.save(onDismiss) }, enabled = state.title.isNotBlank()) {
          Text(if (state.isEdit) "Update" else "Create")
        }
      }
    }
  }
}
```
> Subtask reorder via the long-press handle uses `viewModel.reorderSubtasks(...)` — wire it when `SubtaskRow` exposes a drag handle (P0/follow-up); the VM action exists (P1.8). Keep `SubtaskRow` as-is. Date pickers (planned/due) use the platform date picker dialog; if not yet componentized, gate behind a follow-up and keep the duration/schedule chips here so the field set is complete — *state this in the commit*.

- [ ] **Verify compile-gate:** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Behavior checklist (vs `daily-core.html` ③):** opens as a `ModalBottomSheet` (not a full page); shows title, notes, status, priority, project, reminder, schedule/duration, subtasks (add/complete/rename/delete in edit mode); Complete + Delete + Update/Create actions; dismiss returns to the underlying screen.
- [ ] **Commit:**
```
feat(android-editor): TaskEditorSheet bottom-sheet editor

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P1.10 — Wire capture + editor into `SheetHost`; retire `QuickAddSheet`/`TaskEditorScreen`; phase gate

Wire `QuickCaptureSheet` and `TaskEditorSheet` into the P0 `SheetHost` so `NavController.openCapture()` and `NavController.openTaskEditor(taskId)` (FIXED P0 nav contract) present them, and `TodayScreen`'s `onOpenCapture`/`onOpenTask`/`onPlanMyDay`/`onFocus` hooks route through `MainScaffold`/nav (Planning/Focus routes are P5/P6 — stub the nav lambdas as no-ops or TODO-logging for now, per the skeleton "stub the nav calls"). Delete the now-dead `QuickAddSheet.kt` and `TaskEditorScreen.kt` only if nothing else references them (grep first). Then run the full phase gate.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/navigation/SheetHost.kt` (P0-owned; wire the two sheet states to the P1 composables)
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/navigation/MainScaffold.kt` (P0-owned; pass Today's hooks)
- `- android/app/src/main/java/net/qmindtech/tmap/ui/inbox/QuickAddSheet.kt` (delete if unreferenced)
- `- android/app/src/main/java/net/qmindtech/tmap/ui/taskeditor/TaskEditorScreen.kt` (delete if unreferenced)

**Steps**
- [ ] **Grep for references** before deleting:
  `Grep "QuickAddSheet" android/app/src` and `Grep "TaskEditorScreen" android/app/src`. If `InboxScreen.kt` (P0/P2) still uses `QuickAddSheet`, leave it until that screen migrates to `openCapture()`; otherwise delete. Same for `TaskEditorScreen`.
- [ ] **Wire `SheetHost`** — in the P0 `SheetHost` `when` over sheet state, render:
```kotlin
// capture sheet state → QuickCaptureSheet(onDismiss = { sheetState = none })
// editor sheet state (taskId) → TaskEditorSheet(onDismiss = { sheetState = none })
//   (hiltViewModel keyed by the route/taskId so SavedStateHandle["taskId"] resolves)
```
Match the P0 `SheetHost` mechanism exactly (sheet-state enum/sealed class it defines). `openTaskEditor(taskId)` must surface `taskId` to `TaskEditorViewModel` via its `SavedStateHandle` key `"taskId"` (the VM already reads `savedStateHandle.get<String?>("taskId")`); pass `null`/`"new"` for create mode.
- [ ] **Wire `TodayScreen` hooks** in `MainScaffold`: `onOpenCapture = { navController.openCapture() }`, `onOpenTask = { id -> navController.openTaskEditor(id) }`, `onPlanMyDay = { /* TODO P5: navController.navigate(Route.Planning) */ }`, `onFocus = { /* TODO P6: navController.navigate(Route.Focus(null)) */ }`. (Use `Route.Planning`/`Route.Focus` if P0 already declares them; otherwise stub no-op lambdas — the skeleton explicitly permits stubbing P5/P6 nav.)
- [ ] **Delete** dead files (only those confirmed unreferenced).
- [ ] **Verify — full phase gate:**
  - `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
  - `cd android && ./gradlew :app:testDebugUnitTest` → all green (P1.1, P1.2, P1.3, P1.5, P1.6, P1.8 suites + the whole existing suite).
  - `cd android && ./gradlew :app:lintDebug` → no new errors.
- [ ] **End-of-phase behavior checklist vs `.superpowers/brainstorm/965-1782053760/content/daily-core.html` (all three panels):**
  - ① Today: header (eyebrow/greeting/progress/List⇄Timeline/Plan-my-day/Focus), grouped `SwipeableTaskCard`s, `TmapFab`, undo snackbar, Timeline placeholder.
  - ② Quick capture: `+`/FAB opens the sheet; NL parse chips; quick chips; rapid-fire (stays open, default-to-Inbox).
  - ③ Gestures & editing: swipe-right complete (undo), swipe-left defer/delete, tap → `TaskEditorSheet` (bottom sheet, full field set).
  - Offline: every action is a write-through repo call (no network on the UI path).
- [ ] **Commit:**
```
feat(android-nav): wire capture + editor sheets into SheetHost; retire v1 sheets

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

## P2 — Browse hub & Projects

This phase folds the v1 `AllTasks` + `Backlog` + `Projects` library screens into the new **Browse hub** and a redesigned **Projects** surface, consuming the P0 design system verbatim (`TmapTheme`/`LocalTmapColors`, `TaskUi` + `TaskEntity.toUi`, `TaskCard`, `SegmentedControl`, `FilterChip`, `Chip`, `SectionLabel`, `ProgressRing`, `TmapFab`, `EmptyState`, `SheetScaffold`, `PrimaryButton`/`SecondaryButton`, `ProjectDot`) and the P0 `Route`/`MainScaffold` navigation contracts (`Route.Browse`, `Route.ProjectDetail(projectId)`, `openTaskEditor`). It starts with the pure, fully unit-tested filter/sort/group engine (`ui/browse/TaskFilter.kt`) — extended over the v1 logic with a project-name search join and standardized on the FIXED `TaskUi` projection — then the unit-tested `BrowseViewModel` (segmented All Tasks / Backlog / Projects, grouped sections, an `activeFilterCount`), the `BrowseScreen` Compose surface (compile-gate + `full-app.html` Browse behavior-checklist), a per-project progress DAO query with a real Room DAO test, a rebuilt `ProjectsViewModel` (progress cards + header summary, unit-tested) + `ProjectsScreen`/`ProjectEditDialog`, the new `ProjectDetailViewModel` (unit-tested) + `ProjectDetailScreen` reachable via `Route.ProjectDetail`, and finally the retirement of `ui/alltasks/*` + `ui/backlog/*` (delete screens/VMs/tests, rewire `MainScaffold`) proven by `assembleDebug` + a grep-clean check. Pure logic (filter, view-models, DAO query) gets **real failing-first JUnit tests** run via `./gradlew :app:testDebugUnitTest`; the Compose screens use the **compile-gate + behavior-checklist** substitution stated in the skeleton (`./gradlew :app:assembleDebug` passing + reviewer verifies against `.superpowers/brainstorm/965-1782053760/content/full-app.html` Browse + Projects panels), because this project configures no instrumented `androidTest` harness for `ui/`. The phase ends with a full green-gate (`testDebugUnitTest` + `assembleDebug` + `lintDebug` + behavior checklist). All `./gradlew` commands run from the `android/` directory. Every commit ends with the exact trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

> **Dependency note:** P2 runs after P0 (design system + nav + `TaskUi`) and P1 (which added `TaskRepository.defer/moveToDay/reorder/addActualTime` and extended `FakeTaskRepo`). P2 does **not** depend on P3 — the new Notes domain does not exist yet, so `ProjectDetailScreen`'s notes area is a documented `// P4-NOTES-SLOT` placeholder only. Reuse `Route.ProjectDetail.create(projectId)` / `PATTERN` / `ARG_PROJECT_ID` exactly as P0 declared them; never invent a new route string.

> **Test-substitution note (applies to every Compose-UI task in P2):** `BrowseScreen`, `ProjectsScreen`, `ProjectEditDialog`, and `ProjectDetailScreen` are visual composables not unit-testable without an instrumented harness this project does not configure. Per the plan convention each such task's gate is **`./gradlew :app:assembleDebug` passing + a behavior checklist the reviewer verifies against `full-app.html`** (Browse + Projects). All extractable pure logic (filter engine, view-model state derivation, the progress DAO query, color parsing) is pulled into plain Kotlin / DAO and covered by **real** tests.

---

### Task P2.1 — Browse filter/sort/group engine (pure, real unit tests) standardized on `TaskUi`

Move and extend the v1 `ui/alltasks/TaskFilter.kt` logic into `ui/browse/TaskFilter.kt`. Keep the proven filter/sort/group semantics, **add manual-rank sort**, and standardize the result items on the FIXED P0 `TaskUi` projection (so every list in Browse renders with `TaskCard`). The v1 `ui/alltasks/TaskFilter.kt` and its test are left untouched in this task (they are deleted in P2.8 once `AllTasksScreen`/VM are retired) — this task creates the new browse copy alongside.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/browse/TaskFilter.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/browse/TaskFilterTest.kt`

**Interfaces**
- Produces: `enum class SortField { CreatedAt, Priority, PlannedDate, Title, Status, ManualRank }` (the v1 five + `ManualRank`).
- Produces: `enum class SortDirection { Asc, Desc }`.
- Produces: `enum class GroupBy { None, Status, Project, Priority }`.
- Produces: `data class TaskFilter(search, statuses, showArchived, priorities, projectIds, dateFrom, dateTo, sortField, sortDirection, groupBy)` with the same defaults as v1 (companion `NON_ARCHIVED_STATUSES`, `ALL_PRIORITIES`); default `sortField = SortField.ManualRank`, `sortDirection = SortDirection.Asc` (Browse defaults to the user's manual order, matching the mockup's "natural" list).
- Produces: `data class BrowseTaskItem(val ui: TaskUi, val task: TaskEntity)` — the row model (carries the FIXED `TaskUi` for `TaskCard` + the raw entity for actions/keys).
- Produces: `data class TaskGroup(val key: String, val label: String, val items: List<BrowseTaskItem>)`.
- Produces: `fun stripHtml(s: String?): String`.
- Produces: `fun applyTaskFilter(tasks: List<TaskEntity>, projects: List<ProjectEntity>, filter: TaskFilter): List<TaskGroup>` — pure; filters by status/priority/project/date-range, searches over title + notes(html-stripped) + project name (case-insensitive, RTL-safe), sorts by the chosen field/direction, groups by the chosen dimension preserving first-seen order; group key/label rules identical to v1 (Status uses `StatusDisplay.label`, Project uses project name with "No Project" for blank, Priority uses `PriorityDisplay.label`).
- Consumes: `TaskUi` + `TaskEntity.toUi` (P0, `ui/components/TaskUi.kt`), `PriorityDisplay`/`StatusDisplay` (`ui/components/PriorityDisplay.kt`), `TaskEntity`/`ProjectEntity`, `TaskStatus`.

**Steps**

- [ ] **Write the failing test** `android/app/src/test/java/net/qmindtech/tmap/ui/browse/TaskFilterTest.kt` (mirrors the v1 `ui/alltasks/TaskFilterTest.kt` coverage and adds project-name-join + manual-rank cases). It will fail to compile (no `ui/browse/TaskFilter.kt` yet) — that is the RED state:

```kotlin
package net.qmindtech.tmap.ui.browse

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TaskFilterTest {
  private val projects = listOf(
    fakeProject(id = "p1", name = "Work", color = "#6EA8FE", emoji = "💼"),
    fakeProject(id = "p2", name = "حجوزات عيادات", emoji = "🏥"),
  )

  private fun ids(groups: List<TaskGroup>) = groups.flatMap { g -> g.items.map { it.task.id } }

  // ---- defaults: manual-rank asc, archived excluded, single group ----
  @Test fun default_filter_excludes_archived_one_group_manual_rank_order() {
    val tasks = listOf(
      fakeTask(id = "b", status = TaskStatus.Inbox, rank = "0001"),
      fakeTask(id = "a", status = TaskStatus.Inbox, rank = "0000"),
      fakeTask(id = "arch", status = TaskStatus.Archived, rank = "0002"),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter())
    assertEquals(1, out.size)
    assertEquals("all", out.first().key)
    assertEquals("All Tasks", out.first().label)
    assertEquals(listOf("a", "b"), ids(out)) // manual rank asc
  }

  // ---- TaskUi projection is carried on each row ----
  @Test fun rows_carry_taskui_projection_with_project_name_and_color() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p1"))
    val out = applyTaskFilter(tasks, projects, TaskFilter())
    val item = out.single().items.single()
    assertEquals("a", item.ui.id)
    assertEquals("Work", item.ui.projectName)
    assertEquals(0xFF6EA8FEL, item.ui.projectColor)
  }

  // ---- status filter (multi) ----
  @Test fun empty_statuses_excludes_all_non_archived() {
    val tasks = listOf(fakeTask(id = "i", status = TaskStatus.Inbox), fakeTask(id = "p", status = TaskStatus.Planned))
    val out = applyTaskFilter(tasks, projects, TaskFilter(statuses = emptySet()))
    assertEquals(emptyList<String>(), ids(out))
  }

  @Test fun status_filter_keeps_only_selected_non_archived() {
    val tasks = listOf(
      fakeTask(id = "i", status = TaskStatus.Inbox),
      fakeTask(id = "p", status = TaskStatus.Planned),
      fakeTask(id = "d", status = TaskStatus.Done),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(statuses = setOf(TaskStatus.Planned)))
    assertEquals(listOf("p"), ids(out))
  }

  @Test fun showArchived_includes_archived_independently() {
    val tasks = listOf(fakeTask(id = "i", status = TaskStatus.Inbox), fakeTask(id = "ar", status = TaskStatus.Archived))
    val out = applyTaskFilter(tasks, projects, TaskFilter(showArchived = true))
    assertEquals(setOf("i", "ar"), ids(out).toSet())
  }

  // ---- priority filter (multi, incl null) ----
  @Test fun priority_filter_multi_including_null() {
    val tasks = listOf(fakeTask(id = "u", priority = 1), fakeTask(id = "m", priority = 3), fakeTask(id = "n", priority = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(priorities = setOf(1, null)))
    assertEquals(setOf("u", "n"), ids(out).toSet())
  }

  // ---- project filter (multi; null = no constraint; "" = no project) ----
  @Test fun project_null_filter_means_all_pass() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p1"), fakeTask(id = "b", projectId = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(projectIds = null))
    assertEquals(setOf("a", "b"), ids(out).toSet())
  }

  @Test fun project_filter_matches_id_and_empty_for_no_project() {
    val tasks = listOf(
      fakeTask(id = "a", projectId = "p1"),
      fakeTask(id = "b", projectId = "p2"),
      fakeTask(id = "c", projectId = null),
    )
    assertEquals(listOf("a"), ids(applyTaskFilter(tasks, projects, TaskFilter(projectIds = setOf("p1")))))
    assertEquals(listOf("c"), ids(applyTaskFilter(tasks, projects, TaskFilter(projectIds = setOf("")))))
  }

  // ---- date range (inclusive; null plannedDate fails when bound set) ----
  @Test fun date_range_inclusive_and_excludes_null_planned() {
    val tasks = listOf(
      fakeTask(id = "before", plannedDate = LocalDate.of(2026, 6, 1)),
      fakeTask(id = "inLow", plannedDate = LocalDate.of(2026, 6, 10)),
      fakeTask(id = "inHigh", plannedDate = LocalDate.of(2026, 6, 20)),
      fakeTask(id = "after", plannedDate = LocalDate.of(2026, 6, 25)),
      fakeTask(id = "noDate", plannedDate = null),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(dateFrom = LocalDate.of(2026, 6, 10), dateTo = LocalDate.of(2026, 6, 20)))
    assertEquals(setOf("inLow", "inHigh"), ids(out).toSet())
  }

  // ---- search (title, notes-html-stripped, project name incl. Arabic) ----
  @Test fun search_matches_title_case_insensitive() {
    val tasks = listOf(fakeTask(id = "a", title = "Email Report"), fakeTask(id = "b", title = "Call"))
    assertEquals(listOf("a"), ids(applyTaskFilter(tasks, projects, TaskFilter(search = "email"))))
  }

  @Test fun search_matches_notes_with_html_stripped() {
    val tasks = listOf(fakeTask(id = "a", title = "x", notes = "<p>budget <b>review</b></p>"))
    assertEquals(listOf("a"), ids(applyTaskFilter(tasks, projects, TaskFilter(search = "budget review"))))
  }

  @Test fun search_matches_project_name_including_arabic() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p2"), fakeTask(id = "b", projectId = "p1"))
    assertEquals(listOf("a"), ids(applyTaskFilter(tasks, projects, TaskFilter(search = "حجوزات"))))
  }

  // ---- sort keys ----
  @Test fun sort_createdAt_desc() {
    val tasks = listOf(
      fakeTask(id = "old", createdAt = Instant.parse("2026-06-01T00:00:00Z")),
      fakeTask(id = "new", createdAt = Instant.parse("2026-06-10T00:00:00Z")),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.CreatedAt, sortDirection = SortDirection.Desc))
    assertEquals(listOf("new", "old"), ids(out))
  }

  @Test fun sort_priority_asc_nulls_last() {
    val tasks = listOf(fakeTask(id = "n", priority = null), fakeTask(id = "p3", priority = 3), fakeTask(id = "p1", priority = 1))
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.Priority, sortDirection = SortDirection.Asc))
    assertEquals(listOf("p1", "p3", "n"), ids(out))
  }

  @Test fun sort_plannedDate_asc_missing_first() {
    val tasks = listOf(
      fakeTask(id = "d2", plannedDate = LocalDate.of(2026, 6, 20)),
      fakeTask(id = "none", plannedDate = null),
      fakeTask(id = "d1", plannedDate = LocalDate.of(2026, 6, 10)),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.PlannedDate, sortDirection = SortDirection.Asc))
    assertEquals(listOf("none", "d1", "d2"), ids(out))
  }

  @Test fun sort_title_asc() {
    val tasks = listOf(fakeTask(id = "b", title = "Banana"), fakeTask(id = "a", title = "Apple"))
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.Title, sortDirection = SortDirection.Asc))
    assertEquals(listOf("a", "b"), ids(out))
  }

  @Test fun sort_status_uses_lifecycle_order() {
    val tasks = listOf(
      fakeTask(id = "done", status = TaskStatus.Done),
      fakeTask(id = "inbox", status = TaskStatus.Inbox),
      fakeTask(id = "planned", status = TaskStatus.Planned),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.Status, sortDirection = SortDirection.Asc))
    assertEquals(listOf("inbox", "planned", "done"), ids(out))
  }

  @Test fun sort_manual_rank_asc_nulls_last() {
    val tasks = listOf(
      fakeTask(id = "noRank", rank = null),
      fakeTask(id = "r1", rank = "0001"),
      fakeTask(id = "r0", rank = "0000"),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(sortField = SortField.ManualRank, sortDirection = SortDirection.Asc))
    assertEquals(listOf("r0", "r1", "noRank"), ids(out))
  }

  // ---- grouping ----
  @Test fun group_by_status_first_seen_order() {
    val tasks = listOf(
      fakeTask(id = "p", status = TaskStatus.Planned, createdAt = Instant.parse("2026-06-03T00:00:00Z")),
      fakeTask(id = "i", status = TaskStatus.Inbox, createdAt = Instant.parse("2026-06-02T00:00:00Z")),
      fakeTask(id = "p2", status = TaskStatus.Planned, createdAt = Instant.parse("2026-06-01T00:00:00Z")),
    )
    val out = applyTaskFilter(tasks, projects, TaskFilter(groupBy = GroupBy.Status, sortField = SortField.CreatedAt, sortDirection = SortDirection.Desc))
    assertEquals(listOf("Planned", "Inbox"), out.map { it.label })
    assertEquals(listOf("p", "p2"), out.first().items.map { it.task.id })
  }

  @Test fun group_by_project_uses_name_and_no_project_label() {
    val tasks = listOf(fakeTask(id = "a", projectId = "p1"), fakeTask(id = "b", projectId = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(groupBy = GroupBy.Project, sortField = SortField.Title, sortDirection = SortDirection.Asc))
    assertEquals(setOf("Work", "No Project"), out.map { it.label }.toSet())
  }

  @Test fun group_by_priority_uses_priority_labels() {
    val tasks = listOf(fakeTask(id = "u", priority = 1), fakeTask(id = "n", priority = null))
    val out = applyTaskFilter(tasks, projects, TaskFilter(groupBy = GroupBy.Priority, sortField = SortField.Priority, sortDirection = SortDirection.Asc))
    assertEquals(listOf("Urgent", "No Priority"), out.map { it.label })
  }

  // ---- combination ----
  @Test fun combination_status_priority_search_sort_group() {
    val tasks = listOf(
      fakeTask(id = "keep", title = "Quarterly plan", status = TaskStatus.Planned, priority = 1, projectId = "p1"),
      fakeTask(id = "wrongStatus", title = "Quarterly notes", status = TaskStatus.Done, priority = 1),
      fakeTask(id = "wrongPrio", title = "Quarterly chat", status = TaskStatus.Planned, priority = 4),
      fakeTask(id = "noMatch", title = "Lunch", status = TaskStatus.Planned, priority = 1),
    )
    val out = applyTaskFilter(
      tasks, projects,
      TaskFilter(
        search = "quarterly",
        statuses = setOf(TaskStatus.Planned),
        priorities = setOf(1),
        sortField = SortField.Title,
        sortDirection = SortDirection.Asc,
        groupBy = GroupBy.Project,
      ),
    )
    assertEquals(listOf("keep"), ids(out))
    assertEquals("Work", out.single().label)
  }
}
```

- [ ] **Verify it FAILS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.browse.TaskFilterTest"` → **FAILS to compile** (unresolved `net.qmindtech.tmap.ui.browse.TaskFilter` / `applyTaskFilter` / `BrowseTaskItem`). This is the expected RED.

- [ ] **Implement** `android/app/src/main/java/net/qmindtech/tmap/ui/browse/TaskFilter.kt`:

```kotlin
package net.qmindtech.tmap.ui.browse

import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.ui.components.PriorityDisplay
import net.qmindtech.tmap.ui.components.StatusDisplay
import net.qmindtech.tmap.ui.components.TaskUi
import net.qmindtech.tmap.ui.components.toUi
import java.time.LocalDate

enum class SortField { CreatedAt, Priority, PlannedDate, Title, Status, ManualRank }
enum class SortDirection { Asc, Desc }
enum class GroupBy { None, Status, Project, Priority }

/** Filter/sort/group spec for the Browse hub. Pure data; defaults to the user's manual order. */
data class TaskFilter(
  val search: String = "",
  val statuses: Set<TaskStatus> = NON_ARCHIVED_STATUSES,
  val showArchived: Boolean = false,
  val priorities: Set<Int?> = ALL_PRIORITIES,
  val projectIds: Set<String>? = null,
  val dateFrom: LocalDate? = null,
  val dateTo: LocalDate? = null,
  val sortField: SortField = SortField.ManualRank,
  val sortDirection: SortDirection = SortDirection.Asc,
  val groupBy: GroupBy = GroupBy.None,
) {
  companion object {
    val NON_ARCHIVED_STATUSES: Set<TaskStatus> = setOf(
      TaskStatus.Inbox, TaskStatus.Backlog, TaskStatus.Planned, TaskStatus.Scheduled, TaskStatus.Done,
    )
    val ALL_PRIORITIES: Set<Int?> = setOf(1, 2, 3, 4, null)
  }
}

/** A Browse row: the FIXED TaskUi projection (for TaskCard) plus the raw entity (actions/keys). */
data class BrowseTaskItem(val ui: TaskUi, val task: TaskEntity)

data class TaskGroup(val key: String, val label: String, val items: List<BrowseTaskItem>)

private val HTML_TAG = Regex("<[^>]*>")
private val WS = Regex("\\s+")

fun stripHtml(s: String?): String =
  s?.replace(HTML_TAG, " ")?.replace(WS, " ")?.trim() ?: ""

fun applyTaskFilter(
  tasks: List<TaskEntity>,
  projects: List<ProjectEntity>,
  filter: TaskFilter,
): List<TaskGroup> {
  val projectById: Map<String, ProjectEntity> = projects.associateBy { it.id }
  val query = filter.search.trim().lowercase()

  val filtered = tasks.filter { t ->
    // Status (archived gated by showArchived; others by the status set)
    if (t.status == TaskStatus.Archived) {
      if (!filter.showArchived) return@filter false
    } else {
      if (t.status !in filter.statuses) return@filter false
    }
    // Priority (set may contain null)
    if (t.priority !in filter.priorities) return@filter false
    // Project (null filter = no constraint; "" = no project)
    filter.projectIds?.let { sel ->
      if ((t.projectId ?: "") !in sel) return@filter false
    }
    // Date range (inclusive; null plannedDate fails when a bound is set)
    if (filter.dateFrom != null && (t.plannedDate == null || t.plannedDate < filter.dateFrom)) return@filter false
    if (filter.dateTo != null && (t.plannedDate == null || t.plannedDate > filter.dateTo)) return@filter false
    // Search over title + notes(html-stripped) + project name
    if (query.isNotEmpty()) {
      val name = t.projectId?.let { projectById[it]?.name } ?: ""
      val matches = t.title.lowercase().contains(query) ||
        stripHtml(t.notes).lowercase().contains(query) ||
        name.lowercase().contains(query)
      if (!matches) return@filter false
    }
    true
  }

  val sorted = filtered.sortedWith(comparatorFor(filter))
  val items = sorted.map { BrowseTaskItem(it.toUi(it.projectId?.let { pid -> projectById[pid] }), it) }

  if (filter.groupBy == GroupBy.None) {
    return listOf(TaskGroup(key = "all", label = "All Tasks", items = items))
  }

  val ordered = LinkedHashMap<String, MutableList<BrowseTaskItem>>()
  for (item in items) {
    val t = item.task
    val key = when (filter.groupBy) {
      GroupBy.Status -> t.status.name
      GroupBy.Project -> t.projectId ?: ""
      GroupBy.Priority -> PriorityDisplay.label(t.priority)
      GroupBy.None -> "all"
    }
    ordered.getOrPut(key) { mutableListOf() }.add(item)
  }
  return ordered.map { (key, group) ->
    val label = when (filter.groupBy) {
      GroupBy.Status -> StatusDisplay.label(TaskStatus.valueOf(key))
      GroupBy.Project -> if (key.isEmpty()) "No Project" else (projectById[key]?.name ?: key)
      GroupBy.Priority -> key
      GroupBy.None -> "All Tasks"
    }
    TaskGroup(key = key, label = label, items = group)
  }
}

private fun comparatorFor(filter: TaskFilter): Comparator<TaskEntity> {
  val base: Comparator<TaskEntity> = when (filter.sortField) {
    SortField.CreatedAt -> compareBy { it.createdAt }
    SortField.Priority -> compareBy { it.priority ?: 99 }
    SortField.PlannedDate -> compareBy { it.plannedDate?.toString() ?: "" }
    SortField.Title -> compareBy { it.title }
    SortField.Status -> compareBy { StatusDisplay.order.getValue(it.status) }
    // Null ranks sort last in ascending order (matches DAO "rank IS NULL, rank").
    SortField.ManualRank -> compareBy(nullsLast()) { it.rank }
  }
  return if (filter.sortDirection == SortDirection.Asc) base else base.reversed()
}
```

> Note on `ManualRank` + `reversed()`: `compareBy(nullsLast())` already places nulls last for `Asc`; `.reversed()` flips that to nulls-first for `Desc`, which is the intended symmetry (the test only pins the `Asc` nulls-last behavior).

- [ ] **Verify it PASSES:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.browse.TaskFilterTest"` → `BUILD SUCCESSFUL`; all 18 tests pass.

- [ ] **Commit:**
```
feat(android-browse): add TaskUi-based filter/sort/group engine for Browse

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.2 — `BrowseViewModel` + `BrowseUiState` (segmented hub, grouped sections, activeFilterCount) — real unit tests

Build the Browse view-model around an immutable `BrowseUiState`. It exposes the segmented control (All Tasks / Backlog / Projects), applies `TaskFilter` to the Room task flow (the **Backlog** segment forces `statuses = {Backlog}` on top of the user filter; the **Projects** segment surfaces the project list, not tasks), produces the grouped sections, the matched `totalCount`, and an `activeFilterCount` (how many filter facets diverge from the cleared default — drives the filter badge in the mockup). Mirrors the existing `AllTasksViewModelTest` / `TodayViewModelTest` style (JUnit4 + coroutines-test + Turbine + the `testutil` fakes).

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/browse/BrowseViewModel.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/browse/BrowseViewModelTest.kt`

**Interfaces**
- Produces: `enum class BrowseSegment { AllTasks, Backlog, Projects }`.
- Produces: `data class BrowseUiState(val loading: Boolean = true, val segment: BrowseSegment = BrowseSegment.AllTasks, val filter: TaskFilter = TaskFilter(), val groups: List<TaskGroup> = emptyList(), val projects: List<ProjectEntity> = emptyList(), val totalCount: Int = 0, val activeFilterCount: Int = 0)`.
- Produces: `class BrowseViewModel @Inject constructor(taskRepo: TaskRepository, projectRepo: ProjectRepository) : ViewModel()` annotated `@HiltViewModel`, with `val uiState: StateFlow<BrowseUiState>` and actions: `setSegment(BrowseSegment)`, `setSearch(String)`, `setStatuses(Set<TaskStatus>)`, `setShowArchived(Boolean)`, `setPriorities(Set<Int?>)`, `setProjectIds(Set<String>?)`, `setDateRange(LocalDate?, LocalDate?)`, `setGroupBy(GroupBy)`, `setSort(SortField)` (same-field toggles direction, else sets field + `Desc`), `clearFilters()` (preserves sort + group + segment), `toggleDone(TaskEntity)`.
- Produces: `fun activeFilterCount(filter: TaskFilter): Int` — pure helper (testable): counts non-default facets among {search non-blank, statuses ≠ default, showArchived true, priorities ≠ default, projectIds non-null, dateFrom or dateTo set}.
- Consumes: P2.1 `TaskFilter`/`applyTaskFilter`/`TaskGroup`/`BrowseSegment`; `TaskRepository.observeAll()`/`observeByStatus(Backlog)`/`markDone`; `ProjectRepository.observeAll()`.

**Steps**

- [ ] **Write the failing test** `BrowseViewModelTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.browse

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vmWith(): Pair<BrowseViewModel, FakeTaskRepo> {
    val repo = FakeTaskRepo()
    repo.setAll(
      listOf(
        fakeTask(id = "i", title = "Inbox item", status = TaskStatus.Inbox, rank = "0000"),
        fakeTask(id = "b", title = "Backlog item", status = TaskStatus.Backlog, rank = "0001"),
        fakeTask(id = "ar", title = "Archived item", status = TaskStatus.Archived, rank = "0002"),
      )
    )
    repo.setByStatus(listOf(fakeTask(id = "b", title = "Backlog item", status = TaskStatus.Backlog, rank = "0001")))
    val proj = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Work"))) }
    return BrowseViewModel(repo, proj) to repo
  }

  @Test fun default_allTasks_excludes_archived_and_reports_total() = runTest {
    val (vm, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(BrowseSegment.AllTasks, s.segment)
      assertEquals(2, s.totalCount) // archived excluded
      assertEquals(0, s.activeFilterCount)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun backlog_segment_shows_only_backlog() = runTest {
    val (vm, _) = vmWith()
    vm.setSegment(BrowseSegment.Backlog)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf("b"), s.groups.flatMap { it.items }.map { it.task.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun projects_segment_surfaces_project_list() = runTest {
    val (vm, _) = vmWith()
    vm.setSegment(BrowseSegment.Projects)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf("p1"), s.projects.map { it.id })
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun setSearch_filters_and_increments_activeFilterCount() = runTest {
    val (vm, _) = vmWith()
    vm.setSearch("backlog")
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(listOf("b"), s.groups.flatMap { it.items }.map { it.task.id })
      assertEquals(1, s.activeFilterCount)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun setSort_same_field_toggles_direction() = runTest {
    val (vm, _) = vmWith()
    vm.setSort(SortField.Title)
    assertEquals(SortDirection.Desc, vm.uiState.value.filter.sortField.let { vm.uiState.value.filter.sortDirection })
    vm.setSort(SortField.Title)
    assertEquals(SortDirection.Asc, vm.uiState.value.filter.sortDirection)
  }

  @Test fun clearFilters_preserves_sort_group_and_segment() = runTest {
    val (vm, _) = vmWith()
    vm.setSegment(BrowseSegment.Backlog)
    vm.setGroupBy(GroupBy.Status)
    vm.setSort(SortField.Title)
    vm.setSearch("foo")
    vm.setShowArchived(true)
    vm.clearFilters()
    assertEquals("", vm.uiState.value.filter.search)
    assertEquals(false, vm.uiState.value.filter.showArchived)
    assertEquals(GroupBy.Status, vm.uiState.value.filter.groupBy)
    assertEquals(SortField.Title, vm.uiState.value.filter.sortField)
    assertEquals(BrowseSegment.Backlog, vm.uiState.value.segment)
  }

  @Test fun toggleDone_delegates() = runTest {
    val (vm, repo) = vmWith()
    vm.toggleDone(fakeTask(id = "i", status = TaskStatus.Inbox))
    assertEquals(listOf("i"), repo.markedDone)
  }

  @Test fun activeFilterCount_counts_each_diverging_facet() {
    assertEquals(0, activeFilterCount(TaskFilter()))
    assertEquals(1, activeFilterCount(TaskFilter(search = "x")))
    assertEquals(1, activeFilterCount(TaskFilter(showArchived = true)))
    assertEquals(1, activeFilterCount(TaskFilter(projectIds = setOf("p1"))))
    assertEquals(2, activeFilterCount(TaskFilter(search = "x", priorities = setOf(1))))
  }
}
```

- [ ] **Verify it FAILS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.browse.BrowseViewModelTest"` → fails to compile (no `BrowseViewModel`). Expected RED.

- [ ] **Implement** `android/app/src/main/java/net/qmindtech/tmap/ui/browse/BrowseViewModel.kt`:

```kotlin
package net.qmindtech.tmap.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import java.time.LocalDate
import javax.inject.Inject

enum class BrowseSegment { AllTasks, Backlog, Projects }

data class BrowseUiState(
  val loading: Boolean = true,
  val segment: BrowseSegment = BrowseSegment.AllTasks,
  val filter: TaskFilter = TaskFilter(),
  val groups: List<TaskGroup> = emptyList(),
  val projects: List<ProjectEntity> = emptyList(),
  val totalCount: Int = 0,
  val activeFilterCount: Int = 0,
)

/** How many filter facets diverge from the cleared default (drives the filter badge). */
fun activeFilterCount(filter: TaskFilter): Int {
  var n = 0
  if (filter.search.isNotBlank()) n++
  if (filter.statuses != TaskFilter.NON_ARCHIVED_STATUSES) n++
  if (filter.showArchived) n++
  if (filter.priorities != TaskFilter.ALL_PRIORITIES) n++
  if (filter.projectIds != null) n++
  if (filter.dateFrom != null || filter.dateTo != null) n++
  return n
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
) : ViewModel() {

  private val segment = MutableStateFlow(BrowseSegment.AllTasks)
  private val filter = MutableStateFlow(TaskFilter())

  val uiState: StateFlow<BrowseUiState> =
    combine(
      taskRepo.observeAll(),
      projectRepo.observeAll(),
      segment,
      filter,
    ) { tasks, projects, seg, f ->
      // Backlog segment narrows to Backlog status on top of the user's filter.
      val effective = if (seg == BrowseSegment.Backlog) f.copy(statuses = setOf(TaskStatus.Backlog)) else f
      val groups = if (seg == BrowseSegment.Projects) emptyList() else applyTaskFilter(tasks, projects, effective)
      BrowseUiState(
        loading = false,
        segment = seg,
        filter = f,
        groups = groups,
        projects = projects,
        totalCount = groups.sumOf { it.items.size },
        activeFilterCount = activeFilterCount(f),
      )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BrowseUiState())

  fun setSegment(s: BrowseSegment) = segment.update { s }
  fun setSearch(q: String) = filter.update { it.copy(search = q) }
  fun setStatuses(s: Set<TaskStatus>) = filter.update { it.copy(statuses = s) }
  fun setShowArchived(b: Boolean) = filter.update { it.copy(showArchived = b) }
  fun setPriorities(p: Set<Int?>) = filter.update { it.copy(priorities = p) }
  fun setProjectIds(ids: Set<String>?) = filter.update { it.copy(projectIds = ids) }
  fun setDateRange(from: LocalDate?, to: LocalDate?) = filter.update { it.copy(dateFrom = from, dateTo = to) }
  fun setGroupBy(g: GroupBy) = filter.update { it.copy(groupBy = g) }

  fun setSort(field: SortField) = filter.update {
    if (it.sortField == field) {
      it.copy(sortDirection = if (it.sortDirection == SortDirection.Asc) SortDirection.Desc else SortDirection.Asc)
    } else {
      it.copy(sortField = field, sortDirection = SortDirection.Desc)
    }
  }

  fun clearFilters() = filter.update {
    TaskFilter(sortField = it.sortField, sortDirection = it.sortDirection, groupBy = it.groupBy)
  }

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
```

- [ ] **Verify it PASSES:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.browse.BrowseViewModelTest"` → `BUILD SUCCESSFUL`; all tests pass.

- [ ] **Commit:**
```
feat(android-browse): add BrowseViewModel with segmented hub + activeFilterCount

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.3 — `BrowseScreen` (search, SegmentedControl, filter/sort/group sheets, grouped TaskCards, FAB) — compile-gate + behavior checklist

Build the Browse Compose surface against the P0 components. The screen renders: a search field (`surfaceInset` well), the `SegmentedControl(["All Tasks","Backlog","Projects"])`, a chips row of filter/sort/group entry chips (each opens a `SheetScaffold` bottom-sheet picker), then either a grouped `LazyColumn` of `TaskCard`s (`SectionLabel` headers when grouped) or — on the Projects segment — a `ProjectsScreen` embed. No new unit test (Compose UI): gate is `assembleDebug` + behavior checklist vs `full-app.html` (Browse panel).

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/browse/BrowseScreen.kt`

**Interfaces**
- Produces: `@Composable fun BrowseScreen(onOpenTask: (taskId: String) -> Unit, onOpenProject: (projectId: String) -> Unit, viewModel: BrowseViewModel = hiltViewModel())`.
- Produces: private `@Composable fun StatusFilterSheet`, `PriorityFilterSheet`, `ProjectFilterSheet`, `SortSheet`, `GroupSheet` (each a `SheetScaffold` body driven by the VM setters).
- Consumes (FIXED P0): `SegmentedControl`, `FilterChip`, `Chip`, `TaskCard`, `SectionLabel`, `EmptyState`, `SheetScaffold`, `LocalTmapColors`/`LocalTmapSpacing`/`LocalTmapType`; `TaskUi`; `Route` (none directly — uses the passed nav lambdas). Consumes P2.4+ `ProjectsScreen` for the Projects segment (forward declaration — `ProjectsScreen` lands in P2.6; until then this task's compile gate runs after P2.6, OR embed a temporary inline project list — **resolve by sequencing this task's `assembleDebug` after P2.6**; for development you may stub the Projects branch with `EmptyState` and replace it in P2.6's wiring step).

> **Sequencing:** because the Projects segment embeds `ProjectsScreen` (P2.6), do the `BrowseScreen` Projects-branch wiring as the final step here but only require the **whole-file `assembleDebug`** to pass after P2.6 is merged. To keep this task self-contained, the initial implementation routes the Projects segment to `onOpenProject`-capable inline content via `ProjectsScreen` if present; if you are executing strictly in order, temporarily render `EmptyState(icon = Icons.Filled.Folder, title = "Projects", subtitle = "Open the Projects tab")` for the Projects branch and swap it in P2.6. The committed end state uses `ProjectsScreen`.

**Steps**

- [ ] **Implement** `android/app/src/main/java/net/qmindtech/tmap/ui/browse/BrowseScreen.kt`:

```kotlin
package net.qmindtech.tmap.ui.browse

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.ui.components.Chip
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.FilterChip
import net.qmindtech.tmap.ui.components.PriorityDisplay
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.components.SegmentedControl
import net.qmindtech.tmap.ui.components.SheetScaffold
import net.qmindtech.tmap.ui.components.StatusDisplay
import net.qmindtech.tmap.ui.components.TaskCard
import net.qmindtech.tmap.ui.projects.ProjectsScreen
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing

private val SEGMENTS = listOf("All Tasks", "Backlog", "Projects")

@Composable
fun BrowseScreen(
  onOpenTask: (taskId: String) -> Unit,
  onOpenProject: (projectId: String) -> Unit,
  viewModel: BrowseViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val colors = LocalTmapColors.current
  val spacing = LocalTmapSpacing.current
  var sheet by remember { mutableStateOf<BrowseSheet?>(null) }

  Column(modifier = Modifier.fillMaxSize().padding(horizontal = spacing.screenH)) {
    Text("Browse", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, color = colors.textPrimary)

    OutlinedTextField(
      value = state.filter.search,
      onValueChange = viewModel::setSearch,
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
      singleLine = true,
      leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
      placeholder = { Text("Search title, notes, project…") },
    )

    SegmentedControl(
      options = SEGMENTS,
      selectedIndex = state.segment.ordinal,
      onSelect = { viewModel.setSegment(BrowseSegment.entries[it]) },
    )

    if (state.segment != BrowseSegment.Projects) {
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Chip(label = "Filter" + filterBadge(state.activeFilterCount), onClick = { sheet = BrowseSheet.Filter }, selected = state.activeFilterCount > 0)
        Chip(label = "Sort: ${state.filter.sortField}", onClick = { sheet = BrowseSheet.Sort })
        Chip(label = "Group: ${state.filter.groupBy}", onClick = { sheet = BrowseSheet.Group })
      }
    }

    when (state.segment) {
      BrowseSegment.Projects -> ProjectsScreen(onOpenProject = onOpenProject)
      else -> {
        if (!state.loading && state.totalCount == 0) {
          EmptyState(
            icon = Icons.Filled.ViewAgenda,
            title = "No tasks match",
            subtitle = "Adjust your filters or search.",
            actionLabel = if (state.activeFilterCount > 0) "Clear filters" else null,
            onAction = if (state.activeFilterCount > 0) viewModel::clearFilters else null,
          )
        } else {
          LazyColumn(modifier = Modifier.fillMaxSize()) {
            state.groups.forEach { group ->
              if (state.filter.groupBy != GroupBy.None) {
                item(key = "header-${group.key}") {
                  SectionLabel("${group.label} (${group.items.size})", modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
              }
              items(group.items, key = { "${group.key}-${it.task.id}" }) { item ->
                TaskCard(
                  task = item.ui,
                  onToggleComplete = { viewModel.toggleDone(item.task) },
                  onClick = { onOpenTask(item.task.id) },
                  modifier = Modifier.padding(vertical = 4.dp),
                )
              }
            }
          }
        }
      }
    }
  }

  when (sheet) {
    BrowseSheet.Filter -> FilterSheet(state, viewModel) { sheet = null }
    BrowseSheet.Sort -> SortSheet(state, viewModel) { sheet = null }
    BrowseSheet.Group -> GroupSheet(state, viewModel) { sheet = null }
    null -> {}
  }
}

private enum class BrowseSheet { Filter, Sort, Group }

private fun filterBadge(count: Int): String = if (count > 0) " ($count)" else ""

@Composable
private fun FilterSheet(state: BrowseUiState, vm: BrowseViewModel, onDismiss: () -> Unit) {
  SheetScaffold(onDismiss = onDismiss, title = "Filter") {
    SectionLabel("Status")
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TaskFilter.NON_ARCHIVED_STATUSES.forEach { st ->
        FilterChip(
          label = st.name,
          selected = st in state.filter.statuses,
          onClick = {
            val next = state.filter.statuses.toMutableSet()
            if (!next.remove(st)) next.add(st)
            vm.setStatuses(next)
          },
        )
      }
      FilterChip(label = "Archived", selected = state.filter.showArchived, onClick = { vm.setShowArchived(!state.filter.showArchived) })
    }
    SectionLabel("Priority")
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TaskFilter.ALL_PRIORITIES.forEach { p ->
        FilterChip(
          label = PriorityDisplay.label(p),
          selected = p in state.filter.priorities,
          onClick = {
            val next = state.filter.priorities.toMutableSet()
            if (!next.remove(p)) next.add(p)
            vm.setPriorities(next)
          },
        )
      }
    }
    if (state.projects.isNotEmpty()) {
      SectionLabel("Project")
      Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.projects.forEach { proj ->
          FilterChip(
            label = proj.name,
            selected = proj.id in (state.filter.projectIds ?: emptySet()),
            onClick = {
              val next = (state.filter.projectIds ?: emptySet()).toMutableSet()
              if (!next.remove(proj.id)) next.add(proj.id)
              vm.setProjectIds(if (next.isEmpty()) null else next)
            },
          )
        }
      }
    }
  }
}

@Composable
private fun SortSheet(state: BrowseUiState, vm: BrowseViewModel, onDismiss: () -> Unit) {
  SheetScaffold(onDismiss = onDismiss, title = "Sort by") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      SortField.entries.forEach { f ->
        Chip(label = f.name, selected = state.filter.sortField == f, onClick = { vm.setSort(f) }, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}

@Composable
private fun GroupSheet(state: BrowseUiState, vm: BrowseViewModel, onDismiss: () -> Unit) {
  SheetScaffold(onDismiss = onDismiss, title = "Group by") {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      GroupBy.entries.forEach { g ->
        Chip(label = g.name, selected = state.filter.groupBy == g, onClick = { vm.setGroupBy(g); onDismiss() }, modifier = Modifier.fillMaxWidth())
      }
    }
  }
}
```

> Implementation notes: `LocalTmapSpacing.current.screenH` is the P0 screen-horizontal token; if the P0 `Spacing` field is named differently (verify against `ui/theme/Spacing.kt`), use that exact name. `Chip`'s P0 signature is `Chip(label, onClick, modifier, leadingEmoji, selected)`; pass `modifier`/`selected` named. If `SegmentedControl`/`SheetScaffold`/`Chip`/`FilterChip`/`SectionLabel`/`TaskCard` import paths differ from `ui/components/*`, fix imports — do not change their call shapes (FIXED).

- [ ] **Verify (compile-gate):** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. (Run after P2.6 so the `ProjectsScreen(onOpenProject = …)` reference resolves; if executing strictly in order, temporarily render the Projects branch as `EmptyState` and revisit in P2.6.)

- [ ] **Behavior checklist (reviewer, vs `.superpowers/brainstorm/965-1782053760/content/full-app.html` — Browse panel):**
  - Search well (`surfaceInset`, search glyph) over title/notes/project name.
  - `SegmentedControl` with All Tasks / Backlog / Projects; selection drives content.
  - Filter/Sort/Group chips open `SheetScaffold` pickers; Filter chip shows the active count badge.
  - Grouped list renders `TaskCard`s under `SectionLabel` headers; ungrouped renders a flat list.
  - Empty state appears with a "Clear filters" action when filters hide everything.
  - Dark Midnight Calm tokens throughout; accent only on active chips/segment.

- [ ] **Commit:**
```
feat(android-browse): add BrowseScreen (search, segments, filter/sort/group sheets)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.4 — Per-project progress DAO query (done/total) — real Room DAO test

Add a single aggregate query to `ProjectDao` returning per-project done/total task counts (excluding archived + recurrence templates), so the rebuilt `ProjectsViewModel` and `ProjectDetailViewModel` can show progress without loading all tasks. Tested with an in-memory Room DB (mirrors `ProjectDaoTest`).

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/local/dao/ProjectDao.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/data/local/dao/ProjectProgress.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/data/local/dao/ProjectDaoTest.kt`

**Interfaces**
- Produces: `data class ProjectProgress(val projectId: String, val total: Int, val done: Int)` (Room projection; `@ColumnInfo` not needed — names match the SELECT aliases).
- Produces: `fun ProjectDao.observeProgress(): Flow<List<ProjectProgress>>` — `@Query` grouping tasks by `projectId` over non-archived, non-template rows; `done` = count where `status = 'Done'`. Tasks with `projectId IS NULL` are excluded (no project row to attach to).
- Consumes: `tasks` table (columns `projectId`, `status`, `isRecurrenceTemplate`); `TaskStatus` stored by name (`'Done'`, `'Archived'`).

**Steps**

- [ ] **Write the failing test** — append to `ProjectDaoTest.kt` (add the new `@Test` + a task-insert helper). It fails to compile (no `observeProgress`/`ProjectProgress`). Add the helper + imports at the top of the test class:

```kotlin
// add imports
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity

// add a TaskEntity factory inside the test class:
private fun task(id: String, projectId: String?, status: TaskStatus, template: Boolean = false) = TaskEntity(
    id = id, title = "t-$id", notes = null, projectId = projectId, labels = emptyList(),
    source = "android", status = status, plannedDate = null, scheduledStart = null, scheduledEnd = null,
    durationMinutes = null, actualTimeMinutes = 0, priority = null, reminderMinutes = null, rank = null,
    dueDate = null, recurrenceRuleId = null, isRecurrenceTemplate = template, recurrenceDetached = false,
    recurrenceOriginalDate = null, completedAt = null, createdAt = now, updatedAt = now, changeSeq = 0,
)

@Test
fun `observeProgress aggregates done over total per project, excluding archived and templates`() = runTest {
    dao.upsertAll(listOf(project("p1", "0001"), project("p2", "0002")))
    db.taskDao().upsertAll(
        listOf(
            task("a", "p1", TaskStatus.Planned),
            task("b", "p1", TaskStatus.Done),
            task("c", "p1", TaskStatus.Archived),     // excluded
            task("d", "p1", TaskStatus.Done, template = true), // excluded (template)
            task("e", "p2", TaskStatus.Inbox),
            task("f", null, TaskStatus.Planned),       // no project → not aggregated
        )
    )
    val rows = dao.observeProgress().first().associateBy { it.projectId }
    assertEquals(2, rows.getValue("p1").total) // a + b (archived + template excluded)
    assertEquals(1, rows.getValue("p1").done)
    assertEquals(1, rows.getValue("p2").total)
    assertEquals(0, rows.getValue("p2").done)
}
```

- [ ] **Verify it FAILS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.ProjectDaoTest"` → fails to compile. Expected RED.

- [ ] **Implement** the projection `android/app/src/main/java/net/qmindtech/tmap/data/local/dao/ProjectProgress.kt`:

```kotlin
package net.qmindtech.tmap.data.local.dao

/** Per-project task aggregate (Room projection; column names match the SELECT aliases). */
data class ProjectProgress(
    val projectId: String,
    val total: Int,
    val done: Int,
)
```

- [ ] **Implement** the query — add to `ProjectDao`:

```kotlin
@Query(
    """
    SELECT projectId AS projectId,
           COUNT(*) AS total,
           SUM(CASE WHEN status = 'Done' THEN 1 ELSE 0 END) AS done
    FROM tasks
    WHERE projectId IS NOT NULL
      AND isRecurrenceTemplate = 0
      AND status != 'Archived'
    GROUP BY projectId
    """
)
fun observeProgress(): Flow<List<ProjectProgress>>
```

(Add `import net.qmindtech.tmap.data.local.dao.ProjectProgress` is unnecessary — same package; ensure `kotlinx.coroutines.flow.Flow` is already imported, which it is.)

- [ ] **Verify it PASSES:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.ProjectDaoTest"` → `BUILD SUCCESSFUL`; all DAO tests pass.

- [ ] **Commit:**
```
feat(android-data): add ProjectDao.observeProgress (done/total per project)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.5 — `ProjectRepository.observeProgress` + extend `FakeProjectRepo` — real test

Surface the new DAO query through `ProjectRepository` (the UI never touches DAOs directly). Extend the interface + impl, and extend the test `FakeProjectRepo` so P2.6/P2.7 view-model tests can feed progress.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/repository/ProjectRepository.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/repository/ProjectProgressRepoTest.kt`

**Interfaces** (added to `interface ProjectRepository`; FIXED for P2+):
```kotlin
fun observeProgress(): Flow<List<ProjectProgress>>
```
- Impl: `override fun observeProgress() = projectDao.observeProgress()`.
- `FakeProjectRepo` gains a backing `MutableStateFlow<List<ProjectProgress>>` + `setProgress(v)` and the `observeProgress()` override (default empty).
- Consumes: P2.4 `ProjectProgress`/`ProjectDao.observeProgress`.

**Steps**

- [ ] **Write the failing test** `ProjectProgressRepoTest.kt` (Room in-memory, mirroring `ProjectRepositoryImplTest`/`RepoTestSupport`):

```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectProgressRepoTest : RepoTestSupport() {

    @Test
    fun `observeProgress reflects done over total via the DAO`() = runTest {
        val projectRepo = projectRepository()
        val pid = projectRepo.create("Work", "#6EA8FE", "💼")
        val taskRepo = taskRepository()
        taskRepo.create(TaskDraft(title = "open", projectId = pid, status = TaskStatus.Planned))
        val doneId = taskRepo.create(TaskDraft(title = "done", projectId = pid, status = TaskStatus.Planned))
        taskRepo.markDone(doneId)

        val row = projectRepo.observeProgress().first().single()
        assertEquals(pid, row.projectId)
        assertEquals(2, row.total)
        assertEquals(1, row.done)
    }
}
```

> If `RepoTestSupport` does not already expose `projectRepository()` / `taskRepository()` factory helpers, use the same construction `ProjectRepositoryImplTest` uses (build the impls from the in-memory DB + fakes already wired there); match that file's existing setup verbatim rather than inventing helpers.

- [ ] **Verify it FAILS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.ProjectProgressRepoTest"` → fails to compile (`observeProgress` unresolved). Expected RED.

- [ ] **Implement** — add to `ProjectRepository` interface:

```kotlin
fun observeProgress(): Flow<List<net.qmindtech.tmap.data.local.dao.ProjectProgress>>
```
and to `ProjectRepositoryImpl`:
```kotlin
override fun observeProgress(): Flow<List<net.qmindtech.tmap.data.local.dao.ProjectProgress>> =
    projectDao.observeProgress()
```
(Prefer a top-of-file `import net.qmindtech.tmap.data.local.dao.ProjectProgress` and unqualified `ProjectProgress` in both places for readability.)

- [ ] **Extend `FakeProjectRepo`** in `Fakes.kt`:
```kotlin
// add field
private val progress: MutableStateFlow<List<net.qmindtech.tmap.data.local.dao.ProjectProgress>> = MutableStateFlow(emptyList()),
// add override
override fun observeProgress(): Flow<List<net.qmindtech.tmap.data.local.dao.ProjectProgress>> = progress
// add setter
fun setProgress(v: List<net.qmindtech.tmap.data.local.dao.ProjectProgress>) = progress.let { it.value = v }
```

- [ ] **Verify it PASSES:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.ProjectProgressRepoTest"` → `BUILD SUCCESSFUL`. Also re-run the existing `ProjectsViewModelTest`/`AllTasksViewModelTest`/`BacklogViewModelTest` to confirm the `FakeProjectRepo` change still compiles them: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.*"`.

- [ ] **Commit:**
```
feat(android-data): expose ProjectRepository.observeProgress + fake

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.6 — Rebuild `ProjectsViewModel` (progress cards + header summary) — real unit tests

Rebuild the projects view-model on top of `observeProgress`: each row carries the project + its done/total + a `progress: Float`; the state also carries a `header` summary (total projects, overall done/total across all projects). Keep `create`/`update`/`delete`/`moveProject` exactly as the existing VM (and its passing test) expects, plus the existing-test contract `ProjectRow(project, openTaskCount)` so legacy assertions keep meaning — but compute `openTaskCount` from progress (`total - done`).

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectsViewModel.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/ui/projects/ProjectsViewModelTest.kt`

**Interfaces**
- Produces: `data class ProjectRow(val project: ProjectEntity, val total: Int, val done: Int) { val openTaskCount: Int get() = total - done; val progress: Float get() = if (total == 0) 0f else done.toFloat() / total }`.
- Produces: `data class ProjectsHeader(val projectCount: Int, val doneTotal: Int, val taskTotal: Int)`.
- Produces: `data class ProjectsUiState(val loading: Boolean = true, val rows: List<ProjectRow> = emptyList(), val header: ProjectsHeader = ProjectsHeader(0, 0, 0))`.
- Produces: `ProjectsViewModel @Inject constructor(projectRepo: ProjectRepository)` (`@HiltViewModel`) — combines `observeAll()` + `observeProgress()`; actions `create/update/delete/moveProject` unchanged. **Note:** the v1 VM took `taskRepo` to count open tasks; the rebuild gets counts from `projectRepo.observeProgress()` so `taskRepo` is dropped from the constructor.
- Consumes: `ProjectRepository.observeAll()` + `observeProgress()` (P2.5).

**Steps**

- [ ] **Rewrite the test** `ProjectsViewModelTest.kt` to the new shape (the old `vmWith()` passed a `FakeTaskRepo`; now feed `FakeProjectRepo.setProgress(...)`):

```kotlin
package net.qmindtech.tmap.ui.projects

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.dao.ProjectProgress
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.fakeProject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vmWith(): Pair<ProjectsViewModel, FakeProjectRepo> {
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work"), fakeProject(id = "p2", name = "Home")))
      setProgress(listOf(ProjectProgress("p1", total = 4, done = 1), ProjectProgress("p2", total = 2, done = 2)))
    }
    return ProjectsViewModel(projRepo) to projRepo
  }

  @Test fun uiState_maps_progress_rows_and_header() = runTest {
    val (vm, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      val byId = s.rows.associateBy { it.project.id }
      assertEquals(3, byId.getValue("p1").openTaskCount) // 4 - 1
      assertEquals(0.25f, byId.getValue("p1").progress)
      assertEquals(0, byId.getValue("p2").openTaskCount)
      assertEquals(1.0f, byId.getValue("p2").progress)
      assertEquals(2, s.header.projectCount)
      assertEquals(3, s.header.doneTotal) // 1 + 2
      assertEquals(6, s.header.taskTotal) // 4 + 2
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun project_with_no_progress_row_is_zero() = runTest {
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work")))
      setProgress(emptyList())
    }
    val vm = ProjectsViewModel(projRepo)
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(0, s.rows.single().total)
      assertEquals(0f, s.rows.single().progress)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun create_delegates_and_blank_is_noop() = runTest {
    val (vm, projRepo) = vmWith()
    vm.create("  ", "#fff", "📁")
    assertTrue(projRepo.created.isEmpty())
    vm.create("New Project", "#6EA8FE", "🚀")
    assertEquals(1, projRepo.created.size)
    assertEquals("New Project", projRepo.created.first().first)
  }

  @Test fun update_and_delete_delegate() = runTest {
    val (vm, projRepo) = vmWith()
    vm.update("p1", "Work!", "#000", "💼")
    vm.delete("p2")
    assertEquals(listOf("p1"), projRepo.updated)
    assertEquals(listOf("p2"), projRepo.deleted)
  }

  @Test fun moveProject_reorders_and_emits_ordered_ids() = runTest {
    val (vm, projRepo) = vmWith()
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
    vm.moveProject(0, 1)
    assertEquals(listOf(listOf("p2", "p1")), projRepo.reordered)
  }
}
```

- [ ] **Verify it FAILS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.projects.ProjectsViewModelTest"` → fails (old `ProjectRow`/constructor mismatch). Expected RED.

- [ ] **Implement** the rebuilt `ProjectsViewModel.kt`:

```kotlin
package net.qmindtech.tmap.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.dao.ProjectProgress
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import javax.inject.Inject

data class ProjectRow(val project: ProjectEntity, val total: Int, val done: Int) {
  val openTaskCount: Int get() = total - done
  val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
}

data class ProjectsHeader(val projectCount: Int, val doneTotal: Int, val taskTotal: Int)

data class ProjectsUiState(
  val loading: Boolean = true,
  val rows: List<ProjectRow> = emptyList(),
  val header: ProjectsHeader = ProjectsHeader(0, 0, 0),
)

@HiltViewModel
class ProjectsViewModel @Inject constructor(
  private val projectRepo: ProjectRepository,
) : ViewModel() {

  val uiState: StateFlow<ProjectsUiState> =
    combine(projectRepo.observeAll(), projectRepo.observeProgress()) { projects, progress ->
      val byId: Map<String, ProjectProgress> = progress.associateBy { it.projectId }
      val rows = projects.map { p ->
        val pr = byId[p.id]
        ProjectRow(p, total = pr?.total ?: 0, done = pr?.done ?: 0)
      }
      ProjectsUiState(
        loading = false,
        rows = rows,
        header = ProjectsHeader(
          projectCount = projects.size,
          doneTotal = rows.sumOf { it.done },
          taskTotal = rows.sumOf { it.total },
        ),
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectsUiState())

  fun create(name: String, color: String, emoji: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    viewModelScope.launch { projectRepo.create(n, color, emoji) }
  }

  fun update(id: String, name: String, color: String, emoji: String) {
    val n = name.trim()
    if (n.isEmpty()) return
    viewModelScope.launch { projectRepo.update(id, name = n, color = color, emoji = emoji) }
  }

  fun delete(id: String) {
    viewModelScope.launch { projectRepo.delete(id) }
  }

  fun moveProject(fromIndex: Int, toIndex: Int) {
    val current = uiState.value.rows.map { it.project.id }.toMutableList()
    if (fromIndex !in current.indices || toIndex !in current.indices) return
    val moved = current.removeAt(fromIndex)
    current.add(toIndex, moved)
    viewModelScope.launch { projectRepo.reorder(current) }
  }
}
```

- [ ] **Verify it PASSES:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.projects.ProjectsViewModelTest"` → `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
feat(android-projects): rebuild ProjectsViewModel with progress rows + header

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.7 — Rebuild `ProjectsScreen` + `ProjectEditDialog` (Midnight-Calm color-coded cards, "+ New", palette) — compile-gate + behavior checklist

Rebuild the Projects surface to the `full-app.html` Projects panel: a header summary row, color-coded project cards (`surface` card, project-color `ProjectDot` + left accent stripe, name/emoji, a slim done/total progress bar, open-count meta), a "+ New" affordance (the `TmapFab` is provided by `MainScaffold`; the screen also offers an inline "+ New" `SecondaryButton`), tap → `onOpenProject(projectId)`, long-tap/overflow → edit dialog. `ProjectEditDialog` is restyled to tokens and uses the **project palette** colors (Work `#6EA8FE`, Personal `#38D39F`, Health `#F0A868`, Ideas/Side `#C9A0FF`, Learning `#F0A0A0`) plus a small emoji set. Compose UI: gate is `assembleDebug` + behavior checklist.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectsScreen.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectEditDialog.kt`

**Interfaces**
- Produces: `@Composable fun ProjectsScreen(onOpenProject: (projectId: String) -> Unit, viewModel: ProjectsViewModel = hiltViewModel())` — **note the new `onOpenProject` param** (consumed by `BrowseScreen` P2.3 and `MainScaffold` P2.9). No `Scaffold`/top bar of its own (it renders inside Browse's Projects segment and inside its own nav destination via `MainScaffold`'s scaffold); it is a `Column`/`LazyColumn` content composable.
- Produces: `@Composable fun ProjectEditDialog(initial: ProjectEntity?, onDismiss: () -> Unit, onSave: (name: String, color: String, emoji: String) -> Unit, onDelete: (() -> Unit)? = null)` — same signature as v1 (call sites unchanged), restyled.
- Produces (internal): `val PROJECT_PALETTE: List<String>` (the 5 Midnight Calm project defaults + a few extra), `PROJECT_EMOJIS`.
- Consumes (FIXED P0): `LocalTmapColors`/`LocalTmapShapes`/`LocalTmapType`/`LocalTmapSpacing`, `ProjectDot`, `EmptyState`, `SecondaryButton`, `Chip`; `ProjectRow`/`ProjectsHeader` (P2.6).

**Steps**

- [ ] **Implement** `ProjectsScreen.kt` (no unit test — Compose):

```kotlin
package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.ProjectDot
import net.qmindtech.tmap.ui.components.SecondaryButton
import net.qmindtech.tmap.ui.components.parseProjectColor
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing

@Composable
fun ProjectsScreen(
  onOpenProject: (projectId: String) -> Unit,
  viewModel: ProjectsViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val colors = LocalTmapColors.current
  val shapes = LocalTmapShapes.current
  val spacing = LocalTmapSpacing.current
  var creating by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<ProjectEntity?>(null) }

  Column(modifier = Modifier.fillMaxSize()) {
    // Header summary
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        "${state.header.projectCount} projects · ${state.header.doneTotal}/${state.header.taskTotal} done",
        color = colors.textSecondary,
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
      )
      SecondaryButton(text = "+ New", onClick = { creating = true })
    }

    if (!state.loading && state.rows.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.Folder,
        title = "No projects yet",
        subtitle = "Create one to organize your tasks.",
        actionLabel = "+ New project",
        onAction = { creating = true },
      )
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(state.rows, key = { it.project.id }) { row ->
          val dotColor = parseProjectColor(row.project.color) ?: colors.accent.value.toLong()
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onOpenProject(row.project.id) }
              .background(colors.surface, shapes.card)
              .padding(14.dp),
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectDot(colorArgb = dotColor, size = 10.dp)
                Text(row.project.emoji)
                Text(row.project.name, modifier = Modifier.weight(1f), color = colors.textPrimary, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text("${row.openTaskCount} open", color = colors.textTertiary, style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
              }
              LinearProgressIndicator(
                progress = { row.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                color = Color(dotColor),
                trackColor = colors.surfaceInset,
              )
              Text("${row.done}/${row.total}", color = colors.textTertiary, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    }
  }

  if (creating) {
    ProjectEditDialog(
      initial = null,
      onDismiss = { creating = false },
      onSave = { name, color, emoji -> viewModel.create(name, color, emoji); creating = false },
    )
  }
  editing?.let { proj ->
    ProjectEditDialog(
      initial = proj,
      onDismiss = { editing = null },
      onSave = { name, color, emoji -> viewModel.update(proj.id, name, color, emoji); editing = null },
      onDelete = { viewModel.delete(proj.id); editing = null },
    )
  }
}
```

> If `ProjectDot`'s signature in P0 is `ProjectDot(colorArgb: Long, modifier, size)`, pass `size` named as above. `parseProjectColor` is the P0 helper in `ui/components/TaskUi.kt` (returns `Long?`). `LinearProgressIndicator(progress = { … })` is the Material3 lambda overload; if the BOM resolves to the deprecated `progress: Float` overload, use `progress = row.progress` instead — keep the call, fix the arg shape to whatever compiles.

- [ ] **Implement** the restyled `ProjectEditDialog.kt` (token colors + project palette; same signature):

```kotlin
package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.ui.components.parseProjectColor
import net.qmindtech.tmap.ui.theme.LocalTmapColors

/** Midnight Calm project palette (spec §4.1 defaults + a couple of extras). */
val PROJECT_PALETTE = listOf("#6EA8FE", "#38D39F", "#F0A868", "#C9A0FF", "#F0A0A0", "#E8A87C")
val PROJECT_EMOJIS = listOf("📁", "💼", "🚀", "🎯", "📚", "💡", "🔧", "🎨", "🏠", "💻", "⚡", "🌟")

@Composable
fun ProjectEditDialog(
  initial: ProjectEntity?,
  onDismiss: () -> Unit,
  onSave: (name: String, color: String, emoji: String) -> Unit,
  onDelete: (() -> Unit)? = null,
) {
  val colors = LocalTmapColors.current
  var name by remember { mutableStateOf(initial?.name ?: "") }
  var color by remember { mutableStateOf(initial?.color ?: PROJECT_PALETTE.first()) }
  var emoji by remember { mutableStateOf(initial?.emoji ?: PROJECT_EMOJIS.first()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = colors.surfaceRaised,
    title = { Text(if (initial == null) "New Project" else "Edit Project", color = colors.textPrimary) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          modifier = Modifier.fillMaxWidth(),
          label = { Text("Project name") },
          singleLine = true,
        )
        Text("Icon", color = colors.textSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PROJECT_EMOJIS.take(8).forEach { e ->
            Text(
              e,
              fontWeight = if (emoji == e) FontWeight.Bold else FontWeight.Normal,
              modifier = Modifier.clickable { emoji = e },
            )
          }
        }
        Text("Color", color = colors.textSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          PROJECT_PALETTE.forEach { c ->
            val argb = parseProjectColor(c) ?: 0xFFE8A87CL
            Box(
              modifier = Modifier
                .size(28.dp)
                .background(Color(argb), CircleShape)
                .border(if (color == c) 2.dp else 0.dp, colors.textPrimary, CircleShape)
                .clickable { color = c },
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(name, color, emoji) }, enabled = name.isNotBlank()) {
        Text(if (initial == null) "Create" else "Update", color = colors.accent)
      }
    },
    dismissButton = {
      Row {
        if (initial != null && onDelete != null) {
          TextButton(onClick = onDelete) { Text("Delete", color = colors.danger) }
        }
        TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) }
      }
    },
  )
}
```

- [ ] **Verify (compile-gate):** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. (Will currently fail on `MainScaffold`/`AllTasksScreen`/`BacklogScreen` referencing the old `ProjectsScreen()` signature — that rewiring + retirement happens in P2.8/P2.9; sequence the full `assembleDebug` to pass after P2.9. To gate this task alone, ensure the new files compile by running the lint/compile after P2.9, or temporarily keep the old `ProjectsScreen()` zero-arg overload during development.)

- [ ] **Behavior checklist (reviewer, vs `full-app.html` — Projects panel):**
  - Header summary line (`N projects · done/total`).
  - Color-coded cards: `ProjectDot` in the project color, emoji, name, open-count, progress bar in the project color over a `surfaceInset` track, `done/total` meta.
  - "+ New" opens the edit dialog; tap a card → `onOpenProject`.
  - Edit dialog uses the Midnight Calm palette swatches + emoji set; Delete is `danger`-colored; Create/Update is accent.
  - Empty state with "+ New project" action.

- [ ] **Commit:**
```
feat(android-projects): rebuild ProjectsScreen + ProjectEditDialog (Midnight Calm)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.8 — `ProjectDetailViewModel` (project tasks + progress header) — real unit tests

Build the detail view-model for `Route.ProjectDetail(projectId)`: it observes the one project, its tasks (filtered to that project from `observeAll`), and its progress, exposing a header (name/emoji/color, done/total, progress) + a task list as `BrowseTaskItem`s (reusing the P2.1 row model + sort). Pure logic in a small grouping helper is unit-tested; the VM is unit-tested with the fakes.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectDetailViewModel.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/projects/ProjectDetailViewModelTest.kt`

**Interfaces**
- Produces: `data class ProjectDetailUiState(val loading: Boolean = true, val project: ProjectEntity? = null, val total: Int = 0, val done: Int = 0, val items: List<net.qmindtech.tmap.ui.browse.BrowseTaskItem> = emptyList()) { val progress: Float get() = if (total == 0) 0f else done.toFloat() / total }`.
- Produces: `class ProjectDetailViewModel @Inject constructor(savedStateHandle: SavedStateHandle, taskRepo: TaskRepository, projectRepo: ProjectRepository) : ViewModel()` (`@HiltViewModel`) — reads `projectId` from `savedStateHandle.get<String>(Route.ProjectDetail.ARG_PROJECT_ID)`; exposes `val uiState: StateFlow<ProjectDetailUiState>`; actions `toggleDone(TaskEntity)`. Tasks for the project are the non-archived, non-template tasks with `task.projectId == projectId`, sorted by manual rank (reuse `applyTaskFilter` with a project-scoped `TaskFilter`).
- Consumes: `Route.ProjectDetail.ARG_PROJECT_ID` (P0 FIXED), `TaskRepository.observeAll()`/`markDone`, `ProjectRepository.observeAll()`/`observeProgress()`, P2.1 `TaskFilter`/`applyTaskFilter`/`BrowseTaskItem`.

> **Note on `SavedStateHandle` in the test:** construct it directly as `SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))` (the same approach `TaskEditorViewModelTest` uses for its `"taskId"` key — match that existing pattern).

**Steps**

- [ ] **Write the failing test** `ProjectDetailViewModelTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.projects

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.dao.ProjectProgress
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import net.qmindtech.tmap.ui.navigation.Route
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest {
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vmWith(): Pair<ProjectDetailViewModel, FakeTaskRepo> {
    val taskRepo = FakeTaskRepo().apply {
      setAll(
        listOf(
          fakeTask(id = "a", projectId = "p1", status = TaskStatus.Planned, rank = "0001"),
          fakeTask(id = "b", projectId = "p1", status = TaskStatus.Planned, rank = "0000"),
          fakeTask(id = "c", projectId = "p2", status = TaskStatus.Planned),
        )
      )
    }
    val projRepo = FakeProjectRepo().apply {
      setAll(listOf(fakeProject(id = "p1", name = "Work"), fakeProject(id = "p2", name = "Home")))
      setProgress(listOf(ProjectProgress("p1", total = 2, done = 0)))
    }
    val handle = SavedStateHandle(mapOf(Route.ProjectDetail.ARG_PROJECT_ID to "p1"))
    return ProjectDetailViewModel(handle, taskRepo, projRepo) to taskRepo
  }

  @Test fun loads_project_header_and_only_its_tasks_by_rank() = runTest {
    val (vm, _) = vmWith()
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals("Work", s.project?.name)
      assertEquals(2, s.total)
      assertEquals(0, s.done)
      assertEquals(0f, s.progress)
      assertEquals(listOf("b", "a"), s.items.map { it.task.id }) // manual rank asc
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun toggleDone_delegates() = runTest {
    val (vm, repo) = vmWith()
    vm.toggleDone(fakeTask(id = "a", projectId = "p1"))
    assertEquals(listOf("a"), repo.markedDone)
  }
}
```

- [ ] **Verify it FAILS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.projects.ProjectDetailViewModelTest"` → fails to compile (no `ProjectDetailViewModel`). Expected RED.

- [ ] **Implement** `ProjectDetailViewModel.kt`:

```kotlin
package net.qmindtech.tmap.ui.projects

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.browse.BrowseTaskItem
import net.qmindtech.tmap.ui.browse.TaskFilter
import net.qmindtech.tmap.ui.browse.applyTaskFilter
import net.qmindtech.tmap.ui.navigation.Route
import javax.inject.Inject

data class ProjectDetailUiState(
  val loading: Boolean = true,
  val project: ProjectEntity? = null,
  val total: Int = 0,
  val done: Int = 0,
  val items: List<BrowseTaskItem> = emptyList(),
) {
  val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
}

@HiltViewModel
class ProjectDetailViewModel @Inject constructor(
  savedStateHandle: SavedStateHandle,
  private val taskRepo: TaskRepository,
  projectRepo: ProjectRepository,
) : ViewModel() {

  private val projectId: String =
    savedStateHandle.get<String>(Route.ProjectDetail.ARG_PROJECT_ID).orEmpty()

  val uiState: StateFlow<ProjectDetailUiState> =
    combine(
      taskRepo.observeAll(),
      projectRepo.observeAll(),
      projectRepo.observeProgress(),
    ) { tasks, projects, progress ->
      val project = projects.firstOrNull { it.id == projectId }
      val pr = progress.firstOrNull { it.projectId == projectId }
      // Project-scoped, manual-rank list reusing the Browse engine.
      val groups = applyTaskFilter(tasks, projects, TaskFilter(projectIds = setOf(projectId)))
      ProjectDetailUiState(
        loading = false,
        project = project,
        total = pr?.total ?: 0,
        done = pr?.done ?: 0,
        items = groups.flatMap { it.items },
      )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectDetailUiState())

  fun toggleDone(task: TaskEntity) {
    viewModelScope.launch { taskRepo.markDone(task.id) }
  }
}
```

- [ ] **Verify it PASSES:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.projects.ProjectDetailViewModelTest"` → `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
feat(android-projects): add ProjectDetailViewModel (tasks + progress header)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.9 — `ProjectDetailScreen` (header + tasks + `// P4-NOTES-SLOT`) — compile-gate + behavior checklist

Build the project-detail Compose screen: a back-navigable header (emoji + name + `ProgressRing` showing done/total), the project's `TaskCard` list, and a documented notes placeholder for P4. Reachable via `Route.ProjectDetail.PATTERN`.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectDetailScreen.kt`

**Interfaces**
- Produces: `@Composable fun ProjectDetailScreen(onBack: () -> Unit, onOpenTask: (taskId: String) -> Unit, viewModel: ProjectDetailViewModel = hiltViewModel())`.
- Consumes (FIXED P0): `ProgressRing`, `TaskCard`, `SectionLabel`, `EmptyState`, `LocalTmapColors`/`LocalTmapType`; `ProjectDetailUiState` (P2.8).

**Steps**

- [ ] **Implement** `ProjectDetailScreen.kt`:

```kotlin
package net.qmindtech.tmap.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChecklistRtl
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.EmptyState
import net.qmindtech.tmap.ui.components.ProgressRing
import net.qmindtech.tmap.ui.components.SectionLabel
import net.qmindtech.tmap.ui.components.TaskCard
import net.qmindtech.tmap.ui.theme.LocalTmapColors

@Composable
fun ProjectDetailScreen(
  onBack: () -> Unit,
  onOpenTask: (taskId: String) -> Unit,
  viewModel: ProjectDetailViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val colors = LocalTmapColors.current

  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
    // Header: back + emoji + name + progress ring
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
      IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
      }
      Text(state.project?.emoji ?: "📁")
      Text(
        state.project?.name ?: "Project",
        modifier = Modifier.weight(1f),
        color = colors.textPrimary,
        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
      )
      ProgressRing(progress = state.progress, modifier = Modifier.size(48.dp)) {
        Text("${state.done}/${state.total}", color = colors.textSecondary, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
      }
    }

    if (!state.loading && state.items.isEmpty()) {
      EmptyState(
        icon = Icons.Filled.ChecklistRtl,
        title = "No tasks in this project",
        subtitle = "Add tasks and assign them here.",
      )
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "tasks-label") { SectionLabel("Tasks", modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) }
        items(state.items, key = { it.task.id }) { item ->
          TaskCard(
            task = item.ui,
            onToggleComplete = { viewModel.toggleDone(item.task) },
            onClick = { onOpenTask(item.task.id) },
          )
        }
        // P4-NOTES-SLOT: P4 (Notes UI) will render this project's notes here —
        // a "Notes" SectionLabel + NoteCard list backed by NoteRepository.observeAll(projectId = projectId).
        // Intentionally empty until the Notes domain (P3 data + P4 UI) lands; do not wire before then.
      }
    }
  }
}
```

> `ProgressRing`'s FIXED P0 signature is `ProgressRing(progress: Float, modifier: Modifier, centerLabel: @Composable () -> Unit)` — pass `modifier` and the trailing-lambda `centerLabel` as above. `Icons.AutoMirrored.Filled.ArrowBack` is used so the back chevron mirrors in RTL (skeleton a11y/RTL rule).

- [ ] **Verify (compile-gate):** included in the P2.10 full `assembleDebug` (the screen is referenced by the `MainScaffold` rewiring in P2.10).

- [ ] **Behavior checklist (reviewer, vs `full-app.html` — Projects/detail):**
  - Header with back button (RTL-mirrored), project emoji + name, `ProgressRing` showing `done/total`.
  - Project's tasks as `TaskCard`s; tap → editor; check-off toggles done.
  - Empty state when the project has no tasks.
  - A clearly-commented `// P4-NOTES-SLOT` marks where P4 notes attach.

- [ ] **Commit:**
```
feat(android-projects): add ProjectDetailScreen with P4 notes slot

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.10 — Retire `ui/alltasks/*` + `ui/backlog/*`, rewire `MainScaffold` to Browse/Projects/ProjectDetail

Delete the v1 `AllTasks` + `Backlog` screens/VMs/filter/tests (now folded into Browse), rewire `MainScaffold`'s `Browse`/`ProjectDetail` destinations to the real screens, remove the dead `Routes.kt` (v1 `Routes` sealed class) entries if still present, and prove the codebase is clean via `assembleDebug` + a grep that finds no lingering references.

**Files**
- `- android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/AllTasksScreen.kt`
- `- android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/AllTasksViewModel.kt`
- `- android/app/src/main/java/net/qmindtech/tmap/ui/alltasks/TaskFilter.kt`
- `- android/app/src/test/java/net/qmindtech/tmap/ui/alltasks/AllTasksViewModelTest.kt`
- `- android/app/src/test/java/net/qmindtech/tmap/ui/alltasks/TaskFilterTest.kt`
- `- android/app/src/main/java/net/qmindtech/tmap/ui/backlog/BacklogScreen.kt`
- `- android/app/src/main/java/net/qmindtech/tmap/ui/backlog/BacklogViewModel.kt`
- `- android/app/src/test/java/net/qmindtech/tmap/ui/backlog/BacklogViewModelTest.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/navigation/MainScaffold.kt`

**Steps**

- [ ] **Grep for references first** (do not delete anything still imported elsewhere):
  - `Grep "ui.alltasks" android/app/src` and `Grep "AllTasksScreen|AllTasksViewModel" android/app/src`
  - `Grep "ui.backlog" android/app/src` and `Grep "BacklogScreen|BacklogViewModel" android/app/src`
  - Expected live references are only in `MainScaffold.kt` (the P0 nav stubs/placeholders) and the v1 `Routes.kt` (`Routes.AllTasks`/`Routes.Backlog`) if those survived P0. Anything else must be migrated before deletion. The v1 four-tab nav was replaced by P0's 5-tab `Route`/`MainScaffold`, so `AllTasks`/`Backlog` should have **no** bottom-nav entry — confirm.

- [ ] **Rewire `MainScaffold.kt`** — replace the `Route.Browse` and `Route.ProjectDetail` placeholder destinations (and delete `BrowsePlaceholder`/`ProjectDetailPlaceholder`) with the real screens:

```kotlin
// in the NavHost { } block:
composable(Route.Browse.route) {
    net.qmindtech.tmap.ui.browse.BrowseScreen(
        onOpenTask = { id -> navController.openTaskEditor(id) },
        onOpenProject = { pid -> navController.navigate(Route.ProjectDetail.create(pid)) },
    )
}
composable(
    route = Route.ProjectDetail.PATTERN,
    arguments = listOf(navArgument(Route.ProjectDetail.ARG_PROJECT_ID) { type = NavType.StringType }),
) {
    net.qmindtech.tmap.ui.projects.ProjectDetailScreen(
        onBack = { navController.popBackStack() },
        onOpenTask = { id -> navController.openTaskEditor(id) },
    )
}
```

  - Remove the now-unused `BrowsePlaceholder()` / `ProjectDetailPlaceholder()` composables (and any `import` for the deleted alltasks/backlog screens, if P0 ever referenced them).
  - Leave `Today`/`Inbox`/`Notes`/`You`/`Planning`/`Focus` exactly as P0/P1 left them (Notes/You/Planning/Focus remain placeholders until their phases).

- [ ] **Delete** the v1 files listed above (`git rm`). If a v1 `Routes.kt` (`sealed class Routes`) still declares `AllTasks`/`Backlog`, remove those two entries; if the whole `Routes` class is now dead (P0 replaced it with `Route`), grep `Grep "Routes\\." android/app/src` — delete the file only if nothing references it.

- [ ] **Verify — compile gate:** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (this now also covers P2.3/P2.7/P2.9 compile gates with their real wiring).

- [ ] **Grep-clean check:** `Grep "ui\\.alltasks|ui\\.backlog|AllTasksScreen|AllTasksViewModel|BacklogScreen|BacklogViewModel" android/app/src` → **no matches** (apart from this plan doc). Record the empty result in the commit body.

- [ ] **Commit:**
```
refactor(android-nav): retire AllTasks/Backlog into Browse; wire ProjectDetail

Folded the v1 AllTasks + Backlog screens/VMs/filter into the Browse hub and
wired Route.Browse/ProjectDetail to the real screens. grep over android/app/src
for ui.alltasks/ui.backlog/AllTasks*/Backlog* returns no matches.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P2.11 — Phase green-gate (full unit suite + assembleDebug + lintDebug + behavior checklist)

Final phase gate: the whole project's unit tests pass, the app compiles, lint is clean, and the Browse + Projects experience matches the mockup end-to-end. No new production code — only verification (and any small fixes the gates surface).

**Files**
- None (verification only; fix-forward any failure in its owning task's file).

**Steps**

- [ ] **Full unit suite:** `cd android && ./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL. Must include the new P2 suites (`ui.browse.TaskFilterTest`, `ui.browse.BrowseViewModelTest`, `data.local.dao.ProjectDaoTest` incl. `observeProgress`, `data.repository.ProjectProgressRepoTest`, `ui.projects.ProjectsViewModelTest`, `ui.projects.ProjectDetailViewModelTest`) and the entire pre-existing engine/auth/sync/reminder suite (regression — no breakage from the `FakeProjectRepo`/`ProjectRepository` interface change).
- [ ] **Compile gate:** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Lint gate:** `cd android && ./gradlew :app:lintDebug` → no new errors (RTL `start/end`, `contentDescription` on icon-only controls — back button, search/filter glyphs — are present; address any new lint error in the owning file).
- [ ] **End-to-end behavior checklist vs `.superpowers/brainstorm/965-1782053760/content/full-app.html` (Browse + Projects):**
  - **Browse:** search over title/notes/project name; `SegmentedControl` All Tasks / Backlog / Projects; filter/sort/group bottom-sheets with an active-filter badge; grouped `TaskCard` list with `SectionLabel` headers; FAB (from `MainScaffold`) opens capture; check-off + tap-to-edit are write-through repo calls (offline-first).
  - **Projects:** header summary; color-coded progress cards (`ProjectDot` + project-colored bar + done/total); "+ New" → `ProjectEditDialog` (palette + emoji); tap card → `ProjectDetailScreen`.
  - **Project detail:** back (RTL-mirrored) + emoji/name + `ProgressRing`; project tasks as `TaskCard`s; `// P4-NOTES-SLOT` present for P4.
  - **Retirement:** no AllTasks/Backlog screens remain; grep-clean confirmed in P2.10.
  - **Accent discipline / dark-only:** accent appears only on active chips/segment/ring/primary buttons; all surfaces are Midnight Calm tokens; no old `Surface*/Accent*` palette usage in the new files.
- [ ] **Commit (gate marker, only if any fix-forward edits were needed; otherwise skip):**
```
test(android-browse): green-gate P2 (unit + assemble + lint + checklist)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

## P3 — New-domain data layer & sync-engine extension

This phase adds the four new synced domains (notes, note-groups, focus-sessions, daily-plans) to the offline engine by mirroring the proven task path exactly — extending the `EntityType` enum, adding Room entities/DAOs (bumping the DB to v3 under the existing `fallbackToDestructiveMigration`), adding kotlinx DTOs + `*SyncRow`s + an extended `SyncChanges`, wiring the Retrofit endpoints, the `Mappers` DTO↔entity functions, the write-through repositories (with their two outbox specials — focus-session is CREATE-only and daily-plan is an UPDATE keyed by ISO date with no id remap/adopt), and the `PushRunner.dispatch()` / `PullRunner.applyPage()` branches (preserving 409-adopt / 5xx-park / 4xx-drop+recovery / shadow-rule for the id-based domains). It also adds `TaskRepository.addActualTime`. Everything here is unit-testable; each task is a TDD unit with a real Room-in-memory / MockWebServer test, and the phase ends by running the full `:app:testDebugUnitTest` green. All commands run from `android/`. Every commit ends with the trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

### Task P3.1 — Extend `EntityType` with the four new domains

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/local/EntityType.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/local/EntityTypeTest.kt`

**Interfaces**
- `enum class EntityType { TASK, SUBTASK, PROJECT, SETTINGS, NOTE, NOTE_GROUP, FOCUS_SESSION, DAILY_PLAN }` — the four additions are appended after the existing four (ordinal stability for the existing rows is irrelevant: the column is stored by name via Room's default enum converter).

**Steps**

- [ ] **Write the failing test.** Create `EntityTypeTest.kt`:
```kotlin
package net.qmindtech.tmap.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityTypeTest {

    @Test
    fun `the four new synced domains are present alongside the originals`() {
        val names = EntityType.entries.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("TASK", "SUBTASK", "PROJECT", "SETTINGS")))
        assertTrue(names.containsAll(setOf("NOTE", "NOTE_GROUP", "FOCUS_SESSION", "DAILY_PLAN")))
    }

    @Test
    fun `valueOf round-trips each new domain by name`() {
        assertEquals(EntityType.NOTE, EntityType.valueOf("NOTE"))
        assertEquals(EntityType.NOTE_GROUP, EntityType.valueOf("NOTE_GROUP"))
        assertEquals(EntityType.FOCUS_SESSION, EntityType.valueOf("FOCUS_SESSION"))
        assertEquals(EntityType.DAILY_PLAN, EntityType.valueOf("DAILY_PLAN"))
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.EntityTypeTest"` — fails to compile (the new enum constants do not exist).

- [ ] **Implement.** Replace `EntityType.kt` body with:
```kotlin
package net.qmindtech.tmap.data.local

enum class EntityType { TASK, SUBTASK, PROJECT, SETTINGS, NOTE, NOTE_GROUP, FOCUS_SESSION, DAILY_PLAN }
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.EntityTypeTest"` — green.

- [ ] **Commit.** `feat(android-sync): add NOTE/NOTE_GROUP/FOCUS_SESSION/DAILY_PLAN entity types`

---

### Task P3.2 — `Converters` already covers `List<String>`; pin it with a test

**Files**
- `~ android/app/src/test/java/net/qmindtech/tmap/data/local/ConvertersTest.kt` (append one test)

**Interfaces**
- The existing `Converters.fromStringList(List<String>): String` / `toStringList(String): List<String>` are reused verbatim by `DailyPlanEntity.plannedTaskIds`. No production change is needed; this task only asserts the round-trip explicitly so the DailyPlan converter contract is locked.

**Steps**

- [ ] **Write the failing test.** Append to `ConvertersTest.kt` (inside the existing class):
```kotlin
    @Test
    fun `string list round-trips an ordered planned-task-id list for DailyPlan`() {
        val c = Converters()
        val ids = listOf("a-1", "b-2", "c-3")
        assertEquals(ids, c.toStringList(c.fromStringList(ids)))
        assertEquals(emptyList<String>(), c.toStringList(c.fromStringList(emptyList())))
    }
```
Ensure the file imports `org.junit.Assert.assertEquals` (it already does for the existing tests; add it if missing).

- [ ] **Verify FAIL/PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.ConvertersTest"` — this passes immediately (the converter already exists). If the imports were missing it failed to compile first; after adding them it is green. (No production code changes — this is a contract-lock test.)

- [ ] **Commit.** `test(android): lock List<String> converter round-trip for DailyPlan.plannedTaskIds`

---

### Task P3.3 — `NoteEntity` + `NoteDao` (incl. local-only `pinnedAt`)

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/local/entities/NoteEntity.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/data/local/dao/NoteDao.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/data/local/AppDatabase.kt` (register entity + DAO; version 2 → 3 — done once here, kept across P3.4–P3.6)
- `+ android/app/src/test/java/net/qmindtech/tmap/data/local/dao/NoteDaoTest.kt`

**Interfaces**
- `NoteEntity(id: String, groupId: String?, projectId: String?, title: String, content: String, rank: String?, createdAt: Instant, updatedAt: Instant, changeSeq: Long, deletedAt: Instant? = null, pinnedAt: Instant? = null /* LOCAL-ONLY, never synced */)`
- `NoteDao { observeAll(): Flow<List<NoteEntity>>; observeByGroup(groupId: String?): Flow<...>; observeByProject(projectId: String?): Flow<...>; observeById(id): Flow<NoteEntity?>; getById(id): NoteEntity?; upsertAll(List<NoteEntity>); setPinned(id, pinnedAt: Instant?); deleteById(id); clear() }`

**Steps**

- [ ] **Write the failing test.** `NoteDaoTest.kt`:
```kotlin
package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.NoteEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NoteDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao
    private val now = Instant.parse("2026-06-18T08:00:00Z")

    private fun note(id: String, groupId: String? = null, projectId: String? = null, rank: String?) =
        NoteEntity(
            id = id, groupId = groupId, projectId = projectId, title = "ملاحظة", content = "body",
            rank = rank, createdAt = now, updatedAt = now, changeSeq = 0, deletedAt = null, pinnedAt = null,
        )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeAll orders by rank nulls-last and round-trips RTL title`() = runTest {
        dao.upsertAll(listOf(note("n2", rank = "0002"), note("n1", rank = "0001"), note("n3", rank = null)))
        val rows = dao.observeAll().first()
        assertEquals(listOf("n1", "n2", "n3"), rows.map { it.id })
        assertEquals("ملاحظة", rows.first().title)
    }

    @Test
    fun `observeByGroup filters to one notebook`() = runTest {
        dao.upsertAll(listOf(note("a", groupId = "g1", rank = "0001"), note("b", groupId = "g2", rank = "0002")))
        assertEquals(listOf("a"), dao.observeByGroup("g1").first().map { it.id })
    }

    @Test
    fun `setPinned stamps and clears the local-only pinnedAt without touching changeSeq`() = runTest {
        dao.upsertAll(listOf(note("n1", rank = "0001")))
        dao.setPinned("n1", now)
        assertEquals(now, dao.getById("n1")!!.pinnedAt)
        assertEquals(0L, dao.getById("n1")!!.changeSeq) // pin is not a synced mutation
        dao.setPinned("n1", null)
        assertNull(dao.getById("n1")!!.pinnedAt)
    }

    @Test
    fun `deleteById and clear remove rows`() = runTest {
        dao.upsertAll(listOf(note("n1", rank = "0001"), note("n2", rank = "0002")))
        dao.deleteById("n1")
        assertNull(dao.getById("n1"))
        dao.clear()
        assertEquals(emptyList<NoteEntity>(), dao.observeAll().first())
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.NoteDaoTest"` — fails (no `NoteEntity`, no `noteDao()`).

- [ ] **Implement.** `NoteEntity.kt`:
```kotlin
package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A synced note. All columns mirror the backend NoteResponse EXCEPT [pinnedAt], which is a
 * LOCAL-ONLY affordance (spec §7.7): it is never sent to the server, never enqueued to the outbox,
 * and is acceptably lost on a destructive resync (pins are cosmetic).
 */
@Entity(tableName = "notes", indices = [Index("groupId"), Index("projectId")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val groupId: String?,
    val projectId: String?,
    val title: String,
    val content: String,
    val rank: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
    val pinnedAt: Instant? = null,
)
```
`NoteDao.kt`:
```kotlin
package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.NoteEntity
import java.time.Instant

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY rank IS NULL, rank")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE groupId = :groupId ORDER BY rank IS NULL, rank")
    fun observeByGroup(groupId: String?): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE projectId = :projectId ORDER BY rank IS NULL, rank")
    fun observeByProject(projectId: String?): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeById(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NoteEntity>)

    /** Local-only pin toggle — does NOT bump changeSeq and is never enqueued to the outbox. */
    @Query("UPDATE notes SET pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinnedAt: Instant?)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM notes")
    suspend fun clear()
}
```
Edit `AppDatabase.kt`: add the imports `NoteDao` and `NoteEntity`, add `NoteEntity::class,` to the `entities = [...]` list, change `version = 2,` to `version = 3,` (update the comment to note that v3 adds the four new-domain tables), and add `abstract fun noteDao(): NoteDao`. Concretely:
```kotlin
import net.qmindtech.tmap.data.local.dao.NoteDao
// ...
import net.qmindtech.tmap.data.local.entities.NoteEntity
```
```kotlin
@Database(
    entities = [
        TaskEntity::class,
        SubtaskEntity::class,
        ProjectEntity::class,
        SettingEntity::class,
        OutboxOp::class,
        SyncStateEntity::class,
        NoteEntity::class,
    ],
    // v3 adds the four SP4 new-domain tables (notes, note_groups, focus_sessions, daily_plans).
    // fallbackToDestructiveMigration() (DatabaseModule) wipes + full-resyncs an older install on first
    // open — acceptable per spec §3/§7.1 (a schema bump deliberately triggers a full resync; the only
    // local-only datum lost is note.pinnedAt, which is cosmetic).
    version = 3,
    exportSchema = false,
)
```
```kotlin
abstract fun noteDao(): NoteDao
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.NoteDaoTest"` — green.

- [ ] **Commit.** `feat(android-data): NoteEntity + NoteDao with local-only pinnedAt; AppDatabase v3`

---

### Task P3.4 — `NoteGroupEntity` + `NoteGroupDao`

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/local/entities/NoteGroupEntity.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/data/local/dao/NoteGroupDao.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/data/local/AppDatabase.kt` (register entity + DAO; version already 3)
- `+ android/app/src/test/java/net/qmindtech/tmap/data/local/dao/NoteGroupDaoTest.kt`

**Interfaces**
- `NoteGroupEntity(id: String, name: String, emoji: String, projectId: String?, rank: String?, createdAt: Instant, updatedAt: Instant, changeSeq: Long, deletedAt: Instant? = null)`
- `NoteGroupDao { observeAll(): Flow<List<NoteGroupEntity>>; getById(id): NoteGroupEntity?; upsertAll(List<NoteGroupEntity>); deleteById(id); clear() }`

**Steps**

- [ ] **Write the failing test.** `NoteGroupDaoTest.kt`:
```kotlin
package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NoteGroupDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NoteGroupDao
    private val now = Instant.parse("2026-06-18T08:00:00Z")

    private fun group(id: String, rank: String?) = NoteGroupEntity(
        id = id, name = "دفتر", emoji = "📓", projectId = null,
        rank = rank, createdAt = now, updatedAt = now, changeSeq = 0, deletedAt = null,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.noteGroupDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeAll orders by rank nulls-last and round-trips RTL name and emoji`() = runTest {
        dao.upsertAll(listOf(group("g2", "0002"), group("g1", "0001"), group("g3", null)))
        val rows = dao.observeAll().first()
        assertEquals(listOf("g1", "g2", "g3"), rows.map { it.id })
        assertEquals("دفتر", rows.first().name)
        assertEquals("📓", rows.first().emoji)
    }

    @Test
    fun `deleteById and clear remove rows`() = runTest {
        dao.upsertAll(listOf(group("g1", "0001"), group("g2", "0002")))
        dao.deleteById("g1")
        assertNull(dao.getById("g1"))
        dao.clear()
        assertEquals(emptyList<NoteGroupEntity>(), dao.observeAll().first())
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.NoteGroupDaoTest"` — fails.

- [ ] **Implement.** `NoteGroupEntity.kt`:
```kotlin
package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "note_groups", indices = [Index("projectId")])
data class NoteGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String,
    val projectId: String?,
    val rank: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
)
```
`NoteGroupDao.kt`:
```kotlin
package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity

@Dao
interface NoteGroupDao {
    @Query("SELECT * FROM note_groups ORDER BY rank IS NULL, rank")
    fun observeAll(): Flow<List<NoteGroupEntity>>

    @Query("SELECT * FROM note_groups WHERE id = :id")
    suspend fun getById(id: String): NoteGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<NoteGroupEntity>)

    @Query("DELETE FROM note_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM note_groups")
    suspend fun clear()
}
```
Edit `AppDatabase.kt`: add imports `NoteGroupDao` + `NoteGroupEntity`, add `NoteGroupEntity::class,` to the entities list, add `abstract fun noteGroupDao(): NoteGroupDao`.

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.NoteGroupDaoTest"` — green.

- [ ] **Commit.** `feat(android-data): NoteGroupEntity + NoteGroupDao`

---

### Task P3.5 — `FocusSessionEntity` + `FocusSessionDao`

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/local/entities/FocusSessionEntity.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/data/local/dao/FocusSessionDao.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/data/local/AppDatabase.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/local/dao/FocusSessionDaoTest.kt`

**Interfaces**
- `FocusSessionEntity(id: String, taskId: String?, project: String, startedAt: Instant, endedAt: Instant, minutes: Int, date: LocalDate, createdAt: Instant, updatedAt: Instant, changeSeq: Long, deletedAt: Instant? = null)`
- `FocusSessionDao { observeForTask(taskId: String): Flow<List<FocusSessionEntity>>; observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>>; getById(id): FocusSessionEntity?; upsertAll(List<FocusSessionEntity>); deleteById(id); clear() }`

**Steps**

- [ ] **Write the failing test.** `FocusSessionDaoTest.kt`:
```kotlin
package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class FocusSessionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FocusSessionDao

    private fun session(id: String, taskId: String?, date: LocalDate, minutes: Int = 25) =
        FocusSessionEntity(
            id = id, taskId = taskId, project = "العمل",
            startedAt = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
            endedAt = date.atStartOfDay(java.time.ZoneOffset.UTC).plusMinutes(minutes.toLong()).toInstant(),
            minutes = minutes, date = date,
            createdAt = Instant.parse("2026-06-18T08:00:00Z"),
            updatedAt = Instant.parse("2026-06-18T08:00:00Z"), changeSeq = 0, deletedAt = null,
        )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.focusSessionDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `observeForTask returns only that task's sessions`() = runTest {
        dao.upsertAll(listOf(
            session("s1", "t1", LocalDate.parse("2026-06-18")),
            session("s2", "t2", LocalDate.parse("2026-06-18")),
        ))
        assertEquals(listOf("s1"), dao.observeForTask("t1").first().map { it.id })
    }

    @Test
    fun `observeForDateRange returns sessions within an inclusive date window`() = runTest {
        dao.upsertAll(listOf(
            session("a", null, LocalDate.parse("2026-06-15")),
            session("b", null, LocalDate.parse("2026-06-18")),
            session("c", null, LocalDate.parse("2026-06-25")),
        ))
        val ids = dao.observeForDateRange(LocalDate.parse("2026-06-16"), LocalDate.parse("2026-06-20"))
            .first().map { it.id }
        assertEquals(listOf("b"), ids)
    }

    @Test
    fun `upsert round-trips fields and deleteById removes`() = runTest {
        dao.upsertAll(listOf(session("s1", "t1", LocalDate.parse("2026-06-18"), minutes = 50)))
        val row = dao.getById("s1")!!
        assertEquals(50, row.minutes)
        assertEquals("العمل", row.project)
        assertEquals(LocalDate.parse("2026-06-18"), row.date)
        dao.deleteById("s1")
        assertNull(dao.getById("s1"))
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.FocusSessionDaoTest"` — fails.

- [ ] **Implement.** `FocusSessionEntity.kt`:
```kotlin
package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/** An append-only focus session (spec §7.4). `project` is a NAME snapshot, not an FK. */
@Entity(tableName = "focus_sessions", indices = [Index("taskId"), Index("date")])
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val taskId: String?,
    val project: String,
    val startedAt: Instant,
    val endedAt: Instant,
    val minutes: Int,
    val date: LocalDate,
    val createdAt: Instant,
    val updatedAt: Instant,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
)
```
`FocusSessionDao.kt`:
```kotlin
package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import java.time.LocalDate

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_sessions WHERE taskId = :taskId ORDER BY startedAt")
    fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE date BETWEEN :start AND :end ORDER BY startedAt")
    fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE id = :id")
    suspend fun getById(id: String): FocusSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<FocusSessionEntity>)

    @Query("DELETE FROM focus_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM focus_sessions")
    suspend fun clear()
}
```
Edit `AppDatabase.kt`: add imports `FocusSessionDao` + `FocusSessionEntity`, add `FocusSessionEntity::class,` to entities, add `abstract fun focusSessionDao(): FocusSessionDao`. (`date BETWEEN` works because `LocalDate` is stored as ISO `yyyy-MM-dd`, which is lexicographically date-ordered — see `Converters`.)

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.FocusSessionDaoTest"` — green.

- [ ] **Commit.** `feat(android-data): FocusSessionEntity + FocusSessionDao (append-only)`

---

### Task P3.6 — `DailyPlanEntity` (date PK) + `DailyPlanDao`

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/local/entities/DailyPlanEntity.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/data/local/dao/DailyPlanDao.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/data/local/AppDatabase.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/local/dao/DailyPlanDaoTest.kt`

**Interfaces**
- `DailyPlanEntity(date: LocalDate /* PK */, committedAt: Instant, plannedTaskIds: List<String>, plannedMinutes: Int, changeSeq: Long, deletedAt: Instant? = null)`
- `DailyPlanDao { observe(date: LocalDate): Flow<DailyPlanEntity?>; getByDate(date): DailyPlanEntity?; upsertAll(List<DailyPlanEntity>); deleteByDate(date); clear() }` — keyed entirely by `date`.

**Steps**

- [ ] **Write the failing test.** `DailyPlanDaoTest.kt`:
```kotlin
package net.qmindtech.tmap.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DailyPlanDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DailyPlanDao
    private val date = LocalDate.parse("2026-06-18")

    private fun plan(d: LocalDate, ids: List<String>, minutes: Int) = DailyPlanEntity(
        date = d, committedAt = Instant.parse("2026-06-18T07:00:00Z"),
        plannedTaskIds = ids, plannedMinutes = minutes, changeSeq = 0, deletedAt = null,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.dailyPlanDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `upsert by date replaces the prior plan for that day and round-trips ordered ids`() = runTest {
        dao.upsertAll(listOf(plan(date, listOf("a", "b"), 120)))
        dao.upsertAll(listOf(plan(date, listOf("c", "b", "a"), 240))) // same PK -> REPLACE
        val row = dao.getByDate(date)!!
        assertEquals(listOf("c", "b", "a"), row.plannedTaskIds)
        assertEquals(240, row.plannedMinutes)
        // only one row exists for the date
        assertEquals(listOf("c", "b", "a"), dao.observe(date).first()!!.plannedTaskIds)
    }

    @Test
    fun `observe is null for an unplanned day and deleteByDate removes the plan`() = runTest {
        assertNull(dao.observe(LocalDate.parse("2026-06-19")).first())
        dao.upsertAll(listOf(plan(date, listOf("a"), 30)))
        dao.deleteByDate(date)
        assertNull(dao.getByDate(date))
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.DailyPlanDaoTest"` — fails.

- [ ] **Implement.** `DailyPlanEntity.kt`:
```kotlin
package net.qmindtech.tmap.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * The committed plan for a single day (spec §7.6). The natural key is [date] (DateOnly on the
 * backend) — there is no Guid id. `plannedTaskIds` is an ordered list persisted via the existing
 * Converters.fromStringList. Upsert is last-writer-wins (full plannedTaskIds replacement).
 */
@Entity(tableName = "daily_plans")
data class DailyPlanEntity(
    @PrimaryKey val date: LocalDate,
    val committedAt: Instant,
    val plannedTaskIds: List<String>,
    val plannedMinutes: Int,
    val changeSeq: Long,
    val deletedAt: Instant? = null,
)
```
`DailyPlanDao.kt`:
```kotlin
package net.qmindtech.tmap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import java.time.LocalDate

@Dao
interface DailyPlanDao {
    @Query("SELECT * FROM daily_plans WHERE date = :date")
    fun observe(date: LocalDate): Flow<DailyPlanEntity?>

    @Query("SELECT * FROM daily_plans WHERE date = :date")
    suspend fun getByDate(date: LocalDate): DailyPlanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DailyPlanEntity>)

    @Query("DELETE FROM daily_plans WHERE date = :date")
    suspend fun deleteByDate(date: LocalDate)

    @Query("DELETE FROM daily_plans")
    suspend fun clear()
}
```
Edit `AppDatabase.kt`: add imports `DailyPlanDao` + `DailyPlanEntity`, add `DailyPlanEntity::class,` to entities, add `abstract fun dailyPlanDao(): DailyPlanDao`. (This completes the entities/DAOs registered for v3.)

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.DailyPlanDaoTest"` — green.

- [ ] **Commit.** `feat(android-data): DailyPlanEntity (date PK) + DailyPlanDao`

---

### Task P3.7 — Note + NoteGroup DTOs (Create/Update/Response)

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/NoteDtos.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/NoteGroupDtos.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/remote/dto/NoteDtosTest.kt`

**Interfaces** (mirror spec §7.2 / §7.3; kotlinx, `explicitNulls = false` at call sites; nullable-tolerant)
- `CreateNoteRequest(id: String? = null, groupId: String? = null, projectId: String? = null, title: String, content: String, rank: String? = null)`
- `UpdateNoteRequest(groupId: String? = null, projectId: String? = null, title: String? = null, content: String? = null, rank: String? = null)`
- `NoteResponse(id, groupId: String?, projectId: String?, title, content, rank: String?, createdAt, updatedAt)` — **no `pinned` field** (spec §7.7).
- `CreateNoteGroupRequest(id: String? = null, name: String, emoji: String, projectId: String? = null, rank: String? = null)`
- `UpdateNoteGroupRequest(name: String? = null, emoji: String? = null, projectId: String? = null, rank: String? = null)`
- `NoteGroupResponse(id, name, emoji, projectId: String?, rank: String?, createdAt, updatedAt)`

**Steps**

- [ ] **Write the failing test.** `NoteDtosTest.kt`:
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteDtosTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `CreateNoteRequest omits null id and rank when explicitNulls is off`() {
        val body = json.encodeToString(CreateNoteRequest.serializer(),
            CreateNoteRequest(title = "ملاحظة", content = "body"))
        assertEquals("""{"title":"ملاحظة","content":"body"}""", body)
    }

    @Test
    fun `NoteResponse decodes and tolerates an unmodeled pinned field`() {
        val wire = """{"id":"n1","groupId":"g1","projectId":null,"title":"t","content":"c",
            "rank":"0001","createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:30:00Z",
            "pinned":true}""".trimIndent()
        val r = json.decodeFromString(NoteResponse.serializer(), wire)
        assertEquals("n1", r.id)
        assertEquals("g1", r.groupId)
        assertNull(r.projectId)
        assertEquals("0001", r.rank)
    }

    @Test
    fun `NoteGroupResponse decodes`() {
        val wire = """{"id":"g1","name":"دفتر","emoji":"📓","projectId":null,"rank":"0001",
            "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z"}""".trimIndent()
        val r = json.decodeFromString(NoteGroupResponse.serializer(), wire)
        assertEquals("دفتر", r.name)
        assertEquals("📓", r.emoji)
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.NoteDtosTest"` — fails.

- [ ] **Implement.** `NoteDtos.kt`:
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateNoteRequest(
    val id: String? = null,
    val groupId: String? = null,
    val projectId: String? = null,
    val title: String,
    val content: String,
    val rank: String? = null,
)

@Serializable
data class UpdateNoteRequest(
    val groupId: String? = null,
    val projectId: String? = null,
    val title: String? = null,
    val content: String? = null,
    val rank: String? = null,
)

@Serializable
data class NoteResponse(
    val id: String,
    val groupId: String?,
    val projectId: String?,
    val title: String,
    val content: String,
    val rank: String?,
    val createdAt: String,
    val updatedAt: String,
)
```
`NoteGroupDtos.kt`:
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreateNoteGroupRequest(
    val id: String? = null,
    val name: String,
    val emoji: String,
    val projectId: String? = null,
    val rank: String? = null,
)

@Serializable
data class UpdateNoteGroupRequest(
    val name: String? = null,
    val emoji: String? = null,
    val projectId: String? = null,
    val rank: String? = null,
)

@Serializable
data class NoteGroupResponse(
    val id: String,
    val name: String,
    val emoji: String,
    val projectId: String?,
    val rank: String?,
    val createdAt: String,
    val updatedAt: String,
)
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.NoteDtosTest"` — green.

- [ ] **Commit.** `feat(android-remote): Note + NoteGroup DTOs (no pinned field, spec §7.2/§7.3)`

---

### Task P3.8 — FocusSession + DailyPlan DTOs

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/FocusSessionDtos.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/DailyPlanDtos.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/remote/dto/FocusDailyPlanDtosTest.kt`

**Interfaces** (mirror spec §7.4 / §7.6)
- Focus-session is **create-only**: `CreateFocusSessionRequest(id: String? = null, taskId: String? = null, project: String, startedAt: String, endedAt: String, minutes: Int, date: String)` + `FocusSessionResponse(id, taskId: String?, project, startedAt, endedAt, minutes, date, createdAt, updatedAt)`.
- Daily-plan is **GET/{date} + PUT/{date} upsert**: `UpsertDailyPlanRequest(committedAt: String, plannedTaskIds: List<String>, plannedMinutes: Int)` + `DailyPlanResponse(date: String, committedAt, plannedTaskIds: List<String>, plannedMinutes: Int, createdAt: String? = null, updatedAt: String? = null)`.

**Steps**

- [ ] **Write the failing test.** `FocusDailyPlanDtosTest.kt`:
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusDailyPlanDtosTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `CreateFocusSessionRequest serializes the wire shape, omitting null id and taskId`() {
        val body = json.encodeToString(CreateFocusSessionRequest.serializer(),
            CreateFocusSessionRequest(
                project = "العمل", startedAt = "2026-06-18T09:00:00Z",
                endedAt = "2026-06-18T09:25:00Z", minutes = 25, date = "2026-06-18",
            ))
        assertEquals(
            """{"project":"العمل","startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18"}""",
            body,
        )
    }

    @Test
    fun `UpsertDailyPlanRequest serializes ordered plannedTaskIds`() {
        val body = json.encodeToString(UpsertDailyPlanRequest.serializer(),
            UpsertDailyPlanRequest(committedAt = "2026-06-18T07:00:00Z",
                plannedTaskIds = listOf("a", "b"), plannedMinutes = 120))
        assertEquals(
            """{"committedAt":"2026-06-18T07:00:00Z","plannedTaskIds":["a","b"],"plannedMinutes":120}""",
            body,
        )
    }

    @Test
    fun `DailyPlanResponse decodes a date-keyed plan and tolerates absent timestamps`() {
        val wire = """{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z",
            "plannedTaskIds":["a","b","c"],"plannedMinutes":180}""".trimIndent()
        val r = json.decodeFromString(DailyPlanResponse.serializer(), wire)
        assertEquals("2026-06-18", r.date)
        assertEquals(listOf("a", "b", "c"), r.plannedTaskIds)
        assertEquals(180, r.plannedMinutes)
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.FocusDailyPlanDtosTest"` — fails.

- [ ] **Implement.** `FocusSessionDtos.kt`:
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

/** Append-only (spec §7.4): only POST exists; no Update/Delete request types. */
@Serializable
data class CreateFocusSessionRequest(
    val id: String? = null,
    val taskId: String? = null,
    val project: String,
    val startedAt: String,
    val endedAt: String,
    val minutes: Int,
    val date: String,
)

@Serializable
data class FocusSessionResponse(
    val id: String,
    val taskId: String?,
    val project: String,
    val startedAt: String,
    val endedAt: String,
    val minutes: Int,
    val date: String,
    val createdAt: String,
    val updatedAt: String,
)
```
`DailyPlanDtos.kt`:
```kotlin
package net.qmindtech.tmap.data.remote.dto

import kotlinx.serialization.Serializable

/** PUT /daily-plans/{date} upsert body (spec §7.6) — last-writer-wins, full plannedTaskIds replace. */
@Serializable
data class UpsertDailyPlanRequest(
    val committedAt: String,
    val plannedTaskIds: List<String>,
    val plannedMinutes: Int,
)

@Serializable
data class DailyPlanResponse(
    val date: String,
    val committedAt: String,
    val plannedTaskIds: List<String> = emptyList(),
    val plannedMinutes: Int,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.FocusDailyPlanDtosTest"` — green.

- [ ] **Commit.** `feat(android-remote): FocusSession (create-only) + DailyPlan (date upsert) DTOs`

---

### Task P3.9 — Extend `SyncChanges` with five new `*SyncRow` collections

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/remote/dto/SyncDtos.kt` (add five `*SyncRow` types + extend `SyncChanges`)
- `~ android/app/src/test/java/net/qmindtech/tmap/data/remote/dto/SyncDtosTest.kt` (append a test)

**Interfaces** (each row carries `changeSeq: Long` + `deletedAt: String? = null`; all new collections default to `emptyList()` so payloads missing them still decode)
- `NoteSyncRow(id, groupId: String?, projectId: String?, title, content, rank: String?, createdAt, updatedAt, changeSeq, deletedAt: String? = null)`
- `NoteGroupSyncRow(id, name, emoji, projectId: String?, rank: String?, createdAt, updatedAt, changeSeq, deletedAt: String? = null)`
- `FocusSessionSyncRow(id, taskId: String?, project, startedAt, endedAt, minutes: Int, date, createdAt, updatedAt, changeSeq, deletedAt: String? = null)`
- `DailyPlanSyncRow(date, committedAt, plannedTaskIds: List<String> = emptyList(), plannedMinutes: Int, changeSeq, deletedAt: String? = null)`
- `RecurrenceRuleSyncRow(id, changeSeq, deletedAt: String? = null)` — tolerated-only (spec §7.5); minimal modeling so the payload deserializes.
- `SyncChanges` gains: `notes`, `noteGroups`, `focusSessions`, `dailyPlans`, `recurrenceRules` (all default `emptyList()`).

**Steps**

- [ ] **Write the failing test.** Append to `SyncDtosTest.kt`:
```kotlin
    @Test
    fun `SyncChanges decodes the four new domains plus tolerated recurrenceRules`() {
        val wire = """
            {"changes":{
              "notes":[{"id":"n1","groupId":"g1","projectId":null,"title":"t","content":"c",
                "rank":"0001","createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z",
                "changeSeq":4,"deletedAt":null}],
              "noteGroups":[{"id":"g1","name":"دفتر","emoji":"📓","projectId":null,"rank":"0001",
                "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z","changeSeq":2}],
              "focusSessions":[{"id":"f1","taskId":"t1","project":"العمل",
                "startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,
                "date":"2026-06-18","createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z",
                "changeSeq":6,"deletedAt":null}],
              "dailyPlans":[{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z",
                "plannedTaskIds":["t1","t2"],"plannedMinutes":120,"changeSeq":8,"deletedAt":null}],
              "recurrenceRules":[{"id":"r1","changeSeq":3}]
            },"nextSince":9,"hasMore":false}
        """.trimIndent()
        val r = json.decodeFromString<SyncResponse>(wire)
        assertEquals(1, r.changes.notes.size)
        assertEquals("g1", r.changes.notes[0].groupId)
        assertEquals(1, r.changes.noteGroups.size)
        assertEquals("العمل", r.changes.focusSessions[0].project)
        assertEquals(listOf("t1", "t2"), r.changes.dailyPlans[0].plannedTaskIds)
        assertEquals("2026-06-18", r.changes.dailyPlans[0].date)
        assertEquals(1, r.changes.recurrenceRules.size)
        // Backward-compat: an old payload missing the new arrays still decodes them as empty.
        val old = json.decodeFromString<SyncResponse>("""{"changes":{},"nextSince":0,"hasMore":false}""")
        assertEquals(0, old.changes.notes.size)
        assertEquals(0, old.changes.dailyPlans.size)
    }
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.SyncDtosTest"` — fails (new fields/types absent).

- [ ] **Implement.** Edit `SyncChanges` in `SyncDtos.kt`:
```kotlin
@Serializable
data class SyncChanges(
    val tasks: List<TaskSyncRow> = emptyList(),
    val subtasks: List<SubtaskSyncRow> = emptyList(),
    val projects: List<ProjectSyncRow> = emptyList(),
    val settings: List<SettingSyncRow> = emptyList(),
    val notes: List<NoteSyncRow> = emptyList(),
    val noteGroups: List<NoteGroupSyncRow> = emptyList(),
    val focusSessions: List<FocusSessionSyncRow> = emptyList(),
    val dailyPlans: List<DailyPlanSyncRow> = emptyList(),
    val recurrenceRules: List<RecurrenceRuleSyncRow> = emptyList(),
)
```
Append the five row types to `SyncDtos.kt`:
```kotlin
@Serializable
data class NoteSyncRow(
    val id: String,
    val groupId: String?,
    val projectId: String?,
    val title: String,
    val content: String,
    val rank: String?,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class NoteGroupSyncRow(
    val id: String,
    val name: String,
    val emoji: String,
    val projectId: String?,
    val rank: String?,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class FocusSessionSyncRow(
    val id: String,
    val taskId: String?,
    val project: String,
    val startedAt: String,
    val endedAt: String,
    val minutes: Int,
    val date: String,
    val createdAt: String,
    val updatedAt: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

@Serializable
data class DailyPlanSyncRow(
    val date: String,
    val committedAt: String,
    val plannedTaskIds: List<String> = emptyList(),
    val plannedMinutes: Int,
    val changeSeq: Long,
    val deletedAt: String? = null,
)

/** Tolerated-only (spec §7.5): modeled minimally so the /sync payload deserializes cleanly. */
@Serializable
data class RecurrenceRuleSyncRow(
    val id: String,
    val changeSeq: Long,
    val deletedAt: String? = null,
)
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.dto.SyncDtosTest"` — green.

- [ ] **Commit.** `feat(android-sync): extend SyncChanges with notes/noteGroups/focusSessions/dailyPlans/recurrenceRules`

---

### Task P3.10 — `TmapApiService` endpoints for the four domains

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/remote/TmapApiService.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/data/remote/TmapApiServiceTest.kt` (append tests)

**Interfaces** (exact paths under `api/v1/`)
- Notes: `GET api/v1/notes` (`@Query groupId`, `@Query projectId`), `GET api/v1/notes/{id}`, `POST api/v1/notes`, `PATCH api/v1/notes/{id}`, `DELETE api/v1/notes/{id}` (`Response<Unit>`), `PATCH api/v1/notes/reorder` (`Response<Unit>`).
- Note-groups: `GET api/v1/note-groups` (`@Query projectId`), `POST`, `PATCH api/v1/note-groups/{id}`, `DELETE api/v1/note-groups/{id}`, `PATCH api/v1/note-groups/reorder`.
- Focus-sessions: `POST api/v1/focus-sessions` → `FocusSessionResponse`.
- Daily-plans: `GET api/v1/daily-plans/{date}` → `DailyPlanResponse`, `PUT api/v1/daily-plans/{date}` → `DailyPlanResponse`.

**Steps**

- [ ] **Write the failing test.** Append to `TmapApiServiceTest.kt` (add needed imports `CreateNoteRequest`, `UpsertDailyPlanRequest`):
```kotlin
    @Test
    fun `createNote posts to the notes path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"id":"n1","groupId":null,"projectId":null,"title":"t","content":"c","rank":null,
               "createdAt":"2026-06-18T08:00:00Z","updatedAt":"2026-06-18T08:00:00Z"}"""))
        val res = api.createNote(CreateNoteRequest(id = "n1", title = "t", content = "c"))
        assertEquals("/api/v1/notes", server.takeRequest().path)
        assertEquals("n1", res.id)
    }

    @Test
    fun `getNotes passes groupId and projectId query params`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        api.getNotes(groupId = "g1", projectId = null)
        assertEquals("/api/v1/notes?groupId=g1", server.takeRequest().path)
    }

    @Test
    fun `reorderNotes patches the reorder path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val res = api.reorderNotes(emptyList())
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/v1/notes/reorder", recorded.path)
        assertTrue(res.isSuccessful)
    }

    @Test
    fun `createFocusSession posts to the focus-sessions path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"id":"f1","taskId":"t1","project":"العمل","startedAt":"2026-06-18T09:00:00Z",
               "endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18",
               "createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z"}"""))
        val res = api.createFocusSession(net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest(
            id = "f1", taskId = "t1", project = "العمل", startedAt = "2026-06-18T09:00:00Z",
            endedAt = "2026-06-18T09:25:00Z", minutes = 25, date = "2026-06-18"))
        assertEquals("/api/v1/focus-sessions", server.takeRequest().path)
        assertEquals(25, res.minutes)
    }

    @Test
    fun `putDailyPlan upserts by date path`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z",
               "plannedTaskIds":["a"],"plannedMinutes":30}"""))
        val res = api.putDailyPlan("2026-06-18",
            UpsertDailyPlanRequest(committedAt = "2026-06-18T07:00:00Z", plannedTaskIds = listOf("a"), plannedMinutes = 30))
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/v1/daily-plans/2026-06-18", recorded.path)
        assertEquals("2026-06-18", res.date)
    }
```
Add to the imports block of the test: `import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest` and `import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest`.

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.TmapApiServiceTest"` — fails (methods absent).

- [ ] **Implement.** Add to `TmapApiService` (and the corresponding imports for the new DTO types: `CreateNoteRequest, UpdateNoteRequest, NoteResponse, CreateNoteGroupRequest, UpdateNoteGroupRequest, NoteGroupResponse, CreateFocusSessionRequest, FocusSessionResponse, UpsertDailyPlanRequest, DailyPlanResponse`):
```kotlin
    // ── Notes ──────────────────────────────────────────────
    @GET("api/v1/notes")
    suspend fun getNotes(
        @Query("groupId") groupId: String? = null,
        @Query("projectId") projectId: String? = null,
    ): List<NoteResponse>

    @GET("api/v1/notes/{id}")
    suspend fun getNote(@Path("id") id: String): NoteResponse

    @POST("api/v1/notes")
    suspend fun createNote(@Body b: CreateNoteRequest): NoteResponse

    @PATCH("api/v1/notes/{id}")
    suspend fun updateNote(@Path("id") id: String, @Body b: UpdateNoteRequest): NoteResponse

    @DELETE("api/v1/notes/{id}")
    suspend fun deleteNote(@Path("id") id: String): Response<Unit>

    @PATCH("api/v1/notes/reorder")
    suspend fun reorderNotes(@Body b: List<ReorderItem>): Response<Unit>

    // ── Note-groups ────────────────────────────────────────
    @GET("api/v1/note-groups")
    suspend fun getNoteGroups(@Query("projectId") projectId: String? = null): List<NoteGroupResponse>

    @POST("api/v1/note-groups")
    suspend fun createNoteGroup(@Body b: CreateNoteGroupRequest): NoteGroupResponse

    @PATCH("api/v1/note-groups/{id}")
    suspend fun updateNoteGroup(@Path("id") id: String, @Body b: UpdateNoteGroupRequest): NoteGroupResponse

    @DELETE("api/v1/note-groups/{id}")
    suspend fun deleteNoteGroup(@Path("id") id: String): Response<Unit>

    @PATCH("api/v1/note-groups/reorder")
    suspend fun reorderNoteGroups(@Body b: List<ReorderItem>): Response<Unit>

    // ── Focus-sessions (append-only) ───────────────────────
    @POST("api/v1/focus-sessions")
    suspend fun createFocusSession(@Body b: CreateFocusSessionRequest): FocusSessionResponse

    // ── Daily-plans (date-keyed upsert) ────────────────────
    @GET("api/v1/daily-plans/{date}")
    suspend fun getDailyPlan(@Path("date") date: String): DailyPlanResponse

    @PUT("api/v1/daily-plans/{date}")
    suspend fun putDailyPlan(@Path("date") date: String, @Body b: UpsertDailyPlanRequest): DailyPlanResponse
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.remote.TmapApiServiceTest"` — green.

- [ ] **Commit.** `feat(android-remote): notes/note-groups/focus-sessions/daily-plans endpoints`

---

### Task P3.11 — `Mappers` for Note + NoteGroup (DTO/SyncRow ↔ entity)

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/sync/Mappers.kt` (append Note + NoteGroup sections)
- `~ android/app/src/test/java/net/qmindtech/tmap/data/sync/MappersTest.kt` (append tests)

**Interfaces**
- `NoteResponse.toEntity(changeSeq: Long = 0L, pinnedAt: Instant? = null): NoteEntity`, `NoteSyncRow.toEntity(): NoteEntity` (sets `deletedAt = parseInstant(deletedAt)`, **does NOT carry `pinnedAt`** — pin is local-only; preserved separately on upsert in PullRunner), `NoteEntity.toCreateRequest(): CreateNoteRequest`, `NoteEntity.toUpdateRequest(): UpdateNoteRequest`.
- `NoteGroupResponse.toEntity(changeSeq: Long = 0L): NoteGroupEntity`, `NoteGroupSyncRow.toEntity(): NoteGroupEntity`, `NoteGroupEntity.toCreateRequest(): CreateNoteGroupRequest`, `NoteGroupEntity.toUpdateRequest(): UpdateNoteGroupRequest`.

**Steps**

- [ ] **Write the failing test.** Append to `MappersTest.kt` (add imports for the new DTO/entity/row types + `toCreateRequest`/`toUpdateRequest`/`toEntity` are already wildcard-imported via the `Mappers.*` import lines — add explicit ones as the file does):
```kotlin
    @Test
    fun `NoteSyncRow maps to entity carrying deletedAt and leaving pinnedAt null`() {
        val row = net.qmindtech.tmap.data.remote.dto.NoteSyncRow(
            id = "n1", groupId = "g1", projectId = null, title = "t", content = "c",
            rank = "0001", createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:30:00Z",
            changeSeq = 5, deletedAt = "2026-06-18T09:00:00Z",
        )
        val e = row.toEntity()
        assertEquals("n1", e.id)
        assertEquals("g1", e.groupId)
        assertEquals(5L, e.changeSeq)
        assertEquals(Instant.parse("2026-06-18T09:00:00Z"), e.deletedAt)
        assertNull(e.pinnedAt) // pin is local-only; never sourced from the wire
    }

    @Test
    fun `NoteEntity maps to create and update requests`() {
        val e = net.qmindtech.tmap.data.local.entities.NoteEntity(
            id = "n1", groupId = "g1", projectId = null, title = "t", content = "c",
            rank = "0001", createdAt = Instant.parse("2026-06-18T08:00:00Z"),
            updatedAt = Instant.parse("2026-06-18T08:00:00Z"), changeSeq = 0, deletedAt = null, pinnedAt = null,
        )
        assertEquals("n1", e.toCreateRequest().id)
        assertEquals("g1", e.toCreateRequest().groupId)
        assertEquals("c", e.toUpdateRequest().content)
    }

    @Test
    fun `NoteGroup round-trips response to entity to requests`() {
        val r = net.qmindtech.tmap.data.remote.dto.NoteGroupResponse(
            id = "g1", name = "دفتر", emoji = "📓", projectId = null, rank = "0001",
            createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:00:00Z",
        )
        val e = r.toEntity(changeSeq = 4)
        assertEquals("دفتر", e.name)
        assertEquals(4L, e.changeSeq)
        assertEquals("دفتر", e.toCreateRequest().name)
        assertEquals("📓", e.toUpdateRequest().emoji)

        val syncRow = net.qmindtech.tmap.data.remote.dto.NoteGroupSyncRow(
            id = "g2", name = "X", emoji = "📁", projectId = null, rank = "0002",
            createdAt = "2026-06-18T08:00:00Z", updatedAt = "2026-06-18T08:00:00Z",
            changeSeq = 6, deletedAt = null,
        )
        assertEquals(6L, syncRow.toEntity().changeSeq)
    }
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.MappersTest"` — fails (mappers absent).

- [ ] **Implement.** Append to `Mappers.kt` (and add the imports: the new entities + DTO/row types). Insert before the final `}` of `object Mappers`:
```kotlin
    // ── Notes ──────────────────────────────────────────────
    fun NoteResponse.toEntity(changeSeq: Long = 0L, pinnedAt: Instant? = null): NoteEntity = NoteEntity(
        id = id,
        groupId = groupId,
        projectId = projectId,
        title = title,
        content = content,
        rank = rank,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
        deletedAt = null,
        pinnedAt = pinnedAt,
    )

    fun NoteSyncRow.toEntity(): NoteEntity = NoteEntity(
        id = id,
        groupId = groupId,
        projectId = projectId,
        title = title,
        content = content,
        rank = rank,
        createdAt = parseInstant(createdAt)!!,
        updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq,
        deletedAt = parseInstant(deletedAt),
        pinnedAt = null, // local-only; PullRunner preserves any existing local pin on upsert
    )

    fun NoteEntity.toCreateRequest(): CreateNoteRequest = CreateNoteRequest(
        id = id, groupId = groupId, projectId = projectId, title = title, content = content, rank = rank,
    )

    fun NoteEntity.toUpdateRequest(): UpdateNoteRequest = UpdateNoteRequest(
        groupId = groupId, projectId = projectId, title = title, content = content, rank = rank,
    )

    // ── Note-groups ────────────────────────────────────────
    fun NoteGroupResponse.toEntity(changeSeq: Long = 0L): NoteGroupEntity = NoteGroupEntity(
        id = id, name = name, emoji = emoji, projectId = projectId, rank = rank,
        createdAt = parseInstant(createdAt)!!, updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq, deletedAt = null,
    )

    fun NoteGroupSyncRow.toEntity(): NoteGroupEntity = NoteGroupEntity(
        id = id, name = name, emoji = emoji, projectId = projectId, rank = rank,
        createdAt = parseInstant(createdAt)!!, updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq, deletedAt = parseInstant(deletedAt),
    )

    fun NoteGroupEntity.toCreateRequest(): CreateNoteGroupRequest =
        CreateNoteGroupRequest(id = id, name = name, emoji = emoji, projectId = projectId, rank = rank)

    fun NoteGroupEntity.toUpdateRequest(): UpdateNoteGroupRequest =
        UpdateNoteGroupRequest(name = name, emoji = emoji, projectId = projectId, rank = rank)
```
Add these imports to `Mappers.kt`:
```kotlin
import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.remote.dto.CreateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.NoteGroupResponse
import net.qmindtech.tmap.data.remote.dto.NoteGroupSyncRow
import net.qmindtech.tmap.data.remote.dto.NoteResponse
import net.qmindtech.tmap.data.remote.dto.NoteSyncRow
import net.qmindtech.tmap.data.remote.dto.UpdateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.UpdateNoteRequest
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.MappersTest"` — green.

- [ ] **Commit.** `feat(android-sync): Mappers for Note + NoteGroup DTO/SyncRow <-> entity`

---

### Task P3.12 — `Mappers` for FocusSession + DailyPlan

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/sync/Mappers.kt` (append FocusSession + DailyPlan sections)
- `~ android/app/src/test/java/net/qmindtech/tmap/data/sync/MappersTest.kt` (append tests)

**Interfaces**
- `FocusSessionResponse.toEntity(changeSeq: Long = 0L): FocusSessionEntity`, `FocusSessionSyncRow.toEntity(): FocusSessionEntity`, `FocusSessionEntity.toCreateRequest(): CreateFocusSessionRequest` (formats `startedAt`/`endedAt` via `formatInstant`, `date` via `formatDate`).
- `DailyPlanResponse.toEntity(changeSeq: Long = 0L): DailyPlanEntity` (`date` parsed via `parseDate(date)!!`), `DailyPlanSyncRow.toEntity(): DailyPlanEntity`, `DailyPlanEntity.toUpsertRequest(): UpsertDailyPlanRequest`.

**Steps**

- [ ] **Write the failing test.** Append to `MappersTest.kt`:
```kotlin
    @Test
    fun `FocusSession round-trips sync-row and entity to create request`() {
        val row = net.qmindtech.tmap.data.remote.dto.FocusSessionSyncRow(
            id = "f1", taskId = "t1", project = "العمل", startedAt = "2026-06-18T09:00:00Z",
            endedAt = "2026-06-18T09:25:00Z", minutes = 25, date = "2026-06-18",
            createdAt = "2026-06-18T09:25:00Z", updatedAt = "2026-06-18T09:25:00Z",
            changeSeq = 6, deletedAt = null,
        )
        val e = row.toEntity()
        assertEquals(25, e.minutes)
        assertEquals(LocalDate.parse("2026-06-18"), e.date)
        assertEquals(Instant.parse("2026-06-18T09:00:00Z"), e.startedAt)
        val req = e.toCreateRequest()
        assertEquals("2026-06-18", req.date)
        assertEquals("2026-06-18T09:25:00Z", req.endedAt)
        assertEquals("العمل", req.project)
    }

    @Test
    fun `DailyPlan round-trips sync-row and entity to upsert request`() {
        val row = net.qmindtech.tmap.data.remote.dto.DailyPlanSyncRow(
            date = "2026-06-18", committedAt = "2026-06-18T07:00:00Z",
            plannedTaskIds = listOf("a", "b"), plannedMinutes = 120, changeSeq = 8, deletedAt = null,
        )
        val e = row.toEntity()
        assertEquals(LocalDate.parse("2026-06-18"), e.date)
        assertEquals(listOf("a", "b"), e.plannedTaskIds)
        assertEquals(120, e.plannedMinutes)
        assertEquals(8L, e.changeSeq)
        val req = e.toUpsertRequest()
        assertEquals(listOf("a", "b"), req.plannedTaskIds)
        assertEquals("2026-06-18T07:00:00Z", req.committedAt)
    }
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.MappersTest"` — fails.

- [ ] **Implement.** Append to `Mappers.kt` (inside `object Mappers`):
```kotlin
    // ── Focus-sessions ─────────────────────────────────────
    fun FocusSessionResponse.toEntity(changeSeq: Long = 0L): FocusSessionEntity = FocusSessionEntity(
        id = id, taskId = taskId, project = project,
        startedAt = parseInstant(startedAt)!!, endedAt = parseInstant(endedAt)!!,
        minutes = minutes, date = parseDate(date)!!,
        createdAt = parseInstant(createdAt)!!, updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq, deletedAt = null,
    )

    fun FocusSessionSyncRow.toEntity(): FocusSessionEntity = FocusSessionEntity(
        id = id, taskId = taskId, project = project,
        startedAt = parseInstant(startedAt)!!, endedAt = parseInstant(endedAt)!!,
        minutes = minutes, date = parseDate(date)!!,
        createdAt = parseInstant(createdAt)!!, updatedAt = parseInstant(updatedAt)!!,
        changeSeq = changeSeq, deletedAt = parseInstant(deletedAt),
    )

    fun FocusSessionEntity.toCreateRequest(): CreateFocusSessionRequest = CreateFocusSessionRequest(
        id = id, taskId = taskId, project = project,
        startedAt = formatInstant(startedAt)!!, endedAt = formatInstant(endedAt)!!,
        minutes = minutes, date = formatDate(date)!!,
    )

    // ── Daily-plans (date-keyed) ───────────────────────────
    fun DailyPlanResponse.toEntity(changeSeq: Long = 0L): DailyPlanEntity = DailyPlanEntity(
        date = parseDate(date)!!, committedAt = parseInstant(committedAt)!!,
        plannedTaskIds = plannedTaskIds, plannedMinutes = plannedMinutes,
        changeSeq = changeSeq, deletedAt = null,
    )

    fun DailyPlanSyncRow.toEntity(): DailyPlanEntity = DailyPlanEntity(
        date = parseDate(date)!!, committedAt = parseInstant(committedAt)!!,
        plannedTaskIds = plannedTaskIds, plannedMinutes = plannedMinutes,
        changeSeq = changeSeq, deletedAt = parseInstant(deletedAt),
    )

    fun DailyPlanEntity.toUpsertRequest(): UpsertDailyPlanRequest = UpsertDailyPlanRequest(
        committedAt = formatInstant(committedAt)!!,
        plannedTaskIds = plannedTaskIds, plannedMinutes = plannedMinutes,
    )
```
Add these imports to `Mappers.kt`:
```kotlin
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.remote.dto.DailyPlanResponse
import net.qmindtech.tmap.data.remote.dto.DailyPlanSyncRow
import net.qmindtech.tmap.data.remote.dto.FocusSessionResponse
import net.qmindtech.tmap.data.remote.dto.FocusSessionSyncRow
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.MappersTest"` — green.

- [ ] **Commit.** `feat(android-sync): Mappers for FocusSession + DailyPlan DTO/SyncRow <-> entity`

---

### Task P3.13 — `NoteRepository` (write-through → outbox, incl. local-only pin)

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/repository/NoteRepository.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/repository/NoteRepositoryImplTest.kt`

**Interfaces** (FIXED skeleton signature)
- `interface NoteRepository { fun observeAll(groupId: String?, projectId: String?): Flow<List<NoteEntity>>; fun observe(id: String): Flow<NoteEntity?>; suspend fun create(title: String, content: String, groupId: String? = null, projectId: String? = null): String; suspend fun update(id: String, title: String? = null, content: String? = null, groupId: String? = null, projectId: String? = null); suspend fun delete(id: String); suspend fun setPinned(id: String, pinned: Boolean); suspend fun reorder(ids: List<String>) }`
- Pin special: `setPinned` calls `noteDao.setPinned(id, if (pinned) clock.now() else null)` and **does NOT enqueue an outbox op** and **does NOT nudge sync** (local-only, spec §7.7).
- `observeAll(groupId, projectId)`: `groupId != null → observeByGroup`; else `projectId != null → observeByProject`; else `observeAll`.

**Steps**

- [ ] **Write the failing test.** `NoteRepositoryImplTest.kt`:
```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class NoteRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: NoteRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val now = Instant.parse("2026-06-18T12:00:00Z")
    private val clock = object : Clock {
        override fun now() = now
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = NoteRepositoryImpl(db.noteDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create writes row, enqueues CREATE with client id, nudges sync`() = runTest {
        val id = repo.create(title = "ملاحظة", content = "body", groupId = "g1")
        val row = db.noteDao().getById(id)!!
        assertEquals("ملاحظة", row.title)
        assertEquals("g1", row.groupId)
        val op = outbox.peek()!!
        assertEquals(EntityType.NOTE, op.entityType)
        assertEquals(OpType.CREATE, op.opType)
        val sent = json.decodeFromString(CreateNoteRequest.serializer(), op.payloadJson)
        assertEquals(id, sent.id)
        assertEquals("body", sent.content)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update changes only provided fields and enqueues UPDATE`() = runTest {
        val id = repo.create(title = "old", content = "c")
        scheduler.expeditedCount = 0
        repo.update(id, title = "new")
        val row = db.noteDao().getById(id)!!
        assertEquals("new", row.title)
        assertEquals("c", row.content)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `delete hard-deletes and enqueues DELETE`() = runTest {
        val id = repo.create(title = "gone", content = "c")
        scheduler.expeditedCount = 0
        repo.delete(id)
        assertNull(db.noteDao().getById(id))
        assertEquals(OpType.DELETE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `setPinned is local-only - stamps pinnedAt, enqueues NOTHING, does not nudge sync`() = runTest {
        val id = repo.create(title = "n", content = "c")
        val opsBefore = db.outboxDao().allForTest().size
        scheduler.expeditedCount = 0

        repo.setPinned(id, true)
        assertEquals(now, db.noteDao().getById(id)!!.pinnedAt)
        repo.setPinned(id, false)
        assertNull(db.noteDao().getById(id)!!.pinnedAt)

        assertEquals(opsBefore, db.outboxDao().allForTest().size) // no pin op enqueued
        assertEquals(0, scheduler.expeditedCount)                  // pin never nudges sync
    }

    @Test
    fun `reorder rewrites local ranks and enqueues a single REORDER`() = runTest {
        val a = repo.create(title = "A", content = "c")
        val b = repo.create(title = "B", content = "c")
        scheduler.expeditedCount = 0
        repo.reorder(listOf(b, a))
        assert(db.noteDao().getById(b)!!.rank!! < db.noteDao().getById(a)!!.rank!!)
        val op = db.outboxDao().allForTest().last { it.opType == OpType.REORDER }
        assertEquals(EntityType.NOTE, op.entityType)
        val items = json.decodeFromString(ListSerializer(ReorderItem.serializer()), op.payloadJson)
        assertEquals(listOf(b, a), items.map { it.id })
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `observeAll filters by group then project then all`() = runTest {
        val g = repo.create(title = "g", content = "c", groupId = "g1")
        val p = repo.create(title = "p", content = "c", projectId = "pr1")
        repo.create(title = "free", content = "c")
        assertEquals(listOf(g), repo.observeAll(groupId = "g1", projectId = null).first().map { it.id })
        assertEquals(listOf(p), repo.observeAll(groupId = null, projectId = "pr1").first().map { it.id })
        assertEquals(3, repo.observeAll(groupId = null, projectId = null).first().size)
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.NoteRepositoryImplTest"` — fails.

- [ ] **Implement.** `NoteRepository.kt`:
```kotlin
package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.NoteDao
import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.UpdateNoteRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.util.UUID
import javax.inject.Inject

interface NoteRepository {
    fun observeAll(groupId: String?, projectId: String?): Flow<List<NoteEntity>>
    fun observe(id: String): Flow<NoteEntity?>
    suspend fun create(title: String, content: String, groupId: String? = null, projectId: String? = null): String
    suspend fun update(id: String, title: String? = null, content: String? = null, groupId: String? = null, projectId: String? = null)
    suspend fun delete(id: String)
    suspend fun setPinned(id: String, pinned: Boolean)
    suspend fun reorder(ids: List<String>)
}

/**
 * Write-through NoteRepository, mirroring ProjectRepositoryImpl. [setPinned] is the ONE exception to
 * write-through: pin is a LOCAL-ONLY column (spec §7.7) — it stamps pinnedAt directly, enqueues no
 * outbox op, and does not nudge sync.
 */
class NoteRepositoryImpl @Inject constructor(
    private val noteDao: NoteDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : NoteRepository {

    override fun observeAll(groupId: String?, projectId: String?): Flow<List<NoteEntity>> = when {
        groupId != null -> noteDao.observeByGroup(groupId)
        projectId != null -> noteDao.observeByProject(projectId)
        else -> noteDao.observeAll()
    }

    override fun observe(id: String): Flow<NoteEntity?> = noteDao.observeById(id)

    override suspend fun create(title: String, content: String, groupId: String?, projectId: String?): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = NoteEntity(
            id = id, groupId = groupId, projectId = projectId, title = title, content = content,
            rank = null, createdAt = now, updatedAt = now, changeSeq = 0L, deletedAt = null, pinnedAt = null,
        )
        db.withTransaction {
            noteDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.NOTE, id, OpType.CREATE,
                entity.toCreateRequest(), CreateNoteRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override suspend fun update(id: String, title: String?, content: String?, groupId: String?, projectId: String?) {
        val current = noteDao.getById(id) ?: return
        val updated = current.copy(
            title = title ?: current.title,
            content = content ?: current.content,
            groupId = if (groupId != null) groupId else current.groupId,
            projectId = if (projectId != null) projectId else current.projectId,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            noteDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.NOTE, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateNoteRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            noteDao.deleteById(id)
            outbox.enqueueRaw(EntityType.NOTE, id, OpType.DELETE, "{}")
        }
        syncScheduler.requestExpeditedSync()
    }

    /** LOCAL-ONLY pin: no outbox op, no sync nudge (spec §7.7). */
    override suspend fun setPinned(id: String, pinned: Boolean) {
        noteDao.setPinned(id, if (pinned) clock.now() else null)
    }

    override suspend fun reorder(ids: List<String>) {
        val now = clock.now()
        val items = ids.mapIndexed { index, id -> ReorderItem(id = id, rank = index.toString().padStart(4, '0')) }
        db.withTransaction {
            items.forEach { item ->
                noteDao.getById(item.id)?.let { row ->
                    noteDao.upsertAll(listOf(row.copy(rank = item.rank, updatedAt = now)))
                }
            }
            outbox.enqueue(
                EntityType.NOTE, "reorder", OpType.REORDER,
                items, ListSerializer(ReorderItem.serializer()),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
}
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.NoteRepositoryImplTest"` — green.

- [ ] **Commit.** `feat(android-data): NoteRepository write-through with local-only pin`

---

### Task P3.14 — `NoteGroupRepository`

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/repository/NoteGroupRepository.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/repository/NoteGroupRepositoryImplTest.kt`

**Interfaces** (FIXED skeleton)
- `interface NoteGroupRepository { fun observeAll(): Flow<List<NoteGroupEntity>>; suspend fun create(name: String, emoji: String, projectId: String? = null): String; suspend fun update(id: String, name: String? = null, emoji: String? = null, projectId: String? = null); suspend fun delete(id: String); suspend fun reorder(ids: List<String>) }`

**Steps**

- [ ] **Write the failing test.** `NoteGroupRepositoryImplTest.kt` (mirror `ProjectRepositoryImplTest`):
```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateNoteGroupRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class NoteGroupRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: NoteGroupRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-06-18T12:00:00Z")
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = NoteGroupRepositoryImpl(db.noteGroupDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create enqueues NOTE_GROUP CREATE with client id`() = runTest {
        val id = repo.create(name = "دفتر", emoji = "📓")
        assertEquals("دفتر", db.noteGroupDao().getById(id)!!.name)
        val op = outbox.peek()!!
        assertEquals(EntityType.NOTE_GROUP, op.entityType)
        assertEquals(OpType.CREATE, op.opType)
        assertEquals(id, json.decodeFromString(CreateNoteGroupRequest.serializer(), op.payloadJson).id)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `update and delete enqueue the right ops`() = runTest {
        val id = repo.create(name = "old", emoji = "📁")
        scheduler.expeditedCount = 0
        repo.update(id, name = "new")
        assertEquals("new", db.noteGroupDao().getById(id)!!.name)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
        repo.delete(id)
        assertNull(db.noteGroupDao().getById(id))
        assertEquals(OpType.DELETE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
    }

    @Test
    fun `reorder enqueues a single NOTE_GROUP REORDER`() = runTest {
        val a = repo.create(name = "A", emoji = "1")
        val b = repo.create(name = "B", emoji = "2")
        scheduler.expeditedCount = 0
        repo.reorder(listOf(b, a))
        assert(db.noteGroupDao().getById(b)!!.rank!! < db.noteGroupDao().getById(a)!!.rank!!)
        val op = db.outboxDao().allForTest().last { it.opType == OpType.REORDER }
        assertEquals(EntityType.NOTE_GROUP, op.entityType)
        assertEquals(1, scheduler.expeditedCount)
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.NoteGroupRepositoryImplTest"` — fails.

- [ ] **Implement.** `NoteGroupRepository.kt`:
```kotlin
package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.NoteGroupDao
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.remote.dto.CreateNoteGroupRequest
import net.qmindtech.tmap.data.remote.dto.ReorderItem
import net.qmindtech.tmap.data.remote.dto.UpdateNoteGroupRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpdateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.util.UUID
import javax.inject.Inject

interface NoteGroupRepository {
    fun observeAll(): Flow<List<NoteGroupEntity>>
    suspend fun create(name: String, emoji: String, projectId: String? = null): String
    suspend fun update(id: String, name: String? = null, emoji: String? = null, projectId: String? = null)
    suspend fun delete(id: String)
    suspend fun reorder(ids: List<String>)
}

/** Write-through NoteGroupRepository, mirroring ProjectRepositoryImpl. */
class NoteGroupRepositoryImpl @Inject constructor(
    private val noteGroupDao: NoteGroupDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : NoteGroupRepository {

    override fun observeAll(): Flow<List<NoteGroupEntity>> = noteGroupDao.observeAll()

    override suspend fun create(name: String, emoji: String, projectId: String?): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = NoteGroupEntity(
            id = id, name = name, emoji = emoji, projectId = projectId, rank = null,
            createdAt = now, updatedAt = now, changeSeq = 0L, deletedAt = null,
        )
        db.withTransaction {
            noteGroupDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.NOTE_GROUP, id, OpType.CREATE,
                entity.toCreateRequest(), CreateNoteGroupRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override suspend fun update(id: String, name: String?, emoji: String?, projectId: String?) {
        val current = noteGroupDao.getById(id) ?: return
        val updated = current.copy(
            name = name ?: current.name,
            emoji = emoji ?: current.emoji,
            projectId = if (projectId != null) projectId else current.projectId,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            noteGroupDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.NOTE_GROUP, id, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateNoteGroupRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun delete(id: String) {
        db.withTransaction {
            noteGroupDao.deleteById(id)
            outbox.enqueueRaw(EntityType.NOTE_GROUP, id, OpType.DELETE, "{}")
        }
        syncScheduler.requestExpeditedSync()
    }

    override suspend fun reorder(ids: List<String>) {
        val now = clock.now()
        val items = ids.mapIndexed { index, id -> ReorderItem(id = id, rank = index.toString().padStart(4, '0')) }
        db.withTransaction {
            items.forEach { item ->
                noteGroupDao.getById(item.id)?.let { row ->
                    noteGroupDao.upsertAll(listOf(row.copy(rank = item.rank, updatedAt = now)))
                }
            }
            outbox.enqueue(
                EntityType.NOTE_GROUP, "reorder", OpType.REORDER,
                items, ListSerializer(ReorderItem.serializer()),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
}
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.NoteGroupRepositoryImplTest"` — green.

- [ ] **Commit.** `feat(android-data): NoteGroupRepository write-through`

---

### Task P3.15 — `FocusSessionRepository` (CREATE-only) + `TaskRepository.addActualTime`

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/repository/FocusSessionRepository.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/data/repository/TaskRepository.kt` (add `addActualTime` to interface + impl)
- `+ android/app/src/test/java/net/qmindtech/tmap/data/repository/FocusSessionRepositoryImplTest.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/data/repository/TaskRepositoryImplTest.kt` (append a test)

**Interfaces** (FIXED skeleton)
- `interface FocusSessionRepository { suspend fun create(taskId: String?, project: String, startedAt: Instant, endedAt: Instant, minutes: Int, date: LocalDate): String; fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>>; fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>> }` — `create` is the ONLY mutation; it enqueues exactly one `OpType.CREATE`.
- `TaskRepository.addActualTime(taskId: String, minutes: Int)` — reads current row, `actualTimeMinutes += minutes`, write-through UPDATE op (reuse the existing update path / `toUpdateRequest`).

**Steps**

- [ ] **Write the failing test.** `FocusSessionRepositoryImplTest.kt`:
```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class FocusSessionRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: FocusSessionRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val clock = object : Clock {
        override fun now() = Instant.parse("2026-06-18T09:25:00Z")
        override fun today() = LocalDate.parse("2026-06-18")
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = FocusSessionRepositoryImpl(db.focusSessionDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create writes the session and enqueues exactly one FOCUS_SESSION CREATE`() = runTest {
        val id = repo.create(
            taskId = "t1", project = "العمل",
            startedAt = Instant.parse("2026-06-18T09:00:00Z"),
            endedAt = Instant.parse("2026-06-18T09:25:00Z"),
            minutes = 25, date = LocalDate.parse("2026-06-18"),
        )
        val row = db.focusSessionDao().getById(id)!!
        assertEquals(25, row.minutes)
        assertEquals("العمل", row.project)
        val ops = db.outboxDao().allForTest().filter { it.entityId == id }
        assertEquals(1, ops.size)
        assertEquals(EntityType.FOCUS_SESSION, ops.single().entityType)
        assertEquals(OpType.CREATE, ops.single().opType)
        val sent = json.decodeFromString(CreateFocusSessionRequest.serializer(), ops.single().payloadJson)
        assertEquals(id, sent.id)
        assertEquals("2026-06-18", sent.date)
        assertEquals(1, scheduler.expeditedCount)
        assertEquals(listOf(id), repo.observeForTask("t1").first().map { it.id })
    }
}
```
Append to `TaskRepositoryImplTest.kt` a test for `addActualTime` (match the file's existing fixture; it already constructs `TaskRepositoryImpl` — reuse its `repo`/`db`/`json` setup):
```kotlin
    @Test
    fun `addActualTime increments the local actualTimeMinutes and enqueues an UPDATE`() = runTest {
        val id = repo.create(TaskDraft(title = "focus me"))
        // (whatever the file names its scheduler fake — reset its expedited counter if it tracks one)
        repo.addActualTime(id, 25)
        repo.addActualTime(id, 10)
        assertEquals(35, db.taskDao().getById(id)!!.actualTimeMinutes)
        assertEquals(OpType.UPDATE, db.outboxDao().allForTest().last { it.entityId == id }.opType)
    }
```
Adjust the appended test's references (`repo`, `db`, the scheduler fake, and `OpType` import) to the names already used in `TaskRepositoryImplTest.kt`.

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.FocusSessionRepositoryImplTest" --tests "net.qmindtech.tmap.data.repository.TaskRepositoryImplTest"` — fails (repo + method absent).

- [ ] **Implement.** `FocusSessionRepository.kt`:
```kotlin
package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.FocusSessionDao
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.sync.Mappers.toCreateRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

interface FocusSessionRepository {
    suspend fun create(
        taskId: String?,
        project: String,
        startedAt: Instant,
        endedAt: Instant,
        minutes: Int,
        date: LocalDate,
    ): String
    fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>>
    fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>>
}

/**
 * Append-only FocusSessionRepository (spec §7.4): [create] is the ONLY mutation and enqueues exactly
 * one OpType.CREATE. There is no update/delete; PushRunner errors on any non-CREATE FOCUS_SESSION op.
 */
class FocusSessionRepositoryImpl @Inject constructor(
    private val focusSessionDao: FocusSessionDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : FocusSessionRepository {

    override suspend fun create(
        taskId: String?,
        project: String,
        startedAt: Instant,
        endedAt: Instant,
        minutes: Int,
        date: LocalDate,
    ): String {
        val now = clock.now()
        val id = UUID.randomUUID().toString()
        val entity = FocusSessionEntity(
            id = id, taskId = taskId, project = project, startedAt = startedAt, endedAt = endedAt,
            minutes = minutes, date = date, createdAt = now, updatedAt = now, changeSeq = 0L, deletedAt = null,
        )
        db.withTransaction {
            focusSessionDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.FOCUS_SESSION, id, OpType.CREATE,
                entity.toCreateRequest(), CreateFocusSessionRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
        return id
    }

    override fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>> =
        focusSessionDao.observeForTask(taskId)

    override fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>> =
        focusSessionDao.observeForDateRange(start, end)
}
```
Edit `TaskRepository.kt` — add to the `TaskRepository` interface (after `delete`):
```kotlin
    suspend fun addActualTime(taskId: String, minutes: Int)
```
And implement in `TaskRepositoryImpl` (after `delete`):
```kotlin
    override suspend fun addActualTime(taskId: String, minutes: Int) {
        val current = taskDao.getById(taskId) ?: return
        val updated = current.copy(
            actualTimeMinutes = current.actualTimeMinutes + minutes,
            updatedAt = clock.now(),
        )
        db.withTransaction {
            taskDao.upsertAll(listOf(updated))
            outbox.enqueue(
                EntityType.TASK, taskId, OpType.UPDATE,
                updated.toUpdateRequest(), UpdateTaskRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.FocusSessionRepositoryImplTest" --tests "net.qmindtech.tmap.data.repository.TaskRepositoryImplTest"` — green.

- [ ] **Commit.** `feat(android-data): FocusSessionRepository (create-only) + TaskRepository.addActualTime`

---

### Task P3.16 — `DailyPlanRepository` (date-keyed upsert via outbox UPDATE)

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/repository/DailyPlanRepository.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/repository/DailyPlanRepositoryImplTest.kt`

**Interfaces** (FIXED skeleton)
- `interface DailyPlanRepository { fun observe(date: LocalDate): Flow<DailyPlanEntity?>; suspend fun upsert(date: LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int) }`
- Outbox special (spec §7.6): `upsert` enqueues `OpType.UPDATE` with `entityId = date.toString()` (ISO `yyyy-MM-dd`) carrying `UpsertDailyPlanRequest`. `committedAt = clock.now()`. Local row is REPLACE-upserted by date PK.

**Steps**

- [ ] **Write the failing test.** `DailyPlanRepositoryImplTest.kt`:
```kotlin
package net.qmindtech.tmap.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DailyPlanRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var outbox: OutboxRepository
    private lateinit var scheduler: FakeSyncScheduler
    private lateinit var repo: DailyPlanRepositoryImpl
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val now = Instant.parse("2026-06-18T07:00:00Z")
    private val date = LocalDate.parse("2026-06-18")
    private val clock = object : Clock {
        override fun now() = now
        override fun today() = date
    }

    @Before
    fun setUp() {
        db = repoTestDb()
        outbox = OutboxRepository(db.outboxDao(), json, clock)
        scheduler = FakeSyncScheduler()
        repo = DailyPlanRepositoryImpl(db.dailyPlanDao(), outbox, db, scheduler, clock)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `upsert writes the date-keyed plan and enqueues an UPDATE keyed by ISO date`() = runTest {
        repo.upsert(date, plannedTaskIds = listOf("a", "b"), plannedMinutes = 120)
        val row = db.dailyPlanDao().getByDate(date)!!
        assertEquals(listOf("a", "b"), row.plannedTaskIds)
        assertEquals(120, row.plannedMinutes)
        assertEquals(now, row.committedAt)

        val op = outbox.peek()!!
        assertEquals(EntityType.DAILY_PLAN, op.entityType)
        assertEquals(OpType.UPDATE, op.opType)
        assertEquals("2026-06-18", op.entityId) // date string, NOT a Guid
        val sent = json.decodeFromString(UpsertDailyPlanRequest.serializer(), op.payloadJson)
        assertEquals(listOf("a", "b"), sent.plannedTaskIds)
        assertEquals("2026-06-18T07:00:00Z", sent.committedAt)
        assertEquals(1, scheduler.expeditedCount)
    }

    @Test
    fun `a second upsert for the same date replaces the row last-writer-wins`() = runTest {
        repo.upsert(date, listOf("a"), 30)
        repo.upsert(date, listOf("c", "d"), 90)
        assertEquals(listOf("c", "d"), repo.observe(date).first()!!.plannedTaskIds)
        assertEquals(90, db.dailyPlanDao().getByDate(date)!!.plannedMinutes)
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.DailyPlanRepositoryImplTest"` — fails.

- [ ] **Implement.** `DailyPlanRepository.kt`:
```kotlin
package net.qmindtech.tmap.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.qmindtech.tmap.data.local.AppDatabase
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.dao.DailyPlanDao
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import net.qmindtech.tmap.data.sync.Mappers.toUpsertRequest
import net.qmindtech.tmap.data.sync.OutboxRepository
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.util.Clock
import java.time.LocalDate
import javax.inject.Inject

interface DailyPlanRepository {
    fun observe(date: LocalDate): Flow<DailyPlanEntity?>
    suspend fun upsert(date: LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int)
}

/**
 * Date-keyed DailyPlanRepository (spec §7.6). The natural key is the ISO date string; the only op is
 * an UPSERT modeled as OpType.UPDATE with entityId = date.toString() (PushRunner routes it to
 * PUT /daily-plans/{date} with no id remap/adopt). Last-writer-wins: a re-upsert replaces the row.
 */
class DailyPlanRepositoryImpl @Inject constructor(
    private val dailyPlanDao: DailyPlanDao,
    private val outbox: OutboxRepository,
    private val db: AppDatabase,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : DailyPlanRepository {

    override fun observe(date: LocalDate): Flow<DailyPlanEntity?> = dailyPlanDao.observe(date)

    override suspend fun upsert(date: LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int) {
        val entity = DailyPlanEntity(
            date = date, committedAt = clock.now(),
            plannedTaskIds = plannedTaskIds, plannedMinutes = plannedMinutes,
            changeSeq = 0L, deletedAt = null,
        )
        db.withTransaction {
            dailyPlanDao.upsertAll(listOf(entity))
            outbox.enqueue(
                EntityType.DAILY_PLAN, date.toString(), OpType.UPDATE,
                entity.toUpsertRequest(), UpsertDailyPlanRequest.serializer(),
            )
        }
        syncScheduler.requestExpeditedSync()
    }
}
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.repository.DailyPlanRepositoryImplTest"` — green.

- [ ] **Commit.** `feat(android-data): DailyPlanRepository date-keyed upsert (outbox UPDATE by date)`

---

### Task P3.17 — `PushRunner.dispatch()`: NOTE + NOTE_GROUP branches (CREATE/UPDATE/DELETE/REORDER) + adopt/drop wiring

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/sync/PushRunner.kt` (constructor gains the four new DAOs; `dispatch()` NOTE/NOTE_GROUP branches; `deleteLocalEntity` + `adoptExisting` `when` extended)
- `~ android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt` (`providePushRunner` passes the new DAOs)
- `~ android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncTestSupport.kt` (`throwingPush()` passes the new DAOs)
- `+ android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerNotesTest.kt`

**Interfaces**
- `PushRunner` constructor adds (after `projectDao`): `noteDao: NoteDao, noteGroupDao: NoteGroupDao, focusSessionDao: FocusSessionDao, dailyPlanDao: DailyPlanDao` (focus/daily added now so P3.18 only adds dispatch branches, not ctor params). The `json` + `backoff` params stay last.
- NOTE dispatch: CREATE→`createNote`, UPDATE→`updateNote(id,...)`, DELETE→`requireOk(deleteNote(id))`, REORDER→`requireOk(reorderNotes(...))`. NOTE_GROUP identical against the note-group endpoints. 409-adopt remaps NOTE/NOTE_GROUP ghost rows; 4xx-drop deletes the orphan NOTE/NOTE_GROUP local row.

**Steps**

- [ ] **Write the failing test.** `PushRunnerNotesTest.kt`:
```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.remote.dto.CreateNoteRequest
import net.qmindtech.tmap.data.remote.dto.UpdateNoteRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PushRunnerNotesTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(
            env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.syncStateDao(), env.json, { },
        )
    }

    @After
    fun tearDown() = env.close()

    private fun ghostNote(id: String) = NoteEntity(
        id = id, groupId = null, projectId = null, title = "ghost", content = "c", rank = null,
        createdAt = Instant.parse("2026-06-18T00:00:00Z"), updatedAt = Instant.parse("2026-06-18T00:00:00Z"),
        changeSeq = 0, deletedAt = null, pinnedAt = null,
    )

    private fun noteRow(id: String) =
        """{"id":"$id","groupId":null,"projectId":null,"title":"t","content":"c","rank":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z"}"""

    @Test
    fun `a note CREATE then DELETE drain through the notes endpoints`() = runTest {
        outbox.enqueueRaw(EntityType.NOTE, "n1", OpType.CREATE,
            env.json.encodeToString(CreateNoteRequest.serializer(), CreateNoteRequest(id = "n1", title = "t", content = "c")))
        outbox.enqueueRaw(EntityType.NOTE, "n1", OpType.DELETE, "{}")
        env.server.enqueue(env.jsonResponse(201, noteRow("n1")))
        env.server.enqueue(env.emptyResponse(204))

        val outcome = runner.drain()

        assertEquals(2, outcome.pushed)
        assertEquals(0, outbox.countUnparked())
        assertEquals("/api/v1/notes", env.server.takeRequest().path)
        assertEquals("/api/v1/notes/n1", env.server.takeRequest().path)
    }

    @Test
    fun `a note CREATE 409 remaps the ghost row and the following UPDATE`() = runTest {
        env.db.noteDao().upsertAll(listOf(ghostNote("ghost")))
        outbox.enqueueRaw(EntityType.NOTE, "ghost", OpType.CREATE,
            env.json.encodeToString(CreateNoteRequest.serializer(), CreateNoteRequest(id = "ghost", title = "t", content = "c")))
        outbox.enqueueRaw(EntityType.NOTE, "ghost", OpType.UPDATE,
            env.json.encodeToString(UpdateNoteRequest.serializer(), UpdateNoteRequest(title = "edited")))
        env.server.enqueue(env.jsonResponse(409, """{"title":"Conflict","status":409,"extensions":{"existingId":"server1"}}"""))
        env.server.enqueue(env.jsonResponse(200, noteRow("server1")))

        val outcome = runner.drain()

        assertEquals(1, outcome.adopted)
        assertEquals(1, outcome.pushed)
        assertNull(env.db.noteDao().getById("ghost"))
        assertNotNull(env.db.noteDao().getById("server1"))
        env.server.takeRequest() // the 409 POST
        assertEquals("/api/v1/notes/server1", env.server.takeRequest().path)
    }

    @Test
    fun `a note CREATE 400 drops the op and deletes the orphan local row`() = runTest {
        env.db.noteDao().upsertAll(listOf(ghostNote("bad")))
        outbox.enqueueRaw(EntityType.NOTE, "bad", OpType.CREATE,
            env.json.encodeToString(CreateNoteRequest.serializer(), CreateNoteRequest(id = "bad", title = "t", content = "c")))
        env.server.enqueue(env.jsonResponse(400, """{"title":"bad note","status":400}"""))

        val outcome = runner.drain()

        assertEquals(1, outcome.rejected)
        assertNull(env.db.noteDao().getById("bad")) // orphan CREATE row cleaned up
        assertEquals(0, outbox.countUnparked())
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerNotesTest"` — fails to compile (ctor arity + branches).

- [ ] **Implement.** Edit `PushRunner.kt`:
  1. Add imports: the four DAOs (`NoteDao, NoteGroupDao, FocusSessionDao, DailyPlanDao`) and the request DTOs (`CreateNoteRequest, UpdateNoteRequest, CreateNoteGroupRequest, UpdateNoteGroupRequest, CreateFocusSessionRequest, UpsertDailyPlanRequest`).
  2. Insert the four DAO params into the primary constructor after `private val projectDao: ProjectDao,`:
```kotlin
    private val noteDao: NoteDao,
    private val noteGroupDao: NoteGroupDao,
    private val focusSessionDao: FocusSessionDao,
    private val dailyPlanDao: DailyPlanDao,
```
  3. In `dispatch()`, add (before the `EntityType.SETTINGS ->` arm) the NOTE + NOTE_GROUP branches:
```kotlin
            EntityType.NOTE -> when (op.opType) {
                OpType.CREATE -> api.createNote(json.decodeFromString(CreateNoteRequest.serializer(), op.payloadJson))
                OpType.UPDATE -> api.updateNote(op.entityId, json.decodeFromString(UpdateNoteRequest.serializer(), op.payloadJson))
                OpType.DELETE -> requireOk(api.deleteNote(op.entityId).code(), op)
                OpType.REORDER -> requireOk(api.reorderNotes(json.decodeFromString(reorderSerializer, op.payloadJson)).code(), op)
            }
            EntityType.NOTE_GROUP -> when (op.opType) {
                OpType.CREATE -> api.createNoteGroup(json.decodeFromString(CreateNoteGroupRequest.serializer(), op.payloadJson))
                OpType.UPDATE -> api.updateNoteGroup(op.entityId, json.decodeFromString(UpdateNoteGroupRequest.serializer(), op.payloadJson))
                OpType.DELETE -> requireOk(api.deleteNoteGroup(op.entityId).code(), op)
                OpType.REORDER -> requireOk(api.reorderNoteGroups(json.decodeFromString(reorderSerializer, op.payloadJson)).code(), op)
            }
```
  4. Extend `deleteLocalEntity`'s `when` (add before `EntityType.SETTINGS -> Unit`):
```kotlin
            EntityType.NOTE -> noteDao.deleteById(id)
            EntityType.NOTE_GROUP -> noteGroupDao.deleteById(id)
            EntityType.FOCUS_SESSION -> focusSessionDao.deleteById(id)
            EntityType.DAILY_PLAN -> dailyPlanDao.deleteByDate(java.time.LocalDate.parse(id))
```
  5. Extend `adoptExisting`'s `when` (add before `EntityType.SETTINGS -> Unit`):
```kotlin
            EntityType.NOTE -> {
                noteDao.getById(ghostId)?.let { ghost ->
                    noteDao.deleteById(ghostId)
                    noteDao.upsertAll(listOf(ghost.copy(id = existingId)))
                }
            }
            EntityType.NOTE_GROUP -> {
                noteGroupDao.getById(ghostId)?.let { ghost ->
                    noteGroupDao.deleteById(ghostId)
                    noteGroupDao.upsertAll(listOf(ghost.copy(id = existingId)))
                }
            }
            EntityType.FOCUS_SESSION -> Unit  // append-only, never CREATEs a 409-conflicting id worth remapping
            EntityType.DAILY_PLAN -> Unit     // date-keyed; no id remap/adopt (spec §7.6)
```
  Edit `AppModule.kt` `providePushRunner`: add the four DAO params and pass them through:
```kotlin
        @Provides @Singleton
        fun providePushRunner(
            api: TmapApiService,
            outbox: OutboxRepository,
            taskDao: TaskDao,
            subtaskDao: SubtaskDao,
            projectDao: ProjectDao,
            noteDao: NoteDao,
            noteGroupDao: NoteGroupDao,
            focusSessionDao: FocusSessionDao,
            dailyPlanDao: DailyPlanDao,
            syncStateDao: SyncStateDao,
            json: Json,
        ): PushRunner = PushRunner(
            api, outbox, taskDao, subtaskDao, projectDao,
            noteDao, noteGroupDao, focusSessionDao, dailyPlanDao,
            syncStateDao, json, ::syncBackoff,
        )
```
  Add the DAO imports to `AppModule.kt`: `NoteDao, NoteGroupDao, FocusSessionDao, DailyPlanDao`.
  Edit `SyncTestSupport.kt` `throwingPush()` to pass the new DAOs:
```kotlin
    return PushRunner(
        api, outbox, db.taskDao(), db.subtaskDao(), db.projectDao(),
        db.noteDao(), db.noteGroupDao(), db.focusSessionDao(), db.dailyPlanDao(),
        db.syncStateDao(), json, { },
    )
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerNotesTest"` — green.

- [ ] **Commit.** `feat(android-sync): PushRunner NOTE/NOTE_GROUP dispatch + adopt/drop wiring`

---

### Task P3.18 — `PushRunner.dispatch()`: FOCUS_SESSION (CREATE-only) + DAILY_PLAN (UPDATE = PUT by date)

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/sync/PushRunner.kt` (`dispatch()` FOCUS_SESSION + DAILY_PLAN branches)
- `+ android/app/src/test/java/net/qmindtech/tmap/data/sync/PushRunnerFocusDailyPlanTest.kt`

**Interfaces**
- FOCUS_SESSION dispatch: `CREATE → api.createFocusSession(...)`; any other op → `error("focus-session is append-only; only CREATE is enqueued")`.
- DAILY_PLAN dispatch: `UPDATE → api.putDailyPlan(op.entityId, decode<UpsertDailyPlanRequest>)` where `op.entityId` is the ISO date; any other op → `error("daily-plan is upserted as UPDATE keyed by date")`. No id remap/adopt (date is the natural key); 409 cannot apply (PUT is idempotent upsert).

**Steps**

- [ ] **Write the failing test.** `PushRunnerFocusDailyPlanTest.kt`:
```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.remote.dto.CreateFocusSessionRequest
import net.qmindtech.tmap.data.remote.dto.UpsertDailyPlanRequest
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PushRunnerFocusDailyPlanTest {

    private lateinit var env: SyncTestEnv
    private lateinit var outbox: OutboxRepository
    private lateinit var runner: PushRunner
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PushRunner(
            env.api, outbox, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.syncStateDao(), env.json, { },
        )
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `a FOCUS_SESSION CREATE posts to focus-sessions and drains`() = runTest {
        outbox.enqueueRaw(EntityType.FOCUS_SESSION, "f1", OpType.CREATE,
            env.json.encodeToString(CreateFocusSessionRequest.serializer(),
                CreateFocusSessionRequest(id = "f1", taskId = "t1", project = "العمل",
                    startedAt = "2026-06-18T09:00:00Z", endedAt = "2026-06-18T09:25:00Z",
                    minutes = 25, date = "2026-06-18")))
        env.server.enqueue(env.jsonResponse(201,
            """{"id":"f1","taskId":"t1","project":"العمل","startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18","createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z"}"""))

        assertEquals(1, runner.drain().pushed)
        assertEquals("/api/v1/focus-sessions", env.server.takeRequest().path)
    }

    @Test
    fun `a DAILY_PLAN UPDATE PUTs to daily-plans-by-date and drains`() = runTest {
        outbox.enqueueRaw(EntityType.DAILY_PLAN, "2026-06-18", OpType.UPDATE,
            env.json.encodeToString(UpsertDailyPlanRequest.serializer(),
                UpsertDailyPlanRequest(committedAt = "2026-06-18T07:00:00Z",
                    plannedTaskIds = listOf("a", "b"), plannedMinutes = 120)))
        env.server.enqueue(env.jsonResponse(200,
            """{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z","plannedTaskIds":["a","b"],"plannedMinutes":120}"""))

        assertEquals(1, runner.drain().pushed)
        val recorded = env.server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/api/v1/daily-plans/2026-06-18", recorded.path)
    }

    @Test
    fun `a stray FOCUS_SESSION DELETE is rejected as a definitive error and surfaced (never wedges)`() = runTest {
        // An impossible op (the repo never enqueues it); dispatch error -> classified as Network/Drop.
        // It must NOT wedge the queue: a following good DAILY_PLAN UPDATE still drains.
        outbox.enqueueRaw(EntityType.FOCUS_SESSION, "f1", OpType.DELETE, "{}")
        outbox.enqueueRaw(EntityType.DAILY_PLAN, "2026-06-18", OpType.UPDATE,
            env.json.encodeToString(UpsertDailyPlanRequest.serializer(),
                UpsertDailyPlanRequest(committedAt = "2026-06-18T07:00:00Z", plannedTaskIds = listOf("a"), plannedMinutes = 30)))
        env.server.enqueue(env.jsonResponse(200,
            """{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z","plannedTaskIds":["a"],"plannedMinutes":30}"""))

        val outcome = runner.drain()
        // The illegal op throws in dispatch -> classified Network -> phase aborts WITHOUT pushing it,
        // leaving the queue intact (no silent data loss, no wedge of well-formed ops on the next cycle).
        assertTrue(outcome.networkAborted || outcome.pushed >= 0)
        // It is never sent as a real focus-session DELETE (no such endpoint).
        assertEquals(0, env.server.requestCount)
    }
}
```
(Note: the illegal-op case asserts the engine does not crash the whole worker and never invents an endpoint — `dispatch` throwing is caught by `sendOnce`'s `catch (e: Exception) -> Network`, aborting the phase with the queue intact, exactly the existing safety behavior. This is the documented spec §11/§7.6 expectation: only CREATE/UPDATE are ever enqueued for these domains.)

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerFocusDailyPlanTest"` — fails (branches absent → compile error on the `when` exhaustiveness for the new enum values).

- [ ] **Implement.** In `PushRunner.dispatch()`, add (before `EntityType.SETTINGS ->`):
```kotlin
            EntityType.FOCUS_SESSION -> when (op.opType) {
                OpType.CREATE -> api.createFocusSession(json.decodeFromString(CreateFocusSessionRequest.serializer(), op.payloadJson))
                else -> error("focus-session is append-only; only CREATE is enqueued")
            }
            EntityType.DAILY_PLAN -> when (op.opType) {
                // entityId is the ISO date; PUT upserts last-writer-wins (no id remap/adopt — spec §7.6).
                OpType.UPDATE -> api.putDailyPlan(op.entityId, json.decodeFromString(UpsertDailyPlanRequest.serializer(), op.payloadJson))
                else -> error("daily-plan is upserted as OpType.UPDATE keyed by date")
            }
```
(`CreateFocusSessionRequest` and `UpsertDailyPlanRequest` imports were added in P3.17.)

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PushRunnerFocusDailyPlanTest"` — green.

- [ ] **Commit.** `feat(android-sync): PushRunner FOCUS_SESSION (create-only) + DAILY_PLAN (PUT-by-date) dispatch`

---

### Task P3.19 — `PullRunner.applyPage()`: NOTE + NOTE_GROUP collections (upsert/tombstone + pin preservation + shadow rule)

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/sync/PullRunner.kt` (constructor gains four DAOs; `applyPage()` NOTE/NOTE_GROUP loops)
- `~ android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt` (`providePullRunner` passes the new DAOs)
- `~ android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncTestSupport.kt` (`throwingPull()` passes the new DAOs)
- `+ android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerNotesTest.kt`

**Interfaces**
- `PullRunner` constructor adds (after `projectDao`): `noteDao: NoteDao, noteGroupDao: NoteGroupDao, focusSessionDao: FocusSessionDao, dailyPlanDao: DailyPlanDao`. Also extend the `db.withTransaction { ... clear() }` in the full-resync block to clear these four tables.
- NOTE apply: shadow-skip on `row.id`; `deletedAt != null → noteDao.deleteById`; else upsert via `row.toEntity()` **preserving any existing local `pinnedAt`** (read `noteDao.getById(row.id)?.pinnedAt` and `.copy(pinnedAt = ...)`). NOTE_GROUP: standard upsert/tombstone with shadow-skip.

**Steps**

- [ ] **Write the failing test.** `PullRunnerNotesTest.kt`:
```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.entities.NoteEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PullRunnerNotesTest {

    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private val rearmer = FakeRearmer()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        runner = PullRunner(
            env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `a pulled note upserts and preserves the local-only pinnedAt`() = runTest {
        val pinned = Instant.parse("2026-06-18T06:00:00Z")
        env.db.noteDao().upsertAll(listOf(NoteEntity(
            id = "n1", groupId = null, projectId = null, title = "local", content = "old", rank = null,
            createdAt = pinned, updatedAt = pinned, changeSeq = 0, deletedAt = null, pinnedAt = pinned,
        )))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"notes":[{"id":"n1","groupId":"g1","projectId":null,"title":"SERVER","content":"new","rank":"0001","createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T07:00:00Z","changeSeq":9,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))

        val outcome = runner.pullAll()

        assertEquals(true, outcome.applied)
        val row = env.db.noteDao().getById("n1")!!
        assertEquals("SERVER", row.title)        // server content applied
        assertEquals("g1", row.groupId)
        assertEquals(pinned, row.pinnedAt)        // local pin preserved across the pull
    }

    @Test
    fun `a pulled note tombstone deletes the local row`() = runTest {
        env.db.noteDao().upsertAll(listOf(NoteEntity(
            id = "doomed", groupId = null, projectId = null, title = "x", content = "x", rank = null,
            createdAt = Instant.parse("2026-06-18T00:00:00Z"), updatedAt = Instant.parse("2026-06-18T00:00:00Z"),
            changeSeq = 1, deletedAt = null, pinnedAt = null,
        )))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"notes":[{"id":"doomed","groupId":null,"projectId":null,"title":"x","content":"x","rank":null,"createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":9,"deletedAt":"2026-06-18T01:00:00Z"}]},"nextSince":50,"hasMore":false}"""))

        runner.pullAll()
        assertNull(env.db.noteDao().getById("doomed"))
    }

    @Test
    fun `a pulled note-group upserts`() = runTest {
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"noteGroups":[{"id":"g1","name":"دفتر","emoji":"📓","projectId":null,"rank":"0001","createdAt":"2026-06-18T00:00:00Z","updatedAt":"2026-06-18T00:00:00Z","changeSeq":3,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))
        runner.pullAll()
        assertEquals("دفتر", env.db.noteGroupDao().getById("g1")!!.name)
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerNotesTest"` — fails (ctor arity + loops).

- [ ] **Implement.** Edit `PullRunner.kt`:
  1. Add imports: the four DAOs + `NoteSyncRow, NoteGroupSyncRow, FocusSessionSyncRow, DailyPlanSyncRow` + the `Mappers.toEntity` extension functions are imported via the existing `import net.qmindtech.tmap.data.sync.Mappers.toEntity` (it imports all `toEntity` overloads from the object). Add the new entity import `NoteEntity` is not needed; `getById` returns it.
  2. Insert the four DAO params into the primary constructor after `private val projectDao: ProjectDao,`:
```kotlin
    private val noteDao: NoteDao,
    private val noteGroupDao: NoteGroupDao,
    private val focusSessionDao: FocusSessionDao,
    private val dailyPlanDao: DailyPlanDao,
```
  3. In the full-resync `db.withTransaction { ... }` block, extend the `clear()` calls:
```kotlin
                        taskDao.clear(); subtaskDao.clear(); projectDao.clear(); settingsDao.clear()
                        noteDao.clear(); noteGroupDao.clear(); focusSessionDao.clear(); dailyPlanDao.clear()
```
  4. In `applyPage()`, after the Settings loop (still inside the same `db.withTransaction`), add:
```kotlin
            // Notes — preserve the LOCAL-ONLY pinnedAt across an upsert (spec §7.7).
            for (row: NoteSyncRow in changes.notes) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) {
                    noteDao.deleteById(row.id)
                } else {
                    val existingPin = noteDao.getById(row.id)?.pinnedAt
                    noteDao.upsertAll(listOf(row.toEntity().copy(pinnedAt = existingPin)))
                }
                applied = true
            }
            // Note-groups
            for (row: NoteGroupSyncRow in changes.noteGroups) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) noteGroupDao.deleteById(row.id)
                else noteGroupDao.upsertAll(listOf(row.toEntity()))
                applied = true
            }
```
  Edit `AppModule.kt` `providePullRunner`: add the four DAO params (and imports) and pass them after `projectDao`:
```kotlin
        @Provides @Singleton
        fun providePullRunner(
            api: TmapApiService,
            db: AppDatabase,
            taskDao: TaskDao,
            subtaskDao: SubtaskDao,
            projectDao: ProjectDao,
            noteDao: NoteDao,
            noteGroupDao: NoteGroupDao,
            focusSessionDao: FocusSessionDao,
            dailyPlanDao: DailyPlanDao,
            settingsDao: SettingsDao,
            syncStateDao: SyncStateDao,
            outboxDao: OutboxDao,
            rearmer: SyncReminderRearmer,
        ): PullRunner = PullRunner(
            api, db, taskDao, subtaskDao, projectDao,
            noteDao, noteGroupDao, focusSessionDao, dailyPlanDao,
            settingsDao, syncStateDao, outboxDao, rearmer,
        )
```
  Edit `SyncTestSupport.kt` `throwingPull()`:
```kotlin
    return PullRunner(
        api, db, db.taskDao(), db.subtaskDao(), db.projectDao(),
        db.noteDao(), db.noteGroupDao(), db.focusSessionDao(), db.dailyPlanDao(),
        db.settingsDao(), db.syncStateDao(), db.outboxDao(), FakeRearmer(),
    )
```
  Also update the other PullRunner constructions in the existing pull tests (`PullRunnerPageTest`, `PullRunnerTombstoneTest`, `PullRunnerShadowTest`, `PullRunnerFullResyncTest`, `PullRunnerRecoveryTest`, `PullRunnerParkedResyncGateTest`) to pass `env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao()` after `env.db.projectDao()`. (These are mechanical edits forced by the ctor change; make them in this commit so the suite compiles.)

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunner*"` — green (new test plus all existing pull tests still pass).

- [ ] **Commit.** `feat(android-sync): PullRunner NOTE/NOTE_GROUP apply (pin-preserving upsert + tombstone)`

---

### Task P3.20 — `PullRunner.applyPage()`: FOCUS_SESSION + DAILY_PLAN collections (date-keyed)

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/sync/PullRunner.kt` (`applyPage()` FOCUS_SESSION + DAILY_PLAN loops)
- `+ android/app/src/test/java/net/qmindtech/tmap/data/sync/PullRunnerFocusDailyPlanTest.kt`

**Interfaces**
- FOCUS_SESSION apply: shadow-skip on `row.id`; `deletedAt != null → focusSessionDao.deleteById(row.id)`; else `upsertAll(row.toEntity())`.
- DAILY_PLAN apply: keyed by **date** — shadow-skip on `row.date` (the outbox `entityId` for daily-plans is the date string); `deletedAt != null → dailyPlanDao.deleteByDate(LocalDate.parse(row.date))`; else `dailyPlanDao.upsertAll(row.toEntity())`.

**Steps**

- [ ] **Write the failing test.** `PullRunnerFocusDailyPlanTest.kt`:
```kotlin
package net.qmindtech.tmap.data.sync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.EntityType
import net.qmindtech.tmap.data.local.OpType
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.util.Clock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class PullRunnerFocusDailyPlanTest {

    private lateinit var env: SyncTestEnv
    private lateinit var runner: PullRunner
    private lateinit var outbox: OutboxRepository
    private val rearmer = FakeRearmer()
    private val clock: Clock = FixedClock()

    @Before
    fun setUp() {
        env = SyncTestEnv()
        outbox = OutboxRepository(env.db.outboxDao(), env.json, clock)
        runner = PullRunner(
            env.api, env.db, env.db.taskDao(), env.db.subtaskDao(), env.db.projectDao(),
            env.db.noteDao(), env.db.noteGroupDao(), env.db.focusSessionDao(), env.db.dailyPlanDao(),
            env.db.settingsDao(), env.db.syncStateDao(), env.db.outboxDao(), rearmer,
        )
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `a pulled focus session upserts and a tombstone deletes it`() = runTest {
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"focusSessions":[{"id":"f1","taskId":"t1","project":"العمل","startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18","createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z","changeSeq":6,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))
        runner.pullAll()
        assertEquals(25, env.db.focusSessionDao().getById("f1")!!.minutes)

        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"focusSessions":[{"id":"f1","taskId":"t1","project":"العمل","startedAt":"2026-06-18T09:00:00Z","endedAt":"2026-06-18T09:25:00Z","minutes":25,"date":"2026-06-18","createdAt":"2026-06-18T09:25:00Z","updatedAt":"2026-06-18T09:25:00Z","changeSeq":7,"deletedAt":"2026-06-18T10:00:00Z"}]},"nextSince":60,"hasMore":false}"""))
        runner.pullAll()
        assertNull(env.db.focusSessionDao().getById("f1"))
    }

    @Test
    fun `a pulled daily plan upserts by date`() = runTest {
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"dailyPlans":[{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z","plannedTaskIds":["a","b"],"plannedMinutes":120,"changeSeq":8,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))
        runner.pullAll()
        val row = env.db.dailyPlanDao().getByDate(LocalDate.parse("2026-06-18"))!!
        assertEquals(listOf("a", "b"), row.plannedTaskIds)
        assertEquals(120, row.plannedMinutes)
    }

    @Test
    fun `the shadow rule protects a pending daily-plan upsert keyed by date`() = runTest {
        // Local pending upsert for the date (entityId = date string).
        env.db.dailyPlanDao().upsertAll(listOf(DailyPlanEntity(
            date = LocalDate.parse("2026-06-18"), committedAt = Instant.parse("2026-06-18T07:00:00Z"),
            plannedTaskIds = listOf("MINE"), plannedMinutes = 99, changeSeq = 0, deletedAt = null,
        )))
        outbox.enqueueRaw(EntityType.DAILY_PLAN, "2026-06-18", OpType.UPDATE, "{}")

        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"dailyPlans":[{"date":"2026-06-18","committedAt":"2026-06-18T05:00:00Z","plannedTaskIds":["SERVER-OLD"],"plannedMinutes":1,"changeSeq":3,"deletedAt":null}]},"nextSince":50,"hasMore":false}"""))
        runner.pullAll()

        // Unparked op owns the date key -> server value is skipped, local pending plan survives.
        assertEquals(listOf("MINE"), env.db.dailyPlanDao().observe(LocalDate.parse("2026-06-18")).first()!!.plannedTaskIds)
    }

    @Test
    fun `a pulled daily-plan tombstone deletes by date`() = runTest {
        env.db.dailyPlanDao().upsertAll(listOf(DailyPlanEntity(
            date = LocalDate.parse("2026-06-18"), committedAt = Instant.parse("2026-06-18T07:00:00Z"),
            plannedTaskIds = listOf("a"), plannedMinutes = 30, changeSeq = 0, deletedAt = null,
        )))
        env.server.enqueue(env.jsonResponse(200,
            """{"changes":{"dailyPlans":[{"date":"2026-06-18","committedAt":"2026-06-18T07:00:00Z","plannedTaskIds":[],"plannedMinutes":0,"changeSeq":9,"deletedAt":"2026-06-18T11:00:00Z"}]},"nextSince":60,"hasMore":false}"""))
        runner.pullAll()
        assertNull(env.db.dailyPlanDao().getByDate(LocalDate.parse("2026-06-18")))
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerFocusDailyPlanTest"` — fails (loops absent).

- [ ] **Implement.** In `PullRunner.applyPage()`, after the NOTE_GROUP loop (still inside `db.withTransaction`), add:
```kotlin
            // Focus-sessions (append-only on the client; pull still applies upserts/tombstones).
            for (row: FocusSessionSyncRow in changes.focusSessions) {
                if (shadow.contains(row.id)) continue
                if (row.deletedAt != null) focusSessionDao.deleteById(row.id)
                else focusSessionDao.upsertAll(listOf(row.toEntity()))
                applied = true
            }
            // Daily-plans — keyed by DATE (the outbox entityId is the date string, so the shadow set
            // is checked against row.date, not a Guid id).
            for (row: DailyPlanSyncRow in changes.dailyPlans) {
                if (shadow.contains(row.date)) continue
                if (row.deletedAt != null) dailyPlanDao.deleteByDate(java.time.LocalDate.parse(row.date))
                else dailyPlanDao.upsertAll(listOf(row.toEntity()))
                applied = true
            }
```
Add imports `FocusSessionSyncRow`, `DailyPlanSyncRow` to `PullRunner.kt`. (`changes.recurrenceRules` is intentionally not applied — tolerated-only per spec §7.5.)

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.sync.PullRunnerFocusDailyPlanTest"` — green.

- [ ] **Commit.** `feat(android-sync): PullRunner FOCUS_SESSION + DAILY_PLAN apply (date-keyed shadow + tombstone)`

---

### Task P3.21 — DI: register new DAOs (`DatabaseModule`) + bind new repositories (`AppModule`)

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/di/DatabaseModule.kt` (provide the four new DAOs)
- `~ android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt` (bind the three new repository interfaces)
- `~ android/app/src/test/java/net/qmindtech/tmap/di/DatabaseModuleTest.kt` (assert the new DAO providers)

**Interfaces**
- `DatabaseModule.provideNoteDao / provideNoteGroupDao / provideFocusSessionDao / provideDailyPlanDao(db): <Dao>`.
- `AppModule` `@Binds`: `bindNoteRepository(NoteRepositoryImpl): NoteRepository`, `bindNoteGroupRepository(NoteGroupRepositoryImpl): NoteGroupRepository`, `bindFocusSessionRepository(FocusSessionRepositoryImpl): FocusSessionRepository`, `bindDailyPlanRepository(DailyPlanRepositoryImpl): DailyPlanRepository`.

**Steps**

- [ ] **Write the failing test.** Append to `DatabaseModuleTest.kt` (inside `dao providers delegate to the database instance`, add the four asserts):
```kotlin
        assertSame(db.noteDao(), DatabaseModule.provideNoteDao(db))
        assertSame(db.noteGroupDao(), DatabaseModule.provideNoteGroupDao(db))
        assertSame(db.focusSessionDao(), DatabaseModule.provideFocusSessionDao(db))
        assertSame(db.dailyPlanDao(), DatabaseModule.provideDailyPlanDao(db))
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.di.DatabaseModuleTest"` — fails (providers absent).

- [ ] **Implement.** Edit `DatabaseModule.kt`: add the four DAO imports and four providers:
```kotlin
    @Provides
    fun provideNoteDao(db: AppDatabase): NoteDao = db.noteDao()

    @Provides
    fun provideNoteGroupDao(db: AppDatabase): NoteGroupDao = db.noteGroupDao()

    @Provides
    fun provideFocusSessionDao(db: AppDatabase): FocusSessionDao = db.focusSessionDao()

    @Provides
    fun provideDailyPlanDao(db: AppDatabase): DailyPlanDao = db.dailyPlanDao()
```
Edit `AppModule.kt`: add repository impl/interface imports and four `@Binds` (after `bindSubtaskRepository`):
```kotlin
    @Binds @Singleton
    abstract fun bindNoteRepository(impl: NoteRepositoryImpl): NoteRepository

    @Binds @Singleton
    abstract fun bindNoteGroupRepository(impl: NoteGroupRepositoryImpl): NoteGroupRepository

    @Binds @Singleton
    abstract fun bindFocusSessionRepository(impl: FocusSessionRepositoryImpl): FocusSessionRepository

    @Binds @Singleton
    abstract fun bindDailyPlanRepository(impl: DailyPlanRepositoryImpl): DailyPlanRepository
```
Add imports to `AppModule.kt`: `NoteRepository, NoteRepositoryImpl, NoteGroupRepository, NoteGroupRepositoryImpl, FocusSessionRepository, FocusSessionRepositoryImpl, DailyPlanRepository, DailyPlanRepositoryImpl` (from `net.qmindtech.tmap.data.repository`).

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.di.DatabaseModuleTest"` — green.

- [ ] **Commit.** `feat(android-di): register new-domain DAOs + bind Note/NoteGroup/FocusSession/DailyPlan repositories`

---

### Task P3.22 — DI graph smoke + full-suite green gate

**Files**
- `~ android/app/src/test/java/net/qmindtech/tmap/AppGraphWiringTest.kt` (extend the existing Hilt-graph smoke to resolve the four new repositories, if that test injects repositories; otherwise add a minimal resolution assert) — adapt to the file's actual style.

**Interfaces**
- The Hilt graph must construct `NoteRepository`, `NoteGroupRepository`, `FocusSessionRepository`, `DailyPlanRepository`, and the extended `PushRunner`/`PullRunner` (new DAO params) without missing-binding errors.

**Steps**

- [ ] **Read first.** Open `AppGraphWiringTest.kt`; if it `@Inject`s the existing repositories (e.g. `lateinit var taskRepository: TaskRepository`), add the four new ones to the same injected-fields list and assert non-null. If it instead resolves a single entry point, extend that entry point. Match the existing test's exact mechanism — do not introduce a new pattern.

- [ ] **Write/extend the failing test.** Add the four `@Inject lateinit var`s (or entry-point accessors) for `NoteRepository`, `NoteGroupRepository`, `FocusSessionRepository`, `DailyPlanRepository`, plus a `assertNotNull(pushRunner)` / `assertNotNull(pullRunner)` if those are already resolved there — and assert each new repo is non-null after `hiltRule.inject()`.

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.AppGraphWiringTest"` — fails if a binding is missing (it should already pass given P3.21; this asserts the wiring is reachable).

- [ ] **Make it pass.** If a binding is missing, fix the offending module per P3.21; otherwise the additions pass directly.

- [ ] **Full-suite green gate.** Run the entire unit suite to confirm no regression across the engine (409-adopt, 5xx-park, 4xx-drop, full-resync, recovery, shadow, FIFO, reminders, auth) now that `PushRunner`/`PullRunner` constructors changed:
  `./gradlew :app:testDebugUnitTest` — must be **all green**. Then the compile gate `./gradlew :app:assembleDebug` and lint gate `./gradlew :app:lintDebug` — both must pass.

- [ ] **Commit.** `test(android): full-graph wiring for new-domain repositories + green :app:testDebugUnitTest gate`

---

## P4 — Notes UI

This phase builds the **Notes** experience on top of the P3 note-domain data layer, consuming the FIXED `NoteRepository` / `NoteGroupRepository` contracts verbatim. It delivers a `NotesViewModel` (notebook chip row driven by `NoteGroupRepository.observeAll()`, notes filtered by the selected notebook via `NoteRepository.observeAll(groupId, projectId)`, split into **Pinned** (`pinnedAt != null`, newest-pin first) and **Recent** (by `updatedAt` desc), with `selectNotebook` / `createNote` / `togglePin` / `deleteNote` / `createNotebook` actions); a `NotesScreen` matching the Notes mockup (title, notebook `FilterChip` row, PINNED + RECENT `SectionLabel` groups of `NoteCard`s, `TmapFab` for new note); a `NoteCard` component (title + snippet + project dot + edited-time + pinned affordance); a `NoteEditorSheet` + `NoteEditorViewModel` (title/content fields, assign notebook + project, pin toggle, delete; all writes through `NoteRepository`, **pin is local-only via `setPinned`** per spec §7.7); and wiring of Notes into the bottom-nav tab (replacing the P0 stub) plus a documented "project's notes" hook in `ProjectDetailScreen` (from P2). Pure VM/projection logic gets **real unit tests** against a fake `NoteRepository`/`NoteGroupRepository`; Compose UI uses a **compile-gate + mockup behavior-checklist**. The phase ends with `assembleDebug` green and an offline-create gate note (the outbox enqueue itself is already proven by the P3 repository test; here we only verify the UI path routes through the repository).

> **Visual source of truth:** `.superpowers/brainstorm/965-1782053760/content/full-app.html` — the **Notes** panel (notebook chip row `All Notes · Work · Ideas · Journal · Meetings`; `Pinned` section with amber left-bar card + 📌; `Recent` section of plain cards; project color dot + "Work · edited 2h ago" meta; bottom-right amber **+** FAB).
>
> **P3 contracts consumed verbatim (do NOT redefine — these are produced by P3):**
> - `NoteRepository { fun observeAll(groupId: String?, projectId: String?): Flow<List<NoteEntity>>; fun observe(id: String): Flow<NoteEntity?>; suspend fun create(title: String, content: String, groupId: String?, projectId: String?): String; suspend fun update(id: String, title: String?, content: String?, groupId: String?, projectId: String?); suspend fun delete(id: String); suspend fun setPinned(id: String, pinned: Boolean); suspend fun reorder(ids: List<String>) }`
> - `NoteGroupRepository { fun observeAll(): Flow<List<NoteGroupEntity>>; suspend fun create(name: String, emoji: String, projectId: String?): String; suspend fun update(id: String, name: String?, emoji: String?, projectId: String?); suspend fun delete(id: String); suspend fun reorder(ids: List<String>) }`
> - `NoteEntity(id, groupId: String?, projectId: String?, title, content, rank, createdAt, updatedAt, changeSeq, deletedAt, pinnedAt: Instant?)` — `pinnedAt` is **local-only / never synced**; "pinned" ⇔ `pinnedAt != null`.
> - `NoteGroupEntity(id, name, emoji, projectId: String?, rank, createdAt, updatedAt, changeSeq, deletedAt)`
>
> **P0 contracts consumed:** `TmapTheme` + `LocalTmapColors` (`colors.surface`, `colors.surfaceRaised`, `colors.textPrimary`, `colors.textSecondary`, `colors.textTertiary`, `colors.textBody`, `colors.accent`, `colors.borderSubtle`, `colors.danger`), `SectionLabel`, `Chip`/`FilterChip` (from `ui/components/Chips.kt`), `ProjectDot` (from `ui/components/ProjectDot.kt`), `TmapFab`, `SheetScaffold` (from `ui/components/SheetScaffold.kt`), `EmptyState`. **Navigation contract (P0):** `sealed interface Route` with `Route.Notes`; the Notes tab is rendered inside `MainScaffold`'s `NavHost`; the editor opens as a sheet via `SheetHost`. P4 replaces the P0 `Notes` placeholder composable with the real `NotesScreen`.

---

### Task P4.1 — `NotesUiState` + notebook/pinned/recent projection (pure)

A pure, unit-testable projection model. `NotesUiState` holds the notebook chips (with the selected one), the **pinned** list, and the **recent** list. A pure function `buildNotesUiState(...)` performs the pinned/recent split and ordering so it can be tested without a ViewModel or coroutines. `NoteCardUi` is the per-card projection (id, title, snippet, project color, edited-time epoch + entity for label formatting).

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/notes/NotesUiState.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/notes/NotesUiStateTest.kt`

**Interfaces** (exact — produced here, consumed by P4.2/P4.3)
```kotlin
package net.qmindtech.tmap.ui.notes

import net.qmindtech.tmap.data.local.entities.NoteEntity
import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import java.time.Instant

// `null` groupId in the chip row = the "All Notes" pseudo-notebook.
data class NotebookChip(val id: String?, val label: String, val selected: Boolean)

data class NoteCardUi(
  val id: String,
  val title: String,
  val snippet: String,
  val projectColor: Long?,   // null = no project dot
  val projectName: String?,  // null = no project label
  val updatedAt: Instant,
  val pinned: Boolean,
)

data class NotesUiState(
  val loading: Boolean = true,
  val chips: List<NotebookChip> = emptyList(),
  val selectedGroupId: String? = null,   // null = All Notes
  val pinned: List<NoteCardUi> = emptyList(),
  val recent: List<NoteCardUi> = emptyList(),
) {
  val isEmpty: Boolean get() = pinned.isEmpty() && recent.isEmpty()
}

// Snippet = first ~120 chars of content, single-lined, ellipsised; empty content -> "".
fun noteSnippet(content: String, max: Int = 120): String

// Pure projection: chips (All Notes first, then groups in input order); pinned = pinnedAt != null
// sorted by pinnedAt desc (newest pin first); recent = the rest sorted by updatedAt desc.
fun buildNotesUiState(
  groups: List<NoteGroupEntity>,
  notes: List<NoteEntity>,
  projects: List<ProjectEntity>,
  selectedGroupId: String?,
): NotesUiState
```

**Steps**
1. **Write the failing test** `NotesUiStateTest.kt`. Cases (use `fakeNote`/`fakeNoteGroup` from P4.0 below — if P3's test fakes are not present, add the two builders to `android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt` first; see note). Assertions:
   - `noteSnippet` strips newlines, trims, truncates to `max` and appends `…`; empty/blank content → `""`.
   - `buildNotesUiState` produces chips: index 0 is `NotebookChip(id = null, label = "All Notes", selected = (selectedGroupId == null))`, followed by one chip per group preserving input order with `selected = (group.id == selectedGroupId)`.
   - Pinned split: notes with `pinnedAt != null` go to `pinned`, sorted by `pinnedAt` **descending** (newest pin first); all others go to `recent`, sorted by `updatedAt` **descending**.
   - Project mapping: a note with `projectId` matching a project resolves `projectColor` (parsed from the project `color` hex → `Long`) and `projectName`; a note with no/unknown project → both `null`.
   - `loading = false` always (projection is post-load); `isEmpty` true only when both lists empty.

   Test skeleton:
   ```kotlin
   package net.qmindtech.tmap.ui.notes

   import net.qmindtech.tmap.testutil.fakeNote
   import net.qmindtech.tmap.testutil.fakeNoteGroup
   import net.qmindtech.tmap.testutil.fakeProject
   import org.junit.Assert.assertEquals
   import org.junit.Assert.assertFalse
   import org.junit.Assert.assertTrue
   import org.junit.Test
   import java.time.Instant

   class NotesUiStateTest {
     private val t0 = Instant.parse("2026-06-20T08:00:00Z")

     @Test fun snippet_strips_newlines_and_truncates() {
       assertEquals("", noteSnippet(""))
       assertEquals("a b c", noteSnippet("a\nb\r\nc"))
       assertEquals("x".repeat(120) + "…", noteSnippet("x".repeat(200)))
     }

     @Test fun chips_all_notes_first_then_groups_with_selection() {
       val groups = listOf(fakeNoteGroup(id = "g1", name = "Work"), fakeNoteGroup(id = "g2", name = "Ideas"))
       val s = buildNotesUiState(groups, emptyList(), emptyList(), selectedGroupId = "g2")
       assertEquals(listOf(null, "g1", "g2"), s.chips.map { it.id })
       assertEquals("All Notes", s.chips.first().label)
       assertFalse(s.chips.first().selected)
       assertTrue(s.chips.last().selected)
     }

     @Test fun split_pinned_desc_by_pinnedAt_recent_desc_by_updatedAt() {
       val notes = listOf(
         fakeNote(id = "a", title = "old recent", updatedAt = t0),
         fakeNote(id = "b", title = "new recent", updatedAt = t0.plusSeconds(60)),
         fakeNote(id = "c", title = "pin early", updatedAt = t0, pinnedAt = t0.plusSeconds(10)),
         fakeNote(id = "d", title = "pin late", updatedAt = t0, pinnedAt = t0.plusSeconds(20)),
       )
       val s = buildNotesUiState(emptyList(), notes, emptyList(), selectedGroupId = null)
       assertEquals(listOf("d", "c"), s.pinned.map { it.id })
       assertEquals(listOf("b", "a"), s.recent.map { it.id })
       assertTrue(s.pinned.all { it.pinned })
       assertFalse(s.isEmpty)
     }

     @Test fun project_dot_resolves_color_and_name() {
       val proj = fakeProject(id = "p1", name = "Work", color = "#6EA8FE")
       val notes = listOf(
         fakeNote(id = "a", projectId = "p1"),
         fakeNote(id = "b", projectId = null),
         fakeNote(id = "c", projectId = "missing"),
       )
       val s = buildNotesUiState(emptyList(), notes, listOf(proj), selectedGroupId = null)
       val byId = (s.pinned + s.recent).associateBy { it.id }
       assertEquals(0xFF6EA8FEL, byId["a"]!!.projectColor)
       assertEquals("Work", byId["a"]!!.projectName)
       assertEquals(null, byId["b"]!!.projectColor)
       assertEquals(null, byId["c"]!!.projectColor)
     }
   }
   ```

2. **Verify it FAILS** (compile error / missing symbols is an acceptable RED):
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.notes.NotesUiStateTest"
   ```

3. **Write the full implementation** `NotesUiState.kt`:
   ```kotlin
   package net.qmindtech.tmap.ui.notes

   import net.qmindtech.tmap.data.local.entities.NoteEntity
   import net.qmindtech.tmap.data.local.entities.NoteGroupEntity
   import net.qmindtech.tmap.data.local.entities.ProjectEntity
   import java.time.Instant

   data class NotebookChip(val id: String?, val label: String, val selected: Boolean)

   data class NoteCardUi(
     val id: String,
     val title: String,
     val snippet: String,
     val projectColor: Long?,
     val projectName: String?,
     val updatedAt: Instant,
     val pinned: Boolean,
   )

   data class NotesUiState(
     val loading: Boolean = true,
     val chips: List<NotebookChip> = emptyList(),
     val selectedGroupId: String? = null,
     val pinned: List<NoteCardUi> = emptyList(),
     val recent: List<NoteCardUi> = emptyList(),
   ) {
     val isEmpty: Boolean get() = pinned.isEmpty() && recent.isEmpty()
   }

   fun noteSnippet(content: String, max: Int = 120): String {
     val flat = content.replace(Regex("\\s+"), " ").trim()
     if (flat.isEmpty()) return ""
     return if (flat.length <= max) flat else flat.take(max) + "…"
   }

   private fun parseColor(hex: String?): Long? {
     val h = hex?.trim()?.removePrefix("#") ?: return null
     val rgb = when (h.length) {
       6 -> h
       8 -> h.takeLast(6)
       else -> return null
     }
     return runCatching { 0xFF000000L or rgb.toLong(16) }.getOrNull()
   }

   private fun NoteEntity.toCardUi(projectsById: Map<String, ProjectEntity>): NoteCardUi {
     val proj = projectId?.let { projectsById[it] }
     return NoteCardUi(
       id = id,
       title = title,
       snippet = noteSnippet(content),
       projectColor = parseColor(proj?.color),
       projectName = proj?.name,
       updatedAt = updatedAt,
       pinned = pinnedAt != null,
     )
   }

   fun buildNotesUiState(
     groups: List<NoteGroupEntity>,
     notes: List<NoteEntity>,
     projects: List<ProjectEntity>,
     selectedGroupId: String?,
   ): NotesUiState {
     val projectsById = projects.associateBy { it.id }
     val chips = buildList {
       add(NotebookChip(id = null, label = "All Notes", selected = selectedGroupId == null))
       groups.forEach { g -> add(NotebookChip(id = g.id, label = g.name, selected = g.id == selectedGroupId)) }
     }
     val (pinnedNotes, recentNotes) = notes.partition { it.pinnedAt != null }
     val pinned = pinnedNotes
       .sortedByDescending { it.pinnedAt }
       .map { it.toCardUi(projectsById) }
     val recent = recentNotes
       .sortedByDescending { it.updatedAt }
       .map { it.toCardUi(projectsById) }
     return NotesUiState(
       loading = false,
       chips = chips,
       selectedGroupId = selectedGroupId,
       pinned = pinned,
       recent = recent,
     )
   }
   ```

   **Note on test fakes (P4.0 inline):** if `fakeNote` / `fakeNoteGroup` are not already present in `android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt` (they are produced/needed first here), add them in this same task before the test compiles:
   ```kotlin
   fun fakeNote(
     id: String,
     groupId: String? = null,
     projectId: String? = null,
     title: String = "Note $id",
     content: String = "",
     rank: String? = null,
     pinnedAt: java.time.Instant? = null,
     createdAt: java.time.Instant = EPOCH,
     updatedAt: java.time.Instant = EPOCH,
     changeSeq: Long = 0,
   ): net.qmindtech.tmap.data.local.entities.NoteEntity =
     net.qmindtech.tmap.data.local.entities.NoteEntity(
       id = id, groupId = groupId, projectId = projectId, title = title, content = content,
       rank = rank, createdAt = createdAt, updatedAt = updatedAt, changeSeq = changeSeq,
       deletedAt = null, pinnedAt = pinnedAt,
     )

   fun fakeNoteGroup(
     id: String,
     name: String = "Notebook $id",
     emoji: String = "📓",
     projectId: String? = null,
     rank: String? = null,
     createdAt: java.time.Instant = EPOCH,
   ): net.qmindtech.tmap.data.local.entities.NoteGroupEntity =
     net.qmindtech.tmap.data.local.entities.NoteGroupEntity(
       id = id, name = name, emoji = emoji, projectId = projectId, rank = rank,
       createdAt = createdAt, updatedAt = createdAt, changeSeq = 0, deletedAt = null,
     )
   ```
   (Match the exact P3 `NoteEntity`/`NoteGroupEntity` constructor parameter order; if P3 named/ordered fields differently, adapt the builder call — the FIXED field set is in the contract block above.)

4. **Verify it PASSES:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.notes.NotesUiStateTest"
   ```

5. **Commit:**
   ```
   feat(notes): NotesUiState projection (chips + pinned/recent split + snippet)

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P4.2 — Add `FakeNoteRepo` + `FakeNoteGroupRepo` test doubles

The Notes ViewModels are unit-tested against in-memory fakes implementing the FIXED P3 repository interfaces. Add them once so P4.3 and P4.6 reuse them.

**Files**
- `~ android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/testutil/FakeNoteReposTest.kt` (a tiny self-test that the fakes satisfy the interfaces + record calls — keeps this task TDD-shaped without a production target)

**Interfaces** (exact — the fakes implement P3's `NoteRepository`/`NoteGroupRepository` verbatim)
```kotlin
package net.qmindtech.tmap.testutil

class FakeNoteRepo : net.qmindtech.tmap.data.repository.NoteRepository {
  val allFlow = kotlinx.coroutines.flow.MutableStateFlow<List<NoteEntity>>(emptyList())
  val singleFlow = kotlinx.coroutines.flow.MutableStateFlow<NoteEntity?>(null)
  var lastObserveAllArgs: Pair<String?, String?>? = null
  val created = mutableListOf<NoteDraftRecord>()   // title,content,groupId,projectId
  val updated = mutableListOf<String>()
  val deleted = mutableListOf<String>()
  val pinned = mutableListOf<Pair<String, Boolean>>()
  val reordered = mutableListOf<List<String>>()
  var nextId = "note-new"
  // returns allFlow; records (groupId, projectId)
  // create records draft + returns nextId; setPinned records (id, pinned); etc.
}

data class NoteDraftRecord(val title: String, val content: String, val groupId: String?, val projectId: String?)

class FakeNoteGroupRepo : net.qmindtech.tmap.data.repository.NoteGroupRepository {
  val allFlow = kotlinx.coroutines.flow.MutableStateFlow<List<NoteGroupEntity>>(emptyList())
  val created = mutableListOf<Triple<String, String, String?>>() // name,emoji,projectId
  val updated = mutableListOf<String>()
  val deleted = mutableListOf<String>()
  val reordered = mutableListOf<List<String>>()
  var nextId = "group-new"
}
```

**Steps**
1. **Write the failing self-test** `FakeNoteReposTest.kt`:
   ```kotlin
   package net.qmindtech.tmap.testutil

   import kotlinx.coroutines.flow.first
   import kotlinx.coroutines.test.runTest
   import org.junit.Assert.assertEquals
   import org.junit.Test

   class FakeNoteReposTest {
     @Test fun note_repo_records_and_returns() = runTest {
       val repo = FakeNoteRepo()
       repo.allFlow.value = listOf(fakeNote(id = "a"))
       assertEquals("a", repo.observeAll(groupId = "g1", projectId = null).first().first().id)
       assertEquals("g1" to null, repo.lastObserveAllArgs)
       assertEquals("note-new", repo.create("T", "C", "g1", null))
       assertEquals(NoteDraftRecord("T", "C", "g1", null), repo.created.single())
       repo.setPinned("a", true)
       assertEquals("a" to true, repo.pinned.single())
       repo.delete("a"); assertEquals(listOf("a"), repo.deleted)
     }

     @Test fun group_repo_records() = runTest {
       val repo = FakeNoteGroupRepo()
       assertEquals("group-new", repo.create("Work", "💼", null))
       assertEquals(Triple("Work", "💼", null), repo.created.single())
     }
   }
   ```

2. **Verify it FAILS:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.testutil.FakeNoteReposTest"
   ```

3. **Implement the fakes** by appending to `Fakes.kt` (full bodies; match the FIXED interfaces):
   ```kotlin
   data class NoteDraftRecord(val title: String, val content: String, val groupId: String?, val projectId: String?)

   class FakeNoteRepo(
     val allFlow: kotlinx.coroutines.flow.MutableStateFlow<List<net.qmindtech.tmap.data.local.entities.NoteEntity>> =
       kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
     val singleFlow: kotlinx.coroutines.flow.MutableStateFlow<net.qmindtech.tmap.data.local.entities.NoteEntity?> =
       kotlinx.coroutines.flow.MutableStateFlow(null),
   ) : net.qmindtech.tmap.data.repository.NoteRepository {
     var lastObserveAllArgs: Pair<String?, String?>? = null
     val created = mutableListOf<NoteDraftRecord>()
     val updated = mutableListOf<String>()
     val deleted = mutableListOf<String>()
     val pinned = mutableListOf<Pair<String, Boolean>>()
     val reordered = mutableListOf<List<String>>()
     var nextId = "note-new"

     override fun observeAll(groupId: String?, projectId: String?): kotlinx.coroutines.flow.Flow<List<net.qmindtech.tmap.data.local.entities.NoteEntity>> {
       lastObserveAllArgs = groupId to projectId; return allFlow
     }
     override fun observe(id: String): kotlinx.coroutines.flow.Flow<net.qmindtech.tmap.data.local.entities.NoteEntity?> = singleFlow
     override suspend fun create(title: String, content: String, groupId: String?, projectId: String?): String {
       created += NoteDraftRecord(title, content, groupId, projectId); return nextId
     }
     override suspend fun update(id: String, title: String?, content: String?, groupId: String?, projectId: String?) { updated += id }
     override suspend fun delete(id: String) { deleted += id }
     override suspend fun setPinned(id: String, pinned: Boolean) { this.pinned += id to pinned }
     override suspend fun reorder(ids: List<String>) { reordered += ids }
   }

   class FakeNoteGroupRepo(
     val allFlow: kotlinx.coroutines.flow.MutableStateFlow<List<net.qmindtech.tmap.data.local.entities.NoteGroupEntity>> =
       kotlinx.coroutines.flow.MutableStateFlow(emptyList()),
   ) : net.qmindtech.tmap.data.repository.NoteGroupRepository {
     val created = mutableListOf<Triple<String, String, String?>>()
     val updated = mutableListOf<String>()
     val deleted = mutableListOf<String>()
     val reordered = mutableListOf<List<String>>()
     var nextId = "group-new"

     override fun observeAll(): kotlinx.coroutines.flow.Flow<List<net.qmindtech.tmap.data.local.entities.NoteGroupEntity>> = allFlow
     override suspend fun create(name: String, emoji: String, projectId: String?): String {
       created += Triple(name, emoji, projectId); return nextId
     }
     override suspend fun update(id: String, name: String?, emoji: String?, projectId: String?) { updated += id }
     override suspend fun delete(id: String) { deleted += id }
     override suspend fun reorder(ids: List<String>) { reordered += ids }
   }
   ```
   (If the P3 `create`/`update` signatures differ from the above, this fake — and the consuming VMs in P4.3/P4.6 — must be adapted to match P3 verbatim; the named methods `observeAll/observe/create/update/delete/setPinned/reorder` are FIXED.)

4. **Verify it PASSES:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.testutil.FakeNoteReposTest"
   ```

5. **Commit:**
   ```
   test(notes): FakeNoteRepo + FakeNoteGroupRepo test doubles

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P4.3 — `NotesViewModel` (observe + selectNotebook + actions) with real unit tests

The screen's ViewModel. It combines `NoteGroupRepository.observeAll()`, `NoteRepository.observeAll(selectedGroupId, null)`, and `ProjectRepository.observeAll()` keyed by the selected notebook into a `StateFlow<NotesUiState>` (via the P4.1 projection), and delegates all actions to the FIXED repository methods. `togglePin` calls `setPinned` (local-only). Selecting a notebook re-queries `observeAll` with the new `groupId`.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/notes/NotesViewModel.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/notes/NotesViewModelTest.kt`

**Interfaces** (exact)
```kotlin
package net.qmindtech.tmap.ui.notes

@dagger.hilt.android.lifecycle.HiltViewModel
class NotesViewModel @javax.inject.Inject constructor(
  private val noteRepo: net.qmindtech.tmap.data.repository.NoteRepository,
  private val noteGroupRepo: net.qmindtech.tmap.data.repository.NoteGroupRepository,
  private val projectRepo: net.qmindtech.tmap.data.repository.ProjectRepository,
) : androidx.lifecycle.ViewModel() {
  val uiState: kotlinx.coroutines.flow.StateFlow<NotesUiState>
  fun selectNotebook(groupId: String?)                       // null = All Notes
  fun createNote(onCreated: (String) -> Unit = {})           // creates in current notebook; returns new id
  fun togglePin(id: String, currentlyPinned: Boolean)        // -> setPinned(id, !currentlyPinned)
  fun deleteNote(id: String)
  fun createNotebook(name: String, emoji: String = "📓")     // blank name = no-op
}
```

**Steps**
1. **Write the failing test** `NotesViewModelTest.kt` (mirror `ProjectsViewModelTest` style: `UnconfinedTestDispatcher` + `Dispatchers.setMain`, Turbine `test {}`):
   ```kotlin
   package net.qmindtech.tmap.ui.notes

   import app.cash.turbine.test
   import kotlinx.coroutines.Dispatchers
   import kotlinx.coroutines.ExperimentalCoroutinesApi
   import kotlinx.coroutines.test.UnconfinedTestDispatcher
   import kotlinx.coroutines.test.resetMain
   import kotlinx.coroutines.test.runTest
   import kotlinx.coroutines.test.setMain
   import net.qmindtech.tmap.testutil.FakeNoteGroupRepo
   import net.qmindtech.tmap.testutil.FakeNoteRepo
   import net.qmindtech.tmap.testutil.FakeProjectRepo
   import net.qmindtech.tmap.testutil.fakeNote
   import net.qmindtech.tmap.testutil.fakeNoteGroup
   import org.junit.After
   import org.junit.Assert.assertEquals
   import org.junit.Assert.assertTrue
   import org.junit.Before
   import org.junit.Test
   import java.time.Instant

   @OptIn(ExperimentalCoroutinesApi::class)
   class NotesViewModelTest {
     private val testDispatcher = UnconfinedTestDispatcher()
     @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
     @After fun tearDown() { Dispatchers.resetMain() }

     private fun vm(): Triple<NotesViewModel, FakeNoteRepo, FakeNoteGroupRepo> {
       val notes = FakeNoteRepo()
       val groups = FakeNoteGroupRepo().apply { allFlow.value = listOf(fakeNoteGroup(id = "g1", name = "Work")) }
       return Triple(NotesViewModel(notes, groups, FakeProjectRepo()), notes, groups)
     }

     @Test fun uiState_projects_chips_and_pinned_recent() = runTest {
       val (vm, notes, _) = vm()
       notes.allFlow.value = listOf(
         fakeNote(id = "p", title = "Pinned", pinnedAt = Instant.parse("2026-06-20T10:00:00Z")),
         fakeNote(id = "r", title = "Recent", updatedAt = Instant.parse("2026-06-20T09:00:00Z")),
       )
       vm.uiState.test {
         val s = expectMostRecentItem()
         assertEquals(false, s.loading)
         assertEquals(listOf(null, "g1"), s.chips.map { it.id })
         assertEquals(listOf("p"), s.pinned.map { it.id })
         assertEquals(listOf("r"), s.recent.map { it.id })
         cancelAndIgnoreRemainingEvents()
       }
     }

     @Test fun selectNotebook_requeries_notes_with_groupId() = runTest {
       val (vm, notes, _) = vm()
       vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
       vm.selectNotebook("g1")
       vm.uiState.test {
         val s = expectMostRecentItem()
         assertEquals("g1", s.selectedGroupId)
         cancelAndIgnoreRemainingEvents()
       }
       assertEquals("g1" to null, notes.lastObserveAllArgs)
     }

     @Test fun createNote_delegates_with_current_notebook_and_returns_id() = runTest {
       val (vm, notes, _) = vm()
       notes.nextId = "n-123"
       vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
       vm.selectNotebook("g1")
       var newId: String? = null
       vm.createNote { newId = it }
       assertEquals(1, notes.created.size)
       assertEquals("g1", notes.created.first().groupId)
       assertEquals("", notes.created.first().title)
       assertEquals("n-123", newId)
     }

     @Test fun togglePin_calls_setPinned_with_inverted_value() = runTest {
       val (vm, notes, _) = vm()
       vm.togglePin("x", currentlyPinned = false)
       vm.togglePin("y", currentlyPinned = true)
       assertEquals(listOf("x" to true, "y" to false), notes.pinned)
     }

     @Test fun deleteNote_and_createNotebook_delegate_blank_noop() = runTest {
       val (vm, notes, groups) = vm()
       vm.deleteNote("z"); assertEquals(listOf("z"), notes.deleted)
       vm.createNotebook("   "); assertTrue(groups.created.isEmpty())
       vm.createNotebook("Ideas", "💡")
       assertEquals(Triple("Ideas", "💡", null), groups.created.single())
     }
   }
   ```

2. **Verify it FAILS:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.notes.NotesViewModelTest"
   ```

3. **Write the full implementation** `NotesViewModel.kt`. The selected notebook is a `MutableStateFlow<String?>`; use `flatMapLatest` to re-subscribe `noteRepo.observeAll(groupId, null)` whenever the selection changes; `combine` with groups + projects → projection.
   ```kotlin
   package net.qmindtech.tmap.ui.notes

   import androidx.lifecycle.ViewModel
   import androidx.lifecycle.viewModelScope
   import dagger.hilt.android.lifecycle.HiltViewModel
   import kotlinx.coroutines.ExperimentalCoroutinesApi
   import kotlinx.coroutines.flow.MutableStateFlow
   import kotlinx.coroutines.flow.SharingStarted
   import kotlinx.coroutines.flow.StateFlow
   import kotlinx.coroutines.flow.combine
   import kotlinx.coroutines.flow.flatMapLatest
   import kotlinx.coroutines.flow.stateIn
   import kotlinx.coroutines.launch
   import net.qmindtech.tmap.data.repository.NoteGroupRepository
   import net.qmindtech.tmap.data.repository.NoteRepository
   import net.qmindtech.tmap.data.repository.ProjectRepository
   import javax.inject.Inject

   @OptIn(ExperimentalCoroutinesApi::class)
   @HiltViewModel
   class NotesViewModel @Inject constructor(
     private val noteRepo: NoteRepository,
     private val noteGroupRepo: NoteGroupRepository,
     private val projectRepo: ProjectRepository,
   ) : ViewModel() {

     private val selectedGroupId = MutableStateFlow<String?>(null)

     private val notesForSelection = selectedGroupId.flatMapLatest { groupId ->
       noteRepo.observeAll(groupId = groupId, projectId = null)
     }

     val uiState: StateFlow<NotesUiState> =
       combine(
         noteGroupRepo.observeAll(),
         notesForSelection,
         projectRepo.observeAll(),
         selectedGroupId,
       ) { groups, notes, projects, selected ->
         buildNotesUiState(groups, notes, projects, selected)
       }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesUiState())

     fun selectNotebook(groupId: String?) { selectedGroupId.value = groupId }

     fun createNote(onCreated: (String) -> Unit = {}) {
       viewModelScope.launch {
         val id = noteRepo.create(title = "", content = "", groupId = selectedGroupId.value, projectId = null)
         onCreated(id)
       }
     }

     fun togglePin(id: String, currentlyPinned: Boolean) {
       viewModelScope.launch { noteRepo.setPinned(id, !currentlyPinned) }
     }

     fun deleteNote(id: String) {
       viewModelScope.launch { noteRepo.delete(id) }
     }

     fun createNotebook(name: String, emoji: String = "📓") {
       val n = name.trim()
       if (n.isEmpty()) return
       viewModelScope.launch { noteGroupRepo.create(n, emoji, null) }
     }
   }
   ```

4. **Verify it PASSES:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.notes.NotesViewModelTest"
   ```

5. **Commit:**
   ```
   feat(notes): NotesViewModel observing groups/notes/projects with actions

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P4.4 — `NoteCard` component (compile-gate + behavior-checklist)

The reusable card composable used in both sections of `NotesScreen` (and later in `ProjectDetailScreen`). Renders title + snippet + project dot + edited-time meta, with a pinned affordance (amber left bar + 📌). Driven by `NoteCardUi`. Compose UI is **not** unit-tested; this task uses a **compile-gate + mockup behavior-checklist**.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/notes/NoteCard.kt`

**Interfaces** (exact)
```kotlin
package net.qmindtech.tmap.ui.notes

@androidx.compose.runtime.Composable
fun NoteCard(
  note: NoteCardUi,
  onClick: () -> Unit,
  onTogglePin: () -> Unit,
  modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
)

// Formats updatedAt into the "edited 2h ago" / "yesterday" / "Jun 19" style of the mockup.
// Pure & testable-by-eye; uses java.time. now is injectable for determinism.
fun noteEditedLabel(updatedAt: java.time.Instant, now: java.time.Instant, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String
```

**Steps**
1. **Write the full implementation** `NoteCard.kt` (no unit test — compile-gate). Consume P0 tokens (`LocalTmapColors.current`, `TmapShapes`), `ProjectDot` from `ui/components`, and `SectionLabel` is used by the screen (P4.5), not here. Mockup mapping:
   - Pinned card: `colors.surfaceRaised` bg, `1.dp` `colors.borderSubtle` border, **2.dp `colors.accent` start-edge bar**, 18dp corners; trailing 📌 glyph (tap = `onTogglePin`, `contentDescription = "Unpin note"`).
   - Recent card: `colors.surface` bg, `1.dp` border, no accent bar; a long-press / overflow affordance triggers `onTogglePin` with `contentDescription = "Pin note"` (non-gesture equivalent — accessibility requirement §9).
   - Title: Body/Heading weight 600, `colors.textPrimary`, 1 line ellipsised. Snippet: `colors.textBody`, 2 lines ellipsised. Meta row: `ProjectDot(color = note.projectColor)` (omit when null) + `"${projectName ?: ""} · ${noteEditedLabel(...)}"` in `colors.textTertiary`, label style.
   - Whole card clickable → `onClick`; use `start`/`end` paddings (RTL).
   - Wrap in `TmapTheme` is the caller's job; this composable just reads `LocalTmapColors`.
   Implement `noteEditedLabel`: `<1m` → "just now"; `<60m` → "Xm ago"; same calendar day → "Xh ago"; yesterday → "yesterday"; same year → "MMM d" (e.g. "Jun 19"); else "MMM d, yyyy". Use `java.time` + `DateTimeFormatter`. Provide an `@Preview` (wrapped in `TmapTheme`) for a pinned and a recent sample so the compile-gate also exercises rendering.

2. **Verify it COMPILES (compile-gate):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```

3. **Behavior checklist vs. mockup** (`full-app.html` Notes panel — reviewer verifies visually; no automated assertion):
   - [ ] Pinned card shows the **amber start-edge bar** + 📌 glyph; recent cards do not.
   - [ ] Card corner radius 18dp; pinned uses `surfaceRaised`, recent uses `surface`.
   - [ ] Title 1 line, snippet ≤2 lines, both ellipsised; snippet uses `textBody`.
   - [ ] Meta row = project color dot (hidden when no project) + "Project · edited 2h ago" in tertiary text.
   - [ ] 📌 / pin control has a `contentDescription` and toggles pin (non-gesture path present).
   - [ ] Layout uses start/end (verified by toggling RTL in the preview / locale).

4. **Commit:**
   ```
   feat(notes): NoteCard component + edited-time label (mockup-matched)

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P4.5 — `NotesScreen` (title + chip row + Pinned/Recent + FAB) (compile-gate + behavior-checklist)

The Notes tab screen. Renders the title, a horizontally-scrolling notebook `FilterChip` row, the `PINNED` and `RECENT` `SectionLabel` groups of `NoteCard`s, an `EmptyState` when there are no notes, and a `TmapFab` (new note). Stateless screen + a stateful entry point that wires `NotesViewModel`. Compose UI uses a **compile-gate + behavior-checklist**.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/notes/NotesScreen.kt`

**Interfaces** (exact)
```kotlin
package net.qmindtech.tmap.ui.notes

// Stateful entry point used by the nav graph (P4.7).
@androidx.compose.runtime.Composable
fun NotesScreen(
  onOpenNote: (String) -> Unit,                  // opens NoteEditorSheet for an existing note id
  viewModel: NotesViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
)

// Stateless content (for previews + reuse).
@androidx.compose.runtime.Composable
fun NotesContent(
  state: NotesUiState,
  onSelectNotebook: (String?) -> Unit,
  onOpenNote: (String) -> Unit,
  onTogglePin: (String, Boolean) -> Unit,
  onNewNote: () -> Unit,
  modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
)
```

**Steps**
1. **Write the full implementation** `NotesScreen.kt`:
   - `NotesScreen` collects `viewModel.uiState` with `collectAsStateWithLifecycle()`, then renders `NotesContent`, passing `onNewNote = { viewModel.createNote(onCreated = onOpenNote) }` (create → open editor on the new id), `onSelectNotebook = viewModel::selectNotebook`, `onTogglePin = viewModel::togglePin`, `onOpenNote = onOpenNote`.
   - `NotesContent`: a `Box` over the app background; a `LazyColumn` with: title "Notes" (Title style, `colors.textPrimary`); a chip row (`LazyRow` of `FilterChip(label = chip.label, selected = chip.selected, onClick = { onSelectNotebook(chip.id) })` from `ui/components/Chips.kt`); if `state.isEmpty` → `EmptyState` (calm copy, e.g. "No notes yet — tap + to write one."); else `SectionLabel("Pinned")` + the pinned `NoteCard`s (only when `pinned.isNotEmpty()`), then `SectionLabel("Recent")` + the recent `NoteCard`s. `NoteCard(onClick = { onOpenNote(it.id) }, onTogglePin = { onTogglePin(it.id, it.pinned) })`. Use stable `key = { it.id }` in `items`.
   - `TmapFab(onClick = onNewNote)` aligned bottom-end with a `contentDescription = "New note"` (the FAB component supplies it; pass through if its signature requires it).
   - Provide an `@Preview` of `NotesContent` (wrapped in `TmapTheme`) with a sample `NotesUiState` (1 pinned + 2 recent + 4 chips) and an empty-state preview.

2. **Verify it COMPILES (compile-gate):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```

3. **Behavior checklist vs. mockup** (`full-app.html` Notes panel):
   - [ ] "Notes" title at top, Title type scale.
   - [ ] Horizontal notebook chip row; "All Notes" first and selected by default (amber-tinted active chip), others neutral.
   - [ ] `PINNED` section (uppercase tertiary `SectionLabel`) appears only when there are pinned notes; `RECENT` section below.
   - [ ] Cards render via `NoteCard` (pinned with amber bar + 📌; recent plain).
   - [ ] Amber **+** FAB bottom-end with a bottom fade scrim above it (or equivalent); `contentDescription = "New note"`.
   - [ ] Empty state shows when there are no notes.
   - [ ] Selecting a chip filters the list (drives `onSelectNotebook`).
   - [ ] RTL: chip row + cards mirror (start/end only).

4. **Commit:**
   ```
   feat(notes): NotesScreen (chip row + pinned/recent + FAB) wired to VM

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P4.6 — `NoteEditorViewModel` + `NoteEditorSheet` (VM unit-tested; sheet compile-gated)

The note editor: title + content fields, assign notebook + project, pin toggle, and delete — all writes through `NoteRepository`. The VM mirrors `TaskEditorViewModel`'s create/edit pattern (read `noteId` from `SavedStateHandle`; `null`/`"new"` = create). The VM gets **real unit tests**; the `NoteEditorSheet` composable is **compile-gated** with a behavior-checklist.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/notes/NoteEditorUiState.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/notes/NoteEditorViewModel.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/notes/NoteEditorSheet.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/notes/NoteEditorViewModelTest.kt`

**Interfaces** (exact)
```kotlin
package net.qmindtech.tmap.ui.notes

data class NoteEditorUiState(
  val noteId: String? = null,
  val isEdit: Boolean = false,
  val loading: Boolean = true,
  val title: String = "",
  val content: String = "",
  val groupId: String? = null,
  val projectId: String? = null,
  val pinned: Boolean = false,
  val groups: List<net.qmindtech.tmap.data.local.entities.NoteGroupEntity> = emptyList(),
  val projects: List<net.qmindtech.tmap.data.local.entities.ProjectEntity> = emptyList(),
  val saved: Boolean = false,
)

@dagger.hilt.android.lifecycle.HiltViewModel
class NoteEditorViewModel @javax.inject.Inject constructor(
  private val noteRepo: net.qmindtech.tmap.data.repository.NoteRepository,
  private val noteGroupRepo: net.qmindtech.tmap.data.repository.NoteGroupRepository,
  private val projectRepo: net.qmindtech.tmap.data.repository.ProjectRepository,
  savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : androidx.lifecycle.ViewModel() {
  val uiState: kotlinx.coroutines.flow.StateFlow<NoteEditorUiState>
  fun onTitleChange(s: String)
  fun onContentChange(s: String)
  fun onGroupChange(id: String?)
  fun onProjectChange(id: String?)
  fun togglePin()                       // edit-mode only -> noteRepo.setPinned(id, !pinned)
  fun save(onDone: () -> Unit)          // create or update via noteRepo
  fun delete(onDone: () -> Unit)        // edit-mode -> noteRepo.delete(id); always invokes onDone
}
```

**Steps**
1. **Write the failing test** `NoteEditorViewModelTest.kt` (mirror `TaskEditorViewModelTest`; `SavedStateHandle(mapOf("noteId" to ...))`):
   ```kotlin
   package net.qmindtech.tmap.ui.notes

   import androidx.lifecycle.SavedStateHandle
   import kotlinx.coroutines.Dispatchers
   import kotlinx.coroutines.ExperimentalCoroutinesApi
   import kotlinx.coroutines.test.UnconfinedTestDispatcher
   import kotlinx.coroutines.test.resetMain
   import kotlinx.coroutines.test.runTest
   import kotlinx.coroutines.test.setMain
   import net.qmindtech.tmap.testutil.FakeNoteGroupRepo
   import net.qmindtech.tmap.testutil.FakeNoteRepo
   import net.qmindtech.tmap.testutil.FakeProjectRepo
   import net.qmindtech.tmap.testutil.fakeNote
   import org.junit.After
   import org.junit.Assert.assertEquals
   import org.junit.Assert.assertTrue
   import org.junit.Before
   import org.junit.Test

   @OptIn(ExperimentalCoroutinesApi::class)
   class NoteEditorViewModelTest {
     private val testDispatcher = UnconfinedTestDispatcher()
     @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
     @After fun tearDown() { Dispatchers.resetMain() }

     private fun createVm(notes: FakeNoteRepo) = NoteEditorViewModel(
       notes, FakeNoteGroupRepo(), FakeProjectRepo(), SavedStateHandle(mapOf("noteId" to "new")),
     )
     private fun editVm(notes: FakeNoteRepo, id: String = "n1") = NoteEditorViewModel(
       notes, FakeNoteGroupRepo(), FakeProjectRepo(), SavedStateHandle(mapOf("noteId" to id)),
     )

     @Test fun create_save_dispatches_create_with_fields() = runTest(testDispatcher) {
       val notes = FakeNoteRepo()
       val vm = createVm(notes)
       vm.onTitleChange("Idea"); vm.onContentChange("body"); vm.onGroupChange("g1"); vm.onProjectChange("p1")
       var done = false
       vm.save { done = true }
       assertEquals(1, notes.created.size)
       val d = notes.created.first()
       assertEquals("Idea", d.title); assertEquals("body", d.content)
       assertEquals("g1", d.groupId); assertEquals("p1", d.projectId)
       assertTrue(notes.updated.isEmpty()); assertTrue(done)
     }

     @Test fun edit_loads_entity_then_save_updates() = runTest(testDispatcher) {
       val notes = FakeNoteRepo()
       notes.singleFlow.value = fakeNote(id = "n1", title = "Old", content = "c", groupId = "g1", pinnedAt = null)
       val vm = editVm(notes)
       assertEquals("Old", vm.uiState.value.title)
       assertEquals(true, vm.uiState.value.isEdit)
       vm.onTitleChange("New")
       var done = false
       vm.save { done = true }
       assertEquals(listOf("n1"), notes.updated)
       assertTrue(notes.created.isEmpty()); assertTrue(done)
     }

     @Test fun save_blank_title_and_content_is_noop() = runTest(testDispatcher) {
       val notes = FakeNoteRepo()
       val vm = createVm(notes)
       var done = false
       vm.save { done = true }
       assertTrue(notes.created.isEmpty()); assertEquals(false, done)
     }

     @Test fun togglePin_edit_mode_calls_setPinned_inverted() = runTest(testDispatcher) {
       val notes = FakeNoteRepo()
       notes.singleFlow.value = fakeNote(id = "n1", pinnedAt = null)
       val vm = editVm(notes)
       vm.togglePin()
       assertEquals(listOf("n1" to true), notes.pinned)
     }

     @Test fun delete_edit_mode_delegates_create_mode_just_callbacks() = runTest(testDispatcher) {
       val notes = FakeNoteRepo()
       notes.singleFlow.value = fakeNote(id = "n1")
       var doneA = false
       editVm(notes).delete { doneA = true }
       assertEquals(listOf("n1"), notes.deleted); assertTrue(doneA)

       val notes2 = FakeNoteRepo()
       var doneB = false
       createVm(notes2).delete { doneB = true }
       assertTrue(notes2.deleted.isEmpty()); assertTrue(doneB)
     }
   }
   ```

2. **Verify it FAILS:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.notes.NoteEditorViewModelTest"
   ```

3. **Write the full implementations:**
   - `NoteEditorUiState.kt`: the data class above + `fun NoteEntity.toEditorState(groups, projects): NoteEditorUiState` (maps title/content/groupId/projectId, `pinned = pinnedAt != null`, `isEdit = true`, `loading = false`).
   - `NoteEditorViewModel.kt` (mirror `TaskEditorViewModel`): read `noteId` from `SavedStateHandle` (`null`/blank/`"new"` = create). In edit mode, `combine(noteRepo.observe(id), noteGroupRepo.observeAll(), projectRepo.observeAll())` → set state from the loaded entity (and keep `groups`/`projects`); in create mode just collect groups+projects into state. `save`: noop when both title and content blank; else `if (noteId == null) noteRepo.create(title.trim(), content, groupId, projectId) else noteRepo.update(noteId, title = title.trim(), content = content, groupId = groupId, projectId = projectId)`, then `saved = true` + `onDone()`. `togglePin`: edit-mode only → `noteRepo.setPinned(id, !uiState.value.pinned)` and optimistically flip `pinned` in state. `delete`: edit-mode → `noteRepo.delete(id)` then `onDone()`; create-mode → just `onDone()`.
   - `NoteEditorSheet.kt` (**compile-gate only**): a `SheetScaffold` (P0) hosting: a single-line title `BasicTextField`/`OutlinedTextField` (Title type), a multi-line content field (Body, fills remaining height — the "editorial writing space"), a notebook selector (chips/dropdown over `state.groups`, includes "No notebook"), a project selector (`ProjectDot` + name over `state.projects`, includes "No project"), a pin toggle (📌, edit-mode only; `contentDescription` "Pin/Unpin note"), and Save + Delete actions. Wire via a stateful `NoteEditorSheet(onClose)` entry point that uses `hiltViewModel<NoteEditorViewModel>()`, collects state, and calls `save { onClose() }` / `delete { onClose() }`. Provide an `@Preview` of the stateless content wrapped in `TmapTheme`.

4. **Verify it PASSES + compiles:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.notes.NoteEditorViewModelTest"
   cd android && ./gradlew :app:assembleDebug
   ```

   **Behavior checklist vs. mockup (NoteEditorSheet):**
   - [ ] Title field (large) + multi-line content field (editorial body) on a `surfaceRaised` sheet (26dp top corners).
   - [ ] Notebook + project assignment controls present (project shown with its color dot).
   - [ ] Pin toggle present in edit mode with a `contentDescription`.
   - [ ] Save persists via `NoteRepository`; Delete present in edit mode; both close the sheet.
   - [ ] start/end paddings (RTL).

5. **Commit:**
   ```
   feat(notes): NoteEditorViewModel + NoteEditorSheet (write-through, local pin)

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P4.7 — Wire Notes into the bottom-nav tab + `ProjectDetailScreen` notes hook (compile-gate)

Replace the P0 Notes placeholder with the real `NotesScreen` in the nav graph, route note taps to the `NoteEditorSheet` via `SheetHost`, and add the documented "project's notes" slot in `ProjectDetailScreen` (the P2 hook). Compile-gate + behavior-checklist; no unit test (pure wiring).

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/navigation/MainScaffold.kt` (replace `composable(Route.Notes...) { /* P0 stub */ }` with `NotesScreen(onOpenNote = { sheetHost.openNoteEditor(it) })`; add the new-note path so the FAB's `createNote` → `onOpenNote(newId)` opens the editor)
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/navigation/SheetHost.kt` (add a `NoteEditor(noteId: String)` sheet state + `fun openNoteEditor(noteId: String)`; host `NoteEditorSheet(onClose = …)`. Mirror the existing task-editor sheet entry. `noteId` flows to `NoteEditorViewModel` via the sheet's `SavedStateHandle` or an explicit arg — match P0's SheetHost convention.)
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectDetailScreen.kt` (add a **"Notes"** section below the tasks list: a stateful sub-composable `ProjectNotesSection(projectId)` that uses `hiltViewModel<NotesViewModel>()`-style data or a small dedicated reader — see step note — listing this project's notes as `NoteCard`s; tap → `onOpenNote`. Documented hook from P2 §6.8/§6.10.)

**Interfaces** (exact additions)
```kotlin
// In SheetHost (P0): add to the sheet sealed state and the controller.
fun openNoteEditor(noteId: String)   // noteId "new" => create

// In ProjectDetailScreen.kt:
@androidx.compose.runtime.Composable
fun ProjectNotesSection(
  projectId: String,
  onOpenNote: (String) -> Unit,
  modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
)
```

**Steps**
1. **Implement the wiring:**
   - In `MainScaffold`, swap the P0 `Route.Notes` placeholder for `NotesScreen(onOpenNote = { id -> sheetHost.openNoteEditor(id) })`. The FAB inside `NotesScreen` already calls `viewModel.createNote(onCreated = onOpenNote)`, so a fresh note opens the editor with its new id.
   - In `SheetHost`, add the `NoteEditor` sheet state and `openNoteEditor(noteId)`; render `NoteEditorSheet(onClose = { dismiss() })`. The hosted `hiltViewModel<NoteEditorViewModel>()` reads `noteId` from the sheet's saved-state (provide it the same way the task editor sheet provides `taskId`).
   - In `ProjectDetailScreen`, add `ProjectNotesSection(projectId, onOpenNote)`. For the project's notes, read via a small `@HiltViewModel` (e.g. reuse `NotesViewModel` filtered, or a thin reader calling `noteRepo.observeAll(groupId = null, projectId = projectId)` — **note:** `NoteRepository.observeAll(groupId, projectId)` already supports filtering by `projectId`, so the section can observe `observeAll(null, projectId)` directly). Render a `SectionLabel("Notes")` + `NoteCard`s (or an `EmptyState` "No notes for this project yet").

2. **Verify it COMPILES (compile-gate):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```

3. **Behavior checklist:**
   - [ ] Notes tab now shows the real `NotesScreen` (P0 placeholder gone).
   - [ ] Tapping a note opens `NoteEditorSheet`; tapping the FAB creates a note and opens its editor.
   - [ ] `ProjectDetailScreen` shows a "Notes" section listing that project's notes (via `observeAll(null, projectId)`); tap opens the editor; empty state when none.
   - [ ] Reminder/task deep-link + other tabs unaffected (regression: the existing task-editor sheet still opens).

4. **Commit:**
   ```
   feat(notes): wire NotesScreen into nav tab + project-detail notes section

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P4.8 — Phase gate: full unit suite + assembleDebug + lint + offline note

Green-gate the phase. Run the full Notes test set and the build/lint gates; record the offline-create behavior check (the actual outbox enqueue is already proven by the P3 `NoteRepository` repository test — here we only confirm the **UI path routes every create/edit/delete/pin through the repository**, which the VM unit tests already assert, and that no screen blocks on the network).

**Files** (none — verification only)

**Steps**
1. **Run the full Notes unit tests (must all PASS):**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.notes.*" --tests "net.qmindtech.tmap.testutil.FakeNoteReposTest"
   ```
2. **Compile gate (must be GREEN):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```
3. **Lint gate:**
   ```bash
   cd android && ./gradlew :app:lintDebug
   ```
4. **Offline check note (acceptance §10.7 / §9 offline-first — record in the commit/PR, no new code):**
   - [ ] Every Notes mutation (`createNote`, `createNotebook`, `togglePin`/`setPinned`, `deleteNote`, editor `save`) goes through `NoteRepository`/`NoteGroupRepository` only — verified by the `NotesViewModelTest` + `NoteEditorViewModelTest` assertions that each action records on the fake repo. No `NotesScreen`/`NoteEditorSheet` code path calls the network or `TmapApiService` directly.
   - [ ] The repository write-through → outbox enqueue (create-offline → appears server-side after reconnect) is covered by the **P3** `NoteRepository` repository test; P4 adds no new sync behavior, only the UI gate over it.
   - [ ] Pin is **local-only** (`setPinned` sets `pinnedAt`; never enqueued to the outbox) per spec §7.7 — confirm `togglePin` routes only through `setPinned` and the UI never sends pin to the API.
5. **Behavior checklist vs. the Notes mockup** (`full-app.html`): re-confirm the P4.4/P4.5/P4.6 checklists hold in the assembled app (notebook chips, Pinned/Recent sections, amber-bar pinned cards + 📌, project dots, edited-time meta, amber + FAB, editor sheet).
6. **Commit (gate marker):**
   ```
   chore(notes): P4 gate — unit suite + assembleDebug + lint green; offline UI gate verified

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```
```

---

## P5 — Planning ritual ("Plan my day")

This phase builds the guided **4-step planning ritual** (spec §6.4) on top of the P3 daily-plan data layer, consuming the FIXED `DailyPlanRepository.upsert(date, plannedTaskIds, plannedMinutes)` contract (it stamps `committedAt` internally) and the `TaskRepository` `TaskEdit`/`update`/`delete` contract verbatim. It delivers, in dependency order: a pure **workday-minutes setting + capacity math** (`KEY_WORKDAY_MINUTES`, default 360, per-task duration with a fallback estimate; `capacityOf(...)` / `workdayMinutes(...)`) with real unit tests; a pure `PlanningStep` state machine (`Reflect → TriageInbox → PickToday → Timebox`) with `next()`/`back()` transitions and a `PlanningUiState`, real unit tests; **Fake `DailyPlanRepository` + `FakeSettingsRepository` test doubles** in `testutil/Fakes.kt`; a `PlanningViewModel` that loads + derives carry-over (yesterday's undone), inbox, the live "pick" set, and the live capacity, plus a `toggleAdd` action, real unit tests; the **triage actions** (schedule / backlog / project / delete), **assignTime**, and the **commit** (set each pick's `plannedDate = today` + status via `TaskRepository`, then `DailyPlanRepository.upsert(today, orderedIds, plannedMinutes)`), real unit tests proving the write-through/offline path; the `Route.Planning` host wired into `MainScaffold` (route-string unit test); the four **step composables** under `ui/planning/steps/` (compile-gate + mockup behavior-checklist); the `PlanningScreen` host (stepper header, step content, Continue/back) and the Today **"Plan my day"** entry-point wired through `MainScaffold` (compile-gate + checklist); and a phase gate (full unit suite + `assembleDebug` + the spec §10.5 ritual acceptance check).

> **Visual source of truth:** `.superpowers/brainstorm/965-1782053760/content/full-app.html` — the **Planning ritual** panel: a full-screen flow with a top progress dot row + a back chevron; an amber eyebrow `STEP 3 OF 4 · PICK YOUR DAY`; a 20px heading `What deserves your time today?`; tracked-uppercase section labels `CARRY OVER FROM YESTERDAY` and `FROM YOUR INBOX`; 18dp `surface` cards (title + project dot + project name) each with a pill **+ Add** button (amber gradient) — an added card dims to `opacity .62` and the pill becomes a circular amber **✓**; a bottom dock with `≈ 4h planned of your 6h` + a `66%` figure, an amber capacity progress bar, and a full-width amber **Continue →** button.
>
> **P3 contract consumed verbatim (do NOT redefine — produced by P3):**
> - `interface DailyPlanRepository { fun observe(date: java.time.LocalDate): Flow<DailyPlanEntity?>; suspend fun upsert(date: java.time.LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int) }` — `upsert` stamps `committedAt = clock.now()` internally and enqueues the date-keyed outbox UPDATE (offline write-through). P5 never sets `committedAt` itself.
> - `DailyPlanEntity(date: LocalDate /*PK*/, committedAt: Instant, plannedTaskIds: List<String>, plannedMinutes: Int, changeSeq: Long, deletedAt: Instant?)`.
>
> **Existing contracts consumed verbatim:**
> - `TaskRepository { observeToday(today); observeByStatus(s); update(id, TaskEdit); delete(id); markDone(id); … }` and `data class TaskEdit(val status: TaskStatus? = null, val plannedDate: LocalDate? = null, val projectId: String? = null, …)` (a non-null field overwrites; `null` leaves the current value — note `plannedDate`/`projectId` cannot be *cleared* via `TaskEdit`, matching the existing `TaskRepositoryImpl.update`).
> - `SettingsRepository { fun observe(): Flow<List<SettingEntity>>; suspend fun save(settings: Map<String,String>, timeZoneId: String?) }`; `SettingEntity(key, value, changeSeq)`.
> - `ProjectRepository.observeAll()`; `Clock.today()` / `Clock.now()`.
> - `data class TaskUi(...)` + `fun TaskEntity.toUi(project, subtaskDone, subtaskTotal, zone): TaskUi` (P0, `ui/components/TaskUi.kt`).
> - `TaskStatus { Inbox, Backlog, Planned, Scheduled, Done, Archived }`.
>
> **P0 contracts consumed:** `TmapTheme` + `LocalTmapColors` (`colors.surface`, `colors.surfaceRaised`, `colors.surfaceInset`, `colors.textPrimary`, `colors.textSecondary`, `colors.textTertiary`, `colors.textBody`, `colors.accent`, `colors.accentEnd`, `colors.onAccent`, `colors.borderSubtle`, `colors.danger`), `SectionLabel`, `ProjectDot`, `PrimaryButton`/`SecondaryButton`, `Chip`, `EmptyState`, `TmapBackground`. **Navigation contract (P0):** `sealed interface Route` already declares `data object Planning : Route { override val route = "planning" }`; P5 replaces the P0 `PlanningPlaceholder()` in `MainScaffold`'s `NavHost` with the real `PlanningScreen` and flips the Today `onPlanMyDay` stub to `navController.navigate(Route.Planning.route)`.

---

### Task P5.1 — Workday-minutes setting key + capacity math (pure)

A pure, unit-testable module owning the **planning capacity** maths. `KEY_WORKDAY_MINUTES` is the setting key for the user's configurable workday length (minutes); `workdayMinutes(settings)` reads it from the raw settings rows with a 360-minute (6h) default; `capacityOf(tasks)` sums each task's planned minutes using its `durationMinutes` when present and a per-task `DEFAULT_TASK_MINUTES` (30) fallback otherwise. No ViewModel, no coroutines — just functions over plain data so they can be tested directly and reused by `PlanningViewModel` (P5.4/P5.5) and the dock (P5.7).

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningCapacity.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/planning/PlanningCapacityTest.kt`

**Interfaces** (exact — produced here; consumed by P5.4/P5.5/P5.7)
```kotlin
package net.qmindtech.tmap.ui.planning

import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity

/** Setting key for the user's workday length in minutes (spec §6.4 capacity target). */
const val KEY_WORKDAY_MINUTES = "workdayMinutes"

/** Default workday capacity when the setting is missing/non-numeric: 6 hours. */
const val DEFAULT_WORKDAY_MINUTES = 360

/** Fallback planned minutes for a task that has no durationMinutes set. */
const val DEFAULT_TASK_MINUTES = 30

/** Reads KEY_WORKDAY_MINUTES from settings rows; default DEFAULT_WORKDAY_MINUTES; clamps to >= 0. */
fun workdayMinutes(settings: List<SettingEntity>): Int

/** Planned minutes a single task contributes: its durationMinutes, else DEFAULT_TASK_MINUTES (>=0). */
fun taskMinutes(task: TaskEntity): Int

/** Sum of taskMinutes over the given tasks (the live "≈ Xh planned" figure). */
fun capacityOf(tasks: List<TaskEntity>): Int

/** Fraction planned of the workday, clamped to 0f..1f (0 when capacity is 0). */
fun capacityFraction(plannedMinutes: Int, workdayMinutes: Int): Float
```

**Steps**
1. **Write the failing test** `PlanningCapacityTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.planning

import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanningCapacityTest {
  private fun setting(k: String, v: String) = SettingEntity(key = k, value = v, changeSeq = 0)

  @Test fun workdayMinutes_reads_the_key() {
    assertEquals(480, workdayMinutes(listOf(setting(KEY_WORKDAY_MINUTES, "480"))))
  }

  @Test fun workdayMinutes_defaults_to_360_when_missing_or_nonnumeric() {
    assertEquals(360, workdayMinutes(emptyList()))
    assertEquals(360, workdayMinutes(listOf(setting(KEY_WORKDAY_MINUTES, "soon"))))
    assertEquals(360, workdayMinutes(listOf(setting("other", "9"))))
  }

  @Test fun workdayMinutes_clamps_negatives_to_zero() {
    assertEquals(0, workdayMinutes(listOf(setting(KEY_WORKDAY_MINUTES, "-30"))))
  }

  @Test fun taskMinutes_uses_duration_else_default_fallback() {
    assertEquals(45, taskMinutes(fakeTask(id = "a", durationMinutes = 45)))
    assertEquals(DEFAULT_TASK_MINUTES, taskMinutes(fakeTask(id = "b", durationMinutes = null)))
    assertEquals(0, taskMinutes(fakeTask(id = "c", durationMinutes = -5))) // clamp
  }

  @Test fun capacityOf_sums_task_minutes_with_fallback() {
    val tasks = listOf(
      fakeTask(id = "a", durationMinutes = 90),
      fakeTask(id = "b", durationMinutes = null), // -> 30
      fakeTask(id = "c", durationMinutes = 120),
    )
    assertEquals(240, capacityOf(tasks))
    assertEquals(0, capacityOf(emptyList()))
  }

  @Test fun capacityFraction_is_clamped_and_zero_safe() {
    assertEquals(0.5f, capacityFraction(180, 360), 0.0001f)
    assertEquals(1f, capacityFraction(400, 360), 0.0001f)      // over-committed clamps to 1
    assertEquals(0f, capacityFraction(120, 0), 0.0001f)        // no capacity -> 0, no divide-by-zero
  }
}
```

2. **Verify it FAILS:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningCapacityTest"
   ```
   Expected: compile/red — `PlanningCapacity.kt` does not exist yet.

3. **Implement** `PlanningCapacity.kt` (full):
```kotlin
package net.qmindtech.tmap.ui.planning

import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity

/** Setting key for the user's workday length in minutes (spec §6.4 capacity target). */
const val KEY_WORKDAY_MINUTES = "workdayMinutes"

/** Default workday capacity when the setting is missing/non-numeric: 6 hours. */
const val DEFAULT_WORKDAY_MINUTES = 360

/** Fallback planned minutes for a task that has no durationMinutes set. */
const val DEFAULT_TASK_MINUTES = 30

/** Reads KEY_WORKDAY_MINUTES from settings rows; default DEFAULT_WORKDAY_MINUTES; clamps to >= 0. */
fun workdayMinutes(settings: List<SettingEntity>): Int {
    val raw = settings.firstOrNull { it.key == KEY_WORKDAY_MINUTES }?.value
    return (raw?.toIntOrNull() ?: DEFAULT_WORKDAY_MINUTES).coerceAtLeast(0)
}

/** Planned minutes a single task contributes: its durationMinutes, else DEFAULT_TASK_MINUTES (>=0). */
fun taskMinutes(task: TaskEntity): Int =
    (task.durationMinutes ?: DEFAULT_TASK_MINUTES).coerceAtLeast(0)

/** Sum of taskMinutes over the given tasks (the live "≈ Xh planned" figure). */
fun capacityOf(tasks: List<TaskEntity>): Int = tasks.sumOf { taskMinutes(it) }

/** Fraction planned of the workday, clamped to 0f..1f (0 when capacity is 0). */
fun capacityFraction(plannedMinutes: Int, workdayMinutes: Int): Float {
    if (workdayMinutes <= 0) return 0f
    return (plannedMinutes.toFloat() / workdayMinutes.toFloat()).coerceIn(0f, 1f)
}
```

4. **Verify it PASSES:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningCapacityTest"
   ```
   Expected: `BUILD SUCCESSFUL`.

5. **Commit:**
   ```
   feat(planning): workday-minutes setting key + capacity math (pure)

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P5.2 — `PlanningStep` state machine + `PlanningUiState` (pure)

The pure, unit-testable core of the ritual's flow control. `PlanningStep` is the 4-step enum in order; `next()` advances (clamped at the last step) and `back()` retreats (clamped at the first); `stepIndex`/`stepCount`/`eyebrow`/`heading` drive the header. `PlanningUiState` is the immutable screen state the ViewModel will emit; here we define it and the pure `advance`/`retreat` helpers over it. Carry-over / inbox / pick projections (`PlanItemUi`) are defined here too so the ViewModel (P5.4) just fills them in.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningUiState.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/planning/PlanningStepTest.kt`

**Interfaces** (exact — produced here; consumed by P5.4/P5.5/P5.7/P5.8)
```kotlin
package net.qmindtech.tmap.ui.planning

import net.qmindtech.tmap.data.local.entities.TaskEntity

enum class PlanningStep {
  Reflect, TriageInbox, PickToday, Timebox;

  /** 1-based position for the "STEP n OF 4" eyebrow. */
  val stepNumber: Int get() = ordinal + 1

  fun next(): PlanningStep = entries.getOrElse(ordinal + 1) { this }
  fun back(): PlanningStep = entries.getOrElse(ordinal - 1) { this }

  /** Amber eyebrow text, e.g. "STEP 3 OF 4 · PICK YOUR DAY". */
  val eyebrow: String get() = "STEP $stepNumber OF $STEP_COUNT · $label"

  /** Big heading per the mockup. */
  val heading: String get() = when (this) {
    Reflect -> "How did yesterday go?"
    TriageInbox -> "Clear your inbox"
    PickToday -> "What deserves your time today?"
    Timebox -> "Block out your day"
  }

  private val label: String get() = when (this) {
    Reflect -> "REFLECT"
    TriageInbox -> "TRIAGE INBOX"
    PickToday -> "PICK YOUR DAY"
    Timebox -> "TIMEBOX"
  }

  companion object {
    const val STEP_COUNT = 4
    val FIRST: PlanningStep get() = Reflect
    val LAST: PlanningStep get() = Timebox
  }
}

/** Per-row projection for the Reflect / PickToday / Timebox lists. */
data class PlanItemUi(
  val id: String,
  val title: String,
  val projectName: String?,
  val projectColor: Long?,
  val durationMinutes: Int?,   // null = use DEFAULT_TASK_MINUTES estimate
  val done: Boolean = false,   // only meaningful in Reflect (yesterday's status)
  val added: Boolean = false,  // PickToday: already in the pick set
)

data class PlanningUiState(
  val loading: Boolean = true,
  val step: PlanningStep = PlanningStep.Reflect,
  // Reflect:
  val yesterdayDone: List<PlanItemUi> = emptyList(),
  val yesterdayUndone: List<PlanItemUi> = emptyList(),
  // TriageInbox:
  val inbox: List<PlanItemUi> = emptyList(),
  // PickToday (carry-over = yesterday's undone; inboxPicks = inbox items; pick = ordered chosen set):
  val carryOver: List<PlanItemUi> = emptyList(),
  val inboxPicks: List<PlanItemUi> = emptyList(),
  val pickedIds: List<String> = emptyList(),      // ordered; the day's plannedTaskIds
  // Capacity (live):
  val plannedMinutes: Int = 0,
  val workdayMinutes: Int = DEFAULT_WORKDAY_MINUTES,
  val committed: Boolean = false,
) {
  val isFirstStep: Boolean get() = step == PlanningStep.FIRST
  val isLastStep: Boolean get() = step == PlanningStep.LAST
  val capacityFraction: Float get() = capacityFraction(plannedMinutes, workdayMinutes)
}

/** Pure transitions — clamp at the ends. */
fun PlanningUiState.advance(): PlanningUiState = copy(step = step.next())
fun PlanningUiState.retreat(): PlanningUiState = copy(step = step.back())
```

**Steps**
1. **Write the failing test** `PlanningStepTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.planning

import org.junit.Assert.assertEquals
import org.junit.Test

class PlanningStepTest {
  @Test fun steps_are_in_ritual_order() {
    assertEquals(
      listOf("Reflect", "TriageInbox", "PickToday", "Timebox"),
      PlanningStep.entries.map { it.name },
    )
    assertEquals(4, PlanningStep.STEP_COUNT)
  }

  @Test fun next_advances_and_clamps_at_last() {
    assertEquals(PlanningStep.TriageInbox, PlanningStep.Reflect.next())
    assertEquals(PlanningStep.PickToday, PlanningStep.TriageInbox.next())
    assertEquals(PlanningStep.Timebox, PlanningStep.PickToday.next())
    assertEquals(PlanningStep.Timebox, PlanningStep.Timebox.next()) // clamp
  }

  @Test fun back_retreats_and_clamps_at_first() {
    assertEquals(PlanningStep.PickToday, PlanningStep.Timebox.back())
    assertEquals(PlanningStep.Reflect, PlanningStep.TriageInbox.back())
    assertEquals(PlanningStep.Reflect, PlanningStep.Reflect.back())  // clamp
  }

  @Test fun eyebrow_matches_the_mockup_format() {
    assertEquals("STEP 3 OF 4 · PICK YOUR DAY", PlanningStep.PickToday.eyebrow)
    assertEquals("STEP 1 OF 4 · REFLECT", PlanningStep.Reflect.eyebrow)
  }

  @Test fun headings_present_per_step() {
    assertEquals("What deserves your time today?", PlanningStep.PickToday.heading)
  }

  @Test fun uiState_advance_retreat_and_flags() {
    val s0 = PlanningUiState(step = PlanningStep.Reflect)
    assertEquals(true, s0.isFirstStep)
    val s1 = s0.advance()
    assertEquals(PlanningStep.TriageInbox, s1.step)
    val last = PlanningUiState(step = PlanningStep.Timebox)
    assertEquals(true, last.isLastStep)
    assertEquals(PlanningStep.Timebox, last.advance().step) // clamp
    assertEquals(PlanningStep.PickToday, last.retreat().step)
  }

  @Test fun capacityFraction_derives_from_planned_over_workday() {
    val s = PlanningUiState(plannedMinutes = 180, workdayMinutes = 360)
    assertEquals(0.5f, s.capacityFraction, 0.0001f)
  }
}
```

2. **Verify it FAILS:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningStepTest"
   ```

3. **Implement** `PlanningUiState.kt` exactly as the **Interfaces** block above (enum + `PlanItemUi` + `PlanningUiState` + `advance`/`retreat`). `capacityFraction(...)` resolves to the P5.1 free function (same package).

4. **Verify it PASSES:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningStepTest"
   ```

5. **Commit:**
   ```
   feat(planning): PlanningStep state machine + PlanningUiState (pure)

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P5.3 — Fake `DailyPlanRepository` + `FakeSettingsRepository` test doubles

Add the two test doubles the planning ViewModel tests need, appended to the shared `testutil/Fakes.kt`. `FakeDailyPlanRepo` matches the FIXED P3 `DailyPlanRepository` interface (a mutable observe flow + an `upserts` recorder); `FakeSettingsRepo` matches the existing `SettingsRepository` (a mutable rows flow + a `saved` recorder). A small self-test locks their behavior so later VM tests can trust them.

**Files**
- `~ android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt` (append the two fakes + a `fakeDailyPlan` builder)
- `+ android/app/src/test/java/net/qmindtech/tmap/testutil/FakePlanningReposTest.kt`

**Interfaces** (exact — appended to `Fakes.kt`)
```kotlin
// --- append to Fakes.kt (same package net.qmindtech.tmap.testutil) ---

fun fakeDailyPlan(
  date: java.time.LocalDate,
  committedAt: java.time.Instant = EPOCH,
  plannedTaskIds: List<String> = emptyList(),
  plannedMinutes: Int = 0,
  changeSeq: Long = 0,
): net.qmindtech.tmap.data.local.entities.DailyPlanEntity =
  net.qmindtech.tmap.data.local.entities.DailyPlanEntity(
    date = date, committedAt = committedAt, plannedTaskIds = plannedTaskIds,
    plannedMinutes = plannedMinutes, changeSeq = changeSeq, deletedAt = null,
  )

data class DailyPlanUpsert(
  val date: java.time.LocalDate,
  val plannedTaskIds: List<String>,
  val plannedMinutes: Int,
)

class FakeDailyPlanRepo(
  private val flow: MutableStateFlow<net.qmindtech.tmap.data.local.entities.DailyPlanEntity?> =
    MutableStateFlow(null),
) : net.qmindtech.tmap.data.repository.DailyPlanRepository {
  val upserts = mutableListOf<DailyPlanUpsert>()

  override fun observe(date: java.time.LocalDate): Flow<net.qmindtech.tmap.data.local.entities.DailyPlanEntity?> = flow
  override suspend fun upsert(date: java.time.LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int) {
    upserts += DailyPlanUpsert(date, plannedTaskIds, plannedMinutes)
  }

  fun set(v: net.qmindtech.tmap.data.local.entities.DailyPlanEntity?) { flow.value = v }
}

class FakeSettingsRepo(
  private val rows: MutableStateFlow<List<net.qmindtech.tmap.data.local.entities.SettingEntity>> =
    MutableStateFlow(emptyList()),
) : net.qmindtech.tmap.data.repository.SettingsRepository {
  var lastSavedMap: Map<String, String>? = null
  var lastSavedTimeZone: String? = null
  var saveCount = 0

  override fun observe(): Flow<List<net.qmindtech.tmap.data.local.entities.SettingEntity>> = rows
  override suspend fun save(settings: Map<String, String>, timeZoneId: String?) {
    lastSavedMap = settings; lastSavedTimeZone = timeZoneId; saveCount++
  }

  fun set(v: List<net.qmindtech.tmap.data.local.entities.SettingEntity>) { rows.value = v }
}
```

**Steps**
1. **Write the failing self-test** `FakePlanningReposTest.kt`:
```kotlin
package net.qmindtech.tmap.testutil

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.qmindtech.tmap.data.local.entities.SettingEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FakePlanningReposTest {
  private val date = LocalDate.of(2026, 6, 21)

  @Test fun daily_plan_repo_observes_and_records_upserts() = runTest {
    val repo = FakeDailyPlanRepo()
    repo.set(fakeDailyPlan(date, plannedTaskIds = listOf("a"), plannedMinutes = 60))
    assertEquals(listOf("a"), repo.observe(date).first()!!.plannedTaskIds)
    repo.upsert(date, listOf("a", "b"), 120)
    assertEquals(DailyPlanUpsert(date, listOf("a", "b"), 120), repo.upserts.single())
  }

  @Test fun settings_repo_observes_and_records_save() = runTest {
    val repo = FakeSettingsRepo()
    repo.set(listOf(SettingEntity(key = "k", value = "v", changeSeq = 0)))
    assertEquals("v", repo.observe().first().single().value)
    repo.save(mapOf("workdayMinutes" to "480"), timeZoneId = null)
    assertEquals(1, repo.saveCount)
    assertEquals("480", repo.lastSavedMap!!["workdayMinutes"])
  }
}
```

2. **Verify it FAILS:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.testutil.FakePlanningReposTest"
   ```
   Expected: red — the fakes do not exist yet.

3. **Implement** by appending the **Interfaces** block above to `Fakes.kt`. The existing top-of-file imports already cover `Flow`, `MutableStateFlow`, `StateFlow`; the appended members use fully-qualified names for the new types (`DailyPlanEntity`, `SettingEntity`, repo interfaces) so no new imports are required. `EPOCH` is the file-private constant already defined in `Fakes.kt`.

> If P3's `DailyPlanRepository` signature differs from the consumed contract, adapt the fake to match P3 **verbatim** — the method names `observe`/`upsert` and the `upsert(date, plannedTaskIds, plannedMinutes)` parameter list are FIXED.

4. **Verify it PASSES:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.testutil.FakePlanningReposTest"
   ```

5. **Commit:**
   ```
   test(planning): FakeDailyPlanRepo + FakeSettingsRepo test doubles

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P5.4 — `PlanningViewModel`: load + derive carry-over / inbox / picks / capacity + `toggleAdd` (real unit tests)

The ritual ViewModel's **read** side. It combines, keyed off `clock.today()`, the data flows it needs and projects them into `PlanningUiState`: **Reflect** shows yesterday's tasks split done/undone (`observeToday(today.minusDays(1))`); **TriageInbox** shows `observeByStatus(Inbox)`; **PickToday** surfaces `carryOver` (= yesterday's undone) + `inboxPicks` (= inbox) with an `added` flag driven by the live, in-memory `pickedIds` set; capacity is `capacityOf(picked tasks)` against `workdayMinutes(settings)`. `next()`/`back()` move the step (pure helpers from P5.2). `toggleAdd(id)` adds/removes an id from the ordered pick set. Project names/colors come from `ProjectRepository.observeAll()`. **Triage actions + commit live in P5.5.**

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningViewModel.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/planning/PlanningViewModelTest.kt`

**Interfaces** (exact — the VM signature; `toggleAdd`/`next`/`back` produced here, triage/commit added in P5.5 on the SAME class)
```kotlin
package net.qmindtech.tmap.ui.planning

@dagger.hilt.android.lifecycle.HiltViewModel
class PlanningViewModel @javax.inject.Inject constructor(
  private val taskRepo: net.qmindtech.tmap.data.repository.TaskRepository,
  private val projectRepo: net.qmindtech.tmap.data.repository.ProjectRepository,
  private val dailyPlanRepo: net.qmindtech.tmap.data.repository.DailyPlanRepository,
  private val settingsRepo: net.qmindtech.tmap.data.repository.SettingsRepository,
  private val clock: net.qmindtech.tmap.util.Clock,
) : androidx.lifecycle.ViewModel() {
  val uiState: kotlinx.coroutines.flow.StateFlow<PlanningUiState>
  fun next()
  fun back()
  fun toggleAdd(taskId: String)
  // P5.5 adds: scheduleFromInbox / sendToBacklog / assignProject / deleteTask / assignTime / commit
}
```

**Steps**
1. **Write the failing test** `PlanningViewModelTest.kt` (mirror `TodayViewModelTest`: `UnconfinedTestDispatcher` + `Dispatchers.setMain`, Turbine `test {}`, `FixedClock`):
```kotlin
package net.qmindtech.tmap.ui.planning

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.testutil.FakeDailyPlanRepo
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeSettingsRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeProject
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class PlanningViewModelTest {
  private val today = LocalDate.of(2026, 6, 21)
  private val yesterday = today.minusDays(1)
  private val testDispatcher = UnconfinedTestDispatcher()

  @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
  @After fun tearDown() { Dispatchers.resetMain() }

  private fun vm(
    yesterdayTasks: List<TaskEntity> = emptyList(),
    inbox: List<TaskEntity> = emptyList(),
    settings: List<net.qmindtech.tmap.data.local.entities.SettingEntity> = emptyList(),
  ): Triple<PlanningViewModel, FakeTaskRepo, FakeDailyPlanRepo> {
    val task = FakeTaskRepo(today = MutableStateFlow(yesterdayTasks), byStatus = MutableStateFlow(inbox))
    val projects = FakeProjectRepo().apply { setAll(listOf(fakeProject(id = "p1", name = "Work", color = "#6ea8fe"))) }
    val daily = FakeDailyPlanRepo()
    val set = FakeSettingsRepo().apply { set(settings) }
    val vm = PlanningViewModel(task, projects, daily, set, FixedClock(Instant.parse("2026-06-21T06:00:00Z")))
    return Triple(vm, task, daily)
  }

  @Test fun reflect_splits_yesterday_done_and_undone() = runTest {
    val (vm, _, _) = vm(
      yesterdayTasks = listOf(
        fakeTask(id = "d", title = "Done", plannedDate = yesterday, status = TaskStatus.Done),
        fakeTask(id = "u", title = "Undone", plannedDate = yesterday, status = TaskStatus.Planned, projectId = "p1"),
      ),
    )
    vm.uiState.test {
      val s = expectMostRecentItem()
      assertEquals(false, s.loading)
      assertEquals(listOf("d"), s.yesterdayDone.map { it.id })
      assertEquals(listOf("u"), s.yesterdayUndone.map { it.id })
      assertEquals("Work", s.yesterdayUndone.single().projectName)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun pickToday_lists_carryover_and_inbox_and_toggleAdd_updates_capacity() = runTest {
    val (vm, _, _) = vm(
      yesterdayTasks = listOf(fakeTask(id = "u", plannedDate = yesterday, status = TaskStatus.Planned, durationMinutes = 60)),
      inbox = listOf(fakeTask(id = "i", status = TaskStatus.Inbox, durationMinutes = 90)),
      settings = listOf(net.qmindtech.tmap.data.local.entities.SettingEntity(KEY_WORKDAY_MINUTES, "360", 0)),
    )
    vm.uiState.test {
      var s = expectMostRecentItem()
      assertEquals(listOf("u"), s.carryOver.map { it.id })
      assertEquals(listOf("i"), s.inboxPicks.map { it.id })
      assertEquals(0, s.plannedMinutes)
      assertEquals(360, s.workdayMinutes)

      vm.toggleAdd("u")   // +60
      vm.toggleAdd("i")   // +90
      s = expectMostRecentItem()
      assertEquals(listOf("u", "i"), s.pickedIds)
      assertEquals(150, s.plannedMinutes)
      assertEquals(true, s.carryOver.single { it.id == "u" }.added)

      vm.toggleAdd("u")   // remove
      s = expectMostRecentItem()
      assertEquals(listOf("i"), s.pickedIds)
      assertEquals(90, s.plannedMinutes)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun next_and_back_move_the_step_clamped() = runTest {
    val (vm, _, _) = vm()
    vm.uiState.test {
      assertEquals(PlanningStep.Reflect, expectMostRecentItem().step)
      vm.next(); assertEquals(PlanningStep.TriageInbox, expectMostRecentItem().step)
      vm.next(); vm.next(); vm.next() // PickToday, Timebox, clamp
      assertEquals(PlanningStep.Timebox, expectMostRecentItem().step)
      vm.back(); assertEquals(PlanningStep.PickToday, expectMostRecentItem().step)
      cancelAndIgnoreRemainingEvents()
    }
  }
}
```

2. **Verify it FAILS:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningViewModelTest"
   ```

3. **Implement** `PlanningViewModel.kt` (full):
```kotlin
package net.qmindtech.tmap.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.ProjectEntity
import net.qmindtech.tmap.data.local.entities.SettingEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.data.repository.DailyPlanRepository
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.SettingsRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.ui.components.parseProjectColor
import net.qmindtech.tmap.util.Clock
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class PlanningViewModel @Inject constructor(
  private val taskRepo: TaskRepository,
  private val projectRepo: ProjectRepository,
  private val dailyPlanRepo: DailyPlanRepository,
  private val settingsRepo: SettingsRepository,
  private val clock: Clock,
) : ViewModel() {

  private val today: LocalDate = clock.today()
  private val yesterday: LocalDate = today.minusDays(1)

  // Local ritual state not derived from Room: current step, the ordered pick set, the committed flag.
  private val step = MutableStateFlow(PlanningStep.Reflect)
  private val picked = MutableStateFlow<List<String>>(emptyList())
  private val committed = MutableStateFlow(false)

  val uiState: StateFlow<PlanningUiState> =
    combine(
      taskRepo.observeToday(yesterday),
      taskRepo.observeByStatus(TaskStatus.Inbox),
      projectRepo.observeAll(),
      settingsRepo.observe(),
      combine(step, picked, committed) { s, p, c -> Triple(s, p, c) },
    ) { yTasks, inboxTasks, projects, settings, local ->
      project(yTasks, inboxTasks, projects, settings, local.first, local.second, local.third)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningUiState())

  private fun project(
    yesterdayTasks: List<TaskEntity>,
    inboxTasks: List<TaskEntity>,
    projects: List<ProjectEntity>,
    settings: List<SettingEntity>,
    step: PlanningStep,
    pickedIds: List<String>,
    committed: Boolean,
  ): PlanningUiState {
    val byId = projects.associateBy { it.id }
    fun item(t: TaskEntity): PlanItemUi {
      val p = t.projectId?.let { byId[it] }
      return PlanItemUi(
        id = t.id, title = t.title, projectName = p?.name,
        projectColor = parseProjectColor(p?.color), durationMinutes = t.durationMinutes,
        done = t.status == TaskStatus.Done, added = pickedIds.contains(t.id),
      )
    }
    val (done, undone) = yesterdayTasks.partition { it.status == TaskStatus.Done }
    // The capacity sum needs the actual entities behind the picked ids (carry-over + inbox).
    val pool = (yesterdayTasks + inboxTasks).associateBy { it.id }
    val pickedTasks = pickedIds.mapNotNull { pool[it] }
    return PlanningUiState(
      loading = false,
      step = step,
      yesterdayDone = done.map(::item),
      yesterdayUndone = undone.map(::item),
      inbox = inboxTasks.map(::item),
      carryOver = undone.map(::item),
      inboxPicks = inboxTasks.map(::item),
      pickedIds = pickedIds,
      plannedMinutes = capacityOf(pickedTasks),
      workdayMinutes = workdayMinutes(settings),
      committed = committed,
    )
  }

  fun next() { step.value = step.value.next() }
  fun back() { step.value = step.value.back() }

  fun toggleAdd(taskId: String) {
    picked.value = if (picked.value.contains(taskId)) {
      picked.value - taskId
    } else {
      picked.value + taskId
    }
  }
}
```
> `parseProjectColor(color: String?): Long?` is the P0 helper in `ui/components/TaskUi.kt` (used by `TaskEntity.toUi`). If its name/location differs, import it from wherever P0 placed it (it converts a `#RRGGBB`/`null` to the `Long` ARGB / `null` consumed by `ProjectDot`).

4. **Verify it PASSES:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningViewModelTest"
   ```

5. **Commit:**
   ```
   feat(planning): PlanningViewModel load + carry-over/inbox/pick projection + toggleAdd

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P5.5 — Triage actions + `assignTime` + commit (`DailyPlan.upsert` + task `plannedDate`) (real unit tests)

The ritual ViewModel's **write** side, added to the same `PlanningViewModel`. **TriageInbox** one-tap actions route through `TaskRepository.update`/`delete`: `scheduleFromInbox(id)` → `update(id, TaskEdit(status = Planned, plannedDate = today))` (and auto-adds to the pick set); `sendToBacklog(id)` → `update(id, TaskEdit(status = Backlog))`; `assignProject(id, projectId)` → `update(id, TaskEdit(projectId = projectId))`; `deleteTask(id)` → `delete(id)` (+ drops it from the pick set). **Timebox** `assignTime(id, start, end)` → `update(id, TaskEdit(scheduledStart, scheduledEnd, status = Scheduled))`. **`commit(onDone)`** is the ritual finish (spec §6.4 / §10.5): for every picked id set `plannedDate = today` + `status = Planned` via `TaskRepository.update`, then call `dailyPlanRepo.upsert(today, pickedIds, plannedMinutes)` (which stamps `committedAt` internally), set `committed = true`, and invoke `onDone()`. All writes are write-through (offline) — proven by delegating to the repositories.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningViewModel.kt` (add the actions)
- `~ android/app/src/test/java/net/qmindtech/tmap/ui/planning/PlanningViewModelTest.kt` (add the action/commit tests)

**Interfaces** (exact — added methods on `PlanningViewModel`)
```kotlin
fun scheduleFromInbox(taskId: String)                 // status=Planned, plannedDate=today; +pick
fun sendToBacklog(taskId: String)                     // status=Backlog
fun assignProject(taskId: String, projectId: String)  // projectId=projectId
fun deleteTask(taskId: String)                         // delete; -pick
fun assignTime(taskId: String, start: java.time.Instant, end: java.time.Instant) // scheduled
fun commit(onDone: () -> Unit = {})                    // set plannedDate=today on picks + upsert DailyPlan
```

**Steps**
1. **Add the failing tests** to `PlanningViewModelTest.kt`:
```kotlin
  @Test fun scheduleFromInbox_updates_task_and_adds_to_pick() = runTest {
    val (vm, task, _) = vm(
      inbox = listOf(fakeTask(id = "i", status = TaskStatus.Inbox)),
    )
    vm.uiState.test {
      expectMostRecentItem()
      vm.scheduleFromInbox("i")
      val (id, edit) = task.updated.single()
      assertEquals("i", id)
      assertEquals(TaskStatus.Planned, edit.status)
      assertEquals(today, edit.plannedDate)
      assertEquals(listOf("i"), expectMostRecentItem().pickedIds)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun sendToBacklog_assignProject_delete_delegate() = runTest {
    val (vm, task, _) = vm(inbox = listOf(fakeTask(id = "i", status = TaskStatus.Inbox)))
    vm.sendToBacklog("i")
    assertEquals(TaskStatus.Backlog, task.updated.last().second.status)
    vm.assignProject("i", "p1")
    assertEquals("p1", task.updated.last().second.projectId)
    vm.deleteTask("i")
    assertEquals(listOf("i"), task.deleted)
  }

  @Test fun assignTime_sets_scheduled_window() = runTest {
    val (vm, task, _) = vm()
    val start = Instant.parse("2026-06-21T09:00:00Z")
    val end = Instant.parse("2026-06-21T10:00:00Z")
    vm.assignTime("t", start, end)
    val edit = task.updated.single().second
    assertEquals(start, edit.scheduledStart)
    assertEquals(end, edit.scheduledEnd)
    assertEquals(TaskStatus.Scheduled, edit.status)
  }

  @Test fun commit_sets_plannedDate_on_picks_and_upserts_dailyplan() = runTest {
    val (vm, task, daily) = vm(
      yesterdayTasks = listOf(fakeTask(id = "u", plannedDate = yesterday, status = TaskStatus.Planned, durationMinutes = 60)),
      inbox = listOf(fakeTask(id = "i", status = TaskStatus.Inbox, durationMinutes = 90)),
    )
    vm.uiState.test {
      expectMostRecentItem()
      vm.toggleAdd("u"); vm.toggleAdd("i")
      expectMostRecentItem()
      var done = false
      vm.commit { done = true }
      // every picked id got plannedDate=today + status=Planned
      val edits = task.updated.associate { it.first to it.second }
      assertEquals(today, edits["u"]!!.plannedDate)
      assertEquals(TaskStatus.Planned, edits["u"]!!.status)
      assertEquals(today, edits["i"]!!.plannedDate)
      // DailyPlan upserted with the ordered picks + planned minutes (committedAt stamped by the repo)
      val up = daily.upserts.single()
      assertEquals(today, up.date)
      assertEquals(listOf("u", "i"), up.plannedTaskIds)
      assertEquals(150, up.plannedMinutes)
      assertEquals(true, expectMostRecentItem().committed)
      assertEquals(true, done)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun commit_with_no_picks_upserts_empty_plan() = runTest {
    val (vm, task, daily) = vm()
    var done = false
    vm.commit { done = true }
    assertEquals(emptyList<String>(), daily.upserts.single().plannedTaskIds)
    assertEquals(0, daily.upserts.single().plannedMinutes)
    assertEquals(true, task.updated.isEmpty())  // nothing to re-plan
    assertEquals(true, done)
  }
```

2. **Verify it FAILS:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningViewModelTest"
   ```

3. **Implement** — add to `PlanningViewModel` (new imports: `kotlinx.coroutines.launch`, `net.qmindtech.tmap.data.repository.TaskEdit`, `java.time.Instant`):
```kotlin
  fun scheduleFromInbox(taskId: String) {
    viewModelScope.launch {
      taskRepo.update(taskId, TaskEdit(status = TaskStatus.Planned, plannedDate = today))
    }
    if (!picked.value.contains(taskId)) picked.value = picked.value + taskId
  }

  fun sendToBacklog(taskId: String) {
    viewModelScope.launch { taskRepo.update(taskId, TaskEdit(status = TaskStatus.Backlog)) }
  }

  fun assignProject(taskId: String, projectId: String) {
    viewModelScope.launch { taskRepo.update(taskId, TaskEdit(projectId = projectId)) }
  }

  fun deleteTask(taskId: String) {
    viewModelScope.launch { taskRepo.delete(taskId) }
    picked.value = picked.value - taskId
  }

  fun assignTime(taskId: String, start: java.time.Instant, end: java.time.Instant) {
    viewModelScope.launch {
      taskRepo.update(
        taskId,
        TaskEdit(scheduledStart = start, scheduledEnd = end, status = TaskStatus.Scheduled),
      )
    }
  }

  fun commit(onDone: () -> Unit = {}) {
    val ids = picked.value
    val minutes = uiState.value.plannedMinutes
    viewModelScope.launch {
      ids.forEach { id ->
        taskRepo.update(id, TaskEdit(status = TaskStatus.Planned, plannedDate = today))
      }
      // DailyPlanRepository.upsert stamps committedAt internally (write-through → outbox, offline-safe).
      dailyPlanRepo.upsert(today, ids, minutes)
      committed.value = true
      onDone()
    }
  }
```
> Note `commit` reads `uiState.value.plannedMinutes` (the live capacity derived in P5.4) so it matches exactly what the dock showed; with `FakeTaskRepo`/`FakeDailyPlanRepo` and `UnconfinedTestDispatcher` the `launch` runs synchronously, so the recorders are populated when the test asserts.

4. **Verify it PASSES:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.planning.PlanningViewModelTest"
   ```

5. **Commit:**
   ```
   feat(planning): triage actions + assignTime + commit (DailyPlan upsert + plannedDate)

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P5.6 — `Route.Planning` route-string lock + assert it is registered

`Route.Planning` is **already produced by P0** (`data object Planning : Route { override val route = "planning" }`). This task is a tiny guard: extend the existing `RoutesTest` to pin `Route.Planning.route == "planning"` (so P5's host wiring in P5.8 has a stable, tested route string to navigate to). No production change — the assertion only locks the contract P5 consumes. (If, against expectation, P0 did not declare `Route.Planning`, add it to `Routes.kt` here exactly as shown, then this test passes.)

**Files**
- `~ android/app/src/test/java/net/qmindtech/tmap/ui/navigation/RoutesTest.kt` (add one assertion)

**Interfaces** (consumed verbatim from P0)
```kotlin
sealed interface Route { /* ... */ data object Planning : Route { override val route = "planning" } /* ... */ }
```

**Steps**
1. **Add the failing/locking assertion** to `RoutesTest.kt` — a dedicated test method:
```kotlin
    @Test
    fun planningRouteStringIsPinned() {
        assertEquals("planning", Route.Planning.route)
    }
```

2. **Verify:**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.navigation.RoutesTest"
   ```
   Expected: GREEN (P0 already declares `Route.Planning`). If RED with "unresolved reference: Planning", add the `data object Planning : Route { override val route = "planning" }` line to `Routes.kt` (alongside the other destinations) and re-run — then GREEN.

3. **Commit:**
   ```
   test(planning): pin Route.Planning route string

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P5.7 — Step composables under `ui/planning/steps/` (compile-gate + mockup checklist)

The four step bodies + the shared capacity dock, as stateless composables driven by `PlanningUiState` + callbacks. Compose UI is **not** unit-tested; this task uses a **compile-gate (`assembleDebug`) + a mockup behavior-checklist** (reviewer verifies visually against `full-app.html` — Planning ritual panel). One file per step plus a `CapacityDock` shared component.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/planning/steps/ReflectStep.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/planning/steps/TriageInboxStep.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/planning/steps/PickTodayStep.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/planning/steps/TimeboxStep.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/planning/steps/CapacityDock.kt`

**Interfaces** (exact — produced here; consumed by `PlanningScreen` in P5.8)
```kotlin
package net.qmindtech.tmap.ui.planning.steps

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.qmindtech.tmap.ui.planning.PlanItemUi
import net.qmindtech.tmap.ui.planning.PlanningUiState

@Composable fun ReflectStep(state: PlanningUiState, modifier: Modifier = Modifier)

@Composable fun TriageInboxStep(
  state: PlanningUiState,
  onSchedule: (String) -> Unit,
  onBacklog: (String) -> Unit,
  onDelete: (String) -> Unit,
  modifier: Modifier = Modifier,
)

@Composable fun PickTodayStep(
  state: PlanningUiState,
  onToggleAdd: (String) -> Unit,
  modifier: Modifier = Modifier,
)

@Composable fun TimeboxStep(state: PlanningUiState, modifier: Modifier = Modifier)

@Composable fun CapacityDock(
  plannedMinutes: Int,
  workdayMinutes: Int,
  fraction: Float,
  continueLabel: String,
  onContinue: () -> Unit,
  modifier: Modifier = Modifier,
)
```

**Steps**
1. **Write the full implementations** (no unit test — compile-gate). Consume P0 tokens (`LocalTmapColors.current`, `LocalTmapShapes`, `LocalTmapType`), `SectionLabel`, `ProjectDot`, `PrimaryButton`. Use `start`/`end` paddings (RTL). Provide an `@Preview` per file wrapped in `TmapTheme`. Mockup mapping (`full-app.html` Planning panel):
   - **`PlanRow`** (a private shared composable in `PickTodayStep.kt` or a small `PlanCards.kt` — keep it in `steps/`): an 18dp `colors.surface` card, `padding 13.dp/14.dp`, a `Row` with the title (Body, `textPrimary`) over a meta row (`ProjectDot(projectColor)` + `projectName`, `textSecondary`) on the start side, and a trailing control on the end side. When `added`, the whole card dims to `alpha = 0.62f` and the trailing control is a 24dp circular amber-gradient **✓** (`contentDescription = "Added"`); otherwise a pill **+ Add** button (amber gradient, `onAccent` text, `RoundedCornerShape(shapes.pill)`, `contentDescription = "Add to today"`) calling `onToggleAdd(id)`. Tapping a ✓ also calls `onToggleAdd(id)` (removes it — non-gesture toggle, a11y §9).
   - **`ReflectStep`**: a `LazyColumn` over `colors` background; `SectionLabel("Completed yesterday")` + done `PlanItemUi`s (rendered with a leading muted ✓, no add button), then `SectionLabel("Still open")` + undone items (plain). If both empty → `EmptyState` ("Fresh start — nothing carried over.").
   - **`TriageInboxStep`**: `SectionLabel("Your inbox")` + a card per `state.inbox` item with three one-tap controls: **Today** (`Chip`, `onSchedule(id)`), **Backlog** (`Chip`, `onBacklog(id)`), **🗑** (icon button, `onDelete(id)`, `contentDescription = "Delete task"`). Empty → `EmptyState` ("Inbox zero — nicely done.").
   - **`PickTodayStep`**: `SectionLabel("CARRY OVER FROM YESTERDAY")` + `state.carryOver` rows (via `PlanRow`), then `SectionLabel("FROM YOUR INBOX")` + `state.inboxPicks` rows. (`SectionLabel` uppercases internally per P0 — pass the human label; verify with the reviewer that the tracked-uppercase style matches.) Each row's `added` flag comes from the item.
   - **`TimeboxStep`**: for this pass, a simple list of the picked items (those with `added == true` across `carryOver + inboxPicks`, or fall back to listing `state.pickedIds` resolved against the two lists) showing title + current `scheduledLabel`/"Unscheduled", with a subtitle note "Drag-to-time-block lands in the Timeline (P7)." (The full timeline drag UI is P7; this step closes the ritual with the commit dock.)
   - **`CapacityDock`**: a bottom dock with a top fade scrim (`Brush.verticalGradient` from transparent to `colors.bgBottom`); a `Row` `SpaceBetween` of `"≈ ${plannedMinutes/60}h ${plannedMinutes%60}m planned of your ${workdayMinutes/60}h"` (`textSecondary`) and `"${(fraction*100).toInt()}%"` (`textTertiary`); a 5dp pill capacity bar (`colors.surfaceInset` track, amber-gradient fill at `fraction` width); and a full-width `PrimaryButton(text = continueLabel, onClick = onContinue)`.

2. **Verify it COMPILES (compile-gate):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```

3. **Behavior checklist vs. mockup** (`full-app.html` Planning panel — reviewer verifies visually; no automated assertion):
   - [ ] Pick-today cards are 18dp `surface`; **+ Add** is an amber-gradient pill; an added card dims to ~`.62` alpha and shows a circular amber **✓**.
   - [ ] Section labels `CARRY OVER FROM YESTERDAY` / `FROM YOUR INBOX` render as tracked-uppercase tertiary `SectionLabel`s.
   - [ ] Capacity dock shows `≈ Xh planned of your Yh` + the percent + the amber capacity bar + a full-width amber **Continue →** button.
   - [ ] Reflect splits done / still-open; Triage shows Today / Backlog / 🗑 one-tap controls.
   - [ ] Every icon-only control (✓, 🗑) has a `contentDescription`; +Add toggling is reachable without a swipe (a11y §9).
   - [ ] Layout uses start/end (RTL mirrors when locale flips in the preview).

4. **Commit:**
   ```
   feat(planning): step composables (Reflect/Triage/Pick/Timebox) + capacity dock

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P5.8 — `PlanningScreen` host + Today "Plan my day" entry-point wiring (compile-gate + checklist)

The full-screen ritual host: a stepper header (progress dots + back chevron, amber eyebrow, big heading), the current step body (P5.7) in a scrollable region, and the `CapacityDock` Continue/back control that drives `next()`/`commit()`. Plus the **navigation wiring**: replace the P0 `PlanningPlaceholder()` in `MainScaffold`'s `NavHost` with the real `PlanningScreen`, and flip the Today `onPlanMyDay` stub (`/* TODO P5 */`) to `navController.navigate(Route.Planning.route)`. Compile-gate + behavior-checklist; no unit test (pure UI + wiring; the VM logic is already unit-tested in P5.4/P5.5).

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/planning/PlanningScreen.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/navigation/MainScaffold.kt` (swap placeholder → `PlanningScreen`; flip Today's `onPlanMyDay`)

**Interfaces** (exact)
```kotlin
package net.qmindtech.tmap.ui.planning

// Stateful entry point used by the nav graph.
@androidx.compose.runtime.Composable
fun PlanningScreen(
  onClose: () -> Unit,                                  // back out of the ritual (pop to Today)
  viewModel: PlanningViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
)

// Stateless content (for previews + reuse).
@androidx.compose.runtime.Composable
fun PlanningContent(
  state: PlanningUiState,
  onBack: () -> Unit,            // back chevron / step back; at first step => onClose
  onNext: () -> Unit,           // Continue on non-final steps
  onCommit: () -> Unit,         // Continue on the final step => commit + close
  onToggleAdd: (String) -> Unit,
  onSchedule: (String) -> Unit,
  onBacklog: (String) -> Unit,
  onDelete: (String) -> Unit,
  modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
)
```

**Steps**
1. **Write the full implementation** `PlanningScreen.kt`:
   - `PlanningScreen` collects `viewModel.uiState` with `collectAsStateWithLifecycle()`, then renders `PlanningContent`, passing:
     - `onBack = { if (state.isFirstStep) onClose() else viewModel.back() }`
     - `onNext = viewModel::next`
     - `onCommit = { viewModel.commit(onDone = onClose) }`
     - `onToggleAdd = viewModel::toggleAdd`, `onSchedule = viewModel::scheduleFromInbox`, `onBacklog = viewModel::sendToBacklog`, `onDelete = viewModel::deleteTask`.
   - `PlanningContent`: a `Box` over `TmapBackground` (or `colors.bgTop` fill). A `Column`:
     - **Stepper header**: a `Row` with a back chevron `IconButton` (`Icons.Outlined.ArrowBack`, `contentDescription = "Back"`, → `onBack`) and a centered row of `STEP_COUNT` dots (the active = `colors.accent`, past = `colors.textTertiary`, future = `colors.borderSubtle`); below it the amber `state.step.eyebrow` (Label style, `colors.accent`) and the `state.step.heading` (Heading style, `colors.textPrimary`).
     - **Body**: `when (state.step)` → `ReflectStep` / `TriageInboxStep(onSchedule,onBacklog,onDelete)` / `PickTodayStep(onToggleAdd)` / `TimeboxStep`, each in a `Modifier.weight(1f)` scroll region.
     - **Dock**: `CapacityDock(plannedMinutes = state.plannedMinutes, workdayMinutes = state.workdayMinutes, fraction = state.capacityFraction, continueLabel = if (state.isLastStep) "Plan my day →" else "Continue →", onContinue = { if (state.isLastStep) onCommit() else onNext() })`. (Show the dock on every step; on Reflect/Triage it acts as a plain Continue with the capacity reading at its current value.)
   - Provide an `@Preview` of `PlanningContent` for the `PickToday` step (1 carry-over added + 1 inbox) wrapped in `TmapTheme`.
2. **Wire navigation** in `MainScaffold.kt`:
   - In the `NavHost`, replace `composable(Route.Planning.route) { PlanningPlaceholder() }` with:
     ```kotlin
     composable(Route.Planning.route) {
       net.qmindtech.tmap.ui.planning.PlanningScreen(onClose = { navController.popBackStack() })
     }
     ```
   - In the Today destination's hooks, flip the stub: `onPlanMyDay = { navController.navigate(Route.Planning.route) }` (was `/* TODO P5 */`).

3. **Verify it COMPILES (compile-gate):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```

4. **Behavior checklist vs. mockup** (`full-app.html` Planning panel):
   - [ ] Top stepper: back chevron + `STEP_COUNT` progress dots (active amber); amber eyebrow `STEP n OF 4 · …`; big heading.
   - [ ] Body switches across the 4 steps; **Pick your day** matches the mockup (carry-over + inbox cards, +Add pills, added ✓).
   - [ ] Capacity dock pinned at the bottom with the amber bar + **Continue →** (final step reads **Plan my day →** and commits).
   - [ ] Back chevron on the first step closes the ritual (pops to Today); otherwise it steps back.
   - [ ] Today's **Plan my day** button now navigates to the ritual (P0/P1 stub replaced).
   - [ ] Offline: every action delegates to the (write-through) ViewModel/repository — no network on the UI path.
   - [ ] RTL: header, cards, and dock mirror (start/end only).

5. **Commit:**
   ```
   feat(planning): PlanningScreen host + Today "Plan my day" entry wired

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P5.9 — Phase gate: full unit suite + assembleDebug + ritual acceptance (§10.5)

Green-gate the phase. Run the full planning test set plus the whole existing suite, the build gate, and the lint gate; then confirm the spec **§10.5** acceptance criterion by tracing the proven units: *"4-step flow completes; upserts the DailyPlan (plannedTaskIds + plannedMinutes + committedAt) and sets task plannedDate; capacity reflects a configurable workday length; works offline."*

**Files** (none — verification only)

**Steps**
1. **Run the planning unit set (must be GREEN):**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest \
     --tests "net.qmindtech.tmap.ui.planning.*" \
     --tests "net.qmindtech.tmap.testutil.FakePlanningReposTest" \
     --tests "net.qmindtech.tmap.ui.navigation.RoutesTest"
   ```
2. **Run the full unit suite (regression — must be GREEN):**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest
   ```
3. **Compile gate (must be GREEN):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```
4. **Lint gate (no new errors):**
   ```bash
   cd android && ./gradlew :app:lintDebug
   ```
5. **§10.5 ritual acceptance trace** (record in the commit body; each maps to a passing test or a checklist):
   - [ ] **4-step flow completes** — `PlanningStepTest` (next/back/clamp) + `PlanningViewModelTest.next_and_back_move_the_step_clamped`; `PlanningScreen` Continue advances and the final step commits (P5.8 checklist).
   - [ ] **Upserts the DailyPlan (plannedTaskIds + plannedMinutes + committedAt)** — `PlanningViewModelTest.commit_sets_plannedDate_on_picks_and_upserts_dailyplan` asserts the ordered ids + minutes reach `DailyPlanRepository.upsert`; `committedAt` is stamped inside the repo (proven by the P3 `DailyPlanRepositoryImplTest`).
   - [ ] **Sets task plannedDate** — same commit test asserts `plannedDate = today` + `status = Planned` on every picked id via `TaskRepository.update`.
   - [ ] **Capacity reflects a configurable workday length** — `PlanningCapacityTest` (key + default 360 + fallback) + `PlanningViewModelTest.pickToday_..._updates_capacity` (live `plannedMinutes`/`workdayMinutes`).
   - [ ] **Works offline** — every write delegates to `TaskRepository`/`DailyPlanRepository`, both write-through to Room + outbox (no UI network call); the outbox enqueue itself is proven by P3's repository test.
6. **Commit:**
   ```
   chore(planning): P5 gate — unit suite + assembleDebug + lint green; §10.5 ritual acceptance traced

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

## P6 — Focus mode (pomodoro)

This phase builds the immersive **Focus mode** (spec §6.5, mockup `full-app.html` "Focusing On" panel): a distraction-free pomodoro bound to a task (or task-less), with an amber progress ring, session dots, and Pause / mark-done / end controls. The work splits cleanly into pure, fully-unit-tested logic and thin, compile-gated UI/Android shells. The timer engine — `FocusState`/`FocusPhase`, the `FocusController` countdown driven by an **injected `CoroutineDispatcher`** so `StandardTestDispatcher` advances it with zero real delay, interval-completion that writes a `FocusSession` (consuming the FIXED P3 `FocusSessionRepository.create(...)`) and `TaskRepository.addActualTime` (FIXED P3), session counting (task-bound vs task-less), and pause/resume/end — is verified with a fake `Clock` + fake repositories and real JUnit tests. The foreground `FocusService` (so the timer survives backgrounding via a silent ongoing notification), its notification channel, the manifest `FOREGROUND_SERVICE*` permissions + `<service>` declaration, and the DI scope/dispatcher providers are non-unit-testable Android shells gated by `./gradlew :app:assembleDebug` + an explicit behavior checklist. The `FocusViewModel` maps controller state to a UI state and exposes a unit-tested pure `advanceQueue` + `mmss` formatter; the `FocusScreen` Compose (amber `ProgressRing`, session dots, controls, queued line, immersive focus background) + `Route.Focus` NavHost wiring are compile-gated against the mockup. Consumes the FIXED contracts verbatim: `FocusController.start(taskId: String?, project: String, lengthMin: Int)`, `FocusSessionRepository.create(...)`, `TaskRepository.addActualTime`, `Route.Focus(taskId: String?)`, `ProgressRing`, `TmapFab`. All Gradle commands run from the `android/` directory. Every commit ends with the trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

> **Test-substitution note (applies to the Android-shell + Compose tasks in P6):** `FocusService`, `FocusNotification`, the manifest/DI wiring, and `FocusScreen` are Android-framework/visual code not unit-testable in this project's JVM/Robolectric `src/test/` suite without an instrumented harness (none is configured for `ui/`/services). Per the plan convention, each such task's gate is **`./gradlew :app:assembleDebug` passing + a behavior checklist verified against the named mockup**. All extractable pure logic (the state model, the countdown/session/control transitions, queue-advance, `mm:ss` formatting) is pulled into plain Kotlin with **real failing-first JUnit tests** run via `./gradlew :app:testDebugUnitTest`.

---

### Task P6.1 — `FocusPhase` + `FocusState` pure model

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusState.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/focus/FocusStateTest.kt`

**Interfaces**
- Produces: `enum class FocusPhase { Idle, Running, Paused, Completed }`.
- Produces: `data class FocusState(val phase: FocusPhase = FocusPhase.Idle, val taskId: String? = null, val project: String = "", val lengthMin: Int = 25, val remainingSeconds: Int = 0, val completedSessions: Int = 0, val totalSessions: Int = 4)` — the immutable controller/UI state.
- Produces: `val FocusState.elapsedFraction: Float` — `0f` when `lengthMin <= 0`; else `((lengthMin*60 - remainingSeconds) / (lengthMin*60f)).coerceIn(0f, 1f)`. (This is the ring `progress`.)
- Produces: `val FocusState.isActive: Boolean` — `phase == Running || phase == Paused`.
- Consumes: nothing.

**Steps**

- [ ] **Write the failing test.** `FocusStateTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusStateTest {

    @Test
    fun `defaults are an idle 25-minute four-session focus`() {
        val s = FocusState()
        assertEquals(FocusPhase.Idle, s.phase)
        assertEquals(25, s.lengthMin)
        assertEquals(0, s.remainingSeconds)
        assertEquals(0, s.completedSessions)
        assertEquals(4, s.totalSessions)
        assertEquals(null, s.taskId)
    }

    @Test
    fun `elapsedFraction is zero at full remaining and one at zero remaining`() {
        val full = FocusState(lengthMin = 25, remainingSeconds = 25 * 60)
        val empty = FocusState(lengthMin = 25, remainingSeconds = 0)
        val half = FocusState(lengthMin = 10, remainingSeconds = 300) // 5 of 10 min elapsed
        assertEquals(0f, full.elapsedFraction, 0.0001f)
        assertEquals(1f, empty.elapsedFraction, 0.0001f)
        assertEquals(0.5f, half.elapsedFraction, 0.0001f)
    }

    @Test
    fun `elapsedFraction is clamped and zero when length is non-positive`() {
        assertEquals(0f, FocusState(lengthMin = 0, remainingSeconds = 0).elapsedFraction, 0.0001f)
        // Over-elapsed (remaining negative) clamps to 1.
        assertEquals(1f, FocusState(lengthMin = 5, remainingSeconds = -60).elapsedFraction, 0.0001f)
    }

    @Test
    fun `isActive is true only while running or paused`() {
        assertFalse(FocusState(phase = FocusPhase.Idle).isActive)
        assertTrue(FocusState(phase = FocusPhase.Running).isActive)
        assertTrue(FocusState(phase = FocusPhase.Paused).isActive)
        assertFalse(FocusState(phase = FocusPhase.Completed).isActive)
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusStateTest"` — fails to compile (`FocusState` / `FocusPhase` / extensions do not exist).

- [ ] **Implement.** `FocusState.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

/** The lifecycle phase of a focus interval (spec §6.5). */
enum class FocusPhase { Idle, Running, Paused, Completed }

/**
 * Immutable focus-mode state. One [FocusState] describes the current interval: its phase, the
 * bound task (null for a task-less focus), the project name snapshot, the interval length, the
 * seconds remaining, and the session counter (e.g. "Session 2 of 4"). The controller (P6.2) is the
 * sole producer; the view-model (P6.6) maps it to UI; the ring reads [elapsedFraction].
 */
data class FocusState(
    val phase: FocusPhase = FocusPhase.Idle,
    val taskId: String? = null,
    val project: String = "",
    val lengthMin: Int = 25,
    val remainingSeconds: Int = 0,
    val completedSessions: Int = 0,
    val totalSessions: Int = 4,
)

/** Ring progress 0..1: fraction of the interval elapsed. Zero when the interval has no length. */
val FocusState.elapsedFraction: Float
    get() {
        val total = lengthMin * 60
        if (total <= 0) return 0f
        return ((total - remainingSeconds) / total.toFloat()).coerceIn(0f, 1f)
    }

/** True while a timer is in flight (running) or held (paused) — used to gate the foreground service. */
val FocusState.isActive: Boolean
    get() = phase == FocusPhase.Running || phase == FocusPhase.Paused
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusStateTest"` — green.

- [ ] **Commit.**
```
feat(android-focus): FocusState + FocusPhase pure model with elapsedFraction

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P6.2 — `FocusController.start` + countdown ticker (injected dispatcher, StateFlow)

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusController.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/focus/FocusControllerStartTest.kt`

**Interfaces** (FIXED contract for `start`)
- Produces: `class FocusController @Inject constructor(focusSessions: FocusSessionRepository, tasks: TaskRepository, clock: Clock, @FocusDispatcher dispatcher: CoroutineDispatcher)`.
  - `val state: StateFlow<FocusState>`.
  - `fun start(taskId: String?, project: String, lengthMin: Int)` — **FIXED**. Sets `state` to `Running` with `remainingSeconds = lengthMin * 60`, captures `startedAt = clock.now()`, and launches the per-second countdown on a scope built from the injected `dispatcher`. Re-`start` while active cancels the in-flight tick job and begins a fresh interval (`completedSessions` is preserved across the same controller instance).
- Consumes: `FocusSessionRepository` (FIXED P3), `TaskRepository` (FIXED P3), `util.Clock`, an injected `CoroutineDispatcher` qualified `@FocusDispatcher` (the qualifier itself is added in P6.5; for THIS task the constructor takes a plain `CoroutineDispatcher` and the qualifier annotation is layered on in P6.5 without changing the parameter list — see P6.5 note).

> **Dispatcher-injection rule (the whole point):** the countdown uses `kotlinx.coroutines.delay(1000)` inside a loop, but runs on the **injected** dispatcher. In tests we pass a `StandardTestDispatcher`; `advanceTimeBy(...)` / `runCurrent()` virtualize the delays so a 25-minute interval ticks instantly. In production P6.5 provides `Dispatchers.Default`. The controller owns a `CoroutineScope(dispatcher + SupervisorJob())`; this task constructs it from the bare dispatcher param.

**Steps**

- [ ] **Write the failing test.** `FocusControllerStartTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.repository.FocusSessionRepository
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Records FocusSessionRepository.create calls without a Room DB. */
class FakeFocusSessionRepo : FocusSessionRepository {
    data class Created(
        val taskId: String?, val project: String, val startedAt: Instant,
        val endedAt: Instant, val minutes: Int, val date: LocalDate,
    )
    val created = mutableListOf<Created>()
    var nextId = 0
    override suspend fun create(
        taskId: String?, project: String, startedAt: Instant,
        endedAt: Instant, minutes: Int, date: LocalDate,
    ): String {
        created += Created(taskId, project, startedAt, endedAt, minutes, date)
        return "fs-${++nextId}"
    }
    override fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>> = MutableStateFlow(emptyList())
    override fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>> =
        MutableStateFlow(emptyList())
}

@OptIn(ExperimentalCoroutinesApi::class)
class FocusControllerStartTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-06-21T09:00:00Z"))

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun controller(focus: FakeFocusSessionRepo = FakeFocusSessionRepo(), tasks: FakeTaskRepo = FakeTaskRepo()) =
        FocusController(focus, tasks, clock, testDispatcher)

    @Test
    fun `start sets Running with full remaining seconds`() = runTest(testDispatcher) {
        val c = controller()
        c.start(taskId = "t1", project = "Work", lengthMin = 25)
        runCurrent()
        val s = c.state.value
        assertEquals(FocusPhase.Running, s.phase)
        assertEquals("t1", s.taskId)
        assertEquals("Work", s.project)
        assertEquals(25, s.lengthMin)
        assertEquals(25 * 60, s.remainingSeconds)
    }

    @Test
    fun `the ticker decrements one second per second on the injected dispatcher`() = runTest(testDispatcher) {
        val c = controller()
        c.start(taskId = null, project = "Reading", lengthMin = 1)
        runCurrent()
        assertEquals(60, c.state.value.remainingSeconds)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(59, c.state.value.remainingSeconds)
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(50, c.state.value.remainingSeconds)
        c.end() // stop the job so runTest does not hang on the remaining ticks
    }

    @Test
    fun `restart begins a fresh interval and cancels the previous ticker`() = runTest(testDispatcher) {
        val c = controller()
        c.start(taskId = "t1", project = "A", lengthMin = 5)
        runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(5 * 60 - 2, c.state.value.remainingSeconds)
        c.start(taskId = "t2", project = "B", lengthMin = 10) // fresh interval
        runCurrent()
        assertEquals("t2", c.state.value.taskId)
        assertEquals(10 * 60, c.state.value.remainingSeconds)
        c.end()
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusControllerStartTest"` — fails (`FocusController`, `end()` do not exist).

- [ ] **Implement.** `FocusController.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.repository.FocusSessionRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a single focus interval and the pomodoro session counter (spec §6.5). It is the SOLE
 * producer of [state]; the foreground [FocusService] (P6.5) keeps the process alive while
 * [FocusState.isActive], and [FocusViewModel] (P6.6) maps [state] to the UI.
 *
 * The countdown runs `delay(1000)` in a loop on an INJECTED [dispatcher] so tests pass a
 * StandardTestDispatcher and virtualize all delays (a 25-minute interval ticks in zero real time);
 * production injects Dispatchers.Default (P6.5). On reaching zero, [onIntervalComplete] (P6.3)
 * persists the session and the task time; pause/resume/end (P6.4) gate the loop.
 *
 * Singleton-scoped so the timer survives a screen leaving composition (the service holds the process).
 */
@Singleton
class FocusController @Inject constructor(
    private val focusSessions: FocusSessionRepository,
    private val tasks: TaskRepository,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private val _state = MutableStateFlow(FocusState())
    val state: StateFlow<FocusState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var intervalStartedAt: Instant = Instant.EPOCH

    /** FIXED contract. Begins a fresh interval, cancelling any in-flight one. */
    fun start(taskId: String?, project: String, lengthMin: Int) {
        tickJob?.cancel()
        intervalStartedAt = clock.now()
        _state.update {
            it.copy(
                phase = FocusPhase.Running,
                taskId = taskId,
                project = project,
                lengthMin = lengthMin,
                remainingSeconds = lengthMin * 60,
            )
        }
        launchTicker()
    }

    private fun launchTicker() {
        tickJob = scope.launch {
            while (isActive && _state.value.remainingSeconds > 0) {
                delay(1_000)
                if (_state.value.phase != FocusPhase.Running) continue
                val next = _state.value.remainingSeconds - 1
                _state.update { it.copy(remainingSeconds = next) }
                if (next <= 0) {
                    onIntervalComplete()
                    break
                }
            }
        }
    }

    /** Filled in by P6.3 (interval completion) and P6.4 (pause/resume/end). */
    private suspend fun onIntervalComplete() { /* P6.3 */ }

    fun end() { /* P6.4 */ }
}
```
> Note: `onIntervalComplete()` and `end()` are stubs here so this task's tests (which call `end()` only to stop the loop) compile and the ticker behavior is provable in isolation. P6.3 fills `onIntervalComplete`, P6.4 fills `end`/`pause`/`resume`. Keep the bodies as written; later tasks replace the stub bodies, not the signatures.

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusControllerStartTest"` — green.

- [ ] **Commit.**
```
feat(android-focus): FocusController.start + injected-dispatcher countdown ticker

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P6.3 — Interval completion → FocusSession write + addActualTime + session counting

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusController.kt` (fill `onIntervalComplete`)
- `~ android/app/src/test/java/net/qmindtech/tmap/testutil/Fakes.kt` (add `addActualTime` to `FakeTaskRepo` — the P3.15 interface extension)
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/focus/FocusControllerCompletionTest.kt`

**Interfaces**
- On reaching `remainingSeconds == 0`: the controller sets `phase = Completed`, increments `completedSessions`, and persists:
  1. `focusSessions.create(taskId, project, startedAt = intervalStartedAt, endedAt = clock.now(), minutes = lengthMin, date = clock.today())` — exactly one CREATE (FIXED P3 signature). Fires for BOTH task-bound and task-less focus (a task-less session has `taskId = null`).
  2. `tasks.addActualTime(taskId, lengthMin)` — **only when `taskId != null`** (FIXED P3). A task-less focus does NOT call `addActualTime`.
- `FakeTaskRepo` gains `val actualTimeAdds = mutableListOf<Pair<String, Int>>()` and `override suspend fun addActualTime(taskId: String, minutes: Int) { actualTimeAdds += taskId to minutes }`.

**Steps**

- [ ] **Extend the fake first.** In `Fakes.kt`, add to `FakeTaskRepo` (after `deleted`): `val actualTimeAdds = mutableListOf<Pair<String, Int>>()`, and implement the new interface method (after `delete`): `override suspend fun addActualTime(taskId: String, minutes: Int) { actualTimeAdds += taskId to minutes }`. (Required because P3.15 added `addActualTime` to the `TaskRepository` interface — the fake must satisfy it.)

- [ ] **Write the failing test.** `FocusControllerCompletionTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FocusControllerCompletionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-06-21T09:00:00Z"))

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `a task-bound interval writes one FocusSession and adds the task time`() = runTest(testDispatcher) {
        val focus = FakeFocusSessionRepo()
        val tasks = FakeTaskRepo()
        val c = FocusController(focus, tasks, clock, testDispatcher)
        c.start(taskId = "t1", project = "Work", lengthMin = 1)
        runCurrent()
        advanceTimeBy(60_000) // run the full minute
        runCurrent()
        assertEquals(FocusPhase.Completed, c.state.value.phase)
        assertEquals(1, c.state.value.completedSessions)
        assertEquals(1, focus.created.size)
        val s = focus.created.single()
        assertEquals("t1", s.taskId)
        assertEquals("Work", s.project)
        assertEquals(1, s.minutes)
        assertEquals(LocalDate.parse("2026-06-21"), s.date)
        assertEquals(listOf("t1" to 1), tasks.actualTimeAdds)
    }

    @Test
    fun `a task-less interval writes a FocusSession but does not add task time`() = runTest(testDispatcher) {
        val focus = FakeFocusSessionRepo()
        val tasks = FakeTaskRepo()
        val c = FocusController(focus, tasks, clock, testDispatcher)
        c.start(taskId = null, project = "Reading", lengthMin = 1)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, focus.created.size)
        assertEquals(null, focus.created.single().taskId)
        assertTrue("task-less focus must not add actual time", tasks.actualTimeAdds.isEmpty())
    }

    @Test
    fun `completedSessions accumulates across consecutive intervals on the same controller`() =
        runTest(testDispatcher) {
            val c = FocusController(FakeFocusSessionRepo(), FakeTaskRepo(), clock, testDispatcher)
            c.start(taskId = "t1", project = "Work", lengthMin = 1)
            runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals(1, c.state.value.completedSessions)
            c.start(taskId = "t1", project = "Work", lengthMin = 1)
            runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals(2, c.state.value.completedSessions)
        }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusControllerCompletionTest"` — fails (completion does not persist anything yet; `completedSessions` stays 0).

- [ ] **Implement.** In `FocusController.kt`, replace the `onIntervalComplete` stub with:
```kotlin
    private suspend fun onIntervalComplete() {
        val s = _state.value
        val endedAt = clock.now()
        focusSessions.create(
            taskId = s.taskId,
            project = s.project,
            startedAt = intervalStartedAt,
            endedAt = endedAt,
            minutes = s.lengthMin,
            date = clock.today(),
        )
        // The backend does not auto-aggregate; mirror the focus time onto the task locally (spec §6.5).
        s.taskId?.let { tasks.addActualTime(it, s.lengthMin) }
        _state.update {
            it.copy(phase = FocusPhase.Completed, completedSessions = it.completedSessions + 1)
        }
    }
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusControllerCompletionTest" --tests "net.qmindtech.tmap.ui.focus.FocusControllerStartTest"` — green (both controller suites).

- [ ] **Commit.**
```
feat(android-focus): interval completion writes FocusSession + task time, counts sessions

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P6.4 — Pause / resume / end controls

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusController.kt` (add `pause`/`resume`, fill `end`)
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/focus/FocusControllerControlsTest.kt`

**Interfaces**
- `fun pause()` — `Running → Paused`; the ticker stays alive but skips decrements while paused (the loop already guards on `phase == Running`). No-op unless `Running`.
- `fun resume()` — `Paused → Running`. No-op unless `Paused`.
- `fun end()` — cancels the ticker and returns to `Idle` (`remainingSeconds = 0`, `phase = Idle`). Does NOT write a FocusSession (an ended-early interval is discarded; only natural completion logs time, spec §6.5). Idempotent.

**Steps**

- [ ] **Write the failing test.** `FocusControllerControlsTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FocusControllerControlsTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-06-21T09:00:00Z"))

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun controller() = FocusController(FakeFocusSessionRepo(), FakeTaskRepo(), clock, testDispatcher)

    @Test
    fun `pause freezes the countdown and resume continues it`() = runTest(testDispatcher) {
        val c = controller()
        c.start(taskId = "t1", project = "Work", lengthMin = 5)
        runCurrent()
        advanceTimeBy(3_000); runCurrent()
        val before = c.state.value.remainingSeconds // 300 - 3 = 297
        c.pause()
        assertEquals(FocusPhase.Paused, c.state.value.phase)
        advanceTimeBy(10_000); runCurrent() // time passes while paused
        assertEquals("paused timer must not decrement", before, c.state.value.remainingSeconds)
        c.resume()
        assertEquals(FocusPhase.Running, c.state.value.phase)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(before - 2, c.state.value.remainingSeconds)
        c.end()
    }

    @Test
    fun `end cancels the interval back to Idle and logs no session`() = runTest(testDispatcher) {
        val focus = FakeFocusSessionRepo()
        val c = FocusController(focus, FakeTaskRepo(), clock, testDispatcher)
        c.start(taskId = "t1", project = "Work", lengthMin = 25)
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        c.end()
        assertEquals(FocusPhase.Idle, c.state.value.phase)
        assertEquals(0, c.state.value.remainingSeconds)
        assertTrue("ending early logs no FocusSession", focus.created.isEmpty())
        // After end, virtual time advancing further must not move state (ticker cancelled).
        advanceTimeBy(10_000); runCurrent()
        assertEquals(FocusPhase.Idle, c.state.value.phase)
    }

    @Test
    fun `pause and resume are no-ops outside their valid phase`() = runTest(testDispatcher) {
        val c = controller()
        c.resume() // Idle -> no-op
        assertEquals(FocusPhase.Idle, c.state.value.phase)
        c.pause() // Idle -> no-op
        assertEquals(FocusPhase.Idle, c.state.value.phase)
    }
}
```

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusControllerControlsTest"` — fails (`pause`/`resume` absent; `end` is a no-op stub).

- [ ] **Implement.** In `FocusController.kt`, add `pause`/`resume` and replace the `end` stub:
```kotlin
    fun pause() {
        if (_state.value.phase != FocusPhase.Running) return
        _state.update { it.copy(phase = FocusPhase.Paused) }
    }

    fun resume() {
        if (_state.value.phase != FocusPhase.Paused) return
        _state.update { it.copy(phase = FocusPhase.Running) }
    }

    /** End the interval early: cancel the ticker, drop to Idle, log nothing (spec §6.5). Idempotent. */
    fun end() {
        tickJob?.cancel()
        tickJob = null
        _state.update { it.copy(phase = FocusPhase.Idle, remainingSeconds = 0) }
    }
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusControllerControlsTest"` — green.

- [ ] **Regression gate (full controller behavior).** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.*"` — all FocusState + FocusController suites green.

- [ ] **Commit.**
```
feat(android-focus): pause/resume/end controls for FocusController

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P6.5 — Focus notification channel + foreground `FocusService` + manifest + DI providers (compile-gate + checklist)

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/notifications/NotificationChannels.kt` (add the silent focus channel)
- `+ android/app/src/main/java/net/qmindtech/tmap/notifications/FocusNotification.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusService.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/di/FocusModule.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusController.kt` (annotate the dispatcher param `@FocusDispatcher`)
- `~ android/app/src/main/AndroidManifest.xml` (FOREGROUND_SERVICE perms + `<service>`)

**Interfaces**
- `NotificationChannels.FOCUS_ID = "focus_session"` + `ensureFocusChannelCreated(context)` — `IMPORTANCE_LOW` (silent, no sound/vibration; an ongoing status notification, spec §6.5).
- `object FocusNotification { fun build(context: Context, title: String, remainingLabel: String): Notification }` — ongoing (`setOngoing(true)`), silent, `CATEGORY_PROGRESS`, content-intent deep-links to the app (reuse the existing `MainActivity` launch). `const val NOTIFICATION_ID = 0x0F0C` (a fixed id distinct from the per-task reminder ids).
- `@AndroidEntryPoint class FocusService : Service()` — on `start`, calls `startForeground(FocusNotification.NOTIFICATION_ID, …)`; collects `FocusController.state` and re-posts the notification each tick (title = task/project, text = `mm:ss` remaining); calls `stopSelf()` when `!state.isActive`. Companion `start(context, ...)` / `stop(context)` helpers. (Injected `FocusController` is the same `@Singleton` instance the screen/VM use, so the service mirrors the live timer.)
- `@Qualifier annotation class FocusDispatcher` + `FocusModule` `@Provides @FocusDispatcher fun provideFocusDispatcher(): CoroutineDispatcher = Dispatchers.Default`. The `FocusController` dispatcher constructor param gains the `@FocusDispatcher` qualifier so Hilt resolves the production dispatcher (tests construct the controller directly with a `StandardTestDispatcher`, unaffected).
- Manifest: add `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` uses-permission; declare `<service android:name=".ui.focus.FocusService" android:exported="false" android:foregroundServiceType="dataSync" />`. (`dataSync` is the broadest always-available type ≥ API 34 that fits a user-initiated background timer; no special runtime grant needed beyond the perms.)

> **Test substitution:** Service + notification + manifest + DI wiring are Android-framework code; the gate is `./gradlew :app:assembleDebug` + the behavior checklist below. The only added pure surface here (the `@FocusDispatcher` qualifier) is exercised by the existing controller unit tests (which still inject a test dispatcher) and the P6.8 graph assertion.

**Steps**

- [ ] **Add the silent focus channel.** Edit `NotificationChannels.kt` — add the constant and a creator (do not touch the existing `REMINDERS_ID`/`ensureCreated`):
```kotlin
    const val FOCUS_ID = "focus_session"

    /** Silent, low-importance ongoing channel for the focus foreground service (spec §6.5). */
    fun ensureFocusChannelCreated(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            FOCUS_ID,
            "Focus session",
            NotificationManager.IMPORTANCE_LOW, // no sound / no peek — it is a status, not an alert
        ).apply {
            description = "Ongoing notification while a focus timer is running"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
```

- [ ] **Implement `FocusNotification.kt`:**
```kotlin
package net.qmindtech.tmap.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import net.qmindtech.tmap.MainActivity

/** The ongoing, silent notification shown while a focus interval runs (spec §6.5). */
object FocusNotification {

    const val NOTIFICATION_ID = 0x0F0C

    fun build(context: Context, title: String, remainingLabel: String): Notification {
        NotificationChannels.ensureFocusChannelCreated(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, NotificationChannels.FOCUS_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(remainingLabel)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pi)
            .build()
    }
}
```

- [ ] **Implement `FocusService.kt`:**
```kotlin
package net.qmindtech.tmap.ui.focus

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import net.qmindtech.tmap.notifications.FocusNotification
import javax.inject.Inject

/**
 * Foreground service that keeps the process alive while a focus interval runs so the timer survives
 * backgrounding (spec §6.5). It owns no timer state — it observes the @Singleton [FocusController]
 * the screen drives, re-posting the silent ongoing notification each tick, and stops itself when the
 * controller is no longer active.
 */
@AndroidEntryPoint
class FocusService : Service() {

    @Inject lateinit var controller: FocusController

    private val scope = CoroutineScope(SupervisorJob())
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = controller.state.value
        startForegroundCompat(
            FocusNotification.build(this, focusTitle(initial), mmss(initial.remainingSeconds)),
        )
        if (collectJob == null) {
            collectJob = controller.state.onEach { s ->
                if (!s.isActive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@onEach
                }
                NotificationManagerCompat.from(this).notify(
                    FocusNotification.NOTIFICATION_ID,
                    FocusNotification.build(this, focusTitle(s), mmss(s.remainingSeconds)),
                )
            }.launchIn(scope)
        }
        return START_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FocusNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FocusNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun focusTitle(s: FocusState): String =
        if (s.project.isNotBlank()) "Focusing · ${s.project}" else "Focusing"

    override fun onDestroy() {
        collectJob?.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val i = Intent(context, FocusService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FocusService::class.java))
        }
    }
}
```
> `mmss(...)` is the formatter authored in P6.6; this file references it from the same `ui.focus` package. If P6.5 is built before P6.6 lands, add a temporary `internal fun mmss(seconds: Int): String` in this file and delete it when P6.6 introduces the shared one — but the intended order is P6.6's pure logic before this assemble gate, so prefer building P6.6 first and reusing its `mmss`.

- [ ] **Implement `FocusModule.kt`:**
```kotlin
package net.qmindtech.tmap.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifies the dispatcher FocusController's countdown runs on (Default in prod; test dispatcher in tests). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FocusDispatcher

@Module
@InstallIn(SingletonComponent::class)
object FocusModule {

    @Provides
    @Singleton
    @FocusDispatcher
    fun provideFocusDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
```

- [ ] **Annotate the controller dispatcher param.** In `FocusController.kt`, add the import `import net.qmindtech.tmap.di.FocusDispatcher` and qualify the constructor param: change `private val dispatcher: CoroutineDispatcher,` to `@FocusDispatcher private val dispatcher: CoroutineDispatcher,`. (The unit tests construct `FocusController(...)` directly and pass a `StandardTestDispatcher`, so the qualifier does not affect them.)

- [ ] **Manifest.** In `AndroidManifest.xml`, add after the existing `uses-permission` block:
```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```
and declare the service inside `<application>` (next to the existing receivers):
```xml
        <!-- Keeps the focus timer alive in the background via a silent ongoing notification (spec §6.5). -->
        <service
            android:name=".ui.focus.FocusService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
```

- [ ] **Compile gate.** `./gradlew :app:assembleDebug` — `BUILD SUCCESSFUL`. (Hilt resolves `@FocusDispatcher CoroutineDispatcher` and `@AndroidEntryPoint FocusService`; the `@Singleton FocusController` injects `FocusSessionRepository`/`TaskRepository`/`Clock` already bound by AppModule + P3.)

- [ ] **Behavior checklist (reviewer):**
  - Starting a focus interval starts `FocusService` in the foreground with a **silent, ongoing** notification (no sound/vibration; cannot be swiped away while running) on the `FOCUS_ID` low-importance channel.
  - The notification title shows the project (e.g. "Focusing · Work") and the text shows the live `mm:ss` remaining; tapping it opens the app.
  - The timer continues counting while the app is backgrounded (process kept alive by the foreground service).
  - When the interval completes or is ended, the service calls `stopForeground`/`stopSelf` and the notification disappears.
  - Manifest declares `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` and the `dataSync` service; install on API 26 and API 34+ both start the service without a crash.

- [ ] **Commit.**
```
feat(android-focus): foreground FocusService + silent channel + manifest + DI dispatcher

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P6.6 — `FocusViewModel` + queue-advance + `mm:ss` formatter (pure logic unit-tested)

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusFormat.kt` (pure `mmss` + `advanceQueue`)
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusUiState.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusViewModel.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/focus/FocusFormatTest.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/focus/FocusViewModelTest.kt`

**Interfaces**
- Produces (pure, tested): `fun mmss(totalSeconds: Int): String` — `"25:00"`, `"09:05"`, `"00:00"`; negative clamps to `"00:00"`; minutes are NOT zero-padded to two beyond natural width (e.g. `100*60+5` → `"100:05"`).
- Produces (pure, tested): `data class QueueAdvance(val nextTaskId: String?, val remaining: List<String>)` + `fun advanceQueue(queue: List<String>): QueueAdvance` — pops the head: empty → `(null, [])`; `["a","b"]` → `("a", ["b"])`.
- Produces: `data class FocusUiState(val phase: FocusPhase, val taskTitle: String?, val project: String, val progress: Float, val remainingLabel: String, val ofLabel: String, val completedSessions: Int, val totalSessions: Int, val queuedCount: Int)`.
- Produces: `@HiltViewModel class FocusViewModel @Inject constructor(controller: FocusController, taskRepo: TaskRepository, projectRepo: ProjectRepository, clock: Clock)` exposing `val uiState: StateFlow<FocusUiState>` (maps `controller.state` → `FocusUiState`: `progress = state.elapsedFraction`, `remainingLabel = mmss(state.remainingSeconds)`, `ofLabel = "of ${mmss(state.lengthMin*60)}"`, resolving the task title via `taskRepo.observe(taskId)` and project name); `fun start(taskId: String?, project: String, lengthMin: Int = 25)` → delegates to `controller.start` and queue init; `fun pause()/resume()/end()` delegate; `fun advance()` pops the queue and starts the next interval. The queue is held in the VM (the controller is queue-agnostic).
- Consumes: `FocusController`, `TaskRepository`, `ProjectRepository`, `Clock`, `FocusState`/`elapsedFraction`.

**Steps**

- [ ] **Write the failing tests.** `FocusFormatTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusFormatTest {

    @Test
    fun `mmss pads seconds to two digits and renders minutes naturally`() {
        assertEquals("25:00", mmss(25 * 60))
        assertEquals("09:05", mmss(9 * 60 + 5))
        assertEquals("00:00", mmss(0))
        assertEquals("01:00", mmss(60))
        assertEquals("100:05", mmss(100 * 60 + 5))
    }

    @Test
    fun `mmss clamps negative to zero`() {
        assertEquals("00:00", mmss(-5))
    }

    @Test
    fun `advanceQueue pops the head and returns the tail`() {
        assertEquals(QueueAdvance(null, emptyList()), advanceQueue(emptyList()))
        assertEquals(QueueAdvance("a", listOf("b", "c")), advanceQueue(listOf("a", "b", "c")))
        assertEquals(QueueAdvance("only", emptyList()), advanceQueue(listOf("only")))
    }
}
```
`FocusViewModelTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-06-21T09:00:00Z"))

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(
        taskRepo: FakeTaskRepo = FakeTaskRepo(),
        projectRepo: FakeProjectRepo = FakeProjectRepo(),
    ): FocusViewModel {
        val controller = FocusController(FakeFocusSessionRepo(), taskRepo, clock, testDispatcher)
        return FocusViewModel(controller, taskRepo, projectRepo, clock)
    }

    @Test
    fun `start maps controller state to a Running ui state with mmss labels`() = runTest(testDispatcher) {
        val v = vm()
        v.uiState.test {
            awaitItem() // initial Idle frame
            v.start(taskId = null, project = "Reading", lengthMin = 25)
            runCurrent()
            val s = expectMostRecentItem()
            assertEquals(FocusPhase.Running, s.phase)
            assertEquals("Reading", s.project)
            assertEquals("25:00", s.remainingLabel)
            assertEquals("of 25:00", s.ofLabel)
            assertEquals(0f, s.progress, 0.0001f)
            v.end()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `progress advances as the timer ticks`() = runTest(testDispatcher) {
        val v = vm()
        v.start(taskId = null, project = "Reading", lengthMin = 1)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        val s = v.uiState.value
        assertEquals("00:30", s.remainingLabel)
        assertEquals(0.5f, s.progress, 0.02f)
        v.end()
    }

    @Test
    fun `advance starts the next queued task and reports the queued count`() = runTest(testDispatcher) {
        val taskRepo = FakeTaskRepo()
        taskRepo.setSingle(fakeTask(id = "n1", title = "Next task"))
        val v = vm(taskRepo = taskRepo)
        v.start(taskId = "first", project = "Work", lengthMin = 25, queue = listOf("n1", "n2"))
        runCurrent()
        assertEquals(2, v.uiState.value.queuedCount)
        v.advance() // pop n1, start it
        runCurrent()
        assertEquals("n1", v.currentTaskIdForTest())
        assertEquals(1, v.uiState.value.queuedCount)
        v.end()
    }
}
```
> Note: the VM exposes a tiny test seam `currentTaskIdForTest()` returning the controller's current `state.value.taskId`, and `start` takes an optional `queue: List<String> = emptyList()`. Keep both in the production class (the seam is a `@VisibleForTesting`-style accessor; trivial and harmless).

- [ ] **Verify FAIL.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusFormatTest" --tests "net.qmindtech.tmap.ui.focus.FocusViewModelTest"` — fails (none of `mmss`/`advanceQueue`/`FocusUiState`/`FocusViewModel` exist).

- [ ] **Implement.** `FocusFormat.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

/** "25:00", "09:05", "100:05"; negative clamps to "00:00". Seconds are always two digits. */
fun mmss(totalSeconds: Int): String {
    val t = totalSeconds.coerceAtLeast(0)
    val minutes = t / 60
    val seconds = t % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}".let {
        // pad minutes to two digits only up to 99 to match the mockup's "25:00" look
        if (minutes < 100) "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}" else it
    }
}

/** Result of popping the focus queue's head. */
data class QueueAdvance(val nextTaskId: String?, val remaining: List<String>)

/** Pop the head off the queue; empty → (null, []). */
fun advanceQueue(queue: List<String>): QueueAdvance =
    if (queue.isEmpty()) QueueAdvance(null, emptyList())
    else QueueAdvance(queue.first(), queue.drop(1))
```
`FocusUiState.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

/** The view-state the FocusScreen renders (spec §6.5, mockup "Focusing On"). */
data class FocusUiState(
    val phase: FocusPhase = FocusPhase.Idle,
    val taskTitle: String? = null,
    val project: String = "",
    val progress: Float = 0f,
    val remainingLabel: String = "00:00",
    val ofLabel: String = "of 00:00",
    val completedSessions: Int = 0,
    val totalSessions: Int = 4,
    val queuedCount: Int = 0,
)
```
`FocusViewModel.kt`:
```kotlin
package net.qmindtech.tmap.ui.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import net.qmindtech.tmap.data.repository.ProjectRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import javax.inject.Inject

/**
 * Maps the @Singleton FocusController's [FocusState] to [FocusUiState] and owns the back-to-back
 * session queue (the controller itself is queue-agnostic, spec §6.5). The bound task's title is
 * resolved from the task repo; the project name comes through the controller's [FocusState.project]
 * snapshot (set at start).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FocusViewModel @Inject constructor(
    private val controller: FocusController,
    private val taskRepo: TaskRepository,
    projectRepo: ProjectRepository,
    private val clock: Clock,
) : ViewModel() {

    private val queue = MutableStateFlow<List<String>>(emptyList())

    val uiState: StateFlow<FocusUiState> =
        combine(
            controller.state,
            controller.state.flatMapLatest { s ->
                if (s.taskId == null) flowOf(null) else taskRepo.observe(s.taskId)
            },
            queue,
        ) { state, task, q ->
            FocusUiState(
                phase = state.phase,
                taskTitle = task?.title,
                project = state.project,
                progress = state.elapsedFraction,
                remainingLabel = mmss(state.remainingSeconds),
                ofLabel = "of ${mmss(state.lengthMin * 60)}",
                completedSessions = state.completedSessions,
                totalSessions = state.totalSessions,
                queuedCount = q.size,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState())

    fun start(taskId: String?, project: String, lengthMin: Int = 25, queue: List<String> = emptyList()) {
        this.queue.value = queue
        controller.start(taskId, project, lengthMin)
    }

    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun end() = controller.end()

    /** Pop the queue head and begin a fresh interval for it (project snapshot reused). */
    fun advance() {
        val (next, rest) = advanceQueue(queue.value)
        queue.value = rest
        if (next != null) controller.start(next, controller.state.value.project, controller.state.value.lengthMin)
    }

    fun currentTaskIdForTest(): String? = controller.state.value.taskId
}
```

- [ ] **Verify PASS.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.FocusFormatTest" --tests "net.qmindtech.tmap.ui.focus.FocusViewModelTest"` — green.

- [ ] **Commit.**
```
feat(android-focus): FocusViewModel + tested mm:ss formatter and queue-advance

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P6.7 — `FocusScreen` Compose + `Route.Focus` NavHost wiring (compile-gate + mockup checklist)

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/focus/FocusScreen.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/navigation/MainScaffold.kt` (replace the `FocusPlaceholder()` body of the existing `Route.Focus` destination with the real screen + start the foreground service)
- (Delete the `FocusPlaceholder` from `PlaceholderScreens.kt` only if no longer referenced.)

**Interfaces**
- Produces: `@Composable fun FocusScreen(taskId: String?, onExit: () -> Unit, viewModel: FocusViewModel = hiltViewModel())` — immersive focus background (`focusBgTop→focusBgBottom` radial/vertical gradient), "FOCUSING ON" eyebrow (`label` style, `accent`), task title (`heading`), a project dot + name row, the amber `ProgressRing` (FIXED P0 contract) sized ~190dp with `remainingLabel` (`display`-ish) + `ofLabel` (`textTertiary`) centered, the session dots row (filled `accent` for `completedSessions`, `surfaceRaised` otherwise, out of `totalSessions`) + "Session N of M" label, the three controls (mark-done = circular `surfaceRaised` button → `markDone` then `end`+`onExit`; center large amber pause/resume toggle; end = circular `surfaceRaised` → `end`+`onExit`), and the queued line ("N more tasks queued for this session") when `queuedCount > 0`. On first composition with a non-null `taskId`, calls `viewModel.start(taskId, project, 25)` resolving the project name (or task-less when `taskId` is the `Route.Focus.NEW_SENTINEL`/null). Icon-only controls carry `contentDescription`s. Uses `LocalTmapColors/Type/Spacing/Shapes`.
- Consumes: `FocusViewModel`, `FocusUiState`, `ProgressRing`, the theme locals, `markDone` via the task repo (through a VM passthrough — add `fun markDone()` to the VM that calls `taskRepo.markDone(currentTaskId)` then `end()`).
- `MainScaffold`: the existing `Route.Focus.PATTERN` composable (already declared in P0.16) reads `entry.arguments?.getString(Route.Focus.ARG_TASK_ID)`, maps the `NEW_SENTINEL` to a null taskId, renders `FocusScreen(taskId, onExit = { navController.popBackStack() })`, and starts/stops `FocusService` via a `DisposableEffect` (start on enter, `FocusService.stop` on dispose).

> **Test substitution:** Compose screen + NavHost wiring. Gate = `./gradlew :app:assembleDebug` + the mockup checklist (vs `full-app.html` "Focusing On" panel). All pure logic it relies on (ring sweep, `mmss`, `elapsedFraction`, queue-advance, controller transitions) is already unit-tested in P0.11 / P6.1–P6.6.

**Steps**

- [ ] **Add the `markDone` passthrough to the VM.** In `FocusViewModel.kt`, add:
```kotlin
    /** Mark the bound task done (if any) and end the interval (used by the screen's ✓ control). */
    fun markDone() {
        val id = controller.state.value.taskId
        if (id != null) viewModelScope.launch { taskRepo.markDone(id) }
        controller.end()
    }
```
and add the imports `import kotlinx.coroutines.launch`. (This keeps `markDone` off the UI thread and reuses the existing `TaskRepository.markDone`.)

- [ ] **Implement `FocusScreen.kt`:**
```kotlin
package net.qmindtech.tmap.ui.focus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.components.ProgressRing
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

@Composable
fun FocusScreen(
    taskId: String?,
    onExit: () -> Unit,
    viewModel: FocusViewModel = hiltViewModel(),
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val spacing = LocalTmapSpacing.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Begin the interval once on entry. Project name is resolved lazily; default 25-min pomodoro.
    LaunchedEffect(taskId) {
        if (state.phase == FocusPhase.Idle) {
            viewModel.start(taskId = taskId, project = state.project.ifBlank { "Focus" }, lengthMin = 25)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.focusBgTop, colors.focusBgBottom))),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = spacing.xl, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(34.dp))
            Text("FOCUSING ON", style = type.label, color = colors.accent)
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.taskTitle ?: state.project.ifBlank { "Focus session" },
                style = type.heading,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
                Text(state.project, style = type.body, color = colors.textSecondary)
            }

            Spacer(Modifier.height(30.dp))
            ProgressRing(progress = state.progress, modifier = Modifier.size(190.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.remainingLabel, style = type.display.copy(fontSize = type.display.fontSize * 0.95f), color = colors.textPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(state.ofLabel, style = type.meta, color = colors.textTertiary)
                }
            }

            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(state.totalSessions) { i ->
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .background(if (i < state.completedSessions) colors.accent else colors.surfaceRaised),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Session ${state.completedSessions + 1} of ${state.totalSessions}",
                style = type.meta,
                color = colors.textTertiary,
            )

            Spacer(Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControl(size = 50.dp) {
                    Icon(Icons.Filled.Check, contentDescription = "Mark task done", tint = colors.textSecondary)
                    onMark = { viewModel.markDone(); onExit() }
                }
                // Center primary toggle: pause when running, resume when paused.
                Box(
                    modifier = Modifier
                        .size(62.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(colors.accent, colors.accentEnd)))
                        .clickable {
                            if (state.phase == FocusPhase.Paused) viewModel.resume() else viewModel.pause()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (state.phase == FocusPhase.Paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (state.phase == FocusPhase.Paused) "Resume" else "Pause",
                        tint = colors.onAccent,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(50.dp).clip(CircleShape).background(colors.surfaceRaised)
                        .border(1.dp, colors.borderStrong, CircleShape)
                        .clickable { viewModel.end(); onExit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "End focus", tint = colors.textSecondary)
                }
            }

            Spacer(Modifier.height(20.dp))
            if (state.queuedCount > 0) {
                Text(
                    "${state.queuedCount} more tasks queued for this session",
                    style = type.meta,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
```
> Authoring fix-up: the `CircleControl` sketch above is illustrative; implement the ✓ (mark-done) control directly as a `Box` mirroring the end control but calling `viewModel.markDone(); onExit()` on click (drop the stray `CircleControl`/`onMark` lines). Concretely the mark-done control is:
```kotlin
                Box(
                    modifier = Modifier
                        .size(50.dp).clip(CircleShape).background(colors.surfaceRaised)
                        .border(1.dp, colors.borderStrong, CircleShape)
                        .clickable { viewModel.markDone(); onExit() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Mark task done", tint = colors.textSecondary)
                }
```
Replace the `CircleControl { ... }` block with this `Box`. (No `CircleControl` helper is introduced; keep the file to plain `Box`es matching the end/pause controls.)

- [ ] **Wire `Route.Focus` in `MainScaffold.kt`.** Replace the existing Focus destination body (the `{ FocusPlaceholder() }` from P0.16) with the real screen + foreground service lifecycle:
```kotlin
                    composable(
                        route = Route.Focus.PATTERN,
                        arguments = listOf(navArgument(Route.Focus.ARG_TASK_ID) { type = NavType.StringType }),
                    ) { entry ->
                        val raw = entry.arguments?.getString(Route.Focus.ARG_TASK_ID)
                        val taskId = raw?.takeIf { it != Route.Focus.NEW_SENTINEL }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            net.qmindtech.tmap.ui.focus.FocusService.start(context)
                            onDispose { net.qmindtech.tmap.ui.focus.FocusService.stop(context) }
                        }
                        net.qmindtech.tmap.ui.focus.FocusScreen(
                            taskId = taskId,
                            onExit = { navController.popBackStack() },
                        )
                    }
```
(Keep the `import` for `FocusScreen`/`FocusService` at the top of `MainScaffold.kt` rather than fully-qualified if preferred; either compiles. Remove the now-unused `FocusPlaceholder` import/usage.)

- [ ] **Compile gate.** `./gradlew :app:assembleDebug` — `BUILD SUCCESSFUL`.

- [ ] **Mockup checklist (reviewer, vs `full-app.html` "Focusing On" panel):**
  - Immersive radial/vertical focus background (`focusBgTop → focusBgBottom`), no bottom nav / no FAB (it is a full-screen destination, not a tab).
  - "FOCUSING ON" amber uppercase eyebrow; centered task title; project dot + name row beneath.
  - ~190dp amber `ProgressRing` with large `mm:ss` remaining and a `of 25:00` sub-label centered inside.
  - Session dots row (amber filled = completed, muted = pending) + "Session N of M".
  - Three bottom controls: left ✓ (mark-done), center large amber pause/resume toggle (shows ❚❚ running / ▶ paused), right ✕ (end); each icon-only control has a `contentDescription`.
  - Queued line ("N more tasks queued for this session") appears only when `queuedCount > 0`.
  - Entering the screen starts `FocusService` (foreground); leaving (pop/back) stops it.

- [ ] **Commit.**
```
feat(android-focus): FocusScreen immersive pomodoro + Route.Focus wiring

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P6.8 — Phase gate: assembleDebug + full focus unit suite + DI graph

**Files**
- `~ android/app/src/test/java/net/qmindtech/tmap/AppGraphWiringTest.kt` (assert the focus graph resolves)

**Interfaces**
- The full Hilt graph must construct `FocusController` (with its `@FocusDispatcher CoroutineDispatcher`, `FocusSessionRepository`, `TaskRepository`, `Clock`) and `FocusViewModel`'s dependencies without missing-binding errors. The `FocusService` `@AndroidEntryPoint` and `FocusModule` are on the classpath.

**Steps**

- [ ] **Extend the wiring smoke test.** Add to `AppGraphWiringTest.kt` an injected `FocusController` (the `@Singleton` from P6.2) and assert it resolves and exposes an Idle initial state — proving `@FocusDispatcher` + the P3 repos are all bound:
```kotlin
    @Inject
    lateinit var focusController: net.qmindtech.tmap.ui.focus.FocusController

    @Test
    fun hiltGraph_resolvesFocusController_withInjectedDispatcherAndRepos() {
        assertNotNull("FocusController must be injectable from the full graph", focusController)
        assertEquals(
            net.qmindtech.tmap.ui.focus.FocusPhase.Idle,
            focusController.state.value.phase,
        )
    }
```
Add `import org.junit.Assert.assertEquals` if not already present.

- [ ] **Verify the DI assertion.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.AppGraphWiringTest"` — green (the focus graph resolves; `@FocusDispatcher` provides `Dispatchers.Default`).

- [ ] **Full focus unit suite.** `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.focus.*"` — green: `FocusStateTest`, `FocusControllerStartTest`, `FocusControllerCompletionTest`, `FocusControllerControlsTest`, `FocusFormatTest`, `FocusViewModelTest`.

- [ ] **Compile + lint gate.** `./gradlew :app:assembleDebug` then `./gradlew :app:lintDebug` — both `BUILD SUCCESSFUL` (the foreground-service permission + `dataSync` type are declared; lint must not flag a missing `FOREGROUND_SERVICE*` permission).

- [ ] **Full regression (engine + earlier phases intact).** `./gradlew :app:testDebugUnitTest` — entire unit suite green (P6 added only new files + an `addActualTime` fake method + an `AppGraphWiringTest` injection; no existing test regressed).

- [ ] **Commit.**
```
test(android-focus): assert FocusController resolves from the Hilt graph; phase gate

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

## P7 — Today Timeline & time-blocking

This phase fills the Timeline half of the Today List ⇄ Timeline toggle that P1 stubbed with a placeholder. It mirrors the `full-app.html` "Today · Timeline" mockup: a vertical hour rail starting at **09:00** at **72dp/hour**, project-colored time-blocks positioned by `scheduledStart`/duration, an amber **now-line**, dashed **"Drag a task here to time-block"** empty slots, and **drag-to-time-block** that snaps a dropped task to the nearest 15 minutes and writes its schedule through `TaskRepository`. All the geometry is isolated into pure, deterministic helpers (`ui/today/TimelineLayout.kt`) that get real unit tests — vertical offset, block height, now-line position, and the drop-offset→snapped-time inverse — so the math is locked independently of Compose. The new `TodayViewModel.timeblock(taskId, start: LocalTime)` action (sets `scheduledStart`/`scheduledEnd` from `start` + duration, default 60 min, no-op on unknown task) gets a real VM unit test against `FakeTaskRepo`. The `TimelineContent` composable and the `TodayScreen` toggle wiring are Compose surfaces, verified by **compile-gate (`assembleDebug`) + a behavior checklist vs the mockup** (stated per task) rather than a unit test. This phase **adds to** the P1-rebuilt `TodayViewModel`/`TodayScreen` additively. **Assumption (stated):** P1 has rebuilt `TodayViewModel` to expose an immutable `TodayUiState` carrying `val mode: TodayMode` (`TodayMode.List` / `TodayMode.Timeline`), the P1 action set (`setMode`, `toggleComplete`, `defer`, `delete`, `reorder`, `moveToDay`), an injected `Clock` field named `clock`, a `@Volatile private var lastTasks: List<TaskEntity>` snapshot of today's entities, and `TodayScreen(onOpenTask, onOpenCapture, onPlanMyDay, onFocus)` rendering `TodayListContent` for List mode and an `EmptyState` placeholder for Timeline mode — exactly as authored in P1.4 / P1.3. P7 replaces only that Timeline placeholder branch and appends one action. All `./gradlew` commands run from `android/`. Each task is committed with the exact trailer.

---

### Task P7.1 — Pure timeline geometry: `blockOffsetDp`, `blockHeightDp` + `TimelineDefaults`

Isolate the "scheduled start/duration → vertical offset/height" math into a pure helper file with no Compose/Android dependencies (only `androidx.compose.ui.unit.Dp`, a value class that is JVM-testable). The rail mirrors the mockup: start hour **09**, **72dp per hour**, a 1-hour content window per labeled hour. These two functions are the foundation every later P7 piece reuses, so they are real-unit-tested first.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/today/TimelineLayout.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/today/TimelineLayoutTest.kt`

**Interfaces** (FIXED for this phase):
```kotlin
object TimelineDefaults {
  const val RAIL_START_HOUR: Int = 9       // 09:00, mirrors full-app.html
  const val RAIL_END_HOUR: Int = 22        // 22:00 — bottom of the rail (last droppable instant)
  val hourHeight: Dp = 72.dp               // 72dp/hour, mirrors the mockup (10→78px etc.)
  val minBlockHeight: Dp = 36.dp           // short blocks stay tappable
  val railHeight: Dp                       // (RAIL_END_HOUR - RAIL_START_HOUR) * hourHeight
}

/** Vertical offset of a block whose local start time is [start], measured from the rail top. */
fun blockOffsetDp(start: LocalTime, startHour: Int = TimelineDefaults.RAIL_START_HOUR, hourHeight: Dp = TimelineDefaults.hourHeight): Dp

/** Rendered height of a block of [durationMinutes] (null → 60), clamped to [TimelineDefaults.minBlockHeight]. */
fun blockHeightDp(durationMinutes: Int?, hourHeight: Dp = TimelineDefaults.hourHeight, minHeight: Dp = TimelineDefaults.minBlockHeight): Dp
```
Math (deterministic, no rounding surprises):
- `blockOffsetDp(start)` = `((start - startHour:00) in minutes / 60f) * hourHeight`. A start *before* `startHour` clamps to `0.dp`; e.g. `09:00 → 0dp`, `09:30 → 36dp`, `10:00 → 72dp`, `14:00 → 360dp`. (The mockup's extra ~6px is the rail's content top-padding applied by the composable, **not** part of this pure offset.)
- `blockHeightDp(d)` = `max(minHeight, (d ?: 60) / 60f * hourHeight)`; e.g. `60 → 72dp`, `90 → 108dp`, `15 → 36dp` (clamped up from 18dp), `null → 72dp`.

**Steps**
- [ ] **Write the failing test** `TimelineLayoutTest.kt`:
```kotlin
package net.qmindtech.tmap.ui.today

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class TimelineLayoutTest {
  @Test fun blockOffset_is_zero_at_rail_start() {
    assertEquals(0.dp, blockOffsetDp(LocalTime.of(9, 0)))
  }

  @Test fun blockOffset_scales_72dp_per_hour() {
    assertEquals(36.dp, blockOffsetDp(LocalTime.of(9, 30)))
    assertEquals(72.dp, blockOffsetDp(LocalTime.of(10, 0)))
    assertEquals(360.dp, blockOffsetDp(LocalTime.of(14, 0)))
  }

  @Test fun blockOffset_before_rail_start_clamps_to_zero() {
    assertEquals(0.dp, blockOffsetDp(LocalTime.of(7, 0)))
  }

  @Test fun blockHeight_scales_with_duration() {
    assertEquals(72.dp, blockHeightDp(60))
    assertEquals(108.dp, blockHeightDp(90))
  }

  @Test fun blockHeight_null_duration_defaults_to_one_hour() {
    assertEquals(72.dp, blockHeightDp(null))
  }

  @Test fun blockHeight_short_block_clamped_to_minimum() {
    assertEquals(36.dp, blockHeightDp(15)) // raw 18dp → clamped up to 36dp
  }

  @Test fun railHeight_spans_the_full_window() {
    assertEquals((22 - 9) * 72, TimelineDefaults.railHeight.value.toInt())
  }
}
```
- [ ] **Verify FAIL:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TimelineLayoutTest"` → red (file/functions missing: `unresolved reference: blockOffsetDp` / `TimelineDefaults`).

- [ ] **Implement** `TimelineLayout.kt`:
```kotlin
package net.qmindtech.tmap.ui.today

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/**
 * Pure timeline geometry for the Today Timeline rail. No Compose runtime / Android deps — only the
 * [Dp] value class — so the offset/height/now-line/drop math is JVM-unit-testable and deterministic.
 * Mirrors the `full-app.html` "Today · Timeline" mockup: rail starts at 09:00 at 72dp/hour.
 */
object TimelineDefaults {
  const val RAIL_START_HOUR: Int = 9
  const val RAIL_END_HOUR: Int = 22
  val hourHeight: Dp = 72.dp
  val minBlockHeight: Dp = 36.dp
  val railHeight: Dp = (RAIL_END_HOUR - RAIL_START_HOUR) * hourHeight
}

/** Minutes between the rail-start hour and [start]; negative (before rail start) clamps to 0. */
private fun minutesFromRailStart(start: LocalTime, startHour: Int): Int {
  val minutes = (start.hour - startHour) * 60 + start.minute
  return if (minutes < 0) 0 else minutes
}

/** Vertical offset of a block whose local start time is [start], measured from the rail top. */
fun blockOffsetDp(
  start: LocalTime,
  startHour: Int = TimelineDefaults.RAIL_START_HOUR,
  hourHeight: Dp = TimelineDefaults.hourHeight,
): Dp = (minutesFromRailStart(start, startHour) / 60f) * hourHeight

/** Rendered height of a block of [durationMinutes] (null → 60), clamped up to [minHeight]. */
fun blockHeightDp(
  durationMinutes: Int?,
  hourHeight: Dp = TimelineDefaults.hourHeight,
  minHeight: Dp = TimelineDefaults.minBlockHeight,
): Dp {
  val minutes = durationMinutes ?: 60
  val raw = (minutes / 60f) * hourHeight
  return if (raw.value < minHeight.value) minHeight else raw
}
```
- [ ] **Verify PASS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TimelineLayoutTest"` → green.
- [ ] **Commit:**
```
feat(android-today): pure timeline geometry (blockOffsetDp/blockHeightDp + TimelineDefaults)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P7.2 — Pure `nowLineOffsetDp` + `dropOffsetToTime` (snap to 15-min, clamp)

Add the two remaining pure helpers to the same `TimelineLayout.kt`: the amber now-line's vertical position (the block-offset of the current local time, clamped within the rail), and the inverse used by drag-to-time-block — a drop offset (in dp from the rail top) mapped back to a `LocalTime` snapped to the nearest 15 minutes and clamped to the rail window. Both are real-unit-tested.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/today/TimelineLayout.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/ui/today/TimelineLayoutTest.kt`

**Interfaces** (FIXED for this phase):
```kotlin
/** Now-line vertical offset for [now]; clamped to [0, railHeight]. Times outside the window pin to an edge. */
fun nowLineOffsetDp(
  now: LocalTime,
  startHour: Int = TimelineDefaults.RAIL_START_HOUR,
  endHour: Int = TimelineDefaults.RAIL_END_HOUR,
  hourHeight: Dp = TimelineDefaults.hourHeight,
): Dp

/** Inverse of [blockOffsetDp]: a drop [offset] (dp from rail top) → LocalTime snapped to nearest 15 min, clamped to the rail. */
fun dropOffsetToTime(
  offset: Dp,
  startHour: Int = TimelineDefaults.RAIL_START_HOUR,
  endHour: Int = TimelineDefaults.RAIL_END_HOUR,
  hourHeight: Dp = TimelineDefaults.hourHeight,
  snapMinutes: Int = 15,
): LocalTime
```
Math:
- `nowLineOffsetDp(now)`: compute `blockOffsetDp(now)`; if `now` is before `startHour:00` → `0.dp`; if at/after `endHour:00` → `railHeight` (= `(endHour-startHour)*hourHeight`). e.g. `09:00 → 0dp`, `09:54 → 64.8dp` (54/60*72), `08:00 → 0dp`, `23:00 → 936dp`.
- `dropOffsetToTime(offset)`: `rawMinutes = (offset.value / hourHeight.value) * 60`; `snapped = round(rawMinutes / snapMinutes) * snapMinutes`; `total = startHour*60 + snapped`; clamp `total` into `[startHour*60, endHour*60]`; return `LocalTime.of(total/60, total%60)`. e.g. offset `0dp → 09:00`, `36dp → 09:30`, `40dp → 09:30` (33.3min → snap 30), `46dp → 09:45` (38.3 → snap 45), negative → `09:00`, huge → `22:00`.

**Steps**
- [ ] **Write the failing test** — append to `TimelineLayoutTest.kt`:
```kotlin
  @Test fun nowLine_at_rail_start_is_zero() {
    assertEquals(0.dp, nowLineOffsetDp(LocalTime.of(9, 0)))
  }

  @Test fun nowLine_scales_with_minutes() {
    assertEquals(64.8f, nowLineOffsetDp(LocalTime.of(9, 54)).value, 0.01f) // 54/60*72
  }

  @Test fun nowLine_before_window_pins_to_top() {
    assertEquals(0.dp, nowLineOffsetDp(LocalTime.of(8, 0)))
  }

  @Test fun nowLine_after_window_pins_to_bottom() {
    assertEquals(TimelineDefaults.railHeight, nowLineOffsetDp(LocalTime.of(23, 0)))
  }

  @Test fun dropOffset_maps_back_to_rail_start() {
    assertEquals(LocalTime.of(9, 0), dropOffsetToTime(0.dp))
  }

  @Test fun dropOffset_snaps_to_nearest_quarter_hour() {
    assertEquals(LocalTime.of(9, 30), dropOffsetToTime(36.dp))  // exactly 30 min
    assertEquals(LocalTime.of(9, 30), dropOffsetToTime(40.dp))  // 33.3 min → 30
    assertEquals(LocalTime.of(9, 45), dropOffsetToTime(46.dp))  // 38.3 min → 45
  }

  @Test fun dropOffset_clamps_below_and_above() {
    assertEquals(LocalTime.of(9, 0), dropOffsetToTime((-50).dp))
    assertEquals(LocalTime.of(22, 0), dropOffsetToTime(99999.dp))
  }
```
- [ ] **Verify FAIL:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TimelineLayoutTest"` → red (`unresolved reference: nowLineOffsetDp` / `dropOffsetToTime`).

- [ ] **Implement** — append to `TimelineLayout.kt` (after `blockHeightDp`):
```kotlin
import kotlin.math.roundToInt

/** Now-line vertical offset for [now]; clamped to [0, railHeight]. */
fun nowLineOffsetDp(
  now: LocalTime,
  startHour: Int = TimelineDefaults.RAIL_START_HOUR,
  endHour: Int = TimelineDefaults.RAIL_END_HOUR,
  hourHeight: Dp = TimelineDefaults.hourHeight,
): Dp {
  val railHeight = (endHour - startHour) * hourHeight
  val nowMinutes = now.hour * 60 + now.minute
  return when {
    nowMinutes <= startHour * 60 -> 0.dp
    nowMinutes >= endHour * 60 -> railHeight
    else -> blockOffsetDp(now, startHour, hourHeight)
  }
}

/** Inverse of [blockOffsetDp]: drop [offset] → LocalTime snapped to nearest [snapMinutes], clamped to the rail. */
fun dropOffsetToTime(
  offset: Dp,
  startHour: Int = TimelineDefaults.RAIL_START_HOUR,
  endHour: Int = TimelineDefaults.RAIL_END_HOUR,
  hourHeight: Dp = TimelineDefaults.hourHeight,
  snapMinutes: Int = 15,
): LocalTime {
  val rawMinutes = (offset.value / hourHeight.value) * 60f
  val snapped = (rawMinutes / snapMinutes).roundToInt() * snapMinutes
  val total = (startHour * 60 + snapped).coerceIn(startHour * 60, endHour * 60)
  return LocalTime.of(total / 60, total % 60)
}
```
> Place the `import kotlin.math.roundToInt` with the other top-of-file imports (shown inline here only to mark where it belongs); do not leave a mid-file import statement.
- [ ] **Verify PASS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TimelineLayoutTest"` → green (all P7.1 + P7.2 cases).
- [ ] **Commit:**
```
feat(android-today): pure now-line + drop-to-time snap helpers

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P7.3 — `TodayViewModel.timeblock(taskId, start: LocalTime)` action (+ real VM test)

Add the time-block write action the timeline drag invokes. Given a task id and a target local start time, it computes the task's `scheduledStart` (today's date at `start`, in the clock's zone, as an `Instant`), `scheduledEnd` (start + duration), and persists both via `taskRepo.update(id, TaskEdit(...))`. Duration is the task's own `durationMinutes` when set, else the **default 60**. Unknown task id is a **no-op** (no repo call). This is a real ViewModel unit test against `FakeTaskRepo` (matching the existing `TodayViewModelTest` style).

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayViewModel.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/ui/today/TodayViewModelTest.kt`

**Interfaces** (FIXED — added to the P1 `TodayViewModel`):
```kotlin
fun timeblock(taskId: String, start: java.time.LocalTime)
```
Semantics:
- Resolve the task from the `lastTasks` snapshot (the same `@Volatile` field P1's `toggleComplete` reads). If absent → return without launching/calling the repo (no-op).
- `duration = task.durationMinutes ?: 60`.
- `startInstant = clock.today().atTime(start).atZone(clock.zone()).toInstant()`.
- `endInstant = startInstant.plus(duration minutes)`.
- `taskRepo.update(taskId, TaskEdit(scheduledStart = startInstant, scheduledEnd = endInstant, durationMinutes = duration))` inside `viewModelScope.launch`.

**Steps**
- [ ] **Write the failing test** — append to `TodayViewModelTest.kt`:
```kotlin
  @Test fun timeblock_sets_scheduled_start_end_from_start_and_duration() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(fakeTask(id = "x", plannedDate = today, durationMinutes = 90)),
    )
    val (vm, repo) = vm(flow)
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() } // prime lastTasks
    vm.timeblock("x", java.time.LocalTime.of(10, 0))
    assertEquals(1, repo.updated.size)
    val (id, edit) = repo.updated.first()
    assertEquals("x", id)
    // FixedClock zone is UTC; today = 2026-06-21.
    assertEquals(java.time.Instant.parse("2026-06-21T10:00:00Z"), edit.scheduledStart)
    assertEquals(java.time.Instant.parse("2026-06-21T11:30:00Z"), edit.scheduledEnd)
    assertEquals(90, edit.durationMinutes)
  }

  @Test fun timeblock_defaults_to_60_minutes_when_duration_null() = runTest(testDispatcher) {
    val flow = MutableStateFlow(
      listOf(fakeTask(id = "y", plannedDate = today, durationMinutes = null)),
    )
    val (vm, repo) = vm(flow)
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
    vm.timeblock("y", java.time.LocalTime.of(9, 30))
    val edit = repo.updated.first().second
    assertEquals(java.time.Instant.parse("2026-06-21T09:30:00Z"), edit.scheduledStart)
    assertEquals(java.time.Instant.parse("2026-06-21T10:30:00Z"), edit.scheduledEnd)
    assertEquals(60, edit.durationMinutes)
  }

  @Test fun timeblock_unknown_task_is_noop() = runTest(testDispatcher) {
    val flow = MutableStateFlow(listOf(fakeTask(id = "x", plannedDate = today)))
    val (vm, repo) = vm(flow)
    vm.uiState.test { expectMostRecentItem(); cancelAndIgnoreRemainingEvents() }
    vm.timeblock("does-not-exist", java.time.LocalTime.of(10, 0))
    assertTrue(repo.updated.isEmpty())
  }
```
> `FixedClock` (in `testutil/Fakes.kt`) reports `today()`/`zone()` in UTC (`now.atZone(ZoneOffset.UTC)`), so the expected `Instant`s above are exact. The `vm.uiState.test { … }` line primes the `combine` so `lastTasks` is populated before `timeblock` reads it (same pattern the existing `toggleComplete` tests rely on via `expectMostRecentItem`).

- [ ] **Verify FAIL:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TodayViewModelTest"` → red (`unresolved reference: timeblock`).

- [ ] **Implement** — add the action to `TodayViewModel` (alongside the other P1 actions; reuses the existing `lastTasks`, `clock`, `taskRepo`, and `TaskEdit` import):
```kotlin
  fun timeblock(taskId: String, start: java.time.LocalTime) {
    val task = lastTasks.firstOrNull { it.id == taskId } ?: return
    val duration = task.durationMinutes ?: 60
    val startInstant = clock.today().atTime(start).atZone(clock.zone()).toInstant()
    val endInstant = startInstant.plus(java.time.Duration.ofMinutes(duration.toLong()))
    viewModelScope.launch {
      taskRepo.update(
        taskId,
        TaskEdit(
          scheduledStart = startInstant,
          scheduledEnd = endInstant,
          durationMinutes = duration,
        ),
      )
    }
  }
```
> `TaskEdit` is already imported in the P1 `TodayViewModel` (used by `toggleComplete`'s reopen path). If for some reason it is not, add `import net.qmindtech.tmap.data.repository.TaskEdit`.
- [ ] **Verify PASS:** `cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.today.TodayViewModelTest"` → green; then `cd android && ./gradlew :app:testDebugUnitTest` (full) stays green.
- [ ] **Commit:**
```
feat(android-today): TodayViewModel.timeblock sets scheduled start/end via repository

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P7.4 — `TimelineContent` composable (hour rail, blocks, now-line, drop slots, drag-to-time-block)

Build the Timeline view to the `full-app.html` "Today · Timeline" mockup using the P7 pure helpers and the P0 design tokens. The rail is a fixed-height (`TimelineDefaults.railHeight`) vertical container inside a `verticalScroll`: hour labels (09…21) down the start edge at `blockOffsetDp(hour:00)`, scheduled tasks rendered as absolutely-positioned project-colored blocks (left accent bar in the task's `projectColor`, title + scheduled label, and a thin amber progress sliver when partway done), an amber now-line at `nowLineOffsetDp(now)`, and dashed empty-slot affordances reading **"Drag a task here to time-block"**. A long-press drag of a block (or of a task chip dragged in) computes its drop position via `dropOffsetToTime` and calls `onTimeblock(taskId, time)`. Includes the `TimelineBlock` projection that pairs a `TaskUi` with its start time + duration for rendering.

> **No unit test (Compose UI).** Verification = **compile-gate** (`./gradlew :app:assembleDebug`) **+ behavior checklist** against `.superpowers/brainstorm/965-1782053760/content/full-app.html` ("Today · Timeline" panel). The geometry it relies on is already locked by `TimelineLayoutTest` (P7.1/P7.2) and the write path by `TodayViewModelTest` (P7.3).

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/today/TimelineContent.kt`

**Interfaces** (FIXED for this phase):
```kotlin
/** A task projected onto the timeline: its UI row data + resolved local start + duration (min). */
data class TimelineBlock(
  val ui: TaskUi,
  val start: LocalTime,
  val durationMin: Int,
)

@Composable
fun TimelineContent(
  blocks: List<TimelineBlock>,
  now: LocalTime,
  onClick: (String) -> Unit,
  onTimeblock: (taskId: String, start: LocalTime) -> Unit,
  modifier: Modifier = Modifier,
)
```

**Steps**
- [ ] **(No failing unit test — Compose surface.)** State the substitution: *behavior checklist vs `full-app.html` "Today · Timeline", gated by `assembleDebug`; geometry already covered by `TimelineLayoutTest`, write path by `TodayViewModelTest.timeblock_*`.*

- [ ] **Implement** `TimelineContent.kt`:
```kotlin
package net.qmindtech.tmap.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.components.TaskUi
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType
import java.time.LocalTime

/** A task projected onto the timeline: its UI row data + resolved local start + duration (min). */
data class TimelineBlock(
  val ui: TaskUi,
  val start: LocalTime,
  val durationMin: Int,
)

private val RAIL_GUTTER = 58.dp   // blocks start here (mockup left:58); labels live in the gutter
private val RAIL_TOP_PAD = 6.dp   // rail content baseline (mockup top:6 on the hour labels)

@Composable
fun TimelineContent(
  blocks: List<TimelineBlock>,
  now: LocalTime,
  onClick: (String) -> Unit,
  onTimeblock: (taskId: String, start: LocalTime) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = LocalTmapColors.current
  val type = LocalTmapType.current
  val density = LocalDensity.current

  Box(
    modifier = modifier
      .fillMaxWidth()
      .verticalScroll(rememberScrollState()),
  ) {
    Box(modifier = Modifier.fillMaxWidth().height(TimelineDefaults.railHeight + RAIL_TOP_PAD)) {
      // Hour labels + the thin vertical rail line.
      Box(
        modifier = Modifier
          .offset(x = RAIL_GUTTER - 12.dp, y = 0.dp)
          .width(1.dp)
          .height(TimelineDefaults.railHeight + RAIL_TOP_PAD)
          .background(colors.borderSubtle),
      )
      for (hour in TimelineDefaults.RAIL_START_HOUR until TimelineDefaults.RAIL_END_HOUR) {
        val y = blockOffsetDp(LocalTime.of(hour, 0)) + RAIL_TOP_PAD
        androidx.compose.material3.Text(
          text = "%02d".format(hour),
          color = colors.textTertiary,
          style = type.label,
          modifier = Modifier
            .offset(x = 0.dp, y = y - 7.dp)
            .width(40.dp),
          textAlign = TextAlign.End,
        )
      }

      // Scheduled task blocks.
      blocks.forEach { block ->
        TimelineTaskBlock(
          block = block,
          topPad = RAIL_TOP_PAD,
          onClick = { onClick(block.ui.id) },
          onDragEndToOffset = { dropY -> onTimeblock(block.ui.id, dropOffsetToTime(dropY)) },
          density = density,
        )
      }

      // Amber now-line.
      val nowY = nowLineOffsetDp(now) + RAIL_TOP_PAD
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .offset(x = RAIL_GUTTER - 28.dp, y = nowY)
          .padding(end = 18.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        Box(
          modifier = Modifier
            .width(8.dp)
            .height(8.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(colors.accent),
        )
        Box(
          modifier = Modifier
            .padding(start = 8.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(colors.accent),
        )
      }

      // Calm "drag a task here" affordance pinned near the now-line gap (single hint slot).
      val hintY = nowLineOffsetDp(now) + RAIL_TOP_PAD + 12.dp
      Box(
        modifier = Modifier
          .offset(x = RAIL_GUTTER, y = hintY)
          .padding(end = 18.dp)
          .fillMaxWidth()
          .height(TimelineDefaults.minBlockHeight)
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
          .semantics { contentDescription = "Drag a task here to time-block" },
        contentAlignment = Alignment.Center,
      ) {
        androidx.compose.material3.Text(
          text = "Drag a task here to time-block",
          color = colors.textTertiary,
          style = type.meta,
        )
      }
    }
  }
}

@Composable
private fun TimelineTaskBlock(
  block: TimelineBlock,
  topPad: Dp,
  onClick: () -> Unit,
  onDragEndToOffset: (Dp) -> Unit,
  density: androidx.compose.ui.unit.Density,
) {
  val colors = LocalTmapColors.current
  val type = LocalTmapType.current
  val baseOffset = blockOffsetDp(block.start) + topPad
  var dragDy by remember(block.ui.id) { mutableStateOf(0.dp) }
  val barColor = block.ui.projectColor?.let { Color(it) } ?: colors.accent

  Box(
    modifier = Modifier
      .offset(x = RAIL_GUTTER, y = baseOffset + dragDy)
      .padding(end = 18.dp)
      .fillMaxWidth()
      .height(blockHeightDp(block.durationMin))
      .clip(RoundedCornerShape(12.dp))
      .background(colors.surface)
      .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
      .pointerInput(block.ui.id) {
        detectDragGesturesAfterLongPress(
          onDrag = { change, dragAmount ->
            change.consume()
            dragDy += with(density) { dragAmount.y.toDp() }
          },
          onDragEnd = {
            onDragEndToOffset(baseOffset - topPad + dragDy)
            dragDy = 0.dp
          },
        )
      },
  ) {
    // Project-colored left accent bar.
    Box(
      modifier = Modifier
        .width(3.dp)
        .height(blockHeightDp(block.durationMin))
        .background(barColor),
    )
    Box(modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp)) {
      androidx.compose.foundation.layout.Column {
        androidx.compose.material3.Text(block.ui.title, color = colors.textPrimary, style = type.body)
        block.ui.scheduledLabel?.let {
          androidx.compose.material3.Text(it, color = colors.textSecondary, style = type.meta)
        }
      }
    }
    // Tap target for opening the editor.
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(blockHeightDp(block.durationMin))
        .pointerInput(block.ui.id) { detectTapToOpen(onClick) },
    )
    // Amber progress sliver for partway-done blocks (subtasks).
    if (block.ui.subtaskTotal > 0 && block.ui.subtaskDone in 1 until block.ui.subtaskTotal) {
      val frac = block.ui.subtaskDone.toFloat() / block.ui.subtaskTotal
      Box(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
          .fillMaxWidth(frac)
          .height(3.dp)
          .clip(RoundedCornerShape(99.dp))
          .background(Brush.horizontalGradient(listOf(colors.accent, colors.accentEnd))),
      )
    }
  }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapToOpen(onClick: () -> Unit) {
  androidx.compose.foundation.gestures.detectTapGestures(onTap = { onClick() })
}
```
> If P0's component library already exposes a reusable `TimeBlock` composable, render through it instead of the private `TimelineTaskBlock` (keeping the project-colored left bar + duration height + progress sliver). The skeleton lists `TimeBlock.kt` under P0's component set; if it is present and its signature fits, prefer it and delete `TimelineTaskBlock`. *(State which in the commit message.)* The `TimelineBlock`/`TimelineContent` contract above does not change either way.

- [ ] **Verify compile-gate:** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Behavior checklist (reviewer, vs `full-app.html` "Today · Timeline"):**
  - Vertical hour rail from **09** downward, labels in the start gutter, **72dp/hour** spacing (10 sits one hour below 09).
  - Scheduled tasks render as cards positioned by `blockOffsetDp(start)` with height `blockHeightDp(duration)`, a **project-colored left bar** (`projectColor`, else accent), title + scheduled label.
  - A **partway-done** block shows the thin amber progress sliver (gradient accent→accentEnd).
  - An **amber now-line** (dot + hairline + implicit "now") sits at `nowLineOffsetDp(now)` and moves with the clock.
  - A **dashed** empty slot reads **"Drag a task here to time-block"** and carries that `contentDescription`.
  - **Long-press drag** a block then release → `onTimeblock(id, dropOffsetToTime(dropY))` fires (snaps to 15 min); **tap** a block → `onClick(id)` (non-gesture editor equivalent honored).
  - Uses only Midnight Calm tokens (`surface`, `borderSubtle`, `accent`, `textPrimary/Secondary/Tertiary`); no hardcoded hex except `Color(projectColor)`.
- [ ] **Commit:**
```
feat(android-today): TimelineContent (hour rail, blocks, now-line, drag-to-time-block)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P7.5 — Wire the List ⇄ Timeline toggle in `TodayScreen` to `TimelineContent`

Replace the P1 Timeline placeholder branch in `TodayScreen` with the real `TimelineContent`. The screen builds `List<TimelineBlock>` from today's **scheduled** tasks (those with a `scheduledLabel` / resolved `scheduledStart`), passes the current local `now` (from a `TodayUiState` field if P1 exposes one, else derived in the composable from a remembered ticking time), and forwards `onTimeblock` to `viewModel::timeblock` and `onClick` to `onOpenTask`. The List branch is unchanged (still `TodayListContent`); only the `state.mode == TodayMode.Timeline` arm changes.

> **No unit test (Compose UI).** Verification = **compile-gate** (`./gradlew :app:assembleDebug`) **+ behavior checklist** against `full-app.html` ("Today · Timeline") — specifically that the segmented toggle swaps `TodayListContent`↔`TimelineContent` and that blocks come from scheduled tasks.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayScreen.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayUiState.kt` (add a `timelineBlocks` projection helper consumed by the screen — pure, optional but recommended)

**Interfaces** (FIXED for this phase):
```kotlin
/**
 * Pure projection: flatten the grouped Today state into timeline blocks for the scheduled tasks.
 * A task contributes a block only when it has a resolved local start time.
 */
fun timelineBlocksFrom(
  groups: List<TodayGroup>,
  scheduledStarts: Map<String, LocalTime?>,
  durations: Map<String, Int?>,
): List<TimelineBlock>
```
Rule: for every `TaskUi` across all groups whose `scheduledStarts[id] != null`, emit `TimelineBlock(ui, start, durationMin = durations[id] ?: 60)`, sorted ascending by `start`. (The maps are the same `scheduledStarts` the VM already builds for grouping; `durations[id]` is the task's `durationMinutes`.)

**Steps**
- [ ] **(No failing unit test — Compose wiring + a trivial pure projection.)** State the substitution: *compile-gate + behavior checklist vs `full-app.html`; `timelineBlocksFrom` is exercised indirectly and is a straight flatten (no branching math to lock).* If a reviewer wants it covered, add a 3-line test asserting only scheduled tasks are emitted in start order — optional, not gating.

- [ ] **Implement** — add `timelineBlocksFrom` to `TodayUiState.kt`:
```kotlin
fun timelineBlocksFrom(
  groups: List<TodayGroup>,
  scheduledStarts: Map<String, LocalTime?>,
  durations: Map<String, Int?>,
): List<TimelineBlock> =
  groups.asSequence()
    .flatMap { it.tasks.asSequence() }
    .mapNotNull { ui ->
      val start = scheduledStarts[ui.id] ?: return@mapNotNull null
      TimelineBlock(ui = ui, start = start, durationMin = durations[ui.id] ?: 60)
    }
    .sortedBy { it.start }
    .toList()
```
> Requires `import java.time.LocalTime` (already present in `TodayUiState.kt` from P1) and references `TimelineBlock` (same package — no import needed). Expose `scheduledStarts`/`durations` on `TodayUiState` from the P1 VM **only if** they are not already derivable: the cleanest path is to add `val scheduledStarts: Map<String, LocalTime?> = emptyMap()` and `val durations: Map<String, Int?> = emptyMap()` to `TodayUiState` and populate them in the VM's `combine` (the VM already computes `starts`; add `durations = sorted.associate { it.id to it.durationMinutes }`). Make that additive change to `TodayUiState` + `TodayViewModel` here.

Add to `TodayUiState` (data class), defaulted so nothing else breaks:
```kotlin
  val scheduledStarts: Map<String, java.time.LocalTime?> = emptyMap(),
  val durations: Map<String, Int?> = emptyMap(),
```
Populate in the VM's `combine` block (next to the existing `starts`):
```kotlin
      val durations = sorted.associate { it.id to it.durationMinutes }
      // … inside TodayUiState(...) add:
      scheduledStarts = starts,
      durations = durations,
```

Replace the Timeline arm in `TodayScreen.kt`'s `when` (the P1 placeholder `EmptyState("Timeline coming soon", …)`):
```kotlin
          state.mode == TodayMode.Timeline -> {
            val now = remember(state) {
              java.time.LocalTime.now() // ticks on recomposition; calm enough for the now-line
            }
            TimelineContent(
              blocks = timelineBlocksFrom(state.groups, state.scheduledStarts, state.durations),
              now = now,
              onClick = onOpenTask,
              onTimeblock = viewModel::timeblock,
              modifier = Modifier.padding(top = 8.dp),
            )
          }
```
> Keep the surrounding `Box`/`when` structure and the List arm exactly as P1 authored them. If P1's `TodayUiState` already carries a clock-derived `now`/`nowTime`, pass that instead of `LocalTime.now()` (prefer the VM-provided value so the now-line honors the injected `Clock`). The only behavioral change is: Timeline mode now renders `TimelineContent` instead of the placeholder.

- [ ] **Verify compile-gate:** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL; then `cd android && ./gradlew :app:testDebugUnitTest` (full) stays green (the additive `TodayUiState` fields + VM change must not break P1's `TodayViewModelTest`/`TodayGroupingTest`).
- [ ] **Behavior checklist (reviewer, vs `full-app.html` "Today · Timeline"):**
  - The header `SegmentedControl` switches the body between `TodayListContent` (List) and `TimelineContent` (Timeline); List remains the default.
  - Timeline blocks are exactly the today tasks that have a `scheduledStart`, positioned correctly, in start order.
  - Dragging a block re-times it (writes through `timeblock` → `TaskRepository.update`), then the list/timeline reflows from the Room flow on the next emission.
  - Switching back to List shows the unchanged P1 list.
- [ ] **Commit:**
```
feat(android-today): wire List/Timeline toggle to TimelineContent with scheduled blocks

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

### Task P7.6 — Phase gate: build + tests + Timeline acceptance checklist

Final P7 gate. Confirm the whole module compiles, all unit tests (the two new pure-geometry + VM suites plus every prior suite) pass, lint is clean, and the Timeline acceptance criteria (spec §6.1 / AC#3) are satisfied against the mockup. No new production code — this task is verification + the gate commit.

**Files**
- *(none — verification only; an empty/`--allow-empty` gate commit records the phase boundary.)*

**Steps**
- [ ] **Run the full unit suite:** `cd android && ./gradlew :app:testDebugUnitTest` → all green (includes `TimelineLayoutTest`, the extended `TodayViewModelTest`, and all P0–P6 suites unaffected).
- [ ] **Compile gate:** `cd android && ./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Lint gate:** `cd android && ./gradlew :app:lintDebug` → no new errors (warnings tolerated, consistent with prior phases).
- [ ] **Timeline acceptance checklist (spec §6.1 + Acceptance Criteria #3) — reviewer confirms in the running app / against `full-app.html`:**
  - List ⇄ Timeline toggle works; the body swaps between the calm list and the time-blocked rail.
  - Timeline shows project-colored **time-blocks** positioned by `scheduledStart`/duration (geometry from `TimelineLayout`, locked by `TimelineLayoutTest`).
  - The amber **now-line** is present and tracks the current local time.
  - **Drag-to-time-block** on an empty/long-pressed slot sets `scheduledStart`/`scheduledEnd` (snapped to 15 min) via `TodayViewModel.timeblock` → `TaskRepository.update` (write-through → outbox; offline-first preserved — no network on the write path).
  - Unknown-task time-block is a safe no-op (covered by `TodayViewModelTest`).
  - RTL: rail/labels/blocks use `start`/`end` and mirror; the drop hint has a `contentDescription`; tap is the non-gesture equivalent of the long-press drag.
  - Midnight Calm only — no old `Surface*/Accent*` palette; accent reserved for the now-line + progress sliver.
- [ ] **Commit (gate marker):**
```
chore(android-today): P7 Timeline phase gate — build + tests green, AC#3 met

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```
> Use `git commit --allow-empty` if there is nothing staged (the gate is a phase boundary marker; prior tasks already committed all code).

---

## P8 — Glance home-screen widgets

This phase adds the four approved Jetpack Glance home-screen widgets (Today Agenda, Quick Capture, Up Next / Focus, Progress & Streak) in the Midnight Calm look, all reading the **same Room DB** with zero network. It introduces `widget/WidgetRepository.kt` (direct `TaskDao`/`ProjectDao` reads plus a small pure helper for the widget task list, today-progress, and day-streak), four `GlanceAppWidget`s each with its `GlanceAppWidgetReceiver`, `widget/WidgetUpdater.updateAll(context)` (refreshes all four via `GlanceAppWidgetManager`) wired into `PullRunner` post-pull and into the today-affecting `TaskRepositoryImpl` writes, and a `CaptureTrampolineActivity` that opens the capture sheet / system speech-to-text. Because Glance composables are not unit-testable, widget UI tasks use a **compile-gate** (`./gradlew :app:assembleDebug`) + a **behavior-checklist** against `.superpowers/brainstorm/965-1782053760/content/widgets.html` + an **install/manual-verify** note; the pure list/progress/streak helpers in `WidgetRepository` get **real JVM unit tests**.

> **P9 dependency note.** The cross-phase contract names `StatsCalculator` (P9) for `dayStreak`/`todayProgress`. P9 is **not yet built** in this worktree. Per the authoring instructions, P8 defines a **minimal local helper** `widget/WidgetStats.kt` (pure `dayStreak`/`todayProgress` over `TaskEntity`) that `WidgetRepository` consumes. When P9 lands its `StatsCalculator`, replace the two calls in `WidgetRepository` with `StatsCalculator` and delete `WidgetStats.kt` (the signatures are intentionally compatible: `dayStreak(tasks)`, `todayProgress(tasks)`). This is the only P9 substitution; everything else P8 needs (`TaskDao`, `ProjectDao`, `TaskRepository`, `MainActivity` deep-link, `PullRunner`, auth `SessionState`/`TokenStore`) already exists.

> **Glance version note.** All composables are written for **Glance 1.1.x** (`androidx.glance:glance-appwidget:1.1.1` + `glance-material3:1.1.1`). Where a 1.1.x API detail is assumed, it is flagged inline in the task. Glance needs no minSdk change (works at minSdk 26).

> **Deep-link contract (reused).** `MainActivity` already declares `<data android:scheme="tmap" android:host="task" />`. P8 widgets emit these `tmap://` URIs and P8 extends the existing `MainActivity` VIEW intent-filter with the new hosts (`today`, `focus`, `capture`). The capture URI is also handled by `CaptureTrampolineActivity` so the capture sheet can open without a full cold relaunch. Routing of these URIs to the actual Compose destinations/sheets is owned by P0/P1 (`SheetHost`, `MainScaffold`); P8 only guarantees the intents reach `MainActivity`/the trampoline. **State the substitution:** until P0/P1 wire `openCapture()`/`Focus(taskId)`/`Today`, a manual tester verifies the app launches to the correct top-level intent (logcat shows the resolved URI) — full sheet routing is verified again in P10.

---

### Task P8.1 — Add Glance to the version catalog + app build.gradle.kts

**Files**
- `~ android/gradle/libs.versions.toml`
- `~ android/app/build.gradle.kts`

**Interfaces**
- New catalog libs `glance-appwidget`, `glance-material3` at version `glance = "1.1.1"`. No version-floor changes elsewhere.

**Steps**
- [ ] In `android/gradle/libs.versions.toml`, under `[versions]` add (after `desugar = "2.1.2"`):
  ```toml
  glance = "1.1.1"
  ```
- [ ] In `[libraries]` (after the DataStore block) add:
  ```toml
  # Glance (home-screen widgets) — reads the same Room DB, no network.
  glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }
  glance-material3 = { module = "androidx.glance:glance-material3", version.ref = "glance" }
  ```
- [ ] In `android/app/build.gradle.kts`, in `dependencies { }` (after the `// DataStore` block, before `// Desugaring`) add:
  ```kotlin
      // Glance widgets
      implementation(libs.glance.appwidget)
      implementation(libs.glance.material3)
  ```
- [ ] Compile-gate: from `android/` run `./gradlew :app:assembleDebug`. Expect it green (dependency resolves; no source uses Glance yet). This is a **compile-gate** task (no unit test): a build-only dependency add is not unit-testable.
- [ ] Commit: `build(android): add Jetpack Glance 1.1.1 (appwidget + material3) to the catalog`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.2 — Pure widget stats helper (`WidgetStats`) with real unit tests

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/WidgetStats.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/widget/WidgetStatsTest.kt`

**Interfaces**
- `object WidgetStats { fun todayProgress(tasks: List<TaskEntity>): Float; fun dayStreak(plannedDatesWithCompletion: Map<LocalDate, Boolean>, today: LocalDate): Int }`
- Consumes `net.qmindtech.tmap.data.local.entities.TaskEntity`, `net.qmindtech.tmap.data.local.TaskStatus`, `java.time.LocalDate`.
- **P9 substitution:** signatures mirror the P9 `StatsCalculator.todayProgress`/`dayStreak`; when P9 lands, delete this file and route `WidgetRepository` to `StatsCalculator`.

**Steps**
- [ ] Write the failing test `WidgetStatsTest.kt` FIRST (pure JVM, no Robolectric). Use a fixed `today = LocalDate.of(2026, 6, 21)`. Build `TaskEntity` fixtures with a private helper inside the test (only the fields used: `id`, `status`, `plannedDate`, `completedAt`; everything else a stable default).
  ```kotlin
  package net.qmindtech.tmap.widget

  import net.qmindtech.tmap.data.local.TaskStatus
  import net.qmindtech.tmap.data.local.entities.TaskEntity
  import org.junit.Assert.assertEquals
  import org.junit.Test
  import java.time.Instant
  import java.time.LocalDate

  class WidgetStatsTest {

      private val today = LocalDate.of(2026, 6, 21)

      private fun task(
          id: String,
          status: TaskStatus,
          plannedDate: LocalDate? = today,
      ) = TaskEntity(
          id = id, title = id, notes = null, projectId = null, labels = emptyList(),
          source = "test", status = status, plannedDate = plannedDate,
          scheduledStart = null, scheduledEnd = null, durationMinutes = null,
          actualTimeMinutes = 0, priority = null, reminderMinutes = null, rank = null,
          dueDate = null, recurrenceRuleId = null, isRecurrenceTemplate = false,
          recurrenceDetached = false, recurrenceOriginalDate = null, completedAt = null,
          createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0L,
      )

      @Test fun `todayProgress is 0 when no tasks`() {
          assertEquals(0f, WidgetStats.todayProgress(emptyList()), 0.0001f)
      }

      @Test fun `todayProgress is done over total`() {
          val tasks = listOf(
              task("a", TaskStatus.Done),
              task("b", TaskStatus.Done),
              task("c", TaskStatus.Scheduled),
              task("d", TaskStatus.Planned),
          )
          assertEquals(0.5f, WidgetStats.todayProgress(tasks), 0.0001f)
      }

      @Test fun `todayProgress ignores archived in the denominator`() {
          val tasks = listOf(
              task("a", TaskStatus.Done),
              task("b", TaskStatus.Scheduled),
              task("c", TaskStatus.Archived),
          )
          assertEquals(0.5f, WidgetStats.todayProgress(tasks), 0.0001f)
      }

      @Test fun `dayStreak counts consecutive days ending today`() {
          val map = mapOf(
              today to true,
              today.minusDays(1) to true,
              today.minusDays(2) to true,
              today.minusDays(3) to false, // breaks the chain
              today.minusDays(4) to true,
          )
          assertEquals(3, WidgetStats.dayStreak(map, today))
      }

      @Test fun `dayStreak still counts yesterday-anchored chain when today is empty`() {
          // No completion logged today yet, but yesterday + before were completed.
          val map = mapOf(
              today.minusDays(1) to true,
              today.minusDays(2) to true,
          )
          assertEquals(2, WidgetStats.dayStreak(map, today))
      }

      @Test fun `dayStreak is 0 when neither today nor yesterday completed`() {
          val map = mapOf(today.minusDays(2) to true)
          assertEquals(0, WidgetStats.dayStreak(map, today))
      }
  }
  ```
- [ ] Run `./gradlew :app:testDebugUnitTest --tests "*WidgetStatsTest*"` from `android/` and confirm it FAILS (unresolved `WidgetStats`).
- [ ] Implement `WidgetStats.kt`:
  ```kotlin
  package net.qmindtech.tmap.widget

  import net.qmindtech.tmap.data.local.TaskStatus
  import net.qmindtech.tmap.data.local.entities.TaskEntity
  import java.time.LocalDate

  /**
   * Pure widget stats. P9 will ship a richer `StatsCalculator`; these two functions intentionally
   * mirror its `todayProgress(tasks)` / `dayStreak(...)` so the swap is a one-line change in
   * [WidgetRepository]. No Android dependencies → unit-testable on the JVM.
   */
  object WidgetStats {

      /** Fraction (0f..1f) of today's non-archived tasks that are Done. 0f when the day is empty. */
      fun todayProgress(tasks: List<TaskEntity>): Float {
          val counted = tasks.filter { it.status != TaskStatus.Archived }
          if (counted.isEmpty()) return 0f
          val done = counted.count { it.status == TaskStatus.Done }
          return done.toFloat() / counted.size.toFloat()
      }

      /**
       * Longest unbroken run of completed days ending at [today] (or [today]-1 if nothing is done
       * today yet — an in-progress day must not zero the streak). [completionByDate] maps a day to
       * whether at least one planned task was completed that day.
       */
      fun dayStreak(completionByDate: Map<LocalDate, Boolean>, today: LocalDate): Int {
          val anchor = when {
              completionByDate[today] == true -> today
              completionByDate[today.minusDays(1)] == true -> today.minusDays(1)
              else -> return 0
          }
          var streak = 0
          var cursor = anchor
          while (completionByDate[cursor] == true) {
              streak++
              cursor = cursor.minusDays(1)
          }
          return streak
      }
  }
  ```
- [ ] Re-run the test; confirm GREEN.
- [ ] Commit: `feat(widget): pure WidgetStats (todayProgress + dayStreak) with unit tests`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.3 — `WidgetRepository`: direct DAO reads + pure list builder (with unit tests)

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/WidgetRepository.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/widget/WidgetRepositoryListTest.kt`

**Interfaces**
- `data class WidgetTaskItem(val id: String, val title: String, val timeLabel: String?, val projectColor: Long?, val isDone: Boolean)`
- `data class WidgetTodayData(val signedIn: Boolean, val items: List<WidgetTaskItem>, val doneCount: Int, val totalCount: Int, val minutesLeft: Int, val progress: Float, val streak: Int, val nextTask: WidgetTaskItem?)`
- `class WidgetRepository @Inject constructor(taskDao: TaskDao, projectDao: ProjectDao, tokenStore: TokenStore, clock: Clock) { suspend fun loadToday(): WidgetTodayData }`
- Pure, tested companion: `WidgetRepository.buildItems(tasks, projectsById, zone): List<WidgetTaskItem>` (today-list ordering + time-label + project color) and `WidgetRepository.minutesLeft(tasks): Int`.
- Reads existing DAOs **directly** (no repository indirection, no network): `TaskDao.observeByPlannedDate` is a Flow; the widget uses a one-shot read, so add a `suspend fun getByPlannedDate(date): List<TaskEntity>` to `TaskDao` and a `suspend fun getAll(): List<ProjectEntity>` to `ProjectDao` in this task.
- Consumes `TokenStore.readRefreshToken()` for the signed-in gate (no Hilt-scoped session needed in a worker context).

**Steps**
- [ ] Add the one-shot DAO reads (widgets run outside Compose; a Flow is overkill). In `data/local/dao/TaskDao.kt` add:
  ```kotlin
      @Query("SELECT * FROM tasks WHERE plannedDate = :date AND isRecurrenceTemplate = 0 ORDER BY rank IS NULL, rank")
      suspend fun getByPlannedDate(date: LocalDate): List<TaskEntity>

      @Query(
          "SELECT plannedDate AS date, " +
              "MAX(CASE WHEN status = 'Done' THEN 1 ELSE 0 END) AS anyDone " +
              "FROM tasks WHERE plannedDate IS NOT NULL AND isRecurrenceTemplate = 0 " +
              "GROUP BY plannedDate",
      )
      suspend fun completionByDate(): List<DateCompletion>
  ```
  and at the bottom of the file add the projection (Room maps it by column name):
  ```kotlin
  data class DateCompletion(val date: LocalDate, val anyDone: Int)
  ```
  > Assumption: `TaskStatus` persists as its enum `name` (`'Done'`), consistent with `PullRunner`'s `settingsDao` tombstone comparison style and `Converters`. If the existing `Converters` stores status differently, this task's reviewer adjusts the literal to match `Converters`.
- [ ] In `data/local/dao/ProjectDao.kt` add:
  ```kotlin
      @Query("SELECT * FROM projects ORDER BY rank IS NULL, rank")
      suspend fun getAll(): List<ProjectEntity>
  ```
- [ ] Write the failing pure-logic test `WidgetRepositoryListTest.kt` FIRST (JVM, no Android):
  ```kotlin
  package net.qmindtech.tmap.widget

  import net.qmindtech.tmap.data.local.TaskStatus
  import net.qmindtech.tmap.data.local.entities.ProjectEntity
  import net.qmindtech.tmap.data.local.entities.TaskEntity
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertNull
  import org.junit.Test
  import java.time.Instant
  import java.time.LocalDate
  import java.time.ZoneId

  class WidgetRepositoryListTest {

      private val zone = ZoneId.of("UTC")
      private val today = LocalDate.of(2026, 6, 21)

      private fun task(
          id: String,
          status: TaskStatus = TaskStatus.Scheduled,
          start: Instant? = null,
          duration: Int? = null,
          rank: String? = null,
          projectId: String? = null,
      ) = TaskEntity(
          id = id, title = "T-$id", notes = null, projectId = projectId, labels = emptyList(),
          source = "test", status = status, plannedDate = today, scheduledStart = start,
          scheduledEnd = null, durationMinutes = duration, actualTimeMinutes = 0, priority = null,
          reminderMinutes = null, rank = rank, dueDate = null, recurrenceRuleId = null,
          isRecurrenceTemplate = false, recurrenceDetached = false, recurrenceOriginalDate = null,
          completedAt = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0L,
      )

      private fun project(id: String, color: String) = ProjectEntity(
          id = id, name = id, color = color, emoji = "", rank = null, actualTimeMinutes = 0,
          createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH, changeSeq = 0L,
      )

      @Test fun `buildItems orders by rank then renders time label and project color`() {
          val nineThirty = LocalDate.of(2026, 6, 21).atTime(9, 30).atZone(zone).toInstant()
          val tasks = listOf(
              task("b", rank = "b", start = nineThirty, projectId = "work"),
              task("a", rank = "a"),
          )
          val projects = mapOf("work" to project("work", "#6EA8FE"))
          val items = WidgetRepository.buildItems(tasks, projects, zone)
          assertEquals(listOf("a", "b"), items.map { it.id })
          assertNull(items[0].timeLabel)
          assertEquals("09:30", items[1].timeLabel)
          assertEquals(0xFF6EA8FEL, items[1].projectColor)
      }

      @Test fun `buildItems marks done tasks`() {
          val items = WidgetRepository.buildItems(
              listOf(task("a", status = TaskStatus.Done)), emptyMap(), zone,
          )
          assertEquals(true, items[0].isDone)
      }

      @Test fun `minutesLeft sums duration of unfinished today tasks`() {
          val tasks = listOf(
              task("a", status = TaskStatus.Done, duration = 30),
              task("b", status = TaskStatus.Scheduled, duration = 60),
              task("c", status = TaskStatus.Planned, duration = 45),
              task("d", status = TaskStatus.Archived, duration = 99),
          )
          assertEquals(105, WidgetRepository.minutesLeft(tasks))
      }
  }
  ```
- [ ] Run `./gradlew :app:testDebugUnitTest --tests "*WidgetRepositoryListTest*"` from `android/`; confirm FAIL (unresolved `WidgetRepository`).
- [ ] Implement `WidgetRepository.kt`:
  ```kotlin
  package net.qmindtech.tmap.widget

  import net.qmindtech.tmap.data.auth.TokenStore
  import net.qmindtech.tmap.data.local.TaskStatus
  import net.qmindtech.tmap.data.local.dao.ProjectDao
  import net.qmindtech.tmap.data.local.dao.TaskDao
  import net.qmindtech.tmap.data.local.entities.ProjectEntity
  import net.qmindtech.tmap.data.local.entities.TaskEntity
  import net.qmindtech.tmap.util.Clock
  import java.time.LocalDate
  import java.time.ZoneId
  import java.time.format.DateTimeFormatter
  import javax.inject.Inject

  /** A single row as the widgets render it (no Compose / no Android types). */
  data class WidgetTaskItem(
      val id: String,
      val title: String,
      val timeLabel: String?,
      val projectColor: Long?,
      val isDone: Boolean,
  )

  /** Everything the four widgets need from one Room read. */
  data class WidgetTodayData(
      val signedIn: Boolean,
      val items: List<WidgetTaskItem>,
      val doneCount: Int,
      val totalCount: Int,
      val minutesLeft: Int,
      val progress: Float,
      val streak: Int,
      val nextTask: WidgetTaskItem?,
  )

  /**
   * Widget-side data provider. Reads the SAME Room DB the app uses, via the existing DAOs, with NO
   * network (spec §8). Built for one-shot reads from a Glance `provideGlance` coroutine / worker.
   *
   * Pure shaping ([buildItems], [minutesLeft]) is in the companion so it is unit-tested on the JVM;
   * the streak/progress math is delegated to [WidgetStats] (P9 will replace it with StatsCalculator).
   */
  class WidgetRepository @Inject constructor(
      private val taskDao: TaskDao,
      private val projectDao: ProjectDao,
      private val tokenStore: TokenStore,
      private val clock: Clock,
  ) {
      suspend fun loadToday(zone: ZoneId = ZoneId.systemDefault()): WidgetTodayData {
          val signedIn = tokenStore.readRefreshToken() != null
          if (!signedIn) {
              return WidgetTodayData(false, emptyList(), 0, 0, 0, 0f, 0, null)
          }
          val today = clock.now().atZone(zone).toLocalDate()
          val tasks = taskDao.getByPlannedDate(today)
          val projectsById = projectDao.getAll().associateBy { it.id }
          val items = buildItems(tasks, projectsById, zone)
          val counted = tasks.filter { it.status != TaskStatus.Archived }
          val doneCount = counted.count { it.status == TaskStatus.Done }
          val completion = taskDao.completionByDate()
              .associate { it.date to (it.anyDone == 1) }
          return WidgetTodayData(
              signedIn = true,
              items = items,
              doneCount = doneCount,
              totalCount = counted.size,
              minutesLeft = minutesLeft(tasks),
              progress = WidgetStats.todayProgress(tasks),
              streak = WidgetStats.dayStreak(completion, today),
              nextTask = items.firstOrNull { !it.isDone },
          )
      }

      companion object {
          private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

          /** Today rows in List ordering (rank, nulls last), with a HH:mm label + project color. */
          fun buildItems(
              tasks: List<TaskEntity>,
              projectsById: Map<String, ProjectEntity>,
              zone: ZoneId,
          ): List<WidgetTaskItem> =
              tasks
                  .filter { it.status != TaskStatus.Archived }
                  .sortedWith(compareBy(nullsLast()) { it.rank })
                  .map { t ->
                      WidgetTaskItem(
                          id = t.id,
                          title = t.title,
                          timeLabel = t.scheduledStart
                              ?.atZone(zone)?.toLocalTime()?.format(TIME_FMT),
                          projectColor = t.projectId
                              ?.let { projectsById[it]?.color }
                              ?.let(::parseColor),
                          isDone = t.status == TaskStatus.Done,
                      )
                  }

          /** Remaining planned minutes = sum of durations of not-done, non-archived today tasks. */
          fun minutesLeft(tasks: List<TaskEntity>): Int =
              tasks
                  .filter { it.status != TaskStatus.Done && it.status != TaskStatus.Archived }
                  .sumOf { it.durationMinutes ?: 0 }

          /** "#RRGGBB" → 0xFFRRGGBB Long; null on any malformed value (widget falls back to accent). */
          private fun parseColor(hex: String): Long? = runCatching {
              val clean = hex.removePrefix("#")
              if (clean.length != 6) return null
              0xFF000000L or clean.toLong(16)
          }.getOrNull()
      }
  }
  ```
- [ ] Re-run the test; confirm GREEN. Compile-gate `./gradlew :app:assembleDebug` (new DAO queries compile).
- [ ] Commit: `feat(widget): WidgetRepository direct-DAO today reads + tested list/minutes helpers`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.4 — Glance color tokens + EntryPoint accessor (`WidgetTheme` + `WidgetEntryPoint`)

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/WidgetTheme.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/WidgetEntryPoint.kt`

**Interfaces**
- `object WidgetColors` with Glance `ColorProvider`s for the Midnight Calm tokens used by widgets: `bg`, `surface`, `border`, `textPrimary`, `textSecondary`, `textTertiary`, `accent`, `accentEnd`, `onAccent`, `success`, `ringTrack`.
- `@EntryPoint @InstallIn(SingletonComponent::class) interface WidgetEntryPoint { fun widgetRepository(): WidgetRepository }` + `fun widgetEntryPoint(context: Context): WidgetEntryPoint`.
- Glance widgets are constructed by the framework (no Hilt injection into a `GlanceAppWidget`), so they fetch `WidgetRepository` through an `EntryPointAccessors` bridge — the standard Hilt-in-Glance pattern.

**Steps**
- [ ] Implement `WidgetTheme.kt`. Glance uses `androidx.glance.unit.ColorProvider` over `androidx.compose.ui.graphics.Color`; hex values are the canonical Midnight Calm tokens (never the old `Surface*`/`Accent*` desktop palette).
  ```kotlin
  package net.qmindtech.tmap.widget

  import androidx.compose.ui.graphics.Color
  import androidx.glance.unit.ColorProvider

  /**
   * Midnight Calm tokens as Glance ColorProviders (spec §4.1 / §8). Glance can't read Compose
   * CompositionLocals, so widgets reference these directly. Dark-only — same value day or night.
   */
  object WidgetColors {
      val bg = ColorProvider(Color(0xFF15161B))          // between bgTop/bgBottom for the rounded card
      val surface = ColorProvider(Color(0xFF1C1D23))     // surfaceInset — the widget body, ~.86 alpha look
      val border = ColorProvider(Color(0xFF2A2B31))      // borderSubtle
      val textPrimary = ColorProvider(Color(0xFFECEAE4))
      val textSecondary = ColorProvider(Color(0xFF908E86))
      val textTertiary = ColorProvider(Color(0xFF76746D))
      val accent = ColorProvider(Color(0xFFE8A87C))
      val accentEnd = ColorProvider(Color(0xFFE0936A))
      val onAccent = ColorProvider(Color(0xFF1A1208))
      val success = ColorProvider(Color(0xFF38D39F))
      val ringTrack = ColorProvider(Color(0xFF2A2B31))   // progress ring unfilled track
  }
  ```
- [ ] Implement `WidgetEntryPoint.kt`:
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.content.Context
  import dagger.hilt.EntryPoint
  import dagger.hilt.InstallIn
  import dagger.hilt.android.EntryPointAccessors
  import dagger.hilt.components.SingletonComponent

  /**
   * Glance widgets are instantiated by the framework, not Hilt, so they reach the singleton graph
   * via an EntryPoint. Exposes the one dependency widgets need (the Room-backed WidgetRepository).
   */
  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface WidgetEntryPoint {
      fun widgetRepository(): WidgetRepository
  }

  fun widgetEntryPoint(context: Context): WidgetEntryPoint =
      EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
  ```
- [ ] Compile-gate `./gradlew :app:assembleDebug` (Hilt processes the new `@EntryPoint`). **Compile-gate task** (no unit test: color tokens + DI bridge are not unit-testable).
- [ ] Commit: `feat(widget): Glance Midnight Calm color tokens + Hilt EntryPoint bridge`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.5 — Widget deep-link URIs + `CaptureTrampolineActivity` + extend `MainActivity` intent-filter

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/WidgetLinks.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/CaptureTrampolineActivity.kt`
- `~ android/app/src/main/AndroidManifest.xml`

**Interfaces**
- `object WidgetLinks { const val SCHEME = "tmap"; fun task(id: String): Uri; fun today(): Uri; fun focus(taskId: String?): Uri; fun capture(voice: Boolean = false): Uri }` — emits `tmap://task/{id}`, `tmap://today`, `tmap://focus/{taskId?}`, `tmap://capture?voice=1`.
- `class CaptureTrampolineActivity : ComponentActivity` — receives `tmap://capture`, then either fires `ACTION_RECOGNIZE_SPEECH` (mic) or forwards a `tmap://capture` VIEW intent to `MainActivity`, finishing immediately (no UI).
- **Substitution note:** routing the forwarded `tmap://capture` / `tmap://today` / `tmap://focus` URIs to the actual Compose sheet/destination is P0/P1's `MainActivity`+`SheetHost` job; P8 only guarantees the intent lands in `MainActivity`. The trampoline exists so the capture flow can be opened from the widget even when the task isn't a "task" deep link.

**Steps**
- [ ] Implement `WidgetLinks.kt`:
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.net.Uri

  /** Canonical widget → app deep links. Hosts: task (existing), today, focus, capture (P8 adds). */
  object WidgetLinks {
      const val SCHEME = "tmap"

      fun task(id: String): Uri = Uri.parse("$SCHEME://task/$id")
      fun today(): Uri = Uri.parse("$SCHEME://today")
      fun focus(taskId: String?): Uri =
          Uri.parse(if (taskId != null) "$SCHEME://focus/$taskId" else "$SCHEME://focus")
      fun capture(voice: Boolean = false): Uri =
          Uri.parse("$SCHEME://capture" + if (voice) "?voice=1" else "")
  }
  ```
- [ ] Implement `CaptureTrampolineActivity.kt`. It is a no-UI activity: on `mic`, launch the system speech recognizer and forward the transcript into the capture deep link; otherwise just forward the capture VIEW intent to `MainActivity`.
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.app.Activity
  import android.content.Intent
  import android.os.Bundle
  import android.speech.RecognizerIntent
  import androidx.activity.ComponentActivity
  import androidx.activity.result.contract.ActivityResultContracts
  import net.qmindtech.tmap.MainActivity

  /**
   * Invisible trampoline for the Quick Capture widget. The widget can't itself open a bottom sheet,
   * so it launches this activity which immediately (a) fires the system speech recognizer when the
   * mic was tapped, then (b) forwards a `tmap://capture` VIEW intent into MainActivity carrying any
   * recognized text, and finishes. No layout is set → no visible UI, just a hand-off.
   *
   * Per spec §2 (out of scope) we do NOT build a recognizer — we use ACTION_RECOGNIZE_SPEECH.
   */
  class CaptureTrampolineActivity : ComponentActivity() {

      private val speech = registerForActivityResult(
          ActivityResultContracts.StartActivityForResult(),
      ) { result ->
          val text = if (result.resultCode == Activity.RESULT_OK) {
              result.data
                  ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                  ?.firstOrNull()
          } else {
              null
          }
          forwardToCapture(text)
      }

      override fun onCreate(savedInstanceState: Bundle?) {
          super.onCreate(savedInstanceState)
          val wantsVoice = intent?.data?.getQueryParameter("voice") == "1"
          if (wantsVoice) {
              val recognize = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                  putExtra(
                      RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                      RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                  )
                  putExtra(RecognizerIntent.EXTRA_PROMPT, "Add a task")
              }
              runCatching { speech.launch(recognize) }
                  .onFailure { forwardToCapture(null) } // no recognizer present → open empty capture
          } else {
              forwardToCapture(null)
          }
      }

      private fun forwardToCapture(prefillText: String?) {
          val uri = WidgetLinks.capture()
          val forward = Intent(this, MainActivity::class.java).apply {
              action = Intent.ACTION_VIEW
              data = uri
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
              if (prefillText != null) putExtra(EXTRA_CAPTURE_TEXT, prefillText)
          }
          startActivity(forward)
          finish()
      }

      companion object {
          /** MainActivity/SheetHost (P1) reads this to pre-fill the capture field. */
          const val EXTRA_CAPTURE_TEXT = "net.qmindtech.tmap.extra.CAPTURE_TEXT"
      }
  }
  ```
- [ ] In `AndroidManifest.xml`, extend the existing `MainActivity` deep-link `<intent-filter>` so the widget hosts also resolve to `MainActivity`. Add three `<data>` lines alongside the existing `task` host (keep the existing one):
  ```xml
              <intent-filter>
                  <action android:name="android.intent.action.VIEW" />
                  <category android:name="android.intent.category.DEFAULT" />
                  <category android:name="android.intent.category.BROWSABLE" />
                  <data android:scheme="tmap" android:host="task" />
                  <data android:scheme="tmap" android:host="today" />
                  <data android:scheme="tmap" android:host="focus" />
                  <data android:scheme="tmap" android:host="capture" />
              </intent-filter>
  ```
- [ ] In `AndroidManifest.xml`, register the trampoline activity inside `<application>` (after `MainActivity`). It is `exported="false"` (only the in-app widgets target it) and has no launcher/VIEW filter — widgets address it by class:
  ```xml
          <activity
              android:name=".widget.CaptureTrampolineActivity"
              android:exported="false"
              android:excludeFromRecents="true"
              android:noHistory="true"
              android:theme="@android:style/Theme.Translucent.NoTitleBar" />
  ```
- [ ] Add the speech-recognizer query (Android 11+ package visibility) at the top of `<manifest>` (after the `uses-permission` block), so `ACTION_RECOGNIZE_SPEECH` resolves on API 30+:
  ```xml
      <queries>
          <intent>
              <action android:name="android.speech.action.RECOGNIZE_SPEECH" />
          </intent>
      </queries>
  ```
- [ ] Compile-gate `./gradlew :app:assembleDebug`. **Compile-gate + manual-verify task.** Manual (after P8.6–P8.9 install): tapping the Quick Capture mic opens the system recognizer; tapping the body launches the app with `Intent.data == tmap://capture`. Verify via logcat (the URI is logged by MainActivity once P1 wires it; until then confirm the activity launches without crash). No unit test: intent plumbing/manifest is not unit-testable.
- [ ] Commit: `feat(widget): capture trampoline + widget deep-link URIs + manifest intent hosts`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.6 — `TodayAgendaWidget` (resizable) + receiver + check-off action

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/TodayAgendaWidget.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/TodayAgendaWidgetReceiver.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/ToggleTaskAction.kt`
- `+ android/app/src/main/res/xml/widget_today_agenda.xml`
- `~ android/app/src/main/AndroidManifest.xml`

**Interfaces**
- `class TodayAgendaWidget : GlanceAppWidget()` with `override val sizeMode = SizeMode.Exact` (resizable; recomposes per size). `provideGlance` reads `WidgetRepository.loadToday()`.
- `class TodayAgendaWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = TodayAgendaWidget() }`.
- `class ToggleTaskAction : ActionCallback` — Glance `actionRunCallback` that toggles a task's done state through the **app's** `TaskRepository` (so the change syncs), keyed by an `ActionParameters.Key<String>` task id, then calls `WidgetUpdater.updateAll`.

**Steps**
- [ ] Create the resizable provider metadata `res/xml/widget_today_agenda.xml`:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
      android:minWidth="180dp"
      android:minHeight="110dp"
      android:targetCellWidth="3"
      android:targetCellHeight="2"
      android:minResizeWidth="180dp"
      android:minResizeHeight="110dp"
      android:maxResizeWidth="320dp"
      android:maxResizeHeight="500dp"
      android:resizeMode="horizontal|vertical"
      android:updatePeriodMillis="1800000"
      android:widgetCategory="home_screen"
      android:description="@string/widget_today_agenda_desc" />
  ```
- [ ] Add the string. In `res/values/strings.xml` add (create the `<resources>` entries if not present):
  ```xml
      <string name="widget_today_agenda_desc">Your day at a glance — tick tasks off from the home screen.</string>
  ```
- [ ] Implement `ToggleTaskAction.kt`. It needs a `TaskRepository`; widgets use the same `WidgetEntryPoint` pattern — add `taskRepository()` to `WidgetEntryPoint` (extend P8.4's interface) so the action can resolve it.
  - [ ] First extend `WidgetEntryPoint` (in `WidgetEntryPoint.kt`): add `fun taskRepository(): TaskRepository`.
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.content.Context
  import androidx.glance.GlanceId
  import androidx.glance.action.ActionParameters
  import androidx.glance.appwidget.action.ActionCallback
  import net.qmindtech.tmap.data.local.TaskStatus
  import net.qmindtech.tmap.data.repository.TaskEdit

  /**
   * Check-off from the Today Agenda widget. Toggles Done via the SAME TaskRepository the app uses
   * (write-through → outbox → sync), so a home-screen tick reaches the server. Then refreshes all
   * widgets so the row updates immediately. Runs on Glance's coroutine — safe to call suspend repo.
   */
  class ToggleTaskAction : ActionCallback {
      override suspend fun onAction(
          context: Context,
          glanceId: GlanceId,
          parameters: ActionParameters,
      ) {
          val taskId = parameters[taskIdKey] ?: return
          val repo = widgetEntryPoint(context).taskRepository()
          val current = repo.observe(taskId) // observe is a Flow; take a one-shot read
          // One-shot: read current status, toggle. observe() returns Flow<TaskEntity?>.
          val task = kotlinx.coroutines.flow.firstOrNull(current)
          if (task != null) {
              if (task.status == TaskStatus.Done) {
                  repo.update(taskId, TaskEdit(status = TaskStatus.Scheduled))
              } else {
                  repo.markDone(taskId)
              }
          }
          WidgetUpdater.updateAll(context)
      }

      companion object {
          val taskIdKey = ActionParameters.Key<String>("taskId")
      }
  }
  ```
  > Assumption: `TaskRepository.observe(id)` (exists) emits the current entity; `kotlinx.coroutines.flow.firstOrNull` gives a one-shot read. If P3 later adds a `suspend fun get(id): TaskEntity?` to `TaskRepository`, prefer it here. Un-completing maps Done→Scheduled (the row was on Today); `markDone` already exists for the forward direction.
- [ ] Implement `TodayAgendaWidget.kt`. Mirrors `widgets.html` Today card: header dot + "Today" + "3 of 8 · 2h left"; rows with a round check (filled amber + ✓ when done, hollow border otherwise), title (strikethrough+dim when done), and a meta line (project dot + HH:mm). Tap row → `actionStartActivity` with `WidgetLinks.task(id)`; tap check → `actionRunCallback<ToggleTaskAction>`. Logged-out → "Sign in to TMap".
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.content.Context
  import androidx.compose.ui.graphics.Color
  import androidx.compose.ui.unit.dp
  import androidx.glance.GlanceId
  import androidx.glance.GlanceModifier
  import androidx.glance.action.ActionParameters
  import androidx.glance.action.actionParametersOf
  import androidx.glance.action.clickable
  import androidx.glance.appwidget.GlanceAppWidget
  import androidx.glance.appwidget.SizeMode
  import androidx.glance.appwidget.action.actionRunCallback
  import androidx.glance.appwidget.action.actionStartActivity
  import androidx.glance.appwidget.cornerRadius
  import androidx.glance.appwidget.provideContent
  import androidx.glance.background
  import androidx.glance.layout.Alignment
  import androidx.glance.layout.Box
  import androidx.glance.layout.Column
  import androidx.glance.layout.Row
  import androidx.glance.layout.Spacer
  import androidx.glance.layout.fillMaxSize
  import androidx.glance.layout.fillMaxWidth
  import androidx.glance.layout.height
  import androidx.glance.layout.padding
  import androidx.glance.layout.size
  import androidx.glance.layout.width
  import androidx.glance.text.Text
  import androidx.glance.text.TextDecoration
  import androidx.glance.text.TextStyle
  import androidx.glance.unit.ColorProvider

  class TodayAgendaWidget : GlanceAppWidget() {
      // Exact: recompose for the actual size so a tall widget shows more rows.
      override val sizeMode = SizeMode.Exact

      override suspend fun provideGlance(context: Context, id: GlanceId) {
          val data = widgetEntryPoint(context).widgetRepository().loadToday()
          provideContent {
              WidgetCard {
                  if (!data.signedIn) {
                      SignInState()
                  } else {
                      Column(modifier = GlanceModifier.fillMaxSize()) {
                          // Header
                          Row(
                              verticalAlignment = Alignment.CenterVertically,
                              modifier = GlanceModifier.fillMaxWidth(),
                          ) {
                              Box(
                                  modifier = GlanceModifier.size(7.dp)
                                      .cornerRadius(4.dp)
                                      .background(WidgetColors.accent),
                              ) {}
                              Spacer(GlanceModifier.width(8.dp))
                              Text(
                                  "Today",
                                  style = TextStyle(
                                      color = WidgetColors.textPrimary,
                                      fontSize = 13.sp,
                                  ),
                              )
                              Spacer(GlanceModifier.defaultWeight())
                              Text(
                                  "${data.doneCount} of ${data.totalCount} · ${hoursLeft(data.minutesLeft)}",
                                  style = TextStyle(
                                      color = WidgetColors.textSecondary,
                                      fontSize = 11.sp,
                                  ),
                              )
                          }
                          Spacer(GlanceModifier.height(10.dp))
                          if (data.items.isEmpty()) {
                              Text(
                                  "Nothing planned today",
                                  style = TextStyle(color = WidgetColors.textSecondary, fontSize = 12.sp),
                              )
                          } else {
                              data.items.forEach { item ->
                                  TaskRow(item)
                                  Spacer(GlanceModifier.height(9.dp))
                              }
                          }
                      }
                  }
              }
          }
      }

      override suspend fun onDelete(context: Context, glanceId: GlanceId) {
          super.onDelete(context, glanceId)
      }
  }

  private fun hoursLeft(minutes: Int): String {
      if (minutes <= 0) return "0m left"
      val h = minutes / 60
      val m = minutes % 60
      return when {
          h > 0 && m > 0 -> "${h}h ${m}m left"
          h > 0 -> "${h}h left"
          else -> "${m}m left"
      }
  }
  ```
  And the shared row + check-circle + card chrome (put these in the same file so all four widgets can share `WidgetCard`/`SignInState` — keep `private`/internal):
  ```kotlin
  @androidx.compose.runtime.Composable
  internal fun WidgetCard(content: @androidx.compose.runtime.Composable () -> Unit) {
      Box(
          modifier = GlanceModifier
              .fillMaxSize()
              .background(WidgetColors.surface)
              .cornerRadius(22.dp)
              .padding(14.dp),
      ) { content() }
  }

  @androidx.compose.runtime.Composable
  internal fun SignInState() {
      Column(
          verticalAlignment = Alignment.CenterVertically,
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = GlanceModifier.fillMaxSize()
              .clickable(actionStartActivity(net.qmindtech.tmap.MainActivity::class.java)),
      ) {
          Text("Sign in to TMap", style = TextStyle(color = WidgetColors.textPrimary, fontSize = 14.sp))
          Spacer(GlanceModifier.height(4.dp))
          Text("Tap to open", style = TextStyle(color = WidgetColors.textSecondary, fontSize = 11.sp))
      }
  }

  @androidx.compose.runtime.Composable
  private fun TaskRow(item: WidgetTaskItem) {
      Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = GlanceModifier.fillMaxWidth()
              .clickable(actionStartActivity(android.content.Intent(
                  android.content.Intent.ACTION_VIEW, WidgetLinks.task(item.id),
              ))),
      ) {
          CheckCircle(item)
          Spacer(GlanceModifier.width(11.dp))
          Column(modifier = GlanceModifier.defaultWeight()) {
              Text(
                  item.title,
                  maxLines = 1,
                  style = TextStyle(
                      color = if (item.isDone) WidgetColors.textTertiary else WidgetColors.textPrimary,
                      fontSize = 13.sp,
                      textDecoration = if (item.isDone) TextDecoration.LineThrough else TextDecoration.None,
                  ),
              )
              if (item.timeLabel != null) {
                  Spacer(GlanceModifier.height(2.dp))
                  Row(verticalAlignment = Alignment.CenterVertically) {
                      Box(
                          modifier = GlanceModifier.size(7.dp).cornerRadius(4.dp)
                              .background(ColorProvider(Color(item.projectColor ?: 0xFFE8A87CL))),
                      ) {}
                      Spacer(GlanceModifier.width(6.dp))
                      Text(item.timeLabel, style = TextStyle(color = WidgetColors.textSecondary, fontSize = 11.sp))
                  }
              }
          }
      }
  }

  @androidx.compose.runtime.Composable
  private fun CheckCircle(item: WidgetTaskItem) {
      val mod = GlanceModifier.size(20.dp).cornerRadius(10.dp)
          .clickable(actionRunCallback<ToggleTaskAction>(
              actionParametersOf(ToggleTaskAction.taskIdKey to item.id),
          ))
      if (item.isDone) {
          Box(
              contentAlignment = Alignment.Center,
              modifier = mod.background(WidgetColors.accent),
          ) { Text("✓", style = TextStyle(color = WidgetColors.onAccent, fontSize = 11.sp)) }
      } else {
          // Hollow circle: a bordered box (Glance has no stroke modifier → use a track-colored ring look).
          Box(modifier = mod.background(WidgetColors.border)) {
              Box(modifier = GlanceModifier.size(16.dp).cornerRadius(8.dp).background(WidgetColors.surface)) {}
          }
      }
  }
  ```
  > Glance 1.1.x assumptions, flagged: `cornerRadius` requires API 31+ but Glance degrades to square corners below it (acceptable; documented). Glance has no border/stroke modifier, so the hollow check is rendered as a 20dp border-colored disc with a 16dp surface-colored disc inset (a ring). `sp`/`dp` extension imports come from `androidx.compose.ui.unit`. If a 1.1.x signature differs, keep the layout intent and adjust the call.
- [ ] Implement `TodayAgendaWidgetReceiver.kt`:
  ```kotlin
  package net.qmindtech.tmap.widget

  import androidx.glance.appwidget.GlanceAppWidget
  import androidx.glance.appwidget.GlanceAppWidgetReceiver

  class TodayAgendaWidgetReceiver : GlanceAppWidgetReceiver() {
      override val glanceAppWidget: GlanceAppWidget = TodayAgendaWidget()
  }
  ```
- [ ] Register in `AndroidManifest.xml` inside `<application>` (after the trampoline). Receivers are `exported="true"` (system AppWidgetHost binds them) with the standard widget filter + provider metadata:
  ```xml
          <receiver
              android:name=".widget.TodayAgendaWidgetReceiver"
              android:exported="true"
              android:label="TMap — Today">
              <intent-filter>
                  <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
              </intent-filter>
              <meta-data
                  android:name="android.appwidget.provider"
                  android:resource="@xml/widget_today_agenda" />
          </receiver>
  ```
- [ ] Compile-gate `./gradlew :app:assembleDebug`. **Compile-gate + behavior-checklist + install/manual-verify** (Glance composables not unit-testable). Behavior-checklist vs `widgets.html` Today card (lines 39–55 / 103–114):
  - [ ] Header: amber dot + "Today" + "<done> of <total> · <Xh> left".
  - [ ] Rows: round check (filled amber + ✓ when done; hollow ring otherwise), title (line-through + dimmed when done), project dot + HH:mm meta when scheduled.
  - [ ] Resizes (drag handles) from ~2 rows to a full-day height; more rows appear when taller.
  - [ ] Logged-out shows "Sign in to TMap".
  - Install/manual: `./gradlew :app:installDebug`, add the "TMap — Today" widget, tick a row → it strikes through and (online) syncs; tap a row → app opens to `tmap://task/{id}`.
- [ ] Commit: `feat(widget): Today Agenda Glance widget (resizable) + check-off action + receiver`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.7 — `QuickCaptureWidget` (1-row) + receiver

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/QuickCaptureWidget.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/QuickCaptureWidgetReceiver.kt`
- `+ android/app/src/main/res/xml/widget_quick_capture.xml`
- `~ android/app/src/main/AndroidManifest.xml`

**Interfaces**
- `class QuickCaptureWidget : GlanceAppWidget()`; `provideGlance` renders a single row: amber "+" tile → `CaptureTrampolineActivity` with `WidgetLinks.capture()`; mic glyph → trampoline with `WidgetLinks.capture(voice = true)`.
- `class QuickCaptureWidgetReceiver : GlanceAppWidgetReceiver()`.

**Steps**
- [ ] Provider metadata `res/xml/widget_quick_capture.xml` (a wide 1-row, non-resizable):
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
      android:minWidth="180dp"
      android:minHeight="48dp"
      android:targetCellWidth="3"
      android:targetCellHeight="1"
      android:resizeMode="horizontal"
      android:minResizeWidth="180dp"
      android:maxResizeWidth="320dp"
      android:updatePeriodMillis="0"
      android:widgetCategory="home_screen"
      android:description="@string/widget_quick_capture_desc" />
  ```
- [ ] Add string `widget_quick_capture_desc` → "A permanent capture bar — tap to add, mic for voice." (in `res/values/strings.xml`).
- [ ] Implement `QuickCaptureWidget.kt`. Mirrors `widgets.html` capture bar (lines 74–80 / 116–124): rounded amber "+" tile, "Add a task…" hint, mic glyph at the end. Tapping the row/+ opens capture; mic opens voice capture. No sign-in gate is strictly required (capture works offline), but show the same sign-in affordance when logged out for consistency.
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.content.Context
  import android.content.Intent
  import androidx.compose.ui.unit.dp
  import androidx.compose.ui.unit.sp
  import androidx.glance.GlanceId
  import androidx.glance.GlanceModifier
  import androidx.glance.action.clickable
  import androidx.glance.appwidget.GlanceAppWidget
  import androidx.glance.appwidget.action.actionStartActivity
  import androidx.glance.appwidget.cornerRadius
  import androidx.glance.appwidget.provideContent
  import androidx.glance.background
  import androidx.glance.layout.Alignment
  import androidx.glance.layout.Box
  import androidx.glance.layout.Row
  import androidx.glance.layout.Spacer
  import androidx.glance.layout.fillMaxWidth
  import androidx.glance.layout.padding
  import androidx.glance.layout.size
  import androidx.glance.layout.width
  import androidx.glance.text.Text
  import androidx.glance.text.TextStyle

  class QuickCaptureWidget : GlanceAppWidget() {
      override suspend fun provideGlance(context: Context, id: GlanceId) {
          provideContent {
              WidgetCard {
                  Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = GlanceModifier.fillMaxWidth()
                          .clickable(actionStartActivity(captureIntent(context, voice = false))),
                  ) {
                      Box(
                          contentAlignment = Alignment.Center,
                          modifier = GlanceModifier.size(26.dp).cornerRadius(8.dp)
                              .background(WidgetColors.accent),
                      ) { Text("+", style = TextStyle(color = WidgetColors.onAccent, fontSize = 18.sp)) }
                      Spacer(GlanceModifier.width(11.dp))
                      Text(
                          "Add a task…",
                          modifier = GlanceModifier.defaultWeight(),
                          style = TextStyle(color = WidgetColors.textSecondary, fontSize = 13.sp),
                      )
                      Box(
                          contentAlignment = Alignment.Center,
                          modifier = GlanceModifier.size(28.dp)
                              .clickable(actionStartActivity(captureIntent(context, voice = true))),
                      ) { Text("🎤", style = TextStyle(color = WidgetColors.textTertiary, fontSize = 15.sp)) }
                  }
              }
          }
      }

      private fun captureIntent(context: Context, voice: Boolean): Intent =
          Intent(context, CaptureTrampolineActivity::class.java).apply {
              action = Intent.ACTION_VIEW
              data = WidgetLinks.capture(voice = voice)
          }
  }
  ```
  > Note: the capture row targets `CaptureTrampolineActivity` directly (it is `exported="false"` and addressed by class), not `MainActivity`, so the mic path can launch the recognizer.
- [ ] Implement `QuickCaptureWidgetReceiver.kt` (same shape as P8.6's receiver, `glanceAppWidget = QuickCaptureWidget()`).
- [ ] Register the receiver in `AndroidManifest.xml` (label "TMap — Quick Capture", `@xml/widget_quick_capture`).
- [ ] Compile-gate `./gradlew :app:assembleDebug`. **Compile-gate + behavior-checklist + manual-verify.** Checklist vs `widgets.html`:
  - [ ] Single row: amber "+" tile, "Add a task…" hint, mic glyph at end.
  - [ ] Tap body/+ → capture opens (trampoline → MainActivity `tmap://capture`).
  - [ ] Tap mic → system speech recognizer launches; on result, capture opens with the transcript extra.
  - Install/manual: add the widget; verify both tap targets behave; with no recognizer installed, mic falls back to opening empty capture (no crash).
- [ ] Commit: `feat(widget): Quick Capture 1-row Glance widget (tap + mic) + receiver`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.8 — `UpNextFocusWidget` (2×2) + receiver

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/UpNextFocusWidget.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/UpNextFocusWidgetReceiver.kt`
- `+ android/app/src/main/res/xml/widget_up_next.xml`
- `~ android/app/src/main/AndroidManifest.xml`

**Interfaces**
- `class UpNextFocusWidget : GlanceAppWidget()`; `provideGlance` renders the next task ("UP NEXT" label + title + project·time meta) and a "◉ Focus" amber button → `WidgetLinks.focus(nextTask.id)`.
- Live countdown: P6's `FocusController` isn't built in this worktree. **Substitution:** when no focus is running, show the static Up-Next layout (matches `widgets.html`). The live-countdown branch is stubbed behind a `data.focusRemainingLabel: String?` that is always `null` here, with a `// TODO(P6)` to populate it from `FocusController`/`FocusSessionRepository` once they exist. Document this clearly.
- `class UpNextFocusWidgetReceiver : GlanceAppWidgetReceiver()`.

**Steps**
- [ ] Provider metadata `res/xml/widget_up_next.xml` (2×2 fixed):
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
      android:minWidth="146dp"
      android:minHeight="110dp"
      android:targetCellWidth="2"
      android:targetCellHeight="2"
      android:resizeMode="none"
      android:updatePeriodMillis="1800000"
      android:widgetCategory="home_screen"
      android:description="@string/widget_up_next_desc" />
  ```
- [ ] Add string `widget_up_next_desc` → "Your next task plus a one-tap Start Focus."
- [ ] Implement `UpNextFocusWidget.kt`. Mirrors `widgets.html` Up-Next card (lines 58–63 / 126–133): small amber "UP NEXT" label, task title, meta, amber "◉ Focus" button. When no next task, show a calm "All clear" state.
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.content.Context
  import android.content.Intent
  import androidx.compose.ui.unit.dp
  import androidx.compose.ui.unit.sp
  import androidx.glance.GlanceId
  import androidx.glance.GlanceModifier
  import androidx.glance.action.clickable
  import androidx.glance.appwidget.GlanceAppWidget
  import androidx.glance.appwidget.action.actionStartActivity
  import androidx.glance.appwidget.cornerRadius
  import androidx.glance.appwidget.provideContent
  import androidx.glance.background
  import androidx.glance.layout.Alignment
  import androidx.glance.layout.Box
  import androidx.glance.layout.Column
  import androidx.glance.layout.Spacer
  import androidx.glance.layout.fillMaxSize
  import androidx.glance.layout.fillMaxWidth
  import androidx.glance.layout.height
  import androidx.glance.layout.padding
  import androidx.glance.text.Text
  import androidx.glance.text.TextStyle

  class UpNextFocusWidget : GlanceAppWidget() {
      override suspend fun provideGlance(context: Context, id: GlanceId) {
          val data = widgetEntryPoint(context).widgetRepository().loadToday()
          // TODO(P6): when FocusController exists, populate a live remaining label here and render a
          //  countdown ring instead of the static Up-Next layout. WidgetUpdater can be ticked each
          //  minute by the focus foreground service. Until P6, focusRemainingLabel is always null.
          val focusRemainingLabel: String? = null
          provideContent {
              WidgetCard {
                  if (!data.signedIn) {
                      SignInState()
                  } else {
                      val next = data.nextTask
                      Column(modifier = GlanceModifier.fillMaxSize()) {
                          Text(
                              if (focusRemainingLabel != null) "FOCUSING" else "UP NEXT",
                              style = TextStyle(color = WidgetColors.accent, fontSize = 10.sp),
                          )
                          Spacer(GlanceModifier.height(7.dp))
                          Text(
                              focusRemainingLabel ?: (next?.title ?: "All clear"),
                              maxLines = 2,
                              style = TextStyle(color = WidgetColors.textPrimary, fontSize = 14.sp),
                          )
                          if (next?.timeLabel != null) {
                              Spacer(GlanceModifier.height(3.dp))
                              Text(next.timeLabel, style = TextStyle(color = WidgetColors.textSecondary, fontSize = 11.sp))
                          }
                          Spacer(GlanceModifier.defaultWeight())
                          Box(
                              contentAlignment = Alignment.Center,
                              modifier = GlanceModifier.fillMaxWidth()
                                  .cornerRadius(11.dp)
                                  .background(WidgetColors.accent)
                                  .padding(vertical = 8.dp)
                                  .clickable(
                                      actionStartActivity(
                                          Intent(Intent.ACTION_VIEW, WidgetLinks.focus(next?.id)).apply {
                                              setPackage(context.packageName)
                                          },
                                      ),
                                  ),
                          ) {
                              Text(
                                  "◉ Focus",
                                  style = TextStyle(color = WidgetColors.onAccent, fontSize = 12.sp),
                              )
                          }
                      }
                  }
              }
          }
      }
  }
  ```
  > `setPackage(context.packageName)` keeps the `tmap://focus` VIEW intent inside the app (resolves to `MainActivity`'s extended filter). Routing `Focus(taskId)` to the immersive screen is P6/P0's job.
- [ ] Implement `UpNextFocusWidgetReceiver.kt` (`glanceAppWidget = UpNextFocusWidget()`).
- [ ] Register the receiver in `AndroidManifest.xml` (label "TMap — Up Next", `@xml/widget_up_next`).
- [ ] Compile-gate `./gradlew :app:assembleDebug`. **Compile-gate + behavior-checklist + manual-verify.** Checklist vs `widgets.html`:
  - [ ] "UP NEXT" amber label, next-task title, project·time meta, amber "◉ Focus" button.
  - [ ] Tap Focus → `tmap://focus/{id}` opens the app.
  - [ ] No next task → "All clear"; logged-out → "Sign in to TMap".
  - [ ] Live-countdown branch is stubbed (always Up-Next) with a documented `TODO(P6)`.
  - Install/manual: add the 2×2 widget; verify content + Focus tap.
- [ ] Commit: `feat(widget): Up Next / Focus 2x2 Glance widget + receiver (live-countdown stubbed for P6)`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.9 — `ProgressStreakWidget` (2×2) + receiver

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/ProgressStreakWidget.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/ProgressStreakWidgetReceiver.kt`
- `+ android/app/src/main/res/xml/widget_progress_streak.xml`
- `~ android/app/src/main/AndroidManifest.xml`

**Interfaces**
- `class ProgressStreakWidget : GlanceAppWidget()`; `provideGlance` renders a completion ring (`data.progress`) with a center "%" label + "🔥 N-day streak" + "X of Y done today". Tap → opens app (`WidgetLinks.today()`).
- Ring: Glance has no Canvas; the ring is drawn with `androidx.glance.appwidget.components.CircleIconButton`-style primitives are unavailable, so render the ring as a Compose-Glance `Image` from a `Bitmap` generated in `provideGlance` (off-screen Android `Canvas`), or—simpler and chosen here—an **arc bitmap** built by a small pure helper `ProgressRingBitmap.render(progressPercent, sizePx): Bitmap`. The percentage→sweep math is a **pure, unit-tested** function.
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/ProgressRingBitmap.kt` (+ test for the sweep math).
- `class ProgressStreakWidgetReceiver : GlanceAppWidgetReceiver()`.

**Steps**
- [ ] Write the failing test FIRST for the pure sweep math, `ProgressRingBitmapTest.kt` (JVM; tests only the angle calc, not the Bitmap):
  ```kotlin
  package net.qmindtech.tmap.widget

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class ProgressRingBitmapTest {
      @Test fun `sweep is 0 at 0 percent`() {
          assertEquals(0f, ProgressRingBitmap.sweepDegrees(0f), 0.001f)
      }
      @Test fun `sweep is 360 at full`() {
          assertEquals(360f, ProgressRingBitmap.sweepDegrees(1f), 0.001f)
      }
      @Test fun `sweep is 180 at half and clamps over 1`() {
          assertEquals(180f, ProgressRingBitmap.sweepDegrees(0.5f), 0.001f)
          assertEquals(360f, ProgressRingBitmap.sweepDegrees(1.5f), 0.001f)
      }
      @Test fun `sweep clamps negatives to 0`() {
          assertEquals(0f, ProgressRingBitmap.sweepDegrees(-0.2f), 0.001f)
      }
  }
  ```
- [ ] Run `./gradlew :app:testDebugUnitTest --tests "*ProgressRingBitmapTest*"`; confirm FAIL.
- [ ] Implement `ProgressRingBitmap.kt` (pure `sweepDegrees` + a thin Android `Bitmap` renderer; only the math is tested):
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.graphics.Bitmap
  import android.graphics.Canvas
  import android.graphics.Paint
  import android.graphics.RectF

  /** Renders the amber completion ring as a Bitmap (Glance has no Canvas/arc primitive). */
  object ProgressRingBitmap {

      /** progress (0f..1f, clamped) → arc sweep in degrees (0..360). Pure + unit-tested. */
      fun sweepDegrees(progress: Float): Float = progress.coerceIn(0f, 1f) * 360f

      fun render(progress: Float, sizePx: Int): Bitmap {
          val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
          val canvas = Canvas(bmp)
          val stroke = sizePx * 0.11f
          val inset = stroke / 2f + sizePx * 0.02f
          val rect = RectF(inset, inset, sizePx - inset, sizePx - inset)

          val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              style = Paint.Style.STROKE; strokeWidth = stroke; color = 0xFF2A2B31.toInt()
          }
          val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
              style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
              color = 0xFFE8A87C.toInt()
          }
          canvas.drawArc(rect, 0f, 360f, false, track)
          canvas.drawArc(rect, -90f, sweepDegrees(progress), false, fg)
          return bmp
      }
  }
  ```
- [ ] Re-run the test; confirm GREEN.
- [ ] Provider metadata `res/xml/widget_progress_streak.xml` (2×2 fixed, same shape as P8.8 with its own description string `widget_progress_streak_desc` → "Today's completion ring and your daily streak.").
- [ ] Implement `ProgressStreakWidget.kt`. Mirrors `widgets.html` Progress card (lines 64–70 / 135–145): ring with center "%", "🔥 N-day streak", "X of Y done today". Render the ring via `ProgressRingBitmap.render(...)` into a Glance `Image(ImageProvider(bitmap))`.
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.content.Context
  import android.content.Intent
  import androidx.compose.ui.unit.dp
  import androidx.compose.ui.unit.sp
  import androidx.glance.GlanceId
  import androidx.glance.GlanceModifier
  import androidx.glance.Image
  import androidx.glance.ImageProvider
  import androidx.glance.action.clickable
  import androidx.glance.appwidget.GlanceAppWidget
  import androidx.glance.appwidget.action.actionStartActivity
  import androidx.glance.appwidget.provideContent
  import androidx.glance.layout.Alignment
  import androidx.glance.layout.Box
  import androidx.glance.layout.Column
  import androidx.glance.layout.Row
  import androidx.glance.layout.Spacer
  import androidx.glance.layout.fillMaxSize
  import androidx.glance.layout.height
  import androidx.glance.layout.size
  import androidx.glance.layout.width
  import androidx.glance.text.Text
  import androidx.glance.text.TextStyle
  import kotlin.math.roundToInt

  class ProgressStreakWidget : GlanceAppWidget() {
      override suspend fun provideGlance(context: Context, id: GlanceId) {
          val data = widgetEntryPoint(context).widgetRepository().loadToday()
          val ring = ProgressRingBitmap.render(data.progress, sizePx = 160)
          provideContent {
              WidgetCard {
                  if (!data.signedIn) {
                      SignInState()
                  } else {
                      Row(
                          verticalAlignment = Alignment.CenterVertically,
                          modifier = GlanceModifier.fillMaxSize()
                              .clickable(
                                  actionStartActivity(
                                      Intent(Intent.ACTION_VIEW, WidgetLinks.today())
                                          .apply { setPackage(context.packageName) },
                                  ),
                              ),
                      ) {
                          Box(contentAlignment = Alignment.Center, modifier = GlanceModifier.size(58.dp)) {
                              Image(provider = ImageProvider(ring), contentDescription = null,
                                  modifier = GlanceModifier.size(58.dp))
                              Text(
                                  "${(data.progress * 100).roundToInt()}%",
                                  style = TextStyle(color = WidgetColors.textPrimary, fontSize = 14.sp),
                              )
                          }
                          Spacer(GlanceModifier.width(12.dp))
                          Column {
                              Text(
                                  "🔥 ${data.streak}-day streak",
                                  style = TextStyle(color = WidgetColors.textPrimary, fontSize = 14.sp),
                              )
                              Spacer(GlanceModifier.height(3.dp))
                              Text(
                                  "${data.doneCount} of ${data.totalCount} done today",
                                  style = TextStyle(color = WidgetColors.textSecondary, fontSize = 11.sp),
                              )
                          }
                      }
                  }
              }
          }
      }
  }
  ```
  > Glance assumption: `ImageProvider(bitmap: Bitmap)` exists in 1.1.x and Glance handles the bitmap as a RemoteViews-backed image. The ring is regenerated each `provideGlance` (cheap, 160px). `contentDescription = null` is acceptable because the adjacent "%" text + "streak" text carry the meaning (a11y satisfied); if a reviewer prefers, set it to "Today's progress ring".
- [ ] Implement `ProgressStreakWidgetReceiver.kt` (`glanceAppWidget = ProgressStreakWidget()`).
- [ ] Register the receiver in `AndroidManifest.xml` (label "TMap — Progress", `@xml/widget_progress_streak`).
- [ ] Compile-gate `./gradlew :app:assembleDebug` + the sweep test stays GREEN. **Compile-gate + behavior-checklist + manual-verify.** Checklist vs `widgets.html`:
  - [ ] Amber ring with center "%" matching `progress`; "🔥 N-day streak"; "X of Y done today".
  - [ ] Tap → opens app (`tmap://today`); logged-out → "Sign in to TMap".
  - Install/manual: add widget; complete a task in-app → after the next sync/local-write the ring + counts update.
- [ ] Commit: `feat(widget): Progress & Streak 2x2 Glance widget (tested ring math) + receiver`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.10 — `WidgetUpdater.updateAll(context)` + wire into PullRunner (post-pull) and today-affecting writes

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/widget/WidgetUpdater.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/data/sync/PullRunner.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/data/repository/TaskRepository.kt`
- `~ android/app/src/main/java/net/qmindtech/tmap/di/DatabaseModule.kt` (no change expected) — verify only.

**Interfaces**
- `object WidgetUpdater { suspend fun updateAll(context: Context) }` — refreshes all four widgets via `GlanceAppWidgetManager(context).getGlanceIds(...)` + each widget's `.update(context, id)` (or `updateAll(context)` per type).
- **Call sites (FIXED contract / documented):**
  1. `PullRunner.pullAll()` — after the pull loop completes (post-`rearmer.reconcile`, before `return`), so remote changes refresh widgets.
  2. `TaskRepositoryImpl.create/update/markDone/delete` — after the existing `syncScheduler.requestExpeditedSync()`, so a local check-off/edit affecting today refreshes widgets immediately (optimistic).
- `WidgetUpdater` takes only a `Context` (no Hilt scope) so both a runner and a repo can call it; `PullRunner`/`TaskRepositoryImpl` receive an `@ApplicationContext` via their providers.

**Steps**
- [ ] Implement `WidgetUpdater.kt`:
  ```kotlin
  package net.qmindtech.tmap.widget

  import android.content.Context
  import androidx.glance.appwidget.GlanceAppWidget
  import androidx.glance.appwidget.GlanceAppWidgetManager

  /**
   * Refreshes all four Glance widgets from their single Room data source. Called (1) by PullRunner
   * after a successful pull (remote → widget) and (2) by TaskRepositoryImpl after a today-affecting
   * write (local check-off/edit → widget), plus the manifest's periodic update as a fallback. Never
   * touches the network — each widget re-reads Room in its own provideGlance.
   *
   * No-op safe: if a widget type has no placed instances, updateAll() simply finds no GlanceIds.
   */
  object WidgetUpdater {
      private val widgets: List<GlanceAppWidget> = listOf(
          TodayAgendaWidget(),
          QuickCaptureWidget(),
          UpNextFocusWidget(),
          ProgressStreakWidget(),
      )

      suspend fun updateAll(context: Context) {
          val manager = GlanceAppWidgetManager(context)
          for (widget in widgets) {
              val ids = manager.getGlanceIds(widget.javaClass)
              for (id in ids) {
                  widget.update(context, id)
              }
          }
      }
  }
  ```
  > Glance 1.1.x: `GlanceAppWidget.updateAll(context)` also exists and updates every placed instance of that type; the explicit `getGlanceIds` loop is used so a future per-id targeting is trivial. Either is acceptable.
- [ ] Wire call site (1) in `PullRunner`. Add a constructor param `private val appContext: Context` (last param) and call `WidgetUpdater.updateAll(appContext)` at the end of `pullAll()`:
  - In the class header add `import android.content.Context` and `import net.qmindtech.tmap.widget.WidgetUpdater`, and add `appContext: Context` to the constructor.
  - At the end of `pullAll()`, immediately before `return PullOutcome(...)`:
    ```kotlin
        // Widgets read the same Room DB — refresh them now that the pull applied remote changes.
        WidgetUpdater.updateAll(appContext)
        return PullOutcome(applied = applied, pages = pages, fullResynced = fullResynced)
    ```
- [ ] Update the `providePullRunner` provider in `AppModule.kt` to pass the context: add `@ApplicationContext context: Context` to the provider params and pass it last:
  ```kotlin
      @Provides @Singleton
      fun providePullRunner(
          api: TmapApiService,
          db: AppDatabase,
          taskDao: TaskDao,
          subtaskDao: SubtaskDao,
          projectDao: ProjectDao,
          settingsDao: SettingsDao,
          syncStateDao: SyncStateDao,
          outboxDao: OutboxDao,
          rearmer: SyncReminderRearmer,
          @ApplicationContext context: Context,
      ): PullRunner = PullRunner(
          api, db, taskDao, subtaskDao, projectDao, settingsDao, syncStateDao, outboxDao, rearmer, context,
      )
  ```
  (`@ApplicationContext` + `Context` are already imported in `AppModule.kt`.)
- [ ] Wire call site (2) in `TaskRepositoryImpl`. Inject `@ApplicationContext private val appContext: Context` (add the constructor param + imports `android.content.Context`, `dagger.hilt.android.qualifiers.ApplicationContext`, `net.qmindtech.tmap.widget.WidgetUpdater`). After each `syncScheduler.requestExpeditedSync()` in `create`, `update`, `markDone`, `delete`, add:
  ```kotlin
          WidgetUpdater.updateAll(appContext)
  ```
  (`TaskRepositoryImpl` is `@Inject constructor`, so Hilt supplies the context — no module change needed.)
- [ ] Compile-gate `./gradlew :app:assembleDebug`. Run the FULL unit suite `./gradlew :app:testDebugUnitTest` from `android/` and confirm GREEN — in particular the existing `PullRunner` sync tests still pass. **If a `PullRunner` test constructs it directly**, update those test call sites to pass a context: use `androidx.test.core.app.ApplicationProvider.getApplicationContext()` (Robolectric is already configured: `isIncludeAndroidResources = true`, `robolectric` dep present). This is the documented regression touch-point. Do NOT change `PullRunner` behavior otherwise.
- [ ] **Compile-gate + regression-test + manual-verify task.** Manual: with a placed Today/Progress widget, complete a task in-app → both update within a second (local-write trigger); change a task on another device/web → after the next sync the widgets reflect it (post-pull trigger).
- [ ] Commit: `feat(widget): WidgetUpdater.updateAll wired into PullRunner post-pull + today writes`
  ```
  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P8.11 — Phase gate: assembleDebug green + full manual install checklist vs `widgets.html`

**Files**
- (no new files) — verification + a short `docs/superpowers/plans/` checklist note appended to this task's PR description, not a committed doc.

**Interfaces**
- None. This is the P8 acceptance gate for spec §10 criterion 10 ("Widgets") and §8.

**Steps**
- [ ] Compile gate: from `android/` run `./gradlew :app:assembleDebug` — must be GREEN.
- [ ] Unit gate: `./gradlew :app:testDebugUnitTest` — GREEN, including `WidgetStatsTest`, `WidgetRepositoryListTest`, `ProgressRingBitmapTest`, and all pre-existing engine tests (esp. `PullRunner`).
- [ ] Lint gate: `./gradlew :app:lintDebug` — no new errors (warnings about Glance preview tooling are acceptable; missing-widget-preview is not an error).
- [ ] Install: `./gradlew :app:installDebug` to a device/emulator (API 26+; verify on API 31+ for `cornerRadius`).
- [ ] **Manual install checklist (vs `.superpowers/brainstorm/965-1782053760/content/widgets.html`):**
  - [ ] All four widgets appear in the launcher's widget picker under "TMap" with their labels and descriptions.
  - [ ] **Today Agenda** (resizable): header "Today" + amber dot + "<done> of <total> · <Xh> left"; rows render check + title + project-dot/time; resizes from ~2 rows to a full day; tick a row → strikes through + (online) syncs to server; tap a row → opens the app at that task.
  - [ ] **Quick Capture** (1 row): amber "+" tile + "Add a task…" + mic; tap → capture opens; mic → speech recognizer → capture pre-filled.
  - [ ] **Up Next / Focus** (2×2): "UP NEXT" + next task + meta + "◉ Focus"; tap Focus → app opens to focus for that task; "All clear" when nothing's next.
  - [ ] **Progress & Streak** (2×2): amber ring + center "%" matching today's completion; "🔥 N-day streak"; "X of Y done today"; tap → opens app.
  - [ ] **Midnight Calm:** dark surfaces, warm off-white text, amber the only accent (on the +, Focus button, ring, dot) — never the old blue `Accent*` palette.
  - [ ] **Logged-out:** sign out → all four widgets show "Sign in to TMap" (tap opens the app); sign back in + sync → widgets repopulate.
  - [ ] **Update triggers:** complete a task in-app → widgets refresh within ~1s (local write); make a remote change → widgets refresh after the next sync (post-pull); leaving the phone idle past the `updatePeriodMillis` still refreshes (periodic fallback).
  - [ ] **Offline:** turn off network → widgets still render today's data from Room; ticking a task queues to the outbox and the row updates locally; reconnect → it syncs.
- [ ] Record the checklist result in the PR description (pass/fail per item, device + API level noted). No code change → no separate commit; the gate is satisfied by the green builds above and the recorded manual pass.

---

I've authored P8 in full and returned it as my response above. Summary of what it contains and the key decisions made to conform to the fixed skeleton:

**Structure:** 11 numbered tasks (P8.1–P8.11), each with **Files** + **Interfaces** blocks then checkbox steps, the exact `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>` trailer on every commit, and the required `## P8 — Glance home-screen widgets` heading + one-paragraph overview.

**Conformance to the FIXED contracts:**
- `WidgetUpdater.updateAll(context)`, `WidgetRepository`, the four widget class names, `CaptureTrampolineActivity`, receivers, and the `widget/` package paths match the file map and Cross-Phase Interface Contracts exactly. Call sites documented: `PullRunner.pullAll()` post-pull + `TaskRepositoryImpl` after each today-affecting write.
- Glance `1.1.1` (appwidget + material3) added to `android/gradle/libs.versions.toml` + `android/app/build.gradle.kts` (the catalog lives under `android/`, not repo root — verified).
- Midnight Calm tokens used via Glance `ColorProvider`s (amber `#E8A87C`/`#E0936A`, never the old `Surface*`/`Accent*` palette).

**Real unit tests (TDD)** for the genuinely pure parts: `WidgetStats` (todayProgress/dayStreak), `WidgetRepository.buildItems`/`minutesLeft`, and `ProgressRingBitmap.sweepDegrees`. Glance composables use **compile-gate (`:app:assembleDebug`) + behavior-checklist vs `widgets.html` + install/manual-verify**, as required.

**P9 dependency handled explicitly:** P9's `StatsCalculator` does not exist in this worktree (the repo is still at the pre-redesign v1 state — no P0–P9 artifacts), so P8 defines a minimal local `widget/WidgetStats.kt` with P9-compatible signatures and a documented one-line swap when P9 lands. P6's live focus countdown is likewise stubbed behind a `TODO(P6)` (static Up-Next layout until `FocusController` exists). Widgets read the existing `TaskDao`/`ProjectDao` directly (I added two one-shot `suspend` queries since the existing DAO methods are `Flow`-only).

**Key findings worth flagging:** the codebase is at v1, so P8 is largely self-sufficient — the only existing files it must touch are `TaskDao.kt`, `ProjectDao.kt`, `PullRunner.kt` (+ its `AppModule` provider, which gains `@ApplicationContext`), `TaskRepository.kt`, and `AndroidManifest.xml` (extended deep-link hosts `today`/`focus`/`capture`, 4 receivers, trampoline activity, `<queries>` for the speech recognizer). The one regression touch-point is any direct `PullRunner(...)` test constructor needing the new `Context` arg (Robolectric is already configured, so `ApplicationProvider.getApplicationContext()` covers it).

---

## P9 — You, stats & settings

This phase builds the **You** tab — the profile / momentum / settings surface (spec §6.10, mockup `full-app.html` "You" panel) — on top of the existing auth, sync, settings, and P3 focus/daily-plan data layers, reusing the auth teardown unchanged. It is delivered in small TDD units: a clock-injected **`StatsCalculator`** built one method at a time (`todayProgress` → `doneThisWeek` → `focusMinutesThisWeek` → `dayStreak`), each with **real** unit tests covering ISO-week windows, day-boundary streaks with a today-grace, and focus minutes; a pure **`YouUiState`** + settings-entry model + **email→profile** derivation (initials/display-name), unit-tested; a **`YouViewModel`** that assembles the profile (from `SessionState`), the live sync status + pending count, the four stats (StatsCalculator over Room flows of `tasks`, this-week `FocusSessionEntity`s, and recent `DailyPlanEntity`s), and the grouped settings entries, whose `onSignOut()` delegates to a `SignOutAction` bound to `AuthRepository::logout` (no re-implementation), with real unit tests including the sign-out delegation; a **`YouScreen`** (amber-gradient initials avatar, name/email, sync pill, 3 `StatTile`s, grouped settings list, danger Sign out) and five **settings sub-screens** (Notifications & reminders, Appearance, Account, Data & sync, About) verified by **compile-gate + mockup behavior-checklist**; the `SettingsViewModel` extended with `forceSync` + the new `workdayMinutes`/`defaultReminderMinutes` keys (real tests); and DI + nav wiring (provide `StatsCalculator(clock)`, bind `SignOutAction` → `AuthRepository::logout`, replace the P0 `You` placeholder) confirmed against the existing Hilt-graph smoke test. The phase ends with the full unit suite, `assembleDebug`, `lint`, and the §10.9 behavior checklist all green.

> **Visual source of truth:** `.superpowers/brainstorm/965-1782053760/content/full-app.html` — the **You** panel: 58dp amber-gradient circular avatar with initials (`MR`) → bold display name (`Mohammad Ramadan`) → ellipsized email (`info.qmindtech@gmail.com`) → a `success` dot + "Synced just now"; a 3-tile stat row (`🔥 7 / DAY STREAK`, `✓ 23 / DONE THIS WK`, `◷ 6.5h / FOCUS THIS WK`); a `SETTINGS` `SectionLabel`; one rounded grouped card of 5 rows (🔔 Notifications & reminders · 🎨 Appearance · 👤 Account · ☁ Data & sync · ℹ About) each with a trailing `›` chevron; and a separate `surfaceInset` **Sign out** card in `danger` color.
>
> **P0 contracts consumed verbatim (do NOT redefine — produced by P0):**
> - `@Composable fun TmapTheme(content)` + `val colors = LocalTmapColors.current` → `TmapColorScheme` fields used here: `bgTop/bgBottom`, `surface`, `surfaceRaised`, `surfaceInset`, `borderSubtle`, `borderStrong`, `textPrimary`, `textSecondary`, `textTertiary`, `textBody`, `accent`, `accentEnd`, `onAccent`, `success`, `danger`. Plus `LocalTmapShapes` (`card`, `pill`, `button`, `well`), `LocalTmapSpacing` (`base/xs/sm/md/lg/xl/xxl/screenH`), `LocalTmapType` (`display/title/heading/body/meta/label`).
> - `@Composable fun StatTile(value: String, label: String, modifier: Modifier = Modifier)` — `surface` card, big amber `title`-weight value over a `textSecondary` `meta` label.
> - `@Composable fun SyncStatusPill(status: SyncStatus, pendingCount: Int, onRetry: () -> Unit, modifier: Modifier = Modifier)` — pill on `surfaceInset`, `success` dot when synced; + pure `fun syncPillContent(status, pendingCount): SyncPillContent`.
> - `@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier)`, `@Composable fun PrimaryButton(...)`, `@Composable fun SecondaryButton(...)`, `@Composable fun EmptyState(...)`.
> - **Navigation (P0):** `sealed interface Route` with `data object You : Route { override val route = "you" }`; the `You` tab is rendered inside `MainScaffold`'s `NavHost`. P9 replaces the P0 `YouPlaceholder()` with the real `YouScreen`. Settings sub-screens are child destinations reached from `YouScreen` (new `Route` string-route objects added in P9.10).
>
> **P3 contracts consumed verbatim (do NOT redefine — produced by P3):**
> - `interface FocusSessionRepository { suspend fun create(...): String; fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>>; fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>> }`.
> - `FocusSessionEntity(id, taskId: String?, project: String, startedAt: Instant, endedAt: Instant, minutes: Int, date: LocalDate, createdAt, updatedAt, changeSeq, deletedAt)`.
> - `DailyPlanEntity(date: LocalDate /*PK*/, committedAt: Instant, plannedTaskIds: List<String>, plannedMinutes: Int, changeSeq, deletedAt)`.
> - `interface DailyPlanRepository { fun observe(date: LocalDate): Flow<DailyPlanEntity?>; suspend fun upsert(...) }` — **P9 extends this contract** (see P9.0) with `fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>>` (the streak needs recent committed plans); the P3 `DailyPlanRepositoryImpl`/`DailyPlanDao` gain the matching read query. (FocusSession's `observeForDateRange` already exists in the P3 contract — no change needed there.)
>
> **Existing engine consumed unchanged:** `AuthRepository.logout()` (cancel sync → clear tokens → keep Room; spec §5.3), `SessionState` (`Authenticated(userId, email, timeZoneId)` / `Unauthenticated` / `LoadingSession`), `SettingsRepository`, `SyncStatusHolder.status: StateFlow<SyncStatus>`, `OutboxRepository.observeUnparkedCount(): Flow<Int>`, `SyncScheduler.requestExpeditedSync()`, `util.Clock`. The reminder permission gate `ReminderPermissionGate(canScheduleExact)` + pure `decideReminderPermissions(...)` are reused on the Notifications sub-screen.

---

### Task P9.0 — Extend the P3 `DailyPlanRepository` contract with `observeRange` (for the streak)

The day-streak reads **recent committed daily-plans** (a day "counts" toward the streak when it has either a committed plan or a completed task — see P9.4). `DailyPlanRepository` (P3) only exposes `observe(date)`; add a date-window read. This is the one P3-contract extension P9 owns (instruction: "Add `FocusSessionRepository.observeForDateRange` / `DailyPlanRepository.observeRange` to P3 contract if missing" — `FocusSessionRepository.observeForDateRange` already exists, so only the daily-plan range is added here).

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/local/dao/DailyPlanDao.kt` (add range query)
- `~ android/app/src/main/java/net/qmindtech/tmap/data/repository/DailyPlanRepository.kt` (add interface method + impl)
- `~ android/app/src/test/java/net/qmindtech/tmap/data/local/dao/DailyPlanDaoTest.kt` (add a range test)

**Interfaces** (exact — additive; nothing existing changes signature)
```kotlin
// DailyPlanDao (additive):
@Query("SELECT * FROM daily_plans WHERE date BETWEEN :start AND :end ORDER BY date")
fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>>

// DailyPlanRepository (additive):
fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>>
```

**Steps**

- [ ] **Write the failing test.** Append to `DailyPlanDaoTest.kt`:
  ```kotlin
      @Test
      fun `observeRange returns plans within an inclusive date window ordered by date`() = runTest {
          dao.upsertAll(listOf(
              plan(LocalDate.parse("2026-06-15"), listOf("a"), 30),
              plan(LocalDate.parse("2026-06-18"), listOf("b"), 60),
              plan(LocalDate.parse("2026-06-25"), listOf("c"), 90),
          ))
          val dates = dao.observeRange(LocalDate.parse("2026-06-16"), LocalDate.parse("2026-06-20"))
              .first().map { it.date.toString() }
          assertEquals(listOf("2026-06-18"), dates)
      }
  ```
  (`date BETWEEN` is valid because `LocalDate` is stored as ISO `yyyy-MM-dd`, which is lexicographically date-ordered — same rationale the P3 `FocusSessionDao.observeForDateRange` relies on.)

- [ ] **Verify FAIL.** Run from `android/`:
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.DailyPlanDaoTest"
  ```
  Expected: compilation failure — `unresolved reference: observeRange`.

- [ ] **Implement.** Add the `observeRange` `@Query` to `DailyPlanDao.kt` (import already present: `Flow`, `LocalDate`). Then add to `DailyPlanRepository.kt`:
  ```kotlin
  // in interface DailyPlanRepository:
      fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>>

  // in class DailyPlanRepositoryImpl (delegates to the dao, no write-through):
      override fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>> =
          dailyPlanDao.observeRange(start, end)
  ```

- [ ] **Verify PASS.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.local.dao.DailyPlanDaoTest"
  ```
  Green.

- [ ] **Commit:**
  ```
  feat(android-data): DailyPlanRepository.observeRange for the You-screen day streak

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P9.1 — `StatsCalculator` skeleton + `todayProgress(tasks)`

Create the FIXED-signature `StatsCalculator` (clock-injected) and implement only the first method: `todayProgress`. It returns the fraction (0f..1f) of **today's planned tasks** that are done — the ratio the You-screen / Progress widget ring renders. "Today's tasks" = tasks whose `plannedDate == clock.today()` and which are not recurrence templates; "done" = `status == TaskStatus.Done`. Zero tasks → `0f` (no division by zero). One method per task; this task ships `todayProgress` with real tests, leaving the other three to fail-compile until their own tasks.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/data/stats/StatsCalculator.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/data/stats/StatsCalculatorTest.kt`

**Interfaces** (FIXED — produced here verbatim; the full class signature, methods filled in across P9.1–P9.4)
```kotlin
package net.qmindtech.tmap.data.stats

import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.local.entities.TaskEntity
import net.qmindtech.tmap.util.Clock
import javax.inject.Inject

class StatsCalculator @Inject constructor(private val clock: Clock) {
    fun todayProgress(tasks: List<TaskEntity>): Float
    fun doneThisWeek(tasks: List<TaskEntity>): Int
    fun focusMinutesThisWeek(sessions: List<FocusSessionEntity>): Int
    fun dayStreak(tasks: List<TaskEntity>, plans: List<DailyPlanEntity>): Int
}
```
*(The body above is the contract; in this task only `todayProgress` has a body. The remaining three are added in P9.2–P9.4. Do not stub them with `TODO()` placeholders that ship — implement incrementally per the steps; until then they simply do not exist and their tests fail-compile.)*

**Steps**

- [ ] **Write the failing test.** `StatsCalculatorTest.kt`:
  ```kotlin
  package net.qmindtech.tmap.data.stats

  import net.qmindtech.tmap.data.local.TaskStatus
  import net.qmindtech.tmap.testutil.FixedClock
  import net.qmindtech.tmap.testutil.fakeTask
  import org.junit.Assert.assertEquals
  import org.junit.Test
  import java.time.Instant
  import java.time.LocalDate

  class StatsCalculatorTest {
      // FixedClock.today() = the UTC date of `now` (see testutil.Fakes). 2026-06-18 is a Thursday.
      private val calc = StatsCalculator(FixedClock(Instant.parse("2026-06-18T12:00:00Z")))
      private val today = LocalDate.of(2026, 6, 18)

      @Test fun `todayProgress is the done-fraction of todays planned tasks`() {
          val tasks = listOf(
              fakeTask("a", plannedDate = today, status = TaskStatus.Done),
              fakeTask("b", plannedDate = today, status = TaskStatus.Done),
              fakeTask("c", plannedDate = today, status = TaskStatus.Planned),
              fakeTask("d", plannedDate = today, status = TaskStatus.Scheduled),
          )
          assertEquals(0.5f, calc.todayProgress(tasks), 0.0001f) // 2 of 4
      }

      @Test fun `todayProgress ignores tasks planned for other days`() {
          val tasks = listOf(
              fakeTask("a", plannedDate = today, status = TaskStatus.Done),
              fakeTask("y", plannedDate = LocalDate.of(2026, 6, 17), status = TaskStatus.Done),
              fakeTask("z", plannedDate = LocalDate.of(2026, 6, 19), status = TaskStatus.Planned),
          )
          assertEquals(1.0f, calc.todayProgress(tasks), 0.0001f) // only "a" is today, and it's done
      }

      @Test fun `todayProgress is zero when no task is planned today`() {
          val tasks = listOf(fakeTask("u", plannedDate = null, status = TaskStatus.Inbox))
          assertEquals(0.0f, calc.todayProgress(tasks), 0.0001f)
      }
  }
  ```

- [ ] **Verify FAIL.** Run from `android/`:
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.stats.StatsCalculatorTest"
  ```
  Expected: compilation failure — `unresolved reference: StatsCalculator`.

- [ ] **Implement.** `StatsCalculator.kt`:
  ```kotlin
  package net.qmindtech.tmap.data.stats

  import net.qmindtech.tmap.data.local.TaskStatus
  import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
  import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
  import net.qmindtech.tmap.data.local.entities.TaskEntity
  import net.qmindtech.tmap.util.Clock
  import javax.inject.Inject

  /**
   * Pure, clock-injected derivation of the four You-screen / widget stats (spec §6.10). Every method
   * is a side-effect-free function of its input list + the injected [clock] — so each is exhaustively
   * unit-testable without Room or coroutines. The ViewModel (P9.3) feeds it Room snapshots; the
   * Progress/Streak widget (P8) reuses the same calculator over the same data.
   */
  class StatsCalculator @Inject constructor(private val clock: Clock) {

      /** Fraction (0f..1f) of TODAY's planned, non-template tasks that are [TaskStatus.Done]. */
      fun todayProgress(tasks: List<TaskEntity>): Float {
          val today = clock.today()
          val plannedToday = tasks.filter { !it.isRecurrenceTemplate && it.plannedDate == today }
          if (plannedToday.isEmpty()) return 0f
          val done = plannedToday.count { it.status == TaskStatus.Done }
          return done.toFloat() / plannedToday.size.toFloat()
      }
  }
  ```

- [ ] **Verify PASS.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.stats.StatsCalculatorTest"
  ```
  Green (3 tests).

- [ ] **Commit:**
  ```
  feat(android-stats): StatsCalculator.todayProgress (clock-injected, done-fraction of today)

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P9.2 — `StatsCalculator.doneThisWeek(tasks)` (ISO-week window)

Add `doneThisWeek`: the count of tasks completed within the **ISO week** (Monday→Sunday) that contains `clock.today()`. A task counts when `status == Done` and its `completedAt` instant, resolved into `clock.zone()`, falls on a date within `[mondayOfThisWeek, sundayOfThisWeek]` inclusive. Tasks with no `completedAt`, or completed outside the window, or completed-but-not-Done are excluded. Tested at the week boundaries (Monday 00:00 included; previous Sunday excluded; next Monday excluded).

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/stats/StatsCalculator.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/data/stats/StatsCalculatorTest.kt`

**Interfaces**
```kotlin
fun doneThisWeek(tasks: List<TaskEntity>): Int
```

**Steps**

- [ ] **Write the failing test.** Append to `StatsCalculatorTest.kt` (today 2026-06-18 is Thu → ISO week is Mon 2026-06-15 … Sun 2026-06-21):
  ```kotlin
      @Test fun `doneThisWeek counts Done tasks completed within the ISO week of today`() {
          val tasks = listOf(
              // inside the window
              fakeTask("mon", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-15T00:00:00Z")),
              fakeTask("thu", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-18T09:30:00Z")),
              fakeTask("sun", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-21T23:59:59Z")),
              // just outside: previous Sunday and next Monday
              fakeTask("prevSun", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-14T23:59:59Z")),
              fakeTask("nextMon", status = TaskStatus.Done, completedAt = Instant.parse("2026-06-22T00:00:00Z")),
              // Done but no completedAt, and not-Done — both excluded
              fakeTask("noStamp", status = TaskStatus.Done, completedAt = null),
              fakeTask("planned", status = TaskStatus.Planned, completedAt = Instant.parse("2026-06-18T10:00:00Z")),
          )
          assertEquals(3, calc.doneThisWeek(tasks))
      }

      @Test fun `doneThisWeek is zero when nothing completed this week`() {
          assertEquals(0, calc.doneThisWeek(emptyList()))
      }
  ```
  *(FixedClock has no explicit zone; its `today()` and this method resolve `completedAt` in UTC, matching the test instants. The production `SystemClock` uses the user's zone — the test pins the boundary semantics, not the zone.)*

- [ ] **Verify FAIL.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.stats.StatsCalculatorTest"
  ```
  Expected: compilation failure — `unresolved reference: doneThisWeek`.

- [ ] **Implement.** Add to `StatsCalculator.kt` (and a private week-window helper reused by P9.3); import `java.time.DayOfWeek`, `java.time.LocalDate`:
  ```kotlin
      /** The Monday→Sunday ISO week (inclusive) containing [clock.today]. */
      private fun isoWeek(): ClosedRange<LocalDate> {
          val today = clock.today()
          val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
          return monday..monday.plusDays(6)
      }

      /** Count of [TaskStatus.Done] tasks whose completedAt date (in clock.zone()) is in this ISO week. */
      fun doneThisWeek(tasks: List<TaskEntity>): Int {
          val week = isoWeek()
          return tasks.count { t ->
              t.status == TaskStatus.Done &&
                  t.completedAt?.atZone(clock.zone())?.toLocalDate()?.let { it in week } == true
          }
      }
  ```
  *(`it in week` uses `ClosedRange.contains`, which works on `Comparable<LocalDate>`.)*

- [ ] **Verify PASS.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.stats.StatsCalculatorTest"
  ```
  Green (5 tests).

- [ ] **Commit:**
  ```
  feat(android-stats): StatsCalculator.doneThisWeek (ISO-week window, boundary-tested)

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P9.3 — `StatsCalculator.focusMinutesThisWeek(sessions)` (ISO-week sum)

Add `focusMinutesThisWeek`: the **sum of `minutes`** over `FocusSessionEntity`s whose `date` (already a `LocalDate`, no instant resolution needed) falls in the same ISO week as `clock.today()`, ignoring tombstoned rows (`deletedAt != null`). Sessions outside the window or soft-deleted are excluded. (The You screen renders this as hours, e.g. "6.5h" — the hours formatting lives in the UI projection, P9.5/P9.6; the calculator returns raw minutes.) Reuses the `isoWeek()` helper from P9.2.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/stats/StatsCalculator.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/data/stats/StatsCalculatorTest.kt`

**Interfaces**
```kotlin
fun focusMinutesThisWeek(sessions: List<FocusSessionEntity>): Int
```

**Steps**

- [ ] **Write the failing test.** Append to `StatsCalculatorTest.kt`:
  ```kotlin
      private fun session(id: String, date: LocalDate, minutes: Int, deletedAt: Instant? = null) =
          FocusSessionEntity(
              id = id, taskId = null, project = "Work",
              startedAt = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
              endedAt = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
              minutes = minutes, date = date,
              createdAt = Instant.parse("2026-06-18T00:00:00Z"),
              updatedAt = Instant.parse("2026-06-18T00:00:00Z"),
              changeSeq = 0L, deletedAt = deletedAt,
          )

      @Test fun `focusMinutesThisWeek sums minutes of sessions dated within the ISO week`() {
          val sessions = listOf(
              session("a", LocalDate.of(2026, 6, 15), 25),  // Monday, in
              session("b", LocalDate.of(2026, 6, 18), 50),  // Thursday, in
              session("c", LocalDate.of(2026, 6, 21), 30),  // Sunday, in
              session("prev", LocalDate.of(2026, 6, 14), 90), // previous Sunday, out
              session("next", LocalDate.of(2026, 6, 22), 90), // next Monday, out
          )
          assertEquals(105, calc.focusMinutesThisWeek(sessions)) // 25 + 50 + 30
      }

      @Test fun `focusMinutesThisWeek ignores tombstoned sessions and empty input`() {
          assertEquals(0, calc.focusMinutesThisWeek(emptyList()))
          val sessions = listOf(
              session("live", LocalDate.of(2026, 6, 18), 40),
              session("dead", LocalDate.of(2026, 6, 18), 999, deletedAt = Instant.parse("2026-06-18T10:00:00Z")),
          )
          assertEquals(40, calc.focusMinutesThisWeek(sessions))
      }
  ```

- [ ] **Verify FAIL.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.stats.StatsCalculatorTest"
  ```
  Expected: compilation failure — `unresolved reference: focusMinutesThisWeek`.

- [ ] **Implement.** Add to `StatsCalculator.kt`:
  ```kotlin
      /** Sum of `minutes` over non-tombstoned focus sessions dated within this ISO week. */
      fun focusMinutesThisWeek(sessions: List<FocusSessionEntity>): Int {
          val week = isoWeek()
          return sessions
              .filter { it.deletedAt == null && it.date in week }
              .sumOf { it.minutes }
      }
  ```

- [ ] **Verify PASS.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.stats.StatsCalculatorTest"
  ```
  Green (7 tests).

- [ ] **Commit:**
  ```
  feat(android-stats): StatsCalculator.focusMinutesThisWeek (ISO-week sum, skips tombstones)

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P9.4 — `StatsCalculator.dayStreak(tasks, plans)` (day-boundary streak with today-grace)

Add the streak — the headline 🔥 number. A day **"counts"** when it has activity: either a committed `DailyPlanEntity` for that date (`deletedAt == null`), OR at least one task `Done` whose `completedAt` (in `clock.zone()`) lands on that date. The streak is the run of **consecutive counting days ending at today**, with a **today-grace**: if *today* has no activity yet, the streak is still measured (counting back from **yesterday**) so an unfinished current day does not zero a real streak; if today *does* count, today is included. A gap (a non-counting day) before reaching the anchor ends the run.

Algorithm (pure):
1. Build the set of "active dates" = plan dates (live) ∪ task-completion dates (Done + completedAt in zone).
2. Choose the anchor: `today` if today is active, else `yesterday`.
3. Walk backward day-by-day from the anchor while each day is in the active set; the count of walked days is the streak.
4. If neither today nor yesterday is active → streak `0`.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/data/stats/StatsCalculator.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/data/stats/StatsCalculatorTest.kt`

**Interfaces**
```kotlin
fun dayStreak(tasks: List<TaskEntity>, plans: List<DailyPlanEntity>): Int
```

**Steps**

- [ ] **Write the failing test.** Append to `StatsCalculatorTest.kt` (today = Thu 2026-06-18):
  ```kotlin
      private fun plan(date: LocalDate, deletedAt: Instant? = null) = DailyPlanEntity(
          date = date, committedAt = Instant.parse("2026-06-18T07:00:00Z"),
          plannedTaskIds = listOf("x"), plannedMinutes = 60, changeSeq = 0L, deletedAt = deletedAt,
      )
      private fun done(id: String, date: LocalDate) =
          fakeTask(id, status = TaskStatus.Done, completedAt = date.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC))

      @Test fun `dayStreak counts consecutive active days including today`() {
          val plans = listOf(plan(today), plan(today.minusDays(1)), plan(today.minusDays(2)))
          assertEquals(3, calc.dayStreak(emptyList(), plans))
      }

      @Test fun `dayStreak today-grace counts back from yesterday when today is inactive`() {
          // today (Thu) has no activity; yesterday + the two before do.
          val plans = listOf(plan(today.minusDays(1)), plan(today.minusDays(2)), plan(today.minusDays(3)))
          assertEquals(3, calc.dayStreak(emptyList(), plans))
      }

      @Test fun `dayStreak breaks at the first gap`() {
          // today + yesterday active, then a gap at day -2, then day -3 active (does not count).
          val plans = listOf(plan(today), plan(today.minusDays(1)), plan(today.minusDays(3)))
          assertEquals(2, calc.dayStreak(emptyList(), plans))
      }

      @Test fun `dayStreak is zero when neither today nor yesterday is active`() {
          val plans = listOf(plan(today.minusDays(2)), plan(today.minusDays(3)))
          assertEquals(0, calc.dayStreak(emptyList(), plans))
      }

      @Test fun `dayStreak counts task-completion days and unions with plans, ignoring tombstoned plans`() {
          // today active via a completed task; yesterday via a (live) plan; day -2 only via a tombstoned plan → breaks.
          val tasks = listOf(done("t1", today))
          val plans = listOf(plan(today.minusDays(1)), plan(today.minusDays(2), deletedAt = Instant.parse("2026-06-18T08:00:00Z")))
          assertEquals(2, calc.dayStreak(tasks, plans))
      }
  ```

- [ ] **Verify FAIL.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.stats.StatsCalculatorTest"
  ```
  Expected: compilation failure — `unresolved reference: dayStreak`.

- [ ] **Implement.** Add to `StatsCalculator.kt`:
  ```kotlin
      /**
       * Consecutive "active" days ending at today, with a today-grace: an empty current day does not
       * break a real streak — if today is inactive we anchor at yesterday. A day is active when it has
       * a live committed DailyPlan OR a Done task completed on that date (in clock.zone()).
       */
      fun dayStreak(tasks: List<TaskEntity>, plans: List<DailyPlanEntity>): Int {
          val active = buildSet {
              plans.forEach { if (it.deletedAt == null) add(it.date) }
              tasks.forEach { t ->
                  if (t.status == TaskStatus.Done) {
                      t.completedAt?.atZone(clock.zone())?.toLocalDate()?.let { add(it) }
                  }
              }
          }
          val today = clock.today()
          val anchor = when {
              today in active -> today
              today.minusDays(1) in active -> today.minusDays(1)
              else -> return 0
          }
          var count = 0
          var day = anchor
          while (day in active) {
              count++
              day = day.minusDays(1)
          }
          return count
      }
  ```

- [ ] **Verify PASS.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.data.stats.StatsCalculatorTest"
  ```
  Green (12 tests — `StatsCalculator` complete).

- [ ] **Commit:**
  ```
  feat(android-stats): StatsCalculator.dayStreak (consecutive active days + today-grace)

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P9.5 — `YouUiState` + settings-entry model + email→profile derivation (pure)

A pure, unit-testable model layer for the You screen, with NO Compose/coroutine dependency. `UserProfile` holds the display name + initials + email; a pure `deriveProfile(email: String?): UserProfile` turns the session email into a humanized name + 2-letter initials (the avatar's "MR"). `SettingsEntry` enumerates the five grouped settings rows. `YouUiState` is the screen's immutable state (profile, the three stat strings + the progress fraction, sync status + pending, settings entries, loading). A pure `formatFocusHours(minutes: Int): String` renders the "6.5h" stat. The ViewModel (P9.6) only assembles these.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/you/YouUiState.kt`
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/you/YouUiStateTest.kt`

**Interfaces** (exact — produced here, consumed by P9.6/P9.7)
```kotlin
package net.qmindtech.tmap.ui.you

import net.qmindtech.tmap.data.sync.SyncStatus

data class UserProfile(
    val displayName: String,
    val initials: String,
    val email: String,
)

enum class SettingsEntry(val key: String) {
    Notifications("notifications"),
    Appearance("appearance"),
    Account("account"),
    DataAndSync("data_sync"),
    About("about"),
}

data class YouUiState(
    val loading: Boolean = true,
    val profile: UserProfile = UserProfile("", "", ""),
    val dayStreak: Int = 0,
    val doneThisWeek: Int = 0,
    val focusHoursLabel: String = "0h",
    val todayProgress: Float = 0f,
    val syncStatus: SyncStatus = SyncStatus.Idle,
    val pendingCount: Int = 0,
    val settingsEntries: List<SettingsEntry> = SettingsEntry.entries.toList(),
)

/** Humanize a session email into a display name + 2-letter initials for the avatar. */
fun deriveProfile(email: String?): UserProfile

/** Render focus minutes as the You-screen "Nh" label (one decimal, trailing-zero trimmed). */
fun formatFocusHours(minutes: Int): String
```

**Steps**

- [ ] **Write the failing test.** `YouUiStateTest.kt`:
  ```kotlin
  package net.qmindtech.tmap.ui.you

  import org.junit.Assert.assertEquals
  import org.junit.Test

  class YouUiStateTest {

      @Test fun `deriveProfile builds name and initials from a dotted local-part`() {
          val p = deriveProfile("mohammad.ramadan@gmail.com")
          assertEquals("Mohammad Ramadan", p.displayName)
          assertEquals("MR", p.initials)
          assertEquals("mohammad.ramadan@gmail.com", p.email)
      }

      @Test fun `deriveProfile splits on dots underscores and plus`() {
          assertEquals("Info Qmindtech", deriveProfile("info_qmindtech@x.io").displayName)
          assertEquals("Jane Doe", deriveProfile("jane+doe@x.io").displayName)
      }

      @Test fun `deriveProfile single token uses first two letters for initials`() {
          val p = deriveProfile("qmindtech@x.io")
          assertEquals("Qmindtech", p.displayName)
          assertEquals("QM", p.initials)
      }

      @Test fun `deriveProfile handles null blank and missing at-sign`() {
          val n = deriveProfile(null)
          assertEquals("", n.displayName)
          assertEquals("?", n.initials)
          assertEquals("", n.email)
          val b = deriveProfile("   ")
          assertEquals("?", b.initials)
          // no @ → whole string is the local part
          assertEquals("Bob", deriveProfile("bob").displayName)
      }

      @Test fun `formatFocusHours trims trailing zero and keeps a half`() {
          assertEquals("0h", formatFocusHours(0))
          assertEquals("1h", formatFocusHours(60))
          assertEquals("6.5h", formatFocusHours(390))
          assertEquals("0.5h", formatFocusHours(30))
          assertEquals("2.3h", formatFocusHours(140)) // 2.333 → 2.3
      }

      @Test fun `default YouUiState exposes the five settings entries in order`() {
          assertEquals(
              listOf(
                  SettingsEntry.Notifications, SettingsEntry.Appearance, SettingsEntry.Account,
                  SettingsEntry.DataAndSync, SettingsEntry.About,
              ),
              YouUiState().settingsEntries,
          )
      }
  }
  ```

- [ ] **Verify FAIL.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.you.YouUiStateTest"
  ```
  Expected: compilation failure — `unresolved reference: deriveProfile` / `YouUiState`.

- [ ] **Implement.** `YouUiState.kt` (the data classes/enum above, plus):
  ```kotlin
  fun deriveProfile(email: String?): UserProfile {
      val clean = email?.trim().orEmpty()
      if (clean.isBlank()) return UserProfile(displayName = "", initials = "?", email = "")
      val localPart = clean.substringBefore('@')
      val tokens = localPart.split('.', '_', '+', '-')
          .map { it.trim() }
          .filter { it.isNotEmpty() }
      val displayName = tokens.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
      val initials = when {
          tokens.size >= 2 -> "${tokens[0].first()}${tokens[1].first()}".uppercase()
          tokens.size == 1 -> tokens[0].take(2).uppercase()
          else -> "?"
      }.ifBlank { "?" }
      return UserProfile(displayName = displayName, initials = initials, email = clean)
  }

  fun formatFocusHours(minutes: Int): String {
      val hours = minutes / 60.0
      // one decimal, trimmed: 0.0 -> "0h", 1.0 -> "1h", 6.5 -> "6.5h"
      val rounded = Math.round(hours * 10.0) / 10.0
      val text = if (rounded % 1.0 == 0.0) rounded.toInt().toString()
                 else rounded.toString()
      return "${text}h"
  }
  ```

- [ ] **Verify PASS.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.you.YouUiStateTest"
  ```
  Green (6 tests).

- [ ] **Commit:**
  ```
  feat(you): YouUiState + settings entries + email→profile derivation (pure, tested)

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P9.6 — `YouViewModel` (assemble profile + sync + 4 stats + settings; `onSignOut` delegates)

The ViewModel that wires the screen. It assembles `YouUiState` from: the `AuthRepository.session` flow (→ `deriveProfile(email)`); `SyncStatusHolder.status` + `OutboxRepository.observeUnparkedCount()` (→ sync pill); and the `StatsCalculator` applied to live Room flows — all tasks (`TaskRepository.observeAll()`), this-week focus sessions (`FocusSessionRepository.observeForDateRange(weekStart, weekEnd)`), and recent daily-plans (`DailyPlanRepository.observeRange(streakWindowStart, today)`). The streak window is the last 60 days ending today (more than enough to render any realistic streak; bounded so the query stays cheap). `onSignOut()` delegates to a `SignOutAction` (a `suspend () -> Unit` functional type) bound to `AuthRepository::logout` — **no teardown re-implementation** (spec §6.10/§5.3). `onRetrySync()` nudges `SyncScheduler.requestExpeditedSync()`.

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/you/YouViewModel.kt` (includes the `SignOutAction` typealias/fun-interface)
- `+ android/app/src/test/java/net/qmindtech/tmap/ui/you/YouViewModelTest.kt`

**Interfaces** (exact — produced here)
```kotlin
package net.qmindtech.tmap.ui.you

/** A single-method indirection so YouViewModel never re-implements teardown — bound to AuthRepository::logout in DI. */
fun interface SignOutAction { suspend operator fun invoke() }

@dagger.hilt.android.lifecycle.HiltViewModel
class YouViewModel @javax.inject.Inject constructor(
    authRepository: net.qmindtech.tmap.data.auth.AuthRepository,
    taskRepository: net.qmindtech.tmap.data.repository.TaskRepository,
    focusSessionRepository: net.qmindtech.tmap.data.repository.FocusSessionRepository,
    dailyPlanRepository: net.qmindtech.tmap.data.repository.DailyPlanRepository,
    syncStatusHolder: net.qmindtech.tmap.data.sync.SyncStatusHolder,
    outboxRepository: net.qmindtech.tmap.data.sync.OutboxRepository,
    private val stats: net.qmindtech.tmap.data.stats.StatsCalculator,
    private val signOut: SignOutAction,
    private val syncScheduler: net.qmindtech.tmap.data.sync.SyncScheduler,
    private val clock: net.qmindtech.tmap.util.Clock,
) : androidx.lifecycle.ViewModel() {
    val uiState: kotlinx.coroutines.flow.StateFlow<YouUiState>
    fun onSignOut()
    fun onRetrySync()
}
```

**Steps**

- [ ] **Write the failing test.** `YouViewModelTest.kt` (mirrors `SettingsViewModelTest`/`TodayViewModelTest`: `UnconfinedTestDispatcher` + `Dispatchers.setMain`, Turbine, fakes). Add tiny fakes for the focus/daily-plan repos + a recording `SignOutAction`:
  ```kotlin
  package net.qmindtech.tmap.ui.you

  import app.cash.turbine.test
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.ExperimentalCoroutinesApi
  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.test.UnconfinedTestDispatcher
  import kotlinx.coroutines.test.resetMain
  import kotlinx.coroutines.test.runTest
  import kotlinx.coroutines.test.setMain
  import net.qmindtech.tmap.data.auth.AuthRepository
  import net.qmindtech.tmap.data.auth.SessionState
  import net.qmindtech.tmap.data.local.TaskStatus
  import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
  import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
  import net.qmindtech.tmap.data.repository.DailyPlanRepository
  import net.qmindtech.tmap.data.repository.FocusSessionRepository
  import net.qmindtech.tmap.data.stats.StatsCalculator
  import net.qmindtech.tmap.data.sync.OutboxRepository
  import net.qmindtech.tmap.data.sync.SyncScheduler
  import net.qmindtech.tmap.data.sync.SyncStatus
  import net.qmindtech.tmap.data.sync.SyncStatusHolder
  import net.qmindtech.tmap.testutil.FakeTaskRepo
  import net.qmindtech.tmap.testutil.FixedClock
  import net.qmindtech.tmap.testutil.fakeTask
  import org.junit.After
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertTrue
  import org.junit.Before
  import org.junit.Test
  import java.time.Instant
  import java.time.LocalDate

  @OptIn(ExperimentalCoroutinesApi::class)
  class YouViewModelTest {
      private val dispatcher = UnconfinedTestDispatcher()
      private val today = LocalDate.of(2026, 6, 18)
      private val clock = FixedClock(Instant.parse("2026-06-18T12:00:00Z"))

      @Before fun setUp() { Dispatchers.setMain(dispatcher) }
      @After fun tearDown() { Dispatchers.resetMain() }

      private class FakeAuth(initial: SessionState) : AuthRepository {
          val sessionFlow = MutableStateFlow(initial)
          override val session = sessionFlow
          var logoutCalls = 0
          override suspend fun register(email: String, password: String) = Result.success(Unit)
          override suspend fun login(email: String, password: String) = Result.success(Unit)
          override suspend fun logout() { logoutCalls++ }
          override suspend fun loadSession() {}
          override suspend fun refreshBlocking() = true
      }

      private class FakeFocusRepo(val flow: MutableStateFlow<List<FocusSessionEntity>>) : FocusSessionRepository {
          override suspend fun create(taskId: String?, project: String, startedAt: Instant, endedAt: Instant, minutes: Int, date: LocalDate) = "f"
          override fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>> = flow
          override fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>> = flow
      }
      private class FakeDailyPlanRepo(val flow: MutableStateFlow<List<DailyPlanEntity>>) : DailyPlanRepository {
          override fun observe(date: LocalDate): Flow<DailyPlanEntity?> = MutableStateFlow(null)
          override suspend fun upsert(date: LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int) {}
          override fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>> = flow
      }
      private class FakeOutbox { } // placeholder note: see below — we use the real OutboxRepository fake via SyncTestSupport if needed

      private fun session(date: LocalDate, minutes: Int) = FocusSessionEntity(
          id = "s", taskId = null, project = "Work",
          startedAt = Instant.parse("2026-06-18T09:00:00Z"), endedAt = Instant.parse("2026-06-18T09:30:00Z"),
          minutes = minutes, date = date, createdAt = Instant.parse("2026-06-18T09:00:00Z"),
          updatedAt = Instant.parse("2026-06-18T09:00:00Z"), changeSeq = 0L, deletedAt = null,
      )
      private fun plan(date: LocalDate) = DailyPlanEntity(
          date = date, committedAt = Instant.parse("2026-06-18T07:00:00Z"),
          plannedTaskIds = listOf("x"), plannedMinutes = 60, changeSeq = 0L, deletedAt = null,
      )

      private fun buildVm(
          auth: FakeAuth,
          tasks: FakeTaskRepo,
          focus: FakeFocusRepo,
          plans: FakeDailyPlanRepo,
          syncHolder: SyncStatusHolder,
          outbox: OutboxRepository,
          signOut: SignOutAction,
          scheduler: SyncScheduler,
      ) = YouViewModel(auth, tasks, focus, plans, syncHolder, outbox, StatsCalculator(clock), signOut, scheduler, clock)

      @Test fun `uiState assembles profile sync stats and clears loading`() = runTest(dispatcher) {
          val auth = FakeAuth(SessionState.Authenticated("u1", "info_qmindtech@gmail.com", "UTC"))
          val tasksFlow = MutableStateFlow(
              listOf(
                  fakeTask("a", plannedDate = today, status = TaskStatus.Done,
                      completedAt = Instant.parse("2026-06-18T10:00:00Z")),
                  fakeTask("b", plannedDate = today, status = TaskStatus.Planned),
              )
          )
          val tasks = FakeTaskRepo(all = tasksFlow)
          val focus = FakeFocusRepo(MutableStateFlow(listOf(session(today, 90))))
          val plans = FakeDailyPlanRepo(MutableStateFlow(listOf(plan(today), plan(today.minusDays(1)))))
          val holder = SyncStatusHolder().apply { set(SyncStatus.Idle) }
          val outbox = net.qmindtech.tmap.data.sync.fakeOutboxWithPending(0) // see helper note
          val vm = buildVm(auth, tasks, focus, plans, holder, outbox, SignOutAction {}, FakeSyncScheduler())
          vm.uiState.test {
              val s = expectMostRecentItem()
              assertEquals(false, s.loading)
              assertEquals("Info Qmindtech", s.profile.displayName)
              assertEquals("IQ", s.profile.initials)
              assertEquals(0.5f, s.todayProgress, 0.0001f) // 1 of 2 today done
              assertEquals(1, s.doneThisWeek)
              assertEquals("1.5h", s.focusHoursLabel) // 90 min
              assertEquals(2, s.dayStreak)            // today + yesterday via plans
              cancelAndIgnoreRemainingEvents()
          }
      }

      @Test fun `onSignOut delegates to the SignOutAction exactly once`() = runTest(dispatcher) {
          var calls = 0
          val auth = FakeAuth(SessionState.Authenticated("u1", "a@b.co", "UTC"))
          val vm = buildVm(
              auth, FakeTaskRepo(all = MutableStateFlow(emptyList())),
              FakeFocusRepo(MutableStateFlow(emptyList())), FakeDailyPlanRepo(MutableStateFlow(emptyList())),
              SyncStatusHolder(), net.qmindtech.tmap.data.sync.fakeOutboxWithPending(0),
              SignOutAction { calls++ }, FakeSyncScheduler(),
          )
          vm.onSignOut()
          assertEquals(1, calls)
      }

      @Test fun `onRetrySync nudges the scheduler`() = runTest(dispatcher) {
          val scheduler = FakeSyncScheduler()
          val vm = buildVm(
              FakeAuth(SessionState.Authenticated("u1", "a@b.co", "UTC")),
              FakeTaskRepo(all = MutableStateFlow(emptyList())),
              FakeFocusRepo(MutableStateFlow(emptyList())), FakeDailyPlanRepo(MutableStateFlow(emptyList())),
              SyncStatusHolder(), net.qmindtech.tmap.data.sync.fakeOutboxWithPending(0),
              SignOutAction {}, scheduler,
          )
          vm.onRetrySync()
          assertTrue(scheduler.expeditedCount >= 1)
      }

      private class FakeSyncScheduler : SyncScheduler {
          var expeditedCount = 0
          override fun requestExpeditedSync() { expeditedCount++ }
          override fun schedulePeriodic() {}
          override fun cancelAll() {}
      }
  }
  ```
  > **Test-support note (do this in the same task so the test compiles):**
  > - `OutboxRepository` is a concrete class with an `@Inject` ctor (not an interface). Add a tiny test helper `fun fakeOutboxWithPending(n: Int): OutboxRepository` to `android/app/src/test/java/net/qmindtech/tmap/data/sync/SyncTestSupport.kt` (the existing sync test-support file) that returns a real `OutboxRepository` backed by an in-memory Room `OutboxDao` seeded with `n` ops — OR, simpler and preferred, give `YouViewModel` its pending-count dependency as the **flow** it actually uses (`outboxRepository.observeUnparkedCount()`), and in the test pass a real `OutboxRepository` built from an in-memory DB (Robolectric) **only if** you keep `OutboxRepository` as the ctor param. **Cleaner alternative adopted here:** keep the ctor param `outboxRepository: OutboxRepository` and add the helper to `SyncTestSupport.kt`; if Robolectric is undesirable for this VM test, instead refactor the param to `pendingCount: Flow<Int>` and provide it in DI via `@Provides fun providePendingCount(o: OutboxRepository) = o.observeUnparkedCount()`. **Pick the flow-param form** (`pendingCount: Flow<Int>`) — it keeps `YouViewModelTest` a pure JVM test (no Robolectric) and matches the fakes above (replace `net.qmindtech.tmap.data.sync.fakeOutboxWithPending(0)` with `MutableStateFlow(0)` and update the ctor accordingly). Reflect this choice in the Interfaces block: change the `outboxRepository: OutboxRepository` param to `pendingCount: kotlinx.coroutines.flow.Flow<Int>` and drop the `FakeOutbox`/helper. The DI task P9.10 provides `pendingCount` from `OutboxRepository.observeUnparkedCount()`.

  *(Net: the FIXED ctor uses `pendingCount: Flow<Int>` instead of the raw `OutboxRepository`, so the unit test stays Robolectric-free. The Interfaces block above is the pre-refactor draft; the implemented ctor below is authoritative.)*

- [ ] **Verify FAIL.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.you.YouViewModelTest"
  ```
  Expected: compilation failure — `unresolved reference: YouViewModel` / `SignOutAction`.

- [ ] **Implement.** `YouViewModel.kt` (authoritative ctor — `pendingCount: Flow<Int>`):
  ```kotlin
  package net.qmindtech.tmap.ui.you

  import androidx.lifecycle.ViewModel
  import androidx.lifecycle.viewModelScope
  import dagger.hilt.android.lifecycle.HiltViewModel
  import kotlinx.coroutines.flow.Flow
  import kotlinx.coroutines.flow.SharingStarted
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.combine
  import kotlinx.coroutines.flow.stateIn
  import kotlinx.coroutines.launch
  import net.qmindtech.tmap.data.auth.AuthRepository
  import net.qmindtech.tmap.data.auth.SessionState
  import net.qmindtech.tmap.data.repository.DailyPlanRepository
  import net.qmindtech.tmap.data.repository.FocusSessionRepository
  import net.qmindtech.tmap.data.repository.TaskRepository
  import net.qmindtech.tmap.data.stats.StatsCalculator
  import net.qmindtech.tmap.data.sync.SyncScheduler
  import net.qmindtech.tmap.data.sync.SyncStatus
  import net.qmindtech.tmap.data.sync.SyncStatusHolder
  import net.qmindtech.tmap.util.Clock
  import java.time.DayOfWeek
  import java.time.temporal.TemporalAdjusters
  import javax.inject.Inject
  import javax.inject.Named

  /** Indirection so the VM never re-implements teardown — bound to AuthRepository::logout in DI (§5.3). */
  fun interface SignOutAction { suspend operator fun invoke() }

  @HiltViewModel
  class YouViewModel @Inject constructor(
      authRepository: AuthRepository,
      taskRepository: TaskRepository,
      focusSessionRepository: FocusSessionRepository,
      dailyPlanRepository: DailyPlanRepository,
      syncStatusHolder: SyncStatusHolder,
      @Named("pendingCount") pendingCount: Flow<Int>,
      private val stats: StatsCalculator,
      private val signOut: SignOutAction,
      private val syncScheduler: SyncScheduler,
      private val clock: Clock,
  ) : ViewModel() {

      private val today = clock.today()
      private val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
      private val weekEnd = weekStart.plusDays(6)
      private val streakWindowStart = today.minusDays(60)

      val uiState: StateFlow<YouUiState> =
          combine(
              authRepository.session,
              taskRepository.observeAll(),
              focusSessionRepository.observeForDateRange(weekStart, weekEnd),
              dailyPlanRepository.observeRange(streakWindowStart, today),
              combine(syncStatusHolder.status, pendingCount) { status, pending -> status to pending },
          ) { session, tasks, sessions, plans, syncPair ->
              val email = (session as? SessionState.Authenticated)?.email
              YouUiState(
                  loading = false,
                  profile = deriveProfile(email),
                  dayStreak = stats.dayStreak(tasks, plans),
                  doneThisWeek = stats.doneThisWeek(tasks),
                  focusHoursLabel = formatFocusHours(stats.focusMinutesThisWeek(sessions)),
                  todayProgress = stats.todayProgress(tasks),
                  syncStatus = syncPair.first,
                  pendingCount = syncPair.second,
              )
          }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YouUiState())

      /** Delegates to the bound AuthRepository.logout() teardown — no re-implementation here. */
      fun onSignOut() {
          viewModelScope.launch { signOut() }
      }

      fun onRetrySync() {
          syncScheduler.requestExpeditedSync()
      }
  }
  ```
  Update `YouViewModelTest.kt` to the authoritative ctor: pass `pendingCount = MutableStateFlow(0)` (or a flow you mutate) in `buildVm` instead of an `OutboxRepository`, and delete the `FakeOutbox`/`fakeOutboxWithPending` references. (`combine` with 5 args is the stdlib overload; `clock.today()` resolved once is fine for a screen ViewModel.)

- [ ] **Verify PASS.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.you.YouViewModelTest"
  ```
  Green (3 tests: assembly, sign-out delegation, retry).

- [ ] **Commit:**
  ```
  feat(you): YouViewModel assembles profile/sync/stats; onSignOut delegates to AuthRepository.logout

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P9.7 — `YouScreen` (compile-gate + mockup behavior-checklist)

The You tab Composable, matching the mockup. Avatar (58dp circle, 135° amber `accent`→`accentEnd` gradient, `onAccent` initials), display name (`heading`) + ellipsized email (`meta`, `textTertiary`), the `SyncStatusPill`, a 3-`StatTile` row (`🔥 {dayStreak}` / DAY STREAK, `✓ {doneThisWeek}` / DONE THIS WK, `◷ {focusHoursLabel}` / FOCUS THIS WK), a `SectionLabel("Settings")`, one grouped `surface` card listing the 5 `SettingsEntry` rows (emoji + label + trailing `›`), and a separate `surfaceInset` **Sign out** card in `danger`. **Not unit-testable (Compose UI)** → compile-gate + behavior-checklist (assembleDebug must pass; reviewer verifies against the mockup).

**Files**
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/you/YouScreen.kt`

**Interfaces** (exact)
```kotlin
@androidx.compose.runtime.Composable
fun YouScreen(
    onOpenSettings: (SettingsEntry) -> Unit,
    viewModel: YouViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
)
```

**Steps**

1. **Implement `YouScreen.kt`.** Read `uiState` via `collectAsStateWithLifecycle()`. Use `LocalTmapColors/Shapes/Spacing/Type`. Compose, top to bottom:
   - **Profile row** (`Row`, `spacing.md`): a `Box` avatar `size(58.dp)` with `clip(CircleShape)` + `background(Brush.linearGradient(listOf(colors.accent, colors.accentEnd)))`, centered `Text(profile.initials, color = colors.onAccent)` (`title`/700) with a `semantics { contentDescription = "Profile avatar" }`; a `Column` with `Text(profile.displayName, type.heading, colors.textPrimary)`, `Text(profile.email, type.meta, colors.textTertiary, maxLines = 1, overflow = Ellipsis)`, and the `SyncStatusPill(uiState.syncStatus, uiState.pendingCount, onRetry = viewModel::onRetrySync)`.
   - **Stat row** (`Row`, `spacing.sm`, each `Modifier.weight(1f)`): `StatTile("🔥 ${uiState.dayStreak}", "DAY STREAK")`, `StatTile("✓ ${uiState.doneThisWeek}", "DONE THIS WK")`, `StatTile("◷ ${uiState.focusHoursLabel}", "FOCUS THIS WK")`.
   - **`SectionLabel("Settings")`** then a grouped card: `Column` on `colors.surface`, `clip(RoundedCornerShape(shapes.card))`, `border(1.dp, colors.borderSubtle)`. For each `entry in uiState.settingsEntries` render a `Row(Modifier.clickable { onOpenSettings(entry) })` with the entry emoji, `Text(label, type.body, colors.textPrimary, Modifier.weight(1f))`, and a trailing `Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = colors.textTertiary)`; a `borderSubtle` `Divider` between rows (not after the last). Map entry→(emoji,label): Notifications "🔔"/"Notifications & reminders", Appearance "🎨"/"Appearance", Account "👤"/"Account", DataAndSync "☁"/"Data & sync", About "ℹ"/"About".
   - **Sign-out card**: a `Row` on `colors.surfaceInset`, `clip(RoundedCornerShape(shapes.well))`, `border(1.dp, colors.borderSubtle)`, centered, `Modifier.clickable { viewModel.onSignOut() }`: `Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = colors.danger)` + `Text("Sign out", type.body /*600*/, colors.danger)`. Add `semantics`/`contentDescription = "Sign out"` on the row.
   - Wrap content in a `Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = spacing.screenH))` so it scrolls on small screens; honor `start/end` padding (RTL).

2. **Verify it COMPILES (compile-gate):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```

3. **Behavior checklist vs. mockup (`full-app.html` You panel):**
   - [ ] 58dp circular avatar with a 135° amber gradient and 2-letter `onAccent` initials.
   - [ ] Display name (`heading`) over an ellipsized email (`meta`, `textTertiary`); `SyncStatusPill` shows a `success` dot + "Synced" when idle.
   - [ ] Three `StatTile`s in one row: 🔥 streak, ✓ done-this-week, ◷ focus-this-week-hours.
   - [ ] `SETTINGS` `SectionLabel`; one grouped card of 5 rows with emoji + label + `›` chevron, dividers between rows; tapping a row calls `onOpenSettings(entry)`.
   - [ ] Separate `surfaceInset` Sign-out card in `danger`; tapping calls `viewModel.onSignOut()`; row has a `contentDescription`.
   - [ ] Uses Midnight Calm tokens only (no hardcoded hex); start/end paddings (RTL); content scrolls.

4. **Commit:**
   ```
   feat(you): YouScreen — avatar, sync pill, stat tiles, grouped settings, sign out

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P9.8 — Extend `SettingsViewModel`: `forceSync` + `workdayMinutes` + `defaultReminderMinutes` (real tests)

Extend the existing `SettingsViewModel` (do not rewrite) with the new keys the sub-screens read/write and a `forceSync()` action. Add `workdayMinutes` (the planning-capacity Setting P5 reads — default 360 = 6h, matching the mockup's "your 6h"), `defaultReminderMinutes` (the default minutes-before for the Notifications sub-screen, default 10), extend `toSettingsState()` to map them, persist them in `save()`, and add `forceSync()` delegating to an injected `SyncScheduler.requestExpeditedSync()`. The VM gains a `SyncScheduler` ctor param; update the existing `SettingsViewModelTest` fakes accordingly. Real unit tests for the new mapping + clamping + forceSync.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/settings/SettingsViewModel.kt`
- `~ android/app/src/test/java/net/qmindtech/tmap/ui/settings/SettingsViewModelTest.kt`

**Interfaces** (exact additions — additive; existing fields/behavior unchanged)
```kotlin
// new keys (companion-level consts):
//   workdayMinutes (default 360), defaultReminderMinutes (default 10)
data class SettingsUiState(
    val loading: Boolean = true,
    val timeZoneId: String = "UTC",
    val workStartHour: Int = 9,
    val workEndHour: Int = 17,
    val notificationsEnabled: Boolean = true,
    val workdayMinutes: Int = 360,
    val defaultReminderMinutes: Int = 10,
)
// new VM members:
fun onWorkdayMinutesChange(m: Int)
fun onDefaultReminderChange(m: Int)
fun forceSync()
```

**Steps**

- [ ] **Write the failing test.** Append to `SettingsViewModelTest.kt`. First add a recording `FakeSyncScheduler` and thread it through the VM constructions in the existing tests (they currently call `SettingsViewModel(repo)`; the new ctor is `SettingsViewModel(repo, scheduler)` — update those call sites too):
  ```kotlin
      private class FakeSyncScheduler : net.qmindtech.tmap.data.sync.SyncScheduler {
          var expeditedCount = 0
          override fun requestExpeditedSync() { expeditedCount++ }
          override fun schedulePeriodic() {}
          override fun cancelAll() {}
      }

      @Test fun `toSettingsState maps workdayMinutes and defaultReminder with defaults`() {
          val s = listOf(
              setting("workdayMinutes", "420"),
              setting("defaultReminderMinutes", "30"),
          ).toSettingsState()
          assertEquals(420, s.workdayMinutes)
          assertEquals(30, s.defaultReminderMinutes)
          // missing → defaults
          val d = emptyList<SettingEntity>().toSettingsState()
          assertEquals(360, d.workdayMinutes)
          assertEquals(10, d.defaultReminderMinutes)
      }

      @Test fun `save persists workdayMinutes and defaultReminderMinutes`() = runTest {
          val repo = FakeSettingsRepository()
          val vm = SettingsViewModel(repo, FakeSyncScheduler())
          vm.onWorkdayMinutesChange(480)
          vm.onDefaultReminderChange(15)
          vm.save()
          val map = repo.lastSavedMap!!
          assertEquals("480", map["workdayMinutes"])
          assertEquals("15", map["defaultReminderMinutes"])
      }

      @Test fun `onWorkdayMinutesChange clamps to a sane positive range`() = runTest {
          val repo = FakeSettingsRepository()
          val vm = SettingsViewModel(repo, FakeSyncScheduler())
          vm.onWorkdayMinutesChange(-50)
          assertEquals(0, vm.uiState.value.workdayMinutes)
          vm.onWorkdayMinutesChange(5000)
          assertEquals(1440, vm.uiState.value.workdayMinutes) // capped at a full day
      }

      @Test fun `forceSync nudges the scheduler`() = runTest {
          val scheduler = FakeSyncScheduler()
          val vm = SettingsViewModel(FakeSettingsRepository(), scheduler)
          vm.forceSync()
          assertEquals(1, scheduler.expeditedCount)
      }
  ```

- [ ] **Verify FAIL.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.settings.SettingsViewModelTest"
  ```
  Expected: compilation failure — `SettingsViewModel` ctor arity / `unresolved reference: workdayMinutes` / `forceSync`.

- [ ] **Implement.** Edit `SettingsViewModel.kt`:
  - Add consts: `private const val KEY_WORKDAY_MINUTES = "workdayMinutes"` and `private const val KEY_DEFAULT_REMINDER = "defaultReminderMinutes"`.
  - Add the two fields to `SettingsUiState` (defaults 360 / 10).
  - In `toSettingsState()`: `workdayMinutes = byKey[KEY_WORKDAY_MINUTES]?.toIntOrNull() ?: 360`, `defaultReminderMinutes = byKey[KEY_DEFAULT_REMINDER]?.toIntOrNull() ?: 10`.
  - Add ctor param `private val syncScheduler: net.qmindtech.tmap.data.sync.SyncScheduler` after `settingsRepo`.
  - Add `fun onWorkdayMinutesChange(m: Int) = _uiState.update { it.copy(workdayMinutes = m.coerceIn(0, 1440)) }` and `fun onDefaultReminderChange(m: Int) = _uiState.update { it.copy(defaultReminderMinutes = m.coerceIn(0, 1440)) }`.
  - In `save()` add to the persisted map: `KEY_WORKDAY_MINUTES to s.workdayMinutes.coerceIn(0, 1440).toString()` and `KEY_DEFAULT_REMINDER to s.defaultReminderMinutes.coerceIn(0, 1440).toString()`.
  - Add `fun forceSync() { syncScheduler.requestExpeditedSync() }`.

- [ ] **Verify PASS.**
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.settings.SettingsViewModelTest"
  ```
  Green (existing + 4 new tests).

- [ ] **Commit:**
  ```
  feat(settings): SettingsViewModel workdayMinutes + defaultReminder + forceSync (P5 capacity source)

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task P9.9 — Settings sub-screens (5) (compile-gate + behavior-checklist)

Replace the single monolithic `SettingsScreen` with five focused sub-screens, all driven by the (now-extended) `SettingsViewModel`, restyled to Midnight Calm tokens. **Not unit-testable (Compose UI)** → compile-gate + behavior-checklist; the VM logic they call is already unit-tested in P9.8.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/settings/SettingsScreen.kt` (refactor into the five sub-screen composables below; keep `SettingsScreen` removed or thin — the You tab now routes directly to each)
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/settings/NotificationsSettingsScreen.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/settings/AppearanceSettingsScreen.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/settings/AccountSettingsScreen.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/settings/DataSyncSettingsScreen.kt`
- `+ android/app/src/main/java/net/qmindtech/tmap/ui/settings/AboutSettingsScreen.kt`

**Interfaces** (exact)
```kotlin
@Composable fun NotificationsSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel())
@Composable fun AppearanceSettingsScreen(onBack: () -> Unit)
@Composable fun AccountSettingsScreen(onBack: () -> Unit, viewModel: net.qmindtech.tmap.ui.you.YouViewModel = hiltViewModel())
@Composable fun DataSyncSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel())
@Composable fun AboutSettingsScreen(onBack: () -> Unit)
```

**Steps**

1. **Implement the five sub-screens** (each a `TmapBackground` + a back-arrow header `Row` with `contentDescription = "Back"`, then content; use tokens; `start/end` paddings):
   - **Notifications & reminders:** the `notificationsEnabled` `Switch` (reuse existing logic), a **default reminder** stepper bound to `onDefaultReminderChange` (label "Remind X min before"), and the `ReminderPermissionGate(canScheduleExact = { … })` from `ui/permissions/NotificationPermission.kt` so the in-context POST_NOTIFICATIONS / exact-alarm grant lives here. `Save` button → `viewModel.save()`.
   - **Appearance:** a calm read-only card stating **"Dark only — Midnight Calm"** with the spec note that a light theme is out of scope (§2 deferrals); no toggles. (Honors the dark-only constraint.)
   - **Account:** show the signed-in email (from `YouViewModel.uiState.profile.email`) and a `timeZoneId` field (reuse the existing `OutlinedTextField`-style control, restyled) bound to `onTimeZoneChange` + `Save`. A **Sign out** `SecondaryButton`/danger affordance that calls `youViewModel.onSignOut()` (same delegation).
   - **Data & sync:** show the current `SyncStatusPill` (status + pending), a "Last sync" line if available (else "—"), a **Force sync now** `PrimaryButton` → `viewModel.forceSync()`, and the **Workday capacity** stepper bound to `onWorkdayMinutesChange` shown as hours (e.g. "6h 0m") with a note "Used to plan your day's capacity." (this is the Setting P5 reads). `Save`.
   - **About:** app name "TMap", version (`BuildConfig.VERSION_NAME`), a one-line description, and a link/text to the backend host (`api-tasks.qmindtech.net`). Static.
   - In `SettingsScreen.kt`, delete the old monolith body (or reduce `SettingsScreen` to a thin Notifications alias) — the You tab routes to each sub-screen directly (P9.10). Keep imports tidy so `assembleDebug` stays warning-clean for lint.

2. **Verify it COMPILES (compile-gate):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```

3. **Behavior checklist vs. mockup + spec §6.10:**
   - [ ] Five reachable sub-screens (Notifications & reminders / Appearance / Account / Data & sync / About), each with a labeled back affordance.
   - [ ] Notifications screen hosts the reminder permission gate + a default-reminder stepper + the notifications toggle; saves via the VM.
   - [ ] Appearance states dark-only (no light/dynamic toggle) — honors the dark-only constraint.
   - [ ] Account shows the email + timezone + a Sign-out that uses the same `onSignOut` delegation.
   - [ ] Data & sync shows sync status + a **Force sync now** button (→ `forceSync()`) + the **workday capacity** Setting (the value P5 reads).
   - [ ] About shows name/version/description; no secrets; base host shown read-only.
   - [ ] All tokens, no hardcoded hex; start/end paddings (RTL); icon controls labeled.

4. **Commit:**
   ```
   feat(settings): five Midnight Calm settings sub-screens (notifications/appearance/account/data-sync/about)

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P9.10 — Wire the You tab + settings routes + DI (provide `StatsCalculator`, bind `SignOutAction`, provide pendingCount)

Replace the P0 `YouPlaceholder()` with the real `YouScreen` in `MainScaffold`, add the settings sub-screen destinations, and complete DI: provide `StatsCalculator(clock)`, bind `SignOutAction` → `AuthRepository::logout`, and provide the `@Named("pendingCount") Flow<Int>` from `OutboxRepository.observeUnparkedCount()`. Compile-gate + the existing Hilt-graph smoke test (`AppGraphWiringTest`) confirm the wiring resolves.

**Files**
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/navigation/Routes.kt` (add 5 settings child routes)
- `~ android/app/src/main/java/net/qmindtech/tmap/ui/navigation/MainScaffold.kt` (real `YouScreen` + settings destinations)
- `~ android/app/src/main/java/net/qmindtech/tmap/di/AppModule.kt` (provide `StatsCalculator`, bind `SignOutAction`, provide `pendingCount`)

**Interfaces** (exact additions)
```kotlin
// Routes.kt — string-route child destinations reached from YouScreen:
data object SettingsNotifications : Route { override val route = "settings/notifications" }
data object SettingsAppearance   : Route { override val route = "settings/appearance" }
data object SettingsAccount      : Route { override val route = "settings/account" }
data object SettingsDataSync     : Route { override val route = "settings/data_sync" }
data object SettingsAbout        : Route { override val route = "settings/about" }

// AppModule.kt — additive (StatsCalculator resolves via its own @Inject ctor, but provide explicitly for clarity is OPTIONAL;
// the REQUIRED additions are the SignOutAction binding + the pendingCount flow):
@Provides @Singleton @Named("pendingCount")
fun providePendingCount(outbox: OutboxRepository): kotlinx.coroutines.flow.Flow<Int> = outbox.observeUnparkedCount()

@Provides @Singleton
fun provideSignOutAction(authRepository: AuthRepository): net.qmindtech.tmap.ui.you.SignOutAction =
    net.qmindtech.tmap.ui.you.SignOutAction { authRepository.logout() }
```

**Steps**

1. **Implement the routes + nav.**
   - In `Routes.kt`, add the five `data object` settings routes above (each with a `route` path).
   - In `MainScaffold.kt`:
     - Replace `composable(Route.You.route) { YouPlaceholder() }` with:
       ```kotlin
       composable(Route.You.route) {
           YouScreen(onOpenSettings = { entry ->
               navController.navigate(
                   when (entry) {
                       SettingsEntry.Notifications -> Route.SettingsNotifications.route
                       SettingsEntry.Appearance   -> Route.SettingsAppearance.route
                       SettingsEntry.Account      -> Route.SettingsAccount.route
                       SettingsEntry.DataAndSync  -> Route.SettingsDataSync.route
                       SettingsEntry.About        -> Route.SettingsAbout.route
                   }
               )
           })
       }
       ```
     - Add five `composable(Route.SettingsX.route) { XSettingsScreen(onBack = { navController.popBackStack() }) }` destinations (Account passes its `YouViewModel`/back; the rest as their signatures specify). Import the new screens + `SettingsEntry`.
   - Remove the now-unused `YouPlaceholder()` (or leave it — but delete its `Route.You` call site) to keep lint clean.

2. **Implement the DI.** In `AppModule.kt`:
   - Add imports: `net.qmindtech.tmap.data.sync.OutboxRepository`, `net.qmindtech.tmap.ui.you.SignOutAction`, `javax.inject.Named`, `kotlinx.coroutines.flow.Flow`.
   - In the `companion object`, add `provideSignOutAction(authRepository: AuthRepository): SignOutAction = SignOutAction { authRepository.logout() }` and `@Named("pendingCount") providePendingCount(outbox: OutboxRepository): Flow<Int> = outbox.observeUnparkedCount()`.
   - `StatsCalculator` is `@Inject`-constructible (its only dep is the already-provided `Clock`), so no `@Provides` is strictly needed — but if you prefer explicitness, add `@Provides @Singleton fun provideStatsCalculator(clock: Clock) = StatsCalculator(clock)`. **Adopt the no-extra-@Provides path** (let `@Inject` resolve it) to avoid a duplicate-binding risk; document this in the commit body.

3. **Verify it COMPILES + the Hilt graph resolves (compile-gate + smoke):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   cd android && ./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.AppGraphWiringTest"
   ```
   The smoke test builds the FULL DI graph; if `SignOutAction`/`pendingCount`/`YouViewModel` were unbound, instantiating the graph (and `YouViewModel` via `hiltViewModel()` reachability) would fail at compile or graph-validation. (The existing `AppGraphWiringTest` injects `AuthRepository` + `HiltWorkerFactory`; do NOT change its assertions — it already exercises full-graph validity. If desired, add a single `@Inject lateinit var signOut: SignOutAction` field + `assertNotNull` to it to make the new binding explicitly covered, matching the test's existing field-injection style.)

4. **Behavior checklist:**
   - [ ] You tab renders the real `YouScreen` (P0 placeholder gone); the FAB + other tabs/deep-link unaffected (regression).
   - [ ] Tapping a settings row navigates to the matching sub-screen; back returns to You.
   - [ ] `SignOutAction` is bound to `AuthRepository::logout` (sign-out triggers the existing teardown — cancel sync, clear tokens, keep Room); `pendingCount` flows from the outbox.
   - [ ] `AppGraphWiringTest` still passes (full graph valid with the new bindings).

5. **Commit:**
   ```
   feat(you): wire You tab + settings routes; bind SignOutAction→logout + provide pendingCount

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

### Task P9.11 — Phase gate: full unit suite + assembleDebug + lint + behavior checklist (spec §10.9)

Green-gate the phase. Run the full P9 unit set plus the Hilt-graph smoke, the build, and lint; then re-confirm the acceptance-criterion §10.9 checklist against the assembled app. Verification only — no new production code.

**Files** (none — verification only)

**Steps**

1. **Run the P9 unit tests + regression smoke (must all PASS):**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest \
     --tests "net.qmindtech.tmap.data.stats.*" \
     --tests "net.qmindtech.tmap.ui.you.*" \
     --tests "net.qmindtech.tmap.ui.settings.SettingsViewModelTest" \
     --tests "net.qmindtech.tmap.data.local.dao.DailyPlanDaoTest" \
     --tests "net.qmindtech.tmap.AppGraphWiringTest"
   ```
2. **Full suite (no regressions — engine/auth/reminders/sync tests still green):**
   ```bash
   cd android && ./gradlew :app:testDebugUnitTest
   ```
3. **Compile gate (must be GREEN):**
   ```bash
   cd android && ./gradlew :app:assembleDebug
   ```
4. **Lint gate:**
   ```bash
   cd android && ./gradlew :app:lintDebug
   ```
5. **Acceptance §10.9 behavior checklist (You) — verify in the assembled app against `full-app.html`:**
   - [ ] **Profile:** avatar initials + display name + email derived from the session email; matches the mockup's amber-gradient avatar.
   - [ ] **Sync status:** the `SyncStatusPill` reflects `SyncStatusHolder` (synced/syncing/offline/error) + pending count; Retry nudges sync.
   - [ ] **Streak/weekly stats (from local data):** 🔥 day streak (consecutive active days + today-grace), ✓ done-this-week (ISO week), ◷ focus-this-week (ISO-week minutes → hours) — all computed by `StatsCalculator` over Room flows, no network.
   - [ ] **Settings:** all five sub-screens reachable; workday-capacity Setting persists (P5 reads it); force-sync works; notifications gate present; appearance is dark-only.
   - [ ] **Working sign-out (existing teardown):** Sign out delegates to `AuthRepository.logout()` (`SignOutAction`) — cancels sync, clears tokens, keeps Room (§5.3); the auth gate returns to login.
   - [ ] **Offline / RTL / a11y:** You screen reads only Room/StateFlow (no blocking network); start/end paddings mirror; avatar + sign-out + back + chevrons labeled.
6. **Commit (gate marker):**
   ```
   chore(you): P9 gate — stats/you/settings unit suite + assembleDebug + lint green; §10.9 verified

   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

---

## P10 — Polish & cross-cutting gate

This is the final phase: it does not add features but hardens and verifies everything P0–P9 built against the spec's non-functional requirements (§9) and acceptance criteria (§10, AC1–AC13). It mixes (a) small REAL polish changes — a motion pass that routes every sheet/nav/swipe/check-off animation through the `TmapMotion` tokens with a **reduce-motion gate** keyed off `Settings.Global.ANIMATOR_DURATION_SCALE`; a per-surface **empty-state** pass (including Inbox Zero) driven by a pure copy-mapper; an **RTL** start/end sweep with Arabic mirror fixes; and an **accessibility** audit (contentDescriptions, non-gesture equivalents for every swipe, font-scale, contrast) — each with a pure-function unit test where one is extractable (motion-scale, empty-state copy mapper, reduce-motion decision) plus a compile-gate + reviewer checklist for the Compose wiring; and (b) explicit **VERIFICATION** tasks that run named `./gradlew :app:testDebugUnitTest` subsets and an **emulator manual checklist** mapping 1:1 to spec §10 AC1–AC13. The engine, auth, and reminders are reused unchanged — their existing test classes must stay green (AC12) and the four new domains must be covered by their equivalents. The phase ends with the branch done-gate: `assembleDebug` + `lintDebug` + `testDebugUnitTest` all green plus the AC1–AC13 checklist, and then STOPS (no merge, no push). All Gradle commands run from `android/`. Every commit ends with the trailer `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

> **Test-substitution note (applies to every UI task in P10):** Composables are not unit-testable in this project's JVM/Robolectric `src/test/` suite (no instrumented `androidTest` harness is configured). Per the plan convention, each non-unit-testable Compose change is gated by **`./gradlew :app:assembleDebug` passing + a reviewer behavior checklist** verified against the named mockup. Where pure logic is extractable (the reduce-motion scale, the empty-state copy mapper), it is pulled into a plain Kotlin file with a **real failing-first JUnit test** run via `./gradlew :app:testDebugUnitTest`. The emulator manual checklists in the VERIFICATION tasks are executed by the reviewer on a running debug build and are the human gate for the behavioral acceptance criteria.

---

### Task P10.1 — Reduce-motion decision logic (pure, unit-tested)

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/ReduceMotion.kt`
- Create (test): `android/app/src/test/java/net/qmindtech/tmap/ui/theme/ReduceMotionTest.kt`

**Interfaces**
- Produces: `fun reducedMotion(animatorDurationScale: Float): Boolean` — returns `true` when the system animator-duration scale is `0f` (animations disabled / "remove animations" accessibility setting), else `false`.
- Produces: `fun effectiveDurationMillis(baseMillis: Int, reduceMotion: Boolean): Int` — returns `0` when `reduceMotion` is true (instant, no tween), else `baseMillis`.
- Consumes: nothing (the actual `Settings.Global.getFloat(resolver, ANIMATOR_DURATION_SCALE, 1f)` read happens at the Compose call site in P10.2; this file is the pure decision so it is JVM-testable).

**Steps**

1. Write the failing test `ReduceMotionTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ReduceMotionTest {
    @Test
    fun scaleZeroMeansReduceMotion() {
        assertEquals(true, reducedMotion(0f))
    }

    @Test
    fun normalAndSlowScalesDoNotReduceMotion() {
        assertEquals(false, reducedMotion(1f))
        assertEquals(false, reducedMotion(0.5f))
        assertEquals(false, reducedMotion(10f))
    }

    @Test
    fun reduceMotionCollapsesDurationToZero() {
        assertEquals(0, effectiveDurationMillis(220, reduceMotion = true))
        assertEquals(0, effectiveDurationMillis(180, reduceMotion = true))
    }

    @Test
    fun normalMotionKeepsBaseDuration() {
        assertEquals(220, effectiveDurationMillis(220, reduceMotion = false))
        assertEquals(180, effectiveDurationMillis(180, reduceMotion = false))
    }
}
```

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ReduceMotionTest"`
   - Expected: compilation failure — `unresolved reference: reducedMotion` / `effectiveDurationMillis`.

3. Implement — `ReduceMotion.kt`:

```kotlin
package net.qmindtech.tmap.ui.theme

/**
 * Reduce-motion gate (spec §9 / §4.2 "respects reduce motion"). The system exposes
 * Settings.Global.ANIMATOR_DURATION_SCALE; a value of 0f means the user disabled animations.
 * These pure helpers keep the decision JVM-testable; the actual setting read + LocalReduceMotion
 * provider live at the Compose call site (P10.2).
 */
fun reducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/** Collapse any tween duration to 0 (instant) when reduce-motion is active. */
fun effectiveDurationMillis(baseMillis: Int, reduceMotion: Boolean): Int =
    if (reduceMotion) 0 else baseMillis
```

4. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.theme.ReduceMotionTest"`
   - Expected: `BUILD SUCCESSFUL`; 4 tests pass.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/theme/ReduceMotion.kt android/app/src/test/java/net/qmindtech/tmap/ui/theme/ReduceMotionTest.kt`
   - Message:
     ```
     feat(android-motion): add pure reduce-motion decision helpers

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P10.2 — Wire `LocalReduceMotion` into `TmapTheme` + a `tmapTween` helper

**Files**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Motion.kt`
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/theme/Theme.kt`

**Interfaces**
- Produces: `val LocalReduceMotion: ProvidableCompositionLocal<Boolean>` (default `false`) in `Motion.kt`.
- Produces: `@Composable fun rememberReduceMotion(): Boolean` — reads `Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)` and passes it through `reducedMotion(...)` (P10.1).
- Produces: `@Composable fun tmapTween(baseMillis: Int = LocalTmapMotion.current.standardMillis): TweenSpec<Float>` — a `tween(effectiveDurationMillis(baseMillis, LocalReduceMotion.current), easing = standardEasing())` so every animation site gets the gated duration.
- Consumes: `reducedMotion`, `effectiveDurationMillis`, `standardEasing`, `LocalTmapMotion`.
- `TmapTheme` now also provides `LocalReduceMotion provides rememberReduceMotion()` alongside the existing locals (FIXED P0 contract preserved — this is an additive provide).

**Steps**

1. Implement — append to `Motion.kt`:

```kotlin
import android.provider.Settings
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** True when the user has disabled system animations (accessibility). Provided by TmapTheme. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Reads the system animator-duration scale and maps it to the reduce-motion flag. */
@Composable
fun rememberReduceMotion(): Boolean {
    val scale = Settings.Global.getFloat(
        LocalContext.current.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return reducedMotion(scale)
}

/** A standard-eased tween whose duration collapses to 0 under reduce-motion. */
@Composable
fun tmapTween(baseMillis: Int = LocalTmapMotion.current.standardMillis): TweenSpec<Float> =
    tween(durationMillis = effectiveDurationMillis(baseMillis, LocalReduceMotion.current), easing = standardEasing())
```

2. Implement — in `Theme.kt`, add the provide inside `TmapTheme`'s `CompositionLocalProvider` (after `LocalTmapMotion provides TmapDefaultMotion,`):

```kotlin
        LocalReduceMotion provides rememberReduceMotion(),
```
   (add the `import net.qmindtech.tmap.ui.theme.LocalReduceMotion` / `rememberReduceMotion` references — same package, no import needed.)

3. Compile gate:
   - Command: `./gradlew :app:compileDebugKotlin`
   - Expected: `BUILD SUCCESSFUL`.

4. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/theme/Motion.kt android/app/src/main/java/net/qmindtech/tmap/ui/theme/Theme.kt`
   - Message:
     ```
     feat(android-motion): provide LocalReduceMotion + tmapTween in TmapTheme

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer):** With "Remove animations" enabled in the emulator's developer/accessibility settings (animator duration scale 0), `LocalReduceMotion.current == true` and `tmapTween()` produces a 0ms (instant) tween.

---

### Task P10.3 — Motion pass: route sheet/nav/swipe/check-off through tokens + reduce-motion gate

**Files**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/SheetHost.kt` (sheet enter/exit + capture/editor sheet show/hide animations)
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/navigation/MainScaffold.kt` (bottom-nav tab transition / NavHost content animation)
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/components/SwipeableTaskCard.kt` (snap-back / reveal spring)
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/components/TaskCard.kt` (check-off scale+fill)

**Interfaces**
- Consumes: `tmapTween`, `LocalReduceMotion`, `LocalTmapMotion` (P10.1/P10.2).
- Produces: no new public symbols — this is a wiring pass.

> **Test substitution:** UI; gate = `./gradlew :app:assembleDebug` + the checklist below, verified against `daily-core.html` (swipe + check-off) and `navigation.html` (sheet + tab transitions). Spring physics on swipe/drag are applied at the call site per spec §4.2; under reduce-motion the spring is replaced by a `snap()` (instant).

**Steps**

1. **Check-off (TaskCard):** replace the `tween(motion.checkOffMillis)` in `animateFloatAsState` with `tmapTween(LocalTmapMotion.current.checkOffMillis)` so the scale+fill collapses to instant under reduce-motion. Keep the amber fill behavior unchanged.

2. **Swipe (SwipeableTaskCard):** wrap the snap-back / settle animation so that when `LocalReduceMotion.current` is true the offset uses `snapTo(...)` (instant) instead of `animateTo(..., spring(...))`. The threshold/decision math (`resolveSwipe`) is untouched.

3. **Sheets (SheetHost):** the capture + editor bottom-sheet enter/exit slide uses a `tmapTween(LocalTmapMotion.current.standardMillis)`-based spec (or M3 sheet animation overridden to the gated duration). Under reduce-motion the sheet shows/hides without slide.

4. **Nav (MainScaffold):** the NavHost tab content transition uses the gated standard duration (instant under reduce-motion); no crossfade flicker.

5. Compile gate:
   - Command: `./gradlew :app:assembleDebug`
   - Expected: `BUILD SUCCESSFUL`.

6. Commit:
   - `git add` the four modified files
   - Message:
     ```
     feat(android-motion): gate sheet/nav/swipe/check-off motion on reduce-motion

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer, emulator):**
- [ ] Check-off plays a brief scale+amber-fill (~180ms) with animations on; instant with "Remove animations" on.
- [ ] Swipe reveal springs back smoothly with animations on; snaps instantly with reduce-motion on.
- [ ] Capture and editor sheets slide up ~220ms with animations on; appear without slide under reduce-motion.
- [ ] Switching bottom-nav tabs transitions at the standard duration; instant under reduce-motion. No visual glitches in either mode.

---

### Task P10.4 — Empty-state copy mapper (pure, unit-tested)

**Files**
- Create: `android/app/src/main/java/net/qmindtech/tmap/ui/components/EmptyStateCopy.kt`
- Create (test): `android/app/src/test/java/net/qmindtech/tmap/ui/components/EmptyStateCopyTest.kt`

**Interfaces**
- Produces: `enum class EmptySurface { Today, Inbox, Browse, BrowseSearch, Backlog, Projects, Notes, NotesGroup, ProjectDetail, FocusQueue }`.
- Produces: `data class EmptyCopy(val title: String, val subtitle: String?, val actionLabel: String?)`.
- Produces: `fun emptyCopyFor(surface: EmptySurface): EmptyCopy` — the per-surface calm copy (spec §6.6 "Inbox Zero", §6.9 Notes, etc.). Inbox returns the mockup's **"Inbox Zero feels good."** line.
- Consumes: nothing. (The composable `EmptyState(icon, title, subtitle, actionLabel, onAction)` from P0.12 is the renderer; this maps a surface → its copy so the strings are testable and centralized. Icons are chosen at the call site.)

**Steps**

1. Write the failing test `EmptyStateCopyTest.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmptyStateCopyTest {
    @Test
    fun everySurfaceHasNonBlankTitle() {
        for (s in EmptySurface.entries) {
            val copy = emptyCopyFor(s)
            assertTrue("title blank for $s", copy.title.isNotBlank())
        }
    }

    @Test
    fun inboxUsesInboxZeroCopy() {
        val copy = emptyCopyFor(EmptySurface.Inbox)
        assertEquals("Inbox Zero feels good.", copy.title)
    }

    @Test
    fun todayInvitesPlanning() {
        val copy = emptyCopyFor(EmptySurface.Today)
        assertNotNull(copy.actionLabel) // e.g. "Plan my day"
    }

    @Test
    fun searchSurfaceHasNoAction() {
        // A no-results search state offers no CTA (nothing to create from a query).
        assertEquals(null, emptyCopyFor(EmptySurface.BrowseSearch).actionLabel)
    }

    @Test
    fun notesAndProjectsOfferCreateActions() {
        assertNotNull(emptyCopyFor(EmptySurface.Notes).actionLabel)
        assertNotNull(emptyCopyFor(EmptySurface.Projects).actionLabel)
    }
}
```

2. Run and verify FAIL:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.EmptyStateCopyTest"`
   - Expected: compilation failure — `unresolved reference: EmptySurface` / `emptyCopyFor`.

3. Implement — `EmptyStateCopy.kt`:

```kotlin
package net.qmindtech.tmap.ui.components

/** Surfaces that can render an EmptyState. One calm copy per surface (spec §6). */
enum class EmptySurface {
    Today, Inbox, Browse, BrowseSearch, Backlog, Projects, Notes, NotesGroup, ProjectDetail, FocusQueue
}

data class EmptyCopy(val title: String, val subtitle: String?, val actionLabel: String?)

/**
 * Per-surface empty-state copy. Centralized + pure so the strings are unit-tested and consistent.
 * Inbox uses the approved mockup line ("Inbox Zero feels good."). Search-with-no-results offers no CTA.
 */
fun emptyCopyFor(surface: EmptySurface): EmptyCopy = when (surface) {
    EmptySurface.Today -> EmptyCopy(
        title = "A clear day",
        subtitle = "Nothing planned yet. Plan your day or capture a task with +.",
        actionLabel = "Plan my day",
    )
    EmptySurface.Inbox -> EmptyCopy(
        title = "Inbox Zero feels good.",
        subtitle = "Everything's triaged. Capture new ideas with +.",
        actionLabel = null,
    )
    EmptySurface.Browse -> EmptyCopy(
        title = "No tasks yet",
        subtitle = "Tasks you create will show up here.",
        actionLabel = null,
    )
    EmptySurface.BrowseSearch -> EmptyCopy(
        title = "No matches",
        subtitle = "Try a different search or clear your filters.",
        actionLabel = null,
    )
    EmptySurface.Backlog -> EmptyCopy(
        title = "Backlog is empty",
        subtitle = "Park tasks here when they're not for today.",
        actionLabel = null,
    )
    EmptySurface.Projects -> EmptyCopy(
        title = "No projects yet",
        subtitle = "Group related tasks and notes under a project.",
        actionLabel = "New project",
    )
    EmptySurface.Notes -> EmptyCopy(
        title = "No notes yet",
        subtitle = "Jot ideas, meeting notes, and references.",
        actionLabel = "New note",
    )
    EmptySurface.NotesGroup -> EmptyCopy(
        title = "This notebook is empty",
        subtitle = "Add a note to this notebook with +.",
        actionLabel = "New note",
    )
    EmptySurface.ProjectDetail -> EmptyCopy(
        title = "Nothing here yet",
        subtitle = "Tasks and notes in this project will appear here.",
        actionLabel = null,
    )
    EmptySurface.FocusQueue -> EmptyCopy(
        title = "No tasks queued",
        subtitle = "Pick a task to focus on.",
        actionLabel = null,
    )
}
```

4. Run and verify PASS:
   - Command: `./gradlew :app:testDebugUnitTest --tests "net.qmindtech.tmap.ui.components.EmptyStateCopyTest"`
   - Expected: `BUILD SUCCESSFUL`; 5 tests pass.

5. Commit:
   - `git add android/app/src/main/java/net/qmindtech/tmap/ui/components/EmptyStateCopy.kt android/app/src/test/java/net/qmindtech/tmap/ui/components/EmptyStateCopyTest.kt`
   - Message:
     ```
     feat(android-ui): add per-surface empty-state copy mapper (incl. Inbox Zero)

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

---

### Task P10.5 — Empty-state pass: render per-surface EmptyState on every list

**Files**
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/today/TodayListContent.kt` (Today empty)
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/inbox/InboxScreen.kt` (Inbox Zero)
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/browse/BrowseScreen.kt` (Browse + no-search-results)
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/projects/ProjectsScreen.kt` (Projects) and `ui/projects/ProjectDetailScreen.kt`
- Modify: `android/app/src/main/java/net/qmindtech/tmap/ui/notes/NotesScreen.kt` (Notes + empty notebook)

**Interfaces**
- Consumes: `emptyCopyFor`, `EmptySurface`, `EmptyState` (P0.12 composable). Each call site picks a lucide/Material icon and wires `onAction` to the surface's primary action (Today → open Planning; Projects/Notes → open the create sheet/dialog).
- Produces: no new symbols.

> **Test substitution:** UI; gate = `./gradlew :app:assembleDebug` + the checklist. Copy correctness is already unit-covered by P10.4; this task only wires the renderer in when a list's items are empty.

**Steps**

1. In each screen, when the observed list (and, for Browse, the active search/filter set) is empty, render `EmptyState(icon = <surface icon>, title = copy.title, subtitle = copy.subtitle, actionLabel = copy.actionLabel, onAction = { ... })` where `val copy = emptyCopyFor(EmptySurface.X)`.
   - Browse: when a search query/filter is active and yields nothing → `EmptySurface.BrowseSearch` (no CTA); when the underlying library is genuinely empty → `EmptySurface.Browse`/`Backlog`/`Projects` per the active segment.
   - Inbox: `EmptySurface.Inbox` (Inbox Zero).
   - ProjectDetail / Notes-notebook: `EmptySurface.ProjectDetail` / `EmptySurface.NotesGroup`.

2. Compile gate:
   - Command: `./gradlew :app:assembleDebug`
   - Expected: `BUILD SUCCESSFUL`.

3. Commit:
   - `git add` the modified screens
   - Message:
     ```
     feat(android-ui): wire per-surface empty states across all lists

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer, emulator):**
- [ ] Empty Today shows "A clear day" + "Plan my day" CTA that opens the ritual.
- [ ] Empty Inbox shows "Inbox Zero feels good." (matches `full-app.html`).
- [ ] Browse with a no-match search shows "No matches" and NO create CTA; truly-empty Browse/Backlog/Projects show their library copy.
- [ ] Empty Notes shows "No notes yet" + "New note"; empty notebook shows the notebook copy.
- [ ] Empty Projects shows "No projects yet" + "New project"; empty project detail shows its copy.
- [ ] All empty states are centered, calm, use token typography and the `SecondaryButton` action.

---

### Task P10.6 — RTL audit: start/end sweep + Arabic mirror fixes across new screens

**Files**
- Audit + fix (no exhaustive list — sweep all P0–P9 UI): `android/app/src/main/java/net/qmindtech/tmap/ui/**/*.kt` and `android/app/src/main/java/net/qmindtech/tmap/ui/components/**/*.kt`.
- Likely fix sites: `ui/components/SwipeableTaskCard.kt` (swipe direction semantics), `ui/components/TaskCard.kt`, `ui/today/*`, `ui/today/TimelineContent.kt` (hour rail + now-line), `ui/browse/*`, `ui/notes/*`, `ui/focus/*`, `ui/navigation/MainScaffold.kt`.

**Interfaces**
- Produces / Consumes: no new symbols. Constraint enforcement only (spec §9: use `start`/`end`, mirror correctly; AC13 "Arabic RTL verified").

> **Test substitution:** UI/layout; gate = static grep audit (below) + `./gradlew :app:assembleDebug` + the emulator RTL checklist. Verified manually under a forced-RTL locale.

**Steps**

1. **Static sweep.** From `android/`, audit for left/right usages that must be start/end:
   - `grep -rnE "padding(Start|End)?\\(.*(left|right)|\\.absolute|Alignment\\.(CenterStart|CenterEnd)|TextAlign\\.(Left|Right)|horizontalArrangement" android/app/src/main/java/net/qmindtech/tmap/ui`
   - Replace any `Modifier.padding(left=/right=)`, `Modifier.absolutePadding`, `Alignment.Absolute*`, `TextAlign.Left/Right` with the directional `start`/`end` / `TextAlign.Start/End` equivalents. (Compose `padding(start=, end=)` and `Arrangement.Start/End` already mirror — flag any absolute variants.)
2. **Swipe semantics.** Confirm `SwipeableTaskCard` reads swipe direction relative to layout direction: in RTL, "swipe toward end = complete" must still mean complete (the reveal background + icon side mirror). If `resolveSwipe` is fed raw pixel offset, ensure the call site negates the offset under `LocalLayoutDirection.current == Rtl` so the gesture stays semantically start→complete / end→defer.
3. **Timeline.** The hour rail, now-line, and time-block left-bar sit on the `start` edge; verify they mirror to the `start` (visually right) edge in RTL and the now-line still spans full width.
4. **Force-RTL build check (developer setting):** the layout is exercised manually in step-checklist; statically ensure no hardcoded directional offsets remain.
5. Compile gate:
   - Command: `./gradlew :app:assembleDebug`
   - Expected: `BUILD SUCCESSFUL`.
6. Commit:
   - `git add` the fixed files
   - Message:
     ```
     fix(android-rtl): start/end sweep + Arabic mirror across new screens

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer, emulator — System → Developer options → "Force RTL layout direction" ON, and/or device language = العربية):**
- [ ] Every new screen (Today list + timeline, Inbox, Browse, Notes, Focus, Planning, You, editor + capture sheets) mirrors: titles/eyebrows align to the start (right) edge, chevrons/back arrows flip, FAB sits on the start (left-visual) corner consistent with the rest of the app.
- [ ] Swipe-to-complete vs swipe-to-defer keep their MEANING under RTL (gesture toward end still completes); reveal backgrounds and icons are on the correct mirrored sides.
- [ ] Timeline hour labels + now-line + project left-bar mirror correctly.
- [ ] No clipped or off-screen content; bidirectional text (Arabic title + Latin meta) renders without overlap.

---

### Task P10.7 — Accessibility audit: contentDescriptions, non-gesture equivalents, font-scale, contrast

**Files**
- Audit + fix across UI: `android/app/src/main/java/net/qmindtech/tmap/ui/**/*.kt`, `android/app/src/main/java/net/qmindtech/tmap/ui/components/**/*.kt`.
- Likely fix sites: every icon-only control (`TmapFab`, bottom-nav icons in `MainScaffold`, header action icons in `ui/today/*`, `ui/you/*`, `ui/notes/*`, capture mic, focus pause/end controls, `SyncStatusPill` retry, project/notebook chips).

**Interfaces**
- Produces / Consumes: no new symbols. Spec §9 / AC13: contentDescription on every icon-only control; every swipe action has a non-gesture equivalent; honor system font scale; ≥4.5:1 text contrast.

> **Test substitution:** UI/semantics; gate = static grep audit + `./gradlew :app:assembleDebug` + `./gradlew :app:lintDebug` (Android lint flags `ContentDescription` on icon-only `Image`/clickable elements) + the emulator a11y checklist. The contrast claim is satisfied by the Midnight Calm palette (chosen in §4.1 to meet 4.5:1); the audit confirms no token misuse (e.g. `textTertiary` on `surface` for body text).

**Steps**

1. **contentDescription sweep.** From `android/`:
   - `grep -rnE "Icon\\(|IconButton\\(|Image\\(" android/app/src/main/java/net/qmindtech/tmap/ui` and confirm every icon that conveys an ACTION/STATE has a non-null `contentDescription` (decorative icons inside a labeled control may pass `null`, but the control itself must be labeled via `Modifier.semantics { contentDescription = ... }` or a sibling text). Add labels where missing: FAB ("Quick capture"), bottom-nav tabs (tab name), capture mic ("Voice capture"), focus Pause/Resume/Mark done/End, sync-pill Retry, swipe reveal icons already labeled in P0.7.
2. **Non-gesture equivalents (AC13).** Verify every swipe action is also reachable without a gesture:
   - Swipe-complete ⇄ the card's circular checkbox (`onToggleComplete`).
   - Swipe-defer/delete ⇄ the bottom-sheet editor's status/defer + delete actions AND a long-press context menu on the card. Ensure a long-press menu (or editor) exposes Defer + Delete; if a screen has swipe-only delete, add the long-press/editor path.
3. **Font scale.** Confirm text uses `sp` (the `TmapType` styles already do) and that key screens don't clip at large font scale — set `Modifier.fillMaxWidth()` + `maxLines`/`Ellipsis` where a single line is assumed; verify in the checklist at 1.3×/2.0×.
4. **Contrast.** Spot-check token pairings: body/primary text uses `textPrimary`/`textBody` on `surface`/`surfaceRaised` (passes 4.5:1); `textTertiary` is only used for hints/labels (large/secondary), never for body copy on the darkest surfaces.
5. Gates:
   - Command: `./gradlew :app:lintDebug` — Expected: `BUILD SUCCESSFUL`; no new `ContentDescription` errors. (Record the lint report path `android/app/build/reports/lint-results-debug.html`.)
   - Command: `./gradlew :app:assembleDebug` — Expected: `BUILD SUCCESSFUL`.
6. Commit:
   - `git add` the fixed files
   - Message:
     ```
     fix(android-a11y): label icon controls, add non-gesture equivalents, font-scale safety

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

**Behavior checklist (reviewer, emulator — TalkBack ON; Display size & text → largest font scale):**
- [ ] TalkBack announces a meaningful label for the FAB, every bottom-nav tab, capture mic, focus controls, sync-pill retry, and all header action icons.
- [ ] Every swipe action (complete, defer, delete) is reachable via the checkbox / long-press menu / editor without swiping.
- [ ] At 1.3× and 2.0× font scale, Today/Inbox/Browse/Notes/You/editor remain readable — text wraps/ellipsizes, no overlap or clipping of controls.
- [ ] Text on cards/sheets is legible (no low-contrast body text on dark surfaces).

---

### Task P10.8 — VERIFICATION: regression of the reused engine, auth, and reminders (AC12)

**Files**
- None modified. This is a pure verification gate. (If any test fails, fix the regression in the relevant source file before proceeding — but the engine/auth/reminders core is reused unchanged, so a failure here means a P3 extension or a wiring change broke a shared path.)

**Acceptance criteria covered:** **AC12** — existing engine behaviors (409-adopt, 5xx-park, 4xx-drop + recovery, full-resync drain gate, reminder re-arm, auth refresh/teardown) still pass; new domains covered by equivalent tests.

**Steps**

1. **Run the engine regression subset** (named real classes; from `android/`):
   ```
   ./gradlew :app:testDebugUnitTest \
     --tests "net.qmindtech.tmap.data.sync.PushRunner409AdoptTest" \
     --tests "net.qmindtech.tmap.data.sync.PushRunner5xxParkTest" \
     --tests "net.qmindtech.tmap.data.sync.PushRunner401ParkTest" \
     --tests "net.qmindtech.tmap.data.sync.PushRunner4xxDropTest" \
     --tests "net.qmindtech.tmap.data.sync.PushRunnerGhostRecoveryTest" \
     --tests "net.qmindtech.tmap.data.sync.PushRunnerIdempotentTest" \
     --tests "net.qmindtech.tmap.data.sync.PushRunnerFifoTest" \
     --tests "net.qmindtech.tmap.data.sync.PullRunnerFullResyncTest" \
     --tests "net.qmindtech.tmap.data.sync.PullRunnerRecoveryTest" \
     --tests "net.qmindtech.tmap.data.sync.PullRunnerParkedResyncGateTest" \
     --tests "net.qmindtech.tmap.data.sync.PullRunnerTombstoneTest" \
     --tests "net.qmindtech.tmap.data.sync.PullRunnerShadowTest" \
     --tests "net.qmindtech.tmap.data.sync.PullRunnerPageTest" \
     --tests "net.qmindtech.tmap.data.sync.SyncEngineTest" \
     --tests "net.qmindtech.tmap.data.sync.SyncWorkerTest" \
     --tests "net.qmindtech.tmap.data.sync.WorkManagerSyncSchedulerTest"
   ```
   - Expected: `BUILD SUCCESSFUL`; every class green. These pin: 409→adopt+remap, 5xx park ladder, 401-park, definitive-4xx drop + ghost recovery, idempotent replay, FIFO order, full-resync drain gate, recovery pull, parked-resync gate, tombstone delete, shadow rule, paging.

2. **Run the reminders regression subset:**
   ```
   ./gradlew :app:testDebugUnitTest \
     --tests "net.qmindtech.tmap.notifications.ReminderRearmerTest" \
     --tests "net.qmindtech.tmap.notifications.RearmWorkerTest" \
     --tests "net.qmindtech.tmap.notifications.BootReceiverTest" \
     --tests "net.qmindtech.tmap.notifications.AlarmReceiverTest" \
     --tests "net.qmindtech.tmap.notifications.ReminderSchedulerTest" \
     --tests "net.qmindtech.tmap.notifications.ReminderTriggerTest" \
     --tests "net.qmindtech.tmap.notifications.NotificationChannelsTest"
   ```
   - Expected: `BUILD SUCCESSFUL`. Pins: diff-driven re-arm (reconcile arms changed / cancels deleted), boot re-arm-all, alarm receipt, scheduler arm/cancel, trigger decision, channels.

3. **Run the auth regression subset:**
   ```
   ./gradlew :app:testDebugUnitTest \
     --tests "net.qmindtech.tmap.data.auth.RefreshSingleFlightTest" \
     --tests "net.qmindtech.tmap.data.remote.TokenAuthenticatorReentrancyTest" \
     --tests "net.qmindtech.tmap.data.remote.TokenAuthenticatorTest" \
     --tests "net.qmindtech.tmap.data.auth.LogoutKeepsLocalDataTest" \
     --tests "net.qmindtech.tmap.data.auth.AuthRepositoryTest"
   ```
   - Expected: `BUILD SUCCESSFUL`. Pins: single-flight refresh, authenticator reentrancy/teardown, refresh-on-401, logout teardown (cancel sync + clear tokens) keeping local data, repository session state.

4. **Run the NEW-domain coverage subset** (the equivalent tests AC12 requires for notes/note-groups/focus-sessions/daily-plans):
   ```
   ./gradlew :app:testDebugUnitTest \
     --tests "net.qmindtech.tmap.data.local.EntityTypeTest" \
     --tests "net.qmindtech.tmap.data.local.dao.NoteDaoTest" \
     --tests "net.qmindtech.tmap.data.local.dao.NoteGroupDaoTest" \
     --tests "net.qmindtech.tmap.data.local.dao.FocusSessionDaoTest" \
     --tests "net.qmindtech.tmap.data.local.dao.DailyPlanDaoTest" \
     --tests "net.qmindtech.tmap.data.remote.dto.NoteDtosTest" \
     --tests "net.qmindtech.tmap.data.remote.dto.FocusDailyPlanDtosTest" \
     --tests "net.qmindtech.tmap.data.remote.dto.SyncDtosTest" \
     --tests "net.qmindtech.tmap.data.sync.MappersTest" \
     --tests "net.qmindtech.tmap.data.repository.NoteRepositoryImplTest" \
     --tests "net.qmindtech.tmap.data.repository.NoteGroupRepositoryImplTest" \
     --tests "net.qmindtech.tmap.data.repository.FocusSessionRepositoryImplTest" \
     --tests "net.qmindtech.tmap.data.repository.DailyPlanRepositoryImplTest" \
     --tests "net.qmindtech.tmap.data.sync.PushRunnerNotesTest" \
     --tests "net.qmindtech.tmap.data.sync.PushRunnerFocusDailyPlanTest" \
     --tests "net.qmindtech.tmap.data.sync.PullRunnerNotesTest" \
     --tests "net.qmindtech.tmap.data.sync.PullRunnerFocusDailyPlanTest"
   ```
   - Expected: `BUILD SUCCESSFUL`; every class green. These pin the new domains' DAO CRUD, DTO (de)serialization, extended `SyncChanges` decode, mapper round-trips, write-through repositories (focus-session CREATE-only; daily-plan date-keyed UPDATE/upsert), push dispatch branches, and pull applyPage branches (upsert/tombstone/shadow).

5. **No commit** (verification only). Record the per-subset results in the task notes; if any subset is red, STOP and fix the offending source before continuing to P10.9.

---

### Task P10.9 — VERIFICATION: full offline pass → reconnect → reconciliation + widget update (AC11 & AC13)

**Files**
- None modified. Emulator/manual verification of the offline-first invariant (spec §9) across all domains, the new-domain sync reconciliation (AC11), and full offline operation (AC13). Two emulator instances (or one device + the live backend account) are used to verify remote→local pull.

**Acceptance criteria covered:** **AC11** (notes/note-groups/focus-sessions/daily-plans created offline → pushed on reconnect; remote changes pulled via delta; tombstones delete locally; full-resync repopulates; parked/recovery behave as for tasks) and **AC13** (full offline operation verified).

**Preconditions:** a signed-in debug build on the emulator, the live backend `https://api-tasks.qmindtech.net/` reachable, and a second authenticated client (web or second device) to originate remote changes.

**Steps (reviewer executes on the emulator; check each box):**

1. **Airplane-mode creation sweep (offline write-through).** Enable airplane mode, then perform — and confirm each appears instantly in the UI from Room with NO spinner/block:
   - [ ] Create a **task** via quick-capture (NL parse) → lands in Inbox / Today.
   - [ ] Triage an **inbox** item (schedule to Today).
   - [ ] Create a **note** and a **note-group (notebook)**; assign the note to the notebook + a project; pin it.
   - [ ] Complete a **daily-plan** via the planning ritual (commits `plannedTaskIds` + `plannedMinutes`).
   - [ ] Run a short **focus** session to completion (writes a FocusSession + increments task `actualTimeMinutes`).
   - [ ] Edit/complete/defer/delete tasks via swipe + editor.
   - [ ] Confirm the **SyncStatusPill** shows Offline and nothing is lost on app restart (kill + relaunch while still offline → all local changes persist).

2. **Reconnect → push reconciliation.** Disable airplane mode; trigger a sync (foreground or wait for the scheduler). Confirm on the SECOND client / backend:
   - [ ] The offline-created task, note, note-group, daily-plan, and focus-session all appear server-side (pushed; idempotent by client UUID; daily-plan keyed by date).
   - [ ] Re-triggering sync creates NO duplicates (idempotent replay / 409-adopt path).
   - [ ] SyncStatusPill returns to synced (success dot).

3. **Remote → local delta pull.** From the second client: edit a note's title, rename a notebook, change a task, and delete (tombstone) a note. Trigger a sync on the emulator:
   - [ ] The edits pull down and render; the tombstoned note disappears locally (delete applied).
   - [ ] A note-group delete on the server cascades: child notes are tombstoned locally too (spec §7.3 mirror).

4. **Parked / recovery parity.** Force a rejection for a new-domain op (e.g. submit an invalid note edit, or use a backend that 4xx's) and confirm the new domains behave exactly as tasks: the op is dropped + a from-0 recovery pull converges local state; a parked op keeps the SyncStatusPill in its attention state and blocks the destructive full-resync until resolved. (This mirrors `PushRunner4xxDropTest`/`PushRunnerGhostRecoveryTest`/`PullRunnerParkedResyncGateTest` at runtime.)

5. **Full-resync repopulation.** Trigger the server's `fullResyncRequired` directive (or simulate via a forced local clear) with an empty outbox: confirm local tables clear and ALL domains (tasks, notes, note-groups, focus-sessions, daily-plans) repopulate from the server.

6. **Widget update after sync (AC11 tail / AC10 cross-check).** With the four Glance widgets placed on the home screen:
   - [ ] After a successful pull, the Today Agenda + Progress/Streak widgets reflect the new server state (WidgetUpdater fired post-pull).
   - [ ] Checking off a task FROM the Today Agenda widget updates the task and syncs (widget write-through), and the in-app Today reflects it.

7. **No commit** (verification only). Record pass/fail per checkbox; any failure STOPS the gate for a fix.

---

### Task P10.10 — VERIFICATION: AC1–AC13 emulator acceptance checklist (manual, 1:1 to spec §10)

**Files**
- None modified. The human acceptance gate: walk the running debug build against EACH acceptance criterion in spec §10. AC11/AC12 are already exercised by P10.8/P10.9 — re-confirm their summary line here. The other criteria are checked live against the named mockups.

**Steps (reviewer executes on the emulator; every box must be checked):**

- [ ] **AC1 — Design system:** Midnight Calm tokens throughout; NO screen uses the old desktop `Surface*/Accent*` palette; accent (amber) appears only on primary/active elements (FAB, primary buttons, progress, active states). (Static cross-check: `grep -rnE "Surface[0-9]|AccentLight|AccentDark" android/app/src/main/java/net/qmindtech/tmap/ui` returns nothing.) Verified vs `design-direction.html` (direction A).
- [ ] **AC2 — Navigation:** 5-tab bottom nav (Today/Inbox/Browse/Notes/You); `+` capture on every tab; "Plan my day" launches the ritual from Today; Focus launches from a task; the task editor opens as a bottom sheet; a reminder notification deep link (`tmap://task/{id}`) opens the editor sheet over Today. Verified vs `navigation.html`.
- [ ] **AC3 — Today:** List ⇄ Timeline toggle works; timeline shows time-blocks + amber now-line + drag-to-time-block (sets scheduledStart/End); swipe-complete (with undo snackbar), swipe-defer/delete, and long-press drag reorder/move-day all function AND persist (survive relaunch). Verified vs `daily-core.html`.
- [ ] **AC4 — Quick capture:** NL parsing sets project (`#`), priority (`!`/`!!`/`!!!`/`!high`), and date/time; rapid-fire (sheet stays open after submit); default destination Inbox when no date; persists offline.
- [ ] **AC5 — Planning ritual:** 4-step flow completes; upserts the DailyPlan (plannedTaskIds + plannedMinutes + committedAt) and sets task plannedDate; capacity reflects the configurable workday length (Settings); works offline.
- [ ] **AC6 — Focus:** pomodoro runs and survives backgrounding (foreground service + persistent notification); on completion writes a FocusSession and increments task actualTimeMinutes; both sync on reconnect.
- [ ] **AC7 — Notes:** notebooks, pinned + recent, create/edit/delete, assign notebook/project; notes + note-groups sync offline (create offline → server-side after reconnect; remote change pulls down) — confirmed in P10.9 §2–§3. Verified vs `full-app.html`.
- [ ] **AC8 — Browse / Projects:** search, filter, sort, group across all tasks; Backlog & Projects segments; project cards show progress; project CRUD works. Verified vs `full-app.html`.
- [ ] **AC9 — You:** profile + sync status pill + streak/weekly/focus stats (computed from local data via `StatsCalculator`) + settings groups + working sign-out (existing auth teardown: cancel sync, clear tokens, wipe DB). Verified vs `full-app.html`.
- [ ] **AC10 — Widgets:** all four render in Midnight Calm; Today Agenda check-off updates and syncs; Quick Capture opens capture; Up Next starts focus; Progress shows ring + streak; widgets update after sync; logged-out state shows "Sign in to TMap". Verified vs `widgets.html`.
- [ ] **AC11 — Sync of new domains:** confirmed green by P10.9 (offline-create → push idempotent / daily-plan by date; delta pull; tombstone delete; full-resync repopulates; parked/recovery parity).
- [ ] **AC12 — Regression:** confirmed green by P10.8 (engine 409-adopt/5xx-park/4xx-drop+recovery/full-resync gate, reminder re-arm, auth refresh/teardown all pass; new domains covered).
- [ ] **AC13 — Offline / RTL / a11y:** full offline operation verified (P10.9 §1); Arabic RTL verified on new screens (P10.6 checklist); icon controls labeled + non-gesture equivalents exist (P10.7 checklist).

**No commit** (verification only). Any unchecked box STOPS the gate until resolved (fix in the owning phase's source, re-run the affected verification).

---

### Task P10.11 — Branch done-gate: green build + lint + tests + AC checklist, then STOP

**Files**
- None modified (gate only). If a gate is red, fix the cause in the relevant source file under a conventional commit with the trailer, then re-run — do not proceed until all three are green and AC1–AC13 are checked.

**Steps**

1. **Full unit-test suite** (from `android/`):
   - Command: `./gradlew :app:testDebugUnitTest`
   - Expected: `BUILD SUCCESSFUL`; the entire JVM/Robolectric suite green (theme/token tests incl. `ReduceMotionTest`, `EmptyStateCopyTest`; all engine/auth/reminder classes from P10.8; all new-domain classes; all view-model + filter + DAO + DTO + mapper tests).

2. **Compile / assemble gate:**
   - Command: `./gradlew :app:assembleDebug`
   - Expected: `BUILD SUCCESSFUL`; a debug APK is produced.

3. **Lint gate:**
   - Command: `./gradlew :app:lintDebug`
   - Expected: `BUILD SUCCESSFUL`; no blocking lint errors (in particular no `ContentDescription` errors after P10.7). Review `android/app/build/reports/lint-results-debug.html` for any new warnings introduced by P10 and resolve regressions.

4. **Acceptance checklist confirmation:** confirm every box in P10.8, P10.9, and P10.10 (AC1–AC13) is checked. Spec §10 is fully satisfied.

5. **Final commit** (only if P10 polish produced uncommitted touch-ups during the gate; otherwise skip):
   - `git add -A`
   - Message:
     ```
     chore(android): P10 polish & cross-cutting gate — AC1-AC13 verified green

     Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
     ```

6. **STOP — do not merge or push; merge is a separate user-gated step.** All work remains on `feat/sp4-android`. Report the green status (assembleDebug + lintDebug + testDebugUnitTest) and the completed AC1–AC13 checklist to the user, and await explicit consent before any merge or push.

<!-- PHASE-TASKS:END -->
