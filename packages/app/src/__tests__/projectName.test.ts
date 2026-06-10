// packages/app/src/__tests__/projectName.test.ts
import { describe, it, expect, beforeEach } from 'vitest';
import { useStore } from '../store';
import type { Project } from '../types';

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
