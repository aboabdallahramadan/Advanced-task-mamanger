import type { ReportData } from '../../types';
import type {
  TaskSyncRow,
  FocusSessionSyncRow,
  DailyPlanSyncRow,
  ProjectSyncRow,
} from './rows';

export interface ReportRows {
  tasks: TaskSyncRow[];
  focusSessions: FocusSessionSyncRow[];
  dailyPlans: DailyPlanSyncRow[];
  projects: ProjectSyncRow[];
}

export function buildReportData(_rows: ReportRows, _start: string, _end: string): ReportData {
  return { completedTasks: [], sessions: [], dailyPlans: [] };
}
