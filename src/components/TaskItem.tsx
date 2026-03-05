import React, { useState, useRef, useEffect } from 'react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import {
    Check,
    GripVertical,
    MoreHorizontal,
    CalendarPlus,
    Archive,
    Trash2,
    Folder,
    Play,
    ArrowRight,
    Pencil,
    ListTodo,
} from 'lucide-react';
import { Task } from '../types';
import { useStore } from '../store';
import { DurationChip } from './DurationChip';
import { clsx } from 'clsx';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';
import { getPriorityBorderStyle } from '../priorityUtils';

interface TaskItemProps {
    task: Task;
    isDragOverlay?: boolean;
}

export function TaskItem({ task, isDragOverlay }: TaskItemProps) {
    const {
        selectedTaskIds,
        editingTaskId,
        selectTask,
        toggleTaskSelection,
        setEditingTaskId,
        updateTask,
        markDone,
        moveToToday,
        moveToBacklog,
        archiveTask,
        deleteTask,
        startFocusSession,
        focusMode,
        openTaskDialog,
    } = useStore();

    const [editTitle, setEditTitle] = useState(task.title);
    const [showMenu, setShowMenu] = useState(false);
    const editInputRef = useRef<HTMLInputElement>(null);
    const menuRef = useRef<HTMLDivElement>(null);

    const isSelected = selectedTaskIds.has(task.id);
    const isEditing = editingTaskId === task.id;
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

    const style = {
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.3 : 1,
    };

    useEffect(() => {
        if (isEditing) {
            editInputRef.current?.focus();
            editInputRef.current?.select();
        }
    }, [isEditing]);

    useEffect(() => {
        if (!showMenu) return;
        const handleClick = (e: MouseEvent) => {
            if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
                setShowMenu(false);
            }
        };
        document.addEventListener('mousedown', handleClick);
        return () => document.removeEventListener('mousedown', handleClick);
    }, [showMenu]);

    const handleSaveEdit = () => {
        if (editTitle.trim() && editTitle !== task.title) {
            updateTask(task.id, { title: editTitle.trim() });
        } else {
            setEditTitle(task.title);
        }
        setEditingTaskId(null);
    };

    const handleCheckbox = (e: React.MouseEvent) => {
        e.stopPropagation();
        if (isDone) {
            updateTask(task.id, { status: 'planned' });
        } else {
            markDone(task.id);
        }
    };

    const statusColors: Record<string, string> = {
        inbox: 'bg-surface-600',
        backlog: 'bg-surface-500',
        planned: 'bg-accent-600',
        scheduled: 'bg-blue-500',
        done: 'bg-success-500',
    };

    return (
        <div
            ref={setNodeRef}
            style={{ ...style, ...getPriorityBorderStyle(task.priority) }}
            className={clsx(
                'task-item group',
                isSelected && 'task-item-selected',
                isDone && 'opacity-50',
                isDragOverlay && 'drag-overlay shadow-2xl',
            )}
            onClick={(e) => {
                if (e.ctrlKey) toggleTaskSelection(task.id);
                else selectTask(task.id);
            }}
            onDoubleClick={() => openTaskDialog('edit', task.id)}
            role="listitem"
            aria-selected={isSelected}
            tabIndex={0}
            onKeyDown={(e) => {
                if (e.key === 'Enter') setEditingTaskId(task.id);
                if (e.key === 'Delete') archiveTask(task.id);
                if (e.key === 'd' && e.ctrlKey) { e.preventDefault(); markDone(task.id); }
            }}
        >
            <div className="flex items-center gap-2">
                {/* Drag handle */}
                <div
                    {...attributes}
                    {...listeners}
                    className="opacity-0 group-hover:opacity-100 transition-opacity cursor-grab active:cursor-grabbing p-0.5"
                    aria-label="Drag to reorder"
                >
                    <GripVertical className="w-3.5 h-3.5 text-surface-500" />
                </div>

                {/* Checkbox */}
                <button
                    onClick={handleCheckbox}
                    className={clsx(
                        'w-4.5 h-4.5 rounded-full border-2 flex items-center justify-center transition-all flex-shrink-0',
                        isDone
                            ? 'bg-success-500 border-success-500'
                            : 'border-surface-500 hover:border-accent-500',
                    )}
                    aria-label={isDone ? 'Mark as incomplete' : 'Mark as done'}
                >
                    {isDone && <Check className="w-3 h-3 text-white" strokeWidth={3} />}
                </button>

                {/* Title / Edit */}
                <div className="flex-1 min-w-0">
                    {isEditing ? (
                        <input
                            ref={editInputRef}
                            type="text"
                            dir={getTextDirection(editTitle)}
                            style={getDirectionStyle(editTitle)}
                            value={editTitle}
                            onChange={(e) => setEditTitle(e.target.value)}
                            onBlur={handleSaveEdit}
                            onKeyDown={(e) => {
                                if (e.key === 'Enter') handleSaveEdit();
                                if (e.key === 'Escape') {
                                    setEditTitle(task.title);
                                    setEditingTaskId(null);
                                }
                            }}
                            className="w-full bg-transparent text-sm text-surface-100 outline-none border-b border-accent-500/50 pb-0.5"
                        />
                    ) : (
                        <span
                            dir={getTextDirection(task.title)}
                            style={getDirectionStyle(task.title)}
                            className={clsx(
                                'text-sm truncate block',
                                isDone ? 'line-through text-surface-500' : 'text-surface-200',
                            )}
                        >
                            {task.title}
                        </span>
                    )}
                </div>

                {/* Meta */}
                <div className="flex items-center gap-1.5 flex-shrink-0">
                    {task.subtasks && task.subtasks.length > 0 && (
                        <span className={clsx(
                            "chip text-2xs",
                            task.subtasks.every(s => s.completed)
                                ? "text-success-400"
                                : "text-surface-400"
                        )}>
                            <ListTodo className="w-2.5 h-2.5" />
                            {task.subtasks.filter(s => s.completed).length}/{task.subtasks.length}
                        </span>
                    )}

                    {task.project && (
                        <span className="chip text-2xs hidden group-hover:inline-flex sm:inline-flex">
                            <Folder className="w-2.5 h-2.5" />
                            {task.project}
                        </span>
                    )}

                    {!isDone && (
                        <button
                            onClick={(e) => {
                                e.stopPropagation();
                                startFocusSession(task.id);
                            }}
                            className={clsx(
                                "flex items-center justify-center w-6 h-6 rounded-md transition-all",
                                focusMode.activeTaskId === task.id
                                    ? "bg-accent-500 text-white animate-pulse shadow-glow"
                                    : "opacity-0 group-hover:opacity-100 text-surface-400 hover:text-accent-400 hover:bg-accent-500/10"
                            )}
                            title="Focus on this task"
                        >
                            <Play className="w-3.5 h-3.5" fill={focusMode.activeTaskId === task.id ? "currentColor" : "none"} />
                        </button>
                    )}

                    <DurationChip
                        minutes={task.durationMinutes}
                        onChange={(m) => updateTask(task.id, { durationMinutes: m })}
                        compact
                    />

                    {(task.actualTimeMinutes || 0) > 0 && (
                        <span className={clsx(
                            "text-2xs px-1.5 py-0.5 rounded-md font-medium",
                            (task.actualTimeMinutes || 0) > task.durationMinutes
                                ? "bg-warning-900/30 text-warning-400"
                                : "bg-accent-900/30 text-accent-400"
                        )}>
                            {task.actualTimeMinutes! >= 60
                                ? `${(task.actualTimeMinutes! / 60).toFixed(1)}h`
                                : `${task.actualTimeMinutes}m`
                            } tracked
                        </span>
                    )}

                    {task.scheduledStart && (
                        <span className="chip chip-accent text-2xs">
                            {new Date(task.scheduledStart).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                        </span>
                    )}

                    {/* Actions menu */}
                    <div className="relative" ref={menuRef}>
                        <button
                            onClick={(e) => {
                                e.stopPropagation();
                                setShowMenu(!showMenu);
                            }}
                            className="p-1 rounded opacity-0 group-hover:opacity-100 text-surface-500 hover:text-surface-300 hover:bg-surface-800/60 transition-all"
                            aria-label="Task actions"
                        >
                            <MoreHorizontal className="w-3.5 h-3.5" />
                        </button>

                        {showMenu && (
                            <div className="absolute right-0 top-full mt-1 z-50 bg-surface-900 border border-surface-700/60 rounded-lg shadow-xl shadow-black/40 py-1 min-w-[160px] animate-scale-in">
                                <MenuItem
                                    icon={<Pencil className="w-3.5 h-3.5" />}
                                    label="Edit Details"
                                    onClick={() => { openTaskDialog('edit', task.id); setShowMenu(false); }}
                                />
                                <MenuItem
                                    icon={<ArrowRight className="w-3.5 h-3.5" />}
                                    label="Move to Backlog"
                                    onClick={() => { moveToBacklog(task.id); setShowMenu(false); }}
                                />
                                <MenuItem
                                    icon={<Archive className="w-3.5 h-3.5" />}
                                    label="Archive"
                                    onClick={() => { archiveTask(task.id); setShowMenu(false); }}
                                />
                                <div className="my-1 border-t border-surface-800/40" />
                                <MenuItem
                                    icon={<Trash2 className="w-3.5 h-3.5" />}
                                    label="Delete"
                                    onClick={() => { deleteTask(task.id); setShowMenu(false); }}
                                    danger
                                />
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}

function MenuItem({
    icon,
    label,
    onClick,
    danger,
}: {
    icon: React.ReactNode;
    label: string;
    onClick: () => void;
    danger?: boolean;
}) {
    return (
        <button
            onClick={onClick}
            className={clsx(
                'w-full flex items-center gap-2 px-3 py-1.5 text-xs transition-all',
                danger
                    ? 'text-danger-400 hover:bg-danger-900/30'
                    : 'text-surface-300 hover:bg-surface-800/60',
            )}
        >
            {icon}
            {label}
        </button>
    );
}
