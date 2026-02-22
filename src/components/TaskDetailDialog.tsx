import React, { useState, useEffect, useRef } from 'react';
import { useStore } from '../store';
import { Task } from '../types';
import {
    X,
    Calendar,
    Clock,
    FileText,
    Tag,
    Folder,
    Plus,
    Save,
} from 'lucide-react';
import { clsx } from 'clsx';
import { format } from 'date-fns';

const DURATION_PRESETS = [15, 30, 45, 60, 90, 120];
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
        tasks,
        selectedDate,
    } = useStore();

    const titleRef = useRef<HTMLInputElement>(null);

    // Form state
    const [title, setTitle] = useState('');
    const [notes, setNotes] = useState('');
    const [project, setProject] = useState('');
    const [plannedDate, setPlannedDate] = useState('');
    const [durationMinutes, setDurationMinutes] = useState(30);
    const [status, setStatus] = useState<Task['status']>('planned');
    const [dueDate, setDueDate] = useState('');
    const [scheduledStart, setScheduledStart] = useState('');

    // Get existing projects for autocomplete
    const existingProjects = [...new Set(tasks.map(t => t.project).filter(Boolean))];

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
                setStatus(task.status);
                setDueDate(task.dueDate || '');
                setScheduledStart(task.scheduledStart ? task.scheduledStart.split('T')[1]?.substring(0, 5) || '' : '');
            }
        } else {
            // Create mode — reset form
            setTitle('');
            setNotes('');
            setProject('');
            setPlannedDate(selectedDate);
            setDurationMinutes(30);
            setStatus('planned');
            setDueDate('');
            setScheduledStart('');
        }

        // Auto-focus title
        setTimeout(() => titleRef.current?.focus(), 100);
    }, [taskDialog.isOpen, taskDialog.mode, taskDialog.taskId, tasks, selectedDate]);

    if (!taskDialog.isOpen) return null;

    const handleSave = async () => {
        if (!title.trim()) return;

        const taskData: Partial<Task> = {
            title: title.trim(),
            notes,
            project,
            plannedDate: plannedDate || null,
            durationMinutes,
            status,
            dueDate: dueDate || null,
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

    const isEdit = taskDialog.mode === 'edit';

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
                            value={notes}
                            onChange={(e) => setNotes(e.target.value)}
                            placeholder="Add notes, details, or context..."
                            rows={3}
                            className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 placeholder-surface-600 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all resize-none"
                        />
                    </div>

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
                        <input
                            type="text"
                            value={project}
                            onChange={(e) => setProject(e.target.value)}
                            placeholder="Assign to a project..."
                            list="project-suggestions"
                            className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 placeholder-surface-600 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all"
                        />
                        <datalist id="project-suggestions">
                            {existingProjects.map((p) => (
                                <option key={p} value={p} />
                            ))}
                        </datalist>
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
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between px-6 py-4 border-t border-surface-800 bg-surface-900">
                    <span className="text-xs text-surface-500">
                        <kbd className="text-surface-400 bg-surface-800 px-1.5 py-0.5 rounded text-2xs">Ctrl+Enter</kbd> to save
                    </span>
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
