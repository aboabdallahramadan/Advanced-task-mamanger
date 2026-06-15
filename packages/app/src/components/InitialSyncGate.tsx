// packages/app/src/components/InitialSyncGate.tsx
import React from 'react';
import { RefreshCw, CloudOff } from 'lucide-react';
import type { SyncStatus } from '../sync/types';

/**
 * First-render gate (spec §7.4 / C10). While the initial full sync is incomplete:
 *   - show a spinner;
 *   - if the engine reports an error/issue AND the initial sync is still incomplete,
 *     show a retry button (calls syncNow) over whatever partial pages were applied;
 *   - once initialSyncComplete flips true, render children.
 * Never blocks render indefinitely: a failed/interrupted initial pull leaves the
 * retry affordance and resumes on the next trigger.
 */
export function InitialSyncGate({
  status,
  onRetry,
  children,
}: {
  status: SyncStatus;
  onRetry: () => void;
  children: React.ReactNode;
}) {
  if (status.initialSyncComplete) return <>{children}</>;

  const failed = status.issues.length > 0 || (!status.syncing && !status.online);

  return (
    <div className="h-screen flex flex-col items-center justify-center bg-surface-950 text-surface-400 select-none">
      <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />
      {failed ? (
        <>
          <CloudOff className="w-8 h-8 text-warning-400" />
          <p className="mt-4 text-sm text-surface-300">Couldn’t finish the first sync.</p>
          <button
            onClick={onRetry}
            className="mt-4 flex items-center gap-2 px-4 py-2 text-sm font-medium rounded-lg bg-accent-600 hover:bg-accent-500 text-white transition-colors"
          >
            <RefreshCw className="w-4 h-4" />
            Retry
          </button>
        </>
      ) : (
        <>
          <div className="w-8 h-8 rounded-full border-2 border-surface-700 border-t-accent-500 animate-spin" />
          <p className="mt-4 text-sm">Syncing your data…</p>
        </>
      )}
    </div>
  );
}
