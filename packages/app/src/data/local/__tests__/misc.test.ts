import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, planRow, settingRow } from './helpers';

afterEach(closeAll);

describe('LocalDataClient.dailyPlans.upsert', () => {
  it('writes the row keyed by date, stamps committedAt locally, queues a PUT', async () => {
    const store = openStore();
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    const plan = await dc.dailyPlans.upsert({
      date: '2026-06-11',
      plannedTaskIds: ['t1', 't2'],
      plannedMinutes: 90,
    });
    expect(plan.date).toBe('2026-06-11');
    expect(plan.plannedTaskIds).toEqual(['t1', 't2']);
    expect(typeof plan.committedAt).toBe('string');
    const row = await store.dailyPlans.get('2026-06-11');
    expect(row!.plannedMinutes).toBe(90);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PUT');
    expect(op.path).toBe('/api/v1/daily-plans/2026-06-11');
    expect(op.body).toEqual({ plannedTaskIds: ['t1', 't2'], plannedMinutes: 90 });
    expect(op.entityKeys).toEqual(['dailyPlans:2026-06-11']);
    expect(bridge.nudges).toBe(1);
  });

  it('overwrites the existing plan for the same date', async () => {
    const store = openStore();
    await store.dailyPlans.put(planRow('2026-06-11', { plannedMinutes: 10 }));
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.dailyPlans.upsert({ date: '2026-06-11', plannedTaskIds: [], plannedMinutes: 200 });
    expect((await store.dailyPlans.get('2026-06-11'))!.plannedMinutes).toBe(200);
  });
});

describe('LocalDataClient.settings.save (diffed PUT)', () => {
  it('sends only changed keys; updates local rows', async () => {
    const store = openStore();
    await store.settings.bulkPut([settingRow('workStartHour', '8'), settingRow('workEndHour', '18')]);
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.settings.save({ workStartHour: 9, workEndHour: 18 });
    expect((await store.settings.get('workStartHour'))!.value).toBe('9');
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PUT');
    expect(op.path).toBe('/api/v1/settings');
    expect(op.body).toEqual({ settings: { workStartHour: '9' } }); // only the changed key
    expect(op.entityKeys).toEqual(['settings:workStartHour']);
    expect(bridge.nudges).toBe(1);
  });

  it('includes timeZoneId only when changed and updates meta.lastUser', async () => {
    const store = openStore();
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
    const dc = new LocalDataClient(store, fakeBridge());
    await dc.settings.save({ workStartHour: 8 }, 'America/New_York');
    const op = (await store.ops.toArray())[0];
    expect(op.body).toEqual({
      settings: { workStartHour: '8' },
      timeZoneId: 'America/New_York',
    });
    const lastUser = await store.getMeta<{ timeZoneId: string }>('lastUser');
    expect(lastUser!.timeZoneId).toBe('America/New_York');
  });

  it('no-op save (nothing changed, same timeZoneId) queues nothing', async () => {
    const store = openStore();
    await store.settings.put(settingRow('workStartHour', '8'));
    await store.setMeta('lastUser', { id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.settings.save({ workStartHour: 8 }, 'UTC');
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});

describe('LocalDataClient.focusSessions.add', () => {
  it('inserts a row with a client id and queues a create op', async () => {
    const store = openStore();
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    const s = await dc.focusSessions.add({
      taskId: null,
      project: 'Work',
      startedAt: '2026-06-11T09:00:00Z',
      endedAt: '2026-06-11T09:25:00Z',
      minutes: 25,
      date: '2026-06-11',
    });
    expect(s.id).toBeTruthy();
    expect(s.project).toBe('Work');
    const row = await store.focusSessions.get(s.id);
    expect(row!.minutes).toBe(25);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('POST');
    expect(op.path).toBe('/api/v1/focus-sessions');
    expect(op.kind).toBe('create');
    expect(op.entityKeys).toEqual([`focusSessions:${s.id}`]);
    expect(op.body).toEqual({
      id: s.id,
      taskId: null,
      project: 'Work',
      startedAt: '2026-06-11T09:00:00Z',
      endedAt: '2026-06-11T09:25:00Z',
      minutes: 25,
      date: '2026-06-11',
    });
    expect(bridge.nudges).toBe(1);
  });
});
