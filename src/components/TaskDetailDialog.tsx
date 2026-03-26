import React, { useState, useEffect, useRef } from 'react';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import { ResizableImage } from './ResizableImage';
import { useStore } from '../store';
import { Task, Subtask, RecurrenceFrequency, RecurrenceEndType } from '../types';
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
    Check,
    ListTodo,
    Repeat,
    Bold,
    Italic,
    Strikethrough,
    ImagePlus,
} from 'lucide-react';
import { clsx } from 'clsx';
import { format } from 'date-fns';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';
import { PRIORITY_COLORS, PRIORITY_LABELS } from '../priorityUtils';
import { RecurrenceEditor } from './RecurrenceEditor';
import { RecurrenceActionDialog } from './RecurrenceActionDialog';

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
        createRecurringTask,
        updateRecurrenceSeries,
        deleteRecurrenceSeries,
        deleteRecurrenceSeriesFuture,
        detachRecurrenceInstance,
    } = useStore();

    const titleRef = useRef<HTMLInputElement>(null);

    // Form state
    const [title, setTitle] = useState('');
    const [project, setProject] = useState('');
    const [plannedDate, setPlannedDate] = useState('');
    const [durationMinutes, setDurationMinutes] = useState(30);
    const [actualTimeMinutes, setActualTimeMinutes] = useState(0);
    const [status, setStatus] = useState<Task['status']>('planned');
    const [scheduledStart, setScheduledStart] = useState('');
    const [priority, setPriority] = useState<Task['priority']>(null);
    const [reminderMinutes, setReminderMinutes] = useState<number | null>(0);
    const [newSubtaskTitle, setNewSubtaskTitle] = useState('');
    const [editingSubtaskId, setEditingSubtaskId] = useState<string | null>(null);
    const [editingSubtaskTitle, setEditingSubtaskTitle] = useState('');

    // Recurrence state
    const [isRecurring, setIsRecurring] = useState(false);
    const [recurrenceFrequency, setRecurrenceFrequency] = useState<RecurrenceFrequency>('daily');
    const [recurrenceInterval, setRecurrenceInterval] = useState(1);
    const [recurrenceDaysOfWeek, setRecurrenceDaysOfWeek] = useState<number[]>([0]);
    const [recurrenceEndType, setRecurrenceEndType] = useState<RecurrenceEndType>('never');
    const [recurrenceEndCount, setRecurrenceEndCount] = useState(10);
    const [recurrenceEndDate, setRecurrenceEndDate] = useState('');
    const [recurrenceActionDialog, setRecurrenceActionDialog] = useState<{ mode: 'edit' | 'delete'; pendingData?: Partial<Task> } | null>(null);

    const descriptionEditor = useEditor({
        extensions: [
            StarterKit.configure({ heading: { levels: [1, 2, 3] } }),
            Placeholder.configure({ placeholder: 'Add notes, details, or context...' }),
            ResizableImage.configure({ inline: false, allowBase64: true }),
        ],
        content: '',
        editorProps: {
            attributes: {
                class: 'focus:outline-none min-h-[60px] max-h-[150px] overflow-y-auto px-4 py-2.5 text-sm text-surface-100',
            },
        },
    });

    const handleInsertDescriptionImage = () => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.onchange = (e) => {
            const file = (e.target as HTMLInputElement).files?.[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = () => {
                descriptionEditor?.chain().focus().setImage({ src: reader.result as string }).run();
            };
            reader.readAsDataURL(file);
        };
        input.click();
    };

    // Populate form on open
    useEffect(() => {
        if (!taskDialog.isOpen) return;

        if (taskDialog.mode === 'edit' && taskDialog.taskId) {
            const task = tasks.find(t => t.id === taskDialog.taskId);
            if (task) {
                setTitle(task.title);
                setTimeout(() => descriptionEditor?.commands.setContent(task.notes || ''), 0);
                setProject(task.project || '');
                setPlannedDate(task.plannedDate || '');
                setDurationMinutes(task.durationMinutes || 30);
                setActualTimeMinutes(task.actualTimeMinutes || 0);
                setStatus(task.status);
                setScheduledStart(task.scheduledStart ? task.scheduledStart.split('T')[1]?.substring(0, 5) || '' : '');
                setPriority(task.priority ?? null);
                setReminderMinutes(task.reminderMinutes ?? 0);
                setIsRecurring(!!task.recurrenceRuleId);

                // Load recurrence rule if exists
                if (task.recurrenceRuleId) {
                    window.api.recurrence.getRule(task.recurrenceRuleId).then((rule) => {
                        if (rule) {
                            setRecurrenceFrequency(rule.frequency);
                            setRecurrenceInterval(rule.interval);
                            setRecurrenceDaysOfWeek(rule.daysOfWeek.length > 0 ? rule.daysOfWeek : [0]);
                            setRecurrenceEndType(rule.endType);
                            setRecurrenceEndCount(rule.endCount ?? 10);
                            setRecurrenceEndDate(rule.endDate ?? '');
                        }
                    });
                }
            }
        } else {
            // Create mode — reset form
            setTitle('');
            setTimeout(() => descriptionEditor?.commands.setContent(''), 0);
            setProject('');
            setPlannedDate(selectedDate);
            setDurationMinutes(30);
            setActualTimeMinutes(0);
            setStatus('planned');
            setScheduledStart('');
            setPriority(null);
            setReminderMinutes(0);
            setIsRecurring(false);
            setRecurrenceFrequency('daily');
            setRecurrenceInterval(1);
            setRecurrenceDaysOfWeek([0]);
            setRecurrenceEndType('never');
            setRecurrenceEndCount(10);
            setRecurrenceEndDate('');
        }
        setRecurrenceActionDialog(null);

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
            notes: descriptionEditor?.getHTML() || '',
            project,
            plannedDate: plannedDate || null,
            durationMinutes,
            actualTimeMinutes,
            status,
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
            // If editing a recurring task, show action dialog
            if (currentTask?.recurrenceRuleId && !currentTask.recurrenceDetached) {
                setRecurrenceActionDialog({ mode: 'edit', pendingData: taskData });
                return;
            }
            await updateTask(taskDialog.taskId, taskData);
        } else {
            // Create mode
            if (isRecurring) {
                await createRecurringTask(taskData, {
                    frequency: recurrenceFrequency,
                    interval: recurrenceInterval,
                    daysOfWeek: recurrenceFrequency === 'weekly' ? recurrenceDaysOfWeek : [],
                    endType: recurrenceEndType,
                    endCount: recurrenceEndType === 'count' ? recurrenceEndCount : undefined,
                    endDate: recurrenceEndType === 'date' ? recurrenceEndDate : undefined,
                });
            } else {
                await createTask(taskData);
            }
        }

        closeTaskDialog();
    };

    const handleRecurrenceAction = async (scope: 'this' | 'future' | 'all') => {
        if (!recurrenceActionDialog || !taskDialog.taskId || !currentTask?.recurrenceRuleId) return;

        if (recurrenceActionDialog.mode === 'edit' && recurrenceActionDialog.pendingData) {
            if (scope === 'this') {
                await updateTask(taskDialog.taskId, recurrenceActionDialog.pendingData);
                await detachRecurrenceInstance(taskDialog.taskId);
            } else if (scope === 'future') {
                await updateRecurrenceSeries(currentTask.recurrenceRuleId, recurrenceActionDialog.pendingData);
            }
        } else if (recurrenceActionDialog.mode === 'delete') {
            if (scope === 'this') {
                await deleteTask(taskDialog.taskId);
            } else if (scope === 'future') {
                await deleteRecurrenceSeriesFuture(currentTask.recurrenceRuleId, currentTask.plannedDate || format(new Date(), 'yyyy-MM-dd'));
            } else if (scope === 'all') {
                await deleteRecurrenceSeries(currentTask.recurrenceRuleId);
            }
        }

        setRecurrenceActionDialog(null);
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
                    {/* Recurring task banner */}
                    {isEdit && currentTask?.recurrenceRuleId && (
                        <div className="flex items-center gap-2 px-3 py-2 bg-accent-600/10 border border-accent-500/20 rounded-xl">
                            <Repeat className="w-4 h-4 text-accent-400" />
                            <span className="text-xs text-accent-300">
                                {currentTask.recurrenceDetached ? 'Detached from recurring series' : 'This is a recurring task'}
                            </span>
                        </div>
                    )}

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
                        <div className="bg-surface-950 border border-surface-700/60 rounded-xl focus-within:border-accent-500/50 focus-within:ring-1 focus-within:ring-accent-500/20 transition-all overflow-hidden">
                            {descriptionEditor && (
                                <div className="flex items-center gap-0.5 px-3 py-1.5 border-b border-surface-800/40">
                                    <MiniToolbarButton
                                        onClick={() => descriptionEditor.chain().focus().toggleBold().run()}
                                        active={descriptionEditor.isActive('bold')}
                                        title="Bold"
                                    >
                                        <Bold className="w-3 h-3" />
                                    </MiniToolbarButton>
                                    <MiniToolbarButton
                                        onClick={() => descriptionEditor.chain().focus().toggleItalic().run()}
                                        active={descriptionEditor.isActive('italic')}
                                        title="Italic"
                                    >
                                        <Italic className="w-3 h-3" />
                                    </MiniToolbarButton>
                                    <MiniToolbarButton
                                        onClick={() => descriptionEditor.chain().focus().toggleStrike().run()}
                                        active={descriptionEditor.isActive('strike')}
                                        title="Strikethrough"
                                    >
                                        <Strikethrough className="w-3 h-3" />
                                    </MiniToolbarButton>
                                    <div className="w-px h-3 bg-surface-700 mx-1" />
                                    <MiniToolbarButton
                                        onClick={handleInsertDescriptionImage}
                                        active={false}
                                        title="Insert image"
                                    >
                                        <ImagePlus className="w-3 h-3" />
                                    </MiniToolbarButton>
                                    {descriptionEditor.isActive('image') && (
                                        <>
                                            <div className="w-px h-3 bg-surface-700 mx-1" />
                                            {['25%', '50%', '75%', '100%'].map((size) => (
                                                <button
                                                    key={size}
                                                    type="button"
                                                    onClick={() => descriptionEditor.chain().focus().updateAttributes('image', { width: size }).run()}
                                                    className="px-1 py-0.5 text-2xs rounded text-surface-400 hover:text-surface-100 hover:bg-surface-700/50 transition-colors"
                                                    title={`Resize to ${size}`}
                                                >
                                                    {size}
                                                </button>
                                            ))}
                                        </>
                                    )}
                                </div>
                            )}
                            <EditorContent editor={descriptionEditor} />
                        </div>
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

                            {/* Progress bar */}
                            {subtasks.length > 0 && (
                                <div className="h-1.5 w-full bg-surface-800 rounded-full overflow-hidden mb-3">
                                    <div
                                        className="h-full bg-success-500 rounded-full transition-all duration-500 ease-out"
                                        style={{ width: `${(subtasks.filter(s => s.completed).length / subtasks.length) * 100}%` }}
                                    />
                                </div>
                            )}

                            <div className="space-y-1 bg-surface-950/50 rounded-xl border border-surface-800/40 p-2">
                                {subtasks.map((st) => (
                                    <div
                                        key={st.id}
                                        className={clsx(
                                            "flex items-center gap-2.5 group/subtask rounded-lg px-2.5 py-2 transition-all",
                                            st.completed
                                                ? "bg-transparent"
                                                : "hover:bg-surface-800/40"
                                        )}
                                    >
                                        <button
                                            onClick={() => handleToggleSubtask(st)}
                                            className="flex-shrink-0 transition-all duration-200"
                                        >
                                            <div className={clsx(
                                                "w-[18px] h-[18px] rounded-full border-2 flex items-center justify-center transition-all duration-200",
                                                st.completed
                                                    ? "bg-success-500 border-success-500 scale-100"
                                                    : "border-surface-600 hover:border-accent-500 hover:scale-110"
                                            )}>
                                                {st.completed && <Check className="w-2.5 h-2.5 text-white" strokeWidth={3} />}
                                            </div>
                                        </button>
                                        {editingSubtaskId === st.id ? (
                                            <input
                                                type="text"
                                                dir={getTextDirection(editingSubtaskTitle)}
                                                style={getDirectionStyle(editingSubtaskTitle)}
                                                value={editingSubtaskTitle}
                                                onChange={(e) => setEditingSubtaskTitle(e.target.value)}
                                                onBlur={() => handleSaveSubtaskEdit(st)}
                                                onKeyDown={(e) => {
                                                    if (e.key === 'Enter') handleSaveSubtaskEdit(st);
                                                    if (e.key === 'Escape') setEditingSubtaskId(null);
                                                }}
                                                autoFocus
                                                className="flex-1 bg-surface-900 text-sm text-surface-100 outline-none border border-accent-500/40 rounded-lg px-2.5 py-1 focus:ring-1 focus:ring-accent-500/20 transition-all"
                                            />
                                        ) : (
                                            <span
                                                dir={getTextDirection(st.title)}
                                                style={getDirectionStyle(st.title)}
                                                onClick={() => {
                                                    setEditingSubtaskId(st.id);
                                                    setEditingSubtaskTitle(st.title);
                                                }}
                                                className={clsx(
                                                    'flex-1 text-sm cursor-text transition-all duration-200',
                                                    st.completed
                                                        ? 'line-through text-surface-600'
                                                        : 'text-surface-200',
                                                )}
                                            >
                                                {st.title}
                                            </span>
                                        )}
                                        <button
                                            onClick={() => handleDeleteSubtask(st.id)}
                                            className="flex-shrink-0 opacity-0 group-hover/subtask:opacity-100 text-surface-600 hover:text-red-400 transition-all p-1 rounded-md hover:bg-red-500/10"
                                        >
                                            <Trash2 className="w-3 h-3" />
                                        </button>
                                    </div>
                                ))}
                                {/* Add new subtask */}
                                <div className="flex items-center gap-2.5 px-2.5 py-2 rounded-lg hover:bg-surface-800/30 transition-colors">
                                    <div className="w-[18px] h-[18px] flex items-center justify-center flex-shrink-0">
                                        <Plus className="w-3.5 h-3.5 text-surface-600" />
                                    </div>
                                    <input
                                        type="text"
                                        dir={getTextDirection(newSubtaskTitle)}
                                        style={getDirectionStyle(newSubtaskTitle)}
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
                                        className="flex-1 bg-transparent text-sm text-surface-200 placeholder-surface-600 outline-none transition-colors"
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

                    {/* Recurrence (create mode or editing a recurring task's rule) */}
                    {!isEdit && (
                        <RecurrenceEditor
                            enabled={isRecurring}
                            onToggle={setIsRecurring}
                            frequency={recurrenceFrequency}
                            onFrequencyChange={setRecurrenceFrequency}
                            interval={recurrenceInterval}
                            onIntervalChange={setRecurrenceInterval}
                            daysOfWeek={recurrenceDaysOfWeek}
                            onDaysOfWeekChange={setRecurrenceDaysOfWeek}
                            endType={recurrenceEndType}
                            onEndTypeChange={setRecurrenceEndType}
                            endCount={recurrenceEndCount}
                            onEndCountChange={setRecurrenceEndCount}
                            endDate={recurrenceEndDate}
                            onEndDateChange={setRecurrenceEndDate}
                        />
                    )}

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
                                    if (!taskDialog.taskId) return;
                                    if (currentTask?.recurrenceRuleId) {
                                        setRecurrenceActionDialog({ mode: 'delete' });
                                    } else {
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

            {/* Recurrence Action Dialog */}
            {recurrenceActionDialog && (
                <RecurrenceActionDialog
                    mode={recurrenceActionDialog.mode}
                    onAction={handleRecurrenceAction}
                    onCancel={() => setRecurrenceActionDialog(null)}
                />
            )}
        </div>
    );
}

function MiniToolbarButton({
    onClick,
    active,
    title,
    children,
}: {
    onClick: () => void;
    active: boolean;
    title: string;
    children: React.ReactNode;
}) {
    return (
        <button
            type="button"
            onClick={onClick}
            title={title}
            className={clsx(
                'p-1 rounded transition-colors',
                active
                    ? 'bg-accent-600/30 text-accent-300'
                    : 'text-surface-400 hover:text-surface-200 hover:bg-surface-700/50',
            )}
        >
            {children}
        </button>
    );
}
