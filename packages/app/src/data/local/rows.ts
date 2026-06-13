/**
 * rows.ts — the wire shapes local rows are stored in (spec §2, contract C6).
 *
 * Type-only re-exports of the R0-regenerated sync DTO schemas. Each *SyncRow
 * mirrors its *Response minus nested children (TaskSyncRow has no subtasks
 * list) plus `changeSeq` and `deletedAt`. Locally-created rows synthesize the
 * same shape with `changeSeq: 0` and `deletedAt: null` (contract C3).
 * NEVER edit schema.d.ts by hand — regen only (`npm run gen:api-client`).
 */

import type { components } from '@tmap/api-client';

export type TaskSyncRow = components['schemas']['TaskSyncRow'];
export type SubtaskSyncRow = components['schemas']['SubtaskSyncRow'];
export type ProjectSyncRow = components['schemas']['ProjectSyncRow'];
export type NoteGroupSyncRow = components['schemas']['NoteGroupSyncRow'];
export type NoteSyncRow = components['schemas']['NoteSyncRow'];
export type RecurrenceRuleSyncRow = components['schemas']['RecurrenceRuleSyncRow'];
export type FocusSessionSyncRow = components['schemas']['FocusSessionSyncRow'];
export type DailyPlanSyncRow = components['schemas']['DailyPlanSyncRow'];
export type SettingSyncRow = components['schemas']['SettingSyncRow'];

// Pull envelope shapes (contract C6) — consumed by SyncTransport.pull / applyPull in R3/R4.
export type SyncResponse = components['schemas']['SyncResponse'];
export type SyncChanges = components['schemas']['SyncChanges'];
