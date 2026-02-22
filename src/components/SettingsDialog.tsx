import React, { useState, useEffect } from 'react';
import { useStore } from '../store';
import { X, Save, Clock } from 'lucide-react';
import { clsx } from 'clsx';

const TIME_OPTIONS = Array.from({ length: 24 }, (_, i) => ({
    value: i,
    label: i === 0 ? '12:00 AM' : i < 12 ? `${i}:00 AM` : i === 12 ? '12:00 PM' : `${i - 12}:00 PM`,
}));

export function SettingsDialog() {
    const {
        settingsOpen,
        setSettingsOpen,
        workStartHour,
        workEndHour,
        timeIncrement,
        setWorkHours,
        setTimeIncrement,
    } = useStore();

    const [start, setStart] = useState(workStartHour);
    const [end, setEnd] = useState(workEndHour);
    const [increment, setIncrement] = useState(timeIncrement);

    useEffect(() => {
        if (settingsOpen) {
            setStart(workStartHour);
            setEnd(workEndHour);
            setIncrement(timeIncrement);
        }
    }, [settingsOpen, workStartHour, workEndHour, timeIncrement]);

    if (!settingsOpen) return null;

    const handleSave = () => {
        if (start < end) {
            setWorkHours(start, end);
            setTimeIncrement(increment);
        }
        setSettingsOpen(false);
    };

    return (
        <div
            className="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4"
            onClick={(e) => { if (e.target === e.currentTarget) setSettingsOpen(false); }}
            onKeyDown={(e) => {
                if (e.key === 'Escape') setSettingsOpen(false);
            }}
        >
            <div className="w-full max-w-md bg-surface-900 border border-surface-700/60 rounded-2xl shadow-2xl flex flex-col animate-scale-in overflow-hidden">
                {/* Header */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-surface-800">
                    <h2 className="text-lg font-semibold text-surface-100 flex items-center gap-2">
                        <Clock className="w-5 h-5 text-accent-400" />
                        Settings
                    </h2>
                    <button
                        onClick={() => setSettingsOpen(false)}
                        className="p-2 -mr-2 text-surface-400 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                    >
                        <X className="w-5 h-5" />
                    </button>
                </div>

                {/* Body */}
                <div className="p-6 space-y-6">
                    {/* Work Hours */}
                    <div>
                        <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
                            Work Hours
                        </h3>
                        <p className="text-xs text-surface-500 mb-3">
                            The timeline will show only the hours within your work schedule.
                        </p>
                        <div className="grid grid-cols-2 gap-4">
                            <div>
                                <label className="block text-xs text-surface-400 mb-1.5">Start</label>
                                <select
                                    value={start}
                                    onChange={(e) => setStart(Number(e.target.value))}
                                    className="w-full px-3 py-2 bg-surface-950 border border-surface-700/60 rounded-xl text-sm text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 [color-scheme:dark]"
                                >
                                    {TIME_OPTIONS.filter(t => t.value < end).map(t => (
                                        <option key={t.value} value={t.value}>{t.label}</option>
                                    ))}
                                </select>
                            </div>
                            <div>
                                <label className="block text-xs text-surface-400 mb-1.5">End</label>
                                <select
                                    value={end}
                                    onChange={(e) => setEnd(Number(e.target.value))}
                                    className="w-full px-3 py-2 bg-surface-950 border border-surface-700/60 rounded-xl text-sm text-surface-100 focus:outline-none focus:border-accent-500/50 focus:ring-1 focus:ring-accent-500/20 [color-scheme:dark]"
                                >
                                    {TIME_OPTIONS.filter(t => t.value > start).map(t => (
                                        <option key={t.value} value={t.value}>{t.label}</option>
                                    ))}
                                </select>
                            </div>
                        </div>

                        {/* Preview */}
                        <div className="mt-3 px-3 py-2 bg-surface-950 rounded-lg border border-surface-800/60 text-xs text-surface-400 flex items-center gap-2">
                            <Clock className="w-3.5 h-3.5" />
                            <span>Timeline: {TIME_OPTIONS.find(t => t.value === start)?.label} – {TIME_OPTIONS.find(t => t.value === end)?.label} ({end - start} hours)</span>
                        </div>
                    </div>

                    {/* Time Increment */}
                    <div>
                        <h3 className="text-xs font-semibold uppercase tracking-wider text-surface-400 mb-3">
                            Time Increment
                        </h3>
                        <p className="text-xs text-surface-500 mb-3">
                            Snap tasks to these intervals when dragging on the timeline.
                        </p>
                        <div className="flex items-center gap-2">
                            {[5, 10, 15, 30].map(inc => (
                                <button
                                    key={inc}
                                    onClick={() => setIncrement(inc)}
                                    className={clsx(
                                        "px-4 py-2 rounded-lg text-sm font-medium transition-all border",
                                        increment === inc
                                            ? "bg-accent-600/20 border-accent-500/40 text-accent-400"
                                            : "bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600"
                                    )}
                                >
                                    {inc}m
                                </button>
                            ))}
                        </div>
                    </div>
                </div>

                {/* Footer */}
                <div className="flex items-center justify-end px-6 py-4 border-t border-surface-800 gap-3">
                    <button
                        onClick={() => setSettingsOpen(false)}
                        className="px-4 py-2 text-sm text-surface-300 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                    >
                        Cancel
                    </button>
                    <button
                        onClick={handleSave}
                        className="flex items-center gap-2 px-5 py-2 text-sm font-medium rounded-lg transition-all shadow-lg bg-accent-600 hover:bg-accent-500 text-white shadow-accent-500/20"
                    >
                        <Save className="w-4 h-4" />
                        Save
                    </button>
                </div>
            </div>
        </div>
    );
}
