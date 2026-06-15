import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import {
  openStore,
  closeAll,
  fakeBridge,
  taskRow,
  subtaskRow,
  projectRow,
  noteGroupRow,
  noteRow,
} from './helpers';

afterEach(closeAll);

describe('LocalDataClient.tasks.delete (cascade subtasks locally)', () => {
  it('removes the task + its subtasks; op DELETE lists all affected keys', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('t1'));
    await store.subtasks.bulkPut([subtaskRow('s1', 't1'), subtaskRow('s2', 't1')]);
    await store.subtasks.put(subtaskRow('sx', 'other'));
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);

    await dc.tasks.delete('t1');

    expect(await store.tasks.get('t1')).toBeUndefined();
    expect(await store.subtasks.where('taskId').equals('t1').count()).toBe(0);
    expect(await store.subtasks.get('sx')).toBeTruthy(); // foreign subtask untouched
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('DELETE');
    expect(op.path).toBe('/api/v1/tasks/t1');
    expect(new Set(op.entityKeys)).toEqual(new Set(['tasks:t1', 'subtasks:s1', 'subtasks:s2']));
    expect(bridge.nudges).toBe(1);
  });
});

describe('LocalDataClient.noteGroups.delete (cascade notes locally)', () => {
  it('removes the group + its notes; op lists all keys', async () => {
    const store = openStore();
    await store.noteGroups.put(noteGroupRow('g1'));
    await store.notes.bulkPut([noteRow('n1', { groupId: 'g1' }), noteRow('n2', { groupId: 'g1' })]);
    await store.notes.put(noteRow('n3', { groupId: 'other' }));
    const dc = new LocalDataClient(store, fakeBridge());

    await dc.noteGroups.delete('g1');

    expect(await store.noteGroups.get('g1')).toBeUndefined();
    expect(await store.notes.where('groupId').equals('g1').count()).toBe(0);
    expect(await store.notes.get('n3')).toBeTruthy();
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('DELETE');
    expect(op.path).toBe('/api/v1/note-groups/g1');
    expect(new Set(op.entityKeys)).toEqual(new Set(['noteGroups:g1', 'notes:n1', 'notes:n2']));
  });
});

describe('LocalDataClient.projects.delete (set-null cascade locally)', () => {
  it('nulls projectId on tasks/notes/noteGroups; op lists every reparented key', async () => {
    const store = openStore();
    await store.projects.put(projectRow('p1'));
    await store.tasks.put(taskRow('t1', { projectId: 'p1' }));
    await store.notes.put(noteRow('n1', { projectId: 'p1' }));
    await store.noteGroups.put(noteGroupRow('g1', { projectId: 'p1' }));
    await store.tasks.put(taskRow('t2', { projectId: 'other' }));
    const dc = new LocalDataClient(store, fakeBridge());

    await dc.projects.delete('p1');

    expect(await store.projects.get('p1')).toBeUndefined();
    expect((await store.tasks.get('t1'))!.projectId).toBeNull();
    expect((await store.notes.get('n1'))!.projectId).toBeNull();
    expect((await store.noteGroups.get('g1'))!.projectId).toBeNull();
    expect((await store.tasks.get('t2'))!.projectId).toBe('other'); // unaffected
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('DELETE');
    expect(op.path).toBe('/api/v1/projects/p1');
    expect(new Set(op.entityKeys)).toEqual(
      new Set(['projects:p1', 'tasks:t1', 'notes:n1', 'noteGroups:g1']),
    );
  });
});

describe('LocalDataClient.notes.delete / subtasks.delete', () => {
  it('notes.delete removes the row + queues DELETE', async () => {
    const store = openStore();
    await store.notes.put(noteRow('n1'));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.notes.delete('n1');
    expect(await store.notes.get('n1')).toBeUndefined();
    const op = (await store.ops.toArray())[0];
    expect(op.path).toBe('/api/v1/notes/n1');
    expect(op.entityKeys).toEqual(['notes:n1']);
  });

  it('subtasks.delete removes the row + queues DELETE', async () => {
    const store = openStore();
    await store.subtasks.put(subtaskRow('s1', 't1'));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.subtasks.delete('s1');
    expect(await store.subtasks.get('s1')).toBeUndefined();
    const op = (await store.ops.toArray())[0];
    expect(op.path).toBe('/api/v1/subtasks/s1');
    expect(op.entityKeys).toEqual(['subtasks:s1']);
  });
});
