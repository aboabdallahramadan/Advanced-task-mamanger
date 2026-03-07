import React from 'react';
import { Repeat } from 'lucide-react';
import { clsx } from 'clsx';
import { RecurrenceFrequency, RecurrenceEndType } from '../types';

interface RecurrenceEditorProps {
    enabled: boolean;
    onToggle: (enabled: boolean) => void;
    frequency: RecurrenceFrequency;
    onFrequencyChange: (f: RecurrenceFrequency) => void;
    interval: number;
    onIntervalChange: (n: number) => void;
    daysOfWeek: number[];
    onDaysOfWeekChange: (days: number[]) => void;
    endType: RecurrenceEndType;
    onEndTypeChange: (t: RecurrenceEndType) => void;
    endCount: number;
    onEndCountChange: (n: number) => void;
    endDate: string;
    onEndDateChange: (d: string) => void;
}

const DAY_LABELS = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

export function RecurrenceEditor({
    enabled,
    onToggle,
    frequency,
    onFrequencyChange,
    interval,
    onIntervalChange,
    daysOfWeek,
    onDaysOfWeekChange,
    endType,
    onEndTypeChange,
    endCount,
    onEndCountChange,
    endDate,
    onEndDateChange,
}: RecurrenceEditorProps) {
    const toggleDay = (day: number) => {
        if (daysOfWeek.includes(day)) {
            if (daysOfWeek.length > 1) {
                onDaysOfWeekChange(daysOfWeek.filter((d) => d !== day));
            }
        } else {
            onDaysOfWeekChange([...daysOfWeek, day].sort());
        }
    };

    return (
        <div>
            <label className="block text-xs font-semibold uppercase tracking-wider text-surface-400 mb-2 flex items-center gap-1.5">
                <Repeat className="w-3.5 h-3.5" />
                Repeat
            </label>

            {/* Toggle */}
            <button
                type="button"
                onClick={() => onToggle(!enabled)}
                className={clsx(
                    'flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium transition-all border mb-3',
                    enabled
                        ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                        : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                )}
            >
                <Repeat className="w-3.5 h-3.5" />
                {enabled ? 'Recurring' : 'No repeat'}
            </button>

            {enabled && (
                <div className="space-y-3 pl-1">
                    {/* Frequency */}
                    <div className="flex items-center gap-2">
                        {(['daily', 'weekly'] as const).map((f) => (
                            <button
                                key={f}
                                type="button"
                                onClick={() => onFrequencyChange(f)}
                                className={clsx(
                                    'px-3 py-1.5 rounded-lg text-xs font-medium transition-all border capitalize',
                                    frequency === f
                                        ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                                        : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                                )}
                            >
                                {f}
                            </button>
                        ))}
                    </div>

                    {/* Interval */}
                    <div className="flex items-center gap-2">
                        <span className="text-xs text-surface-400">Every</span>
                        <input
                            type="number"
                            min={1}
                            max={52}
                            value={interval}
                            onChange={(e) => onIntervalChange(Math.max(1, parseInt(e.target.value) || 1))}
                            className="w-16 px-2 py-1.5 bg-surface-950 border border-surface-700/60 rounded-lg text-xs text-surface-200 focus:outline-none focus:border-accent-500/50 text-center"
                        />
                        <span className="text-xs text-surface-400">
                            {frequency === 'daily' ? (interval === 1 ? 'day' : 'days') : (interval === 1 ? 'week' : 'weeks')}
                        </span>
                    </div>

                    {/* Days of week (weekly only) */}
                    {frequency === 'weekly' && (
                        <div className="flex items-center gap-1">
                            {DAY_LABELS.map((label, i) => (
                                <button
                                    key={i}
                                    type="button"
                                    onClick={() => toggleDay(i)}
                                    className={clsx(
                                        'w-8 h-8 rounded-lg text-xs font-medium transition-all border',
                                        daysOfWeek.includes(i)
                                            ? 'bg-accent-600/20 border-accent-500/40 text-accent-400'
                                            : 'bg-surface-950 border-surface-700/60 text-surface-400 hover:text-surface-200 hover:border-surface-600',
                                    )}
                                >
                                    {label}
                                </button>
                            ))}
                        </div>
                    )}

                    {/* End condition */}
                    <div className="space-y-2">
                        <span className="text-xs text-surface-400 block">Ends</span>
                        <div className="flex flex-col gap-2">
                            <label className="flex items-center gap-2 cursor-pointer">
                                <input
                                    type="radio"
                                    name="recurrence-end"
                                    checked={endType === 'never'}
                                    onChange={() => onEndTypeChange('never')}
                                    className="accent-accent-500"
                                />
                                <span className="text-xs text-surface-300">Never</span>
                            </label>
                            <label className="flex items-center gap-2 cursor-pointer">
                                <input
                                    type="radio"
                                    name="recurrence-end"
                                    checked={endType === 'count'}
                                    onChange={() => onEndTypeChange('count')}
                                    className="accent-accent-500"
                                />
                                <span className="text-xs text-surface-300">After</span>
                                {endType === 'count' && (
                                    <>
                                        <input
                                            type="number"
                                            min={1}
                                            max={365}
                                            value={endCount}
                                            onChange={(e) => onEndCountChange(Math.max(1, parseInt(e.target.value) || 1))}
                                            className="w-16 px-2 py-1 bg-surface-950 border border-surface-700/60 rounded-lg text-xs text-surface-200 focus:outline-none focus:border-accent-500/50 text-center"
                                        />
                                        <span className="text-xs text-surface-400">occurrences</span>
                                    </>
                                )}
                            </label>
                            <label className="flex items-center gap-2 cursor-pointer">
                                <input
                                    type="radio"
                                    name="recurrence-end"
                                    checked={endType === 'date'}
                                    onChange={() => onEndTypeChange('date')}
                                    className="accent-accent-500"
                                />
                                <span className="text-xs text-surface-300">On date</span>
                                {endType === 'date' && (
                                    <input
                                        type="date"
                                        value={endDate}
                                        onChange={(e) => onEndDateChange(e.target.value)}
                                        className="px-2 py-1 bg-surface-950 border border-surface-700/60 rounded-lg text-xs text-surface-200 focus:outline-none focus:border-accent-500/50 [color-scheme:dark]"
                                    />
                                )}
                            </label>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
