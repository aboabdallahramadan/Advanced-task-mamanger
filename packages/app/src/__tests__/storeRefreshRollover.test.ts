// packages/app/src/__tests__/storeRefreshRollover.test.ts
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useStore, setDataClient } from '../store';
import type { DataClient } from '../data/DataClient';
import type { Task } from '../types';
import { format, addDays } from 'date-fns';

// A LocalDataClient-shaped fake: records which seam methods get called so we can
// prove refreshFromLocal has NO side effects (no rollover update, no ensureInstances)
// and that runRolloverOnce is once-per-session.
function makeFakeClient(tasks: Task[]) {
  const calls = {
    tasksGetAll: 0,
    tasksUpdate: 0,
    projectsGetAll: 0,
    noteGroupsGetAll: 0,
    ensureInstances: 0,
    settingsGet: 0,
  };
  const client = {
    tasks: {
      getAll: vi.fn(async () => {
        calls.tasksGetAll += 1;
        return tasks;
      }),
      update: vi.fn(async (id: string, updates: Partial<Task>) => {
        calls.tasksUpdate += 1;
        return { ...(tasks.find((t) => t.id === id) as Task), ...updates };
      }),
    },
    projects: { getAll: vi.fn(async () => { calls.projectsGetAll += 1; return []; }) },
    noteGroups: { getAll: vi.fn(async () => { calls.noteGroupsGetAll += 1; return []; }) },
    recurrence: {
      ensureInstances: vi.fn(async () => { calls.ensureInstances += 1; return []; }),
    },
    settings: { get: vi.fn(async () => { calls.settingsGet += 1; return { settings: {}, timeZoneId: 'UTC' }; }) },
  } as unknown as DataClient;
  return { client, calls };
}

function staleTask(): Task {
  // A past, not-done, non-recurring task — the rollover trigger.
  const yesterday = format(addDays(new Date(), -1), 'yyyy-MM-dd');
  return {
    id: 't-stale',
    title: 'stale',
    status: 'planned',
    plannedDate: yesterday,
    order: 0,
  } as unknown as Task;
}

describe('refreshFromLocal (C9.1) — no side effects', () => {
  beforeEach(() => {
    useStore.getState().reset();
  });

  it('re-reads collections but NEVER rolls over or ensures instances', async () => {
    const { client, calls } = makeFakeClient([staleTask()]);
    setDataClient(client);

    await useStore.getState().refreshFromLocal();

    expect(calls.tasksGetAll).toBe(1);
    expect(calls.projectsGetAll).toBe(1);
    expect(calls.noteGroupsGetAll).toBe(1);
    // The defining assertions: a stale task is present yet NO rollover update fires,
    // and ensureInstances is never called from refreshFromLocal.
    expect(calls.tasksUpdate).toBe(0);
    expect(calls.ensureInstances).toBe(0);
    // The stale task is surfaced verbatim (no plannedDate rewrite).
    const t = useStore.getState().tasks.find((x) => x.id === 't-stale')!;
    expect(t.plannedDate).toBe(staleTask().plannedDate);
  });
});

describe('runRolloverOnce (C9.3) — once per session, resets on reset()', () => {
  beforeEach(() => {
    useStore.getState().reset();
  });

  it('rolls a stale task forward exactly once across repeated calls, then re-arms after reset()', async () => {
    const { client, calls } = makeFakeClient([staleTask()]);
    setDataClient(client);
    // Seed the store with the stale task (rollover reads from store state).
    await useStore.getState().refreshFromLocal();
    expect(calls.tasksUpdate).toBe(0);

    await useStore.getState().runRolloverOnce();
    await useStore.getState().runRolloverOnce(); // idempotent — no second pass
    // Let the bounded-concurrency background updates settle.
    await new Promise((r) => setTimeout(r, 0));
    expect(calls.tasksUpdate).toBe(1); // exactly one rollover update

    // reset() clears the once-flag so a fresh session can roll over again.
    useStore.getState().reset();
    const second = makeFakeClient([staleTask()]);
    setDataClient(second.client);
    await useStore.getState().refreshFromLocal();
    await useStore.getState().runRolloverOnce();
    await new Promise((r) => setTimeout(r, 0));
    expect(second.calls.tasksUpdate).toBe(1);
  });
});

describe('loadTasks (C9.3) — keeps ensureInstances, no longer rolls over', () => {
  beforeEach(() => {
    useStore.getState().reset();
  });

  it('does not roll over but still calls ensureInstances for the horizon', async () => {
    const { client, calls } = makeFakeClient([staleTask()]);
    setDataClient(client);

    await useStore.getState().loadTasks();
    await new Promise((r) => setTimeout(r, 0));
    expect(calls.tasksUpdate).toBe(0); // rollover moved out of loadTasks
    expect(calls.ensureInstances).toBe(1); // horizon ensure call stays
  });
});
