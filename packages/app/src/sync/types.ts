/**
 * Sync op-queue types — cross-phase contract C2 of the SP3 plan.
 * The queue stores wire-shaped requests resolved at enqueue time (spec §3.1);
 * replay (R3) is a dumb FIFO loop over these records.
 */

export type OpKind = 'create' | 'other';

export interface SyncOp {
  /** Dexie `++seq` primary key — absent before insert. */
  seq?: number;
  method: 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  /** Resolved absolute API path, e.g. '/api/v1/tasks/3f2a…'. */
  path: string;
  /** Wire-shaped JSON body (request DTO), or undefined for body-less ops. */
  body?: unknown;
  /** '<table>:<key>' — 'tasks:<uuid>', 'dailyPlans:2026-06-11', 'settings:workStartHour'. */
  entityKeys: string[];
  /** 'create' enables adopt-existing + ghost-row recovery (spec §3.3). */
  kind: OpKind;
  /** Set on recurrence create + updateRule ops (GeneratedUntil reset → spec §4.3 trigger). */
  regenAfterPush?: true;
  /** Total attempts across cycles (parked at PARK_THRESHOLD, spec §3.3). */
  attempts: number;
  lastError?: string;
}

export interface SyncIssue {
  /** Dexie `++id` primary key — absent before insert. */
  id?: number;
  /** ISO timestamp of when the op was dropped/parked. */
  at: string;
  /** Snapshot of the dropped/parked op. */
  op: SyncOp;
  /** Human-readable reason (HTTP status + ProblemDetails title when present). */
  reason: string;
  /** Parked ops can be retried or discarded from the popover (spec §8). */
  status: 'dropped' | 'parked';
}

export interface SyncStatus {
  online: boolean;
  syncing: boolean;
  pendingOps: number;
  lastSyncedAt: string | null;
  issues: SyncIssue[];
  initialSyncComplete: boolean;
}

/** The nine synced Dexie tables (spec §2) — the table half of an entity key. */
export type SyncTable =
  | 'tasks'
  | 'subtasks'
  | 'projects'
  | 'noteGroups'
  | 'notes'
  | 'recurrenceRules'
  | 'focusSessions'
  | 'dailyPlans'
  | 'settings';

/**
 * Canonical entity key used in SyncOp.entityKeys, the ops multiEntry index,
 * and the §4.2 pull shadow rule. Key is each table's §2 primary key:
 * uuid for uuid entities, the date string for dailyPlans, the setting key for settings.
 */
export function entityKey(table: SyncTable, key: string): string {
  return `${table}:${key}`;
}
