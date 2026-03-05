import { Database as SqlJsDatabase } from 'sql.js';
export interface Task {
    id: string;
    title: string;
    notes: string;
    project: string;
    labels: string[];
    source: string;
    status: 'inbox' | 'backlog' | 'planned' | 'scheduled' | 'done' | 'archived';
    dueDate: string | null;
    plannedDate: string | null;
    scheduledStart: string | null;
    scheduledEnd: string | null;
    durationMinutes: number;
    actualTimeMinutes?: number;
    priority: 1 | 2 | 3 | 4 | null;
    order: number;
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
    search(query: string): Task[];
}
