# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**TMap** — a desktop daily planning and task management app (Electron + React). Think Sunsama clone with time blocking, recurring tasks, focus mode, and a planning ritual.

## Build & Development Commands

```bash
npm run dev        # Vite dev server + Electron in dev mode
npm run build      # TypeScript compile + Vite bundle + Electron build
npm run package    # Build + electron-builder (Windows NSIS installer)
npm run format     # Prettier formatting
```

No test framework is configured — there are no unit or e2e tests.

## Tech Stack

- **Frontend:** React 18, TypeScript (strict), Zustand, Tailwind CSS, Radix UI, @dnd-kit, lucide-react, date-fns
- **Backend:** Electron 33, SQL.js (SQLite via WASM), IPC bridge
- **Build:** Vite 6 with vite-plugin-electron, electron-builder
- **Path alias:** `@/` → `./src/`

## Architecture

### Two-process model (Electron)

- **Main process** (`electron/`): Window management, database, IPC handlers, tray, notifications, focus widget
- **Renderer process** (`src/`): React SPA with Zustand store

### Data flow

`React Component → Zustand action → IPC call (window.api.*) → Electron handler → SQL.js → response back`

### Key files

| File | Purpose |
|------|---------|
| `src/store.ts` | Centralized Zustand store (~730 lines, 100+ actions) — source of truth for all app state |
| `src/types.ts` | TypeScript interfaces (Task, Project, Subtask, etc.) |
| `electron/database.ts` | SQL.js setup, schema migrations, all table definitions |
| `electron/taskService.ts` | Task CRUD operations (~450 lines) |
| `electron/projectService.ts` | Project CRUD |
| `electron/recurrenceUtils.ts` | Recurring task instance generation |
| `electron/main.ts` | App init, window creation, IPC handler registration, tray |
| `electron/preload.ts` | IPC bridge — exposes `window.api` to renderer |

### Component structure

- `src/components/` — 19 components, flat structure except:
  - `planning/` — 3-step planning flow (ReviewYesterday → TriageInbox → Timebox)
  - `focus/` — Focus mode overlay with pomodoro timer
- Main views: `TaskList`, `DayTimeline`, `WeeklyBoardView`, `AllTasksView`, `ProjectView`
- `TaskDetailDialog.tsx` (~420 lines) is the full task editor modal

### Database (SQL.js / SQLite)

Tables: `tasks`, `projects`, `subtasks`, `recurrence_rules`, `recurrence_exceptions`, `migrations`

Task status flow: `inbox → backlog | planned → scheduled → done | archived`

Settings stored as JSON in `~/userData/settings.json`, not in the database.

### State management pattern

Single Zustand store owns all state. Components read via selectors, mutate via store actions. Every store action calls `window.api.*` (IPC) to persist, then updates local state.

## Code Conventions

- **Formatting:** Prettier — 100 char width, single quotes, trailing commas, 2-space indent
- **Dark mode:** Always-on dark theme via Tailwind `darkMode: 'class'`
- **Custom Tailwind colors:** `surface-*` (50–950), `accent`, `success`, `warning`, `danger`
- **RTL support:** `useTextDirection` hook for bidirectional text
- **Frameless window:** Custom titlebar overlay (Windows-style)
- **TypeScript:** Strict mode, ES2022 target, separate tsconfig for electron (`tsconfig.electron.json`)
