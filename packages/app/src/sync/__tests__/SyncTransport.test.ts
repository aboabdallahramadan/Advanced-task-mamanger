import { describe, it, expect, vi } from 'vitest';
import { HttpSyncTransport } from '../SyncTransport';
import type { MinimalClient } from '../../auth/refreshClient';
import type { SyncOp } from '../types';

type Call = { method: string; path: string; init: unknown };

function fakeClient(
  reply: (method: string, path: string, init: unknown) => { data?: unknown; error?: unknown; response: { status: number } } | Promise<never>,
) {
  const calls: Call[] = [];
  const make = (method: string) => (path: string, init: unknown) => {
    calls.push({ method, path, init });
    return Promise.resolve(reply(method, path, init)) as never;
  };
  const client: MinimalClient = {
    GET: make('GET'), POST: make('POST'), PATCH: make('PATCH'),
    PUT: make('PUT'), DELETE: make('DELETE'),
  };
  return { client, calls };
}

function op(o: Partial<SyncOp> & Pick<SyncOp, 'method' | 'path'>): SyncOp {
  return { entityKeys: [], kind: 'other', attempts: 0, ...o };
}

describe('HttpSyncTransport.send', () => {
  it('maps method+path+body to the wrapped client and returns {status, body}', async () => {
    const { client, calls } = fakeClient(() => ({ data: { ok: true }, response: { status: 201 } }));
    const t = new HttpSyncTransport(client);
    const r = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: { id: 't1' }, kind: 'create' }));
    expect(r).toEqual({ status: 201, body: { ok: true } });
    expect(calls).toEqual([{ method: 'POST', path: '/api/v1/tasks', init: { body: { id: 't1' } } }]);
  });

  it('returns the error body when the client reports a non-2xx result', async () => {
    const { client } = fakeClient(() => ({
      error: { title: 'Conflict', extensions: { existingId: 'p1' } },
      response: { status: 409 },
    }));
    const t = new HttpSyncTransport(client);
    const r = await t.send(op({ method: 'POST', path: '/api/v1/projects', body: { id: 'p2', name: 'X' }, kind: 'create' }));
    expect(r.status).toBe(409);
    expect((r.body as { extensions: { existingId: string } }).extensions.existingId).toBe('p1');
  });

  it('propagates a thrown network/terminal error from the wrapped client', async () => {
    const { client } = fakeClient(() => Promise.reject(Object.assign(new TypeError('Failed to fetch'), { name: 'TypeError' })));
    const t = new HttpSyncTransport(client);
    await expect(t.send(op({ method: 'DELETE', path: '/api/v1/tasks/t1' }))).rejects.toThrow('Failed to fetch');
  });

  it('DELETE without a body sends no body key', async () => {
    const { client, calls } = fakeClient(() => ({ data: undefined, response: { status: 204 } }));
    const t = new HttpSyncTransport(client);
    await t.send(op({ method: 'DELETE', path: '/api/v1/tasks/t1' }));
    expect(calls[0].init).toEqual({});
  });
});

describe('HttpSyncTransport.pull / ensureInstances — query in the concrete path', () => {
  it('pull issues GET /api/v1/sync?since=&limit= and returns the data envelope', async () => {
    const env = { changes: {}, nextSince: 42, hasMore: false };
    const { client, calls } = fakeClient((method, path) => {
      expect(method).toBe('GET');
      return { data: env, response: { status: 200 } };
    });
    const t = new HttpSyncTransport(client);
    const res = await t.pull(7, 500);
    expect(calls[0].path).toBe('/api/v1/sync?since=7&limit=500');
    expect(res).toBe(env);
  });

  it('ensureInstances issues POST /api/v1/recurrence/ensure-instances?start=&end=', async () => {
    const rows = [{ id: 'i1' }];
    const { client, calls } = fakeClient(() => ({ data: rows, response: { status: 200 } }));
    const t = new HttpSyncTransport(client);
    const res = await t.ensureInstances('2026-06-01', '2026-06-14');
    expect(calls[0]).toMatchObject({
      method: 'POST',
      path: '/api/v1/recurrence/ensure-instances?start=2026-06-01&end=2026-06-14',
    });
    expect(res).toBe(rows);
  });

  it('pull throws when the client returns an error result', async () => {
    const { client } = fakeClient(() => ({ error: { title: 'boom' }, response: { status: 500 } }));
    const t = new HttpSyncTransport(client);
    await expect(t.pull(0, 500)).rejects.toBeTruthy();
  });
});
