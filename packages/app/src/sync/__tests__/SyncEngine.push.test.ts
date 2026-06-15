import 'fake-indexeddb/auto';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { LocalStore } from '../../data/local/LocalStore';
import { SyncEngine } from '../SyncEngine';
import type { SyncTransport } from '../SyncTransport';
import type { SyncOp, SyncStatus } from '../types';
import type { SyncResponse, TaskSyncRow } from '../../data/local/rows';
import { PUSH_DEBOUNCE_MS, PERIODIC_SYNC_MS } from '../constants';

let n = 0;
const stores: LocalStore[] = [];
function freshStore(): LocalStore {
  const s = LocalStore.open(`eng-${Date.now()}-${n++}`);
  stores.push(s);
  return s;
}
afterEach(() => {
  for (const s of stores.splice(0)) s.close();
  vi.useRealTimers();
});

/** A transport spy that records sent ops and replies per a scripted function. */
function spyTransport(
  reply: (op: SyncOp) => { status: number; body?: unknown } | Promise<{ status: number; body?: unknown }> = () => ({ status: 200 }),
): SyncTransport & { sent: SyncOp[]; pulls: number; ensured: [string, string][] } {
  const sent: SyncOp[] = [];
  const ensured: [string, string][] = [];
  let pulls = 0;
  const emptyPage: SyncResponse = { changes: {}, nextSince: 0, hasMore: false } as unknown as SyncResponse;
  return {
    sent,
    ensured,
    get pulls() { return pulls; },
    async send(op: SyncOp) { sent.push(op); return reply(op); },
    async pull(): Promise<SyncResponse> { pulls++; return emptyPage; },
    async ensureInstances(start: string, end: string): Promise<TaskSyncRow[]> { ensured.push([start, end]); return []; },
  } as SyncTransport & { sent: SyncOp[]; pulls: number; ensured: [string, string][] };
}

function op(o: Partial<SyncOp> & Pick<SyncOp, 'method' | 'path'>): SyncOp {
  return { entityKeys: [], kind: 'other', attempts: 0, ...o };
}

describe('SyncEngine skeleton — lifecycle, triggers, single-flight, cycle shape', () => {
  it('syncNow drains a queued op (2xx → deleted) then runs the pull stub', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 200 }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(t.sent).toHaveLength(1);
    expect(await store.ops.count()).toBe(0);
    expect(t.pulls).toBe(1); // pull phase always runs (R4 fills it; stub here)
  });

  it('requestSync debounces: many calls collapse into ONE cycle after PUSH_DEBOUNCE_MS', async () => {
    vi.useFakeTimers();
    const store = freshStore();
    const t = spyTransport();
    const engine = new SyncEngine({ store, transport: t });
    await store.ops.add(op({ method: 'DELETE', path: '/api/v1/tasks/a', entityKeys: ['tasks:a'] }));

    engine.requestSync();
    engine.requestSync();
    engine.requestSync();
    expect(t.pulls).toBe(0); // nothing fired yet
    await vi.advanceTimersByTimeAsync(PUSH_DEBOUNCE_MS);
    await vi.waitFor(() => expect(t.pulls).toBe(1));
    expect(t.sent).toHaveLength(1);
  });

  it('start runs a first cycle and arms the periodic timer (PERIODIC_SYNC_MS)', async () => {
    vi.useFakeTimers();
    const store = freshStore();
    const t = spyTransport();
    const engine = new SyncEngine({ store, transport: t });
    engine.start();
    await vi.waitFor(() => expect(t.pulls).toBe(1)); // first cycle on start
    await vi.advanceTimersByTimeAsync(PERIODIC_SYNC_MS);
    await vi.waitFor(() => expect(t.pulls).toBe(2)); // periodic tick
    engine.stop();
    await vi.advanceTimersByTimeAsync(PERIODIC_SYNC_MS * 2);
    expect(t.pulls).toBe(2); // stopped → no more ticks
  });

  it('single-flight: a second syncNow while one is in flight does not start a concurrent cycle', async () => {
    const store = freshStore();
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let sends = 0;
    const t = spyTransport(async () => {
      sends++;
      await gate; // hold the first cycle open
      return { status: 200 };
    });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    const first = engine.syncNow();
    const second = engine.syncNow(); // must no-op (a cycle is running)
    release();
    await Promise.all([first, second]);
    expect(sends).toBe(1); // the op was sent exactly once
  });

  it('subscribe fires getStatus() once immediately, then a fresh snapshot; emits on change', async () => {
    const store = freshStore();
    const t = spyTransport();
    await store.ops.add(op({ method: 'DELETE', path: '/api/v1/tasks/a', entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    const seen: SyncStatus[] = [];
    engine.subscribe((s) => seen.push(s));
    expect(seen.length).toBeGreaterThanOrEqual(1); // synchronous immediate fire (getStatus())
    expect(typeof seen[0].online).toBe('boolean');

    // The async cache refresh queued by subscribe delivers a snapshot reflecting the op.
    await vi.waitFor(() => expect(seen[seen.length - 1].pendingOps).toBe(1));
    expect(engine.getStatus().pendingOps).toBe(1); // synchronous getStatus() now reflects the cache

    await engine.syncNow();
    const last = seen[seen.length - 1];
    expect(last.pendingOps).toBe(0); // op drained
    expect(last.syncing).toBe(false);
    expect(engine.getStatus().pendingOps).toBe(0);
  });

  it('online() reflects the injected connectivity', () => {
    const store = freshStore();
    const t = spyTransport();
    const engine = new SyncEngine({
      store,
      transport: t,
      connectivity: { online: () => false, subscribe: () => () => {} },
    });
    expect(engine.online()).toBe(false);
  });

  it('an online connectivity event triggers a cycle', async () => {
    const store = freshStore();
    const t = spyTransport();
    let fire: (online: boolean) => void = () => {};
    const engine = new SyncEngine({
      store,
      transport: t,
      connectivity: { online: () => true, subscribe: (cb) => { fire = cb; return () => {}; } },
    });
    engine.start();
    await vi.waitFor(() => expect(t.pulls).toBe(1));
    fire(true); // online event
    await vi.waitFor(() => expect(t.pulls).toBe(2));
    engine.stop();
  });
});
