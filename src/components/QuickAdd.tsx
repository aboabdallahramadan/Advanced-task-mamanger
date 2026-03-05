import React, { useState, useRef, useEffect } from 'react';
import { Plus } from 'lucide-react';
import { useStore } from '../store';
import { getTextDirection, getDirectionStyle } from '../useTextDirection';

export function QuickAdd() {
    const [value, setValue] = useState('');
    const [isOpen, setIsOpen] = useState(false);
    const inputRef = useRef<HTMLInputElement>(null);
    const { createTask, currentView } = useStore();

    useEffect(() => {
        const handleKeyDown = (e: KeyboardEvent) => {
            if (e.ctrlKey && e.key === 'n') {
                e.preventDefault();
                setIsOpen(true);
                setTimeout(() => inputRef.current?.focus(), 50);
            }
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, []);

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!value.trim()) return;

        const status = currentView === 'inbox' ? 'inbox' : currentView === 'backlog' ? 'backlog' : 'planned';

        await createTask({
            title: value.trim(),
            status,
        });

        setValue('');
        if (!isOpen) return;
        inputRef.current?.focus();
    };

    if (!isOpen) {
        return (
            <button
                onClick={() => {
                    setIsOpen(true);
                    setTimeout(() => inputRef.current?.focus(), 50);
                }}
                className="w-full flex items-center gap-2 px-3 py-2.5 rounded-lg text-sm text-surface-500 
                   hover:text-surface-300 hover:bg-surface-800/40 border border-dashed 
                   border-surface-700/40 hover:border-surface-600/60 transition-all duration-150"
                aria-label="Add new task (Ctrl+N)"
            >
                <Plus className="w-4 h-4" />
                <span>Add task</span>
                <span className="ml-auto text-xs text-surface-600">Ctrl+N</span>
            </button>
        );
    }

    return (
        <form onSubmit={handleSubmit} className="animate-fade-in">
            <div className="flex items-center gap-2 bg-surface-900/80 border border-surface-700/60 rounded-lg px-3 py-2 focus-within:ring-2 focus-within:ring-accent-500/30 focus-within:border-accent-600/60 transition-all">
                <Plus className="w-4 h-4 text-accent-400" />
                <input
                    ref={inputRef}
                    type="text"
                    dir={getTextDirection(value)}
                    style={getDirectionStyle(value)}
                    value={value}
                    onChange={(e) => setValue(e.target.value)}
                    onBlur={() => {
                        if (!value.trim()) setIsOpen(false);
                    }}
                    onKeyDown={(e) => {
                        if (e.key === 'Escape') {
                            setValue('');
                            setIsOpen(false);
                        }
                    }}
                    placeholder="What needs to be done?"
                    className="flex-1 bg-transparent text-sm text-surface-100 placeholder:text-surface-500 outline-none"
                    autoFocus
                />
                <span className="text-2xs text-surface-600">Enter ↵</span>
            </div>
        </form>
    );
}
