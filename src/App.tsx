import React, { useEffect, useCallback } from 'react';
import {
    DndContext,
    DragOverlay,
    useSensor,
    useSensors,
    PointerSensor,
    type DragEndEvent,
    type DragStartEvent,
    type DragOverEvent,
} from '@dnd-kit/core';
import { Sidebar } from './components/Sidebar';
import { TaskList } from './components/TaskList';
import { DayTimeline } from './components/DayTimeline';
import { PlanningFlowOverlay } from './components/planning/PlanningFlowOverlay';
import { FocusModeOverlay } from './components/focus/FocusModeOverlay';
import { TaskDetailDialog } from './components/TaskDetailDialog';
import { ProjectDialog } from './components/ProjectDialog';
import { SettingsDialog } from './components/SettingsDialog';
import { WeeklyBoardView } from './components/WeeklyBoardView';
import { ProjectView } from './components/ProjectView';
import { useStore } from './store';
import { Task } from './types';
import { addMinutes, format } from 'date-fns';
import { GripVertical, Clock } from 'lucide-react';

export default function App() {
    const { loadTasks, loadProjects, scheduleTask, startPlanningFlow, currentView } = useStore();
    const [draggedTask, setDraggedTask] = React.useState<Task | null>(null);
    const [overSlot, setOverSlot] = React.useState<{ hour: number; minute: number } | null>(null);

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    );

    useEffect(() => {
        loadTasks();
        loadProjects();
    }, [loadTasks, loadProjects]);

    // Global keyboard shortcuts
    useEffect(() => {
        const handleKeyDown = (e: KeyboardEvent) => {
            // These are handled in individual components
        };
        window.addEventListener('keydown', handleKeyDown);

        // Listen for IPC navigation events
        window.api.on('navigate', (route) => {
            if (route === 'plan-today') {
                startPlanningFlow();
            }
        });

        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [startPlanningFlow]);

    const handleDragStart = useCallback((event: DragStartEvent) => {
        const data = event.active.data.current;
        if (data?.type === 'task') {
            setDraggedTask(data.task);
        }
    }, []);

    const handleDragOver = useCallback((event: DragOverEvent) => {
        const over = event.over;
        if (over?.data.current?.type === 'timeline-slot') {
            setOverSlot({
                hour: over.data.current.hour,
                minute: over.data.current.minute,
            });
        } else {
            setOverSlot(null);
        }
    }, []);

    const handleDragEnd = useCallback(
        async (event: DragEndEvent) => {
            const { active, over } = event;
            setDraggedTask(null);
            setOverSlot(null);

            if (!over) return;

            // Check if dropped on a timeline slot
            const overData = over.data.current;
            if (overData?.type === 'timeline-slot') {
                const activeData = active.data.current;
                if (activeData?.type === 'task') {
                    const task: Task = activeData.task;
                    const { hour, minute, selectedDate } = overData;
                    const duration = task.durationMinutes || 30;

                    const start = `${selectedDate}T${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}:00`;
                    const endDate = addMinutes(new Date(start), duration);
                    const end = format(endDate, "yyyy-MM-dd'T'HH:mm:ss");

                    await scheduleTask(task.id, start, end);
                }
            }
        },
        [scheduleTask],
    );

    return (
        <DndContext
            sensors={sensors}
            onDragStart={handleDragStart}
            onDragOver={handleDragOver}
            onDragEnd={handleDragEnd}
        >
            <div className="h-screen flex bg-surface-950 select-none">
                {/* Title bar drag region */}
                <div className="fixed top-0 left-0 right-0 h-10 titlebar-drag-region z-50" />

                {/* Sidebar */}
                <Sidebar />

                {/* Main content area */}
                {currentView === 'board' ? (
                    <WeeklyBoardView />
                ) : currentView === 'project' ? (
                    <ProjectView />
                ) : (
                    <div className="flex-1 flex min-w-0">
                        {/* Task List Panel */}
                        <div className="w-[420px] min-w-[320px] max-w-[600px] border-r border-surface-800/40 flex flex-col bg-surface-950">
                            <TaskList />
                        </div>

                        {/* Day Timeline Panel */}
                        <div className="flex-1 min-w-[400px] flex flex-col bg-surface-950/50">
                            <DayTimeline />
                        </div>
                    </div>
                )}
            </div>

            {/* Modals & Overlays */}
            <PlanningFlowOverlay />
            <FocusModeOverlay />
            <TaskDetailDialog />
            <ProjectDialog />
            <SettingsDialog />

            {/* Drag overlay for cross-panel drag */}
            <DragOverlay dropAnimation={null}>
                {draggedTask && (
                    <div className="drag-overlay bg-surface-900 border border-accent-500/50 rounded-lg px-3 py-2.5 max-w-[300px] shadow-2xl">
                        <div className="flex items-center gap-2">
                            <GripVertical className="w-3.5 h-3.5 text-surface-500" />
                            <span className="text-sm font-medium text-surface-200 truncate">
                                {draggedTask.title}
                            </span>
                            <span className="chip chip-accent text-2xs ml-auto">
                                <Clock className="w-2.5 h-2.5" />
                                {draggedTask.durationMinutes}m
                            </span>
                        </div>
                        {overSlot && (
                            <div className="mt-1.5 text-xs text-accent-400 animate-fade-in">
                                → {overSlot.hour > 12 ? overSlot.hour - 12 : overSlot.hour}:
                                {String(overSlot.minute).padStart(2, '0')}{' '}
                                {overSlot.hour >= 12 ? 'PM' : 'AM'}
                            </div>
                        )}
                    </div>
                )}
            </DragOverlay>
        </DndContext>
    );
}
