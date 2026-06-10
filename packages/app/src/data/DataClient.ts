import type {
  Task,
  Subtask,
  Project,
  NoteGroup,
  Note,
  FocusSession,
  DailyPlan,
  ReportData,
  RecurrenceRule,
} from '../types';

/** Reorder payload as the store produces it (desired sequential order). */
export interface ReorderInput {
  id: string;
  order: number;
}

/** Recurrence rule input the store passes through createRecurringTask/updateRule. */
export interface RecurrenceRuleInput {
  frequency: 'daily' | 'weekly';
  interval: number;
  daysOfWeek: number[];
  endType: 'never' | 'count' | 'date';
  endCount?: number;
  endDate?: string;
}

/**
 * The data seam the Zustand store talks to. SP2 has exactly one implementation
 * (HttpDataClient over @tmap/api-client). SP3 will wrap it in a CachingDataClient
 * without changing the store. Only methods the store actually calls are present.
 */
export interface DataClient {
  tasks: {
    getAll(): Promise<Task[]>;
    getByDate(date: string): Promise<Task[]>;
    create(t: Partial<Task>): Promise<Task>;
    update(id: string, u: Partial<Task>): Promise<Task>;
    delete(id: string): Promise<void>;
    reorder(items: ReorderInput[]): Promise<void>;
  };
  subtasks: {
    create(taskId: string, title: string): Promise<Subtask>;
    update(
      id: string,
      u: { title?: string; completed?: boolean; order?: number },
    ): Promise<void>;
    delete(id: string): Promise<void>;
  };
  projects: {
    getAll(): Promise<Project[]>;
    create(i: { name: string; color?: string; emoji?: string }): Promise<Project>;
    update(id: string, u: Partial<Project>): Promise<Project>;
    delete(id: string): Promise<void>;
    reorder(items: ReorderInput[]): Promise<void>;
  };
  noteGroups: {
    getAll(): Promise<NoteGroup[]>;
    getByProject(projectId: string): Promise<NoteGroup[]>;
    create(i: { name: string; emoji?: string; projectId?: string }): Promise<NoteGroup>;
    update(id: string, u: Partial<NoteGroup>): Promise<NoteGroup>;
    delete(id: string): Promise<void>;
    reorder(items: ReorderInput[]): Promise<void>;
  };
  notes: {
    getAll(): Promise<Note[]>;
    getByGroup(groupId: string): Promise<Note[]>;
    getByProject(projectId: string): Promise<Note[]>;
    getById(id: string): Promise<Note | null>;
    create(i: {
      groupId?: string;
      projectId?: string;
      title?: string;
      content?: string;
    }): Promise<Note>;
    update(
      id: string,
      u: Partial<{
        title: string;
        content: string;
        groupId: string;
        projectId: string;
        order: number;
      }>,
    ): Promise<Note>;
    delete(id: string): Promise<void>;
    reorder(items: ReorderInput[]): Promise<void>;
  };
  recurrence: {
    create(task: Partial<Task>, rule: RecurrenceRuleInput): Promise<Task[]>;
    updateSeries(ruleId: string, u: Partial<Task>): Promise<void>;
    deleteSeries(ruleId: string): Promise<void>;
    deleteSeriesFuture(ruleId: string, from: string): Promise<void>;
    detachInstance(taskId: string): Promise<void>;
    ensureInstances(start: string, end: string): Promise<Task[]>;
    updateRule(ruleId: string, u: RecurrenceRuleInput): Promise<void>;
    getRule(ruleId: string): Promise<RecurrenceRule | null>;
  };
  focusSessions: {
    add(s: {
      taskId: string | null;
      project: string;
      startedAt: string;
      endedAt: string;
      minutes: number;
      date: string;
    }): Promise<FocusSession>;
  };
  dailyPlans: {
    upsert(p: {
      date: string;
      plannedTaskIds: string[];
      plannedMinutes: number;
    }): Promise<DailyPlan>;
    get(date: string): Promise<DailyPlan | null>;
  };
  reports: {
    getData(start: string, end: string): Promise<ReportData>;
  };
  settings: {
    get(): Promise<{ settings: Record<string, unknown>; timeZoneId: string }>;
    save(s: Record<string, unknown>, timeZoneId?: string): Promise<void>;
  };
}
