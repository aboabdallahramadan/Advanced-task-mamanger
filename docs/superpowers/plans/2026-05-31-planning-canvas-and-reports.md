# Planning Canvas Redesign + Reports — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the read-only 3-step planning wizard with a guided 3-column "planning canvas" that produces a committed, time-blocked day, and add a Reports view (throughput + time-by-project) backed by newly captured data.

**Architecture:** Electron (main) + React/Zustand (renderer) over SQL.js. A new migration adds `completed_at` to tasks plus `focus_sessions` and `daily_plans` tables. New main-process services expose `focusSessions`/`dailyPlans`/`reports` IPC namespaces (mirroring the existing `tasks` pattern). All report/capacity/date math lives in pure, unit-tested modules under `src/lib/`. The renderer fetches raw rows per date range and aggregates with those pure functions. The planning canvas and reports view are new top-level React components following existing conventions.

**Tech Stack:** React 18, TypeScript (strict), Zustand, Tailwind, @dnd-kit, date-fns, **recharts** (new), **Vitest** (new), Electron 33, SQL.js.

**Conventions for every commit:** end the commit message with the trailer:
```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```
Work happens on the existing branch `feature/planning-canvas-and-reports`.

---

## File map

**Create:**
- `vitest.config.ts` — test runner config isolated from `vite-plugin-electron`
- `src/lib/focusSession.ts` + `src/lib/focusSession.test.ts` — session-minute rounding
- `src/lib/capacity.ts` + `src/lib/capacity.test.ts` — planning capacity math
- `src/lib/dateRange.ts` + `src/lib/dateRange.test.ts` — report range/day math
- `src/lib/reports.ts` + `src/lib/reports.test.ts` — throughput / time-by-project / summary aggregation
- `electron/focusSessionService.ts`, `electron/dailyPlanService.ts`, `electron/reportService.ts`
- `src/components/planning/PlanningCanvas.tsx`
- `src/components/ReportsView.tsx`

**Modify:**
- `package.json`, `tsconfig.json`
- `electron/database.ts` (migration), `electron/taskService.ts` (completed_at)
- `electron/main.ts`, `electron/preload.ts` (IPC)
- `src/types.ts` (entities, `ViewMode`, `PlanningPhase`, `ElectronAPI`)
- `src/store.ts` (focus logging, rollover fix, planning state/actions/selectors, reports slice)
- `src/components/TaskItem.tsx` ("Plan for today")
- `src/App.tsx` (mount canvas + reports branch)
- `src/components/Sidebar.tsx` (Reports nav)

**Delete (after canvas works):**
- `src/components/planning/PlanningFlowOverlay.tsx`, `StepReviewYesterday.tsx`, `StepTriageInbox.tsx`, `StepTimebox.tsx`

---

## Task 1: Tooling — add Recharts + Vitest

**Files:**
- Modify: `package.json`
- Modify: `tsconfig.json`
- Create: `vitest.config.ts`
- Create: `src/lib/sanity.test.ts` (temporary, removed in Task 2)

- [ ] **Step 1: Add the runtime + dev dependencies**

Run (from repo root):
```bash
npm install recharts
npm install -D vitest@^3
```
Expected: `recharts` appears in `dependencies`, `vitest` in `devDependencies`. (recharts ships its own types; no `@types/recharts`. All tests are pure-logic, so no jsdom/`@testing-library` is needed.)

- [ ] **Step 2: Add test scripts to `package.json`**

In `package.json` `scripts` (currently):
```json
"scripts": {
    "dev": "vite",
    "build": "tsc && vite build && tsc -p tsconfig.electron.json",
    "preview": "vite preview",
    "package": "npm run build && electron-builder",
    "format": "prettier --write \"**/*.{ts,tsx,css,json}\""
},
```
Add `test` and `test:run`:
```json
"scripts": {
    "dev": "vite",
    "build": "tsc && vite build && tsc -p tsconfig.electron.json",
    "preview": "vite preview",
    "package": "npm run build && electron-builder",
    "format": "prettier --write \"**/*.{ts,tsx,css,json}\"",
    "test": "vitest",
    "test:run": "vitest run"
},
```

- [ ] **Step 3: Create `vitest.config.ts`** (isolated from the Electron plugin)

```ts
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
    plugins: [react()],
    resolve: {
        alias: {
            '@': path.resolve(__dirname, './src'),
        },
    },
    test: {
        environment: 'node',
        include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
    },
});
```
> Vitest auto-prefers `vitest.config.ts` over `vite.config.ts`, so `vite-plugin-electron` is never loaded during tests. Tests import `{ describe, it, expect }` from `'vitest'` explicitly (no globals), so no tsconfig `types` array is needed.

- [ ] **Step 4: Exclude test files from the production typecheck/build**

In `tsconfig.json`, add an `exclude` array (the file currently has only `include` and `references`). Insert it as a sibling of `include`:
```json
    "include": [
        "src/**/*",
        "src/**/*.d.ts"
    ],
    "exclude": [
        "src/**/*.test.ts",
        "src/**/*.test.tsx"
    ],
    "references": [
        {
            "path": "./tsconfig.electron.json"
        }
    ]
```

- [ ] **Step 5: Add a temporary sanity test** `src/lib/sanity.test.ts`

```ts
import { describe, it, expect } from 'vitest';

describe('vitest wiring', () => {
    it('runs', () => {
        expect(1 + 1).toBe(2);
    });
});
```

- [ ] **Step 6: Run the test suite**

Run: `npm run test:run`
Expected: PASS — 1 test file, 1 passing test.

- [ ] **Step 7: Confirm the production build still works**

Run: `npm run build`
Expected: completes with no errors (recharts present, tests excluded from `tsc`).

- [ ] **Step 8: Commit**

```bash
git add package.json package-lock.json tsconfig.json vitest.config.ts src/lib/sanity.test.ts
git commit -m "chore: add recharts + vitest tooling"
```

---

## Task 2: Types — entities, ViewMode, PlanningPhase, ElectronAPI

**Files:**
- Modify: `src/types.ts`
- Delete: `src/lib/sanity.test.ts`

- [ ] **Step 1: Add `completedAt` to the renderer `Task` interface**

In `src/types.ts`, the `Task` interface ends:
```ts
    createdAt: string;
    updatedAt: string;
}
```
Change to:
```ts
    createdAt: string;
    updatedAt: string;
    completedAt?: string | null;
}
```

- [ ] **Step 2: Add `'reports'` to `ViewMode`**

Current (line ~53):
```ts
export type ViewMode = 'today' | 'tomorrow' | 'week' | 'inbox' | 'backlog' | 'board' | 'project' | 'all' | 'noteGroup' | 'noteEditor' | 'allNotes';
```
Change to:
```ts
export type ViewMode = 'today' | 'tomorrow' | 'week' | 'inbox' | 'backlog' | 'board' | 'project' | 'all' | 'noteGroup' | 'noteEditor' | 'allNotes' | 'reports';
```

- [ ] **Step 3: Add new types** — add this block immediately after the `ViewMode` line:

```ts
export type PlanningPhase = 'review' | 'choose' | 'timebox' | 'commit';

export interface FocusSession {
    id: string;
    taskId: string | null;
    project: string; // project NAME at session time ('' if none)
    startedAt: string; // ISO
    endedAt: string; // ISO
    minutes: number;
    date: string; // YYYY-MM-DD (local day of startedAt)
    createdAt: string;
}

export interface DailyPlan {
    date: string; // YYYY-MM-DD (primary key)
    committedAt: string; // ISO
    plannedTaskIds: string[];
    plannedMinutes: number;
}

export type ReportRangeMode = 'day' | 'week' | 'month' | 'year';

export interface ThroughputPoint {
    date: string; // YYYY-MM-DD
    completed: number; // tasks completed that day
    planned: number; // committed tasks for that day (0 if no plan)
    hasPlan: boolean;
}

export interface ProjectTime {
    project: string; // '' => "No project"
    minutes: number;
}

export interface ReportSummary {
    completed: number;
    completionRate: number | null; // 0..1, null if no planned tasks in range
    focusMinutes: number;
    topProject: string | null;
    topProjectMinutes: number;
    delta: {
        completed: number; // current - previous
        completionRate: number | null;
        focusMinutes: number;
    };
}

// Raw rows returned by reports:getData, aggregated in the renderer
export interface ReportData {
    completedTasks: { id: string; project: string; date: string }[];
    sessions: { project: string; minutes: number; date: string }[];
    dailyPlans: DailyPlan[];
}
```

- [ ] **Step 4: Add the new IPC namespaces to `ElectronAPI`**

In `src/types.ts`, the `ElectronAPI` interface has a `focus` block ending like:
```ts
    focus: {
        updateTray: (data: { taskTitle: string | null; elapsed: string | null; isPlaying: boolean }) => void;
        showWidget: () => void;
        hideWidget: () => void;
        sendWidgetState: (data: any) => void;
    };
```
Immediately after that closing `};`, add:
```ts
    focusSessions: {
        add: (session: {
            taskId: string | null;
            project: string;
            startedAt: string;
            endedAt: string;
            minutes: number;
            date: string;
        }) => Promise<FocusSession>;
    };
    dailyPlans: {
        upsert: (plan: { date: string; plannedTaskIds: string[]; plannedMinutes: number }) => Promise<DailyPlan>;
        get: (date: string) => Promise<DailyPlan | null>;
    };
    reports: {
        getData: (start: string, end: string) => Promise<ReportData>;
    };
```

- [ ] **Step 5: Remove the temporary sanity test**

```bash
git rm src/lib/sanity.test.ts
```

- [ ] **Step 6: Typecheck**

Run: `npx tsc -p tsconfig.json --noEmit`
Expected: PASS (adding optional/new types does not break existing code).

- [ ] **Step 7: Commit**

```bash
git add src/types.ts
git commit -m "feat(types): add completed_at, reports/planning types and IPC namespaces"
```

---

## Task 3: Pure logic — focus session minutes (TDD)

**Files:**
- Create: `src/lib/focusSession.ts`
- Test: `src/lib/focusSession.test.ts`

- [ ] **Step 1: Write the failing test** — `src/lib/focusSession.test.ts`

```ts
import { describe, it, expect } from 'vitest';
import { sessionMinutes } from './focusSession';

describe('sessionMinutes', () => {
    it('rounds elapsed ms to whole minutes', () => {
        expect(sessionMinutes(0, 120000)).toBe(2); // 2 min
        expect(sessionMinutes(0, 90000)).toBe(2); // 1.5 -> 2
    });
    it('returns 0 for sub-30-second sessions', () => {
        expect(sessionMinutes(0, 20000)).toBe(0); // 0.33 -> 0
    });
    it('never returns negative for inverted timestamps', () => {
        expect(sessionMinutes(120000, 0)).toBe(0);
    });
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `npm run test:run -- src/lib/focusSession.test.ts`
Expected: FAIL — "Failed to resolve import './focusSession'" / `sessionMinutes is not a function`.

- [ ] **Step 3: Implement** — `src/lib/focusSession.ts`

```ts
/** Round an elapsed interval (epoch ms) to whole minutes, never negative. */
export function sessionMinutes(startMs: number, endMs: number): number {
    return Math.max(0, Math.round((endMs - startMs) / 60000));
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `npm run test:run -- src/lib/focusSession.test.ts`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/lib/focusSession.ts src/lib/focusSession.test.ts
git commit -m "feat(lib): sessionMinutes helper with tests"
```

---

## Task 4: Pure logic — capacity math (TDD)

**Files:**
- Create: `src/lib/capacity.ts`
- Test: `src/lib/capacity.test.ts`

- [ ] **Step 1: Write the failing test** — `src/lib/capacity.test.ts`

```ts
import { describe, it, expect } from 'vitest';
import { workMinutes, sumDurationMinutes, capacityStatus } from './capacity';

describe('capacity', () => {
    it('workMinutes spans the work day', () => {
        expect(workMinutes(8, 20)).toBe(720);
    });
    it('sumDurationMinutes tolerates missing durations', () => {
        expect(sumDurationMinutes([{ durationMinutes: 30 }, {}, { durationMinutes: 15 }])).toBe(45);
    });
    it('capacityStatus reports remaining and over-capacity', () => {
        const under = capacityStatus([{ durationMinutes: 60 }], 8, 10); // cap 120
        expect(under).toEqual({ planned: 60, capacity: 120, remaining: 60, over: false });
        const over = capacityStatus([{ durationMinutes: 200 }], 8, 10);
        expect(over).toEqual({ planned: 200, capacity: 120, remaining: -80, over: true });
    });
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `npm run test:run -- src/lib/capacity.test.ts`
Expected: FAIL — cannot resolve `./capacity`.

- [ ] **Step 3: Implement** — `src/lib/capacity.ts`

```ts
export function workMinutes(workStartHour: number, workEndHour: number): number {
    return (workEndHour - workStartHour) * 60;
}

export function sumDurationMinutes(tasks: { durationMinutes?: number }[]): number {
    return tasks.reduce((sum, t) => sum + (t.durationMinutes || 0), 0);
}

export interface CapacityStatus {
    planned: number;
    capacity: number;
    remaining: number;
    over: boolean;
}

export function capacityStatus(
    tasks: { durationMinutes?: number }[],
    workStartHour: number,
    workEndHour: number,
): CapacityStatus {
    const planned = sumDurationMinutes(tasks);
    const capacity = workMinutes(workStartHour, workEndHour);
    return { planned, capacity, remaining: capacity - planned, over: planned > capacity };
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `npm run test:run -- src/lib/capacity.test.ts`
Expected: PASS — 3 tests.

- [ ] **Step 5: Commit**

```bash
git add src/lib/capacity.ts src/lib/capacity.test.ts
git commit -m "feat(lib): capacity math with tests"
```

---

## Task 5: Pure logic — report date ranges (TDD)

**Files:**
- Create: `src/lib/dateRange.ts`
- Test: `src/lib/dateRange.test.ts`

- [ ] **Step 1: Write the failing test** — `src/lib/dateRange.test.ts`

```ts
import { describe, it, expect } from 'vitest';
import { getRange, getPreviousRange, daysInRange } from './dateRange';

// 2026-05-31 is a Sunday. Weeks are Monday-based.
const ref = new Date(2026, 4, 31, 12, 0, 0); // month is 0-indexed -> May

describe('getRange', () => {
    it('day', () => {
        expect(getRange('day', ref)).toEqual({ start: '2026-05-31', end: '2026-05-31' });
    });
    it('week (Mon-Sun)', () => {
        expect(getRange('week', ref)).toEqual({ start: '2026-05-25', end: '2026-05-31' });
    });
    it('month', () => {
        expect(getRange('month', ref)).toEqual({ start: '2026-05-01', end: '2026-05-31' });
    });
    it('year', () => {
        expect(getRange('year', ref)).toEqual({ start: '2026-01-01', end: '2026-12-31' });
    });
});

describe('getPreviousRange', () => {
    it('week shifts back one week', () => {
        expect(getPreviousRange('week', ref)).toEqual({ start: '2026-05-18', end: '2026-05-24' });
    });
    it('day shifts back one day', () => {
        expect(getPreviousRange('day', ref)).toEqual({ start: '2026-05-30', end: '2026-05-30' });
    });
});

describe('daysInRange', () => {
    it('enumerates inclusive days', () => {
        expect(daysInRange({ start: '2026-05-25', end: '2026-05-31' })).toHaveLength(7);
        expect(daysInRange({ start: '2026-05-25', end: '2026-05-31' })[0]).toBe('2026-05-25');
        expect(daysInRange({ start: '2026-05-31', end: '2026-05-31' })).toEqual(['2026-05-31']);
    });
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `npm run test:run -- src/lib/dateRange.test.ts`
Expected: FAIL — cannot resolve `./dateRange`.

- [ ] **Step 3: Implement** — `src/lib/dateRange.ts`

```ts
import {
    format,
    startOfWeek,
    endOfWeek,
    startOfMonth,
    endOfMonth,
    startOfYear,
    endOfYear,
    subDays,
    subWeeks,
    subMonths,
    subYears,
    eachDayOfInterval,
    parseISO,
} from 'date-fns';
import { ReportRangeMode } from '../types';

export interface DateRange {
    start: string; // YYYY-MM-DD inclusive
    end: string; // YYYY-MM-DD inclusive
}

const fmt = (d: Date) => format(d, 'yyyy-MM-dd');

export function getRange(mode: ReportRangeMode, ref: Date): DateRange {
    switch (mode) {
        case 'day':
            return { start: fmt(ref), end: fmt(ref) };
        case 'week':
            return {
                start: fmt(startOfWeek(ref, { weekStartsOn: 1 })),
                end: fmt(endOfWeek(ref, { weekStartsOn: 1 })),
            };
        case 'month':
            return { start: fmt(startOfMonth(ref)), end: fmt(endOfMonth(ref)) };
        case 'year':
            return { start: fmt(startOfYear(ref)), end: fmt(endOfYear(ref)) };
    }
}

export function getPreviousRange(mode: ReportRangeMode, ref: Date): DateRange {
    switch (mode) {
        case 'day':
            return getRange('day', subDays(ref, 1));
        case 'week':
            return getRange('week', subWeeks(ref, 1));
        case 'month':
            return getRange('month', subMonths(ref, 1));
        case 'year':
            return getRange('year', subYears(ref, 1));
    }
}

export function daysInRange(range: DateRange): string[] {
    return eachDayOfInterval({ start: parseISO(range.start), end: parseISO(range.end) }).map(fmt);
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `npm run test:run -- src/lib/dateRange.test.ts`
Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
git add src/lib/dateRange.ts src/lib/dateRange.test.ts
git commit -m "feat(lib): report date-range helpers with tests"
```

---

## Task 6: Pure logic — report aggregation (TDD)

**Files:**
- Create: `src/lib/reports.ts`
- Test: `src/lib/reports.test.ts`

- [ ] **Step 1: Write the failing test** — `src/lib/reports.test.ts`

```ts
import { describe, it, expect } from 'vitest';
import { throughputByDay, timeByProject, completionRate, summarize } from './reports';
import { ReportData } from '../types';

const current: ReportData = {
    completedTasks: [
        { id: 't1', project: 'TMap', date: '2026-05-25' },
        { id: 't2', project: 'TMap', date: '2026-05-25' },
        { id: 't3', project: 'Personal', date: '2026-05-26' },
    ],
    sessions: [
        { project: 'TMap', minutes: 60, date: '2026-05-25' },
        { project: 'TMap', minutes: 30, date: '2026-05-26' },
        { project: 'Personal', minutes: 45, date: '2026-05-26' },
    ],
    dailyPlans: [
        { date: '2026-05-25', committedAt: '', plannedTaskIds: ['t1', 't2', 'tX'], plannedMinutes: 90 },
    ],
};

const previous: ReportData = {
    completedTasks: [{ id: 'p1', project: 'TMap', date: '2026-05-18' }],
    sessions: [{ project: 'TMap', minutes: 30, date: '2026-05-18' }],
    dailyPlans: [],
};

describe('throughputByDay', () => {
    it('counts completed per day and attaches plan size', () => {
        const days = ['2026-05-25', '2026-05-26', '2026-05-27'];
        expect(throughputByDay(current, days)).toEqual([
            { date: '2026-05-25', completed: 2, planned: 3, hasPlan: true },
            { date: '2026-05-26', completed: 1, planned: 0, hasPlan: false },
            { date: '2026-05-27', completed: 0, planned: 0, hasPlan: false },
        ]);
    });
});

describe('timeByProject', () => {
    it('sums minutes per project, sorted descending', () => {
        expect(timeByProject(current.sessions)).toEqual([
            { project: 'TMap', minutes: 90 },
            { project: 'Personal', minutes: 45 },
        ]);
    });
});

describe('completionRate', () => {
    it('is done-of-planned across the range, counting only same-day completions', () => {
        // plan 2026-05-25 had t1,t2,tX; t1+t2 completed that day, tX never -> 2/3
        expect(completionRate(current)).toBeCloseTo(2 / 3);
    });
    it('is null when nothing was planned', () => {
        expect(completionRate(previous)).toBeNull();
    });
});

describe('summarize', () => {
    it('produces totals, top project, and deltas vs previous', () => {
        const s = summarize(current, previous);
        expect(s.completed).toBe(3);
        expect(s.focusMinutes).toBe(135);
        expect(s.topProject).toBe('TMap');
        expect(s.topProjectMinutes).toBe(90);
        expect(s.completionRate).toBeCloseTo(2 / 3);
        expect(s.delta.completed).toBe(2); // 3 - 1
        expect(s.delta.focusMinutes).toBe(105); // 135 - 30
        expect(s.delta.completionRate).toBeNull(); // previous had no plan
    });
});
```

- [ ] **Step 2: Run it to confirm it fails**

Run: `npm run test:run -- src/lib/reports.test.ts`
Expected: FAIL — cannot resolve `./reports`.

- [ ] **Step 3: Implement** — `src/lib/reports.ts`

```ts
import { ReportData, ThroughputPoint, ProjectTime, ReportSummary } from '../types';

export function throughputByDay(data: ReportData, days: string[]): ThroughputPoint[] {
    return days.map((day) => {
        const completed = data.completedTasks.filter((t) => t.date === day).length;
        const plan = data.dailyPlans.find((p) => p.date === day);
        return {
            date: day,
            completed,
            planned: plan ? plan.plannedTaskIds.length : 0,
            hasPlan: !!plan,
        };
    });
}

export function timeByProject(sessions: { project: string; minutes: number }[]): ProjectTime[] {
    const totals = new Map<string, number>();
    for (const s of sessions) {
        totals.set(s.project, (totals.get(s.project) || 0) + s.minutes);
    }
    return Array.from(totals.entries())
        .map(([project, minutes]) => ({ project, minutes }))
        .sort((a, b) => b.minutes - a.minutes);
}

/** done-of-planned across all daily plans in range; only same-day completions count. null if nothing planned. */
export function completionRate(data: ReportData): number | null {
    const doneKeys = new Set(data.completedTasks.map((t) => `${t.id}|${t.date}`));
    let planned = 0;
    let done = 0;
    for (const plan of data.dailyPlans) {
        planned += plan.plannedTaskIds.length;
        done += plan.plannedTaskIds.filter((id) => doneKeys.has(`${id}|${plan.date}`)).length;
    }
    return planned > 0 ? done / planned : null;
}

export function summarize(current: ReportData, previous: ReportData): ReportSummary {
    const byProject = timeByProject(current.sessions);
    const focusMinutes = current.sessions.reduce((s, x) => s + x.minutes, 0);
    const prevFocus = previous.sessions.reduce((s, x) => s + x.minutes, 0);
    const rate = completionRate(current);
    const prevRate = completionRate(previous);
    const top = byProject[0] ?? null;

    return {
        completed: current.completedTasks.length,
        completionRate: rate,
        focusMinutes,
        topProject: top ? top.project || 'No project' : null,
        topProjectMinutes: top ? top.minutes : 0,
        delta: {
            completed: current.completedTasks.length - previous.completedTasks.length,
            completionRate: rate != null && prevRate != null ? rate - prevRate : null,
            focusMinutes: focusMinutes - prevFocus,
        },
    };
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `npm run test:run -- src/lib/reports.test.ts`
Expected: PASS — 6 tests.

- [ ] **Step 5: Run the whole suite**

Run: `npm run test:run`
Expected: PASS — all four lib test files green.

- [ ] **Step 6: Commit**

```bash
git add src/lib/reports.ts src/lib/reports.test.ts
git commit -m "feat(lib): report aggregation with tests"
```

---

## Task 7: Migration 011 — completed_at + focus_sessions + daily_plans

**Files:**
- Modify: `electron/database.ts`

- [ ] **Step 1: Append the migration**

In `electron/database.ts`, the `migrations` array's last element is followed by the closing `];`. Append a new element immediately **before** that closing `];`. (If a migration numbered `011` already exists, use the next unused number, e.g. `012`, and keep the same `name` suffix.)

```ts
    {
        name: '011_planning_reports',
        sql: `
      ALTER TABLE tasks ADD COLUMN completed_at TEXT DEFAULT NULL;
      CREATE TABLE IF NOT EXISTS focus_sessions (
        id TEXT PRIMARY KEY,
        task_id TEXT,
        project TEXT DEFAULT '',
        started_at TEXT NOT NULL,
        ended_at TEXT NOT NULL,
        minutes INTEGER NOT NULL DEFAULT 0,
        date TEXT NOT NULL,
        created_at TEXT NOT NULL DEFAULT (datetime('now')),
        FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE SET NULL
      );
      CREATE INDEX IF NOT EXISTS idx_focus_sessions_date ON focus_sessions(date);
      CREATE TABLE IF NOT EXISTS daily_plans (
        date TEXT PRIMARY KEY,
        committed_at TEXT NOT NULL,
        planned_task_ids TEXT NOT NULL DEFAULT '[]',
        planned_minutes INTEGER NOT NULL DEFAULT 0
      )
    `,
    },
```
> The migration runner splits on `;` and runs each statement separately — this is fine because none of these statements contains a `;` inside a string/CHECK/trigger. The `datetime('now')` default contains no `;`.

- [ ] **Step 2: Build electron to typecheck**

Run: `npm run build`
Expected: PASS (no TS errors in `electron/`).

- [ ] **Step 3: Run the app and confirm the migration applies**

Run: `npm run dev`
Expected: the app launches; the dev console (Electron main process terminal) prints `Applied migration: 011_planning_reports` on first run; no SQL errors. Close the app.
> If you had an existing `tmap.db`, the migration runs once and is recorded. Re-launching does not re-run it.

- [ ] **Step 4: Commit**

```bash
git add electron/database.ts
git commit -m "feat(db): migration 011 - completed_at, focus_sessions, daily_plans"
```

---

## Task 8: taskService — stamp completed_at on done

**Files:**
- Modify: `electron/taskService.ts`

- [ ] **Step 1: Add `completedAt` to the electron `Task` interface**

The interface ends:
```ts
    createdAt: string;
    updatedAt: string;
}
```
Change to:
```ts
    createdAt: string;
    updatedAt: string;
    completedAt: string | null;
}
```

- [ ] **Step 2: Map the column in `rowToTask`**

In `rowToTask`, the return object has:
```ts
        recurrenceOriginalDate: row.recurrence_original_date || null,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
    };
```
Change to:
```ts
        recurrenceOriginalDate: row.recurrence_original_date || null,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        completedAt: row.completed_at || null,
    };
```

- [ ] **Step 3: Stamp/clear `completed_at` in `update()`**

In `update()`, replace this exact line:
```ts
        if (updates.status !== undefined) { sets.push('status = ?'); values.push(updates.status); }
```
with:
```ts
        if (updates.status !== undefined) {
            sets.push('status = ?'); values.push(updates.status);
            sets.push('completed_at = ?'); values.push(updates.status === 'done' ? now : null);
        }
```
> `now` is already declared at the top of `update()`. This auto-stamps the completion time on the done transition and clears it on any other status change — callers need not pass `completedAt`. Reads use `SELECT *`, so no read-query change is needed.

- [ ] **Step 4: Build to typecheck**

Run: `npm run build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add electron/taskService.ts
git commit -m "feat(db): stamp tasks.completed_at on done transition"
```

---

## Task 9: New services — focusSession / dailyPlan / report

**Files:**
- Create: `electron/focusSessionService.ts`
- Create: `electron/dailyPlanService.ts`
- Create: `electron/reportService.ts`

> All three take the SQL.js `Database` in their constructor (like `TaskService`), use `db.run(sql, params)` for writes followed by `saveDatabase()`, and a `prepare/bind/step` loop for parameterized reads. Match `electron/taskService.ts`'s `import { v4 as uuid } from 'uuid';`.

- [ ] **Step 1: Create `electron/focusSessionService.ts`**

```ts
import { Database } from 'sql.js';
import { v4 as uuid } from 'uuid';
import { saveDatabase } from './database';

export interface FocusSessionInput {
    taskId: string | null;
    project: string;
    startedAt: string;
    endedAt: string;
    minutes: number;
    date: string;
}

export class FocusSessionService {
    constructor(private db: Database) {}

    add(input: FocusSessionInput) {
        const id = uuid();
        const now = new Date().toISOString();
        this.db.run(
            `INSERT INTO focus_sessions (id, task_id, project, started_at, ended_at, minutes, date, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
            [id, input.taskId, input.project, input.startedAt, input.endedAt, input.minutes, input.date, now],
        );
        saveDatabase();
        return { id, ...input, createdAt: now };
    }
}
```

- [ ] **Step 2: Create `electron/dailyPlanService.ts`**

```ts
import { Database } from 'sql.js';
import { saveDatabase } from './database';

export interface DailyPlanInput {
    date: string;
    plannedTaskIds: string[];
    plannedMinutes: number;
}

export class DailyPlanService {
    constructor(private db: Database) {}

    upsert(input: DailyPlanInput) {
        const now = new Date().toISOString();
        this.db.run(
            `INSERT INTO daily_plans (date, committed_at, planned_task_ids, planned_minutes)
             VALUES (?, ?, ?, ?)
             ON CONFLICT(date) DO UPDATE SET
               committed_at = excluded.committed_at,
               planned_task_ids = excluded.planned_task_ids,
               planned_minutes = excluded.planned_minutes`,
            [input.date, now, JSON.stringify(input.plannedTaskIds), input.plannedMinutes],
        );
        saveDatabase();
        return {
            date: input.date,
            committedAt: now,
            plannedTaskIds: input.plannedTaskIds,
            plannedMinutes: input.plannedMinutes,
        };
    }

    get(date: string) {
        const stmt = this.db.prepare('SELECT * FROM daily_plans WHERE date = ?');
        stmt.bind([date]);
        let row: any = null;
        if (stmt.step()) row = stmt.getAsObject();
        stmt.free();
        if (!row) return null;
        return {
            date: row.date,
            committedAt: row.committed_at,
            plannedTaskIds: JSON.parse(row.planned_task_ids || '[]'),
            plannedMinutes: row.planned_minutes || 0,
        };
    }
}
```

- [ ] **Step 3: Create `electron/reportService.ts`**

```ts
import { Database } from 'sql.js';

export class ReportService {
    constructor(private db: Database) {}

    private query(sql: string, params: any[] = []): any[] {
        const stmt = this.db.prepare(sql);
        stmt.bind(params);
        const rows: any[] = [];
        while (stmt.step()) rows.push(stmt.getAsObject());
        stmt.free();
        return rows;
    }

    getData(start: string, end: string) {
        // 'localtime' converts the UTC-stored completed_at to the host's local
        // calendar day, matching focus_sessions.date and the renderer's day buckets.
        const completedTasks = this.query(
            `SELECT id, project, date(completed_at, 'localtime') AS d
             FROM tasks
             WHERE completed_at IS NOT NULL
               AND date(completed_at, 'localtime') >= ? AND date(completed_at, 'localtime') <= ?`,
            [start, end],
        ).map((r) => ({ id: r.id, project: r.project || '', date: r.d }));

        const sessions = this.query(
            `SELECT project, minutes, date FROM focus_sessions WHERE date >= ? AND date <= ?`,
            [start, end],
        ).map((r) => ({ project: r.project || '', minutes: r.minutes || 0, date: r.date }));

        const dailyPlans = this.query(
            `SELECT date, committed_at, planned_task_ids, planned_minutes
             FROM daily_plans WHERE date >= ? AND date <= ?`,
            [start, end],
        ).map((r) => ({
            date: r.date,
            committedAt: r.committed_at,
            plannedTaskIds: JSON.parse(r.planned_task_ids || '[]'),
            plannedMinutes: r.planned_minutes || 0,
        }));

        return { completedTasks, sessions, dailyPlans };
    }
}
```

- [ ] **Step 4: Build to typecheck**

Run: `npm run build`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add electron/focusSessionService.ts electron/dailyPlanService.ts electron/reportService.ts
git commit -m "feat(db): focusSession, dailyPlan and report services"
```

---

## Task 10: IPC wiring — main.ts + preload.ts

**Files:**
- Modify: `electron/main.ts`
- Modify: `electron/preload.ts`

- [ ] **Step 1: Import the new services in `main.ts`**

After this import group:
```ts
import { initDatabase, getDatabase, saveDatabase } from './database';
import { TaskService } from './taskService';
import { ProjectService } from './projectService';
import { NoteService } from './noteService';
import { seedDemoData } from './seed';
```
add:
```ts
import { FocusSessionService } from './focusSessionService';
import { DailyPlanService } from './dailyPlanService';
import { ReportService } from './reportService';
```

- [ ] **Step 2: Declare the module-level service variables**

After:
```ts
let taskService: TaskService;
let projectService: ProjectService;
let noteService: NoteService;
```
add:
```ts
let focusSessionService: FocusSessionService;
let dailyPlanService: DailyPlanService;
let reportService: ReportService;
```

- [ ] **Step 3: Instantiate them in `app.whenReady()`**

After:
```ts
        taskService = new TaskService(getDatabase());
        projectService = new ProjectService(getDatabase());
        noteService = new NoteService(getDatabase());
```
add:
```ts
        focusSessionService = new FocusSessionService(getDatabase());
        dailyPlanService = new DailyPlanService(getDatabase());
        reportService = new ReportService(getDatabase());
```

- [ ] **Step 4: Register handlers inside `registerIpcHandlers()`**

Add this block before the closing `}` of `registerIpcHandlers()` (e.g. right after the `tasks:*` block):
```ts
    // ─── Planning & Reports IPC Handlers ─────────────────────
    ipcMain.handle('focusSessions:add', (_e: any, input: any) => {
        return focusSessionService.add(input);
    });

    ipcMain.handle('dailyPlans:upsert', (_e: any, input: any) => {
        return dailyPlanService.upsert(input);
    });

    ipcMain.handle('dailyPlans:get', (_e: any, date: string) => {
        return dailyPlanService.get(date);
    });

    ipcMain.handle('reports:getData', (_e: any, start: string, end: string) => {
        return reportService.getData(start, end);
    });
```

- [ ] **Step 5: Expose the namespaces in `preload.ts`**

In the `const api = { ... }` object, after the `focus: { ... }` namespace (and before the `on`/`off`/`removeAllListeners` keys), add:
```ts
    focusSessions: {
        add: (session: {
            taskId: string | null;
            project: string;
            startedAt: string;
            endedAt: string;
            minutes: number;
            date: string;
        }) => ipcRenderer.invoke('focusSessions:add', session),
    },
    dailyPlans: {
        upsert: (plan: { date: string; plannedTaskIds: string[]; plannedMinutes: number }) =>
            ipcRenderer.invoke('dailyPlans:upsert', plan),
        get: (date: string) => ipcRenderer.invoke('dailyPlans:get', date),
    },
    reports: {
        getData: (start: string, end: string) => ipcRenderer.invoke('reports:getData', start, end),
    },
```

- [ ] **Step 6: Build to typecheck**

Run: `npm run build`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add electron/main.ts electron/preload.ts
git commit -m "feat(ipc): wire focusSessions, dailyPlans, reports channels"
```

---

## Task 11: Store — focus-session logging + rollover bug fix

**Files:**
- Modify: `src/store.ts`

- [ ] **Step 1: Import `sessionMinutes`**

At the top of `src/store.ts`, the imports include:
```ts
import { format, addDays, startOfWeek, endOfWeek } from 'date-fns';
```
Add a new import line below the existing imports:
```ts
import { sessionMinutes } from './lib/focusSession';
```

- [ ] **Step 2: Declare the `logFocusSession` action in the `AppState` interface**

Find the focus-mode action signatures block (near `startFocusSession`, `pauseFocusSession`, `stopFocusSession` declarations). Add this signature alongside them:
```ts
    logFocusSession: (
        targetType: 'task' | 'project',
        targetId: string,
        startMs: number,
        endMs: number,
        minutes: number,
    ) => void;
```

- [ ] **Step 3: Implement `logFocusSession`** — add this action in the store body, just above `startFocusSession`:

```ts
    logFocusSession: (targetType, targetId, startMs, endMs, minutes) => {
        const { tasks, projects } = get();
        let taskId: string | null = null;
        let project = '';
        if (targetType === 'task') {
            taskId = targetId;
            project = tasks.find((t) => t.id === targetId)?.project || '';
        } else {
            project = projects.find((p) => p.id === targetId)?.name || '';
        }
        window.api.focusSessions
            .add({
                taskId,
                project,
                startedAt: new Date(startMs).toISOString(),
                endedAt: new Date(endMs).toISOString(),
                minutes,
                date: format(new Date(startMs), 'yyyy-MM-dd'),
            })
            .catch((e) => console.error('Failed to log focus session:', e));
    },
```

- [ ] **Step 4: Log a session in `pauseFocusSession`**

Replace the body section of `pauseFocusSession` that currently reads:
```ts
        const elapsedMs = Date.now() - focusMode.sessionStartTime;
        const elapsedMinutes = Math.round(elapsedMs / 60000);

        if (elapsedMinutes > 0) {
            if (focusMode.targetType === 'task') {
                const task = tasks.find(t => t.id === focusMode.targetId);
                if (task) updateTask(task.id, { actualTimeMinutes: (task.actualTimeMinutes || 0) + elapsedMinutes });
            } else {
                const project = projects.find(p => p.id === focusMode.targetId);
                if (project) updateProject(project.id, { actualTimeMinutes: (project.actualTimeMinutes || 0) + elapsedMinutes });
            }
        }
```
with:
```ts
        const endMs = Date.now();
        const elapsedMinutes = sessionMinutes(focusMode.sessionStartTime, endMs);

        if (elapsedMinutes > 0) {
            get().logFocusSession(focusMode.targetType, focusMode.targetId, focusMode.sessionStartTime, endMs, elapsedMinutes);
            if (focusMode.targetType === 'task') {
                const task = tasks.find(t => t.id === focusMode.targetId);
                if (task) updateTask(task.id, { actualTimeMinutes: (task.actualTimeMinutes || 0) + elapsedMinutes });
            } else {
                const project = projects.find(p => p.id === focusMode.targetId);
                if (project) updateProject(project.id, { actualTimeMinutes: (project.actualTimeMinutes || 0) + elapsedMinutes });
            }
        }
```

- [ ] **Step 5: Log a session in `stopFocusSession`**

Replace the section that currently reads:
```ts
            const elapsedMs = Date.now() - focusMode.sessionStartTime;
            const elapsedMinutes = Math.round(elapsedMs / 60000);

            if (elapsedMinutes > 0) {
                if (focusMode.targetType === 'task') {
                    const task = tasks.find(t => t.id === focusMode.targetId);
                    if (task) await updateTask(task.id, { actualTimeMinutes: (task.actualTimeMinutes || 0) + elapsedMinutes });
                } else {
                    const project = projects.find(p => p.id === focusMode.targetId);
                    if (project) await updateProject(project.id, { actualTimeMinutes: (project.actualTimeMinutes || 0) + elapsedMinutes });
                }
            }
```
with:
```ts
            const endMs = Date.now();
            const elapsedMinutes = sessionMinutes(focusMode.sessionStartTime, endMs);

            if (elapsedMinutes > 0) {
                get().logFocusSession(focusMode.targetType, focusMode.targetId, focusMode.sessionStartTime, endMs, elapsedMinutes);
                if (focusMode.targetType === 'task') {
                    const task = tasks.find(t => t.id === focusMode.targetId);
                    if (task) await updateTask(task.id, { actualTimeMinutes: (task.actualTimeMinutes || 0) + elapsedMinutes });
                } else {
                    const project = projects.find(p => p.id === focusMode.targetId);
                    if (project) await updateProject(project.id, { actualTimeMinutes: (project.actualTimeMinutes || 0) + elapsedMinutes });
                }
            }
```

- [ ] **Step 6: Fix the rollover `'todo'` bug**

In `loadTasks`, replace this line (the persisted update):
```ts
                    if (task.status === 'scheduled') {
                        (updates as any).status = 'todo';
                    }
```
with:
```ts
                    if (task.status === 'scheduled') {
                        updates.status = 'planned';
                    }
```
and replace the local-state line:
```ts
                        status: (task.status === 'scheduled' ? 'todo' : task.status) as Task['status'],
```
with:
```ts
                        status: (task.status === 'scheduled' ? 'planned' : task.status) as Task['status'],
```

- [ ] **Step 7: Build + run tests**

Run: `npm run build && npm run test:run`
Expected: both PASS.

- [ ] **Step 8: Smoke-test focus logging**

Run: `npm run dev`. Start a focus session on a task, let it run ~1 minute, then pause/stop. Confirm no console errors (a `focus_sessions` row is written; it will surface in Reports later). Close the app.

- [ ] **Step 9: Commit**

```bash
git add src/store.ts
git commit -m "feat(store): log focus sessions; fix invalid 'todo' rollover status"
```

---

## Task 12: Store — planning state refactor + selectors + commitDay

**Files:**
- Modify: `src/store.ts`

- [ ] **Step 1: Replace the `planningFlow` state shape in the `AppState` interface**

Current:
```ts
    planningFlow: {
        isOpen: boolean;
        step: 0 | 1 | 2; // 0: Review Yesterday, 1: Triage Inbox, 2: Timebox
    };
```
Change to:
```ts
    planningFlow: {
        isOpen: boolean;
        phase: PlanningPhase;
        targetDate: string; // YYYY-MM-DD
    };
```

- [ ] **Step 2: Update the planning action + selector signatures in the interface**

Find the planning action signatures:
```ts
    setPlanningStep: (step: 0 | 1 | 2) => void;
    closePlanningFlow: () => void;
```
Replace with:
```ts
    setPlanningPhase: (phase: PlanningPhase) => void;
    closePlanningFlow: () => void;
    commitDay: () => Promise<void>;
    planForDate: (id: string, date: string) => Promise<void>;
    leftoverTasks: (beforeDate: string) => Task[];
    plannedForDate: (date: string) => Task[];
    unscheduledPlannedTasks: (date: string) => Task[];
```

- [ ] **Step 3: Import `PlanningPhase`**

The store imports from `./types`:
```ts
import { Task, Subtask, ViewMode, Project, NoteGroup, Note, RecurrenceFrequency, RecurrenceEndType, RecurrenceRule } from './types';
```
Add `PlanningPhase`:
```ts
import { Task, Subtask, ViewMode, Project, NoteGroup, Note, RecurrenceFrequency, RecurrenceEndType, RecurrenceRule, PlanningPhase } from './types';
```

- [ ] **Step 4: Update the initial state**

Current:
```ts
    planningFlow: {
        isOpen: false,
        step: 0,
    },
```
Change to:
```ts
    planningFlow: {
        isOpen: false,
        phase: 'review',
        targetDate: format(new Date(), 'yyyy-MM-dd'),
    },
```

- [ ] **Step 5: Replace the planning action implementations**

Current:
```ts
    startPlanningFlow: () => set({ planningFlow: { isOpen: true, step: 0 } }),
    setPlanningStep: (step: 0 | 1 | 2) => set((state) => ({ planningFlow: { ...state.planningFlow, step } })),
    closePlanningFlow: () => set((state) => ({ planningFlow: { ...state.planningFlow, isOpen: false } })),
```
Replace with:
```ts
    startPlanningFlow: () =>
        set({
            planningFlow: { isOpen: true, phase: 'review', targetDate: format(new Date(), 'yyyy-MM-dd') },
        }),
    setPlanningPhase: (phase: PlanningPhase) =>
        set((state) => ({ planningFlow: { ...state.planningFlow, phase } })),
    closePlanningFlow: () => set((state) => ({ planningFlow: { ...state.planningFlow, isOpen: false } })),
    planForDate: async (id: string, date: string) => {
        const { updateTask } = get();
        await updateTask(id, { status: 'planned', plannedDate: date });
    },
    commitDay: async () => {
        const { planningFlow, plannedForDate } = get();
        const date = planningFlow.targetDate;
        const tasksForDay = plannedForDate(date);
        const plannedMinutes = tasksForDay.reduce((s, t) => s + (t.durationMinutes || 0), 0);
        try {
            await window.api.dailyPlans.upsert({
                date,
                plannedTaskIds: tasksForDay.map((t) => t.id),
                plannedMinutes,
            });
        } catch (e) {
            console.error('Failed to commit day:', e);
        }
        set((state) => ({ planningFlow: { ...state.planningFlow, isOpen: false } }));
    },
```

- [ ] **Step 6: Add the new selectors**

Next to the existing selectors (e.g. just after `backlogTasks`), add:
```ts
    leftoverTasks: (beforeDate: string) => {
        const { tasks } = get();
        return tasks.filter(
            (t) =>
                t.status !== 'done' &&
                t.status !== 'archived' &&
                !!t.plannedDate &&
                t.plannedDate < beforeDate,
        );
    },
    plannedForDate: (date: string) => {
        const { tasks } = get();
        return tasks.filter(
            (t) => t.plannedDate === date && t.status !== 'archived' && t.status !== 'done',
        );
    },
    unscheduledPlannedTasks: (date: string) => {
        const { tasks } = get();
        return tasks.filter(
            (t) =>
                t.plannedDate === date &&
                t.status !== 'archived' &&
                t.status !== 'done' &&
                !t.scheduledStart,
        );
    },
```

- [ ] **Step 7: Typecheck**

Run: `npx tsc -p tsconfig.json --noEmit`
Expected: FAIL — `src/components/planning/PlanningFlowOverlay.tsx` references `planningFlow.step` / `setPlanningStep` (these are removed). This is expected; the file is replaced in Task 14. Do **not** fix it here. Confirm the only errors are inside `src/components/planning/PlanningFlowOverlay.tsx`.

- [ ] **Step 8: Commit**

```bash
git add src/store.ts
git commit -m "feat(store): planning phases, targetDate, commitDay, planning selectors"
```

---

## Task 13: Store — reports slice

**Files:**
- Modify: `src/store.ts`

- [ ] **Step 1: Import the report libs**

Add below the existing imports:
```ts
import { getRange, getPreviousRange, daysInRange } from './lib/dateRange';
import { summarize, throughputByDay, timeByProject } from './lib/reports';
import { ReportRangeMode, ReportSummary, ThroughputPoint, ProjectTime } from './types';
```
> Merge the type names into the existing `./types` import line if you prefer a single import; either is fine.

- [ ] **Step 2: Add report state + action signatures to `AppState`**

Add to the interface (near the other view-related fields/actions):
```ts
    reportRange: ReportRangeMode;
    reportData: {
        summary: ReportSummary;
        throughput: ThroughputPoint[];
        timeByProject: ProjectTime[];
    } | null;
    reportLoading: boolean;
    setReportRange: (mode: ReportRangeMode) => void;
    loadReports: () => Promise<void>;
```

- [ ] **Step 3: Add report initial state**

In the initial state object (near `currentView: 'board'`), add:
```ts
    reportRange: 'week',
    reportData: null,
    reportLoading: false,
```

- [ ] **Step 4: Implement the report actions** (add in the store body):

```ts
    setReportRange: (mode: ReportRangeMode) => {
        set({ reportRange: mode });
        get().loadReports();
    },
    loadReports: async () => {
        const { reportRange } = get();
        set({ reportLoading: true });
        try {
            const now = new Date();
            const range = getRange(reportRange, now);
            const prev = getPreviousRange(reportRange, now);
            const [current, previous] = await Promise.all([
                window.api.reports.getData(range.start, range.end),
                window.api.reports.getData(prev.start, prev.end),
            ]);
            set({
                reportData: {
                    summary: summarize(current, previous),
                    throughput: throughputByDay(current, daysInRange(range)),
                    timeByProject: timeByProject(current.sessions),
                },
                reportLoading: false,
            });
        } catch (e) {
            console.error('Failed to load reports:', e);
            set({ reportLoading: false });
        }
    },
```

- [ ] **Step 5: Typecheck**

Run: `npx tsc -p tsconfig.json --noEmit`
Expected: same single pre-existing error set in `PlanningFlowOverlay.tsx` only (replaced in Task 14). No new errors.

- [ ] **Step 6: Commit**

```bash
git add src/store.ts
git commit -m "feat(store): reports slice (range, data, loadReports)"
```

---

## Task 14: Planning Canvas component (replaces the old overlay)

**Files:**
- Create: `src/components/planning/PlanningCanvas.tsx`
- Modify: `src/components/TaskItem.tsx`
- Modify: `src/App.tsx`
- Delete: `src/components/planning/PlanningFlowOverlay.tsx`, `StepReviewYesterday.tsx`, `StepTriageInbox.tsx`, `StepTimebox.tsx`

- [ ] **Step 1: Add "Plan for today" to `TaskItem`'s menu**

In `src/components/TaskItem.tsx`, the dropdown menu has an "Edit Details" item followed by "Move to Backlog". Insert a new `MenuItem` between them:
```tsx
                                <MenuItem
                                    icon={<CalendarPlus className="w-3.5 h-3.5" />}
                                    label="Plan for today"
                                    onClick={() => { moveToToday(task.id); setShowMenu(false); }}
                                />
```
> `CalendarPlus` is already imported and `moveToToday` is already destructured from the store — no other change needed in this file.

- [ ] **Step 2: Create `src/components/planning/PlanningCanvas.tsx`**

```tsx
import React from 'react';
import { useStore } from '../../store';
import { TaskItem } from '../TaskItem';
import { DayTimeline } from '../DayTimeline';
import { capacityStatus } from '../../lib/capacity';
import { PlanningPhase } from '../../types';
import { format, parseISO } from 'date-fns';
import { clsx } from 'clsx';
import { CheckCircle, Inbox, CalendarDays, Flag, X, ChevronLeft, ChevronRight, AlertTriangle } from 'lucide-react';

const PHASES: { id: PlanningPhase; title: string; icon: React.ReactNode }[] = [
    { id: 'review', title: 'Review', icon: <CheckCircle className="w-4 h-4" /> },
    { id: 'choose', title: 'Choose', icon: <Inbox className="w-4 h-4" /> },
    { id: 'timebox', title: 'Timebox', icon: <CalendarDays className="w-4 h-4" /> },
    { id: 'commit', title: 'Commit', icon: <Flag className="w-4 h-4" /> },
];

const fmtMin = (m: number) => `${Math.floor(m / 60)}h ${m % 60}m`;

export const PlanningCanvas: React.FC = () => {
    const {
        planningFlow,
        closePlanningFlow,
        setPlanningPhase,
        commitDay,
        leftoverTasks,
        inboxTasks,
        backlogTasks,
        plannedForDate,
        workStartHour,
        workEndHour,
    } = useStore();

    if (!planningFlow.isOpen) return null;

    const { phase, targetDate } = planningFlow;
    const phaseIndex = PHASES.findIndex((p) => p.id === phase);

    const leftovers = leftoverTasks(targetDate);
    const pool = [...inboxTasks(), ...backlogTasks()];
    const todays = plannedForDate(targetDate);
    const cap = capacityStatus(todays, workStartHour, workEndHour);
    const weekday = format(parseISO(targetDate), 'EEEE');

    const goNext = () => {
        if (phaseIndex < PHASES.length - 1) setPlanningPhase(PHASES[phaseIndex + 1].id);
        else commitDay();
    };
    const goPrev = () => {
        if (phaseIndex > 0) setPlanningPhase(PHASES[phaseIndex - 1].id);
    };

    return (
        <div className="fixed inset-0 z-50 bg-surface-950/90 backdrop-blur-sm flex justify-center pt-12 px-4 pb-6 w-full h-full">
            <div className="w-full max-w-6xl bg-surface-900 border border-surface-800 rounded-xl shadow-2xl flex flex-col h-full max-h-[88vh] animate-scale-in">
                {/* Header + phase bar */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-surface-800 shrink-0">
                    <h2 className="text-xl font-semibold text-surface-100">Plan {weekday}</h2>
                    <div className="flex gap-2">
                        {PHASES.map((p, i) => (
                            <button
                                key={p.id}
                                onClick={() => setPlanningPhase(p.id)}
                                className={clsx(
                                    'flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium transition-all',
                                    i === phaseIndex
                                        ? 'bg-accent-600 text-white'
                                        : i < phaseIndex
                                        ? 'bg-accent-900/50 text-accent-300'
                                        : 'bg-surface-800/70 text-surface-400 hover:text-surface-200',
                                )}
                            >
                                {p.icon}
                                {p.title}
                            </button>
                        ))}
                    </div>
                    <button
                        onClick={closePlanningFlow}
                        className="p-2 -mr-2 text-surface-400 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                    >
                        <X className="w-5 h-5" />
                    </button>
                </div>

                {/* 3-column canvas */}
                <div className="flex-1 min-h-0 grid grid-cols-3 gap-px bg-surface-800/40">
                    <Column
                        title={phase === 'review' ? 'Leftovers' : 'Inbox & Backlog'}
                        subtitle={phase === 'review' ? 'From earlier days' : 'Pick what to do today'}
                        dim={phase === 'timebox' || phase === 'commit'}
                    >
                        {phase === 'review'
                            ? leftovers.length === 0
                                ? <Empty text="Nothing left over 🎉" />
                                : leftovers.map((t) => <TaskItem key={t.id} task={t} />)
                            : pool.length === 0
                                ? <Empty text="Inbox & backlog are empty" />
                                : pool.map((t) => <TaskItem key={t.id} task={t} />)}
                    </Column>

                    <Column
                        title={`Today · ${weekday}`}
                        dim={phase === 'review'}
                        header={<CapacityMeter planned={cap.planned} capacity={cap.capacity} over={cap.over} />}
                    >
                        {todays.length === 0
                            ? <Empty text={'No tasks yet. Use a task’s “Plan for today” action.'} />
                            : todays.map((t) => <TaskItem key={t.id} task={t} />)}
                    </Column>

                    <div className={clsx('bg-surface-950 flex flex-col min-h-0', phase !== 'timebox' && phase !== 'commit' && 'opacity-60')}>
                        <DayTimeline />
                    </div>
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between px-6 py-4 border-t border-surface-800 shrink-0">
                    <button
                        onClick={goPrev}
                        disabled={phaseIndex === 0}
                        className={clsx(
                            'flex items-center gap-2 px-4 py-2 rounded-lg font-medium transition-all',
                            phaseIndex === 0 ? 'opacity-0 pointer-events-none' : 'text-surface-300 hover:text-surface-100 hover:bg-surface-800',
                        )}
                    >
                        <ChevronLeft className="w-4 h-4" /> Back
                    </button>

                    {phase === 'commit' ? (
                        <div className="flex items-center gap-4">
                            <span className="text-sm text-surface-400">
                                {todays.length} task{todays.length === 1 ? '' : 's'} · {fmtMin(cap.planned)} of {fmtMin(cap.capacity)}
                            </span>
                            {cap.over && (
                                <span className="flex items-center gap-1 text-xs text-warning-400">
                                    <AlertTriangle className="w-3.5 h-3.5" /> Over capacity
                                </span>
                            )}
                            <button
                                onClick={commitDay}
                                className="flex items-center gap-2 px-5 py-2.5 bg-accent-600 hover:bg-accent-500 text-white rounded-lg font-medium transition-colors shadow-lg shadow-accent-500/20"
                            >
                                Commit day ✓
                            </button>
                        </div>
                    ) : (
                        <button
                            onClick={goNext}
                            className="flex items-center gap-2 px-5 py-2.5 bg-accent-600 hover:bg-accent-500 text-white rounded-lg font-medium transition-colors shadow-lg shadow-accent-500/20"
                        >
                            Next: {PHASES[phaseIndex + 1].title} <ChevronRight className="w-4 h-4" />
                        </button>
                    )}
                </div>
            </div>
        </div>
    );
};

function Column({
    title,
    subtitle,
    header,
    dim,
    children,
}: {
    title: string;
    subtitle?: string;
    header?: React.ReactNode;
    dim?: boolean;
    children: React.ReactNode;
}) {
    return (
        <div className={clsx('bg-surface-900 flex flex-col min-h-0 transition-opacity', dim && 'opacity-60')}>
            <div className="px-4 pt-4 pb-2 shrink-0">
                <h3 className="text-sm font-semibold text-surface-200">{title}</h3>
                {subtitle && <p className="text-2xs text-surface-500 mt-0.5">{subtitle}</p>}
                {header}
            </div>
            <div className="flex-1 overflow-y-auto custom-scrollbar px-4 pb-4 space-y-2">{children}</div>
        </div>
    );
}

function CapacityMeter({ planned, capacity, over }: { planned: number; capacity: number; over: boolean }) {
    const pct = capacity > 0 ? Math.min(100, Math.round((planned / capacity) * 100)) : 0;
    return (
        <div className="mt-2">
            <div className="flex justify-between text-2xs mb-1">
                <span className="text-surface-400">{fmtMin(planned)} planned</span>
                <span className={clsx(over ? 'text-warning-400' : 'text-surface-500')}>{fmtMin(capacity)} available</span>
            </div>
            <div className="h-1.5 rounded-full bg-surface-800 overflow-hidden">
                <div
                    className={clsx('h-full rounded-full transition-all', over ? 'bg-warning-500' : 'bg-accent-500')}
                    style={{ width: `${pct}%` }}
                />
            </div>
        </div>
    );
}

function Empty({ text }: { text: string }) {
    return <div className="text-center text-xs text-surface-500 py-8">{text}</div>;
}
```

- [ ] **Step 3: Swap the mount in `src/App.tsx`**

Replace the import:
```tsx
import { PlanningFlowOverlay } from './components/planning/PlanningFlowOverlay';
```
with:
```tsx
import { PlanningCanvas } from './components/planning/PlanningCanvas';
```
Then in the modal mount block, replace:
```tsx
            <PlanningFlowOverlay />
```
with:
```tsx
            <PlanningCanvas />
```

- [ ] **Step 4: Delete the obsolete planning files**

```bash
git rm src/components/planning/PlanningFlowOverlay.tsx src/components/planning/StepReviewYesterday.tsx src/components/planning/StepTriageInbox.tsx src/components/planning/StepTimebox.tsx
```

- [ ] **Step 5: Typecheck + build**

Run: `npm run build`
Expected: PASS (the previous `PlanningFlowOverlay` errors are gone; new component compiles).

- [ ] **Step 6: Smoke-test the planning flow**

Run: `npm run dev`. Click **Plan Today**. Verify: the canvas opens at **Review** showing leftovers; the phase chips switch Review→Choose→Timebox→Commit; in **Choose**, a task's "Plan for today" moves it into the Today column and the capacity meter updates; in **Timebox**, dragging a Today task onto the timeline schedules it; on **Commit**, "Commit day ✓" closes the canvas with no console errors. Close the app.

- [ ] **Step 7: Commit**

```bash
git add src/components/planning/PlanningCanvas.tsx src/components/TaskItem.tsx src/App.tsx
git commit -m "feat(planning): guided 3-column canvas replacing the step wizard"
```

---

## Task 15: Reports nav + view branch

**Files:**
- Modify: `src/components/Sidebar.tsx`
- Modify: `src/App.tsx`

> `ReportsView` is created in Task 16. To keep this task building on its own, create a minimal placeholder first, then flesh it out in Task 16.

- [ ] **Step 1: Create a placeholder `src/components/ReportsView.tsx`**

```tsx
export function ReportsView() {
    return <div className="flex-1 bg-surface-950" />;
}
```

- [ ] **Step 2: Add the Reports nav item in `Sidebar.tsx`**

In the lucide-react import block, add `BarChart3`:
```tsx
    BarChart3,
} from 'lucide-react';
```
> Insert it among the existing icon names; the exact position does not matter.

Append to the `navItems` array:
```tsx
    { id: 'all', label: 'All Tasks', icon: <ListChecks className="w-4 h-4" /> },
    { id: 'reports', label: 'Reports', icon: <BarChart3 className="w-4 h-4" /> },
];
```
> The nav render loop, collapsed-sidebar loop, and `getCounts` default already handle the new item — no other Sidebar change.

- [ ] **Step 3: Add the view branch in `App.tsx`**

Add the import alongside the other view imports:
```tsx
import { ReportsView } from './components/ReportsView';
```
In the view-switch ternary chain, insert a rung before the final `) : (` fallback:
```tsx
                ) : currentView === 'noteEditor' ? (
                    <NoteEditorView />
                ) : currentView === 'reports' ? (
                    <ReportsView />
                ) : (
```

- [ ] **Step 4: Build**

Run: `npm run build`
Expected: PASS.

- [ ] **Step 5: Smoke-test navigation**

Run: `npm run dev`. Click **Reports** in the sidebar; the main area switches to the (empty) reports view with no errors. Close the app.

- [ ] **Step 6: Commit**

```bash
git add src/components/Sidebar.tsx src/App.tsx src/components/ReportsView.tsx
git commit -m "feat(nav): Reports sidebar item and view branch"
```

---

## Task 16: Reports view UI (Recharts)

**Files:**
- Modify: `src/components/ReportsView.tsx`

- [ ] **Step 1: Replace `src/components/ReportsView.tsx` with the full implementation**

```tsx
import { useEffect } from 'react';
import { useStore } from '../store';
import { ReportRangeMode } from '../types';
import { BarChart3, TrendingUp, TrendingDown } from 'lucide-react';
import { clsx } from 'clsx';
import {
    ResponsiveContainer,
    BarChart,
    Bar,
    XAxis,
    YAxis,
    CartesianGrid,
    Tooltip,
    ReferenceLine,
    Cell,
} from 'recharts';
import { format, parseISO } from 'date-fns';

const RANGES: { id: ReportRangeMode; label: string }[] = [
    { id: 'day', label: 'Day' },
    { id: 'week', label: 'Week' },
    { id: 'month', label: 'Month' },
    { id: 'year', label: 'Year' },
];

const COLORS = ['#3b82f6', '#f59e0b', '#22c55e', '#a855f7', '#ef4444', '#14b8a6', '#eab308'];

const fmtH = (m: number) =>
    m >= 60 ? `${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ''}` : `${m}m`;

export function ReportsView() {
    const { reportRange, reportData, reportLoading, setReportRange, loadReports } = useStore();

    useEffect(() => {
        loadReports();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const summary = reportData?.summary ?? null;
    const throughput = reportData?.throughput ?? [];
    const projects = reportData?.timeByProject ?? [];

    const throughputData = throughput.map((p) => ({ ...p, label: format(parseISO(p.date), 'EEE') }));
    const projectData = projects.map((p) => ({ name: p.project || 'No project', minutes: p.minutes }));
    const avg = throughput.length ? throughput.reduce((s, p) => s + p.completed, 0) / throughput.length : 0;
    const totalProjMinutes = projects.reduce((s, p) => s + p.minutes, 0);

    return (
        <div className="flex-1 flex flex-col h-full bg-surface-950">
            <div className="px-6 pt-10 pb-4 border-b border-surface-800/40 flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <BarChart3 className="w-5 h-5 text-accent-400" />
                    <h1 className="text-lg font-bold text-surface-100">Reports</h1>
                </div>
                <div className="flex gap-1">
                    {RANGES.map((r) => (
                        <button
                            key={r.id}
                            onClick={() => setReportRange(r.id)}
                            className={clsx(
                                'px-3 py-1.5 rounded-lg text-xs font-medium transition-all',
                                reportRange === r.id ? 'bg-accent-600 text-white' : 'bg-surface-800/60 text-surface-400 hover:text-surface-200',
                            )}
                        >
                            {r.label}
                        </button>
                    ))}
                </div>
            </div>

            <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-6">
                {reportLoading && <p className="text-sm text-surface-500">Loading…</p>}

                {summary && (
                    <>
                        <div className="grid grid-cols-4 gap-4">
                            <StatCard label="Completed" value={String(summary.completed)} delta={summary.delta.completed} />
                            <StatCard
                                label="Completion rate"
                                value={summary.completionRate == null ? '—' : `${Math.round(summary.completionRate * 100)}%`}
                                delta={summary.delta.completionRate == null ? null : Math.round(summary.delta.completionRate * 100)}
                                deltaSuffix="%"
                            />
                            <StatCard label="Focus time" value={fmtH(summary.focusMinutes)} delta={summary.delta.focusMinutes} deltaFormat={fmtH} />
                            <StatCard label="Top project" value={summary.topProject || '—'} sub={summary.topProject ? fmtH(summary.topProjectMinutes) : ''} />
                        </div>

                        <div className="grid grid-cols-[1.5fr_1fr] gap-4">
                            <Panel title="Tasks completed per day" subtitle={`avg ${avg.toFixed(1)}/day`}>
                                <ResponsiveContainer width="100%" height={220}>
                                    <BarChart data={throughputData} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
                                        <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
                                        <XAxis dataKey="label" stroke="#94a3b8" fontSize={11} tickLine={false} axisLine={false} />
                                        <YAxis stroke="#94a3b8" fontSize={11} tickLine={false} axisLine={false} allowDecimals={false} />
                                        <Tooltip
                                            cursor={{ fill: '#1e293b55' }}
                                            contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 8, fontSize: 12 }}
                                        />
                                        {avg > 0 && <ReferenceLine y={avg} stroke="#475569" strokeDasharray="4 4" />}
                                        <Bar dataKey="completed" fill="#3b82f6" radius={[4, 4, 0, 0]} />
                                    </BarChart>
                                </ResponsiveContainer>
                            </Panel>

                            <Panel title="Time by project" subtitle={fmtH(totalProjMinutes)}>
                                {projectData.length === 0 ? (
                                    <p className="text-xs text-surface-500 py-8 text-center">No tracked time yet. Use Focus mode to log time.</p>
                                ) : (
                                    <ResponsiveContainer width="100%" height={220}>
                                        <BarChart data={projectData} layout="vertical" margin={{ top: 4, right: 16, bottom: 0, left: 8 }}>
                                            <XAxis
                                                type="number"
                                                stroke="#94a3b8"
                                                fontSize={11}
                                                tickLine={false}
                                                axisLine={false}
                                                tickFormatter={(v: number) => `${Math.round(v / 60)}h`}
                                            />
                                            <YAxis type="category" dataKey="name" stroke="#94a3b8" fontSize={11} width={90} tickLine={false} axisLine={false} />
                                            <Tooltip
                                                cursor={{ fill: '#1e293b55' }}
                                                contentStyle={{ background: '#0f172a', border: '1px solid #334155', borderRadius: 8, fontSize: 12 }}
                                                formatter={(v: number) => fmtH(v)}
                                            />
                                            <Bar dataKey="minutes" radius={[0, 4, 4, 0]}>
                                                {projectData.map((_, i) => (
                                                    <Cell key={i} fill={COLORS[i % COLORS.length]} />
                                                ))}
                                            </Bar>
                                        </BarChart>
                                    </ResponsiveContainer>
                                )}
                            </Panel>
                        </div>

                        {!reportLoading && summary.completed === 0 && projects.length === 0 && (
                            <p className="text-sm text-surface-500 text-center py-8">
                                No activity in this range yet. Complete tasks and track focus time to see reports build up.
                            </p>
                        )}
                    </>
                )}
            </div>
        </div>
    );
}

function StatCard({
    label,
    value,
    sub,
    delta,
    deltaSuffix,
    deltaFormat,
}: {
    label: string;
    value: string;
    sub?: string;
    delta?: number | null;
    deltaSuffix?: string;
    deltaFormat?: (n: number) => string;
}) {
    const showDelta = delta !== undefined && delta !== null && delta !== 0;
    const up = (delta ?? 0) > 0;
    return (
        <div className="bg-surface-900 border border-surface-800/60 rounded-xl p-4">
            <div className="text-2xs uppercase tracking-wide text-surface-500">{label}</div>
            <div className="text-2xl font-bold text-surface-100 mt-1 truncate">{value}</div>
            {sub && <div className="text-2xs text-surface-500 mt-0.5">{sub}</div>}
            {showDelta && (
                <div className={clsx('text-2xs mt-1 flex items-center gap-1', up ? 'text-success-400' : 'text-danger-400')}>
                    {up ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
                    {up ? '+' : '−'}
                    {deltaFormat ? deltaFormat(Math.abs(delta!)) : Math.abs(delta!)}
                    {deltaSuffix || ''} vs prev
                </div>
            )}
        </div>
    );
}

function Panel({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
    return (
        <div className="bg-surface-900 border border-surface-800/60 rounded-xl p-4">
            <div className="flex items-center justify-between mb-3">
                <h3 className="text-sm font-medium text-surface-200">{title}</h3>
                {subtitle && <span className="text-2xs text-surface-500">{subtitle}</span>}
            </div>
            {children}
        </div>
    );
}
```

- [ ] **Step 2: Build**

Run: `npm run build`
Expected: PASS.

- [ ] **Step 3: Smoke-test reports end-to-end**

Run: `npm run dev`. Mark a few tasks done and run a Focus session or two (≥1 min each). Open **Reports**: the summary cards populate, the throughput bar chart shows completions per day with an average line, and Time-by-project shows horizontal bars. Switch Day/Week/Month/Year and confirm the data updates without errors. Close the app.

- [ ] **Step 4: Commit**

```bash
git add src/components/ReportsView.tsx
git commit -m "feat(reports): dashboard with throughput + time-by-project charts"
```

---

## Task 17: Final verification

**Files:** none (verification only)

- [ ] **Step 1: Full test suite**

Run: `npm run test:run`
Expected: PASS — all `src/lib/*.test.ts` green.

- [ ] **Step 2: Full production build**

Run: `npm run build`
Expected: PASS — `tsc` (renderer), `vite build`, and `tsc -p tsconfig.electron.json` all succeed.

- [ ] **Step 3: Manual acceptance pass** (`npm run dev`)

Verify the end-to-end story:
1. **Plan Today** opens the guided canvas at Review; phases advance; "Plan for today" moves tasks into Today; capacity meter reflects planned-vs-available and turns the warning color when over; Timebox drag schedules onto the timeline; Commit closes the flow.
2. Re-open the planner — it opens fresh at Review for today (no stale step state).
3. Complete tasks + run Focus sessions, then open **Reports**: throughput, completion rate (only days you committed show a %), focus time, and time-by-project all populate; range tabs work; empty ranges show the friendly empty state.
4. No `'todo'` status warnings in the console after rollover (leave a past-dated unfinished scheduled task and reload).

- [ ] **Step 4: Format**

Run: `npm run format`
Then commit any formatting changes:
```bash
git add -A
git commit -m "chore: format planning canvas + reports"
```

---

## Notes & deferrals
- **DnD between columns** (Sources → Today) is intentionally not wired; planning into Today uses the task's "Plan for today" action. Today → Timeline drag uses the existing App-level `DndContext`. Column-to-column drag is a future enhancement.
- **Completion rate** only has a denominator on days the user pressed "Commit day" (a `daily_plans` row); other days render "—", not 0%.
- **Tracked time** is Focus-mode sessions only. Time worked without a focus session is not counted in Time-by-project.
- **No historical backfill** — reports build up from first use of the new capture.
