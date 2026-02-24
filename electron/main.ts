import { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, Notification, screen, globalShortcut, dialog } from 'electron';
import path from 'path';
import fs from 'fs';

function getSettingsPath() {
    return path.join(app.getPath('userData'), 'settings.json');
}

function loadSettings(): Record<string, any> {
    try {
        const p = getSettingsPath();
        if (fs.existsSync(p)) {
            return JSON.parse(fs.readFileSync(p, 'utf-8'));
        }
    } catch (e) {
        console.error('Failed to load settings:', e);
    }
    return {};
}

function persistSettings(settings: Record<string, any>): void {
    try {
        fs.writeFileSync(getSettingsPath(), JSON.stringify(settings, null, 2), 'utf-8');
    } catch (e) {
        console.error('Failed to save settings:', e);
    }
}
import { initDatabase, getDatabase, saveDatabase } from './database';
import { TaskService } from './taskService';
import { ProjectService } from './projectService';
import { seedDemoData } from './seed';

let mainWindow: BrowserWindow | null = null;
let focusWidget: BrowserWindow | null = null;
let tray: Tray | null = null;
let taskService: TaskService;
let projectService: ProjectService;

function createFocusWidget() {
    if (focusWidget && !focusWidget.isDestroyed()) {
        focusWidget.show();
        return;
    }

    const { width } = screen.getPrimaryDisplay().workAreaSize;

    focusWidget = new BrowserWindow({
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
        focusWidget.loadFile(path.join(__dirname, '../public/focus-widget.html'));
    } else {
        focusWidget.loadFile(path.join(__dirname, '../dist/focus-widget.html'));
    }

    focusWidget.setAlwaysOnTop(true, 'screen-saver');

    focusWidget.on('closed', () => {
        focusWidget = null;
    });
}

const gotTheLock = app.requestSingleInstanceLock();

if (!gotTheLock) {
    app.quit();
} else {
    app.on('second-instance', () => {
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.focus();
        }
    });

    app.whenReady().then(async () => {
        const dbPath = path.join(app.getPath('userData'), 'tmap.db');
        await initDatabase(dbPath);
        taskService = new TaskService(getDatabase());
        projectService = new ProjectService(getDatabase());

        // Seed demo data if empty
        const tasks = taskService.getAll();
        if (tasks.length === 0) {
            seedDemoData(taskService);
        }

        createWindow();
        createTray();
        registerIpcHandlers();

        // Global shortcut: Ctrl+Shift+F to toggle the focus timer widget
        globalShortcut.register('Ctrl+Shift+Q', () => {
            if (focusWidget && !focusWidget.isDestroyed()) {
                focusWidget.close();
                focusWidget = null;
            } else {
                createFocusWidget();
                // Send current focus state to the new widget
                mainWindow?.webContents.send('focus:resyncWidget');
            }
        });
    });

    app.on('will-quit', () => {
        globalShortcut.unregisterAll();
    });

    app.on('window-all-closed', () => {
        // On Windows, don't quit — minimize to tray
    });

    app.on('activate', () => {
        if (mainWindow === null) createWindow();
    });
}

function createWindow() {
    mainWindow = new BrowserWindow({
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
            preload: path.join(__dirname, 'preload.js'),
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
    } else {
        mainWindow.loadFile(path.join(__dirname, '../dist/index.html'));
    }
}

function createTray() {
    const icon = nativeImage.createFromDataURL(
        'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAEeSURBVFhH7ZZBDoMwDATz/0+nV3pBQkns9dqCHpBWGu+OHYcQ8vX19Z8cHpBcHpBcHpBcHpBcPwcUJxck1c8BNckF0QswkFwQvQAD6UVxAoakF8UJGJJeFCdgSC+KEzAkvShOwJD0ojgBQ9KL4gQMSS+KEzAkvShOwJD0ojgBQ9KL4gQMSS+KEzAkvShOwJD0ojgBQ9KL4v8Aezhe4GIXuxjf24X8m+2dvmcsbufiU3+h28Vu5+JTP6PLxW7n4lO/oMvFbufidT7RzMXrfKKZi9f5RDMX+0QzF/tEMxf7RDMX+0QzF/tEMxf7RDMX+0QzF/tEMxf7RLPy/gBgIL0ouQBD0ouSBzCQXpQ8gIH04v8DQvgGdIlrOH7OONYAAAAASUVORK5CYII='
    );
    tray = new Tray(icon);

    const contextMenu = Menu.buildFromTemplate([
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
                app.quit();
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
    ipcMain.handle('tasks:getAll', () => {
        return taskService.getAll();
    });

    ipcMain.handle('tasks:getByDate', (_e: any, date: string) => {
        return taskService.getByDate(date);
    });

    ipcMain.handle('tasks:getByStatus', (_e: any, status: string) => {
        return taskService.getByStatus(status);
    });

    ipcMain.handle('tasks:create', (_e: any, task: any) => {
        return taskService.create(task);
    });

    ipcMain.handle('tasks:update', (_e: any, id: string, updates: any) => {
        return taskService.update(id, updates);
    });

    ipcMain.handle('tasks:delete', (_e: any, id: string) => {
        return taskService.delete(id);
    });

    ipcMain.handle('tasks:reorder', (_e: any, tasks: { id: string; order: number }[]) => {
        return taskService.reorder(tasks);
    });

    ipcMain.handle('tasks:search', (_e: any, query: string) => {
        return taskService.search(query);
    });

    ipcMain.handle('app:getVersion', () => {
        return app.getVersion();
    });

    ipcMain.handle('app:showNotification', (_e: any, title: string, body: string) => {
        new Notification({ title, body }).show();
    });

    // ─── Project IPC Handlers ────────────────────────────────
    ipcMain.handle('projects:getAll', () => {
        return projectService.getAll();
    });

    ipcMain.handle('projects:create', (_e: any, input: any) => {
        return projectService.create(input);
    });

    ipcMain.handle('projects:update', (_e: any, id: string, updates: any) => {
        return projectService.update(id, updates);
    });

    ipcMain.handle('projects:delete', (_e: any, id: string) => {
        return projectService.delete(id);
    });

    ipcMain.handle('projects:reorder', (_e: any, items: { id: string; order: number }[]) => {
        return projectService.reorder(items);
    });

    // ─── Settings IPC Handlers ───────────────────────────────
    ipcMain.handle('settings:get', () => {
        return loadSettings();
    });

    ipcMain.handle('settings:save', (_e: any, settings: Record<string, any>) => {
        persistSettings(settings);
        return true;
    });

    // ─── Focus Timer Tray Widget ────────────────────────────
    ipcMain.on('focus:updateTray', (_e: any, data: { taskTitle: string | null; elapsed: string | null; isPlaying: boolean }) => {
        if (!tray) return;

        if (data.taskTitle && data.elapsed) {
            // Focus session active — update tray tooltip
            const state = data.isPlaying ? '▶' : '⏸';
            tray.setToolTip(`${state} ${data.taskTitle}\n⏱ ${data.elapsed}`);

            // Rebuild context menu with focus controls
            const contextMenu = Menu.buildFromTemplate([
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
                        app.quit();
                    },
                },
            ]);
            tray.setContextMenu(contextMenu);
        } else {
            // No focus session — reset to default
            tray.setToolTip('TMap');
            const contextMenu = Menu.buildFromTemplate([
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
                        app.quit();
                    },
                },
            ]);
            tray.setContextMenu(contextMenu);
        }
    });

    // ─── Focus Widget Window (Always-on-Top) ────────────────
    ipcMain.on('focus:showWidget', () => {
        createFocusWidget();
    });

    ipcMain.on('focus:hideWidget', () => {
        if (focusWidget && !focusWidget.isDestroyed()) {
            focusWidget.close();
            focusWidget = null;
        }
    });

    ipcMain.on('focus:widgetState', (_e: any, data: any) => {
        if (focusWidget && !focusWidget.isDestroyed()) {
            focusWidget.webContents.send('focus:state', data);
        }
    });

    // Widget button actions → forward to main renderer
    ipcMain.on('focus:widgetAction', (_e: any, action: string) => {
        if (!mainWindow) return;
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
    ipcMain.handle('data:export', async (_e: any, settings: any) => {
        try {
            const db = getDatabase();

            // Query all tasks
            const tasksResult = db.exec('SELECT * FROM tasks');
            const tasks: any[] = [];
            if (tasksResult.length > 0) {
                const cols = tasksResult[0].columns;
                for (const row of tasksResult[0].values) {
                    const obj: any = {};
                    cols.forEach((col: string, i: number) => { obj[col] = row[i]; });
                    // Parse labels JSON string
                    if (typeof obj.labels === 'string') {
                        try { obj.labels = JSON.parse(obj.labels); } catch { obj.labels = []; }
                    }
                    tasks.push(obj);
                }
            }

            // Query all projects
            const projectsResult = db.exec('SELECT * FROM projects');
            const projects: any[] = [];
            if (projectsResult.length > 0) {
                const cols = projectsResult[0].columns;
                for (const row of projectsResult[0].values) {
                    const obj: any = {};
                    cols.forEach((col: string, i: number) => { obj[col] = row[i]; });
                    projects.push(obj);
                }
            }

            const exportData = {
                meta: {
                    appName: 'TMap',
                    version: app.getVersion(),
                    exportedAt: new Date().toISOString(),
                },
                settings: settings || {},
                tasks,
                projects,
            };

            const { canceled, filePath } = await dialog.showSaveDialog(mainWindow!, {
                title: 'Export TMap Data',
                defaultPath: `tmap-backup-${new Date().toISOString().slice(0, 10)}.json`,
                filters: [{ name: 'JSON Files', extensions: ['json'] }],
            });

            if (canceled || !filePath) {
                return { success: false, canceled: true };
            }

            fs.writeFileSync(filePath, JSON.stringify(exportData, null, 2), 'utf-8');
            return { success: true, filePath };
        } catch (err: any) {
            console.error('Export failed:', err);
            return { success: false, error: err.message };
        }
    });

    ipcMain.handle('data:import', async () => {
        try {
            const { canceled, filePaths } = await dialog.showOpenDialog(mainWindow!, {
                title: 'Import TMap Data',
                filters: [{ name: 'JSON Files', extensions: ['json'] }],
                properties: ['openFile'],
            });

            if (canceled || filePaths.length === 0) {
                return { success: false, canceled: true };
            }

            const raw = fs.readFileSync(filePaths[0], 'utf-8');
            let parsed: any;
            try {
                parsed = JSON.parse(raw);
            } catch {
                return { success: false, error: 'Invalid JSON file.' };
            }

            // Validate structure
            if (!parsed.tasks || !Array.isArray(parsed.tasks)) {
                return { success: false, error: 'Invalid backup file: missing tasks array.' };
            }
            if (!parsed.projects || !Array.isArray(parsed.projects)) {
                return { success: false, error: 'Invalid backup file: missing projects array.' };
            }

            const db = getDatabase();

            db.run('BEGIN TRANSACTION;');
            try {
                // Clear existing data
                db.run('DELETE FROM tasks;');
                db.run('DELETE FROM projects;');

                // Insert tasks
                for (const t of parsed.tasks) {
                    db.run(
                        `INSERT INTO tasks (id, title, notes, project, labels, source, status, due_date, planned_date, scheduled_start, scheduled_end, duration_minutes, actual_time_minutes, sort_order, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
                        [
                            t.id, t.title, t.notes || '', t.project || '',
                            Array.isArray(t.labels) ? JSON.stringify(t.labels) : (t.labels || '[]'),
                            t.source || 'local', t.status || 'inbox',
                            t.due_date || null, t.planned_date || null,
                            t.scheduled_start || null, t.scheduled_end || null,
                            t.duration_minutes ?? 30, t.actual_time_minutes ?? 0,
                            t.sort_order ?? 0,
                            t.created_at || new Date().toISOString(),
                            t.updated_at || new Date().toISOString(),
                        ]
                    );
                }

                // Insert projects
                for (const p of parsed.projects) {
                    db.run(
                        `INSERT INTO projects (id, name, color, emoji, sort_order, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?)`,
                        [
                            p.id, p.name, p.color || '#6366f1', p.emoji || '📁',
                            p.sort_order ?? 0,
                            p.created_at || new Date().toISOString(),
                            p.updated_at || new Date().toISOString(),
                        ]
                    );
                }

                db.run('COMMIT;');
                saveDatabase();

                return {
                    success: true,
                    data: {
                        settings: parsed.settings || {},
                        taskCount: parsed.tasks.length,
                        projectCount: parsed.projects.length,
                    },
                };
            } catch (err: any) {
                db.run('ROLLBACK;');
                console.error('Import transaction failed:', err);
                return { success: false, error: 'Import failed: ' + err.message };
            }
        } catch (err: any) {
            console.error('Import failed:', err);
            return { success: false, error: err.message };
        }
    });
}
