import React from 'react';
import { AlertTriangle, X } from 'lucide-react';
import { useStore } from '../store';

/**
 * Non-destructive banner shown when the app can't reach the server. No local
 * cache in SP2 — failed reads/writes set `onlineError` and surface here.
 */
export function OnlineErrorBanner() {
  const onlineError = useStore((s) => s.onlineError);
  const setOnlineError = useStore((s) => s.setOnlineError);

  if (!onlineError) return null;

  return (
    <div className="fixed top-10 left-1/2 -translate-x-1/2 z-[9998] max-w-xl w-[calc(100%-2rem)]">
      <div className="flex items-center gap-3 px-4 py-2.5 rounded-xl bg-danger-600/15 border border-danger-500/40 text-danger-200 shadow-lg shadow-black/30 backdrop-blur-md animate-fade-in">
        <AlertTriangle className="w-4 h-4 flex-shrink-0 text-danger-400" />
        <span className="text-sm flex-1">{onlineError}</span>
        <button
          onClick={() => setOnlineError(null)}
          className="p-1 -mr-1 rounded-md text-danger-300 hover:text-danger-100 hover:bg-danger-600/20 transition-colors"
          aria-label="Dismiss"
        >
          <X className="w-4 h-4" />
        </button>
      </div>
    </div>
  );
}
