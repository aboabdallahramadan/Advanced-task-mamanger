import React, { useState, useEffect } from 'react';
import { useStore } from '../store';
import { X, Save, Clock, Power, LogOut } from 'lucide-react';
import { clsx } from 'clsx';
import { usePlatform, useEngine } from '../AppRoot';
import { getAuthStore } from '../auth';
import { LocalStore, getLastUserId, setLastUserId } from '../data/local/LocalStore';
import { shouldConfirmSignOut, signOutWarning, performSignOut } from './signOutConfirm';

const TIME_OPTIONS = Array.from({ length: 24 }, (_, i) => ({
  value: i,
  label: i === 0 ? '12:00 AM' : i < 12 ? `${i}:00 AM` : i === 12 ? '12:00 PM' : `${i - 12}:00 PM`,
}));

export function SettingsDialog() {
  const {
    settingsOpen,
    setSettingsOpen,
    workStartHour,
    workEndHour,
    timeIncrement,
    setWorkHours,
    setTimeIncrement,
  } = useStore();
  const platform = usePlatform();

  const [start, setStart] = useState(workStartHour);
  const [end, setEnd] = useState(workEndHour);
  const [increment, setIncrement] = useState(timeIncrement);
  const [autoLaunch, setAutoLaunch] = useState(false);
  const [confirmSignOut, setConfirmSignOut] = useState(false);

  const autoLaunchSupported = platform.capabilities.autoLaunch && !!platform.autoLaunch;
  // C5: useEngine() returns the typed SyncEngine surface; getStatus() is a synchronous
  // SyncStatus snapshot — read pendingOps directly, no `as any`/`?.()` cast.
  const engine = useEngine();

  // Clear the global pointer, drive the store logout (which stops the engine + closes
  // the store via AppRoot.onLoggedOut), THEN wipe the local DB. The engine MUST be
  // stopped BEFORE the wipe — otherwise a periodic tick / online event in the gap would
  // re-open the deleted Dexie DB and resurrect pulled rows. C10/§7.2; order in
  // performSignOut().
  const doSignOut = async () => {
    const userId = getLastUserId();
    setConfirmSignOut(false);
    setSettingsOpen(false);
    await performSignOut({
      clearPointer: () => setLastUserId(null),
      logout: () => getAuthStore().getState().logout(),
      wipe: async () => {
        if (userId) await LocalStore.wipe(userId);
      },
    });
  };

  const onSignOutClick = () => {
    // C10: the Sign-out confirm reads engine.getStatus().pendingOps to decide whether
    // to warn. The only guard is a null engine (signed-out shell); the engine ALWAYS
    // provides getStatus() synchronously when present (C5).
    const pending = engine ? engine.getStatus().pendingOps : 0;
    if (shouldConfirmSignOut(pending)) {
      setConfirmSignOut(true);
    } else {
      void doSignOut();
    }
  };

  const pendingForWarning = engine ? engine.getStatus().pendingOps : 0;

  useEffect(() => {
    if (settingsOpen) {
      setStart(workStartHour);
      setEnd(workEndHour);
      setIncrement(timeIncrement);
      if (autoLaunchSupported) {
        platform
          .autoLaunch!.get()
          .then(setAutoLaunch)
          .catch(() => {});
      }
    }
  }, [settingsOpen, workStartHour, workEndHour, timeIncrement, autoLaunchSupported, platform]);

  if (!settingsOpen) return null;

  const handleSave = () => {
    if (start < end) {
      setWorkHours(start, end);
      setTimeIncrement(increment);
    }
    setSettingsOpen(false);
  };

  return (
    <div
      className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget) setSettingsOpen(false);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') setSettingsOpen(false);
      }}
    >
      <div className="w-full max-w-md bg-surface-900 border border-surface-700/60 rounded-2xl shadow-2xl flex flex-col animate-scale-in overflow-hidden max-h-[90vh]">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-surface-800">
          <h2 className="text-lg font-semibold text-surface-100 flex items-center gap-2">
            <Clock className="w-5 h-5 text-accent-400" />
            Settings
          </h2>
          <button
            onClick={() => setSettingsOpen(false)}
            className="p-2 -mr-2 text-surface-400 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Body */}
        <div className="p-6 space-y-6 overflow-y-auto">
          {/* Startup (desktop only) */}
          {autoLaunchSupported && (
            <div>
              <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
                Startup
              </h3>
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <Power className="w-4 h-4 text-surface-400" />
                  <span className="text-sm text-surface-200">Launch on system startup</span>
                </div>
                <button
                  onClick={() => {
                    const next = !autoLaunch;
                    setAutoLaunch(next);
                    void platform.autoLaunch!.set(next);
                  }}
                  className={clsx(
                    'relative w-10 h-5 rounded-full transition-colors',
                    autoLaunch ? 'bg-accent-600' : 'bg-surface-700',
                  )}
                >
                  <span
                    className={clsx(
                      'absolute top-0.5 left-0.5 w-4 h-4 rounded-full bg-white transition-transform',
                      autoLaunch && 'translate-x-5',
                    )}
                  />
                </button>
              </div>
            </div>
          )}

          {/* Work Hours */}
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
              Work Hours
            </h3>
            <p className="text-xs text-surface-500 mb-3">
              The timeline will show only the hours within your work schedule.
            </p>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-xs text-surface-400 mb-1.5">Start</label>
                <select
                  value={start}
                  onChange={(e) => setStart(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-surface-950 border border-surface-700/60 rounded-xl text-sm text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 [color-scheme:dark]"
                >
                  {TIME_OPTIONS.filter((t) => t.value < end).map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="block text-xs text-surface-400 mb-1.5">End</label>
                <select
                  value={end}
                  onChange={(e) => setEnd(Number(e.target.value))}
                  className="w-full px-3 py-2 bg-surface-950 border border-surface-700/60 rounded-xl text-sm text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 [color-scheme:dark]"
                >
                  {TIME_OPTIONS.filter((t) => t.value > start).map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>

            <div className="mt-3 px-3 py-2 bg-surface-950 rounded-lg border border-surface-800/60 text-xs text-surface-400 flex items-center gap-2">
              <Clock className="w-3.5 h-3.5" />
              <span>
                Timeline: {TIME_OPTIONS.find((t) => t.value === start)?.label} –{' '}
                {TIME_OPTIONS.find((t) => t.value === end)?.label} ({end - start} hours)
              </span>
            </div>
          </div>

          {/* Time Increment */}
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
              Time Increment
            </h3>
            <p className="text-xs text-surface-500 mb-3">
              Snap tasks to these intervals when dragging on the timeline.
            </p>
            <div className="flex items-center gap-2">
              {[5, 10, 15, 30].map((inc) => (
                <button
                  key={inc}
                  onClick={() => setIncrement(inc)}
                  className={clsx(
                    'px-4 py-2 rounded-lg text-sm font-medium transition-all border',
                    increment === inc
                      ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                      : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                  )}
                >
                  {inc}m
                </button>
              ))}
            </div>
          </div>

          {/* Account / Sign out (SP3 §7.2). */}
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
              Account
            </h3>
            <button
              onClick={onSignOutClick}
              className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium border border-danger-500/40 text-danger-300 hover:bg-danger-600/15 transition-colors"
            >
              <LogOut className="w-4 h-4" />
              Sign out
            </button>
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-end px-6 py-4 border-t border-surface-800 gap-3">
          <button
            onClick={() => setSettingsOpen(false)}
            className="px-4 py-2 text-sm text-surface-300 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
          >
            Cancel
          </button>
          <button
            onClick={handleSave}
            className="flex items-center gap-2 px-5 py-2 text-sm font-medium rounded-lg transition-all shadow-lg bg-accent-600 hover:bg-accent-500 text-white shadow-accent-500/20"
          >
            <Save className="w-4 h-4" />
            Save
          </button>
        </div>
      </div>

      {confirmSignOut && (
        <div
          className="fixed inset-0 z-[60] bg-black/70 backdrop-blur-sm flex items-center justify-center p-4"
          onClick={(e) => {
            if (e.target === e.currentTarget) setConfirmSignOut(false);
          }}
        >
          <div className="w-full max-w-sm bg-surface-900 border border-danger-500/40 rounded-2xl shadow-2xl p-6 animate-scale-in">
            <div className="flex items-center gap-2 mb-3">
              <LogOut className="w-5 h-5 text-danger-400" />
              <h3 className="text-base font-semibold text-surface-100">Sign out?</h3>
            </div>
            <p className="text-sm text-surface-300 mb-5">{signOutWarning(pendingForWarning)}</p>
            <div className="flex items-center justify-end gap-3">
              <button
                onClick={() => setConfirmSignOut(false)}
                className="px-4 py-2 text-sm text-surface-300 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => void doSignOut()}
                className="px-4 py-2 text-sm font-medium rounded-lg bg-danger-600 hover:bg-danger-500 text-white transition-colors"
              >
                Sign out &amp; erase
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
