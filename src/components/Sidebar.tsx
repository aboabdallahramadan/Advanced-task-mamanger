import React from 'react';
import {
    Calendar,
    Inbox,
    Archive,
    LayoutList,
    ChevronLeft,
    ChevronRight,
    Clock,
    Folder,
    Sun,
    LayoutGrid,
    Settings,
    GripVertical,
} from 'lucide-react';
import {
    DndContext,
    closestCenter,
    PointerSensor,
    useSensor,
    useSensors,
    type DragEndEvent,
} from '@dnd-kit/core';
import {
    SortableContext,
    verticalListSortingStrategy,
    useSortable,
    arrayMove,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { useStore } from '../store';
import { ViewMode, Project } from '../types';
import { format } from 'date-fns';
import { clsx } from 'clsx';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';

const navItems: { id: ViewMode; label: string; icon: React.ReactNode }[] = [
    { id: 'board', label: 'Board', icon: <LayoutGrid className="w-4 h-4" /> },
    { id: 'today', label: 'Day', icon: <Calendar className="w-4 h-4" /> },
    { id: 'week', label: 'This Week', icon: <LayoutList className="w-4 h-4" /> },
    { id: 'inbox', label: 'Inbox', icon: <Inbox className="w-4 h-4" /> },
    { id: 'backlog', label: 'Backlog', icon: <Archive className="w-4 h-4" /> },
];

export function Sidebar() {
    const {
        currentView,
        setCurrentView,
        tasks,
        projects,
        selectedProjectId,
        sidebarCollapsed,
        setSidebarCollapsed,
        startPlanningFlow,
        openProjectDialog,
        selectProject,
        reorderProjects,
    } = useStore();

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    );

    const inboxCount = tasks.filter((t) => t.status === 'inbox').length;
    const backlogCount = tasks.filter((t) => t.status === 'backlog').length;
    const todayDate = format(new Date(), 'yyyy-MM-dd');
    const todayCount = tasks.filter(
        (t) => t.plannedDate === todayDate && t.status !== 'archived' && t.status !== 'done',
    ).length;

    const getCounts = (id: ViewMode) => {
        switch (id) {
            case 'today': return todayCount;
            case 'inbox': return inboxCount;
            case 'backlog': return backlogCount;
            default: return 0;
        }
    };

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
            <div className="px-2 py-2 mb-2">
                <button
                    onClick={startPlanningFlow}
                    className="w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-sm font-medium bg-accent-600/10 text-accent-400 hover:bg-accent-600/20 hover:text-accent-300 transition-all border border-accent-600/20 shadow-inner"
                >
                    <Sun className="w-4 h-4" />
                    <span className="flex-1 text-left">Plan Today</span>
                </button>
            </div>

            <nav className="px-2 py-0 flex flex-col gap-0.5" role="navigation" aria-label="Main navigation">
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
                <div className="flex items-center justify-between mb-2">
                    <h3 className="text-2xs font-semibold uppercase tracking-wider text-surface-500">
                        Projects
                    </h3>
                    <button
                        onClick={() => openProjectDialog('create')}
                        className="text-2xs text-surface-500 hover:text-accent-400 transition-colors"
                    >
                        + New
                    </button>
                </div>
                <DndContext
                    sensors={sensors}
                    collisionDetection={closestCenter}
                    onDragEnd={(event: DragEndEvent) => {
                        const { active, over } = event;
                        if (!over || active.id === over.id) return;
                        const oldIndex = projects.findIndex((p) => p.id === active.id);
                        const newIndex = projects.findIndex((p) => p.id === over.id);
                        if (oldIndex === -1 || newIndex === -1) return;
                        const reordered = arrayMove(projects, oldIndex, newIndex);
                        const items = reordered.map((p, i) => ({ id: p.id, order: i }));
                        reorderProjects(items);
                    }}
                >
                    <SortableContext items={projects.map((p) => p.id)} strategy={verticalListSortingStrategy}>
                        <div className="flex flex-col gap-0.5">
                            {projects.length === 0 && (
                                <p className="text-xs text-surface-600 italic px-1">No projects yet</p>
                            )}
                            {projects.map((project) => {
                                const count = tasks.filter(
                                    (t) => t.project === project.name && t.status !== 'archived' && t.status !== 'done',
                                ).length;
                                const isActive = currentView === 'project' && selectedProjectId === project.id;
                                return (
                                    <SortableProjectItem
                                        key={project.id}
                                        project={project}
                                        count={count}
                                        isActive={isActive}
                                        onSelect={() => selectProject(project.id)}
                                    />
                                );
                            })}
                        </div>
                    </SortableContext>
                </DndContext>
            </div>

            {/* Footer: free time indicator + settings */}
            <div className="mt-auto px-4 py-3 border-t border-surface-800/40 space-y-2">
                <FreeTimeIndicator />
                <button
                    onClick={() => useStore.getState().setSettingsOpen(true)}
                    className="flex items-center gap-2 w-full px-2 py-1.5 text-xs text-surface-500 hover:text-surface-200 hover:bg-surface-800/40 rounded-md transition-all"
                >
                    <Settings className="w-3.5 h-3.5" />
                    <span>Settings</span>
                </button>
            </div>
        </div>
    );
}

function SortableProjectItem({
    project,
    count,
    isActive,
    onSelect,
}: {
    project: Project;
    count: number;
    isActive: boolean;
    onSelect: () => void;
}) {
    const {
        attributes,
        listeners,
        setNodeRef,
        transform,
        transition,
        isDragging,
    } = useSortable({ id: project.id });

    const style = {
        transform: CSS.Transform.toString(transform),
        transition,
        opacity: isDragging ? 0.5 : 1,
    };

    return (
        <div
            ref={setNodeRef}
            style={style}
            onClick={onSelect}
            className={clsx(
                "flex items-center gap-2 px-2 py-1.5 rounded-md text-sm transition-all cursor-pointer group",
                isActive
                    ? "bg-accent-600/15 text-accent-300"
                    : "text-surface-400 hover:text-surface-200 hover:bg-surface-800/40"
            )}
        >
            <button
                {...attributes}
                {...listeners}
                className="cursor-grab active:cursor-grabbing text-surface-600 hover:text-surface-400 opacity-0 group-hover:opacity-100 transition-opacity -ml-0.5 p-0.5"
                onClick={(e) => e.stopPropagation()}
                aria-label="Drag to reorder"
            >
                <GripVertical className="w-3 h-3" />
            </button>
            <span className="text-sm">{project.emoji}</span>
            <span dir={getTextDirection(project.name)} style={getDirectionStyle(project.name)} className="flex-1 truncate">{project.name}</span>
            <div className="w-2.5 h-2.5 rounded-full flex-shrink-0" style={{ backgroundColor: project.color }} />
            <span className="text-xs text-surface-500">{count}</span>
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
