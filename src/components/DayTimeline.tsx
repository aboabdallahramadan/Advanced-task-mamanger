import React, { useRef, useEffect, useState, useCallback, useMemo } from 'react';
import { useDroppable } from '@dnd-kit/core';
import { Clock, AlertTriangle, GripVertical } from 'lucide-react';
import { useStore } from '../store';
import { Task } from '../types';
import { clsx } from 'clsx';
import { format, parseISO, differenceInMinutes, setHours, setMinutes, addMinutes } from 'date-fns';

const HOUR_HEIGHT = 80; // pixels per hour
const MIN_BLOCK_MINUTES = 15;

export function DayTimeline() {
    const {
        tasks,
        selectedDate,
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

    // Scroll to current time on mount
    useEffect(() => {
        if (containerRef.current && currentTimeTop > 0) {
            containerRef.current.scrollTop = Math.max(0, currentTimeTop - 200);
        }
    }, []);

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

    return (
        <div className="flex flex-col h-full">
            {/* Timeline header */}
            <div className="px-4 pt-10 pb-3 border-b border-surface-800/40 flex items-center justify-between">
                <div className="flex items-center gap-3">
                    <h2 className="text-lg font-semibold text-surface-100 tracking-tight">Schedule</h2>
                    <span className={clsx(
                        'chip text-xs',
                        freeMinutes > 120 ? 'chip-success' : freeMinutes > 0 ? 'chip-warning' : 'bg-danger-900/40 text-danger-300 border-danger-700/30',
                    )}>
                        {freeMinutes > 0
                            ? `${Math.floor(freeMinutes / 60)}h ${freeMinutes % 60}m free`
                            : 'Fully booked'}
                    </span>
                </div>
                {conflicts.size > 0 && (
                    <div className="flex items-center gap-1.5 text-xs text-warning-400">
                        <AlertTriangle className="w-3.5 h-3.5" />
                        <span>{conflicts.size / 2} conflict{conflicts.size > 2 ? 's' : ''}</span>
                    </div>
                )}
            </div>

            {/* Timeline body */}
            <div ref={containerRef} className="flex-1 overflow-y-auto relative">
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

// Scheduled task block
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
    const [dragStartY, setDragStartY] = useState(0);
    const [currentTop, setCurrentTop] = useState(top);
    const [currentHeight, setCurrentHeight] = useState(height);
    const blockRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        setCurrentTop(top);
        setCurrentHeight(height);
    }, [top, height]);

    const projectColors: Record<string, { bg: string; border: string; text: string }> = {
        Engineering: { bg: 'bg-blue-900/50', border: 'border-blue-600/50', text: 'text-blue-300' },
        Design: { bg: 'bg-purple-900/50', border: 'border-purple-600/50', text: 'text-purple-300' },
        Product: { bg: 'bg-emerald-900/50', border: 'border-emerald-600/50', text: 'text-emerald-300' },
        DevOps: { bg: 'bg-orange-900/50', border: 'border-orange-600/50', text: 'text-orange-300' },
        Research: { bg: 'bg-cyan-900/50', border: 'border-cyan-600/50', text: 'text-cyan-300' },
        Communication: { bg: 'bg-pink-900/50', border: 'border-pink-600/50', text: 'text-pink-300' },
    };

    const colors = projectColors[task.project] || {
        bg: 'bg-accent-900/50',
        border: 'border-accent-600/50',
        text: 'text-accent-300',
    };

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
        setDragStartY(e.clientY - currentTop);

        const handleMove = (ev: MouseEvent) => {
            const newTop = snapToGrid(ev.clientY - (e.clientY - currentTop));
            const maxTop = (workEndHour - workStartHour) * HOUR_HEIGHT - currentHeight;
            setCurrentTop(Math.max(0, Math.min(newTop, maxTop)));
        };

        const handleUp = () => {
            setIsDragging(false);
            document.removeEventListener('mousemove', handleMove);
            document.removeEventListener('mouseup', handleUp);

            // Calculate new time
            const totalMinutes = (currentTop / HOUR_HEIGHT) * 60;
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
        const startHeight = currentHeight;

        const handleMove = (ev: MouseEvent) => {
            const delta = ev.clientY - startY;
            const newHeight = snapToGrid(startHeight + delta);
            const minHeight = (MIN_BLOCK_MINUTES / 60) * HOUR_HEIGHT;
            setCurrentHeight(Math.max(minHeight, newHeight));
        };

        const handleUp = () => {
            setIsResizing(false);
            document.removeEventListener('mousemove', handleMove);
            document.removeEventListener('mouseup', handleUp);

            // Calculate new duration
            const newDuration = Math.round((currentHeight / HOUR_HEIGHT) * 60);
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
                'transition-shadow duration-100',
                colors.bg,
                colors.border,
                hasConflict && 'ring-2 ring-warning-500/50',
                isDragging && 'cursor-grabbing shadow-2xl shadow-black/40 z-40 opacity-90',
                !isDragging && 'cursor-grab',
            )}
            style={{
                top: currentTop,
                height: Math.max(currentHeight, 24),
            }}
            onMouseDown={handleMoveStart}
            onDoubleClick={() => onUnschedule(task.id)}
            title="Drag to move • Double-click to unschedule"
        >
            {/* Content */}
            <div className="flex items-start justify-between h-full overflow-hidden">
                <div className="flex-1 min-w-0">
                    <p className={clsx('text-xs font-medium truncate', colors.text)}>
                        {task.title}
                    </p>
                    {currentHeight > 40 && (
                        <p className="text-2xs text-surface-500 mt-0.5">
                            {startTime} – {endTime}
                        </p>
                    )}
                    {currentHeight > 60 && task.project && (
                        <p className="text-2xs text-surface-600 mt-0.5">{task.project}</p>
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
