// packages/app/src/auth/__tests__/authStore.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  describe as describe2,
  it as it2,
  expect as expect2,
  vi as vi2,
  beforeEach as beforeEach2,
} from 'vitest';
import { createAuthStore } from '../authStore';
import type { AuthTokenResponse } from '../types';

function ok(data: unknown) {
  return { data, error: undefined, response: { status: 200 } };
}
function fail(status: number, problem: unknown) {
  return { data: undefined, error: problem, response: { status } };
}
function auth(token: string): AuthTokenResponse {
  return {
    accessToken: token,
    expiresIn: 900,
    user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' },
  };
}

function makePlatform(refreshImpl: () => Promise<AuthTokenResponse | null>) {
  return {
    auth: {
      refreshAndGetAccess: vi.fn(refreshImpl),
      clear: vi.fn(async () => {}),
    },
  };
}

describe('authStore — register/login transitions', () => {
  let client: any;
  let onAuthed: ReturnType<typeof vi.fn>;
  let onLoggedOut: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    onAuthed = vi.fn();
    onLoggedOut = vi.fn();
    client = {
      POST: vi.fn(),
      GET: vi.fn(),
      PATCH: vi.fn(),
      PUT: vi.fn(),
      DELETE: vi.fn(),
    };
  });

  it('starts in loading status with no user/token', () => {
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
    const s = store.getState();
    expect(s.status).toBe('loading');
    expect(s.user).toBeNull();
    expect(s.accessToken).toBeNull();
  });

  it('register success → authed, sets user + in-memory access token, fires onAuthed', async () => {
    client.POST.mockImplementation(async (path: string) => {
      if (path === '/api/v1/auth/register') return ok(auth('tok-1'));
      return ok({});
    });
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });

    await store.getState().register('a@b.c', 'password123');
    const s = store.getState();
    expect(s.status).toBe('authed');
    expect(s.user).toEqual({ id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
    expect(s.accessToken).toBe('tok-1');
    expect(onAuthed).toHaveBeenCalledTimes(1);
    expect(client.POST).toHaveBeenCalledWith(
      '/api/v1/auth/register',
      expect.objectContaining({ body: { email: 'a@b.c', password: 'password123' } }),
    );
  });

  it('login success → authed and exposes the access token to the client middleware', async () => {
    client.POST.mockImplementation(async (path: string) =>
      path === '/api/v1/auth/login' ? ok(auth('tok-login')) : ok({}),
    );
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });

    await store.getState().login('a@b.c', 'password123');
    expect(store.getState().status).toBe('authed');
    expect(store.getState().getAccessToken()).toBe('tok-login');
  });

  it('login 401 → stays anonymous and sets an error message from ProblemDetails', async () => {
    client.POST.mockImplementation(async () =>
      fail(401, { title: 'Invalid credentials', status: 401 }),
    );
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });

    await expect(store.getState().login('a@b.c', 'wrong')).rejects.toThrow();
    const s = store.getState();
    expect(s.status).toBe('anonymous');
    expect(s.error).toMatch(/Invalid credentials/);
    expect(onAuthed).not.toHaveBeenCalled();
  });

  it('register 400 with field errors → anonymous and joined field-error message', async () => {
    client.POST.mockImplementation(async () =>
      fail(400, { title: 'Bad', errors: { Password: ['Password too short'] } }),
    );
    const platform = makePlatform(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });

    await expect(store.getState().register('a@b.c', 'x')).rejects.toThrow();
    expect(store.getState().status).toBe('anonymous');
    expect(store.getState().error).toMatch(/Password too short/);
  });
});

// --- append to packages/app/src/auth/__tests__/authStore.test.ts ---

describe2('authStore — bootstrap network-vs-401 + logout', () => {
  let client: any;
  let onAuthed: ReturnType<typeof vi2.fn>;
  let onLoggedOut: ReturnType<typeof vi2.fn>;

  function ok2(data: unknown) {
    return { data, error: undefined, response: { status: 200 } };
  }
  function auth2(token: string) {
    return {
      accessToken: token,
      expiresIn: 900,
      user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' },
    };
  }
  function makePlatform2(refreshImpl: () => Promise<any>) {
    return {
      auth: {
        refreshAndGetAccess: vi2.fn(refreshImpl),
        clear: vi2.fn(async () => {}),
        logout: vi2.fn(async () => {}),
      },
    };
  }

  beforeEach2(() => {
    onAuthed = vi2.fn();
    onLoggedOut = vi2.fn();
    client = {
      POST: vi2.fn(async () => ok2({})),
      GET: vi2.fn(),
      PATCH: vi2.fn(),
      PUT: vi2.fn(),
      DELETE: vi2.fn(),
    };
  });

  it2('bootstrap with valid refresh → authed', async () => {
    const platform = makePlatform2(async () => auth2('boot-tok'));
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
    await store.getState().bootstrap();
    const s = store.getState();
    expect2(s.status).toBe('authed');
    expect2(s.accessToken).toBe('boot-tok');
    expect2(s.user?.id).toBe('u1');
    expect2(onAuthed).toHaveBeenCalledTimes(1);
  });

  it2('bootstrap refresh returns null (401) → anonymous AND clears stored token', async () => {
    const platform = makePlatform2(async () => null);
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
    await store.getState().bootstrap();
    expect2(store.getState().status).toBe('anonymous');
    expect2(store.getState().networkError).toBe(false);
    expect2(platform.auth.clear).toHaveBeenCalledTimes(1);
  });

  it2(
    'bootstrap refresh throws network error → anonymous, token NOT cleared, networkError set',
    async () => {
      const platform = makePlatform2(async () => {
        throw new TypeError('Failed to fetch');
      });
      const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
      await store.getState().bootstrap();
      const s = store.getState();
      expect2(s.status).toBe('anonymous');
      expect2(s.networkError).toBe(true);
      expect2(platform.auth.clear).not.toHaveBeenCalled();
    },
  );

  it2('logout → anonymous, clears token, calls platform.clear + onLoggedOut', async () => {
    const platform = makePlatform2(async () => auth2('boot-tok'));
    const store = createAuthStore({ client, platform, onAuthed, onLoggedOut });
    await store.getState().bootstrap(); // authed
    await store.getState().logout();
    const s = store.getState();
    expect2(s.status).toBe('anonymous');
    expect2(s.accessToken).toBeNull();
    expect2(s.user).toBeNull();
    expect2(platform.auth.clear).toHaveBeenCalledTimes(1);
    expect2(onLoggedOut).toHaveBeenCalledTimes(1);
    // logout now revokes server-side via the host adapter (desktop: main sends the
    // stored token; web: cookie), NOT a renderer client.POST({refreshToken:null}).
    expect2(platform.auth.logout).toHaveBeenCalledTimes(1);
  });
});

// --- desktop refresh-token persistence on login/register (Q6-8) ---

describe2('authStore — desktop persists refresh token on login/register', () => {
  function ok3(data: unknown) {
    return { data, error: undefined, response: { status: 200 } };
  }
  // Native (desktop) auth body carries a refreshToken alongside the renderer-safe fields.
  function authWithRefresh(token: string, refreshToken: string) {
    return {
      accessToken: token,
      expiresIn: 900,
      refreshToken,
      user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' },
    };
  }
  // Web auth body: no refreshToken (server set an httpOnly cookie instead).
  function authNoRefresh(token: string) {
    return {
      accessToken: token,
      expiresIn: 900,
      user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' },
    };
  }

  it2('login with body.refreshToken → platform.auth.setRefreshToken called with it', async () => {
    const client = {
      POST: vi2.fn(async () => ok3(authWithRefresh('acc-1', 'rt-abc'))),
      GET: vi2.fn(),
      PATCH: vi2.fn(),
      PUT: vi2.fn(),
      DELETE: vi2.fn(),
    };
    const setRefreshToken = vi2.fn(async () => {});
    const platform = {
      auth: {
        refreshAndGetAccess: vi2.fn(async () => null),
        clear: vi2.fn(async () => {}),
        setRefreshToken,
      },
    };
    const store = createAuthStore({ client, platform, onAuthed: vi2.fn(), onLoggedOut: vi2.fn() });

    await store.getState().login('a@b.c', 'password123');
    expect2(store.getState().status).toBe('authed');
    expect2(setRefreshToken).toHaveBeenCalledTimes(1);
    expect2(setRefreshToken).toHaveBeenCalledWith('rt-abc');
  });

  it2('register with body.refreshToken → setRefreshToken called', async () => {
    const client = {
      POST: vi2.fn(async () => ok3(authWithRefresh('acc-2', 'rt-xyz'))),
      GET: vi2.fn(),
      PATCH: vi2.fn(),
      PUT: vi2.fn(),
      DELETE: vi2.fn(),
    };
    const setRefreshToken = vi2.fn(async () => {});
    const platform = {
      auth: {
        refreshAndGetAccess: vi2.fn(async () => null),
        clear: vi2.fn(async () => {}),
        setRefreshToken,
      },
    };
    const store = createAuthStore({ client, platform, onAuthed: vi2.fn(), onLoggedOut: vi2.fn() });

    await store.getState().register('a@b.c', 'password123');
    expect2(setRefreshToken).toHaveBeenCalledWith('rt-xyz');
  });

  it2('web body (no refreshToken) → setRefreshToken NOT called', async () => {
    const client = {
      // No refreshToken in the body (web sets an httpOnly cookie instead).
      POST: vi2.fn(async () => ok3(authNoRefresh('acc-3'))),
      GET: vi2.fn(),
      PATCH: vi2.fn(),
      PUT: vi2.fn(),
      DELETE: vi2.fn(),
    };
    const setRefreshToken = vi2.fn(async () => {});
    const platform = {
      auth: {
        refreshAndGetAccess: vi2.fn(async () => null),
        clear: vi2.fn(async () => {}),
        setRefreshToken,
      },
    };
    const store = createAuthStore({ client, platform, onAuthed: vi2.fn(), onLoggedOut: vi2.fn() });

    await store.getState().login('a@b.c', 'password123');
    expect2(store.getState().status).toBe('authed');
    expect2(setRefreshToken).not.toHaveBeenCalled();
  });
});
