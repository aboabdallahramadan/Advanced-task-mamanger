# SP4 Android — Experience Redesign (Design Spec)

- **Date:** 2026-06-21
- **Status:** Draft (awaiting user review)
- **Branch / worktree:** `feat/sp4-android`
- **Supersedes the UI layer of:** `docs/superpowers/specs/2026-06-18-sp4-android-native-app-design.md` (the offline engine, auth, and reminders designed there are **kept**; only the experience layer is redesigned and the feature set is expanded).

---

## 1. Why this exists

The first SP4 build shipped a correct offline engine but a bare, functional UI. The user's verdict was blunt and repeated: *"the app is too bad, it too poor, functionality, the UI, everything I don't like it at all."* Root cause: all rigor went into the invisible sync/auth/reminders engine; the screens were built to a minimal spec with no design pass and a thin feature set.

This spec defines a **full experience redesign** to a **best-in-class mobile-planner bar** (Things 3 / Sunsama / Akiflow calibre), expanding the feature set to near-parity with the desktop app. The data/sync/auth/reminders foundation is reused unchanged; the experience layer on top is rebuilt, and four new synced domains are added.

**Design decisions locked with the user (via visual mockups):**

1. **Visual language — "Midnight Calm":** warm, spacious, editorial **dark** theme with a soft amber accent.
2. **Navigation — "Daily-first":** 5-tab bottom navigation (Today · Inbox · Browse · Notes · You); corner **+** quick-capture on every tab; planning ritual launched from the Today header; focus launched from any task.
3. **Daily interaction model:** Today with a **List ⇄ Timeline** toggle; quick-capture bottom sheet with **natural-language parsing**; **swipe gestures** (right = complete, left = defer/delete); **long-press drag** to reorder / move days; **tap = bottom-sheet editor**.
4. **Full feature set:** planning ritual, day timeline / time-blocking, focus (pomodoro), and notes — in addition to the elevated Today / Inbox / Backlog / All-Tasks / Projects / Settings.
5. **Native home-screen widgets** (Jetpack Glance): Today Agenda (resizable), Quick Capture (1-row), Up Next / Focus (2×2), Progress & Streak (2×2) — all four selected.

---

## 2. Scope

### In scope

- A new **design system** (Midnight Calm) replacing the current desktop-mirrored palette.
- **Redesign of every existing screen** and the navigation model.
- **New synced domains:** Notes, Note-groups, Focus-sessions, Daily-plans — Room entities, DAOs, repositories, DTOs, sync wiring, and API endpoints.
- **New feature surfaces:** Planning ritual, Today Timeline / time-blocking, Focus mode, Notes, Browse hub, richer Projects, Quick-capture with NL parsing, swipe/drag gestures, bottom-sheet editor, profile/stats/settings.
- **Glance widgets** (4) + their data providers and update wiring.
- Motion / transitions, empty states, and micro-interactions consistent with the bar.

### Out of scope (deferred, unchanged from v1 deferrals)

- **Recurrence authoring UI.** Recurring instances generated server-side already sync down and display normally; the client will not yet offer UI to *create/edit* recurrence rules. (`RecurrenceRules` will be tolerated in the sync payload — see §7.5.)
- **Reports / analytics dashboards** beyond the lightweight streak + weekly stats on the You screen.
- **Weekly board** multi-day drag view (desktop has it; not in this mobile pass).
- **Light theme.** Dark-only, consistent with the brand and the desktop app. (Midnight Calm is a dark language; a light variant is a future option, not this pass.)
- **Voice capture transcription engine** — the mic affordance on the Quick Capture widget/sheet opens the system speech-to-text input; we do not build a custom recognizer.

### Reused unchanged (the engine)

The following are kept as-is and are **not** redesigned (only extended where new domains require new branches):

- Auth: `AuthRepository(Impl)`, `KeystoreTokenStore`, `TokenAuthenticator`, `AuthInterceptor`, `SessionState`.
- Sync engine core: `SyncEngine`, `PushRunner`, `PullRunner`, `OutboxRepository`, `WorkManagerSyncScheduler`, `SyncWorker` (its FIFO push, 409-adopt / 5xx-park / 4xx-drop+recovery, overlap-cursor delta pull, shadow rule, full-resync drain gate).
- Reminders: `AlarmReminderScheduler`, `ReminderRearmer`, `AlarmReceiver`, `BootReceiver`, `NotificationChannels`, `RearmWorker`.
- Build/DI scaffolding: Hilt modules, Room setup, version catalog (extended with Glance).

---

## 3. What stays · what's rebuilt · what's new

| Layer | Disposition |
|---|---|
| Room engine (Task/Subtask/Project/Settings/Outbox/SyncState), sync, auth, reminders, DI, build | **Stays.** Extended only to register new entities/DAOs/EntityTypes. |
| `ui/theme/{Color,Theme,Type}.kt` (desktop-mirrored palette) | **Rebuilt** as the Midnight Calm design system + new spacing/shape/motion tokens and a component library. |
| `ui/navigation/{Routes,TmapApp,BottomNavItem}.kt` | **Rebuilt** for the Daily-first 5-tab IA + new destinations + bottom-sheet editor + ritual/focus routes. |
| Today / Inbox / Backlog / AllTasks / Projects / Settings / TaskEditor screens + components | **Rebuilt** to the new design and interaction model. Backlog + AllTasks + Projects consolidate under **Browse**. TaskEditor becomes a bottom sheet. |
| Notes, Note-groups, Focus-sessions, Daily-plans (data + UI) | **New.** Entities, DAOs, repos, DTOs, sync wiring, endpoints, and screens (Notes, Planning ritual, Focus mode). |
| Glance widgets | **New** module + 4 widgets + providers + manifest receivers. |

---

## 4. Design system — "Midnight Calm"

Implemented as Compose design tokens (a `theme/` package): a custom `TmapColors`, `TmapShapes`, `TmapSpacing`, `TmapType`, and motion specs, exposed via `CompositionLocal`s and layered over Material 3 where convenient. Dark-only.

### 4.1 Color tokens

| Token | Value | Use |
|---|---|---|
| `bgTop` / `bgBottom` | `#191A20` → `#141519` | App background (subtle vertical gradient) |
| `surface` | `#202127` | Cards, list rows |
| `surfaceRaised` | `#23242B` | Bottom sheets, dialogs, segmented containers |
| `surfaceInset` | `#1C1D23` | Search fields, toggles, subtle wells |
| `borderSubtle` / `borderStrong` | `#2A2B31` / `#34353C` | Hairlines, dividers, outlines |
| `textPrimary` | `#ECEAE4` | Titles, primary content (warm off-white) |
| `textSecondary` | `#908E86` | Meta, secondary |
| `textTertiary` | `#76746D` | Labels, hints |
| `accent` / `accentGradient` | `#E8A87C` → `#E0936A` | The single accent (amber). FABs, primary buttons, progress, active states |
| `onAccent` | `#1A1208` | Text/icon on amber |
| `success` | `#38D39F` (grad `#2F7D5B→#38D39F`) | Completion, sync-ok |
| `danger` | `#F0A0A0` | Destructive / sign-out |
| Project palette | Work `#6EA8FE`, Personal `#38D39F`, Health `#F0A868`, Ideas/Side `#C9A0FF`, Learning `#F0A0A0` | Project dots/stripes/swatches. (Projects store their own color; these are the defaults/legend.) |

Accent is used **sparingly** — it marks the one primary action or the live/active element on a screen, never as a fill for ordinary content.

### 4.2 Type, shape, spacing, motion

- **Type scale** (system font, weights 300–700): Display 40/300 (home clock), Title 25/600 (screen titles), Heading 19–20/600, Body 14.5/500, Meta 12/—, Label 11/700 uppercase + letter-spacing for section headers.
- **Shape:** cards/rows 18dp, sheets 26dp (top corners), pills/chips 999dp, buttons 13–14dp, small wells 12dp.
- **Spacing:** 4dp base; common rhythm 8/10/14/16/20/22dp; screen horizontal padding 16–20dp; generous vertical breathing room (calm, not dense).
- **Motion:** standard 200–250ms ease for sheet/nav transitions; spring on swipe/drag; check-off has a brief satisfying scale + color fill; respects "reduce motion" / animator-duration system setting.
- **Elevation:** soft, diffuse shadows on raised surfaces and the FAB only; flat surfaces otherwise (calm, editorial).

### 4.3 Component library (Compose)

Reusable composables (rebuild the `ui/components/` set + add): `TaskCard` (checkbox, title, project dot, time, subtask progress, priority flag, reminder glyph, swipeable container with undo), `TimeBlock` (timeline card with project-colored left bar + duration height), `SectionLabel`, `ProjectDot` / `ProjectSwatch` / `ProjectProgressCard`, `Chip` / `FilterChip` / `SegmentedControl`, `QuickCaptureSheet`, `TaskEditorSheet`, `NoteCard`, `InboxItemCard` (with one-tap triage actions), `StatTile`, `ProgressRing`, `Fab`, `BottomNav`, `EmptyState` (per-surface calm illustrations/copy), `SyncStatusPill`.

---

## 5. Information architecture & navigation

**Bottom navigation (5 tabs):** **Today** · **Inbox** · **Browse** · **Notes** · **You**.

- **Quick capture:** a corner **+** FAB present on every tab → opens the Quick-capture bottom sheet.
- **Planning ritual:** launched from the Today header ("Plan my day"). Full-screen guided flow.
- **Focus:** launched from any task (and from the Today header / Up-Next widget). Immersive screen.
- **Browse** consolidates the libraries via a segmented control: **All Tasks · Backlog · Projects** (plus search, filter, sort, group).
- **You** holds profile, sync status, streak/weekly stats, and settings (Notifications & reminders, Appearance, Account, Data & sync, About, Sign out).
- **Task editor** is a **bottom sheet** reachable from any task tap (not a full page).
- **Deep link** `tmap://task/{taskId}` (from a reminder notification) opens the editor sheet over Today — reuses the existing manifest intent filter.

`Routes`/`TmapApp`/`BottomNavItem` are rebuilt accordingly; the auth gate (`SessionState`) and splash flow are preserved.

---

## 6. Screens & interactions

Each screen below was approved as a mockup. Behavior notes focus on what the engineering must satisfy.

### 6.1 Today (List + Timeline)
- Header: date eyebrow, greeting, **List ⇄ Timeline** segmented toggle, progress (done/total + time-left), **Plan my day** and **Focus** entry buttons.
- **List mode:** tasks grouped (e.g., This morning / afternoon / evening, derived from `scheduledStart`; undated-but-planned in a plain group). Rich `TaskCard`s.
- **Timeline mode:** vertical hour rail; tasks rendered as `TimeBlock`s positioned by `scheduledStart`/duration with project-colored left bar; an **amber now-line**; empty slots accept **drag-to-time-block** (sets `scheduledStart`/`scheduledEnd`).
- Gestures: swipe-right complete (with undo snackbar), swipe-left defer/delete, long-press drag to reorder (`rank`) or move to another day (`plannedDate`).
- Data: tasks with `plannedDate == today`. The day's `DailyPlan` (if present) informs ordering of the planned set.

### 6.2 Quick capture (the +)
- Bottom sheet, instant. Single text field + send.
- **Natural-language parsing** (client-side): `#project` → project match/create; `!`/`!!`/`!!!` or `!high` → priority; date/time phrases ("tomorrow 3pm", "fri", "today") → `plannedDate` + `scheduledStart`. Parsed tokens render as inline chips; unparsed text is the title.
- One-tap chips: Today / Inbox / Priority / Remind.
- Stays open after submit for rapid-fire capture; each submit writes through the repository → outbox.
- Default destination when no date given: **Inbox** (`status = Inbox`).

### 6.3 Task editor (bottom sheet)
- Fields: title, notes, subtasks (add/complete/reorder/delete), project, planned date, scheduled start/end + duration, due date, priority, reminder (minutes-before), status. Delete + complete actions.
- All writes go through repositories (write-through → outbox), identical to today's persistence contract.

### 6.4 Planning ritual ("Plan my day")
- Guided 4-step full-screen flow: **Reflect on yesterday** (show yesterday's done/undone; no persisted journal — backend `DailyPlan` has no reflection field) → **Triage inbox** (one-tap schedule/backlog/project/delete) → **Pick today** (carry-over unfinished + inbox items; tap **+ Add** to plan; live capacity "≈ Xh planned of your Yh") → **Timebox** (assign times on the timeline).
- Persistence: completing the ritual upserts the **DailyPlan** for the date (`PUT /daily-plans/{date}` semantics via outbox): `plannedTaskIds` (ordered) + `plannedMinutes` + `committedAt`. Adding a task to "today" also sets the task's `plannedDate = today` (and status) through the task repository.
- Capacity target ("your 6h") comes from a Setting (default workday minutes), editable in Settings.

### 6.5 Focus mode (pomodoro)
- Immersive screen bound to a task: amber progress ring, remaining time, session dots (e.g., 2 of 4), Pause / mark-done / end controls; optional queued tasks for back-to-back sessions.
- On completion of a focus interval: create a **FocusSession** (`POST /focus-sessions`, append-only) with `taskId`, `project` (name snapshot), `startedAt`, `endedAt`, `minutes`, `date`; and **increment the task's `actualTimeMinutes`** locally + push (backend does not auto-aggregate). Timer runs via a foreground service or coroutine tied to the screen with a persistent notification so it survives backgrounding (implementation detail for the plan).

### 6.6 Inbox
- Captured items (`status = Inbox`) as cards, each with one-tap triage chips: **Today** (schedule), **Backlog**, **+ Project**, **🗑** — plus swipe gestures. Calm "Inbox Zero" affordance. Count in header.

### 6.7 Browse (libraries hub)
- Search field (full-text over title/notes), segmented **All Tasks · Backlog · Projects**, and filter/sort/group controls: filter by status/priority/project/date-range; sort by due/created/priority/manual; group by project/status/priority/none. (Reuses and extends the existing `TaskFilter` logic — the v1 fix already wired most of these.)

### 6.8 Projects
- Color-coded project cards with per-project progress (done/total) bars; **+ New**; tap → project detail (its tasks + notes). Create/edit modal (name, color, emoji). CRUD via existing `ProjectRepository`.

### 6.9 Notes (new)
- Notebooks (note-groups) as a chip row; **Pinned** + **Recent** note cards with title + snippet + project dot + edited-time; **+** FAB creates a note. Tap → note editor (title + content; assign notebook/project; pin). Pin state: see §7.6.

### 6.10 You (profile / stats / settings)
- Avatar (initials), name, email, **sync status pill**; stat tiles (day streak, done-this-week, focus-this-week — computed locally from tasks + focus-sessions); grouped Settings list; Sign out. Sign-out uses the existing auth teardown (cancel sync, clear tokens, wipe DB).

---

## 7. New synced domains — data & sync

All four domains are **verified to participate in the backend `/sync` delta** (each row carries `changeSeq` + `deletedAt` tombstone), so they use the **same Room + outbox + delta-pull pattern** as tasks. The sync engine is currently **hardcoded per `EntityType`**, so each new domain extends the enum and the push/pull dispatch.

### 7.1 Engine extension points (per new domain)
1. New `EntityType` enum value(s).
2. New Room `@Entity` + table + `@Dao`; register in `AppDatabase` (bump version; `fallbackToDestructiveMigration` already on — destructive local reset is acceptable because the server is the source of truth and a full-resync repopulates).
3. New DTOs: `Create/Update*Request`, `*Response`, and `*SyncRow` (mirroring §7 contracts).
4. `PushRunner.dispatch()` branch (CREATE/UPDATE/DELETE/REORDER as applicable).
5. `PullRunner.applyPage()` branch (upsert on present, delete on `deletedAt`, shadow-rule honored).
6. New `*Repository` (write-through → `OutboxRepository.enqueue`) + read flows for the UI.
7. New `TmapApiService` endpoints.
8. Extend the Kotlin `SyncChanges` DTO with `notes`, `noteGroups`, `focusSessions`, `dailyPlans` lists.

### 7.2 Notes
- Endpoints: `GET /notes` (`?groupId&projectId`), `GET /notes/{id}`, `POST /notes`, `PATCH /notes/{id}`, `DELETE /notes/{id}`, `PATCH /notes/reorder`.
- Fields: `id`, `groupId?`, `projectId?`, `title`, `content`, `rank` (lexorank string), `createdAt`, `updatedAt`, (+sync `changeSeq`, `deletedAt`).
- Ops: CREATE/UPDATE/DELETE/REORDER. Standard.

### 7.3 Note-groups
- Endpoints: `GET /note-groups` (`?projectId`), `POST`, `PATCH /{id}`, `DELETE /{id}` (cascades soft-delete to child notes — client mirrors by also tombstoning children on pull), `PATCH /note-groups/reorder`.
- Fields: `id`, `name`, `emoji`, `projectId?`, `rank`, timestamps (+sync fields).
- Ops: CREATE/UPDATE/DELETE/REORDER. Standard.

### 7.4 Focus-sessions (append-only)
- Endpoint: **`POST /focus-sessions` only** (no GET/PATCH/DELETE). Synced via `/sync`.
- Fields: `id`, `taskId?`, `project` (name string snapshot, not FK), `startedAt`, `endedAt`, `minutes`, `date` (DateOnly), timestamps (+sync fields).
- Ops: **CREATE only.** No update/delete ops are ever enqueued. Pull still applies upserts/tombstones from the server.

### 7.5 Recurrence-rules (tolerated, not authored)
- `/sync` includes `RecurrenceRules`. We add a Kotlin `RecurrenceRuleSyncRow` field to `SyncChanges` so the payload deserializes cleanly, and (minimally) store rules read-only if needed to render recurring task badges. No authoring UI this pass. (kotlinx `ignoreUnknownKeys = true` already prevents breakage even if unmodeled.)

### 7.6 Daily-plans (date-keyed upsert)
- Endpoints: `GET /daily-plans/{date}`, `PUT /daily-plans/{date}` (upsert; **last-writer-wins**, full `plannedTaskIds` replacement).
- Fields: `date` (DateOnly, **primary key**, not a Guid), `committedAt`, `plannedTaskIds: List<Guid>`, `plannedMinutes`, (+sync `changeSeq`, `deletedAt`).
- **Special outbox handling:** keyed by `date` string (not a Guid id); the only op is an **UPSERT** (model as `OpType.UPDATE` with `entityId = date`). No id remap/adopt needed (date is the natural key). Pull applies by date.
- **Open design note (§11):** Daily-plan, focus-session, and the others must be handled by the FIFO outbox; the engine's id-based dedup/remap assumes Guid ids. Daily-plan keyed-by-date and focus-session append-only need explicit, tested branches rather than reusing the Guid path verbatim.

### 7.7 Notes on `pinned`
The backend `NoteResponse` has **no `pinned` field** (only `id, groupId, projectId, title, content, rank, timestamps`). "Pinned" in the Notes mockup is therefore a **client-only / convention** state. Decision: represent pin as a **local-only Room column** that does not sync (a `pinnedAt` on the note entity, never sent to the server), OR drop pinning to stay fully server-backed. **Recommended: local-only `pinnedAt`** (pins are a personal, device-reasonable affordance; not syncing them is acceptable and avoids a backend change). Flagged for user confirmation in §11.

---

## 8. Widgets (Jetpack Glance)

- Add `androidx.glance:glance-appwidget` to the version catalog; new `widget/` package; `AppWidgetProvider`/`GlanceAppWidgetReceiver` declared in the manifest (currently none exist).
- **Four widgets:**
  1. **Today Agenda** (resizable) — today's tasks; **check-off action** updates the task via the repository (so it syncs); tap row → deep-link into the app; respects List ordering.
  2. **Quick Capture** (1-row) — tap → launches the capture sheet (deep link / trampoline activity); mic → system speech-to-text into the same capture flow.
  3. **Up Next / Focus** (2×2) — next task + **Start Focus** deep link; shows a live countdown when a focus session is running.
  4. **Progress & Streak** (2×2) — today's completion ring + day-streak (computed from local data).
- **Data source:** widgets read the **same Room DB** (via a widget-side repository/`GlanceStateDefinition` or direct DAO query in the provider's coroutine). **Update triggers:** after each successful sync (`SyncWorker`/`PullRunner` post a widget-update) and after local writes that affect today; periodic update as a fallback. No network in the widget — Room only.
- Widgets honor Midnight Calm (dark, amber) using Glance color providers; degrade gracefully when logged out (a "Sign in to TMap" state).

---

## 9. Non-functional requirements

- **Offline-first:** every screen reads from Room and works fully offline; all mutations are write-through to the outbox and reconciled by the existing sync engine. No screen blocks on the network.
- **Dark-only**, Midnight Calm, across app + widgets + notifications.
- **RTL:** preserve the existing bidirectional support; all new layouts use start/end (not left/right) and mirror correctly (Arabic verified).
- **Accessibility:** content descriptions on icon-only controls; swipe actions have non-gesture equivalents (editor + long-press menu); minimum 4.5:1 contrast for text on surfaces (the palette is chosen to meet this); honor system font scale and reduce-motion.
- **Performance:** lazy lists with stable keys; timeline and Browse handle large datasets; check-off and capture feel instant (optimistic local write, sync in background).
- **Min/target SDK 26/35**, AGP 8.7.3, Kotlin 2.0.21, JDK 21 (unchanged). Glance requires no min-SDK change.

---

## 10. Acceptance criteria

1. **Design system:** Midnight Calm tokens implemented; no screen uses the old desktop-mirrored palette; accent appears only on primary/active elements.
2. **Navigation:** 5-tab bottom nav (Today/Inbox/Browse/Notes/You); + capture on every tab; ritual from Today; focus from a task; editor as a bottom sheet; reminder deep link opens the editor.
3. **Today:** List ⇄ Timeline toggle works; timeline shows time-blocks + now-line + drag-to-time-block; swipe-complete (with undo), swipe-defer/delete, long-press drag reorder/move-day all function and persist.
4. **Quick capture:** NL parsing sets project/priority/date-time; rapid-fire capture; default-to-Inbox; persists offline.
5. **Planning ritual:** 4-step flow completes; upserts the DailyPlan (plannedTaskIds + plannedMinutes + committedAt) and sets task plannedDate; capacity reflects a configurable workday length; works offline.
6. **Focus:** pomodoro runs and survives backgrounding; on completion writes a FocusSession and increments task actualTimeMinutes; both sync.
7. **Notes:** notebooks, pinned + recent, create/edit/delete, assign notebook/project; notes + note-groups sync offline (create offline → appears server-side after reconnect; remote change pulls down).
8. **Browse / Projects:** search, filter, sort, group across all tasks; backlog & projects segments; project cards show progress; project CRUD works.
9. **You:** profile + sync status + streak/weekly stats (from local data) + settings + working sign-out (existing teardown).
10. **Widgets:** all four render in Midnight Calm; Today Agenda check-off updates and syncs; Quick Capture opens capture; Up Next starts focus; Progress shows ring + streak; widgets update after sync; logged-out state handled.
11. **Sync of new domains:** Notes, Note-groups, Focus-sessions, Daily-plans each: created offline → pushed on reconnect (idempotent by client id; daily-plan by date); remote changes pulled via delta; tombstones delete locally; full-resync repopulates them; parked/recovery paths behave as for tasks.
12. **Regression:** existing engine behaviors (409-adopt, 5xx-park, 4xx-drop + recovery, full-resync drain gate, reminder re-arm, auth refresh/teardown) still pass their tests; new domains are covered by equivalent tests.
13. **Offline / RTL / a11y:** full offline operation verified; Arabic RTL verified on the new screens; icon controls labeled; non-gesture equivalents exist.

---

## 11. Open questions / risks (to resolve in planning)

1. **Pinned notes** (§7.7): confirm **local-only `pinnedAt`** (recommended) vs. dropping pin. — *Default if no objection: local-only.*
2. **Daily-plan & focus-session outbox modeling** (§7.6): these break the Guid-id/CREATE-UPDATE-DELETE assumption (date-keyed upsert; append-only). The plan must define explicit, tested outbox branches rather than forcing them through the task path.
3. **Focus timer mechanism:** foreground service vs. screen-scoped coroutine + persistent notification — decide in planning for reliable backgrounding without over-engineering.
4. **Widget update cadence:** confirm the trigger set (post-sync broadcast + local-write trigger + periodic fallback) is enough without excessive wakeups.
5. **Destructive Room migration:** bumping the schema for new tables uses `fallbackToDestructiveMigration` (wipes local, then full-resync). Acceptable because the server is authoritative — confirm no unsynced local-only data (e.g., local `pinnedAt`) is lost in a way that matters. (Pins are cosmetic; acceptable.)
6. **Scope size:** this is large. The implementation plan should phase it: (P0) design system + nav shell; (P1) Today list/capture/gestures/editor; (P2) Browse/Projects; (P3) new-domain engine extension (notes/focus/daily-plan data+sync); (P4) Notes UI; (P5) Planning ritual; (P6) Focus; (P7) Timeline/time-blocking; (P8) Widgets; (P9) You/stats; (P10) polish/motion/empty-states + full offline/RTL/a11y gate.

---

## 12. Reference — approved mockups

Browser mockups produced and approved during brainstorming live under `.superpowers/brainstorm/<session>/content/` (git-ignored): `design-direction.html` (A·Midnight Calm chosen), `navigation.html` (1·Daily-first chosen), `daily-core.html`, `full-app.html`, `widgets.html`. They are the visual source of truth for this spec.
