import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { LocalStore } from '../../data/local/LocalStore';
import { SyncEngine } from '../SyncEngine';
import { CURSOR_OVERLAP, PULL_LIMIT } from '../constants';
import type { SyncTransport } from '../SyncTransport';
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
