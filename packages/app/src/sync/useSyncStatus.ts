// packages/app/src/sync/useSyncStatus.ts
import { useEffect, useState } from 'react';
import type { SyncStatus } from './types';

export type PillKind = 'ok' | 'syncing' | 'offline' | 'warning';

export interface PillState {
  kind: PillKind;
  label: string;
  pending: number;
}

/**
 * Pure projection of a SyncStatus to the pill's visual state (§8). Precedence:
 * a running cycle (syncing) > warning (issues OR initial sync incomplete) >
 * offline (with pending count) > quiet ok.
 */
export function derivePillState(s: SyncStatus): PillState {
  if (s.syncing) return { kind: 'syncing', label: 'Syncing…', pending: s.pendingOps };
  if (s.issues.length > 0 || !s.initialSyncComplete)
    return { kind: 'warning', label: 'Sync issue', pending: s.pendingOps };
  if (!s.online) return { kind: 'offline', label: 'Offline', pending: s.pendingOps };
  return { kind: 'ok', label: 'Synced', pending: s.pendingOps };
}

/** Minimal structural surface of the SyncEngine this hook needs (C5 subset). */
export interface StatusSource {
  subscribe(cb: (s: SyncStatus) => void): () => void;
}

const EMPTY: SyncStatus = {
  online: true,
  syncing: false,
  pendingOps: 0,
  lastSyncedAt: null,
  issues: [],
  initialSyncComplete: true,
};

/** Subscribes to the engine's status stream; returns the latest SyncStatus. */
export function useSyncStatus(engine: StatusSource | null): SyncStatus {
  const [status, setStatus] = useState<SyncStatus>(EMPTY);
  useEffect(() => {
    if (!engine) return;
    return engine.subscribe(setStatus);
  }, [engine]);
  return status;
}
