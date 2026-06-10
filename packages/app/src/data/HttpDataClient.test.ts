import { describe, it, expect, beforeEach } from 'vitest';
import { HttpDataClient } from './HttpDataClient';
import type { TmapClient } from '@tmap/api-client';

// ── minimal fake TmapClient ──────────────────────────────────
interface Recorded {
  method: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  path: string;
  body?: unknown;
}

function fakeClient(handlers: Record<string, (body: unknown) => unknown>) {
  const calls: Recorded[] = [];
  const make =
    (method: Recorded['method']) =>
    (path: string, opts?: { body?: unknown; params?: unknown }) => {
      calls.push({ method, path, body: opts?.body });
      const key = `${method} ${path}`;
      const handler = handlers[key];
      const data = handler ? handler(opts?.body) : undefined;
      return Promise.resolve({ data, error: undefined });
    };
  const client = {
    GET: make('GET'),
    POST: make('POST'),
    PATCH: make('PATCH'),
    PUT: make('PUT'),
    DELETE: make('DELETE'),
  } as unknown as TmapClient;
  return { client, calls };
}

function taskRow(id: string, rank: string) {
  return {
    id, title: id, notes: '', projectId: null, labels: [], source: 'manual',
    status: 'Inbox', plannedDate: null, scheduledStart: null, scheduledEnd: null,
    durationMinutes: 0, actualTimeMinutes: 0, priority: null, reminderMinutes: null,
    rank, dueDate: null, recurrenceRuleId: null, isRecurrenceTemplate: false,
    recurrenceDetached: false, recurrenceOriginalDate: null, completedAt: null,
    createdAt: 'a', updatedAt: 'b', changeSeq: 1, subtasks: [],
  };
}

describe('HttpDataClient.tasks.getAll — rank -> sequential order + cache', () => {
  it('sorts by rank and assigns sequential integer order', async () => {
    const { client } = fakeClient({
      'GET /api/v1/tasks': () => [taskRow('c', 'p'), taskRow('a', 'd'), taskRow('b', 'k')],
    });
    const dc = new HttpDataClient(client);
    const tasks = await dc.tasks.getAll();
    expect(tasks.map((t) => t.id)).toEqual(['a', 'b', 'c']); // d < k < p
    expect(tasks.map((t) => t.order)).toEqual([0, 1, 2]);
  });
});

describe('HttpDataClient.tasks.reorder — computes one rank per moved id', () => {
  beforeEach(() => {});

  it('moves a single item between two neighbors using cached ranks', async () => {
    const sent: Array<{ id: string; rank: string }[]> = [];
    const { client } = fakeClient({
      'GET /api/v1/tasks': () => [taskRow('a', 'd'), taskRow('b', 'k'), taskRow('c', 'p')],
      'PATCH /api/v1/tasks/reorder': (body) => {
        sent.push(body as { id: string; rank: string }[]);
        return undefined;
      },
    });
    const dc = new HttpDataClient(client);
    // prime the cache (getAll caches a..d, b..k, c..p)
    await dc.tasks.getAll();
    // desired new order: a(0), c(1), b(2)  -> only c moved (between a='d' and b='k')
    await dc.tasks.reorder([
      { id: 'a', order: 0 },
      { id: 'c', order: 1 },
      { id: 'b', order: 2 },
    ]);
    expect(sent).toHaveLength(1);
    const payload = sent[0];
    // exactly the moved row(s) are sent — c is the only id whose neighbors changed
    const cItem = payload.find((p) => p.id === 'c');
    expect(cItem).toBeDefined();
    // c's new rank must sort strictly between a's 'd' and b's 'k'
    expect('d' < cItem!.rank && cItem!.rank < 'k').toBe(true);
  });

  it('appends a moved item to the end with a rank after the last neighbor', async () => {
    const sent: Array<{ id: string; rank: string }[]> = [];
    const { client } = fakeClient({
      'GET /api/v1/tasks': () => [taskRow('a', 'd'), taskRow('b', 'k'), taskRow('c', 'p')],
      'PATCH /api/v1/tasks/reorder': (body) => {
        sent.push(body as { id: string; rank: string }[]);
        return undefined;
      },
    });
    const dc = new HttpDataClient(client);
    await dc.tasks.getAll();
    // move 'a' to the end: b(0), c(1), a(2) -> a after 'p'
    await dc.tasks.reorder([
      { id: 'b', order: 0 },
      { id: 'c', order: 1 },
      { id: 'a', order: 2 },
    ]);
    const aItem = sent[0].find((p) => p.id === 'a');
    expect(aItem).toBeDefined();
    expect(aItem!.rank > 'p').toBe(true);
  });

  it('updates the cache so a second reorder uses the new rank', async () => {
    const sent: Array<{ id: string; rank: string }[]> = [];
    const { client } = fakeClient({
      'GET /api/v1/tasks': () => [taskRow('a', 'd'), taskRow('b', 'k')],
      'PATCH /api/v1/tasks/reorder': (body) => {
        sent.push(body as { id: string; rank: string }[]);
        return undefined;
      },
    });
    const dc = new HttpDataClient(client);
    await dc.tasks.getAll();
    await dc.tasks.reorder([{ id: 'b', order: 0 }, { id: 'a', order: 1 }]); // b before a
    const bRank1 = sent[0].find((p) => p.id === 'b')!.rank;
    await dc.tasks.reorder([{ id: 'a', order: 0 }, { id: 'b', order: 1 }]); // swap back
    const bRank2 = sent[1].find((p) => p.id === 'b')!.rank;
    // second computation must use the cache updated after the first reorder
    expect(bRank2 > bRank1).toBe(true);
  });
});

describe('HttpDataClient.projects.create — passes client rank seed', () => {
  it('seeds rank after the current last project', async () => {
    let createdBody: any;
    const { client } = fakeClient({
      'GET /api/v1/projects': () => [
        { id: 'p1', name: 'A', color: '#1', emoji: '', rank: 'd', actualTimeMinutes: 0, createdAt: 'a', updatedAt: 'b' },
      ],
      'POST /api/v1/projects': (body) => {
        createdBody = body;
        return { id: 'p2', name: 'B', color: '#fff', emoji: '🆕', rank: (body as any).rank ?? 'e', actualTimeMinutes: 0, createdAt: 'a', updatedAt: 'b' };
      },
    });
    const dc = new HttpDataClient(client);
    await dc.projects.getAll(); // primes cache: last rank 'd'
    const created = await dc.projects.create({ name: 'B' });
    expect(createdBody.rank).toBeDefined();
    expect(createdBody.rank > 'd').toBe(true);
    expect(created.name).toBe('B');
  });
});
