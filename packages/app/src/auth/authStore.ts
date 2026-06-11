// packages/app/src/auth/authStore.ts
// Zustand auth store: status machine + in-memory access token + single-flight refresh.
// Token storage is delegated to platform.auth (desktop keychain-in-main / web httpOnly cookie).
import { createStore, type StoreApi } from 'zustand/vanilla';
import { useStore as useZustand } from 'zustand';
import type { Platform } from '../platform/Platform';
import {
  type AuthTokenResponse,
  type AuthUser,
  isProblemDetails,
  problemToMessage,
  unwrapAuth,
} from './types';

type Verb = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
type FetchLike = (path: string, init?: any) => Promise<any>;

export type AuthStatus = 'loading' | 'anonymous' | 'authed';

export interface AuthState {
  status: AuthStatus;
  user: AuthUser | null;
  accessToken: string | null;
  error: string | null;
  /** True when the last bootstrap failed due to a network (not auth) error. */
  networkError: boolean;
  /** Synchronous token getter handed to createTmapClient's middleware. */
  getAccessToken: () => string | null;
  register: (email: string, password: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  bootstrap: () => Promise<void>;
  /** Internal single-flight refresh; returns the new token or null on 401. */
  refresh: () => Promise<AuthTokenResponse | null>;
}

export interface AuthStoreDeps {
  client: Record<Verb, FetchLike>;
  platform: Pick<Platform, 'auth'>;
  /** Called after a successful authed transition (register/login/bootstrap). */
  onAuthed: (auth: AuthTokenResponse) => void;
  /** Called after logout completes (status -> anonymous). */
  onLoggedOut: () => void;
}

function isNetworkError(e: unknown): boolean {
  // fetch network failures surface as TypeError ("Failed to fetch") or AbortError.
  if (e instanceof TypeError) return true;
  const name = (e as { name?: string } | null)?.name;
  return name === 'TypeError' || name === 'AbortError' || name === 'NetworkError';
}

/**
 * Reads the optional raw `refreshToken` from a login/register response body.
 * Desktop (native) login/register returns the refresh token in the body so the
 * renderer can hand it to the main process (safeStorage) via platform.auth.setRefreshToken.
 * Web sets the refresh token as an httpOnly cookie instead, so the body omits it
 * and this returns null. `AuthTokenResponse` (the canonical type) does not carry it.
 */
function readRefreshToken(body: unknown): string | null {
  const rt = (body as { refreshToken?: unknown } | null | undefined)?.refreshToken;
  return typeof rt === 'string' && rt.length > 0 ? rt : null;
}

export function createAuthStore(deps: AuthStoreDeps): StoreApi<AuthState> {
  const { client, platform, onAuthed, onLoggedOut } = deps;
  let refreshPromise: Promise<AuthTokenResponse | null> | null = null;

  const store = createStore<AuthState>((set, get) => ({
    status: 'loading',
    user: null,
    accessToken: null,
    error: null,
    networkError: false,

    getAccessToken: () => get().accessToken,

    async register(email, password) {
      set({ error: null });
      const res = await client.POST('/api/v1/auth/register', { body: { email, password } });
      if (res?.error || res?.response?.status >= 400) {
        const msg = isProblemDetails(res?.error)
          ? problemToMessage(res.error, 'Registration failed')
          : 'Registration failed';
        set({ error: msg, status: get().status === 'authed' ? 'authed' : 'anonymous' });
        throw new Error(msg);
      }
      const auth = unwrapAuth(res.data);
      // Desktop: persist the body's refresh token in main (safeStorage). Web: body
      // omits it (httpOnly cookie) and/or platform has no setRefreshToken — no-op.
      const rt = readRefreshToken(res.data);
      if (rt && platform.auth.setRefreshToken) {
        try {
          await platform.auth.setRefreshToken(rt);
        } catch {
          /* ignore — sign-in proceeds; next launch falls back to anonymous */
        }
      }
      set({
        status: 'authed',
        user: auth.user,
        accessToken: auth.accessToken,
        error: null,
        networkError: false,
      });
      onAuthed(auth);
    },

    async login(email, password) {
      set({ error: null });
      const res = await client.POST('/api/v1/auth/login', { body: { email, password } });
      if (res?.error || res?.response?.status >= 400) {
        const msg = isProblemDetails(res?.error)
          ? problemToMessage(res.error, 'Invalid email or password')
          : 'Invalid email or password';
        set({ error: msg, status: get().status === 'authed' ? 'authed' : 'anonymous' });
        throw new Error(msg);
      }
      const auth = unwrapAuth(res.data);
      // Desktop: persist the body's refresh token in main (safeStorage). Web: body
      // omits it (httpOnly cookie) and/or platform has no setRefreshToken — no-op.
      const rt = readRefreshToken(res.data);
      if (rt && platform.auth.setRefreshToken) {
        try {
          await platform.auth.setRefreshToken(rt);
        } catch {
          /* ignore — sign-in proceeds; next launch falls back to anonymous */
        }
      }
      set({
        status: 'authed',
        user: auth.user,
        accessToken: auth.accessToken,
        error: null,
        networkError: false,
      });
      onAuthed(auth);
    },

    async logout() {
      // Server-side revoke via the host adapter: desktop sends the stored refresh
      // token from main; web revokes via the httpOnly cookie. Best-effort — never
      // block local sign-out. (Previously POSTed {refreshToken:null}, which revoked
      // nothing on desktop since the token isn't visible to the renderer.)
      try {
        await platform.auth.logout?.();
      } catch {
        /* ignore — local sign-out proceeds regardless */
      }
      try {
        await platform.auth.clear();
      } catch {
        /* ignore */
      }
      set({ status: 'anonymous', user: null, accessToken: null, error: null });
      onLoggedOut();
    },

    refresh() {
      if (!refreshPromise) {
        refreshPromise = (async () => {
          try {
            const auth = await platform.auth.refreshAndGetAccess();
            if (auth) {
              set({ accessToken: auth.accessToken, user: auth.user });
            }
            return auth;
          } finally {
            refreshPromise = null;
          }
        })();
      }
      return refreshPromise;
    },

    async bootstrap() {
      set({ status: 'loading', error: null, networkError: false });
      try {
        const auth = await get().refresh();
        if (auth) {
          set({
            status: 'authed',
            user: auth.user,
            accessToken: auth.accessToken,
            networkError: false,
          });
          onAuthed(auth);
        } else {
          // refresh returned null === a real 401: stored token is dead, clear it.
          try {
            await platform.auth.clear();
          } catch {
            /* ignore */
          }
          set({ status: 'anonymous', user: null, accessToken: null, networkError: false });
        }
      } catch (e) {
        if (isNetworkError(e)) {
          // Do NOT destroy a (possibly valid) stored token on a transient network failure.
          set({ status: 'anonymous', user: null, accessToken: null, networkError: true });
        } else {
          try {
            await platform.auth.clear();
          } catch {
            /* ignore */
          }
          set({ status: 'anonymous', user: null, accessToken: null, networkError: false });
        }
      }
    },
  }));

  return store;
}

// --- App singleton (built once from real deps at app entry via initAuthStore) ---
let appStore: StoreApi<AuthState> | null = null;

export function initAuthStore(deps: AuthStoreDeps): StoreApi<AuthState> {
  appStore = createAuthStore(deps);
  return appStore;
}

export function getAuthStore(): StoreApi<AuthState> {
  if (!appStore) throw new Error('authStore not initialized — call initAuthStore() at app entry');
  return appStore;
}

/** React hook over the app singleton; pass a selector. */
export function useAuthStore<T>(selector: (s: AuthState) => T): T {
  return useZustand(getAuthStore(), selector);
}
