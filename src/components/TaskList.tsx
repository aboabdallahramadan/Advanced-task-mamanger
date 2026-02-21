import React, { useState, useRef, useEffect, useMemo } from 'react';
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
} from '@dnd-kit/sortable';
import { Search, ListChecks, Inbox as InboxIcon, CheckCircle2 } from 'lucide-react';
import { useStore } from '../store';
import { TaskItem } from './TaskItem';
import { QuickAdd } from './QuickAdd';
import { Task } from '../types';
import { clsx } from 'clsx';
import { format, addDays } from 'date-fns';

export function TaskList() {
    const {
        filteredTasks,
        currentView,
        selectedDate,
        searchQuery,
        searchOpen,
        setSearchQuery,
        setSearchOpen,
        reorderTasks,
        loading,
    } = useStore();

    const [activeTask, setActiveTask] = useState<Task | null>(null);
    const searchInputRef = useRef<HTMLInputElement>(null);

    const tasks = filteredTasks();

    // Separate done tasks
    const activeTasks = tasks.filter((t) => t.status !== 'done');
    const doneTasks = tasks.filter((t) => t.status === 'done');
    const [showDone, setShowDone] = useState(false);

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
        useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
    );

    // Keyboard shortcut: / to search
    useEffect(() => {
        const handleKeyDown = (e: KeyboardEvent) => {
            if (e.key === '/' && !e.ctrlKey && !e.metaKey) {
                const target = e.target as HTMLElement;
                if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA') return;
                e.preventDefault();
                setSearchOpen(true);
                setTimeout(() => searchInputRef.current?.focus(), 50);
            }
            if (e.key === 'Escape' && searchOpen) {
                setSearchOpen(false);
            }
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [searchOpen, setSearchOpen]);

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

    const viewLabel = () => {
        switch (currentView) {
            case 'today':
                return format(new Date(), 'EEEE, MMMM d');
            case 'tomorrow':
                return format(addDays(new Date(), 1), 'EEEE, MMMM d');
            case 'week':
                return 'This Week';
            case 'inbox':
                return 'Inbox';
            case 'backlog':
                return 'Backlog';
        }
    };

    return (
        <div className="flex flex-col h-full">
            {/* Header */}
            <div className="px-4 pt-10 pb-3 border-b border-surface-800/40">
                <div className="flex items-center justify-between mb-2">
                    <h1 className="text-lg font-semibold text-surface-100 tracking-tight">
                        {viewLabel()}
                    </h1>
                    <div className="flex items-center gap-2">
                        <span className="text-xs text-surface-500">
                            {activeTasks.length} task{activeTasks.length !== 1 ? 's' : ''}
                        </span>
                    </div>
                </div>

                {/* Search */}
                {searchOpen && (
                    <div className="flex items-center gap-2 bg-surface-900/80 border border-surface-700/60 rounded-lg px-3 py-2 mb-2 animate-fade-in">
                        <Search className="w-4 h-4 text-surface-500" />
                        <input
                            ref={searchInputRef}
                            type="text"
                            value={searchQuery}
                            onChange={(e) => setSearchQuery(e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === 'Escape') setSearchOpen(false);
                            }}
                            placeholder="Search tasks..."
                            className="flex-1 bg-transparent text-sm text-surface-100 placeholder:text-surface-500 outline-none"
                            autoFocus
                        />
                        <span className="text-2xs text-surface-600">Esc to close</span>
                    </div>
                )}
            </div>

            {/* Task list */}
            <div className="flex-1 overflow-y-auto px-3 py-3">
                {loading ? (
                    <div className="flex items-center justify-center h-40">
                        <div className="w-6 h-6 border-2 border-accent-500/30 border-t-accent-500 rounded-full animate-spin" />
                    </div>
                ) : activeTasks.length === 0 && doneTasks.length === 0 ? (
                    <EmptyState view={currentView} />
                ) : (
                    <>
                        <div className="mb-3">
                            <QuickAdd />
                        </div>

                        <DndContext
                            sensors={sensors}
                            collisionDetection={closestCenter}
                            onDragStart={handleDragStart}
                            onDragEnd={handleDragEnd}
                        >
                            <SortableContext items={activeTasks.map((t) => t.id)} strategy={verticalListSortingStrategy}>
                                <div className="flex flex-col gap-1" role="list" aria-label="Task list">
                                    {activeTasks.map((task) => (
                                        <TaskItem key={task.id} task={task} />
                                    ))}
                                </div>
                            </SortableContext>

                            <DragOverlay dropAnimation={null}>
                                {activeTask && <TaskItem task={activeTask} isDragOverlay />}
                            </DragOverlay>
                        </DndContext>

                        {/* Done tasks */}
                        {doneTasks.length > 0 && (
                            <div className="mt-4">
                                <button
                                    onClick={() => setShowDone(!showDone)}
                                    className="flex items-center gap-2 text-xs text-surface-500 hover:text-surface-300 mb-2 transition-colors"
                                >
                                    <CheckCircle2 className="w-3.5 h-3.5" />
                                    <span>{doneTasks.length} completed</span>
                                    <span className="text-surface-600">{showDone ? '▾' : '▸'}</span>
                                </button>
                                {showDone && (
                                    <div className="flex flex-col gap-1 animate-fade-in">
                                        {doneTasks.map((task) => (
                                            <TaskItem key={task.id} task={task} />
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

function EmptyState({ view }: { view: string }) {
    const icons: Record<string, React.ReactNode> = {
        today: <ListChecks className="w-12 h-12 text-surface-700" />,
        tomorrow: <ListChecks className="w-12 h-12 text-surface-700" />,
        inbox: <InboxIcon className="w-12 h-12 text-surface-700" />,
        backlog: <ListChecks className="w-12 h-12 text-surface-700" />,
        week: <ListChecks className="w-12 h-12 text-surface-700" />,
    };

    const messages: Record<string, { title: string; tip: string }> = {
        today: {
            title: 'No tasks planned for today',
            tip: 'Press Ctrl+N to add a task, or drag tasks from your inbox to plan your day.',
        },
        tomorrow: {
            title: 'Nothing planned for tomorrow yet',
            tip: 'Plan ahead by adding tasks or moving items from your backlog.',
        },
        inbox: {
            title: 'Inbox is empty',
            tip: 'Great job! All tasks have been triaged. New tasks will appear here.',
        },
        backlog: {
            title: 'Backlog is empty',
            tip: 'Move tasks here that you want to do later but not today.',
        },
        week: {
            title: 'No tasks this week',
            tip: 'Start planning your week by adding tasks to specific days.',
        },
    };

    const msg = messages[view] || messages.today;

    return (
        <div className="flex flex-col items-center justify-center h-64 text-center">
            {icons[view]}
            <h3 className="mt-4 text-sm font-medium text-surface-400">{msg.title}</h3>
            <p className="mt-2 text-xs text-surface-500 max-w-[240px]">{msg.tip}</p>
            <div className="mt-4">
                <QuickAdd />
            </div>
        </div>
    );
}
