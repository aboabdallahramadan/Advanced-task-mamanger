import { Database as SqlJsDatabase } from 'sql.js';
export interface DailyPlanInput {
  date: string;
  plannedTaskIds: string[];
  plannedMinutes: number;
}
export declare class DailyPlanService {
  private db;
  constructor(db: SqlJsDatabase);
  upsert(input: DailyPlanInput): {
    date: string;
    committedAt: string;
    plannedTaskIds: string[];
    plannedMinutes: number;
  };
  get(date: string): {
    date: any;
    committedAt: any;
    plannedTaskIds: any;
    plannedMinutes: any;
  } | null;
}
