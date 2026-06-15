import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { LocalStore } from '../../data/local/LocalStore';
import { SyncEngine } from '../SyncEngine';
import { FakeSyncServer } from './fakeSyncServer';
import { LocalDataClient } from '../../data/local/LocalDataClient';
import { CURSOR_OVERLAP, PULL_LIMIT } from '../constants';
import type { SyncTransport } from '../SyncTransport';
import type { SyncOp } from '../types';
import { entityKey } from '../types';
import type { SyncResponse, SyncChanges, TaskSyncRow } from '../../data/local/rows';

let nextUser = 0;
const openStores: LocalStore[] = [];
function openFresh(): LocalStore {
  const s = LocalStore.open(`eng-pull-u${Date.now()}-${nextUser++}`);
  openStores.push(s);
  return s;
}
afterEach(() => {
  for (const s of openStores.splice(0)) s.close();
  vi.restoreAllMocks();
});

function emptyChanges(): SyncChanges {
  return {
    tasks: [], subtasks: [], projects: [], noteGroups: [], notes: [],
    recurrenceRules: [], focusSessions: [], dailyPlans: [], settings: [],
  } as SyncChanges;
}

function taskRow(id: string, changeSeq: number): TaskSyncRow {
  return {
    id, title: `Task ${id}`, notes: '', projectId: null, labels: [], source: 'manual',
    status: 'Planned', plannedDate: '2026-06-11', scheduledStart: null, scheduledEnd: null,
    durationMinutes: 30, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
    rank: 'a0', dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
    createdAt: '2026-06-11T00:00:00Z', updatedAt: '2026-06-11T00:00:00Z',
    changeSeq, deletedAt: null,
  } as TaskSyncRow;
}

/** A transport whose `pull` returns scripted pages; push/ensure are inert (no ops queued). */
function scriptedTransport(pages: SyncResponse[]): {
  transport: SyncTransport;
  pullCalls: Array<{ since: number; limit: number }>;
} {
  const pullCalls: Array<{ since: number; limit: number }> = [];
  let i = 0;
  const transport: SyncTransport = {
    send: vi.fn(),
    ensureInstances: vi.fn().mockResolvedValue([]),
    pull: vi.fn(async (since: number, limit: number) => {
      pullCalls.push({ since, limit });
      const page = pages[i] ?? { changes: emptyChanges(), nextSince: since, hasMore: false };
      i += 1;
      return page;
    }),
  };
  return { transport, pullCalls };
}

describe('SyncEngine pull — pagination + cursor protocol', () => {
  it('first page requests since = max(0, cursor - CURSOR_OVERLAP) at PULL_LIMIT', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', CURSOR_OVERLAP + 12345); // > overlap
    const { transport, pullCalls } = scriptedTransport([
      { changes: emptyChanges(), nextSince: CURSOR_OVERLAP + 12345, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    expect(pullCalls[0]).toEqual({ since: 12345, limit: PULL_LIMIT });
  });

  it('clamps the overlap floor at 0 when the cursor is below CURSOR_OVERLAP', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 100); // < overlap
    const { transport, pullCalls } = scriptedTransport([
      { changes: emptyChanges(), nextSince: 100, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    expect(pullCalls[0].since).toBe(0);
  });

  it('chains nextSince across pages while hasMore, applying each page', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const p1 = { changes: emptyChanges(), nextSince: 500, hasMore: true } as SyncResponse;
    p1.changes.tasks = [taskRow('t1', 500)];
    const p2 = { changes: emptyChanges(), nextSince: 1000, hasMore: true } as SyncResponse;
    p2.changes.tasks = [taskRow('t2', 1000)];
    const p3 = { changes: emptyChanges(), nextSince: 1200, hasMore: false } as SyncResponse;
    p3.changes.tasks = [taskRow('t3', 1200)];
    const { transport, pullCalls } = scriptedTransport([p1, p2, p3]);
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    expect(pullCalls.map((c) => c.since)).toEqual([0, 500, 1000]); // first=max(0,0-overlap)=0, then chained
    expect(await store.tasks.get('t1')).toBeDefined();
    expect(await store.tasks.get('t2')).toBeDefined();
    expect(await store.tasks.get('t3')).toBeDefined();
    expect(await store.getMeta<number>('syncCursor')).toBe(1200); // final nextSince
  });

  it('never regresses the cursor: a smaller nextSince keeps max(prior, nextSince)', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 9000);
    // Overlap pull re-reads from 4000 and the page reports a smaller nextSince than 9000.
    const { transport } = scriptedTransport([
      { changes: emptyChanges(), nextSince: 4200, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    expect(await store.getMeta<number>('syncCursor')).toBe(9000); // max(9000, 4200) — no regression
  });

  it('stamps meta.lastSyncedAt at cycle end', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const { transport } = scriptedTransport([
      { changes: emptyChanges(), nextSince: 0, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    const before = Date.now();
    await engine.syncNow();
    const stamp = await store.getMeta<string>('lastSyncedAt');

    expect(stamp).toBeTypeOf('string');
    expect(Date.parse(stamp!)).toBeGreaterThanOrEqual(before);
  });

  it('emits changesApplied once per cycle when any page had rows; not when all pages were empty', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const withRows = { changes: emptyChanges(), nextSince: 10, hasMore: true } as SyncResponse;
    withRows.changes.tasks = [taskRow('t1', 10)];
    const emptyTail = { changes: emptyChanges(), nextSince: 10, hasMore: false } as SyncResponse;
    const { transport } = scriptedTransport([withRows, emptyTail]);
    const engine = new SyncEngine({ store, transport });

    const applied = vi.fn();
    engine.onChangesApplied(applied);
    await engine.syncNow();
    expect(applied).toHaveBeenCalledTimes(1); // once, despite two pages

    // A second cycle with only empty pages must not emit.
    const applied2 = vi.fn();
    engine.onChangesApplied(applied2);
    await engine.syncNow();
    expect(applied2).not.toHaveBeenCalled();
  });
});

describe('SyncEngine pull — initialSyncComplete gate (spec §4.1)', () => {
  it('sets meta.initialSyncComplete only when the first sync reaches hasMore=false', async () => {
    const store = openFresh();
    // since=0 (no prior cursor). First cycle: one page that still hasMore → NOT complete yet.
    const p1 = { changes: emptyChanges(), nextSince: 500, hasMore: true } as SyncResponse;
    p1.changes.tasks = [taskRow('t1', 500)];
    const p2 = { changes: emptyChanges(), nextSince: 800, hasMore: false } as SyncResponse;
    p2.changes.tasks = [taskRow('t2', 800)];
    const { transport } = scriptedTransport([p1, p2]);
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    // The full paged sync reached hasMore=false within this cycle → gate clears.
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true);
    expect(engine.getStatus().initialSyncComplete).toBe(true);
  });

  it('does NOT set initialSyncComplete while the first sync is still paging (hasMore stays true)', async () => {
    const store = openFresh();
    const only = { changes: emptyChanges(), nextSince: 500, hasMore: true } as SyncResponse;
    only.changes.tasks = [taskRow('t1', 500)];
    // scriptedTransport falls back to an empty hasMore:false page after the scripted ones —
    // so to keep hasMore=true we script a page that loops; instead inject a transport that
    // always reports hasMore=true for the first two pulls then is torn down.
    let calls = 0;
    const transport: SyncTransport = {
      send: vi.fn(),
      ensureInstances: vi.fn().mockResolvedValue([]),
      pull: vi.fn(async (since: number) => {
        calls += 1;
        if (calls > 50) return { changes: emptyChanges(), nextSince: since, hasMore: false };
        const page = { changes: emptyChanges(), nextSince: since + 10, hasMore: true } as SyncResponse;
        page.changes.tasks = [taskRow(`t${calls}`, since + 10)];
        // Stop the unbounded loop after 3 pages by throwing — simulates an interrupted
        // initial sync that never reached hasMore=false.
        if (calls >= 3) throw Object.assign(new TypeError('offline'), { name: 'TypeError' });
        return page;
      }),
    };
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    // The sync was interrupted before hasMore=false → gate must remain false.
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBeFalsy();
    expect(engine.getStatus().initialSyncComplete).toBe(false);
  });
});

describe('SyncEngine pull — pendingEnsureRows drain before delta pull (spec §4.3)', () => {
  it('applies buffered post-push ensure-instances rows even when the delta pull never returns them', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);

    // ensureInstances returns an instance row that the delta `pull` deliberately NEVER
    // returns (empty pages only). The only way this row reaches the local store is via
    // the pendingEnsureRows buffer being drained through applyPull in the pull phase —
    // so this isolates the dead-field fix from the ordinary delta-pull path.
    const instance = taskRow('r1:2026-06-12', 7);
    instance.isRecurrenceTemplate = false;
    instance.recurrenceRuleId = 'r1';
    const transport: SyncTransport = {
      send: vi.fn().mockResolvedValue({ status: 200 }),
      ensureInstances: vi.fn().mockResolvedValue([instance]),
      pull: vi.fn(async (since: number) => (
        { changes: emptyChanges(), nextSince: since, hasMore: false } as SyncResponse
      )),
    };

    await store.ops.add({
      method: 'POST', path: '/api/v1/recurrence',
      body: { task: { id: 'tmpl' }, rule: { id: 'r1', frequency: 'Daily' } },
      entityKeys: ['tasks:tmpl', 'recurrenceRules:r1'],
      kind: 'create', regenAfterPush: true, attempts: 0,
    } as SyncOp);

    const engine = new SyncEngine({ store, transport });
    await engine.syncNow();

    // The buffered instance row landed in local tasks via the drain (delta pull was empty).
    expect(await store.tasks.get('r1:2026-06-12')).toBeDefined();
  });
});

describe('SyncEngine pull — rejection recovery pull from since=0 (spec §3.3, §4.2)', () => {
  it('consumes meta.pendingRecovery: runs an extra pull from since=0 and clears the flag', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 9000);
    await store.setMeta('pendingRecovery', true); // a prior cycle scheduled recovery

    const pullSinces: number[] = [];
    const transport: SyncTransport = {
      send: vi.fn(),
      ensureInstances: vi.fn().mockResolvedValue([]),
      pull: vi.fn(async (since: number) => {
        pullSinces.push(since);
        return { changes: emptyChanges(), nextSince: since, hasMore: false } as SyncResponse;
      }),
    };
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow();

    // The delta pull ran (overlap from 9000) AND a recovery pull from since=0 ran.
    expect(pullSinces).toContain(0);
    // Flag cleared so the next cycle does not re-run recovery.
    expect(await store.getMeta<boolean>('pendingRecovery')).toBeFalsy();
  });

  it('re-delivers a shadow-skipped tombstone after a definitive rejection (edit-vs-delete)', async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    const transport = server.transport();

    // Server has a task that another device deletes.
    server.seed('tasks', {
      id: 'x', title: 'Shared', isRecurrenceTemplate: false, recurrenceRuleId: null,
      plannedDate: '2026-06-11', status: 'Planned', rank: 'a0',
    });
    // Local cycle 1: pull the live row down.
    await store.setMeta('syncCursor', 0);
    let engine = new SyncEngine({ store, transport });
    await engine.syncNow();
    expect(await store.tasks.get('x')).toBeDefined();

    // Remote device deletes 'x' (tombstone bumps change_seq).
    server.tombstone('tasks', 'x');

    // Locally we had a queued PATCH against 'x' (edit) that the server now 404s
    // (edit-vs-delete). The push drops it + schedules recovery. The shadow rule means
    // the tombstone pulled in the SAME cycle is skipped while the op is still queued —
    // so only the since=0 recovery pull re-delivers the tombstone and removes the row.
    await store.tasks.update('x', { title: 'edited locally' });
    await store.ops.add({
      method: 'PATCH', path: '/api/v1/tasks/x', body: { title: 'edited locally' },
      entityKeys: ['tasks:x'], kind: 'other', attempts: 0,
    } as SyncOp);

    engine = new SyncEngine({ store, transport });
    await engine.syncNow();

    // The op was dropped (issue recorded), recovery ran, and the tombstone removed 'x'.
    expect(await store.ops.count()).toBe(0);
    expect((await store.issues.toArray()).length).toBeGreaterThan(0);
    expect(await store.tasks.get('x')).toBeUndefined(); // delete wins
    expect(await store.getMeta<boolean>('pendingRecovery')).toBeFalsy();
  });
});

describe('SyncEngine pull — initialSyncComplete gate flag', () => {
  it('stays false through paged pages and is set true only when hasMore reaches false', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const p1 = { changes: emptyChanges(), nextSince: 500, hasMore: true } as SyncResponse;
    const p2 = { changes: emptyChanges(), nextSince: 900, hasMore: false } as SyncResponse;
    const { transport } = scriptedTransport([p1, p2]);
    const engine = new SyncEngine({ store, transport });

    expect(await store.getMeta<boolean>('initialSyncComplete')).toBeUndefined();
    await engine.syncNow();
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true);
  });

  it('leaves the flag false when the initial page chain is interrupted before hasMore=false', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    const transport: SyncTransport = {
      send: vi.fn(),
      ensureInstances: vi.fn().mockResolvedValue([]),
      pull: vi
        .fn()
        // first page says hasMore, then the second page throws (interrupted initial sync)
        .mockResolvedValueOnce({ changes: emptyChanges(), nextSince: 500, hasMore: true })
        .mockRejectedValueOnce(Object.assign(new TypeError('network down'), { name: 'NetworkError' })),
    };
    const engine = new SyncEngine({ store, transport });

    await engine.syncNow(); // the engine swallows the cycle error; the flag must stay unset

    expect(await store.getMeta<boolean>('initialSyncComplete')).toBeFalsy();
  });

  it('never unsets the flag once set, and exposes it in subscribe() status', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);
    await store.setMeta('initialSyncComplete', true); // already complete from a prior session
    const { transport } = scriptedTransport([
      { changes: emptyChanges(), nextSince: 0, hasMore: false },
    ]);
    const engine = new SyncEngine({ store, transport });

    const seen: boolean[] = [];
    engine.subscribe((s) => seen.push(s.initialSyncComplete));
    await engine.syncNow();

    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true); // still true
    // emitStatus refreshes the status cache asynchronously; wait for it to land.
    await vi.waitFor(() => expect(seen.some((v) => v === true)).toBe(true)); // status reflects it
    expect(engine.getStatus().initialSyncComplete).toBe(true); // synchronous getStatus too
  });
});

describe('SyncEngine pull — recovery pull (since=0) after a dropped op (§3.3, §4.2)', () => {
  it("a 400-dropped op's ghost edit is reverted by the recovery pull", async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    const engine = new SyncEngine({ store, transport: server.transport() });
    const client = new LocalDataClient(store, {
      nudge: () => {},
      online: () => true,
      ensureInstances: (s: string, e: string) => engine.ensureInstances(s, e),
    });

    // A clean task exists on both server and local.
    const created = await client.tasks.create({ title: 'Clean' });
    await engine.syncNow(); // create lands; pull is a no-op for own write

    // Force a definitive rejection: the fake server fails the next PATCH with 400.
    // We drive the edit through the client so the local ghost edit exists too.
    await client.tasks.update(created.id, { title: 'ghost edit' });
    server.failNext((o) => o.method === 'PATCH', { status: 400, body: { title: 'bad request' } });

    await engine.syncNow(); // op dropped + pendingRecovery → since=0 recovery snaps the title back

    const back = (await client.tasks.getAll()).find((t) => t.id === created.id);
    expect(back!.title).toBe('Clean'); // server truth restored — the ghost edit is gone
    const issues = await store.issues.toArray();
    expect(issues).toHaveLength(1);
    expect(issues[0].status).toBe('dropped');
  });

  it('a shadow-skipped tombstone is delivered by the recovery pull after its pending op is dropped (spec §4.2)', async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    const engine = new SyncEngine({ store, transport: server.transport() });
    const client = new LocalDataClient(store, {
      nudge: () => {},
      online: () => true,
      ensureInstances: (s: string, e: string) => engine.ensureInstances(s, e),
    });

    const created = await client.tasks.create({ title: 'DoomedRemotely' });
    await engine.syncNow(); // create lands on the server

    // Remote device deletes the task (server tombstones it, advancing changeSeq).
    server.tombstone('tasks', created.id);

    // This device edits the same task offline → a pending op now shadows tasks:<id>,
    // so the tombstone pulled this cycle is shadow-skipped (the row survives locally).
    await client.tasks.update(created.id, { title: 'edited while deleted remotely' });
    // The edit pushes as a PATCH → server 404 (tombstoned) → drop op + pendingRecovery.
    await engine.syncNow();

    // The since=0 recovery, run with the op already dropped (no shadow), deletes the row.
    const after = (await client.tasks.getAll()).find((t) => t.id === created.id);
    expect(after).toBeUndefined(); // delete wins via recovery (§3.3, §4.2)
    const issues = await store.issues.toArray();
    expect(issues.some((i) => i.reason.includes('404'))).toBe(true);
  });

  it('clears pendingRecovery after the recovery pull so the next clean cycle does not re-run since=0', async () => {
    const store = openFresh();
    const server = new FakeSyncServer();
    const engine = new SyncEngine({ store, transport: server.transport() });
    const client = new LocalDataClient(store, {
      nudge: () => {},
      online: () => true,
      ensureInstances: (s: string, e: string) => engine.ensureInstances(s, e),
    });
    const created = await client.tasks.create({ title: 'X' });
    await engine.syncNow();
    await client.tasks.update(created.id, { title: 'ghost' });
    server.failNext((o) => o.method === 'PATCH', { status: 400, body: { title: 'bad' } });
    await engine.syncNow(); // recovery runs once

    const pullSpy = vi.spyOn(server, 'pullCount'); // harness: total pull() invocations counter
    const before = server.pullCount();
    await engine.syncNow(); // clean cycle — must NOT trigger a second since=0 recovery
    const delta = server.pullCount() - before;
    // A clean cycle pulls exactly one chain (1+ pages); a lingering recovery would double it
    // by re-pulling from since=0. With a tiny dataset both are single-page, so assert the
    // recovery is not re-armed by checking the flag indirectly: exactly one normal pull chain.
    expect(delta).toBe(1);
    pullSpy.mockRestore();
  });
});

describe('SyncEngine — onFirstCycleSettled (fires once per start(), success or failure)', () => {
  it('fires once after a successful first cycle and not on later cycles', async () => {
    vi.useFakeTimers();
    try {
      const store = openFresh();
      await store.setMeta('syncCursor', 0);
      const { transport } = scriptedTransport([
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
      ]);
      const engine = new SyncEngine({ store, transport });
      const settled = vi.fn();
      engine.onFirstCycleSettled(settled);

      engine.start(); // online (connectivity defaults online in node fake-idb env)
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();

      expect(settled).toHaveBeenCalledTimes(1);

      // A second explicit cycle must not re-fire.
      await engine.syncNow();
      expect(settled).toHaveBeenCalledTimes(1);
      engine.stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('fires once when the first cycle fails terminally (pull throws)', async () => {
    vi.useFakeTimers();
    try {
      const store = openFresh();
      await store.setMeta('syncCursor', 0);
      const transport: SyncTransport = {
        send: vi.fn(),
        ensureInstances: vi.fn().mockResolvedValue([]),
        pull: vi.fn().mockRejectedValue(Object.assign(new TypeError('down'), { name: 'NetworkError' })),
      };
      const engine = new SyncEngine({ store, transport });
      const settled = vi.fn();
      engine.onFirstCycleSettled(settled);

      engine.start();
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();

      expect(settled).toHaveBeenCalledTimes(1); // a failed cycle still settles
      engine.stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('fires once when started offline and the cycle cannot even begin (§7.1.3)', async () => {
    vi.useFakeTimers();
    try {
      const store = openFresh();
      const { transport } = scriptedTransport([
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
      ]);
      const engine = new SyncEngine({ store, transport });
      // Force offline for this engine instance.
      vi.spyOn(engine, 'online').mockReturnValue(false);
      const settled = vi.fn();
      engine.onFirstCycleSettled(settled);

      engine.start(); // offline → no cycle runs, but the hook must still settle
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();

      expect(settled).toHaveBeenCalledTimes(1);
      engine.stop();
    } finally {
      vi.useRealTimers();
    }
  });

  it('re-arms on a fresh start() so the hook can fire again next session', async () => {
    vi.useFakeTimers();
    try {
      const store = openFresh();
      await store.setMeta('syncCursor', 0);
      const { transport } = scriptedTransport([
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
        { changes: emptyChanges(), nextSince: 0, hasMore: false },
      ]);
      const engine = new SyncEngine({ store, transport });
      const settled = vi.fn();
      engine.onFirstCycleSettled(settled);

      engine.start();
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();
      engine.stop();
      expect(settled).toHaveBeenCalledTimes(1);

      engine.start(); // new session
      await vi.runOnlyPendingTimersAsync();
      await Promise.resolve();
      await vi.runOnlyPendingTimersAsync();
      engine.stop();
      expect(settled).toHaveBeenCalledTimes(2);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('SyncEngine — post-push ensure-instances rows applied via applyPull (§4.3)', () => {
  it('upserts returned ensure rows before the pull phase, honoring the shadow rule', async () => {
    const store = openFresh();
    await store.setMeta('syncCursor', 0);

    const ensureRows: TaskSyncRow[] = [
      taskRow('inst-1', 100),
      taskRow('inst-2', 101),
    ];
    // Shadow inst-2 with a pending op — ensure-application must skip it.
    await store.ops.add({
      method: 'PATCH',
      path: '/api/v1/tasks/inst-2',
      body: { title: 'local' },
      entityKeys: [entityKey('tasks', 'inst-2')],
      kind: 'other',
      attempts: 0,
    });
    await store.tasks.put(taskRow('inst-2', 1)); // the local (shadowed) version
    await store.tasks.update('inst-2', { title: 'LOCAL OWNS THIS' });

    const transport: SyncTransport = {
      // The shadow op stays queued (network-abort keeps the queue intact) so it still
      // shadows inst-2; the push aborts cleanly and the pull phase still runs (C5).
      send: vi.fn().mockRejectedValue(Object.assign(new TypeError('offline'), { name: 'NetworkError' })),
      ensureInstances: vi.fn().mockResolvedValue(ensureRows),
      pull: vi.fn().mockResolvedValue({ changes: emptyChanges(), nextSince: 0, hasMore: false }),
    };
    const engine = new SyncEngine({ store, transport });

    // Simulate R3's post-push hook having buffered the ensure rows for this cycle.
    // (Internal field per the engine-internals contract-gap note.)
    (engine as unknown as { pendingEnsureRows: TaskSyncRow[] | null }).pendingEnsureRows = ensureRows;

    await engine.syncNow();

    expect((await store.tasks.get('inst-1'))!.title).toBe('Task inst-1'); // ensure row applied
    expect((await store.tasks.get('inst-2'))!.title).toBe('LOCAL OWNS THIS'); // shadowed → not clobbered
    // The buffer is cleared after application so a later cycle does not re-apply stale rows.
    expect((engine as unknown as { pendingEnsureRows: TaskSyncRow[] | null }).pendingEnsureRows).toBeNull();
  });
});
