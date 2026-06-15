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

describe('push loop — FIFO drain + network abort (spec §3.3)', () => {
  it('replays ops in strict seq (FIFO) order', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 200 }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: {}, entityKeys: ['tasks:a'] }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/b', body: {}, entityKeys: ['tasks:b'] }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/c', body: {}, entityKeys: ['tasks:c'] }));
    const engine = new SyncEngine({ store, transport: t });
    await engine.syncNow();
    expect(t.sent.map((o) => o.path)).toEqual([
      '/api/v1/tasks/a', '/api/v1/tasks/b', '/api/v1/tasks/c',
    ]);
    expect(await store.ops.count()).toBe(0);
  });

  it('a network throw aborts the push phase: the failing op and its successors stay queued', async () => {
    const store = freshStore();
    const t = spyTransport((o) => {
      if (o.path === '/api/v1/tasks/b') {
        throw Object.assign(new TypeError('Failed to fetch'), { name: 'TypeError' });
      }
      return { status: 200 };
    });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: {}, entityKeys: ['tasks:a'] }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/b', body: {}, entityKeys: ['tasks:b'] }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/c', body: {}, entityKeys: ['tasks:c'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    // 'a' drained; 'b' (network) + 'c' remain, in order.
    const remaining = await store.ops.orderBy('seq').toArray();
    expect(remaining.map((o) => o.path)).toEqual(['/api/v1/tasks/b', '/api/v1/tasks/c']);
  });

  it('pull still runs even when push aborted on a network error (§3.3)', async () => {
    const store = freshStore();
    const t = spyTransport(() => {
      throw Object.assign(new TypeError('offline'), { name: 'TypeError' });
    });
    await store.ops.add(op({ method: 'DELETE', path: '/api/v1/tasks/a', entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });
    await engine.syncNow();
    expect(t.pulls).toBe(1);                 // pull phase ran despite push abort
    expect(await store.ops.count()).toBe(1); // op intact for the next trigger
  });

  it('the engine recovers on the next trigger after a transient network failure', async () => {
    const store = freshStore();
    let offline = true;
    const t = spyTransport(() => {
      if (offline) throw Object.assign(new TypeError('offline'), { name: 'TypeError' });
      return { status: 200 };
    });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: {}, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();                 // fails: op intact
    expect(await store.ops.count()).toBe(1);
    offline = false;
    await engine.syncNow();                 // succeeds now
    expect(await store.ops.count()).toBe(0);
  });
});

describe('5xx policy — in-cycle retry/backoff + parking (spec §3.3)', () => {
  it('retries a 5xx within the cycle up to CYCLE_5XX_RETRIES with backoff, then aborts the phase', async () => {
    vi.useFakeTimers();
    const store = freshStore();
    let attempts = 0;
    // Always 503: the op is sent 1 + CYCLE_5XX_RETRIES times within one cycle.
    const t = spyTransport(() => {
      attempts++;
      return { status: 503, body: { title: 'unavailable' } };
    });
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'] }));
    const engine = new SyncEngine({ store, transport: t });

    const cycle = engine.syncNow();
    // 1 immediate + 3 retries = 4 sends, gated behind 1s/2s/4s backoff.
    await vi.advanceTimersByTimeAsync(1000 + 2000 + 4000 + 10);
    await cycle;

    expect(attempts).toBe(1 + 3); // CYCLE_5XX_RETRIES = 3
    const head = await store.ops.orderBy('seq').first();
    expect(head).toBeDefined();           // op survived (phase aborted)
    expect(head!.attempts).toBeGreaterThanOrEqual(3); // attempts persisted
    expect(head!.lastError).toContain('503');
  });

  it('parks an op once attempts reach PARK_THRESHOLD (moved to issues, removed from ops)', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 500, body: { title: 'boom' } }));
    // Pre-seed attempts so a single 500 pushes it to the threshold.
    await store.ops.add(op({
      method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' },
      entityKeys: ['tasks:a'], attempts: 9, // +1 this send → 10 = PARK_THRESHOLD
    }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.ops.count()).toBe(0); // parked → off the queue
    const issues = await store.issues.toArray();
    expect(issues).toHaveLength(1);
    expect(issues[0].status).toBe('parked');
    expect(issues[0].op.path).toBe('/api/v1/tasks/a');
  });

  it('retryIssue re-queues a parked op with attempts reset and clears the issue', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 200 }));
    await store.issues.add({
      at: new Date().toISOString(),
      op: op({ method: 'PATCH', path: '/api/v1/tasks/a', body: { title: 'x' }, entityKeys: ['tasks:a'], attempts: 10, lastError: 'HTTP 500' }),
      reason: 'parked', status: 'parked',
    });
    const issue = (await store.issues.toArray())[0];
    const engine = new SyncEngine({ store, transport: t });

    await engine.retryIssue(issue.id!);

    expect(await store.issues.count()).toBe(0);
    const requeued = await store.ops.orderBy('seq').first();
    expect(requeued!.path).toBe('/api/v1/tasks/a');
    expect(requeued!.attempts).toBe(0);          // reset
    expect(requeued!.lastError).toBeUndefined();
  });

  it('discardIssue removes a parked op and schedules rejection recovery', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 200 }));
    await store.issues.add({
      at: new Date().toISOString(),
      op: op({ method: 'POST', path: '/api/v1/projects', body: { id: 'p1', name: 'X' }, entityKeys: ['projects:p1'], kind: 'create', attempts: 10 }),
      reason: 'parked', status: 'parked',
    });
    const issue = (await store.issues.toArray())[0];
    const engine = new SyncEngine({ store, transport: t });

    await engine.discardIssue(issue.id!);

    expect(await store.issues.count()).toBe(0);
    expect(await store.getMeta<boolean>('pendingRecovery')).toBe(true); // recovery scheduled (R4 honors)
  });
});

describe('definitive rejection — drop + ghost-row recovery (spec §3.3, §3.2)', () => {
  it('404 on a DELETE op is treated as success: op removed, no issue', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 404, body: { title: 'Not found' } }));
    await store.ops.add(op({ method: 'DELETE', path: '/api/v1/tasks/gone', entityKeys: ['tasks:gone'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.ops.count()).toBe(0);   // dequeued as done
    expect(await store.issues.count()).toBe(0); // no issue (§3.2)
  });

  it('404 on a PATCH (edit-vs-delete) drops the op, records an issue, schedules recovery', async () => {
    const store = freshStore();
    const t = spyTransport(() => ({ status: 404, body: { title: 'Not found' } }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/x', body: { title: 'edited' }, entityKeys: ['tasks:x'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.ops.count()).toBe(0);
    const issues = await store.issues.toArray();
    expect(issues).toHaveLength(1);
    expect(issues[0].status).toBe('dropped');
    expect(issues[0].op.method).toBe('PATCH');
    expect(await store.getMeta<boolean>('pendingRecovery')).toBe(true);
  });

  it('400 on a rejected CREATE deletes the ghost local row + drops dependent queued ops', async () => {
    const store = freshStore();
    // The create gets a 400; later PATCH against the same ghost id must be dropped too.
    const t = spyTransport((o) => (o.method === 'POST' ? { status: 400, body: { title: 'Bad' } } : { status: 200 }));
    // Ghost local row that the create produced:
    await store.tasks.put({
      id: 'ghost', title: 'Ghost', notes: '', projectId: null, labels: [], source: 'manual',
      status: 'Inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
      durationMinutes: 0, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
      rank: 'a0', dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
      recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
      createdAt: 'a', updatedAt: 'b', changeSeq: 0, deletedAt: null,
    } as never);
    await store.ops.add(op({ method: 'POST', path: '/api/v1/tasks', body: { id: 'ghost' }, entityKeys: ['tasks:ghost'], kind: 'create' }));
    await store.ops.add(op({ method: 'PATCH', path: '/api/v1/tasks/ghost', body: { title: 'edit' }, entityKeys: ['tasks:ghost'] }));
    const engine = new SyncEngine({ store, transport: t });

    await engine.syncNow();

    expect(await store.tasks.get('ghost')).toBeUndefined(); // ghost row deleted
    expect(await store.ops.count()).toBe(0);                // both ops gone
    const issues = await store.issues.toArray();
    // one for the create, one for the dependent PATCH (each recorded)
    expect(issues.length).toBe(2);
    expect(issues.some((i) => i.op.method === 'POST')).toBe(true);
    expect(issues.some((i) => i.op.method === 'PATCH')).toBe(true);
    expect(await store.getMeta<boolean>('pendingRecovery')).toBe(true);
  });
});
