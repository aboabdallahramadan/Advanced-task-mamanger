export interface TaskInput {
    title: string;
    notes?: string;
    project?: string;
    labels?: string[];
    status?: string;
    plannedDate?: string;
    scheduledStart?: string;
    scheduledEnd?: string;
    durationMinutes?: number;
    actualTimeMinutes?: number;
    priority?: 1 | 2 | 3 | 4 | null;
    reminderMinutes?: number | null;
}
export interface TaskUpdate {
    title?: string;
    notes?: string;
    project?: string;
    labels?: string[];
    status?: string;
    plannedDate?: string;
    scheduledStart?: string;
    scheduledEnd?: string;
    durationMinutes?: number;
    actualTimeMinutes?: number;
    priority?: 1 | 2 | 3 | 4 | null;
    reminderMinutes?: number | null;
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
    subtasks: {
        create: (taskId: string, title: string) => Promise<any>;
        update: (id: string, updates: {
            title?: string;
            completed?: boolean;
            order?: number;
        }) => Promise<any>;
        delete: (id: string) => Promise<any>;
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
        reorder: (items: {
            id: string;
            order: number;
        }[]) => Promise<any>;
    };
    settings: {
        get: () => Promise<any>;
        save: (settings: Record<string, any>) => Promise<any>;
    };
    app: {
        getVersion: () => Promise<any>;
        showNotification: (title: string, body: string) => Promise<any>;
        getAutoLaunch: () => Promise<any>;
        setAutoLaunch: (enabled: boolean) => Promise<any>;
    };
    data: {
        exportAll: (settings: any) => Promise<any>;
        importAll: () => Promise<any>;
    };
    recurrence: {
        create: (task: any, rule: any) => Promise<any>;
        updateSeries: (ruleId: string, updates: any) => Promise<any>;
        deleteSeries: (ruleId: string) => Promise<any>;
        deleteSeriesFuture: (ruleId: string, fromDate: string) => Promise<any>;
        detachInstance: (taskId: string) => Promise<any>;
        ensureInstances: (startDate: string, endDate: string) => Promise<any>;
        updateRule: (ruleId: string, ruleUpdates: any) => Promise<any>;
        getRule: (ruleId: string) => Promise<any>;
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
