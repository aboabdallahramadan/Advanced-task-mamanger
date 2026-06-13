/**
 * LocalStore — per-user Dexie (IndexedDB) database `tmap-{userId}`.
 * Cross-phase contract C3 of the SP3 plan; spec §2.
 *
 * Tables hold LIVE rows only, in server sync-DTO wire shape (rows.ts):
 * applying a pulled tombstone deletes the local row; a local delete removes
 * the row immediately (the op queue carries the intent). Locally-created
 * rows synthesize the wire shape with `changeSeq: 0`, `deletedAt: null`.
 * `close()` is inherited from Dexie.
 */

import Dexie, { type Table } from 'dexie';
import type { SyncIssue, SyncOp } from '../../sync/types';
import type {
  DailyPlanSyncRow,
  FocusSessionSyncRow,
  NoteGroupSyncRow,
  NoteSyncRow,
  ProjectSyncRow,
  RecurrenceRuleSyncRow,
  SettingSyncRow,
  SubtaskSyncRow,
  TaskSyncRow,
} from './rows';

/** meta rows: syncCursor · initialSyncComplete · lastSyncedAt · lastUser {id,email,timeZoneId}. */
export interface MetaRow {
  key: string;
  value: unknown;
}

export class LocalStore extends Dexie {
  tasks!: Table<TaskSyncRow, string>;
  subtasks!: Table<SubtaskSyncRow, string>;
  projects!: Table<ProjectSyncRow, string>;
  noteGroups!: Table<NoteGroupSyncRow, string>;
  notes!: Table<NoteSyncRow, string>;
  recurrenceRules!: Table<RecurrenceRuleSyncRow, string>;
  focusSessions!: Table<FocusSessionSyncRow, string>;
  dailyPlans!: Table<DailyPlanSyncRow, string>;
  settings!: Table<SettingSyncRow, string>;
  ops!: Table<SyncOp, number>;
  issues!: Table<SyncIssue, number>;
  meta!: Table<MetaRow, string>;

  private constructor(userId: string) {
    super(`tmap-${userId}`);
    this.version(1).stores({
      tasks: 'id, plannedDate, status, recurrenceRuleId',
      subtasks: 'id, taskId',
      projects: 'id',
      noteGroups: 'id',
      notes: 'id, groupId, projectId',
      recurrenceRules: 'id',
      focusSessions: 'id, date',
      dailyPlans: 'date',
      settings: 'key',
      ops: '++seq, *entityKeys',
      issues: '++id',
      meta: 'key',
    });
  }

  static open(userId: string): LocalStore {
    return new LocalStore(userId);
  }

  /** Deletes the per-user database entirely (explicit logout, spec §7.2). */
  static async wipe(userId: string): Promise<void> {
    await Dexie.delete(`tmap-${userId}`);
  }

  async getMeta<T>(key: string): Promise<T | undefined> {
    const row = await this.meta.get(key);
    return row === undefined ? undefined : (row.value as T);
  }

  async setMeta(key: string, value: unknown): Promise<void> {
    await this.meta.put({ key, value });
  }
}

// ---------------------------------------------------------------------------
// Global pointer: which per-user DB to open at offline bootstrap (spec §2, §7.2).
// Lives OUTSIDE the per-user DB by design — it breaks the chicken-and-egg of
// needing a userId before any network identity is available.
// ---------------------------------------------------------------------------

const LAST_USER_KEY = 'tmap:lastUserId';

/** Same storage-resolution trick as lib/localPrefs.ts — node tests inject a Storage. */
function resolveStorage(explicit?: Storage): Storage | null {
  if (explicit) return explicit;
  try {
    return typeof localStorage !== 'undefined' ? localStorage : null;
  } catch {
    return null;
  }
}

/** Last successfully-authed user id, or null. Never throws. */
export function getLastUserId(storage?: Storage): string | null {
  const s = resolveStorage(storage);
  if (!s) return null;
  try {
    return s.getItem(LAST_USER_KEY);
  } catch {
    return null;
  }
}

/**
 * Written on every successful auth; cleared (pass null) ONLY on explicit
 * logout — session expiry keeps the pointer so re-login reuses the DB (C8.3).
 * Never throws.
 */
export function setLastUserId(id: string | null, storage?: Storage): void {
  const s = resolveStorage(storage);
  if (!s) return;
  try {
    if (id === null) s.removeItem(LAST_USER_KEY);
    else s.setItem(LAST_USER_KEY, id);
  } catch {
    // storage full / blocked → ignore
  }
}
