import React, { useEffect } from 'react';
import { useStore } from '../../store';
import { TaskItem } from '../TaskItem';
import { DayTimeline } from '../DayTimeline';
import { capacityStatus } from '../../lib/capacity';
import { PlanningPhase } from '../../types';
import { format, parseISO } from 'date-fns';
import { clsx } from 'clsx';
import {
  CheckCircle,
  Inbox,
  CalendarDays,
  Flag,
  X,
  ChevronLeft,
  ChevronRight,
  AlertTriangle,
} from 'lucide-react';

const PHASES: { id: PlanningPhase; title: string; icon: React.ReactNode }[] = [
  { id: 'review', title: 'Review', icon: <CheckCircle className="w-4 h-4" /> },
  { id: 'choose', title: 'Choose', icon: <Inbox className="w-4 h-4" /> },
  { id: 'timebox', title: 'Timebox', icon: <CalendarDays className="w-4 h-4" /> },
  { id: 'commit', title: 'Commit', icon: <Flag className="w-4 h-4" /> },
];

const fmtMin = (m: number) => `${Math.floor(m / 60)}h ${m % 60}m`;

export const PlanningCanvas: React.FC = () => {
  const {
    planningFlow,
    closePlanningFlow,
    setPlanningPhase,
    commitDay,
    leftoverTasks,
    inboxTasks,
    backlogTasks,
    plannedForDate,
    workStartHour,
    workEndHour,
    setSelectedDate,
  } = useStore();

  // Sync the timeline to targetDate when the canvas opens so the Timebox column
  // always shows the day being planned, regardless of where the user last navigated.
  // On close, restore the user's previous selectedDate so closing the canvas does not
  // silently move them to the planned day.
  useEffect(() => {
    if (!planningFlow.isOpen) return;
    const prevSelectedDate = useStore.getState().selectedDate;
    setSelectedDate(planningFlow.targetDate);
    return () => {
      setSelectedDate(prevSelectedDate);
    };
  }, [planningFlow.isOpen, planningFlow.targetDate, setSelectedDate]);

  if (!planningFlow.isOpen) return null;

  const { phase, targetDate, commitError } = planningFlow;
  const phaseIndex = PHASES.findIndex((p) => p.id === phase);

  const leftovers = leftoverTasks(targetDate);
  const pool = [...inboxTasks(), ...backlogTasks()];
  const todays = plannedForDate(targetDate);
  const cap = capacityStatus(todays, workStartHour, workEndHour);
  const weekday = format(parseISO(targetDate), 'EEEE');

  const goNext = () => {
    if (phaseIndex < PHASES.length - 1) setPlanningPhase(PHASES[phaseIndex + 1].id);
    else commitDay();
  };
  const goPrev = () => {
    if (phaseIndex > 0) setPlanningPhase(PHASES[phaseIndex - 1].id);
  };

  return (
    <div className="fixed inset-0 z-50 bg-surface-950/90 backdrop-blur-sm flex justify-center pt-12 px-4 pb-6 w-full h-full">
      <div className="w-full max-w-6xl bg-surface-900 border border-surface-800 rounded-xl shadow-2xl flex flex-col h-full max-h-[88vh] animate-scale-in">
        {/* Header + phase bar */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-surface-800 shrink-0">
          <h2 className="text-xl font-semibold text-surface-100">Plan {weekday}</h2>
          <div className="flex gap-2">
            {PHASES.map((p, i) => (
              <button
                key={p.id}
                onClick={() => setPlanningPhase(p.id)}
                className={clsx(
                  'flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-medium transition-all',
                  i === phaseIndex
                    ? 'bg-accent-600 text-white'
                    : i < phaseIndex
                      ? 'bg-accent-900/50 text-accent-300'
                      : 'bg-surface-800/70 text-surface-400 hover:text-surface-200',
                )}
              >
                {p.icon}
                {p.title}
              </button>
            ))}
          </div>
          <button
            onClick={closePlanningFlow}
            className="p-2 -mr-2 text-surface-400 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* 3-column canvas */}
        <div className="flex-1 min-h-0 grid grid-cols-3 gap-px bg-surface-800/40">
          <Column
            title={phase === 'review' ? 'Leftovers' : 'Inbox & Backlog'}
            subtitle={phase === 'review' ? 'From earlier days' : 'Pick what to do today'}
            dim={phase === 'timebox' || phase === 'commit'}
          >
            {phase === 'review' ? (
              leftovers.length === 0 ? (
                <Empty text="Nothing left over 🎉" />
              ) : (
                leftovers.map((t) => <TaskItem key={t.id} task={t} />)
              )
            ) : pool.length === 0 ? (
              <Empty text="Inbox & backlog are empty" />
            ) : (
              pool.map((t) => <TaskItem key={t.id} task={t} />)
            )}
          </Column>

          <Column
            title={`Today · ${weekday}`}
            dim={phase === 'review'}
            header={<CapacityMeter planned={cap.planned} capacity={cap.capacity} over={cap.over} />}
          >
            {todays.length === 0 ? (
              <Empty text={'No tasks yet. Use a task\'s "Plan for today" action.'} />
            ) : (
              todays.map((t) => <TaskItem key={t.id} task={t} />)
            )}
          </Column>

          <div
            className={clsx(
              'bg-surface-950 flex flex-col min-h-0',
              phase !== 'timebox' && phase !== 'commit' && 'opacity-60',
            )}
          >
            <DayTimeline />
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between px-6 py-4 border-t border-surface-800 shrink-0">
          <button
            onClick={goPrev}
            disabled={phaseIndex === 0}
            className={clsx(
              'flex items-center gap-2 px-4 py-2 rounded-lg font-medium transition-all',
              phaseIndex === 0
                ? 'opacity-0 pointer-events-none'
                : 'text-surface-300 hover:text-surface-100 hover:bg-surface-800',
            )}
          >
            <ChevronLeft className="w-4 h-4" /> Back
          </button>

          {phase === 'commit' ? (
            <div className="flex flex-col items-end gap-2">
              <div className="flex items-center gap-4">
                <span className="text-sm text-surface-400">
                  {todays.length} task{todays.length === 1 ? '' : 's'} · {fmtMin(cap.planned)} of{' '}
                  {fmtMin(cap.capacity)}
                </span>
                {cap.over && (
                  <span className="flex items-center gap-1 text-xs text-warning-400">
                    <AlertTriangle className="w-3.5 h-3.5" /> Over capacity
                  </span>
                )}
                <button
                  onClick={commitDay}
                  className="flex items-center gap-2 px-5 py-2.5 bg-accent-600 hover:bg-accent-500 text-white rounded-lg font-medium transition-colors shadow-lg shadow-accent-500/20"
                >
                  Commit day ✓
                </button>
              </div>
              {commitError && (
                <p className="text-xs text-danger-400 max-w-sm text-right">{commitError}</p>
              )}
            </div>
          ) : (
            <button
              onClick={goNext}
              className="flex items-center gap-2 px-5 py-2.5 bg-accent-600 hover:bg-accent-500 text-white rounded-lg font-medium transition-colors shadow-lg shadow-accent-500/20"
            >
              Next: {PHASES[phaseIndex + 1].title} <ChevronRight className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

function Column({
  title,
  subtitle,
  header,
  dim,
  children,
}: {
  title: string;
  subtitle?: string;
  header?: React.ReactNode;
  dim?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div
      className={clsx(
        'bg-surface-900 flex flex-col min-h-0 transition-opacity',
        dim && 'opacity-60',
      )}
    >
      <div className="px-4 pt-4 pb-2 shrink-0">
        <h3 className="text-sm font-semibold text-surface-200">{title}</h3>
        {subtitle && <p className="text-2xs text-surface-500 mt-0.5">{subtitle}</p>}
        {header}
      </div>
      <div className="flex-1 overflow-y-auto custom-scrollbar px-4 pb-4 space-y-2">{children}</div>
    </div>
  );
}

function CapacityMeter({
  planned,
  capacity,
  over,
}: {
  planned: number;
  capacity: number;
  over: boolean;
}) {
  const pct = capacity > 0 ? Math.min(100, Math.round((planned / capacity) * 100)) : 0;
  return (
    <div className="mt-2">
      <div className="flex justify-between text-2xs mb-1">
        <span className="text-surface-400">{fmtMin(planned)} planned</span>
        <span className={clsx(over ? 'text-warning-400' : 'text-surface-500')}>
          {fmtMin(capacity)} available
        </span>
      </div>
      <div className="h-1.5 rounded-full bg-surface-800 overflow-hidden">
        <div
          className={clsx(
            'h-full rounded-full transition-all',
            over ? 'bg-warning-500' : 'bg-accent-500',
          )}
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return <div className="text-center text-xs text-surface-500 py-8">{text}</div>;
}
