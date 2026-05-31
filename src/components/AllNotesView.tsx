import React, { useEffect, useMemo, useState } from 'react';
import { useStore } from '../store';
import { Note } from '../types';
import {
    StickyNote,
    Search,
    X,
    Folder,
    FileText,
    Trash2,
    ChevronDown,
    Filter,
} from 'lucide-react';
import { clsx } from 'clsx';
import { format, parseISO } from 'date-fns';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';

function stripHtml(html: string): string {
    return html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
}

function extractFirstImage(html: string): string | null {
    const match = html.match(/<img[^>]+src="([^"]+)"/);
    return match ? match[1] : null;
}

type SortField = 'updatedAt' | 'createdAt' | 'title';
type SortDirection = 'asc' | 'desc';
type SourceFilter = 'all' | 'group' | 'project' | 'orphan';

const SORT_LABELS: Record<SortField, string> = {
    updatedAt: 'Last Updated',
    createdAt: 'Date Created',
    title: 'Title',
};

const STORAGE_KEY = 'allNotesFilters';

interface SavedFilters {
    sortField: SortField;
    sortDirection: SortDirection;
    sourceFilter: SourceFilter;
    selectedGroupIds: string[] | null;
    selectedProjectIds: string[] | null;
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

export function AllNotesView() {
    const {
        allNotes,
        noteGroups,
        projects,
        loadAllNotes,
        selectNote,
        deleteNote,
    } = useStore();

    const saved = React.useRef(loadFilters()).current;

    const [search, setSearch] = useState('');
    const [sortField, setSortField] = useState<SortField>(saved.sortField ?? 'updatedAt');
    const [sortDirection, setSortDirection] = useState<SortDirection>(saved.sortDirection ?? 'desc');
    const [sourceFilter, setSourceFilter] = useState<SourceFilter>(saved.sourceFilter ?? 'all');
    const [selectedGroupIds, setSelectedGroupIds] = useState<Set<string> | null>(
        () => saved.selectedGroupIds ? new Set(saved.selectedGroupIds) : null
    );
    const [selectedProjectIds, setSelectedProjectIds] = useState<Set<string> | null>(
        () => saved.selectedProjectIds ? new Set(saved.selectedProjectIds) : null
    );

    useEffect(() => {
        loadAllNotes();
    }, [loadAllNotes]);

    useEffect(() => {
        saveFilters({
            sortField,
            sortDirection,
            sourceFilter,
            selectedGroupIds: selectedGroupIds ? Array.from(selectedGroupIds) : null,
            selectedProjectIds: selectedProjectIds ? Array.from(selectedProjectIds) : null,
        });
    }, [sortField, sortDirection, sourceFilter, selectedGroupIds, selectedProjectIds]);

    const groupById = useMemo(() => {
        const m = new Map<string, typeof noteGroups[number]>();
        noteGroups.forEach((g) => m.set(g.id, g));
        return m;
    }, [noteGroups]);

    const projectById = useMemo(() => {
        const m = new Map<string, typeof projects[number]>();
        projects.forEach((p) => m.set(p.id, p));
        return m;
    }, [projects]);

    const filteredNotes = useMemo(() => {
        let result = allNotes.slice();

        // Source filter
        if (sourceFilter === 'group') {
            result = result.filter((n) => n.groupId);
        } else if (sourceFilter === 'project') {
            result = result.filter((n) => n.projectId && !n.groupId);
        } else if (sourceFilter === 'orphan') {
            result = result.filter((n) => !n.groupId && !n.projectId);
        }

        // Group filter
        if (selectedGroupIds !== null) {
            result = result.filter((n) => n.groupId && selectedGroupIds.has(n.groupId));
        }

        // Project filter
        if (selectedProjectIds !== null) {
            result = result.filter((n) => n.projectId && selectedProjectIds.has(n.projectId));
        }

        // Search
        if (search.trim()) {
            const q = search.trim().toLowerCase();
            result = result.filter((n) => {
                if (n.title.toLowerCase().includes(q)) return true;
                if (stripHtml(n.content).toLowerCase().includes(q)) return true;
                // Search by group/project name too
                if (n.groupId) {
                    const g = groupById.get(n.groupId);
                    if (g?.name.toLowerCase().includes(q)) return true;
                }
                if (n.projectId) {
                    const p = projectById.get(n.projectId);
                    if (p?.name.toLowerCase().includes(q)) return true;
                }
                return false;
            });
        }

        // Sort
        result.sort((a, b) => {
            let cmp = 0;
            switch (sortField) {
                case 'updatedAt':
                    cmp = a.updatedAt.localeCompare(b.updatedAt);
                    break;
                case 'createdAt':
                    cmp = a.createdAt.localeCompare(b.createdAt);
                    break;
                case 'title':
                    cmp = (a.title || '').localeCompare(b.title || '');
                    break;
            }
            return sortDirection === 'asc' ? cmp : -cmp;
        });

        return result;
    }, [allNotes, search, sourceFilter, selectedGroupIds, selectedProjectIds, sortField, sortDirection, groupById, projectById]);

    const isFiltered =
        !!search ||
        sourceFilter !== 'all' ||
        selectedGroupIds !== null ||
        selectedProjectIds !== null;

    const clearFilters = () => {
        setSearch('');
        setSourceFilter('all');
        setSelectedGroupIds(null);
        setSelectedProjectIds(null);
    };

    return (
        <div className="flex-1 flex flex-col h-full bg-surface-950">
            {/* Header */}
            <div className="px-6 pt-10 pb-4 border-b border-surface-800/40">
                <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-3">
                        <StickyNote className="w-5 h-5 text-accent-400" />
                        <h1 className="text-lg font-bold text-surface-100">All Notes</h1>
                        <span className="chip text-xs">
                            {filteredNotes.length} {filteredNotes.length === 1 ? 'note' : 'notes'}
                        </span>
                    </div>
                </div>

                {/* Search */}
                <div className="relative">
                    <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-surface-500" />
                    <input
                        type="text"
                        value={search}
                        onChange={(e) => setSearch(e.target.value)}
                        dir={getTextDirection(search)}
                        style={getDirectionStyle(search)}
                        placeholder="Search notes by title, content, group, or project..."
                        className="w-full pl-9 pr-8 py-2 text-sm bg-surface-900 border border-surface-800/60 rounded-lg text-surface-100 placeholder-surface-600 outline-none focus:border-accent-500/50 transition-colors"
                        autoFocus
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
                    label={
                        sourceFilter === 'all' ? 'Source: All' :
                        sourceFilter === 'group' ? 'Source: In Group' :
                        sourceFilter === 'project' ? 'Source: Project Note' :
                        'Source: Unfiled'
                    }
                    noCount
                >
                    {([
                        { value: 'all', label: 'All notes' },
                        { value: 'group', label: 'In a group' },
                        { value: 'project', label: 'Project notes' },
                        { value: 'orphan', label: 'Unfiled' },
                    ] as { value: SourceFilter; label: string }[]).map(({ value, label }) => (
                        <button
                            key={value}
                            onClick={() => setSourceFilter(value)}
                            className={clsx(
                                'w-full text-left px-3 py-1.5 text-xs transition-all',
                                sourceFilter === value
                                    ? 'text-accent-400 bg-accent-950/40'
                                    : 'text-surface-300 hover:bg-surface-800/60'
                            )}
                        >
                            {label}
                        </button>
                    ))}
                </FilterDropdown>

                {noteGroups.length > 0 && (
                    <FilterDropdown
                        label="Groups"
                        count={selectedGroupIds?.size ?? noteGroups.length}
                        total={noteGroups.length}
                    >
                        <button
                            onClick={() => setSelectedGroupIds(null)}
                            className={clsx(
                                'w-full text-left px-3 py-1.5 text-xs transition-all',
                                selectedGroupIds === null
                                    ? 'text-accent-400 bg-accent-950/40'
                                    : 'text-surface-300 hover:bg-surface-800/60'
                            )}
                        >
                            All groups
                        </button>
                        <div className="my-1 border-t border-surface-800/40" />
                        {noteGroups.map((g) => {
                            const checked = selectedGroupIds === null || selectedGroupIds.has(g.id);
                            return (
                                <FilterCheckbox
                                    key={g.id}
                                    label={`${g.emoji} ${g.name}`}
                                    checked={checked}
                                    onChange={(c) => {
                                        if (selectedGroupIds === null) {
                                            const full = new Set(noteGroups.map((x) => x.id));
                                            if (!c) full.delete(g.id);
                                            setSelectedGroupIds(full);
                                        } else {
                                            const next = new Set(selectedGroupIds);
                                            c ? next.add(g.id) : next.delete(g.id);
                                            if (next.size === noteGroups.length) {
                                                setSelectedGroupIds(null);
                                            } else {
                                                setSelectedGroupIds(next);
                                            }
                                        }
                                    }}
                                />
                            );
                        })}
                    </FilterDropdown>
                )}

                {projects.length > 0 && (
                    <FilterDropdown
                        label="Projects"
                        count={selectedProjectIds?.size ?? projects.length}
                        total={projects.length}
                    >
                        <button
                            onClick={() => setSelectedProjectIds(null)}
                            className={clsx(
                                'w-full text-left px-3 py-1.5 text-xs transition-all',
                                selectedProjectIds === null
                                    ? 'text-accent-400 bg-accent-950/40'
                                    : 'text-surface-300 hover:bg-surface-800/60'
                            )}
                        >
                            All projects
                        </button>
                        <div className="my-1 border-t border-surface-800/40" />
                        {projects.map((p) => {
                            const checked = selectedProjectIds === null || selectedProjectIds.has(p.id);
                            return (
                                <FilterCheckbox
                                    key={p.id}
                                    label={`${p.emoji} ${p.name}`}
                                    checked={checked}
                                    onChange={(c) => {
                                        if (selectedProjectIds === null) {
                                            const full = new Set(projects.map((x) => x.id));
                                            if (!c) full.delete(p.id);
                                            setSelectedProjectIds(full);
                                        } else {
                                            const next = new Set(selectedProjectIds);
                                            c ? next.add(p.id) : next.delete(p.id);
                                            if (next.size === projects.length) {
                                                setSelectedProjectIds(null);
                                            } else {
                                                setSelectedProjectIds(next);
                                            }
                                        }
                                    }}
                                />
                            );
                        })}
                    </FilterDropdown>
                )}

                <div className="w-px h-5 bg-surface-800/60 mx-1" />

                <FilterDropdown
                    label={`Sort: ${SORT_LABELS[sortField]} ${sortDirection === 'asc' ? '↑' : '↓'}`}
                    noCount
                >
                    {(Object.keys(SORT_LABELS) as SortField[]).map((field) => (
                        <button
                            key={field}
                            onClick={() => {
                                if (sortField === field) {
                                    setSortDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
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

            {/* Notes Grid */}
            <div className="flex-1 overflow-y-auto px-6 py-4 custom-scrollbar">
                {filteredNotes.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-full text-surface-500">
                        <StickyNote className="w-10 h-10 mb-3 text-surface-700" />
                        <p className="text-sm font-medium mb-1">
                            {allNotes.length === 0
                                ? 'No notes yet'
                                : 'No notes match your search'}
                        </p>
                        <p className="text-xs text-surface-600 mb-4">
                            {allNotes.length === 0
                                ? 'Create a note in a group or project to get started'
                                : 'Try adjusting your search or filters'}
                        </p>
                        {isFiltered && (
                            <button onClick={clearFilters} className="btn-ghost text-xs">
                                Clear Filters
                            </button>
                        )}
                    </div>
                ) : (
                    <div className="grid grid-cols-2 gap-3">
                        {filteredNotes.map((note) => (
                            <AllNotesCard
                                key={note.id}
                                note={note}
                                group={note.groupId ? groupById.get(note.groupId) : undefined}
                                project={note.projectId ? projectById.get(note.projectId) : undefined}
                                searchQuery={search}
                                onClick={() => selectNote(note.id, 'allNotes')}
                                onDelete={() => {
                                    if (confirm('Delete this note?')) {
                                        deleteNote(note.id);
                                    }
                                }}
                            />
                        ))}
                    </div>
                )}
            </div>
        </div>
    );
}

// ─── All Notes Card ──────────────────────────────────────────────────

interface AllNotesCardProps {
    note: Note;
    group?: { name: string; emoji: string };
    project?: { name: string; emoji: string; color: string };
    searchQuery: string;
    onClick: () => void;
    onDelete: () => void;
}

function AllNotesCard({ note, group, project, searchQuery, onClick, onDelete }: AllNotesCardProps) {
    const snippet = note.content ? stripHtml(note.content) : '';
    const thumbnail = note.content ? extractFirstImage(note.content) : null;

    // Highlight matched snippet around the search hit
    const displaySnippet = useMemo(() => {
        if (!searchQuery.trim() || !snippet) {
            return snippet.slice(0, 200);
        }
        const q = searchQuery.trim().toLowerCase();
        const lower = snippet.toLowerCase();
        const idx = lower.indexOf(q);
        if (idx === -1) return snippet.slice(0, 200);
        const start = Math.max(0, idx - 40);
        const end = Math.min(snippet.length, idx + q.length + 160);
        const prefix = start > 0 ? '… ' : '';
        const suffix = end < snippet.length ? ' …' : '';
        return prefix + snippet.slice(start, end) + suffix;
    }, [snippet, searchQuery]);

    const renderHighlighted = (text: string) => {
        const q = searchQuery.trim();
        if (!q) return text;
        const parts: React.ReactNode[] = [];
        const lower = text.toLowerCase();
        const qLower = q.toLowerCase();
        let cursor = 0;
        while (cursor < text.length) {
            const idx = lower.indexOf(qLower, cursor);
            if (idx === -1) {
                parts.push(text.slice(cursor));
                break;
            }
            if (idx > cursor) parts.push(text.slice(cursor, idx));
            parts.push(
                <mark
                    key={idx}
                    className="bg-accent-500/30 text-accent-200 rounded px-0.5"
                >
                    {text.slice(idx, idx + q.length)}
                </mark>
            );
            cursor = idx + q.length;
        }
        return parts;
    };

    return (
        <div
            onClick={onClick}
            className={clsx(
                'group relative flex flex-col rounded-xl cursor-pointer transition-all',
                'border border-surface-800/40 bg-surface-900/70',
                'shadow-sm shadow-black/20',
                'hover:shadow-md hover:shadow-black/30 hover:border-surface-700/60 hover:scale-[1.01]',
            )}
        >
            {thumbnail && (
                <div className="w-full overflow-hidden rounded-t-xl">
                    <img src={thumbnail} alt="" className="w-full h-32 object-cover" />
                </div>
            )}

            <div className="p-4 flex flex-col gap-1.5">
                {/* Source chip */}
                <div className="flex items-center gap-1.5 text-2xs text-surface-500">
                    {group ? (
                        <>
                            <Folder className="w-3 h-3" />
                            <span className="truncate">
                                {group.emoji} {group.name}
                            </span>
                        </>
                    ) : project ? (
                        <>
                            <span
                                className="w-2 h-2 rounded-full flex-shrink-0"
                                style={{ backgroundColor: project.color }}
                            />
                            <span className="truncate">
                                {project.emoji} {project.name}
                            </span>
                        </>
                    ) : (
                        <>
                            <FileText className="w-3 h-3" />
                            <span>Unfiled</span>
                        </>
                    )}
                </div>

                {note.title && note.title !== 'Untitled' && (
                    <h4
                        dir={getTextDirection(note.title)}
                        style={getDirectionStyle(note.title)}
                        className="text-sm font-semibold text-surface-100 line-clamp-2 leading-snug"
                    >
                        {renderHighlighted(note.title)}
                    </h4>
                )}

                {displaySnippet && (
                    <p className="text-xs text-surface-400 line-clamp-4 leading-relaxed">
                        {renderHighlighted(displaySnippet)}
                    </p>
                )}

                <span className="text-2xs text-surface-600 mt-1">
                    {format(parseISO(note.updatedAt), 'MMM d, yyyy')}
                </span>
            </div>

            <button
                onClick={(e) => {
                    e.stopPropagation();
                    onDelete();
                }}
                className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 p-1.5 text-surface-500 hover:text-danger-400 rounded-lg bg-surface-900/80 hover:bg-danger-900/20 transition-all backdrop-blur-sm"
                title="Delete note"
            >
                <Trash2 className="w-3.5 h-3.5" />
            </button>
        </div>
    );
}

// ─── Filter Dropdown ─────────────────────────────────────────────────

interface FilterDropdownProps {
    label: string;
    count?: number;
    total?: number;
    noCount?: boolean;
    children: React.ReactNode;
}

function FilterDropdown({ label, count, total, noCount, children }: FilterDropdownProps) {
    const [open, setOpen] = useState(false);
    const ref = React.useRef<HTMLDivElement>(null);

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
                {label}
                {!noCount && count !== undefined && total !== undefined && (
                    <span className="text-surface-600">{count}/{total}</span>
                )}
                <ChevronDown className={clsx('w-3 h-3 transition-transform', open && 'rotate-180')} />
            </button>

            {open && (
                <div className="absolute left-0 top-full mt-1 z-50 bg-surface-900 border border-surface-700/60 rounded-lg shadow-xl shadow-black/40 py-1 min-w-[200px] max-h-72 overflow-y-auto custom-scrollbar animate-scale-in">
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
}

function FilterCheckbox({ label, checked, onChange }: FilterCheckboxProps) {
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
            <span className="truncate">{label}</span>
        </label>
    );
}
