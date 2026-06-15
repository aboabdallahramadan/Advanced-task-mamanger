// packages/app/src/wiring/createSyncWiring.ts
// Pure wiring logic for AppRoot's authed lifecycle (C10), extracted so it can be
// unit-tested in the node vitest env without React. AppRoot injects real
// collaborators (LocalStore.open, new SyncEngine, new LocalDataClient, the store
// actions); this owns only the SEQUENCE and the online-vs-offline rollover scheduling.
import type { AuthUser } from '../auth/types';
import type { SyncStatus } from '../sync/types';

// Structural surfaces — only the members this factory touches (subset of C3/C5/C4).
export interface WiringStore {
  setMeta(key: string, value: unknown): Promise<void>;
  getMeta<T>(key: string): Promise<T | undefined>;
  close(): void;
}
// The members AppRoot/App's shells read off the live engine. A structural subset of
// the C5 SyncEngine surface (getStatus/subscribe/syncNow/retryIssue/discardIssue +
// the lifecycle + callback hooks) so the React shells are typed without `as any`.
export interface WiringEngine {
  start(): void;
  stop(): void;
  online(): boolean;
  getStatus(): SyncStatus;
  subscribe(cb: (s: SyncStatus) => void): () => void;
  syncNow(): Promise<void>;
  retryIssue(id: number): Promise<void>;
  discardIssue(id: number): Promise<void>;
  onChangesApplied(cb: () => void): () => void;
  onFirstCycleSettled(cb: () => void): () => void;
}

export interface SyncWiringDeps {
  /** Global pointer write (C3 setLastUserId over localStorage). */
  setLastUserId(id: string | null): void;
  /** LocalStore.open(userId) (C3). */
  openStore(userId: string): WiringStore;
  /** Persist meta.lastUser { id, email, timeZoneId }. */
  writeLastUserMeta(store: WiringStore, user: AuthUser): Promise<void>;
  /** new SyncEngine({ store, transport }) (C5). */
  buildEngine(store: WiringStore): WiringEngine;
  /** new LocalDataClient(store, engine) (C4). */
  buildDataClient(store: WiringStore, engine: WiringEngine): unknown;
  /** store.setDataClient(localDataClient). */
  setDataClient(client: unknown): void;
  /** store.initialLoad(). */
  initialLoad(): Promise<void>;
  /** store.refreshFromLocal() (C9.1) — driven by the engine after a remote pull. */
  refreshFromLocal(): Promise<void>;
  /** store.runRolloverOnce() (C9.3). */
  runRolloverOnce(): Promise<void>;
  /** store.reset(). */
  resetStore(): void;
}

export interface OnAuthedResult {
  /** True when meta.initialSyncComplete is falsy → AppRoot renders <InitialSyncGate>. */
  needsInitialSyncGate: boolean;
}

export interface SyncWiring {
  onAuthed(user: AuthUser): Promise<OnAuthedResult>;
  onLoggedOut(): void;
  /** Current engine (for AppRoot to feed SyncStatusPill via engine.subscribe); null before onAuthed. */
  getEngine(): WiringEngine | null;
}

export function createSyncWiring(deps: SyncWiringDeps): SyncWiring {
  let store: WiringStore | null = null;
  let engine: WiringEngine | null = null;
  const subscriptions: Array<() => void> = []; // unsubscribers, drained on logout

  return {
    async onAuthed(user: AuthUser): Promise<OnAuthedResult> {
      deps.setLastUserId(user.id);
      store = deps.openStore(user.id);
      await deps.writeLastUserMeta(store, user);
      engine = deps.buildEngine(store);
      const dataClient = deps.buildDataClient(store, engine);
      deps.setDataClient(dataClient);

      // C10: the remote-pull → UI-refresh path (spec §1/AC3). When the engine applies
      // pulled changes to the local store it fires onChangesApplied; re-read collections
      // into the Zustand store so the UI reflects converged multi-device state. Registered
      // BEFORE engine.start() so the very first cycle's applied changes are not missed.
      subscriptions.push(
        engine.onChangesApplied(() => {
          void deps.refreshFromLocal();
        }),
      );

      engine.start();

      const initialSyncComplete = (await store.getMeta<boolean>('initialSyncComplete')) === true;

      // C9.3 rollover scheduling: online → defer until the first cycle settles (reconciled
      // rows); offline → run now (queued writes are the newest intent).
      if (engine.online()) {
        subscriptions.push(
          engine.onFirstCycleSettled(() => {
            void deps.runRolloverOnce();
          }),
        );
        await deps.initialLoad();
      } else {
        await deps.initialLoad();
        await deps.runRolloverOnce();
      }

      return { needsInitialSyncGate: !initialSyncComplete };
    },

    onLoggedOut(): void {
      for (const unsub of subscriptions.splice(0)) {
        try {
          unsub();
        } catch {
          /* ignore */
        }
      }
      engine?.stop();
      store?.close();
      deps.resetStore();
      engine = null;
      store = null;
    },

    getEngine(): WiringEngine | null {
      return engine;
    },
  };
}
