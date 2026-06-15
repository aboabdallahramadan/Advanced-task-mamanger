/**
 * applyPull — apply one pulled page (SyncChanges) into the local store
 * in a single Dexie rw transaction. Spec §4.2, contract C5.
 *
 * Per table, per row:
 *   - deletedAt != null  → delete the local row by that table's key (tombstone;
 *                          local tables hold live rows only, spec §2);
 *   - otherwise          → put (idempotent upsert-by-key).
 *
 * Shadow rule: skip any row whose entity key ('<table>:<key>') appears in ANY
 * pending op's entityKeys. The cycle is push-then-pull so the queue is normally
 * empty here, but this protects a write made mid-pull; the next cycle reconciles
 * it. (A shadow-skipped tombstone whose op is later dropped is re-delivered by
 * the rejection-recovery pull from since=0, spec §3.3.)
 *
 * Returns true if any row was actually written or deleted (used by the engine to
 * emit `changesApplied` at most once per cycle).
 */

import type { LocalStore } from './LocalStore';
import { entityKey, type SyncTable } from '../../sync/types';
import type { SyncChanges } from './rows';

/** A pulled row carries at least a key field, changeSeq, and deletedAt. */
interface PulledRow {
  changeSeq?: number;
  deletedAt?: string | null;
  [field: string]: unknown;
}

/**
 * Map each SyncChanges field to its Dexie table name and the property that holds
 * the table's primary key. Mirrors the C3 schema: id for the seven uuid tables,
 * `date` for dailyPlans, `key` for settings.
 */
const TABLE_SPECS: ReadonlyArray<{
  field: keyof SyncChanges;
  table: SyncTable;
  keyProp: 'id' | 'date' | 'key';
}> = [
  { field: 'tasks', table: 'tasks', keyProp: 'id' },
  { field: 'subtasks', table: 'subtasks', keyProp: 'id' },
  { field: 'projects', table: 'projects', keyProp: 'id' },
  { field: 'noteGroups', table: 'noteGroups', keyProp: 'id' },
  { field: 'notes', table: 'notes', keyProp: 'id' },
  { field: 'recurrenceRules', table: 'recurrenceRules', keyProp: 'id' },
  { field: 'focusSessions', table: 'focusSessions', keyProp: 'id' },
  { field: 'dailyPlans', table: 'dailyPlans', keyProp: 'date' },
  { field: 'settings', table: 'settings', keyProp: 'key' },
];

export async function applyPullPage(store: LocalStore, changes: SyncChanges): Promise<boolean> {
  // All nine entity tables + ops (the shadow read) in one rw transaction.
  // Array overload (matches the repo convention in LocalDataClient/SyncEngine);
  // the variadic form caps at 6 typed args under Dexie's declarations.
  return store.transaction(
    'rw',
    [
      store.tasks,
      store.subtasks,
      store.projects,
      store.noteGroups,
      store.notes,
      store.recurrenceRules,
      store.focusSessions,
      store.dailyPlans,
      store.settings,
      store.ops,
    ],
    async () => {
      // Build the shadow set once per page from the ops multiEntry index.
      const shadow = new Set<string>();
      await store.ops.each((op) => {
        for (const k of op.entityKeys) shadow.add(k);
      });

      let applied = false;

      for (const spec of TABLE_SPECS) {
        const rows = (changes[spec.field] ?? []) as PulledRow[];
        if (rows.length === 0) continue;
        const dexieTable = store.table(spec.table);

        for (const row of rows) {
          const keyValue = row[spec.keyProp] as string;
          if (shadow.has(entityKey(spec.table, keyValue))) {
            continue; // shadow rule — a pending op owns this entity key
          }
          if (row.deletedAt != null) {
            await dexieTable.delete(keyValue);
          } else {
            await dexieTable.put(row);
          }
          applied = true;
        }
      }

      return applied;
    },
  );
}
