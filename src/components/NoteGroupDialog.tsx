import React, { useState, useEffect, useRef } from 'react';
import { useStore } from '../store';
import { X, Save, Trash2 } from 'lucide-react';
import { clsx } from 'clsx';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';

const NOTE_EMOJIS = [
    '📝', '📓', '📒', '📋', '🗒️', '📄', '📑', '📌',
    '💡', '🔖', '📎', '🧠', '📚', '🎯', '💼', '🔬',
];

export function NoteGroupDialog() {
    const {
        noteGroupDialog,
        closeNoteGroupDialog,
        createNoteGroup,
        updateNoteGroup,
        deleteNoteGroup,
        noteGroups,
        projects,
    } = useStore();

    const nameRef = useRef<HTMLInputElement>(null);
    const [name, setName] = useState('');
    const [emoji, setEmoji] = useState('📝');
    const [projectId, setProjectId] = useState<string>('');

    useEffect(() => {
        if (!noteGroupDialog.isOpen) return;

        if (noteGroupDialog.mode === 'edit' && noteGroupDialog.groupId) {
            const group = noteGroups.find((g) => g.id === noteGroupDialog.groupId);
            if (group) {
                setName(group.name);
                setEmoji(group.emoji);
                setProjectId(group.projectId || '');
            }
        } else {
            setName('');
            setEmoji('📝');
            setProjectId(noteGroupDialog.defaultProjectId || '');
        }

        setTimeout(() => nameRef.current?.focus(), 100);
    }, [
        noteGroupDialog.isOpen,
        noteGroupDialog.mode,
        noteGroupDialog.groupId,
        noteGroupDialog.defaultProjectId,
        noteGroups,
    ]);

    if (!noteGroupDialog.isOpen) return null;

    const isEdit = noteGroupDialog.mode === 'edit';

    const handleSave = async () => {
        if (!name.trim()) return;

        if (isEdit && noteGroupDialog.groupId) {
            await updateNoteGroup(noteGroupDialog.groupId, {
                name: name.trim(),
                emoji,
                projectId: projectId || null,
            });
        } else {
            await createNoteGroup({
                name: name.trim(),
                emoji,
                ...(projectId ? { projectId } : {}),
            });
        }
        closeNoteGroupDialog();
    };

    const handleDelete = async () => {
        if (!isEdit || !noteGroupDialog.groupId) return;
        if (confirm('Delete this note group? All notes inside will be deleted.')) {
            await deleteNoteGroup(noteGroupDialog.groupId);
            closeNoteGroupDialog();
        }
    };

    return (
        <div
            className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4"
            onClick={(e) => {
                if (e.target === e.currentTarget) closeNoteGroupDialog();
            }}
            onKeyDown={(e) => {
                if (e.key === 'Escape') closeNoteGroupDialog();
                if (e.key === 'Enter' && e.ctrlKey) handleSave();
            }}
        >
            <div className="w-full max-w-md bg-surface-900 border border-surface-700/60 rounded-2xl shadow-2xl flex flex-col animate-scale-in overflow-hidden">
                {/* Header */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-surface-800">
                    <h2 className="text-lg font-semibold text-surface-100">
                        {isEdit ? 'Edit Note Group' : 'New Note Group'}
                    </h2>
                    <button
                        onClick={closeNoteGroupDialog}
                        className="p-2 -mr-2 text-surface-400 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                    >
                        <X className="w-5 h-5" />
                    </button>
                </div>

                {/* Body */}
                <div className="p-6 space-y-5">
                    {/* Name */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2">
                            Group Name
                        </label>
                        <input
                            ref={nameRef}
                            type="text"
                            dir={getTextDirection(name)}
                            style={getDirectionStyle(name)}
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                            placeholder="e.g. Meeting Notes, Ideas, Research..."
                            className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 placeholder-surface-600 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all"
                        />
                    </div>

                    {/* Emoji */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2">
                            Icon
                        </label>
                        <div className="flex flex-wrap gap-2">
                            {NOTE_EMOJIS.map((e) => (
                                <button
                                    key={e}
                                    onClick={() => setEmoji(e)}
                                    className={clsx(
                                        'w-9 h-9 rounded-lg text-lg flex items-center justify-center transition-all border',
                                        emoji === e
                                            ? 'bg-accent-600/20 border-accent-500/40 scale-110'
                                            : 'bg-surface-950 border-surface-700/60 hover:border-surface-600',
                                    )}
                                >
                                    {e}
                                </button>
                            ))}
                        </div>
                    </div>

                    {/* Project Link */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2">
                            Link to Project (optional)
                        </label>
                        <select
                            value={projectId}
                            onChange={(e) => setProjectId(e.target.value)}
                            className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all"
                        >
                            <option value="">No project</option>
                            {projects.map((p) => (
                                <option key={p.id} value={p.id}>
                                    {p.emoji} {p.name}
                                </option>
                            ))}
                        </select>
                    </div>

                    {/* Preview */}
                    <div className="flex items-center gap-3 px-4 py-3 bg-surface-950 rounded-xl border border-surface-800/60">
                        <span className="text-lg">{emoji}</span>
                        <span
                            dir={getTextDirection(name || 'Note Group')}
                            style={getDirectionStyle(name || 'Note Group')}
                            className="text-sm font-medium text-surface-100"
                        >
                            {name || 'Note Group'}
                        </span>
                        {projectId && (
                            <span className="ml-auto text-2xs text-surface-500">
                                {projects.find((p) => p.id === projectId)?.name}
                            </span>
                        )}
                    </div>
                </div>

                {/* Footer */}
                <div className="flex items-center justify-between px-6 py-4 border-t border-surface-800">
                    {isEdit ? (
                        <button
                            onClick={handleDelete}
                            className="flex items-center gap-1.5 px-3 py-2 text-xs text-danger-400 hover:bg-danger-900/20 rounded-lg transition-colors"
                        >
                            <Trash2 className="w-3.5 h-3.5" />
                            Delete
                        </button>
                    ) : (
                        <span />
                    )}
                    <div className="flex items-center gap-3">
                        <button
                            onClick={closeNoteGroupDialog}
                            className="px-4 py-2 text-sm text-surface-300 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={!name.trim()}
                            className={clsx(
                                'flex items-center gap-2 px-5 py-2 text-sm font-medium rounded-lg transition-all shadow-lg',
                                name.trim()
                                    ? 'bg-accent-600 hover:bg-accent-500 text-white shadow-accent-500/20'
                                    : 'bg-surface-800 text-surface-500 cursor-not-allowed shadow-none',
                            )}
                        >
                            <Save className="w-4 h-4" />
                            {isEdit ? 'Update' : 'Create'}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
