import React from 'react';
import { useStore } from '../../store';
import { TaskItem } from '../TaskItem';

export const StepReviewYesterday: React.FC = () => {
    const { tasks, selectedDate } = useStore();

    // Find all tasks planned for before today that are NOT done
    const overdueTasks = tasks.filter(t => {
        if (t.status === 'done') return false;
        if (!t.plannedDate) return false;
        return t.plannedDate < selectedDate;
    });

    if (overdueTasks.length === 0) {
        return (
            <div className="flex flex-col items-center justify-center h-full text-surface-400">
                <div className="text-4xl mb-4">🎉</div>
                <h3 className="text-lg font-medium text-surface-200 mb-2">You're all caught up!</h3>
                <p className="text-sm">No tasks left over from yesterday.</p>
            </div>
        );
    }

    return (
        <div className="flex flex-col gap-6">
            <div className="text-center max-w-xl mx-auto mb-4">
                <h3 className="text-2xl font-semibold text-surface-100 mb-3">Review Yesterday</h3>
                <p className="text-surface-400 text-sm">
                    You have tasks left over from previous days.
                    Take a moment to mark them as done, move them to today's plan, or send them back to the backlog if they're no longer a priority.
                </p>
            </div>

            <div className="space-y-3 mx-auto w-full max-w-2xl bg-surface-900/40 p-6 rounded-xl border border-surface-800">
                {overdueTasks.map(task => (
                    <TaskItem key={task.id} task={task} />
                ))}
            </div>
        </div>
    );
};
