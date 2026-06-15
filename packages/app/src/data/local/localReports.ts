/**
 * localReports.ts — the offline ReportData projection (spec §6, contract C4).
 *
 * Mirrors the server Reports slice (ReportsEndpoints.GetData): completedTasks from
 * task rows with a non-null completedAt, day-bucketed into the DEVICE's LOCAL timezone
 * (known v1 divergence from the server's account timeZoneId, spec §6), filtered to the
 * [start, end] inclusive range, project name resolved from local projects ('' when none);
 * sessions + dailyPlans filtered by their already-local `date`. The output shape matches
 * types.ts ReportData, so lib/reports.ts aggregates it unchanged.
 */

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

/** YYYY-MM-DD of an ISO timestamp in the device's LOCAL timezone. */
function localDay(iso: string): string {
  const d = new Date(iso);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

export function buildReportData(rows: ReportRows, start: string, end: string): ReportData {
  const projectName = new Map(rows.projects.map((p) => [p.id, p.name]));

  const completedTasks = rows.tasks
    .filter((t) => t.completedAt != null)
    .map((t) => ({
      id: t.id,
      project: t.projectId != null ? (projectName.get(t.projectId) ?? '') : '',
      date: localDay(t.completedAt as string),
    }))
    .filter((t) => t.date >= start && t.date <= end);

  const sessions = rows.focusSessions
    .filter((f) => f.date >= start && f.date <= end)
    .map((f) => ({ project: f.project ?? '', minutes: Number(f.minutes), date: f.date }));

  const dailyPlans = rows.dailyPlans
    .filter((p) => p.date >= start && p.date <= end)
    .map((p) => ({
      date: p.date,
      committedAt: p.committedAt,
      plannedTaskIds: p.plannedTaskIds,
      plannedMinutes: Number(p.plannedMinutes),
    }));

  return { completedTasks, sessions, dailyPlans };
}
