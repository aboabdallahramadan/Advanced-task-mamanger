import { describe, it, expect } from 'vitest';
import { FakeSyncServer } from './fakeSyncServer';
import type { SyncOp } from '../types';

function op(o: Partial<SyncOp> & Pick<SyncOp, 'method' | 'path'>): SyncOp {
  return { entityKeys: [], kind: 'other', attempts: 0, ...o };
}

function taskBody(id: string, over: Record<string, unknown> = {}) {
  return {
    id, title: `t-${id}`, notes: '', projectId: null, labels: [], source: 'manual',
    status: 'Inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
    durationMinutes: 0, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
    rank: 'a0', dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
    createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z', ...over,
  };
}

describe('FakeSyncServer transport — REST semantics', () => {
  it('POST create is PK-idempotent: re-send returns 200 + existing row, no duplicate', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    const r1 = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    expect(r1.status).toBe(201);
    const r2 = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    expect(r2.status).toBe(200);
    const page = await t.pull(0, 500);
    expect(page.changes.tasks.filter((x) => x.id === 't1')).toHaveLength(1);
  });

  it('POST project with a duplicate name → 409 + extensions.existingId', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/projects',
      body: { id: 'p1', name: 'Inbox-Zero', color: '#fff', emoji: '📁', rank: 'a0' }, kind: 'create' }));
    const dup = await t.send(op({ method: 'POST', path: '/api/v1/projects',
      body: { id: 'p2', name: 'Inbox-Zero', color: '#000', emoji: '📦', rank: 'a1' }, kind: 'create' }));
    expect(dup.status).toBe(409);
    expect((dup.body as { extensions: { existingId: string } }).extensions.existingId).toBe('p1');
  });

  it('PATCH field-merges and bumps changeSeq; 404 on a missing target', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1', { title: 'orig' }), kind: 'create' }));
    const r = await t.send(op({ method: 'PATCH', path: '/api/v1/tasks/t1', body: { title: 'edited' } }));
    expect(r.status).toBe(200);
    const page = await t.pull(0, 500);
    const row = page.changes.tasks.find((x) => x.id === 't1')!;
    expect(row.title).toBe('edited');
    expect(row.changeSeq).toBeGreaterThan(1);

    const miss = await t.send(op({ method: 'PATCH', path: '/api/v1/tasks/absent', body: { title: 'x' } }));
    expect(miss.status).toBe(404);
  });

  it('DELETE tombstones + cascades to subtasks; pull includes the tombstones', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    await t.send(op({ method: 'POST', path: '/api/v1/tasks/t1/subtasks',
      body: { id: 's1', taskId: 't1', title: 'sub', completed: false, sortOrder: 0 }, kind: 'create' }));
    const del = await t.send(op({ method: 'DELETE', path: '/api/v1/tasks/t1' }));
    expect(del.status).toBe(204);
    const page = await t.pull(0, 500);
    expect(page.changes.tasks.find((x) => x.id === 't1')!.deletedAt).not.toBeNull();
    expect(page.changes.subtasks.find((x) => x.id === 's1')!.deletedAt).not.toBeNull();
    const reDel = await t.send(op({ method: 'DELETE', path: '/api/v1/tasks/t1' }));
    expect(reDel.status).toBe(404); // already tombstoned
  });

  it('reorder writes ranks; PUT daily-plan re-stamps committedAt; PUT settings is per-key', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1', { rank: 'a0' }), kind: 'create' }));
    await t.send(op({ method: 'PATCH', path: '/api/v1/tasks/reorder', body: [{ id: 't1', rank: 'b5' }] }));
    let page = await t.pull(0, 500);
    expect(page.changes.tasks.find((x) => x.id === 't1')!.rank).toBe('b5');

    await t.send(op({ method: 'PUT', path: '/api/v1/daily-plans/2026-06-11',
      body: { plannedTaskIds: ['t1'], plannedMinutes: 30 } }));
    page = await t.pull(0, 500);
    const plan = page.changes.dailyPlans.find((p) => p.date === '2026-06-11')!;
    expect(plan.plannedTaskIds).toEqual(['t1']);
    expect(plan.committedAt).toBeTruthy();

    await t.send(op({ method: 'PUT', path: '/api/v1/settings',
      body: { settings: { workStartHour: '6' } } }));
    page = await t.pull(0, 500);
    expect(page.changes.settings.find((x) => x.key === 'workStartHour')!.value).toBe('6');
  });

  it('recurrence: POST stores rule+template with zero instances; ensureInstances materializes them at a0', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/recurrence', kind: 'create', regenAfterPush: true,
      body: { task: taskBody('tmpl', { isRecurrenceTemplate: true, recurrenceRuleId: 'r1' }),
              rule: { id: 'r1', frequency: 'Daily', interval: 1, daysOfWeek: [], endType: 'Never',
                      endCount: null, endDate: null } } }));
    let page = await t.pull(0, 500);
    expect(page.changes.tasks.filter((x) => x.recurrenceRuleId === 'r1' && !x.isRecurrenceTemplate)).toHaveLength(0);
    expect(page.changes.recurrenceRules.find((x) => x.id === 'r1')).toBeDefined();

    const created = await t.ensureInstances('2026-06-01', '2026-06-14');
    expect(created.length).toBeGreaterThanOrEqual(7);
    expect(created.every((r) => r.rank === 'a0')).toBe(true);
    page = await t.pull(0, 500);
    expect(
      page.changes.tasks.filter((x) => x.recurrenceRuleId === 'r1' && !x.isRecurrenceTemplate).length,
    ).toBeGreaterThanOrEqual(7);
  });

  it('pull paginates by changeSeq with nextSince + hasMore; seed stamps the next seq', async () => {
    const s = new FakeSyncServer();
    s.seed('tasks', taskBody('a'));
    s.seed('tasks', taskBody('b'));
    s.seed('tasks', taskBody('c'));
    const t = s.transport();
    const p1 = await t.pull(0, 2);
    expect(p1.changes.tasks).toHaveLength(2);
    expect(p1.hasMore).toBe(true);
    const p2 = await t.pull(p1.nextSince, 2);
    expect(p2.changes.tasks).toHaveLength(1);
    expect(p2.hasMore).toBe(false);
  });

  it('failNext injects a status fault then clears; latency hook is awaited', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    s.failNext((o) => o.method === 'POST', { status: 503 });
    const r1 = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    expect(r1.status).toBe(503);
    const r2 = await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    expect(r2.status).toBe(201); // fault consumed (next-only)

    s.failNext(() => true, 'network');
    await expect(t.send(op({ method: 'PATCH', path: '/api/v1/tasks/t1', body: { title: 'z' } }))).rejects.toThrow();
  });

  it('server mutators: tombstone soft-deletes + cascades, bumpSeq advances a row, pullCount + latency work', async () => {
    const s = new FakeSyncServer();
    const t = s.transport();
    await t.send(op({ method: 'POST', path: '/api/v1/tasks', body: taskBody('t1'), kind: 'create' }));
    await t.send(op({ method: 'POST', path: '/api/v1/tasks/t1/subtasks',
      body: { id: 's1', taskId: 't1', title: 'sub', completed: false, sortOrder: 0 }, kind: 'create' }));

    // pullCount starts at 0, counts each served pull.
    expect(s.pullCount()).toBe(0);
    const before = await t.pull(0, 500);
    expect(s.pullCount()).toBe(1);
    const baseSeq = before.changes.tasks.find((x) => x.id === 't1')!.changeSeq;

    // bumpSeq advances the row's changeSeq (and applies the patch) without an op.
    s.bumpSeq('tasks', 't1', { title: 'remote edit' });
    const afterBump = await t.pull(0, 500);
    expect(s.pullCount()).toBe(2);
    const bumped = afterBump.changes.tasks.find((x) => x.id === 't1')!;
    expect(bumped.title).toBe('remote edit');
    expect(bumped.changeSeq).toBeGreaterThan(baseSeq);

    // tombstone soft-deletes the task and cascades to the subtask.
    s.tombstone('tasks', 't1');
    const afterTs = await t.pull(0, 500);
    expect(afterTs.changes.tasks.find((x) => x.id === 't1')!.deletedAt).not.toBeNull();
    expect(afterTs.changes.subtasks.find((x) => x.id === 's1')!.deletedAt).not.toBeNull();

    // latency(ms) makes calls take measurable time.
    s.latency(20);
    const t0 = Date.now();
    await t.pull(0, 500);
    expect(Date.now() - t0).toBeGreaterThanOrEqual(15);
    expect(s.pullCount()).toBe(4);
  });
});
