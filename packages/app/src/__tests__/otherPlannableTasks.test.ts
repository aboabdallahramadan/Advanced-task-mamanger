// packages/app/src/__tests__/otherPlannableTasks.test.ts
import { describe, it, expect } from 'vitest';
import { useStore } from '../store';
import type { Task } from '../types';

// Minimal task factory (mirrors projectName.test.ts).
function makeTask(overrides: Partial<Task>): Task {
  return {
    id: 't1', title: 'Test', notes: '', projectId: null,
    labels: [], source: 'manual',
    status: 'inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
    durationMinutes: 30, priority: null, reminderMinutes: null, subtasks: [],
    order: 0, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null,
    createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z', changeSeq: 0,
    ...overrides,
  };
}

const TARGET = '2026-06-27';

describe('otherPlannableTasks selector', () => {
  it('lists planned/scheduled tasks living on other days (incl. undated), excluding the target day, inbox, backlog, and done', () => {
    useStore.setState({
      tasks: [
        makeTask({ id: 'future', status: 'planned', plannedDate: '2026-06-30' }),
        makeTask({ id: 'past', status: 'planned', plannedDate: '2026-06-20' }),
        makeTask({ id: 'sched-elsewhere', status: 'scheduled', plannedDate: '2026-07-02' }),
        makeTask({ id: 'undated', status: 'planned', plannedDate: null }),
        // Excluded:
        makeTask({ id: 'on-target', status: 'planned', plannedDate: TARGET }),
        makeTask({ id: 'inbox', status: 'inbox' }),
        makeTask({ id: 'backlog', status: 'backlog' }),
        makeTask({ id: 'done-elsewhere', status: 'done', plannedDate: '2026-06-30' }),
        makeTask({ id: 'archived', status: 'archived', plannedDate: '2026-06-30' }),
      ],
    });

    const ids = useStore.getState().otherPlannableTasks(TARGET).map((t) => t.id);

    expect(ids).toEqual(
      expect.arrayContaining(['future', 'past', 'sched-elsewhere', 'undated']),
    );
    expect(ids).not.toContain('on-target');
    expect(ids).not.toContain('inbox');
    expect(ids).not.toContain('backlog');
    expect(ids).not.toContain('done-elsewhere');
    expect(ids).not.toContain('archived');
    expect(ids).toHaveLength(4);
  });
});
