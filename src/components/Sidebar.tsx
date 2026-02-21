import React from 'react';
import {
    Calendar,
    CalendarDays,
    Inbox,
    Archive,
    LayoutList,
    ChevronLeft,
    ChevronRight,
    Clock,
    Folder,
} from 'lucide-react';
import { useStore } from '../store';
import { ViewMode } from '../types';
import { format, addDays } from 'date-fns';
import { clsx } from 'clsx';

const navItems: { id: ViewMode; label: string; icon: React.ReactNode }[] = [
    { id: 'today', label: 'Today', icon: <Calendar className="w-4 h-4" /> },
    { id: 'tomorrow', label: 'Tomorrow', icon: <CalendarDays className="w-4 h-4" /> },
    { id: 'week', label: 'This Week', icon: <LayoutList className="w-4 h-4" /> },
    { id: 'inbox', label: 'Inbox', icon: <Inbox className="w-4 h-4" /> },
    { id: 'backlog', label: 'Backlog', icon: <Archive className="w-4 h-4" /> },
];

export function Sidebar() {
    const {
        currentView,
        setCurrentView,
        tasks,
        sidebarCollapsed,
        setSidebarCollapsed,
    } = useStore();

    const inboxCount = tasks.filter((t) => t.status === 'inbox').length;
    const backlogCount = tasks.filter((t) => t.status === 'backlog').length;
    const todayDate = format(new Date(), 'yyyy-MM-dd');
    const todayCount = tasks.filter(
        (t) => t.plannedDate === todayDate && t.status !== 'archived' && t.status !== 'done',
    ).length;
    const tomorrowDate = format(addDays(new Date(), 1), 'yyyy-MM-dd');
    const tomorrowCount = tasks.filter(
        (t) => t.plannedDate === tomorrowDate && t.status !== 'archived' && t.status !== 'done',
    ).length;

    const getCounts = (id: ViewMode) => {
        switch (id) {
            case 'today': return todayCount;
            case 'tomorrow': return tomorrowCount;
            case 'inbox': return inboxCount;
            case 'backlog': return backlogCount;
            default: return 0;
        }
    };

    const projects = [...new Set(tasks.map((t) => t.project).filter(Boolean))];

    if (sidebarCollapsed) {
        return (
            <div className="w-12 bg-surface-950 border-r border-surface-800/60 flex flex-col items-center py-4 gap-2">
                <button
                    onClick={() => setSidebarCollapsed(false)}
                    className="p-2 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
                    aria-label="Expand sidebar"
                >
                    <ChevronRight className="w-4 h-4" />
                </button>
                {navItems.map((item) => (
                    <button
                        key={item.id}
                        onClick={() => setCurrentView(item.id)}
                        className={clsx(
                            'p-2 rounded-lg transition-all relative',
                            currentView === item.id
                                ? 'text-accent-400 bg-accent-950/60'
                                : 'text-surface-400 hover:text-surface-200 hover:bg-surface-800/60',
                        )}
                        aria-label={item.label}
                        title={item.label}
                    >
                        {item.icon}
                        {getCounts(item.id) > 0 && (
                            <span className="absolute -top-0.5 -right-0.5 w-3.5 h-3.5 bg-accent-600 rounded-full text-[8px] font-bold flex items-center justify-center text-white">
                                {getCounts(item.id)}
                            </span>
                        )}
                    </button>
                ))}
            </div>
        );
    }

    return (
        <div className="w-56 bg-surface-950 border-r border-surface-800/60 flex flex-col animate-slide-in">
            {/* Header */}
            <div className="px-4 pt-12 pb-3 flex items-center justify-between">
                <div className="flex items-center gap-2">
                    <Clock className="w-5 h-5 text-accent-400" />
                    <span className="font-semibold text-sm text-surface-200 tracking-tight">Daily Planner</span>
                </div>
                <button
                    onClick={() => setSidebarCollapsed(true)}
                    className="p-1 rounded text-surface-500 hover:text-surface-300 hover:bg-surface-800/60 transition-all"
                    aria-label="Collapse sidebar"
                >
                    <ChevronLeft className="w-4 h-4" />
                </button>
            </div>

            {/* Nav */}
            <nav className="px-2 py-2 flex flex-col gap-0.5" role="navigation" aria-label="Main navigation">
                {navItems.map((item) => {
                    const count = getCounts(item.id);
                    return (
                        <button
                            key={item.id}
                            onClick={() => setCurrentView(item.id)}
                            className={clsx(
                                'sidebar-item',
                                currentView === item.id && 'sidebar-item-active',
                            )}
                            aria-current={currentView === item.id ? 'page' : undefined}
                        >
                            {item.icon}
                            <span className="flex-1 text-left">{item.label}</span>
                            {count > 0 && (
                                <span className={clsx(
                                    'text-xs font-medium px-1.5 py-0.5 rounded-md min-w-[1.25rem] text-center',
                                    currentView === item.id
                                        ? 'bg-accent-700/40 text-accent-300'
                                        : 'bg-surface-800/80 text-surface-400',
                                )}>
                                    {count}
                                </span>
                            )}
                        </button>
                    );
                })}
            </nav>

            {/* Separator */}
            <div className="mx-4 my-2 border-t border-surface-800/40" />

            {/* Projects */}
            <div className="px-4 py-2">
                <h3 className="text-2xs font-semibold uppercase tracking-wider text-surface-500 mb-2">
                    Projects
                </h3>
                <div className="flex flex-col gap-0.5">
                    {projects.length === 0 && (
                        <p className="text-xs text-surface-600 italic px-1">No projects yet</p>
                    )}
                    {projects.map((project) => {
                        const count = tasks.filter(
                            (t) => t.project === project && t.status !== 'archived',
                        ).length;
                        return (
                            <div
                                key={project}
                                className="flex items-center gap-2 px-2 py-1.5 rounded-md text-sm text-surface-400 hover:text-surface-200 hover:bg-surface-800/40 transition-all cursor-pointer"
                            >
                                <Folder className="w-3.5 h-3.5" />
                                <span className="flex-1 truncate">{project}</span>
                                <span className="text-xs text-surface-500">{count}</span>
                            </div>
                        );
                    })}
                </div>
            </div>

            {/* Free time indicator */}
            <div className="mt-auto px-4 py-3 border-t border-surface-800/40">
                <FreeTimeIndicator />
            </div>
        </div>
    );
}

function FreeTimeIndicator() {
    const freeMinutes = useStore((s) => s.freeMinutesRemaining());
    const hours = Math.floor(freeMinutes / 60);
    const mins = freeMinutes % 60;

    return (
        <div className="flex items-center gap-2 text-xs">
            <div className={clsx(
                'w-2 h-2 rounded-full',
                freeMinutes > 120 ? 'bg-success-500' :
                    freeMinutes > 60 ? 'bg-warning-500' :
                        'bg-danger-500',
            )} />
            <span className="text-surface-400">
                {freeMinutes <= 0 ? 'Fully scheduled' : `${hours}h ${mins}m free`}
            </span>
        </div>
    );
}
