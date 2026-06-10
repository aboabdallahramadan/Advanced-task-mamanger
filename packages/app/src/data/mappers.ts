/**
 * mappers.ts — bidirectional conversion between the server API schema
 * (PascalCase enums, string-coerced numerics) and the domain types used
 * throughout the app (lowercase enums, proper numbers).
 *
 * The rule: server → domain uses `from*`, domain → server uses `to*`.
 */

import type { components } from '@tmap/api-client';
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

type TaskResponse = components['schemas']['TaskResponse'];
type SubtaskResponse = components['schemas']['SubtaskResponse'];
type ProjectResponse = components['schemas']['ProjectResponse'];
type NoteGroupResponse = components['schemas']['NoteGroupResponse'];
type NoteResponse = components['schemas']['NoteResponse'];
type FocusSessionResponse = components['schemas']['FocusSessionResponse'];
type DailyPlanResponse = components['schemas']['DailyPlanResponse'];
type RecurrenceRuleResponse = components['schemas']['RecurrenceRuleResponse'];
type ReportDataResponse = components['schemas']['ReportDataResponse'];
type TaskStatus = components['schemas']['TaskStatus'];
type RecurrenceFrequency = components['schemas']['RecurrenceFrequency'];
type RecurrenceEndType = components['schemas']['RecurrenceEndType'];

// ---------------------------------------------------------------------------
// Numeric coercion helpers
// ---------------------------------------------------------------------------

function toNum(v: number | string | null | undefined, fallback = 0): number {
  if (v === null || v === undefined) return fallback;
  const n = typeof v === 'string' ? Number(v) : v;
  return isNaN(n) ? fallback : n;
}

function toNumOrNull(v: number | string | null | undefined): number | null {
  if (v === null || v === undefined) return null;
  const n = typeof v === 'string' ? Number(v) : v;
  return isNaN(n) ? null : n;
}

// ---------------------------------------------------------------------------
// Status enum
// ---------------------------------------------------------------------------

type DomainStatus = Task['status'];

export function fromServerStatus(status: TaskStatus | null | undefined): DomainStatus {
  if (!status) return 'inbox';
  return status.toLowerCase() as DomainStatus;
}

export function toServerStatus(status: DomainStatus): TaskStatus {
  return (status.charAt(0).toUpperCase() + status.slice(1)) as TaskStatus;
}

// ---------------------------------------------------------------------------
// Recurrence frequency enum
// ---------------------------------------------------------------------------

type DomainFrequency = 'daily' | 'weekly';

export function fromServerFrequency(freq: RecurrenceFrequency): DomainFrequency {
  return freq.toLowerCase() as DomainFrequency;
}

export function toServerFrequency(freq: DomainFrequency): RecurrenceFrequency {
  return (freq.charAt(0).toUpperCase() + freq.slice(1)) as RecurrenceFrequency;
}

// ---------------------------------------------------------------------------
// Recurrence end type enum
// ---------------------------------------------------------------------------

type DomainEndType = 'never' | 'count' | 'date';

export function fromServerEndType(et: RecurrenceEndType): DomainEndType {
  return et.toLowerCase() as DomainEndType;
}

export function toServerEndType(et: DomainEndType): RecurrenceEndType {
  return (et.charAt(0).toUpperCase() + et.slice(1)) as RecurrenceEndType;
}

// ---------------------------------------------------------------------------
// Subtask
// ---------------------------------------------------------------------------

export function toSubtask(r: SubtaskResponse, order: number): Subtask {
  return {
    id: r.id,
    taskId: r.taskId,
    title: r.title,
    completed: r.completed,
    order,
    createdAt: r.createdAt,
  };
}

export function fromSubtaskUpdate(
  updates: Partial<Pick<Subtask, 'title' | 'completed' | 'order'>>,
): components['schemas']['UpdateSubtaskRequest'] {
  const body: components['schemas']['UpdateSubtaskRequest'] = {
    title: updates.title !== undefined ? updates.title : null,
    completed: updates.completed !== undefined ? updates.completed : null,
    sortOrder: updates.order !== undefined ? updates.order : null,
  };
  return body;
}

// ---------------------------------------------------------------------------
// Task
// ---------------------------------------------------------------------------

function coercePriority(v: number | string | null | undefined): 1 | 2 | 3 | 4 | null {
  if (v === null || v === undefined) return null;
  const n = typeof v === 'string' ? Number(v) : v;
  if (n === 1 || n === 2 || n === 3 || n === 4) return n;
  return null;
}

export function toTask(r: TaskResponse): Task {
  const subtasks = [...(r.subtasks ?? [])].sort(
    (a, b) => toNum(a.sortOrder) - toNum(b.sortOrder),
  );
  const mappedSubtasks = subtasks.map((s, i) => toSubtask(s, i));

  return {
    id: r.id,
    title: r.title,
    notes: r.notes,
    projectId: r.projectId ?? null,
    labels: r.labels ?? [],
    source: r.source,
    status: fromServerStatus(r.status),
    plannedDate: r.plannedDate ?? null,
    scheduledStart: r.scheduledStart ?? null,
    scheduledEnd: r.scheduledEnd ?? null,
    durationMinutes: toNum(r.durationMinutes, 30),
    actualTimeMinutes: toNum(r.actualTimeMinutes, 0),
    priority: coercePriority(r.priority),
    reminderMinutes: toNumOrNull(r.reminderMinutes),
    subtasks: mappedSubtasks,
    order: 0, // owned by the container rank-sort pass
    recurrenceRuleId: r.recurrenceRuleId ?? null,
    isRecurrenceTemplate: r.isRecurrenceTemplate,
    recurrenceDetached: r.recurrenceDetached,
    recurrenceOriginalDate: r.recurrenceOriginalDate ?? null,
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    changeSeq: toNum(r.changeSeq, 0),
    completedAt: r.completedAt ?? null,
  };
}

/**
 * Converts a partial domain task (or plain update object) into a server
 * request body.  Uses PATCH semantics: only keys present in the input are
 * included in the output.  The `order` field is never forwarded to the server
 * (it is a local rank-sort concern).
 */
export function fromTask(
  updates: Partial<Task> & { id?: string },
): Record<string, unknown> {
  const body: Record<string, unknown> = {};

  const { order: _order, ...rest } = updates as Record<string, unknown>;
  void _order; // explicitly excluded

  for (const [key, value] of Object.entries(rest)) {
    if (key === 'status' && typeof value === 'string') {
      body[key] = toServerStatus(value as DomainStatus);
    } else {
      body[key] = value;
    }
  }

  return body;
}

// ---------------------------------------------------------------------------
// Project
// ---------------------------------------------------------------------------

export function toProject(r: ProjectResponse): Project & { _rank: string } {
  return {
    id: r.id,
    name: r.name,
    color: r.color,
    emoji: r.emoji,
    order: 0, // owned by the container rank-sort pass
    actualTimeMinutes: toNum(r.actualTimeMinutes, 0),
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    _rank: r.rank,
  };
}

// ---------------------------------------------------------------------------
// NoteGroup
// ---------------------------------------------------------------------------

export function toNoteGroup(r: NoteGroupResponse, order = 0): NoteGroup & { _rank: string } {
  return {
    id: r.id,
    name: r.name,
    emoji: r.emoji,
    projectId: r.projectId ?? null,
    order,
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    _rank: r.rank,
  };
}

// ---------------------------------------------------------------------------
// Note
// ---------------------------------------------------------------------------

export function toNote(r: NoteResponse, order = 0): Note & { _rank: string } {
  return {
    id: r.id,
    groupId: r.groupId ?? null,
    projectId: r.projectId ?? null,
    title: r.title,
    content: r.content,
    order,
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
    _rank: r.rank,
  };
}

// ---------------------------------------------------------------------------
// FocusSession
// ---------------------------------------------------------------------------

export function toFocusSession(r: FocusSessionResponse): FocusSession {
  return {
    id: r.id,
    taskId: r.taskId ?? null,
    project: r.project, // FocusSession.project is a name string (project name at session time)
    startedAt: r.startedAt,
    endedAt: r.endedAt,
    minutes: toNum(r.minutes, 0),
    date: r.date,
    createdAt: r.createdAt,
  };
}

// ---------------------------------------------------------------------------
// DailyPlan
// ---------------------------------------------------------------------------

export function toDailyPlan(r: DailyPlanResponse): DailyPlan {
  return {
    date: r.date,
    committedAt: r.committedAt,
    plannedTaskIds: r.plannedTaskIds,
    plannedMinutes: toNum(r.plannedMinutes, 0),
  };
}

// ---------------------------------------------------------------------------
// RecurrenceRule
// ---------------------------------------------------------------------------

export function toRecurrenceRule(r: RecurrenceRuleResponse): RecurrenceRule {
  return {
    id: r.id,
    frequency: fromServerFrequency(r.frequency),
    interval: toNum(r.interval, 1),
    daysOfWeek: (r.daysOfWeek ?? []).map((d) => toNum(d)),
    endType: fromServerEndType(r.endType),
    endCount: toNumOrNull(r.endCount),
    endDate: r.endDate ?? null,
    generatedUntil: r.generatedUntil ?? null,
    createdAt: r.createdAt,
    updatedAt: r.updatedAt,
  };
}

// ---------------------------------------------------------------------------
// ReportData
// ---------------------------------------------------------------------------

export function toReportData(r: ReportDataResponse): ReportData {
  return {
    completedTasks: r.completedTasks.map((t) => ({
      id: t.id,
      project: t.project, // ReportData completedTask.project is a name string
      date: t.date,
    })),
    sessions: r.sessions.map((s) => ({
      project: s.project, // ReportData session.project is a name string
      minutes: toNum(s.minutes, 0),
      date: s.date,
    })),
    dailyPlans: r.dailyPlans.map((dp) => ({
      date: dp.date,
      committedAt: dp.committedAt,
      plannedTaskIds: dp.plannedTaskIds,
      plannedMinutes: toNum(dp.plannedMinutes, 0),
    })),
  };
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

const SYNCED_NUMERIC_KEYS = ['workStartHour', 'workEndHour', 'timeIncrement'] as const;

/**
 * Parse a SettingsResponse.settings map (Record<string,string>) into the
 * store-friendly object: the three synced values become numbers; any other key
 * is left as its string value. `timeZoneId` is handled separately (top-level).
 */
export function parseSettings(map: Record<string, string>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(map)) {
    if ((SYNCED_NUMERIC_KEYS as readonly string[]).includes(k)) {
      const n = Number(v);
      out[k] = Number.isFinite(n) ? n : v;
    } else {
      out[k] = v;
    }
  }
  return out;
}

/**
 * Stringify a store settings object into SaveSettingsRequest.settings
 * (Record<string,string>). Only the three synced numeric keys are persisted to
 * the server in SP2 (local-only keys like sidebarCollapsed live in localStorage,
 * handled by the store).
 */
export function stringifySettings(s: Record<string, unknown>): Record<string, string> {
  const out: Record<string, string> = {};
  for (const k of SYNCED_NUMERIC_KEYS) {
    if (s[k] !== undefined && s[k] !== null) {
      out[k] = String(s[k]);
    }
  }
  return out;
}
