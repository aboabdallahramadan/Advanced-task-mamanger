import React, { useMemo, useState } from 'react';
import { useStore } from '../store';
import { Task } from '../types';
import {
    Check,
    Plus,
    Settings,
    Clock,
    Calendar,
    Play,
    ArrowLeft,
    CheckCircle2,
    GripVertical,
} from 'lucide-react';
import { clsx } from 'clsx';
import { format, parseISO } from 'date-fns';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';
import { getPriorityBorderStyle } from '../priorityUtils';
import {
    DndContext,
    DragOverlay,
    closestCenter,
    KeyboardSensor,
    PointerSensor,
    useSensor,
    useSensors,
    type DragStartEvent,
    type DragEndEvent,
} from '@dnd-kit/core';
import {
    SortableContext,
    verticalListSortingStrategy,
    sortableKeyboardCoordinates,
    useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

export function ProjectView() {
    const {
        tasks,
        projects,
        selectedProjectId,
        updateTask,
        markDone,
        openTaskDialog,
        openProjectDialog,
        startFocusSession,
        focusMode,
        setCurrentView,
        createTask,
        reorderTasks,
    } = useStore();

    const [activeTask, setActiveTask] = useState<Task | null>(null);

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
        useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
    );

    const project = projects.find(p => p.id === selectedProjectId);

    const projectTasks = useMemo(() => {
        if (!project) return [];
        let result = tasks
            .filter(t => t.project === project.name && t.status !== 'archived')
            .sort((a, b) => {
                // Sort: active first, then done; within each group by order
                if (a.status === 'done' && b.status !== 'done') return 1;
                if (a.status !== 'done' && b.status === 'done') return -1;
                return a.order - b.order;
            });

        // Collapse recurring instances: show only the next upcoming per recurrence rule
        const today = format(new Date(), 'yyyy-MM-dd');
        const bestByRule = new Map<string, Task>();
        const nonRecurring: Task[] = [];
        for (const t of result) {
            if (!t.recurrenceRuleId) {
                nonRecurring.push(t);
                continue;
            }
            const existing = bestByRule.get(t.recurrenceRuleId);
            if (!existing) {
                bestByRule.set(t.recurrenceRuleId, t);
                continue;
            }
            const tIsUpcoming = t.plannedDate ? t.plannedDate >= today : false;
            const eIsUpcoming = existing.plannedDate ? existing.plannedDate >= today : false;
            if (tIsUpcoming && !eIsUpcoming) {
                bestByRule.set(t.recurrenceRuleId, t);
            } else if (tIsUpcoming && eIsUpcoming && t.plannedDate! < existing.plannedDate!) {
                bestByRule.set(t.recurrenceRuleId, t);
            } else if (!tIsUpcoming && !eIsUpcoming && t.plannedDate! > existing.plannedDate!) {
                bestByRule.set(t.recurrenceRuleId, t);
            }
        }
        result = [...nonRecurring, ...bestByRule.values()];

        return result;
    }, [tasks, project]);

    const activeTasks = projectTasks.filter(t => t.status !== 'done');
    const doneTasks = projectTasks.filter(t => t.status === 'done');
    const [showDone, setShowDone] = React.useState(false);

    const totalPlanned = activeTasks.reduce((s, t) => s + (t.durationMinutes || 0), 0);
    const totalActual = projectTasks.reduce((s, t) => s + (t.actualTimeMinutes || 0), 0);

    // Quick add
    const [quickAddText, setQuickAddText] = React.useState('');
    const quickAddRef = React.useRef<HTMLInputElement>(null);

    if (!project) {
        return (
            <div className="flex-1 flex items-center justify-center text-surface-500">
                <p>Project not found</p>
            </div>
        );
    }

    const handleDragStart = (event: DragStartEvent) => {
        const task = activeTasks.find((t) => t.id === event.active.id);
        if (task) setActiveTask(task);
    };

    const handleDragEnd = (event: DragEndEvent) => {
        setActiveTask(null);
        const { active, over } = event;
        if (!over || active.id === over.id) return;

        const oldIndex = activeTasks.findIndex((t) => t.id === active.id);
        const newIndex = activeTasks.findIndex((t) => t.id === over.id);
        if (oldIndex === -1 || newIndex === -1) return;

        const reordered = [...activeTasks];
        const [moved] = reordered.splice(oldIndex, 1);
        reordered.splice(newIndex, 0, moved);

        const updates = reordered.map((t, i) => ({ id: t.id, order: i }));
        reorderTasks(updates);
    };

    const handleQuickAdd = () => {
        if (quickAddText.trim() && project) {
            createTask({
                title: quickAddText.trim(),
                project: project.name,
                status: 'planned',
            });
            setQuickAddText('');
        }
    };

    return (
        <div className="flex-1 flex flex-col h-full bg-surface-950">
            {/* Header */}
            <div className="px-6 pt-10 pb-4 border-b border-surface-800/40">
                {/* Back + Title */}
                <div className="flex items-center gap-3 mb-3">
                    <button
                        onClick={() => setCurrentView('board')}
                        className="p-1.5 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
                    >
                        <ArrowLeft className="w-4 h-4" />
                    </button>
                    <div className="flex items-center gap-3 flex-1">
                        <span className="text-2xl">{project.emoji}</span>
                        <div>
                            <h1 dir={getTextDirection(project.name)} style={getDirectionStyle(project.name)} className="text-xl font-bold text-surface-100">{project.name}</h1>
                            <p className="text-xs text-surface-500">
                                {activeTasks.length} active · {doneTasks.length} completed
                            </p>
                        </div>
                        <div
                            className="w-3 h-3 rounded-full ml-1"
                            style={{ backgroundColor: project.color }}
                        />
                    </div>
                    <button
                        onClick={() => openProjectDialog('edit', project.id)}
                        className="p-2 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
                        title="Edit project"
                    >
                        <Settings className="w-4 h-4" />
                    </button>
                </div>

                {/* Stats bar */}
                <div className="flex items-center gap-6">
                    <div className="flex items-center gap-2 text-xs text-surface-400">
                        <Clock className="w-3.5 h-3.5" />
                        <span>
                            {Math.floor(totalPlanned / 60)}h {totalPlanned % 60}m planned
                        </span>
                    </div>
                    <div className="flex items-center gap-2 text-xs text-surface-400">
                        <Play className="w-3.5 h-3.5" />
                        <span>
                            {Math.floor(totalActual / 60)}h {totalActual % 60}m tracked
                        </span>
                    </div>
                    {activeTasks.length > 0 && (
                        <div className="flex-1 max-w-[200px]">
                            <div className="h-1.5 w-full bg-surface-800 rounded-full overflow-hidden">
                                <div
                                    className="h-full rounded-full transition-all"
                                    style={{
                                        width: `${Math.min(100, (doneTasks.length / (activeTasks.length + doneTasks.length)) * 100)}%`,
                                        backgroundColor: project.color,
                                    }}
                                />
                            </div>
                        </div>
                    )}
                </div>
            </div>

            {/* Quick Add */}
            <div className="px-6 py-3 border-b border-surface-800/20">
                <div className="flex items-center gap-2">
                    <Plus className="w-4 h-4 text-surface-500" />
                    <input
                        ref={quickAddRef}
                        type="text"
                        dir={getTextDirection(quickAddText)}
                        style={getDirectionStyle(quickAddText)}
                        value={quickAddText}
                        onChange={(e) => setQuickAddText(e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === 'Enter') handleQuickAdd();
                        }}
                        placeholder={`Add task to ${project.name}...`}
                        className="flex-1 bg-transparent text-sm text-surface-100 placeholder-surface-600 outline-none"
                    />
                </div>
            </div>

            {/* Task List */}
            <div className="flex-1 overflow-y-auto px-6 py-4 custom-scrollbar">
                {projectTasks.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-64 text-center">
                        <span className="text-4xl mb-3">{project.emoji}</span>
                        <h3 className="text-sm font-medium text-surface-400">No tasks in this project</h3>
                        <p className="text-xs text-surface-500 mt-1">Add a task above to get started</p>
                    </div>
                ) : (
                    <>
                        {/* Active tasks */}
                        <DndContext
                            sensors={sensors}
                            collisionDetection={closestCenter}
                            onDragStart={handleDragStart}
                            onDragEnd={handleDragEnd}
                        >
                            <SortableContext items={activeTasks.map(t => t.id)} strategy={verticalListSortingStrategy}>
                                <div className="space-y-1">
                                    {activeTasks.map(task => (
                                        <ProjectTaskRow
                                            key={task.id}
                                            task={task}
                                            projectColor={project.color}
                                            onToggleDone={() => markDone(task.id)}
                                            onClick={() => openTaskDialog('edit', task.id)}
                                            onStartTimer={() => startFocusSession(task.id)}
                                            isFocused={focusMode.activeTaskId === task.id}
                                        />
                                    ))}
                                </div>
                            </SortableContext>

                            <DragOverlay dropAnimation={null}>
                                {activeTask && (
                                    <ProjectTaskRow
                                        task={activeTask}
                                        projectColor={project.color}
                                        onToggleDone={() => {}}
                                        onClick={() => {}}
                                        onStartTimer={() => {}}
                                        isFocused={false}
                                        isDragOverlay
                                    />
                                )}
                            </DragOverlay>
                        </DndContext>

                        {/* Done tasks */}
                        {doneTasks.length > 0 && (
                            <div className="mt-6">
                                <button
                                    onClick={() => setShowDone(!showDone)}
                                    className="flex items-center gap-2 text-xs text-surface-500 hover:text-surface-300 mb-2 transition-colors"
                                >
                                    <CheckCircle2 className="w-3.5 h-3.5" />
                                    <span>{doneTasks.length} completed</span>
                                    <span className="text-surface-600">{showDone ? '▾' : '▸'}</span>
                                </button>
                                {showDone && (
                                    <div className="space-y-1 animate-fade-in">
                                        {doneTasks.map(task => (
                                            <ProjectTaskRow
                                                key={task.id}
                                                task={task}
                                                projectColor={project.color}
                                                onToggleDone={() => updateTask(task.id, { status: 'planned' })}
                                                onClick={() => openTaskDialog('edit', task.id)}
                                                onStartTimer={() => { }}
                                                isFocused={false}
                                            />
                                        ))}
                                    </div>
                                )}
                            </div>
                        )}
                    </>
                )}
            </div>
        </div>
    );
}

// ─── Project Task Row ────────────────────────────────────────────────
interface ProjectTaskRowProps {
    task: Task;
    projectColor: string;
    onToggleDone: () => void;
    onClick: () => void;
    onStartTimer: () => void;
    isFocused: boolean;
    isDragOverlay?: boolean;
}

function ProjectTaskRow({ task, projectColor, onToggleDone, onClick, onStartTimer, isFocused, isDragOverlay }: ProjectTaskRowProps) {
    const isDone = task.status === 'done';

    const {
        attributes,
        listeners,
        setNodeRef,
        transform,
        transition,
        isDragging,
    } = useSortable({
        id: task.id,
        data: { type: 'task', task },
    });

    const sortableStyle = {
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.3 : 1,
    };

    return (
        <div
            ref={setNodeRef}
            style={{ ...sortableStyle, ...getPriorityBorderStyle(task.priority) }}
            onClick={onClick}
            className={clsx(
                "group flex items-center gap-3 px-4 py-3 rounded-xl cursor-pointer transition-all",
                "border border-transparent hover:border-surface-800/60 hover:bg-surface-900/50",
                isFocused && "border-accent-500/40 bg-accent-500/5",
                isDone && "opacity-50",
                isDragOverlay && "shadow-2xl bg-surface-900 border-surface-700/60"
            )}
        >
            {/* Drag handle */}
            {!isDone && (
                <div
                    {...attributes}
                    {...listeners}
                    className="opacity-0 group-hover:opacity-100 transition-opacity cursor-grab active:cursor-grabbing p-0.5 flex-shrink-0"
                    aria-label="Drag to reorder"
                    onClick={(e) => e.stopPropagation()}
                >
                    <GripVertical className="w-3.5 h-3.5 text-surface-500" />
                </div>
            )}

            {/* Checkbox */}
            <button
                onClick={(e) => { e.stopPropagation(); onToggleDone(); }}
                className={clsx(
                    "w-5 h-5 rounded-full border-2 flex items-center justify-center flex-shrink-0 transition-all",
                    isDone
                        ? "border-success-500 bg-success-500"
                        : "border-surface-600 hover:border-accent-500"
                )}
                style={!isDone ? { borderColor: projectColor + '60' } : undefined}
            >
                {isDone && <Check className="w-3 h-3 text-white" strokeWidth={3} />}
            </button>

            {/* Title & meta */}
            <div className="flex-1 min-w-0">
                <h4 dir={getTextDirection(task.title)} style={getDirectionStyle(task.title)} className={clsx(
                    "text-sm font-medium",
                    isDone ? "line-through text-surface-500" : "text-surface-100"
                )}>
                    {task.title}
                </h4>
                <div className="flex items-center gap-3 mt-0.5">
                    {task.plannedDate && (
                        <span className="flex items-center gap-1 text-2xs text-surface-500">
                            <Calendar className="w-3 h-3" />
                            {format(parseISO(task.plannedDate), 'MMM d')}
                        </span>
                    )}
                </div>
            </div>

            {/* Duration chip */}
            <span className="chip text-2xs flex-shrink-0">
                {task.durationMinutes >= 60
                    ? `${(task.durationMinutes / 60).toFixed(task.durationMinutes % 60 ? 1 : 0)}h`
                    : `${task.durationMinutes}m`
                }
            </span>

            {/* Timer button */}
            {!isDone && (
                <button
                    onClick={(e) => { e.stopPropagation(); onStartTimer(); }}
                    className={clsx(
                        "flex items-center justify-center w-7 h-7 rounded-full transition-all flex-shrink-0",
                        isFocused
                            ? "bg-accent-500 text-white animate-pulse"
                            : "opacity-0 group-hover:opacity-100 text-surface-500 hover:text-accent-400 hover:bg-accent-500/10"
                    )}
                    title="Start timer"
                >
                    <Play className="w-3.5 h-3.5" fill={isFocused ? "currentColor" : "none"} />
                </button>
            )}

            {/* Status badge */}
            <span className={clsx(
                "text-2xs px-2 py-0.5 rounded-md flex-shrink-0",
                task.status === 'done' && "bg-success-900/30 text-success-400",
                task.status === 'planned' && "bg-accent-900/30 text-accent-400",
                task.status === 'scheduled' && "bg-blue-900/30 text-blue-400",
                task.status === 'inbox' && "bg-surface-800 text-surface-400",
                task.status === 'backlog' && "bg-surface-800 text-surface-400",
            )}>
                {task.status}
            </span>
        </div>
    );
}
