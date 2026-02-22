"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const electron_1 = require("electron");
const path_1 = __importDefault(require("path"));
const database_1 = require("./database");
const taskService_1 = require("./taskService");
const projectService_1 = require("./projectService");
const seed_1 = require("./seed");
let mainWindow = null;
let focusWidget = null;
let tray = null;
let taskService;
let projectService;
function createFocusWidget() {
    if (focusWidget && !focusWidget.isDestroyed()) {
        focusWidget.show();
        return;
    }
    const { width } = electron_1.screen.getPrimaryDisplay().workAreaSize;
    focusWidget = new electron_1.BrowserWindow({
        width: 420,
        height: 52,
        x: Math.round(width / 2 - 210),
        y: 8,
        frame: false,
        transparent: true,
        resizable: false,
        skipTaskbar: true,
        alwaysOnTop: true,
        focusable: true,
        hasShadow: false,
        webPreferences: {
            nodeIntegration: true,
            contextIsolation: false,
        },
    });
    if (process.env.VITE_DEV_SERVER_URL) {
        // In dev, load from Vite's public directory
        focusWidget.loadFile(path_1.default.join(__dirname, '../public/focus-widget.html'));
    }
    else {
        focusWidget.loadFile(path_1.default.join(__dirname, '../dist/focus-widget.html'));
    }
    focusWidget.setAlwaysOnTop(true, 'screen-saver');
    focusWidget.on('closed', () => {
        focusWidget = null;
    });
}
const gotTheLock = electron_1.app.requestSingleInstanceLock();
if (!gotTheLock) {
    electron_1.app.quit();
}
else {
    electron_1.app.on('second-instance', () => {
        if (mainWindow) {
            if (mainWindow.isMinimized())
                mainWindow.restore();
            mainWindow.focus();
        }
    });
    electron_1.app.whenReady().then(async () => {
        const dbPath = path_1.default.join(electron_1.app.getPath('userData'), 'tmap.db');
        await (0, database_1.initDatabase)(dbPath);
        taskService = new taskService_1.TaskService((0, database_1.getDatabase)());
        projectService = new projectService_1.ProjectService((0, database_1.getDatabase)());
        // Seed demo data if empty
        const tasks = taskService.getAll();
        if (tasks.length === 0) {
            (0, seed_1.seedDemoData)(taskService);
        }
        createWindow();
        createTray();
        registerIpcHandlers();
    });
    electron_1.app.on('window-all-closed', () => {
        // On Windows, don't quit — minimize to tray
    });
    electron_1.app.on('activate', () => {
        if (mainWindow === null)
            createWindow();
    });
}
function createWindow() {
    mainWindow = new electron_1.BrowserWindow({
        width: 1400,
        height: 900,
        minWidth: 1000,
        minHeight: 700,
        frame: false,
        titleBarStyle: 'hidden',
        titleBarOverlay: {
            color: '#020617',
            symbolColor: '#94a3b8',
            height: 40,
        },
        backgroundColor: '#020617',
        webPreferences: {
            preload: path_1.default.join(__dirname, 'preload.js'),
            contextIsolation: true,
            nodeIntegration: false,
            sandbox: false,
        },
        show: false,
    });
    mainWindow.once('ready-to-show', () => {
        mainWindow?.show();
    });
    mainWindow.on('close', (e) => {
        e.preventDefault();
        mainWindow?.hide();
    });
    if (process.env.VITE_DEV_SERVER_URL) {
        mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL);
    }
    else {
        mainWindow.loadFile(path_1.default.join(__dirname, '../dist/index.html'));
    }
}
function createTray() {
    const icon = electron_1.nativeImage.createFromDataURL('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAEeSURBVFhH7ZZBDoMwDATz/0+nV3pBQkns9dqCHpBWGu+OHYcQ8vX19Z8cHpBcHpBcHpBcHpBcPwcUJxck1c8BNckF0QswkFwQvQAD6UVxAoakF8UJGJJeFCdgSC+KEzAkvShOwJD0ojgBQ9KL4gQMSS+KEzAkvShOwJD0ojgBQ9KL4gQMSS+KEzAkvShOwJD0ojgBQ9KL4v8Aezhe4GIXuxjf24X8m+2dvmcsbufiU3+h28Vu5+JTP6PLxW7n4lO/oMvFbufidT7RzMXrfKKZi9f5RDMX+0QzF/tEMxf7RDMX+0QzF/tEMxf7RDMX+0QzF/tEMxf7RLPy/gBgIL0ouQBD0ouSBzCQXpQ8gIH04v8DQvgGdIlrOH7OONYAAAAASUVORK5CYII=');
    tray = new electron_1.Tray(icon);
    const contextMenu = electron_1.Menu.buildFromTemplate([
        {
            label: 'Open TMap',
            click: () => {
                mainWindow?.show();
                mainWindow?.focus();
            },
        },
        {
            label: 'Plan Today',
            click: () => {
                mainWindow?.show();
                mainWindow?.focus();
                mainWindow?.webContents.send('navigate', 'plan-today');
            },
        },
        { type: 'separator' },
        {
            label: 'Quit',
            click: () => {
                mainWindow?.destroy();
                electron_1.app.quit();
            },
        },
    ]);
    tray.setToolTip('TMap');
    tray.setContextMenu(contextMenu);
    tray.on('double-click', () => {
        mainWindow?.show();
        mainWindow?.focus();
    });
}
function registerIpcHandlers() {
    electron_1.ipcMain.handle('tasks:getAll', () => {
        return taskService.getAll();
    });
    electron_1.ipcMain.handle('tasks:getByDate', (_e, date) => {
        return taskService.getByDate(date);
    });
    electron_1.ipcMain.handle('tasks:getByStatus', (_e, status) => {
        return taskService.getByStatus(status);
    });
    electron_1.ipcMain.handle('tasks:create', (_e, task) => {
        return taskService.create(task);
    });
    electron_1.ipcMain.handle('tasks:update', (_e, id, updates) => {
        return taskService.update(id, updates);
    });
    electron_1.ipcMain.handle('tasks:delete', (_e, id) => {
        return taskService.delete(id);
    });
    electron_1.ipcMain.handle('tasks:reorder', (_e, tasks) => {
        return taskService.reorder(tasks);
    });
    electron_1.ipcMain.handle('tasks:search', (_e, query) => {
        return taskService.search(query);
    });
    electron_1.ipcMain.handle('app:getVersion', () => {
        return electron_1.app.getVersion();
    });
    electron_1.ipcMain.handle('app:showNotification', (_e, title, body) => {
        new electron_1.Notification({ title, body }).show();
    });
    // ─── Project IPC Handlers ────────────────────────────────
    electron_1.ipcMain.handle('projects:getAll', () => {
        return projectService.getAll();
    });
    electron_1.ipcMain.handle('projects:create', (_e, input) => {
        return projectService.create(input);
    });
    electron_1.ipcMain.handle('projects:update', (_e, id, updates) => {
        return projectService.update(id, updates);
    });
    electron_1.ipcMain.handle('projects:delete', (_e, id) => {
        return projectService.delete(id);
    });
    // ─── Focus Timer Tray Widget ────────────────────────────
    electron_1.ipcMain.on('focus:updateTray', (_e, data) => {
        if (!tray)
            return;
        if (data.taskTitle && data.elapsed) {
            // Focus session active — update tray tooltip
            const state = data.isPlaying ? '▶' : '⏸';
            tray.setToolTip(`${state} ${data.taskTitle}\n⏱ ${data.elapsed}`);
            // Rebuild context menu with focus controls
            const contextMenu = electron_1.Menu.buildFromTemplate([
                {
                    label: `${state} ${data.taskTitle}`,
                    enabled: false,
                },
                {
                    label: `⏱ ${data.elapsed}`,
                    enabled: false,
                },
                { type: 'separator' },
                {
                    label: data.isPlaying ? '⏸ Pause' : '▶ Resume',
                    click: () => {
                        mainWindow?.webContents.send('focus:togglePlayPause');
                    },
                },
                {
                    label: '⏹ Stop Timer',
                    click: () => {
                        mainWindow?.webContents.send('focus:stop');
                    },
                },
                { type: 'separator' },
                {
                    label: 'Open TMap',
                    click: () => {
                        mainWindow?.show();
                        mainWindow?.focus();
                    },
                },
                {
                    label: 'Quit',
                    click: () => {
                        mainWindow?.destroy();
                        electron_1.app.quit();
                    },
                },
            ]);
            tray.setContextMenu(contextMenu);
        }
        else {
            // No focus session — reset to default
            tray.setToolTip('TMap');
            const contextMenu = electron_1.Menu.buildFromTemplate([
                {
                    label: 'Open TMap',
                    click: () => {
                        mainWindow?.show();
                        mainWindow?.focus();
                    },
                },
                { type: 'separator' },
                {
                    label: 'Quit',
                    click: () => {
                        mainWindow?.destroy();
                        electron_1.app.quit();
                    },
                },
            ]);
            tray.setContextMenu(contextMenu);
        }
    });
    // ─── Focus Widget Window (Always-on-Top) ────────────────
    electron_1.ipcMain.on('focus:showWidget', () => {
        createFocusWidget();
    });
    electron_1.ipcMain.on('focus:hideWidget', () => {
        if (focusWidget && !focusWidget.isDestroyed()) {
            focusWidget.close();
            focusWidget = null;
        }
    });
    electron_1.ipcMain.on('focus:widgetState', (_e, data) => {
        if (focusWidget && !focusWidget.isDestroyed()) {
            focusWidget.webContents.send('focus:state', data);
        }
    });
    // Widget button actions → forward to main renderer
    electron_1.ipcMain.on('focus:widgetAction', (_e, action) => {
        if (!mainWindow)
            return;
        switch (action) {
            case 'togglePlayPause':
                mainWindow.webContents.send('focus:togglePlayPause');
                break;
            case 'stop':
                mainWindow.webContents.send('focus:stop');
                break;
            case 'done':
                mainWindow.webContents.send('focus:done');
                break;
        }
    });
}
