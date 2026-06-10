// packages/app/src/auth/__tests__/refreshClient.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createRefreshClient } from '../refreshClient';
import type { AuthTokenResponse } from '../types';

// A fake openapi-fetch-shaped result.
type FetchResult = { data?: unknown; error?: unknown; response: { status: number } };

function ok(data: unknown): FetchResult {
  return { data, error: undefined, response: { status: 200 } };
}
function unauthorized(): FetchResult {
  return { data: undefined, error: { title: 'Unauthorized' }, response: { status: 401 } };
}

function makeAuth(token: string): AuthTokenResponse {
  return { accessToken: token, expiresIn: 900, user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' } };
}

describe('createRefreshClient', () => {
  let getCalls: { path: string; init: any }[];
  let fakeClient: any;
  let refresh: ReturnType<typeof vi.fn>;
  let onLogout: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    getCalls = [];
  });

  // Builds a fake client whose GET fails with 401 the first `failFirst` times, then 200.
  function buildClient(failFirst: number) {
    let calls = 0;
    return {
      GET: vi.fn(async (path: string, init: any) => {
        getCalls.push({ path, init });
        calls += 1;
        if (calls <= failFirst) return unauthorized();
        return ok({ path, attempt: calls });
      }),
      POST: vi.fn(async (path: string, init: any) => {
        getCalls.push({ path, init });
        calls += 1;
        if (calls <= failFirst) return unauthorized();
        return ok({ id: init?.body?.id, attempt: calls });
      }),
      PATCH: vi.fn(async () => ok({})),
      DELETE: vi.fn(async () => ok({})),
    };
  }

  it('retries once after a single-flight refresh on 401 (GET succeeds on retry)', async () => {
    fakeClient = buildClient(1);
    refresh = vi.fn(async () => makeAuth('new-token'));
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    const res = await rc.GET('/api/v1/tasks', {});
    expect(refresh).toHaveBeenCalledTimes(1);
    expect((res as FetchResult).response.status).toBe(200);
    expect(fakeClient.GET).toHaveBeenCalledTimes(2); // original + one retry
  });

  it('coalesces concurrent 401s into a single refresh call', async () => {
    fakeClient = buildClient(1);
    // Each underlying call fails once then succeeds; refresh must be called only ONCE total.
    let calls = 0;
    fakeClient.GET = vi.fn(async (path: string, init: any) => {
      getCalls.push({ path, init });
      calls += 1;
      // first two calls (the two originals) are 401; subsequent (retries) are 200
      return calls <= 2 ? unauthorized() : ok({ path });
    });
    refresh = vi.fn(async () => {
      await new Promise((r) => setTimeout(r, 10));
      return makeAuth('new-token');
    });
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    const [a, b] = await Promise.all([rc.GET('/api/v1/tasks', {}), rc.GET('/api/v1/projects', {})]);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect((a as FetchResult).response.status).toBe(200);
    expect((b as FetchResult).response.status).toBe(200);
  });

  it('rejects the whole queue and logs out when refresh fails (returns null)', async () => {
    fakeClient = buildClient(99); // always 401
    refresh = vi.fn(async () => null); // refresh fails
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    await expect(rc.GET('/api/v1/tasks', {})).rejects.toThrow();
    expect(onLogout).toHaveBeenCalledTimes(1);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('never recurses: a refresh that throws logs out exactly once', async () => {
    fakeClient = buildClient(99);
    refresh = vi.fn(async () => {
      throw new Error('refresh endpoint 401');
    });
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    await expect(rc.GET('/api/v1/tasks', {})).rejects.toThrow();
    expect(refresh).toHaveBeenCalledTimes(1); // no recursion
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('retried create reuses the same client id (no double-create)', async () => {
    fakeClient = buildClient(1);
    refresh = vi.fn(async () => makeAuth('new-token'));
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    await rc.POST('/api/v1/tasks', { body: { id: 'uuid-v7-abc', title: 'x' } });
    const ids = getCalls.map((c) => c.init?.body?.id);
    expect(ids).toEqual(['uuid-v7-abc', 'uuid-v7-abc']); // original + retry, same id
  });

  it('after signOut(), makes no new refresh and rejects calls', async () => {
    fakeClient = buildClient(99);
    refresh = vi.fn(async () => makeAuth('new-token'));
    onLogout = vi.fn();
    const rc = createRefreshClient({ client: fakeClient, refresh, onLogout });

    rc.signOut();
    await expect(rc.GET('/api/v1/tasks', {})).rejects.toThrow();
    expect(refresh).not.toHaveBeenCalled();
  });
});
