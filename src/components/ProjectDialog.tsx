import React, { useState, useEffect, useRef } from 'react';
import { useStore } from '../store';
import { X, Save, Trash2 } from 'lucide-react';
import { clsx } from 'clsx';

const PROJECT_COLORS = [
    '#6366f1', // indigo
    '#8b5cf6', // violet
    '#ec4899', // pink
    '#ef4444', // red
    '#f97316', // orange
    '#eab308', // yellow
    '#22c55e', // green
    '#14b8a6', // teal
    '#06b6d4', // cyan
    '#3b82f6', // blue
    '#a855f7', // purple
    '#f43f5e', // rose
];

const PROJECT_EMOJIS = ['📁', '💼', '🚀', '🎯', '📚', '💡', '🔧', '🎨', '📊', '🏠', '💻', '📝', '⚡', '🌟', '🔥', '🎮'];

export function ProjectDialog() {
    const {
        projectDialog,
        closeProjectDialog,
        createProject,
        updateProject,
        deleteProject,
        projects,
    } = useStore();

    const nameRef = useRef<HTMLInputElement>(null);
    const [name, setName] = useState('');
    const [color, setColor] = useState('#6366f1');
    const [emoji, setEmoji] = useState('📁');

    useEffect(() => {
        if (!projectDialog.isOpen) return;

        if (projectDialog.mode === 'edit' && projectDialog.projectId) {
            const project = projects.find(p => p.id === projectDialog.projectId);
            if (project) {
                setName(project.name);
                setColor(project.color);
                setEmoji(project.emoji);
            }
        } else {
            setName('');
            setColor('#6366f1');
            setEmoji('📁');
        }

        setTimeout(() => nameRef.current?.focus(), 100);
    }, [projectDialog.isOpen, projectDialog.mode, projectDialog.projectId, projects]);

    if (!projectDialog.isOpen) return null;

    const isEdit = projectDialog.mode === 'edit';

    const handleSave = async () => {
        if (!name.trim()) return;

        if (isEdit && projectDialog.projectId) {
            await updateProject(projectDialog.projectId, { name: name.trim(), color, emoji });
        } else {
            await createProject({ name: name.trim(), color, emoji });
        }
        closeProjectDialog();
    };

    const handleDelete = async () => {
        if (!isEdit || !projectDialog.projectId) return;
        if (confirm('Delete this project? Tasks assigned to it will be unassigned.')) {
            await deleteProject(projectDialog.projectId);
            closeProjectDialog();
        }
    };

    return (
        <div
            className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4"
            onClick={(e) => { if (e.target === e.currentTarget) closeProjectDialog(); }}
            onKeyDown={(e) => {
                if (e.key === 'Escape') closeProjectDialog();
                if (e.key === 'Enter' && e.ctrlKey) handleSave();
            }}
        >
            <div className="w-full max-w-md bg-surface-900 border border-surface-700/60 rounded-2xl shadow-2xl flex flex-col animate-scale-in overflow-hidden">
                {/* Header */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-surface-800">
                    <h2 className="text-lg font-semibold text-surface-100">
                        {isEdit ? 'Edit Project' : 'New Project'}
                    </h2>
                    <button
                        onClick={closeProjectDialog}
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
                            Project Name
                        </label>
                        <input
                            ref={nameRef}
                            type="text"
                            value={name}
                            onChange={(e) => setName(e.target.value)}
                            placeholder="e.g. Work, Personal, Side Project..."
                            className="w-full px-4 py-2.5 bg-surface-950 border border-surface-700/60 rounded-xl text-surface-100 placeholder-surface-600 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 transition-all"
                        />
                    </div>

                    {/* Emoji */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2">
                            Icon
                        </label>
                        <div className="flex flex-wrap gap-2">
                            {PROJECT_EMOJIS.map(e => (
                                <button
                                    key={e}
                                    onClick={() => setEmoji(e)}
                                    className={clsx(
                                        "w-9 h-9 rounded-lg text-lg flex items-center justify-center transition-all border",
                                        emoji === e
                                            ? "bg-accent-600/20 border-accent-500/40 scale-110"
                                            : "bg-surface-950 border-surface-700/60 hover:border-surface-600"
                                    )}
                                >
                                    {e}
                                </button>
                            ))}
                        </div>
                    </div>

                    {/* Color */}
                    <div>
                        <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2">
                            Color
                        </label>
                        <div className="flex flex-wrap gap-2">
                            {PROJECT_COLORS.map(c => (
                                <button
                                    key={c}
                                    onClick={() => setColor(c)}
                                    className={clsx(
                                        "w-8 h-8 rounded-full transition-all border-2",
                                        color === c
                                            ? "border-white scale-110 shadow-lg"
                                            : "border-transparent hover:scale-105"
                                    )}
                                    style={{ backgroundColor: c }}
                                />
                            ))}
                        </div>
                    </div>

                    {/* Preview */}
                    <div className="flex items-center gap-3 px-4 py-3 bg-surface-950 rounded-xl border border-surface-800/60">
                        <span className="text-lg">{emoji}</span>
                        <span className="text-sm font-medium text-surface-100">{name || 'Project Name'}</span>
                        <div className="w-3 h-3 rounded-full ml-auto" style={{ backgroundColor: color }} />
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
                            onClick={closeProjectDialog}
                            className="px-4 py-2 text-sm text-surface-300 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                        >
                            Cancel
                        </button>
                        <button
                            onClick={handleSave}
                            disabled={!name.trim()}
                            className={clsx(
                                "flex items-center gap-2 px-5 py-2 text-sm font-medium rounded-lg transition-all shadow-lg",
                                name.trim()
                                    ? "bg-accent-600 hover:bg-accent-500 text-white shadow-accent-500/20"
                                    : "bg-surface-800 text-surface-500 cursor-not-allowed shadow-none"
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
