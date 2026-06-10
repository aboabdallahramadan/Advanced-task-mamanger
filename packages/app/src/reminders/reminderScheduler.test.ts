import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  selectDueReminders,
  pruneNotified,
  startReminderScheduler,
  type ReminderTask,
} from './reminderScheduler';

const MIN = 60_000;

function task(p: Partial<ReminderTask>): ReminderTask {
  return {
    id: 'id',
    title: 'T',
    status: 'scheduled',
    scheduledStart: null,
    reminderMinutes: 0,
    ...p,
  };
}

describe('selectDueReminders', () => {
  const now = Date.parse('2026-06-08T09:00:00Z');

  it('fires when notifyAt (start - reminderMinutes) has arrived and start is not >5m past', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now + 10 * MIN).toISOString(),
      reminderMinutes: 10,
    });
    expect(selectDueReminders([t], now, new Set()).map((x) => x.id)).toEqual(['a']);
  });

  it('does not fire before notifyAt', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now + 20 * MIN).toISOString(),
      reminderMinutes: 10,
    });
    expect(selectDueReminders([t], now, new Set())).toEqual([]);
  });

  it('does not fire once already notified', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now + 1 * MIN).toISOString(),
      reminderMinutes: 10,
    });
    expect(selectDueReminders([t], now, new Set(['a']))).toEqual([]);
  });

  it('does not fire for tasks whose start is more than 5 minutes in the past', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now - 6 * MIN).toISOString(),
      reminderMinutes: 0,
    });
    expect(selectDueReminders([t], now, new Set())).toEqual([]);
  });

  it('fires at exactly start time when reminderMinutes is 0 ("Starting now")', () => {
    const t = task({ id: 'a', scheduledStart: new Date(now).toISOString(), reminderMinutes: 0 });
    expect(selectDueReminders([t], now, new Set()).map((x) => x.id)).toEqual(['a']);
  });

  it('skips tasks without a scheduledStart', () => {
    const t = task({ id: 'a', scheduledStart: null, reminderMinutes: 10 });
    expect(selectDueReminders([t], now, new Set())).toEqual([]);
  });

  it('skips tasks with null reminderMinutes (reminders disabled)', () => {
    const t = task({
      id: 'a',
      scheduledStart: new Date(now).toISOString(),
      reminderMinutes: null,
    });
    expect(selectDueReminders([t], now, new Set())).toEqual([]);
  });

  it('skips done/archived tasks', () => {
    const base = {
      id: 'a',
      scheduledStart: new Date(now).toISOString(),
      reminderMinutes: 0,
    };
    expect(selectDueReminders([task({ ...base, status: 'done' })], now, new Set())).toEqual([]);
    expect(selectDueReminders([task({ ...base, status: 'archived' })], now, new Set())).toEqual([]);
  });
});

describe('pruneNotified', () => {
  const now = Date.parse('2026-06-08T09:00:00Z');
  it('drops ids whose task started more than 1 hour ago', () => {
    const tasks = [task({ id: 'a', scheduledStart: new Date(now - 70 * MIN).toISOString() })];
    const next = pruneNotified(new Set(['a', 'ghost']), tasks, now);
    expect(next.has('a')).toBe(false);
    // ids no longer present in the task list are kept (can't prove they're old) — or dropped; assert 'a' specifically
  });

  it('keeps ids whose task started within the last hour', () => {
    const tasks = [task({ id: 'a', scheduledStart: new Date(now - 10 * MIN).toISOString() })];
    const next = pruneNotified(new Set(['a']), tasks, now);
    expect(next.has('a')).toBe(true);
  });
});

describe('startReminderScheduler', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('polls on the interval and calls notify for each newly-due task exactly once', () => {
    const now = Date.parse('2026-06-08T09:00:00Z');
    vi.setSystemTime(now);
    const notify = vi.fn();
    const due = task({
      id: 'a',
      title: 'Standup',
      scheduledStart: new Date(now).toISOString(),
      reminderMinutes: 0,
    });
    const stop = startReminderScheduler({
      getTasks: () => [due],
      notify,
      intervalMs: 30_000,
    });
    vi.advanceTimersByTime(30_000);
    expect(notify).toHaveBeenCalledTimes(1);
    expect(notify).toHaveBeenCalledWith('Standup', 'Starting now');
    // second tick must not re-notify the same task
    vi.advanceTimersByTime(30_000);
    expect(notify).toHaveBeenCalledTimes(1);
    stop();
  });

  it('uses "Starting in N minutes" copy when reminderMinutes > 0', () => {
    const now = Date.parse('2026-06-08T09:00:00Z');
    vi.setSystemTime(now);
    const notify = vi.fn();
    const due = task({
      id: 'a',
      title: 'Review',
      scheduledStart: new Date(now + 5 * MIN).toISOString(),
      reminderMinutes: 5,
    });
    const stop = startReminderScheduler({ getTasks: () => [due], notify, intervalMs: 30_000 });
    vi.advanceTimersByTime(30_000);
    expect(notify).toHaveBeenCalledWith('Review', 'Starting in 5 minutes');
    stop();
  });

  it('stop() clears the interval (no further notifies)', () => {
    const now = Date.parse('2026-06-08T09:00:00Z');
    vi.setSystemTime(now);
    const notify = vi.fn();
    const stop = startReminderScheduler({
      getTasks: () => [
        task({ id: 'a', scheduledStart: new Date(now).toISOString(), reminderMinutes: 0 }),
      ],
      notify,
      intervalMs: 30_000,
    });
    stop();
    vi.advanceTimersByTime(120_000);
    expect(notify).not.toHaveBeenCalled();
  });
});
