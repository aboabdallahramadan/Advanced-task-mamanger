export interface RecurrenceRuleData {
    frequency: 'daily' | 'weekly';
    interval: number;
    daysOfWeek: number[];
    endType: 'never' | 'count' | 'date';
    endCount: number | null;
    endDate: string | null;
}
export declare function generateOccurrenceDates(rule: RecurrenceRuleData, startDate: string, rangeStart: string, rangeEnd: string, existingDates: Set<string>, exceptionDates: Set<string>): string[];
