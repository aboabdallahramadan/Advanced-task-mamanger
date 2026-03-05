import type { Task } from './types';
import type React from 'react';

export const PRIORITY_COLORS: Record<number, string> = {
    1: '#ef4444', // Urgent
    2: '#f97316', // High
    3: '#eab308', // Medium
    4: '#3b82f6', // Low
};

export const PRIORITY_LABELS: Record<number, string> = {
    1: 'Urgent',
    2: 'High',
    3: 'Medium',
    4: 'Low',
};

export function getPriorityBorderStyle(priority: Task['priority']): React.CSSProperties {
    if (!priority) return {};
    return {
        borderLeftColor: PRIORITY_COLORS[priority],
        borderLeftWidth: '3px',
        borderLeftStyle: 'solid',
    };
}
