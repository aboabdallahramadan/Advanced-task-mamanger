// packages/app/src/components/SyncStatusPill.tsx
import React, { useState } from 'react';
import * as Popover from '@radix-ui/react-popover';
import { Cloud, CloudOff, RefreshCw, AlertTriangle, Check, X, Trash2 } from 'lucide-react';
import { clsx } from 'clsx';
import { useEngine } from '../AppRoot';
import { useSyncStatus, derivePillState } from '../sync/useSyncStatus';
import type { SyncIssue } from '../sync/types';

/**
 * Sync-status pill (spec §8). Mounted in App's titlebar region. Reads the live
 * SyncEngine status via the engine context (engine.subscribe). Click → popover
 * with last-synced time, pending count, the persisted issues list (retry/discard),
 * and a "retry now" button. Hidden in the quiet-ok state to stay unobtrusive.
 */
export function SyncStatusPill() {
  // C5: useEngine() is the typed SyncEngine surface (subscribe/syncNow/retryIssue/
  // discardIssue) — no `as any` cast. It structurally satisfies useSyncStatus's
  // StatusSource (it has subscribe).
  const engine = useEngine();
  const status = useSyncStatus(engine);
  const pill = derivePillState(status);
  const [open, setOpen] = useState(false);

  // Quiet-ok with no pending and no issues → stay out of the way.
  if (pill.kind === 'ok' && pill.pending === 0) return null;

  const Icon =
    pill.kind === 'syncing'
      ? RefreshCw
      : pill.kind === 'offline'
        ? CloudOff
        : pill.kind === 'warning'
          ? AlertTriangle
          : Cloud;

  const tone =
    pill.kind === 'warning'
      ? 'text-warning-300 border-warning-500/40 bg-warning-600/10'
      : pill.kind === 'offline'
        ? 'text-surface-300 border-surface-700/60 bg-surface-800/60'
        : 'text-surface-300 border-surface-700/60 bg-surface-800/40';

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button
          className={clsx(
            'flex items-center gap-1.5 px-2.5 py-1 rounded-full border text-xs font-medium transition-colors no-drag',
            tone,
          )}
          title="Sync status"
        >
          <Icon className={clsx('w-3.5 h-3.5', pill.kind === 'syncing' && 'animate-spin')} />
          <span>{pill.label}</span>
          {pill.pending > 0 && (
            <span className="ml-0.5 px-1.5 py-px rounded-full bg-surface-700/80 text-2xs text-surface-200">
              {pill.pending}
            </span>
          )}
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          sideOffset={6}
          align="end"
          className="z-[9999] w-80 rounded-xl border border-surface-700/60 bg-surface-900 shadow-2xl p-3 text-sm text-surface-200 animate-scale-in"
        >
          <div className="flex items-center justify-between mb-2">
            <span className="font-semibold text-surface-100">Sync</span>
            <button
              onClick={() => void engine?.syncNow()}
              className="flex items-center gap-1 px-2 py-1 rounded-md text-xs text-accent-300 hover:text-accent-100 hover:bg-surface-800 transition-colors"
            >
              <RefreshCw className="w-3 h-3" />
              Retry now
            </button>
          </div>
          <div className="text-xs text-surface-400 mb-3">
            {status.lastSyncedAt
              ? `Last synced ${new Date(status.lastSyncedAt).toLocaleString()}`
              : 'Not synced yet'}
            {status.pendingOps > 0 && ` · ${status.pendingOps} pending`}
          </div>
          {status.issues.length === 0 ? (
            <div className="flex items-center gap-2 text-xs text-success-300">
              <Check className="w-3.5 h-3.5" />
              No issues.
            </div>
          ) : (
            <ul className="space-y-2 max-h-60 overflow-y-auto">
              {status.issues.map((iss: SyncIssue) => (
                <li
                  key={iss.id}
                  className="rounded-lg border border-surface-800 bg-surface-950/60 p-2"
                >
                  <div className="flex items-start gap-2">
                    <AlertTriangle className="w-3.5 h-3.5 mt-0.5 flex-shrink-0 text-warning-400" />
                    <div className="flex-1 min-w-0">
                      <p className="text-xs text-surface-300 truncate">{iss.reason}</p>
                      <p className="text-2xs text-surface-500 truncate">
                        {iss.op.method} {iss.op.path}
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center justify-end gap-1 mt-1.5">
                    {iss.status === 'parked' && (
                      <button
                        onClick={() => iss.id != null && void engine?.retryIssue(iss.id)}
                        className="flex items-center gap-1 px-2 py-0.5 rounded-md text-2xs text-accent-300 hover:bg-surface-800 transition-colors"
                      >
                        <RefreshCw className="w-3 h-3" />
                        Retry
                      </button>
                    )}
                    <button
                      onClick={() => iss.id != null && void engine?.discardIssue(iss.id)}
                      className="flex items-center gap-1 px-2 py-0.5 rounded-md text-2xs text-danger-300 hover:bg-surface-800 transition-colors"
                    >
                      <Trash2 className="w-3 h-3" />
                      Discard
                    </button>
                  </div>
                </li>
              ))}
            </ul>
          )}
          <Popover.Close asChild>
            <button
              aria-label="Close"
              className="absolute top-2 right-2 p-1 rounded-md text-surface-500 hover:text-surface-200 hover:bg-surface-800 transition-colors"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </Popover.Close>
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
