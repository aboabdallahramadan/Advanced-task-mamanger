import React, { useMemo, useState, useCallback } from 'react';
import {
    DndContext,
    DragOverlay,
    PointerSensor,
    useSensor,
    useSensors,
    useDroppable,
    type DragStartEvent,
    type DragEndEvent,
    type DragOverEvent,
    closestCenter,
} from '@dnd-kit/core';
import {
    SortableContext,
    verticalListSortingStrategy,
    useSortable,
    arrayMove,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useStore } from '../store';
import { Task } from '../types';
import {
    Check,
    Plus,
    ChevronLeft,
    ChevronRight,
    Play,
    GripVertical,
} from 'lucide-react';
import { clsx } from 'clsx';
import {
    format,
    addDays,
    startOfWeek,
    isToday,
    parseISO,
} from 'date-fns';

export function WeeklyBoardView() {
    const {
        tasks,
        createTask,
        updateTask,
        markDone,
        reorderTasks,
        openTaskDialog,
        startFocusSession,
        focusMode,
    } = useStore();

    const [weekOffset, setWeekOffset] = useState(0);
    const [activeTask, setActiveTask] = useState<Task | null>(null);

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    );

    // Calculate the 7 days of the current week
    const weekDays = useMemo(() => {
        const baseDate = addDays(new Date(), weekOffset * 7);
        const weekStart = startOfWeek(baseDate, { weekStartsOn: 0 });
        return Array.from({ length: 7 }, (_, i) => addDays(weekStart, i));
    }, [weekOffset]);

    const dateKeys = useMemo(() => weekDays.map(d => format(d, 'yyyy-MM-dd')), [weekDays]);

    // Group tasks by planned date
    const tasksByDate = useMemo(() => {
        const map = new Map<string, Task[]>();
        for (const key of dateKeys) {
            map.set(key, []);
        }
        for (const task of tasks) {
            if (task.status === 'archived') continue;
            if (task.plannedDate && map.has(task.plannedDate)) {
                map.get(task.plannedDate)!.push(task);
            }
        }
        for (const [, dayTasks] of map) {
            dayTasks.sort((a, b) => a.order - b.order);
        }
        return map;
    }, [tasks, dateKeys]);

    // Total for the week
    const weekTotalMinutes = useMemo(() => {
        let total = 0;
        for (const [, dayTasks] of tasksByDate) {
            for (const t of dayTasks) {
                if (t.status !== 'done') total += (t.durationMinutes || 0);
            }
        }
        return total;
    }, [tasksByDate]);

    const weekLabel = useMemo(() => {
        const first = weekDays[0];
        const last = weekDays[6];
        if (first.getMonth() === last.getMonth()) {
            return `${format(first, 'MMMM d')} – ${format(last, 'd, yyyy')}`;
        }
        return `${format(first, 'MMM d')} – ${format(last, 'MMM d, yyyy')}`;
    }, [weekDays]);

    // ─── DnD Handlers ────────────────────────────────────────────────
    const handleDragStart = useCallback((event: DragStartEvent) => {
        const taskId = event.active.id as string;
        const task = tasks.find(t => t.id === taskId);
        if (task) setActiveTask(task);
    }, [tasks]);

    const handleDragEnd = useCallback((event: DragEndEvent) => {
        setActiveTask(null);
        const { active, over } = event;
        if (!over) return;

        const draggedId = active.id as string;
        const overId = over.id as string;

        // Find which column the dragged task came from
        const draggedTask = tasks.find(t => t.id === draggedId);
        if (!draggedTask) return;

        // Check if we dropped over a column droppable (date key)
        const isDroppedOnColumn = dateKeys.includes(overId);

        // Determine the target date
        let targetDate: string;
        if (isDroppedOnColumn) {
            targetDate = overId;
        } else {
            // Dropped on another task — find that task's date
            const overTask = tasks.find(t => t.id === overId);
            if (!overTask || !overTask.plannedDate) return;
            targetDate = overTask.plannedDate;
        }

        const sourceDate = draggedTask.plannedDate;

        if (sourceDate === targetDate && !isDroppedOnColumn) {
            // Same column reorder
            const columnTasks = [...(tasksByDate.get(targetDate) || [])];
            const oldIndex = columnTasks.findIndex(t => t.id === draggedId);
            const newIndex = columnTasks.findIndex(t => t.id === overId);
            if (oldIndex === -1 || newIndex === -1 || oldIndex === newIndex) return;

            const reordered = arrayMove(columnTasks, oldIndex, newIndex);
            const updates = reordered.map((t, i) => ({ id: t.id, order: i }));
            reorderTasks(updates);
        } else {
            // Cross-column move: update the task's planned date
            const targetTasks = [...(tasksByDate.get(targetDate) || [])];
            let insertIndex = targetTasks.length; // default: end

            if (!isDroppedOnColumn) {
                const overIndex = targetTasks.findIndex(t => t.id === overId);
                if (overIndex !== -1) insertIndex = overIndex;
            }

            // Move task to new date
            updateTask(draggedId, {
                plannedDate: targetDate,
                status: draggedTask.status === 'scheduled' ? 'planned' : draggedTask.status,
                scheduledStart: null,
                scheduledEnd: null,
                order: insertIndex,
            });
        }
    }, [tasks, tasksByDate, dateKeys, reorderTasks, updateTask]);

    return (
        <div className="flex flex-col h-full bg-surface-950 flex-1">
            {/* Top bar */}
            <div className="flex items-center justify-between px-6 pt-10 pb-4 border-b border-surface-800/40">
                <div className="flex items-center gap-4">
                    <button
                        onClick={() => setWeekOffset(0)}
                        className={clsx(
                            "px-3 py-1.5 text-xs font-medium rounded-lg transition-all border",
                            weekOffset === 0
                                ? "bg-accent-600/20 border-accent-500/40 text-accent-400"
                                : "border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600"
                        )}
                    >
                        Today
                    </button>
                    <div className="flex items-center gap-1">
                        <button
                            onClick={() => setWeekOffset(w => w - 1)}
                            className="p-1.5 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
                        >
                            <ChevronLeft className="w-4 h-4" />
                        </button>
                        <button
                            onClick={() => setWeekOffset(w => w + 1)}
                            className="p-1.5 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
                        >
                            <ChevronRight className="w-4 h-4" />
                        </button>
                    </div>
                    <span className="text-sm font-medium text-surface-200">{weekLabel}</span>
                </div>
                <div className="flex items-center gap-3">
                    <span className="text-xs text-surface-500">
                        {Math.floor(weekTotalMinutes / 60)}h {weekTotalMinutes % 60}m planned
                    </span>
                </div>
            </div>

            {/* Columns with DnD */}
            <DndContext
                sensors={sensors}
                collisionDetection={closestCenter}
                onDragStart={handleDragStart}
                onDragEnd={handleDragEnd}
            >
                <div className="flex-1 flex overflow-x-auto overflow-y-hidden">
                    {weekDays.map((day, i) => {
                        const dateKey = dateKeys[i];
                        const dayTasks = tasksByDate.get(dateKey) || [];
                        return (
                            <DayColumn
                                key={dateKey}
                                date={day}
                                dateKey={dateKey}
                                tasks={dayTasks}
                                isToday={isToday(day)}
                                onCreateTask={(title) => {
                                    createTask({
                                        title,
                                        plannedDate: dateKey,
                                        status: 'planned',
                                    });
                                }}
                                onToggleDone={(task) => {
                                    if (task.status === 'done') {
                                        updateTask(task.id, { status: 'planned' });
                                    } else {
                                        markDone(task.id);
                                    }
                                }}
                                onOpenDetail={(task) => openTaskDialog('edit', task.id)}
                                onStartTimer={(task) => startFocusSession(task.id)}
                                focusActiveId={focusMode.activeTaskId}
                            />
                        );
                    })}
                </div>

                <DragOverlay dropAnimation={null}>
                    {activeTask && (
                        <BoardTaskCard
                            task={activeTask}
                            isDragOverlay
                            onToggleDone={() => { }}
                            onClick={() => { }}
                            onStartTimer={() => { }}
                            focusActiveId={null}
                        />
                    )}
                </DragOverlay>
            </DndContext>
        </div>
    );
}

// ─── Day Column ──────────────────────────────────────────────────────
interface DayColumnProps {
    date: Date;
    dateKey: string;
    tasks: Task[];
    isToday: boolean;
    onCreateTask: (title: string) => void;
    onToggleDone: (task: Task) => void;
    onOpenDetail: (task: Task) => void;
    onStartTimer: (task: Task) => void;
    focusActiveId: string | null;
}

function DayColumn({
    date,
    dateKey,
    tasks,
    isToday: today,
    onCreateTask,
    onToggleDone,
    onOpenDetail,
    onStartTimer,
    focusActiveId,
}: DayColumnProps) {
    const [quickAddText, setQuickAddText] = useState('');
    const [isAdding, setIsAdding] = useState(false);
    const quickAddRef = React.useRef<HTMLInputElement>(null);

    // Make the column itself a droppable target
    const { setNodeRef, isOver } = useDroppable({ id: dateKey });

    const totalMinutes = tasks
        .filter(t => t.status !== 'done')
        .reduce((sum, t) => sum + (t.durationMinutes || 0), 0);
    const hrs = Math.floor(totalMinutes / 60);
    const mins = totalMinutes % 60;

    const handleQuickAdd = () => {
        if (quickAddText.trim()) {
            onCreateTask(quickAddText.trim());
            setQuickAddText('');
        }
        setIsAdding(false);
    };

    React.useEffect(() => {
        if (isAdding) quickAddRef.current?.focus();
    }, [isAdding]);

    return (
        <div
            ref={setNodeRef}
            className={clsx(
                "flex-1 min-w-[180px] max-w-[260px] flex flex-col border-r border-surface-800/30 last:border-r-0 transition-colors",
                today && "bg-surface-900/30",
                isOver && "bg-accent-500/5 border-accent-500/20"
            )}
        >
            {/* Day Header */}
            <div className="px-4 pt-4 pb-3">
                <div className="flex items-baseline justify-between">
                    <div>
                        <h3
                            className={clsx(
                                "text-sm font-bold",
                                today ? "text-surface-50" : "text-surface-200"
                            )}
                        >
                            {format(date, 'EEEE')}
                        </h3>
                        <p className={clsx(
                            "text-xs mt-0.5",
                            today ? "text-accent-400" : "text-surface-500"
                        )}>
                            {format(date, 'MMMM d')}
                        </p>
                    </div>
                </div>
                {totalMinutes > 0 && (
                    <div className="mt-2 h-1 w-full bg-surface-800 rounded-full overflow-hidden">
                        <div
                            className={clsx(
                                "h-full rounded-full transition-all",
                                today ? "bg-accent-500" : "bg-surface-600"
                            )}
                            style={{ width: `${Math.min(100, (totalMinutes / 480) * 100)}%` }}
                        />
                    </div>
                )}
            </div>

            {/* Add task row */}
            <div className="px-3 pb-2">
                {isAdding ? (
                    <div className="flex items-center gap-1.5">
                        <input
                            ref={quickAddRef}
                            type="text"
                            value={quickAddText}
                            onChange={(e) => setQuickAddText(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === 'Enter') handleQuickAdd();
                                if (e.key === 'Escape') { setIsAdding(false); setQuickAddText(''); }
                            }}
                            onBlur={handleQuickAdd}
                            placeholder="Task title..."
                            className="flex-1 px-2 py-1.5 text-xs bg-surface-950 border border-surface-700/60 rounded-lg text-surface-100 placeholder-surface-600 outline-none focus:border-accent-500/50"
                        />
                    </div>
                ) : (
                    <div className="flex items-center justify-between">
                        <button
                            onClick={() => setIsAdding(true)}
                            className="flex items-center gap-1.5 text-xs text-surface-500 hover:text-surface-300 transition-colors"
                        >
                            <Plus className="w-3.5 h-3.5" />
                            Add task
                        </button>
                        {totalMinutes > 0 && (
                            <span className="chip text-2xs">
                                {hrs > 0 ? `${hrs}:${String(mins).padStart(2, '0')}` : `${mins}m`}
                            </span>
                        )}
                    </div>
                )}
            </div>

            {/* Task cards (sortable) */}
            <SortableContext items={tasks.map(t => t.id)} strategy={verticalListSortingStrategy}>
                <div className="flex-1 overflow-y-auto px-3 pb-4 space-y-2 custom-scrollbar">
                    {tasks.map((task) => (
                        <SortableBoardTaskCard
                            key={task.id}
                            task={task}
                            onToggleDone={() => onToggleDone(task)}
                            onClick={() => onOpenDetail(task)}
                            onStartTimer={() => onStartTimer(task)}
                            focusActiveId={focusActiveId}
                        />
                    ))}
                    {tasks.length === 0 && isOver && (
                        <div className="border-2 border-dashed border-accent-500/30 rounded-xl h-16 flex items-center justify-center">
                            <span className="text-xs text-accent-500/50">Drop here</span>
                        </div>
                    )}
                </div>
            </SortableContext>
        </div>
    );
}

// ─── Sortable Wrapper ────────────────────────────────────────────────
interface SortableCardProps {
    task: Task;
    onToggleDone: () => void;
    onClick: () => void;
    onStartTimer: () => void;
    focusActiveId: string | null;
}

function SortableBoardTaskCard({ task, onToggleDone, onClick, onStartTimer, focusActiveId }: SortableCardProps) {
    const {
        attributes,
        listeners,
        setNodeRef,
        transform,
        transition,
        isDragging,
    } = useSortable({ id: task.id, data: { type: 'board-task', task } });

    const style = {
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.3 : 1,
    };

    return (
        <div ref={setNodeRef} style={style}>
            <BoardTaskCard
                task={task}
                onToggleDone={onToggleDone}
                onClick={onClick}
                onStartTimer={onStartTimer}
                focusActiveId={focusActiveId}
                dragHandleProps={{ ...attributes, ...listeners }}
            />
        </div>
    );
}

// ─── Board Task Card ─────────────────────────────────────────────────
interface BoardTaskCardProps {
    task: Task;
    onToggleDone: () => void;
    onClick: () => void;
    onStartTimer: () => void;
    focusActiveId: string | null;
    isDragOverlay?: boolean;
    dragHandleProps?: Record<string, any>;
}

function BoardTaskCard({
    task,
    onToggleDone,
    onClick,
    onStartTimer,
    focusActiveId,
    isDragOverlay,
    dragHandleProps,
}: BoardTaskCardProps) {
    const isDone = task.status === 'done';
    const isFocused = focusActiveId === task.id;
    const timeLabel = task.scheduledStart
        ? format(parseISO(task.scheduledStart), 'h:mm a')
        : null;

    return (
        <div
            onClick={onClick}
            className={clsx(
                "group bg-surface-900 border rounded-xl p-3 cursor-pointer transition-all",
                isDragOverlay
                    ? "shadow-2xl border-accent-500/40 rotate-2 scale-105"
                    : "border-surface-800/60 hover:border-surface-700/80 hover:shadow-lg hover:shadow-black/20",
                isDone && "opacity-50",
                isFocused && "border-accent-500/60 ring-1 ring-accent-500/20"
            )}
        >
            {/* Drag handle + Time & Duration */}
            <div className="flex items-center justify-between mb-1.5">
                <div className="flex items-center gap-1.5">
                    {dragHandleProps && (
                        <div
                            {...dragHandleProps}
                            className="cursor-grab active:cursor-grabbing opacity-0 group-hover:opacity-100 transition-opacity p-0.5"
                            onClick={(e) => e.stopPropagation()}
                        >
                            <GripVertical className="w-3 h-3 text-surface-600" />
                        </div>
                    )}
                    {timeLabel ? (
                        <span className="text-2xs text-warning-400 font-medium">~{timeLabel}</span>
                    ) : (
                        <span />
                    )}
                </div>
                <span className="chip text-2xs">
                    {task.durationMinutes >= 60
                        ? `${(task.durationMinutes / 60).toFixed(task.durationMinutes % 60 ? 1 : 0)}h`
                        : `0:${String(task.durationMinutes).padStart(2, '0')}`
                    }
                </span>
            </div>

            {/* Title */}
            <h4
                className={clsx(
                    "text-sm font-medium leading-snug mb-2",
                    isDone ? "line-through text-surface-500" : "text-surface-100"
                )}
            >
                {task.title}
            </h4>

            {/* Footer */}
            <div className="flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <button
                        onClick={(e) => {
                            e.stopPropagation();
                            onToggleDone();
                        }}
                        className={clsx(
                            "w-5 h-5 rounded-full border-2 flex items-center justify-center transition-all",
                            isDone
                                ? "bg-success-500 border-success-500"
                                : "border-surface-600 hover:border-accent-500"
                        )}
                        aria-label={isDone ? 'Mark incomplete' : 'Mark done'}
                    >
                        {isDone && <Check className="w-3 h-3 text-white" strokeWidth={3} />}
                    </button>

                    {/* Timer button */}
                    {!isDone && (
                        <button
                            onClick={(e) => {
                                e.stopPropagation();
                                onStartTimer();
                            }}
                            className={clsx(
                                "flex items-center justify-center w-5 h-5 rounded-full transition-all",
                                isFocused
                                    ? "bg-accent-500 text-white animate-pulse"
                                    : "opacity-0 group-hover:opacity-100 text-surface-500 hover:text-accent-400 hover:bg-accent-500/10"
                            )}
                            title="Start timer"
                        >
                            <Play className="w-3 h-3" fill={isFocused ? "currentColor" : "none"} />
                        </button>
                    )}
                </div>

                {task.project && (
                    <span className="text-2xs text-accent-500 font-medium">
                        #{task.project}
                    </span>
                )}
            </div>
        </div>
    );
}
