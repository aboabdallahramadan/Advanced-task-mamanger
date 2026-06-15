// packages/app/src/wiring/__tests__/createSyncWiring.test.ts
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createSyncWiring } from '../createSyncWiring';

// Minimal fakes for the injected collaborators. We assert the ORDER, the
// online-vs-offline rollover scheduling, and the onChangesApplied→refreshFromLocal
// subscription (C10) — all the logic the factory owns.
function makeDeps(opts: { online: boolean; initialSyncComplete: boolean }) {
  const order: string[] = [];
  let firstCycleCb: (() => void) | null = null;
  let changesAppliedCb: (() => void) | null = null;

  const store = {
    open: vi.fn((id: string) => {
      order.push(`open:${id}`);
      return {
        setMeta: vi.fn(async (k: string) => { order.push(`setMeta:${k}`); }),
        getMeta: vi.fn(async (k: string) =>
          k === 'initialSyncComplete' ? opts.initialSyncComplete : undefined,
        ),
        close: vi.fn(() => { order.push('store.close'); }),
      };
    }),
  };
  const engine = {
    start: vi.fn(() => { order.push('engine.start'); }),
    stop: vi.fn(() => { order.push('engine.stop'); }),
    online: vi.fn(() => opts.online),
    onChangesApplied: vi.fn((cb: () => void) => {
      changesAppliedCb = cb;
      return () => { order.push('unsub:changesApplied'); };
    }),
    onFirstCycleSettled: vi.fn((cb: () => void) => {
      firstCycleCb = cb;
      return () => { order.push('unsub:firstCycle'); };
    }),
  };
  const buildEngine = vi.fn((_store: any) => { order.push('buildEngine'); return engine; });
  const buildDataClient = vi.fn((_store: any, _engine: any) => {
    order.push('buildDataClient');
    return { kind: 'local' };
  });

  const deps = {
    setLastUserId: vi.fn((id: string | null) => { order.push(`setLastUserId:${id}`); }),
    openStore: (id: string) => store.open(id),
    writeLastUserMeta: vi.fn(async (s: any, user: any) => {
      order.push(`writeLastUserMeta:${user.id}`);
      await s.setMeta('lastUser', user);
    }),
    buildEngine,
    buildDataClient,
    setDataClient: vi.fn((_c: any) => { order.push('setDataClient'); }),
    initialLoad: vi.fn(async () => { order.push('initialLoad'); }),
    refreshFromLocal: vi.fn(async () => { order.push('refreshFromLocal'); }),
    runRolloverOnce: vi.fn(async () => { order.push('runRolloverOnce'); }),
    resetStore: vi.fn(() => { order.push('resetStore'); }),
  };
  return {
    deps,
    order,
    engine,
    store,
    fireFirstCycle: () => firstCycleCb?.(),
    fireChangesApplied: () => changesAppliedCb?.(),
  };
}

describe('createSyncWiring.onAuthed', () => {
  it('runs the C10 sequence in order and DEFERS rollover to onFirstCycleSettled when online', async () => {
    const { deps, order, engine, fireFirstCycle } = makeDeps({ online: true, initialSyncComplete: true });
    const wiring = createSyncWiring(deps as any);

    await wiring.onAuthed({ id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });

    // Order through initialLoad; rollover NOT yet run (deferred online).
    expect(order).toEqual([
      'setLastUserId:u1',
      'open:u1',
      'writeLastUserMeta:u1',
      'setMeta:lastUser',
      'buildEngine',
      'buildDataClient',
      'setDataClient',
      'engine.start',
      'initialLoad',
    ]);
    expect(engine.onFirstCycleSettled).toHaveBeenCalledTimes(1);
    expect(deps.runRolloverOnce).not.toHaveBeenCalled();

    // When the first cycle settles, rollover runs against reconciled rows.
    fireFirstCycle();
    await Promise.resolve();
    expect(deps.runRolloverOnce).toHaveBeenCalledTimes(1);
  });

  it('runs rollover IMMEDIATELY (not deferred) when connectivity says offline at start', async () => {
    const { deps, engine } = makeDeps({ online: false, initialSyncComplete: true });
    const wiring = createSyncWiring(deps as any);

    await wiring.onAuthed({ id: 'u2', email: 'o@ff.line', timeZoneId: 'UTC' });

    // Offline start: the session's queued writes are the newest intent, so roll over now.
    expect(deps.runRolloverOnce).toHaveBeenCalledTimes(1);
    expect(engine.onFirstCycleSettled).not.toHaveBeenCalled();
  });

  it('reports needsInitialSyncGate from meta.initialSyncComplete', async () => {
    const a = makeDeps({ online: true, initialSyncComplete: false });
    const wiringA = createSyncWiring(a.deps as any);
    const resA = await wiringA.onAuthed({ id: 'u3', email: 'x@y.z', timeZoneId: 'UTC' });
    expect(resA.needsInitialSyncGate).toBe(true);

    const b = makeDeps({ online: true, initialSyncComplete: true });
    const wiringB = createSyncWiring(b.deps as any);
    const resB = await wiringB.onAuthed({ id: 'u4', email: 'x@y.z', timeZoneId: 'UTC' });
    expect(resB.needsInitialSyncGate).toBe(false);
  });

  it('subscribes onChangesApplied → refreshFromLocal (the remote-pull→UI-refresh path, C10)', async () => {
    const { deps, engine, fireChangesApplied } = makeDeps({ online: true, initialSyncComplete: true });
    const wiring = createSyncWiring(deps as any);

    await wiring.onAuthed({ id: 'u5', email: 'a@b.c', timeZoneId: 'UTC' });

    // The subscription is registered during onAuthed (before the first cycle can apply pages).
    expect(engine.onChangesApplied).toHaveBeenCalledTimes(1);
    expect(deps.refreshFromLocal).not.toHaveBeenCalled();

    // When the engine applies pulled changes, the store re-reads from local.
    fireChangesApplied();
    await Promise.resolve();
    expect(deps.refreshFromLocal).toHaveBeenCalledTimes(1);
  });
});

describe('createSyncWiring.onLoggedOut', () => {
  it('drains subscriptions, stops the engine, closes the store, resets the store', async () => {
    const { deps, order } = makeDeps({ online: true, initialSyncComplete: true });
    const wiring = createSyncWiring(deps as any);
    await wiring.onAuthed({ id: 'u1', email: 'a@b.c', timeZoneId: 'UTC' });
    order.length = 0;

    wiring.onLoggedOut();
    // Online start registered BOTH onChangesApplied + onFirstCycleSettled; their
    // unsubscribers are drained (in registration order) before engine.stop/teardown.
    expect(order).toEqual([
      'unsub:changesApplied',
      'unsub:firstCycle',
      'engine.stop',
      'store.close',
      'resetStore',
    ]);
  });

  it('onLoggedOut before any onAuthed is a safe no-op (no engine/store yet)', () => {
    const { deps } = makeDeps({ online: true, initialSyncComplete: true });
    const wiring = createSyncWiring(deps as any);
    expect(() => wiring.onLoggedOut()).not.toThrow();
  });
});
