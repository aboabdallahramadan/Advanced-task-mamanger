export interface Subtask {
  id: string;
  taskId: string;
  title: string;
  completed: boolean;
  order: number;
  createdAt: string;
}

export type RecurrenceFrequency = 'daily' | 'weekly';
export type RecurrenceEndType = 'never' | 'count' | 'date';

export interface RecurrenceRule {
  id: string;
  frequency: RecurrenceFrequency;
  interval: number;
  daysOfWeek: number[];
  endType: RecurrenceEndType;
  endCount: number | null;
  endDate: string | null;
  generatedUntil: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface Task {
  id: string;
  title: string;
  notes: string;
  projectId: string | null;
  labels: string[];
  source: string;
  status: 'inbox' | 'backlog' | 'planned' | 'scheduled' | 'done' | 'archived';
  plannedDate: string | null;
  scheduledStart: string | null;
  scheduledEnd: string | null;
  durationMinutes: number;
  actualTimeMinutes?: number;
  priority: 1 | 2 | 3 | 4 | null;
  reminderMinutes: number | null;
  subtasks: Subtask[];
  order: number;
  recurrenceRuleId: string | null;
  isRecurrenceTemplate: boolean;
  recurrenceDetached: boolean;
  recurrenceOriginalDate: string | null;
  createdAt: string;
  updatedAt: string;
  changeSeq: number;
  completedAt?: string | null;
}

export type TaskStatus = Task['status'];

export type ViewMode =
  | 'today'
  | 'tomorrow'
  | 'week'
  | 'inbox'
  | 'backlog'
  | 'board'
  | 'project'
  | 'all'
  | 'noteGroup'
  | 'noteEditor'
  | 'allNotes'
  | 'reports';

export type PlanningPhase = 'review' | 'choose' | 'timebox' | 'commit';

export interface FocusSession {
  id: string;
  taskId: string | null;
  project: string; // project NAME at session time ('' if none)
  startedAt: string; // ISO
  endedAt: string; // ISO
  minutes: number;
  date: string; // YYYY-MM-DD (local day of startedAt)
  createdAt: string;
}

export interface DailyPlan {
  date: string; // YYYY-MM-DD (primary key)
  committedAt: string; // ISO
  plannedTaskIds: string[];
  plannedMinutes: number;
}

export type ReportRangeMode = 'day' | 'week' | 'month' | 'year';

export interface ThroughputPoint {
  date: string; // YYYY-MM-DD
  completed: number; // tasks completed that day
  planned: number; // committed tasks for that day (0 if no plan)
  hasPlan: boolean;
}

export interface ProjectTime {
  project: string; // '' => "No project"
  minutes: number;
}

export interface ReportSummary {
  completed: number;
  completionRate: number | null; // 0..1, null if no planned tasks in range
  focusMinutes: number;
  topProject: string | null;
  topProjectMinutes: number;
  delta: {
    completed: number; // current - previous
    completionRate: number | null;
    focusMinutes: number;
  };
}

// Raw rows returned by reports:getData, aggregated in the renderer
export interface ReportData {
  completedTasks: { id: string; project: string; date: string }[];
  sessions: { project: string; minutes: number; date: string }[];
  dailyPlans: DailyPlan[];
}

export interface Project {
  id: string;
  name: string;
  color: string;
  emoji: string;
  order: number;
  actualTimeMinutes?: number;
  createdAt: string;
  updatedAt: string;
}

export interface NoteGroup {
  id: string;
  name: string;
  emoji: string;
  projectId: string | null;
  order: number;
  createdAt: string;
  updatedAt: string;
}

export interface Note {
  id: string;
  groupId: string | null;
  projectId: string | null;
  title: string;
  content: string;
  order: number;
  createdAt: string;
  updatedAt: string;
}

export interface TimeSlot {
  hour: number;
  minute: number;
  label: string;
}
