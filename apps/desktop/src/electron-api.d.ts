// apps/desktop/src/electron-api.d.ts
// Desktop-local renderer typing for the Electron preload (`window.api`).
//
// The new preload (Q5-8) exposes ONLY host capabilities — no data IPC. This file
// is the desktop-local replacement for the old, data-heavy `ElectronAPI`/`Window.api`
// block that still lives in `packages/app/src/types.ts`.
//
// SEAM (Q5-9): while that old block remains, augmenting `Window.api` here would
// collide with it (two incompatible `Window['api']` declarations). So the
// `declare global` augmentation below stays commented until Q5-9 removes the old
// block from `packages/app/src/types.ts`; at that point this becomes the SOLE
// `Window.api` declaration (the web build has no `window.api`).
export interface DesktopApi {
  app: {
    getVersion(): Promise<string>;
    showNotification(title: string, body: string): void;
    getAutoLaunch(): Promise<boolean>;
    setAutoLaunch(enabled: boolean): Promise<boolean>;
  };
  secureStore: {
    setRefreshToken(token: string): Promise<void>;
    clear(): Promise<void>;
    logout(): Promise<void>;
    refreshAndGetAccess(): Promise<{
      accessToken: string;
      expiresIn: number;
      user: { id: string; email: string; timeZoneId: string };
    } | null>;
  };
  focus: {
    showWidget(): void;
    hideWidget(): void;
    sendWidgetState(data: unknown): void;
    updateTray(data: {
      taskTitle: string | null;
      elapsed: string | null;
      isPlaying: boolean;
    }): void;
  };
  on(channel: string, callback: (...args: any[]) => void): void;
  off(channel: string, callback: (...args: any[]) => void): void;
  removeAllListeners(channel: string): void;
}

// Q5-9: the old `ElectronAPI`/`Window.api` block has been removed from
// `packages/app/src/types.ts`, so this is now the SOLE `Window.api` declaration
// (desktop-only; the web build has no `window.api`).
declare global {
  interface Window {
    api: DesktopApi;
  }
}

export {};
