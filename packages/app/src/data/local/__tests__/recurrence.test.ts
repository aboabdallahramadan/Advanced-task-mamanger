import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, taskRow, ruleRow } from './helpers';
import type { RecurrenceRuleInput } from '../../DataClient';

afterEach(closeAll);

const dailyRule: RecurrenceRuleInput = {
  frequency: 'daily',
  interval: 1,
  daysOfWeek: [],
  endType: 'never',
};

describe('LocalDataClient.recurrence.create', () => {
  it('inserts template task + rule, queues POST /recurrence with both ids + regenAfterPush, returns full task list', async () => {
    const store = openStore();
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    const tasks = await dc.recurrence.create(
      { title: 'Standup', plannedDate: '2026-06-11', durationMinutes: 15 },
      dailyRule,
    );
    expect(tasks).toHaveLength(1);
    const tmpl = tasks[0];
    expect(tmpl.title).toBe('Standup');
    expect(tmpl.isRecurrenceTemplate).toBe(true);
    const row = await store.tasks.get(tmpl.id);
    expect(row!.rank).toBe('a0');
    expect(row!.isRecurrenceTemplate).toBe(true);
    expect(row!.recurrenceOriginalDate).toBe('2026-06-11');
    const rules = await store.recurrenceRules.toArray();
    expect(rules).toHaveLength(1);
    expect(row!.recurrenceRuleId).toBe(rules[0].id);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('POST');
    expect(op.path).toBe('/api/v1/recurrence');
    expect(op.kind).toBe('create');
    expect(op.regenAfterPush).toBe(true);
    const body = op.body as { task: Record<string, unknown>; rule: Record<string, unknown> };
    expect(body.task.id).toBe(tmpl.id);
    expect(body.task.title).toBe('Standup');
    expect(body.rule.id).toBe(rules[0].id);
    expect(body.rule.frequency).toBe('Daily');
    expect(new Set(op.entityKeys)).toEqual(
      new Set([`tasks:${tmpl.id}`, `recurrenceRules:${rules[0].id}`]),
    );
    expect(bridge.nudges).toBe(1);
  });
});

describe('LocalDataClient.recurrence.updateSeries', () => {
  it('patches the template + future not-done non-detached instances; queues PATCH series', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R'));
    await store.tasks.bulkPut([
      taskRow('tmpl', { recurrenceRuleId: 'R', isRecurrenceTemplate: true, title: 'old' }),
      taskRow('fut', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Planned', title: 'old' }),
      taskRow('done', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Done', title: 'old' }),
      taskRow('past', { recurrenceRuleId: 'R', plannedDate: '2000-01-01', status: 'Planned', title: 'old' }),
      taskRow('det', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', recurrenceDetached: true, title: 'old' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.updateSeries('R', { title: 'new' });
    expect((await store.tasks.get('tmpl'))!.title).toBe('new');
    expect((await store.tasks.get('fut'))!.title).toBe('new');
    expect((await store.tasks.get('done'))!.title).toBe('old');
    expect((await store.tasks.get('past'))!.title).toBe('old');
    expect((await store.tasks.get('det'))!.title).toBe('old');
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/recurrence/rules/R/series');
    expect(op.regenAfterPush).toBeUndefined();
    expect(op.body).toMatchObject({ title: 'new' });
  });
});

describe('LocalDataClient.recurrence.deleteSeries', () => {
  it('removes the rule + all its tasks; queues DELETE', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R'));
    await store.tasks.bulkPut([
      taskRow('tmpl', { recurrenceRuleId: 'R', isRecurrenceTemplate: true }),
      taskRow('i1', { recurrenceRuleId: 'R' }),
      taskRow('other', { recurrenceRuleId: null }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.deleteSeries('R');
    expect(await store.recurrenceRules.get('R')).toBeUndefined();
    expect(await store.tasks.where('recurrenceRuleId').equals('R').count()).toBe(0);
    expect(await store.tasks.get('other')).toBeTruthy();
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('DELETE');
    expect(op.path).toBe('/api/v1/recurrence/rules/R');
  });
});

describe('LocalDataClient.recurrence.deleteSeriesFuture', () => {
  it('removes future not-done NON-TEMPLATE instances incl. detached; queues POST delete-future', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R'));
    await store.tasks.bulkPut([
      taskRow('tmpl', { recurrenceRuleId: 'R', isRecurrenceTemplate: true, plannedDate: '2999-01-01' }),
      taskRow('fut', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Planned' }),
      taskRow('futDet', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', recurrenceDetached: true }),
      taskRow('futDone', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Done' }),
      taskRow('pastP', { recurrenceRuleId: 'R', plannedDate: '2000-01-01', status: 'Planned' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.deleteSeriesFuture('R', '2026-06-11');
    expect(await store.tasks.get('fut')).toBeUndefined();
    expect(await store.tasks.get('futDet')).toBeUndefined(); // detached included (§6)
    expect(await store.tasks.get('futDone')).toBeTruthy(); // done kept
    expect(await store.tasks.get('tmpl')).toBeTruthy(); // template kept
    expect(await store.tasks.get('pastP')).toBeTruthy(); // past kept
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('POST');
    expect(op.path).toBe('/api/v1/recurrence/rules/R/delete-future');
    expect(op.body).toEqual({ fromDate: '2026-06-11' });
  });
});

describe('LocalDataClient.recurrence.updateRule', () => {
  it('updates the rule row + removes future non-detached not-done instances; PATCH rules/{id} regenAfterPush', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R', { interval: 1 }));
    await store.tasks.bulkPut([
      taskRow('tmpl', { recurrenceRuleId: 'R', isRecurrenceTemplate: true, plannedDate: '2999-01-01' }),
      taskRow('fut', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Planned' }),
      taskRow('futDet', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', recurrenceDetached: true }),
      taskRow('futDone', { recurrenceRuleId: 'R', plannedDate: '2999-01-01', status: 'Done' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.updateRule('R', { ...dailyRule, interval: 3 });
    expect(Number((await store.recurrenceRules.get('R'))!.interval)).toBe(3);
    expect(await store.tasks.get('fut')).toBeUndefined();
    expect(await store.tasks.get('futDet')).toBeTruthy(); // detached kept (non-detached filter)
    expect(await store.tasks.get('futDone')).toBeTruthy(); // done kept
    expect(await store.tasks.get('tmpl')).toBeTruthy();
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/recurrence/rules/R');
    expect(op.regenAfterPush).toBe(true);
    expect(op.body).toMatchObject({ frequency: 'Daily', interval: 3 });
  });
});

describe('LocalDataClient.recurrence.detachInstance + getRule', () => {
  it('detachInstance sets recurrenceDetached + queues PATCH detach', async () => {
    const store = openStore();
    await store.tasks.put(taskRow('i1', { recurrenceRuleId: 'R', recurrenceDetached: false }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.recurrence.detachInstance('i1');
    expect((await store.tasks.get('i1'))!.recurrenceDetached).toBe(true);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/recurrence/instances/i1/detach');
    expect(op.entityKeys).toEqual(['tasks:i1']);
  });

  it('getRule reads the local rule or null', async () => {
    const store = openStore();
    await store.recurrenceRules.put(ruleRow('R', { frequency: 'Weekly', interval: 2 }));
    const dc = new LocalDataClient(store, fakeBridge());
    const rule = await dc.recurrence.getRule('R');
    expect(rule!.frequency).toBe('weekly');
    expect(rule!.interval).toBe(2);
    expect(await dc.recurrence.getRule('absent')).toBeNull();
  });
});

describe('LocalDataClient.recurrence.ensureInstances (read-through)', () => {
  it('offline returns [] and writes nothing', async () => {
    const store = openStore();
    const dc = new LocalDataClient(store, fakeBridge({ online: false }));
    expect(await dc.recurrence.ensureInstances('2026-06-11', '2026-06-25')).toEqual([]);
    expect(await store.tasks.count()).toBe(0);
  });

  it('online upserts returned rows locally and returns them mapped', async () => {
    const store = openStore();
    const returned = [taskRow('gen1', { rank: 'a0' }), taskRow('gen2', { rank: 'a0' })];
    const bridge = fakeBridge({
      online: true,
      ensureInstances: async () => returned,
    });
    const dc = new LocalDataClient(store, bridge);
    const got = await dc.recurrence.ensureInstances('2026-06-11', '2026-06-25');
    expect(got.map((t) => t.id).sort()).toEqual(['gen1', 'gen2']);
    expect(await store.tasks.get('gen1')).toBeTruthy();
    expect(await store.tasks.get('gen2')).toBeTruthy();
  });
});
