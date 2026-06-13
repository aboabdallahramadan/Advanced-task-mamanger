/**
 * LocalDataClient — DataClient over the per-user Dexie LocalStore (contract C4, spec §1).
 *
 * Reads: rank-bearing collections sort by (rank, id) ordinal (spec §5.1); tasks join
 * live subtasks (sorted (sortOrder, id)) and map via mappers.toTask.
 * Writes: one Dexie rw-transaction over the entity tables + `ops`. Request bodies are
 * resolved at enqueue (domain→DTO mapping, ranks, client UUIDs) — replay (R3) is a dumb
 * FIFO loop. Updates diff against the current local row and enqueue only changed fields
 * (diff-at-enqueue, spec §3.1). Deletes apply the same cascades the server applies.
 *
 * mappers.ts / ranking.ts are reused verbatim — they are byte-compatible with the server.
 * HttpDataClient.ts is mirrored (its mapper usage + wire bodies), never imported.
 */

import type {
  Task,
  Subtask,
  Project,
  NoteGroup,
  Note,
  FocusSession,
  DailyPlan,
  ReportData,
  RecurrenceRule,
} from '../../types';
import type { DataClient, ReorderInput, RecurrenceRuleInput } from '../DataClient';
import type { LocalStore } from './LocalStore';
import type { TaskSyncRow, SubtaskSyncRow } from './rows';
import { toTask } from '../mappers';

/** Ordinal (byte) string compare — never the locale collator. */
function ordinal(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Sort rank-bearing rows by (rank, id) — the §5.1 total order shared with the server. */
function byRankId<T extends { id: string; rank: string }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => ordinal(a.rank, b.rank) || ordinal(a.id, b.id));
}

/** The SyncEngine surface LocalDataClient needs at enqueue/read-through time. */
export interface SyncBridge {
  /** An op was enqueued → debounce a push. */
  nudge(): void;
  online(): boolean;
  /** Server ensure-instances call; throws when offline (caller guards with online()). */
  ensureInstances(start: string, end: string): Promise<TaskSyncRow[]>;
}

export class LocalDataClient implements DataClient {
  constructor(
    private store: LocalStore,
    private bridge: SyncBridge,
  ) {}

  // ── helpers ────────────────────────────────────────────────
  /** Map a live task row + its joined subtask rows into a domain Task via mappers.toTask. */
  private mapTask(row: TaskSyncRow, subs: SubtaskSyncRow[]): Task {
    const subtasks = byRankIdSubtasks(subs);
    // toTask expects a TaskResponse-shaped object with a nested `subtasks` array (mappers.ts:131-163);
    // TaskSyncRow has no nested subtasks (spec §2), so synthesize one.
    return toTask({ ...(row as unknown as Record<string, unknown>), subtasks } as never);
  }

  private async liveSubtasks(taskId: string): Promise<SubtaskSyncRow[]> {
    return this.store.subtasks.where('taskId').equals(taskId).toArray();
  }

  // ── tasks ──────────────────────────────────────────────────
  tasks = {
    getAll: async (): Promise<Task[]> => {
      const rows = byRankId(await this.store.tasks.toArray());
      const allSubs = await this.store.subtasks.toArray();
      const byTask = new Map<string, SubtaskSyncRow[]>();
      for (const s of allSubs) {
        const arr = byTask.get(s.taskId);
        if (arr) arr.push(s);
        else byTask.set(s.taskId, [s]);
      }
      return rows.map((r, i) => ({ ...this.mapTask(r, byTask.get(r.id) ?? []), order: i }));
    },
    getByDate: async (date: string): Promise<Task[]> => {
      const filtered = byRankId(
        await this.store.tasks.where('plannedDate').equals(date).toArray(),
      );
      return Promise.all(
        filtered.map(async (r, i) => ({
          ...this.mapTask(r, await this.liveSubtasks(r.id)),
          order: i,
        })),
      );
    },
    create: async (_t: Partial<Task>): Promise<Task> => {
      throw new Error('not implemented in R2-1');
    },
    update: async (_id: string, _u: Partial<Task>): Promise<Task> => {
      throw new Error('not implemented in R2-1');
    },
    delete: async (_id: string): Promise<void> => {
      throw new Error('not implemented in R2-1');
    },
    reorder: async (_items: ReorderInput[]): Promise<void> => {
      throw new Error('not implemented in R2-1');
    },
  };

  // The remaining DataClient groups are filled in by later R2 tasks.
  subtasks!: DataClient['subtasks'];
  projects!: DataClient['projects'];
  noteGroups!: DataClient['noteGroups'];
  notes!: DataClient['notes'];
  recurrence!: DataClient['recurrence'];
  focusSessions!: DataClient['focusSessions'];
  dailyPlans!: DataClient['dailyPlans'];
  reports!: DataClient['reports'];
  settings!: DataClient['settings'];
}

/** Subtasks sort by (sortOrder, id) — the server's join order (TasksEndpoints.cs:114). */
function byRankIdSubtasks(subs: SubtaskSyncRow[]): SubtaskSyncRow[] {
  return [...subs].sort(
    (a, b) => Number(a.sortOrder) - Number(b.sortOrder) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0),
  );
}
