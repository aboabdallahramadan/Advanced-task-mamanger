"use strict";
const electron = require("electron");
const api = {
  tasks: {
    getAll: () => electron.ipcRenderer.invoke("tasks:getAll"),
    getByDate: (date) => electron.ipcRenderer.invoke("tasks:getByDate", date),
    getByStatus: (status) => electron.ipcRenderer.invoke("tasks:getByStatus", status),
    create: (task) => electron.ipcRenderer.invoke("tasks:create", task),
    update: (id, updates) => electron.ipcRenderer.invoke("tasks:update", id, updates),
    delete: (id) => electron.ipcRenderer.invoke("tasks:delete", id),
    reorder: (tasks) => electron.ipcRenderer.invoke("tasks:reorder", tasks),
    search: (query) => electron.ipcRenderer.invoke("tasks:search", query)
  },
  projects: {
    getAll: () => electron.ipcRenderer.invoke("projects:getAll"),
    create: (input) => electron.ipcRenderer.invoke("projects:create", input),
    update: (id, updates) => electron.ipcRenderer.invoke("projects:update", id, updates),
    delete: (id) => electron.ipcRenderer.invoke("projects:delete", id)
  },
  app: {
    getVersion: () => electron.ipcRenderer.invoke("app:getVersion"),
    showNotification: (title, body) => electron.ipcRenderer.invoke("app:showNotification", title, body)
  },
  focus: {
    updateTray: (data) => electron.ipcRenderer.send("focus:updateTray", data),
    showWidget: () => electron.ipcRenderer.send("focus:showWidget"),
    hideWidget: () => electron.ipcRenderer.send("focus:hideWidget"),
    sendWidgetState: (data) => electron.ipcRenderer.send("focus:widgetState", data)
  },
  on: (channel, callback) => {
    electron.ipcRenderer.on(channel, (_e, ...args) => callback(...args));
  },
  off: (channel, callback) => {
    electron.ipcRenderer.removeListener(channel, callback);
  }
};
electron.contextBridge.exposeInMainWorld("api", api);
