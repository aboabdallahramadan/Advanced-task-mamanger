import React, { useEffect, useState, useRef, useCallback } from 'react';
import { useStore } from '../../store';
import { Play, Pause, Square, CheckCircle2, GripVertical, Minimize2, Maximize2 } from 'lucide-react';
import { clsx } from 'clsx';
import { getTextDirection, getDirectionStyle } from '../../useTextDirection';

export const FocusModeOverlay: React.FC = () => {
    const {
        tasks,
        projects,
        focusMode,
        pauseFocusSession,
        startFocusSession,
        startProjectFocus,
        stopFocusSession,
        markDone
    } = useStore();

    const [elapsedSeconds, setElapsedSeconds] = useState(0);
    const [isMinimized, setIsMinimized] = useState(false);

    // ─── Dragging State ────────────────────────────────────────
    const [position, setPosition] = useState({ x: -1, y: -1 });
    const [isDragging, setIsDragging] = useState(false);
    const dragRef = useRef<HTMLDivElement>(null);
    const offsetRef = useRef({ x: 0, y: 0 });
    const posRef = useRef(position);

    useEffect(() => {
        if (position.x === -1) {
            setPosition({
                x: window.innerWidth - 340,
                y: window.innerHeight - 220,
            });
        }
    }, []);

    useEffect(() => { posRef.current = position; }, [position]);

    const handleMouseDown = useCallback((e: React.MouseEvent) => {
        if ((e.target as HTMLElement).closest('button')) return;
        e.preventDefault();
        setIsDragging(true);
        offsetRef.current = {
            x: e.clientX - posRef.current.x,
            y: e.clientY - posRef.current.y,
        };

        const handleMouseMove = (ev: MouseEvent) => {
            const newX = Math.max(0, Math.min(window.innerWidth - 320, ev.clientX - offsetRef.current.x));
            const newY = Math.max(0, Math.min(window.innerHeight - 80, ev.clientY - offsetRef.current.y));
            setPosition({ x: newX, y: newY });
        };

        const handleMouseUp = () => {
            setIsDragging(false);
            document.removeEventListener('mousemove', handleMouseMove);
            document.removeEventListener('mouseup', handleMouseUp);
        };

        document.addEventListener('mousemove', handleMouseMove);
        document.addEventListener('mouseup', handleMouseUp);
    }, []);

    const focusedTask = focusMode.targetType === 'task' ? tasks.find(t => t.id === focusMode.targetId) : undefined;
    const focusedProject = focusMode.targetType === 'project' ? projects.find(p => p.id === focusMode.targetId) : undefined;
    const target = focusedTask
        ? {
            kind: 'task' as const,
            title: focusedTask.title,
            accumulatedMinutes: focusedTask.actualTimeMinutes || 0,
            plannedMinutes: focusedTask.durationMinutes || 0,
        }
        : focusedProject
        ? {
            kind: 'project' as const,
            title: `${focusedProject.emoji} ${focusedProject.name}`,
            accumulatedMinutes: focusedProject.actualTimeMinutes || 0,
            plannedMinutes: 0,
        }
        : null;

    const resumeFocus = () => {
        if (focusMode.targetType === 'task' && focusMode.targetId) startFocusSession(focusMode.targetId);
        else if (focusMode.targetType === 'project' && focusMode.targetId) startProjectFocus(focusMode.targetId);
    };

    // Update timer every second
    useEffect(() => {
        if (!focusMode.isPlaying || !focusMode.sessionStartTime || !target) {
            return;
        }

        const interval = setInterval(() => {
            const currentSessionMs = Date.now() - focusMode.sessionStartTime!;
            const totalMs = target.accumulatedMinutes * 60000 + currentSessionMs;
            setElapsedSeconds(Math.floor(totalMs / 1000));
        }, 1000);

        return () => clearInterval(interval);
    }, [focusMode.isPlaying, focusMode.sessionStartTime, target?.accumulatedMinutes]);

    const formatTime = (totalSeconds: number) => {
        const h = Math.floor(totalSeconds / 3600);
        const m = Math.floor((totalSeconds % 3600) / 60);
        const s = totalSeconds % 60;
        if (h > 0) {
            return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
        }
        return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
    };

    const plannedMinutes = target?.plannedMinutes || 0;
    const elapsedMins = Math.floor(elapsedSeconds / 60);
    const isOvertime = plannedMinutes > 0 && elapsedMins >= plannedMinutes;
    const progressPercent = plannedMinutes > 0 ? Math.min(100, (elapsedMins / plannedMinutes) * 100) : 0;

    // ─── Always-on-top Widget Window ──────────────────────────
    // Show/hide the external focus widget when a session starts/ends
    useEffect(() => {
        if (target) {
            window.api?.focus?.showWidget();
        } else {
            window.api?.focus?.hideWidget();
        }

        return () => {
            window.api?.focus?.hideWidget();
        };
    }, [!!target]);

    // Push timing state to the external widget (only on state changes, not every tick)
    // The widget runs its own local timer using these raw values
    useEffect(() => {
        if (!target) return;

        window.api?.focus?.sendWidgetState({
            taskTitle: target.title,
            isPlaying: focusMode.isPlaying,
            sessionStartTime: focusMode.sessionStartTime,
            accumulatedMinutes: target.accumulatedMinutes,
            plannedMinutes: target.plannedMinutes,
            canComplete: target.kind === 'task',
        });
    }, [focusMode.targetId, target?.title, target?.accumulatedMinutes, target?.plannedMinutes, target?.kind, focusMode.isPlaying, focusMode.sessionStartTime]);

    // ─── Tray Communication ───────────────────────────────────
    useEffect(() => {
        if (!target) {
            window.api?.focus?.updateTray({ taskTitle: null, elapsed: null, isPlaying: false });
            return;
        }

        const elapsed = formatTime(elapsedSeconds);
        window.api?.focus?.updateTray({
            taskTitle: target.title,
            elapsed,
            isPlaying: focusMode.isPlaying,
        });
    }, [target?.title, target?.kind, elapsedSeconds, focusMode.isPlaying]);

    // Listen for commands from tray and external widget
    useEffect(() => {
        // Clear any previously accumulated listeners first
        window.api?.removeAllListeners?.('focus:togglePlayPause');
        window.api?.removeAllListeners?.('focus:stop');
        window.api?.removeAllListeners?.('focus:done');

        const handleToggle = () => {
            const state = useStore.getState();
            const fm = state.focusMode;
            if (!fm.targetId) return;
            if (fm.isPlaying) {
                state.pauseFocusSession();
            } else if (fm.targetType === 'task') {
                state.startFocusSession(fm.targetId);
            } else if (fm.targetType === 'project') {
                state.startProjectFocus(fm.targetId);
            }
        };

        const handleStop = () => {
            useStore.getState().stopFocusSession();
        };

        const handleDone = async () => {
            const state = useStore.getState();
            const fm = state.focusMode;
            if (!fm.targetId) return;
            await state.stopFocusSession();
            // "Done" only marks tasks complete; projects are never "done".
            if (fm.targetType === 'task') {
                await state.markDone(fm.targetId);
            }
        };

        window.api?.on('focus:togglePlayPause', handleToggle);
        window.api?.on('focus:stop', handleStop);
        window.api?.on('focus:done', handleDone);

        const handleResync = () => {
            const s = useStore.getState();
            const fm = s.focusMode;
            const t = fm.targetType === 'task'
                ? s.tasks.find(x => x.id === fm.targetId)
                : undefined;
            const p = fm.targetType === 'project'
                ? s.projects.find(x => x.id === fm.targetId)
                : undefined;
            if (t) {
                window.api?.focus?.sendWidgetState({
                    taskTitle: t.title,
                    isPlaying: fm.isPlaying,
                    sessionStartTime: fm.sessionStartTime,
                    accumulatedMinutes: t.actualTimeMinutes || 0,
                    plannedMinutes: t.durationMinutes || 0,
                    canComplete: true,
                });
            } else if (p) {
                window.api?.focus?.sendWidgetState({
                    taskTitle: `${p.emoji} ${p.name}`,
                    isPlaying: fm.isPlaying,
                    sessionStartTime: fm.sessionStartTime,
                    accumulatedMinutes: p.actualTimeMinutes || 0,
                    plannedMinutes: 0,
                    canComplete: false,
                });
            }
        };
        window.api?.on('focus:resyncWidget', handleResync);

        return () => {
            window.api?.removeAllListeners?.('focus:togglePlayPause');
            window.api?.removeAllListeners?.('focus:stop');
            window.api?.removeAllListeners?.('focus:done');
            window.api?.removeAllListeners?.('focus:resyncWidget');
        };
    }, []);

    if (!target) return null;

    const handleComplete = async () => {
        await stopFocusSession();
        if (target.kind === 'task' && focusMode.targetId) await markDone(focusMode.targetId);
    };

    // ─── Minimized Compact View ───────────────────────────────
    if (isMinimized) {
        return (
            <div
                ref={dragRef}
                className={clsx(
                    "fixed z-[9999] select-none",
                    isDragging ? "cursor-grabbing" : "cursor-grab"
                )}
                style={{ left: position.x, top: position.y }}
                onMouseDown={handleMouseDown}
            >
                <div className="flex items-center gap-2 px-3 py-2 rounded-xl bg-surface-900/95 backdrop-blur-xl border border-surface-700/60 shadow-2xl shadow-black/40">
                    <GripVertical className="w-3.5 h-3.5 text-surface-600 flex-shrink-0" />
                    <div className={clsx(
                        "w-2 h-2 rounded-full flex-shrink-0",
                        focusMode.isPlaying ? "bg-accent-500 animate-pulse" : "bg-surface-500"
                    )} />
                    <span className={clsx(
                        "text-sm font-mono font-semibold tabular-nums",
                        isOvertime ? "text-warning-400" : "text-surface-100"
                    )}>
                        {formatTime(elapsedSeconds)}
                    </span>

                    {focusMode.isPlaying ? (
                        <button onClick={pauseFocusSession} className="p-1 rounded-md text-surface-400 hover:text-surface-100 hover:bg-surface-800 transition-colors">
                            <Pause className="w-3.5 h-3.5" />
                        </button>
                    ) : (
                        <button onClick={resumeFocus} className="p-1 rounded-md text-accent-400 hover:text-accent-300 hover:bg-accent-600/20 transition-colors">
                            <Play className="w-3.5 h-3.5" />
                        </button>
                    )}

                    <button onClick={() => setIsMinimized(false)} className="p-1 rounded-md text-surface-500 hover:text-surface-200 hover:bg-surface-800 transition-colors">
                        <Maximize2 className="w-3 h-3" />
                    </button>
                </div>
            </div>
        );
    }

    // ─── Full Expanded View ───────────────────────────────────
    return (
        <div
            ref={dragRef}
            className={clsx("fixed z-[9999] select-none", isDragging ? "cursor-grabbing" : "")}
            style={{ left: position.x, top: position.y }}
        >
            <div className="w-80 bg-surface-900/95 backdrop-blur-xl border border-surface-700/60 rounded-2xl shadow-2xl shadow-black/50 overflow-hidden">
                {/* Drag Handle */}
                <div
                    className="flex items-center justify-between px-4 py-2.5 border-b border-surface-800/60 cursor-grab active:cursor-grabbing"
                    onMouseDown={handleMouseDown}
                >
                    <div className="flex items-center gap-2">
                        <GripVertical className="w-4 h-4 text-surface-600" />
                        <div className={clsx("w-2 h-2 rounded-full", focusMode.isPlaying ? "bg-accent-500 animate-pulse" : "bg-surface-500")} />
                        <span className="text-xs font-semibold tracking-wider uppercase text-accent-500">Focus Mode</span>
                    </div>
                    <button
                        onClick={() => setIsMinimized(true)}
                        className="p-1 rounded-md text-surface-500 hover:text-surface-200 hover:bg-surface-800 transition-colors"
                        title="Minimize"
                    >
                        <Minimize2 className="w-3.5 h-3.5" />
                    </button>
                </div>

                <div className="p-4">
                    <h3 dir={getTextDirection(target.title)} style={getDirectionStyle(target.title)} className="text-surface-100 font-medium truncate mb-3" title={target.title}>{target.title}</h3>

                    <div className="flex items-center justify-between">
                        <div className={clsx("text-3xl font-mono font-semibold tracking-tight tabular-nums", isOvertime ? "text-warning-400" : "text-surface-100")}>
                            {formatTime(elapsedSeconds)}
                        </div>
                        {plannedMinutes > 0 && (
                            <div className="text-right flex flex-col items-end">
                                <span className="text-2xs text-surface-500 uppercase tracking-wider font-semibold">Planned</span>
                                <span className="text-sm font-medium text-surface-300">{plannedMinutes}m</span>
                            </div>
                        )}
                    </div>

                    {plannedMinutes > 0 && (
                        <div className="h-1.5 w-full bg-surface-800 rounded-full overflow-hidden mt-3">
                            <div
                                className={clsx("h-full rounded-full transition-all duration-1000", isOvertime ? "bg-warning-500" : "bg-accent-500")}
                                style={{ width: `${progressPercent}%` }}
                            />
                        </div>
                    )}

                    <div className="flex items-center gap-2 mt-4">
                        {focusMode.isPlaying ? (
                            <button onClick={pauseFocusSession} className="flex-1 py-2.5 flex justify-center items-center gap-2 rounded-xl bg-surface-800 hover:bg-surface-700 text-surface-200 transition-all font-medium text-sm" title="Pause">
                                <Pause className="w-4 h-4" /> Pause
                            </button>
                        ) : (
                            <button onClick={resumeFocus} className="flex-1 py-2.5 flex justify-center items-center gap-2 rounded-xl bg-accent-600/20 hover:bg-accent-600/30 text-accent-400 transition-all font-medium text-sm" title="Resume">
                                <Play className="w-4 h-4" /> Resume
                            </button>
                        )}

                        <button onClick={stopFocusSession} className="p-2.5 flex justify-center items-center rounded-xl bg-surface-800 hover:bg-surface-700 text-surface-400 hover:text-surface-200 transition-all" title="Stop">
                            <Square className="w-4 h-4" />
                        </button>

                        {target.kind === 'task' && (
                            <button onClick={handleComplete} className="p-2.5 flex justify-center items-center rounded-xl bg-success-600/20 hover:bg-success-600/30 text-success-500 transition-all" title="Complete">
                                <CheckCircle2 className="w-4 h-4" />
                            </button>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
};
