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
      // 401 → true auth failure: return null so bootstrap clears the session.
      // network failure / 5xx → THROW (name 'NetworkError') so authStore.bootstrap's
      // network branch shows a retry banner and KEEPS the possibly-valid cookie session.
      const res = await fetch(`${this.baseUrl}/api/v1/auth/refresh`, {
        method: 'POST',
        credentials: 'include',
        headers: { 'X-Tmap-Refresh': '1' },
      });
      if (res.status === 401) return null;
      if (!res.ok) {
        const err = new Error(`refresh failed: ${res.status}`);
        err.name = 'NetworkError';
        throw err;
      }
      return (await res.json()) as AuthTokenResponse;
    },
    clear: async (): Promise<void> => {
      // The httpOnly cookie is cleared server-side by /auth/logout (called via
      // logout() below). Nothing JS-accessible to clear here.
    },
    setRefreshToken: async (): Promise<void> => {
      // Web: refresh token is set as an httpOnly cookie by the server response.
    },
    logout: async (): Promise<void> => {
      // Revoke server-side via the httpOnly cookie (server reads it; no body).
      try {
        await fetch(`${this.baseUrl}/api/v1/auth/logout`, {
          method: 'POST',
          credentials: 'include',
        });
      } catch {
        // best-effort; local sign-out proceeds regardless
      }
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
