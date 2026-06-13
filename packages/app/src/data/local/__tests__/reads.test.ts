import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, taskRow, subtaskRow } from './helpers';

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
