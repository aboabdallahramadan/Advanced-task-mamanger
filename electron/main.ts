import { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, Notification } from 'electron';
import path from 'path';
import { initDatabase, getDatabase } from './database';
import { TaskService } from './taskService';
import { ProjectService } from './projectService';
import { seedDemoData } from './seed';

let mainWindow: BrowserWindow | null = null;
let tray: Tray | null = null;
let taskService: TaskService;
let projectService: ProjectService;

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
        const dbPath = path.join(app.getPath('userData'), 'daily-planner.db');
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
            label: 'Open Daily Planner',
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

    tray.setToolTip('Daily Planner');
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
}
