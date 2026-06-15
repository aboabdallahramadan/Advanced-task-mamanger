/**
 * SyncEngine — single-flight push→(ensure-instances)→pull cycle (contract C5).
 * Push side: spec §3 (op replay, failure taxonomy, recovery, triggers, status).
 * Pull side: pullPhase() is a NO-OP STUB here — R4 fills it (cursor + applyPull +
 * recovery + initialSyncComplete gate). The cycle is structured now so R4 only
 * implements pullPhase without reshaping start/stop/single-flight.
 */

import type { LocalStore } from '../data/local/LocalStore';
import type { TaskSyncRow, SyncChanges } from '../data/local/rows';
import type { SyncTransport } from './SyncTransport';
import { applyPullPage } from '../data/local/applyPull';
import type { SyncBridge } from '../data/local/LocalDataClient';
import type { SyncIssue, SyncOp, SyncStatus, SyncTable } from './types';
import { entityKey } from './types';
import { createConnectivity, type Connectivity } from './connectivity';
import {
  CYCLE_5XX_BACKOFF_MS,
  CYCLE_5XX_RETRIES,
  ENSURE_HORIZON_DAYS,
  PARK_THRESHOLD,
  PERIODIC_SYNC_MS,
  PUSH_DEBOUNCE_MS,
  PULL_LIMIT,
  CURSOR_OVERLAP,
} from './constants';

export interface SyncEngineDeps {
  store: LocalStore;
  transport: SyncTransport;
  /** Defaults to createConnectivity(); injected in tests. */
  connectivity?: Connectivity;
  /** Lock name suffix; defaults to the store's DB-name tail. */
  userId?: string;
  /** Injected for fake timers in tests; defaults to setTimeout/clearTimeout. */
  setTimer?: (cb: () => void, ms: number) => ReturnType<typeof setTimeout>;
  clearTimer?: (h: ReturnType<typeof setTimeout>) => void;
  /** Awaitable delay (fake-timer friendly); defaults to setTimeout-based sleep. */
  sleep?: (ms: number) => Promise<void>;
}

/** Outcome of replaying one op, classified per spec §3.3. */
type OpOutcome =
  | { kind: 'done' }                 // 2xx (or 404-delete) → delete op, continue
  | { kind: 'network' }              // throw → abort push phase, queue intact
  | { kind: 'terminal' }             // 401 terminal (refresh dead) → stop engine
  | { kind: 'retry5xx' }             // 5xx, attempts under threshold → retry in-cycle
  | { kind: 'park' }                 // attempts >= PARK_THRESHOLD → move to issues
  | { kind: 'drop'; recover: boolean } // definitive 4xx → drop + issue (+recovery)
  | { kind: 'adopted' };             // 409 adopt-existing handled → op already removed

const TERMINAL_MESSAGE = 'Session expired — refresh failed';

export class SyncEngine implements SyncBridge {
  private readonly store: LocalStore;
  private readonly transport: SyncTransport;
  private readonly connectivity: Connectivity;
  private readonly userId: string;
  private readonly setTimer: (cb: () => void, ms: number) => ReturnType<typeof setTimeout>;
  private readonly clearTimer: (h: ReturnType<typeof setTimeout>) => void;
  private readonly sleep: (ms: number) => Promise<void>;

  private running = false;          // start() called, stop() not yet
  private terminal = false;         // 401-dead; no further cycles
  private cyclePromise: Promise<void> | null = null; // in-process single-flight
  private debounceHandle: ReturnType<typeof setTimeout> | null = null;
  private periodicHandle: ReturnType<typeof setTimeout> | null = null;
  private unsubConnectivity: (() => void) | null = null;
  private firstCycleSettled = false;
  /** Post-push ensure-instances rows buffered for R4's pull phase to drain (spec §4.3). */
  private pendingEnsureRows: TaskSyncRow[] | null = null;

  private statusListeners = new Set<(s: SyncStatus) => void>();
  private changesAppliedCbs: Array<() => void> = [];
  private firstCycleSettledCbs: Array<() => void> = [];

  /** Current snapshot fields not derived from tables on every emit (synchronous getStatus). */
  private syncing = false;
  /** Cached counts/values so getStatus() is synchronous (refreshed on every cycle/mutation). */
  private pendingOpsCount = 0;
  private cachedIssues: SyncIssue[] = [];
  private cachedLastSyncedAt: string | null = null;
  private cachedInitialSyncComplete = false;
  /** Serializes emitStatus() refresh→deliver so the last-scheduled emit wins (no out-of-order races). */
  private emitTail: Promise<void> = Promise.resolve();

  constructor(deps: SyncEngineDeps) {
    this.store = deps.store;
    this.transport = deps.transport;
    this.connectivity = deps.connectivity ?? createConnectivity();
    this.userId = deps.userId ?? deps.store.name.replace(/^tmap-/, '');
    this.setTimer = deps.setTimer ?? ((cb, ms) => setTimeout(cb, ms));
    this.clearTimer = deps.clearTimer ?? ((h) => clearTimeout(h));
    this.sleep = deps.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    // Prime the synchronous-getStatus cache from the store (best-effort; emitStatus refreshes it).
    void this.refreshSnapshot();
  }

  // ── lifecycle ──────────────────────────────────────────────
  start(): void {
    if (this.running) return;
    this.running = true;
    this.firstCycleSettled = false; // re-arm the settle guard per start() session
    this.unsubConnectivity = this.connectivity.subscribe((online) => {
      if (online) void this.syncNow();
    });
    this.armPeriodic();
    void this.syncNow(); // first cycle — runCycle's finally settles onFirstCycleSettled once
  }

  stop(): void {
    this.running = false;
    if (this.debounceHandle) {
      this.clearTimer(this.debounceHandle);
      this.debounceHandle = null;
    }
    if (this.periodicHandle) {
      this.clearTimer(this.periodicHandle);
      this.periodicHandle = null;
    }
    this.unsubConnectivity?.();
    this.unsubConnectivity = null;
  }

  private armPeriodic(): void {
    if (!this.running) return;
    this.periodicHandle = this.setTimer(() => {
      void this.syncNow();
      this.armPeriodic();
    }, PERIODIC_SYNC_MS);
  }

  // ── triggers ───────────────────────────────────────────────
  /** SyncBridge.nudge — the debounced push trigger (PUSH_DEBOUNCE_MS). Primary name. */
  nudge(): void {
    if (this.debounceHandle) this.clearTimer(this.debounceHandle);
    this.debounceHandle = this.setTimer(() => {
      this.debounceHandle = null;
      void this.syncNow();
    }, PUSH_DEBOUNCE_MS);
  }

  /** Kept back-compat alias of nudge() — both schedule the debounced cycle. */
  requestSync(): void {
    this.nudge();
  }

  online(): boolean {
    return this.connectivity.online();
  }

  // ── single-flight cycle ────────────────────────────────────
  /** Immediate cycle (manual retry / triggers). No-op if a cycle is already running. */
  syncNow(): Promise<void> {
    if (this.terminal) return Promise.resolve();
    if (this.cyclePromise) return this.cyclePromise;
    this.cyclePromise = this.withLock(() => this.runCycle()).finally(() => {
      this.cyclePromise = null;
    });
    return this.cyclePromise;
  }

  /**
   * Cross-tab single-flight via navigator.locks; falls back to the in-process
   * mutex (cyclePromise) when navigator.locks is unavailable (node tests).
   */
  private withLock(fn: () => Promise<void>): Promise<void> {
    const locks = (globalThis as { navigator?: { locks?: LockManager } }).navigator?.locks;
    if (locks && typeof locks.request === 'function') {
      return locks.request(`tmap-sync-${this.userId}`, () => fn());
    }
    return fn();
  }

  private async runCycle(): Promise<void> {
    this.syncing = true;
    this.emitStatus();
    try {
      // Offline → the cycle cannot even begin (spec §7.1.3): skip the network push/pull
      // phases entirely and fall through to the `finally`, which still settles
      // onFirstCycleSettled once so the rollover-deferral hook fires against local rows.
      // (With a real transport an offline cycle aborts on the first network throw; this
      // explicit guard makes the same "can't-start" outcome hold without touching the wire.)
      if (!this.online()) return;
      const replayed = await this.pushPhase();
      if (this.terminal) return; // 401 killed the engine mid-push
      if (replayed.some((o) => o.regenAfterPush)) {
        await this.runEnsureInstances();
      }
      await this.pullPhase(); // R4 fills this in
    } catch {
      // network aborts surface here as resolved (push returns), so a throw is
      // unexpected — swallow to keep the engine alive for the next trigger.
    } finally {
      this.syncing = false;
      if (!this.firstCycleSettled) {
        this.firstCycleSettled = true;
        for (const cb of this.firstCycleSettledCbs) cb();
      }
      this.emitStatus();
      // Wait for the final emit's refresh→deliver to land before resolving the cycle,
      // so callers awaiting syncNow() observe the settled snapshot synchronously.
      await this.emitTail;
    }
  }

  // ── push phase (spec §3.3) ─────────────────────────────────
  /** Drain the ops table FIFO; returns the ops successfully replayed (for §4.3). */
  private async pushPhase(): Promise<SyncOp[]> {
    const replayed: SyncOp[] = [];
    // FIFO by seq; re-read the head each iteration (recovery may have mutated the queue).
    for (;;) {
      const head = await this.store.ops.orderBy('seq').first();
      if (!head) break;

      let attempt = 0;
      let outcome = await this.replayOnce(head);

      // In-cycle 5xx retry ladder.
      while (outcome.kind === 'retry5xx' && attempt < CYCLE_5XX_RETRIES) {
        await this.sleep(CYCLE_5XX_BACKOFF_MS[attempt] ?? CYCLE_5XX_BACKOFF_MS[CYCLE_5XX_BACKOFF_MS.length - 1]);
        attempt++;
        outcome = await this.replayOnce(head);
      }

      switch (outcome.kind) {
        case 'done':
          await this.store.ops.delete(head.seq!);
          replayed.push(head);
          continue;
        case 'adopted':
          // adopt-existing already dropped this create op + rewrote the queue.
          continue;
        case 'network':
          return replayed; // abort push phase; queue intact; pull still runs
        case 'terminal':
          this.terminal = true;
          this.stop();
          return replayed;
        case 'retry5xx':
          // Exhausted in-cycle retries: persist the accumulated attempts/lastError
          // once (deferred off the hot path), abort the phase, resume next trigger.
          await this.store.ops.update(head.seq!, {
            attempts: head.attempts,
            lastError: head.lastError,
          });
          return replayed;
        case 'park':
          await this.parkOp(head);
          continue;
        case 'drop':
          await this.dropOp(head, outcome.recover);
          continue;
      }
    }
    return replayed;
  }

  /** Replay one op once and classify the result (no retry loop here). */
  private async replayOnce(op: SyncOp): Promise<OpOutcome> {
    let res: { status: number; body?: unknown };
    try {
      res = await this.transport.send(op);
    } catch (e) {
      return this.classifyThrow(e);
    }
    const s = res.status;

    if (s >= 200 && s < 300) return { kind: 'done' };

    // 404 on a delete op = already tombstoned → success (spec §3.2).
    if (s === 404 && op.method === 'DELETE') return { kind: 'done' };

    if (s === 409 && op.kind === 'create') {
      const existingId = this.existingIdFrom(res.body);
      if (existingId) {
        await this.adoptExisting(op, existingId);
        return { kind: 'adopted' };
      }
      // 409 without an existingId: treat as definitive drop + recovery.
      return { kind: 'drop', recover: true };
    }

    if (s >= 500) {
      // Account attempts in memory only on the hot retry path; pushPhase persists
      // once when the in-cycle ladder settles (abort/park). Persisting on every send
      // would interleave a real Dexie write (fake-indexeddb runs on setImmediate)
      // between the fake-timer backoff sleeps and deadlock vi.advanceTimersByTimeAsync.
      op.attempts = (op.attempts ?? 0) + 1;
      op.lastError = `HTTP ${s}`;
      return op.attempts >= PARK_THRESHOLD ? { kind: 'park' } : { kind: 'retry5xx' };
    }

    // Definitive 4xx (400/404-on-non-delete/403/etc., except the 401 path).
    if (s === 401) return { kind: 'terminal' };
    // Stamp the status so the dropped issue's reason carries it (C2 — "HTTP status
    // + ProblemDetails title"); e.g. an edit-vs-delete PATCH 404 surfaces as 'HTTP 404'.
    op.lastError = `HTTP ${s}`;
    return { kind: 'drop', recover: op.kind === 'create' };
  }

  /** Classify a thrown transport error: terminal 401 vs transient network (§7.3). */
  private classifyThrow(e: unknown): OpOutcome {
    const err = e as { name?: string; message?: string };
    if (err?.message === TERMINAL_MESSAGE) return { kind: 'terminal' };
    // NetworkError / TypeError (fetch) / transient refresh failure → network abort.
    return { kind: 'network' };
  }

  private existingIdFrom(body: unknown): string | null {
    const ext = (body as { extensions?: { existingId?: unknown } } | undefined)?.extensions;
    const id = ext?.existingId;
    return typeof id === 'string' ? id : null;
  }

  // ── definitive rejection: drop + ghost-row recovery (spec §3.3) ─────────
  /**
   * A definitive 4xx drops the op, records a `dropped` issue, and ALWAYS schedules
   * a recovery pull (the server diverged — e.g. edit-vs-delete on a PATCH — so we
   * must converge from since=0). The `recover` flag (true only for creates) gates
   * the additional ghost-row deletion + dependent-op dropping.
   */
  private async dropOp(op: SyncOp, recover: boolean): Promise<void> {
    await this.recordIssue(op, 'dropped', op.lastError ?? `HTTP rejection`);
    if (recover) {
      await this.recoverGhostRows(op);
    }
    await this.store.ops.delete(op.seq!);
    await this.scheduleRecovery();
  }

  /** Delete local rows a rejected create produced + drop queued ops referencing them. */
  private async recoverGhostRows(op: SyncOp): Promise<void> {
    for (const key of op.entityKeys) {
      const [table, id] = this.splitKey(key);
      if (!table) continue;
      await (this.store as unknown as Record<string, { delete(id: string): Promise<void> }>)[table]
        .delete(id)
        .catch(() => {});
      // Drop other queued ops that reference this ghost key (each recorded).
      const dependents = await this.store.ops.where('entityKeys').equals(key).toArray();
      for (const dep of dependents) {
        if (dep.seq === op.seq) continue;
        await this.recordIssue(dep, 'dropped', `ghost-row recovery: ${op.lastError ?? 'rejected create'}`);
        await this.store.ops.delete(dep.seq!);
      }
    }
  }

  /** Schedule a recovery pull from since=0 at the end of the cycle (R4 honors the flag). */
  private async scheduleRecovery(): Promise<void> {
    await this.store.setMeta('pendingRecovery', true);
  }

  // ── 409 adopt-existing (spec §3.3) ─────────────────────────
  /** Rewrite ghost create id → existingId across local rows, queued ops, then drop the create. */
  private async adoptExisting(createOp: SyncOp, existingId: string): Promise<void> {
    const [table, ghostId] = this.splitKey(createOp.entityKeys[0]);
    if (!table || !ghostId) {
      await this.store.ops.delete(createOp.seq!);
      return;
    }
    await this.store.transaction('rw', this.store.tables, async () => {
      // 1. Move the ghost row's data under existingId is unnecessary — the existing
      //    server row wins; we delete the ghost local row and repoint FKs.
      await (this.store as unknown as Record<string, { delete(id: string): Promise<void> }>)[table]
        .delete(ghostId)
        .catch(() => {});
      // 2. Repoint FKs on local rows (table-specific).
      await this.repointForeignKeys(table as SyncTable, ghostId, existingId);
      // 3. Rewrite queued op bodies/paths/entityKeys referencing the ghost id.
      const ghostKey = entityKey(table as SyncTable, ghostId);
      const newKey = entityKey(table as SyncTable, existingId);
      const ops = await this.store.ops.toArray();
      for (const o of ops) {
        if (o.seq === createOp.seq) continue;
        let changed = false;
        const newKeys = o.entityKeys.map((k) => {
          if (k === ghostKey) { changed = true; return newKey; }
          return k;
        });
        const newPath = o.path.includes(ghostId)
          ? o.path.split(ghostId).join(existingId)
          : o.path;
        const newBody = this.rewriteBodyIds(o.body, ghostId, existingId);
        if (changed || newPath !== o.path || newBody !== o.body) {
          await this.store.ops.update(o.seq!, { entityKeys: newKeys, path: newPath, body: newBody });
        }
      }
      // 4. Drop the create op itself.
      await this.store.ops.delete(createOp.seq!);
    });
    await this.recordIssue(
      createOp,
      'dropped',
      `409 — adopted existing ${table} ${existingId}`,
    );
  }

  /** Repoint child FKs from a ghost id to the adopted existingId. */
  private async repointForeignKeys(table: SyncTable, ghostId: string, existingId: string): Promise<void> {
    if (table === 'projects') {
      await this.repoint(this.store.tasks, 'projectId', ghostId, existingId);
      await this.repoint(this.store.notes, 'projectId', ghostId, existingId);
      await this.repoint(this.store.noteGroups, 'projectId', ghostId, existingId);
    } else if (table === 'noteGroups') {
      await this.repoint(this.store.notes, 'groupId', ghostId, existingId);
    } else if (table === 'tasks') {
      await this.repoint(this.store.subtasks, 'taskId', ghostId, existingId);
    } else if (table === 'recurrenceRules') {
      await this.repoint(this.store.tasks, 'recurrenceRuleId', ghostId, existingId);
    }
  }

  private async repoint<T>(
    tbl: { toArray(): Promise<T[]>; update(key: string, c: Partial<T>): Promise<number> },
    field: keyof T,
    from: string,
    to: string,
  ): Promise<void> {
    const rows = await tbl.toArray();
    for (const r of rows) {
      const rec = r as unknown as Record<string, unknown> & { id?: string };
      if (rec[field as string] === from && rec.id) {
        await tbl.update(rec.id, { [field]: to } as unknown as Partial<T>);
      }
    }
  }

  /** Deep-rewrite any string === ghostId to existingId in a queued op body. */
  private rewriteBodyIds(body: unknown, ghostId: string, existingId: string): unknown {
    if (body === ghostId) return existingId;
    if (Array.isArray(body)) return body.map((v) => this.rewriteBodyIds(v, ghostId, existingId));
    if (body && typeof body === 'object') {
      const out: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(body as Record<string, unknown>)) {
        out[k] = this.rewriteBodyIds(v, ghostId, existingId);
      }
      return out;
    }
    return body;
  }

  // ── poison-op parking (spec §3.3) ──────────────────────────
  private async parkOp(op: SyncOp): Promise<void> {
    await this.recordIssue(op, 'parked', op.lastError ?? `parked after ${op.attempts} attempts`);
    await this.store.ops.delete(op.seq!);
  }

  private async recordIssue(op: SyncOp, status: SyncIssue['status'], reason: string): Promise<void> {
    const issue: SyncIssue = { at: new Date().toISOString(), op: { ...op }, reason, status };
    await this.store.issues.add(issue);
  }

  // ── parked-op retry/discard (C5) ───────────────────────────
  async retryIssue(id: number): Promise<void> {
    const issue = await this.store.issues.get(id);
    if (!issue) return;
    const { seq, ...rest } = issue.op;
    await this.store.ops.add({ ...rest, attempts: 0, lastError: undefined });
    await this.store.issues.delete(id);
    this.emitStatus();
  }

  async discardIssue(id: number): Promise<void> {
    const issue = await this.store.issues.get(id);
    if (!issue) return;
    await this.store.issues.delete(id);
    if (issue.op.kind === 'create') await this.recoverGhostRows(issue.op);
    await this.scheduleRecovery();
    this.emitStatus();
  }

  // ── post-push ensure-instances (spec §4.3) ─────────────────
  private async runEnsureInstances(): Promise<void> {
    const today = new Date().toISOString().slice(0, 10);
    const end = new Date(Date.now() + ENSURE_HORIZON_DAYS * 86_400_000).toISOString().slice(0, 10);
    try {
      const rows = await this.transport.ensureInstances(today, end);
      // Buffer the returned instances; R4's pull phase drains pendingEnsureRows
      // through applyPull (shadow rule honored), then nulls it.
      this.pendingEnsureRows = rows;
    } catch {
      // ensure-instances failure is non-fatal; the next cycle retries.
    }
  }

  // ── pull phase (spec §4) ───────────────────────────────────
  /**
   * Pull phase (spec §4), in order (plan line 141/146):
   *   1. Drain the post-push ensure-instances buffer (`pendingEnsureRows`) through
   *      applyPull BEFORE the delta pull, then null it (§4.3 — makes an offline-created
   *      series' instances visible on reconnect).
   *   2. Delta pull, paged from the overlap floor (§4.1). The first cycle that runs this
   *      loop to completion (hasMore=false, uninterrupted) sets `initialSyncComplete`.
   *   3. Rejection recovery: if `pendingRecovery` is set (R3 scheduleRecovery / discardIssue),
   *      consume+clear it and run an extra paged pull from since=0 (§3.3) — snaps local
   *      state back to server truth incl. shadow-skipped tombstones lost past the cursor.
   * The stored cursor advances to max(prior, nextSince) and never regresses. changesApplied
   * fires once per cycle if any page applied rows; lastSyncedAt is stamped at the end.
   */
  protected async pullPhase(): Promise<void> {
    let anyApplied = false;

    // 1. Drain buffered post-push ensure-instances rows before the delta pull (§4.3).
    if (this.pendingEnsureRows && this.pendingEnsureRows.length > 0) {
      const applied = await applyPullPage(this.store, this.toChanges(this.pendingEnsureRows));
      anyApplied = anyApplied || applied;
    }
    this.pendingEnsureRows = null;

    // 2. Delta pull from the overlap floor. `reachedEnd` is true only if the paged loop
    //    terminated naturally at hasMore=false (a network throw would propagate, leaving it
    //    false → the initialSyncComplete gate stays closed on an interrupted initial sync).
    //    Read syncCursor together with the two gate flags in one parallel batch so the cycle
    //    reaches its settle (runCycle's finally → onFirstCycleSettled) in fewer serial Dexie
    //    roundtrips — important under fake timers, where fake-indexeddb runs on real
    //    setImmediate and each serial getMeta/setMeta costs an event-loop turn.
    const [priorRaw, alreadyComplete, recoveryPending] = await Promise.all([
      this.store.getMeta<number>('syncCursor'),
      this.store.getMeta<boolean>('initialSyncComplete'),
      this.store.getMeta<boolean>('pendingRecovery'),
    ]);
    const prior = priorRaw ?? 0;
    const delta = await this.pullPaged(Math.max(0, prior - CURSOR_OVERLAP), prior);
    anyApplied = anyApplied || delta.applied;

    // §4.1 — set the bootstrap-completeness gate once, the first time a full delta pass
    //   reaches hasMore=false. Cursor presence is NOT the signal (a crash mid-initial-sync
    //   leaves a partial prefix); reaching the end of the paged loop is. The authoritative
    //   pre-pull `alreadyComplete` read (not the cache, which may be mid-refresh) keeps the
    //   write idempotent and independent of emit ordering.
    if (delta.reachedEnd && !alreadyComplete) {
      await this.store.setMeta('initialSyncComplete', true);
      this.cachedInitialSyncComplete = true;
    }

    // 3. Rejection recovery (§3.3): consume+clear the persisted flag, then re-pull from 0.
    if (recoveryPending) {
      await this.store.setMeta('pendingRecovery', false);
      const recovery = await this.pullPaged(0, delta.cursor);
      anyApplied = anyApplied || recovery.applied;
    }

    await this.store.setMeta('lastSyncedAt', new Date().toISOString());

    if (anyApplied) {
      for (const cb of this.changesAppliedCbs) cb();
    }
  }

  /**
   * Run one paged pull starting at `since`, chaining nextSince while hasMore. Each page is
   * applied via applyPullPage; the cursor advances to max(startCursor, nextSince) and is
   * persisted after every page (never regresses). Returns whether any page applied rows, the
   * final cursor, and whether the loop terminated at hasMore=false (vs. throwing).
   */
  private async pullPaged(
    since: number,
    startCursor: number,
  ): Promise<{ applied: boolean; cursor: number; reachedEnd: boolean }> {
    let cursor = startCursor;
    let applied = false;
    let hasMore = true;
    while (hasMore) {
      const page = await this.transport.pull(since, PULL_LIMIT);
      const pageApplied = await applyPullPage(this.store, page.changes);
      applied = applied || pageApplied;
      // nextSince is int64 (typed number | string by the generator) — coerce.
      const nextSince = Number(page.nextSince);
      const advanced = Math.max(cursor, nextSince);
      // Persist the cursor only when it actually advances (it never regresses). An
      // empty/no-progress page (steady state) skips the write, trimming a serial Dexie
      // roundtrip so the cycle settles within the event-loop turns tests pump under fake
      // timers; the meta value is unchanged, so this is behavior-preserving.
      if (advanced !== cursor) {
        cursor = advanced;
        await this.store.setMeta('syncCursor', cursor);
      }
      since = nextSince;
      hasMore = page.hasMore;
    }
    return { applied, cursor, reachedEnd: true };
  }

  /** Wrap a flat list of pulled TaskSyncRows as a SyncChanges page (only the tasks field). */
  private toChanges(tasks: TaskSyncRow[]): SyncChanges {
    return {
      tasks,
      subtasks: [], projects: [], noteGroups: [], notes: [],
      recurrenceRules: [], focusSessions: [], dailyPlans: [], settings: [],
    } as SyncChanges;
  }

  // ── status + notifications ─────────────────────────────────
  subscribe(cb: (s: SyncStatus) => void): () => void {
    this.statusListeners.add(cb);
    cb(this.getStatus()); // fire once immediately with the current snapshot (synchronous)
    // Refresh the cache from the store, then emit again so the first delivery is fresh.
    void this.refreshSnapshot().then(() => cb(this.getStatus()));
    return () => {
      this.statusListeners.delete(cb);
    };
  }

  onChangesApplied(cb: () => void): () => void {
    this.changesAppliedCbs.push(cb);
    return () => {
      this.changesAppliedCbs = this.changesAppliedCbs.filter((c) => c !== cb);
    };
  }

  onFirstCycleSettled(cb: () => void): () => void {
    this.firstCycleSettledCbs.push(cb);
    return () => {
      this.firstCycleSettledCbs = this.firstCycleSettledCbs.filter((c) => c !== cb);
    };
  }

  /** SyncBridge passthrough (C4) — server call; throws offline. */
  ensureInstances(start: string, end: string): Promise<TaskSyncRow[]> {
    return this.transport.ensureInstances(start, end);
  }

  /**
   * SYNCHRONOUS current snapshot (C5) — built from the cached table-derived fields
   * refreshed by refreshSnapshot()/emitStatus(). Used by AppRoot's initial gate and
   * the Sign-out pendingOps read, which both need a value without awaiting Dexie.
   */
  getStatus(): SyncStatus {
    return {
      online: this.connectivity.online(),
      syncing: this.syncing,
      pendingOps: this.pendingOpsCount,
      lastSyncedAt: this.cachedLastSyncedAt,
      issues: this.cachedIssues,
      initialSyncComplete: this.cachedInitialSyncComplete,
    };
  }

  /** Re-read the table-derived status fields into the synchronous-getStatus cache. */
  private async refreshSnapshot(): Promise<void> {
    try {
      const [pendingOps, issues, lastSyncedAt, initialSyncComplete] = await Promise.all([
        this.store.ops.count(),
        this.store.issues.toArray(),
        this.store.getMeta<string>('lastSyncedAt'),
        this.store.getMeta<boolean>('initialSyncComplete'),
      ]);
      this.pendingOpsCount = pendingOps;
      this.cachedIssues = issues;
      this.cachedLastSyncedAt = lastSyncedAt ?? null;
      this.cachedInitialSyncComplete = initialSyncComplete ?? false;
    } catch {
      // The store may have been closed (logout/teardown) between scheduling and
      // running this refresh — keep the last cached snapshot rather than crashing.
    }
  }

  private emitStatus(): void {
    // Serialize through emitTail so concurrent emits deliver in scheduling order:
    // refresh the cache, then push a fresh getStatus() to every subscriber. The last
    // emit scheduled (e.g. the cycle's settled finally-emit) is therefore the last
    // delivered, even when an earlier in-cycle refresh resolves later.
    this.emitTail = this.emitTail.then(async () => {
      await this.refreshSnapshot();
      const s = this.getStatus();
      for (const cb of this.statusListeners) cb(s);
    });
  }

  private splitKey(key: string | undefined): [SyncTable | null, string] {
    if (!key) return [null, ''];
    const i = key.indexOf(':');
    if (i === -1) return [null, ''];
    return [key.slice(0, i) as SyncTable, key.slice(i + 1)];
  }
}

/** Minimal Web Locks shape (lib.dom may not include it in node typings). */
interface LockManager {
  request(name: string, cb: () => Promise<void>): Promise<void>;
}
