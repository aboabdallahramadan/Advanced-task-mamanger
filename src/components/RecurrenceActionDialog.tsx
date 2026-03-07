import React from 'react';
import { Repeat, X } from 'lucide-react';

interface RecurrenceActionDialogProps {
    mode: 'edit' | 'delete';
    onAction: (scope: 'this' | 'future' | 'all') => void;
    onCancel: () => void;
}

export function RecurrenceActionDialog({ mode, onAction, onCancel }: RecurrenceActionDialogProps) {
    return (
        <div
            className="fixed inset-0 z-[60] bg-black/60 backdrop-blur-sm flex items-center justify-center p-4"
            onClick={(e) => {
                if (e.target === e.currentTarget) onCancel();
            }}
        >
            <div className="w-full max-w-sm bg-surface-900 border border-surface-700/60 rounded-2xl shadow-2xl overflow-hidden animate-scale-in">
                <div className="flex items-center justify-between px-5 py-4 border-b border-surface-800">
                    <h3 className="text-sm font-semibold text-surface-100 flex items-center gap-2">
                        <Repeat className="w-4 h-4 text-accent-400" />
                        {mode === 'edit' ? 'Edit Recurring Task' : 'Delete Recurring Task'}
                    </h3>
                    <button
                        onClick={onCancel}
                        className="p-1.5 text-surface-400 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                    >
                        <X className="w-4 h-4" />
                    </button>
                </div>
                <div className="p-4 space-y-2">
                    <p className="text-xs text-surface-400 mb-3">
                        {mode === 'edit'
                            ? 'How would you like to apply this change?'
                            : 'What would you like to delete?'}
                    </p>
                    <button
                        onClick={() => onAction('this')}
                        className="w-full text-left px-4 py-2.5 rounded-xl text-sm text-surface-200 hover:bg-surface-800 transition-colors border border-surface-700/40 hover:border-surface-600"
                    >
                        This occurrence only
                    </button>
                    <button
                        onClick={() => onAction('future')}
                        className="w-full text-left px-4 py-2.5 rounded-xl text-sm text-surface-200 hover:bg-surface-800 transition-colors border border-surface-700/40 hover:border-surface-600"
                    >
                        This and future occurrences
                    </button>
                    {mode === 'delete' && (
                        <button
                            onClick={() => onAction('all')}
                            className="w-full text-left px-4 py-2.5 rounded-xl text-sm text-red-400 hover:bg-red-500/10 transition-colors border border-surface-700/40 hover:border-red-500/30"
                        >
                            All occurrences
                        </button>
                    )}
                </div>
                <div className="px-4 pb-4">
                    <button
                        onClick={onCancel}
                        className="w-full px-4 py-2 text-xs text-surface-400 hover:text-surface-200 rounded-lg hover:bg-surface-800 transition-colors"
                    >
                        Cancel
                    </button>
                </div>
            </div>
        </div>
    );
}
