import { describe, it, expect } from 'vitest';
import { getRange, getPreviousRange, daysInRange } from './dateRange';

// 2026-05-31 is a Sunday. Weeks are Monday-based.
const ref = new Date(2026, 4, 31, 12, 0, 0); // month is 0-indexed -> May

describe('getRange', () => {
  it('day', () => {
    expect(getRange('day', ref)).toEqual({ start: '2026-05-31', end: '2026-05-31' });
  });
  it('week (Mon-Sun)', () => {
    expect(getRange('week', ref)).toEqual({ start: '2026-05-25', end: '2026-05-31' });
  });
  it('month', () => {
    expect(getRange('month', ref)).toEqual({ start: '2026-05-01', end: '2026-05-31' });
  });
  it('year', () => {
    expect(getRange('year', ref)).toEqual({ start: '2026-01-01', end: '2026-12-31' });
  });
});

describe('getPreviousRange', () => {
  it('week shifts back one week', () => {
    expect(getPreviousRange('week', ref)).toEqual({ start: '2026-05-18', end: '2026-05-24' });
  });
  it('day shifts back one day', () => {
    expect(getPreviousRange('day', ref)).toEqual({ start: '2026-05-30', end: '2026-05-30' });
  });
  it('month shifts back one month (May 31 → April)', () => {
    // date-fns subMonths clamps May 31 → Apr 30; previous month range is all of April
    expect(getPreviousRange('month', ref)).toEqual({ start: '2026-04-01', end: '2026-04-30' });
  });
  it('year shifts back one year', () => {
    expect(getPreviousRange('year', ref)).toEqual({ start: '2025-01-01', end: '2025-12-31' });
  });
  it('month edge case: Jan 31 → previous December (clamping)', () => {
    // date-fns subMonths clamps Jan 31 → Dec 31; previous month range is all of December
    const jan31 = new Date(2026, 0, 31, 12, 0, 0);
    expect(getPreviousRange('month', jan31)).toEqual({ start: '2025-12-01', end: '2025-12-31' });
  });
});

describe('daysInRange', () => {
  it('enumerates inclusive days', () => {
    expect(daysInRange({ start: '2026-05-25', end: '2026-05-31' })).toHaveLength(7);
    expect(daysInRange({ start: '2026-05-25', end: '2026-05-31' })[0]).toBe('2026-05-25');
    expect(daysInRange({ start: '2026-05-31', end: '2026-05-31' })).toEqual(['2026-05-31']);
  });
});
