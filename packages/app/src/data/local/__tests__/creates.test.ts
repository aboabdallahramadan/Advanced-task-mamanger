import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, taskRow, projectRow } from './helpers';
import { rankAfter } from '../../ranking';

afterEach(closeAll);

describe('LocalDataClient.tasks.create', () => {
  it('inserts a synthesized row, appends rank after the local max, enqueues a create op, nudges', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('seed', { rank: 'n' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);

    const created = await dc.tasks.create({ title: 'Buy milk', durationMinutes: 45 });

    expect(created.title).toBe('Buy milk');
    expect(created.durationMinutes).toBe(45);
    const row = await store.tasks.get(created.id);
    expect(row).toBeTruthy();
    expect(row!.rank).toBe(rankAfter('n')); // append after the seed
    expect(row!.changeSeq).toBe(0);
    expect(row!.deletedAt).toBeNull();
    expect(row!.status).toBe('Planned'); // domain default 'planned' → server 'Planned'

    const ops = await store.ops.toArray();
    expect(ops).toHaveLength(1);
    expect(ops[0].method).toBe('POST');
    expect(ops[0].path).toBe('/api/v1/tasks');
    expect(ops[0].kind).toBe('create');
    expect(ops[0].entityKeys).toEqual([`tasks:${created.id}`]);
    const body = ops[0].body as Record<string, unknown>;
    expect(body.id).toBe(created.id);
    expect(body.rank).toBe(row!.rank);
    expect(body.title).toBe('Buy milk');
    expect(body.durationMinutes).toBe(45);
    expect(bridge.nudges).toBe(1);
  });

  it('seeds rank "n" for the first task in an empty container', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    const created = await dc.tasks.create({ title: 'first' });
    expect((await store.tasks.get(created.id))!.rank).toBe('n');
  });
});

describe('LocalDataClient.projects.create', () => {
  it('synthesizes defaults (color/emoji) matching HttpDataClient and queues the create', async () => {
    const store = openStore();
    await store.projects.put(projectRow('seed', { rank: 'n' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    const created = await dc.projects.create({ name: 'Work' });
    expect(created.name).toBe('Work');
    expect(created.color).toBe('#6366f1');
    expect(created.emoji).toBe('📁');
    const ops = await store.ops.toArray();
    expect(ops[0].path).toBe('/api/v1/projects');
    const body = ops[0].body as Record<string, unknown>;
    expect(body).toEqual({
      name: 'Work',
      color: '#6366f1',
      emoji: '📁',
      rank: rankAfter('n'),
      id: created.id,
    });
    expect(ops[0].entityKeys).toEqual([`projects:${created.id}`]);
    expect(bridge.nudges).toBe(1);
  });
});

describe('LocalDataClient.noteGroups.create / notes.create', () => {
  it('noteGroups.create queues id + rank + null emoji/projectId defaults', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    const g = await dc.noteGroups.create({ name: 'Ideas' });
    const op = (await store.ops.toArray())[0];
    expect(op.path).toBe('/api/v1/note-groups');
    expect(op.body).toEqual({ name: 'Ideas', emoji: null, projectId: null, rank: 'n', id: g.id });
  });

  it('notes.create queues null group/project, empty title default, null content', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    const n = await dc.notes.create({});
    const op = (await store.ops.toArray())[0];
    expect(op.path).toBe('/api/v1/notes');
    expect(op.body).toEqual({
      groupId: null,
      projectId: null,
      title: '',
      content: null,
      rank: 'n',
      id: n.id,
    });
  });
});

describe('LocalDataClient.subtasks.create', () => {
  it('inserts a subtask row with client id + sortOrder after the local max, queues POST under the parent', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1'));
    const dc = new LocalDataClient(store, fakeBridge());
    const s1 = await dc.subtasks.create('t1', 'first');
    const s2 = await dc.subtasks.create('t1', 'second');
    const rows = await store.subtasks.where('taskId').equals('t1').sortBy('sortOrder');
    expect(rows.map((r) => r.title)).toEqual(['first', 'second']);
    expect(rows.map((r) => Number(r.sortOrder))).toEqual([0, 1]);
    const ops = await store.ops.toArray();
    expect(ops[0].method).toBe('POST');
    expect(ops[0].path).toBe('/api/v1/tasks/t1/subtasks');
    expect(ops[0].body).toEqual({ id: s1.id, title: 'first' });
    expect(ops[0].entityKeys).toEqual([`subtasks:${s1.id}`]);
    expect(ops[1].body).toEqual({ id: s2.id, title: 'second' });
  });
});

describe('create atomicity', () => {
  it('rolls back the row when the op append fails (transaction abort)', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge());
    // Force the ops table write to throw mid-transaction.
    const orig = store.ops.add.bind(store.ops);
    (store.ops as unknown as { add: unknown }).add = () => {
      throw new Error('boom');
    };
    await expect(dc.tasks.create({ title: 'doomed' })).rejects.toThrow('boom');
    (store.ops as unknown as { add: typeof orig }).add = orig;
    expect(await store.tasks.count()).toBe(0); // row rolled back with the op
    expect(await store.ops.count()).toBe(0);
  });
});
