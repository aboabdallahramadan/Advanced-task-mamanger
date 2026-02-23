"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const electron_1 = require("electron");
const path_1 = __importDefault(require("path"));
const fs_1 = __importDefault(require("fs"));
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
        // Global shortcut: Ctrl+Shift+F to toggle the focus timer widget
        electron_1.globalShortcut.register('Ctrl+Shift+Q', () => {
            if (focusWidget && !focusWidget.isDestroyed()) {
                focusWidget.close();
                focusWidget = null;
            }
            else {
                createFocusWidget();
                // Send current focus state to the new widget
                mainWindow?.webContents.send('focus:resyncWidget');
            }
        });
    });
    electron_1.app.on('will-quit', () => {
        electron_1.globalShortcut.unregisterAll();
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
    // ─── Data Import / Export ────────────────────────────────
    electron_1.ipcMain.handle('data:export', async (_e, settings) => {
        try {
            const db = (0, database_1.getDatabase)();
            // Query all tasks
            const tasksResult = db.exec('SELECT * FROM tasks');
            const tasks = [];
            if (tasksResult.length > 0) {
                const cols = tasksResult[0].columns;
                for (const row of tasksResult[0].values) {
                    const obj = {};
                    cols.forEach((col, i) => { obj[col] = row[i]; });
                    // Parse labels JSON string
                    if (typeof obj.labels === 'string') {
                        try {
                            obj.labels = JSON.parse(obj.labels);
                        }
                        catch {
                            obj.labels = [];
                        }
                    }
                    tasks.push(obj);
                }
            }
            // Query all projects
            const projectsResult = db.exec('SELECT * FROM projects');
            const projects = [];
            if (projectsResult.length > 0) {
                const cols = projectsResult[0].columns;
                for (const row of projectsResult[0].values) {
                    const obj = {};
                    cols.forEach((col, i) => { obj[col] = row[i]; });
                    projects.push(obj);
                }
            }
            const exportData = {
                meta: {
                    appName: 'TMap',
                    version: electron_1.app.getVersion(),
                    exportedAt: new Date().toISOString(),
                },
                settings: settings || {},
                tasks,
                projects,
            };
            const { canceled, filePath } = await electron_1.dialog.showSaveDialog(mainWindow, {
                title: 'Export TMap Data',
                defaultPath: `tmap-backup-${new Date().toISOString().slice(0, 10)}.json`,
                filters: [{ name: 'JSON Files', extensions: ['json'] }],
            });
            if (canceled || !filePath) {
                return { success: false, canceled: true };
            }
            fs_1.default.writeFileSync(filePath, JSON.stringify(exportData, null, 2), 'utf-8');
            return { success: true, filePath };
        }
        catch (err) {
            console.error('Export failed:', err);
            return { success: false, error: err.message };
        }
    });
    electron_1.ipcMain.handle('data:import', async () => {
        try {
            const { canceled, filePaths } = await electron_1.dialog.showOpenDialog(mainWindow, {
                title: 'Import TMap Data',
                filters: [{ name: 'JSON Files', extensions: ['json'] }],
                properties: ['openFile'],
            });
            if (canceled || filePaths.length === 0) {
                return { success: false, canceled: true };
            }
            const raw = fs_1.default.readFileSync(filePaths[0], 'utf-8');
            let parsed;
            try {
                parsed = JSON.parse(raw);
            }
            catch {
                return { success: false, error: 'Invalid JSON file.' };
            }
            // Validate structure
            if (!parsed.tasks || !Array.isArray(parsed.tasks)) {
                return { success: false, error: 'Invalid backup file: missing tasks array.' };
            }
            if (!parsed.projects || !Array.isArray(parsed.projects)) {
                return { success: false, error: 'Invalid backup file: missing projects array.' };
            }
            const db = (0, database_1.getDatabase)();
            db.run('BEGIN TRANSACTION;');
            try {
                // Clear existing data
                db.run('DELETE FROM tasks;');
                db.run('DELETE FROM projects;');
                // Insert tasks
                for (const t of parsed.tasks) {
                    db.run(`INSERT INTO tasks (id, title, notes, project, labels, source, status, due_date, planned_date, scheduled_start, scheduled_end, duration_minutes, actual_time_minutes, sort_order, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
                        t.id, t.title, t.notes || '', t.project || '',
                        Array.isArray(t.labels) ? JSON.stringify(t.labels) : (t.labels || '[]'),
                        t.source || 'local', t.status || 'inbox',
                        t.due_date || null, t.planned_date || null,
                        t.scheduled_start || null, t.scheduled_end || null,
                        t.duration_minutes ?? 30, t.actual_time_minutes ?? 0,
                        t.sort_order ?? 0,
                        t.created_at || new Date().toISOString(),
                        t.updated_at || new Date().toISOString(),
                    ]);
                }
                // Insert projects
                for (const p of parsed.projects) {
                    db.run(`INSERT INTO projects (id, name, color, emoji, sort_order, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?)`, [
                        p.id, p.name, p.color || '#6366f1', p.emoji || '📁',
                        p.sort_order ?? 0,
                        p.created_at || new Date().toISOString(),
                        p.updated_at || new Date().toISOString(),
                    ]);
                }
                db.run('COMMIT;');
                (0, database_1.saveDatabase)();
                return {
                    success: true,
                    data: {
                        settings: parsed.settings || {},
                        taskCount: parsed.tasks.length,
                        projectCount: parsed.projects.length,
                    },
                };
            }
            catch (err) {
                db.run('ROLLBACK;');
                console.error('Import transaction failed:', err);
                return { success: false, error: 'Import failed: ' + err.message };
            }
        }
        catch (err) {
            console.error('Import failed:', err);
            return { success: false, error: err.message };
        }
    });
}
