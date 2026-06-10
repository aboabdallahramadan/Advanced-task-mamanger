import { ReportData, ThroughputPoint, ProjectTime, ReportSummary } from '../types';

export function throughputByDay(data: ReportData, days: string[]): ThroughputPoint[] {
  return days.map((day) => {
    const completed = data.completedTasks.filter((t) => t.date === day).length;
    const plan = data.dailyPlans.find((p) => p.date === day);
    return {
      date: day,
      completed,
      planned: plan ? plan.plannedTaskIds.length : 0,
      hasPlan: !!plan,
    };
  });
}

export function timeByProject(sessions: { project: string; minutes: number }[]): ProjectTime[] {
  const totals = new Map<string, number>();
  for (const s of sessions) {
    totals.set(s.project, (totals.get(s.project) || 0) + s.minutes); // ReportData ProjectTime.project is a name string
  }
  return Array.from(totals.entries())
    .map(([project, minutes]) => ({ project, minutes }))
    .sort((a, b) => b.minutes - a.minutes);
}

/** done-of-planned across all daily plans in range; only same-day completions count. null if nothing planned. */
export function completionRate(data: ReportData): number | null {
  const doneKeys = new Set(data.completedTasks.map((t) => `${t.id}|${t.date}`));
  let planned = 0;
  let done = 0;
  for (const plan of data.dailyPlans) {
    planned += plan.plannedTaskIds.length;
    done += plan.plannedTaskIds.filter((id) => doneKeys.has(`${id}|${plan.date}`)).length;
  }
  return planned > 0 ? done / planned : null;
}

export function summarize(current: ReportData, previous: ReportData): ReportSummary {
  const byProject = timeByProject(current.sessions);
  const focusMinutes = current.sessions.reduce((s, x) => s + x.minutes, 0);
  const prevFocus = previous.sessions.reduce((s, x) => s + x.minutes, 0);
  const rate = completionRate(current);
  const prevRate = completionRate(previous);
  const top = byProject[0] ?? null;

  return {
    completed: current.completedTasks.length,
    completionRate: rate,
    focusMinutes,
    topProject: top ? top.project || 'No project' : null,
    topProjectMinutes: top ? top.minutes : 0,
    delta: {
      completed: current.completedTasks.length - previous.completedTasks.length,
      completionRate: rate != null && prevRate != null ? rate - prevRate : null,
      focusMinutes: focusMinutes - prevFocus,
    },
  };
}
