import {
    format,
    startOfWeek,
    endOfWeek,
    startOfMonth,
    endOfMonth,
    startOfYear,
    endOfYear,
    subDays,
    subWeeks,
    subMonths,
    subYears,
    eachDayOfInterval,
    parseISO,
} from 'date-fns';
import { ReportRangeMode } from '../types';

export interface DateRange {
    start: string; // YYYY-MM-DD inclusive
    end: string; // YYYY-MM-DD inclusive
}

const fmt = (d: Date) => format(d, 'yyyy-MM-dd');

export function getRange(mode: ReportRangeMode, ref: Date): DateRange {
    switch (mode) {
        case 'day':
            return { start: fmt(ref), end: fmt(ref) };
        case 'week':
            return {
                start: fmt(startOfWeek(ref, { weekStartsOn: 1 })),
                end: fmt(endOfWeek(ref, { weekStartsOn: 1 })),
            };
        case 'month':
            return { start: fmt(startOfMonth(ref)), end: fmt(endOfMonth(ref)) };
        case 'year':
            return { start: fmt(startOfYear(ref)), end: fmt(endOfYear(ref)) };
    }
}

export function getPreviousRange(mode: ReportRangeMode, ref: Date): DateRange {
    switch (mode) {
        case 'day':
            return getRange('day', subDays(ref, 1));
        case 'week':
            return getRange('week', subWeeks(ref, 1));
        case 'month':
            return getRange('month', subMonths(ref, 1));
        case 'year':
            return getRange('year', subYears(ref, 1));
    }
}

export function daysInRange(range: DateRange): string[] {
    return eachDayOfInterval({ start: parseISO(range.start), end: parseISO(range.end) }).map(fmt);
}
