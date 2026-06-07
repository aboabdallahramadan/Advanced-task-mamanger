import { Database as SqlJsDatabase } from 'sql.js';
import { saveDatabase } from './database';

export interface DailyPlanInput {
  date: string;
  plannedTaskIds: string[];
  plannedMinutes: number;
}

export class DailyPlanService {
  constructor(private db: SqlJsDatabase) {}

  upsert(input: DailyPlanInput) {
    const now = new Date().toISOString();
    this.db.run(
      `INSERT INTO daily_plans (date, committed_at, planned_task_ids, planned_minutes)
             VALUES (?, ?, ?, ?)
             ON CONFLICT(date) DO UPDATE SET
               committed_at = excluded.committed_at,
               planned_task_ids = excluded.planned_task_ids,
               planned_minutes = excluded.planned_minutes`,
      [input.date, now, JSON.stringify(input.plannedTaskIds), input.plannedMinutes],
    );
    saveDatabase();
    return {
      date: input.date,
      committedAt: now,
      plannedTaskIds: input.plannedTaskIds,
      plannedMinutes: input.plannedMinutes,
    };
  }

  get(date: string) {
    const stmt = this.db.prepare('SELECT * FROM daily_plans WHERE date = ?');
    stmt.bind([date]);
    let row: any = null;
    if (stmt.step()) row = stmt.getAsObject();
    stmt.free();
    if (!row) return null;
    return {
      date: row.date,
      committedAt: row.committed_at,
      plannedTaskIds: JSON.parse(row.planned_task_ids || '[]'),
      plannedMinutes: row.planned_minutes || 0,
    };
  }
}
