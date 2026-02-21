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
    order: number;
    createdAt: string;
    updatedAt: string;
}

export type TaskStatus = Task['status'];

export type ViewMode = 'today' | 'tomorrow' | 'week' | 'inbox' | 'backlog';

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
    app: {
        getVersion: () => Promise<string>;
        showNotification: (title: string, body: string) => Promise<void>;
    };
    on: (channel: string, callback: (...args: any[]) => void) => void;
}

declare global {
    interface Window {
        api: ElectronAPI;
    }
}
