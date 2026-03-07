import { contextBridge, ipcRenderer } from 'electron';

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
    subtasks: {
        create: (taskId: string, title: string) => ipcRenderer.invoke('subtasks:create', taskId, title),
        update: (id: string, updates: { title?: string; completed?: boolean; order?: number }) => ipcRenderer.invoke('subtasks:update', id, updates),
        delete: (id: string) => ipcRenderer.invoke('subtasks:delete', id),
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
        getAutoLaunch: () => ipcRenderer.invoke('app:getAutoLaunch'),
        setAutoLaunch: (enabled: boolean) => ipcRenderer.invoke('app:setAutoLaunch', enabled),
    },
    data: {
        exportAll: (settings: any) => ipcRenderer.invoke('data:export', settings),
        importAll: () => ipcRenderer.invoke('data:import'),
    },
    recurrence: {
        create: (task: any, rule: any) => ipcRenderer.invoke('recurrence:create', task, rule),
        updateSeries: (ruleId: string, updates: any) => ipcRenderer.invoke('recurrence:updateSeries', ruleId, updates),
        deleteSeries: (ruleId: string) => ipcRenderer.invoke('recurrence:deleteSeries', ruleId),
        deleteSeriesFuture: (ruleId: string, fromDate: string) => ipcRenderer.invoke('recurrence:deleteSeriesFuture', ruleId, fromDate),
        detachInstance: (taskId: string) => ipcRenderer.invoke('recurrence:detachInstance', taskId),
        ensureInstances: (startDate: string, endDate: string) => ipcRenderer.invoke('recurrence:ensureInstances', startDate, endDate),
        updateRule: (ruleId: string, ruleUpdates: any) => ipcRenderer.invoke('recurrence:updateRule', ruleId, ruleUpdates),
        getRule: (ruleId: string) => ipcRenderer.invoke('recurrence:getRule', ruleId),
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
