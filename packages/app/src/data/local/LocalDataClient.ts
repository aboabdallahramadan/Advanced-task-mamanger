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
import {
  toTask,
  toProject,
  toNoteGroup,
  toNote,
  toDailyPlan,
  parseSettings,
} from '../mappers';

/** Ordinal (byte) string compare — never the locale collator. */
function ordinal(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Sort rank-bearing rows by (rank, id) — the §5.1 total order shared with the server. */
function byRankId<T extends { id: string; rank: string }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => ordinal(a.rank, b.rank) || ordinal(a.id, b.id));
}

/** Drop the `_rank` the to* mappers attach (HttpDataClient strips it the same way). */
function stripRank<T extends { _rank?: string }>(v: T): Omit<T, '_rank'> {
  const { _rank, ...rest } = v;
  void _rank;
  return rest;
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

  // ── projects ───────────────────────────────────────────────
  projects = {
    getAll: async (): Promise<Project[]> => {
      const rows = byRankId(await this.store.projects.toArray());
      return rows.map((r, i) => stripRank({ ...toProject(r), order: i }));
    },
    create: async (_i: { name: string; color?: string; emoji?: string }): Promise<Project> => {
      throw new Error('not implemented in R2-2');
    },
    update: async (_id: string, _u: Partial<Project>): Promise<Project> => {
      throw new Error('not implemented in R2-2');
    },
    delete: async (_id: string): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
    reorder: async (_items: ReorderInput[]): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
  };

  // ── note groups ────────────────────────────────────────────
  noteGroups = {
    getAll: async (): Promise<NoteGroup[]> => {
      const rows = byRankId(await this.store.noteGroups.toArray());
      return rows.map((r, i) => stripRank({ ...toNoteGroup(r, i) }));
    },
    getByProject: async (projectId: string): Promise<NoteGroup[]> => {
      // noteGroups is keyed by `id` only (contract C3 — no projectId index), so
      // filter in-memory rather than via a Dexie .where() clause.
      const all = await this.store.noteGroups.toArray();
      const rows = byRankId(all.filter((g) => g.projectId === projectId));
      return rows.map((r, i) => stripRank({ ...toNoteGroup(r, i) }));
    },
    create: async (_i: {
      name: string;
      emoji?: string;
      projectId?: string;
    }): Promise<NoteGroup> => {
      throw new Error('not implemented in R2-2');
    },
    update: async (_id: string, _u: Partial<NoteGroup>): Promise<NoteGroup> => {
      throw new Error('not implemented in R2-2');
    },
    delete: async (_id: string): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
    reorder: async (_items: ReorderInput[]): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
  };

  // ── notes ──────────────────────────────────────────────────
  notes = {
    getAll: async (): Promise<Note[]> => {
      const rows = byRankId(await this.store.notes.toArray());
      return rows.map((r, i) => stripRank({ ...toNote(r, i) }));
    },
    getByGroup: async (groupId: string): Promise<Note[]> => {
      const rows = byRankId(await this.store.notes.where('groupId').equals(groupId).toArray());
      return rows.map((r, i) => stripRank({ ...toNote(r, i) }));
    },
    getByProject: async (projectId: string): Promise<Note[]> => {
      const rows = byRankId(await this.store.notes.where('projectId').equals(projectId).toArray());
      return rows.map((r, i) => stripRank({ ...toNote(r, i) }));
    },
    getById: async (id: string): Promise<Note | null> => {
      const row = await this.store.notes.get(id);
      return row ? stripRank({ ...toNote(row) }) : null;
    },
    create: async (_i: {
      groupId?: string;
      projectId?: string;
      title?: string;
      content?: string;
    }): Promise<Note> => {
      throw new Error('not implemented in R2-2');
    },
    update: async (
      _id: string,
      _u: Partial<{
        title: string;
        content: string;
        groupId: string;
        projectId: string;
        order: number;
      }>,
    ): Promise<Note> => {
      throw new Error('not implemented in R2-2');
    },
    delete: async (_id: string): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
    reorder: async (_items: ReorderInput[]): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
  };

  // ── daily plans ────────────────────────────────────────────
  dailyPlans = {
    upsert: async (_p: {
      date: string;
      plannedTaskIds: string[];
      plannedMinutes: number;
    }): Promise<DailyPlan> => {
      throw new Error('not implemented in R2-2');
    },
    get: async (date: string): Promise<DailyPlan | null> => {
      const row = await this.store.dailyPlans.get(date);
      return row ? toDailyPlan(row as never) : null;
    },
  };

  // ── settings ───────────────────────────────────────────────
  settings = {
    get: async (): Promise<{ settings: Record<string, unknown>; timeZoneId: string }> => {
      const rows = await this.store.settings.toArray();
      const map: Record<string, string> = {};
      for (const r of rows) map[r.key] = r.value;
      const lastUser = await this.store.getMeta<{ timeZoneId?: string }>('lastUser');
      return { settings: parseSettings(map), timeZoneId: lastUser?.timeZoneId ?? 'UTC' };
    },
    save: async (_s: Record<string, unknown>, _timeZoneId?: string): Promise<void> => {
      throw new Error('not implemented in R2-2');
    },
  };

  // Still placeholders (filled in by later R2 tasks):
  subtasks!: DataClient['subtasks'];
  recurrence!: DataClient['recurrence'];
  focusSessions!: DataClient['focusSessions'];
  reports!: DataClient['reports'];
}

/** Subtasks sort by (sortOrder, id) — the server's join order (TasksEndpoints.cs:114). */
function byRankIdSubtasks(subs: SubtaskSyncRow[]): SubtaskSyncRow[] {
  return [...subs].sort(
    (a, b) => Number(a.sortOrder) - Number(b.sortOrder) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0),
  );
}
