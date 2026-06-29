import { describe, it, expect } from 'vitest';
import { groupByProject } from '../lib/groupByProject';
import type { Task, Project } from '../types';

// Minimal factories (mirror otherPlannableTasks.test.ts).
function makeTask(overrides: Partial<Task>): Task {
  return {
    id: 't1', title: 'Test', notes: '', projectId: null,
    labels: [], source: 'manual',
    status: 'planned', plannedDate: null, scheduledStart: null, scheduledEnd: null,
    durationMinutes: 30, priority: null, reminderMinutes: null, subtasks: [],
    order: 0, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null,
    createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z', changeSeq: 0,
    ...overrides,
  };
}

function makeProject(overrides: Partial<Project>): Project {
  return {
    id: 'p1', name: 'Project', color: '#6ea8fe', emoji: '', order: 0,
    createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
    ...overrides,
  };
}

describe('groupByProject', () => {
  const projWork = makeProject({ id: 'work', name: 'Work', color: '#111111', order: 0 });
  const projHealth = makeProject({ id: 'health', name: 'Health', color: '#222222', order: 1 });
  const projEmpty = makeProject({ id: 'empty', name: 'Empty', color: '#333333', order: 2 });

  it('orders groups by project order and puts No Project last', () => {
    const tasks = [
      makeTask({ id: 'h1', projectId: 'health' }),
      makeTask({ id: 'w1', projectId: 'work' }),
      makeTask({ id: 'n1', projectId: null }),
    ];
    const groups = groupByProject(tasks, [projWork, projHealth]);
    expect(groups.map((g) => g.name)).toEqual(['Work', 'Health', 'No Project']);
    expect(groups.map((g) => g.projectId)).toEqual(['work', 'health', null]);
    expect(groups[0].color).toBe('#111111');
    expect(groups[2].color).toBeNull();
  });

  it('routes null and unknown/deleted projectId into No Project, in input order', () => {
    const tasks = [
      makeTask({ id: 'a', projectId: null }),
      makeTask({ id: 'b', projectId: 'deleted-id' }),
    ];
    const groups = groupByProject(tasks, [projWork]);
    expect(groups).toHaveLength(1);
    expect(groups[0].projectId).toBeNull();
    expect(groups[0].tasks.map((t) => t.id)).toEqual(['a', 'b']);
  });

  it('skips projects with no matching tasks', () => {
    const tasks = [makeTask({ id: 'w1', projectId: 'work' })];
    const groups = groupByProject(tasks, [projWork, projEmpty]);
    expect(groups.map((g) => g.name)).toEqual(['Work']);
  });

  it('preserves task order within a group', () => {
    const tasks = [
      makeTask({ id: 'w2', projectId: 'work' }),
      makeTask({ id: 'w1', projectId: 'work' }),
      makeTask({ id: 'w3', projectId: 'work' }),
    ];
    const groups = groupByProject(tasks, [projWork]);
    expect(groups[0].tasks.map((t) => t.id)).toEqual(['w2', 'w1', 'w3']);
  });

  it('omits the No Project group when every task has a known project', () => {
    const tasks = [makeTask({ id: 'w1', projectId: 'work' })];
    const groups = groupByProject(tasks, [projWork]);
    expect(groups.some((g) => g.projectId === null)).toBe(false);
  });

  it('returns empty array for empty input', () => {
    expect(groupByProject([], [projWork])).toEqual([]);
  });
});
