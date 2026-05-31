import { describe, it, expect } from 'vitest';
import { throughputByDay, timeByProject, completionRate, summarize } from './reports';
import { ReportData } from '../types';

const current: ReportData = {
  completedTasks: [
    { id: 't1', project: 'TMap', date: '2026-05-25' },
    { id: 't2', project: 'TMap', date: '2026-05-25' },
    { id: 't3', project: 'Personal', date: '2026-05-26' },
  ],
  sessions: [
    { project: 'TMap', minutes: 60, date: '2026-05-25' },
    { project: 'TMap', minutes: 30, date: '2026-05-26' },
    { project: 'Personal', minutes: 45, date: '2026-05-26' },
  ],
  dailyPlans: [
    { date: '2026-05-25', committedAt: '', plannedTaskIds: ['t1', 't2', 'tX'], plannedMinutes: 90 },
  ],
};

const previous: ReportData = {
  completedTasks: [{ id: 'p1', project: 'TMap', date: '2026-05-18' }],
  sessions: [{ project: 'TMap', minutes: 30, date: '2026-05-18' }],
  dailyPlans: [],
};

describe('throughputByDay', () => {
  it('counts completed per day and attaches plan size', () => {
    const days = ['2026-05-25', '2026-05-26', '2026-05-27'];
    expect(throughputByDay(current, days)).toEqual([
      { date: '2026-05-25', completed: 2, planned: 3, hasPlan: true },
      { date: '2026-05-26', completed: 1, planned: 0, hasPlan: false },
      { date: '2026-05-27', completed: 0, planned: 0, hasPlan: false },
    ]);
  });
});

describe('timeByProject', () => {
  it('sums minutes per project, sorted descending', () => {
    expect(timeByProject(current.sessions)).toEqual([
      { project: 'TMap', minutes: 90 },
      { project: 'Personal', minutes: 45 },
    ]);
  });
});

describe('completionRate', () => {
  it('is done-of-planned across the range, counting only same-day completions', () => {
    // plan 2026-05-25 had t1,t2,tX; t1+t2 completed that day, tX never -> 2/3
    expect(completionRate(current)).toBeCloseTo(2 / 3);
  });
  it('is null when nothing was planned', () => {
    expect(completionRate(previous)).toBeNull();
  });
});

describe('summarize', () => {
  it('produces totals, top project, and deltas vs previous', () => {
    const s = summarize(current, previous);
    expect(s.completed).toBe(3);
    expect(s.focusMinutes).toBe(135);
    expect(s.topProject).toBe('TMap');
    expect(s.topProjectMinutes).toBe(90);
    expect(s.completionRate).toBeCloseTo(2 / 3);
    expect(s.delta.completed).toBe(2); // 3 - 1
    expect(s.delta.focusMinutes).toBe(105); // 135 - 30
    expect(s.delta.completionRate).toBeNull(); // previous had no plan
  });
  it('returns topProject=null and topProjectMinutes=0 when sessions is empty', () => {
    const empty: ReportData = { completedTasks: [], sessions: [], dailyPlans: [] };
    const s = summarize(empty, empty);
    expect(s.topProject).toBeNull();
    expect(s.topProjectMinutes).toBe(0);
    expect(s.focusMinutes).toBe(0);
    expect(s.completed).toBe(0);
  });
});
