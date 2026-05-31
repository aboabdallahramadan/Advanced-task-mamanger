export interface Subtask {
  id: string;
  taskId: string;
  title: string;
  completed: boolean;
  order: number;
  createdAt: string;
}

export type RecurrenceFrequency = 'daily' | 'weekly';
export type RecurrenceEndType = 'never' | 'count' | 'date';

export interface RecurrenceRule {
  id: string;
  frequency: RecurrenceFrequency;
  interval: number;
  daysOfWeek: number[];
  endType: RecurrenceEndType;
  endCount: number | null;
  endDate: string | null;
  generatedUntil: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Task {
  id: string;
  title: string;
  notes: string;
  project: string;
  labels: string[];
  source: string;
  status: 'inbox' | 'backlog' | 'planned' | 'scheduled' | 'done' | 'archived';
  plannedDate: string | null;
  scheduledStart: string | null;
  scheduledEnd: string | null;
  durationMinutes: number;
  actualTimeMinutes?: number;
  priority: 1 | 2 | 3 | 4 | null;
  reminderMinutes: number | null;
  subtasks: Subtask[];
  order: number;
  recurrenceRuleId: string | null;
  isRecurrenceTemplate: boolean;
  recurrenceDetached: boolean;
  recurrenceOriginalDate: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
}

export type TaskStatus = Task['status'];

export type ViewMode =
  | 'today'
  | 'tomorrow'
  | 'week'
  | 'inbox'
  | 'backlog'
  | 'board'
  | 'project'
  | 'all'
  | 'noteGroup'
  | 'noteEditor'
  | 'allNotes'
  | 'reports';

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

export interface NoteGroup {
  id: string;
  name: string;
  emoji: string;
  projectId: string | null;
  order: number;
  createdAt: string;
  updatedAt: string;
}

export interface Note {
  id: string;
  groupId: string | null;
  projectId: string | null;
  title: string;
  content: string;
  order: number;
  createdAt: string;
  updatedAt: string;
}

export interface TimeSlot {
  hour: number;
  minute: number;
  label: string;
}

export interface ElectronAPI {
  tasks: {
    getAll: () => Promise<Task[]>;
    getByDate: (date: string) => Promise<Task[]>;
    getByStatus: (status: string) => Promise<Task[]>;
    create: (task: Partial<Task>) => Promise<Task>;
    update: (id: string, updates: Partial<Task>) => Promise<Task>;
    delete: (id: string) => Promise<boolean>;
    reorder: (tasks: { id: string; order: number }[]) => Promise<void>;
    search: (query: string) => Promise<Task[]>;
  };
  subtasks: {
    create: (taskId: string, title: string) => Promise<Subtask>;
    update: (
      id: string,
      updates: { title?: string; completed?: boolean; order?: number },
    ) => Promise<void>;
    delete: (id: string) => Promise<void>;
  };
  projects: {
    getAll: () => Promise<Project[]>;
    create: (input: { name: string; color?: string; emoji?: string }) => Promise<Project>;
    update: (id: string, updates: Partial<Project>) => Promise<Project>;
    delete: (id: string) => Promise<boolean>;
    reorder: (items: { id: string; order: number }[]) => Promise<void>;
  };
  noteGroups: {
    getAll: () => Promise<NoteGroup[]>;
    getByProject: (projectId: string) => Promise<NoteGroup[]>;
    create: (input: { name: string; emoji?: string; projectId?: string }) => Promise<NoteGroup>;
    update: (
      id: string,
      updates: Partial<{ name: string; emoji: string; projectId: string | null; order: number }>,
    ) => Promise<NoteGroup>;
    delete: (id: string) => Promise<boolean>;
    reorder: (items: { id: string; order: number }[]) => Promise<void>;
  };
  notes: {
    getAll: () => Promise<Note[]>;
    getByGroup: (groupId: string) => Promise<Note[]>;
    getByProject: (projectId: string) => Promise<Note[]>;
    getById: (id: string) => Promise<Note | null>;
    create: (input: {
      groupId?: string;
      projectId?: string;
      title?: string;
      content?: string;
    }) => Promise<Note>;
    update: (
      id: string,
      updates: Partial<{
        title: string;
        content: string;
        groupId: string;
        projectId: string;
        order: number;
      }>,
    ) => Promise<Note>;
    delete: (id: string) => Promise<boolean>;
    reorder: (items: { id: string; order: number }[]) => Promise<void>;
  };
  settings: {
    get: () => Promise<Record<string, any>>;
    save: (settings: Record<string, any>) => Promise<boolean>;
  };
  app: {
    getVersion: () => Promise<string>;
    showNotification: (title: string, body: string) => Promise<void>;
    getAutoLaunch: () => Promise<boolean>;
    setAutoLaunch: (enabled: boolean) => Promise<boolean>;
  };
  data: {
    exportAll: (
      settings: Record<string, any>,
    ) => Promise<{ success: boolean; canceled?: boolean; filePath?: string; error?: string }>;
    importAll: () => Promise<{
      success: boolean;
      canceled?: boolean;
      error?: string;
      data?: { settings: Record<string, any>; taskCount: number; projectCount: number };
    }>;
  };
  recurrence: {
    create: (
      task: Partial<Task>,
      rule: {
        frequency: RecurrenceFrequency;
        interval: number;
        daysOfWeek: number[];
        endType: RecurrenceEndType;
        endCount?: number;
        endDate?: string;
      },
    ) => Promise<Task>;
    updateSeries: (ruleId: string, updates: Partial<Task>) => Promise<void>;
    deleteSeries: (ruleId: string) => Promise<void>;
    deleteSeriesFuture: (ruleId: string, fromDate: string) => Promise<void>;
    detachInstance: (taskId: string) => Promise<void>;
    ensureInstances: (startDate: string, endDate: string) => Promise<Task[]>;
    updateRule: (ruleId: string, ruleUpdates: any) => Promise<void>;
    getRule: (ruleId: string) => Promise<RecurrenceRule | null>;
  };
  focus: {
    updateTray: (data: {
      taskTitle: string | null;
      elapsed: string | null;
      isPlaying: boolean;
    }) => void;
    showWidget: () => void;
    hideWidget: () => void;
    sendWidgetState: (data: any) => void;
  };
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
  on: (channel: string, callback: (...args: any[]) => void) => void;
  off: (channel: string, callback: (...args: any[]) => void) => void;
  removeAllListeners: (channel: string) => void;
}

declare global {
  interface Window {
    api: ElectronAPI;
  }
}
