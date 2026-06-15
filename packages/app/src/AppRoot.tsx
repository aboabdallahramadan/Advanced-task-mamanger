// packages/app/src/AppRoot.tsx
import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react';
import App from './App';
import { useStore } from './store';
import { LocalDataClient } from './data/local/LocalDataClient';
import { LocalStore, getLastUserId, setLastUserId } from './data/local/LocalStore';
import { SyncEngine } from './sync/SyncEngine';
import { HttpSyncTransport } from './sync/SyncTransport';
import { InitialSyncGate } from './components/InitialSyncGate';
import { createSyncWiring, type WiringStore, type WiringEngine } from './wiring/createSyncWiring';
import type { Platform } from './platform/Platform';
import type { TmapClient } from '@tmap/api-client';
import type { AuthUser } from './auth/types';
import type { SyncStatus } from './sync/types';
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
   * layer and routes BOTH auth POSTs (register/login/logout) AND the sync transport
   * through that single wrapped client — one shared refresh path.
   */
  tmapClient: TmapClient;
}

// ─── Platform context ───────────────────────────────────────
const PlatformContext = createContext<Platform | null>(null);
export function usePlatform(): Platform {
  const p = useContext(PlatformContext);
  if (!p) throw new Error('usePlatform must be used within <AppRoot>');
  return p;
}
export { PlatformContext };

// The live SyncEngine for the current authed session (read by App's SyncStatusPill).
// Set by the wiring factory's onAuthed; cleared on logout.
const EngineContext = createContext<WiringEngine | null>(null);
export function useEngine(): WiringEngine | null {
  return useContext(EngineContext);
}

type AnonScreen = 'login' | 'register';

export function AppRoot({ platform, tmapClient }: AppRootProps) {
  const initialized = useRef(false);
  const [anonScreen, setAnonScreen] = useState<AnonScreen>('login');
  const [engine, setEngine] = useState<WiringEngine | null>(null);
  const [needsGate, setNeedsGate] = useState(false);

  if (!initialized.current) {
    initialized.current = true;

    // ONE refresh wrapper around the raw client (401→refresh→retry); see C8.1.
    const refreshClient = createRefreshClient({
      client: tmapClient as any,
      refresh: () => getAuthStore().getState().refresh(),
      onLogout: () => {
        void getAuthStore().getState().logout();
      },
    });
    refreshClient.setAbortController(new AbortController());

    // Pure wiring factory (C10). Injects real LocalStore/SyncEngine/LocalDataClient
    // builders + the store actions. The transport is the refresh-wrapped client.
    const wiring = createSyncWiring({
      setLastUserId: (id) => setLastUserId(id),
      openStore: (userId) => LocalStore.open(userId) as unknown as WiringStore,
      writeLastUserMeta: async (store, user) => {
        await store.setMeta('lastUser', {
          id: user.id,
          email: user.email,
          timeZoneId: user.timeZoneId,
        });
      },
      buildEngine: (store) =>
        new SyncEngine({
          store: store as unknown as LocalStore,
          transport: new HttpSyncTransport(refreshClient),
        }) as unknown as WiringEngine,
      buildDataClient: (store, eng) =>
        new LocalDataClient(store as unknown as LocalStore, eng as unknown as any),
      setDataClient: (client) => useStore.getState().setDataClient(client as any),
      initialLoad: () => useStore.getState().initialLoad(),
      // C10: the engine's onChangesApplied → store.refreshFromLocal() is wired by the
      // factory; AppRoot supplies the store action here (read getState() lazily so the
      // call always hits the live store, even after a reset()).
      refreshFromLocal: () => useStore.getState().refreshFromLocal(),
      runRolloverOnce: () => useStore.getState().runRolloverOnce(),
      resetStore: () => useStore.getState().reset(),
    });

    initAuthStore({
      client: tmapClient as any, // raw: auth 401s are real errors, not refresh triggers
      platform,
      // C8.3: resolve last user from the global pointer + that DB's meta.lastUser,
      // consulted only on the transient-refresh branch (authed-offline).
      resolveLastUser: async () => {
        const id = getLastUserId();
        if (!id) return null;
        const store = LocalStore.open(id);
        try {
          const lu = await store.getMeta<AuthUser>('lastUser');
          return lu ?? null;
        } finally {
          store.close();
        }
      },
      onAuthed: (auth) => {
        refreshClient.reactivate();
        void (async () => {
          const { needsInitialSyncGate } = await wiring.onAuthed(auth.user);
          setEngine(wiring.getEngine());
          setNeedsGate(needsInitialSyncGate);
        })();
      },
      onLoggedOut: () => {
        refreshClient.signOut();
        wiring.onLoggedOut();
        setEngine(null);
        setNeedsGate(false);
      },
    });
  }

  const status = useAuthStore((s: AuthState) => s.status);

  useEffect(() => {
    void getAuthStore().getState().bootstrap();
  }, []);

  // Web only: request Notification permission once so client-side reminders can fire.
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
        <EngineContext.Provider value={engine}>
          <GatedApp engine={engine} needsGate={needsGate} />
        </EngineContext.Provider>
      </PlatformContext.Provider>
    );
  }, [status, anonScreen, platform, engine, needsGate]);

  return content;
}

/**
 * Wraps <App/> in the InitialSyncGate while the first full sync is incomplete.
 * Subscribes to the engine's status so the gate re-renders as pages apply.
 */
function GatedApp({ engine, needsGate }: { engine: WiringEngine | null; needsGate: boolean }) {
  // C5: getStatus() is a SYNCHRONOUS snapshot — no optional-call / `as any` cast needed.
  const [status, setStatus] = useState<SyncStatus | null>(() =>
    engine ? engine.getStatus() : null,
  );

  useEffect(() => {
    if (!engine) return;
    // SyncEngine.subscribe (C5) fires once immediately with getStatus() then on each
    // change, and returns an unsubscribe fn. The gate re-renders as pages apply and
    // lifts once a subscribed status reports initialSyncComplete (C10).
    return engine.subscribe((s) => setStatus(s));
  }, [engine]);

  if (!needsGate || !engine || !status) return <App />;

  return (
    <InitialSyncGate status={status} onRetry={() => void engine.syncNow()}>
      <App />
    </InitialSyncGate>
  );
}

export default AppRoot;
