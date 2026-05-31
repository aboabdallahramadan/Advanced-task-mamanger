import { Database as SqlJsDatabase } from 'sql.js';
export interface FocusSessionInput {
  taskId: string | null;
  project: string;
  startedAt: string;
  endedAt: string;
  minutes: number;
  date: string;
}
export declare class FocusSessionService {
  private db;
  constructor(db: SqlJsDatabase);
  add(input: FocusSessionInput): {
    createdAt: string;
    taskId: string | null;
    project: string;
    startedAt: string;
    endedAt: string;
    minutes: number;
    date: string;
    id: string;
  };
}
