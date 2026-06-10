/** Minimal shape the scheduler needs from a Task (decoupled from the full domain type). */
export interface ReminderTask {
  id: string;
  title: string;
  status: string;
  scheduledStart: string | null;
  reminderMinutes: number | null;
}

const FIVE_MIN_MS = 5 * 60_000;
const ONE_HOUR_MS = 60 * 60_000;

/**
 * Decide which tasks should fire a reminder right now.
 * Mirrors the removed main-process scheduler: fire when
 * (start - reminderMinutes) <= now AND start > now - 5m, skipping already-notified
 * and non-active tasks.
 */
export function selectDueReminders(
  tasks: ReminderTask[],
  now: number,
  notified: ReadonlySet<string>,
): ReminderTask[] {
  const due: ReminderTask[] = [];
  for (const t of tasks) {
    if (notified.has(t.id)) continue;
    if (t.status === 'done' || t.status === 'archived') continue;
    if (!t.scheduledStart) continue;
    if (t.reminderMinutes == null) continue;

    const startTime = new Date(t.scheduledStart).getTime();
    if (Number.isNaN(startTime)) continue;

    const notifyAt = startTime - t.reminderMinutes * 60_000;
    if (notifyAt <= now && startTime > now - FIVE_MIN_MS) {
      due.push(t);
    }
  }
  return due;
}

/** Remove notified ids whose task started more than an hour ago (matches old cleanup). */
export function pruneNotified(
  notified: ReadonlySet<string>,
  tasks: ReminderTask[],
  now: number,
): Set<string> {
  const next = new Set(notified);
  for (const id of notified) {
    const t = tasks.find((x) => x.id === id);
    if (t?.scheduledStart) {
      const startTime = new Date(t.scheduledStart).getTime();
      if (!Number.isNaN(startTime) && startTime < now - ONE_HOUR_MS) {
        next.delete(id);
      }
    }
  }
  return next;
}

export function reminderBody(reminderMinutes: number): string {
  return reminderMinutes === 0 ? 'Starting now' : `Starting in ${reminderMinutes} minutes`;
}

export interface ReminderSchedulerOptions {
  /** Snapshot of the current in-store tasks (called every tick). */
  getTasks: () => ReminderTask[];
  /** Host notification sink (platform.notify). */
  notify: (title: string, body: string) => void;
  /** Poll interval; defaults to 30s to match the old main scheduler. */
  intervalMs?: number;
}

/**
 * Client-side reminder timer. Replaces the deleted main-process setInterval.
 * Host-agnostic: callers pass platform.notify. Returns a stop() disposer.
 */
export function startReminderScheduler(opts: ReminderSchedulerOptions): () => void {
  const intervalMs = opts.intervalMs ?? 30_000;
  let notified = new Set<string>();

  const handle = setInterval(() => {
    try {
      const now = Date.now();
      const tasks = opts.getTasks();
      const due = selectDueReminders(tasks, now, notified);
      for (const t of due) {
        opts.notify(t.title, reminderBody(t.reminderMinutes ?? 0));
        notified.add(t.id);
      }
      notified = pruneNotified(notified, tasks, now);
    } catch (e) {
      // never let a tick crash the timer
      console.error('Reminder scheduler tick failed:', e);
    }
  }, intervalMs);

  return () => clearInterval(handle);
}
