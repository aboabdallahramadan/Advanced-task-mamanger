export interface Task {
    id: string;
    title: string;
    notes: string;
    project: string;
    labels: string[];
    source: string;
    status: 'inbox' | 'backlog' | 'planned' | 'scheduled' | 'done' | 'archived';
    dueDate: string | null;
    plannedDate: string | null;
    scheduledStart: string | null;
    scheduledEnd: string | null;
    durationMinutes: number;
    actualTimeMinutes?: number;
    order: number;
    createdAt: string;
    updatedAt: string;
}

export type TaskStatus = Task['status'];

export type ViewMode = 'today' | 'tomorrow' | 'week' | 'inbox' | 'backlog' | 'board' | 'project';

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
    projects: {
        getAll: () => Promise<Project[]>;
        create: (input: { name: string; color?: string; emoji?: string }) => Promise<Project>;
        update: (id: string, updates: Partial<Project>) => Promise<Project>;
        delete: (id: string) => Promise<boolean>;
    };
    app: {
        getVersion: () => Promise<string>;
        showNotification: (title: string, body: string) => Promise<void>;
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
