import { contextBridge, ipcRenderer } from 'electron';

/**
 * Desktop-only bridge. SP2 moves ALL data CRUD to HTTP (@tmap/api-client),
 * so this preload exposes ONLY host capabilities: secure refresh-token store,
 * notifications, auto-launch, tray/navigation events, the focus widget, and
 * app metadata. No tasks/projects/notes/settings data channels remain.
 */
const api = {
  app: {
    getVersion: (): Promise<string> => ipcRenderer.invoke('app:getVersion'),
    showNotification: (title: string, body: string): void =>
      ipcRenderer.send('app:showNotification', title, body),
    getAutoLaunch: (): Promise<boolean> => ipcRenderer.invoke('app:getAutoLaunch'),
    setAutoLaunch: (enabled: boolean): Promise<boolean> =>
      ipcRenderer.invoke('app:setAutoLaunch', enabled),
  },

  /**
   * Refresh token lives ONLY in the main process (safeStorage). The renderer can
   * ask main to perform /auth/refresh and receive only the access token back; it
   * can store a freshly-issued refresh token (after login/register) and clear it.
   */
  secureStore: {
    setRefreshToken: (token: string): Promise<void> =>
      ipcRenderer.invoke('secureStore:setRefreshToken', token),
    clear: (): Promise<void> => ipcRenderer.invoke('secureStore:clear'),
    /** Revokes the stored refresh token server-side (POST /auth/logout in main), then clears it. */
    logout: (): Promise<void> => ipcRenderer.invoke('secureStore:logout'),
    /**
     * Performs POST /auth/refresh in main using the stored refresh token. Returns a
     * discriminated result (C8.2): the keychain token is cleared in main ONLY on a
     * true 401 (`unauthorized`); 5xx / network failures are `transient` and keep it.
     */
    refreshAndGetAccess: (): Promise<
      | { ok: true; accessToken: string; expiresIn: number; user: { id: string; email: string; timeZoneId: string } }
      | { ok: false; reason: 'unauthorized' | 'transient' }
    > => ipcRenderer.invoke('secureStore:refreshAndGetAccess'),
  },

  focus: {
    // forward state to the always-on-top widget window
    showWidget: (): void => ipcRenderer.send('focus:showWidget'),
    hideWidget: (): void => ipcRenderer.send('focus:hideWidget'),
    sendWidgetState: (data: unknown): void => ipcRenderer.send('focus:widgetState', data),
    updateTray: (data: {
      taskTitle: string | null;
      elapsed: string | null;
      isPlaying: boolean;
    }): void => ipcRenderer.send('focus:updateTray', data),
  },

  on: (channel: string, callback: (...args: any[]) => void): void => {
    ipcRenderer.on(channel, (_e, ...args) => callback(...args));
  },
  off: (channel: string, callback: (...args: any[]) => void): void => {
    ipcRenderer.removeListener(channel, callback);
  },
  removeAllListeners: (channel: string): void => {
    ipcRenderer.removeAllListeners(channel);
  },
};

contextBridge.exposeInMainWorld('api', api);

export type ElectronAPI = typeof api;
