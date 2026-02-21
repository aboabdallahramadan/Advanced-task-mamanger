import { Database as SqlJsDatabase } from 'sql.js';
import { v4 as uuid } from 'uuid';
import { saveDatabase } from './database';

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
    order: number;
    createdAt: string;
    updatedAt: string;
}

function rowToTask(columns: string[], values: any[]): Task {
    const row: any = {};
    columns.forEach((col, i) => (row[col] = values[i]));

    return {
        id: row.id,
        title: row.title,
        notes: row.notes || '',
        project: row.project || '',
        labels: JSON.parse(row.labels || '[]'),
        source: row.source || 'local',
        status: row.status as Task['status'],
        dueDate: row.due_date,
        plannedDate: row.planned_date,
        scheduledStart: row.scheduled_start,
        scheduledEnd: row.scheduled_end,
        durationMinutes: row.duration_minutes || 30,
        order: row.sort_order || 0,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
    };
}

function queryTasks(db: SqlJsDatabase, sql: string, params: any[] = []): Task[] {
    const result = db.exec(sql, params);
    if (result.length === 0) return [];
    const columns = result[0].columns;
    return result[0].values.map((row) => rowToTask(columns, row));
}

export class TaskService {
    private db: SqlJsDatabase;

    constructor(db: SqlJsDatabase) {
        this.db = db;
    }

    getAll(): Task[] {
        return queryTasks(
            this.db,
            'SELECT * FROM tasks WHERE status != ? ORDER BY sort_order ASC, created_at DESC',
            ['archived'],
        );
    }

    getByDate(date: string): Task[] {
        return queryTasks(
            this.db,
            'SELECT * FROM tasks WHERE planned_date = ? AND status != ? ORDER BY sort_order ASC',
            [date, 'archived'],
        );
    }

    getByStatus(status: string): Task[] {
        return queryTasks(
            this.db,
            'SELECT * FROM tasks WHERE status = ? ORDER BY sort_order ASC',
            [status],
        );
    }

    create(input: Partial<Task>): Task {
        const id = uuid();
        const now = new Date().toISOString();

        const maxResult = this.db.exec('SELECT MAX(sort_order) as max_order FROM tasks');
        const maxOrder = maxResult.length > 0 && maxResult[0].values[0][0] != null
            ? (maxResult[0].values[0][0] as number) : 0;

        this.db.run(
            `INSERT INTO tasks (id, title, notes, project, labels, source, status, due_date, planned_date, scheduled_start, scheduled_end, duration_minutes, sort_order, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
            [
                id,
                input.title || 'Untitled',
                input.notes || '',
                input.project || '',
                JSON.stringify(input.labels || []),
                input.source || 'local',
                input.status || 'inbox',
                input.dueDate || null,
                input.plannedDate || null,
                input.scheduledStart || null,
                input.scheduledEnd || null,
                input.durationMinutes || 30,
                maxOrder + 1,
                now,
                now,
            ],
        );

        saveDatabase();
        return this.getById(id)!;
    }

    getById(id: string): Task | null {
        const tasks = queryTasks(this.db, 'SELECT * FROM tasks WHERE id = ?', [id]);
        return tasks.length > 0 ? tasks[0] : null;
    }

    update(id: string, updates: Partial<Task>): Task | null {
        const now = new Date().toISOString();
        const sets: string[] = [];
        const values: any[] = [];

        if (updates.title !== undefined) { sets.push('title = ?'); values.push(updates.title); }
        if (updates.notes !== undefined) { sets.push('notes = ?'); values.push(updates.notes); }
        if (updates.project !== undefined) { sets.push('project = ?'); values.push(updates.project); }
        if (updates.labels !== undefined) { sets.push('labels = ?'); values.push(JSON.stringify(updates.labels)); }
        if (updates.status !== undefined) { sets.push('status = ?'); values.push(updates.status); }
        if (updates.dueDate !== undefined) { sets.push('due_date = ?'); values.push(updates.dueDate); }
        if (updates.plannedDate !== undefined) { sets.push('planned_date = ?'); values.push(updates.plannedDate); }
        if (updates.scheduledStart !== undefined) { sets.push('scheduled_start = ?'); values.push(updates.scheduledStart); }
        if (updates.scheduledEnd !== undefined) { sets.push('scheduled_end = ?'); values.push(updates.scheduledEnd); }
        if (updates.durationMinutes !== undefined) { sets.push('duration_minutes = ?'); values.push(updates.durationMinutes); }
        if (updates.order !== undefined) { sets.push('sort_order = ?'); values.push(updates.order); }

        if (sets.length === 0) return this.getById(id);

        sets.push('updated_at = ?');
        values.push(now);
        values.push(id);

        this.db.run(`UPDATE tasks SET ${sets.join(', ')} WHERE id = ?`, values);
        saveDatabase();
        return this.getById(id);
    }

    delete(id: string): boolean {
        this.db.run('DELETE FROM tasks WHERE id = ?', [id]);
        saveDatabase();
        return true;
    }

    reorder(items: { id: string; order: number }[]): void {
        const now = new Date().toISOString();
        for (const item of items) {
            this.db.run('UPDATE tasks SET sort_order = ?, updated_at = ? WHERE id = ?', [
                item.order,
                now,
                item.id,
            ]);
        }
        saveDatabase();
    }

    search(query: string): Task[] {
        return queryTasks(
            this.db,
            `SELECT * FROM tasks WHERE (title LIKE ? OR notes LIKE ?) AND status != ? ORDER BY sort_order ASC LIMIT 50`,
            [`%${query}%`, `%${query}%`, 'archived'],
        );
    }
}
