import { useEffect } from 'react';
import { useStore } from '../store';
import { ReportRangeMode } from '../types';
import { BarChart3, TrendingUp, TrendingDown } from 'lucide-react';
import { clsx } from 'clsx';
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ReferenceLine,
  Cell,
} from 'recharts';
import { format, parseISO } from 'date-fns';

const RANGES: { id: ReportRangeMode; label: string }[] = [
  { id: 'day', label: 'Day' },
  { id: 'week', label: 'Week' },
  { id: 'month', label: 'Month' },
  { id: 'year', label: 'Year' },
];

const COLORS = ['#3b82f6', '#f59e0b', '#22c55e', '#a855f7', '#ef4444', '#14b8a6', '#eab308'];

const fmtH = (m: number) =>
  m >= 60 ? `${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ''}` : `${m}m`;

function xAxisDateFormat(range: ReportRangeMode): string {
  if (range === 'year') return 'MMM';
  if (range === 'month') return 'd';
  return 'EEE'; // 'week' and 'day'
}

export function ReportsView() {
  const { reportRange, reportData, reportLoading, setReportRange, loadReports } = useStore();
  const allProjects = useStore((s) => s.projects);

  useEffect(() => {
    loadReports();
  }, [loadReports]);

  const summary = reportData?.summary ?? null;
  const throughput = reportData?.throughput ?? [];
  const projects = reportData?.timeByProject ?? [];

  const isYear = reportRange === 'year';
  const today = format(new Date(), 'yyyy-MM-dd');

  // For the 'year' range, bucket the ~365 daily points into ~12 monthly bars
  // (sum completed per calendar month, labelled 'MMM'). Other ranges stay daily.
  const dateFmt = xAxisDateFormat(reportRange);
  const throughputData = isYear
    ? (() => {
        const byMonth = new Map<string, number>();
        for (const p of throughput) {
          const key = p.date.slice(0, 7); // YYYY-MM
          byMonth.set(key, (byMonth.get(key) || 0) + p.completed);
        }
        return Array.from(byMonth.entries())
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([month, completed]) => ({
            date: month,
            completed,
            label: format(parseISO(`${month}-01`), 'MMM'),
          }));
      })()
    : throughput.map((p) => ({
        ...p,
        label: format(parseISO(p.date), dateFmt),
      }));

  // Map each project's color from the real project list (matched by name); fall back to
  // the positional palette for unmatched rows (including the "No project" bucket).
  const projectColorByName = new Map(allProjects.map((p) => [p.name, p.color]));
  const projectData = projects.map((p, i) => ({
    name: p.project || 'No project',
    minutes: p.minutes,
    color: projectColorByName.get(p.project) ?? COLORS[i % COLORS.length],
  }));
  const avg = throughput.length
    ? throughput.reduce((s, p) => s + p.completed, 0) / throughput.length
    : 0;
  const totalProjMinutes = projects.reduce((s, p) => s + p.minutes, 0);

  return (
    <div className="flex-1 flex flex-col h-full bg-surface-950">
      <div className="px-6 pt-10 pb-4 border-b border-surface-800/40 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <BarChart3 className="w-5 h-5 text-accent-400" />
          <h1 className="text-lg font-bold text-surface-100">Reports</h1>
        </div>
        <div className="flex gap-1">
          {RANGES.map((r) => (
            <button
              key={r.id}
              onClick={() => setReportRange(r.id)}
              className={clsx(
                'px-3 py-1.5 rounded-lg text-xs font-medium transition-all',
                reportRange === r.id
                  ? 'bg-accent-600 text-white'
                  : 'bg-surface-800/60 text-surface-400 hover:text-surface-200',
              )}
            >
              {r.label}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-6">
        {reportLoading && <p className="text-sm text-surface-500">Loading…</p>}

        {summary && (
          <>
            <div className="grid grid-cols-4 gap-4">
              <StatCard
                label="Completed"
                value={String(summary.completed)}
                delta={summary.delta.completed}
              />
              <StatCard
                label="Completion rate"
                value={
                  summary.completionRate == null
                    ? '—'
                    : `${Math.round(summary.completionRate * 100)}%`
                }
                delta={
                  summary.delta.completionRate == null
                    ? null
                    : Math.round(summary.delta.completionRate * 100)
                }
                deltaSuffix="%"
              />
              <StatCard
                label="Focus time"
                value={fmtH(summary.focusMinutes)}
                delta={summary.delta.focusMinutes}
                deltaFormat={fmtH}
              />
              <StatCard
                label="Top project"
                value={summary.topProject || '—'}
                sub={summary.topProject ? fmtH(summary.topProjectMinutes) : ''}
              />
            </div>

            <div className="grid grid-cols-[1.5fr_1fr] gap-4">
              <Panel title="Tasks completed per day" subtitle={`avg ${avg.toFixed(1)}/day`}>
                <ResponsiveContainer width="100%" height={220}>
                  <BarChart
                    data={throughputData}
                    margin={{ top: 8, right: 8, bottom: 0, left: -16 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
                    <XAxis
                      dataKey="label"
                      stroke="#94a3b8"
                      fontSize={11}
                      tickLine={false}
                      axisLine={false}
                    />
                    <YAxis
                      stroke="#94a3b8"
                      fontSize={11}
                      tickLine={false}
                      axisLine={false}
                      allowDecimals={false}
                    />
                    <Tooltip
                      cursor={{ fill: '#1e293b55' }}
                      contentStyle={{
                        background: '#0f172a',
                        border: '1px solid #334155',
                        borderRadius: 8,
                        fontSize: 12,
                      }}
                    />
                    {avg > 0 && <ReferenceLine y={avg} stroke="#475569" strokeDasharray="4 4" />}
                    <Bar dataKey="completed" fill="#3b82f6" radius={[4, 4, 0, 0]}>
                      {throughputData.map((d) => (
                        <Cell
                          key={d.date}
                          fill={!isYear && d.date === today ? '#22c55e' : '#3b82f6'}
                        />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </Panel>

              <Panel title="Time by project" subtitle={fmtH(totalProjMinutes)}>
                {projectData.length === 0 ? (
                  <p className="text-xs text-surface-500 py-8 text-center">
                    No tracked time yet. Use Focus mode to log time.
                  </p>
                ) : (
                  <ResponsiveContainer width="100%" height={220}>
                    <BarChart
                      data={projectData}
                      layout="vertical"
                      margin={{ top: 4, right: 16, bottom: 0, left: 8 }}
                    >
                      <XAxis
                        type="number"
                        stroke="#94a3b8"
                        fontSize={11}
                        tickLine={false}
                        axisLine={false}
                        tickFormatter={(v: number) => `${Math.round(v / 60)}h`}
                      />
                      <YAxis
                        type="category"
                        dataKey="name"
                        stroke="#94a3b8"
                        fontSize={11}
                        width={90}
                        tickLine={false}
                        axisLine={false}
                      />
                      <Tooltip
                        cursor={{ fill: '#1e293b55' }}
                        contentStyle={{
                          background: '#0f172a',
                          border: '1px solid #334155',
                          borderRadius: 8,
                          fontSize: 12,
                        }}
                        formatter={(v) => (typeof v === 'number' ? fmtH(v) : v)}
                      />
                      <Bar dataKey="minutes" radius={[0, 4, 4, 0]}>
                        {projectData.map((d, i) => (
                          <Cell key={i} fill={d.color} />
                        ))}
                      </Bar>
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </Panel>
            </div>

            {!reportLoading && summary.completed === 0 && projects.length === 0 && (
              <p className="text-sm text-surface-500 text-center py-8">
                No activity in this range yet. Complete tasks and track focus time to see reports
                build up.
              </p>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function StatCard({
  label,
  value,
  sub,
  delta,
  deltaSuffix,
  deltaFormat,
}: {
  label: string;
  value: string;
  sub?: string;
  delta?: number | null;
  deltaSuffix?: string;
  deltaFormat?: (n: number) => string;
}) {
  const showDelta = delta !== undefined && delta !== null && delta !== 0;
  const up = (delta ?? 0) > 0;
  return (
    <div className="bg-surface-900 border border-surface-800/60 rounded-xl p-4">
      <div className="text-2xs uppercase tracking-wide text-surface-500">{label}</div>
      <div className="text-2xl font-bold text-surface-100 mt-1 truncate">{value}</div>
      {sub && <div className="text-2xs text-surface-500 mt-0.5">{sub}</div>}
      {showDelta && (
        <div
          className={clsx(
            'text-2xs mt-1 flex items-center gap-1',
            up ? 'text-success-400' : 'text-danger-400',
          )}
        >
          {up ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
          {up ? '+' : '−'}
          {deltaFormat ? deltaFormat(Math.abs(delta!)) : Math.abs(delta!)}
          {deltaSuffix || ''} vs prev
        </div>
      )}
    </div>
  );
}

function Panel({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="bg-surface-900 border border-surface-800/60 rounded-xl p-4">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-medium text-surface-200">{title}</h3>
        {subtitle && <span className="text-2xs text-surface-500">{subtitle}</span>}
      </div>
      {children}
    </div>
  );
}
