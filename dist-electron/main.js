"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
const electron_1 = require("electron");
const path_1 = __importDefault(require("path"));
const fs_1 = __importDefault(require("fs"));
function getSettingsPath() {
    return path_1.default.join(electron_1.app.getPath('userData'), 'settings.json');
}
function loadSettings() {
    try {
        const p = getSettingsPath();
        if (fs_1.default.existsSync(p)) {
            return JSON.parse(fs_1.default.readFileSync(p, 'utf-8'));
        }
    }
    catch (e) {
        console.error('Failed to load settings:', e);
    }
    return {};
}
function persistSettings(settings) {
    try {
        fs_1.default.writeFileSync(getSettingsPath(), JSON.stringify(settings, null, 2), 'utf-8');
    }
    catch (e) {
        console.error('Failed to save settings:', e);
    }
}
const database_1 = require("./database");
const taskService_1 = require("./taskService");
const projectService_1 = require("./projectService");
const noteService_1 = require("./noteService");
const seed_1 = require("./seed");
let mainWindow = null;
let focusWidget = null;
let tray = null;
let taskService;
let projectService;
let noteService;
const notifiedTaskIds = new Set();
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
    electron_1.app.setAppUserModelId('com.tmap.app');
    electron_1.app.whenReady().then(async () => {
        const dbPath = path_1.default.join(electron_1.app.getPath('userData'), 'tmap.db');
        await (0, database_1.initDatabase)(dbPath);
        taskService = new taskService_1.TaskService((0, database_1.getDatabase)());
        projectService = new projectService_1.ProjectService((0, database_1.getDatabase)());
        noteService = new noteService_1.NoteService((0, database_1.getDatabase)());
        // Seed demo data if empty
        const tasks = taskService.getAll();
        if (tasks.length === 0) {
            (0, seed_1.seedDemoData)(taskService);
        }
        createWindow();
        createTray();
        registerIpcHandlers();
        // ─── Notification Scheduler ──────────────────────────────
        setInterval(() => {
            try {
                const tasks = taskService.getUpcomingWithReminders();
                const now = Date.now();
                for (const task of tasks) {
                    if (notifiedTaskIds.has(task.id))
                        continue;
                    if (!task.scheduledStart)
                        continue;
                    const startTime = new Date(task.scheduledStart).getTime();
                    const reminderMs = (task.reminderMinutes ?? 0) * 60000;
                    const notifyAt = startTime - reminderMs;
                    // Notify if time has come but task start is not more than 5 min in the past
                    if (notifyAt <= now && startTime > now - 5 * 60000) {
                        const body = task.reminderMinutes === 0
                            ? 'Starting now'
                            : `Starting in ${task.reminderMinutes} minutes`;
                        const notification = new electron_1.Notification({
                            title: task.title,
                            body,
                            icon: path_1.default.join(__dirname, '../build/icon.png'),
                        });
                        notification.on('click', () => {
                            mainWindow?.show();
                            mainWindow?.focus();
                        });
                        notification.show();
                        notifiedTaskIds.add(task.id);
                    }
                }
                // Cleanup: remove entries for tasks started more than 1 hour ago
                for (const id of notifiedTaskIds) {
                    const task = tasks.find(t => t.id === id);
                    if (task?.scheduledStart) {
                        const startTime = new Date(task.scheduledStart).getTime();
                        if (startTime < now - 3600000) {
                            notifiedTaskIds.delete(id);
                        }
                    }
                }
            }
            catch (e) {
                console.error('Notification scheduler error:', e);
            }
        }, 30000);
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
        if (updates.scheduledStart !== undefined || updates.reminderMinutes !== undefined) {
            notifiedTaskIds.delete(id);
        }
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
    // ─── Subtask IPC Handlers ───────────────────────────────
    electron_1.ipcMain.handle('subtasks:create', (_e, taskId, title) => {
        return taskService.createSubtask(taskId, title);
    });
    electron_1.ipcMain.handle('subtasks:update', (_e, id, updates) => {
        return taskService.updateSubtask(id, updates);
    });
    electron_1.ipcMain.handle('subtasks:delete', (_e, id) => {
        return taskService.deleteSubtask(id);
    });
    electron_1.ipcMain.handle('app:getVersion', () => {
        return electron_1.app.getVersion();
    });
    electron_1.ipcMain.handle('app:showNotification', (_e, title, body) => {
        new electron_1.Notification({ title, body, icon: path_1.default.join(__dirname, '../build/icon.png') }).show();
    });
    // ─── Auto-Launch ─────────────────────────────────────────
    electron_1.ipcMain.handle('app:getAutoLaunch', () => {
        return electron_1.app.getLoginItemSettings().openAtLogin;
    });
    electron_1.ipcMain.handle('app:setAutoLaunch', (_e, enabled) => {
        electron_1.app.setLoginItemSettings({ openAtLogin: enabled });
        return true;
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
    electron_1.ipcMain.handle('projects:reorder', (_e, items) => {
        return projectService.reorder(items);
    });
    // ─── Note Group IPC Handlers ─────────────────────────────
    electron_1.ipcMain.handle('noteGroups:getAll', () => {
        return noteService.getAllGroups();
    });
    electron_1.ipcMain.handle('noteGroups:getByProject', (_e, projectId) => {
        return noteService.getGroupsByProject(projectId);
    });
    electron_1.ipcMain.handle('noteGroups:create', (_e, input) => {
        return noteService.createGroup(input);
    });
    electron_1.ipcMain.handle('noteGroups:update', (_e, id, updates) => {
        return noteService.updateGroup(id, updates);
    });
    electron_1.ipcMain.handle('noteGroups:delete', (_e, id) => {
        return noteService.deleteGroup(id);
    });
    electron_1.ipcMain.handle('noteGroups:reorder', (_e, items) => {
        return noteService.reorderGroups(items);
    });
    // ─── Note IPC Handlers ───────────────────────────────────
    electron_1.ipcMain.handle('notes:getAll', () => {
        return noteService.getAllNotes();
    });
    electron_1.ipcMain.handle('notes:getByGroup', (_e, groupId) => {
        return noteService.getNotesByGroup(groupId);
    });
    electron_1.ipcMain.handle('notes:getByProject', (_e, projectId) => {
        return noteService.getNotesByProject(projectId);
    });
    electron_1.ipcMain.handle('notes:getById', (_e, id) => {
        return noteService.getNoteById(id);
    });
    electron_1.ipcMain.handle('notes:create', (_e, input) => {
        return noteService.createNote(input);
    });
    electron_1.ipcMain.handle('notes:update', (_e, id, updates) => {
        return noteService.updateNote(id, updates);
    });
    electron_1.ipcMain.handle('notes:delete', (_e, id) => {
        return noteService.deleteNote(id);
    });
    electron_1.ipcMain.handle('notes:reorder', (_e, items) => {
        return noteService.reorderNotes(items);
    });
    // ─── Settings IPC Handlers ───────────────────────────────
    electron_1.ipcMain.handle('settings:get', () => {
        return loadSettings();
    });
    electron_1.ipcMain.handle('settings:save', (_e, settings) => {
        persistSettings(settings);
        return true;
    });
    // ─── Recurrence IPC Handlers ─────────────────────────────
    electron_1.ipcMain.handle('recurrence:create', (_e, task, rule) => {
        return taskService.createRecurringTask(task, rule);
    });
    electron_1.ipcMain.handle('recurrence:updateSeries', (_e, ruleId, updates) => {
        return taskService.updateSeries(ruleId, updates);
    });
    electron_1.ipcMain.handle('recurrence:deleteSeries', (_e, ruleId) => {
        return taskService.deleteSeries(ruleId);
    });
    electron_1.ipcMain.handle('recurrence:deleteSeriesFuture', (_e, ruleId, fromDate) => {
        return taskService.deleteSeriesFuture(ruleId, fromDate);
    });
    electron_1.ipcMain.handle('recurrence:detachInstance', (_e, taskId) => {
        return taskService.detachInstance(taskId);
    });
    electron_1.ipcMain.handle('recurrence:ensureInstances', (_e, startDate, endDate) => {
        return taskService.ensureInstancesForDateRange(startDate, endDate);
    });
    electron_1.ipcMain.handle('recurrence:updateRule', (_e, ruleId, ruleUpdates) => {
        return taskService.updateRecurrenceRule(ruleId, ruleUpdates);
    });
    electron_1.ipcMain.handle('recurrence:getRule', (_e, ruleId) => {
        return taskService.getRecurrenceRule(ruleId);
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
            // Query recurrence rules
            const rulesResult = db.exec('SELECT * FROM recurrence_rules');
            const recurrenceRules = [];
            if (rulesResult.length > 0) {
                const cols = rulesResult[0].columns;
                for (const row of rulesResult[0].values) {
                    const obj = {};
                    cols.forEach((col, i) => { obj[col] = row[i]; });
                    recurrenceRules.push(obj);
                }
            }
            // Query recurrence exceptions
            const excResult = db.exec('SELECT * FROM recurrence_exceptions');
            const recurrenceExceptions = [];
            if (excResult.length > 0) {
                const cols = excResult[0].columns;
                for (const row of excResult[0].values) {
                    const obj = {};
                    cols.forEach((col, i) => { obj[col] = row[i]; });
                    recurrenceExceptions.push(obj);
                }
            }
            // Query note groups
            const noteGroupsResult = db.exec('SELECT * FROM note_groups');
            const noteGroupsData = [];
            if (noteGroupsResult.length > 0) {
                const cols = noteGroupsResult[0].columns;
                for (const row of noteGroupsResult[0].values) {
                    const obj = {};
                    cols.forEach((col, i) => { obj[col] = row[i]; });
                    noteGroupsData.push(obj);
                }
            }
            // Query notes
            const notesResult = db.exec('SELECT * FROM notes');
            const notesData = [];
            if (notesResult.length > 0) {
                const cols = notesResult[0].columns;
                for (const row of notesResult[0].values) {
                    const obj = {};
                    cols.forEach((col, i) => { obj[col] = row[i]; });
                    notesData.push(obj);
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
                recurrenceRules,
                recurrenceExceptions,
                noteGroups: noteGroupsData,
                notes: notesData,
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
                db.run('DELETE FROM notes;');
                db.run('DELETE FROM note_groups;');
                db.run('DELETE FROM recurrence_exceptions;');
                db.run('DELETE FROM tasks;');
                db.run('DELETE FROM recurrence_rules;');
                db.run('DELETE FROM projects;');
                // Insert recurrence rules
                if (parsed.recurrenceRules && Array.isArray(parsed.recurrenceRules)) {
                    for (const r of parsed.recurrenceRules) {
                        db.run(`INSERT INTO recurrence_rules (id, frequency, interval_value, days_of_week, end_type, end_count, end_date, generated_until, created_at, updated_at)
                             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
                            r.id, r.frequency, r.interval_value ?? 1,
                            r.days_of_week || '[]', r.end_type || 'never',
                            r.end_count ?? null, r.end_date ?? null,
                            r.generated_until ?? null,
                            r.created_at || new Date().toISOString(),
                            r.updated_at || new Date().toISOString(),
                        ]);
                    }
                }
                // Insert tasks
                for (const t of parsed.tasks) {
                    db.run(`INSERT INTO tasks (id, title, notes, project, labels, source, status, due_date, planned_date, scheduled_start, scheduled_end, duration_minutes, actual_time_minutes, priority, reminder_minutes, sort_order, recurrence_rule_id, is_recurrence_template, recurrence_detached, recurrence_original_date, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
                        t.id, t.title, t.notes || '', t.project || '',
                        Array.isArray(t.labels) ? JSON.stringify(t.labels) : (t.labels || '[]'),
                        t.source || 'local', t.status || 'inbox',
                        t.due_date || null, t.planned_date || null,
                        t.scheduled_start || null, t.scheduled_end || null,
                        t.duration_minutes ?? 30, t.actual_time_minutes ?? 0,
                        t.priority ?? null, t.reminder_minutes ?? 0,
                        t.sort_order ?? 0,
                        t.recurrence_rule_id ?? null,
                        t.is_recurrence_template ?? 0,
                        t.recurrence_detached ?? 0,
                        t.recurrence_original_date ?? null,
                        t.created_at || new Date().toISOString(),
                        t.updated_at || new Date().toISOString(),
                    ]);
                }
                // Insert recurrence exceptions
                if (parsed.recurrenceExceptions && Array.isArray(parsed.recurrenceExceptions)) {
                    for (const e of parsed.recurrenceExceptions) {
                        db.run(`INSERT INTO recurrence_exceptions (id, recurrence_rule_id, exception_date, created_at)
                             VALUES (?, ?, ?, ?)`, [e.id, e.recurrence_rule_id, e.exception_date, e.created_at || new Date().toISOString()]);
                    }
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
                // Insert note groups
                if (parsed.noteGroups && Array.isArray(parsed.noteGroups)) {
                    for (const g of parsed.noteGroups) {
                        db.run(`INSERT INTO note_groups (id, name, emoji, project_id, sort_order, created_at, updated_at)
                             VALUES (?, ?, ?, ?, ?, ?, ?)`, [
                            g.id, g.name, g.emoji || '📝', g.project_id || null,
                            g.sort_order ?? 0,
                            g.created_at || new Date().toISOString(),
                            g.updated_at || new Date().toISOString(),
                        ]);
                    }
                }
                // Insert notes
                if (parsed.notes && Array.isArray(parsed.notes)) {
                    for (const n of parsed.notes) {
                        db.run(`INSERT INTO notes (id, group_id, project_id, title, content, sort_order, created_at, updated_at)
                             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`, [
                            n.id, n.group_id || null, n.project_id || null,
                            n.title || 'Untitled', n.content || '',
                            n.sort_order ?? 0,
                            n.created_at || new Date().toISOString(),
                            n.updated_at || new Date().toISOString(),
                        ]);
                    }
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
