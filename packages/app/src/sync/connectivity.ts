/**
 * connectivity.ts — `navigator.onLine` + window `online`/`offline` events,
 * the source for the engine's online-event trigger (spec §3.4(b)) and the
 * `online` field of the status snapshot.
 *
 * Safe in non-DOM environments (vitest node, SSR): with no `navigator`,
 * `online()` returns true (offline-first reads never depend on this — it only
 * gates whether the engine *attempts* a cycle); with no `window`, `subscribe`
 * is a no-op. Production callers use the zero-arg form; tests inject fakes.
 */

export interface Connectivity {
  /** Current connectivity per `navigator.onLine` (true when navigator is absent). */
  online(): boolean;
  /** Subscribe to online/offline transitions; the callback receives the new state. Returns an unsubscribe. */
  subscribe(cb: (online: boolean) => void): () => void;
}

export interface ConnectivityDeps {
  nav?: Navigator;
  win?: Window;
}

function resolveNav(explicit?: Navigator | undefined): Navigator | undefined {
  if (explicit !== undefined) return explicit;
  return typeof navigator !== 'undefined' ? navigator : undefined;
}

function resolveWin(explicit?: Window | undefined): Window | undefined {
  if (explicit !== undefined) return explicit;
  return typeof window !== 'undefined' ? window : undefined;
}

export function createConnectivity(deps: ConnectivityDeps = {}): Connectivity {
  // Distinguish "not passed" (resolve from globals) from "passed undefined" (absent env).
  const nav = 'nav' in deps ? deps.nav : resolveNav();
  const win = 'win' in deps ? deps.win : resolveWin();

  return {
    online(): boolean {
      // Node 21+ exposes a global `navigator` whose `onLine` is undefined (it is a
      // browser-only property). Treat a non-boolean reading as online — connectivity
      // only gates whether the engine *attempts* a cycle, never offline-first reads.
      return nav && typeof nav.onLine === 'boolean' ? nav.onLine : true;
    },
    subscribe(cb: (online: boolean) => void): () => void {
      if (!win) return () => {};
      const onOnline = () => cb(true);
      const onOffline = () => cb(false);
      win.addEventListener('online', onOnline);
      win.addEventListener('offline', onOffline);
      return () => {
        win.removeEventListener('online', onOnline);
        win.removeEventListener('offline', onOffline);
      };
    },
  };
}
