import React, { useMemo, useState, useRef, useEffect, useCallback } from 'react';
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
} from '@dnd-kit/sortable';
import { useStore } from '../store';
import { Task, TaskStatus } from '../types';
import { TaskItem } from './TaskItem';
import {
    ListChecks,
    Search,
    ChevronDown,
    X,
    ArrowUpDown,
    Filter,
    Layers,
} from 'lucide-react';
import { clsx } from 'clsx';
import { format } from 'date-fns';
import { PRIORITY_COLORS, PRIORITY_LABELS } from '../priorityUtils';

type SortField = 'createdAt' | 'priority' | 'plannedDate' | 'title' | 'status';
type SortDirection = 'asc' | 'desc';
type GroupBy = 'none' | 'status' | 'project' | 'priority';

const ALL_STATUSES: TaskStatus[] = ['inbox', 'backlog', 'planned', 'scheduled', 'done'];
const STATUS_LABELS: Record<string, string> = {
    inbox: 'Inbox',
    backlog: 'Backlog',
    planned: 'Planned',
    scheduled: 'Scheduled',
    done: 'Done',
    archived: 'Archived',
};
const STATUS_COLORS: Record<string, string> = {
    inbox: 'bg-surface-600',
    backlog: 'bg-surface-500',
    planned: 'bg-accent-600',
    scheduled: 'bg-blue-500',
    done: 'bg-success-500',
    archived: 'bg-surface-700',
};

const SORT_LABELS: Record<SortField, string> = {
    createdAt: 'Date Created',
    priority: 'Priority',
    plannedDate: 'Planned Date',
    title: 'Title',
    status: 'Status',
};

const GROUP_LABELS: Record<GroupBy, string> = {
    none: 'No Grouping',
    status: 'Status',
    project: 'Project',
    priority: 'Priority',
};

const STORAGE_KEY = 'allTasksFilters';

interface SavedFilters {
    selectedStatuses: TaskStatus[];
    showArchived: boolean;
    selectedPriorities: (number | null)[];
    selectedProjects: string[] | null;
    dateFrom: string;
    dateTo: string;
    sortField: SortField;
    sortDirection: SortDirection;
    groupBy: GroupBy;
}

function loadFilters(): Partial<SavedFilters> {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) return JSON.parse(raw);
    } catch { /* ignore */ }
    return {};
}

function saveFilters(filters: SavedFilters) {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(filters));
    } catch { /* ignore */ }
}

export function AllTasksView() {
    const { tasks, projects, reorderTasks } = useStore();

    // Load saved filters once
    const saved = useRef(loadFilters()).current;

    // Filter state
    const [search, setSearch] = useState('');
    const [selectedStatuses, setSelectedStatuses] = useState<Set<TaskStatus>>(
        () => new Set(saved.selectedStatuses ?? ALL_STATUSES)
    );
    const [showArchived, setShowArchived] = useState(saved.showArchived ?? false);
    const [selectedPriorities, setSelectedPriorities] = useState<Set<number | null>>(
        () => new Set(saved.selectedPriorities ?? [1, 2, 3, 4, null])
    );
    const [selectedProjects, setSelectedProjects] = useState<Set<string> | null>(
        () => saved.selectedProjects ? new Set(saved.selectedProjects) : null
    );
    const [dateFrom, setDateFrom] = useState(saved.dateFrom ?? '');
    const [dateTo, setDateTo] = useState(saved.dateTo ?? '');
    const [sortField, setSortField] = useState<SortField>(saved.sortField ?? 'createdAt');
    const [sortDirection, setSortDirection] = useState<SortDirection>(saved.sortDirection ?? 'desc');
    const [groupBy, setGroupBy] = useState<GroupBy>(saved.groupBy ?? 'none');

    // Persist filters whenever they change
    useEffect(() => {
        saveFilters({
            selectedStatuses: Array.from(selectedStatuses),
            showArchived,
            selectedPriorities: Array.from(selectedPriorities),
            selectedProjects: selectedProjects ? Array.from(selectedProjects) : null,
            dateFrom,
            dateTo,
            sortField,
            sortDirection,
            groupBy,
        });
    }, [selectedStatuses, showArchived, selectedPriorities, selectedProjects, dateFrom, dateTo, sortField, sortDirection, groupBy]);

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    );

    const isFiltered = search ||
        selectedStatuses.size !== ALL_STATUSES.length ||
        showArchived ||
        selectedPriorities.size !== 5 ||
        selectedProjects !== null ||
        dateFrom || dateTo;

    const clearFilters = () => {
        setSearch('');
        setSelectedStatuses(new Set(ALL_STATUSES));
        setShowArchived(false);
        setSelectedPriorities(new Set([1, 2, 3, 4, null]));
        setSelectedProjects(null);
        setDateFrom('');
        setDateTo('');
    };

    // Filter + sort
    const filteredTasks = useMemo(() => {
        let result = tasks.filter(t => {
            // Status
            if (t.status === 'archived') {
                if (!showArchived) return false;
            } else {
                if (!selectedStatuses.has(t.status)) return false;
            }
            // Priority
            if (!selectedPriorities.has(t.priority)) return false;
            // Project
            if (selectedProjects !== null) {
                const proj = t.project || '';
                if (!selectedProjects.has(proj)) return false;
            }
            // Date range
            if (dateFrom && (!t.plannedDate || t.plannedDate < dateFrom)) return false;
            if (dateTo && (!t.plannedDate || t.plannedDate > dateTo)) return false;
            // Search
            if (search) {
                const q = search.toLowerCase();
                if (
                    !t.title.toLowerCase().includes(q) &&
                    !t.notes.replace(/<[^>]*>/g, ' ').toLowerCase().includes(q) &&
                    !t.project.toLowerCase().includes(q)
                ) return false;
            }
            return true;
        });

        // Sort
        result = [...result].sort((a, b) => {
            let cmp = 0;
            switch (sortField) {
                case 'createdAt':
                    cmp = a.createdAt.localeCompare(b.createdAt);
                    break;
                case 'priority': {
                    const pa = a.priority ?? 99;
                    const pb = b.priority ?? 99;
                    cmp = pa - pb;
                    break;
                }
                case 'plannedDate':
                    cmp = (a.plannedDate || '').localeCompare(b.plannedDate || '');
                    break;
                case 'title':
                    cmp = a.title.localeCompare(b.title);
                    break;
                case 'status': {
                    const order: Record<string, number> = { inbox: 0, backlog: 1, planned: 2, scheduled: 3, done: 4, archived: 5 };
                    cmp = (order[a.status] ?? 6) - (order[b.status] ?? 6);
                    break;
                }
            }
            return sortDirection === 'asc' ? cmp : -cmp;
        });

        // Collapse recurring instances: show only the next upcoming per recurrence rule
        const today = format(new Date(), 'yyyy-MM-dd');
        const bestByRule = new Map<string, Task>();
        const nonRecurring: Task[] = [];
        for (const t of result) {
            if (!t.recurrenceRuleId) {
                nonRecurring.push(t);
                continue;
            }
            const existing = bestByRule.get(t.recurrenceRuleId);
            if (!existing) {
                bestByRule.set(t.recurrenceRuleId, t);
                continue;
            }
            const tIsUpcoming = t.plannedDate ? t.plannedDate >= today : false;
            const eIsUpcoming = existing.plannedDate ? existing.plannedDate >= today : false;
            if (tIsUpcoming && !eIsUpcoming) {
                bestByRule.set(t.recurrenceRuleId, t);
            } else if (tIsUpcoming && eIsUpcoming && t.plannedDate! < existing.plannedDate!) {
                bestByRule.set(t.recurrenceRuleId, t);
            } else if (!tIsUpcoming && !eIsUpcoming && t.plannedDate! > existing.plannedDate!) {
                bestByRule.set(t.recurrenceRuleId, t);
            }
        }
        result = [...nonRecurring, ...bestByRule.values()];

        return result;
    }, [tasks, search, selectedStatuses, showArchived, selectedPriorities, selectedProjects, dateFrom, dateTo, sortField, sortDirection]);

    // Group
    const groups = useMemo(() => {
        if (groupBy === 'none') {
            return [{ key: 'all', label: `All Tasks`, tasks: filteredTasks }];
        }

        const map = new Map<string, Task[]>();
        const order: string[] = [];

        for (const task of filteredTasks) {
            let key: string;
            switch (groupBy) {
                case 'status':
                    key = task.status;
                    break;
                case 'project':
                    key = task.project || 'No Project';
                    break;
                case 'priority':
                    key = task.priority ? PRIORITY_LABELS[task.priority] : 'No Priority';
                    break;
            }
            if (!map.has(key)) {
                map.set(key, []);
                order.push(key);
            }
            map.get(key)!.push(task);
        }

        return order.map(key => ({
            key,
            label: groupBy === 'status' ? (STATUS_LABELS[key] || key) : key,
            tasks: map.get(key)!,
        }));
    }, [filteredTasks, groupBy]);

    // Stats
    const totalMinutes = filteredTasks
        .filter(t => t.status !== 'done')
        .reduce((s, t) => s + (t.durationMinutes || 0), 0);

    const handleDragEnd = useCallback((event: DragEndEvent) => {
        // No-op: reorder doesn't make sense in a filtered/sorted view
    }, []);

    // Unique project names for filter
    const allProjectNames = useMemo(() => {
        const names = new Set<string>();
        for (const t of tasks) {
            if (t.project) names.add(t.project);
        }
        return Array.from(names).sort();
    }, [tasks]);

    return (
        <div className="flex-1 flex flex-col h-full bg-surface-950">
            {/* Header */}
            <div className="px-6 pt-10 pb-4 border-b border-surface-800/40">
                <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-3">
                        <ListChecks className="w-5 h-5 text-accent-400" />
                        <h1 className="text-lg font-bold text-surface-100">All Tasks</h1>
                        <span className="chip text-xs">
                            {filteredTasks.length} {filteredTasks.length === 1 ? 'task' : 'tasks'}
                        </span>
                    </div>
                    <div className="flex items-center gap-3 text-xs text-surface-500">
                        {totalMinutes > 0 && (
                            <span>
                                {Math.floor(totalMinutes / 60)}h {totalMinutes % 60}m planned
                            </span>
                        )}
                    </div>
                </div>

                {/* Search */}
                <div className="relative">
                    <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-surface-500" />
                    <input
                        type="text"
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        placeholder="Search tasks..."
                        className="w-full pl-9 pr-8 py-2 text-sm bg-surface-900 border border-surface-800/60 rounded-lg text-surface-100 placeholder-surface-600 outline-none focus:border-accent-500/50 transition-colors"
                    />
                    {search && (
                        <button
                            onClick={() => setSearch('')}
                            className="absolute right-3 top-1/2 -translate-y-1/2 text-surface-500 hover:text-surface-300"
                        >
                            <X className="w-3.5 h-3.5" />
                        </button>
                    )}
                </div>
            </div>

            {/* Filter Bar */}
            <div className="px-6 py-3 border-b border-surface-800/40 flex items-center gap-2 flex-wrap">
                <Filter className="w-3.5 h-3.5 text-surface-500 flex-shrink-0" />

                <FilterDropdown
                    label="Status"
                    count={selectedStatuses.size + (showArchived ? 1 : 0)}
                    total={ALL_STATUSES.length + 1}
                >
                    {ALL_STATUSES.map(s => (
                        <FilterCheckbox
                            key={s}
                            label={STATUS_LABELS[s]}
                            checked={selectedStatuses.has(s)}
                            color={STATUS_COLORS[s]}
                            onChange={(checked) => {
                                const next = new Set(selectedStatuses);
                                checked ? next.add(s) : next.delete(s);
                                setSelectedStatuses(next);
                            }}
                        />
                    ))}
                    <div className="my-1 border-t border-surface-800/40" />
                    <FilterCheckbox
                        label="Archived"
                        checked={showArchived}
                        color={STATUS_COLORS.archived}
                        onChange={setShowArchived}
                    />
                </FilterDropdown>

                <FilterDropdown
                    label="Priority"
                    count={selectedPriorities.size}
                    total={5}
                >
                    {([1, 2, 3, 4] as const).map(p => (
                        <FilterCheckbox
                            key={p}
                            label={PRIORITY_LABELS[p]}
                            checked={selectedPriorities.has(p)}
                            dotColor={PRIORITY_COLORS[p]}
                            onChange={(checked) => {
                                const next = new Set(selectedPriorities);
                                checked ? next.add(p) : next.delete(p);
                                setSelectedPriorities(next);
                            }}
                        />
                    ))}
                    <FilterCheckbox
                        label="No Priority"
                        checked={selectedPriorities.has(null)}
                        onChange={(checked) => {
                            const next = new Set(selectedPriorities);
                            checked ? next.add(null) : next.delete(null);
                            setSelectedPriorities(next);
                        }}
                    />
                </FilterDropdown>

                <FilterDropdown
                    label="Project"
                    count={selectedProjects?.size ?? allProjectNames.length + 1}
                    total={allProjectNames.length + 1}
                >
                    <FilterCheckbox
                        label="No Project"
                        checked={selectedProjects === null || selectedProjects.has('')}
                        onChange={(checked) => {
                            if (selectedProjects === null) {
                                // Switching from "all" to selective: include all except toggled
                                const next = new Set(allProjectNames);
                                if (!checked) next.delete(''); else next.add('');
                                // Actually start with all + no-project
                                const full = new Set(['', ...allProjectNames]);
                                if (!checked) full.delete('');
                                setSelectedProjects(full);
                            } else {
                                const next = new Set(selectedProjects);
                                checked ? next.add('') : next.delete('');
                                // If all are now selected, reset to null
                                if (next.size === allProjectNames.length + 1) {
                                    setSelectedProjects(null);
                                } else {
                                    setSelectedProjects(next);
                                }
                            }
                        }}
                    />
                    {allProjectNames.map(name => (
                        <FilterCheckbox
                            key={name}
                            label={name}
                            checked={selectedProjects === null || selectedProjects.has(name)}
                            onChange={(checked) => {
                                if (selectedProjects === null) {
                                    const full = new Set(['', ...allProjectNames]);
                                    if (!checked) full.delete(name);
                                    setSelectedProjects(full);
                                } else {
                                    const next = new Set(selectedProjects);
                                    checked ? next.add(name) : next.delete(name);
                                    if (next.size === allProjectNames.length + 1) {
                                        setSelectedProjects(null);
                                    } else {
                                        setSelectedProjects(next);
                                    }
                                }
                            }}
                        />
                    ))}
                </FilterDropdown>

                {/* Date range */}
                <div className="flex items-center gap-1.5">
                    <input
                        type="date"
                        value={dateFrom}
                        onChange={(e) => setDateFrom(e.target.value)}
                        className="px-2 py-1 text-xs bg-surface-900 border border-surface-800/60 rounded-lg text-surface-300 outline-none focus:border-accent-500/50 transition-colors"
                        title="From date"
                    />
                    <span className="text-xs text-surface-600">–</span>
                    <input
                        type="date"
                        value={dateTo}
                        onChange={(e) => setDateTo(e.target.value)}
                        className="px-2 py-1 text-xs bg-surface-900 border border-surface-800/60 rounded-lg text-surface-300 outline-none focus:border-accent-500/50 transition-colors"
                        title="To date"
                    />
                </div>

                <div className="w-px h-5 bg-surface-800/60 mx-1" />

                {/* Sort */}
                <FilterDropdown
                    label={`Sort: ${SORT_LABELS[sortField]}`}
                    icon={<ArrowUpDown className="w-3 h-3" />}
                    noCount
                >
                    {(Object.keys(SORT_LABELS) as SortField[]).map(field => (
                        <button
                            key={field}
                            onClick={() => {
                                if (sortField === field) {
                                    setSortDirection(d => d === 'asc' ? 'desc' : 'asc');
                                } else {
                                    setSortField(field);
                                    setSortDirection('desc');
                                }
                            }}
                            className={clsx(
                                'w-full text-left px-3 py-1.5 text-xs transition-all',
                                sortField === field
                                    ? 'text-accent-400 bg-accent-950/40'
                                    : 'text-surface-300 hover:bg-surface-800/60'
                            )}
                        >
                            {SORT_LABELS[field]}
                            {sortField === field && (
                                <span className="ml-1.5 text-surface-500">
                                    {sortDirection === 'asc' ? '↑' : '↓'}
                                </span>
                            )}
                        </button>
                    ))}
                </FilterDropdown>

                {/* Group by */}
                <FilterDropdown
                    label={`Group: ${GROUP_LABELS[groupBy]}`}
                    icon={<Layers className="w-3 h-3" />}
                    noCount
                >
                    {(Object.keys(GROUP_LABELS) as GroupBy[]).map(g => (
                        <button
                            key={g}
                            onClick={() => setGroupBy(g)}
                            className={clsx(
                                'w-full text-left px-3 py-1.5 text-xs transition-all',
                                groupBy === g
                                    ? 'text-accent-400 bg-accent-950/40'
                                    : 'text-surface-300 hover:bg-surface-800/60'
                            )}
                        >
                            {GROUP_LABELS[g]}
                        </button>
                    ))}
                </FilterDropdown>

                {isFiltered && (
                    <button
                        onClick={clearFilters}
                        className="flex items-center gap-1 px-2 py-1 text-xs text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 rounded-lg transition-all"
                    >
                        <X className="w-3 h-3" />
                        Clear
                    </button>
                )}
            </div>

            {/* Task List */}
            <div className="flex-1 overflow-y-auto custom-scrollbar">
                {filteredTasks.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-full text-surface-500">
                        <ListChecks className="w-10 h-10 mb-3 text-surface-700" />
                        <p className="text-sm font-medium mb-1">No tasks match your filters</p>
                        <p className="text-xs text-surface-600 mb-4">Try adjusting your filters or search query</p>
                        {isFiltered && (
                            <button
                                onClick={clearFilters}
                                className="btn-ghost text-xs"
                            >
                                Clear Filters
                            </button>
                        )}
                    </div>
                ) : (
                    <DndContext
                        sensors={sensors}
                        collisionDetection={closestCenter}
                        onDragEnd={handleDragEnd}
                    >
                        {groups.map(group => (
                            <div key={group.key}>
                                {groupBy !== 'none' && (
                                    <div className="sticky top-0 z-10 px-6 py-2 bg-surface-950/95 backdrop-blur-sm border-b border-surface-800/30">
                                        <div className="flex items-center gap-2">
                                            {groupBy === 'status' && (
                                                <span className={clsx('w-2 h-2 rounded-full', STATUS_COLORS[group.key] || 'bg-surface-600')} />
                                            )}
                                            {groupBy === 'priority' && group.key !== 'No Priority' && (
                                                <span
                                                    className="w-2 h-2 rounded-full"
                                                    style={{
                                                        backgroundColor: PRIORITY_COLORS[
                                                            Object.entries(PRIORITY_LABELS).find(([, v]) => v === group.key)?.[0] as unknown as number
                                                        ] || undefined
                                                    }}
                                                />
                                            )}
                                            <span className="text-xs font-semibold text-surface-300">
                                                {group.label}
                                            </span>
                                            <span className="chip text-2xs">{group.tasks.length}</span>
                                        </div>
                                    </div>
                                )}
                                <SortableContext
                                    items={group.tasks.map(t => t.id)}
                                    strategy={verticalListSortingStrategy}
                                >
                                    <div className="px-4 py-1" role="list">
                                        {group.tasks.map(task => (
                                            <TaskItem key={task.id} task={task} />
                                        ))}
                                    </div>
                                </SortableContext>
                            </div>
                        ))}
                    </DndContext>
                )}
            </div>
        </div>
    );
}

// ─── Filter Dropdown ─────────────────────────────────────────────────

interface FilterDropdownProps {
    label: string;
    count?: number;
    total?: number;
    icon?: React.ReactNode;
    noCount?: boolean;
    children: React.ReactNode;
}

function FilterDropdown({ label, count, total, icon, noCount, children }: FilterDropdownProps) {
    const [open, setOpen] = useState(false);
    const ref = useRef<HTMLDivElement>(null);

    useEffect(() => {
        if (!open) return;
        const handleClick = (e: MouseEvent) => {
            if (ref.current && !ref.current.contains(e.target as Node)) {
                setOpen(false);
            }
        };
        document.addEventListener('mousedown', handleClick);
        return () => document.removeEventListener('mousedown', handleClick);
    }, [open]);

    const isPartial = !noCount && count !== undefined && total !== undefined && count < total;

    return (
        <div className="relative" ref={ref}>
            <button
                onClick={() => setOpen(!open)}
                className={clsx(
                    'flex items-center gap-1.5 px-2.5 py-1.5 text-xs rounded-lg border transition-all',
                    isPartial
                        ? 'border-accent-500/40 bg-accent-950/30 text-accent-400'
                        : 'border-surface-800/60 text-surface-400 hover:text-surface-200 hover:border-surface-700'
                )}
            >
                {icon}
                {label}
                {!noCount && count !== undefined && total !== undefined && (
                    <span className="text-surface-600">{count}/{total}</span>
                )}
                <ChevronDown className={clsx('w-3 h-3 transition-transform', open && 'rotate-180')} />
            </button>

            {open && (
                <div className="absolute left-0 top-full mt-1 z-50 bg-surface-900 border border-surface-700/60 rounded-lg shadow-xl shadow-black/40 py-1 min-w-[180px] animate-scale-in">
                    {children}
                </div>
            )}
        </div>
    );
}

// ─── Filter Checkbox ─────────────────────────────────────────────────

interface FilterCheckboxProps {
    label: string;
    checked: boolean;
    onChange: (checked: boolean) => void;
    color?: string;
    dotColor?: string;
}

function FilterCheckbox({ label, checked, onChange, color, dotColor }: FilterCheckboxProps) {
    return (
        <label className="flex items-center gap-2 px-3 py-1.5 text-xs text-surface-300 hover:bg-surface-800/60 transition-all cursor-pointer">
            <input
                type="checkbox"
                checked={checked}
                onChange={(e) => onChange(e.target.checked)}
                className="sr-only"
            />
            <span className={clsx(
                'w-3.5 h-3.5 rounded border-2 flex items-center justify-center transition-all flex-shrink-0',
                checked
                    ? 'bg-accent-600 border-accent-600'
                    : 'border-surface-600'
            )}>
                {checked && (
                    <svg className="w-2.5 h-2.5 text-white" viewBox="0 0 12 12" fill="none">
                        <path d="M2 6l3 3 5-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                    </svg>
                )}
            </span>
            {color && <span className={clsx('w-2 h-2 rounded-full flex-shrink-0', color)} />}
            {dotColor && <span className="w-2 h-2 rounded-full flex-shrink-0" style={{ backgroundColor: dotColor }} />}
            {label}
        </label>
    );
}
