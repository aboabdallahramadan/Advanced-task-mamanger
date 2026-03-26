import { create } from 'zustand';
import { Task, Subtask, ViewMode, Project, NoteGroup, Note, RecurrenceFrequency, RecurrenceEndType, RecurrenceRule } from './types';
import { format, addDays, startOfWeek, endOfWeek } from 'date-fns';

function stripHtml(html: string): string {
    return html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
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
    currentNotes: Note[];
    noteGroupDialog: {
        isOpen: boolean;
        mode: 'create' | 'edit';
        groupId: string | null;
        defaultProjectId?: string | null;
    };
    projectNotes: Note[];
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
        step: 0 | 1 | 2; // 0: Review Yesterday, 1: Triage Inbox, 2: Timebox
    };
    focusMode: {
        activeTaskId: string | null;
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
    createRecurringTask: (task: Partial<Task>, rule: { frequency: RecurrenceFrequency; interval: number; daysOfWeek: number[]; endType: RecurrenceEndType; endCount?: number; endDate?: string }) => Promise<Task | null>;
    updateRecurrenceSeries: (ruleId: string, updates: Partial<Task>) => Promise<void>;
    deleteRecurrenceSeries: (ruleId: string) => Promise<void>;
    deleteRecurrenceSeriesFuture: (ruleId: string, fromDate: string) => Promise<void>;
    detachRecurrenceInstance: (taskId: string) => Promise<void>;
    ensureRecurrenceInstances: (startDate: string, endDate: string) => Promise<void>;

    // Subtask Actions
    createSubtask: (taskId: string, title: string) => Promise<Subtask | null>;
    updateSubtask: (taskId: string, subtaskId: string, updates: { title?: string; completed?: boolean; order?: number }) => Promise<void>;
    deleteSubtask: (taskId: string, subtaskId: string) => Promise<void>;

    // Project Actions
    loadProjects: () => Promise<void>;
    createProject: (input: { name: string; color?: string; emoji?: string }) => Promise<Project | null>;
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
    setPlanningStep: (step: 0 | 1 | 2) => void;
    closePlanningFlow: () => void;

    // Focus Mode Actions
    startFocusSession: (taskId: string) => void;
    pauseFocusSession: () => void;
    stopFocusSession: () => Promise<void>;

    // Settings Persistence
    loadSettings: () => Promise<void>;
    saveSettings: () => Promise<void>;

    // Note Group Actions
    loadNoteGroups: () => Promise<void>;
    createNoteGroup: (input: { name: string; emoji?: string; projectId?: string }) => Promise<NoteGroup | null>;
    updateNoteGroup: (id: string, updates: Partial<NoteGroup>) => Promise<void>;
    deleteNoteGroup: (id: string) => Promise<void>;
    openNoteGroupDialog: (mode: 'create' | 'edit', groupId?: string, defaultProjectId?: string) => void;
    closeNoteGroupDialog: () => void;
    selectNoteGroup: (groupId: string) => void;
    reorderNoteGroups: (items: { id: string; order: number }[]) => Promise<void>;

    // Note Actions
    loadNotesByProject: (projectId: string) => Promise<void>;
    createProjectNote: (projectId: string) => Promise<Note | null>;
    loadNotesByGroup: (groupId: string) => Promise<void>;
    createNote: (groupId: string) => Promise<Note | null>;
    updateNote: (id: string, updates: Partial<{ title: string; content: string; groupId: string; projectId: string; order: number }>) => Promise<void>;
    deleteNote: (id: string) => Promise<void>;
    selectNote: (noteId: string) => void;
    reorderNotes: (items: { id: string; order: number }[]) => Promise<void>;
    setProjectActiveTab: (tab: 'tasks' | 'notes') => void;
    setNotesCollapsed: (collapsed: boolean) => void;
    setProjectsCollapsed: (collapsed: boolean) => void;

    // Project Reorder
    reorderProjects: (items: { id: string; order: number }[]) => Promise<void>;

    // Computed
    todayTasks: () => Task[];
    scheduledTasks: () => Task[];
    inboxTasks: () => Task[];
    backlogTasks: () => Task[];
    filteredTasks: () => Task[];
    freeMinutesRemaining: () => number;
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
    currentNotes: [],
    noteGroupDialog: {
        isOpen: false,
        mode: 'create',
        groupId: null,
        defaultProjectId: null,
    },
    projectNotes: [],
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
        step: 0,
    },
    focusMode: {
        activeTaskId: null,
        isPlaying: false,
        sessionStartTime: null,
    },

    loadTasks: async () => {
        set({ loading: true });
        try {
            const tasks = await window.api.tasks.getAll();
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
                        rolloverPromises.push(window.api.tasks.update(task.id, { status: 'archived' }));
                        return { ...task, status: 'archived' as Task['status'] };
                    }

                    const updates: Partial<Task> = {
                        plannedDate: today,
                        scheduledStart: undefined,
                        scheduledEnd: undefined,
                    };
                    // If task was scheduled, unschedule it (the old time slot is in the past)
                    if (task.status === 'scheduled') {
                        (updates as any).status = 'todo';
                    }
                    rolloverPromises.push(window.api.tasks.update(task.id, updates));
                    return {
                        ...task,
                        ...updates,
                        status: (task.status === 'scheduled' ? 'todo' : task.status) as Task['status'],
                    };
                }
                return task;
            });

            // Fire rollover updates in the background (don't block UI)
            if (rolloverPromises.length > 0) {
                Promise.all(rolloverPromises).catch(e => console.error('Rollover update failed:', e));
            }

            set({ tasks: updatedTasks, loading: false });

            // Ensure recurring task instances are generated for the next 2 weeks
            try {
                const twoWeeks = format(addDays(new Date(), 14), 'yyyy-MM-dd');
                const newInstances = await window.api.recurrence.ensureInstances(today, twoWeeks);
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
            const tasks = await window.api.tasks.getByDate(date);
            set({ tasks, loading: false });
        } catch (e) {
            console.error('Failed to load tasks by date:', e);
            set({ loading: false });
        }
    },

    createTask: async (task: Partial<Task>) => {
        try {
            const newTask = await window.api.tasks.create(task);
            set((s) => ({ tasks: [...s.tasks, newTask] }));
            return newTask;
        } catch (e) {
            console.error('Failed to create task:', e);
            return null;
        }
    },

    updateTask: async (id: string, updates: Partial<Task>) => {
        try {
            const updated = await window.api.tasks.update(id, updates);
            set((s) => ({
                tasks: s.tasks.map((t) => (t.id === id ? updated : t)),
            }));
        } catch (e) {
            console.error('Failed to update task:', e);
        }
    },

    deleteTask: async (id: string) => {
        try {
            await window.api.tasks.delete(id);
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
            await window.api.tasks.reorder(tasks);
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
        await updateTask(id, { status: 'backlog', plannedDate: null, scheduledStart: null, scheduledEnd: null });
    },

    archiveTask: async (id: string) => {
        const { updateTask } = get();
        await updateTask(id, { status: 'archived' });
    },

    // ─── Recurrence Actions ─────────────────────────────────
    createRecurringTask: async (task, rule) => {
        try {
            await window.api.recurrence.create(task, rule);
            // Reload all tasks since multiple instances were created
            await get().loadTasks();
            return null;
        } catch (e) {
            console.error('Failed to create recurring task:', e);
            return null;
        }
    },

    updateRecurrenceSeries: async (ruleId, updates) => {
        try {
            await window.api.recurrence.updateSeries(ruleId, updates);
            await get().loadTasks();
        } catch (e) {
            console.error('Failed to update recurrence series:', e);
        }
    },

    deleteRecurrenceSeries: async (ruleId) => {
        try {
            await window.api.recurrence.deleteSeries(ruleId);
            await get().loadTasks();
        } catch (e) {
            console.error('Failed to delete recurrence series:', e);
        }
    },

    deleteRecurrenceSeriesFuture: async (ruleId, fromDate) => {
        try {
            await window.api.recurrence.deleteSeriesFuture(ruleId, fromDate);
            await get().loadTasks();
        } catch (e) {
            console.error('Failed to delete future recurrence instances:', e);
        }
    },

    detachRecurrenceInstance: async (taskId) => {
        try {
            await window.api.recurrence.detachInstance(taskId);
            set((s) => ({
                tasks: s.tasks.map((t) => t.id === taskId ? { ...t, recurrenceDetached: true } : t),
            }));
        } catch (e) {
            console.error('Failed to detach recurrence instance:', e);
        }
    },

    ensureRecurrenceInstances: async (startDate, endDate) => {
        try {
            const newInstances = await window.api.recurrence.ensureInstances(startDate, endDate);
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
            const subtask = await window.api.subtasks.create(taskId, title);
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
            await window.api.subtasks.update(subtaskId, updates);
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
            await window.api.subtasks.delete(subtaskId);
            set((s) => ({
                tasks: s.tasks.map((t) =>
                    t.id === taskId
                        ? { ...t, subtasks: t.subtasks.filter((st) => st.id !== subtaskId) }
                        : t,
                ),
            }));
        } catch (e) {
            console.error('Failed to delete subtask:', e);
        }
    },

    // ─── Project Actions ─────────────────────────────────────
    loadProjects: async () => {
        try {
            const projects = await window.api.projects.getAll();
            set({ projects });
        } catch (e) {
            console.error('Failed to load projects:', e);
        }
    },

    createProject: async (input) => {
        try {
            const project = await window.api.projects.create(input);
            set((s) => ({ projects: [...s.projects, project] }));
            return project;
        } catch (e) {
            console.error('Failed to create project:', e);
            return null;
        }
    },

    updateProject: async (id, updates) => {
        try {
            const updated = await window.api.projects.update(id, updates);
            set((s) => ({
                projects: s.projects.map((p) => (p.id === id ? updated : p)),
            }));
        } catch (e) {
            console.error('Failed to update project:', e);
        }
    },

    deleteProject: async (id) => {
        try {
            await window.api.projects.delete(id);
            set((s) => ({
                projects: s.projects.filter((p) => p.id !== id),
            }));
            // Refresh note groups since project_id is set to NULL on cascade
            get().loadNoteGroups();
        } catch (e) {
            console.error('Failed to delete project:', e);
        }
    },

    openProjectDialog: (mode, projectId?) => set({ projectDialog: { isOpen: true, mode, projectId: projectId || null } }),
    closeProjectDialog: () => set({ projectDialog: { isOpen: false, mode: 'create', projectId: null } }),

    selectProject: (projectId: string) => set({ selectedProjectId: projectId, currentView: 'project' as ViewMode }),

    // ─── Note Group Actions ──────────────────────────────────
    loadNoteGroups: async () => {
        try {
            const noteGroups = await window.api.noteGroups.getAll();
            set({ noteGroups });
        } catch (e) {
            console.error('Failed to load note groups:', e);
        }
    },

    createNoteGroup: async (input) => {
        try {
            const group = await window.api.noteGroups.create(input);
            set((s) => ({ noteGroups: [...s.noteGroups, group] }));
            return group;
        } catch (e) {
            console.error('Failed to create note group:', e);
            return null;
        }
    },

    updateNoteGroup: async (id, updates) => {
        try {
            const updated = await window.api.noteGroups.update(id, updates);
            set((s) => ({
                noteGroups: s.noteGroups.map((g) => (g.id === id ? updated : g)),
            }));
        } catch (e) {
            console.error('Failed to update note group:', e);
        }
    },

    deleteNoteGroup: async (id) => {
        try {
            await window.api.noteGroups.delete(id);
            set((s) => ({
                noteGroups: s.noteGroups.filter((g) => g.id !== id),
                selectedNoteGroupId: s.selectedNoteGroupId === id ? null : s.selectedNoteGroupId,
            }));
        } catch (e) {
            console.error('Failed to delete note group:', e);
        }
    },

    openNoteGroupDialog: (mode, groupId?, defaultProjectId?) =>
        set({ noteGroupDialog: { isOpen: true, mode, groupId: groupId || null, defaultProjectId: defaultProjectId || null } }),
    closeNoteGroupDialog: () =>
        set({ noteGroupDialog: { isOpen: false, mode: 'create', groupId: null, defaultProjectId: null } }),

    selectNoteGroup: (groupId: string) => {
        set({ selectedNoteGroupId: groupId, currentView: 'noteGroup' as ViewMode });
        get().loadNotesByGroup(groupId);
    },

    reorderNoteGroups: async (items) => {
        try {
            await window.api.noteGroups.reorder(items);
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
    loadNotesByProject: async (projectId) => {
        try {
            const notes = await window.api.notes.getByProject(projectId);
            set({ projectNotes: notes });
        } catch (e) {
            console.error('Failed to load project notes:', e);
        }
    },

    createProjectNote: async (projectId) => {
        try {
            const note = await window.api.notes.create({ projectId });
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
            const notes = await window.api.notes.getByGroup(groupId);
            set({ currentNotes: notes });
        } catch (e) {
            console.error('Failed to load notes:', e);
        }
    },

    createNote: async (groupId) => {
        try {
            const note = await window.api.notes.create({ groupId });
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
            const updated = await window.api.notes.update(id, updates);
            set((s) => ({
                currentNotes: s.currentNotes.map((n) => (n.id === id ? updated : n)),
                projectNotes: s.projectNotes.map((n) => (n.id === id ? updated : n)),
            }));
        } catch (e) {
            console.error('Failed to update note:', e);
        }
    },

    deleteNote: async (id) => {
        try {
            // Capture note context before deleting for smart navigation
            const note = get().currentNotes.find((n) => n.id === id)
                || get().projectNotes.find((n) => n.id === id);

            await window.api.notes.delete(id);
            const { selectedNoteId, selectedNoteGroupId } = get();

            let viewUpdate: Record<string, any> = {};
            if (selectedNoteId === id) {
                if (note?.projectId && !note.groupId) {
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
                ...viewUpdate,
            }));
        } catch (e) {
            console.error('Failed to delete note:', e);
        }
    },

    selectNote: (noteId: string) =>
        set({ selectedNoteId: noteId, currentView: 'noteEditor' as ViewMode }),

    reorderNotes: async (items) => {
        try {
            await window.api.notes.reorder(items);
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
    openTaskDialog: (mode: 'create' | 'edit', taskId?: string) => set({ taskDialog: { isOpen: true, mode, taskId: taskId || null } }),
    closeTaskDialog: () => set({ taskDialog: { isOpen: false, mode: 'create', taskId: null } }),
    startPlanningFlow: () => set({ planningFlow: { isOpen: true, step: 0 } }),
    setPlanningStep: (step: 0 | 1 | 2) => set((state) => ({ planningFlow: { ...state.planningFlow, step } })),
    closePlanningFlow: () => set((state) => ({ planningFlow: { ...state.planningFlow, isOpen: false } })),

    // Settings Persistence
    loadSettings: async () => {
        try {
            const settings = await window.api.settings.get();
            if (settings && typeof settings === 'object') {
                const updates: Partial<AppState> = {};
                if (typeof settings.workStartHour === 'number') updates.workStartHour = settings.workStartHour;
                if (typeof settings.workEndHour === 'number') updates.workEndHour = settings.workEndHour;
                if (typeof settings.timeIncrement === 'number') updates.timeIncrement = settings.timeIncrement;
                if (typeof settings.sidebarCollapsed === 'boolean') updates.sidebarCollapsed = settings.sidebarCollapsed;
                if (typeof settings.notesCollapsed === 'boolean') updates.notesCollapsed = settings.notesCollapsed;
                if (typeof settings.projectsCollapsed === 'boolean') updates.projectsCollapsed = settings.projectsCollapsed;
                set(updates);
            }
        } catch (e) {
            console.error('Failed to load settings:', e);
        }
    },

    saveSettings: async () => {
        try {
            const { workStartHour, workEndHour, timeIncrement, sidebarCollapsed, notesCollapsed, projectsCollapsed } = get();
            await window.api.settings.save({
                workStartHour,
                workEndHour,
                timeIncrement,
                sidebarCollapsed,
                notesCollapsed,
                projectsCollapsed,
            });
        } catch (e) {
            console.error('Failed to save settings:', e);
        }
    },

    // Project Reorder
    reorderProjects: async (items: { id: string; order: number }[]) => {
        try {
            await window.api.projects.reorder(items);
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
        set({
            focusMode: {
                activeTaskId: taskId,
                isPlaying: true,
                sessionStartTime: Date.now(),
            }
        });
    },

    pauseFocusSession: () => {
        const { focusMode, updateTask, tasks } = get();
        if (!focusMode.activeTaskId || !focusMode.isPlaying || !focusMode.sessionStartTime) return;

        // Calculate elapsed minutes (round to nearest minute to preserve seconds)
        const elapsedMs = Date.now() - focusMode.sessionStartTime;
        const elapsedMinutes = Math.round(elapsedMs / 60000);

        const task = tasks.find(t => t.id === focusMode.activeTaskId);
        if (task && elapsedMinutes > 0) {
            const newActual = (task.actualTimeMinutes || 0) + elapsedMinutes;
            updateTask(task.id, { actualTimeMinutes: newActual });
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
        const { focusMode, updateTask, tasks } = get();
        if (!focusMode.activeTaskId) return;

        // Save any pending time if playing
        if (focusMode.isPlaying && focusMode.sessionStartTime) {
            const elapsedMs = Date.now() - focusMode.sessionStartTime;
            const elapsedMinutes = Math.round(elapsedMs / 60000);

            const task = tasks.find(t => t.id === focusMode.activeTaskId);
            if (task && elapsedMinutes > 0) {
                const newActual = (task.actualTimeMinutes || 0) + elapsedMinutes;
                await updateTask(task.id, { actualTimeMinutes: newActual });
            }
        }

        set({
            focusMode: {
                activeTaskId: null,
                isPlaying: false,
                sessionStartTime: null,
            }
        });
    },

    todayTasks: () => {
        const { tasks, selectedDate } = get();
        return tasks.filter(
            (t) =>
                t.plannedDate === selectedDate &&
                t.status !== 'archived' &&
                t.status !== 'done',
        );
    },

    scheduledTasks: () => {
        const { tasks, selectedDate } = get();
        return tasks.filter(
            (t) =>
                t.status === 'scheduled' &&
                t.scheduledStart &&
                t.scheduledStart.startsWith(selectedDate),
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

    filteredTasks: () => {
        const { tasks, currentView, selectedDate, searchQuery } = get();
        let filtered = tasks.filter((t) => t.status !== 'archived');

        if (searchQuery) {
            const q = searchQuery.toLowerCase();
            return filtered.filter(
                (t) =>
                    t.title.toLowerCase().includes(q) ||
                    stripHtml(t.notes).toLowerCase().includes(q) ||
                    t.project.toLowerCase().includes(q),
            );
        }

        switch (currentView) {
            case 'today':
            case 'tomorrow':
                filtered = filtered.filter(
                    (t) => t.plannedDate === selectedDate || (t.status === 'scheduled' && t.scheduledStart?.startsWith(selectedDate)),
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
}));
