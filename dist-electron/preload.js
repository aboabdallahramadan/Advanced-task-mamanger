"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const electron_1 = require("electron");
const api = {
    tasks: {
        getAll: () => electron_1.ipcRenderer.invoke('tasks:getAll'),
        getByDate: (date) => electron_1.ipcRenderer.invoke('tasks:getByDate', date),
        getByStatus: (status) => electron_1.ipcRenderer.invoke('tasks:getByStatus', status),
        create: (task) => electron_1.ipcRenderer.invoke('tasks:create', task),
        update: (id, updates) => electron_1.ipcRenderer.invoke('tasks:update', id, updates),
        delete: (id) => electron_1.ipcRenderer.invoke('tasks:delete', id),
        reorder: (tasks) => electron_1.ipcRenderer.invoke('tasks:reorder', tasks),
        search: (query) => electron_1.ipcRenderer.invoke('tasks:search', query),
    },
    subtasks: {
        create: (taskId, title) => electron_1.ipcRenderer.invoke('subtasks:create', taskId, title),
        update: (id, updates) => electron_1.ipcRenderer.invoke('subtasks:update', id, updates),
        delete: (id) => electron_1.ipcRenderer.invoke('subtasks:delete', id),
    },
    projects: {
        getAll: () => electron_1.ipcRenderer.invoke('projects:getAll'),
        create: (input) => electron_1.ipcRenderer.invoke('projects:create', input),
        update: (id, updates) => electron_1.ipcRenderer.invoke('projects:update', id, updates),
        delete: (id) => electron_1.ipcRenderer.invoke('projects:delete', id),
        reorder: (items) => electron_1.ipcRenderer.invoke('projects:reorder', items),
    },
    noteGroups: {
        getAll: () => electron_1.ipcRenderer.invoke('noteGroups:getAll'),
        getByProject: (projectId) => electron_1.ipcRenderer.invoke('noteGroups:getByProject', projectId),
        create: (input) => electron_1.ipcRenderer.invoke('noteGroups:create', input),
        update: (id, updates) => electron_1.ipcRenderer.invoke('noteGroups:update', id, updates),
        delete: (id) => electron_1.ipcRenderer.invoke('noteGroups:delete', id),
        reorder: (items) => electron_1.ipcRenderer.invoke('noteGroups:reorder', items),
    },
    notes: {
        getAll: () => electron_1.ipcRenderer.invoke('notes:getAll'),
        getByGroup: (groupId) => electron_1.ipcRenderer.invoke('notes:getByGroup', groupId),
        getByProject: (projectId) => electron_1.ipcRenderer.invoke('notes:getByProject', projectId),
        getById: (id) => electron_1.ipcRenderer.invoke('notes:getById', id),
        create: (input) => electron_1.ipcRenderer.invoke('notes:create', input),
        update: (id, updates) => electron_1.ipcRenderer.invoke('notes:update', id, updates),
        delete: (id) => electron_1.ipcRenderer.invoke('notes:delete', id),
        reorder: (items) => electron_1.ipcRenderer.invoke('notes:reorder', items),
    },
    settings: {
        get: () => electron_1.ipcRenderer.invoke('settings:get'),
        save: (settings) => electron_1.ipcRenderer.invoke('settings:save', settings),
    },
    app: {
        getVersion: () => electron_1.ipcRenderer.invoke('app:getVersion'),
        showNotification: (title, body) => electron_1.ipcRenderer.invoke('app:showNotification', title, body),
        getAutoLaunch: () => electron_1.ipcRenderer.invoke('app:getAutoLaunch'),
        setAutoLaunch: (enabled) => electron_1.ipcRenderer.invoke('app:setAutoLaunch', enabled),
    },
    data: {
        exportAll: (settings) => electron_1.ipcRenderer.invoke('data:export', settings),
        importAll: () => electron_1.ipcRenderer.invoke('data:import'),
    },
    recurrence: {
        create: (task, rule) => electron_1.ipcRenderer.invoke('recurrence:create', task, rule),
        updateSeries: (ruleId, updates) => electron_1.ipcRenderer.invoke('recurrence:updateSeries', ruleId, updates),
        deleteSeries: (ruleId) => electron_1.ipcRenderer.invoke('recurrence:deleteSeries', ruleId),
        deleteSeriesFuture: (ruleId, fromDate) => electron_1.ipcRenderer.invoke('recurrence:deleteSeriesFuture', ruleId, fromDate),
        detachInstance: (taskId) => electron_1.ipcRenderer.invoke('recurrence:detachInstance', taskId),
        ensureInstances: (startDate, endDate) => electron_1.ipcRenderer.invoke('recurrence:ensureInstances', startDate, endDate),
        updateRule: (ruleId, ruleUpdates) => electron_1.ipcRenderer.invoke('recurrence:updateRule', ruleId, ruleUpdates),
        getRule: (ruleId) => electron_1.ipcRenderer.invoke('recurrence:getRule', ruleId),
    },
    focus: {
        updateTray: (data) => electron_1.ipcRenderer.send('focus:updateTray', data),
        showWidget: () => electron_1.ipcRenderer.send('focus:showWidget'),
        hideWidget: () => electron_1.ipcRenderer.send('focus:hideWidget'),
        sendWidgetState: (data) => electron_1.ipcRenderer.send('focus:widgetState', data),
    },
    on: (channel, callback) => {
        electron_1.ipcRenderer.on(channel, (_e, ...args) => callback(...args));
    },
    off: (channel, callback) => {
        electron_1.ipcRenderer.removeListener(channel, callback);
    },
    removeAllListeners: (channel) => {
        electron_1.ipcRenderer.removeAllListeners(channel);
    },
};
electron_1.contextBridge.exposeInMainWorld('api', api);
