import { contextBridge, ipcRenderer } from 'electron';

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

const api = {
    tasks: {
        getAll: () => ipcRenderer.invoke('tasks:getAll'),
        getByDate: (date: string) => ipcRenderer.invoke('tasks:getByDate', date),
        getByStatus: (status: string) => ipcRenderer.invoke('tasks:getByStatus', status),
        create: (task: TaskInput) => ipcRenderer.invoke('tasks:create', task),
        update: (id: string, updates: TaskUpdate) => ipcRenderer.invoke('tasks:update', id, updates),
        delete: (id: string) => ipcRenderer.invoke('tasks:delete', id),
        reorder: (tasks: { id: string; order: number }[]) => ipcRenderer.invoke('tasks:reorder', tasks),
        search: (query: string) => ipcRenderer.invoke('tasks:search', query),
    },
    app: {
        getVersion: () => ipcRenderer.invoke('app:getVersion'),
        showNotification: (title: string, body: string) =>
            ipcRenderer.invoke('app:showNotification', title, body),
    },
    on: (channel: string, callback: (...args: any[]) => void) => {
        ipcRenderer.on(channel, (_e, ...args) => callback(...args));
    },
};

contextBridge.exposeInMainWorld('api', api);

export type ElectronAPI = typeof api;
