import React, { useEffect } from 'react';
import { useStore } from '../store';
import { ArrowLeft, Plus, Settings } from 'lucide-react';
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

    const sensors = useSensors(
        useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    );

    const group = noteGroups.find((g) => g.id === selectedNoteGroupId);
    const linkedProject = group?.projectId
        ? projects.find((p) => p.id === group.projectId)
        : null;

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
                                    {currentNotes.length} note
                                    {currentNotes.length !== 1 ? 's' : ''}
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

            {/* Quick Add */}
            <div className="px-6 py-3 border-b border-surface-800/20">
                <button
                    onClick={handleCreateNote}
                    className="flex items-center gap-2 text-sm text-surface-400 hover:text-accent-400 transition-colors"
                >
                    <Plus className="w-4 h-4" />
                    <span>New note</span>
                </button>
            </div>

            {/* Note Cards */}
            <div className="flex-1 overflow-y-auto px-6 py-4 custom-scrollbar">
                {currentNotes.length === 0 ? (
                    <div className="flex flex-col items-center justify-center h-64 text-center">
                        <span className="text-4xl mb-3">{group.emoji}</span>
                        <h3 className="text-sm font-medium text-surface-400">No notes yet</h3>
                        <p className="text-xs text-surface-500 mt-1">
                            Create a note to get started
                        </p>
                    </div>
                ) : (
                    <DndContext
                        sensors={sensors}
                        collisionDetection={closestCenter}
                        onDragEnd={handleDragEnd}
                    >
                        <SortableContext
                            items={currentNotes.map((n) => n.id)}
                            strategy={rectSortingStrategy}
                        >
                            <div className="grid grid-cols-2 gap-3">
                                {currentNotes.map((note) => (
                                    <NoteCard
                                        key={note.id}
                                        note={note}
                                        sortable
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
