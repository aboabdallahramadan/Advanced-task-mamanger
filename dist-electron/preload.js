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
    projects: {
        getAll: () => electron_1.ipcRenderer.invoke('projects:getAll'),
        create: (input) => electron_1.ipcRenderer.invoke('projects:create', input),
        update: (id, updates) => electron_1.ipcRenderer.invoke('projects:update', id, updates),
        delete: (id) => electron_1.ipcRenderer.invoke('projects:delete', id),
    },
    app: {
        getVersion: () => electron_1.ipcRenderer.invoke('app:getVersion'),
        showNotification: (title, body) => electron_1.ipcRenderer.invoke('app:showNotification', title, body),
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
