import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import Dexie from 'dexie';
import { LocalStore, getLastUserId, setLastUserId } from '../LocalStore';
import { entityKey, type SyncOp } from '../../../sync/types';
import type { TaskSyncRow, SubtaskSyncRow } from '../rows';

// fake-indexeddb keeps one global registry for the whole test file, so every
// test uses a unique userId (unique DB name) for isolation.
let nextUser = 0;
function freshUserId(): string {
  return `u${Date.now()}-${nextUser++}`;
}

let openStores: LocalStore[] = [];
function open(userId: string): LocalStore {
  const s = LocalStore.open(userId);
  openStores.push(s);
  return s;
}

afterEach(() => {
  for (const s of openStores) s.close();
  openStores = [];
});

function taskRow(id: string, overrides: Partial<TaskSyncRow> = {}): TaskSyncRow {
  return {
    id,
    title: `Task ${id}`,
    notes: '',
    projectId: null,
    labels: [],
    source: 'manual',
    status: 'Planned',
    plannedDate: '2026-06-11',
    scheduledStart: null,
    scheduledEnd: null,
    durationMinutes: 30,
    actualTimeMinutes: 0,
    priority: null,
    reminderMinutes: null,
    rank: 'a0',
    dueDate: null,
    recurrenceRuleId: null,
    isRecurrenceTemplate: false,
    recurrenceDetached: false,
    recurrenceOriginalDate: null,
    completedAt: null,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
    ...overrides,
  };
}

function subtaskRow(id: string, taskId: string): SubtaskSyncRow {
  return {
    id,
    taskId,
    title: `Sub ${id}`,
    completed: false,
    sortOrder: 0,
    createdAt: '2026-06-11T00:00:00Z',
    updatedAt: '2026-06-11T00:00:00Z',
    changeSeq: 0,
    deletedAt: null,
  };
}

function memoryStorage(): Storage {
  let m: Record<string, string> = {};
  return {
    get length() {
      return Object.keys(m).length;
    },
    clear: () => {
      m = {};
    },
    getItem: (k: string) => (k in m ? m[k] : null),
    key: (i: number) => Object.keys(m)[i] ?? null,
    removeItem: (k: string) => {
      delete m[k];
    },
    setItem: (k: string, v: string) => {
      m[k] = v;
    },
  };
}

describe('LocalStore.open', () => {
  it('creates a per-user database named tmap-{userId}', async () => {
    const userId = freshUserId();
    const store = open(userId);
    await store.setMeta('probe', 1); // force the lazy DB open/create
    expect(await Dexie.exists(`tmap-${userId}`)).toBe(true);
  });

  it('isolates two userIds into separate databases', async () => {
    const a = open(freshUserId());
    const b = open(freshUserId());
    await a.tasks.put(taskRow('t1'));
    expect(await a.tasks.count()).toBe(1);
    expect(await b.tasks.count()).toBe(0);
  });
});

describe('meta', () => {
  it('round-trips scalars and objects; returns undefined for missing keys', async () => {
    const store = open(freshUserId());
    expect(await store.getMeta('syncCursor')).toBeUndefined();
    await store.setMeta('syncCursor', 42);
    expect(await store.getMeta<number>('syncCursor')).toBe(42);
    await store.setMeta('syncCursor', 99); // overwrite
    expect(await store.getMeta<number>('syncCursor')).toBe(99);
    const lastUser = { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' };
    await store.setMeta('lastUser', lastUser);
    expect(await store.getMeta('lastUser')).toEqual(lastUser);
    await store.setMeta('initialSyncComplete', true);
    expect(await store.getMeta<boolean>('initialSyncComplete')).toBe(true);
  });
});

describe('entity table indexes (pinned C3 schema)', () => {
  it('queries tasks by plannedDate/status and subtasks by taskId', async () => {
    const store = open(freshUserId());
    await store.tasks.bulkPut([
      taskRow('t1', { plannedDate: '2026-06-11', status: 'Planned' }),
      taskRow('t2', { plannedDate: '2026-06-12', status: 'Done' }),
    ]);
    await store.subtasks.put(subtaskRow('s1', 't1'));
    const onThe11th = await store.tasks.where('plannedDate').equals('2026-06-11').toArray();
    expect(onThe11th.map((t) => t.id)).toEqual(['t1']);
    const done = await store.tasks.where('status').equals('Done').toArray();
    expect(done.map((t) => t.id)).toEqual(['t2']);
    expect(await store.subtasks.where('taskId').equals('t1').count()).toBe(1);
  });
});

describe('ops queue', () => {
  it('auto-increments seq in FIFO insertion order', async () => {
    const store = open(freshUserId());
    const op = (path: string, key: string): SyncOp => ({
      method: 'PATCH',
      path,
      body: { title: 'x' },
      entityKeys: [key],
      kind: 'other',
      attempts: 0,
    });
    await store.ops.add(op('/api/v1/tasks/a', entityKey('tasks', 'a')));
    await store.ops.add(op('/api/v1/tasks/b', entityKey('tasks', 'b')));
    const all = await store.ops.orderBy('seq').toArray();
    expect(all.map((o) => o.path)).toEqual(['/api/v1/tasks/a', '/api/v1/tasks/b']);
    expect(all[0].seq!).toBeLessThan(all[1].seq!);
  });

  it('finds ops by any member of entityKeys (multiEntry index)', async () => {
    const store = open(freshUserId());
    await store.ops.add({
      method: 'DELETE',
      path: '/api/v1/tasks/x',
      entityKeys: [
        entityKey('tasks', 'x'),
        entityKey('subtasks', 's1'),
        entityKey('subtasks', 's2'),
      ],
      kind: 'other',
      attempts: 0,
    });
    await store.ops.add({
      method: 'PUT',
      path: '/api/v1/plans/2026-06-11',
      body: { date: '2026-06-11', taskIds: [] },
      entityKeys: [entityKey('dailyPlans', '2026-06-11')],
      kind: 'other',
      attempts: 0,
    });
    expect(await store.ops.where('entityKeys').equals('tasks:x').count()).toBe(1);
    expect(await store.ops.where('entityKeys').equals('subtasks:s2').count()).toBe(1);
    expect(await store.ops.where('entityKeys').equals('dailyPlans:2026-06-11').count()).toBe(1);
    expect(await store.ops.where('entityKeys').equals('tasks:absent').count()).toBe(0);
  });
});

describe('LocalStore.wipe', () => {
  it('deletes the per-user database; a reopen starts empty', async () => {
    const userId = freshUserId();
    const store = open(userId);
    await store.tasks.put(taskRow('t1'));
    store.close();
    await LocalStore.wipe(userId);
    expect(await Dexie.exists(`tmap-${userId}`)).toBe(false);
    const again = open(userId);
    expect(await again.tasks.count()).toBe(0);
  });
});

describe('lastUserId pointer', () => {
  it('round-trips through storage and clears on null', () => {
    const s = memoryStorage();
    expect(getLastUserId(s)).toBeNull();
    setLastUserId('user-1', s);
    expect(getLastUserId(s)).toBe('user-1');
    expect(s.getItem('tmap:lastUserId')).toBe('user-1');
    setLastUserId(null, s);
    expect(getLastUserId(s)).toBeNull();
    expect(s.getItem('tmap:lastUserId')).toBeNull();
  });

  it('never throws when storage is unavailable or throws', () => {
    const blocked: Storage = {
      ...memoryStorage(),
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    };
    expect(() => setLastUserId('u', blocked)).not.toThrow();
    expect(getLastUserId(blocked)).toBeNull();
    expect(() => setLastUserId(null, blocked)).not.toThrow();
  });
});
