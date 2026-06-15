import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalStore } from '../LocalStore';
import { applyPullPage } from '../applyPull';
import { entityKey, type SyncOp } from '../../../sync/types';
import type {
  TaskSyncRow,
  DailyPlanSyncRow,
  SettingSyncRow,
  SyncChanges,
} from '../rows';

let nextUser = 0;
const openStores: LocalStore[] = [];
function openFresh(): LocalStore {
  const s = LocalStore.open(`pull-u${Date.now()}-${nextUser++}`);
  openStores.push(s);
  return s;
}
afterEach(() => {
  for (const s of openStores.splice(0)) s.close();
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
    changeSeq: 1,
    deletedAt: null,
    ...overrides,
  } as TaskSyncRow;
}

function planRow(date: string, overrides: Partial<DailyPlanSyncRow> = {}): DailyPlanSyncRow {
  return {
    date,
    committedAt: '2026-06-11T08:00:00Z',
    plannedTaskIds: [],
    plannedMinutes: 0,
    changeSeq: 1,
    deletedAt: null,
    ...overrides,
  } as DailyPlanSyncRow;
}

function settingRow(key: string, value: string, overrides: Partial<SettingSyncRow> = {}): SettingSyncRow {
  return {
    key,
    value,
    changeSeq: 1,
    deletedAt: null,
    ...overrides,
  } as SettingSyncRow;
}

/** An empty SyncChanges with all nine tables present (the wire envelope shape, C6). */
function emptyChanges(): SyncChanges {
  return {
    tasks: [],
    subtasks: [],
    projects: [],
    noteGroups: [],
    notes: [],
    recurrenceRules: [],
    focusSessions: [],
    dailyPlans: [],
    settings: [],
  } as SyncChanges;
}

async function enqueue(store: LocalStore, op: Omit<SyncOp, 'seq' | 'attempts'>): Promise<void> {
  await store.ops.add({ attempts: 0, ...op } as SyncOp);
}

describe('applyPullPage — upserts', () => {
  it('inserts new rows and overwrites existing rows by key across uuid/date/key tables', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1', { title: 'old', changeSeq: 1 }));

    const changes = emptyChanges();
    changes.tasks = [
      taskRow('t1', { title: 'new', changeSeq: 5 }), // overwrite
      taskRow('t2', { title: 'fresh', changeSeq: 6 }), // insert
    ];
    changes.dailyPlans = [planRow('2026-06-11', { plannedMinutes: 90, changeSeq: 7 })];
    changes.settings = [settingRow('workStartHour', '6', { changeSeq: 8 })];

    const applied = await applyPullPage(store, changes);

    expect(applied).toBe(true);
    expect((await store.tasks.get('t1'))!.title).toBe('new');
    expect((await store.tasks.get('t1'))!.changeSeq).toBe(5);
    expect((await store.tasks.get('t2'))!.title).toBe('fresh');
    expect((await store.dailyPlans.get('2026-06-11'))!.plannedMinutes).toBe(90);
    expect((await store.settings.get('workStartHour'))!.value).toBe('6');
  });

  it('an empty page applies nothing and reports applied=false', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1'));
    const applied = await applyPullPage(store, emptyChanges());
    expect(applied).toBe(false);
    expect(await store.tasks.count()).toBe(1);
  });
});

describe('applyPullPage — tombstone deletes', () => {
  it('deletes the local row when deletedAt != null, by each table key', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1'));
    await store.dailyPlans.put(planRow('2026-06-11'));
    await store.settings.put(settingRow('workStartHour', '8'));

    const changes = emptyChanges();
    changes.tasks = [taskRow('t1', { deletedAt: '2026-06-12T00:00:00Z', changeSeq: 9 })];
    changes.dailyPlans = [planRow('2026-06-11', { deletedAt: '2026-06-12T00:00:00Z', changeSeq: 10 })];
    changes.settings = [settingRow('workStartHour', '8', { deletedAt: '2026-06-12T00:00:00Z', changeSeq: 11 })];

    const applied = await applyPullPage(store, changes);

    expect(applied).toBe(true);
    expect(await store.tasks.get('t1')).toBeUndefined();
    expect(await store.dailyPlans.get('2026-06-11')).toBeUndefined();
    expect(await store.settings.get('workStartHour')).toBeUndefined();
  });

  it('a tombstone for an already-absent row is a harmless no-op', async () => {
    const store = openFresh();
    const changes = emptyChanges();
    changes.tasks = [taskRow('ghost', { deletedAt: '2026-06-12T00:00:00Z' })];
    const applied = await applyPullPage(store, changes);
    expect(applied).toBe(true); // the page carried a row even though delete was a no-op
    expect(await store.tasks.get('ghost')).toBeUndefined();
  });
});

describe('applyPullPage — shadow rule (skip rows with a pending op on the same entity key)', () => {
  it('skips an upsert for a uuid entity shadowed by a pending op', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1', { title: 'local edit', changeSeq: 1 }));
    // Pending op touches tasks:t1 — a mid-pull local write.
    await enqueue(store, {
      method: 'PATCH',
      path: '/api/v1/tasks/t1',
      body: { title: 'local edit' },
      entityKeys: [entityKey('tasks', 't1')],
      kind: 'other',
    });

    const changes = emptyChanges();
    changes.tasks = [
      taskRow('t1', { title: 'stale remote', changeSeq: 4 }), // shadowed → skipped
      taskRow('t2', { title: 'remote t2', changeSeq: 5 }), // not shadowed → applied
    ];

    const applied = await applyPullPage(store, changes);

    expect(applied).toBe(true); // t2 applied
    expect((await store.tasks.get('t1'))!.title).toBe('local edit'); // NOT clobbered
    expect((await store.tasks.get('t2'))!.title).toBe('remote t2');
  });

  it('shadow rule also blocks a tombstone delete for a shadowed row', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1', { title: 'kept' }));
    await enqueue(store, {
      method: 'PATCH',
      path: '/api/v1/tasks/t1',
      body: { title: 'kept' },
      entityKeys: [entityKey('tasks', 't1')],
      kind: 'other',
    });
    const changes = emptyChanges();
    changes.tasks = [taskRow('t1', { deletedAt: '2026-06-12T00:00:00Z', changeSeq: 9 })];
    await applyPullPage(store, changes);
    expect(await store.tasks.get('t1')).toBeDefined(); // tombstone shadowed → row kept
  });

  it('skips a shadowed date entity (dailyPlans) and a shadowed key entity (settings)', async () => {
    const store = openFresh();
    await store.dailyPlans.put(planRow('2026-06-11', { plannedMinutes: 15 }));
    await store.settings.put(settingRow('workStartHour', '7'));
    await enqueue(store, {
      method: 'PUT',
      path: '/api/v1/daily-plans/2026-06-11',
      body: { date: '2026-06-11', plannedTaskIds: [], plannedMinutes: 15 },
      entityKeys: [entityKey('dailyPlans', '2026-06-11')],
      kind: 'other',
    });
    await enqueue(store, {
      method: 'PUT',
      path: '/api/v1/settings',
      body: { workStartHour: 7 },
      entityKeys: [entityKey('settings', 'workStartHour')],
      kind: 'other',
    });

    const changes = emptyChanges();
    changes.dailyPlans = [planRow('2026-06-11', { plannedMinutes: 99, changeSeq: 4 })];
    changes.settings = [settingRow('workStartHour', '5', { changeSeq: 5 })];

    await applyPullPage(store, changes);

    expect((await store.dailyPlans.get('2026-06-11'))!.plannedMinutes).toBe(15); // shadowed
    expect((await store.settings.get('workStartHour'))!.value).toBe('7'); // shadowed
  });

  it('a shadowed page where EVERY row is skipped reports applied=false', async () => {
    const store = openFresh();
    await store.tasks.put(taskRow('t1'));
    await enqueue(store, {
      method: 'PATCH',
      path: '/api/v1/tasks/t1',
      body: { title: 'x' },
      entityKeys: [entityKey('tasks', 't1')],
      kind: 'other',
    });
    const changes = emptyChanges();
    changes.tasks = [taskRow('t1', { title: 'stale', changeSeq: 9 })];
    const applied = await applyPullPage(store, changes);
    expect(applied).toBe(false); // nothing actually applied
  });
});
