import React, { useRef, useEffect, useState, useCallback, useMemo } from 'react';
import { useDroppable } from '@dnd-kit/core';
import { Clock, AlertTriangle, ChevronLeft, ChevronRight } from 'lucide-react';
import { useStore } from '../store';
import { Task } from '../types';
import { clsx } from 'clsx';
import { format, parseISO, addMinutes, addDays, subDays, isToday as isTodayFn } from 'date-fns';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';

const HOUR_HEIGHT = 80; // pixels per hour
const MIN_BLOCK_MINUTES = 15;

export function DayTimeline() {
    const {
        tasks,
        selectedDate,
        setSelectedDate,
        workStartHour,
        workEndHour,
        timeIncrement,
        scheduleTask,
        unscheduleTask,
        updateTask,
        freeMinutesRemaining,
    } = useStore();

    const containerRef = useRef<HTMLDivElement>(null);
    const [currentTimeTop, setCurrentTimeTop] = useState(0);

    const scheduledTasks = useMemo(
        () =>
            tasks.filter(
                (t) =>
                    t.status === 'scheduled' &&
                    t.scheduledStart &&
                    t.scheduledStart.startsWith(selectedDate),
            ),
        [tasks, selectedDate],
    );

    const hours = useMemo(() => {
        const arr: number[] = [];
        for (let h = workStartHour; h <= workEndHour; h++) arr.push(h);
        return arr;
    }, [workStartHour, workEndHour]);

    // Current time indicator
    useEffect(() => {
        const update = () => {
            const now = new Date();
            const today = format(now, 'yyyy-MM-dd');
            if (today !== selectedDate) {
                setCurrentTimeTop(-1);
                return;
            }
            const minutesSinceStart = (now.getHours() - workStartHour) * 60 + now.getMinutes();
            const top = (minutesSinceStart / 60) * HOUR_HEIGHT;
            setCurrentTimeTop(top);
        };
        update();
        const interval = setInterval(update, 60000);
        return () => clearInterval(interval);
    }, [selectedDate, workStartHour]);

    // Scroll to current time on mount or date change
    useEffect(() => {
        if (containerRef.current && currentTimeTop > 0) {
            containerRef.current.scrollTop = Math.max(0, currentTimeTop - 200);
        }
    }, [selectedDate]);

    const getTimeFromY = useCallback(
        (y: number): { hour: number; minute: number } => {
            const totalMinutes = (y / HOUR_HEIGHT) * 60;
            const snappedMinutes = Math.round(totalMinutes / timeIncrement) * timeIncrement;
            const hour = workStartHour + Math.floor(snappedMinutes / 60);
            const minute = snappedMinutes % 60;
            return { hour: Math.min(hour, workEndHour), minute };
        },
        [timeIncrement, workStartHour, workEndHour],
    );

    const getYFromTime = useCallback(
        (dateStr: string): number => {
            const date = parseISO(dateStr);
            const minutesSinceStart = (date.getHours() - workStartHour) * 60 + date.getMinutes();
            return (minutesSinceStart / 60) * HOUR_HEIGHT;
        },
        [workStartHour],
    );

    // Check for conflicts
    const conflicts = useMemo(() => {
        const sorted = [...scheduledTasks].sort((a, b) =>
            (a.scheduledStart || '').localeCompare(b.scheduledStart || ''),
        );
        const conflictIds = new Set<string>();
        for (let i = 0; i < sorted.length; i++) {
            for (let j = i + 1; j < sorted.length; j++) {
                if (sorted[i].scheduledEnd! > sorted[j].scheduledStart!) {
                    conflictIds.add(sorted[i].id);
                    conflictIds.add(sorted[j].id);
                }
            }
        }
        return conflictIds;
    }, [scheduledTasks]);

    const freeMinutes = freeMinutesRemaining();

    // Date navigation
    const goToday = () => setSelectedDate(format(new Date(), 'yyyy-MM-dd'));
    const goPrev = () => setSelectedDate(format(subDays(parseISO(selectedDate), 1), 'yyyy-MM-dd'));
    const goNext = () => setSelectedDate(format(addDays(parseISO(selectedDate), 1), 'yyyy-MM-dd'));

    const displayDate = parseISO(selectedDate);
    const isToday = isTodayFn(displayDate);
    const dayLabel = format(displayDate, 'EEEE, MMM d');

    return (
        <div className="flex flex-col h-full">
            {/* Timeline header with date nav */}
            <div className="px-4 pt-10 pb-3 border-b border-surface-800/40">
                {/* Date navigation row */}
                <div className="flex items-center justify-between mb-2">
                    <div className="flex items-center gap-1">
                        <button
                            onClick={goPrev}
                            className="p-1.5 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
                        >
                            <ChevronLeft className="w-4 h-4" />
                        </button>
                        <button
                            onClick={goNext}
                            className="p-1.5 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
                        >
                            <ChevronRight className="w-4 h-4" />
                        </button>
                        <h2 className="text-base font-semibold text-surface-100 ml-2">{dayLabel}</h2>
                        {isToday && (
                            <span className="ml-2 text-2xs bg-accent-600/20 text-accent-400 px-2 py-0.5 rounded-md font-medium">
                                Today
                            </span>
                        )}
                    </div>
                    {!isToday && (
                        <button
                            onClick={goToday}
                            className="text-xs text-accent-400 hover:text-accent-300 transition-colors"
                        >
                            Go to today
                        </button>
                    )}
                </div>
                {/* Stats row */}
                <div className="flex items-center gap-3 flex-wrap">
                    {/* Daily tracked time */}
                    {(() => {
                        const dayTasks = tasks.filter(t => t.plannedDate === selectedDate && t.status !== 'archived');
                        const totalPlanned = dayTasks.reduce((s, t) => s + (t.durationMinutes || 0), 0);
                        const totalTracked = dayTasks.reduce((s, t) => s + (t.actualTimeMinutes || 0), 0);
                        const ph = Math.floor(totalPlanned / 60);
                        const pm = totalPlanned % 60;
                        const th = Math.floor(totalTracked / 60);
                        const tm = totalTracked % 60;
                        return (
                            <>
                                <span className="chip text-xs bg-surface-800 text-surface-300 border-surface-700/40">
                                    📋 {ph}h {pm}m planned
                                </span>
                                <span className={clsx(
                                    "chip text-xs",
                                    totalTracked > totalPlanned && totalTracked > 0
                                        ? "bg-warning-900/40 text-warning-300 border-warning-700/30"
                                        : "bg-accent-900/30 text-accent-300 border-accent-700/30"
                                )}>
                                    ⏱ {th}h {tm}m tracked
                                </span>
                            </>
                        );
                    })()}
                    <span className={clsx(
                        'chip text-xs',
                        freeMinutes > 120 ? 'chip-success' : freeMinutes > 0 ? 'chip-warning' : 'bg-danger-900/40 text-danger-300 border-danger-700/30',
                    )}>
                        {freeMinutes > 0
                            ? `${Math.floor(freeMinutes / 60)}h ${freeMinutes % 60}m free`
                            : 'Fully booked'}
                    </span>
                    {conflicts.size > 0 && (
                        <div className="flex items-center gap-1.5 text-xs text-warning-400">
                            <AlertTriangle className="w-3.5 h-3.5" />
                            <span>{Math.floor(conflicts.size / 2)} conflict{conflicts.size > 2 ? 's' : ''}</span>
                        </div>
                    )}
                </div>
            </div>

            {/* Timeline body */}
            <div ref={containerRef} className="flex-1 overflow-y-auto relative custom-scrollbar">
                <div
                    className="relative ml-16 mr-4"
                    style={{ height: (workEndHour - workStartHour + 1) * HOUR_HEIGHT }}
                >
                    {/* Hour lines */}
                    {hours.map((hour) => (
                        <HourLine
                            key={hour}
                            hour={hour}
                            top={(hour - workStartHour) * HOUR_HEIGHT}
                        />
                    ))}

                    {/* Drop zones for each time slot */}
                    {hours.map((hour) => {
                        const slotsPerHour = 60 / timeIncrement;
                        return Array.from({ length: slotsPerHour }, (_, i) => {
                            const minute = i * timeIncrement;
                            const slotId = `slot-${hour}-${minute}`;
                            const top = (hour - workStartHour) * HOUR_HEIGHT + (minute / 60) * HOUR_HEIGHT;
                            const height = (timeIncrement / 60) * HOUR_HEIGHT;
                            return (
                                <TimeSlotDrop
                                    key={slotId}
                                    id={slotId}
                                    top={top}
                                    height={height}
                                    hour={hour}
                                    minute={minute}
                                    selectedDate={selectedDate}
                                    timeIncrement={timeIncrement}
                                />
                            );
                        });
                    })}

                    {/* Current time indicator */}
                    {currentTimeTop > 0 && currentTimeTop < (workEndHour - workStartHour + 1) * HOUR_HEIGHT && (
                        <div
                            className="absolute left-0 right-0 z-30 pointer-events-none"
                            style={{ top: currentTimeTop }}
                        >
                            <div className="flex items-center">
                                <div className="w-2.5 h-2.5 bg-danger-500 rounded-full -ml-1.5 shadow-lg shadow-danger-500/30" />
                                <div className="flex-1 h-[2px] bg-danger-500/60" />
                            </div>
                        </div>
                    )}

                    {/* Scheduled task blocks */}
                    {scheduledTasks.map((task) => (
                        <TimeBlock
                            key={task.id}
                            task={task}
                            top={getYFromTime(task.scheduledStart!)}
                            height={(task.durationMinutes / 60) * HOUR_HEIGHT}
                            hasConflict={conflicts.has(task.id)}
                            selectedDate={selectedDate}
                            timeIncrement={timeIncrement}
                            workStartHour={workStartHour}
                            workEndHour={workEndHour}
                            onUpdate={updateTask}
                            onUnschedule={unscheduleTask}
                        />
                    ))}
                </div>
            </div>
        </div>
    );
}

// Hour label + line
function HourLine({ hour, top }: { hour: number; top: number }) {
    const label = hour === 0 ? '12 AM' : hour < 12 ? `${hour} AM` : hour === 12 ? '12 PM' : `${hour - 12} PM`;

    return (
        <div className="absolute left-0 right-0" style={{ top }}>
            <div className="flex items-start">
                <span className="text-2xs text-surface-500 w-14 -ml-16 text-right pr-3 -mt-1.5 select-none">
                    {label}
                </span>
                <div className="flex-1 border-t border-surface-800/40" />
            </div>
        </div>
    );
}

// Drop target for time slots
function TimeSlotDrop({
    id,
    top,
    height,
    hour,
    minute,
    selectedDate,
    timeIncrement,
}: {
    id: string;
    top: number;
    height: number;
    hour: number;
    minute: number;
    selectedDate: string;
    timeIncrement: number;
}) {
    const { isOver, setNodeRef } = useDroppable({
        id,
        data: {
            type: 'timeline-slot',
            hour,
            minute,
            selectedDate,
            timeIncrement,
        },
    });

    return (
        <div
            ref={setNodeRef}
            className={clsx(
                'absolute left-0 right-0 transition-colors duration-75',
                isOver && 'bg-accent-500/10 ring-1 ring-inset ring-accent-500/30 rounded',
            )}
            style={{ top, height }}
        />
    );
}

// Scheduled task block — uses refs for drag/resize to avoid stale closures
function TimeBlock({
    task,
    top,
    height,
    hasConflict,
    selectedDate,
    timeIncrement,
    workStartHour,
    workEndHour,
    onUpdate,
    onUnschedule,
}: {
    task: Task;
    top: number;
    height: number;
    hasConflict: boolean;
    selectedDate: string;
    timeIncrement: number;
    workStartHour: number;
    workEndHour: number;
    onUpdate: (id: string, updates: Partial<Task>) => Promise<void>;
    onUnschedule: (id: string) => Promise<void>;
}) {
    const [isDragging, setIsDragging] = useState(false);
    const [isResizing, setIsResizing] = useState(false);
    const [currentTop, setCurrentTop] = useState(top);
    const [currentHeight, setCurrentHeight] = useState(height);
    const blockRef = useRef<HTMLDivElement>(null);

    // Refs to track latest values for mouse event handlers (avoids stale closure)
    const currentTopRef = useRef(currentTop);
    const currentHeightRef = useRef(currentHeight);
    useEffect(() => { currentTopRef.current = currentTop; }, [currentTop]);
    useEffect(() => { currentHeightRef.current = currentHeight; }, [currentHeight]);

    useEffect(() => {
        setCurrentTop(top);
        setCurrentHeight(height);
    }, [top, height]);

    const { projects } = useStore();
    const projectObj = projects.find(p => p.name === task.project);
    const projectColor = projectObj?.color || '#6366f1';

    const snapToGrid = (y: number) => {
        const minuteHeight = HOUR_HEIGHT / 60;
        const snappedMinutes = Math.round(y / minuteHeight / timeIncrement) * timeIncrement;
        return snappedMinutes * minuteHeight;
    };

    const handleMoveStart = (e: React.MouseEvent) => {
        if (isResizing) return;
        e.preventDefault();
        e.stopPropagation();
        setIsDragging(true);
        const offsetY = e.clientY - currentTopRef.current;

        const handleMove = (ev: MouseEvent) => {
            const newTop = snapToGrid(ev.clientY - offsetY);
            const maxTop = (workEndHour - workStartHour) * HOUR_HEIGHT - currentHeightRef.current;
            const clamped = Math.max(0, Math.min(newTop, maxTop));
            setCurrentTop(clamped);
            currentTopRef.current = clamped;
        };

        const handleUp = () => {
            setIsDragging(false);
            document.removeEventListener('mousemove', handleMove);
            document.removeEventListener('mouseup', handleUp);

            // Read from ref for the latest value
            const latestTop = currentTopRef.current;
            const totalMinutes = (latestTop / HOUR_HEIGHT) * 60;
            const newHour = workStartHour + Math.floor(totalMinutes / 60);
            const newMinute = Math.round(totalMinutes % 60);
            const start = `${selectedDate}T${String(newHour).padStart(2, '0')}:${String(newMinute).padStart(2, '0')}:00`;
            const endDate = addMinutes(parseISO(start), task.durationMinutes);
            const end = format(endDate, "yyyy-MM-dd'T'HH:mm:ss");

            onUpdate(task.id, { scheduledStart: start, scheduledEnd: end });
        };

        document.addEventListener('mousemove', handleMove);
        document.addEventListener('mouseup', handleUp);
    };

    const handleResizeStart = (e: React.MouseEvent) => {
        e.preventDefault();
        e.stopPropagation();
        setIsResizing(true);
        const startY = e.clientY;
        const startHeight = currentHeightRef.current;

        const handleMove = (ev: MouseEvent) => {
            const delta = ev.clientY - startY;
            const newHeight = snapToGrid(startHeight + delta);
            const minHeight = (MIN_BLOCK_MINUTES / 60) * HOUR_HEIGHT;
            const clamped = Math.max(minHeight, newHeight);
            setCurrentHeight(clamped);
            currentHeightRef.current = clamped;
        };

        const handleUp = () => {
            setIsResizing(false);
            document.removeEventListener('mousemove', handleMove);
            document.removeEventListener('mouseup', handleUp);

            // Read from ref for the latest value
            const latestHeight = currentHeightRef.current;
            const newDuration = Math.round((latestHeight / HOUR_HEIGHT) * 60);
            const snappedDuration = Math.max(
                MIN_BLOCK_MINUTES,
                Math.round(newDuration / timeIncrement) * timeIncrement,
            );
            const start = parseISO(task.scheduledStart!);
            const end = addMinutes(start, snappedDuration);

            onUpdate(task.id, {
                durationMinutes: snappedDuration,
                scheduledEnd: format(end, "yyyy-MM-dd'T'HH:mm:ss"),
            });
        };

        document.addEventListener('mousemove', handleMove);
        document.addEventListener('mouseup', handleUp);
    };

    const startTime = task.scheduledStart
        ? format(parseISO(task.scheduledStart), 'h:mm a')
        : '';
    const endTime = task.scheduledEnd
        ? format(parseISO(task.scheduledEnd), 'h:mm a')
        : '';

    return (
        <div
            ref={blockRef}
            className={clsx(
                'absolute left-1 right-1 rounded-lg border px-2.5 py-1.5 z-20 group',
                hasConflict && 'ring-2 ring-warning-500/50',
                isDragging && 'cursor-grabbing shadow-2xl shadow-black/40 z-40 opacity-90',
                !isDragging && 'cursor-grab',
            )}
            style={{
                top: currentTop,
                height: Math.max(currentHeight, 24),
                backgroundColor: projectColor + '20',
                borderColor: projectColor + '50',
            }}
            onMouseDown={handleMoveStart}
            onDoubleClick={() => onUnschedule(task.id)}
            title="Drag to move • Double-click to unschedule"
        >
            {/* Content */}
            <div className="flex items-start justify-between h-full overflow-hidden">
                <div className="flex-1 min-w-0">
                    <p dir={getTextDirection(task.title)} className="text-xs font-medium truncate" style={{ ...getDirectionStyle(task.title), color: projectColor }}>
                        {task.title}
                    </p>
                    {currentHeight > 40 && (
                        <p className="text-2xs text-surface-500 mt-0.5">
                            {startTime} – {endTime}
                        </p>
                    )}
                    {currentHeight > 60 && task.project && (
                        <p dir={getTextDirection(task.project!)} className="text-2xs mt-0.5" style={{ ...getDirectionStyle(task.project!), color: projectColor + '99' }}>{task.project}</p>
                    )}
                </div>
                {hasConflict && (
                    <AlertTriangle className="w-3 h-3 text-warning-400 flex-shrink-0 mt-0.5" />
                )}
            </div>

            {/* Resize handle (bottom) */}
            <div
                className="absolute bottom-0 left-0 right-0 h-2 cursor-ns-resize opacity-0 group-hover:opacity-100 transition-opacity flex items-center justify-center"
                onMouseDown={handleResizeStart}
            >
                <div className="w-8 h-1 bg-surface-400/50 rounded-full" />
            </div>
        </div>
    );
}
