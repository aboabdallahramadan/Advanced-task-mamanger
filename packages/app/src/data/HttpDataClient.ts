import type { TmapClient } from '@tmap/api-client';
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
} from '../types';
import type { DataClient, ReorderInput, RecurrenceRuleInput } from './DataClient';
import { rankBetween } from './ranking';
import {
  toTask,
  fromTask,
  toSubtask,
  fromSubtaskUpdate,
  toProject,
  toNoteGroup,
  toNote,
  toFocusSession,
  toDailyPlan,
  toReportData,
  toRecurrenceRule,
  toServerFrequency,
  toServerEndType,
  parseSettings,
  stringifySettings,
} from './mappers';

/** crypto.randomUUID with a safe fallback (used for client-provided ids). */
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

/** Unwrap an openapi-fetch result; throw on transport/HTTP error. */
function unwrap<T>(res: { data?: T; error?: unknown }): T {
  if (res.error) {
    throw res.error;
  }
  return res.data as T;
}

/**
 * The only SP2 DataClient implementation. Talks to the API via @tmap/api-client
 * and converts every DTO<->domain shape through mappers.ts. Keeps a per-container
 * id->rank cache so reorder can compute a single fractional rank per moved row
 * (via ranking.rankBetween) instead of renumbering the whole list.
 */
export class HttpDataClient implements DataClient {
  private readonly api: TmapClient;

  // Per-container rank caches. Keyed by entity id.
  private taskRanks = new Map<string, string>();
  private projectRanks = new Map<string, string>();
  private noteGroupRanks = new Map<string, string>();
  private noteRanks = new Map<string, string>();

  constructor(client: TmapClient) {
    this.api = client;
  }

  // ── rank helpers ───────────────────────────────────────────
  /** Rebuild a cache from freshly-read rows (each row carries _rank). */
  private cacheRanks<T extends { id: string; _rank: string }>(
    cache: Map<string, string>,
    rows: T[],
  ): void {
    cache.clear();
    for (const r of rows) cache.set(r.id, r._rank);
  }

  /** Sort rows by their cached rank and assign sequential integer order. */
  private withOrder<T extends { id: string; _rank: string }>(rows: T[]): T[] {
    const sorted = [...rows].sort((a, b) => (a._rank < b._rank ? -1 : a._rank > b._rank ? 1 : 0));
    return sorted.map((r, i) => ({ ...r, order: i }));
  }

  /** Rank that sorts after the current max in a cache (for create/append). */
  private rankForAppend(cache: Map<string, string>): string {
    let max: string | null = null;
    for (const r of cache.values()) {
      if (max === null || r > max) max = r;
    }
    return rankBetween(max, null);
  }

  /**
   * Given the store's desired [{id, order}] and the current rank cache, compute
   * the minimal set of [{id, rank}] to send: for each item whose new neighbors
   * differ from a position consistent with its current rank, assign a fresh rank
   * strictly between the (already-finalized) previous item and the next item's
   * current rank. We process in target order and update the cache as we go.
   */
  private computeReorder(cache: Map<string, string>, items: ReorderInput[]): { id: string; rank: string }[] {
    const ordered = [...items].sort((a, b) => a.order - b.order);
    const result: { id: string; rank: string }[] = [];
    // Working ranks: start from current cache; finalized items overwrite theirs.
    const working = new Map(cache);
    let prevRank: string | null = null;

    for (let i = 0; i < ordered.length; i++) {
      const cur = ordered[i];
      const curRank = working.get(cur.id) ?? null;
      // Upper bound = the rank of the nearest later item (in target order) that
      // could plausibly stay put, i.e. whose current rank already sorts above the
      // finalized previous item. Later items whose rank is <= prevRank are
      // themselves out of place (they will be re-ranked above us) and must not
      // bound the current item — otherwise moving an item to the end would be
      // capped by a stale, lower rank.
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
        continue; // unchanged — no payload entry
      }

      const newRank = rankBetween(prevRank, nextRank);
      working.set(cur.id, newRank);
      cache.set(cur.id, newRank);
      result.push({ id: cur.id, rank: newRank });
      prevRank = newRank;
    }
    return result;
  }

  // ── tasks ──────────────────────────────────────────────────
  tasks = {
    getAll: async (): Promise<Task[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/tasks', {}));
      const mapped = rows.map((r) => ({ ...toTask(r), _rank: r.rank }));
      this.cacheRanks(this.taskRanks, mapped);
      return this.withOrder(mapped).map(({ _rank, ...t }) => t as Task);
    },
    getByDate: async (date: string): Promise<Task[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/tasks', { params: { query: { date } } }));
      const mapped = rows.map((r) => ({ ...toTask(r), _rank: r.rank }));
      // date-filtered reads do not own the global cache; merge ranks for these ids.
      for (const r of mapped) this.taskRanks.set(r.id, r._rank);
      return this.withOrder(mapped).map(({ _rank, ...t }) => t as Task);
    },
    create: async (t: Partial<Task>): Promise<Task> => {
      const body = fromTask(t) as Record<string, unknown>;
      if (body.id === undefined) body.id = newId();
      if (body.rank === undefined) body.rank = this.rankForAppend(this.taskRanks);
      const row = unwrap(
        await this.api.POST('/api/v1/tasks', { body: body as never }),
      );
      this.taskRanks.set(row.id, row.rank);
      return toTask(row);
    },
    update: async (id: string, u: Partial<Task>): Promise<Task> => {
      const row = unwrap(
        await this.api.PATCH('/api/v1/tasks/{id}', {
          params: { path: { id } },
          body: fromTask(u) as never,
        }),
      );
      this.taskRanks.set(row.id, row.rank);
      return toTask(row);
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/tasks/{id}', { params: { path: { id } } }));
      this.taskRanks.delete(id);
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      const payload = this.computeReorder(this.taskRanks, items);
      if (payload.length === 0) return;
      unwrap(await this.api.PATCH('/api/v1/tasks/reorder', { body: payload as never }));
    },
  };

  // ── subtasks ───────────────────────────────────────────────
  subtasks = {
    create: async (taskId: string, title: string): Promise<Subtask> => {
      const row = unwrap(
        await this.api.POST('/api/v1/tasks/{taskId}/subtasks', {
          params: { path: { taskId } },
          body: { title } as never,
        }),
      );
      return toSubtask(row, 0);
    },
    update: async (
      id: string,
      u: { title?: string; completed?: boolean; order?: number },
    ): Promise<void> => {
      unwrap(
        await this.api.PATCH('/api/v1/subtasks/{id}', {
          params: { path: { id } },
          body: fromSubtaskUpdate(u) as never,
        }),
      );
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/subtasks/{id}', { params: { path: { id } } }));
    },
  };

  // ── projects ───────────────────────────────────────────────
  projects = {
    getAll: async (): Promise<Project[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/projects', {}));
      const mapped = rows.map(toProject);
      this.cacheRanks(this.projectRanks, mapped);
      return this.withOrder(mapped).map(({ _rank, ...p }) => p as Project);
    },
    create: async (i: { name: string; color?: string; emoji?: string }): Promise<Project> => {
      const row = unwrap(
        await this.api.POST('/api/v1/projects', {
          body: {
            name: i.name,
            color: i.color ?? '#6366f1',
            emoji: i.emoji ?? '📁',
            rank: this.rankForAppend(this.projectRanks),
            id: newId(),
          } as never,
        }),
      );
      this.projectRanks.set(row.id, row.rank);
      const { _rank, ...p } = toProject(row);
      return p as Project;
    },
    update: async (id: string, u: Partial<Project>): Promise<Project> => {
      // PUT replaces all four mutable fields; merge against the cached/known state.
      const current = await this.projects.getAll().then((all) => all.find((p) => p.id === id));
      const row = unwrap(
        await this.api.PATCH('/api/v1/projects/{id}', {
          params: { path: { id } },
          body: {
            name: u.name ?? current?.name ?? '',
            color: u.color ?? current?.color ?? '#6366f1',
            emoji: u.emoji ?? current?.emoji ?? '📁',
            rank: this.projectRanks.get(id) ?? 'n',
          } as never,
        }),
      );
      this.projectRanks.set(row.id, row.rank);
      const { _rank, ...p } = toProject(row);
      return p as Project;
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/projects/{id}', { params: { path: { id } } }));
      this.projectRanks.delete(id);
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      const payload = this.computeReorder(this.projectRanks, items);
      if (payload.length === 0) return;
      unwrap(await this.api.PATCH('/api/v1/projects/reorder', { body: payload as never }));
    },
  };

  // ── note groups ────────────────────────────────────────────
  noteGroups = {
    getAll: async (): Promise<NoteGroup[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/note-groups', {}));
      const mapped = rows.map((r) => toNoteGroup(r));
      this.cacheRanks(this.noteGroupRanks, mapped);
      return this.withOrder(mapped).map(({ _rank, ...g }) => g as NoteGroup);
    },
    getByProject: async (projectId: string): Promise<NoteGroup[]> => {
      const rows = unwrap(
        await this.api.GET('/api/v1/note-groups', { params: { query: { projectId } } }),
      );
      const mapped = rows.map((r) => toNoteGroup(r));
      for (const g of mapped) this.noteGroupRanks.set(g.id, g._rank);
      return this.withOrder(mapped).map(({ _rank, ...g }) => g as NoteGroup);
    },
    create: async (i: { name: string; emoji?: string; projectId?: string }): Promise<NoteGroup> => {
      const row = unwrap(
        await this.api.POST('/api/v1/note-groups', {
          body: {
            name: i.name,
            emoji: i.emoji ?? null,
            projectId: i.projectId ?? null,
            rank: this.rankForAppend(this.noteGroupRanks),
            id: newId(),
          } as never,
        }),
      );
      this.noteGroupRanks.set(row.id, row.rank);
      const { _rank, ...g } = toNoteGroup(row);
      return g as NoteGroup;
    },
    update: async (id: string, u: Partial<NoteGroup>): Promise<NoteGroup> => {
      const row = unwrap(
        await this.api.PATCH('/api/v1/note-groups/{id}', {
          params: { path: { id } },
          body: {
            name: u.name ?? null,
            emoji: u.emoji ?? null,
            projectId: u.projectId === undefined ? null : u.projectId,
            rank: null,
          } as never,
        }),
      );
      this.noteGroupRanks.set(row.id, row.rank);
      const { _rank, ...g } = toNoteGroup(row);
      return g as NoteGroup;
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/note-groups/{id}', { params: { path: { id } } }));
      this.noteGroupRanks.delete(id);
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      const payload = this.computeReorder(this.noteGroupRanks, items);
      if (payload.length === 0) return;
      // Added in Q0 (§0.2): PATCH /api/v1/note-groups/reorder.
      unwrap(await this.api.PATCH('/api/v1/note-groups/reorder', { body: payload as never }));
    },
  };

  // ── notes ──────────────────────────────────────────────────
  notes = {
    getAll: async (): Promise<Note[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/notes', {}));
      const mapped = rows.map((r) => toNote(r));
      this.cacheRanks(this.noteRanks, mapped);
      return this.withOrder(mapped).map(({ _rank, ...n }) => n as Note);
    },
    getByGroup: async (groupId: string): Promise<Note[]> => {
      const rows = unwrap(await this.api.GET('/api/v1/notes', { params: { query: { groupId } } }));
      const mapped = rows.map((r) => toNote(r));
      for (const n of mapped) this.noteRanks.set(n.id, n._rank);
      return this.withOrder(mapped).map(({ _rank, ...n }) => n as Note);
    },
    getByProject: async (projectId: string): Promise<Note[]> => {
      const rows = unwrap(
        await this.api.GET('/api/v1/notes', { params: { query: { projectId } } }),
      );
      const mapped = rows.map((r) => toNote(r));
      for (const n of mapped) this.noteRanks.set(n.id, n._rank);
      return this.withOrder(mapped).map(({ _rank, ...n }) => n as Note);
    },
    getById: async (id: string): Promise<Note | null> => {
      const res = await this.api.GET('/api/v1/notes/{id}', { params: { path: { id } } });
      if (res.error) {
        return null;
      }
      const { _rank, ...n } = toNote(res.data as never);
      this.noteRanks.set(n.id, _rank);
      return n as Note;
    },
    create: async (i: {
      groupId?: string;
      projectId?: string;
      title?: string;
      content?: string;
    }): Promise<Note> => {
      const row = unwrap(
        await this.api.POST('/api/v1/notes', {
          body: {
            groupId: i.groupId ?? null,
            projectId: i.projectId ?? null,
            title: i.title ?? '',
            content: i.content ?? null,
            rank: this.rankForAppend(this.noteRanks),
            id: newId(),
          } as never,
        }),
      );
      this.noteRanks.set(row.id, row.rank);
      const { _rank, ...n } = toNote(row);
      return n as Note;
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
      const row = unwrap(
        await this.api.PATCH('/api/v1/notes/{id}', {
          params: { path: { id } },
          body: {
            groupId: u.groupId === undefined ? null : u.groupId,
            projectId: u.projectId === undefined ? null : u.projectId,
            title: u.title ?? null,
            content: u.content ?? null,
            rank: null,
          } as never,
        }),
      );
      this.noteRanks.set(row.id, row.rank);
      const { _rank, ...n } = toNote(row);
      return n as Note;
    },
    delete: async (id: string): Promise<void> => {
      unwrap(await this.api.DELETE('/api/v1/notes/{id}', { params: { path: { id } } }));
      this.noteRanks.delete(id);
    },
    reorder: async (items: ReorderInput[]): Promise<void> => {
      const payload = this.computeReorder(this.noteRanks, items);
      if (payload.length === 0) return;
      unwrap(await this.api.PATCH('/api/v1/notes/reorder', { body: payload as never }));
    },
  };

  // ── recurrence ─────────────────────────────────────────────
  recurrence = {
    create: async (task: Partial<Task>, rule: RecurrenceRuleInput): Promise<Task[]> => {
      unwrap(
        await this.api.POST('/api/v1/recurrence', {
          body: {
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
              id: task.id ?? newId(),
            },
            rule: {
              frequency: toServerFrequency(rule.frequency),
              interval: rule.interval,
              daysOfWeek: rule.daysOfWeek,
              endType: toServerEndType(rule.endType),
              endCount: rule.endCount ?? null,
              endDate: rule.endDate ?? null,
            },
          } as never,
        }),
      );
      // Per §2: the store does a targeted reload after create (the create endpoint
      // returns a summary RecurringTaskResponse, not full instances). Return the
      // freshly-read full task list so the store can replace state without stubs.
      return this.tasks.getAll();
    },
    updateSeries: async (ruleId: string, u: Partial<Task>): Promise<void> => {
      unwrap(
        await this.api.PATCH('/api/v1/recurrence/rules/{ruleId}/series', {
          params: { path: { ruleId } },
          body: {
            title: u.title ?? null,
            notes: u.notes ?? null,
            projectId: u.projectId === undefined ? null : u.projectId,
            labels: u.labels ?? null,
            durationMinutes: u.durationMinutes ?? null,
            priority: u.priority ?? null,
            reminderMinutes: u.reminderMinutes ?? null,
          } as never,
        }),
      );
    },
    deleteSeries: async (ruleId: string): Promise<void> => {
      unwrap(
        await this.api.DELETE('/api/v1/recurrence/rules/{ruleId}', {
          params: { path: { ruleId } },
        }),
      );
    },
    deleteSeriesFuture: async (ruleId: string, from: string): Promise<void> => {
      unwrap(
        await this.api.POST('/api/v1/recurrence/rules/{ruleId}/delete-future', {
          params: { path: { ruleId } },
          body: { fromDate: from } as never,
        }),
      );
    },
    detachInstance: async (taskId: string): Promise<void> => {
      unwrap(
        await this.api.PATCH('/api/v1/recurrence/instances/{taskId}/detach', {
          params: { path: { taskId } },
        }),
      );
    },
    ensureInstances: async (start: string, end: string): Promise<Task[]> => {
      // Q0 (§0.3) makes this return a bare full TaskResponse[]; map them as real Tasks.
      const created = unwrap(
        await this.api.POST('/api/v1/recurrence/ensure-instances', {
          params: { query: { start, end } },
        }),
      );
      const mapped = created.map((r) => ({ ...toTask(r), _rank: r.rank }));
      for (const r of mapped) this.taskRanks.set(r.id, r._rank);
      return mapped.map(({ _rank, ...t }) => t as Task);
    },
    updateRule: async (ruleId: string, u: RecurrenceRuleInput): Promise<void> => {
      unwrap(
        await this.api.PATCH('/api/v1/recurrence/rules/{ruleId}', {
          params: { path: { ruleId } },
          body: {
            frequency: toServerFrequency(u.frequency),
            interval: u.interval,
            daysOfWeek: u.daysOfWeek,
            endType: toServerEndType(u.endType),
            endCount: u.endCount ?? null,
            endDate: u.endDate ?? null,
          } as never,
        }),
      );
    },
    getRule: async (ruleId: string): Promise<RecurrenceRule | null> => {
      const res = await this.api.GET('/api/v1/recurrence/rules/{ruleId}', {
        params: { path: { ruleId } },
      });
      if (res.error) return null;
      return toRecurrenceRule(res.data as never);
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
      const row = unwrap(
        await this.api.POST('/api/v1/focus-sessions', {
          body: {
            taskId: s.taskId,
            project: s.project, // FocusSession.project is a name string (project name at session time)
            startedAt: s.startedAt,
            endedAt: s.endedAt,
            minutes: s.minutes,
            date: s.date,
          } as never,
        }),
      );
      return toFocusSession(row);
    },
  };

  // ── daily plans ────────────────────────────────────────────
  dailyPlans = {
    upsert: async (p: {
      date: string;
      plannedTaskIds: string[];
      plannedMinutes: number;
    }): Promise<DailyPlan> => {
      const row = unwrap(
        await this.api.PUT('/api/v1/daily-plans/{date}', {
          params: { path: { date: p.date } },
          body: { plannedTaskIds: p.plannedTaskIds, plannedMinutes: p.plannedMinutes } as never,
        }),
      );
      return toDailyPlan(row);
    },
    get: async (date: string): Promise<DailyPlan | null> => {
      const res = await this.api.GET('/api/v1/daily-plans/{date}', {
        params: { path: { date } },
      });
      if (res.error) return null;
      return toDailyPlan(res.data as never);
    },
  };

  // ── reports ────────────────────────────────────────────────
  reports = {
    getData: async (start: string, end: string): Promise<ReportData> => {
      const data = unwrap(
        await this.api.GET('/api/v1/reports', { params: { query: { start, end } } }),
      );
      return toReportData(data);
    },
  };

  // ── settings ───────────────────────────────────────────────
  settings = {
    get: async (): Promise<{ settings: Record<string, unknown>; timeZoneId: string }> => {
      const data = unwrap(await this.api.GET('/api/v1/settings', {}));
      return { settings: parseSettings(data.settings ?? {}), timeZoneId: data.timeZoneId };
    },
    save: async (s: Record<string, unknown>, timeZoneId?: string): Promise<void> => {
      const body: { settings: Record<string, string>; timeZoneId?: string } = {
        settings: stringifySettings(s),
      };
      if (timeZoneId !== undefined) body.timeZoneId = timeZoneId;
      unwrap(await this.api.PUT('/api/v1/settings', { body: body as never }));
    },
  };
}
