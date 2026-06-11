// packages/app/src/AppRoot.tsx
import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import App from './App';
import { useStore } from './store';
import { HttpDataClient } from './data/HttpDataClient';
import type { Platform } from './platform/Platform';
import type { TmapClient } from '@tmap/api-client';
import {
  initAuthStore,
  getAuthStore,
  useAuthStore,
  createRefreshClient,
  LoginView,
  RegisterView,
  type AuthState,
} from './auth';

export interface AppRootProps {
  /** Host capabilities + token storage + events. */
  platform: Platform;
  /**
   * Raw typed client (no refresh wrapping). AppRoot wraps it ONCE with the 401→refresh
   * layer and routes BOTH auth POSTs (register/login/logout) AND the data layer
   * (HttpDataClient) through that single wrapped client — one shared refresh path.
   * The access token is injected by the host's `getAccessToken` middleware reading the
   * authStore; web additionally passes `credentials:'include'` for the refresh cookie.
   */
  tmapClient: TmapClient;
}

// ─── Platform context ───────────────────────────────────────
// Exposes the host adapter to the shared app (FocusModeOverlay / App / SettingsDialog)
// without prop-drilling. Provided by AppRoot's authed branch.
const PlatformContext = createContext<Platform | null>(null);

export function usePlatform(): Platform {
  const p = useContext(PlatformContext);
  if (!p) throw new Error('usePlatform must be used within <AppRoot>');
  return p;
}

export { PlatformContext };

type AnonScreen = 'login' | 'register';

export function AppRoot({ platform, tmapClient }: AppRootProps) {
  const initialized = useRef(false);
  const [anonScreen, setAnonScreen] = useState<AnonScreen>('login');

  // Build the auth singleton + ONE refresh wrapper exactly once.
  //
  // The 401→refresh→retry layer wraps the DATA path only (HttpDataClient). Auth POSTs
  // (login/register/logout) deliberately use the RAW client: a 401 there means "wrong
  // credentials / no session", which must surface as an error — NOT trigger a token
  // refresh + retry + logout.
  //
  // Crucially, the data path's refresh is the authStore's own `refresh()` (NOT
  // platform.auth.refreshAndGetAccess directly): authStore.refresh() is single-flight
  // AND writes the new access token into the store, so the retried request — whose
  // Bearer header is injected from the store via getAccessToken — picks up the fresh
  // token. There is therefore exactly one 401→refresh path, funneled through the store.
  if (!initialized.current) {
    initialized.current = true;
    const refreshClient = createRefreshClient({
      client: tmapClient as any,
      refresh: () => getAuthStore().getState().refresh(),
      onLogout: () => {
        // refresh path gave up: drive a full logout through the store.
        void getAuthStore().getState().logout();
      },
    });
    refreshClient.setAbortController(new AbortController());

    // The store's data seam talks to the refresh-wrapped client (401→refresh→retry).
    const dataClient = new HttpDataClient(refreshClient as unknown as TmapClient);

    initAuthStore({
      client: tmapClient as any, // raw: auth 401s are real errors, not refresh triggers
      platform,
      onAuthed: () => {
        // Re-arm the single refresh wrapper in case a prior session signed it out
        // (logout → re-login within one app session). Without this, the SAME dead
        // client is re-injected and every data call throws "Session ended".
        refreshClient.reactivate();
        useStore.getState().setDataClient(dataClient);
        // Initial load over HTTP (tasks/projects/noteGroups/settings, etc.).
        void useStore.getState().initialLoad?.();
      },
      onLoggedOut: () => {
        refreshClient.signOut();
        useStore.getState().reset();
      },
    });
  }

  const status = useAuthStore((s: AuthState) => s.status);

  useEffect(() => {
    void getAuthStore().getState().bootstrap();
  }, []);

  // Web only: request Notification permission once so client-side reminders can fire.
  // Desktop notifies through the OS via main (platform.notify → window.api), so the
  // renderer's web Notification prompt is both unnecessary and a UX wart there. The
  // Electron renderer is Chromium, so `window.Notification` IS present on desktop —
  // gate on capabilities.tray (web=false) rather than feature-detecting the API.
  useEffect(() => {
    if (platform.capabilities.tray) return; // desktop → OS notifications via main
    if (
      typeof window !== 'undefined' &&
      'Notification' in window &&
      window.Notification.permission === 'default'
    ) {
      void window.Notification.requestPermission().catch(() => {});
    }
  }, []);

  const content = useMemo(() => {
    if (status === 'loading') {
      return (
        <div className="h-screen flex flex-col items-center justify-center bg-surface-950 text-surface-400 select-none">
          <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
          <div className="w-8 h-8 rounded-full border-2 border-surface-700 border-t-accent-500 animate-spin" />
          <p className="mt-4 text-sm">Loading…</p>
        </div>
      );
    }
    if (status === 'anonymous') {
      return anonScreen === 'login' ? (
        <LoginView onSwitchToRegister={() => setAnonScreen('register')} />
      ) : (
        <RegisterView onSwitchToLogin={() => setAnonScreen('login')} />
      );
    }
    return (
      <PlatformContext.Provider value={platform}>
        <App />
      </PlatformContext.Provider>
    );
  }, [status, anonScreen, platform]);

  return content;
}

export default AppRoot;
