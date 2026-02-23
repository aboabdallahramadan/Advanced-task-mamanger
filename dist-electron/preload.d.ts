export interface TaskInput {
    title: string;
    notes?: string;
    project?: string;
    labels?: string[];
    status?: string;
    dueDate?: string;
    plannedDate?: string;
    scheduledStart?: string;
    scheduledEnd?: string;
    durationMinutes?: number;
    actualTimeMinutes?: number;
}
export interface TaskUpdate {
    title?: string;
    notes?: string;
    project?: string;
    labels?: string[];
    status?: string;
    dueDate?: string;
    plannedDate?: string;
    scheduledStart?: string;
    scheduledEnd?: string;
    durationMinutes?: number;
    actualTimeMinutes?: number;
    order?: number;
}
declare const api: {
    tasks: {
        getAll: () => Promise<any>;
        getByDate: (date: string) => Promise<any>;
        getByStatus: (status: string) => Promise<any>;
        create: (task: TaskInput) => Promise<any>;
        update: (id: string, updates: TaskUpdate) => Promise<any>;
        delete: (id: string) => Promise<any>;
        reorder: (tasks: {
            id: string;
            order: number;
        }[]) => Promise<any>;
        search: (query: string) => Promise<any>;
    };
    projects: {
        getAll: () => Promise<any>;
        create: (input: {
            name: string;
            color?: string;
            emoji?: string;
        }) => Promise<any>;
        update: (id: string, updates: {
            name?: string;
            color?: string;
            emoji?: string;
            order?: number;
        }) => Promise<any>;
        delete: (id: string) => Promise<any>;
    };
    app: {
        getVersion: () => Promise<any>;
        showNotification: (title: string, body: string) => Promise<any>;
    };
    data: {
        exportAll: (settings: any) => Promise<any>;
        importAll: () => Promise<any>;
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
    on: (channel: string, callback: (...args: any[]) => void) => void;
    off: (channel: string, callback: (...args: any[]) => void) => void;
    removeAllListeners: (channel: string) => void;
};
export type ElectronAPI = typeof api;
export {};
