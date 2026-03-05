import React, { useState, useEffect, useRef } from 'react';
import { useStore } from '../store';
import { Task, Subtask } from '../types';
import {
    X,
    Calendar,
    Clock,
    FileText,
    Tag,
    Folder,
    Plus,
    Save,
    Timer,
    Trash2,
    Flag,
    Bell,
    CheckSquare,
    Square,
    ListTodo,
} from 'lucide-react';
import { clsx } from 'clsx';
import { format } from 'date-fns';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';
import { PRIORITY_COLORS, PRIORITY_LABELS } from '../priorityUtils';

const DURATION_PRESETS = [15, 30, 45, 60, 90, 120];
const TRACKED_PRESETS = [0, 15, 30, 60, 90, 120];
const STATUS_OPTIONS: { value: Task['status']; label: string; color: string }[] = [
    { value: 'inbox', label: 'Inbox', color: 'bg-surface-500' },
    { value: 'backlog', label: 'Backlog', color: 'bg-surface-400' },
    { value: 'planned', label: 'Planned', color: 'bg-accent-500' },
    { value: 'scheduled', label: 'Scheduled', color: 'bg-blue-500' },
    { value: 'done', label: 'Done', color: 'bg-success-500' },
];

export function TaskDetailDialog() {
    const {
        taskDialog,
        closeTaskDialog,
        createTask,
        updateTask,
        deleteTask,
        tasks,
        projects,
        selectedDate,
        createSubtask,
        updateSubtask,
        deleteSubtask,
    } = useStore();

    const titleRef = useRef<HTMLInputElement>(null);

    // Form state
    const [title, setTitle] = useState('');
    const [notes, setNotes] = useState('');
    const [project, setProject] = useState('');
    const [plannedDate, setPlannedDate] = useState('');
    const [durationMinutes, setDurationMinutes] = useState(30);
    const [actualTimeMinutes, setActualTimeMinutes] = useState(0);
    const [status, setStatus] = useState<Task['status']>('planned');
    const [dueDate, setDueDate] = useState('');
    const [scheduledStart, setScheduledStart] = useState('');
    const [priority, setPriority] = useState<Task['priority']>(null);
    const [reminderMinutes, setReminderMinutes] = useState<number | null>(0);
    const [newSubtaskTitle, setNewSubtaskTitle] = useState('');
    const [editingSubtaskId, setEditingSubtaskId] = useState<string | null>(null);
    const [editingSubtaskTitle, setEditingSubtaskTitle] = useState('');


    // Populate form on open
    useEffect(() => {
        if (!taskDialog.isOpen) return;

        if (taskDialog.mode === 'edit' && taskDialog.taskId) {
            const task = tasks.find(t => t.id === taskDialog.taskId);
            if (task) {
                setTitle(task.title);
                setNotes(task.notes || '');
                setProject(task.project || '');
                setPlannedDate(task.plannedDate || '');
                setDurationMinutes(task.durationMinutes || 30);
                setActualTimeMinutes(task.actualTimeMinutes || 0);
                setStatus(task.status);
                setDueDate(task.dueDate || '');
                setScheduledStart(task.scheduledStart ? task.scheduledStart.split('T')[1]?.substring(0, 5) || '' : '');
                setPriority(task.priority ?? null);
                setReminderMinutes(task.reminderMinutes ?? 0);
            }
        } else {
            // Create mode — reset form
            setTitle('');
            setNotes('');
            setProject('');
            setPlannedDate(selectedDate);
            setDurationMinutes(30);
            setActualTimeMinutes(0);
            setStatus('planned');
            setDueDate('');
            setScheduledStart('');
            setPriority(null);
            setReminderMinutes(0);
        }

        // Auto-focus title
        setTimeout(() => titleRef.current?.focus(), 100);
    }, [taskDialog.isOpen, taskDialog.mode, taskDialog.taskId, tasks, selectedDate]);

    if (!taskDialog.isOpen) return null;

    const isEdit = taskDialog.mode === 'edit';
    const currentTask = isEdit && taskDialog.taskId ? tasks.find(t => t.id === taskDialog.taskId) : null;
    const subtasks = currentTask?.subtasks || [];

    const handleAddSubtask = async () => {
        if (!newSubtaskTitle.trim() || !taskDialog.taskId) return;
        await createSubtask(taskDialog.taskId, newSubtaskTitle.trim());
        setNewSubtaskTitle('');
    };

    const handleToggleSubtask = async (subtask: Subtask) => {
        if (!taskDialog.taskId) return;
        await updateSubtask(taskDialog.taskId, subtask.id, { completed: !subtask.completed });
    };

    const handleSaveSubtaskEdit = async (subtask: Subtask) => {
        if (!taskDialog.taskId) return;
        if (editingSubtaskTitle.trim() && editingSubtaskTitle !== subtask.title) {
            await updateSubtask(taskDialog.taskId, subtask.id, { title: editingSubtaskTitle.trim() });
        }
        setEditingSubtaskId(null);
    };

    const handleDeleteSubtask = async (subtaskId: string) => {
        if (!taskDialog.taskId) return;
        await deleteSubtask(taskDialog.taskId, subtaskId);
    };

    const handleSave = async () => {
        if (!title.trim()) return;

        const taskData: Partial<Task> = {
            title: title.trim(),
            notes,
            project,
            plannedDate: plannedDate || null,
            durationMinutes,
            actualTimeMinutes,
            status,
            dueDate: dueDate || null,
            priority,
            reminderMinutes,
        };

        // If a scheduled start time is set
        if (scheduledStart && plannedDate) {
            taskData.scheduledStart = `${plannedDate}T${scheduledStart}:00`;
            const startMs = new Date(taskData.scheduledStart).getTime();
            const endMs = startMs + durationMinutes * 60000;
            taskData.scheduledEnd = new Date(endMs).toISOString().replace('Z', '').split('.')[0];
            taskData.status = 'scheduled';
        }

        if (taskDialog.mode === 'edit' && taskDialog.taskId) {
            await updateTask(taskDialog.taskId, taskData);
        } else {
            await createTask(taskData);
        }

        closeTaskDialog();
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (e.key === 'Escape') {
            closeTaskDialog();
        }
        if (e.key === 'Enter' && e.ctrlKey) {
            handleSave();
        }
    };

    return (
        <div
            className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4"
            onClick={(e) => {
                if (e.target === e.currentTarget) closeTaskDialog();
            }}
            onKeyDown={handleKeyDown}
        >
            <div className="w-full max-w-lg bg-surface-900 border border-surface-700/60 rounded-2xl shadow-2xl flex flex-col animate-scale-in overflow-hidden">
                {/* Header */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-surface-800">
                    <h2 className="text-lg font-semibold text-surface-100 flex items-center gap-2">
                        {isEdit ? (
                            <>
                                <FileText className="w-5 h-5 text-accent-400" />
                                Edit Task
                            </>
                        ) : (
                            <>
                                <Plus className="w-5 h-5 text-accent-400" />
                                New Task
                            </>
                        )}
                    </h2>
                    <button
                        onClick={closeTaskDialog}
                        className="p-2 -mr-2 text-surface-400 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                    >
                        <X className="w-5 h-5" />
                    </button>
                </div>

                {/* Form Body */}
                <div className="p-6 space-y-5 overflow-y-auto max-h-[65vh] custom-scrollbar">
                    {/* Title */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2">
                            Title
                        </label>
                        <input
                            ref={titleRef}
                            type="text"
                            dir={getTextDirection(title)}
                            style={getDirectionStyle(title)}
                            value={title}
                            onChange={(e) => setTitle(e.target.value)}
                            placeholder="What are you working on?"
                            className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 placeholder-surface-600 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all"
                        />
                    </div>

                    {/* Notes / Description */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2">
                            Description
                        </label>
                        <textarea
                            dir={getTextDirection(notes)}
                            style={getDirectionStyle(notes)}
                            value={notes}
                            onChange={(e) => setNotes(e.target.value)}
                            placeholder="Add notes, details, or context..."
                            rows={3}
                            className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 placeholder-surface-600 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all resize-none"
                        />
                    </div>

                    {/* Subtasks (edit mode only) */}
                    {isEdit && taskDialog.taskId && (
                        <div>
                            <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                                <ListTodo className="w-3.5 h-3.5" />
                                Subtasks
                                {subtasks.length > 0 && (
                                    <span className="text-surface-500 font-normal normal-case">
                                        ({subtasks.filter(s => s.completed).length}/{subtasks.length})
                                    </span>
                                )}
                            </label>
                            <div className="space-y-1">
                                {subtasks.map((st) => (
                                    <div key={st.id} className="flex items-center gap-2 group/subtask">
                                        <button
                                            onClick={() => handleToggleSubtask(st)}
                                            className="flex-shrink-0 text-surface-400 hover:text-accent-400 transition-colors"
                                        >
                                            {st.completed ? (
                                                <CheckSquare className="w-4 h-4 text-success-500" />
                                            ) : (
                                                <Square className="w-4 h-4" />
                                            )}
                                        </button>
                                        {editingSubtaskId === st.id ? (
                                            <input
                                                type="text"
                                                value={editingSubtaskTitle}
                                                onChange={(e) => setEditingSubtaskTitle(e.target.value)}
                                                onBlur={() => handleSaveSubtaskEdit(st)}
                                                onKeyDown={(e) => {
                                                    if (e.key === 'Enter') handleSaveSubtaskEdit(st);
                                                    if (e.key === 'Escape') setEditingSubtaskId(null);
                                                }}
                                                autoFocus
                                                className="flex-1 bg-transparent text-sm text-surface-100 outline-none border-b border-accent-500/50 pb-0.5"
                                            />
                                        ) : (
                                            <span
                                                onClick={() => {
                                                    setEditingSubtaskId(st.id);
                                                    setEditingSubtaskTitle(st.title);
                                                }}
                                                className={clsx(
                                                    'flex-1 text-sm cursor-text',
                                                    st.completed ? 'line-through text-surface-500' : 'text-surface-200',
                                                )}
                                            >
                                                {st.title}
                                            </span>
                                        )}
                                        <button
                                            onClick={() => handleDeleteSubtask(st.id)}
                                            className="flex-shrink-0 opacity-0 group-hover/subtask:opacity-100 text-surface-500 hover:text-red-400 transition-all p-0.5"
                                        >
                                            <X className="w-3.5 h-3.5" />
                                        </button>
                                    </div>
                                ))}
                                {/* Add new subtask */}
                                <div className="flex items-center gap-2 mt-1">
                                    <Plus className="w-4 h-4 text-surface-500 flex-shrink-0" />
                                    <input
                                        type="text"
                                        value={newSubtaskTitle}
                                        onChange={(e) => setNewSubtaskTitle(e.target.value)}
                                        onKeyDown={(e) => {
                                            if (e.key === 'Enter') {
                                                e.preventDefault();
                                                e.stopPropagation();
                                                handleAddSubtask();
                                            }
                                        }}
                                        placeholder="Add a subtask..."
                                        className="flex-1 bg-transparent text-sm text-surface-200 placeholder-surface-600 outline-none border-b border-transparent focus:border-surface-700 pb-0.5 transition-colors"
                                    />
                                </div>
                            </div>
                        </div>
                    )}

                    {/* Date & Time Row */}
                    <div className="grid grid-cols-2 gap-4">
                        {/* Planned Date */}
                        <div>
                            <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                                <Calendar className="w-3.5 h-3.5" />
                                Planned Date
                            </label>
                            <input
                                type="date"
                                value={plannedDate}
                                onChange={(e) => setPlannedDate(e.target.value)}
                                className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all [color-scheme:dark]"
                            />
                        </div>

                        {/* Scheduled Start */}
                        <div>
                            <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                                <Clock className="w-3.5 h-3.5" />
                                Start Time
                            </label>
                            <input
                                type="time"
                                value={scheduledStart}
                                onChange={(e) => setScheduledStart(e.target.value)}
                                className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all [color-scheme:dark]"
                            />
                        </div>
                    </div>

                    {/* Duration */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                            <Clock className="w-3.5 h-3.5" />
                            Duration
                        </label>
                        <div className="flex items-center gap-2 flex-wrap">
                            {DURATION_PRESETS.map((d) => (
                                <button
                                    key={d}
                                    onClick={() => setDurationMinutes(d)}
                                    className={clsx(
                                        'px-3 py-1.5 rounded-lg text-xs font-medium transition-all border',
                                        durationMinutes === d
                                            ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                                            : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                                    )}
                                >
                                    {d >= 60 ? `${d / 60}h` : `${d}m`}
                                </button>
                            ))}
                            {/* Custom input */}
                            <input
                                type="number"
                                min={5}
                                max={480}
                                step={5}
                                value={durationMinutes}
                                onChange={(e) => setDurationMinutes(Math.max(5, parseInt(e.target.value) || 5))}
                                className="w-20 px-3 py-1.5 bg-surface-950 border border-surface-700/60 rounded-lg text-xs text-surface-200 focus:outline-none focus:border-accent-500/50 text-center"
                            />
                            <span className="text-xs text-surface-500">min</span>
                        </div>
                    </div>

                    {/* Tracked Time (edit mode only) */}
                    {isEdit && (
                        <div>
                            <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                                <Timer className="w-3.5 h-3.5" />
                                Tracked Time
                            </label>
                            <div className="flex items-center gap-2 flex-wrap">
                                {TRACKED_PRESETS.map((d) => (
                                    <button
                                        key={d}
                                        onClick={() => setActualTimeMinutes(d)}
                                        className={clsx(
                                            'px-3 py-1.5 rounded-lg text-xs font-medium transition-all border',
                                            actualTimeMinutes === d
                                                ? 'bg-emerald-600/20 border-emerald-500/40 text-emerald-400'
                                                : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                                        )}
                                    >
                                        {d >= 60 ? `${d / 60}h` : `${d}m`}
                                    </button>
                                ))}
                                <input
                                    type="number"
                                    min={0}
                                    max={960}
                                    step={1}
                                    value={actualTimeMinutes}
                                    onChange={(e) => setActualTimeMinutes(Math.max(0, parseInt(e.target.value) || 0))}
                                    className="w-20 px-3 py-1.5 bg-surface-950 border border-surface-700/60 rounded-lg text-xs text-surface-200 focus:outline-none focus:border-emerald-500/50 text-center"
                                />
                                <span className="text-xs text-surface-500">min</span>
                            </div>
                            {actualTimeMinutes > 0 && (
                                <p className="mt-1.5 text-xs text-surface-500">
                                    {Math.floor(actualTimeMinutes / 60)}h {actualTimeMinutes % 60}m tracked
                                </p>
                            )}
                        </div>
                    )}

                    {/* Due Date */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                            <Calendar className="w-3.5 h-3.5" />
                            Due Date <span className="text-surface-600 font-normal normal-case">(optional)</span>
                        </label>
                        <input
                            type="date"
                            value={dueDate}
                            onChange={(e) => setDueDate(e.target.value)}
                            className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all [color-scheme:dark]"
                        />
                    </div>

                    {/* Project */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                            <Folder className="w-3.5 h-3.5" />
                            Project
                        </label>
                        <select
                            value={project}
                            onChange={(e) => setProject(e.target.value)}
                            className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all [color-scheme:dark] appearance-none cursor-pointer"
                            style={{ backgroundImage: `url("data:image/svg+xml,%3csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%2364748b' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3e%3cpolyline points='6 9 12 15 18 9'%3e%3c/polyline%3e%3c/svg%3e")`, backgroundRepeat: 'no-repeat', backgroundPosition: 'right 12px center' }}
                        >
                            <option value="">No project</option>
                            {projects.map((p) => (
                                <option key={p.id} value={p.name}>
                                    {p.emoji} {p.name}
                                </option>
                            ))}
                        </select>
                    </div>

                    {/* Status */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                            <Tag className="w-3.5 h-3.5" />
                            Status
                        </label>
                        <div className="flex items-center gap-2 flex-wrap">
                            {STATUS_OPTIONS.map((opt) => (
                                <button
                                    key={opt.value}
                                    onClick={() => setStatus(opt.value)}
                                    className={clsx(
                                        'flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all border',
                                        status === opt.value
                                            ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                                            : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                                    )}
                                >
                                    <div className={clsx('w-2 h-2 rounded-full', opt.color)} />
                                    {opt.label}
                                </button>
                            ))}
                        </div>
                    </div>

                    {/* Priority */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                            <Flag className="w-3.5 h-3.5" />
                            Priority
                        </label>
                        <div className="flex items-center gap-2 flex-wrap">
                            <button
                                onClick={() => setPriority(null)}
                                className={clsx(
                                    'flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all border',
                                    priority === null
                                        ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                                        : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                                )}
                            >
                                None
                            </button>
                            {([1, 2, 3, 4] as const).map((p) => (
                                <button
                                    key={p}
                                    onClick={() => setPriority(p)}
                                    className={clsx(
                                        'flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all border',
                                        priority === p
                                            ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                                            : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                                    )}
                                >
                                    <div
                                        className="w-2 h-2 rounded-full flex-shrink-0"
                                        style={{ backgroundColor: PRIORITY_COLORS[p] }}
                                    />
                                    {PRIORITY_LABELS[p]}
                                </button>
                            ))}
                        </div>
                    </div>

                    {/* Reminder */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                            <Bell className="w-3.5 h-3.5" />
                            Reminder
                        </label>
                        <div className="flex items-center gap-2 flex-wrap">
                            {([
                                { value: null, label: 'None' },
                                { value: 0, label: 'At start' },
                                { value: 5, label: '5m before' },
                                { value: 10, label: '10m before' },
                                { value: 15, label: '15m before' },
                                { value: 30, label: '30m before' },
                            ] as const).map((opt) => (
                                <button
                                    key={String(opt.value)}
                                    onClick={() => setReminderMinutes(opt.value)}
                                    className={clsx(
                                        'px-3 py-1.5 rounded-lg text-xs font-medium transition-all border',
                                        reminderMinutes === opt.value
                                            ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                                            : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                                    )}
                                >
                                    {opt.label}
                                </button>
                            ))}
                        </div>
                    </div>
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between px-6 py-4 border-t border-surface-800 bg-surface-900">
                    <div className="flex items-center gap-2">
                        <span className="text-xs text-surface-500">
                            <kbd className="text-surface-400 bg-surface-800 px-1.5 py-0.5 rounded text-2xs">Ctrl+Enter</kbd> to save
                        </span>
                        {isEdit && taskDialog.taskId && (
                            <button
                                onClick={async () => {
                                    if (taskDialog.taskId) {
                                        await deleteTask(taskDialog.taskId);
                                        closeTaskDialog();
                                    }
                                }}
                                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-red-400 hover:text-red-300 hover:bg-red-500/10 rounded-lg transition-colors border border-transparent hover:border-red-500/20"
                            >
                                <Trash2 className="w-3.5 h-3.5" />
                                Delete
                            </button>
                        )}
                    </div>
                    <div className="flex items-center gap-3">
                        <button
                            onClick={closeTaskDialog}
                            className="px-4 py-2 text-sm text-surface-300 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={!title.trim()}
                            className={clsx(
                                "flex items-center gap-2 px-5 py-2 text-sm font-medium rounded-lg transition-all shadow-lg",
                                title.trim()
                                    ? "bg-accent-600 hover:bg-accent-500 text-white shadow-accent-500/20"
                                    : "bg-surface-800 text-surface-500 cursor-not-allowed shadow-none",
                            )}
                        >
                            <Save className="w-4 h-4" />
                            {isEdit ? 'Update' : 'Create'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
