import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import {
  openStore,
  closeAll,
  fakeBridge,
  taskRow,
  projectRow,
  noteGroupRow,
  noteRow,
  subtaskRow,
} from './helpers';

afterEach(closeAll);

describe('LocalDataClient.tasks.update (true PATCH presence)', () => {
  it('enqueues only changed keys and updates only those columns', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1', { title: 'old', notes: 'keep', durationMinutes: 30 }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);

    const updated = await dc.tasks.update('t1', { title: 'new', durationMinutes: 30 });

    expect(updated.title).toBe('new');
    const row = await store.tasks.get('t1');
    expect(row!.title).toBe('new');
    expect(row!.notes).toBe('keep');
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/tasks/t1');
    expect(op.kind).toBe('other');
    expect(op.entityKeys).toEqual(['tasks:t1']);
    expect(op.body).toEqual({ title: 'new' }); // durationMinutes unchanged → omitted
    expect(bridge.nudges).toBe(1);
  });

  it('case-folds status and sends only it', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1', { status: 'Planned' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.tasks.update('t1', { status: 'done' });
    expect((await store.tasks.get('t1'))!.status).toBe('Done');
    expect((await store.ops.toArray())[0].body).toEqual({ status: 'Done' });
  });

  it('clears a field to null when the patch sets it null (SF-2)', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1', { projectId: 'P', plannedDate: '2026-06-11' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.tasks.update('t1', { projectId: null });
    expect((await store.tasks.get('t1'))!.projectId).toBeNull();
    expect((await store.ops.toArray())[0].body).toEqual({ projectId: null });
  });

  it('no-op diff queues nothing and does not nudge', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1', { title: 'same' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.tasks.update('t1', { title: 'same' });
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});

describe('LocalDataClient.projects/noteGroups/notes.update (null=unchanged convention)', () => {
  it('projects.update: changed key real value, all others null, rank null', async () => {
    const store = openStore();
    await store.projects.put(projectRow('p1', { name: 'old', color: '#111111', emoji: '📁' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.projects.update('p1', { name: 'New' });
    expect((await store.projects.get('p1'))!.name).toBe('New');
    expect((await store.projects.get('p1'))!.color).toBe('#111111');
    const op = (await store.ops.toArray())[0];
    expect(op.body).toEqual({ name: 'New', color: null, emoji: null, rank: null });
  });

  it('noteGroups.update: changed projectId, others null', async () => {
    const store = openStore();
    await store.noteGroups.put(noteGroupRow('g1', { projectId: null, name: 'G' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.noteGroups.update('g1', { projectId: 'P' });
    expect((await store.noteGroups.get('g1'))!.projectId).toBe('P');
    const op = (await store.ops.toArray())[0];
    expect(op.body).toEqual({ name: null, emoji: null, projectId: 'P', rank: null });
  });

  it('notes.update: changed content, others null', async () => {
    const store = openStore();
    await store.notes.put(noteRow('n1', { content: 'a', title: 'T' }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.notes.update('n1', { content: 'b' });
    expect((await store.notes.get('n1'))!.content).toBe('b');
    const op = (await store.ops.toArray())[0];
    expect(op.body).toEqual({ groupId: null, projectId: null, title: null, content: 'b', rank: null });
  });

  it('projects.update no-op diff queues nothing', async () => {
    const store = openStore();
    await store.projects.put(projectRow('p1', { name: 'same' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.projects.update('p1', { name: 'same' });
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});

describe('LocalDataClient.subtasks.update (order→sortOrder)', () => {
  it('maps order to sortOrder and sends only changed fields', async () => {
    const store = openStore();
    await store.subtasks.put(subtaskRow('s1', 't1', { title: 'a', completed: false, sortOrder: 0 }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.subtasks.update('s1', { completed: true, order: 2 });
    const row = await store.subtasks.get('s1');
    expect(row!.completed).toBe(true);
    expect(Number(row!.sortOrder)).toBe(2);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/subtasks/s1');
    expect(op.body).toEqual({ completed: true, sortOrder: 2 });
  });

  it('subtasks.update no-op diff queues nothing', async () => {
    const store = openStore();
    await store.subtasks.put(subtaskRow('s1', 't1', { title: 'a' }));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.subtasks.update('s1', { title: 'a' });
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});
