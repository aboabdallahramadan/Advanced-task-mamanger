// packages/app/src/auth/__tests__/authStore.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createAuthStore } from '../authStore';
import type { AuthTokenResponse } from '../types';

function ok(data: unknown) {
  return { data, error: undefined, response: { status: 200 } };
}
function fail(status: number, problem: unknown) {
  return { data: undefined, error: problem, response: { status } };
}
function auth(token: string): AuthTokenResponse {
  return { accessToken: token, expiresIn: 900, user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' } };
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
