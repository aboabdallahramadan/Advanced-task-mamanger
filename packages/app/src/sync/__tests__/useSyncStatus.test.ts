// packages/app/src/sync/__tests__/useSyncStatus.test.ts
import { describe, it, expect } from 'vitest';
import { derivePillState } from '../useSyncStatus';
import type { SyncStatus } from '../types';

function status(p: Partial<SyncStatus>): SyncStatus {
  return {
    online: true,
    syncing: false,
    pendingOps: 0,
    lastSyncedAt: null,
    issues: [],
    initialSyncComplete: true,
    ...p,
  };
}

describe('derivePillState (§8)', () => {
  it('synced + idle → quiet ok', () => {
    expect(derivePillState(status({}))).toEqual({ kind: 'ok', label: 'Synced', pending: 0 });
  });

  it('a running cycle → syncing spinner (takes precedence over pending count)', () => {
    const s = derivePillState(status({ syncing: true, pendingOps: 3 }));
    expect(s.kind).toBe('syncing');
  });

  it('offline with pending → offline state showing the pending count', () => {
    const s = derivePillState(status({ online: false, pendingOps: 2 }));
    expect(s.kind).toBe('offline');
    expect(s.pending).toBe(2);
  });

  it('non-empty issues → warning (even when online and idle)', () => {
    const s = derivePillState(
      status({ issues: [{ at: 'now', op: {} as any, reason: '400', status: 'dropped' }] }),
    );
    expect(s.kind).toBe('warning');
  });

  it('incomplete initial sync → warning', () => {
    const s = derivePillState(status({ initialSyncComplete: false }));
    expect(s.kind).toBe('warning');
  });

  it('syncing precedence: a running cycle beats a warning issue list', () => {
    const s = derivePillState(
      status({ syncing: true, issues: [{ at: 'n', op: {} as any, reason: 'x', status: 'parked' }] }),
    );
    expect(s.kind).toBe('syncing');
  });
});
