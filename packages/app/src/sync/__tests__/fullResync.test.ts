import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { LocalStore } from '../../data/local/LocalStore';
import { LocalDataClient } from '../../data/local/LocalDataClient';
import type { SyncBridge } from '../../data/local/LocalDataClient';
import { SyncEngine } from '../SyncEngine';
import { FakeSyncServer } from './fakeSyncServer';

let n = 0;
const open: LocalStore[] = [];
function freshStore(): LocalStore {
  const s = LocalStore.open(`fr-u${Date.now()}-${n++}`);
  open.push(s);
  return s;
}
afterEach(() => {
  for (const s of open.splice(0)) s.close();
  vi.restoreAllMocks();
});

const inertBridge: SyncBridge = {
  nudge: () => {},
  online: () => true,
  ensureInstances: async () => [],
};

function reset(store: LocalStore): () => Promise<void> {
  return () => new LocalDataClient(store, inertBridge).resetForFullResync();
}

describe('SyncEngine full-resync enforcement (C9)', () => {
  it('drained queue: directive → reset local store + re-pull from since=0', async () => {
    const store = freshStore();
    const server = new FakeSyncServer();
    // Server has one project the re-pull from 0 will repopulate.
    server.seed('projects', { id: 'p1', name: 'P', color: '#111', emoji: '📁', rank: 'a0' });

    // Pre-existing stale local state that the reset must wipe.
    await store.tasks.add({ id: 'stale', rank: 'a0', changeSeq: 1, deletedAt: null } as never);
    await store.setMeta('syncCursor', 9999);
    await store.setMeta('initialSyncComplete', true);
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });

    server.requireFullResyncOnce(); // the first pull this cycle returns the directive

    const engine = new SyncEngine({
      store,
      transport: server.transport(),
      resetForFullResync: reset(store),
    });

    await engine.syncNow();

    // Stale row gone; server's project repopulated from the since=0 re-pull.
    expect(await store.tasks.get('stale')).toBeUndefined();
    expect(await store.projects.get('p1')).toBeDefined();
    // lastUser survived the reset.
    expect(await store.getMeta<{ id: string }>('lastUser')).toEqual({
      id: 'u1', email: 'a@b.c', timeZoneId: 'UTC',
    });
    // initialSyncComplete re-set by the completed re-pull from 0.
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true);
    // The directive pull + the re-pull from 0 → at least 2 pull() calls.
    expect(server.pullCount()).toBeGreaterThanOrEqual(2);
  });

  it('ops remain: directive is DEFERRED — no reset, queue + stale state intact', async () => {
    const store = freshStore();
    const server = new FakeSyncServer();
    server.seed('projects', { id: 'p1', name: 'P', color: '#111', emoji: '📁', rank: 'a0' });

    // A queued op that will FAIL to push (network) so the push phase aborts with ops remaining.
    await store.ops.add({
      method: 'PATCH', path: '/api/v1/tasks/keep', body: { title: 'x' },
      entityKeys: ['tasks:keep'], kind: 'other', attempts: 0,
    } as never);
    server.failNext((op) => op.path === '/api/v1/tasks/keep', 'network');

    // Stale local state that must survive because the reset is deferred.
    await store.tasks.add({ id: 'stale', rank: 'a0', changeSeq: 1, deletedAt: null } as never);
    await store.setMeta('syncCursor', 9999);

    server.requireFullResyncOnce();

    const reset = vi.fn(() => Promise.resolve());
    const engine = new SyncEngine({
      store,
      transport: server.transport(),
      resetForFullResync: reset,
    });

    await engine.syncNow();

    // Reset NOT called (queue not drained).
    expect(reset).not.toHaveBeenCalled();
    // The queued op is still pending; the stale row + cursor survive.
    expect(await store.ops.count()).toBe(1);
    expect(await store.tasks.get('stale')).toBeDefined();
    expect(await store.getMeta<number>('syncCursor')).toBe(9999);
  });
});
