// packages/app/src/auth/refreshClient.ts
// Wraps an openapi-fetch-shaped client with 401 → refresh → retry logic.
// Contract:
//   • On 401: await one shared in-flight refresh(), then retry the original call ONCE.
//   • Concurrent 401s share ONE refresh() call (single-flight).
//   • If refresh() fails or returns null: reject all queued callers + call onLogout() once.
//   • A 401 that comes FROM the refresh path itself never recurses — logs out immediately.
//   • Retried POSTs/PATCHes reuse the exact same init object (no body mutation).
//   • Every request carries the AbortController's signal (set via setAbortController).
//   • After signOut(): abort in-flight requests, reject all future calls without refreshing.

import type { AuthTokenResponse } from './types';

// Minimal shape of an openapi-fetch client we need.
export interface MinimalClient {
  GET: (
    path: string,
    init: unknown,
  ) => Promise<{ data?: unknown; error?: unknown; response: { status: number } }>;
  POST: (
    path: string,
    init: unknown,
  ) => Promise<{ data?: unknown; error?: unknown; response: { status: number } }>;
  PATCH: (
    path: string,
    init: unknown,
  ) => Promise<{ data?: unknown; error?: unknown; response: { status: number } }>;
  PUT: (
    path: string,
    init: unknown,
  ) => Promise<{ data?: unknown; error?: unknown; response: { status: number } }>;
  DELETE: (
    path: string,
    init: unknown,
  ) => Promise<{ data?: unknown; error?: unknown; response: { status: number } }>;
}

export interface RefreshClientOptions {
  client: MinimalClient;
  /** Called when a token refresh is needed. Returns null / throws if the session is dead. */
  refresh: () => Promise<AuthTokenResponse | null>;
  /** Called once when the session is definitively ended. */
  onLogout: () => void;
}

export interface RefreshClient extends MinimalClient {
  /** Mark the session as ended — abort in-flight requests, reject future calls, no more refresh. */
  signOut: () => void;
  /**
   * Re-arm a signed-out client for a fresh session (e.g. logout → re-login within one app
   * session): clears the terminal `signedOut` flag AND installs a fresh AbortController,
   * replacing the aborted one so revived requests carry a live (non-aborted) signal.
   */
  reactivate: () => void;
  /** Sets the AbortController whose signal is attached to every wrapped request. */
  setAbortController: (ac: AbortController) => void;
}

type Method = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

export function createRefreshClient({
  client,
  refresh,
  onLogout,
}: RefreshClientOptions): RefreshClient {
  // When truthy, a refresh is already in-flight; all 401-waiting calls queue on this.
  let refreshPromise: Promise<AuthTokenResponse | null> | null = null;
  // Set to true after signOut() or a terminal refresh failure.
  let signedOut = false;
  // The controller whose signal is attached to every request; aborted on signOut().
  let abortController: AbortController | undefined;

  function doLogout(): void {
    signedOut = true;
    onLogout();
  }

  /**
   * Attaches the AbortController's signal to a request init, without clobbering a
   * caller-supplied signal. Pass-through when no controller has been set.
   */
  function withSignal(init: unknown): unknown {
    if (!abortController?.signal) return init;
    const base = (init ?? {}) as { signal?: AbortSignal };
    return { ...base, signal: base.signal ?? abortController.signal };
  }

  /**
   * Execute a single refresh, shared across concurrent callers.
   * Clears the in-flight slot when done.
   */
  function ensureRefresh(): Promise<AuthTokenResponse | null> {
    if (refreshPromise) return refreshPromise;

    refreshPromise = refresh()
      .catch(() => null)
      .finally(() => {
        refreshPromise = null;
      });

    return refreshPromise;
  }

  /**
   * Calls the underlying client method. On 401:
   *   - If already in the "retry" pass, gives up immediately (no-recurse).
   *   - Otherwise, awaits a single shared refresh, then retries once.
   */
  async function call(
    method: Method,
    path: string,
    init: unknown,
    isRetry: boolean,
  ): Promise<{ data?: unknown; error?: unknown; response: { status: number } }> {
    if (signedOut) {
      throw new Error('Session ended — not making request');
    }

    // NOTE: the original `init` (incl. any body with a client-generated id) is preserved
    // across the original attempt and the retry; only the abort signal is layered on here.
    const result = await client[method](path, withSignal(init));

    if (result.response.status !== 401) {
      return result;
    }

    // 401 path —————————————————————————————————————————
    if (isRetry) {
      // We already retried once; do not recurse into refresh.
      doLogout();
      throw new Error('Unauthorized after token refresh');
    }

    // Await the single shared refresh.
    const newAuth = await ensureRefresh();

    if (!newAuth) {
      // refresh returned null or threw — session is dead.
      doLogout();
      throw new Error('Session expired — refresh failed');
    }

    // Retry the original call exactly once.
    return call(method, path, init, true /* isRetry */);
  }

  return {
    GET: (path, init) => call('GET', path, init, false),
    POST: (path, init) => call('POST', path, init, false),
    PATCH: (path, init) => call('PATCH', path, init, false),
    PUT: (path, init) => call('PUT', path, init, false),
    DELETE: (path, init) => call('DELETE', path, init, false),
    signOut() {
      signedOut = true;
      abortController?.abort();
    },
    reactivate() {
      // Revive a signed-out client: lift the terminal flag and swap in a fresh,
      // non-aborted controller so subsequent requests don't carry the dead signal.
      signedOut = false;
      abortController = new AbortController();
    },
    setAbortController(ac: AbortController) {
      abortController = ac;
    },
  };
}
