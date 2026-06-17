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
 * Wire bodies match the REST request DTOs the SyncEngine replays to the API.
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
import type { Table } from 'dexie';
import type { DataClient, ReorderInput, RecurrenceRuleInput } from '../DataClient';
import type { LocalStore } from './LocalStore';
import type {
  TaskSyncRow,
  SubtaskSyncRow,
  ProjectSyncRow,
  NoteGroupSyncRow,
  NoteSyncRow,
  RecurrenceRuleSyncRow,
} from './rows';
import { entityKey } from '../../sync/types';
import { buildReportData } from './localReports';
import { rankAfter, rankBetween } from '../ranking';
import {
  toTask,
  toSubtask,
  toProject,
  toNoteGroup,
  toNote,
  toDailyPlan,
  toFocusSession,
  toRecurrenceRule,
  toServerFrequency,
  toServerEndType,
  parseSettings,
  toServerStatus,
} from '../mappers';

/** Ordinal (byte) string compare — never the locale collator. */
function ordinal(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Sort rank-bearing rows by (rank, id) — the §5.1 total order shared with the server. */
function byRankId<T extends { id: string; rank: string }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => ordinal(a.rank, b.rank) || ordinal(a.id, b.id));
}

/** Drop the `_rank` the to* mappers attach (it never goes on the wire body). */
function stripRank<T extends { _rank?: string }>(v: T): Omit<T, '_rank'> {
  const { _rank, ...rest } = v;
  void _rank;
  return rest;
}

/** crypto.randomUUID with a safe fallback (used for client-provided ids on create ops). */
function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

/** The max rank across a container's local rows (null when empty → rankAfter seeds 'n'). */
function maxRank(rows: { rank: string }[]): string | null {
  let max: string | null = null;
  for (const r of rows) {
    if (max === null || r.rank > max) max = r.rank;
  }
  return max;
}

/** ISO timestamp for synthesized rows (server re-stamps on push; advisory locally). */
function nowIso(): string {
  return new Date().toISOString();
}

/** Today's date as YYYY-MM-DD in UTC (mirrors the server's DateOnly.FromDateTime(DateTime.UtcNow)). */
function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/**
 * Diff a wire-shaped patch against the current row's wire-shaped value, returning the
 * subset of keys whose value actually changed (deep-equal by JSON for array/object
 * fields like labels/plannedTaskIds). Explicit null in the patch is a real value (clear).
 */
function diffFields(
  current: Record<string, unknown>,
  patch: Record<string, unknown>,
): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(patch)) {
    if (!valueEqual(current[k], v)) out[k] = v;
  }
  return out;
}

function valueEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (Array.isArray(a) || Array.isArray(b) || (typeof a === 'object' && a !== null)) {
    return JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
  }
  return false;
}

/**
 * fromTask for the CREATE path — maps the domain partial to the POST /tasks wire body.
 * mappers.fromTask only forwards present keys and case-folds
 * status; the create endpoint defaults the rest server-side. We add id + rank at the call site.
 */
function fromTaskCreate(t: Partial<Task>): Record<string, unknown> {
  const body: Record<string, unknown> = {};
  const { order: _order, id: _id, ...rest } = t as Record<string, unknown>;
  void _order;
  void _id;
  for (const [k, v] of Object.entries(rest)) {
    if (k === 'status' && typeof v === 'string') body[k] = toServerStatus(v as Task['status']);
    else body[k] = v;
  }
  return body;
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

  /** Run `fn` in one rw-transaction over the named entity tables + the ops table, then nudge. */
  private async writeTx<T>(
    tables: Parameters<LocalStore['transaction']>[1][],
    fn: () => Promise<T>,
  ): Promise<T> {
    const result = await this.store.transaction('rw', [...tables, this.store.ops], fn);
    this.bridge.nudge();
    return result;
  }

  /**
   * Full-resync reset (C9 / spec §5.3). Wipes the local store back to an
   * empty-but-known-user state so the SyncEngine can re-pull from since=0:
   * clears the 9 entity tables + the issues log, and resets the meta keys
   * syncCursor / initialSyncComplete(→false) / pendingRecovery(→false), while
   * KEEPING meta.lastUser (the authed-offline identity). Runs in one Dexie rw
   * transaction so an interrupted reset never leaves a torn partial state. The
   * ops table is intentionally untouched — the engine only calls this once the
   * push queue is fully drained (SyncEngine pullPhase precondition).
   */
  async resetForFullResync(): Promise<void> {
    await this.store.transaction(
      'rw',
      [
        this.store.tasks,
        this.store.subtasks,
        this.store.projects,
        this.store.noteGroups,
        this.store.notes,
        this.store.recurrenceRules,
        this.store.focusSessions,
        this.store.dailyPlans,
        this.store.settings,
        this.store.issues,
        this.store.meta,
      ],
      async () => {
        await Promise.all([
          this.store.tasks.clear(),
          this.store.subtasks.clear(),
          this.store.projects.clear(),
          this.store.noteGroups.clear(),
          this.store.notes.clear(),
          this.store.recurrenceRules.clear(),
          this.store.focusSessions.clear(),
          this.store.dailyPlans.clear(),
          this.store.settings.clear(),
          this.store.issues.clear(),
        ]);
        // Per-key meta writes leave lastUser untouched. syncCursor is removed
        // (so getMeta returns undefined → the next pull starts from 0); the two
        // gate flags are reset to false rather than deleted (their consumers read
        // them as booleans).
        await this.store.meta.delete('syncCursor');
        await this.store.setMeta('initialSyncComplete', false);
        await this.store.setMeta('pendingRecovery', false);
      },
    );
  }

  /**
   * Minimal-rank reorder computation against LOCAL rows, with §5.1 tie repair.
   * Returns the minimal [{id, rank}] payload AND mutates `ranks` to the post-move state
   * so the caller can persist the same ranks to the rows in one transaction.
   */
  private computeReorderLocal(
    ranks: Map<string, string>,
    items: ReorderInput[],
  ): { id: string; rank: string }[] {
    const ordered = [...items].sort((a, b) => a.order - b.order);
    const result: { id: string; rank: string }[] = [];
    const working = new Map(ranks);
    let prevRank: string | null = null;

    for (let i = 0; i < ordered.length; i++) {
      const cur = ordered[i];
      const curRank = working.get(cur.id) ?? null;

      // Upper bound = nearest later item whose current rank sorts strictly above prevRank.
      let nextRank: string | null = null;
      for (let j = i + 1; j < ordered.length; j++) {
        const r = working.get(ordered[j].id) ?? null;
        if (r !== null && (prevRank === null || r > prevRank)) {
          nextRank = r;
          break;
        }
      }

      const inOrder =
        curRank !== null &&
        (prevRank === null || prevRank < curRank) &&
        (nextRank === null || curRank < nextRank);

      if (inOrder) {
        prevRank = curRank;
        continue;
      }

      // §5.1 tie repair: rankBetween(prev, next) with prev === next sorts after BOTH.
      // When the bounding neighbors tie (or prev would equal next), append after prev so
      // the fresh rank is strictly greater than prevRank and below the next distinct rank.
      let newRank: string;
      if (prevRank !== null && nextRank !== null && prevRank >= nextRank) {
        newRank = rankAfter(prevRank);
      } else {
        newRank = rankBetween(prevRank, nextRank);
      }
      working.set(cur.id, newRank);
      ranks.set(cur.id, newRank);
      result.push({ id: cur.id, rank: newRank });
      prevRank = newRank;
    }
    return result;
  }

  /** Shared reorder driver for every rank-bearing table. */
  private async reorderTable<R extends { id: string; rank: string }>(
    table: Table<R, string>,
    keyTable: Parameters<typeof entityKey>[0],
    path: string,
    items: ReorderInput[],
  ): Promise<void> {
    const rows = await table.toArray();
    const ranks = new Map(rows.map((r) => [r.id, r.rank]));
    const payload = this.computeReorderLocal(ranks, items);
    if (payload.length === 0) return;
    const byId = new Map(rows.map((r) => [r.id, r]));
    await this.writeTx([table], async () => {
      for (const { id, rank } of payload) {
        const row = byId.get(id);
        if (row) await table.put({ ...row, rank });
      }
      await this.store.ops.add({
        method: 'PATCH',
        path,
        body: payload,
        entityKeys: payload.map((e) => entityKey(keyTable, e.id)),
        kind: 'other',
        attempts: 0,
      });
    });
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
    create: async (t: Partial<Task>): Promise<Task> => {
      const id = t.id ?? newId();
      const existing = await this.store.tasks.toArray();
      const rank = rankAfter(maxRank(existing));
      const row: TaskSyncRow = {
        id,
        title: t.title ?? '',
        notes: t.notes ?? '',
        projectId: t.projectId ?? null,
        labels: t.labels ?? [],
        source: t.source ?? 'manual',
        status: toServerStatus(t.status ?? 'planned'),
        plannedDate: t.plannedDate ?? null,
        scheduledStart: t.scheduledStart ?? null,
        scheduledEnd: t.scheduledEnd ?? null,
        durationMinutes: t.durationMinutes ?? 30,
        actualTimeMinutes: t.actualTimeMinutes ?? 0,
        priority: t.priority ?? null,
        reminderMinutes: t.reminderMinutes ?? null,
        rank,
        dueDate: null,
        recurrenceRuleId: t.recurrenceRuleId ?? null,
        isRecurrenceTemplate: t.isRecurrenceTemplate ?? false,
        recurrenceDetached: t.recurrenceDetached ?? false,
        recurrenceOriginalDate: t.recurrenceOriginalDate ?? null,
        completedAt: t.completedAt ?? null,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      // Wire body for POST /tasks: fromTask(t) + explicit id + rank.
      const body = { ...fromTaskCreate(t), id, rank };
      await this.writeTx([this.store.tasks], async () => {
        await this.store.tasks.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/tasks',
          body,
          entityKeys: [entityKey('tasks', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return this.mapTask(row, []);
    },
    update: async (id: string, u: Partial<Task>): Promise<Task> => {
      const row = await this.store.tasks.get(id);
      if (!row) throw new Error(`task ${id} not found`);
      // Map the domain patch to wire shape (case-fold status, drop order), then diff vs the row.
      const patch = fromTaskCreate(u);
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      const subs = await this.liveSubtasks(id);
      if (Object.keys(changed).length === 0) {
        return { ...this.mapTask(row, subs), order: 0 };
      }
      const next = { ...row, ...changed, updatedAt: nowIso() } as TaskSyncRow;
      await this.writeTx([this.store.tasks], async () => {
        await this.store.tasks.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/tasks/${id}`,
          body: changed,
          entityKeys: [entityKey('tasks', id)],
          kind: 'other',
          attempts: 0,
        });
      });
      return { ...this.mapTask(next, subs), order: 0 };
    },
    delete: async (id: string): Promise<void> => {
      const subs = await this.store.subtasks.where('taskId').equals(id).toArray();
      const keys = [entityKey('tasks', id), ...subs.map((s) => entityKey('subtasks', s.id))];
      await this.writeTx([this.store.tasks, this.store.subtasks], async () => {
        await this.store.tasks.delete(id);
        await this.store.subtasks.where('taskId').equals(id).delete();
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/tasks/${id}`,
          entityKeys: keys,
          kind: 'other',
          attempts: 0,
        });
      });
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      await this.reorderTable(this.store.tasks, 'tasks', '/api/v1/tasks/reorder', items);
    },
  };

  // ── projects ───────────────────────────────────────────────
  projects = {
    getAll: async (): Promise<Project[]> => {
      const rows = byRankId(await this.store.projects.toArray());
      return rows.map((r, i) => stripRank({ ...toProject(r), order: i }));
    },
    create: async (i: { name: string; color?: string; emoji?: string }): Promise<Project> => {
      const id = newId();
      const existing = await this.store.projects.toArray();
      const rank = rankAfter(maxRank(existing));
      const color = i.color ?? '#6366f1';
      const emoji = i.emoji ?? '📁';
      const row: ProjectSyncRow = {
        id,
        name: i.name,
        color,
        emoji,
        rank,
        actualTimeMinutes: 0,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = { name: i.name, color, emoji, rank, id };
      await this.writeTx([this.store.projects], async () => {
        await this.store.projects.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/projects',
          body,
          entityKeys: [entityKey('projects', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return stripRank({ ...toProject(row), order: 0 });
    },
    update: async (id: string, u: Partial<Project>): Promise<Project> => {
      const row = await this.store.projects.get(id);
      if (!row) throw new Error(`project ${id} not found`);
      const patch: Record<string, unknown> = {};
      if (u.name !== undefined) patch.name = u.name;
      if (u.color !== undefined) patch.color = u.color;
      if (u.emoji !== undefined) patch.emoji = u.emoji;
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      if (Object.keys(changed).length === 0) {
        return stripRank({ ...toProject(row), order: 0 });
      }
      const next = { ...row, ...changed, updatedAt: nowIso() } as ProjectSyncRow;
      // Wire body uses the server null=unchanged convention: changed keys real, rest null.
      const body = {
        name: 'name' in changed ? changed.name : null,
        color: 'color' in changed ? changed.color : null,
        emoji: 'emoji' in changed ? changed.emoji : null,
        rank: null,
      };
      await this.writeTx([this.store.projects], async () => {
        await this.store.projects.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/projects/${id}`,
          body,
          entityKeys: [entityKey('projects', id)],
          kind: 'other',
          attempts: 0,
        });
      });
      return stripRank({ ...toProject(next), order: 0 });
    },
    delete: async (id: string): Promise<void> => {
      // tasks/noteGroups have no projectId index (contract C3), so filter in-memory
      // (mirrors noteGroups.getByProject); notes IS indexed on projectId.
      const tasks = (await this.store.tasks.toArray()).filter((t) => t.projectId === id);
      const notes = await this.store.notes.where('projectId').equals(id).toArray();
      const groups = (await this.store.noteGroups.toArray()).filter((g) => g.projectId === id);
      const keys = [
        entityKey('projects', id),
        ...tasks.map((t) => entityKey('tasks', t.id)),
        ...notes.map((n) => entityKey('notes', n.id)),
        ...groups.map((g) => entityKey('noteGroups', g.id)),
      ];
      await this.writeTx(
        [this.store.projects, this.store.tasks, this.store.notes, this.store.noteGroups],
        async () => {
          await this.store.projects.delete(id);
          for (const t of tasks) await this.store.tasks.put({ ...t, projectId: null });
          for (const n of notes) await this.store.notes.put({ ...n, projectId: null });
          for (const g of groups) await this.store.noteGroups.put({ ...g, projectId: null });
          await this.store.ops.add({
            method: 'DELETE',
            path: `/api/v1/projects/${id}`,
            entityKeys: keys,
            kind: 'other',
            attempts: 0,
          });
        },
      );
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      await this.reorderTable(this.store.projects, 'projects', '/api/v1/projects/reorder', items);
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
    create: async (i: { name: string; emoji?: string; projectId?: string }): Promise<NoteGroup> => {
      const id = newId();
      const existing = await this.store.noteGroups.toArray();
      const rank = rankAfter(maxRank(existing));
      const emoji = i.emoji ?? null;
      const projectId = i.projectId ?? null;
      const row: NoteGroupSyncRow = {
        id,
        name: i.name,
        emoji: emoji ?? '',
        projectId,
        rank,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = { name: i.name, emoji, projectId, rank, id };
      await this.writeTx([this.store.noteGroups], async () => {
        await this.store.noteGroups.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/note-groups',
          body,
          entityKeys: [entityKey('noteGroups', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return stripRank({ ...toNoteGroup(row) });
    },
    update: async (id: string, u: Partial<NoteGroup>): Promise<NoteGroup> => {
      const row = await this.store.noteGroups.get(id);
      if (!row) throw new Error(`noteGroup ${id} not found`);
      const patch: Record<string, unknown> = {};
      if (u.name !== undefined) patch.name = u.name;
      if (u.emoji !== undefined) patch.emoji = u.emoji;
      if (u.projectId !== undefined) patch.projectId = u.projectId;
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      if (Object.keys(changed).length === 0) {
        return stripRank({ ...toNoteGroup(row) });
      }
      const next = { ...row, ...changed, updatedAt: nowIso() } as NoteGroupSyncRow;
      const body = {
        name: 'name' in changed ? changed.name : null,
        emoji: 'emoji' in changed ? changed.emoji : null,
        projectId: 'projectId' in changed ? changed.projectId : null,
        rank: null,
      };
      await this.writeTx([this.store.noteGroups], async () => {
        await this.store.noteGroups.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/note-groups/${id}`,
          body,
          entityKeys: [entityKey('noteGroups', id)],
          kind: 'other',
          attempts: 0,
        });
      });
      return stripRank({ ...toNoteGroup(next) });
    },
    delete: async (id: string): Promise<void> => {
      const notes = await this.store.notes.where('groupId').equals(id).toArray();
      const keys = [entityKey('noteGroups', id), ...notes.map((n) => entityKey('notes', n.id))];
      await this.writeTx([this.store.noteGroups, this.store.notes], async () => {
        await this.store.noteGroups.delete(id);
        await this.store.notes.where('groupId').equals(id).delete();
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/note-groups/${id}`,
          entityKeys: keys,
          kind: 'other',
          attempts: 0,
        });
      });
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      await this.reorderTable(
        this.store.noteGroups,
        'noteGroups',
        '/api/v1/note-groups/reorder',
        items,
      );
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
    create: async (i: {
      groupId?: string;
      projectId?: string;
      title?: string;
      content?: string;
    }): Promise<Note> => {
      const id = newId();
      const existing = await this.store.notes.toArray();
      const rank = rankAfter(maxRank(existing));
      const groupId = i.groupId ?? null;
      const projectId = i.projectId ?? null;
      const title = i.title ?? '';
      const content = i.content ?? null;
      const row: NoteSyncRow = {
        id,
        groupId,
        projectId,
        title,
        content: content ?? '',
        rank,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = { groupId, projectId, title, content, rank, id };
      await this.writeTx([this.store.notes], async () => {
        await this.store.notes.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/notes',
          body,
          entityKeys: [entityKey('notes', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return stripRank({ ...toNote(row) });
    },
    update: async (
      id: string,
      u: Partial<{
        title: string;
        content: string;
        groupId: string;
        projectId: string;
        order: number;
      }>,
    ): Promise<Note> => {
      const row = await this.store.notes.get(id);
      if (!row) throw new Error(`note ${id} not found`);
      const patch: Record<string, unknown> = {};
      if (u.groupId !== undefined) patch.groupId = u.groupId;
      if (u.projectId !== undefined) patch.projectId = u.projectId;
      if (u.title !== undefined) patch.title = u.title;
      if (u.content !== undefined) patch.content = u.content;
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      if (Object.keys(changed).length === 0) {
        return stripRank({ ...toNote(row) });
      }
      const next = { ...row, ...changed, updatedAt: nowIso() } as NoteSyncRow;
      const body = {
        groupId: 'groupId' in changed ? changed.groupId : null,
        projectId: 'projectId' in changed ? changed.projectId : null,
        title: 'title' in changed ? changed.title : null,
        content: 'content' in changed ? changed.content : null,
        rank: null,
      };
      await this.writeTx([this.store.notes], async () => {
        await this.store.notes.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/notes/${id}`,
          body,
          entityKeys: [entityKey('notes', id)],
          kind: 'other',
          attempts: 0,
        });
      });
      return stripRank({ ...toNote(next) });
    },
    delete: async (id: string): Promise<void> => {
      await this.writeTx([this.store.notes], async () => {
        await this.store.notes.delete(id);
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/notes/${id}`,
          entityKeys: [entityKey('notes', id)],
          kind: 'other',
          attempts: 0,
        });
      });
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      await this.reorderTable(this.store.notes, 'notes', '/api/v1/notes/reorder', items);
    },
  };

  // ── daily plans ────────────────────────────────────────────
  dailyPlans = {
    upsert: async (p: {
      date: string;
      plannedTaskIds: string[];
      plannedMinutes: number;
    }): Promise<DailyPlan> => {
      // committedAt is stamped locally for an offline-readable plan; the server
      // re-stamps the authoritative value on apply (spec §6).
      const committedAt = nowIso();
      const existing = await this.store.dailyPlans.get(p.date);
      const row = {
        date: p.date,
        committedAt,
        plannedTaskIds: p.plannedTaskIds,
        plannedMinutes: p.plannedMinutes,
        changeSeq: existing?.changeSeq ?? 0,
        deletedAt: null,
      };
      await this.writeTx([this.store.dailyPlans], async () => {
        await this.store.dailyPlans.put(row as never);
        await this.store.ops.add({
          method: 'PUT',
          path: `/api/v1/daily-plans/${p.date}`,
          body: { plannedTaskIds: p.plannedTaskIds, plannedMinutes: p.plannedMinutes },
          entityKeys: [entityKey('dailyPlans', p.date)],
          kind: 'other',
          attempts: 0,
        });
      });
      return toDailyPlan(row as never);
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
    save: async (s: Record<string, unknown>, timeZoneId?: string): Promise<void> => {
      // The three synced numeric keys, stringified (mirrors mappers.stringifySettings).
      const SYNCED = ['workStartHour', 'workEndHour', 'timeIncrement'] as const;
      const rows = await this.store.settings.toArray();
      const current: Record<string, string> = {};
      for (const r of rows) current[r.key] = r.value;
      const changedSettings: Record<string, string> = {};
      for (const k of SYNCED) {
        const v = s[k];
        if (v === undefined || v === null) continue;
        const str = String(v);
        if (current[k] !== str) changedSettings[k] = str;
      }
      const lastUser = await this.store.getMeta<{ id: string; email: string; timeZoneId: string }>(
        'lastUser',
      );
      const tzChanged = timeZoneId !== undefined && timeZoneId !== lastUser?.timeZoneId;
      if (Object.keys(changedSettings).length === 0 && !tzChanged) return;

      const body: { settings: Record<string, string>; timeZoneId?: string } = {
        settings: changedSettings,
      };
      if (tzChanged) body.timeZoneId = timeZoneId;

      const tables = [this.store.settings, this.store.meta];
      await this.writeTx(tables, async () => {
        for (const [k, v] of Object.entries(changedSettings)) {
          const ex = await this.store.settings.get(k);
          await this.store.settings.put({
            key: k,
            value: v,
            changeSeq: ex?.changeSeq ?? 0,
            deletedAt: null,
          } as never);
        }
        if (tzChanged && lastUser) {
          await this.store.meta.put({ key: 'lastUser', value: { ...lastUser, timeZoneId } });
        }
        await this.store.ops.add({
          method: 'PUT',
          path: '/api/v1/settings',
          body,
          entityKeys: Object.keys(changedSettings).map((k) => entityKey('settings', k)),
          kind: 'other',
          attempts: 0,
        });
      });
    },
  };

  // ── subtasks ───────────────────────────────────────────────
  subtasks = {
    create: async (taskId: string, title: string): Promise<Subtask> => {
      const id = newId();
      const existing = await this.store.subtasks.where('taskId').equals(taskId).toArray();
      const sortOrder = existing.reduce((m, s) => Math.max(m, Number(s.sortOrder) + 1), 0);
      const row: SubtaskSyncRow = {
        id,
        taskId,
        title,
        completed: false,
        sortOrder,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = { id, title };
      await this.writeTx([this.store.subtasks], async () => {
        await this.store.subtasks.add(row);
        await this.store.ops.add({
          method: 'POST',
          path: `/api/v1/tasks/${taskId}/subtasks`,
          body,
          entityKeys: [entityKey('subtasks', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return toSubtask(row as never, sortOrder);
    },
    update: async (
      id: string,
      u: { title?: string; completed?: boolean; order?: number },
    ): Promise<void> => {
      const row = await this.store.subtasks.get(id);
      if (!row) throw new Error(`subtask ${id} not found`);
      const patch: Record<string, unknown> = {};
      if (u.title !== undefined) patch.title = u.title;
      if (u.completed !== undefined) patch.completed = u.completed;
      if (u.order !== undefined) patch.sortOrder = u.order;
      const changed = diffFields(row as unknown as Record<string, unknown>, patch);
      if (Object.keys(changed).length === 0) return;
      const next = { ...row, ...changed, updatedAt: nowIso() } as SubtaskSyncRow;
      await this.writeTx([this.store.subtasks], async () => {
        await this.store.subtasks.put(next);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/subtasks/${id}`,
          body: changed,
          entityKeys: [entityKey('subtasks', id)],
          kind: 'other',
          attempts: 0,
        });
      });
    },
    delete: async (id: string): Promise<void> => {
      await this.writeTx([this.store.subtasks], async () => {
        await this.store.subtasks.delete(id);
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/subtasks/${id}`,
          entityKeys: [entityKey('subtasks', id)],
          kind: 'other',
          attempts: 0,
        });
      });
    },
  };

  // ── focus sessions ─────────────────────────────────────────
  focusSessions = {
    add: async (s: {
      taskId: string | null;
      project: string;
      startedAt: string;
      endedAt: string;
      minutes: number;
      date: string;
    }): Promise<FocusSession> => {
      const id = newId();
      const row = {
        id,
        taskId: s.taskId,
        project: s.project,
        startedAt: s.startedAt,
        endedAt: s.endedAt,
        minutes: s.minutes,
        date: s.date,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const body = {
        id,
        taskId: s.taskId,
        project: s.project,
        startedAt: s.startedAt,
        endedAt: s.endedAt,
        minutes: s.minutes,
        date: s.date,
      };
      await this.writeTx([this.store.focusSessions], async () => {
        await this.store.focusSessions.add(row as never);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/focus-sessions',
          body,
          entityKeys: [entityKey('focusSessions', id)],
          kind: 'create',
          attempts: 0,
        });
      });
      return toFocusSession(row as never);
    },
  };

  // ── recurrence ─────────────────────────────────────────────
  recurrence = {
    create: async (task: Partial<Task>, rule: RecurrenceRuleInput): Promise<Task[]> => {
      const taskId = task.id ?? newId();
      const ruleId = newId();
      const startDate = task.plannedDate ?? todayIso();
      const ruleRowVal: RecurrenceRuleSyncRow = {
        id: ruleId,
        frequency: toServerFrequency(rule.frequency),
        interval: rule.interval < 1 ? 1 : rule.interval,
        daysOfWeek: rule.daysOfWeek,
        endType: toServerEndType(rule.endType),
        endCount: rule.endType === 'count' ? (rule.endCount ?? null) : null,
        endDate: rule.endType === 'date' ? (rule.endDate ?? null) : null,
        generatedUntil: null,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      const templateRow: TaskSyncRow = {
        id: taskId,
        title: task.title ?? '',
        notes: task.notes ?? '',
        projectId: task.projectId ?? null,
        labels: task.labels ?? [],
        source: task.source ?? 'manual',
        status: 'Planned',
        plannedDate: startDate,
        scheduledStart: null,
        scheduledEnd: null,
        durationMinutes: task.durationMinutes && task.durationMinutes > 0 ? task.durationMinutes : 30,
        actualTimeMinutes: 0,
        priority: task.priority ?? null,
        reminderMinutes: task.reminderMinutes ?? 0,
        rank: 'a0',
        dueDate: null,
        recurrenceRuleId: ruleId,
        isRecurrenceTemplate: true,
        recurrenceDetached: false,
        recurrenceOriginalDate: startDate,
        completedAt: null,
        createdAt: nowIso(),
        updatedAt: nowIso(),
        changeSeq: 0,
        deletedAt: null,
      };
      // Wire body for POST /recurrence + the C7.4 rule id touch-up.
      const body = {
        task: {
          title: task.title ?? '',
          notes: task.notes ?? '',
          projectId: task.projectId ?? null,
          labels: task.labels ?? [],
          source: task.source ?? 'manual',
          plannedDate: task.plannedDate ?? null,
          durationMinutes: task.durationMinutes ?? 30,
          priority: task.priority ?? null,
          reminderMinutes: task.reminderMinutes ?? null,
          id: taskId,
        },
        rule: {
          frequency: toServerFrequency(rule.frequency),
          interval: rule.interval,
          daysOfWeek: rule.daysOfWeek,
          endType: toServerEndType(rule.endType),
          endCount: rule.endCount ?? null,
          endDate: rule.endDate ?? null,
          id: ruleId,
        },
      };
      await this.writeTx([this.store.tasks, this.store.recurrenceRules], async () => {
        await this.store.recurrenceRules.add(ruleRowVal);
        await this.store.tasks.add(templateRow);
        await this.store.ops.add({
          method: 'POST',
          path: '/api/v1/recurrence',
          body,
          entityKeys: [entityKey('tasks', taskId), entityKey('recurrenceRules', ruleId)],
          kind: 'create',
          regenAfterPush: true,
          attempts: 0,
        });
      });
      // §6 store contract (store.ts:667): return the full local task list.
      return this.tasks.getAll();
    },

    updateSeries: async (ruleId: string, u: Partial<Task>): Promise<void> => {
      const today = todayIso();
      const all = await this.store.tasks.where('recurrenceRuleId').equals(ruleId).toArray();
      const targets = all.filter(
        (t) =>
          t.isRecurrenceTemplate ||
          (!t.recurrenceDetached &&
            t.status !== 'Done' &&
            t.plannedDate != null &&
            t.plannedDate >= today),
      );
      // Apply the same fields the server's UpdateSeries propagates (non-null only).
      const body = {
        title: u.title ?? null,
        notes: u.notes ?? null,
        projectId: u.projectId === undefined ? null : u.projectId,
        labels: u.labels ?? null,
        durationMinutes: u.durationMinutes ?? null,
        priority: u.priority ?? null,
        reminderMinutes: u.reminderMinutes ?? null,
      };
      await this.writeTx([this.store.tasks], async () => {
        for (const t of targets) {
          const next = { ...t };
          if (u.title !== undefined) next.title = u.title;
          if (u.notes !== undefined) next.notes = u.notes;
          if (u.projectId !== undefined) next.projectId = u.projectId;
          if (u.labels !== undefined) next.labels = u.labels;
          if (u.durationMinutes !== undefined) next.durationMinutes = u.durationMinutes;
          if (u.priority !== undefined) next.priority = u.priority;
          if (u.reminderMinutes !== undefined) next.reminderMinutes = u.reminderMinutes;
          next.updatedAt = nowIso();
          await this.store.tasks.put(next);
        }
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/recurrence/rules/${ruleId}/series`,
          body,
          entityKeys: targets.map((t) => entityKey('tasks', t.id)),
          kind: 'other',
          attempts: 0,
        });
      });
    },

    deleteSeries: async (ruleId: string): Promise<void> => {
      const tasks = await this.store.tasks.where('recurrenceRuleId').equals(ruleId).toArray();
      const keys = [
        entityKey('recurrenceRules', ruleId),
        ...tasks.map((t) => entityKey('tasks', t.id)),
      ];
      await this.writeTx([this.store.recurrenceRules, this.store.tasks], async () => {
        await this.store.recurrenceRules.delete(ruleId);
        await this.store.tasks.where('recurrenceRuleId').equals(ruleId).delete();
        await this.store.ops.add({
          method: 'DELETE',
          path: `/api/v1/recurrence/rules/${ruleId}`,
          entityKeys: keys,
          kind: 'other',
          attempts: 0,
        });
      });
    },

    deleteSeriesFuture: async (ruleId: string, from: string): Promise<void> => {
      const all = await this.store.tasks.where('recurrenceRuleId').equals(ruleId).toArray();
      // §6 pinned: future, not-done, NON-template instances — detached included.
      const victims = all.filter(
        (t) =>
          !t.isRecurrenceTemplate &&
          t.status !== 'Done' &&
          t.plannedDate != null &&
          t.plannedDate >= from,
      );
      await this.writeTx([this.store.tasks], async () => {
        for (const t of victims) await this.store.tasks.delete(t.id);
        await this.store.ops.add({
          method: 'POST',
          path: `/api/v1/recurrence/rules/${ruleId}/delete-future`,
          body: { fromDate: from },
          entityKeys: victims.map((t) => entityKey('tasks', t.id)),
          kind: 'other',
          attempts: 0,
        });
      });
    },

    updateRule: async (ruleId: string, u: RecurrenceRuleInput): Promise<void> => {
      const today = todayIso();
      const rule = await this.store.recurrenceRules.get(ruleId);
      const all = await this.store.tasks.where('recurrenceRuleId').equals(ruleId).toArray();
      // Mirror server UpdateRule: tombstone future, non-detached, not-done, non-template instances.
      const victims = all.filter(
        (t) =>
          !t.isRecurrenceTemplate &&
          !t.recurrenceDetached &&
          t.status !== 'Done' &&
          t.plannedDate != null &&
          t.plannedDate >= today,
      );
      const body = {
        frequency: toServerFrequency(u.frequency),
        interval: u.interval,
        daysOfWeek: u.daysOfWeek,
        endType: toServerEndType(u.endType),
        endCount: u.endCount ?? null,
        endDate: u.endDate ?? null,
      };
      await this.writeTx([this.store.recurrenceRules, this.store.tasks], async () => {
        if (rule) {
          await this.store.recurrenceRules.put({
            ...rule,
            frequency: toServerFrequency(u.frequency),
            interval: u.interval,
            daysOfWeek: u.daysOfWeek,
            endType: toServerEndType(u.endType),
            endCount: u.endType === 'count' ? (u.endCount ?? null) : null,
            endDate: u.endType === 'date' ? (u.endDate ?? null) : null,
            generatedUntil: null,
            updatedAt: nowIso(),
          });
        }
        for (const t of victims) await this.store.tasks.delete(t.id);
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/recurrence/rules/${ruleId}`,
          body,
          entityKeys: [
            entityKey('recurrenceRules', ruleId),
            ...victims.map((t) => entityKey('tasks', t.id)),
          ],
          kind: 'other',
          regenAfterPush: true,
          attempts: 0,
        });
      });
    },

    detachInstance: async (taskId: string): Promise<void> => {
      const row = await this.store.tasks.get(taskId);
      await this.writeTx([this.store.tasks], async () => {
        if (row) {
          await this.store.tasks.put({ ...row, recurrenceDetached: true, updatedAt: nowIso() });
        }
        await this.store.ops.add({
          method: 'PATCH',
          path: `/api/v1/recurrence/instances/${taskId}/detach`,
          entityKeys: [entityKey('tasks', taskId)],
          kind: 'other',
          attempts: 0,
        });
      });
    },

    ensureInstances: async (start: string, end: string): Promise<Task[]> => {
      if (!this.bridge.online()) return [];
      const rows = await this.bridge.ensureInstances(start, end);
      if (rows.length > 0) {
        await this.store.tasks.bulkPut(rows);
      }
      const subsByTask = new Map<string, SubtaskSyncRow[]>();
      // Generated instances carry no subtasks; map each row with an empty subtask list.
      return rows.map((r) => this.mapTask(r, subsByTask.get(r.id) ?? []));
    },

    getRule: async (ruleId: string): Promise<RecurrenceRule | null> => {
      const row = await this.store.recurrenceRules.get(ruleId);
      return row ? toRecurrenceRule(row as never) : null;
    },
  };

  // ── reports ────────────────────────────────────────────────
  reports = {
    getData: async (start: string, end: string): Promise<ReportData> => {
      const [tasks, focusSessions, dailyPlans, projects] = await Promise.all([
        this.store.tasks.toArray(),
        this.store.focusSessions.toArray(),
        this.store.dailyPlans.toArray(),
        this.store.projects.toArray(),
      ]);
      return buildReportData({ tasks, focusSessions, dailyPlans, projects }, start, end);
    },
  };
}

/** Subtasks sort by (sortOrder, id) — the server's join order (TasksEndpoints.cs:114). */
function byRankIdSubtasks(subs: SubtaskSyncRow[]): SubtaskSyncRow[] {
  return [...subs].sort(
    (a, b) => Number(a.sortOrder) - Number(b.sortOrder) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0),
  );
}
