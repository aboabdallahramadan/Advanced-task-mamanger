import { Database as SqlJsDatabase } from 'sql.js';
import { v4 as uuid } from 'uuid';
import { saveDatabase } from './database';

export interface FocusSessionInput {
  taskId: string | null;
  project: string;
  startedAt: string;
  endedAt: string;
  minutes: number;
  date: string;
}

export class FocusSessionService {
  constructor(private db: SqlJsDatabase) {}

  add(input: FocusSessionInput) {
    const id = uuid();
    const now = new Date().toISOString();
    try {
      this.db.run(
        `INSERT INTO focus_sessions (id, task_id, project, started_at, ended_at, minutes, date, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
        [
          id,
          input.taskId,
          input.project,
          input.startedAt,
          input.endedAt,
          input.minutes,
          input.date,
          now,
        ],
      );
      saveDatabase();
    } catch (e) {
      console.error('FocusSessionService.add failed:', e);
      throw e;
    }
    return { id, ...input, createdAt: now };
  }
}
