import { create } from 'zustand';
import {
  Task,
  Subtask,
  ViewMode,
  Project,
  NoteGroup,
  Note,
  RecurrenceFrequency,
  RecurrenceEndType,
  RecurrenceRule,
  PlanningPhase,
  ReportRangeMode,
  ReportSummary,
  ThroughputPoint,
  ProjectTime,
} from './types';
import { format, addDays, startOfWeek, endOfWeek } from 'date-fns';
import { sessionMinutes } from './lib/focusSession';
import { getRange, getPreviousRange, daysInRange } from './lib/dateRange';
import { summarize, throughputByDay, timeByProject as timeByProjectFn } from './lib/reports';
import type { DataClient } from './data/DataClient';

// ── DataClient injection seam ───────────────────────────────
// The host (AppRoot, built per app entry) injects the concrete HttpDataClient
// after auth. Every data action reads it via dc(); calling a data action before
// injection is a programming error (auth gates the app, so this never happens
// in normal flow) and throws a clear message.
let _dataClient: DataClient | null = null;

/** Inject the DataClient. Called once by AppRoot after the client is built. */
export function setDataClient(client: DataClient): void {
  _dataClient = client;
}

/** Accessor used by every store data action. */
function dc(): DataClient {
  if (!_dataClient) {
    throw new Error('DataClient not initialized — call setDataClient() before using the store');
  }
  return _dataClient;
}

function stripHtml(html: string): string {
  return html
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

// Monotonic request token for loadReports. Rapidly switching ranges can let an older
// IPC response resolve after a newer one; we only apply the result whose token is latest.
let reportRequestToken = 0;

/**
 * Internal helper — not part of the public store API.
 * Logs a completed focus session to the database. Minutes are derived from
 * startMs/endMs via sessionMinutes() so callers cannot pass stale values.
 * Only called from pauseFocusSession and stopFocusSession.
 */
function logFocusSession(
  get: () => AppState,
  targetType: 'task' | 'project',
  targetId: string,
  startMs: number,
  endMs: number,
): void {
  const minutes = sessionMinutes(startMs, endMs);
  if (minutes <= 0) return;
  const { tasks, projects } = get();
  let taskId: string | null = null;
  let project = '';
  if (targetType === 'task') {
    taskId = targetId;
    const t = tasks.find((tt) => tt.id === targetId);
    project = (t?.projectId ? projects.find((p) => p.id === t.projectId)?.name : '') || '';
  } else {
    project = projects.find((p) => p.id === targetId)?.name || '';
  }
  dc()
    .focusSessions.add({
      taskId,
      project,
      startedAt: new Date(startMs).toISOString(),
      endedAt: new Date(endMs).toISOString(),
      minutes,
      date: format(new Date(startMs), 'yyyy-MM-dd'),
    })
    .catch((e) => console.error('Failed to log focus session:', e));
}

interface AppState {
  // Tasks
  tasks: Task[];
  loading: boolean;

  // Projects
  projects: Project[];
  selectedProjectId: string | null;
  projectDialog: {
    isOpen: boolean;
    mode: 'create' | 'edit';
    projectId: string | null;
  };

  // Notes
  noteGroups: NoteGroup[];
  selectedNoteGroupId: string | null;
  selectedNoteId: string | null;
  noteEditorReturnView: ViewMode | null;
  currentNotes: Note[];
  noteGroupDialog: {
    isOpen: boolean;
    mode: 'create' | 'edit';
    groupId: string | null;
    defaultProjectId?: string | null;
  };
  projectNotes: Note[];
  allNotes: Note[];
  projectActiveTab: 'tasks' | 'notes';
  notesCollapsed: boolean;
  projectsCollapsed: boolean;

  // View
  currentView: ViewMode;
  selectedDate: string;
  selectedTaskIds: Set<string>;
  editingTaskId: string | null;
  searchQuery: string;
  searchOpen: boolean;

  // Timeline
  timeIncrement: number; // 5, 10, or 15
  workStartHour: number;
  workEndHour: number;
  allowOverlaps: boolean;

  // UI
  sidebarCollapsed: boolean;
  quickAddOpen: boolean;
  settingsOpen: boolean;
  taskDialog: {
    isOpen: boolean;
    mode: 'create' | 'edit';
    taskId: string | null; // null for create, task id for edit
  };
  planningFlow: {
    isOpen: boolean;
    phase: PlanningPhase;
    targetDate: string; // YYYY-MM-DD
    commitError: string | null; // non-null when the last commitDay call failed
  };
  focusMode: {
    targetType: 'task' | 'project' | null;
    targetId: string | null;
    isPlaying: boolean;
    sessionStartTime: number | null;
  };

  // Actions
  loadTasks: () => Promise<void>;
  loadTasksByDate: (date: string) => Promise<void>;
  createTask: (task: Partial<Task>) => Promise<Task | null>;
  updateTask: (id: string, updates: Partial<Task>) => Promise<void>;
  deleteTask: (id: string) => Promise<void>;
  reorderTasks: (tasks: { id: string; order: number }[]) => Promise<void>;
  scheduleTask: (id: string, start: string, end: string) => Promise<void>;
  unscheduleTask: (id: string) => Promise<void>;
  markDone: (id: string) => Promise<void>;
  moveToToday: (id: string) => Promise<void>;
  moveToBacklog: (id: string) => Promise<void>;
  archiveTask: (id: string) => Promise<void>;

  // Recurrence Actions
  createRecurringTask: (
    task: Partial<Task>,
    rule: {
      frequency: RecurrenceFrequency;
      interval: number;
      daysOfWeek: number[];
      endType: RecurrenceEndType;
      endCount?: number;
      endDate?: string;
    },
  ) => Promise<Task | null>;
  updateRecurrenceSeries: (ruleId: string, updates: Partial<Task>) => Promise<void>;
  deleteRecurrenceSeries: (ruleId: string) => Promise<void>;
  deleteRecurrenceSeriesFuture: (ruleId: string, fromDate: string) => Promise<void>;
  detachRecurrenceInstance: (taskId: string) => Promise<void>;
  ensureRecurrenceInstances: (startDate: string, endDate: string) => Promise<void>;

  // Subtask Actions
  createSubtask: (taskId: string, title: string) => Promise<Subtask | null>;
  updateSubtask: (
    taskId: string,
    subtaskId: string,
    updates: { title?: string; completed?: boolean; order?: number },
  ) => Promise<void>;
  deleteSubtask: (taskId: string, subtaskId: string) => Promise<void>;

  // Project Actions
  loadProjects: () => Promise<void>;
  createProject: (input: {
    name: string;
    color?: string;
    emoji?: string;
  }) => Promise<Project | null>;
  updateProject: (id: string, updates: Partial<Project>) => Promise<void>;
  deleteProject: (id: string) => Promise<void>;
  openProjectDialog: (mode: 'create' | 'edit', projectId?: string) => void;
  closeProjectDialog: () => void;
  selectProject: (projectId: string) => void;

  setCurrentView: (view: ViewMode) => void;
  setSelectedDate: (date: string) => void;
  toggleTaskSelection: (id: string) => void;
  selectTask: (id: string) => void;
  clearSelection: () => void;
  setEditingTaskId: (id: string | null) => void;
  setSearchQuery: (query: string) => void;
  setSearchOpen: (open: boolean) => void;
  setSidebarCollapsed: (collapsed: boolean) => void;
  setTimeIncrement: (inc: number) => void;
  setQuickAddOpen: (isOpen: boolean) => void;
  setSettingsOpen: (open: boolean) => void;
  setWorkHours: (start: number, end: number) => void;
  openTaskDialog: (mode: 'create' | 'edit', taskId?: string) => void;
  closeTaskDialog: () => void;
  startPlanningFlow: () => void;
  setPlanningPhase: (phase: PlanningPhase) => void;
  closePlanningFlow: () => void;
  commitDay: () => Promise<void>;
  planForDate: (id: string, date: string) => Promise<void>;
  leftoverTasks: (beforeDate: string) => Task[];
  plannedForDate: (date: string) => Task[];
  unscheduledPlannedTasks: (date: string) => Task[];

  // Focus Mode Actions
  startFocusSession: (taskId: string) => void;
  startProjectFocus: (projectId: string) => void;
  pauseFocusSession: () => void;
  stopFocusSession: () => Promise<void>;
  isTaskFocused: (taskId: string) => boolean;
  isProjectFocused: (projectId: string) => boolean;

  // Settings Persistence
  loadSettings: () => Promise<void>;
  saveSettings: () => Promise<void>;

  // Note Group Actions
  loadNoteGroups: () => Promise<void>;
  createNoteGroup: (input: {
    name: string;
    emoji?: string;
    projectId?: string;
  }) => Promise<NoteGroup | null>;
  updateNoteGroup: (id: string, updates: Partial<NoteGroup>) => Promise<void>;
  deleteNoteGroup: (id: string) => Promise<void>;
  openNoteGroupDialog: (
    mode: 'create' | 'edit',
    groupId?: string,
    defaultProjectId?: string,
  ) => void;
  closeNoteGroupDialog: () => void;
  selectNoteGroup: (groupId: string) => void;
  reorderNoteGroups: (items: { id: string; order: number }[]) => Promise<void>;

  // Note Actions
  loadAllNotes: () => Promise<void>;
  loadNotesByProject: (projectId: string) => Promise<void>;
  createProjectNote: (projectId: string) => Promise<Note | null>;
  loadNotesByGroup: (groupId: string) => Promise<void>;
  createNote: (groupId: string) => Promise<Note | null>;
  updateNote: (
    id: string,
    updates: Partial<{
      title: string;
      content: string;
      groupId: string;
      projectId: string;
      order: number;
    }>,
  ) => Promise<void>;
  deleteNote: (id: string) => Promise<void>;
  selectNote: (noteId: string, returnView?: ViewMode) => void;
  reorderNotes: (items: { id: string; order: number }[]) => Promise<void>;
  setProjectActiveTab: (tab: 'tasks' | 'notes') => void;
  setNotesCollapsed: (collapsed: boolean) => void;
  setProjectsCollapsed: (collapsed: boolean) => void;

  // Project Reorder
  reorderProjects: (items: { id: string; order: number }[]) => Promise<void>;

  // Reports
  reportRange: ReportRangeMode;
  reportData: {
    summary: ReportSummary;
    throughput: ThroughputPoint[];
    timeByProject: ProjectTime[];
  } | null;
  reportLoading: boolean;
  setReportRange: (mode: ReportRangeMode) => void;
  loadReports: () => Promise<void>;

  // Computed
  todayTasks: () => Task[];
  scheduledTasks: () => Task[];
  inboxTasks: () => Task[];
  backlogTasks: () => Task[];
  filteredTasks: () => Task[];
  freeMinutesRemaining: () => number;
  projectName: (projectId: string | null) => string;
}

export const useStore = create<AppState>((set, get) => ({
  tasks: [],
  loading: false,
  projects: [],
  selectedProjectId: null,
  projectDialog: {
    isOpen: false,
    mode: 'create',
    projectId: null,
  },
  noteGroups: [],
  selectedNoteGroupId: null,
  selectedNoteId: null,
  noteEditorReturnView: null,
  currentNotes: [],
  noteGroupDialog: {
    isOpen: false,
    mode: 'create',
    groupId: null,
    defaultProjectId: null,
  },
  projectNotes: [],
  allNotes: [],
  projectActiveTab: 'tasks',
  notesCollapsed: false,
  projectsCollapsed: false,
  currentView: 'board',
  selectedDate: format(new Date(), 'yyyy-MM-dd'),
  selectedTaskIds: new Set(),
  editingTaskId: null,
  searchQuery: '',
  searchOpen: false,
  timeIncrement: 15,
  workStartHour: 8,
  workEndHour: 20,
  allowOverlaps: false,
  sidebarCollapsed: false,
  quickAddOpen: false,
  settingsOpen: false,
  taskDialog: {
    isOpen: false,
    mode: 'create',
    taskId: null,
  },
  planningFlow: {
    isOpen: false,
    phase: 'review',
    targetDate: format(new Date(), 'yyyy-MM-dd'),
    commitError: null,
  },
  focusMode: {
    targetType: null,
    targetId: null,
    isPlaying: false,
    sessionStartTime: null,
  },
  reportRange: 'week',
  reportData: null,
  reportLoading: false,

  loadTasks: async () => {
    set({ loading: true });
    try {
      const tasks = await dc().tasks.getAll();
      const today = format(new Date(), 'yyyy-MM-dd');

      // Auto-rollover: move past unfinished tasks to today
      // For recurring instances, archive past missed ones instead of rolling forward
      const rolloverPromises: Promise<any>[] = [];
      const updatedTasks = tasks.map((task: Task) => {
        if (
          task.plannedDate &&
          task.plannedDate < today &&
          task.status !== 'done' &&
          task.status !== 'archived' &&
          task.status !== 'backlog'
        ) {
          // Recurring instances: archive past missed ones
          if (task.recurrenceRuleId && task.recurrenceOriginalDate) {
            rolloverPromises.push(dc().tasks.update(task.id, { status: 'archived' }));
            return { ...task, status: 'archived' as Task['status'] };
          }

          const updates: Partial<Task> = {
            plannedDate: today,
            scheduledStart: null,
            scheduledEnd: null,
          };
          // If task was scheduled, unschedule it (the old time slot is in the past)
          if (task.status === 'scheduled') {
            updates.status = 'planned';
          }
          rolloverPromises.push(dc().tasks.update(task.id, updates));
          return {
            ...task,
            ...updates,
            status: (task.status === 'scheduled' ? 'planned' : task.status) as Task['status'],
          };
        }
        return task;
      });

      // Fire rollover updates in the background (don't block UI)
      if (rolloverPromises.length > 0) {
        Promise.all(rolloverPromises).catch((e) => console.error('Rollover update failed:', e));
      }

      set({ tasks: updatedTasks, loading: false });

      // Ensure recurring task instances are generated for the next 2 weeks
      try {
        const twoWeeks = format(addDays(new Date(), 14), 'yyyy-MM-dd');
        const newInstances = await dc().recurrence.ensureInstances(today, twoWeeks);
        if (newInstances && newInstances.length > 0) {
          set((s) => ({ tasks: [...s.tasks, ...newInstances] }));
        }
      } catch (e) {
        console.error('Failed to ensure recurrence instances:', e);
      }
    } catch (e) {
      console.error('Failed to load tasks:', e);
      set({ loading: false });
    }
  },

  loadTasksByDate: async (date: string) => {
    set({ loading: true });
    try {
      const tasks = await dc().tasks.getByDate(date);
      set({ tasks, loading: false });
    } catch (e) {
      console.error('Failed to load tasks by date:', e);
      set({ loading: false });
    }
  },

  createTask: async (task: Partial<Task>) => {
    try {
      const newTask = await dc().tasks.create(task);
      set((s) => ({ tasks: [...s.tasks, newTask] }));
      return newTask;
    } catch (e) {
      console.error('Failed to create task:', e);
      return null;
    }
  },

  updateTask: async (id: string, updates: Partial<Task>) => {
    try {
      const updated = await dc().tasks.update(id, updates);
      set((s) => ({
        tasks: s.tasks.map((t) => (t.id === id ? updated : t)),
      }));
    } catch (e) {
      console.error('Failed to update task:', e);
    }
  },

  deleteTask: async (id: string) => {
    try {
      const { focusMode } = get();
      if (focusMode.targetType === 'task' && focusMode.targetId === id) {
        set({
          focusMode: { targetType: null, targetId: null, isPlaying: false, sessionStartTime: null },
        });
      }
      await dc().tasks.delete(id);
      set((s) => ({
        tasks: s.tasks.filter((t) => t.id !== id),
        selectedTaskIds: new Set([...s.selectedTaskIds].filter((sid) => sid !== id)),
      }));
    } catch (e) {
      console.error('Failed to delete task:', e);
    }
  },

  reorderTasks: async (tasks: { id: string; order: number }[]) => {
    try {
      await dc().tasks.reorder(tasks);
      set((s) => {
        const orderMap = new Map(tasks.map((i) => [i.id, i.order]));
        return {
          tasks: s.tasks
            .map((t) => (orderMap.has(t.id) ? { ...t, order: orderMap.get(t.id)! } : t))
            .sort((a, b) => a.order - b.order),
        };
      });
    } catch (e) {
      console.error('Failed to reorder tasks:', e);
    }
  },

  scheduleTask: async (id: string, start: string, end: string) => {
    const { updateTask } = get();
    await updateTask(id, {
      status: 'scheduled',
      scheduledStart: start,
      scheduledEnd: end,
      plannedDate: start.split('T')[0],
    });
  },

  unscheduleTask: async (id: string) => {
    const { updateTask, selectedDate } = get();
    await updateTask(id, {
      status: 'planned',
      scheduledStart: null,
      scheduledEnd: null,
    });
  },

  markDone: async (id: string) => {
    const today = format(new Date(), 'yyyy-MM-dd');
    const { updateTask } = get();
    await updateTask(id, { status: 'done', plannedDate: today });
  },

  moveToToday: async (id: string) => {
    const today = format(new Date(), 'yyyy-MM-dd');
    const { updateTask } = get();
    await updateTask(id, { status: 'planned', plannedDate: today });
  },

  moveToBacklog: async (id: string) => {
    const { updateTask } = get();
    await updateTask(id, {
      status: 'backlog',
      plannedDate: null,
      scheduledStart: null,
      scheduledEnd: null,
    });
  },

  archiveTask: async (id: string) => {
    const { updateTask } = get();
    await updateTask(id, { status: 'archived' });
  },

  // ─── Recurrence Actions ─────────────────────────────────
  createRecurringTask: async (task, rule) => {
    try {
      // HttpDataClient returns the full task list (targeted reload) after create.
      const tasks = await dc().recurrence.create(task, rule);
      set({ tasks });
      return null;
    } catch (e) {
      console.error('Failed to create recurring task:', e);
      return null;
    }
  },

  updateRecurrenceSeries: async (ruleId, updates) => {
    try {
      await dc().recurrence.updateSeries(ruleId, updates);
      await get().loadTasks();
    } catch (e) {
      console.error('Failed to update recurrence series:', e);
    }
  },

  deleteRecurrenceSeries: async (ruleId) => {
    try {
      await dc().recurrence.deleteSeries(ruleId);
      await get().loadTasks();
    } catch (e) {
      console.error('Failed to delete recurrence series:', e);
    }
  },

  deleteRecurrenceSeriesFuture: async (ruleId, fromDate) => {
    try {
      await dc().recurrence.deleteSeriesFuture(ruleId, fromDate);
      await get().loadTasks();
    } catch (e) {
      console.error('Failed to delete future recurrence instances:', e);
    }
  },

  detachRecurrenceInstance: async (taskId) => {
    try {
      await dc().recurrence.detachInstance(taskId);
      set((s) => ({
        tasks: s.tasks.map((t) => (t.id === taskId ? { ...t, recurrenceDetached: true } : t)),
      }));
    } catch (e) {
      console.error('Failed to detach recurrence instance:', e);
    }
  },

  ensureRecurrenceInstances: async (startDate, endDate) => {
    try {
      const newInstances = await dc().recurrence.ensureInstances(startDate, endDate);
      if (newInstances && newInstances.length > 0) {
        set((s) => ({ tasks: [...s.tasks, ...newInstances] }));
      }
    } catch (e) {
      console.error('Failed to ensure recurrence instances:', e);
    }
  },

  // ─── Subtask Actions ─────────────────────────────────────
  createSubtask: async (taskId: string, title: string) => {
    try {
      const subtask = await dc().subtasks.create(taskId, title);
      set((s) => ({
        tasks: s.tasks.map((t) =>
          t.id === taskId ? { ...t, subtasks: [...t.subtasks, subtask] } : t,
        ),
      }));
      return subtask;
    } catch (e) {
      console.error('Failed to create subtask:', e);
      return null;
    }
  },

  updateSubtask: async (taskId: string, subtaskId: string, updates) => {
    try {
      await dc().subtasks.update(subtaskId, updates);
      set((s) => ({
        tasks: s.tasks.map((t) =>
          t.id === taskId
            ? {
                ...t,
                subtasks: t.subtasks.map((st) =>
                  st.id === subtaskId ? { ...st, ...updates } : st,
                ),
              }
            : t,
        ),
      }));
    } catch (e) {
      console.error('Failed to update subtask:', e);
    }
  },

  deleteSubtask: async (taskId: string, subtaskId: string) => {
    try {
      await dc().subtasks.delete(subtaskId);
      set((s) => ({
        tasks: s.tasks.map((t) =>
          t.id === taskId ? { ...t, subtasks: t.subtasks.filter((st) => st.id !== subtaskId) } : t,
        ),
      }));
    } catch (e) {
      console.error('Failed to delete subtask:', e);
    }
  },

  // ─── Project Actions ─────────────────────────────────────
  loadProjects: async () => {
    try {
      const projects = await dc().projects.getAll();
      set({ projects });
    } catch (e) {
      console.error('Failed to load projects:', e);
    }
  },

  createProject: async (input) => {
    try {
      const project = await dc().projects.create(input);
      set((s) => ({ projects: [...s.projects, project] }));
      return project;
    } catch (e) {
      console.error('Failed to create project:', e);
      return null;
    }
  },

  updateProject: async (id, updates) => {
    try {
      const updated = await dc().projects.update(id, updates);
      set((s) => ({
        projects: s.projects.map((p) => (p.id === id ? updated : p)),
      }));
    } catch (e) {
      console.error('Failed to update project:', e);
    }
  },

  deleteProject: async (id) => {
    try {
      const { focusMode } = get();
      if (focusMode.targetType === 'project' && focusMode.targetId === id) {
        set({
          focusMode: { targetType: null, targetId: null, isPlaying: false, sessionStartTime: null },
        });
      }
      await dc().projects.delete(id);
      set((s) => ({
        projects: s.projects.filter((p) => p.id !== id),
      }));
      // Refresh note groups since project_id is set to NULL on cascade
      get().loadNoteGroups();
    } catch (e) {
      console.error('Failed to delete project:', e);
    }
  },

  openProjectDialog: (mode, projectId?) =>
    set({ projectDialog: { isOpen: true, mode, projectId: projectId || null } }),
  closeProjectDialog: () =>
    set({ projectDialog: { isOpen: false, mode: 'create', projectId: null } }),

  selectProject: (projectId: string) =>
    set({ selectedProjectId: projectId, currentView: 'project' as ViewMode }),

  // ─── Note Group Actions ──────────────────────────────────
  loadNoteGroups: async () => {
    try {
      const noteGroups = await dc().noteGroups.getAll();
      set({ noteGroups });
    } catch (e) {
      console.error('Failed to load note groups:', e);
    }
  },

  createNoteGroup: async (input) => {
    try {
      const group = await dc().noteGroups.create(input);
      set((s) => ({ noteGroups: [...s.noteGroups, group] }));
      return group;
    } catch (e) {
      console.error('Failed to create note group:', e);
      return null;
    }
  },

  updateNoteGroup: async (id, updates) => {
    try {
      const updated = await dc().noteGroups.update(id, updates);
      set((s) => ({
        noteGroups: s.noteGroups.map((g) => (g.id === id ? updated : g)),
      }));
    } catch (e) {
      console.error('Failed to update note group:', e);
    }
  },

  deleteNoteGroup: async (id) => {
    try {
      await dc().noteGroups.delete(id);
      set((s) => ({
        noteGroups: s.noteGroups.filter((g) => g.id !== id),
        selectedNoteGroupId: s.selectedNoteGroupId === id ? null : s.selectedNoteGroupId,
      }));
    } catch (e) {
      console.error('Failed to delete note group:', e);
    }
  },

  openNoteGroupDialog: (mode, groupId?, defaultProjectId?) =>
    set({
      noteGroupDialog: {
        isOpen: true,
        mode,
        groupId: groupId || null,
        defaultProjectId: defaultProjectId || null,
      },
    }),
  closeNoteGroupDialog: () =>
    set({
      noteGroupDialog: { isOpen: false, mode: 'create', groupId: null, defaultProjectId: null },
    }),

  selectNoteGroup: (groupId: string) => {
    set({ selectedNoteGroupId: groupId, currentView: 'noteGroup' as ViewMode });
    get().loadNotesByGroup(groupId);
  },

  reorderNoteGroups: async (items) => {
    try {
      await dc().noteGroups.reorder(items);
      set((s) => {
        const orderMap = new Map(items.map((i) => [i.id, i.order]));
        return {
          noteGroups: s.noteGroups
            .map((g) => (orderMap.has(g.id) ? { ...g, order: orderMap.get(g.id)! } : g))
            .sort((a, b) => a.order - b.order),
        };
      });
    } catch (e) {
      console.error('Failed to reorder note groups:', e);
    }
  },

  // ─── Note Actions ────────────────────────────────────────
  loadAllNotes: async () => {
    try {
      const notes = await dc().notes.getAll();
      set({ allNotes: notes });
    } catch (e) {
      console.error('Failed to load all notes:', e);
    }
  },

  loadNotesByProject: async (projectId) => {
    try {
      const notes = await dc().notes.getByProject(projectId);
      set({ projectNotes: notes });
    } catch (e) {
      console.error('Failed to load project notes:', e);
    }
  },

  createProjectNote: async (projectId) => {
    try {
      const note = await dc().notes.create({ projectId });
      set((s) => ({
        projectNotes: [...s.projectNotes, note],
        selectedNoteId: note.id,
        currentView: 'noteEditor' as ViewMode,
      }));
      return note;
    } catch (e) {
      console.error('Failed to create project note:', e);
      return null;
    }
  },

  loadNotesByGroup: async (groupId) => {
    try {
      const notes = await dc().notes.getByGroup(groupId);
      set({ currentNotes: notes });
    } catch (e) {
      console.error('Failed to load notes:', e);
    }
  },

  createNote: async (groupId) => {
    try {
      const note = await dc().notes.create({ groupId });
      set((s) => ({
        currentNotes: [...s.currentNotes, note],
        selectedNoteId: note.id,
        currentView: 'noteEditor' as ViewMode,
      }));
      return note;
    } catch (e) {
      console.error('Failed to create note:', e);
      return null;
    }
  },

  updateNote: async (id, updates) => {
    try {
      const updated = await dc().notes.update(id, updates);
      set((s) => ({
        currentNotes: s.currentNotes.map((n) => (n.id === id ? updated : n)),
        projectNotes: s.projectNotes.map((n) => (n.id === id ? updated : n)),
        allNotes: s.allNotes.map((n) => (n.id === id ? updated : n)),
      }));
    } catch (e) {
      console.error('Failed to update note:', e);
    }
  },

  deleteNote: async (id) => {
    try {
      // Capture note context before deleting for smart navigation
      const note =
        get().currentNotes.find((n) => n.id === id) ||
        get().projectNotes.find((n) => n.id === id) ||
        get().allNotes.find((n) => n.id === id);

      await dc().notes.delete(id);
      const { selectedNoteId, selectedNoteGroupId, noteEditorReturnView } = get();

      let viewUpdate: Record<string, any> = {};
      if (selectedNoteId === id) {
        if (noteEditorReturnView === 'allNotes') {
          viewUpdate = { selectedNoteId: null, currentView: 'allNotes' as ViewMode };
        } else if (note?.projectId && !note.groupId) {
          // Project note — go back to project view, notes tab
          viewUpdate = { selectedNoteId: null, currentView: 'project' as ViewMode };
          get().setProjectActiveTab('notes');
        } else if (selectedNoteGroupId) {
          viewUpdate = { selectedNoteId: null, currentView: 'noteGroup' as ViewMode };
        } else {
          viewUpdate = { selectedNoteId: null, currentView: 'board' as ViewMode };
        }
      }

      set((s) => ({
        currentNotes: s.currentNotes.filter((n) => n.id !== id),
        projectNotes: s.projectNotes.filter((n) => n.id !== id),
        allNotes: s.allNotes.filter((n) => n.id !== id),
        ...viewUpdate,
      }));
    } catch (e) {
      console.error('Failed to delete note:', e);
    }
  },

  selectNote: (noteId: string, returnView?: ViewMode) =>
    set({
      selectedNoteId: noteId,
      currentView: 'noteEditor' as ViewMode,
      noteEditorReturnView: returnView ?? null,
    }),

  reorderNotes: async (items) => {
    try {
      await dc().notes.reorder(items);
      set((s) => {
        const orderMap = new Map(items.map((i) => [i.id, i.order]));
        return {
          currentNotes: s.currentNotes
            .map((n) => (orderMap.has(n.id) ? { ...n, order: orderMap.get(n.id)! } : n))
            .sort((a, b) => a.order - b.order),
          projectNotes: s.projectNotes
            .map((n) => (orderMap.has(n.id) ? { ...n, order: orderMap.get(n.id)! } : n))
            .sort((a, b) => a.order - b.order),
        };
      });
    } catch (e) {
      console.error('Failed to reorder notes:', e);
    }
  },

  setProjectActiveTab: (tab: 'tasks' | 'notes') => set({ projectActiveTab: tab }),

  setNotesCollapsed: (collapsed: boolean) => {
    set({ notesCollapsed: collapsed });
    get().saveSettings();
  },
  setProjectsCollapsed: (collapsed: boolean) => {
    set({ projectsCollapsed: collapsed });
    get().saveSettings();
  },

  setCurrentView: (view: ViewMode) => {
    const now = new Date();
    let date = format(now, 'yyyy-MM-dd');
    if (view === 'tomorrow') date = format(addDays(now, 1), 'yyyy-MM-dd');
    set({ currentView: view, selectedDate: date });
  },

  setSelectedDate: (date: string) => set({ selectedDate: date }),

  toggleTaskSelection: (id: string) =>
    set((s) => {
      const newSet = new Set(s.selectedTaskIds);
      if (newSet.has(id)) newSet.delete(id);
      else newSet.add(id);
      return { selectedTaskIds: newSet };
    }),

  selectTask: (id: string) => set({ selectedTaskIds: new Set([id]) }),

  clearSelection: () => set({ selectedTaskIds: new Set() }),

  setEditingTaskId: (id: string | null) => set({ editingTaskId: id }),

  setSearchQuery: (query: string) => set({ searchQuery: query }),

  setSearchOpen: (open: boolean) => set({ searchOpen: open, searchQuery: '' }),

  setSidebarCollapsed: (collapsed: boolean) => set({ sidebarCollapsed: collapsed }),

  setTimeIncrement: (inc: number) => {
    set({ timeIncrement: inc });
    // Persist settings in the background
    setTimeout(() => get().saveSettings(), 0);
  },

  setQuickAddOpen: (isOpen: boolean) => set({ quickAddOpen: isOpen }),
  setSettingsOpen: (open: boolean) => set({ settingsOpen: open }),
  setWorkHours: (start: number, end: number) => {
    set({ workStartHour: start, workEndHour: end });
    // Persist settings in the background
    setTimeout(() => get().saveSettings(), 0);
  },
  openTaskDialog: (mode: 'create' | 'edit', taskId?: string) =>
    set({ taskDialog: { isOpen: true, mode, taskId: taskId || null } }),
  closeTaskDialog: () => set({ taskDialog: { isOpen: false, mode: 'create', taskId: null } }),
  startPlanningFlow: () =>
    set({
      planningFlow: {
        isOpen: true,
        phase: 'review',
        targetDate: format(new Date(), 'yyyy-MM-dd'),
        commitError: null,
      },
    }),
  setPlanningPhase: (phase: PlanningPhase) =>
    set((state) => ({ planningFlow: { ...state.planningFlow, phase } })),
  closePlanningFlow: () =>
    set((state) => ({ planningFlow: { ...state.planningFlow, isOpen: false, commitError: null } })),
  planForDate: async (id: string, date: string) => {
    const { tasks, updateTask } = get();
    const task = tasks.find((t) => t.id === id);
    // Only promote status to 'planned' if the task is not already 'scheduled'.
    // Demoting a 'scheduled' task to 'planned' would silently discard its time-block.
    const statusUpdate = task?.status === 'scheduled' ? {} : { status: 'planned' as const };
    await updateTask(id, { ...statusUpdate, plannedDate: date });
  },
  commitDay: async () => {
    const { planningFlow, plannedForDate } = get();
    const date = planningFlow.targetDate;
    const tasksForDay = plannedForDate(date);
    const plannedMinutes = tasksForDay.reduce((s, t) => s + (t.durationMinutes || 0), 0);
    try {
      await dc().dailyPlans.upsert({
        date,
        plannedTaskIds: tasksForDay.map((t) => t.id),
        plannedMinutes,
      });
      // Only close the canvas and clear any previous error when the DB write succeeds.
      set((state) => ({
        planningFlow: { ...state.planningFlow, isOpen: false, commitError: null },
      }));
    } catch (e) {
      // Store the error in commitError so the canvas can display a failure state.
      // The re-throw is omitted: commitError drives UI feedback, and a dual-signal
      // contract would require every future caller to add its own catch.
      const message = e instanceof Error ? e.message : String(e);
      console.error('Failed to commit day:', e);
      set((state) => ({ planningFlow: { ...state.planningFlow, commitError: message } }));
    }
  },

  // Settings Persistence
  loadSettings: async () => {
    try {
      const { settings } = await dc().settings.get();
      const updates: Partial<AppState> = {};
      if (typeof settings.workStartHour === 'number')
        updates.workStartHour = settings.workStartHour;
      if (typeof settings.workEndHour === 'number') updates.workEndHour = settings.workEndHour;
      if (typeof settings.timeIncrement === 'number')
        updates.timeIncrement = settings.timeIncrement;
      set(updates);
    } catch (e) {
      console.error('Failed to load settings:', e);
    }
  },

  saveSettings: async () => {
    try {
      const { workStartHour, workEndHour, timeIncrement } = get();
      // Only the three synced numeric values go to the server; local-only UI
      // collapse flags persist to localStorage (handled by the host in Q5/Q6).
      await dc().settings.save({ workStartHour, workEndHour, timeIncrement });
    } catch (e) {
      console.error('Failed to save settings:', e);
    }
  },

  // Project Reorder
  reorderProjects: async (items: { id: string; order: number }[]) => {
    try {
      await dc().projects.reorder(items);
      set((s) => {
        const orderMap = new Map(items.map((i) => [i.id, i.order]));
        return {
          projects: s.projects
            .map((p) => (orderMap.has(p.id) ? { ...p, order: orderMap.get(p.id)! } : p))
            .sort((a, b) => a.order - b.order),
        };
      });
    } catch (e) {
      console.error('Failed to reorder projects:', e);
    }
  },

  // Focus Mode Actions
  startFocusSession: (taskId: string) => {
    get().pauseFocusSession(); // flush any running session first
    set({
      focusMode: {
        targetType: 'task',
        targetId: taskId,
        isPlaying: true,
        sessionStartTime: Date.now(),
      },
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
      },
    });
  },

  pauseFocusSession: () => {
    const { focusMode, updateTask, updateProject, tasks, projects } = get();
    if (
      !focusMode.targetType ||
      !focusMode.targetId ||
      !focusMode.isPlaying ||
      !focusMode.sessionStartTime
    )
      return;

    const endMs = Date.now();
    const elapsedMinutes = sessionMinutes(focusMode.sessionStartTime, endMs);

    if (elapsedMinutes > 0) {
      logFocusSession(
        get,
        focusMode.targetType,
        focusMode.targetId,
        focusMode.sessionStartTime,
        endMs,
      );
      if (focusMode.targetType === 'task') {
        const task = tasks.find((t) => t.id === focusMode.targetId);
        if (task)
          updateTask(task.id, {
            actualTimeMinutes: (task.actualTimeMinutes || 0) + elapsedMinutes,
          });
      } else {
        const project = projects.find((p) => p.id === focusMode.targetId);
        if (project)
          updateProject(project.id, {
            actualTimeMinutes: (project.actualTimeMinutes || 0) + elapsedMinutes,
          });
      }
    }

    set({
      focusMode: {
        ...focusMode,
        isPlaying: false,
        sessionStartTime: null,
      },
    });
  },

  stopFocusSession: async () => {
    const { focusMode, updateTask, updateProject, tasks, projects } = get();
    if (!focusMode.targetType || !focusMode.targetId) return;

    if (focusMode.isPlaying && focusMode.sessionStartTime) {
      const endMs = Date.now();
      const elapsedMinutes = sessionMinutes(focusMode.sessionStartTime, endMs);

      if (elapsedMinutes > 0) {
        logFocusSession(
          get,
          focusMode.targetType,
          focusMode.targetId,
          focusMode.sessionStartTime,
          endMs,
        );
        if (focusMode.targetType === 'task') {
          const task = tasks.find((t) => t.id === focusMode.targetId);
          if (task)
            await updateTask(task.id, {
              actualTimeMinutes: (task.actualTimeMinutes || 0) + elapsedMinutes,
            });
        } else {
          const project = projects.find((p) => p.id === focusMode.targetId);
          if (project)
            await updateProject(project.id, {
              actualTimeMinutes: (project.actualTimeMinutes || 0) + elapsedMinutes,
            });
        }
      }
    }

    set({
      focusMode: {
        targetType: null,
        targetId: null,
        isPlaying: false,
        sessionStartTime: null,
      },
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

  todayTasks: () => {
    const { tasks, selectedDate } = get();
    return tasks.filter(
      (t) => t.plannedDate === selectedDate && t.status !== 'archived' && t.status !== 'done',
    );
  },

  scheduledTasks: () => {
    const { tasks, selectedDate } = get();
    return tasks.filter(
      (t) =>
        t.status === 'scheduled' && t.scheduledStart && t.scheduledStart.startsWith(selectedDate),
    );
  },

  inboxTasks: () => {
    const { tasks } = get();
    return tasks.filter((t) => t.status === 'inbox');
  },

  backlogTasks: () => {
    const { tasks } = get();
    return tasks.filter((t) => t.status === 'backlog');
  },

  leftoverTasks: (beforeDate: string) => {
    // Only returns tasks that were actively planned (status 'planned' or 'scheduled') for
    // a past date. Inbox and backlog tasks with a stale plannedDate are excluded because
    // they were never committed to a day and should not appear as 'leftovers' in the
    // Review column of the planning canvas.
    const { tasks } = get();
    return tasks.filter(
      (t) =>
        (t.status === 'planned' || t.status === 'scheduled') &&
        !!t.plannedDate &&
        t.plannedDate < beforeDate,
    );
  },
  plannedForDate: (date: string) => {
    // Only returns tasks that are actively planned or scheduled for the given date.
    // Inbox and backlog tasks are not meaningfully 'planned for a date' and are excluded
    // even if they happen to carry a matching plannedDate.
    const { tasks } = get();
    return tasks.filter(
      (t) => t.plannedDate === date && (t.status === 'planned' || t.status === 'scheduled'),
    );
  },
  unscheduledPlannedTasks: (date: string) => {
    const { tasks } = get();
    return tasks.filter(
      (t) =>
        t.plannedDate === date &&
        (t.status === 'planned' || t.status === 'scheduled') &&
        !t.scheduledStart,
    );
  },

  filteredTasks: () => {
    const { tasks, currentView, selectedDate, searchQuery } = get();
    let filtered = tasks.filter((t) => t.status !== 'archived');

    if (searchQuery) {
      const q = searchQuery.toLowerCase();
      const { projectName } = get();
      return filtered.filter(
        (t) =>
          t.title.toLowerCase().includes(q) ||
          stripHtml(t.notes).toLowerCase().includes(q) ||
          projectName(t.projectId).toLowerCase().includes(q),
      );
    }

    switch (currentView) {
      case 'today':
      case 'tomorrow':
        filtered = filtered.filter(
          (t) =>
            t.plannedDate === selectedDate ||
            (t.status === 'scheduled' && t.scheduledStart?.startsWith(selectedDate)),
        );
        break;
      case 'inbox':
        filtered = filtered.filter((t) => t.status === 'inbox');
        break;
      case 'backlog':
        filtered = filtered.filter((t) => t.status === 'backlog');
        break;
      case 'week': {
        const start = format(startOfWeek(new Date(), { weekStartsOn: 1 }), 'yyyy-MM-dd');
        const end = format(endOfWeek(new Date(), { weekStartsOn: 1 }), 'yyyy-MM-dd');
        filtered = filtered.filter(
          (t) => t.plannedDate && t.plannedDate >= start && t.plannedDate <= end,
        );
        break;
      }
    }

    return filtered.sort((a, b) => {
      const pa = a.priority ?? 5;
      const pb = b.priority ?? 5;
      if (pa !== pb) return pa - pb;
      return a.order - b.order;
    });
  },

  freeMinutesRemaining: () => {
    const { scheduledTasks, workStartHour, workEndHour } = get();
    const scheduled = scheduledTasks();
    const totalWorkMinutes = (workEndHour - workStartHour) * 60;
    const scheduledMinutes = scheduled.reduce((sum, t) => sum + (t.durationMinutes || 0), 0);
    return totalWorkMinutes - scheduledMinutes;
  },

  projectName: (projectId: string | null) => {
    if (!projectId) return '';
    const { projects } = get();
    return projects.find((p) => p.id === projectId)?.name ?? '';
  },

  setReportRange: (mode: ReportRangeMode) => {
    set({ reportRange: mode });
    get().loadReports();
  },
  loadReports: async () => {
    const { reportRange } = get();
    const token = ++reportRequestToken;
    set({ reportLoading: true, reportData: null });
    try {
      const now = new Date();
      const range = getRange(reportRange, now);
      const prev = getPreviousRange(reportRange, now);
      const [current, previous] = await Promise.all([
        dc().reports.getData(range.start, range.end),
        dc().reports.getData(prev.start, prev.end),
      ]);
      // Drop the result if a newer loadReports call superseded this one.
      if (token !== reportRequestToken) return;
      set({
        reportData: {
          summary: summarize(current, previous),
          throughput: throughputByDay(current, daysInRange(range)),
          timeByProject: timeByProjectFn(current.sessions),
        },
        reportLoading: false,
      });
    } catch (e) {
      if (token !== reportRequestToken) return;
      console.error('Failed to load reports:', e);
      set({ reportData: null, reportLoading: false });
    }
  },
}));
