import { describe, it, expect } from 'vitest';
import {
  entityKey,
  type SyncOp,
  type SyncIssue,
  type SyncStatus,
  type SyncTable,
} from '../types';

describe('entityKey', () => {
  it('composes table:key for uuid entities', () => {
    const table: SyncTable = 'tasks';
    expect(entityKey(table, '3f2a0b1c-7d4e-4a89-9c01-5e6f7a8b9c0d')).toBe(
      'tasks:3f2a0b1c-7d4e-4a89-9c01-5e6f7a8b9c0d',
    );
  });

  it('composes date keys for dailyPlans', () => {
    expect(entityKey('dailyPlans', '2026-06-11')).toBe('dailyPlans:2026-06-11');
  });

  it('composes setting keys for settings', () => {
    expect(entityKey('settings', 'workStartHour')).toBe('settings:workStartHour');
  });

  it('feeds a SyncOp entityKeys array (C2 shape: seq absent before insert)', () => {
    const op: SyncOp = {
      method: 'DELETE',
      path: '/api/v1/tasks/abc',
      entityKeys: [entityKey('tasks', 'abc'), entityKey('subtasks', 'def')],
      kind: 'other',
      attempts: 0,
    };
    expect(op.entityKeys).toEqual(['tasks:abc', 'subtasks:def']);
    expect(op.seq).toBeUndefined();
    expect(op.body).toBeUndefined();
  });

  it('SyncIssue and SyncStatus accept C2-conformant literals', () => {
    const issue: SyncIssue = {
      at: '2026-06-11T12:00:00.000Z',
      op: {
        method: 'POST',
        path: '/api/v1/projects',
        body: { name: 'X' },
        entityKeys: [entityKey('projects', 'p1')],
        kind: 'create',
        attempts: 10,
        lastError: 'HTTP 500',
      },
      reason: '409 Conflict — duplicate project name',
      status: 'parked',
    };
    const status: SyncStatus = {
      online: true,
      syncing: false,
      pendingOps: 0,
      lastSyncedAt: null,
      issues: [issue],
      initialSyncComplete: false,
    };
    expect(status.issues[0].status).toBe('parked');
    expect(status.issues[0].op.kind).toBe('create');
  });
});
