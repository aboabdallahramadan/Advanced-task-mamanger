import React from 'react';
import { useStore } from '../../store';
import { TaskItem } from '../TaskItem';
import { format } from 'date-fns';

export const StepTriageInbox: React.FC = () => {
    const { tasks, selectedDate } = useStore();

    // Inbox and backlog tasks
    const backlogTasks = tasks.filter(t => t.status === 'inbox' || t.status === 'backlog');

    // Tasks already planned for today
    const todayTasks = tasks.filter(t => {
        if (t.status === 'done') return false;
        return t.plannedDate === selectedDate;
    });

    return (
        <div className="flex gap-6 h-full">
            {/* Left Column: Inbox & Backlog */}
            <div className="flex-1 flex flex-col bg-surface-900/40 rounded-xl border border-surface-800 overflow-hidden">
                <div className="px-4 py-3 border-b border-surface-800 bg-surface-900/60 font-medium text-surface-200 flex justify-between">
                    <span>Backlog & Inbox</span>
                    <span className="text-surface-500">{backlogTasks.length}</span>
                </div>
                <div className="flex-1 overflow-y-auto p-4 space-y-2 custom-scrollbar">
                    {backlogTasks.length === 0 ? (
                        <div className="text-center text-surface-500 py-8">Inbox is empty</div>
                    ) : (
                        backlogTasks.map(task => (
                            <TaskItem key={task.id} task={task} />
                        ))
                    )}
                </div>
            </div>

            {/* Right Column: Today */}
            <div className="flex-1 flex flex-col bg-accent-950/20 rounded-xl border border-accent-900/30 overflow-hidden">
                <div className="px-4 py-3 border-b border-accent-900/30 bg-accent-950/40 font-medium text-accent-400 flex justify-between">
                    <span>Today's Plan</span>
                    <span className="text-accent-600/60">{todayTasks.length}</span>
                </div>
                <div className="flex-1 overflow-y-auto p-4 space-y-2 custom-scrollbar">
                    {todayTasks.length === 0 ? (
                        <div className="text-center text-accent-500/50 py-8">
                            Move tasks here to plan them for {format(new Date(selectedDate), 'EEEE')}
                        </div>
                    ) : (
                        todayTasks.map(task => (
                            <TaskItem key={task.id} task={task} />
                        ))
                    )}
                </div>
            </div>
        </div>
    );
};
