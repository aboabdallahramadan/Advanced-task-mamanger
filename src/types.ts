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
}

export type TaskStatus = Task['status'];

export type ViewMode = 'today' | 'tomorrow' | 'week' | 'inbox' | 'backlog' | 'board' | 'project' | 'all';

export interface Project {
    id: string;
    name: string;
    color: string;
    emoji: string;
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
        update: (id: string, updates: { title?: string; completed?: boolean; order?: number }) => Promise<void>;
        delete: (id: string) => Promise<void>;
    };
    projects: {
        getAll: () => Promise<Project[]>;
        create: (input: { name: string; color?: string; emoji?: string }) => Promise<Project>;
        update: (id: string, updates: Partial<Project>) => Promise<Project>;
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
        exportAll: (settings: Record<string, any>) => Promise<{ success: boolean; canceled?: boolean; filePath?: string; error?: string }>;
        importAll: () => Promise<{ success: boolean; canceled?: boolean; error?: string; data?: { settings: Record<string, any>; taskCount: number; projectCount: number } }>;
    };
    recurrence: {
        create: (task: Partial<Task>, rule: { frequency: RecurrenceFrequency; interval: number; daysOfWeek: number[]; endType: RecurrenceEndType; endCount?: number; endDate?: string }) => Promise<Task>;
        updateSeries: (ruleId: string, updates: Partial<Task>) => Promise<void>;
        deleteSeries: (ruleId: string) => Promise<void>;
        deleteSeriesFuture: (ruleId: string, fromDate: string) => Promise<void>;
        detachInstance: (taskId: string) => Promise<void>;
        ensureInstances: (startDate: string, endDate: string) => Promise<Task[]>;
        updateRule: (ruleId: string, ruleUpdates: any) => Promise<void>;
        getRule: (ruleId: string) => Promise<RecurrenceRule | null>;
    };
    focus: {
        updateTray: (data: { taskTitle: string | null; elapsed: string | null; isPlaying: boolean }) => void;
        showWidget: () => void;
        hideWidget: () => void;
        sendWidgetState: (data: any) => void;
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
