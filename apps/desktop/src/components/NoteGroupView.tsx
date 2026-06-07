import React, { useEffect, useMemo, useState } from 'react';
import { useStore } from '../store';
import { ArrowLeft, Plus, Settings, Search, X } from 'lucide-react';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';
import { NoteCard } from './NoteCard';
import {
  DndContext,
  closestCenter,
  PointerSensor,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import { SortableContext, rectSortingStrategy } from '@dnd-kit/sortable';

function stripHtml(html: string): string {
  return html
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export function NoteGroupView() {
  const {
    noteGroups,
    selectedNoteGroupId,
    currentNotes,
    projects,
    selectNote,
    createNote,
    deleteNote,
    reorderNotes,
    openNoteGroupDialog,
    setCurrentView,
  } = useStore();

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 8 } }));

  const [search, setSearch] = useState('');

  const group = noteGroups.find((g) => g.id === selectedNoteGroupId);

  const filteredNotes = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return currentNotes;
    return currentNotes.filter(
      (n) => n.title.toLowerCase().includes(q) || stripHtml(n.content).toLowerCase().includes(q),
    );
  }, [currentNotes, search]);
  const linkedProject = group?.projectId ? projects.find((p) => p.id === group.projectId) : null;

  useEffect(() => {
    if (!group) {
      setCurrentView('board');
    }
  }, [group, setCurrentView]);

  if (!group) {
    return null;
  }

  const handleCreateNote = async () => {
    await createNote(group.id);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    // Reordering only works when not searching, since the list is filtered
    if (search.trim()) return;

    const oldIndex = currentNotes.findIndex((n) => n.id === active.id);
    const newIndex = currentNotes.findIndex((n) => n.id === over.id);
    if (oldIndex === -1 || newIndex === -1) return;

    const reordered = [...currentNotes];
    const [moved] = reordered.splice(oldIndex, 1);
    reordered.splice(newIndex, 0, moved);

    const updates = reordered.map((n, i) => ({ id: n.id, order: i }));
    reorderNotes(updates);
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-surface-950">
      {/* Header */}
      <div className="px-6 pt-10 pb-4 border-b border-surface-800/40">
        <div className="flex items-center gap-3 mb-2">
          <button
            onClick={() => setCurrentView('board')}
            className="p-1.5 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div className="flex items-center gap-3 flex-1">
            <span className="text-2xl">{group.emoji}</span>
            <div>
              <h1
                dir={getTextDirection(group.name)}
                style={getDirectionStyle(group.name)}
                className="text-xl font-bold text-surface-100"
              >
                {group.name}
              </h1>
              <div className="flex items-center gap-2 mt-0.5">
                <p className="text-xs text-surface-500">
                  {search.trim()
                    ? `${filteredNotes.length} of ${currentNotes.length}`
                    : currentNotes.length}{' '}
                  note
                  {(search.trim() ? filteredNotes.length : currentNotes.length) !== 1 ? 's' : ''}
                </p>
                {linkedProject && (
                  <span className="text-2xs text-surface-500 bg-surface-800/60 px-2 py-0.5 rounded-md">
                    {linkedProject.emoji} {linkedProject.name}
                  </span>
                )}
              </div>
            </div>
          </div>
          <button
            onClick={() => openNoteGroupDialog('edit', group.id)}
            className="p-2 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/60 transition-all"
            title="Edit group"
          >
            <Settings className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Quick Add + Search */}
      <div className="px-6 py-3 border-b border-surface-800/20 flex items-center gap-3">
        <button
          onClick={handleCreateNote}
          className="flex items-center gap-2 text-sm text-surface-400 hover:text-accent-400 transition-colors flex-shrink-0"
        >
          <Plus className="w-4 h-4" />
          <span>New note</span>
        </button>
        <div className="relative flex-1 max-w-md ml-auto">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-surface-500" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            dir={getTextDirection(search)}
            style={getDirectionStyle(search)}
            placeholder="Search notes..."
            className="w-full pl-8 pr-7 py-1.5 text-xs bg-surface-900 border border-surface-800/60 rounded-lg text-surface-100 placeholder-surface-600 outline-none focus:border-accent-500/50 transition-colors"
          />
          {search && (
            <button
              onClick={() => setSearch('')}
              className="absolute right-2 top-1/2 -translate-y-1/2 text-surface-500 hover:text-surface-300"
            >
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
      </div>

      {/* Note Cards */}
      <div className="flex-1 overflow-y-auto px-6 py-4 custom-scrollbar">
        {currentNotes.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-64 text-center">
            <span className="text-4xl mb-3">{group.emoji}</span>
            <h3 className="text-sm font-medium text-surface-400">No notes yet</h3>
            <p className="text-xs text-surface-500 mt-1">Create a note to get started</p>
          </div>
        ) : filteredNotes.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-64 text-center">
            <Search className="w-8 h-8 text-surface-600 mb-2" />
            <h3 className="text-sm font-medium text-surface-400">No notes match</h3>
            <p className="text-xs text-surface-500 mt-1">Try a different search term</p>
          </div>
        ) : (
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragEnd={handleDragEnd}
          >
            <SortableContext items={filteredNotes.map((n) => n.id)} strategy={rectSortingStrategy}>
              <div className="grid grid-cols-2 gap-3">
                {filteredNotes.map((note) => (
                  <NoteCard
                    key={note.id}
                    note={note}
                    sortable={!search.trim()}
                    onClick={() => selectNote(note.id)}
                    onDelete={() => deleteNote(note.id)}
                  />
                ))}
              </div>
            </SortableContext>
          </DndContext>
        )}
      </div>
    </div>
  );
}
