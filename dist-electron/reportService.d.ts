import { Database as SqlJsDatabase } from 'sql.js';
export declare class ReportService {
  private db;
  constructor(db: SqlJsDatabase);
  private query;
  getData(
    start: string,
    end: string,
  ): {
    completedTasks: {
      id: any;
      project: any;
      date: any;
    }[];
    sessions: {
      project: any;
      minutes: any;
      date: any;
    }[];
    dailyPlans: {
      date: any;
      committedAt: any;
      plannedTaskIds: any;
      plannedMinutes: any;
    }[];
  };
}
