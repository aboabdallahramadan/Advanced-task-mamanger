// packages/app/src/AppRoot.tsx
import React, { useEffect, useMemo, useRef, useState } from 'react';
import App from './App';
import { useStore } from './store';
import type { DataClient } from './data/DataClient';
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
  /** Data seam the store calls; built by the host entry over the (refresh-wrapped) TmapClient. */
  dataClient: DataClient;
  /** Host capabilities + token storage + events. */
  platform: Platform;
  /** Raw typed client for auth POSTs (register/login/logout) and 401-retry wrapping. */
  tmapClient: TmapClient;
}

type AnonScreen = 'login' | 'register';

export function AppRoot({ dataClient, platform, tmapClient }: AppRootProps) {
  const initialized = useRef(false);
  const [anonScreen, setAnonScreen] = useState<AnonScreen>('login');

  // Build the auth singleton + refresh wrapper exactly once.
  if (!initialized.current) {
    initialized.current = true;
    const refreshClient = createRefreshClient({
      client: tmapClient as any,
      refresh: () => platform.auth.refreshAndGetAccess(),
      onLogout: () => {
        // refresh path gave up: drive a full logout through the store.
        void getAuthStore().getState().logout();
      },
    });
    refreshClient.setAbortController(new AbortController());

    initAuthStore({
      client: tmapClient as any,
      platform,
      onAuthed: () => {
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
    return <App />;
  }, [status, anonScreen]);

  return content;
}

export default AppRoot;
