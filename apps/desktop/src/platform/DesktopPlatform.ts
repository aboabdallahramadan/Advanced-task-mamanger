import type {
  Platform,
  AppChannel,
  FocusWidgetState,
  FocusWidgetAction,
} from '@/platform/Platform';
import type { AuthTokenResponse } from '@/auth/types';

/**
 * Desktop host adapter. Talks to the Electron preload (`window.api`). All data
 * goes over HTTP elsewhere; this only covers host capabilities: the secure
 * refresh-token store, OS notifications, the always-on-top focus widget window +
 * tray, auto-launch, and the tray/widget reverse event channels.
 */
export class DesktopPlatform implements Platform {
  readonly capabilities = {
    tray: true,
    focusWidgetWindow: true,
    autoLaunch: true,
    dataPort: false,
  };

  readonly auth = {
    refreshAndGetAccess: async (): Promise<AuthTokenResponse | null> => {
      const res = await window.api.secureStore.refreshAndGetAccess();
      return res;
    },
    clear: async (): Promise<void> => {
      await window.api.secureStore.clear();
    },
    /** Desktop extra: persist a freshly-issued refresh token in main (safeStorage). */
    setRefreshToken: async (token: string): Promise<void> => {
      await window.api.secureStore.setRefreshToken(token);
    },
  };

  notify(title: string, body: string): void {
    window.api.app.showNotification(title, body);
  }

  on(channel: AppChannel, cb: (...args: any[]) => void): void {
    window.api.on(channel, cb);
  }

  off(channel: AppChannel, cb: (...args: any[]) => void): void {
    window.api.off(channel, cb);
  }

  readonly focusWidget = {
    pushState: (state: FocusWidgetState): void => {
      window.api.focus.sendWidgetState(state);
      // Desktop also reflects the running session in the tray tooltip. The overlay
      // no longer formats per-second elapsed for the tray (the widget window shows
      // live elapsed); pass a coarse label so the tray reflects play/pause + title.
      window.api.focus.updateTray({
        taskTitle: state.taskTitle || null,
        elapsed: state.taskTitle ? '' : null,
        isPlaying: state.isPlaying,
      });
    },
    show: (): void => {
      window.api.focus.showWidget();
    },
    hide: (): void => {
      window.api.focus.hideWidget();
    },
    onAction: (cb: (action: FocusWidgetAction) => void): void => {
      window.api.on('focus:togglePlayPause', () => cb('togglePlayPause'));
      window.api.on('focus:stop', () => cb('stop'));
      window.api.on('focus:done', () => cb('done'));
    },
    onResyncRequest: (cb: () => void): void => {
      window.api.on('focus:resyncWidget', cb);
    },
  };

  readonly autoLaunch = {
    get: (): Promise<boolean> => window.api.app.getAutoLaunch(),
    set: async (on: boolean): Promise<void> => {
      await window.api.app.setAutoLaunch(on);
    },
  };
}
