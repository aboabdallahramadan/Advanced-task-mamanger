import {
  app,
  BrowserWindow,
  ipcMain,
  Tray,
  Menu,
  nativeImage,
  Notification,
  screen,
  globalShortcut,
  safeStorage,
} from 'electron';
import path from 'path';
import fs from 'fs';
import { API_BASE_URL } from './config';

let mainWindow: BrowserWindow | null = null;
let focusWidget: BrowserWindow | null = null;
let tray: Tray | null = null;

// ─── Secure refresh-token store (main-process only) ────────────
function refreshTokenPath(): string {
  return path.join(app.getPath('userData'), 'refresh.bin');
}

function persistRefreshToken(token: string): void {
  try {
    const buf = safeStorage.isEncryptionAvailable()
      ? safeStorage.encryptString(token)
      : Buffer.from(token, 'utf-8');
    fs.writeFileSync(refreshTokenPath(), buf);
  } catch (e) {
    console.error('Failed to persist refresh token:', e);
  }
}

function readRefreshToken(): string | null {
  try {
    const p = refreshTokenPath();
    if (!fs.existsSync(p)) return null;
    const buf = fs.readFileSync(p);
    return safeStorage.isEncryptionAvailable()
      ? safeStorage.decryptString(buf)
      : buf.toString('utf-8');
  } catch (e) {
    console.error('Failed to read refresh token:', e);
    return null;
  }
}

function clearRefreshToken(): void {
  try {
    const p = refreshTokenPath();
    if (fs.existsSync(p)) fs.unlinkSync(p);
  } catch (e) {
    console.error('Failed to clear refresh token:', e);
  }
}

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

  app.setAppUserModelId('com.tmap.app');

  app.whenReady().then(() => {
    createWindow();
    createTray();
    registerIpcHandlers();

    // Global shortcut: Ctrl+Shift+Q to toggle the focus timer widget
    globalShortcut.register('Ctrl+Shift+Q', () => {
      if (focusWidget && !focusWidget.isDestroyed()) {
        focusWidget.close();
        focusWidget = null;
      } else {
        createFocusWidget();
        // Ask the renderer to resync the new widget with current focus state
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
    'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAEeSURBVFhH7ZZBDoMwDATz/0+nV3pBQkns9dqCHpBWGu+OHYcQ8vX19Z8cHpBcHpBcHpBcHpBcPwcUJxck1c8BNckF0QswkFwQvQAD6UVxAoakF8UJGJJeFCdgSC+KEzAkvShOwJD0ojgBQ9KL4gQMSS+KEzAkvShOwJD0ojgBQ9KL4gQMSS+KEzAkvShOwJD0ojgBQ9KL4v8Aezhe4GIXuxjf24X8m+2dvmcsbufiU3+h28Vu5+JTP6PLxW7n4lO/oMvFbufidT7RzMXrfKKZi9f5RDMX+0QzF/tEMxf7RDMX+0QzF/tEMxf7RDMX+0QzF/tEMxf7RLPy/gBgIL0ouQBD0ouSBzCQXpQ8gIH04v8DQvgGdIlrOH7OONYAAAAASUVORK5CYII=',
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
  // ─── App metadata / notifications ──────────────────────────
  ipcMain.handle('app:getVersion', () => app.getVersion());

  ipcMain.on('app:showNotification', (_e, title: string, body: string) => {
    const n = new Notification({
      title,
      body,
      icon: path.join(__dirname, '../build/icon.png'),
    });
    n.on('click', () => {
      mainWindow?.show();
      mainWindow?.focus();
    });
    n.show();
  });

  // ─── Auto-Launch ───────────────────────────────────────────
  ipcMain.handle('app:getAutoLaunch', () => app.getLoginItemSettings().openAtLogin);

  ipcMain.handle('app:setAutoLaunch', (_e, enabled: boolean) => {
    app.setLoginItemSettings({ openAtLogin: enabled });
    return true;
  });

  // ─── Secure refresh-token store + refresh ──────────────────
  ipcMain.handle('secureStore:setRefreshToken', (_e, token: string) => {
    persistRefreshToken(token);
  });

  ipcMain.handle('secureStore:clear', () => {
    clearRefreshToken();
  });

  ipcMain.handle('secureStore:refreshAndGetAccess', async () => {
    const refreshToken = readRefreshToken();
    if (!refreshToken) return null;
    try {
      const res = await fetch(`${API_BASE_URL}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // Native path: refresh token in the body (SP1 §3.4).
        body: JSON.stringify({ refreshToken }),
      });
      if (res.status === 401) {
        // Refresh token rejected/rotated-away → forget it; renderer logs out.
        clearRefreshToken();
        return null;
      }
      if (!res.ok) {
        // Network/5xx — do NOT destroy a possibly-valid token; signal "couldn't refresh".
        return null;
      }
      const data = (await res.json()) as {
        accessToken: string;
        expiresIn: number;
        refreshToken?: string;
        user: { id: string; email: string; timeZoneId: string };
      };
      // Rotation: store the new refresh token if one was returned.
      if (data.refreshToken) persistRefreshToken(data.refreshToken);
      return {
        accessToken: data.accessToken,
        expiresIn: data.expiresIn,
        user: data.user,
      };
    } catch (e) {
      console.error('refreshAndGetAccess failed:', e);
      return null;
    }
  });

  ipcMain.handle('secureStore:logout', async () => {
    // Revoke the session server-side using the stored refresh token, THEN forget it
    // locally. (The renderer can't see the refresh token, so the server-side revoke
    // must originate here — otherwise logout left the desktop session valid until expiry.)
    const refreshToken = readRefreshToken();
    if (refreshToken) {
      try {
        await fetch(`${API_BASE_URL}/api/v1/auth/logout`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refreshToken }),
        });
      } catch (e) {
        console.error('secureStore:logout revoke failed:', e);
      }
    }
    clearRefreshToken();
  });

  // ─── Focus Timer Tray Widget ───────────────────────────────
  ipcMain.on(
    'focus:updateTray',
    (_e, data: { taskTitle: string | null; elapsed: string | null; isPlaying: boolean }) => {
      if (!tray) return;

      if (data.taskTitle && data.elapsed) {
        const state = data.isPlaying ? '▶' : '⏸';
        tray.setToolTip(`${state} ${data.taskTitle}\n⏱ ${data.elapsed}`);
        const contextMenu = Menu.buildFromTemplate([
          { label: `${state} ${data.taskTitle}`, enabled: false },
          { label: `⏱ ${data.elapsed}`, enabled: false },
          { type: 'separator' },
          {
            label: data.isPlaying ? '⏸ Pause' : '▶ Resume',
            click: () => mainWindow?.webContents.send('focus:togglePlayPause'),
          },
          {
            label: '⏹ Stop Timer',
            click: () => mainWindow?.webContents.send('focus:stop'),
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
    },
  );

  // ─── Focus Widget Window (Always-on-Top) ───────────────────
  ipcMain.on('focus:showWidget', () => {
    createFocusWidget();
  });

  ipcMain.on('focus:hideWidget', () => {
    if (focusWidget && !focusWidget.isDestroyed()) {
      focusWidget.close();
      focusWidget = null;
    }
  });

  ipcMain.on('focus:widgetState', (_e, data: unknown) => {
    if (focusWidget && !focusWidget.isDestroyed()) {
      focusWidget.webContents.send('focus:state', data);
    }
  });

  // Widget button actions → forward to main renderer
  ipcMain.on('focus:widgetAction', (_e, action: string) => {
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
}
