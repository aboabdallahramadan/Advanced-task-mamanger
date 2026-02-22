import React, { useEffect, useState } from 'react';
import { useStore } from '../../store';
import { Play, Pause, Square, CheckCircle2 } from 'lucide-react';
import { clsx } from 'clsx';

export const FocusModeOverlay: React.FC = () => {
    const {
        tasks,
        focusMode,
        pauseFocusSession,
        startFocusSession,
        stopFocusSession,
        markDone
    } = useStore();

    const [elapsedSeconds, setElapsedSeconds] = useState(0);

    const activeTask = tasks.find(t => t.id === focusMode.activeTaskId);

    // Update timer every second
    useEffect(() => {
        if (!focusMode.isPlaying || !focusMode.sessionStartTime || !activeTask) {
            return;
        }

        const interval = setInterval(() => {
            const currentSessionMs = Date.now() - focusMode.sessionStartTime!;
            const totalMs = (activeTask.actualTimeMinutes || 0) * 60000 + currentSessionMs;
            setElapsedSeconds(Math.floor(totalMs / 1000));
        }, 1000);

        return () => clearInterval(interval);
    }, [focusMode.isPlaying, focusMode.sessionStartTime, activeTask]);

    // Format mm:ss or hh:mm:ss
    const formatTime = (totalSeconds: number) => {
        const h = Math.floor(totalSeconds / 3600);
        const m = Math.floor((totalSeconds % 3600) / 60);
        const s = totalSeconds % 60;

        if (h > 0) {
            return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
        }
        return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
    };

    // ─── Tray Widget Communication ────────────────────────────
    // Send timer state to tray every second
    useEffect(() => {
        if (!activeTask) {
            // Clear tray when no task focused
            window.api?.focus?.updateTray({ taskTitle: null, elapsed: null, isPlaying: false });
            return;
        }

        const elapsed = formatTime(elapsedSeconds);
        window.api?.focus?.updateTray({
            taskTitle: activeTask.title,
            elapsed,
            isPlaying: focusMode.isPlaying,
        });
    }, [activeTask, elapsedSeconds, focusMode.isPlaying]);

    // Listen for tray commands (pause/stop from Windows tray)
    useEffect(() => {
        const handleToggle = () => {
            const state = useStore.getState();
            if (state.focusMode.activeTaskId) {
                if (state.focusMode.isPlaying) {
                    state.pauseFocusSession();
                } else {
                    state.startFocusSession(state.focusMode.activeTaskId);
                }
            }
        };

        const handleStop = () => {
            useStore.getState().stopFocusSession();
        };

        window.api?.on('focus:togglePlayPause', handleToggle);
        window.api?.on('focus:stop', handleStop);

        return () => {
            // Cleanup not strictly necessary for IPC in Electron but good practice
        };
    }, []);

    if (!activeTask) return null;

    const plannedMinutes = activeTask.durationMinutes || 0;
    const elapsedMins = Math.floor(elapsedSeconds / 60);
    const isOvertime = plannedMinutes > 0 && elapsedMins >= plannedMinutes;

    const handleComplete = async () => {
        await stopFocusSession();
        await markDone(activeTask.id);
    };

    return (
        <div className="fixed bottom-6 right-6 z-50 animate-slide-up">
            <div className="bg-surface-900 border border-surface-700/60 rounded-2xl shadow-2xl p-4 w-80 flex flex-col gap-3">
                {/* Header */}
                <div className="flex items-start justify-between gap-3">
                    <div className="flex-1 min-w-0">
                        <div className="text-xs font-semibold tracking-wider uppercase text-accent-500 mb-1">
                            Focusing On
                        </div>
                        <h3 className="text-surface-100 font-medium truncate" title={activeTask.title}>
                            {activeTask.title}
                        </h3>
                    </div>
                </div>

                {/* Timer Display */}
                <div className="flex items-center justify-between mt-2">
                    <div className={clsx(
                        "text-3xl font-mono font-medium tracking-tight",
                        isOvertime ? "text-warning-400" : "text-surface-100"
                    )}>
                        {formatTime(elapsedSeconds)}
                    </div>
                    {plannedMinutes > 0 && (
                        <div className="text-right flex flex-col items-end">
                            <span className="text-xs text-surface-500 uppercase tracking-wider font-semibold">
                                Planned
                            </span>
                            <span className="text-sm font-medium text-surface-300">
                                {plannedMinutes}m
                            </span>
                        </div>
                    )}
                </div>

                {/* Progress Bar */}
                {plannedMinutes > 0 && (
                    <div className="h-1.5 w-full bg-surface-800 rounded-full overflow-hidden mt-1">
                        <div
                            className={clsx(
                                "h-full transition-all duration-1000",
                                isOvertime ? "bg-warning-500" : "bg-accent-500"
                            )}
                            style={{
                                width: `${Math.min(100, (elapsedMins / plannedMinutes) * 100)}%`
                            }}
                        />
                    </div>
                )}

                {/* Controls */}
                <div className="flex items-center gap-2 mt-2">
                    {focusMode.isPlaying ? (
                        <button
                            onClick={pauseFocusSession}
                            className="flex-1 py-2 flex justify-center items-center rounded-lg bg-surface-800 hover:bg-surface-700 text-surface-200 transition-colors"
                            title="Pause Timer"
                        >
                            <Pause className="w-5 h-5" />
                        </button>
                    ) : (
                        <button
                            onClick={() => startFocusSession(activeTask.id)}
                            className="flex-1 py-2 flex justify-center items-center rounded-lg bg-accent-600/20 hover:bg-accent-600/30 text-accent-400 transition-colors"
                            title="Resume Timer"
                        >
                            <Play className="w-5 h-5" />
                        </button>
                    )}

                    <button
                        onClick={stopFocusSession}
                        className="p-2 flex justify-center items-center rounded-lg bg-surface-800 hover:bg-surface-700 text-surface-400 hover:text-surface-200 transition-colors"
                        title="Stop Timer"
                    >
                        <Square className="w-5 h-5" />
                    </button>

                    <button
                        onClick={handleComplete}
                        className="px-4 py-2 flex justify-center items-center rounded-lg bg-success-600/20 hover:bg-success-600/30 text-success-500 transition-colors ml-1"
                        title="Complete Task"
                    >
                        <CheckCircle2 className="w-5 h-5" />
                    </button>
                </div>
            </div>
        </div>
    );
};
