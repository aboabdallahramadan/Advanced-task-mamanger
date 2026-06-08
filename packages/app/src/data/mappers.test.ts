import { describe, it, expect } from 'vitest';
import type { components } from '@tmap/api-client';
import {
  toTask,
  fromTask,
  toServerStatus,
  fromServerStatus,
  toServerFrequency,
  fromServerFrequency,
  toServerEndType,
  fromServerEndType,
  toSubtask,
  fromSubtaskUpdate,
  toProject,
  toNoteGroup,
  toNote,
  toFocusSession,
  toDailyPlan,
  toReportData,
  toRecurrenceRule,
  parseSettings,
  stringifySettings,
} from './mappers';

type TaskResponse = components['schemas']['TaskResponse'];
type SubtaskResponse = components['schemas']['SubtaskResponse'];

function baseTaskResponse(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: '00000000-0000-0000-0000-000000000001',
    title: 'T',
    notes: '',
    projectId: null,
    labels: [],
    source: 'manual',
    status: 'Inbox',
    plannedDate: null,
    scheduledStart: null,
    scheduledEnd: null,
    durationMinutes: 30,
    actualTimeMinutes: 0,
    priority: null,
    reminderMinutes: null,
    rank: 'n',
    dueDate: null,
    recurrenceRuleId: null,
    isRecurrenceTemplate: false,
    recurrenceDetached: false,
    recurrenceOriginalDate: null,
    completedAt: null,
    createdAt: '2026-06-08T00:00:00Z',
    updatedAt: '2026-06-08T00:00:00Z',
    changeSeq: 1,
    subtasks: [],
    ...overrides,
  };
}

describe('status enum case-folding', () => {
  it('folds every server PascalCase status to the lowercase union on read', () => {
    expect(fromServerStatus('Inbox')).toBe('inbox');
    expect(fromServerStatus('Backlog')).toBe('backlog');
    expect(fromServerStatus('Planned')).toBe('planned');
    expect(fromServerStatus('Scheduled')).toBe('scheduled');
    expect(fromServerStatus('Done')).toBe('done');
    expect(fromServerStatus('Archived')).toBe('archived');
  });

  it('defends against server status: null by treating it as inbox', () => {
    expect(fromServerStatus(null)).toBe('inbox');
  });

  it('folds the lowercase union back to PascalCase on write', () => {
    expect(toServerStatus('inbox')).toBe('Inbox');
    expect(toServerStatus('scheduled')).toBe('Scheduled');
    expect(toServerStatus('archived')).toBe('Archived');
  });
});

describe('recurrence enum case-folding', () => {
  it('folds frequency both ways', () => {
    expect(fromServerFrequency('Daily')).toBe('daily');
    expect(fromServerFrequency('Weekly')).toBe('weekly');
    expect(toServerFrequency('daily')).toBe('Daily');
    expect(toServerFrequency('weekly')).toBe('Weekly');
  });

  it('folds end type both ways', () => {
    expect(fromServerEndType('Never')).toBe('never');
    expect(fromServerEndType('Count')).toBe('count');
    expect(fromServerEndType('Date')).toBe('date');
    expect(toServerEndType('never')).toBe('Never');
    expect(toServerEndType('count')).toBe('Count');
    expect(toServerEndType('date')).toBe('Date');
  });
});

describe('toTask — numeric coercion + projectId + sync columns', () => {
  it('coerces string numerics to numbers', () => {
    const t = toTask(baseTaskResponse({ durationMinutes: '45', actualTimeMinutes: '12', changeSeq: '7' }));
    expect(t.durationMinutes).toBe(45);
    expect(t.actualTimeMinutes).toBe(12);
    expect(t.changeSeq).toBe(7);
  });

  it('re-narrows priority to 1|2|3|4|null', () => {
    expect(toTask(baseTaskResponse({ priority: '3' })).priority).toBe(3);
    expect(toTask(baseTaskResponse({ priority: null })).priority).toBe(null);
    // out-of-range coerces to null (defensive)
    expect(toTask(baseTaskResponse({ priority: '9' })).priority).toBe(null);
    expect(toTask(baseTaskResponse({ priority: '0' })).priority).toBe(null);
  });

  it('maps projectId through (uuid|null)', () => {
    expect(toTask(baseTaskResponse({ projectId: null })).projectId).toBe(null);
    expect(toTask(baseTaskResponse({ projectId: 'p-1' })).projectId).toBe('p-1');
  });

  it('carries updatedAt and changeSeq onto the domain task', () => {
    const t = toTask(baseTaskResponse({ updatedAt: '2026-06-09T10:00:00Z', changeSeq: 42 }));
    expect(t.updatedAt).toBe('2026-06-09T10:00:00Z');
    expect(t.changeSeq).toBe(42);
  });

  it('does NOT assign order (order is owned by the container rank-sort pass, defaults 0)', () => {
    expect(toTask(baseTaskResponse()).order).toBe(0);
  });

  it('reminderMinutes null stays null; numeric coerces', () => {
    expect(toTask(baseTaskResponse({ reminderMinutes: null })).reminderMinutes).toBe(null);
    expect(toTask(baseTaskResponse({ reminderMinutes: '15' })).reminderMinutes).toBe(15);
  });

  it('maps nested subtasks via sortOrder -> order, sorted ascending', () => {
    const subs: SubtaskResponse[] = [
      { id: 's2', taskId: 't', title: 'b', completed: false, sortOrder: '5', createdAt: 'x', updatedAt: 'x' },
      { id: 's1', taskId: 't', title: 'a', completed: true, sortOrder: '1', createdAt: 'x', updatedAt: 'x' },
    ];
    const t = toTask(baseTaskResponse({ subtasks: subs }));
    expect(t.subtasks.map((s) => s.id)).toEqual(['s1', 's2']);
    expect(t.subtasks.map((s) => s.order)).toEqual([0, 1]);
    expect(t.subtasks[0].completed).toBe(true);
  });
});

describe('fromTask — domain -> CreateTaskRequest / UpdateTaskRequest', () => {
  it('omits undefined fields and only sends provided keys (PATCH semantics)', () => {
    const body = fromTask({ title: 'New title' });
    expect(body).toEqual({ title: 'New title' });
  });

  it('maps projectId and status (lower->Pascal) and never sends order', () => {
    const body = fromTask({ projectId: 'p-9', status: 'scheduled', order: 4 });
    expect(body.projectId).toBe('p-9');
    expect(body.status).toBe('Scheduled');
    expect('order' in body).toBe(false);
  });

  it('sends projectId: null explicitly when caller clears the project', () => {
    const body = fromTask({ projectId: null });
    expect(body).toEqual({ projectId: null });
  });

  it('passes through a client-provided id for idempotent create retries', () => {
    const body = fromTask({ id: 'client-uuid', title: 'x' });
    expect(body.id).toBe('client-uuid');
  });
});
