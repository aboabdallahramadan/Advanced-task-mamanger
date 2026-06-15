import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, taskRow, subtaskRow } from './helpers';
import { projectRow, noteGroupRow, noteRow, planRow, settingRow } from './helpers';

afterEach(closeAll);

describe('LocalDataClient.tasks reads', () => {
  it('orders tasks by (rank, id) ordinal, breaking tied ranks by id', async () => {
    const store = openStore();
    // Two rows share rank 'a0' (the recurrence-instance tie, spec §5.1). Ordinal (rank, id)
    // compare puts both 'a0' rows before the 'm' row ('a0' < 'm'); within the tie 'b1' beats
    // 'c2' by id ('b1' < 'c2'). 'z9' (rank 'm') sorts last.
    await store.tasks.bulkPut([
      taskRow('c2', { rank: 'a0' }),
      taskRow('b1', { rank: 'a0' }),
      taskRow('z9', { rank: 'm' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    const got = await dc.tasks.getAll();
    expect(got.map((t) => t.id)).toEqual(['b1', 'c2', 'z9']);
    expect(got.map((t) => t.order)).toEqual([0, 1, 2]);
  });

  it('joins live subtasks sorted by (sortOrder, id) and maps them as domain subtasks', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1'));
    await store.subtasks.bulkPut([
      subtaskRow('s2', 't1', { sortOrder: 1, title: 'second' }),
      subtaskRow('s1', 't1', { sortOrder: 0, title: 'first' }),
      subtaskRow('sx', 'other', { sortOrder: 0, title: 'foreign' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    const [t] = await dc.tasks.getAll();
    expect(t.subtasks.map((s) => s.title)).toEqual(['first', 'second']);
    expect(t.subtasks.map((s) => s.order)).toEqual([0, 1]);
  });

  it('getByDate filters via the plannedDate index', async () => {
    const store = openStore();
    await store.tasks.bulkPut([
      taskRow('t1', { plannedDate: '2026-06-11', rank: 'a' }),
      taskRow('t2', { plannedDate: '2026-06-12', rank: 'b' }),
      taskRow('t3', { plannedDate: '2026-06-11', rank: 'c' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    const got = await dc.tasks.getByDate('2026-06-11');
    expect(got.map((t) => t.id)).toEqual(['t1', 't3']);
  });
});

describe('LocalDataClient.projects/noteGroups/notes reads', () => {
  it('projects.getAll orders by (rank, id) and assigns sequential order', async () => {
    const store = openStore();
    await store.projects.bulkPut([
      projectRow('p-c', { rank: 'a0' }),
      projectRow('p-a', { rank: 'a0' }),
      projectRow('p-b', { rank: 'm' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    const got = await dc.projects.getAll();
    // (rank, id) ordinal order: 'a0' rows before 'm'; within the 'a0' tie 'p-a' < 'p-c' by id.
    expect(got.map((p) => p.id)).toEqual(['p-a', 'p-c', 'p-b']);
    expect(got.map((p) => p.order)).toEqual([0, 1, 2]);
    expect((got[0] as Record<string, unknown>)._rank).toBeUndefined();
  });

  it('noteGroups.getAll / getByProject filter + order', async () => {
    const store = openStore();
    await store.noteGroups.bulkPut([
      noteGroupRow('g1', { projectId: 'P', rank: 'b' }),
      noteGroupRow('g2', { projectId: null, rank: 'a' }),
      noteGroupRow('g3', { projectId: 'P', rank: 'c' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    expect((await dc.noteGroups.getAll()).map((g) => g.id)).toEqual(['g2', 'g1', 'g3']);
    expect((await dc.noteGroups.getByProject('P')).map((g) => g.id)).toEqual(['g1', 'g3']);
  });

  it('notes.getAll / getByGroup / getByProject / getById', async () => {
    const store = openStore();
    await store.notes.bulkPut([
      noteRow('n1', { groupId: 'G', rank: 'b' }),
      noteRow('n2', { projectId: 'P', rank: 'a' }),
      noteRow('n3', { groupId: 'G', rank: 'c' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    expect((await dc.notes.getAll()).map((n) => n.id)).toEqual(['n2', 'n1', 'n3']);
    expect((await dc.notes.getByGroup('G')).map((n) => n.id)).toEqual(['n1', 'n3']);
    expect((await dc.notes.getByProject('P')).map((n) => n.id)).toEqual(['n2']);
    expect((await dc.notes.getById('n2'))?.id).toBe('n2');
    expect(await dc.notes.getById('absent')).toBeNull();
  });
});

describe('LocalDataClient.settings.get + dailyPlans.get', () => {
  it('settings.get parses synced numeric keys and reads timeZoneId from meta.lastUser', async () => {
    const store = openStore();
    await store.settings.bulkPut([
      settingRow('workStartHour', '8'),
      settingRow('workEndHour', '18'),
      settingRow('timeIncrement', '15'),
    ]);
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'America/New_York' });
    const dc = new LocalDataClient(store, fakeBridge());
    const { settings, timeZoneId } = await dc.settings.get();
    expect(settings).toEqual({ workStartHour: 8, workEndHour: 18, timeIncrement: 15 });
    expect(timeZoneId).toBe('America/New_York');
  });

  it('settings.get defaults timeZoneId to UTC when meta.lastUser is absent', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    expect((await dc.settings.get()).timeZoneId).toBe('UTC');
  });

  it('dailyPlans.get returns the mapped plan or null', async () => {
    const store = openStore();
    await store.dailyPlans.put(planRow('2026-06-11', { plannedMinutes: 120, plannedTaskIds: ['t1'] }));
    const dc = new LocalDataClient(store, fakeBridge());
    const plan = await dc.dailyPlans.get('2026-06-11');
    expect(plan).toEqual({
      date: '2026-06-11',
      committedAt: '2026-06-11T08:00:00Z',
      plannedTaskIds: ['t1'],
      plannedMinutes: 120,
    });
    expect(await dc.dailyPlans.get('2026-06-12')).toBeNull();
  });
});
