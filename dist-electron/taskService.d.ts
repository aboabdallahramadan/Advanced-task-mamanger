import { Database as SqlJsDatabase } from 'sql.js';
export interface Subtask {
    id: string;
    taskId: string;
    title: string;
    completed: boolean;
    order: number;
    createdAt: string;
}
export interface Task {
    id: string;
    title: string;
    notes: string;
    project: string;
    labels: string[];
    source: string;
    status: 'inbox' | 'backlog' | 'planned' | 'scheduled' | 'done' | 'archived';
    plannedDate: string | null;
    scheduledStart: string | null;
    scheduledEnd: string | null;
    durationMinutes: number;
    actualTimeMinutes?: number;
    priority: 1 | 2 | 3 | 4 | null;
    reminderMinutes: number | null;
    subtasks: Subtask[];
    order: number;
    recurrenceRuleId: string | null;
    isRecurrenceTemplate: boolean;
    recurrenceDetached: boolean;
    recurrenceOriginalDate: string | null;
    createdAt: string;
    updatedAt: string;
}
export interface RecurrenceRule {
    id: string;
    frequency: 'daily' | 'weekly';
    interval: number;
    daysOfWeek: number[];
    endType: 'never' | 'count' | 'date';
    endCount: number | null;
    endDate: string | null;
    generatedUntil: string | null;
    createdAt: string;
    updatedAt: string;
}
export declare class TaskService {
    private db;
    constructor(db: SqlJsDatabase);
    getAll(): Task[];
    getByDate(date: string): Task[];
    getByStatus(status: string): Task[];
    create(input: Partial<Task>): Task;
    getById(id: string): Task | null;
    update(id: string, updates: Partial<Task>): Task | null;
    delete(id: string): boolean;
    reorder(items: {
        id: string;
        order: number;
    }[]): void;
    getUpcomingWithReminders(): Task[];
    search(query: string): Task[];
    createSubtask(taskId: string, title: string): Subtask;
    updateSubtask(id: string, updates: {
        title?: string;
        completed?: boolean;
        order?: number;
    }): void;
    deleteSubtask(id: string): void;
    getRecurrenceRule(ruleId: string): RecurrenceRule | null;
    createRecurringTask(taskInput: Partial<Task>, ruleInput: {
        frequency: 'daily' | 'weekly';
        interval: number;
        daysOfWeek: number[];
        endType: 'never' | 'count' | 'date';
        endCount?: number;
        endDate?: string;
    }): Task;
    generateInstancesForRange(ruleId: string, startDate: string, endDate: string): Task[];
    ensureInstancesForDateRange(startDate: string, endDate: string): Task[];
    updateSeries(ruleId: string, updates: Partial<Task>): void;
    deleteSeries(ruleId: string): void;
    deleteSeriesFuture(ruleId: string, fromDate: string): void;
    detachInstance(taskId: string): void;
    updateRecurrenceRule(ruleId: string, ruleUpdates: {
        frequency?: 'daily' | 'weekly';
        interval?: number;
        daysOfWeek?: number[];
        endType?: 'never' | 'count' | 'date';
        endCount?: number;
        endDate?: string;
    }): void;
}
