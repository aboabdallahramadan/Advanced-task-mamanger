import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalStore } from '../LocalStore';
import { LocalDataClient } from '../LocalDataClient';
import type { SyncBridge } from '../LocalDataClient';

let n = 0;
const open: LocalStore[] = [];
function freshStore(): LocalStore {
  const s = LocalStore.open(`reset-u${Date.now()}-${n++}`);
  open.push(s);
  return s;
}
afterEach(() => {
  for (const s of open.splice(0)) s.close();
});

const inertBridge: SyncBridge = {
  nudge: () => {},
  online: () => true,
  ensureInstances: async () => [],
};

describe('LocalDataClient.resetForFullResync', () => {
  it('clears the 9 entity tables + issues + reset meta keys, KEEPS lastUser', async () => {
    const store = freshStore();
    const client = new LocalDataClient(store, inertBridge);

    // Seed every entity table with one live row (wire shape; only the fields the
    // primary keys need are load-bearing for the count assertions).
    await store.tasks.add({ id: 't1', rank: 'a0', changeSeq: 5, deletedAt: null } as never);
    await store.subtasks.add({ id: 's1', taskId: 't1', changeSeq: 5, deletedAt: null } as never);
    await store.projects.add({ id: 'p1', name: 'P', rank: 'a0', changeSeq: 5, deletedAt: null } as never);
    await store.noteGroups.add({ id: 'g1', name: 'G', rank: 'a0', changeSeq: 5, deletedAt: null } as never);
    await store.notes.add({ id: 'no1', rank: 'a0', changeSeq: 5, deletedAt: null } as never);
    await store.recurrenceRules.add({ id: 'r1', changeSeq: 5, deletedAt: null } as never);
    await store.focusSessions.add({ id: 'f1', date: '2026-06-16', changeSeq: 5, deletedAt: null } as never);
    await store.dailyPlans.add({ date: '2026-06-16', plannedMinutes: 0, changeSeq: 5, deletedAt: null } as never);
    await store.settings.add({ key: 'workStartHour', value: '9', changeSeq: 5, deletedAt: null } as never);

    // An issue row (the parked/dropped-op log) and an op (must survive — not in scope).
    await store.issues.add({ at: new Date().toISOString(), op: {} as never, reason: 'x', status: 'parked' } as never);
    await store.ops.add({ method: 'PATCH', path: '/api/v1/tasks/t1', entityKeys: ['tasks:t1'], kind: 'other', attempts: 0 } as never);

    // Meta: the cursor + gate flags to be cleared, and lastUser to be PRESERVED.
    await store.setMeta('syncCursor', 12345);
    await store.setMeta('initialSyncComplete', true);
    await store.setMeta('pendingRecovery', true);
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });

    await client.resetForFullResync();

    // 9 entity tables empty.
    expect(await store.tasks.count()).toBe(0);
    expect(await store.subtasks.count()).toBe(0);
    expect(await store.projects.count()).toBe(0);
    expect(await store.noteGroups.count()).toBe(0);
    expect(await store.notes.count()).toBe(0);
    expect(await store.recurrenceRules.count()).toBe(0);
    expect(await store.focusSessions.count()).toBe(0);
    expect(await store.dailyPlans.count()).toBe(0);
    expect(await store.settings.count()).toBe(0);

    // issues cleared; ops untouched (out of scope — drained precondition guards it).
    expect(await store.issues.count()).toBe(0);
    expect(await store.ops.count()).toBe(1);

    // Meta: cursor cleared (undefined), initialSyncComplete=false, pendingRecovery=false.
    expect(await store.getMeta<number>('syncCursor')).toBeUndefined();
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(false);
    expect(await store.getMeta<boolean>('pendingRecovery')).toBe(false);

    // lastUser PRESERVED (authed-offline identity).
    expect(await store.getMeta<{ id: string }>('lastUser')).toEqual({
      id: 'u1', email: 'a@b.c', timeZoneId: 'UTC',
    });
  });
});
