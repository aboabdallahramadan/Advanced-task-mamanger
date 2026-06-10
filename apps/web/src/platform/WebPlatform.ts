import type { Platform, AppChannel } from '@/platform/Platform';
import type { AuthTokenResponse } from '@/auth/types';

/**
 * Web host adapter. The refresh token lives in an httpOnly cookie (never visible
 * to JS); refresh is a credentialed POST with the CSRF custom header. No tray,
 * no widget window, no auto-launch. Notifications use the Web Notifications API.
 */
export class WebPlatform implements Platform {
  readonly capabilities = {
    tray: false,
    focusWidgetWindow: false,
    autoLaunch: false,
    dataPort: false,
  };

  private readonly baseUrl: string;

  constructor(baseUrl: string) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
  }

  readonly auth = {
    refreshAndGetAccess: async (): Promise<AuthTokenResponse | null> => {
      try {
        const res = await fetch(`${this.baseUrl}/api/v1/auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-Tmap-Refresh': '1' },
        });
        if (res.status === 401) return null; // cookie missing/expired → anonymous
        if (!res.ok) return null; // network/5xx → couldn't refresh (don't clear)
        const data = (await res.json()) as AuthTokenResponse;
        return data;
      } catch {
        return null; // network failure → couldn't refresh
      }
    },
    clear: async (): Promise<void> => {
      // The httpOnly cookie is cleared server-side by /auth/logout (called by
      // authStore.logout). Nothing JS-accessible to clear here.
    },
    setRefreshToken: async (): Promise<void> => {
      // Web: refresh token is set as an httpOnly cookie by the server response.
    },
  };

  notify(title: string, body: string): void {
    try {
      if (typeof Notification === 'undefined') return;
      if (Notification.permission === 'granted') {
        new Notification(title, { body });
      } else if (Notification.permission === 'default') {
        void Notification.requestPermission().then((perm) => {
          if (perm === 'granted') new Notification(title, { body });
        });
      }
      // 'denied' → silent
    } catch {
      // Notifications unavailable → silent
    }
  }

  // Web has no host event channels; on/off are no-ops by design (spec §4).
  on(_channel: AppChannel, _cb: (...args: any[]) => void): void {}
  off(_channel: AppChannel, _cb: (...args: any[]) => void): void {}

  // focusWidget and autoLaunch intentionally absent (capabilities are false).
}
