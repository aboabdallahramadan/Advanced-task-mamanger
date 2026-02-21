import React, { useState, useRef } from 'react';
import { Clock } from 'lucide-react';
import { clsx } from 'clsx';

const presets = [15, 30, 45, 60, 90, 120];

interface DurationChipProps {
    minutes: number;
    onChange: (minutes: number) => void;
    compact?: boolean;
}

export function DurationChip({ minutes, onChange, compact }: DurationChipProps) {
    const [isOpen, setIsOpen] = useState(false);
    const [customValue, setCustomValue] = useState('');
    const containerRef = useRef<HTMLDivElement>(null);

    const formatDuration = (m: number) => {
        if (m < 60) return `${m}m`;
        const h = Math.floor(m / 60);
        const mins = m % 60;
        return mins > 0 ? `${h}h ${mins}m` : `${h}h`;
    };

    const handleCustomSubmit = () => {
        const val = parseInt(customValue, 10);
        if (val > 0 && val <= 480) {
            onChange(val);
            setIsOpen(false);
            setCustomValue('');
        }
    };

    return (
        <div className="relative" ref={containerRef}>
            <button
                onClick={(e) => {
                    e.stopPropagation();
                    setIsOpen(!isOpen);
                }}
                className={clsx(
                    'chip chip-accent cursor-pointer hover:bg-accent-800/60 transition-all',
                    compact && 'text-2xs py-0 px-1.5',
                )}
                aria-label={`Duration: ${formatDuration(minutes)}`}
            >
                <Clock className={clsx('w-3 h-3', compact && 'w-2.5 h-2.5')} />
                {formatDuration(minutes)}
            </button>

            {isOpen && (
                <>
                    <div className="fixed inset-0 z-40" onClick={() => setIsOpen(false)} />
                    <div className="absolute top-full left-0 mt-1 z-50 bg-surface-900 border border-surface-700/60 rounded-lg shadow-xl shadow-black/40 p-2 min-w-[140px] animate-scale-in">
                        <div className="grid grid-cols-3 gap-1 mb-2">
                            {presets.map((p) => (
                                <button
                                    key={p}
                                    onClick={() => {
                                        onChange(p);
                                        setIsOpen(false);
                                    }}
                                    className={clsx(
                                        'text-xs py-1.5 px-2 rounded-md transition-all text-center',
                                        minutes === p
                                            ? 'bg-accent-600 text-white'
                                            : 'bg-surface-800/60 text-surface-300 hover:bg-surface-700/60',
                                    )}
                                >
                                    {formatDuration(p)}
                                </button>
                            ))}
                        </div>
                        <div className="flex gap-1">
                            <input
                                type="number"
                                value={customValue}
                                onChange={(e) => setCustomValue(e.target.value)}
                                onKeyDown={(e) => {
                                    if (e.key === 'Enter') handleCustomSubmit();
                                }}
                                placeholder="min"
                                className="flex-1 input-base text-xs py-1 px-2"
                                min={1}
                                max={480}
                            />
                            <button
                                onClick={handleCustomSubmit}
                                className="text-xs btn-primary py-1 px-2"
                            >
                                Set
                            </button>
                        </div>
                    </div>
                </>
            )}
        </div>
    );
}
