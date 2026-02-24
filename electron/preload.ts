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
    projects: {
        getAll: () => ipcRenderer.invoke('projects:getAll'),
        create: (input: { name: string; color?: string; emoji?: string }) => ipcRenderer.invoke('projects:create', input),
        update: (id: string, updates: { name?: string; color?: string; emoji?: string; order?: number }) => ipcRenderer.invoke('projects:update', id, updates),
        delete: (id: string) => ipcRenderer.invoke('projects:delete', id),
        reorder: (items: { id: string; order: number }[]) => ipcRenderer.invoke('projects:reorder', items),
    },
    settings: {
        get: () => ipcRenderer.invoke('settings:get'),
        save: (settings: Record<string, any>) => ipcRenderer.invoke('settings:save', settings),
    },
    app: {
        getVersion: () => ipcRenderer.invoke('app:getVersion'),
        showNotification: (title: string, body: string) =>
            ipcRenderer.invoke('app:showNotification', title, body),
    },
    data: {
        exportAll: (settings: any) => ipcRenderer.invoke('data:export', settings),
        importAll: () => ipcRenderer.invoke('data:import'),
    },
    focus: {
        updateTray: (data: { taskTitle: string | null; elapsed: string | null; isPlaying: boolean }) =>
            ipcRenderer.send('focus:updateTray', data),
        showWidget: () => ipcRenderer.send('focus:showWidget'),
        hideWidget: () => ipcRenderer.send('focus:hideWidget'),
        sendWidgetState: (data: any) => ipcRenderer.send('focus:widgetState', data),
    },
    on: (channel: string, callback: (...args: any[]) => void) => {
        ipcRenderer.on(channel, (_e, ...args) => callback(...args));
    },
    off: (channel: string, callback: (...args: any[]) => void) => {
        ipcRenderer.removeListener(channel, callback);
    },
    removeAllListeners: (channel: string) => {
        ipcRenderer.removeAllListeners(channel);
    },
};

contextBridge.exposeInMainWorld('api', api);

export type ElectronAPI = typeof api;
