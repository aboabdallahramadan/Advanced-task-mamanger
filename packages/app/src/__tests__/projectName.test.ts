// packages/app/src/__tests__/projectName.test.ts
import { describe, it, expect, beforeEach } from 'vitest';
import { useStore } from '../store';
import type { Project, Task } from '../types';

// Minimal task factory
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

// Helper: inject projects directly into the store for unit testing
function setProjects(projects: Project[]) {
  useStore.setState({ projects });
}

const P1: Project = {
  id: 'proj-1', name: 'Design', color: '#3b82f6', emoji: '🎨',
  order: 0, createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
};
const P2: Project = {
  id: 'proj-2', name: 'Backend', color: '#22c55e', emoji: '🛠️',
  order: 1, createdAt: '2024-01-01T00:00:00Z', updatedAt: '2024-01-01T00:00:00Z',
};

describe('projectName selector', () => {
  beforeEach(() => setProjects([P1, P2]));

  it('resolves a known projectId to its name', () => {
    expect(useStore.getState().projectName('proj-1')).toBe('Design');
    expect(useStore.getState().projectName('proj-2')).toBe('Backend');
  });

  it('returns empty string for null', () => {
    expect(useStore.getState().projectName(null)).toBe('');
  });

  it('returns empty string for an unknown id', () => {
    expect(useStore.getState().projectName('unknown-id')).toBe('');
  });

  it('reflects updated project list', () => {
    setProjects([{ ...P1, name: 'Design v2' }]);
    expect(useStore.getState().projectName('proj-1')).toBe('Design v2');
  });
});

describe('filteredTasks search includes project name', () => {
  beforeEach(() => {
    setProjects([P1, P2]);
    useStore.setState({
      tasks: [
        makeTask({ id: 't-design', title: 'UI work', projectId: 'proj-1' }),
        makeTask({ id: 't-none', title: 'No project task', projectId: null }),
        makeTask({ id: 't-backend', title: 'API fix', projectId: 'proj-2' }),
      ],
      currentView: 'board' as any,
      searchQuery: 'Design',
    });
  });

  it('finds tasks whose project name matches the search query', () => {
    const result = useStore.getState().filteredTasks();
    expect(result.map((t) => t.id)).toContain('t-design');
    expect(result.map((t) => t.id)).not.toContain('t-none');
  });
});
