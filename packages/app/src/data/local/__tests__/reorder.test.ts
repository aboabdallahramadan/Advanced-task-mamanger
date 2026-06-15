import 'fake-indexeddb/auto';
import { describe, it, expect, afterEach } from 'vitest';
import { LocalDataClient } from '../LocalDataClient';
import { openStore, closeAll, fakeBridge, taskRow } from './helpers';

afterEach(closeAll);

// Helper: read the final (rank,id)-sorted id order straight from the store.
async function order(store: ReturnType<typeof openStore>): Promise<string[]> {
  const rows = await store.tasks.toArray();
  return rows
    .sort((a, b) => (a.rank < b.rank ? -1 : a.rank > b.rank ? 1 : a.id < b.id ? -1 : 1))
    .map((r) => r.id);
}

describe('LocalDataClient.tasks.reorder', () => {
  it('simple move: minimal payload re-ranks only the moved row', async () => {
    const store = openStore();
    // Head ranks are mid-range (the seeded 'n' band) — not the lexicographic floor
    // 'a', below which no [a-z] rank exists (ranking.ts / server Ranking both bottom
    // out at 'a'), so a single-row front insert is representable.
    await store.tasks.bulkPut([
      taskRow('a', { rank: 'd' }),
      taskRow('b', { rank: 'k' }),
      taskRow('c', { rank: 'p' }),
    ]);
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    // Move 'c' to the front: desired order [c, a, b].
    await dc.tasks.reorder([
      { id: 'c', order: 0 },
      { id: 'a', order: 1 },
      { id: 'b', order: 2 },
    ]);
    expect(await order(store)).toEqual(['c', 'a', 'b']);
    const op = (await store.ops.toArray())[0];
    expect(op.method).toBe('PATCH');
    expect(op.path).toBe('/api/v1/tasks/reorder');
    const body = op.body as { id: string; rank: string }[];
    expect(body.map((e) => e.id)).toEqual(['c']); // only the moved row
    expect(body[0].rank < 'd').toBe(true); // sorts before the new following neighbor 'a'
    expect(bridge.nudges).toBe(1);
  });

  it('moving between tied neighbors re-ranks the tied run with fresh distinct ranks', async () => {
    const store = openStore();
    // Three rows tie on 'a0' (the recurrence-instance population, §5.1) + one mover at the end.
    await store.tasks.bulkPut([
      taskRow('p', { rank: 'a0' }),
      taskRow('q', { rank: 'a0' }),
      taskRow('r', { rank: 'a0' }),
      taskRow('z', { rank: 'm' }),
    ]);
    const dc = new LocalDataClient(store, fakeBridge());
    // Desired order: p, z, q, r — insert z between the tied p and q.
    await dc.tasks.reorder([
      { id: 'p', order: 0 },
      { id: 'z', order: 1 },
      { id: 'q', order: 2 },
      { id: 'r', order: 3 },
    ]);
    expect(await order(store)).toEqual(['p', 'z', 'q', 'r']);
    const body = (await store.ops.toArray())[0].body as { id: string; rank: string }[];
    // Every row in the payload must have a distinct rank, and the final order must hold.
    const ranks = new Map(body.map((e) => [e.id, e.rank]));
    const finalRank = (id: string) =>
      ranks.get(id) ?? { p: 'a0', q: 'a0', r: 'a0', z: 'm' }[id]!;
    expect(finalRank('p') < finalRank('z')).toBe(true);
    expect(finalRank('z') < finalRank('q')).toBe(true);
    expect(finalRank('q') < finalRank('r')).toBe(true);
    expect(new Set(body.map((e) => e.rank)).size).toBe(body.length); // all distinct
  });

  it('no-op reorder (already in order) queues nothing and does not nudge', async () => {
    const store = openStore();
    await store.tasks.bulkPut([
      taskRow('a', { rank: 'a' }),
      taskRow('b', { rank: 'b' }),
    ]);
    const bridge = fakeBridge();
    const dc = new LocalDataClient(store, bridge);
    await dc.tasks.reorder([
      { id: 'a', order: 0 },
      { id: 'b', order: 1 },
    ]);
    expect(await store.ops.count()).toBe(0);
    expect(bridge.nudges).toBe(0);
  });
});
