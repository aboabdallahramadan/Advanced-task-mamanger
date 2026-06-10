// packages/app/src/platform/Platform.ts
// Host adapter seam (spec §4). DesktopPlatform and WebPlatform implement this;
// the shared app in packages/app depends only on this interface.
// AuthTokenResponse is imported from auth/types (not authStore) to avoid a circular dep.
import type { AuthTokenResponse } from '../auth/types';

/**
 * Host capability flags. The shared app feature-gates UI off these
 * (hide auto-launch / OS tray / separate-widget-window / data export-import on web).
 */
export interface PlatformCapabilities {
  tray: boolean;
  focusWidgetWindow: boolean;
  autoLaunch: boolean;
  dataPort: boolean;
}

/** Event channels the shared app listens on; web no-ops, desktop maps to IPC. */
export type AppChannel =
  | 'navigate'
  | 'focus:togglePlayPause'
  | 'focus:stop'
  | 'focus:done'
  | 'focus:resyncWidget';

/** Snapshot pushed to the desktop always-on-top focus widget window. */
export interface FocusWidgetState {
  taskTitle: string;
  isPlaying: boolean;
  /** Wall-clock ms when the current running session began, or null when paused. */
  sessionStartTime: number | null;
  accumulatedMinutes: number;
  plannedMinutes: number;
  /** Whether the widget's "done" button is meaningful (tasks yes, projects no). */
  canComplete: boolean;
}

/** Actions the desktop focus widget can fire back at the app. */
export type FocusWidgetAction = 'togglePlayPause' | 'stop' | 'done';

/**
 * Host adapter seam. DesktopPlatform (apps/desktop) and WebPlatform (apps/web)
 * implement this; the shared app in packages/app depends only on this interface.
 */
export interface Platform {
  capabilities: PlatformCapabilities;

  /**
   * Token plumbing. Desktop keeps the refresh token in the main process
   * (safeStorage) and returns only the access token to the renderer; web
   * uses the httpOnly refresh cookie. `clear` revokes/forgets the stored token.
   */
  auth: {
    refreshAndGetAccess(): Promise<AuthTokenResponse | null>;
    clear(): Promise<void>;
  };

  /** OS notification on desktop; permission-gated Web Notifications on web. */
  notify(title: string, body: string): void;

  /** Subscribe to a host event (web no-ops). */
  on(channel: AppChannel, cb: (...args: any[]) => void): void;
  /** Unsubscribe (web no-ops). */
  off(channel: AppChannel, cb: (...args: any[]) => void): void;

  /** Desktop only — present iff capabilities.focusWidgetWindow. */
  focusWidget?: {
    pushState(state: FocusWidgetState): void;
    show(): void;
    hide(): void;
    onAction(cb: (action: FocusWidgetAction) => void): void;
    onResyncRequest(cb: () => void): void;
  };

  /** Desktop only — present iff capabilities.autoLaunch. */
  autoLaunch?: {
    get(): Promise<boolean>;
    set(on: boolean): Promise<void>;
  };
}
