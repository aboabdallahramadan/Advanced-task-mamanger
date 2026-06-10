import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { WebPlatform } from './WebPlatform';

describe('WebPlatform', () => {
  const origFetch = globalThis.fetch;
  afterEach(() => {
    globalThis.fetch = origFetch;
    vi.restoreAllMocks();
  });

  it('has web-shaped capabilities (no tray/widget/autoLaunch) and no focusWidget/autoLaunch', () => {
    const p = new WebPlatform('http://api.test');
    expect(p.capabilities).toEqual({
      tray: false,
      focusWidgetWindow: false,
      autoLaunch: false,
      dataPort: false,
    });
    expect(p.focusWidget).toBeUndefined();
    expect(p.autoLaunch).toBeUndefined();
  });

  it('on/off are no-ops (do not throw)', () => {
    const p = new WebPlatform('http://api.test');
    const cb = () => {};
    expect(() => p.on('navigate', cb)).not.toThrow();
    expect(() => p.off('navigate', cb)).not.toThrow();
  });

  it('refreshAndGetAccess POSTs /auth/refresh with credentials include + X-Tmap-Refresh, returns token', async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          accessToken: 'AT',
          expiresIn: 900,
          user: { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    );
    globalThis.fetch = fetchMock as unknown as typeof fetch;

    const p = new WebPlatform('http://api.test');
    const res = await p.auth.refreshAndGetAccess();

    expect(res?.accessToken).toBe('AT');
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://api.test/api/v1/auth/refresh');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('include');
    expect((init.headers as Record<string, string>)['X-Tmap-Refresh']).toBe('1');
  });

  it('returns null on a 401 from refresh (clears session upstream)', async () => {
    globalThis.fetch = vi.fn(async () => new Response('', { status: 401 })) as unknown as typeof fetch;
    const p = new WebPlatform('http://api.test');
    expect(await p.auth.refreshAndGetAccess()).toBeNull();
  });

  it('returns null (does not throw) on network failure', async () => {
    globalThis.fetch = vi.fn(async () => {
      throw new TypeError('Failed to fetch');
    }) as unknown as typeof fetch;
    const p = new WebPlatform('http://api.test');
    expect(await p.auth.refreshAndGetAccess()).toBeNull();
  });
});
