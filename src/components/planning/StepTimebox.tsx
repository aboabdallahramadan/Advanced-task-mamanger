import React from 'react';
import { useStore } from '../../store';
import { TaskItem } from '../TaskItem';
import { DayTimeline } from '../DayTimeline';
import { format } from 'date-fns';

export const StepTimebox: React.FC = () => {
    const { tasks, selectedDate } = useStore();

    // Tasks planned for today that are NOT scheduled
    const unscheduledTasks = tasks.filter(t => {
        if (t.status === 'done') return false;
        return t.plannedDate === selectedDate && !t.scheduledStart;
    });

    return (
        <div className="flex gap-8 h-[600px] w-full">
            {/* Left: Unscheduled Tasks */}
            <div className="w-[300px] flex flex-col pt-4">
                <div className="mb-6">
                    <h3 className="text-xl font-semibold text-surface-100 flex items-center gap-2">
                        Timebox
                    </h3>
                    <p className="text-surface-400 text-sm mt-1">
                        Drag your tasks onto the timeline to allocate time for them.
                    </p>
                </div>

                <div className="flex-1 overflow-y-auto space-y-2 pr-2 custom-scrollbar">
                    {unscheduledTasks.length === 0 ? (
                        <div className="text-center text-surface-500 py-8 bg-surface-900/40 rounded-xl border border-surface-800 border-dashed">
                            All tasks are scheduled!
                        </div>
                    ) : (
                        unscheduledTasks.map(task => (
                            <TaskItem key={task.id} task={task} />
                        ))
                    )}
                </div>
            </div>

            {/* Right: Timeline */}
            <div className="flex-1 border bg-surface-950/60 border-surface-800 rounded-xl overflow-hidden shadow-inner">
                <DayTimeline />
            </div>
        </div>
    );
};
