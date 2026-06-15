import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { buildReportData } from '../localReports';
import { LocalDataClient } from '../LocalDataClient';
import {
  openStore,
  closeAll,
  fakeBridge,
  taskRow,
  projectRow,
  focusRow,
  planRow,
} from './helpers';

afterEach(closeAll);

// CompletedAt at local-noon avoids any tz boundary ambiguity in the date bucket.
function completed(id: string, date: string, projectId: string | null): ReturnType<typeof taskRow> {
  return taskRow(id, { completedAt: `${date}T12:00:00`, projectId });
}

describe('buildReportData', () => {
  it('buckets completedAt into local days, resolves project names, filters to range', () => {
    const projects = [projectRow('P', { name: 'Work' })];
    const tasks = [
      completed('t1', '2026-06-11', 'P'),
      completed('t2', '2026-06-12', null),
      completed('t3', '2026-06-20', 'P'), // out of range
      taskRow('t4', { completedAt: null }), // never completed
    ];
    const data = buildReportData(
      { tasks, projects, focusSessions: [], dailyPlans: [] },
      '2026-06-11',
      '2026-06-15',
    );
    expect(data.completedTasks).toEqual([
      { id: 't1', project: 'Work', date: '2026-06-11' },
      { id: 't2', project: '', date: '2026-06-12' },
    ]);
  });

  it('filters sessions and dailyPlans by date range; sessions carry name + minutes', () => {
    const sessions = [
      focusRow('f1', { project: 'Work', minutes: 30, date: '2026-06-11' }),
      focusRow('f2', { project: '', minutes: 10, date: '2026-06-20' }), // out
    ];
    const plans = [
      planRow('2026-06-11', { plannedTaskIds: ['t1'], plannedMinutes: 60 }),
      planRow('2026-06-20', { plannedMinutes: 5 }), // out
    ];
    const data = buildReportData(
      { tasks: [], projects: [], focusSessions: sessions, dailyPlans: plans },
      '2026-06-11',
      '2026-06-15',
    );
    expect(data.sessions).toEqual([{ project: 'Work', minutes: 30, date: '2026-06-11' }]);
    expect(data.dailyPlans).toEqual([
      { date: '2026-06-11', committedAt: '2026-06-11T08:00:00Z', plannedTaskIds: ['t1'], plannedMinutes: 60 },
    ]);
  });

  it('includes boundary dates (inclusive start and end)', () => {
    const sessions = [
      focusRow('f1', { minutes: 1, date: '2026-06-11' }), // == start
      focusRow('f2', { minutes: 2, date: '2026-06-15' }), // == end
    ];
    const data = buildReportData(
      { tasks: [], projects: [], focusSessions: sessions, dailyPlans: [] },
      '2026-06-11',
      '2026-06-15',
    );
    expect(data.sessions.map((s) => s.date)).toEqual(['2026-06-11', '2026-06-15']);
  });
});

describe('LocalDataClient.reports.getData', () => {
  it('reads the local tables and delegates to buildReportData', async () => {
    const store = openStore();
    await store.projects.put(projectRow('P', { name: 'Work' }));
    await store.tasks.put(taskRow('t1', { completedAt: '2026-06-11T12:00:00', projectId: 'P' }));
    await store.focusSessions.put(focusRow('f1', { project: 'Work', minutes: 25, date: '2026-06-11' }));
    await store.dailyPlans.put(planRow('2026-06-11', { plannedTaskIds: ['t1'], plannedMinutes: 30 }));
    const dc = new LocalDataClient(store, fakeBridge());
    const data = await dc.reports.getData('2026-06-11', '2026-06-15');
    expect(data.completedTasks).toEqual([{ id: 't1', project: 'Work', date: '2026-06-11' }]);
    expect(data.sessions).toEqual([{ project: 'Work', minutes: 25, date: '2026-06-11' }]);
    expect(data.dailyPlans[0].plannedTaskIds).toEqual(['t1']);
  });
});
