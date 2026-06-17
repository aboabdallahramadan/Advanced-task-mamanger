/**
 * fakeSyncServer.ts — in-memory scripted backend for SP3 sync tests (HARNESS, not
 * production). Reused by R3 push, R4 pull/recovery, and R6 convergence (see
 * .sp3-plan-parts/07-r6.md). Lives under __tests__/ but is NOT a *.test.ts file,
 * so vitest does not run it as a suite, yet tsc --noEmit DOES compile it — keep it
 * strict-clean. Surface relied on elsewhere (C5a): new FakeSyncServer(), seed,
 * transport, apply, tombstone, bumpSeq, failNext, pullCount, latency. (No
 * rejectNext — rejections go through failNext(matcher, { status, body }).)
 *
 * Rows are stored in server sync-DTO wire shape (rows.ts): each table is a Map by
 * primary key; a global changeSeq counter stamps every write; tombstones set
 * deletedAt and keep the row (pull delivers tombstones). The op→effect mapping
 * mirrors the REST semantics the engine replays against (spec §3, §4.3).
 */

import type { SyncOp } from '../types';
import type { SyncResponse, TaskSyncRow } from '../../data/local/rows';

type Row = Record<string, unknown> & { id?: string; changeSeq: number; deletedAt: string | null };
type Fault = { status: number } | 'network';
type Matcher = (op: SyncOp) => boolean;

const PULL_TABLES = [
  'tasks', 'subtasks', 'projects', 'noteGroups', 'notes',
  'recurrenceRules', 'focusSessions', 'dailyPlans', 'settings',
] as const;
type PullTable = (typeof PULL_TABLES)[number];

interface Rule {
  id: string;
  frequency: string;
  interval: number;
  daysOfWeek: number[];
  endType: string;
  endDate: string | null;
  generatedUntil: string | null;
}

export class FakeSyncServer {
  private seq = 0;
  private tables: Record<PullTable, Map<string, Row>> = {
    tasks: new Map(), subtasks: new Map(), projects: new Map(), noteGroups: new Map(),
    notes: new Map(), recurrenceRules: new Map(), focusSessions: new Map(),
    dailyPlans: new Map(), settings: new Map(),
  };
  private rules = new Map<string, Rule>();
  private faults: { match: Matcher; fault: Fault }[] = [];
  private latencyMs = 0;
  private pulls = 0;
  /** One-shot: the next pull() returns the full-resync directive (C9), then clears. */
  private directiveOnce = false;

  private next(): number {
    return ++this.seq;
  }

  /**
   * Arm a single full-resync directive (C9): the NEXT pull() returns
   * { changes: empty, nextSince: <high-water>, hasMore: false, fullResyncRequired: true }
   * regardless of `since`, then disarms. Mirrors the server's behavior when the
   * client's cursor has fallen below the purge watermark.
   */
  requireFullResyncOnce(): void {
    this.directiveOnce = true;
  }

  /** Awaited before every send/pull/ensureInstances (default no delay). */
  private async delay(): Promise<void> {
    if (this.latencyMs > 0) await new Promise((r) => setTimeout(r, this.latencyMs));
  }

  /** Set an artificial per-call delay (ms) for send/pull/ensureInstances (C5a). */
  latency(ms: number): void {
    this.latencyMs = ms;
  }

  /** Number of pull() calls served so far (assertion helper, C5a). */
  pullCount(): number {
    return this.pulls;
  }

  private keyOf(table: PullTable): string {
    if (table === 'dailyPlans') return 'date';
    if (table === 'settings') return 'key';
    return 'id';
  }

  /** Insert a wire-shaped row, stamping the next changeSeq (test setup helper). */
  seed(table: PullTable, row: Record<string, unknown>): void {
    const k = this.keyOf(table);
    const stamped: Row = { ...(row as object), changeSeq: this.next(), deletedAt: null } as Row;
    this.tables[table].set(String((stamped as Record<string, unknown>)[k]), stamped);
  }

  /** Queue a single-use fault matched against the next op that satisfies `match`. */
  failNext(match: Matcher, fault: Fault): void {
    this.faults.push({ match, fault });
  }

  /**
   * Server-side soft-delete (C5a): tombstone an existing row by key + cascade,
   * stamping a fresh changeSeq (simulates a remote device deleting the row).
   */
  tombstone(table: PullTable, key: string): void {
    const row = this.tables[table].get(key);
    if (!row || row.deletedAt !== null) return;
    const ts = new Date().toISOString();
    this.tables[table].set(key, { ...row, deletedAt: ts, changeSeq: this.next() });
    this.cascadeDelete(table, key, ts);
  }

  /**
   * Server-side field change (C5a): apply a patch (or a no-op touch) to an existing
   * row and stamp a fresh changeSeq — simulates "another device wrote" without an op.
   */
  bumpSeq(table: PullTable, key: string, patch: Record<string, unknown> = {}): void {
    const row = this.tables[table].get(key);
    if (!row) return;
    this.tables[table].set(key, { ...row, ...patch, changeSeq: this.next() });
  }

  private takeFault(op: SyncOp): Fault | null {
    const i = this.faults.findIndex((f) => f.match(op));
    if (i === -1) return null;
    const [{ fault }] = this.faults.splice(i, 1);
    return fault;
  }

  transport() {
    const self = this;
    return {
      async send(op: SyncOp): Promise<{ status: number; body?: unknown }> {
        await self.delay();
        const fault = self.takeFault(op);
        if (fault === 'network') throw Object.assign(new TypeError('Failed to fetch'), { name: 'TypeError' });
        if (fault) return { status: fault.status, body: { title: `HTTP ${fault.status}` } };
        return self.apply(op);
      },
      async pull(since: number, limit: number): Promise<SyncResponse> {
        await self.delay();
        self.pulls += 1;
        return self.pullPage(since, limit);
      },
      async ensureInstances(start: string, end: string): Promise<TaskSyncRow[]> {
        await self.delay();
        return self.ensure(start, end);
      },
    };
  }

  // ── op application ─────────────────────────────────────────
  /** Internal REST semantics for one op (C5a); also used by transport.send. */
  apply(op: SyncOp): { status: number; body?: unknown } {
    const { method, path } = op;
    const segs = path.replace(/^\/api\/v1\//, '').split('/');

    // POST /recurrence (series create)
    if (method === 'POST' && path === '/api/v1/recurrence') {
      const b = op.body as { task: Record<string, unknown>; rule: Record<string, unknown> };
      const rule = b.rule;
      const ruleId = String(rule.id);
      this.rules.set(ruleId, {
        id: ruleId,
        frequency: String(rule.frequency),
        interval: Number(rule.interval ?? 1),
        daysOfWeek: (rule.daysOfWeek as number[]) ?? [],
        endType: String(rule.endType),
        endDate: (rule.endDate as string | null) ?? null,
        generatedUntil: null,
      });
      this.tables.recurrenceRules.set(ruleId, {
        ...rule, id: ruleId, generatedUntil: null, changeSeq: this.next(), deletedAt: null,
      } as Row);
      const tid = String(b.task.id);
      const existed = this.tables.tasks.get(tid);
      if (existed && existed.deletedAt === null) return { status: 200, body: existed };
      // Stamp the template server-side exactly as RecurrenceEndpoints.Create does (the
      // request body's `task` carries only client fields — title/notes/… — NOT the
      // server-derived recurrenceRuleId / isRecurrenceTemplate / status / rank). Without
      // this, ensure() can't find the template and materializes zero instances.
      const startDate = (b.task.plannedDate as string | null) ?? null;
      this.tables.tasks.set(tid, {
        ...b.task, id: tid, recurrenceRuleId: ruleId, isRecurrenceTemplate: true,
        recurrenceDetached: false, recurrenceOriginalDate: startDate, status: 'Planned',
        rank: 'a0', changeSeq: this.next(), deletedAt: null,
      } as Row);
      return { status: 201, body: this.tables.tasks.get(tid) };
    }

    // PATCH /recurrence/rules/{id} (updateRule → resets generatedUntil)
    if (method === 'PATCH' && segs[0] === 'recurrence' && segs[1] === 'rules' && segs.length === 3) {
      const ruleId = segs[2];
      const r = this.rules.get(ruleId);
      const row = this.tables.recurrenceRules.get(ruleId);
      if (!r || !row || row.deletedAt !== null) return { status: 404, body: { title: 'Not found' } };
      r.generatedUntil = null;
      this.tables.recurrenceRules.set(ruleId, {
        ...row, ...(op.body as object), generatedUntil: null, changeSeq: this.next(),
      } as Row);
      return { status: 200, body: this.tables.recurrenceRules.get(ruleId) };
    }

    // subtask create: POST /tasks/{taskId}/subtasks
    if (method === 'POST' && segs[0] === 'tasks' && segs[2] === 'subtasks') {
      return this.create('subtasks', op.body as Record<string, unknown>);
    }

    // reorder: PATCH /{table}/reorder
    if (method === 'PATCH' && segs.length === 2 && segs[1] === 'reorder') {
      const table = this.tableFromSeg(segs[0]);
      if (!table) return { status: 404, body: { title: 'Unknown table' } };
      for (const { id, rank } of op.body as { id: string; rank: string }[]) {
        const row = this.tables[table].get(id);
        if (row && row.deletedAt === null) {
          this.tables[table].set(id, { ...row, rank, changeSeq: this.next() });
        }
      }
      return { status: 200 };
    }

    // create: POST /{table}
    if (method === 'POST' && segs.length === 1) {
      const table = this.tableFromSeg(segs[0]);
      if (!table) return { status: 404, body: { title: 'Unknown table' } };
      if (table === 'projects') {
        const body = op.body as Record<string, unknown>;
        for (const ex of this.tables.projects.values()) {
          if (ex.deletedAt === null && ex.name === body.name && ex.id !== body.id) {
            return { status: 409, body: { title: 'Duplicate name', extensions: { existingId: ex.id } } };
          }
        }
      }
      return this.create(table, op.body as Record<string, unknown>);
    }

    // PUT /daily-plans/{date}
    if (method === 'PUT' && segs[0] === 'daily-plans' && segs.length === 2) {
      const date = segs[1];
      const body = op.body as { plannedTaskIds: string[]; plannedMinutes: number };
      this.tables.dailyPlans.set(date, {
        date, plannedTaskIds: body.plannedTaskIds, plannedMinutes: body.plannedMinutes,
        committedAt: new Date().toISOString(), changeSeq: this.next(), deletedAt: null,
      } as Row);
      return { status: 200 };
    }

    // PUT /settings (per-key)
    if (method === 'PUT' && path === '/api/v1/settings') {
      const body = op.body as { settings?: Record<string, string> };
      for (const [key, value] of Object.entries(body.settings ?? {})) {
        this.tables.settings.set(key, { key, value, changeSeq: this.next(), deletedAt: null } as Row);
      }
      return { status: 200 };
    }

    // PATCH /{table}/{id}
    if (method === 'PATCH' && segs.length === 2) {
      const table = this.tableFromSeg(segs[0]);
      if (!table) return { status: 404, body: { title: 'Unknown table' } };
      const row = this.tables[table].get(segs[1]);
      if (!row || row.deletedAt !== null) return { status: 404, body: { title: 'Not found' } };
      const patch = op.body as Record<string, unknown>;
      const merged: Row = { ...row, changeSeq: this.next() };
      for (const [k, v] of Object.entries(patch)) merged[k] = v; // explicit null = clear
      this.tables[table].set(segs[1], merged);
      return { status: 200, body: merged };
    }

    // DELETE /{table}/{id}
    if (method === 'DELETE' && segs.length === 2) {
      const table = this.tableFromSeg(segs[0]);
      if (!table) return { status: 404, body: { title: 'Unknown table' } };
      const row = this.tables[table].get(segs[1]);
      if (!row || row.deletedAt !== null) return { status: 404, body: { title: 'Not found' } };
      const ts = new Date().toISOString();
      this.tables[table].set(segs[1], { ...row, deletedAt: ts, changeSeq: this.next() });
      this.cascadeDelete(table, segs[1], ts);
      return { status: 204 };
    }

    return { status: 404, body: { title: `Unhandled ${method} ${path}` } };
  }

  private tableFromSeg(seg: string): PullTable | null {
    switch (seg) {
      case 'tasks': return 'tasks';
      case 'projects': return 'projects';
      case 'note-groups': return 'noteGroups';
      case 'notes': return 'notes';
      case 'focus-sessions': return 'focusSessions';
      case 'subtasks': return 'subtasks';
      case 'recurrence-rules': return 'recurrenceRules';
      default: return null;
    }
  }

  private create(table: PullTable, body: Record<string, unknown>): { status: number; body?: unknown } {
    const k = this.keyOf(table);
    const id = String(body[k]);
    const existing = this.tables[table].get(id);
    if (existing) return { status: 200, body: existing }; // PK-idempotent (incl. tombstoned)
    const row: Row = { ...body, changeSeq: this.next(), deletedAt: null } as Row;
    this.tables[table].set(id, row);
    return { status: 201, body: row };
  }

  private cascadeDelete(table: PullTable, id: string, ts: string): void {
    if (table === 'tasks') {
      for (const [sid, st] of this.tables.subtasks) {
        if (st.taskId === id && st.deletedAt === null) {
          this.tables.subtasks.set(sid, { ...st, deletedAt: ts, changeSeq: this.next() });
        }
      }
    } else if (table === 'noteGroups') {
      for (const [nid, nt] of this.tables.notes) {
        if (nt.groupId === id && nt.deletedAt === null) {
          this.tables.notes.set(nid, { ...nt, deletedAt: ts, changeSeq: this.next() });
        }
      }
    } else if (table === 'projects') {
      for (const map of [this.tables.tasks, this.tables.notes, this.tables.noteGroups]) {
        for (const [cid, ch] of map) {
          if (ch.projectId === id && ch.deletedAt === null) {
            map.set(cid, { ...ch, projectId: null, changeSeq: this.next() });
          }
        }
      }
    }
  }

  // ── recurrence ensure-instances ────────────────────────────
  private ensure(start: string, end: string): TaskSyncRow[] {
    const created: TaskSyncRow[] = [];
    for (const rule of this.rules.values()) {
      const template = [...this.tables.tasks.values()].find(
        (t) => t.recurrenceRuleId === rule.id && t.isRecurrenceTemplate && t.deletedAt === null,
      );
      if (!template) continue;
      const from = rule.generatedUntil && rule.generatedUntil > start ? rule.generatedUntil : start;
      for (const date of datesInRange(from, end, rule)) {
        const id = `${rule.id}:${date}`;
        if (this.tables.tasks.has(id)) continue;
        const row: Row = {
          ...(template as object), id, isRecurrenceTemplate: false, plannedDate: date,
          recurrenceOriginalDate: date, rank: 'a0', completedAt: null,
          changeSeq: this.next(), deletedAt: null,
        } as Row;
        this.tables.tasks.set(id, row);
        created.push(row as unknown as TaskSyncRow);
      }
      rule.generatedUntil = end;
      const ruleRow = this.tables.recurrenceRules.get(rule.id);
      if (ruleRow) {
        this.tables.recurrenceRules.set(rule.id, { ...ruleRow, generatedUntil: end, changeSeq: this.next() });
      }
    }
    return created;
  }

  // ── pull ───────────────────────────────────────────────────
  private pullPage(since: number, limit: number): SyncResponse {
    if (this.directiveOnce) {
      this.directiveOnce = false;
      const empty = (): Row[] => [];
      const changes: Record<PullTable, Row[]> = {
        tasks: empty(), subtasks: empty(), projects: empty(), noteGroups: empty(), notes: empty(),
        recurrenceRules: empty(), focusSessions: empty(), dailyPlans: empty(), settings: empty(),
      };
      return { changes, nextSince: this.seq, hasMore: false, fullResyncRequired: true } as unknown as SyncResponse;
    }
    const all: { table: PullTable; row: Row }[] = [];
    for (const table of PULL_TABLES) {
      for (const row of this.tables[table].values()) {
        if (row.changeSeq > since) all.push({ table, row });
      }
    }
    all.sort((a, b) => a.row.changeSeq - b.row.changeSeq);
    const hasMore = all.length > limit;
    const page = all.slice(0, limit);
    const empty = (): Row[] => [];
    const changes: Record<PullTable, Row[]> = {
      tasks: empty(), subtasks: empty(), projects: empty(), noteGroups: empty(), notes: empty(),
      recurrenceRules: empty(), focusSessions: empty(), dailyPlans: empty(), settings: empty(),
    };
    for (const { table, row } of page) changes[table].push(row);
    const nextSince = page.length > 0 ? page[page.length - 1].row.changeSeq : since;
    return { changes, nextSince, hasMore, fullResyncRequired: false } as unknown as SyncResponse;
  }
}

/** Enumerate occurrence dates in [from, end] for a (daily|weekly) rule. */
function datesInRange(from: string, end: string, rule: Rule): string[] {
  const out: string[] = [];
  const start = new Date(`${from}T00:00:00Z`);
  const last = new Date(`${end}T00:00:00Z`);
  const daily = rule.frequency.toLowerCase() === 'daily';
  const interval = Math.max(1, rule.interval);
  for (let d = new Date(start); d <= last; d.setUTCDate(d.getUTCDate() + 1)) {
    const iso = d.toISOString().slice(0, 10);
    if (iso <= from && from !== start.toISOString().slice(0, 10)) continue;
    if (daily) {
      out.push(iso);
    } else {
      const dow = d.getUTCDay();
      if (rule.daysOfWeek.length === 0 || rule.daysOfWeek.includes(dow)) {
        if (((d.getTime() - start.getTime()) / 86_400_000) % (7 * interval) >= 0) out.push(iso);
      }
    }
  }
  return out;
}
