// Two-device convergence: the SP3 marquee suite (spec §11, AC4/AC5).
// Each test interleaves offline edits on devices A and B of the same user and
// asserts both converge to the identical state after their cycles run.
import 'fake-indexeddb/auto';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { LocalStore } from '../../data/local/LocalStore';
import { LocalDataClient } from '../../data/local/LocalDataClient';
import { SyncEngine } from '../SyncEngine';
import { FakeSyncServer } from './fakeSyncServer';
import type { DataClient } from '../../data/DataClient';
import type { SyncIssue } from '../types';
import type { Task } from '../../types';

interface Device {
  store: LocalStore;
  client: DataClient;
  engine: SyncEngine;
  /** Run one full push→(ensure-instances)→pull cycle. Engines are never start()ed here. */
  sync: () => Promise<void>;
  issues: () => Promise<SyncIssue[]>;
}

const openStores: LocalStore[] = [];

function makeDevice(userId: string, server: FakeSyncServer): Device {
  // Unique Dexie DB per device — two devices of the same user must NOT share local state.
  const store = LocalStore.open(`${userId}-${Math.random().toString(36).slice(2, 10)}`);
  openStores.push(store);
  const engine = new SyncEngine({ store, transport: server.transport() });
  const client = new LocalDataClient(store, {
    nudge: () => {}, // cycles are driven manually via sync(); no debounced auto-push in tests
    online: () => true,
    ensureInstances: (start: string, end: string) => engine.ensureInstances(start, end),
  });
  return {
    store,
    client,
    engine,
    sync: () => engine.syncNow(),
    issues: () => store.table('issues').toArray() as Promise<SyncIssue[]>,
  };
}

/** Server sync-DTO wire shape (TaskSyncRow = TaskResponse minus subtasks, plus deletedAt).
 *  changeSeq is stamped by FakeSyncServer.seed. */
function serverTaskRow(id: string, rank: string): Record<string, unknown> {
  return {
    id, title: `task-${id.slice(0, 8)}`, notes: '', projectId: null, labels: [],
    source: 'manual', status: 'Inbox', plannedDate: null, scheduledStart: null,
    scheduledEnd: null, durationMinutes: 0, actualTimeMinutes: 0, priority: null,
    reminderMinutes: null, rank, dueDate: null, recurrenceRuleId: null,
    isRecurrenceTemplate: false, recurrenceDetached: false, recurrenceOriginalDate: null,
    completedAt: null, createdAt: '2026-06-01T00:00:00Z', updatedAt: '2026-06-01T00:00:00Z',
    deletedAt: null,
  };
}

async function taskOn(d: Device, id: string): Promise<Task | undefined> {
  return (await d.client.tasks.getAll()).find((t) => t.id === id);
}

describe('two-device convergence (LocalStore + LocalDataClient + SyncEngine over one fake server)', () => {
  let server: FakeSyncServer;

  beforeEach(() => {
    server = new FakeSyncServer();
  });

  afterEach(() => {
    for (const s of openStores.splice(0)) s.close();
  });

  it('(a) different-field offline edits merge via diff-at-enqueue: A title + B plannedDate both survive on both', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    const created = await a.client.tasks.create({ title: 'Draft' });
    await a.sync(); // create lands on the server
    await b.sync(); // B pulls it

    // Interleaved offline edits — no cycle runs between the two writes.
    await a.client.tasks.update(created.id, { title: 'Final title' });
    await b.client.tasks.update(created.id, { plannedDate: '2026-06-12' });

    await a.sync(); // pushes ONLY { title } (diff-at-enqueue)
    await b.sync(); // pushes ONLY { plannedDate } — must not carry B's stale title — then pulls A's title
    await a.sync(); // A pulls B's plannedDate

    for (const d of [a, b]) {
      const t = await taskOn(d, created.id);
      expect(t).toBeDefined();
      expect(t!.title).toBe('Final title');       // A's edit survived B's push
      expect(t!.plannedDate).toBe('2026-06-12');  // B's edit survived A's push
    }
  });

  it('(b) same-field offline edits: later arrival at the server wins on both (arrival-LWW)', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    const created = await a.client.tasks.create({ title: 'Original' });
    await a.sync();
    await b.sync();

    await a.client.tasks.update(created.id, { title: 'from-A' });
    await b.client.tasks.update(created.id, { title: 'from-B' });

    await a.sync(); // 'from-A' arrives first
    await b.sync(); // 'from-B' arrives later → wins the field
    await a.sync(); // A pulls the winner

    expect((await taskOn(a, created.id))!.title).toBe('from-B');
    expect((await taskOn(b, created.id))!.title).toBe('from-B');
  });

  it('(c) edit-vs-delete: delete wins — 404 drops the edit, recovery pull removes the row on A, issue recorded', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    const created = await a.client.tasks.create({ title: 'Doomed' });
    await a.sync();
    await b.sync();

    await a.client.tasks.update(created.id, { title: 'edited while B deletes' }); // A offline edit
    await b.client.tasks.delete(created.id); // B offline delete

    await b.sync(); // server tombstones the task
    await a.sync(); // A's PATCH → 404 → drop op + record issue + recovery pull from since=0 removes the row

    expect(await taskOn(a, created.id)).toBeUndefined();
    expect(await taskOn(b, created.id)).toBeUndefined();

    const issues = await a.issues();
    expect(issues).toHaveLength(1);
    expect(issues[0].status).toBe('dropped');
    expect(issues[0].reason).toContain('404');
    expect(issues[0].op.method).toBe('PATCH');
    expect(await b.issues()).toEqual([]); // B's delete replayed cleanly
  });

  it('(d) daily plans: whole-day LWW — the later-arriving PUT replaces the day on both (no merge)', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    const t1 = await a.client.tasks.create({ title: 'plan-A task' });
    const t2 = await a.client.tasks.create({ title: 'plan-B task' });
    await a.sync();
    await b.sync();

    await a.client.dailyPlans.upsert({ date: '2026-06-11', plannedTaskIds: [t1.id], plannedMinutes: 60 });
    await b.client.dailyPlans.upsert({ date: '2026-06-11', plannedTaskIds: [t2.id], plannedMinutes: 30 });

    await a.sync(); // A's day arrives first
    await b.sync(); // B's day arrives later → whole-day replace wins
    await a.sync(); // A pulls the winner

    for (const d of [a, b]) {
      const plan = await d.client.dailyPlans.get('2026-06-11');
      expect(plan).not.toBeNull();
      expect(plan!.plannedTaskIds).toEqual([t2.id]); // NOT merged with [t1.id]
      expect(plan!.plannedMinutes).toBe(30);
    }
  });

  it('(e) settings: per-key LWW — A changes workStartHour, B changes workEndHour, both survive on both', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);

    // Baseline rows on the server and on both devices.
    await a.client.settings.save({ workStartHour: 8, workEndHour: 20, timeIncrement: 15 });
    await a.sync();
    await b.sync();

    // Whole-map saves (the store's real call shape) — diff-at-enqueue must reduce each
    // to a single-key PUT, or one device's save would clobber the other's key.
    await a.client.settings.save({ workStartHour: 6, workEndHour: 20, timeIncrement: 15 });
    await b.client.settings.save({ workStartHour: 8, workEndHour: 22, timeIncrement: 15 });

    await a.sync();
    await b.sync();
    await a.sync();

    for (const d of [a, b]) {
      const { settings } = await d.client.settings.get();
      expect(settings.workStartHour).toBe(6);  // A's key survived B's save
      expect(settings.workEndHour).toBe(22);   // B's key survived A's save
      expect(settings.timeIncrement).toBe(15); // untouched key unchanged
    }
  });

  it("(f) interleaved offline reorders seeded with tied 'a0' ranks converge to an identical (rank, id) order", async () => {
    const T1 = '11111111-1111-4111-8111-111111111111';
    const T2 = '22222222-2222-4222-8222-222222222222';
    const T3 = '33333333-3333-4333-8333-333333333333';
    // The real degenerate population: every recurrence instance copies rank 'a0' (spec §5.1).
    server.seed('tasks', serverTaskRow(T1, 'a0'));
    server.seed('tasks', serverTaskRow(T2, 'a0'));
    server.seed('tasks', serverTaskRow(T3, 'a0'));

    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);
    await a.sync();
    await b.sync();

    // Sanity: the tied seed renders in the same deterministic (rank, id) order on both.
    const before = (await a.client.tasks.getAll()).map((t) => t.id);
    expect(before).toEqual([T1, T2, T3]); // equal ranks → ordinal id tie-break
    expect((await b.client.tasks.getAll()).map((t) => t.id)).toEqual(before);

    // Interleaved offline reorders. Tie-aware insertion (§5.1.2) must re-rank the tied
    // run instead of producing a rank that sorts after both 'a0' neighbors.
    await a.client.tasks.reorder([
      { id: T2, order: 0 },
      { id: T1, order: 1 },
      { id: T3, order: 2 },
    ]);
    await b.client.tasks.reorder([
      { id: T3, order: 0 },
      { id: T2, order: 1 },
      { id: T1, order: 2 },
    ]);

    await a.sync();
    await b.sync();
    await a.sync();

    const orderA = (await a.client.tasks.getAll()).map((t) => t.id);
    const orderB = (await b.client.tasks.getAll()).map((t) => t.id);
    expect(orderA).toEqual(orderB);                          // identical (rank, id) order everywhere
    expect([...orderA].sort()).toEqual([T1, T2, T3]);        // same three rows, no dupes/losses
  });

  it('(g) a series created offline gains server-generated instances on both devices after sync (regenAfterPush)', async () => {
    const a = makeDevice('u1', server);
    const b = makeDevice('u1', server);
    await a.sync();
    await b.sync();

    // Created OFFLINE: returns the full local task list (store contract, spec §6) —
    // template present, zero instances until reconnect.
    const listAfterCreate = await a.client.recurrence.create(
      { title: 'Standup', durationMinutes: 15 },
      { frequency: 'daily', interval: 1, daysOfWeek: [], endType: 'never' },
    );
    const template = listAfterCreate.find((t) => t.isRecurrenceTemplate);
    expect(template).toBeDefined();
    expect(template!.recurrenceRuleId).not.toBeNull(); // client rule id (C7.4)
    expect(
      listAfterCreate.filter((t) => t.recurrenceRuleId !== null && !t.isRecurrenceTemplate),
    ).toHaveLength(0);

    await a.sync(); // push POST /recurrence (op carries regenAfterPush) → ensure-instances → pull delivers instances
    await b.sync(); // pull-only: same instances arrive on B

    const instancesOf = (tasks: Task[]) =>
      tasks.filter((t) => t.recurrenceRuleId === template!.recurrenceRuleId && !t.isRecurrenceTemplate);

    const allA = await a.client.tasks.getAll();
    const allB = await b.client.tasks.getAll();
    const instA = instancesOf(allA);
    const instB = instancesOf(allB);

    expect(instA.length).toBeGreaterThanOrEqual(7); // ~14-day horizon, daily rule
    expect(instA.map((t) => t.id).sort()).toEqual(instB.map((t) => t.id).sort());
    expect(allA.filter((t) => t.isRecurrenceTemplate)).toHaveLength(1); // adopt nothing — no ghost/duplicate template
    expect(allB.filter((t) => t.isRecurrenceTemplate)).toHaveLength(1);
    expect(await a.issues()).toEqual([]); // no drops, no adopt-existing rewrites needed
  });
});
