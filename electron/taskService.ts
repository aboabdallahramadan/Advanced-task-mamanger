import { Database as SqlJsDatabase } from 'sql.js';
import { v4 as uuid } from 'uuid';
import { saveDatabase } from './database';
import { generateOccurrenceDates, RecurrenceRuleData } from './recurrenceUtils';

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
        plannedDate: row.planned_date,
        scheduledStart: row.scheduled_start,
        scheduledEnd: row.scheduled_end,
        durationMinutes: row.duration_minutes || 30,
        actualTimeMinutes: row.actual_time_minutes || 0,
        priority: (row.priority as 1 | 2 | 3 | 4 | null) ?? null,
        reminderMinutes: row.reminder_minutes ?? 0,
        subtasks: [],
        order: row.sort_order || 0,
        recurrenceRuleId: row.recurrence_rule_id || null,
        isRecurrenceTemplate: !!row.is_recurrence_template,
        recurrenceDetached: !!row.recurrence_detached,
        recurrenceOriginalDate: row.recurrence_original_date || null,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
    };
}

function rowToSubtask(columns: string[], values: any[]): Subtask {
    const row: any = {};
    columns.forEach((col, i) => (row[col] = values[i]));
    return {
        id: row.id,
        taskId: row.task_id,
        title: row.title,
        completed: !!row.completed,
        order: row.sort_order || 0,
        createdAt: row.created_at,
    };
}

function queryTasks(db: SqlJsDatabase, sql: string, params: any[] = []): Task[] {
    const result = db.exec(sql, params);
    if (result.length === 0) return [];
    const columns = result[0].columns;
    const tasks = result[0].values.map((row: any[]) => rowToTask(columns, row));

    // Batch-load all subtasks for these tasks
    if (tasks.length > 0) {
        const taskIds = tasks.map((t: Task) => t.id);
        const placeholders = taskIds.map(() => '?').join(',');
        const subResult = db.exec(
            `SELECT * FROM subtasks WHERE task_id IN (${placeholders}) ORDER BY sort_order ASC, created_at ASC`,
            taskIds,
        );
        if (subResult.length > 0) {
            const subColumns = subResult[0].columns;
            const subtasks = subResult[0].values.map((row: any[]) => rowToSubtask(subColumns, row));
            const subtaskMap = new Map<string, Subtask[]>();
            for (const st of subtasks) {
                if (!subtaskMap.has(st.taskId)) subtaskMap.set(st.taskId, []);
                subtaskMap.get(st.taskId)!.push(st);
            }
            for (const task of tasks) {
                task.subtasks = subtaskMap.get(task.id) || [];
            }
        }
    }

    return tasks;
}

export class TaskService {
    private db: SqlJsDatabase;

    constructor(db: SqlJsDatabase) {
        this.db = db;
    }

    getAll(): Task[] {
        return queryTasks(
            this.db,
            'SELECT * FROM tasks WHERE status != ? AND is_recurrence_template = 0 ORDER BY sort_order ASC, created_at DESC',
            ['archived'],
        );
    }

    getByDate(date: string): Task[] {
        return queryTasks(
            this.db,
            'SELECT * FROM tasks WHERE planned_date = ? AND status != ? AND is_recurrence_template = 0 ORDER BY sort_order ASC',
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
            `INSERT INTO tasks (id, title, notes, project, labels, source, status, planned_date, scheduled_start, scheduled_end, duration_minutes, actual_time_minutes, priority, reminder_minutes, sort_order, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
            [
                id,
                input.title || 'Untitled',
                input.notes || '',
                input.project || '',
                JSON.stringify(input.labels || []),
                input.source || 'local',
                input.status || 'inbox',
                input.plannedDate || null,
                input.scheduledStart || null,
                input.scheduledEnd || null,
                input.durationMinutes || 30,
                input.actualTimeMinutes || 0,
                input.priority ?? null,
                input.reminderMinutes ?? 0,
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
        if (updates.plannedDate !== undefined) { sets.push('planned_date = ?'); values.push(updates.plannedDate); }
        if (updates.scheduledStart !== undefined) { sets.push('scheduled_start = ?'); values.push(updates.scheduledStart); }
        if (updates.scheduledEnd !== undefined) { sets.push('scheduled_end = ?'); values.push(updates.scheduledEnd); }
        if (updates.durationMinutes !== undefined) { sets.push('duration_minutes = ?'); values.push(updates.durationMinutes); }
        if (updates.actualTimeMinutes !== undefined) { sets.push('actual_time_minutes = ?'); values.push(updates.actualTimeMinutes); }
        if (updates.priority !== undefined) { sets.push('priority = ?'); values.push(updates.priority); }
        if (updates.reminderMinutes !== undefined) { sets.push('reminder_minutes = ?'); values.push(updates.reminderMinutes); }
        if (updates.order !== undefined) { sets.push('sort_order = ?'); values.push(updates.order); }
        if ((updates as any).recurrenceDetached !== undefined) { sets.push('recurrence_detached = ?'); values.push((updates as any).recurrenceDetached ? 1 : 0); }

        if (sets.length === 0) return this.getById(id);

        sets.push('updated_at = ?');
        values.push(now);
        values.push(id);

        this.db.run(`UPDATE tasks SET ${sets.join(', ')} WHERE id = ?`, values);
        saveDatabase();
        return this.getById(id);
    }

    delete(id: string): boolean {
        // If this is a recurring instance, record an exception so it won't be re-generated
        const task = this.getById(id);
        if (task && task.recurrenceRuleId && !task.isRecurrenceTemplate && task.recurrenceOriginalDate) {
            const excId = uuid();
            this.db.run(
                `INSERT OR IGNORE INTO recurrence_exceptions (id, recurrence_rule_id, exception_date) VALUES (?, ?, ?)`,
                [excId, task.recurrenceRuleId, task.recurrenceOriginalDate],
            );
        }
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

    getUpcomingWithReminders(): Task[] {
        return queryTasks(
            this.db,
            `SELECT * FROM tasks
             WHERE scheduled_start IS NOT NULL
             AND reminder_minutes IS NOT NULL
             AND status NOT IN ('done', 'archived')
             ORDER BY scheduled_start ASC`,
        );
    }

    search(query: string): Task[] {
        return queryTasks(
            this.db,
            `SELECT * FROM tasks WHERE (title LIKE ? OR notes LIKE ?) AND status != ? AND is_recurrence_template = 0 ORDER BY sort_order ASC LIMIT 50`,
            [`%${query}%`, `%${query}%`, 'archived'],
        );
    }

    // ─── Subtask Operations ─────────────────────────────────

    createSubtask(taskId: string, title: string): Subtask {
        const id = uuid();
        const now = new Date().toISOString();

        const maxResult = this.db.exec(
            'SELECT MAX(sort_order) as max_order FROM subtasks WHERE task_id = ?',
            [taskId],
        );
        const maxOrder = maxResult.length > 0 && maxResult[0].values[0][0] != null
            ? (maxResult[0].values[0][0] as number) : 0;

        this.db.run(
            `INSERT INTO subtasks (id, task_id, title, completed, sort_order, created_at) VALUES (?, ?, ?, 0, ?, ?)`,
            [id, taskId, title, maxOrder + 1, now],
        );
        saveDatabase();

        return { id, taskId, title, completed: false, order: maxOrder + 1, createdAt: now };
    }

    updateSubtask(id: string, updates: { title?: string; completed?: boolean; order?: number }): void {
        const sets: string[] = [];
        const values: any[] = [];

        if (updates.title !== undefined) { sets.push('title = ?'); values.push(updates.title); }
        if (updates.completed !== undefined) { sets.push('completed = ?'); values.push(updates.completed ? 1 : 0); }
        if (updates.order !== undefined) { sets.push('sort_order = ?'); values.push(updates.order); }

        if (sets.length === 0) return;

        values.push(id);
        this.db.run(`UPDATE subtasks SET ${sets.join(', ')} WHERE id = ?`, values);
        saveDatabase();
    }

    deleteSubtask(id: string): void {
        this.db.run('DELETE FROM subtasks WHERE id = ?', [id]);
        saveDatabase();
    }

    // ─── Recurrence Operations ────────────────────────────────

    getRecurrenceRule(ruleId: string): RecurrenceRule | null {
        const result = this.db.exec('SELECT * FROM recurrence_rules WHERE id = ?', [ruleId]);
        if (result.length === 0 || result[0].values.length === 0) return null;
        const cols = result[0].columns;
        const row: any = {};
        cols.forEach((col: string, i: number) => (row[col] = result[0].values[0][i]));
        return {
            id: row.id,
            frequency: row.frequency,
            interval: row.interval_value || 1,
            daysOfWeek: JSON.parse(row.days_of_week || '[]'),
            endType: row.end_type || 'never',
            endCount: row.end_count ?? null,
            endDate: row.end_date ?? null,
            generatedUntil: row.generated_until ?? null,
            createdAt: row.created_at,
            updatedAt: row.updated_at,
        };
    }

    createRecurringTask(
        taskInput: Partial<Task>,
        ruleInput: { frequency: 'daily' | 'weekly'; interval: number; daysOfWeek: number[]; endType: 'never' | 'count' | 'date'; endCount?: number; endDate?: string },
    ): Task {
        const now = new Date().toISOString();
        const ruleId = uuid();

        // Create recurrence rule
        this.db.run(
            `INSERT INTO recurrence_rules (id, frequency, interval_value, days_of_week, end_type, end_count, end_date, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
            [
                ruleId,
                ruleInput.frequency,
                ruleInput.interval || 1,
                JSON.stringify(ruleInput.daysOfWeek || []),
                ruleInput.endType || 'never',
                ruleInput.endCount ?? null,
                ruleInput.endDate ?? null,
                now,
                now,
            ],
        );

        // Create template task
        const templateId = uuid();
        const startDate = taskInput.plannedDate || new Date().toISOString().split('T')[0];

        this.db.run(
            `INSERT INTO tasks (id, title, notes, project, labels, source, status, planned_date, scheduled_start, scheduled_end, duration_minutes, actual_time_minutes, priority, reminder_minutes, sort_order, recurrence_rule_id, is_recurrence_template, recurrence_original_date, created_at, updated_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)`,
            [
                templateId,
                taskInput.title || 'Untitled',
                taskInput.notes || '',
                taskInput.project || '',
                JSON.stringify(taskInput.labels || []),
                taskInput.source || 'local',
                taskInput.status || 'planned',
                startDate,
                taskInput.scheduledStart || null,
                taskInput.scheduledEnd || null,
                taskInput.durationMinutes || 30,
                0,
                taskInput.priority ?? null,
                taskInput.reminderMinutes ?? 0,
                0,
                ruleId,
                startDate,
                now,
                now,
            ],
        );

        // Generate instances for the next 14 days
        const today = new Date().toISOString().split('T')[0];
        const twoWeeks = new Date(Date.now() + 14 * 86400000).toISOString().split('T')[0];
        this.generateInstancesForRange(ruleId, today, twoWeeks);

        saveDatabase();
        return this.getById(templateId)!;
    }

    generateInstancesForRange(ruleId: string, startDate: string, endDate: string): Task[] {
        const rule = this.getRecurrenceRule(ruleId);
        if (!rule) return [];

        // Find the template task
        const templateResult = queryTasks(
            this.db,
            'SELECT * FROM tasks WHERE recurrence_rule_id = ? AND is_recurrence_template = 1',
            [ruleId],
        );
        if (templateResult.length === 0) return [];
        const template = templateResult[0];

        // Get existing instance dates
        const existingResult = this.db.exec(
            'SELECT recurrence_original_date FROM tasks WHERE recurrence_rule_id = ? AND is_recurrence_template = 0 AND recurrence_original_date IS NOT NULL',
            [ruleId],
        );
        const existingDates = new Set<string>();
        if (existingResult.length > 0) {
            for (const row of existingResult[0].values) {
                if (row[0]) existingDates.add(row[0] as string);
            }
        }

        // Get exception dates
        const excResult = this.db.exec(
            'SELECT exception_date FROM recurrence_exceptions WHERE recurrence_rule_id = ?',
            [ruleId],
        );
        const exceptionDates = new Set<string>();
        if (excResult.length > 0) {
            for (const row of excResult[0].values) {
                if (row[0]) exceptionDates.add(row[0] as string);
            }
        }

        const ruleData: RecurrenceRuleData = {
            frequency: rule.frequency,
            interval: rule.interval,
            daysOfWeek: rule.daysOfWeek,
            endType: rule.endType,
            endCount: rule.endCount,
            endDate: rule.endDate,
        };

        const templateStart = template.recurrenceOriginalDate || template.plannedDate || startDate;
        const dates = generateOccurrenceDates(ruleData, templateStart, startDate, endDate, existingDates, exceptionDates);

        const now = new Date().toISOString();
        const newTasks: Task[] = [];

        for (const date of dates) {
            const id = uuid();
            const maxResult = this.db.exec('SELECT MAX(sort_order) as max_order FROM tasks');
            const maxOrder = maxResult.length > 0 && maxResult[0].values[0][0] != null
                ? (maxResult[0].values[0][0] as number) : 0;

            this.db.run(
                `INSERT INTO tasks (id, title, notes, project, labels, source, status, planned_date, scheduled_start, scheduled_end, duration_minutes, actual_time_minutes, priority, reminder_minutes, sort_order, recurrence_rule_id, is_recurrence_template, recurrence_detached, recurrence_original_date, created_at, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)`,
                [
                    id,
                    template.title,
                    template.notes,
                    template.project,
                    JSON.stringify(template.labels),
                    template.source,
                    'planned',
                    date,
                    template.scheduledStart || null,
                    template.scheduledEnd || null,
                    template.durationMinutes,
                    0,
                    template.priority,
                    template.reminderMinutes,
                    maxOrder + 1,
                    ruleId,
                    date,
                    now,
                    now,
                ],
            );

            const task = this.getById(id);
            if (task) newTasks.push(task);
        }

        // Update generated_until
        this.db.run(
            'UPDATE recurrence_rules SET generated_until = ?, updated_at = ? WHERE id = ?',
            [endDate, now, ruleId],
        );

        saveDatabase();
        return newTasks;
    }

    ensureInstancesForDateRange(startDate: string, endDate: string): Task[] {
        // Find all rules that need generation extended
        const result = this.db.exec(
            `SELECT id FROM recurrence_rules WHERE generated_until IS NULL OR generated_until < ?`,
            [endDate],
        );
        if (result.length === 0) return [];

        const allNew: Task[] = [];
        for (const row of result[0].values) {
            const ruleId = row[0] as string;
            const newTasks = this.generateInstancesForRange(ruleId, startDate, endDate);
            allNew.push(...newTasks);
        }
        return allNew;
    }

    updateSeries(ruleId: string, updates: Partial<Task>): void {
        const now = new Date().toISOString();
        const today = new Date().toISOString().split('T')[0];

        // Build SET clause for allowed fields
        const sets: string[] = [];
        const values: any[] = [];

        if (updates.title !== undefined) { sets.push('title = ?'); values.push(updates.title); }
        if (updates.notes !== undefined) { sets.push('notes = ?'); values.push(updates.notes); }
        if (updates.project !== undefined) { sets.push('project = ?'); values.push(updates.project); }
        if (updates.durationMinutes !== undefined) { sets.push('duration_minutes = ?'); values.push(updates.durationMinutes); }
        if (updates.priority !== undefined) { sets.push('priority = ?'); values.push(updates.priority); }
        if (updates.reminderMinutes !== undefined) { sets.push('reminder_minutes = ?'); values.push(updates.reminderMinutes); }
        if (updates.scheduledStart !== undefined) { sets.push('scheduled_start = ?'); values.push(updates.scheduledStart); }
        if (updates.scheduledEnd !== undefined) { sets.push('scheduled_end = ?'); values.push(updates.scheduledEnd); }

        if (sets.length === 0) return;

        sets.push('updated_at = ?');
        values.push(now);

        // Update the template
        const templateValues = [...values, ruleId];
        this.db.run(`UPDATE tasks SET ${sets.join(', ')} WHERE recurrence_rule_id = ? AND is_recurrence_template = 1`, templateValues);

        // Update non-detached future instances
        const instanceValues = [...values, ruleId, today];
        this.db.run(`UPDATE tasks SET ${sets.join(', ')} WHERE recurrence_rule_id = ? AND is_recurrence_template = 0 AND recurrence_detached = 0 AND planned_date >= ? AND status != 'done'`, instanceValues);

        saveDatabase();
    }

    deleteSeries(ruleId: string): void {
        // Delete all tasks (template + instances)
        this.db.run('DELETE FROM tasks WHERE recurrence_rule_id = ?', [ruleId]);
        // Delete exceptions
        this.db.run('DELETE FROM recurrence_exceptions WHERE recurrence_rule_id = ?', [ruleId]);
        // Delete rule
        this.db.run('DELETE FROM recurrence_rules WHERE id = ?', [ruleId]);
        saveDatabase();
    }

    deleteSeriesFuture(ruleId: string, fromDate: string): void {
        const now = new Date().toISOString();
        // Delete future instances (not done)
        this.db.run(
            `DELETE FROM tasks WHERE recurrence_rule_id = ? AND is_recurrence_template = 0 AND planned_date >= ? AND status != 'done'`,
            [ruleId, fromDate],
        );
        // Cap the rule end date
        const dayBefore = new Date(new Date(fromDate + 'T00:00:00').getTime() - 86400000).toISOString().split('T')[0];
        this.db.run(
            'UPDATE recurrence_rules SET end_type = ?, end_date = ?, updated_at = ? WHERE id = ?',
            ['date', dayBefore, now, ruleId],
        );
        saveDatabase();
    }

    detachInstance(taskId: string): void {
        const now = new Date().toISOString();
        this.db.run('UPDATE tasks SET recurrence_detached = 1, updated_at = ? WHERE id = ?', [now, taskId]);
        saveDatabase();
    }

    updateRecurrenceRule(
        ruleId: string,
        ruleUpdates: { frequency?: 'daily' | 'weekly'; interval?: number; daysOfWeek?: number[]; endType?: 'never' | 'count' | 'date'; endCount?: number; endDate?: string },
    ): void {
        const now = new Date().toISOString();
        const today = new Date().toISOString().split('T')[0];

        const sets: string[] = [];
        const values: any[] = [];

        if (ruleUpdates.frequency !== undefined) { sets.push('frequency = ?'); values.push(ruleUpdates.frequency); }
        if (ruleUpdates.interval !== undefined) { sets.push('interval_value = ?'); values.push(ruleUpdates.interval); }
        if (ruleUpdates.daysOfWeek !== undefined) { sets.push('days_of_week = ?'); values.push(JSON.stringify(ruleUpdates.daysOfWeek)); }
        if (ruleUpdates.endType !== undefined) { sets.push('end_type = ?'); values.push(ruleUpdates.endType); }
        if (ruleUpdates.endCount !== undefined) { sets.push('end_count = ?'); values.push(ruleUpdates.endCount); }
        if (ruleUpdates.endDate !== undefined) { sets.push('end_date = ?'); values.push(ruleUpdates.endDate); }

        if (sets.length === 0) return;

        // Reset generated_until to force re-generation
        sets.push('generated_until = NULL');
        sets.push('updated_at = ?');
        values.push(now);
        values.push(ruleId);

        this.db.run(`UPDATE recurrence_rules SET ${sets.join(', ')} WHERE id = ?`, values);

        // Delete future non-detached, non-done instances
        this.db.run(
            `DELETE FROM tasks WHERE recurrence_rule_id = ? AND is_recurrence_template = 0 AND recurrence_detached = 0 AND planned_date >= ? AND status != 'done'`,
            [ruleId, today],
        );

        // Re-generate
        const twoWeeks = new Date(Date.now() + 14 * 86400000).toISOString().split('T')[0];
        this.generateInstancesForRange(ruleId, today, twoWeeks);

        saveDatabase();
    }
}
