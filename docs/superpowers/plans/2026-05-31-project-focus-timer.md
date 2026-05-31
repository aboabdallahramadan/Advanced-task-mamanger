# Project Focus Timer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user start a focus session on a whole project (not just a task), accumulating session time onto a persistent per-project total, with an open-ended (no-target) timer.

**Architecture:** Replace the task-only `focusMode.activeTaskId` with a unified focus target (`{ targetType: 'task' | 'project'; targetId }`). On pause/stop/switch, elapsed minutes flush to either the task's or the project's `actualTimeMinutes`. The overlay, always-on-top widget, and tray render whichever target is active. Project focus is launched from the ProjectView header and from a project switcher inside the overlay.

**Tech Stack:** React 18 + TypeScript (strict), Zustand, Electron 33, SQL.js, Tailwind, lucide-react.

> **No test framework is configured** (per CLAUDE.md). Each task's verification is a **TypeScript typecheck** plus, at the end, a **manual run-through** with `npm run dev`. Renderer check: `npx tsc --noEmit -p tsconfig.json`. Electron check: `npx tsc --noEmit -p tsconfig.electron.json`. Commit after each task.

---

## File Structure

| File | Responsibility | Touched in |
|------|----------------|-----------|
| `electron/database.ts` | New migration adding `projects.actual_time_minutes` | Task 1 |
| `electron/projectService.ts` | Persist/read `actualTimeMinutes` on projects | Task 1 |
| `electron/preload.ts` | IPC `projects.update` payload includes `actualTimeMinutes` | Task 2 |
| `src/types.ts` | `Project.actualTimeMinutes`; unified `focusMode` shape | Task 2, Task 3 |
| `src/store.ts` | Unified focus target, actions, helpers, delete guards | Task 3 |
| `src/components/TaskItem.tsx` | Task-focus highlight uses unified target | Task 3 |
| `src/components/WeeklyBoardView.tsx` | Task-focus highlight uses unified target | Task 3 |
| `src/components/ProjectView.tsx` | Task-row highlight (Task 3); header Focus button + project time stat (Task 5) | Task 3, Task 5 |
| `src/components/focus/FocusModeOverlay.tsx` | Generic target rendering + project switcher | Task 3, Task 4 |
| `public/focus-widget.html` | Hide "Done" button in project mode | Task 6 |

**Note on the spec:** the spec mentioned hiding a tray "Done" action, but the tray context menu in `electron/main.ts` has **no** Done item (only Pause/Resume, Stop, Open, Quit). The "Done" control exists only in the always-on-top widget (`focus-widget.html`), handled in Task 6. **`electron/main.ts` needs no changes** — its `updateTray` handler already renders a generic title. The renderer-side "no markDone for projects" safety lives in the overlay's `focus:done` handler (Task 3).

---

## Task 1: Persist project actual time (DB + service)

**Files:**
- Modify: `electron/database.ts` (append to `migrations` array, ends at line 232)
- Modify: `electron/projectService.ts`

- [ ] **Step 1: Add the migration**

In `electron/database.ts`, the `migrations` array currently ends with `009_notes_project_id` and closes with `];` at line 232. Add a new entry as the last element (before the closing `];`):

```ts
    {
        name: '010_add_project_actual_time',
        sql: `ALTER TABLE projects ADD COLUMN actual_time_minutes INTEGER DEFAULT 0`,
    },
```

- [ ] **Step 2: Add the field to the service `Project` interface**

In `electron/projectService.ts`, change the interface (lines 5–13):

```ts
export interface Project {
    id: string;
    name: string;
    color: string;
    emoji: string;
    order: number;
    actualTimeMinutes: number;
    createdAt: string;
    updatedAt: string;
}
```

- [ ] **Step 3: Map the new column in `mapRow`**

In `electron/projectService.ts`, change `mapRow` (lines 18–28):

```ts
    private mapRow(row: any): Project {
        return {
            id: row.id,
            name: row.name,
            color: row.color,
            emoji: row.emoji,
            order: row.sort_order,
            actualTimeMinutes: row.actual_time_minutes ?? 0,
            createdAt: row.created_at,
            updatedAt: row.updated_at,
        };
    }
```

- [ ] **Step 4: Accept `actualTimeMinutes` in `update`**

In `electron/projectService.ts`, change the `update` signature and add a `sets` clause (lines 74–81):

```ts
    update(id: string, updates: Partial<{ name: string; color: string; emoji: string; order: number; actualTimeMinutes: number }>): Project {
        const sets: string[] = [];
        const values: any[] = [];

        if (updates.name !== undefined) { sets.push('name = ?'); values.push(updates.name); }
        if (updates.color !== undefined) { sets.push('color = ?'); values.push(updates.color); }
        if (updates.emoji !== undefined) { sets.push('emoji = ?'); values.push(updates.emoji); }
        if (updates.order !== undefined) { sets.push('sort_order = ?'); values.push(updates.order); }
        if (updates.actualTimeMinutes !== undefined) { sets.push('actual_time_minutes = ?'); values.push(updates.actualTimeMinutes); }
```

(Leave the rest of `update` unchanged.)

- [ ] **Step 5: Typecheck electron**

Run: `npx tsc --noEmit -p tsconfig.electron.json`
Expected: no errors.

- [ ] **Step 6: Commit**

```bash
git add electron/database.ts electron/projectService.ts
git commit -m "added project actual-time column and service support"
```

---

## Task 2: Plumb `actualTimeMinutes` through IPC and renderer types

**Files:**
- Modify: `electron/preload.ts:53`
- Modify: `src/types.ts:55-63`

- [ ] **Step 1: Widen the preload `projects.update` type**

In `electron/preload.ts`, change line 53:

```ts
        update: (id: string, updates: { name?: string; color?: string; emoji?: string; order?: number; actualTimeMinutes?: number }) => ipcRenderer.invoke('projects:update', id, updates),
```

- [ ] **Step 2: Add the field to the renderer `Project` type**

In `src/types.ts`, change the `Project` interface (lines 55–63):

```ts
export interface Project {
    id: string;
    name: string;
    color: string;
    emoji: string;
    order: number;
    actualTimeMinutes?: number;
    createdAt: string;
    updatedAt: string;
}
```

- [ ] **Step 3: Typecheck both projects**

Run: `npx tsc --noEmit -p tsconfig.json`
Run: `npx tsc --noEmit -p tsconfig.electron.json`
Expected: no errors. (`store.ts`'s `updateProject(id, updates: Partial<Project>)` now accepts `actualTimeMinutes` automatically.)

- [ ] **Step 4: Commit**

```bash
git add electron/preload.ts src/types.ts
git commit -m "plumbed project actualTimeMinutes through IPC and renderer types"
```

---

## Task 3: Unified focus target end-to-end (store + consumers + overlay)

This is the atomic refactor: changing the `focusMode` shape requires every reader to change in the same commit so the build stays green. After this task, **task focus behaves exactly as before**, and **project focus renders correctly when triggered** — but the launch UI (header button / switcher) arrives in Tasks 4–5, so project focus is not yet reachable by the user.

**Files:**
- Modify: `src/types.ts` (focusMode shape)
- Modify: `src/store.ts` (interface decls, initial state, actions, delete guards)
- Modify: `src/components/TaskItem.tsx`
- Modify: `src/components/WeeklyBoardView.tsx`
- Modify: `src/components/ProjectView.tsx` (task-row highlight only)
- Modify: `src/components/focus/FocusModeOverlay.tsx`

- [ ] **Step 1: Change the `focusMode` type in `src/types.ts`**

Find the `focusMode` block inside the store-state interface in `src/types.ts` (the same shape currently declared in `store.ts` ~line 68). If `focusMode` is declared in `store.ts` only, skip this step. In `store.ts` the interface declaration is at lines 68–72 — change it there:

```ts
    focusMode: {
        targetType: 'task' | 'project' | null;
        targetId: string | null;
        isPlaying: boolean;
        sessionStartTime: number | null;
    };
```

- [ ] **Step 2: Add new action declarations in the store interface**

In `src/store.ts`, replace the `// Focus Mode Actions` declaration block (lines 129–132):

```ts
    // Focus Mode Actions
    startFocusSession: (taskId: string) => void;
    startProjectFocus: (projectId: string) => void;
    pauseFocusSession: () => void;
    stopFocusSession: () => Promise<void>;
    isTaskFocused: (taskId: string) => boolean;
    isProjectFocused: (projectId: string) => boolean;
```

- [ ] **Step 3: Change the initial state**

In `src/store.ts`, replace the initial `focusMode` (lines 222–226):

```ts
    focusMode: {
        targetType: null,
        targetId: null,
        isPlaying: false,
        sessionStartTime: null,
    },
```

- [ ] **Step 4: Replace the focus-action implementations**

In `src/store.ts`, replace the entire block from `// Focus Mode Actions` (line 872) through the end of `stopFocusSession` (line 929) with:

```ts
    // Focus Mode Actions
    startFocusSession: (taskId: string) => {
        get().pauseFocusSession(); // flush any running session first
        set({
            focusMode: {
                targetType: 'task',
                targetId: taskId,
                isPlaying: true,
                sessionStartTime: Date.now(),
            }
        });
    },

    startProjectFocus: (projectId: string) => {
        get().pauseFocusSession(); // flush any running session first
        set({
            focusMode: {
                targetType: 'project',
                targetId: projectId,
                isPlaying: true,
                sessionStartTime: Date.now(),
            }
        });
    },

    pauseFocusSession: () => {
        const { focusMode, updateTask, updateProject, tasks, projects } = get();
        if (!focusMode.targetType || !focusMode.targetId || !focusMode.isPlaying || !focusMode.sessionStartTime) return;

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

        set({
            focusMode: {
                ...focusMode,
                isPlaying: false,
                sessionStartTime: null,
            }
        });
    },

    stopFocusSession: async () => {
        const { focusMode, updateTask, updateProject, tasks, projects } = get();
        if (!focusMode.targetType || !focusMode.targetId) return;

        if (focusMode.isPlaying && focusMode.sessionStartTime) {
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
        }

        set({
            focusMode: {
                targetType: null,
                targetId: null,
                isPlaying: false,
                sessionStartTime: null,
            }
        });
    },

    isTaskFocused: (taskId: string) => {
        const { focusMode } = get();
        return focusMode.targetType === 'task' && focusMode.targetId === taskId;
    },

    isProjectFocused: (projectId: string) => {
        const { focusMode } = get();
        return focusMode.targetType === 'project' && focusMode.targetId === projectId;
    },
```

- [ ] **Step 5: Clear focus when the focused project is deleted**

In `src/store.ts`, replace the start of `deleteProject` (lines 540–542) so it clears focus before deleting:

```ts
    deleteProject: async (id) => {
        try {
            const { focusMode } = get();
            if (focusMode.targetType === 'project' && focusMode.targetId === id) {
                set({ focusMode: { targetType: null, targetId: null, isPlaying: false, sessionStartTime: null } });
            }
            await window.api.projects.delete(id);
```

(Leave the rest of `deleteProject` unchanged.)

- [ ] **Step 6: Clear focus when the focused task is deleted**

In `src/store.ts`, replace the start of `deleteTask` (lines 326–328):

```ts
    deleteTask: async (id: string) => {
        try {
            const { focusMode } = get();
            if (focusMode.targetType === 'task' && focusMode.targetId === id) {
                set({ focusMode: { targetType: null, targetId: null, isPlaying: false, sessionStartTime: null } });
            }
            await window.api.tasks.delete(id);
```

(Leave the rest of `deleteTask` unchanged.)

- [ ] **Step 7: Update `TaskItem.tsx` highlight**

In `src/components/TaskItem.tsx`, the component destructures `focusMode` from the store. Replace the two `focusMode.activeTaskId === task.id` checks (lines 239 and 245):

Line 239:
```tsx
                                focusMode.targetType === 'task' && focusMode.targetId === task.id
```

Line 245:
```tsx
                            <Play className="w-3.5 h-3.5" fill={focusMode.targetType === 'task' && focusMode.targetId === task.id ? "currentColor" : "none"} />
```

- [ ] **Step 8: Update `WeeklyBoardView.tsx` highlight**

In `src/components/WeeklyBoardView.tsx`, replace line 262:

```tsx
                                focusActiveId={focusMode.targetType === 'task' ? focusMode.targetId : null}
```

- [ ] **Step 9: Update `ProjectView.tsx` task-row highlight**

In `src/components/ProjectView.tsx`, replace line 313:

```tsx
                                            isFocused={focusMode.targetType === 'task' && focusMode.targetId === task.id}
```

- [ ] **Step 10: Make the overlay target-aware — destructure**

In `src/components/focus/FocusModeOverlay.tsx`, replace the store destructure (lines 8–15):

```tsx
    const {
        tasks,
        projects,
        focusMode,
        pauseFocusSession,
        startFocusSession,
        startProjectFocus,
        stopFocusSession,
        markDone
    } = useStore();
```

- [ ] **Step 11: Resolve the active target**

In `src/components/focus/FocusModeOverlay.tsx`, replace line 63 (`const activeTask = tasks.find(...)`) with a generic target resolver:

```tsx
    const focusedTask = focusMode.targetType === 'task' ? tasks.find(t => t.id === focusMode.targetId) : undefined;
    const focusedProject = focusMode.targetType === 'project' ? projects.find(p => p.id === focusMode.targetId) : undefined;
    const target = focusedTask
        ? {
            kind: 'task' as const,
            title: focusedTask.title,
            accumulatedMinutes: focusedTask.actualTimeMinutes || 0,
            plannedMinutes: focusedTask.durationMinutes || 0,
        }
        : focusedProject
        ? {
            kind: 'project' as const,
            title: `${focusedProject.emoji} ${focusedProject.name}`,
            accumulatedMinutes: focusedProject.actualTimeMinutes || 0,
            plannedMinutes: 0,
        }
        : null;

    const resumeFocus = () => {
        if (focusMode.targetType === 'task' && focusMode.targetId) startFocusSession(focusMode.targetId);
        else if (focusMode.targetType === 'project' && focusMode.targetId) startProjectFocus(focusMode.targetId);
    };
```

- [ ] **Step 12: Update the timer effect**

In `src/components/focus/FocusModeOverlay.tsx`, replace the timer `useEffect` (lines 66–78):

```tsx
    useEffect(() => {
        if (!focusMode.isPlaying || !focusMode.sessionStartTime || !target) {
            return;
        }

        const interval = setInterval(() => {
            const currentSessionMs = Date.now() - focusMode.sessionStartTime!;
            const totalMs = target.accumulatedMinutes * 60000 + currentSessionMs;
            setElapsedSeconds(Math.floor(totalMs / 1000));
        }, 1000);

        return () => clearInterval(interval);
    }, [focusMode.isPlaying, focusMode.sessionStartTime, target?.accumulatedMinutes]);
```

- [ ] **Step 13: Update planned/progress derivations**

In `src/components/focus/FocusModeOverlay.tsx`, replace line 90:

```tsx
    const plannedMinutes = target?.plannedMinutes || 0;
```

- [ ] **Step 14: Update the widget show/hide effect**

In `src/components/focus/FocusModeOverlay.tsx`, replace the widget show/hide `useEffect` (lines 97–108):

```tsx
    useEffect(() => {
        if (target) {
            window.api?.focus?.showWidget();
        } else {
            window.api?.focus?.hideWidget();
        }

        return () => {
            window.api?.focus?.hideWidget();
        };
    }, [!!target]);
```

- [ ] **Step 15: Update the widget state push**

In `src/components/focus/FocusModeOverlay.tsx`, replace the `sendWidgetState` `useEffect` (lines 112–122):

```tsx
    useEffect(() => {
        if (!target) return;

        window.api?.focus?.sendWidgetState({
            taskTitle: target.title,
            isPlaying: focusMode.isPlaying,
            sessionStartTime: focusMode.sessionStartTime,
            accumulatedMinutes: target.accumulatedMinutes,
            plannedMinutes: target.plannedMinutes,
            canComplete: target.kind === 'task',
        });
    }, [focusMode.targetId, target?.title, target?.accumulatedMinutes, target?.plannedMinutes, target?.kind, focusMode.isPlaying, focusMode.sessionStartTime]);
```

- [ ] **Step 16: Update the tray push**

In `src/components/focus/FocusModeOverlay.tsx`, replace the tray `useEffect` (lines 125–137):

```tsx
    useEffect(() => {
        if (!target) {
            window.api?.focus?.updateTray({ taskTitle: null, elapsed: null, isPlaying: false });
            return;
        }

        const elapsed = formatTime(elapsedSeconds);
        window.api?.focus?.updateTray({
            taskTitle: target.title,
            elapsed,
            isPlaying: focusMode.isPlaying,
        });
    }, [target?.title, target?.kind, elapsedSeconds, focusMode.isPlaying]);
```

- [ ] **Step 17: Make the IPC command handlers target-aware**

In `src/components/focus/FocusModeOverlay.tsx`, replace the `handleToggle`, `handleStop`, `handleDone`, and `handleResync` definitions inside the listener `useEffect` (lines 146–187):

```tsx
        const handleToggle = () => {
            const state = useStore.getState();
            const fm = state.focusMode;
            if (!fm.targetId) return;
            if (fm.isPlaying) {
                state.pauseFocusSession();
            } else if (fm.targetType === 'task') {
                state.startFocusSession(fm.targetId);
            } else if (fm.targetType === 'project') {
                state.startProjectFocus(fm.targetId);
            }
        };

        const handleStop = () => {
            useStore.getState().stopFocusSession();
        };

        const handleDone = async () => {
            const state = useStore.getState();
            const fm = state.focusMode;
            if (!fm.targetId) return;
            await state.stopFocusSession();
            // "Done" only marks tasks complete; projects are never "done".
            if (fm.targetType === 'task') {
                await state.markDone(fm.targetId);
            }
        };

        window.api?.on('focus:togglePlayPause', handleToggle);
        window.api?.on('focus:stop', handleStop);
        window.api?.on('focus:done', handleDone);

        const handleResync = () => {
            const s = useStore.getState();
            const fm = s.focusMode;
            const t = fm.targetType === 'task'
                ? s.tasks.find(x => x.id === fm.targetId)
                : undefined;
            const p = fm.targetType === 'project'
                ? s.projects.find(x => x.id === fm.targetId)
                : undefined;
            if (t) {
                window.api?.focus?.sendWidgetState({
                    taskTitle: t.title,
                    isPlaying: fm.isPlaying,
                    sessionStartTime: fm.sessionStartTime,
                    accumulatedMinutes: t.actualTimeMinutes || 0,
                    plannedMinutes: t.durationMinutes || 0,
                    canComplete: true,
                });
            } else if (p) {
                window.api?.focus?.sendWidgetState({
                    taskTitle: `${p.emoji} ${p.name}`,
                    isPlaying: fm.isPlaying,
                    sessionStartTime: fm.sessionStartTime,
                    accumulatedMinutes: p.actualTimeMinutes || 0,
                    plannedMinutes: 0,
                    canComplete: false,
                });
            }
        };
        window.api?.on('focus:resyncWidget', handleResync);
```

- [ ] **Step 18: Guard render and the task title**

In `src/components/focus/FocusModeOverlay.tsx`, replace line 197:

```tsx
    if (!target) return null;
```

Then replace the `handleComplete` helper (lines 199–202) to be task-scoped:

```tsx
    const handleComplete = async () => {
        await stopFocusSession();
        if (target.kind === 'task' && focusMode.targetId) await markDone(focusMode.targetId);
    };
```

- [ ] **Step 19: Render the target title in both views**

In `src/components/focus/FocusModeOverlay.tsx`, the minimized view does not show a title, so no change there. In the full view, replace the title heading (line 275):

```tsx
                    <h3 dir={getTextDirection(target.title)} style={getDirectionStyle(target.title)} className="text-surface-100 font-medium truncate mb-3" title={target.title}>{target.title}</h3>
```

- [ ] **Step 20: Make resume buttons target-aware and hide Complete for projects**

In `src/components/focus/FocusModeOverlay.tsx`, replace the minimized-view resume button (line 234):

```tsx
                        <button onClick={resumeFocus} className="p-1 rounded-md text-accent-400 hover:text-accent-300 hover:bg-accent-600/20 transition-colors">
                            <Play className="w-3.5 h-3.5" />
                        </button>
```

Replace the full-view resume button (line 304):

```tsx
                            <button onClick={resumeFocus} className="flex-1 py-2.5 flex justify-center items-center gap-2 rounded-xl bg-accent-600/20 hover:bg-accent-600/30 text-accent-400 transition-all font-medium text-sm" title="Resume">
                                <Play className="w-4 h-4" /> Resume
                            </button>
```

Replace the full-view Complete button (lines 313–315) so it only renders for task targets:

```tsx
                        {target.kind === 'task' && (
                            <button onClick={handleComplete} className="p-2.5 flex justify-center items-center rounded-xl bg-success-600/20 hover:bg-success-600/30 text-success-500 transition-all" title="Complete">
                                <CheckCircle2 className="w-4 h-4" />
                            </button>
                        )}
```

- [ ] **Step 21: Typecheck the renderer**

Run: `npx tsc --noEmit -p tsconfig.json`
Expected: no errors.

- [ ] **Step 22: Commit**

```bash
git add src/types.ts src/store.ts src/components/TaskItem.tsx src/components/WeeklyBoardView.tsx src/components/ProjectView.tsx src/components/focus/FocusModeOverlay.tsx
git commit -m "unified focus target to support task or project sessions"
```

---

## Task 4: Project switcher inside the focus overlay

Add a dropdown in the overlay's full view that lists projects and starts/switches a project focus session, satisfying "change the focused project from anywhere."

**Files:**
- Modify: `src/components/focus/FocusModeOverlay.tsx`

- [ ] **Step 1: Add icon import and switcher open-state**

In `src/components/focus/FocusModeOverlay.tsx`, change the lucide import (line 3) to add `FolderOpen` and `ChevronDown`:

```tsx
import { Play, Pause, Square, CheckCircle2, GripVertical, Minimize2, Maximize2, FolderOpen, ChevronDown } from 'lucide-react';
```

Add a switcher open-state next to the existing `isMinimized` state (after line 18, `const [isMinimized, setIsMinimized] = useState(false);`):

```tsx
    const [switcherOpen, setSwitcherOpen] = useState(false);
```

- [ ] **Step 2: Render the switcher in the full-view header**

In `src/components/focus/FocusModeOverlay.tsx`, the full view's drag-handle header ends with the Minimize button. Replace the Minimize button block (lines 265–271) with a wrapper that adds the project switcher beside it:

```tsx
                    <div className="flex items-center gap-1">
                        <div className="relative">
                            <button
                                onClick={() => setSwitcherOpen(o => !o)}
                                className="flex items-center gap-1 p-1 rounded-md text-surface-500 hover:text-surface-200 hover:bg-surface-800 transition-colors"
                                title="Focus on a project"
                            >
                                <FolderOpen className="w-3.5 h-3.5" />
                                <ChevronDown className="w-3 h-3" />
                            </button>
                            {switcherOpen && (
                                <>
                                <div className="fixed inset-0 z-[10000]" onClick={() => setSwitcherOpen(false)} />
                                <div className="absolute right-0 top-8 z-[10001] w-52 max-h-64 overflow-y-auto custom-scrollbar bg-surface-900 border border-surface-700/60 rounded-xl shadow-2xl shadow-black/50 py-1">
                                    {projects.length === 0 ? (
                                        <div className="px-3 py-2 text-xs text-surface-500">No projects yet</div>
                                    ) : (
                                        projects.map(p => (
                                            <button
                                                key={p.id}
                                                onClick={() => { startProjectFocus(p.id); setSwitcherOpen(false); }}
                                                className={clsx(
                                                    "w-full flex items-center gap-2 px-3 py-1.5 text-left text-sm transition-colors",
                                                    focusMode.targetType === 'project' && focusMode.targetId === p.id
                                                        ? "text-accent-400 bg-accent-600/10"
                                                        : "text-surface-200 hover:bg-surface-800"
                                                )}
                                            >
                                                <span className="text-base leading-none">{p.emoji}</span>
                                                <span className="truncate">{p.name}</span>
                                            </button>
                                        ))
                                    )}
                                </div>
                                </>
                            )}
                        </div>
                        <button
                            onClick={() => setIsMinimized(true)}
                            className="p-1 rounded-md text-surface-500 hover:text-surface-200 hover:bg-surface-800 transition-colors"
                            title="Minimize"
                        >
                            <Minimize2 className="w-3.5 h-3.5" />
                        </button>
                    </div>
```

- [ ] **Step 3: Typecheck the renderer**

Run: `npx tsc --noEmit -p tsconfig.json`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add src/components/focus/FocusModeOverlay.tsx
git commit -m "added project switcher to the focus overlay"
```

---

## Task 5: Launch project focus from the ProjectView header

Add a play/pause Focus button to the project header and show the project's accumulated focus time as a stat.

**Files:**
- Modify: `src/components/ProjectView.tsx`

- [ ] **Step 1: Add icons and store actions to the component**

In `src/components/ProjectView.tsx`, change the lucide import (lines 4–18) to add `Pause` and `Timer`:

```tsx
import {
    Check,
    Plus,
    Settings,
    Clock,
    Calendar,
    Play,
    Pause,
    Timer,
    ArrowLeft,
    CheckCircle2,
    GripVertical,
    FileText,
    StickyNote,
    Search,
    X,
} from 'lucide-react';
```

In the store destructure (lines 49–67), add `startProjectFocus`, `pauseFocusSession`, and `isProjectFocused` (the block already pulls `startFocusSession` and `focusMode`):

```tsx
        startFocusSession,
        startProjectFocus,
        pauseFocusSession,
        isProjectFocused,
        focusMode,
```

- [ ] **Step 2: Add the Focus button to the header**

In `src/components/ProjectView.tsx`, the header's title row ends with the "Edit project" Settings button (lines 197–203). Insert a Focus button immediately before that Settings button:

```tsx
                    <button
                        onClick={() => {
                            if (isProjectFocused(project.id) && focusMode.isPlaying) {
                                pauseFocusSession();
                            } else {
                                startProjectFocus(project.id);
                            }
                        }}
                        className={clsx(
                            "p-2 rounded-lg transition-all",
                            isProjectFocused(project.id)
                                ? "bg-accent-500 text-white animate-pulse shadow-glow"
                                : "text-surface-400 hover:text-accent-400 hover:bg-surface-800/60"
                        )}
                        title={isProjectFocused(project.id) && focusMode.isPlaying ? "Pause project focus" : "Focus on this project"}
                    >
                        {isProjectFocused(project.id) && focusMode.isPlaying
                            ? <Pause className="w-4 h-4" />
                            : <Play className="w-4 h-4" />}
                    </button>
```

- [ ] **Step 3: Show accumulated project focus time in the stats bar**

In `src/components/ProjectView.tsx`, the stats bar has a "tracked" stat ending at line 219. Insert a new "focused" stat immediately after that closing `</div>` (after line 219, before the `activeTasks.length > 0` progress block at line 220):

```tsx
                    <div className="flex items-center gap-2 text-xs text-surface-400">
                        <Timer className="w-3.5 h-3.5" />
                        <span>
                            {Math.floor((project.actualTimeMinutes || 0) / 60)}h {(project.actualTimeMinutes || 0) % 60}m focused
                        </span>
                    </div>
```

- [ ] **Step 4: Typecheck the renderer**

Run: `npx tsc --noEmit -p tsconfig.json`
Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add src/components/ProjectView.tsx
git commit -m "added project focus launch button and focused-time stat to project header"
```

---

## Task 6: Hide the widget "Done" button in project mode

The always-on-top widget receives `canComplete` (sent since Task 3). Hide its Done button when focusing a project.

**Files:**
- Modify: `public/focus-widget.html`

- [ ] **Step 1: Toggle the Done button on state**

In `public/focus-widget.html`, inside the `ipcRenderer.on('focus:state', ...)` handler, add a `canComplete` line. Replace the block that maps incoming fields (lines 308–312):

```js
            if (data.taskTitle !== undefined) state.taskTitle = data.taskTitle;
            if (data.isPlaying !== undefined) state.isPlaying = data.isPlaying;
            if (data.sessionStartTime !== undefined) state.sessionStartTime = data.sessionStartTime;
            if (data.accumulatedMinutes !== undefined) state.accumulatedMinutes = data.accumulatedMinutes;
            if (data.plannedMinutes !== undefined) state.plannedMinutes = data.plannedMinutes;
            if (data.canComplete !== undefined) btnDone.classList.toggle('hidden', data.canComplete === false);
```

(`btnDone` and the `.hidden` CSS class already exist in this file.)

- [ ] **Step 2: Typecheck (sanity) and verify the file**

Run: `npx tsc --noEmit -p tsconfig.json`
Expected: no errors (this HTML is not compiled, but the renderer must still typecheck clean).

- [ ] **Step 3: Commit**

```bash
git add public/focus-widget.html
git commit -m "hid widget Done button during project focus sessions"
```

---

## Task 7: Manual verification and full build

**Files:** none (verification only)

- [ ] **Step 1: Full build**

Run: `npm run build`
Expected: `tsc`, `vite build`, and `tsc -p tsconfig.electron.json` all succeed with no errors.

- [ ] **Step 2: Run the app**

Run: `npm run dev`

- [ ] **Step 3: Verify project focus (manual checklist)**

- Open a project → header shows a **Play** Focus button and a **"0h 0m focused"** stat.
- Click Focus → overlay appears showing **emoji + project name**, timer counting up, **no progress bar**, and **no green Complete (✓) button**. Header button switches to **Pause** and pulses.
- Let it run, click **Pause** (overlay or header) → after ≥1 minute the header "focused" stat increases by the elapsed minutes.
- Open the overlay's **project switcher** (folder icon) → pick a different project → time is flushed to the first project; the overlay now shows the new project and starts fresh from its own accumulated total.
- Start a **task** focus (e.g. via a task row's play button) while a project session runs → the project session's time is flushed first; the overlay shows the task with its progress bar and the Complete (✓) button returns.
- The always-on-top widget (Ctrl+Shift+F) and tray tooltip show the project name; the widget shows **no Done** button in project mode.
- **Restart the app** → reopen the project → the "focused" stat persists (DB column).
- **Delete** the focused project → the overlay disappears and no timer keeps running.

- [ ] **Step 4: Format**

Run: `npm run format`

- [ ] **Step 5: Commit any formatting changes**

```bash
git add -A
git commit -m "formatted project focus timer changes"
```

---

## Self-Review

**Spec coverage:**
- Track time to the project → Tasks 1–3 (DB column, flush to `project.actualTimeMinutes` in pause/stop).
- Open-ended / no target → Task 3 (`plannedMinutes: 0` for projects; overlay/widget already hide the bar at 0).
- Launch from project header → Task 5. Launch/switch from overlay picker → Task 4.
- Hide Complete in project mode → Task 3 (overlay) + Task 6 (widget Done).
- Show accumulated project time in header → Task 5.
- Unified single-session model; switching flushes time → Task 3 (`startFocusSession`/`startProjectFocus` flush via `pauseFocusSession`).
- Electron widget/tray reuse → Task 3 (payloads) + Task 6 (Done hide). `main.ts` unchanged (no tray Done exists).
- Edge cases: delete focused project/task clears session → Task 3 (steps 5–6); no persisted-state migration (focusMode in-memory) → inherent.

**Type consistency:** `focusMode` = `{ targetType: 'task'|'project'|null; targetId: string|null; isPlaying; sessionStartTime }` used identically across `types.ts`/`store.ts`/overlay/consumers. Project field `actualTimeMinutes` is `number` in `electron/projectService.ts` and `actualTimeMinutes?: number` in `src/types.ts` (optional renderer-side; the service always returns a value). Action names `startProjectFocus`, `isTaskFocused`, `isProjectFocused` match between interface decls (Task 3 Step 2) and implementations (Step 4). Widget payload key `canComplete` matches between sender (Task 3 Steps 15/17) and consumer (Task 6).

**Placeholder scan:** none — every code step shows complete content.
